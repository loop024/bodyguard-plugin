package plugin.test.com.bodyGuard.storage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import plugin.test.com.bodyGuard.guard.GuardData;

/**
 * Independent append-style record of destructive contract decisions.
 * The registry may be restored from an older backup, but this file keeps the
 * latest release/delete/death intent from being forgotten.
 */
public final class OperationLedgerStorage {

    private static final int CURRENT_VERSION = 1;

    public record Entry(long sequence, UUID operationId, long contractGeneration,
                        UUID guardId, UUID ownerId, GuardData.OperationType type,
                        long acceptedAt, long completedAt, String recordChecksum) {
        public boolean completed() {
            return completedAt > 0L;
        }
    }

    private final SafeYamlFile safeFile;
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    private long nextSequence = 1L;

    public OperationLedgerStorage(JavaPlugin plugin) {
        safeFile = new SafeYamlFile(plugin, "operations.yml", "operations");
    }

    public void load() {
        entries.clear();
        nextSequence = 1L;
        YamlConfiguration configuration = safeFile.load();
        int version = configuration.getInt("version", 0);
        if (version > CURRENT_VERSION) {
            throw new IllegalStateException("operations.yml は未対応の形式です。上書きしません。");
        }
        ConfigurationSection records = configuration.getConfigurationSection("operations");
        if (records == null) return;
        long highest = 0L;
        for (String key : records.getKeys(false)) {
            String path = "operations." + key;
            try {
                long sequence = positiveLong(configuration, path + ".sequence");
                UUID operationId = UUID.fromString(required(configuration, path + ".operation-id"));
                UUID guardId = UUID.fromString(required(configuration, path + ".guard-id"));
                UUID ownerId = UUID.fromString(required(configuration, path + ".owner-id"));
                GuardData.OperationType type = GuardData.OperationType.valueOf(
                        required(configuration, path + ".operation-type")
                                .toUpperCase(java.util.Locale.ROOT));
                long generation = positiveLong(configuration, path + ".contract-generation");
                long acceptedAt = positiveLong(configuration, path + ".accepted-at");
                long completedAt = nonNegativeLong(configuration, path + ".completed-at");
                String checksum = required(configuration, path + ".checksum");
                Entry entry = new Entry(sequence, operationId, generation, guardId, ownerId,
                        type, acceptedAt, completedAt, checksum);
                if (!checksum.equals(checksum(entry))) {
                    throw new IllegalArgumentException("checksumが一致しません");
                }
                Entry previous = entries.putIfAbsent(operationId, entry);
                if (previous != null && !previous.equals(entry)) {
                    throw new IllegalArgumentException("同じ操作IDに異なる台帳記録があります");
                }
                highest = Math.max(highest, sequence);
            } catch (RuntimeException exception) {
                throw new IllegalStateException("operations.yml の台帳記録が不正です: " + key, exception);
            }
        }
        nextSequence = Math.max(1L, highest + 1L);
    }

    public boolean append(UUID operationId, long contractGeneration, UUID guardId, UUID ownerId,
                          GuardData.OperationType type, long acceptedAt) {
        if (operationId == null || guardId == null || ownerId == null || type == null
                || contractGeneration < 1L || acceptedAt < 1L) {
            return false;
        }
        Entry existing = entries.get(operationId);
        if (existing != null) {
            return existing.guardId().equals(guardId)
                    && existing.ownerId().equals(ownerId)
                    && existing.contractGeneration() == contractGeneration
                    && existing.type() == type;
        }
        Entry entry = new Entry(nextSequence++, operationId, contractGeneration, guardId,
                ownerId, type, acceptedAt, 0L, null);
        entry = withChecksum(entry);
        entries.put(operationId, entry);
        if (save()) return true;
        entries.remove(operationId);
        nextSequence = Math.max(1L, nextSequence - 1L);
        return false;
    }

    public boolean complete(UUID operationId, long completedAt) {
        Entry existing = entries.get(operationId);
        if (existing == null) return false;
        if (existing.completed()) return true;
        Entry updated = withChecksum(new Entry(existing.sequence(), existing.operationId(),
                existing.contractGeneration(), existing.guardId(), existing.ownerId(),
                existing.type(), existing.acceptedAt(), Math.max(1L, completedAt), null));
        entries.put(operationId, updated);
        if (save()) return true;
        entries.put(operationId, existing);
        return false;
    }

    /** Re-applies accepted decisions after a registry backup has been restored. */
    public void applyTo(Map<UUID, GuardData> guards) {
        if (guards == null) return;
        for (Entry entry : entries.values()) {
            GuardData data = guards.get(entry.guardId());
            if (data == null || !entry.ownerId().equals(data.getOwnerId())
                    || data.getContractGeneration() != entry.contractGeneration()) {
                // A newer contract generation is intentionally not touched by an
                // older operation. Missing records are left for PDC quarantine.
                continue;
            }
            if (data.getOperationId() == null) {
                data.beginOperation(entry.operationId(), entry.type(), entry.acceptedAt());
            } else if (!entry.operationId().equals(data.getOperationId())) {
                data.markQuarantined("操作台帳と護衛データの操作IDが一致しません");
                continue;
            }
            if (entry.completed()) data.completeOperation(entry.completedAt());
        }
    }

    public Collection<Entry> getEntries() {
        return new ArrayList<>(entries.values());
    }

    public Entry get(UUID operationId) {
        return operationId == null ? null : entries.get(operationId);
    }

    public boolean isHealthy() { return safeFile.isHealthy(); }
    public SafeYamlFile.SaveResult getLastSaveResult() { return safeFile.getLastSaveResult(); }
    public long getLastSaved() { return safeFile.getLastSaved(); }
    public int size() { return entries.size(); }

    private boolean save() {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("version", CURRENT_VERSION);
        configuration.set("record-count", entries.size());
        configuration.createSection("operations");
        for (Entry entry : entries.values()) {
            String path = "operations." + entry.operationId();
            configuration.set(path + ".sequence", entry.sequence());
            configuration.set(path + ".operation-id", entry.operationId().toString());
            configuration.set(path + ".contract-generation", entry.contractGeneration());
            configuration.set(path + ".guard-id", entry.guardId().toString());
            configuration.set(path + ".owner-id", entry.ownerId().toString());
            configuration.set(path + ".operation-type", entry.type().name());
            configuration.set(path + ".accepted-at", entry.acceptedAt());
            configuration.set(path + ".completed-at", entry.completedAt());
            configuration.set(path + ".checksum", entry.recordChecksum());
        }
        return safeFile.saveWithResult(configuration) == SafeYamlFile.SaveResult.SUCCESS;
    }

    private Entry withChecksum(Entry entry) {
        return new Entry(entry.sequence(), entry.operationId(), entry.contractGeneration(),
                entry.guardId(), entry.ownerId(), entry.type(), entry.acceptedAt(),
                entry.completedAt(), checksum(entry));
    }

    private String checksum(Entry entry) {
        String value = entry.sequence() + "|" + entry.operationId() + "|"
                + entry.contractGeneration() + "|" + entry.guardId() + "|"
                + entry.ownerId() + "|" + entry.type() + "|" + entry.acceptedAt()
                + "|" + entry.completedAt();
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : bytes) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception exception) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private String required(YamlConfiguration configuration, String path) {
        String value = configuration.getString(path);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(path + " がありません");
        return value;
    }

    private long positiveLong(YamlConfiguration configuration, String path) {
        long value = nonNegativeLong(configuration, path);
        if (value < 1L) throw new IllegalArgumentException(path + " は正数ではありません");
        return value;
    }

    private long nonNegativeLong(YamlConfiguration configuration, String path) {
        if (!configuration.contains(path)) throw new IllegalArgumentException(path + " がありません");
        Object raw = configuration.get(path);
        if (!(raw instanceof Number number) || number.longValue() < 0
                || number.doubleValue() != number.longValue()) {
            throw new IllegalArgumentException(path + " は非負整数ではありません");
        }
        return number.longValue();
    }
}
