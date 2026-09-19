package plugin.test.com.bodyGuard.storage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.java.JavaPlugin;

import plugin.test.com.bodyGuard.guard.GuardData;
import plugin.test.com.bodyGuard.guard.GuardMode;
import plugin.test.com.bodyGuard.guard.SavedPosition;

/** YAML persistence for the registry that complements Entity PDC data. */
public final class GuardStorage {

    private static final int CURRENT_VERSION = 6;
    private static final String ROLE_ID_PATTERN = "[a-z0-9_-]{1,32}";

    private final JavaPlugin plugin;
    private final SafeYamlFile safeFile;
    private final SafeYamlFile quarantineFile;

    public GuardStorage(JavaPlugin plugin) {
        this.plugin = plugin;
        this.safeFile = new SafeYamlFile(plugin, "guards.yml", "guards");
        this.quarantineFile = new SafeYamlFile(plugin, "quarantine.yml", "entries");
    }

    public Map<UUID, GuardData> load() {
        Map<UUID, GuardData> result = new LinkedHashMap<>();
        YamlConfiguration configuration = safeFile.load();
        int version = configuration.getInt("version", 0);
        if (version > CURRENT_VERSION) {
            throw new IllegalStateException("guards.yml は未対応の新しい形式です (version="
                    + version + ")。古いプラグインで上書きしません。");
        }

        ConfigurationSection guards = configuration.getConfigurationSection("guards");
        if (guards == null) return result;

        YamlConfiguration quarantine = null;
        for (String idText : guards.getKeys(false)) {
            try {
                UUID guardId = UUID.fromString(idText);
                UUID ownerId = UUID.fromString(requiredString(guards, idText + ".owner"));
                EntityType mobType = EntityType.valueOf(requiredString(guards, idText + ".mob-type")
                        .toUpperCase(java.util.Locale.ROOT));
                GuardMode mode = GuardMode.fromString(guards.getString(idText + ".mode", "follow"));
                if (mode == null) throw new IllegalArgumentException("modeが不正です");

                GuardData data = new GuardData(guardId, ownerId, mobType, mode,
                        guards.getString(idText + ".name", "BodyGuard"),
                        guards.getString(idText + ".owner-name", "Player"),
                        null, null, Math.max(0, guards.getInt(idText + ".name-number", 0)));
                data.setSavedPositions(SavedPosition.read(guards, idText + ".anchor-location"),
                        SavedPosition.read(guards, idText + ".last-location"));
                readState(configuration, guards, idText, data);
                result.put(guardId, data);
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("護衛データを隔離します: " + idText + " - "
                        + exception.getMessage());
                quarantine = quarantine(quarantine, guards, idText,
                        exception.getClass().getSimpleName() + ": "
                                + (exception.getMessage() == null ? "詳細なし" : exception.getMessage()));
            }
        }

        if (quarantine != null && quarantineFile.saveWithResult(quarantine)
                != SafeYamlFile.SaveResult.SUCCESS) {
            throw new IllegalStateException("不正な護衛データをquarantine.ymlへ保全できません。"
                    + "元データを守るため読み込みを中止します。");
        }
        return result;
    }

    private void readState(YamlConfiguration configuration, ConfigurationSection guards,
                           String idText, GuardData data) {
        String path = idText;
        String contractText = configuration.getString("guards." + path + ".contract-status");
        if (contractText != null && !contractText.isBlank()) {
            try {
                data.setContractStatus(GuardData.ContractStatus.valueOf(
                        contractText.trim().toUpperCase(java.util.Locale.ROOT)));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("contract-statusが不正です");
            }
        } else {
            boolean releasePending = strictBoolean(guards, path + ".release-pending", false);
            boolean deletionPending = strictBoolean(guards, path + ".deletion-pending", false);
            boolean deathConfirmed = strictBoolean(guards, path + ".death-confirmed", false);
            if ((releasePending ? 1 : 0) + (deletionPending ? 1 : 0)
                    + (deathConfirmed ? 1 : 0) > 1) {
                throw new IllegalArgumentException("旧pending/deathフラグが矛盾しています");
            }
            if (deathConfirmed) data.setContractStatus(GuardData.ContractStatus.DEAD);
            else if (deletionPending) data.setContractStatus(GuardData.ContractStatus.DELETE_PENDING);
            else if (releasePending) data.setContractStatus(GuardData.ContractStatus.RELEASE_PENDING);
            boolean completed = strictBoolean(guards, path + ".operation-completed", false);
            if (completed) data.setOperationCompleted(true);
        }

        String observationText = configuration.getString("guards." + path + ".observation-status");
        if (observationText != null && !observationText.isBlank()) {
            try {
                data.setObservationStatus(GuardData.ObservationStatus.valueOf(
                        observationText.trim().toUpperCase(java.util.Locale.ROOT)));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("observation-statusが不正です");
            }
        } else {
            data.markChecking();
        }

        String operationId = configuration.getString("guards." + path + ".operation-id");
        UUID parsedOperationId = operationId == null || operationId.isBlank()
                ? null : UUID.fromString(operationId);
        String operationType = configuration.getString("guards." + path + ".operation-type");
        GuardData.OperationType parsedOperationType = operationType == null || operationType.isBlank()
                ? null : GuardData.OperationType.valueOf(
                        operationType.trim().toUpperCase(java.util.Locale.ROOT));
        data.setOperationData(parsedOperationId, parsedOperationType,
                nonNegativeLong(configuration, "guards." + path + ".operation-accepted-at"),
                nonNegativeLong(configuration, "guards." + path + ".operation-completed-at"),
                configuration.getString("guards." + path + ".operation-last-failure"));
        validateOperationState(data, parsedOperationId, parsedOperationType,
                data.getOperationAcceptedAt(), data.getOperationCompletedAt());
        data.setContractGeneration(Math.max(1L,
                nonNegativeLong(configuration, "guards." + path + ".contract-generation")));
        data.setSaveRevision(nonNegativeLong(configuration, "guards." + path + ".save-revision"));
        data.setFavorite(strictBoolean(guards, path + ".favorite", false));
        data.setLastSeen(nonNegativeLong(configuration, "guards." + path + ".last-seen"));
        data.setMissingSince(nonNegativeLong(configuration, "guards." + path + ".missing-since"));
        data.setMissingObservations(Math.max(0, configuration.getInt(
                "guards." + path + ".missing-observations", 0)));

        readProtection(configuration, guards, path, data);

        if (data.getContractStatus() == GuardData.ContractStatus.RELEASE_PENDING
                && (parsedOperationType == null || parsedOperationType != GuardData.OperationType.RELEASE)) {
            throw new IllegalArgumentException("解除待ちなのに操作情報がありません");
        }
        if (data.getContractStatus() == GuardData.ContractStatus.DELETE_PENDING
                && (parsedOperationType == null || parsedOperationType != GuardData.OperationType.DELETE)) {
            throw new IllegalArgumentException("削除待ちなのに操作情報がありません");
        }
    }

    private void readProtection(YamlConfiguration configuration, ConfigurationSection guards,
                                String path, GuardData data) {
        String kindText = guards.getString(path + ".protection-kind", "OWNER");
        GuardData.ProtectionKind kind;
        try {
            kind = GuardData.ProtectionKind.valueOf(kindText.trim().toUpperCase(
                    java.util.Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("protection-kindが不正です");
        }

        String roleId = guards.getString(path + ".role-id");
        if (kind == GuardData.ProtectionKind.ROLE) {
            if (roleId == null || !roleId.matches(ROLE_ID_PATTERN)) {
                throw new IllegalArgumentException("role-idが不正です");
            }
        } else {
            roleId = null;
        }

        String selectedText = guards.getString(path + ".selected-target-uuid");
        UUID selectedTarget = null;
        if (selectedText != null && !selectedText.isBlank()) {
            try {
                selectedTarget = UUID.fromString(selectedText);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("selected-target-uuidが不正です");
            }
        }
        if (kind == GuardData.ProtectionKind.OWNER && selectedTarget != null) {
            throw new IllegalArgumentException("OWNER保護にselected-target-uuidがあります");
        }

        long selectionRevision = nonNegativeLong(configuration,
                "guards." + path + ".selection-revision");
        String stateText = guards.getString(path + ".protection-state");
        GuardData.ProtectionState state = kind == GuardData.ProtectionKind.OWNER
                ? GuardData.ProtectionState.ACTIVE : GuardData.ProtectionState.TARGET_UNAVAILABLE;
        if (stateText != null && !stateText.isBlank()) {
            try {
                state = GuardData.ProtectionState.valueOf(
                        stateText.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("protection-stateが不正です");
            }
        }
        data.restoreProtection(new GuardData.ProtectionSnapshot(kind, roleId, selectedTarget,
                selectionRevision, state,
                guards.getString(path + ".protection-failure-reason")));
    }

    private void validateOperationState(GuardData data, UUID operationId,
                                        GuardData.OperationType operationType,
                                        long acceptedAt, long completedAt) {
        if ((operationId == null) != (operationType == null)) {
            throw new IllegalArgumentException("操作IDと操作種類が片方だけ設定されています");
        }
        if (operationId == null) {
            if (acceptedAt != 0L || (completedAt != 0L && data.isActiveContract())) {
                throw new IllegalArgumentException("操作情報がないのに時刻があります");
            }
            return;
        }
        if (acceptedAt < 1L || completedAt < 0L || completedAt > 0L && completedAt < acceptedAt) {
            throw new IllegalArgumentException("操作時刻が不正です");
        }
        switch (data.getContractStatus()) {
            case ACTIVE -> throw new IllegalArgumentException("操作中なのに契約状態がACTIVEです");
            case RELEASE_PENDING -> {
                if (operationType != GuardData.OperationType.RELEASE || completedAt != 0L) {
                    throw new IllegalArgumentException("解除待ちの操作情報が不正です");
                }
            }
            case DELETE_PENDING -> {
                if (operationType != GuardData.OperationType.DELETE || completedAt != 0L) {
                    throw new IllegalArgumentException("削除待ちの操作情報が不正です");
                }
            }
            case RELEASED -> {
                if (operationType != GuardData.OperationType.RELEASE || completedAt == 0L) {
                    throw new IllegalArgumentException("解除済みの操作情報が不正です");
                }
            }
            case DELETED -> {
                if (operationType != GuardData.OperationType.DELETE || completedAt == 0L) {
                    throw new IllegalArgumentException("削除済みの操作情報が不正です");
                }
            }
            case DEAD -> {
                if (operationType != GuardData.OperationType.DEATH) {
                    throw new IllegalArgumentException("死亡済みの操作情報が不正です");
                }
            }
        }
    }

    private boolean strictBoolean(ConfigurationSection section, String path, boolean fallback) {
        if (!section.contains(path)) return fallback;
        Object raw = section.get(path);
        if (!(raw instanceof Boolean value)) throw new IllegalArgumentException(path + " はbooleanではありません");
        return value;
    }

    private long nonNegativeLong(YamlConfiguration configuration, String path) {
        if (!configuration.contains(path)) return 0L;
        Object raw = configuration.get(path);
        if (!(raw instanceof Number number) || number.longValue() < 0
                || number.doubleValue() != number.longValue()) {
            throw new IllegalArgumentException(path + " は非負整数ではありません");
        }
        return number.longValue();
    }

    private String requiredString(ConfigurationSection section, String path) {
        String value = section.getString(path);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(path + " がありません");
        return value;
    }

    private YamlConfiguration quarantine(YamlConfiguration target, ConfigurationSection guards,
                                        String sourceId, String reason) {
        if (target == null) {
            target = quarantineFile.load();
            if (!target.isConfigurationSection("entries")) target.createSection("entries");
        }
        ConfigurationSection source = guards.getConfigurationSection(sourceId);
        String rawText = source == null ? String.valueOf(guards.get(sourceId))
                : source.getValues(true).toString();
        String fingerprint = fingerprint("guards.yml", sourceId, reason, rawText);
        ConfigurationSection entries = target.getConfigurationSection("entries");
        for (String entryId : entries.getKeys(false)) {
            if (fingerprint.equals(target.getString("entries." + entryId + ".fingerprint"))) {
                return target;
            }
        }
        String entry = "entries." + System.currentTimeMillis() + "-" + UUID.randomUUID();
        target.set(entry + ".source-file", "guards.yml");
        target.set(entry + ".source-id", sourceId);
        target.set(entry + ".reason", reason);
        target.set(entry + ".fingerprint", fingerprint);
        target.set(entry + ".quarantined-at", System.currentTimeMillis());
        if (source != null) {
            for (Map.Entry<String, Object> value : source.getValues(true).entrySet()) {
                if (!(value.getValue() instanceof ConfigurationSection)) {
                    target.set(entry + ".raw." + value.getKey(), value.getValue());
                }
            }
        } else {
            target.set(entry + ".raw-value", guards.get(sourceId));
        }
        return target;
    }

    private String fingerprint(String sourceFile, String sourceId, String reason, String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((sourceFile + "\n" + sourceId + "\n" + reason
                    + "\n" + raw).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte value : bytes) result.append(String.format("%02x", value));
            return result.toString();
        } catch (Exception exception) {
            return Integer.toHexString((sourceFile + sourceId + reason + raw).hashCode());
        }
    }

    public SafeYamlFile.SaveResult saveWithResult(Collection<GuardData> guardData) {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("version", CURRENT_VERSION);
        configuration.set("record-count", guardData == null ? 0
                : guardData.stream().filter(java.util.Objects::nonNull).count());
        configuration.createSection("guards");
        if (guardData != null) {
            for (GuardData data : guardData) {
                if (data == null) continue;
                String path = "guards." + data.getGuardId();
                configuration.set(path + ".owner", data.getOwnerId().toString());
                configuration.set(path + ".owner-name", data.getOwnerName());
                configuration.set(path + ".mob-type", data.getMobType().name());
                configuration.set(path + ".mode", data.getMode().commandName());
                configuration.set(path + ".name", data.getName());
                configuration.set(path + ".name-number", data.getNameNumber());
                configuration.set(path + ".contract-status", data.getContractStatus().name());
                configuration.set(path + ".observation-status", data.getObservationStatus().name());
                configuration.set(path + ".release-pending", data.isReleasePending());
                configuration.set(path + ".deletion-pending", data.isDeletionPending());
                configuration.set(path + ".death-confirmed", data.isDeathConfirmed());
                configuration.set(path + ".operation-completed", data.isOperationCompleted());
                configuration.set(path + ".operation-id", data.getOperationId() == null
                        ? null : data.getOperationId().toString());
                configuration.set(path + ".operation-type", data.getOperationType() == null
                        ? null : data.getOperationType().name());
                configuration.set(path + ".operation-accepted-at", data.getOperationAcceptedAt());
                configuration.set(path + ".operation-completed-at", data.getOperationCompletedAt());
                configuration.set(path + ".operation-last-failure", data.getOperationLastFailureReason());
                configuration.set(path + ".contract-generation", data.getContractGeneration());
                configuration.set(path + ".save-revision", data.getSaveRevision());
                configuration.set(path + ".favorite", data.isFavorite());
                configuration.set(path + ".last-seen", data.getLastSeen());
                configuration.set(path + ".missing-since", data.getMissingSince());
                configuration.set(path + ".missing-observations", data.getMissingObservations());
                configuration.set(path + ".protection-kind", data.getProtectionKind().name());
                configuration.set(path + ".role-id", data.getRoleId());
                configuration.set(path + ".selected-target-uuid", data.getSelectedTargetUuid() == null
                        ? null : data.getSelectedTargetUuid().toString());
                configuration.set(path + ".selection-revision", data.getSelectionRevision());
                configuration.set(path + ".protection-state", data.getProtectionState().name());
                configuration.set(path + ".protection-failure-reason",
                        data.getProtectionFailureReason());
                if (data.getSavedAnchor() != null) {
                    data.getSavedAnchor().write(configuration, path + ".anchor-location");
                }
                if (data.getSavedLast() != null) {
                    data.getSavedLast().write(configuration, path + ".last-location");
                }
            }
        }
        return safeFile.saveWithResult(configuration);
    }

    public boolean save(Collection<GuardData> guardData) {
        return saveWithResult(guardData) == SafeYamlFile.SaveResult.SUCCESS;
    }

    public boolean isHealthy() { return safeFile.isHealthy(); }
    public SafeYamlFile.SaveResult getLastSaveResult() { return safeFile.getLastSaveResult(); }
    public long getLastSaved() { return safeFile.getLastSaved(); }
    public long getLastAttempt() { return safeFile.getLastAttempt(); }
    public int getConsecutiveSaveFailures() { return safeFile.getConsecutiveFailures(); }
    public String getLastFailureReason() { return safeFile.getLastFailureReason(); }
}
