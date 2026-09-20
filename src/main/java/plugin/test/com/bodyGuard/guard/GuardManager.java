package plugin.test.com.bodyGuard.guard;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.LinkedHashSet;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.Chunk;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import plugin.test.com.bodyGuard.BodyGuard;
import plugin.test.com.bodyGuard.BodyGuard.GuardFeedback;
import plugin.test.com.bodyGuard.BodyGuard.NamespacedKeys;
import plugin.test.com.bodyGuard.storage.GuardStorage;
import plugin.test.com.bodyGuard.storage.OperationLedgerStorage;
import plugin.test.com.bodyGuard.storage.PlayerDataStorage;
import plugin.test.com.bodyGuard.storage.SafeYamlFile;
import plugin.test.com.bodyGuard.util.EntityUtil;
import plugin.test.com.bodyGuard.util.LocationUtil;
import plugin.test.com.bodyGuard.guard.RoleDefinition;

/** Owns the registry and all BodyGuard metadata operations. */
public final class GuardManager {

    private static final int MISSING_SEARCH_CHUNK_RADIUS = 2;

    private final BodyGuard plugin;
    private final GuardStorage storage;
    private final NamespacedKeys keys;
    private final PlayerDataStorage playerDataStorage;
    private final OperationLedgerStorage operationLedger;
    private final Map<UUID, GuardData> guards = new LinkedHashMap<>();
    private final Set<GuardChunk> managedChunks = new LinkedHashSet<>();
    public enum ChunkWaitReason { DISABLED, OWNER_OFFLINE, OWNER_LIMIT, SERVER_LIMIT, SCHEDULED, UNKNOWN }
    private final Map<UUID, ChunkWaitReason> chunkWaitReasons = new LinkedHashMap<>();

    /** Last management decision, for explanation only; never requests a chunk load. */
    public ChunkWaitReason getChunkWaitReason(GuardData data) {
        if (!plugin.keepGuardChunksLoaded()) return ChunkWaitReason.DISABLED;
        Player owner = Bukkit.getPlayer(data.getOwnerId());
        if (owner == null || !owner.isOnline()) return ChunkWaitReason.OWNER_OFFLINE;
        return chunkWaitReasons.getOrDefault(data.getGuardId(), ChunkWaitReason.UNKNOWN);
    }
    private boolean dirty;
    private final Map<String, Long> failureWarnings = new LinkedHashMap<>();
    private final Map<String, Long> retryNotBefore = new LinkedHashMap<>();
    private final Map<UUID, Integer> searchOffsets = new LinkedHashMap<>();
    private final Set<UUID> pendingProtectionRefresh = new LinkedHashSet<>();
    private int searchBudget = 32;
    private int ownerCursor;
    private long lastCycleDurationNanos = -1L;
    private long lastRegistrySaveDurationNanos = -1L;

    public void reportFailure(String operation, UUID guardId, RuntimeException failure) {
        long now = System.currentTimeMillis();
        String key = operation + ":" + (guardId == null ? "global" : guardId);
        if (now - failureWarnings.getOrDefault(key, 0L) < 30000) return;
        failureWarnings.put(key, now);
        retryNotBefore.put(key, now + Math.min(60000L,
                Math.max(5000L, 5000L + retryNotBefore.size() * 1000L)));
        plugin.getLogger().log(java.util.logging.Level.WARNING,
                "BodyGuard " + operation + " に失敗しました。対象: " + guardId, failure);
    }

    public GuardManager(BodyGuard plugin, GuardStorage storage, NamespacedKeys keys,
                        PlayerDataStorage playerDataStorage,
                        OperationLedgerStorage operationLedger) {
        this.plugin = plugin;
        this.storage = storage;
        this.keys = keys;
        this.playerDataStorage = playerDataStorage;
        this.operationLedger = operationLedger;
    }

    public GuardManager(BodyGuard plugin, GuardStorage storage, NamespacedKeys keys,
                        PlayerDataStorage playerDataStorage) {
        this(plugin, storage, keys, playerDataStorage, null);
    }

    public boolean shouldRetry(String operation, UUID guardId) {
        String key = operation + ":" + (guardId == null ? "global" : guardId);
        return System.currentTimeMillis() >= retryNotBefore.getOrDefault(key, 0L);
    }

    public void clearFailure(String operation, UUID guardId) {
        String key = operation + ":" + (guardId == null ? "global" : guardId);
        retryNotBefore.remove(key);
        failureWarnings.remove(key);
    }

    public void load(Map<UUID, GuardData> savedGuards) {
        guards.clear();
        if (savedGuards != null) {
            guards.putAll(savedGuards);
        }
        if (operationLedger != null) {
            if (operationLedger.applyTo(guards)) {
                dirty = true;
            }
        }
        for (Map.Entry<UUID, UUID> entry : playerDataStorage.getCompanions().entrySet()) {
            GuardData companion = guards.get(entry.getValue());
            if (companion == null || !entry.getKey().equals(companion.getOwnerId())
                    || !companion.isActiveContract()) {
                playerDataStorage.setCompanion(entry.getKey(), null);
            }
        }
        requestProtectionRefreshAll();
        // Ledger application or companion cleanup may have changed the in-memory
        // registry and must be persisted on the normal startup reconciliation path.
    }

    /** Saves changed player and guard data and reports whether both outcomes are known-good. */
    public boolean save() {
        boolean playerSaved;
        try {
            playerSaved = playerDataStorage.retrySave()
                    == SafeYamlFile.SaveResult.SUCCESS;
        } catch (RuntimeException failure) {
            reportFailure("player-save", null, failure);
            playerSaved = false;
        }
        boolean registrySaved;
        try {
            registrySaved = !dirty || persistRegistry();
        } catch (RuntimeException failure) {
            reportFailure("registry-save", null, failure);
            registrySaved = false;
        }
        return playerSaved && registrySaved;
    }

    private boolean persistRegistry() {
        if (!dirty) return true;
        long started = System.nanoTime();
        boolean saved;
        try {
            saved = storage.saveWithResult(new ArrayList<>(guards.values()))
                    == SafeYamlFile.SaveResult.SUCCESS;
        } finally {
            lastRegistrySaveDurationNanos = System.nanoTime() - started;
        }
        if (saved) {
            dirty = false;
        }
        return saved;
    }

    /** Retry failed writes independently of the configured autosave interval. */
    public boolean retryFailedSaves() {
        return save();
    }

    public boolean persistNow() {
        return persistRegistry();
    }

    public boolean isStorageHealthy() {
        return storage.isHealthy() && playerDataStorage.isHealthy()
                && (operationLedger == null || operationLedger.isHealthy());
    }

    public GuardData.Status status(GuardData data) {
        if (data == null) return GuardData.Status.QUARANTINED;
        if (data.isQuarantined()) return GuardData.Status.QUARANTINED;
        switch (data.getContractStatus()) {
            case RELEASE_PENDING -> { return GuardData.Status.RELEASE_PENDING; }
            case RELEASED -> { return GuardData.Status.RELEASED; }
            case DELETE_PENDING -> { return GuardData.Status.DELETE_PENDING; }
            case DELETED -> { return GuardData.Status.DELETED; }
            case DEAD -> { return GuardData.Status.DEAD; }
            case ACTIVE -> { }
        }
        Entity entity = findLoadedEntityForRead(data);
        if (entity != null) {
            if (isEntityConsistent(data, entity) && EntityUtil.isAlive(entity)) {
                return GuardData.Status.AVAILABLE;
            }
            return GuardData.Status.QUARANTINED;
        }
        SavedPosition savedLast = data.getSavedLast();
        if (savedLast == null || savedLast.worldId() == null) {
            return savedLast == null ? GuardData.Status.CHECKING : GuardData.Status.WORLD_UNAVAILABLE;
        }
        World world = Bukkit.getWorld(savedLast.worldId());
        if (world == null) return GuardData.Status.WORLD_UNAVAILABLE;
        if (!world.isChunkLoaded((int) Math.floor(savedLast.x()) >> 4,
                (int) Math.floor(savedLast.z()) >> 4)) {
            return GuardData.Status.UNLOADED;
        }
        return data.getMissingSince() > 0 && data.getMissingObservations() >= 2
                && System.currentTimeMillis() - data.getMissingSince() >= 30000L
                ? GuardData.Status.MISSING : GuardData.Status.CHECKING;
    }

    public List<GuardData> getHistory(UUID ownerId) {
        return guards.values().stream().filter(data -> ownerId != null
                && ownerId.equals(data.getOwnerId()) && data.isRetired()).toList();
    }

    /** Persists the current registry even when no change was recorded, for plugin shutdown. */
    public boolean forceSave() {
        long started = System.nanoTime();
        boolean saved;
        try {
            saved = storage.saveWithResult(new ArrayList<>(guards.values()))
                    == SafeYamlFile.SaveResult.SUCCESS;
        } finally {
            lastRegistrySaveDurationNanos = System.nanoTime() - started;
        }
        if (saved) {
            dirty = false;
        }
        return saved;
    }

    public void recordCycleDuration(long durationNanos) {
        lastCycleDurationNanos = Math.max(0L, durationNanos);
    }

    /** Records a change made by the periodic entity-state synchronizer. */
    public void markDirty() {
        dirty = true;
    }

    public Collection<GuardData> getAllGuardData() {
        return new ArrayList<>(guards.values());
    }

    /** Marks role-target evaluation for the next shared GuardTask tick. */
    public void requestProtectionRefresh(UUID playerId) {
        if (playerId == null) return;
        for (GuardData data : guards.values()) {
            if (data.isActiveContract() && data.isRoleProtection()) {
                pendingProtectionRefresh.add(data.getGuardId());
            }
        }
    }

    /** Re-evaluates all stored role selections after configuration reload/startup. */
    public void requestProtectionRefreshAll() {
        for (GuardData data : guards.values()) {
            if (data.isActiveContract() && data.isRoleProtection()) {
                pendingProtectionRefresh.add(data.getGuardId());
            }
        }
    }

    /** Recovers marked guards that were already loaded before plugin listeners started. */
    public int reconcileAlreadyLoadedEntities() {
        int recovered = 0;
        for (World world : Bukkit.getWorlds()) {
            try {
                for (Chunk chunk : world.getLoadedChunks()) {
                    try {
                        for (Entity entity : chunk.getEntities()) {
                            if (entity == null) continue;
                            try {
                                boolean knownBefore = guards.containsKey(entity.getUniqueId());
                                GuardData tracked = trackLoadedEntity(entity);
                                if (tracked != null && !knownBefore) {
                                    recovered++;
                                }
                            } catch (RuntimeException failure) {
                                reportFailure("startup-reconcile", entity.getUniqueId(), failure);
                            }
                        }
                    } catch (RuntimeException failure) {
                        reportFailure("startup-chunk-reconcile", world.getUID(), failure);
                    }
                }
            } catch (RuntimeException failure) {
                reportFailure("startup-world-reconcile", world.getUID(), failure);
            }
        }
        if (dirty) {
            try {
                save();
            } catch (RuntimeException failure) {
                reportFailure("startup-save", null, failure);
            }
        }
        return recovered;
    }

    public List<GuardData> getGuards(UUID ownerId) {
        List<GuardData> result = new ArrayList<>();
        if (ownerId == null) {
            return result;
        }
        for (GuardData data : guards.values()) {
            // Every ACTIVE contract, including MISSING and WORLD_UNAVAILABLE,
            // retains the owner's slot until the owner explicitly releases it.
            if (ownerId.equals(data.getOwnerId()) && data.isActiveContract()) {
                result.add(data);
            }
        }
        return result;
    }

    public int countGuards(UUID ownerId) {
        return getGuards(ownerId).size();
    }

    public GuardData registerGuard(Mob mob, Player owner) {
        if (mob == null || owner == null || isGuard(mob)) {
            return null;
        }
        Location location = mob.getLocation();
        String ownerName = owner.getName();
        int nameNumber = nextNameNumber(owner.getUniqueId(), mob.getType());
        String name = createDefaultName(ownerName, mob.getType(), nameNumber);
        GuardData data = new GuardData(
                mob.getUniqueId(), owner.getUniqueId(), mob.getType(), GuardMode.FOLLOW,
                name, ownerName, null, location, nameNumber);

        GuardData previous = guards.get(data.getGuardId());
        if (previous == null && isMarked(mob.getPersistentDataContainer())) {
            // A marked Entity without a registry record is not safe to transfer
            // or overwrite. The PDC alone cannot prove that an old contract ended.
            plugin.getLogger().warning("レジストリにないBodyGuard PDC付きMobを勧誘しません: "
                    + data.getGuardId());
            return null;
        }
        if (previous != null && previous.isQuarantined()) return null;
        if (previous != null && previous.getContractStatus() == GuardData.ContractStatus.RELEASED) {
            // An explicit re-recruit may reuse the UUID only after any stale
            // released PDC has been reconciled and removed.
            reconcileRetiredEntity(previous, mob);
            if (isMarked(mob.getPersistentDataContainer())) return null;
        }
        if (previous != null && !previous.getOwnerId().equals(owner.getUniqueId())) return null;
        if (previous != null && previous.getContractStatus() != GuardData.ContractStatus.RELEASED) {
            // A pending, deleted, or dead contract is a tombstone. It must not be
            // reused merely because an old PDC is still attached to the entity.
            return null;
        }
        if (previous != null) {
            long previousGeneration = previous.getContractGeneration();
            data.setContractGeneration(previousGeneration == Long.MAX_VALUE
                    ? Long.MAX_VALUE : previousGeneration + 1L);
        }
        else if (operationLedger != null) {
            data.setContractGeneration(operationLedger.nextContractGeneration(data.getGuardId()));
        }
        try {
            saveOriginalSettings(mob);
            data.observed();
            guards.put(data.getGuardId(), data);
            dirty = true;
            if (!persistNow()) throw new IllegalStateException("護衛の登録を保存できませんでした。");
            applyPdc(mob, data);
            mob.setAware(true);
            configureGuard(mob, data);
            return data;
        } catch (RuntimeException failure) {
            if (previous == null) guards.remove(data.getGuardId());
            else guards.put(data.getGuardId(), previous);
            dirty = true;
            try {
                restoreOriginalSettings(mob);
                clearPdc(mob);
            } catch (RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            reportFailure("register", data.getGuardId(), failure);
            owner.sendMessage("§c[BodyGuard] 登録できませんでした。保存先とサーバーログを確認してください。");
            return null;
        }
    }
    /** Registers a BodyGuard found in a loaded chunk after a restart. */
    public GuardData trackLoadedEntity(Entity entity) {
        if (!(entity instanceof Mob mob)) {
            return null;
        }
        UUID entityId = entity.getUniqueId();
        GuardData previous = guards.get(entityId);
        if (previous != null && previous.isQuarantined()) {
            // A contradictory record must remain visible to administrators until
            // it is repaired. Never replace it with a reconstructed PDC record.
            plugin.getLogger().warning("隔離中の護衛をPDCから再構成しません: " + entityId);
            return null;
        }
        if (previous != null && previous.isRetired()) {
            reconcileRetiredEntity(previous, mob);
            return null;
        }

        PersistentDataContainer pdc = mob.getPersistentDataContainer();
        if (!isMarked(pdc)) {
            if (previous != null && previous.isActiveContract()) {
                quarantine(previous, "登録済みUUIDのEntityからBodyGuard PDCが失われています");
            }
            return null;
        }

        UUID markedGuardId = parseUuid(getString(pdc, keys.guardUuid()));
        if (markedGuardId == null || !entityId.equals(markedGuardId)) {
            if (previous != null) quarantine(previous, "PDCのguard UUIDがEntity UUIDと一致しません");
            plugin.getLogger().warning("BodyGuard PDCのguard UUIDが不正なため再登録しません: " + entityId);
            return null;
        }
        UUID ownerId = parseUuid(getString(pdc, keys.owner()));
        if (ownerId == null) {
            if (previous != null) quarantine(previous, "PDCの所有者UUIDが不正です");
            plugin.getLogger().warning("Ignoring BodyGuard with invalid owner UUID: " + entity.getUniqueId());
            return null;
        }

        if (previous == null) {
            // A PDC marker without a registry contract is not enough evidence
            // to resurrect or transfer an old guard after a rollback.
            String reason = operationLedgerHasGuard(entityId)
                    ? "完了済み操作台帳のUUIDをPDCから再登録しません: "
                    : "レジストリにないBodyGuard PDCを再登録しません: ";
            plugin.getLogger().warning(reason + entityId);
            return null;
        }
        if (!previous.getOwnerId().equals(ownerId)) {
            quarantine(previous, "PDCの所有者UUIDが保存レジストリと一致しません");
            return null;
        }
        if (!isProtectionPdcConsistent(previous, pdc)) {
            quarantine(previous, "PDCの役職保護指定が保存レジストリと一致しません");
            return null;
        }
        String pdcMobType = getString(pdc, keys.mobType());
        if (pdcMobType == null || !mob.getType().name().equalsIgnoreCase(pdcMobType)
                || previous.getMobType() != mob.getType()
                || !previous.getMobType().name().equalsIgnoreCase(pdcMobType)) {
            quarantine(previous, "Mob種類が保存レジストリと一致しません");
            return null;
        }
        GuardMode mode = GuardMode.fromString(getString(pdc, keys.mode()));
        if (mode == null) {
            mode = previous.getMode();
        }

        String ownerName = previous.getOwnerName();
        if (ownerName == null || ownerName.isBlank()) {
            Player owner = Bukkit.getPlayer(ownerId);
            ownerName = owner == null ? "Player" : owner.getName();
        }
        int nameNumber = previous.getNameNumber();
        String name = getString(pdc, keys.name());
        if (name == null || name.isBlank()) {
            name = previous.getName();
        }

        SavedPosition savedAnchor = previous.getSavedAnchor();
        Location anchor = savedAnchor == null ? null : savedAnchor.resolve();
        if (anchor == null && mode != GuardMode.FOLLOW && previous.getSavedAnchor() == null) {
            anchor = entity.getLocation();
        }
        GuardData data = new GuardData(
                entityId, ownerId, mob.getType(), mode, name, ownerName, anchor,
                entity.getLocation(), nameNumber);
        if (savedAnchor != null) data.setSavedPositions(savedAnchor, SavedPosition.of(entity.getLocation()));
        data.observed();
        data.setContractGeneration(previous.getContractGeneration());
        Long pdcGeneration = pdc.get(keys.contractGeneration(), PersistentDataType.LONG);
        if (pdcGeneration != null
                && pdcGeneration.longValue() != previous.getContractGeneration()) {
            quarantine(previous, "PDCの契約世代が保存レジストリと一致しません");
            return null;
        }
        data.setFavorite(previous.isFavorite());
        data.setTactics(previous.getTactics());
        data.setSaveRevision(previous.getSaveRevision());
        data.restoreProtection(previous.snapshotProtection());
        guards.put(entityId, data);
        dirty = true;

        // The entity UUID is authoritative. This repairs an incomplete saved PDC entry.
        applyPdc(mob, data);
        configureGuard(mob, data);
        return data;
    }

    private void reconcileRetiredEntity(GuardData data, Mob mob) {
        try {
            boolean marked = isMarked(mob.getPersistentDataContainer());
            if (data.isReleasePending() || data.isDeletionPending()) {
                if (!marked) {
                    quarantine(data, "解除・削除待ちEntityのBodyGuard PDCがありません");
                    return;
                }
                if (!validateRetiredEntity(data, mob)) {
                    return;
                }
            } else if (marked && !validateRetiredEntity(data, mob)) {
                return;
            }
            switch (data.getContractStatus()) {
                case RELEASE_PENDING, RELEASED -> finalizePendingRelease(data, mob);
                case DELETE_PENDING, DELETED, DEAD -> finalizePendingDeletion(data, mob);
                case ACTIVE -> { }
            }
        } catch (RuntimeException failure) {
            data.recordOperationFailure(failure.getMessage());
            dirty = true;
            reportFailure("reconcile-retired", data.getGuardId(), failure);
        }
    }

    /** Validates a retired entity before restoring or removing its state. */
    private boolean validateRetiredEntity(GuardData data, Mob mob) {
        PersistentDataContainer pdc = mob.getPersistentDataContainer();
        UUID markedGuardId = parseUuid(getString(pdc, keys.guardUuid()));
        UUID markedOwnerId = parseUuid(getString(pdc, keys.owner()));
        String markedMobType = getString(pdc, keys.mobType());
        Long markedGeneration = pdc.get(keys.contractGeneration(), PersistentDataType.LONG);
        if (!isMarked(pdc) || !data.getGuardId().equals(markedGuardId)
                || !data.getOwnerId().equals(markedOwnerId)
                || mob.getType() != data.getMobType()
                || markedMobType == null
                || !data.getMobType().name().equalsIgnoreCase(markedMobType)
                || (markedGeneration != null
                    && markedGeneration.longValue() != data.getContractGeneration())) {
            quarantine(data, "完了・保留操作のEntity PDCが保存データと一致しません");
            return false;
        }
        return true;
    }

    private void quarantine(GuardData data, String reason) {
        if (data == null) return;
        data.markQuarantined(reason);
        dirty = true;
        save();
    }

    private boolean operationLedgerHasGuard(UUID guardId) {
        return operationLedger != null && operationLedger.getEntries().stream()
                .anyMatch(entry -> entry.guardId().equals(guardId));
    }

    public boolean isGuard(Entity entity) {
        return getGuardData(entity) != null;
    }

    public GuardData getGuardData(Entity entity) {
        if (entity == null) {
            return null;
        }
        GuardData data = guards.get(entity.getUniqueId());
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        if (data != null) {
            // This is deliberately a read-only query. GUI rendering, filtering and
            // combat checks never complete operations, rewrite PDC, save, or search.
            if (!data.isActiveContract() || data.isQuarantined() || !isMarked(pdc)) return null;
            return isEntityConsistent(data, entity) ? data : null;
        }
        // Registration/reconciliation is performed only by explicit load-event and
        // startup paths through trackLoadedEntity().
        return null;
    }

    /** Finds registry data by the persistent Guard UUID without loading an entity. */
    public GuardData getGuardData(UUID guardId) {
        return guardId == null ? null : guards.get(guardId);
    }

    public boolean isCompanion(GuardData data) {
        return data != null && data.getGuardId().equals(playerDataStorage.getCompanion(data.getOwnerId()));
    }

    public boolean toggleFavorite(UUID ownerId, UUID guardId) {
        GuardData data = getGuardData(guardId);
        if (data == null || ownerId == null || !ownerId.equals(data.getOwnerId())
                || !data.isActiveContract()) {
            return false;
        }
        boolean previous = data.isFavorite();
        data.setFavorite(!data.isFavorite());
        Mob mob = getLoadedMob(data);
        if (mob != null) {
            applyPdc(mob, data);
        }
        dirty = true;
        if (persistNow()) return true;
        data.setFavorite(previous);
        if (mob != null) applyPdc(mob, data);
        dirty = true;
        return false;
    }

    public boolean toggleCompanion(UUID ownerId, UUID guardId) {
        GuardData data = getGuardData(guardId);
        if (data == null || ownerId == null || !ownerId.equals(data.getOwnerId())) {
            return false;
        }
        UUID current = playerDataStorage.getCompanion(ownerId);
        UUID next = guardId.equals(current) ? null : guardId;
        if (playerDataStorage.setCompanion(ownerId, next)
                == SafeYamlFile.SaveResult.SUCCESS) {
            return true;
        }
        return false;
    }

    /** Changes a selected guard only when it is still owned by the caller and loaded. */
    public boolean setMode(UUID ownerId, UUID guardId, GuardMode mode) {
        return setMode(ownerId, guardId, mode, null);
    }

    /** Changes mode and, for guard mode, uses the explicitly selected patrol point. */
    public boolean setMode(UUID ownerId, UUID guardId, GuardMode mode, Location guardPoint) {
        GuardData data = getGuardData(guardId);
        if (data == null || ownerId == null || !ownerId.equals(data.getOwnerId()) || mode == null) {
            return false;
        }
        Mob mob = getLoadedMob(data);
        if (mob == null || !owns(ownerId, mob)) {
            return false;
        }
        return setMode(data, mob, mode, guardPoint);
    }

    /** Releases one selected guard only when it is still owned by the caller and loaded. */
    public boolean releaseGuard(UUID ownerId, UUID guardId) {
        GuardData data = getGuardData(guardId);
        if (data == null || ownerId == null || !ownerId.equals(data.getOwnerId())
                || !data.isActiveContract()) {
            return false;
        }
        return releaseGuards(ownerId, List.of(guardId)).affected() > 0;
    }

    /**
     * Releases exactly the UUID snapshot supplied by a GUI confirmation screen.
     * It intentionally does not load chunks, and never includes guards added later.
     */
    public ReleaseResult releaseGuards(UUID ownerId, Collection<UUID> guardIds) {
        if (ownerId == null || guardIds == null) return new ReleaseResult(0, 0, 0);
        int released = 0, queued = 0, failed = 0;
        for (UUID id : new LinkedHashSet<>(guardIds)) {
            GuardData data = guards.get(id);
            if (data == null || !ownerId.equals(data.getOwnerId())
                    || (!data.isActiveContract()
                        && !data.isReleasePending())) {
                failed++;
                continue;
            }
            if (data.isReleasePending()) {
                queued++;
                continue;
            }
            if (!acceptOperation(data, GuardData.OperationType.RELEASE)) {
                failed++;
                continue;
            }
            try {
                if (!clearCompanion(data)) {
                    queued++;
                    continue;
                }
                Entity entity = findLoadedEntity(data);
                if (entity instanceof Mob mob) {
                    if (finalizePendingRelease(data, mob)) released++;
                    else queued++;
                } else queued++;
            } catch (RuntimeException failure) {
                plugin.getLogger().log(java.util.logging.Level.WARNING,
                        "護衛の解除は保存済みです。再読み込み時に再試行します: " + id, failure);
                queued++;
            }
        }
        return new ReleaseResult(released, queued, failed);
    }
    public UUID getOwner(Entity entity) {
        GuardData data = getGuardData(entity);
        return data == null ? null : data.getOwnerId();
    }

    public boolean owns(UUID ownerId, Entity entity) {
        GuardData data = getGuardData(entity);
        return data != null && ownerId != null && ownerId.equals(data.getOwnerId());
    }

    public boolean isFriend(UUID ownerId, UUID playerId) {
        return playerDataStorage.isFriend(ownerId, playerId);
    }

    public java.util.Set<UUID> getFriends(UUID ownerId) {
        return playerDataStorage.getFriends(ownerId);
    }

    public boolean addFriend(UUID ownerId, UUID playerId) {
        return playerDataStorage.addFriend(ownerId, playerId);
    }

    public boolean removeFriend(UUID ownerId, UUID playerId) {
        return playerDataStorage.removeFriend(ownerId, playerId);
    }

    public SafeYamlFile.SaveResult getPlayerDataMutationResult() {
        return playerDataStorage.getLastMutationResult();
    }

    public Mob getLoadedMob(GuardData data) {
        if (data == null || !data.isActiveContract() || data.isQuarantined()) {
            return null;
        }
        Entity entity = findLoadedEntityForRead(data);
        if (!(entity instanceof Mob mob) || !EntityUtil.isAlive(entity)) {
            return null;
        }
        GuardData actual = getGuardData(entity);
        return actual != null && data.getGuardId().equals(actual.getGuardId()) ? mob : null;
    }

    /** Resolves the current role target on the shared main-thread task. */
    public Player refreshProtectionTarget(GuardData data) {
        return refreshProtectionTarget(data, getLoadedMob(data));
    }

    /**
     * Resolves a role without ever fabricating an Entity. The remembered UUID is
     * preferred while it is eligible; only an ineligible remembered player causes
     * deterministic candidate selection.
     */
    public Player refreshProtectionTarget(GuardData data, Mob loadedMob) {
        if (data == null || !data.isActiveContract()) return null;
        pendingProtectionRefresh.remove(data.getGuardId());

        if (!data.isRoleProtection()) {
            Player owner = Bukkit.getPlayer(data.getOwnerId());
            if (owner == null || !EntityUtil.isAlive(owner)
                    || owner.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
                setProtectionState(data, GuardData.ProtectionState.TARGET_UNAVAILABLE,
                        "所有者がオンラインではありません");
                return null;
            }
            setProtectionState(data, LocationUtil.sameWorld(
                    loadedMob == null ? null : loadedMob.getLocation(), owner.getLocation())
                    ? GuardData.ProtectionState.SAME_WORLD : GuardData.ProtectionState.ACTIVE, null);
            return owner;
        }

        RoleDefinition role = plugin.isRoleProtectionEnabled()
                ? plugin.getRoleDefinition(data.getRoleId()) : null;
        if (role == null) {
            clearProtectionCombat(data, loadedMob);
            setProtectionState(data, GuardData.ProtectionState.TARGET_UNAVAILABLE,
                    plugin.isRoleProtectionEnabled()
                            ? "役職設定が見つかりません: " + data.getRoleId()
                            : "役職保護が設定で無効です");
            return null;
        }

        UUID rememberedId = data.getSelectedTargetUuid();
        Player remembered = rememberedId == null ? null : Bukkit.getPlayer(rememberedId);
        Player selected = isEligibleRoleTarget(remembered, role) ? remembered
                : chooseRoleTarget(data, loadedMob, role, rememberedId);
        if (selected == null) {
            clearProtectionCombat(data, loadedMob);
            setProtectionState(data, GuardData.ProtectionState.TARGET_UNAVAILABLE,
                    "適格な役職プレイヤーがいません");
            return null;
        }

        if (!Objects.equals(rememberedId, selected.getUniqueId())
                && !rememberProtectionTarget(data, loadedMob, selected.getUniqueId())) {
            clearProtectionCombat(data, loadedMob);
            setProtectionState(data, GuardData.ProtectionState.WAITING,
                    "役職対象の選択保存に失敗しました");
            return null;
        }

        if (loadedMob == null) {
            setProtectionState(data, GuardData.ProtectionState.TARGET_SELECTED,
                    "護衛Entityの読み込みを待機しています");
        } else if (LocationUtil.sameWorld(loadedMob.getLocation(), selected.getLocation())) {
            setProtectionState(data, GuardData.ProtectionState.SAME_WORLD, null);
        } else if (data.getMode() == GuardMode.STAY) {
            setProtectionState(data, GuardData.ProtectionState.ACTIVE,
                    "待機モードのため対象ワールドへ同行しません");
        } else {
            if (!plugin.shouldTeleportDifferentWorld()
                    || data.getProtectionState() != GuardData.ProtectionState.WAITING) {
                setProtectionState(data, GuardData.ProtectionState.WORLD_TRANSFER_PENDING,
                        plugin.shouldTeleportDifferentWorld()
                                ? "対象ワールドへの移動を待機しています"
                                : "別ワールド移動は設定で無効です");
            }
        }
        return selected;
    }

    private Player chooseRoleTarget(GuardData data, Mob loadedMob,
                                    RoleDefinition role, UUID rememberedId) {
        List<Player> candidates = Bukkit.getOnlinePlayers().stream()
                .<Player>map(player -> player)
                .filter(player -> isEligibleRoleTarget(player, role))
                .toList();
        if (candidates.isEmpty()) return null;

        Location mobLocation = loadedMob == null ? null : loadedMob.getLocation();
        boolean hasSameWorld = mobLocation != null && candidates.stream()
                .anyMatch(player -> LocationUtil.sameWorld(mobLocation, player.getLocation()));
        return candidates.stream()
                .min(Comparator
                        .comparingInt((Player player) -> hasSameWorld
                                && LocationUtil.sameWorld(mobLocation, player.getLocation()) ? 0 : 1)
                        .thenComparingDouble(player -> mobLocation == null
                                || !LocationUtil.sameWorld(mobLocation, player.getLocation())
                                ? Double.POSITIVE_INFINITY
                                : mobLocation.distanceSquared(player.getLocation()))
                        .thenComparingInt(player -> Objects.equals(rememberedId,
                                player.getUniqueId()) ? 0 : 1)
                        .thenComparing(player -> player.getUniqueId().toString()))
                .orElse(null);
    }

    private boolean isEligibleRoleTarget(Player player, RoleDefinition role) {
        return player != null && player.isOnline() && EntityUtil.isAlive(player)
                && player.getGameMode() != org.bukkit.GameMode.SPECTATOR
                && role != null && player.hasPermission(role.permission());
    }

    private boolean rememberProtectionTarget(GuardData data, Mob loadedMob, UUID targetId) {
        GuardData.ProtectionSnapshot previous = data.snapshotProtection();
        try {
            if (!data.setSelectedTargetUuid(targetId)) return true;
            data.clearCombat();
            if (loadedMob != null) loadedMob.setTarget(null);
            if (loadedMob != null) applyPdc(loadedMob, data);
            dirty = true;
            if (persistNow()) return true;
            throw new IllegalStateException("役職対象の選択保存結果を確認できません");
        } catch (RuntimeException failure) {
            data.restoreProtection(previous);
            if (loadedMob != null) {
                try {
                    applyPdc(loadedMob, data);
                } catch (RuntimeException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            dirty = true;
            reportFailure("protection-selection", data.getGuardId(), failure);
            return false;
        }
    }

    private void setProtectionState(GuardData data, GuardData.ProtectionState state,
                                    String reason) {
        if (data == null) return;
        if (data.getProtectionState() == state
                && Objects.equals(data.getProtectionFailureReason(), reason)) return;
        data.setProtectionState(state, reason);
        dirty = true;
    }

    public void setProtectionRuntimeState(GuardData data, GuardData.ProtectionState state,
                                          String reason) {
        setProtectionState(data, state, reason);
    }

    /** Updates the moving patrol centre only after a meaningful target movement. */
    public boolean updateRolePatrolAnchor(GuardData data, Mob mob, Location targetLocation) {
        if (data == null || !data.isRoleProtection() || targetLocation == null
                || targetLocation.getWorld() == null) return false;
        SavedPosition current = data.getSavedAnchor();
        Location previous = data.getAnchorLocation();
        if (current != null && previous != null && LocationUtil.sameWorld(previous, targetLocation)
                && previous.distanceSquared(targetLocation) < 1.0) return false;
        data.setAnchorLocation(targetLocation);
        if (mob != null) applyPdc(mob, data);
        dirty = true;
        return true;
    }

    private void clearProtectionCombat(GuardData data, Mob mob) {
        if (data == null) return;
        data.clearCombat();
        if (mob != null) mob.setTarget(null);
    }

    public enum ProtectionChangeResult {
        SUCCESS,
        NOT_FOUND,
        NOT_OWNER,
        ROLE_NOT_CONFIGURED,
        NOT_LOADED,
        SAVE_FAILED
    }

    /** Changes protection for exactly one owned, loaded guard with rollback. */
    public ProtectionChangeResult setProtection(UUID ownerId, UUID guardId,
                                                GuardData.ProtectionKind kind, String roleId) {
        GuardData data = getGuardData(guardId);
        if (data == null || !data.isActiveContract()) return ProtectionChangeResult.NOT_FOUND;
        if (ownerId == null || !ownerId.equals(data.getOwnerId())) {
            return ProtectionChangeResult.NOT_OWNER;
        }
        if (kind == GuardData.ProtectionKind.ROLE) {
            if (!plugin.isRoleProtectionEnabled() || plugin.getRoleDefinition(roleId) == null) {
                return ProtectionChangeResult.ROLE_NOT_CONFIGURED;
            }
        }
        Mob mob = getLoadedMob(data);
        if (mob == null || !owns(ownerId, mob)) return ProtectionChangeResult.NOT_LOADED;

        GuardData.ProtectionSnapshot previous = data.snapshotProtection();
        GuardTactics previousTactics = data.getTactics();
        SavedPosition previousAnchor = data.getSavedAnchor();
        SavedPosition previousLast = data.getSavedLast();
        try {
            if (kind == GuardData.ProtectionKind.ROLE && previousTactics.patrol()) {
                data.setTactics(previousTactics.stopped());
            }
            data.setProtection(kind, roleId);
            data.clearCombat();
            mob.setTarget(null);
            if (data.isRoleProtection() && data.getMode() == GuardMode.GUARD) {
                RoleDefinition role = plugin.getRoleDefinition(data.getRoleId());
                Player target = isEligibleRoleTarget(
                        data.getSelectedTargetUuid() == null ? null
                                : Bukkit.getPlayer(data.getSelectedTargetUuid()), role)
                        ? Bukkit.getPlayer(data.getSelectedTargetUuid()) : null;
                data.setAnchorLocation(target == null ? null : target.getLocation());
            }
            applyPdc(mob, data);
            dirty = true;
            if (!persistNow()) throw new IllegalStateException("保護設定の保存結果を確認できません");
            return ProtectionChangeResult.SUCCESS;
        } catch (RuntimeException failure) {
            data.restoreProtection(previous);
            data.setTactics(previousTactics);
            data.setSavedPositions(previousAnchor, previousLast);
            try {
                applyPdc(mob, data);
            } catch (RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            dirty = true;
            reportFailure("protection-change", guardId, failure);
            return ProtectionChangeResult.SAVE_FAILED;
        }
    }

    public boolean isSelectedProtectionTarget(GuardData data, Player player) {
        if (data == null || player == null || !data.isRoleProtection()
                || !Objects.equals(data.getSelectedTargetUuid(), player.getUniqueId())) {
            return false;
        }
        RoleDefinition role = plugin.isRoleProtectionEnabled()
                ? plugin.getRoleDefinition(data.getRoleId()) : null;
        return isEligibleRoleTarget(player, role);
    }

    /** Commands one selected role guard, never all guards owned by the same owner. */
    public int commandRoleGuardsToTarget(UUID protectedTargetId, LivingEntity target,
                                         boolean defense) {
        if (protectedTargetId == null || target == null || !EntityUtil.isAlive(target)) return 0;
        Player protectedPlayer = Bukkit.getPlayer(protectedTargetId);
        if (protectedPlayer == null || !EntityUtil.isAlive(protectedPlayer)
                || protectedPlayer.getGameMode() == org.bukkit.GameMode.SPECTATOR) return 0;
        if (target.getUniqueId().equals(protectedTargetId)) return 0;
        if (target instanceof Player && !plugin.shouldDefendAgainstPlayers()) return 0;

        int commanded = 0;
        for (GuardData data : getAllGuardData()) {
            if (!data.isActiveContract() || !data.isRoleProtection()
                    || !protectedTargetId.equals(data.getSelectedTargetUuid())
                    || !data.getTactics().policy().allowsCommand(defense)) continue;
            try {
                Mob guard = getLoadedMob(data);
                if (guard == null || !isSelectedProtectionTarget(data, protectedPlayer)
                        || !LocationUtil.sameWorld(guard.getLocation(), protectedPlayer.getLocation())
                        || !LocationUtil.sameWorld(guard.getLocation(), target.getLocation())
                        || guard.getLocation().distanceSquared(protectedPlayer.getLocation())
                            > plugin.getTargetRange() * plugin.getTargetRange()
                        || guard.getLocation().distanceSquared(target.getLocation())
                            > plugin.getTargetRange() * plugin.getTargetRange()
                        || isForbiddenTarget(guard, target)) continue;
                assignCombatTarget(data, guard, target);
                commanded++;
            } catch (RuntimeException failure) {
                reportFailure("role-command-target", data.getGuardId(), failure);
            }
        }
        return commanded;
    }

    private Entity findLoadedEntityForRead(GuardData data) {
        if (data == null) return null;
        Entity entity = Bukkit.getEntity(data.getGuardId());
        if (entity != null && entity.isValid()) return entity;
        // The global UUID index can lag behind an already loaded chunk. Inspect
        // only the recorded chunk here; display and commands must not load it.
        SavedPosition last = data.getSavedLast();
        if (last == null || last.worldId() == null) return null;
        World world = Bukkit.getWorld(last.worldId());
        if (world == null) return null;
        int x = (int) Math.floor(last.x()) >> 4;
        int z = (int) Math.floor(last.z()) >> 4;
        if (!world.isChunkLoaded(x, z)) return null;
        for (Entity candidate : world.getChunkAt(x, z).getEntities()) {
            if (data.getGuardId().equals(candidate.getUniqueId()) && candidate.isValid()) {
                return candidate;
            }
        }
        return null;
    }

    private boolean isEntityConsistent(GuardData data, Entity entity) {
        if (data == null || entity == null || !data.getGuardId().equals(entity.getUniqueId())
                || entity.getType() != data.getMobType() || !(entity instanceof Mob mob)) {
            return false;
        }
        PersistentDataContainer pdc = mob.getPersistentDataContainer();
        return isMarked(pdc)
                && data.getOwnerId().equals(parseUuid(getString(pdc, keys.owner())))
                && data.getGuardId().equals(parseUuid(getString(pdc, keys.guardUuid())))
                && data.getMobType().name().equalsIgnoreCase(getString(pdc, keys.mobType()))
                && isProtectionPdcConsistent(data, pdc);
    }

    /** Missing role keys are repaired from YAML; contradictory present keys are unsafe. */
    private boolean isProtectionPdcConsistent(GuardData data, PersistentDataContainer pdc) {
        Set<NamespacedKey> present = pdc.getKeys();
        boolean hasKind = present.contains(keys.protectionKind());
        boolean hasRole = present.contains(keys.roleId());
        boolean hasSelected = present.contains(keys.selectedTargetUuid());
        boolean hasRevision = present.contains(keys.selectionRevision());
        if (!hasKind) {
            return !hasRole && !hasSelected && !hasRevision;
        }
        if (!pdc.has(keys.protectionKind(), PersistentDataType.STRING)) return false;
        String kindText = getString(pdc, keys.protectionKind());
        if (kindText == null || kindText.isBlank()) return false;
        GuardData.ProtectionKind kind;
        try {
            kind = GuardData.ProtectionKind.valueOf(kindText.trim().toUpperCase(
                    java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return false;
        }
        if (kind != data.getProtectionKind()) return false;
        if (kind == GuardData.ProtectionKind.ROLE) {
            if (!hasRole || !pdc.has(keys.roleId(), PersistentDataType.STRING)
                    || !Objects.equals(data.getRoleId(), getString(pdc, keys.roleId()))) {
                return false;
            }
            if (hasSelected && !pdc.has(keys.selectedTargetUuid(), PersistentDataType.STRING)) {
                return false;
            }
            if (hasRevision && !pdc.has(keys.selectionRevision(), PersistentDataType.LONG)) {
                return false;
            }
        } else if (hasRole || hasSelected || hasRevision) {
            return false;
        }
        String selectedText = getString(pdc, keys.selectedTargetUuid());
        if (hasSelected && (selectedText == null || selectedText.isBlank())) return false;
        UUID selected = parseUuid(selectedText);
        if (selectedText != null && selected == null) return false;
        if (!Objects.equals(data.getSelectedTargetUuid(), selected)) return false;
        Long revision = pdc.get(keys.selectionRevision(), PersistentDataType.LONG);
        return revision == null || revision.longValue() == data.getSelectionRevision();
    }

    public boolean isForbiddenTarget(Mob guard, LivingEntity target) {
        GuardData guardData = getGuardData(guard);
        if (guardData == null || target == null) {
            return false;
        }
        if (target.getUniqueId().equals(guardData.getOwnerId()) && !plugin.guardsCanDamageOwner()) {
            return true;
        }
        if (target instanceof Player player && isSelectedProtectionTarget(guardData, player)) {
            return true;
        }
        if (target instanceof Player && !plugin.shouldDefendAgainstPlayers()) {
            return true;
        }
        if (target instanceof Player player && isFriend(guardData.getOwnerId(), player.getUniqueId())) {
            return true;
        }
        GuardData targetData = getGuardData(target);
        return targetData != null
                && targetData.getOwnerId().equals(guardData.getOwnerId())
                && !plugin.guardsCanDamageEachOther();
    }

    public int commandGuardsToTarget(UUID ownerId, LivingEntity target, boolean defense) {
        if (ownerId == null || target == null || !EntityUtil.isAlive(target)) {
            return 0;
        }
        Player owner = Bukkit.getPlayer(ownerId);
        if (owner == null || !EntityUtil.isAlive(owner)) {
            return 0;
        }
        if (target.getUniqueId().equals(ownerId)
                || (target instanceof Player player && isFriend(ownerId, player.getUniqueId()))
                || (target instanceof Player && !plugin.shouldDefendAgainstPlayers())) {
            return 0;
        }
        GuardData targetData = getGuardData(target);
        if (targetData != null && ownerId.equals(targetData.getOwnerId())) {
            return 0;
        }
        if (!LocationUtil.sameWorld(owner.getLocation(), target.getLocation())
                || owner.getLocation().distanceSquared(target.getLocation())
                > plugin.getTargetRange() * plugin.getTargetRange()) {
            return 0;
        }

        int commanded = 0;
        for (GuardData data : getGuards(ownerId)) {
            try {
                if (!data.getTactics().policy().allowsCommand(defense)) continue;
                if (data.isRoleProtection()) {
                    continue;
                }
                if (defense && data.getMode() == GuardMode.STAY && !plugin.stayGuardsDefendOwner()) {
                    continue;
                }
                Mob guard = getLoadedMob(data);
                if (guard == null || !LocationUtil.sameWorld(guard.getLocation(), target.getLocation())
                        || isForbiddenTarget(guard, target)) {
                    continue;
                }
                assignCombatTarget(data, guard, target);
                commanded++;
            } catch (RuntimeException failure) {
                reportFailure("command-target", data.getGuardId(), failure);
            }
        }
        return commanded;
    }

    public boolean setMode(GuardData data, Mob mob, GuardMode mode) {
        return setMode(data, mob, mode, null);
    }

    public boolean setMode(GuardData data, Mob mob, GuardMode mode, Location guardPoint) {
        if (data == null || mob == null || mode == null) {
            return false;
        }
        GuardMode previousMode = data.getMode();
        GuardTactics previousTactics = data.getTactics();
        SavedPosition previousAnchor = data.getSavedAnchor();
        if (previousTactics.patrol()) {
            data.setTactics(previousTactics.stopped());
        }
        data.setMode(mode);
        data.clearCombat();
        Location anchor;
        if (mode == GuardMode.FOLLOW) {
            anchor = null;
        } else if (mode == GuardMode.GUARD && data.isRoleProtection()) {
            Player target = refreshProtectionTarget(data, mob);
            anchor = target == null ? null : target.getLocation();
        } else {
            anchor = mode == GuardMode.GUARD && guardPoint != null
                    ? guardPoint : mob.getLocation();
        }
        data.setAnchorLocation(mode == GuardMode.FOLLOW ? null : anchor);
        mob.setTarget(null);
        mob.setAware(true);
        applyPdc(mob, data);
        dirty = true;
        if (!persistNow()) {
            data.setMode(previousMode);
            data.setTactics(previousTactics);
            data.setSavedPositions(previousAnchor, data.getSavedLast());
            applyPdc(mob, data);
            dirty = true;
            return false;
        }
        plugin.playGuardFeedback(mob, switch (mode) {
            case FOLLOW -> GuardFeedback.MODE_FOLLOW;
            case STAY -> GuardFeedback.MODE_STAY;
            case GUARD -> GuardFeedback.MODE_GUARD;
        });
        return true;
    }

    public boolean rename(GuardData data, Mob mob, String name) {
        if (data == null || mob == null || name == null || name.isBlank()) {
            return false;
        }
        String previous = data.getName();
        data.setName(name);
        applyPdc(mob, data);
        configureGuard(mob, data);
        dirty = true;
        if (persistNow()) return true;
        data.setName(previous);
        applyPdc(mob, data);
        configureGuard(mob, data);
        dirty = true;
        return false;
    }

    /** Renames exactly one currently loaded guard after rechecking owner and UUID. */
    public boolean renameGuard(UUID ownerId, UUID guardId, String name) {
        GuardData data = getGuardData(guardId);
        if (data == null || ownerId == null || !ownerId.equals(data.getOwnerId())
                || name == null || name.isBlank()) {
            return false;
        }
        Mob mob = getLoadedMob(data);
        if (mob == null || !owns(ownerId, mob)) {
            return false;
        }
        return rename(data, mob, name);
    }

    public record RecallResult(int moved, boolean saved) {}

    public RecallResult teleportGuards(Player owner) {
        if (owner == null) {
            return new RecallResult(0, true);
        }
        int teleported = 0;
        int position = 0;
        for (GuardData data : getGuards(owner.getUniqueId())) {
            try {
                if (teleportGuard(owner.getUniqueId(), data.getGuardId(), position++)) {
                    teleported++;
                }
            } catch (RuntimeException failure) {
                reportFailure("teleport", data.getGuardId(), failure);
            }
        }
        if (teleported > 0) {
            try {
                if (!save()) {
                    reportFailure("teleport-save", owner.getUniqueId(),
                            new IllegalStateException("呼び戻し後の保存結果を確認できません"));
                    return new RecallResult(teleported, false);
                }
            } catch (RuntimeException failure) {
                reportFailure("teleport-save", owner.getUniqueId(), failure);
                return new RecallResult(teleported, false);
            }
        }
        return new RecallResult(teleported, true);
    }

    public int healGuards(Player owner) {
        if (owner == null) {
            return 0;
        }
        int healed = 0;
        for (GuardData data : getGuards(owner.getUniqueId())) {
            try {
                Mob guard = getLoadedMob(data);
                if (guard == null) {
                    continue;
                }
                AttributeInstance maxHealth = guard.getAttribute(Attribute.MAX_HEALTH);
                if (maxHealth == null) {
                    continue;
                }
                double maximum = maxHealth.getValue();
                if (!Double.isFinite(maximum) || maximum <= 0.0 || guard.getHealth() >= maximum) {
                    continue;
                }
                guard.setHealth(maximum);
                if (guard.getHealth() >= maximum) {
                    healed++;
                    plugin.playGuardFeedback(guard, GuardFeedback.HEAL);
                }
            } catch (IllegalArgumentException ignored) {
                // The entity may have changed state during this synchronous operation.
            } catch (RuntimeException failure) {
                reportFailure("heal", data.getGuardId(), failure);
            }
        }
        return healed;
    }

    /** Returns 1 when health increased, 0 when already full, and -1 when unavailable. */
    public int healGuard(UUID ownerId, UUID guardId) {
        GuardData data = getGuardData(guardId);
        if (data == null || ownerId == null || !ownerId.equals(data.getOwnerId())) {
            return -1;
        }
        Mob guard = getLoadedMob(data);
        if (guard == null) {
            return -1;
        }
        AttributeInstance maxHealth = guard.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth == null) {
            return -1;
        }
        double maximum = maxHealth.getValue();
        if (!Double.isFinite(maximum) || maximum <= 0.0 || guard.getHealth() >= maximum) {
            return 0;
        }
        try {
            guard.setHealth(maximum);
            if (guard.getHealth() > 0.0 && guard.getHealth() >= maximum) {
                plugin.playGuardFeedback(guard, GuardFeedback.HEAL);
                return 1;
            }
            return -1;
        } catch (IllegalArgumentException ignored) {
            return -1;
        }
    }

    /** Teleports exactly one loaded guard using the same safe-position rules as /bg tp. */
    public boolean teleportGuard(UUID ownerId, UUID guardId) {
        boolean moved = teleportGuard(ownerId, guardId,
                guardId == null ? 0 : Math.floorMod(guardId.hashCode(), 13));
        if (moved) {
            try {
                if (!save()) {
                    reportFailure("teleport-save", guardId,
                            new IllegalStateException("呼び戻し後の保存結果を確認できません"));
                    return false;
                }
            } catch (RuntimeException failure) {
                reportFailure("teleport-save", guardId, failure);
                return false;
            }
        }
        return moved;
    }

    private boolean teleportGuard(UUID ownerId, UUID guardId, int preferredIndex) {
        GuardData data = getGuardData(guardId);
        if (data == null || ownerId == null || !ownerId.equals(data.getOwnerId())) {
            return false;
        }
        Player owner = Bukkit.getPlayer(ownerId);
        Mob guard = getLoadedMob(data);
        if (owner == null || !EntityUtil.isAlive(owner) || guard == null) {
            return false;
        }
        Location destination = LocationUtil.findSafeLocation(owner.getLocation(), preferredIndex, guard);
        if (destination == null || !guard.teleport(destination)) {
            return false;
        }
        if (data.getTactics().patrol()) {
            data.setTactics(data.getTactics().stopped());
            data.setMode(GuardMode.GUARD);
        }
        data.clearCombat();
        guard.setTarget(null);
        Location arrived = guard.getLocation();
        data.setLastLocation(arrived);
        if (data.getMode() != GuardMode.FOLLOW) {
            data.setAnchorLocation(arrived);
        }
        applyPdc(guard, data);
        dirty = true;
        plugin.playGuardFeedback(guard, GuardFeedback.RECALL);
        return true;
    }

    public boolean releaseGuard(GuardData data, Mob mob) {
        return releaseGuard(data, mob, true);
    }

    private boolean releaseGuard(GuardData data, Mob mob, boolean saveImmediately) {
        if (data == null) return false;
        ReleaseResult result = releaseGuards(data.getOwnerId(), List.of(data.getGuardId()));
        return result.released() > 0;
    }
    public ReleaseResult releaseAll(UUID ownerId) {
        List<UUID> guardIds = getGuards(ownerId).stream().map(GuardData::getGuardId).toList();
        return releaseGuards(ownerId, guardIds);
    }

    /** Deletes an owner's UUID snapshot without force-loading any chunks. */
    public DeleteResult deleteGuards(UUID ownerId, Collection<UUID> guardIds) {
        if (ownerId == null || guardIds == null || guardIds.isEmpty()) {
            return new DeleteResult(0, 0, 0);
        }
        return deleteGuardsInternal(ownerId, guardIds);
    }

    /** Deletes the supplied server-wide UUID snapshot, regardless of owner. */
    public DeleteResult deleteGuardsGlobally(Collection<UUID> guardIds) {
        if (guardIds == null || guardIds.isEmpty()) {
            return new DeleteResult(0, 0, 0);
        }
        return deleteGuardsInternal(null, guardIds);
    }

    private DeleteResult deleteGuardsInternal(UUID expectedOwner, Collection<UUID> guardIds) {
        int deleted = 0;
        int queued = 0;
        int failed = 0;
        for (UUID guardId : new LinkedHashSet<>(guardIds)) {
            GuardData data = getGuardData(guardId);
            if (data == null || (expectedOwner != null && !expectedOwner.equals(data.getOwnerId()))) {
                failed++;
                continue;
            }
            if (data.isDeletionPending()) {
                queued++;
                continue;
            }
            if (!data.isActiveContract() || !acceptOperation(data, GuardData.OperationType.DELETE)) {
                failed++;
                continue;
            }
            try {
                data.clearCombat();
                if (!clearCompanion(data)) {
                    queued++;
                    continue;
                }
                Entity entity = findLoadedEntity(data);
                if (entity instanceof Mob mob) {
                    if (finalizePendingDeletion(data, mob)) deleted++;
                    else queued++;
                } else {
                    queued++;
                }
            } catch (RuntimeException failure) {
                plugin.getLogger().log(java.util.logging.Level.WARNING,
                        "護衛の削除は保存済みです。再読み込み時に再試行します: " + guardId, failure);
                queued++;
            }
        }
        return new DeleteResult(deleted, queued, failed);
    }
    public DeleteResult deleteAll(UUID ownerId) {
        List<UUID> guardIds = getGuards(ownerId).stream().map(GuardData::getGuardId).toList();
        return deleteGuards(ownerId, guardIds);
    }

    public DeleteResult deleteAllGlobally() {
        List<UUID> guardIds = guards.values().stream().map(GuardData::getGuardId).toList();
        return deleteGuardsGlobally(guardIds);
    }

    public GuardData removeGuard(Entity entity) {
        if (entity == null) {
            return null;
        }
        return removeGuard(entity.getUniqueId());
    }

    /**
     * Removes a guard whose death was confirmed by an EntityDeathEvent. The UUID
     * registry is used directly because another plugin may alter PDC during the
     * same death event before this listener runs.
     */
    public GuardData removeGuard(UUID guardId) {
        if (guardId == null) {
            return null;
        }
        GuardData data = guards.get(guardId);
        if (data == null) {
            return null;
        }
        if (!data.isActiveContract() || !acceptOperation(data, GuardData.OperationType.DEATH)) return null;
        clearCompanion(data);
        completeOperation(data);
        return data;
    }

    public void cleanup() {
        for (GuardData data : new ArrayList<>(guards.values())) {
            try {
                if (data.isQuarantined()) {
                    searchOffsets.remove(data.getGuardId());
                    continue;
                }
                if (data.isRetired() && data.isOperationCompleted()) {
                    searchOffsets.remove(data.getGuardId());
                    continue;
                }
                Entity entity = findLoadedEntity(data);
                if (entity instanceof Mob mob && entity.isValid() && !entity.isDead()) {
                    if (data.isDeletionPending()) finalizePendingDeletion(data, mob);
                    else if (data.isReleasePending()) finalizePendingRelease(data, mob);
                    else {
                        if (!isEntityConsistent(data, mob)) {
                            quarantine(data, "定期整合処理でEntityと保存データが一致しません");
                            continue;
                        }
                        data.setLastLocation(entity.getLocation());
                        data.observed();
                        dirty = true;
                    }
                }
                if (!data.isActiveContract() || data.isOperationCompleted()) {
                    searchOffsets.remove(data.getGuardId());
                }
            } catch (RuntimeException failure) {
                reportFailure("cleanup", data.getGuardId(), failure);
            }
        }
    }
    public void handleChunkLoad(Chunk chunk) {
        if (chunk == null) {
            return;
        }
        boolean changed = false;
        try {
            for (Entity entity : chunk.getEntities()) {
                if (entity == null) continue;
                try {
                    boolean wasDirty = dirty;
                    trackLoadedEntity(entity);
                    if (!wasDirty && dirty) changed = true;
                } catch (RuntimeException failure) {
                    reportFailure("chunk-load", entity.getUniqueId(), failure);
                }
            }
        } catch (RuntimeException failure) {
            reportFailure("chunk-load-enumeration", null, failure);
        }
        if (changed) {
            try {
                save();
            } catch (RuntimeException failure) {
                reportFailure("chunk-load-save", null, failure);
            }
        }
    }

    /** Reconciles entities after servers finish the entity-loading phase of a chunk. */
    public void handleEntitiesLoad(Chunk chunk, Collection<Entity> entities) {
        if (chunk == null || entities == null) {
            return;
        }
        boolean changed = false;
        Set<UUID> loadedIds = new java.util.HashSet<>();
        for (Entity entity : entities) {
            if (entity == null) continue;
            loadedIds.add(entity.getUniqueId());
            try {
                boolean wasDirty = dirty;
                trackLoadedEntity(entity);
                if (!wasDirty && dirty) {
                    changed = true;
                }
            } catch (RuntimeException failure) {
                reportFailure("entities-load", entity.getUniqueId(), failure);
            }
        }
        // Only the entity-loading event is evidence that this chunk's entities
        // have been enumerated. An empty collection is meaningful here.
        UUID chunkWorldId = chunk.getWorld() == null ? null : chunk.getWorld().getUID();
        for (GuardData data : new ArrayList<>(guards.values())) {
            try {
                if (data.isRetired()) continue;
                SavedPosition last = data.getSavedLast();
                if (last == null || chunkWorldId == null || !chunkWorldId.equals(last.worldId())
                        || ((int) Math.floor(last.x()) >> 4) != chunk.getX()
                        || ((int) Math.floor(last.z()) >> 4) != chunk.getZ()) continue;
                if (!loadedIds.contains(data.getGuardId())) {
                    data.markMissingObservation(System.currentTimeMillis());
                    dirty = true;
                    changed = true;
                }
            } catch (RuntimeException failure) {
                reportFailure("entities-load-observation", data.getGuardId(), failure);
            }
        }
        if (changed) {
            try {
                save();
            } catch (RuntimeException failure) {
                reportFailure("entities-load-save", null, failure);
            }
        }
    }

    /**
     * Resolves a loaded guard defensively. Bukkit's global UUID index can be
     * temporarily unavailable while entities are attached to a loaded chunk, so
     * also use the world index and nearby already-loaded chunks. This method never
     * force-loads chunks.
     */
    private Entity findLoadedEntity(GuardData data) {
        if (data == null) {
            return null;
        }
        UUID guardId = data.getGuardId();
        Entity entity = Bukkit.getEntity(guardId);
        if (entity != null && entity.isValid()) {
            return entity;
        }

        SavedPosition savedLast = data.getSavedLast();
        World world = savedLast == null || savedLast.worldId() == null
                ? null : Bukkit.getWorld(savedLast.worldId());
        if (world == null) {
            return null;
        }
        int centerX = (int) Math.floor(savedLast.x()) >> 4;
        int centerZ = (int) Math.floor(savedLast.z()) >> 4;
        int offset = searchOffsets.getOrDefault(guardId, 0);
        int checked = 0;
        int diameter = MISSING_SEARCH_CHUNK_RADIUS * 2 + 1;
        int area = diameter * diameter;
        while (checked < area && searchBudget > 0) {
            int index = (offset + checked) % area;
            int x = centerX + index / diameter - MISSING_SEARCH_CHUNK_RADIUS;
            int z = centerZ + index % diameter - MISSING_SEARCH_CHUNK_RADIUS;
            checked++;
            searchBudget--;
            searchOffsets.put(guardId, (index + 1) % area);
            if (!world.isChunkLoaded(x, z)) continue;
            for (Entity candidate : world.getChunkAt(x, z).getEntities()) {
                if (guardId.equals(candidate.getUniqueId())) {
                    searchOffsets.remove(guardId);
                    return candidate;
                }
            }
        }
        return null;
    }

    public void handleChunkUnload(Chunk chunk) {
        if (chunk == null) {
            return;
        }
        try {
            for (Entity entity : chunk.getEntities()) {
                if (entity == null) continue;
                try {
                    GuardData data = guards.get(entity.getUniqueId());
                    if (data != null && data.isActiveContract()) {
                        data.setLastLocation(entity.getLocation());
                        data.markUnloaded();
                        dirty = true;
                    }
                } catch (RuntimeException failure) {
                    reportFailure("chunk-unload", entity.getUniqueId(), failure);
                }
            }
        } catch (RuntimeException failure) {
            reportFailure("chunk-unload-enumeration", null, failure);
        }
    }

    public void freezeOwner(UUID ownerId) {
        if (!plugin.freezeOfflineGuards()) {
            return;
        }
        for (GuardData data : getGuards(ownerId)) {
            try {
                Mob mob = getLoadedMob(data);
                if (mob == null) {
                    continue;
                }
                data.clearCombat();
                mob.setTarget(null);
                mob.setAware(false);
                data.setOfflineFrozen(true);
            } catch (RuntimeException failure) {
                reportFailure("freeze-owner", data.getGuardId(), failure);
            }
        }
        try {
            updateManagedChunks();
        } catch (RuntimeException failure) {
            reportFailure("freeze-chunks", ownerId, failure);
        }
    }

    public void resumeOwner(UUID ownerId) {
        try {
            updateManagedChunks();
        } catch (RuntimeException failure) {
            reportFailure("resume-chunks", ownerId, failure);
        }
        for (GuardData data : getGuards(ownerId)) {
            try {
                Mob mob = getLoadedMob(data);
                if (mob == null) {
                    continue;
                }
                if (data.isOfflineFrozen()) {
                    mob.setAware(true);
                    data.setOfflineFrozen(false);
                }
            } catch (RuntimeException failure) {
                reportFailure("resume-owner", data.getGuardId(), failure);
            }
        }
    }

    /**
     * Keeps every guard belonging to an online owner loaded and therefore fully
     * addressable. Tickets follow the saved guard chunk and are released as soon
     * as the owner logs out or the guard record is removed.
     */
    public void updateManagedChunks() {
        searchBudget = plugin.getSearchChunksPerCycle();
        chunkWaitReasons.clear();
        if (!plugin.keepGuardChunksLoaded()) {
            releaseManagedChunks();
            return;
        }

        Set<GuardChunk> candidates = new LinkedHashSet<>();
        Map<UUID, Set<GuardChunk>> perOwner = new LinkedHashMap<>();
        Map<UUID, List<GuardData>> byOwner = new LinkedHashMap<>();
        Map<UUID, GuardChunk> requestedChunks = new LinkedHashMap<>();
        for (GuardData data : guards.values()) {
            try {
                Player owner = Bukkit.getPlayer(data.getOwnerId());
                GuardData.Status currentStatus = status(data);
                if (owner != null && owner.isOnline() && data.isActiveContract()
                        && currentStatus != GuardData.Status.QUARANTINED
                        && !data.isQuarantined()) {
                    byOwner.computeIfAbsent(data.getOwnerId(), ignored -> new ArrayList<>()).add(data);
                }
            } catch (RuntimeException failure) {
                reportFailure("chunk-plan", data.getGuardId(), failure);
            }
        }
        List<UUID> owners = new ArrayList<>(byOwner.keySet());
        if (!owners.isEmpty()) {
            int ownerStart = Math.floorMod(ownerCursor++, owners.size());
            int maxRounds = byOwner.values().stream().mapToInt(List::size).max().orElse(0);
            for (int round = 0; round < maxRounds; round++) {
                for (int ownerIndex = 0; ownerIndex < owners.size(); ownerIndex++) {
                    UUID ownerId = owners.get((ownerStart + ownerIndex) % owners.size());
                    List<GuardData> ownerGuards = byOwner.get(ownerId);
                    if (round >= ownerGuards.size()) continue;
                    GuardData data = ownerGuards.get(round);
                    try {
                        Entity loaded = Bukkit.getEntity(data.getGuardId());
                        Location location = loaded == null ? resolveSavedLocation(data.getSavedLast())
                                : loaded.getLocation();
                        if (location == null || location.getWorld() == null) continue;
                        GuardChunk requested = new GuardChunk(location.getWorld().getUID(),
                                location.getBlockX() >> 4, location.getBlockZ() >> 4);
                        Set<GuardChunk> ownerChunks = perOwner.computeIfAbsent(ownerId,
                                ignored -> new LinkedHashSet<>());
                        if (ownerChunks.contains(requested) || ownerChunks.size() < plugin.getOwnerChunkLimit()) {
                            ownerChunks.add(requested);
                            candidates.add(requested);
                            requestedChunks.put(data.getGuardId(), requested);
                        } else {
                            chunkWaitReasons.put(data.getGuardId(), ChunkWaitReason.OWNER_LIMIT);
                        }
                    } catch (RuntimeException failure) {
                        reportFailure("chunk-plan-owner", data.getGuardId(), failure);
                    }
                }
            }
        }

        // Keep valid tickets before filling free capacity. Rotating the selected
        // subset every cycle caused repeated chunk unloads when at the limit.
        Set<GuardChunk> desired = new LinkedHashSet<>();
        int limit = plugin.getManagedChunkLimit();
        for (GuardChunk key : managedChunks) {
            if (desired.size() >= limit) break;
            if (candidates.contains(key)) desired.add(key);
        }
        for (GuardChunk key : candidates) {
            if (desired.size() >= limit) break;
            desired.add(key);
        }
        for (Map.Entry<UUID, GuardChunk> request : requestedChunks.entrySet()) {
            chunkWaitReasons.put(request.getKey(), desired.contains(request.getValue())
                    ? ChunkWaitReason.SCHEDULED : ChunkWaitReason.SERVER_LIMIT);
        }

        // Return obsolete tickets before checking capacity for new ones.
        for (GuardChunk key : new ArrayList<>(managedChunks)) {
            if (desired.contains(key)) continue;
            boolean released = false;
            try {
                World world = Bukkit.getWorld(key.worldId());
                if (world != null && world.isChunkLoaded(key.x(), key.z())) {
                    world.getChunkAt(key.x(), key.z()).removePluginChunkTicket(plugin);
                }
                released = true;
            } catch (RuntimeException failure) {
                reportFailure("release-obsolete-chunk", key.worldId(), failure);
            }
            if (released) managedChunks.remove(key);
        }

        int loads = 0;
        for (GuardChunk key : desired) {
            if (managedChunks.contains(key)) {
                continue;
            }
            World world = Bukkit.getWorld(key.worldId());
            if (world == null) {
                continue;
            }
            if (loads >= plugin.getChunkLoadsPerCycle()) break;
            loads++;
            try {
                Chunk chunk = world.getChunkAt(key.x(), key.z());
                if (!chunk.addPluginChunkTicket(plugin)) continue;
                managedChunks.add(key);
                // A synchronously loaded chunk can already contain its entities before
                // EntitiesLoadEvent reaches this plugin, so reconcile it immediately.
                for (Entity entity : chunk.getEntities()) {
                    if (entity == null) continue;
                    try {
                        trackLoadedEntity(entity);
                    } catch (RuntimeException failure) {
                        reportFailure("managed-chunk-reconcile", entity.getUniqueId(), failure);
                    }
                }
            } catch (RuntimeException failure) {
                reportFailure("managed-chunk-load", null, failure);
            }
        }

    }

    private Location resolveSavedLocation(SavedPosition saved) {
        if (saved == null || saved.worldId() == null) return null;
        World world = Bukkit.getWorld(saved.worldId());
        return world == null ? null : new Location(world, saved.x(), saved.y(), saved.z(),
                saved.yaw(), saved.pitch());
    }

    /** Releases all plugin chunk tickets during shutdown or configuration changes. */
    public void releaseManagedChunks() {
        for (GuardChunk key : new ArrayList<>(managedChunks)) {
            try {
                World world = Bukkit.getWorld(key.worldId());
                if (world != null && world.isChunkLoaded(key.x(), key.z())) {
                    world.getChunkAt(key.x(), key.z()).removePluginChunkTicket(plugin);
                }
            } catch (RuntimeException failure) {
                reportFailure("release-chunk", key.worldId(), failure);
            }
        }
        managedChunks.clear();
    }

    public void refreshLoadedGuard(Mob mob, GuardData data) {
        if (mob != null && data != null) {
            configureGuard(mob, data);
        }
    }

    public void assignCombatTarget(GuardData data, Mob mob, LivingEntity target) {
        data.setCombatTargetId(target.getUniqueId());
        data.markCombat(plugin.getCombatGraceMillis());
        mob.setAware(true);
        mob.setTarget(target);
        // Respect another plugin's cancellation or replacement of this command.
        if (!target.equals(mob.getTarget())) {
            data.clearCombatTarget();
        }
    }

    /** Only retain loaded, reachable-world targets within the configured pursuit range. */
    public LivingEntity getCombatTarget(Mob mob, GuardData data) {
        UUID targetId = data.getCombatTargetId();
        if (targetId == null) {
            return null;
        }
        Entity entity = Bukkit.getEntity(targetId);
        if (!(entity instanceof LivingEntity target) || !EntityUtil.isAlive(target)
                || isForbiddenTarget(mob, target)
                || LocationUtil.distanceSquared(mob.getLocation(), target.getLocation())
                > plugin.getTargetRange() * plugin.getTargetRange()
                || (target instanceof Player player
                    && (player.getGameMode() == org.bukkit.GameMode.CREATIVE
                        || player.getGameMode() == org.bukkit.GameMode.SPECTATOR))) {
            data.clearCombat();
            return null;
        }
        return target;
    }

    private void configureGuard(Mob mob, GuardData data) {
        mob.setPersistent(true);
        mob.setRemoveWhenFarAway(false);
        mob.setCustomName(plugin.guardNameplate(data, mob, isCompanion(data)));
        mob.setCustomNameVisible(plugin.showNames());
        if (isForbiddenTarget(mob, mob.getTarget())) {
            mob.setTarget(null);
        }
    }

    private void applyPdc(Mob mob, GuardData data) {
        PersistentDataContainer pdc = mob.getPersistentDataContainer();
        pdc.set(keys.marker(), PersistentDataType.BYTE, (byte) 1);
        pdc.set(keys.owner(), PersistentDataType.STRING, data.getOwnerId().toString());
        pdc.set(keys.guardUuid(), PersistentDataType.STRING, data.getGuardId().toString());
        pdc.set(keys.mobType(), PersistentDataType.STRING, data.getMobType().name());
        pdc.set(keys.mode(), PersistentDataType.STRING, data.getMode().commandName());
        pdc.set(keys.name(), PersistentDataType.STRING, data.getName());
        pdc.set(keys.nameNumber(), PersistentDataType.INTEGER, data.getNameNumber());
        pdc.set(keys.favorite(), PersistentDataType.BYTE, (byte) (data.isFavorite() ? 1 : 0));
        pdc.set(keys.contractGeneration(), PersistentDataType.LONG, data.getContractGeneration());
        pdc.set(keys.protectionKind(), PersistentDataType.STRING, data.getProtectionKind().name());
        if (data.isRoleProtection()) {
            pdc.set(keys.roleId(), PersistentDataType.STRING, data.getRoleId());
            if (data.getSelectedTargetUuid() == null) {
                pdc.remove(keys.selectedTargetUuid());
            } else {
                pdc.set(keys.selectedTargetUuid(), PersistentDataType.STRING,
                        data.getSelectedTargetUuid().toString());
            }
            pdc.set(keys.selectionRevision(), PersistentDataType.LONG, data.getSelectionRevision());
        } else {
            pdc.remove(keys.roleId());
            pdc.remove(keys.selectedTargetUuid());
            pdc.remove(keys.selectionRevision());
        }

        SavedPosition anchor = data.getSavedAnchor();
        if (anchor == null) {
            pdc.remove(keys.anchorWorld());
            pdc.remove(keys.anchorWorldUuid());
            pdc.remove(keys.anchorX());
            pdc.remove(keys.anchorY());
            pdc.remove(keys.anchorZ());
        } else {
            pdc.set(keys.anchorWorld(), PersistentDataType.STRING,
                    anchor.worldName() == null ? "" : anchor.worldName());
            if (anchor.worldId() == null) pdc.remove(keys.anchorWorldUuid());
            else pdc.set(keys.anchorWorldUuid(), PersistentDataType.STRING, anchor.worldId().toString());
            pdc.set(keys.anchorX(), PersistentDataType.DOUBLE, anchor.x());
            pdc.set(keys.anchorY(), PersistentDataType.DOUBLE, anchor.y());
            pdc.set(keys.anchorZ(), PersistentDataType.DOUBLE, anchor.z());
        }
    }

    private void saveOriginalSettings(Mob mob) {
        PersistentDataContainer pdc = mob.getPersistentDataContainer();
        pdc.set(keys.originalName(), PersistentDataType.STRING,
                mob.getCustomName() == null ? "" : mob.getCustomName());
        pdc.set(keys.originalNameVisible(), PersistentDataType.BYTE,
                (byte) (mob.isCustomNameVisible() ? 1 : 0));
        pdc.set(keys.originalRemoveWhenFarAway(), PersistentDataType.BYTE,
                (byte) (mob.getRemoveWhenFarAway() ? 1 : 0));
        pdc.set(keys.originalPersistent(), PersistentDataType.BYTE,
                (byte) (mob.isPersistent() ? 1 : 0));
        pdc.set(keys.originalAware(), PersistentDataType.BYTE,
                (byte) (mob.isAware() ? 1 : 0));
        Entity target = mob.getTarget();
        if (target == null) pdc.remove(keys.originalTarget());
        else pdc.set(keys.originalTarget(), PersistentDataType.STRING, target.getUniqueId().toString());
    }

    private void restoreOriginalSettings(Mob mob) {
        PersistentDataContainer pdc = mob.getPersistentDataContainer();
        String originalName = getString(pdc, keys.originalName());
        mob.setCustomName(originalName == null || originalName.isEmpty() ? null : originalName);
        Byte visible = pdc.get(keys.originalNameVisible(), PersistentDataType.BYTE);
        mob.setCustomNameVisible(visible != null && visible != 0);
        Byte removeFarAway = pdc.get(keys.originalRemoveWhenFarAway(), PersistentDataType.BYTE);
        if (removeFarAway != null) {
            mob.setRemoveWhenFarAway(removeFarAway != 0);
        }
        Byte persistent = pdc.get(keys.originalPersistent(), PersistentDataType.BYTE);
        if (persistent != null) {
            mob.setPersistent(persistent != 0);
        }
        Byte aware = pdc.get(keys.originalAware(), PersistentDataType.BYTE);
        if (aware != null) {
            mob.setAware(aware != 0);
        }
        String targetId = getString(pdc, keys.originalTarget());
        UUID parsedTargetId = targetId == null ? null : parseUuid(targetId);
        Entity target = parsedTargetId == null ? null : Bukkit.getEntity(parsedTargetId);
        mob.setTarget(target instanceof LivingEntity living && EntityUtil.isAlive(living) ? living : null);
    }

    private void clearPdc(Mob mob) {
        PersistentDataContainer pdc = mob.getPersistentDataContainer();
        pdc.remove(keys.marker());
        pdc.remove(keys.owner());
        pdc.remove(keys.guardUuid());
        pdc.remove(keys.mobType());
        pdc.remove(keys.mode());
        pdc.remove(keys.name());
        pdc.remove(keys.nameNumber());
        pdc.remove(keys.favorite());
        pdc.remove(keys.anchorWorld());
        pdc.remove(keys.anchorWorldUuid());
        pdc.remove(keys.anchorX());
        pdc.remove(keys.anchorY());
        pdc.remove(keys.anchorZ());
        pdc.remove(keys.originalName());
        pdc.remove(keys.originalNameVisible());
        pdc.remove(keys.originalRemoveWhenFarAway());
        pdc.remove(keys.originalPersistent());
        pdc.remove(keys.originalAware());
        pdc.remove(keys.originalTarget());
        pdc.remove(keys.contractGeneration());
        pdc.remove(keys.protectionKind());
        pdc.remove(keys.roleId());
        pdc.remove(keys.selectedTargetUuid());
        pdc.remove(keys.selectionRevision());
    }

    private boolean acceptOperation(GuardData data, GuardData.OperationType type) {
        if (data == null || !data.isActiveContract() || data.isQuarantined()
                || operationLedger == null) return false;
        UUID operationId = UUID.randomUUID();
        long acceptedAt = System.currentTimeMillis();
        if (!operationLedger.append(operationId, data.getContractGeneration(), data.getGuardId(),
                data.getOwnerId(), type, acceptedAt)) {
            data.recordOperationFailure("操作台帳へ受理記録を保存できません");
            dirty = true;
            return false;
        }
        data.beginOperation(operationId, type, acceptedAt);
        dirty = true;
        // If this fails, the ledger still re-applies the accepted pending
        // decision after restart. No entity has been touched yet.
        if (!persistNow()) {
            data.recordOperationFailure("護衛レジストリの保存結果を確認できません");
            dirty = true;
        }
        return true;
    }

    private boolean completeOperation(GuardData data) {
        if (data == null || data.getOperationId() == null || operationLedger == null) return false;
        long completedAt = System.currentTimeMillis();
        if (!operationLedger.complete(data.getOperationId(), completedAt)) {
            data.recordOperationFailure("操作台帳の完了記録を保存できません");
            dirty = true;
            return false;
        }
        data.completeOperation(completedAt);
        dirty = true;
        // The operation is safe to retry if the registry write is delayed; the
        // independent ledger already prevents a stale PDC from reviving it.
        return persistNow();
    }

    private boolean finalizePendingRelease(GuardData data, Mob mob) {
        if (data == null || mob == null) return false;
        if (!data.isReleasePending() && data.getContractStatus() != GuardData.ContractStatus.RELEASED) {
            return false;
        }
        boolean marked = isMarked(mob.getPersistentDataContainer());
        if (data.isReleasePending() && !marked) {
            quarantine(data, "解除待ちEntityのBodyGuard PDCがありません");
            return false;
        }
        if (marked && !validateRetiredEntity(data, mob)) {
            return false;
        }
        if (!marked) {
            // A completed release with no BodyGuard marker needs no further
            // entity mutation. A pending release cannot infer that completion.
            return data.getContractStatus() == GuardData.ContractStatus.RELEASED;
        }
        if (!hasOriginalSettings(mob.getPersistentDataContainer())) {
            quarantine(data, "解除時に元のMob設定スナップショットを確認できません");
            return false;
        }
        if (!clearCompanion(data)) return false;
        if (data.isReleasePending() || marked) {
            plugin.playGuardFeedback(mob, GuardFeedback.RELEASE);
            data.clearCombat();
            restoreOriginalSettings(mob);
            clearPdc(mob);
        }
        if (data.getContractStatus() == GuardData.ContractStatus.RELEASED) return true;
        return completeOperation(data);
    }

    private boolean hasOriginalSettings(PersistentDataContainer pdc) {
        return getString(pdc, keys.originalName()) != null
                && pdc.get(keys.originalNameVisible(), PersistentDataType.BYTE) != null
                && pdc.get(keys.originalRemoveWhenFarAway(), PersistentDataType.BYTE) != null
                && pdc.get(keys.originalPersistent(), PersistentDataType.BYTE) != null
                && pdc.get(keys.originalAware(), PersistentDataType.BYTE) != null;
    }

    private boolean finalizePendingDeletion(GuardData data, Mob mob) {
        if (data == null || mob == null) return false;
        boolean marked = isMarked(mob.getPersistentDataContainer());
        if (!marked) {
            // Never remove an entity merely because its UUID matches a retired
            // record when the identifying BodyGuard marker is absent.
            if (data.getContractStatus() == GuardData.ContractStatus.DELETE_PENDING) {
                quarantine(data, "削除待ちEntityのBodyGuard PDCがありません");
                return false;
            }
            return data.getContractStatus() != GuardData.ContractStatus.DELETE_PENDING;
        }
        if (!validateRetiredEntity(data, mob)) return false;
        if (data.getContractStatus() != GuardData.ContractStatus.DELETED) {
            if (!clearCompanion(data)) return false;
            data.clearCombat();
            mob.remove();
            return completeOperation(data);
        }
        // A completed delete must not be revived by a stale marked entity that
        // survived a world or registry rollback.
        mob.remove();
        return true;
    }

    private boolean clearCompanion(GuardData data) {
        if (!isCompanion(data)) {
            return true;
        }
        if (playerDataStorage.setCompanion(data.getOwnerId(), null)
                != SafeYamlFile.SaveResult.SUCCESS) {
            data.recordOperationFailure("相棒設定の保存に失敗しました");
            dirty = true;
            return false;
        }
        Player owner = Bukkit.getPlayer(data.getOwnerId());
        if (owner != null && owner.isOnline()) {
            plugin.getMessages().send(owner, "companion-cleared",
                    "&e{name}がいなくなったため、相棒設定を解除しました。",
                    Map.of("name", data.getName()));
        }
        return true;
    }

    public record ReleaseResult(int released, int queued, int failed) {
        public int affected() {
            return released + queued;
        }
    }


    public record DeleteResult(int deleted, int queued, int failed) {
        public int affected() {
            return deleted + queued;
        }
    }

    public DiagnosticSnapshot diagnostics(UUID ownerId) {
        int active = 0;
        int available = 0;
        int unloaded = 0;
        int worldUnavailable = 0;
        int checking = 0;
        int missing = 0;
        int quarantined = 0;
        int releasePending = 0;
        int deletePending = 0;
        int completed = 0;
        for (GuardData data : guards.values()) {
            if (ownerId != null && !ownerId.equals(data.getOwnerId())) continue;
            if (data.isActiveContract()) {
                active++;
                switch (status(data)) {
                    case AVAILABLE -> available++;
                    case UNLOADED -> unloaded++;
                    case WORLD_UNAVAILABLE -> worldUnavailable++;
                    case CHECKING -> checking++;
                    case MISSING -> missing++;
                    case QUARANTINED -> quarantined++;
                    default -> { }
                }
            } else {
                switch (data.getContractStatus()) {
                    case RELEASE_PENDING -> releasePending++;
                    case DELETE_PENDING -> deletePending++;
                    case RELEASED, DELETED, DEAD -> completed++;
                    case ACTIVE -> { }
                }
            }
        }
        return new DiagnosticSnapshot(active, available, unloaded, worldUnavailable,
                checking, missing, quarantined, releasePending, deletePending, completed,
                managedChunks.size(), plugin.getManagedChunkLimit(),
                storage.getLastSaveResult(), storage.getLastSaved(),
                storage.getLastFailureReason(),
                playerDataStorage.getLastSaveResult(), playerDataStorage.getLastFailureReason(),
                operationLedger == null ? SafeYamlFile.SaveResult.SUCCESS : operationLedger.getLastSaveResult(),
                operationLedger == null ? null : operationLedger.getLastFailureReason(),
                operationLedger == null ? 0 : operationLedger.size(),
                lastCycleDurationNanos, lastRegistrySaveDurationNanos);
    }

    public record DiagnosticSnapshot(int active, int available, int unloaded,
                                     int worldUnavailable, int checking, int missing,
                                     int quarantined, int releasePending, int deletePending,
                                     int completed, int managedChunks, int maxManagedChunks,
                                     SafeYamlFile.SaveResult lastSaveResult, long lastSaved,
                                     String lastFailureReason,
                                     SafeYamlFile.SaveResult playerSaveResult,
                                     String playerFailureReason,
                                     SafeYamlFile.SaveResult ledgerSaveResult,
                                     String ledgerFailureReason,
                                     int ledgerEntries, long lastCycleDurationNanos,
                                     long lastRegistrySaveDurationNanos) {
    }

    private int nextNameNumber(UUID ownerId, EntityType type) {
        int highest = 0;
        for (GuardData data : getGuards(ownerId)) {
            if (data.getMobType() == type) {
                highest = Math.max(highest, data.getNameNumber());
            }
        }
        return Math.max(1, highest + 1);
    }

    private String createDefaultName(String ownerName, EntityType type, int number) {
        return plugin.color(plugin.getDefaultNameTemplate()
                .replace("{owner}", ownerName == null ? "Player" : ownerName)
                .replace("{mob}", EntityUtil.prettyMobName(type))
                .replace("{mob_name}", plugin.getMobDisplayName(type))
                .replace("{number}", String.format(java.util.Locale.ROOT, "%02d", Math.max(1, number))));
    }

    private boolean isMarked(PersistentDataContainer pdc) {
        Byte marker = pdc.get(keys.marker(), PersistentDataType.BYTE);
        return marker != null && marker != 0;
    }

    private String getString(PersistentDataContainer pdc, NamespacedKey key) {
        return pdc.get(key, PersistentDataType.STRING);
    }

    private UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private record GuardChunk(UUID worldId, int x, int z) {
    }
}
