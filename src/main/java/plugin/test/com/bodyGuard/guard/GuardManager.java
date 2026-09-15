package plugin.test.com.bodyGuard.guard;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import plugin.test.com.bodyGuard.storage.PlayerDataStorage;
import plugin.test.com.bodyGuard.util.EntityUtil;
import plugin.test.com.bodyGuard.util.LocationUtil;

/** Owns the registry and all BodyGuard metadata operations. */
public final class GuardManager {

    private static final long MISSING_CONFIRMATION_MILLIS = 5_000L;

    private final BodyGuard plugin;
    private final GuardStorage storage;
    private final NamespacedKeys keys;
    private final PlayerDataStorage playerDataStorage;
    private final Map<UUID, GuardData> guards = new LinkedHashMap<>();
    private final Map<UUID, Long> missingSince = new LinkedHashMap<>();
    private boolean dirty;

    public GuardManager(BodyGuard plugin, GuardStorage storage, NamespacedKeys keys,
                        PlayerDataStorage playerDataStorage) {
        this.plugin = plugin;
        this.storage = storage;
        this.keys = keys;
        this.playerDataStorage = playerDataStorage;
    }

    public void load(Map<UUID, GuardData> savedGuards) {
        guards.clear();
        missingSince.clear();
        if (savedGuards != null) {
            guards.putAll(savedGuards);
        }
        for (Map.Entry<UUID, UUID> entry : playerDataStorage.getCompanions().entrySet()) {
            GuardData companion = guards.get(entry.getValue());
            if (companion == null || !entry.getKey().equals(companion.getOwnerId())) {
                playerDataStorage.setCompanion(entry.getKey(), null);
            }
        }
        dirty = false;
    }

    /** Saves only when persistent guard data changed since the previous successful save. */
    public void save() {
        if (dirty && storage.save(new ArrayList<>(guards.values()))) {
            dirty = false;
        }
    }

    /** Persists the current registry even when no change was recorded, for plugin shutdown. */
    public void forceSave() {
        if (storage.save(new ArrayList<>(guards.values()))) {
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

    public List<GuardData> getGuards(UUID ownerId) {
        List<GuardData> result = new ArrayList<>();
        if (ownerId == null) {
            return result;
        }
        for (GuardData data : guards.values()) {
            // A deletion-pending entry is only an internal tombstone used to remove
            // an entity if its chunk is loaded later. It is no longer a usable guard
            // and must not occupy the player's list or guard limit.
            if (ownerId.equals(data.getOwnerId()) && !data.isDeletionPending()) {
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

        saveOriginalSettings(mob);
        guards.put(data.getGuardId(), data);
        dirty = true;
        applyPdc(mob, data);
        mob.setAware(true);
        configureGuard(mob, data);
        save();
        return data;
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
        if (anchor == null && mode != GuardMode.FOLLOW) {
            anchor = entity.getLocation();
        }
        GuardData data = new GuardData(
                entityId, ownerId, mob.getType(), mode, name, ownerName, anchor,
                entity.getLocation(), nameNumber);
        Byte pdcFavorite = pdc.get(keys.favorite(), PersistentDataType.BYTE);
        data.setFavorite(previous != null ? previous.isFavorite() : pdcFavorite != null && pdcFavorite != 0);
        guards.put(entityId, data);
        missingSince.remove(entityId);
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
            if (!isMarked(pdc)) {
                guards.remove(entity.getUniqueId());
                clearCompanion(data);
                dirty = true;
                return null;
            }
            if (data.isDeletionPending() && entity instanceof Mob mob) {
                finalizePendingDeletion(data, mob);
                return null;
            }
            if (data.isReleasePending() && entity instanceof Mob mob) {
                finalizePendingRelease(data, mob);
                return null;
            }
            return data;
        }
        return isMarked(pdc) ? trackLoadedEntity(entity) : null;
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
        if (ownerId == null || guardIds == null || guardIds.isEmpty()) {
            return new ReleaseResult(0, 0, 0);
        }
        int released = 0;
        int queued = 0;
        int failed = 0;
        for (UUID guardId : new ArrayList<>(guardIds)) {
            GuardData data = getGuardData(guardId);
            if (data == null || !ownerId.equals(data.getOwnerId())) {
                failed++;
                continue;
            }
            if (data.isDeletionPending()) {
                failed++;
                continue;
            }
            if (data.isReleasePending()) {
                Entity pendingEntity = Bukkit.getEntity(data.getGuardId());
                if (pendingEntity instanceof Mob pendingMob && EntityUtil.isAlive(pendingMob)) {
                    finalizePendingRelease(data, pendingMob);
                    released++;
                } else {
                    queued++;
                }
                continue;
            }
            Mob mob = getLoadedMob(data);
            if (mob == null || !owns(ownerId, mob)) {
                if (!data.isReleasePending()) {
                    data.setReleasePending(true);
                    dirty = true;
                }
                queued++;
                continue;
            }
            plugin.playGuardEffect(mob, false);
            if (releaseGuard(data, mob, false)) {
                released++;
            }
        }
        if (released > 0 || queued > 0) {
            save();
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
        if (data == null) {
            return null;
        }
        Entity entity = Bukkit.getEntity(data.getGuardId());
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
        Location destination = LocationUtil.findSafeLocation(owner.getLocation(), preferredIndex);
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
        if (data == null) {
            return false;
        }
        data.clearCombat();
        if (mob != null) {
            restoreOriginalSettings(mob);
            clearPdc(mob);
        }
        boolean removed = guards.remove(data.getGuardId()) != null;
        if (removed) {
            clearCompanion(data);
            dirty = true;
            if (saveImmediately) {
                save();
            }
        }
        return removed;
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
        for (UUID guardId : new ArrayList<>(guardIds)) {
            GuardData data = getGuardData(guardId);
            if (data == null || (expectedOwner != null && !expectedOwner.equals(data.getOwnerId()))) {
                failed++;
                continue;
            }
            Entity entity = Bukkit.getEntity(data.getGuardId());
            if (entity instanceof Mob mob && EntityUtil.isAlive(mob)) {
                plugin.playGuardEffect(mob, false);
                mob.remove();
                guards.remove(data.getGuardId());
                clearCompanion(data);
                dirty = true;
                deleted++;
                continue;
            }
            data.setReleasePending(false);
            data.setDeletionPending(true);
            data.clearCombat();
            clearCompanion(data);
            dirty = true;
            queued++;
        }
        if (deleted > 0 || queued > 0) {
            save();
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
        GuardData data = getGuardData(entity);
        if (data == null) {
            return null;
        }
        guards.remove(data.getGuardId());
        clearCompanion(data);
        dirty = true;
        save();
        return data;
    }

    public void cleanup() {
        boolean changed = false;
        for (GuardData data : new ArrayList<>(guards.values())) {
            Entity entity = Bukkit.getEntity(data.getGuardId());
            if (entity == null) {
                // A null lookup alone is ambiguous. It is safe to remove the record only
                // when the last known chunk is loaded and the UUID is still absent there.
                if (isDefinitelyMissing(data)) {
                    removeMissingRecord(data);
                    changed = true;
                }
                continue;
            }
            missingSince.remove(data.getGuardId());
            // A cross-world teleport can briefly expose the old entity wrapper as
            // invalid. The UUID lookup will become null or resolve to the new wrapper
            // on a later cleanup pass, so that transient state is not proof of loss.
            if (entity.isDead() || !entity.isValid()) {
                continue;
            }
            if (!(entity instanceof Mob) || getGuardData(entity) == null) {
                removeMissingRecord(data);
                changed = true;
            } else {
                data.setLastLocation(entity.getLocation());
            }
        }
        if (changed) {
            save();
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
        // Once this chunk is loaded, saved records whose last confirmed position is
        // inside it can be checked without guessing or force-loading another chunk.
        for (GuardData data : new ArrayList<>(guards.values())) {
            if (isLastKnownChunk(data, chunk) && isDefinitelyMissing(data)) {
                removeMissingRecord(data);
                changed = true;
            }
        }
        if (changed) {
            save();
        }
    }

    private boolean isDefinitelyMissing(GuardData data) {
        if (data == null) {
            return false;
        }
        UUID guardId = data.getGuardId();
        if (Bukkit.getEntity(guardId) != null) {
            missingSince.remove(guardId);
            return false;
        }
        Location last = data.getLastLocation();
        World world = last == null ? null : last.getWorld();
        if (world == null || !world.isChunkLoaded(last.getBlockX() >> 4, last.getBlockZ() >> 4)) {
            missingSince.remove(guardId);
            return false;
        }

        long now = System.currentTimeMillis();
        Long firstMissing = missingSince.putIfAbsent(guardId, now);
        return firstMissing != null && now - firstMissing >= MISSING_CONFIRMATION_MILLIS;
    }

    private boolean isLastKnownChunk(GuardData data, Chunk chunk) {
        Location last = data == null ? null : data.getLastLocation();
        return last != null && last.getWorld() != null
                && last.getWorld().getUID().equals(chunk.getWorld().getUID())
                && (last.getBlockX() >> 4) == chunk.getX()
                && (last.getBlockZ() >> 4) == chunk.getZ();
    }

    private void removeMissingRecord(GuardData data) {
        if (data == null || guards.remove(data.getGuardId()) == null) {
            return;
        }
        missingSince.remove(data.getGuardId());
        clearCompanion(data);
        dirty = true;
        Player owner = Bukkit.getPlayer(data.getOwnerId());
        if (owner != null && owner.isOnline()) {
            plugin.getMessages().send(owner, "guard-missing-removed",
                    "&e存在を確認できなくなった {name} を護衛一覧から整理しました。",
                    Map.of("name", data.getName()));
        }
    }

    public void handleChunkUnload(Chunk chunk) {
        if (chunk == null) {
            return;
        }
        for (Entity entity : chunk.getEntities()) {
            GuardData data = getGuardData(entity);
            if (data != null) {
                data.setLastLocation(entity.getLocation());
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
    }

    public void resumeOwner(UUID ownerId) {
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
        plugin.playGuardFeedback(mob, GuardFeedback.RELEASE);
        data.clearCombat();
        restoreOriginalSettings(mob);
        clearPdc(mob);
        guards.remove(data.getGuardId());
        clearCompanion(data);
        dirty = true;
    }

    private void finalizePendingDeletion(GuardData data, Mob mob) {
        if (data == null || mob == null) {
            return;
        }
        data.clearCombat();
        mob.remove();
        guards.remove(data.getGuardId());
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
}
