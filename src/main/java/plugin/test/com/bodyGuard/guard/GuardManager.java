package plugin.test.com.bodyGuard.guard;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private boolean dirty;
    private final Map<String, Long> failureWarnings = new LinkedHashMap<>();
    private final Map<String, Long> retryNotBefore = new LinkedHashMap<>();
    private final Map<UUID, Integer> searchOffsets = new LinkedHashMap<>();
    private int searchBudget = 32;

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
            operationLedger.applyTo(guards);
            if (guards.values().stream().anyMatch(GuardData::isQuarantined)) {
                dirty = true;
            }
        }
        for (Map.Entry<UUID, UUID> entry : playerDataStorage.getCompanions().entrySet()) {
            GuardData companion = guards.get(entry.getValue());
            if (companion == null || !entry.getKey().equals(companion.getOwnerId())) {
                playerDataStorage.setCompanion(entry.getKey(), null);
            }
        }
        // Ledger application or companion cleanup may have changed the in-memory
        // registry and must be persisted on the normal startup reconciliation path.
    }

    /** Saves only when persistent guard data changed since the previous successful save. */
    public void save() {
        playerDataStorage.retrySave();
        if (dirty) persistRegistry();
    }

    private boolean persistRegistry() {
        if (!dirty) return true;
        boolean saved = storage.saveWithResult(new ArrayList<>(guards.values()))
                == SafeYamlFile.SaveResult.SUCCESS;
        if (saved) {
            dirty = false;
        }
        return saved;
    }

    /** Retry failed writes independently of the configured autosave interval. */
    public void retryFailedSaves() {
        playerDataStorage.retrySave();
        if (dirty) persistRegistry();
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
        switch (data.getContractStatus()) {
            case RELEASE_PENDING -> { return GuardData.Status.RELEASE_PENDING; }
            case RELEASED -> { return GuardData.Status.RELEASED; }
            case DELETE_PENDING -> { return GuardData.Status.DELETE_PENDING; }
            case DELETED -> { return GuardData.Status.DELETED; }
            case DEAD -> { return GuardData.Status.DEAD; }
            case ACTIVE -> { }
        }
        if (data.isQuarantined()) return GuardData.Status.QUARANTINED;
        Entity entity = Bukkit.getEntity(data.getGuardId());
        if (entity != null) {
            if (isEntityConsistent(data, entity) && EntityUtil.isAlive(entity)) {
                return GuardData.Status.AVAILABLE;
            }
            return GuardData.Status.QUARANTINED;
        }
        SavedPosition savedLast = data.getSavedLast();
        if (savedLast == null || savedLast.worldId() == null) {
            return GuardData.Status.CHECKING;
        }
        World world = Bukkit.getWorld(savedLast.worldId());
        if (world == null) return GuardData.Status.WORLD_UNAVAILABLE;
        if (!world.isChunkLoaded(savedLast.x() < 0 ? ((int) Math.floor(savedLast.x())) >> 4
                : ((int) Math.floor(savedLast.x())) >> 4,
                savedLast.z() < 0 ? ((int) Math.floor(savedLast.z())) >> 4
                : ((int) Math.floor(savedLast.z())) >> 4)) {
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
    public void forceSave() {
        if (storage.saveWithResult(new ArrayList<>(guards.values()))
                == plugin.test.com.bodyGuard.storage.SafeYamlFile.SaveResult.SUCCESS) {
            dirty = false;
        }
    }

    /** Records a change made by the periodic entity-state synchronizer. */
    public void markDirty() {
        dirty = true;
    }

    public Collection<GuardData> getAllGuardData() {
        return new ArrayList<>(guards.values());
    }

    /** Recovers marked guards that were already loaded before plugin listeners started. */
    public int reconcileAlreadyLoadedEntities() {
        int recovered = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                for (Entity entity : chunk.getEntities()) {
                    boolean knownBefore = guards.containsKey(entity.getUniqueId());
                    GuardData tracked = trackLoadedEntity(entity);
                    if (tracked != null && !knownBefore) {
                        recovered++;
                    }
                }
            }
        }
        if (dirty) {
            save();
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
        if (previous != null && !previous.getOwnerId().equals(owner.getUniqueId())) return null;
        try {
            saveOriginalSettings(mob);
            data.observed();
            guards.put(data.getGuardId(), data);
            dirty = true;
            applyPdc(mob, data);
            mob.setAware(true);
            configureGuard(mob, data);
            if (!persistNow()) throw new IllegalStateException("護衛の登録を保存できませんでした。");
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
        PersistentDataContainer pdc = mob.getPersistentDataContainer();
        if (!isMarked(pdc)) {
            return null;
        }

        UUID ownerId = parseUuid(getString(pdc, keys.owner()));
        if (ownerId == null) {
            plugin.getLogger().warning("Ignoring BodyGuard with invalid owner UUID: " + entity.getUniqueId());
            return null;
        }

        UUID entityId = entity.getUniqueId();
        GuardData previous = guards.get(entityId);
        if (previous != null && previous.isDeletionPending()) {
            finalizePendingDeletion(previous, mob);
            return null;
        }
        if (previous != null && previous.isReleasePending()) {
            finalizePendingRelease(previous, mob);
            return null;
        }
        GuardMode mode = GuardMode.fromString(getString(pdc, keys.mode()));
        if (mode == null && previous != null) {
            mode = previous.getMode();
        }
        if (mode == null) {
            mode = GuardMode.FOLLOW;
        }

        String ownerName = previous == null ? null : previous.getOwnerName();
        if (ownerName == null || ownerName.isBlank()) {
            Player owner = Bukkit.getPlayer(ownerId);
            ownerName = owner == null ? "Player" : owner.getName();
        }
        Integer pdcNameNumber = pdc.get(keys.nameNumber(), PersistentDataType.INTEGER);
        int nameNumber = previous != null ? previous.getNameNumber()
                : pdcNameNumber == null ? 0 : Math.max(0, pdcNameNumber);
        String name = getString(pdc, keys.name());
        if (name == null || name.isBlank()) {
            if (previous != null) {
                name = previous.getName();
            } else {
                nameNumber = nameNumber > 0 ? nameNumber : nextNameNumber(ownerId, mob.getType());
                name = createDefaultName(ownerName, mob.getType(), nameNumber);
            }
        }

        Location anchor = previous == null ? readAnchorFromPdc(pdc) : previous.getAnchorLocation();
        if (anchor == null && mode != GuardMode.FOLLOW && (previous == null || previous.getSavedAnchor() == null)) {
            anchor = entity.getLocation();
        }
        GuardData data = new GuardData(
                entityId, ownerId, mob.getType(), mode, name, ownerName, anchor,
                entity.getLocation(), nameNumber);
        if (previous != null && previous.getSavedAnchor() != null && anchor == null) {
            data.setSavedPositions(previous.getSavedAnchor(), SavedPosition.of(entity.getLocation()));
        }
        data.observed();
        Byte pdcFavorite = pdc.get(keys.favorite(), PersistentDataType.BYTE);
        data.setFavorite(previous != null ? previous.isFavorite() : pdcFavorite != null && pdcFavorite != 0);
        guards.put(entityId, data);
        dirty = true;

        // The entity UUID is authoritative. This repairs an incomplete saved PDC entry.
        applyPdc(mob, data);
        configureGuard(mob, data);
        return data;
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
            // combat checks must never complete operations or rewrite the registry.
            if (data.isDeletionPending() || data.isReleasePending() || !isMarked(pdc)) return null;
            UUID markedOwner = parseUuid(getString(pdc, keys.owner()));
            return data.getOwnerId().equals(markedOwner) ? data : null;
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
        if (data == null || ownerId == null || !ownerId.equals(data.getOwnerId())) {
            return false;
        }
        data.setFavorite(!data.isFavorite());
        Mob mob = getLoadedMob(data);
        if (mob != null) {
            applyPdc(mob, data);
        }
        dirty = true;
        save();
        return true;
    }

    public boolean toggleCompanion(UUID ownerId, UUID guardId) {
        GuardData data = getGuardData(guardId);
        if (data == null || ownerId == null || !ownerId.equals(data.getOwnerId())) {
            return false;
        }
        UUID current = playerDataStorage.getCompanion(ownerId);
        playerDataStorage.setCompanion(ownerId, guardId.equals(current) ? null : guardId);
        return true;
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
        setMode(data, mob, mode, guardPoint);
        return true;
    }

    /** Releases one selected guard only when it is still owned by the caller and loaded. */
    public boolean releaseGuard(UUID ownerId, UUID guardId) {
        GuardData data = getGuardData(guardId);
        if (data == null || ownerId == null || !ownerId.equals(data.getOwnerId())) {
            return false;
        }
        Mob mob = getLoadedMob(data);
        if (mob == null || !owns(ownerId, mob)) {
            return false;
        }
        return releaseGuard(data, mob);
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
            if (data == null || !ownerId.equals(data.getOwnerId()) || data.isDeletionPending()) {
                failed++;
                continue;
            }
            boolean previous = data.isReleasePending();
            boolean previousCompleted = data.isOperationCompleted();
            data.setReleasePending(true);
            data.setOperationCompleted(false);
            dirty = true;
            if (!persistNow()) {
                data.setReleasePending(previous);
                data.setOperationCompleted(previousCompleted);
                failed++;
                continue;
            }
            try {
                clearCompanion(data);
                Entity entity = findLoadedEntity(data);
                if (entity instanceof Mob mob) {
                    finalizePendingRelease(data, mob);
                    released++;
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

    public Mob getLoadedMob(GuardData data) {
        if (data == null || data.isDeletionPending() || data.isReleasePending()) {
            return null;
        }
        Entity entity = findLoadedEntity(data);
        if (!(entity instanceof Mob mob) || !EntityUtil.isAlive(entity)) {
            return null;
        }
        GuardData actual = getGuardData(entity);
        return actual != null && data.getGuardId().equals(actual.getGuardId()) ? mob : null;
    }

    public boolean isForbiddenTarget(Mob guard, LivingEntity target) {
        GuardData guardData = getGuardData(guard);
        if (guardData == null || target == null) {
            return false;
        }
        if (target.getUniqueId().equals(guardData.getOwnerId()) && !plugin.guardsCanDamageOwner()) {
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
        }
        return commanded;
    }

    public void setMode(GuardData data, Mob mob, GuardMode mode) {
        setMode(data, mob, mode, null);
    }

    public void setMode(GuardData data, Mob mob, GuardMode mode, Location guardPoint) {
        if (data == null || mob == null || mode == null) {
            return;
        }
        data.setMode(mode);
        data.clearCombat();
        Location anchor = mode == GuardMode.GUARD && guardPoint != null
                ? guardPoint
                : mob.getLocation();
        data.setAnchorLocation(mode == GuardMode.FOLLOW ? null : anchor);
        mob.setTarget(null);
        mob.setAware(true);
        applyPdc(mob, data);
        dirty = true;
        save();
        plugin.playGuardFeedback(mob, switch (mode) {
            case FOLLOW -> GuardFeedback.MODE_FOLLOW;
            case STAY -> GuardFeedback.MODE_STAY;
            case GUARD -> GuardFeedback.MODE_GUARD;
        });
    }

    public void rename(GuardData data, Mob mob, String name) {
        if (data == null || mob == null || name == null || name.isBlank()) {
            return;
        }
        data.setName(name);
        applyPdc(mob, data);
        configureGuard(mob, data);
        dirty = true;
        save();
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
        rename(data, mob, name);
        return true;
    }

    public int teleportGuards(Player owner) {
        if (owner == null) {
            return 0;
        }
        int teleported = 0;
        int position = 0;
        for (GuardData data : getGuards(owner.getUniqueId())) {
            if (teleportGuard(owner.getUniqueId(), data.getGuardId(), position++)) {
                teleported++;
            }
        }
        if (teleported > 0) {
            save();
        }
        return teleported;
    }

    public int healGuards(Player owner) {
        if (owner == null) {
            return 0;
        }
        int healed = 0;
        for (GuardData data : getGuards(owner.getUniqueId())) {
            Mob guard = getLoadedMob(data);
            if (guard == null) {
                continue;
            }
            AttributeInstance maxHealth = guard.getAttribute(Attribute.MAX_HEALTH);
            if (maxHealth == null) {
                continue;
            }
            double maximum = maxHealth.getValue();
            if (guard.getHealth() >= maximum) {
                continue;
            }
            try {
                guard.setHealth(maximum);
                if (guard.getHealth() >= maximum) {
                    healed++;
                    plugin.playGuardFeedback(guard, GuardFeedback.HEAL);
                }
            } catch (IllegalArgumentException ignored) {
                // The entity may have changed state during this synchronous operation.
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
            save();
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
        data.clearCombat();
        guard.setTarget(null);
        data.setLastLocation(destination);
        if (data.getMode() != GuardMode.FOLLOW) {
            data.setAnchorLocation(destination);
        }
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
            boolean oldRelease = data.isReleasePending();
            boolean oldDeletion = data.isDeletionPending();
            boolean oldCompleted = data.isOperationCompleted();
            data.setReleasePending(false);
            data.setDeletionPending(true);
            data.setOperationCompleted(false);
            dirty = true;
            // Commit the tombstone before touching the entity. Never infer absence
            // from a loaded chunk: its entity-loading phase may not have finished.
            if (!persistNow()) {
                data.setReleasePending(oldRelease);
                data.setDeletionPending(oldDeletion);
                data.setOperationCompleted(oldCompleted);
                failed++;
                continue;
            }
            try {
                data.clearCombat();
                clearCompanion(data);
                Entity entity = findLoadedEntity(data);
                if (entity instanceof Mob mob) {
                    finalizePendingDeletion(data, mob);
                    deleted++;
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
    private boolean isLastKnownChunkLoaded(GuardData data) {
        Location last = data == null ? null : data.getLastLocation();
        World world = last == null ? null : last.getWorld();
        return world != null && world.isChunkLoaded(
                last.getBlockX() >> 4, last.getBlockZ() >> 4);
    }

    private void removeDeletedGuardRecord(GuardData data) {
        guards.remove(data.getGuardId());
        clearCompanion(data);
        dirty = true;
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
        if (data.isRetired()) return null;
        data.setDeathConfirmed(true);
        data.setDeletionPending(true);
        data.setOperationCompleted(true);
        clearCompanion(data);
        dirty = true;
        save();
        return data;
    }

    public void cleanup() {
        for (GuardData data : new ArrayList<>(guards.values())) {
            if (data.isRetired() && data.isOperationCompleted()) continue;
            Entity entity = findLoadedEntity(data);
            if (entity instanceof Mob mob && entity.isValid() && !entity.isDead()) {
                if (data.isDeletionPending()) finalizePendingDeletion(data, mob);
                else if (data.isReleasePending()) finalizePendingRelease(data, mob);
                else {
                    data.setLastLocation(entity.getLocation());
                    data.observed();
                    dirty = true;
                }
            }
        }
    }
    public void handleChunkLoad(Chunk chunk) {
        if (chunk == null) {
            return;
        }
        boolean changed = false;
        for (Entity entity : chunk.getEntities()) {
            int before = guards.size();
            trackLoadedEntity(entity);
            if (guards.size() != before) {
                dirty = true;
                changed = true;
            }
        }
        if (changed) {
            save();
        }
    }

    /** Reconciles entities after servers finish the entity-loading phase of a chunk. */
    public void handleEntitiesLoad(Chunk chunk, Collection<Entity> entities) {
        if (chunk == null || entities == null) {
            return;
        }
        boolean changed = false;
        for (Entity entity : entities) {
            int before = guards.size();
            trackLoadedEntity(entity);
            if (guards.size() != before) {
                changed = true;
            }
        }
        // Only the entity-loading event is evidence that this chunk's entities
        // have been enumerated. An empty collection is meaningful here.
        Set<UUID> loadedIds = new java.util.HashSet<>();
        for (Entity entity : entities) loadedIds.add(entity.getUniqueId());
        for (GuardData data : guards.values()) {
            if (data.isRetired() || data.getMissingSince() != 0) continue;
            Location last = data.getLastLocation();
            if (last == null || last.getWorld() != chunk.getWorld()
                    || (last.getBlockX() >> 4) != chunk.getX()
                    || (last.getBlockZ() >> 4) != chunk.getZ()) continue;
            if (!loadedIds.contains(data.getGuardId())) {
                data.setMissingSince(System.currentTimeMillis());
                dirty = true;
                changed = true;
            }
        }
        if (changed) {
            save();
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
        if (entity != null) {
            return entity;
        }

        Location last = data.getLastLocation();
        World world = last == null ? null : last.getWorld();
        if (world == null) {
            return null;
        }
        int centerX = last.getBlockX() >> 4;
        int centerZ = last.getBlockZ() >> 4;
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
                if (guardId.equals(candidate.getUniqueId())) return candidate;
            }
        }        return null;
    }

    public void handleChunkUnload(Chunk chunk) {
        if (chunk == null) {
            return;
        }
        for (Entity entity : chunk.getEntities()) {
            GuardData data = getGuardData(entity);
            if (data != null) {
                data.setLastLocation(entity.getLocation());
                data.setMissingSince(0);
                dirty = true;
            }
        }
    }

    public void freezeOwner(UUID ownerId) {
        if (!plugin.freezeOfflineGuards()) {
            return;
        }
        for (GuardData data : getGuards(ownerId)) {
            Mob mob = getLoadedMob(data);
            if (mob == null) {
                continue;
            }
            data.clearCombat();
            mob.setTarget(null);
            mob.setAware(false);
            data.setOfflineFrozen(true);
        }
        updateManagedChunks();
    }

    public void resumeOwner(UUID ownerId) {
        updateManagedChunks();
        for (GuardData data : getGuards(ownerId)) {
            Mob mob = getLoadedMob(data);
            if (mob == null) {
                continue;
            }
            if (data.isOfflineFrozen()) {
                mob.setAware(true);
                data.setOfflineFrozen(false);
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
        if (!plugin.keepGuardChunksLoaded()) {
            releaseManagedChunks();
            return;
        }

        Set<GuardChunk> desired = new LinkedHashSet<>();
        Map<UUID, Set<GuardChunk>> perOwner = new LinkedHashMap<>();
        for (GuardData data : guards.values()) {
            Player owner = Bukkit.getPlayer(data.getOwnerId());
            if (owner == null || !owner.isOnline() || data.isRetired()
                    || status(data) == GuardData.Status.MISSING) {
                continue;
            }
            Entity loaded = Bukkit.getEntity(data.getGuardId());
            Location location = loaded == null ? data.getLastLocation() : loaded.getLocation();
            if (location == null || location.getWorld() == null) {
                continue;
            }
            GuardChunk requested = new GuardChunk(location.getWorld().getUID(),
                    location.getBlockX() >> 4, location.getBlockZ() >> 4);
            Set<GuardChunk> ownerChunks = perOwner.computeIfAbsent(data.getOwnerId(), ignored -> new LinkedHashSet<>());
            if (ownerChunks.size() < plugin.getOwnerChunkLimit() && desired.size() < plugin.getManagedChunkLimit()) {
                ownerChunks.add(requested);
                desired.add(requested);
            }
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
            Chunk chunk = world.getChunkAt(key.x(), key.z());
            chunk.addPluginChunkTicket(plugin);
            managedChunks.add(key);
            // A synchronously loaded chunk can already contain its entities before
            // EntitiesLoadEvent reaches this plugin, so reconcile it immediately.
            for (Entity entity : chunk.getEntities()) {
                trackLoadedEntity(entity);
            }
        }

        for (GuardChunk key : new ArrayList<>(managedChunks)) {
            if (desired.contains(key)) {
                continue;
            }
            World world = Bukkit.getWorld(key.worldId());
            if (world != null && world.isChunkLoaded(key.x(), key.z())) {
                world.getChunkAt(key.x(), key.z()).removePluginChunkTicket(plugin);
            }
            managedChunks.remove(key);
        }
    }

    /** Releases all plugin chunk tickets during shutdown or configuration changes. */
    public void releaseManagedChunks() {
        for (GuardChunk key : new ArrayList<>(managedChunks)) {
            World world = Bukkit.getWorld(key.worldId());
            if (world != null && world.isChunkLoaded(key.x(), key.z())) {
                world.getChunkAt(key.x(), key.z()).removePluginChunkTicket(plugin);
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
            data.clearCombat();
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

        Location anchor = data.getAnchorLocation();
        if (anchor == null || anchor.getWorld() == null) {
            pdc.remove(keys.anchorWorld());
            pdc.remove(keys.anchorWorldUuid());
            pdc.remove(keys.anchorX());
            pdc.remove(keys.anchorY());
            pdc.remove(keys.anchorZ());
        } else {
            pdc.set(keys.anchorWorld(), PersistentDataType.STRING, anchor.getWorld().getName());
            pdc.set(keys.anchorWorldUuid(), PersistentDataType.STRING, anchor.getWorld().getUID().toString());
            pdc.set(keys.anchorX(), PersistentDataType.DOUBLE, anchor.getX());
            pdc.set(keys.anchorY(), PersistentDataType.DOUBLE, anchor.getY());
            pdc.set(keys.anchorZ(), PersistentDataType.DOUBLE, anchor.getZ());
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
        mob.setTarget(null);
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
    }

    private void finalizePendingRelease(GuardData data, Mob mob) {
        if (data == null || mob == null) {
            return;
        }
        if (data.isOperationCompleted() && !isMarked(mob.getPersistentDataContainer())) return;
        plugin.playGuardFeedback(mob, GuardFeedback.RELEASE);
        data.clearCombat();
        restoreOriginalSettings(mob);
        clearPdc(mob);
        data.setOperationCompleted(true);
        // Retain the persistent operation record after processing the entity.
        clearCompanion(data);
        dirty = true;
    }

    private void finalizePendingDeletion(GuardData data, Mob mob) {
        if (data == null || mob == null) {
            return;
        }
        data.clearCombat();
        mob.remove();
        data.setOperationCompleted(true);
        // Retain the persistent operation record after processing the entity.
        clearCompanion(data);
        dirty = true;
    }

    private void clearCompanion(GuardData data) {
        if (!isCompanion(data)) {
            return;
        }
        playerDataStorage.setCompanion(data.getOwnerId(), null);
        Player owner = Bukkit.getPlayer(data.getOwnerId());
        if (owner != null && owner.isOnline()) {
            plugin.getMessages().send(owner, "companion-cleared",
                    "&e{name}がいなくなったため、相棒設定を解除しました。",
                    Map.of("name", data.getName()));
        }
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

    private Location readAnchorFromPdc(PersistentDataContainer pdc) {
        String worldName = getString(pdc, keys.anchorWorld());
        UUID worldId = parseUuid(getString(pdc, keys.anchorWorldUuid()));
        Double x = pdc.get(keys.anchorX(), PersistentDataType.DOUBLE);
        Double y = pdc.get(keys.anchorY(), PersistentDataType.DOUBLE);
        Double z = pdc.get(keys.anchorZ(), PersistentDataType.DOUBLE);
        World world = worldId == null ? null : Bukkit.getWorld(worldId);
        if (world == null && worldName != null) {
            world = Bukkit.getWorld(worldName);
        }
        if (world == null || x == null || y == null || z == null
                || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            return null;
        }
        return new Location(world, x, y, z);
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
