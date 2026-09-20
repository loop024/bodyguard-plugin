package plugin.test.com.bodyGuard.guard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import plugin.test.com.bodyGuard.BodyGuard;
import plugin.test.com.bodyGuard.util.EntityUtil;
import plugin.test.com.bodyGuard.util.LocationUtil;

/** One low-frequency task for all registered guards; no per-guard tasks are created. */
public final class GuardTask extends BukkitRunnable {

    private final BodyGuard plugin;
    private final GuardManager manager;
    private long executions;
    private int nextGuardIndex;

    public GuardTask(BodyGuard plugin, GuardManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public void run() {
        long started = System.nanoTime();
        executions++;
        try { manager.updateManagedChunks(); }
        catch (RuntimeException failure) { manager.reportFailure("chunks", null, failure); }
        List<GuardData> guards = new ArrayList<>(manager.getAllGuardData());
        guards.removeIf(GuardData::isRetired);
        int limit = Math.min(guards.size(), plugin.getMaxGuardsPerCycle());
        for (int processed = 0; processed < limit; processed++) {
            GuardData data = guards.get(nextGuardIndex % guards.size());
            nextGuardIndex = (nextGuardIndex + 1) % guards.size();
            if (!manager.shouldRetry("tick", data.getGuardId())) continue;
            try {
                tickGuard(data);
                manager.clearFailure("tick", data.getGuardId());
            } catch (RuntimeException failure) {
                manager.reportFailure("tick", data.getGuardId(), failure);
            }
        }
        if (executions % 20L == 0L) {
            try { manager.cleanup(); }
            catch (RuntimeException failure) { manager.reportFailure("cleanup", null, failure); }
        }
        long autosaveTicks = plugin.getAutosaveIntervalTicks();
        long executionsPerAutosave = Math.max(1L, autosaveTicks / 10L);
        if (autosaveTicks > 0L && executions % executionsPerAutosave == 0L) {
            try {
                manager.save();
            } catch (RuntimeException failure) {
                manager.reportFailure("autosave", null, failure);
            }
        }
        // Failed writes need a retry even when periodic autosave is disabled.
        if (executions % 60L == 0L) {
            try {
                manager.retryFailedSaves();
            } catch (RuntimeException failure) {
                manager.reportFailure("save-retry", null, failure);
            }
        }
        manager.recordCycleDuration(System.nanoTime() - started);
    }

    private void tickGuard(GuardData data) {

            Mob mob = manager.getLoadedMob(data);
            if (mob == null) {
                return;
            }

            if (data.updateLastLocation(mob.getLocation(), 0.25)) {
                manager.markDirty();
            }
            manager.refreshLoadedGuard(mob, data);

            Player owner = org.bukkit.Bukkit.getPlayer(data.getOwnerId());
            if (owner == null) {
                if (plugin.freezeOfflineGuards()) {
                    freeze(mob, data);
                    return;
                }
                if (data.isOfflineFrozen()) {
                    mob.setAware(true);
                    data.setOfflineFrozen(false);
                }
            } else if (data.isOfflineFrozen()) {
                mob.setAware(true);
                data.setOfflineFrozen(false);
            }

            if (data.isRoleProtection()) {
                Player protectedTarget = manager.refreshProtectionTarget(data, mob);
                if (protectedTarget == null) {
                    data.clearCombat();
                    mob.setTarget(null);
                    LocationUtil.stopHorizontal(mob);
                    return;
                }
                // Target selection may clear combat and the mob's target.
                // Validate only after that change so this tick uses the current target.
                LivingEntity target = validateCurrentTarget(mob, data);
                switch (data.getMode()) {
                    case FOLLOW -> tickRoleFollow(data, mob, protectedTarget, target);
                    case STAY -> {
                        manager.setProtectionRuntimeState(data,
                                GuardData.ProtectionState.ACTIVE, null);
                        tickStay(data, mob, target);
                    }
                    case GUARD -> tickRoleGuard(data, mob, protectedTarget, target);
                }
                return;
            }
            LivingEntity target = validateCurrentTarget(mob, data);
            switch (data.getMode()) {
                case FOLLOW -> tickFollow(data, mob, owner, target);
                case STAY -> tickStay(data, mob, target);
                case GUARD -> tickGuard(data, mob, target);
            }
    }

    private LivingEntity validateCurrentTarget(Mob mob, GuardData data) {
        java.util.UUID previousTargetId = data.getCombatTargetId();
        LivingEntity commanded = manager.getCombatTarget(mob, data);
        if (commanded != null) {
            mob.setAware(true);
            if (!commanded.equals(mob.getTarget())) {
                manager.assignCombatTarget(data, mob, commanded);
            }
        } else if (previousTargetId != null) {
            mob.setTarget(null);
        }
        LivingEntity target = mob.getTarget();
        if (target == null || !EntityUtil.isAlive(target)) {
            if (target != null) {
                mob.setTarget(null);
            }
            return null;
        }
        if (!LocationUtil.sameWorld(mob.getLocation(), target.getLocation())
                || manager.isForbiddenTarget(mob, target)) {
            mob.setTarget(null);
            return null;
        }
        data.markCombat(plugin.getCombatGraceMillis());
        mob.setAware(true);
        return target;
    }

    private void tickFollow(GuardData data, Mob mob, Player owner, LivingEntity target) {
        if (owner == null) {
            LocationUtil.stopHorizontal(mob);
            return;
        }

        Location ownerLocation = owner.getLocation();
        Location guardLocation = mob.getLocation();
        if (!LocationUtil.sameWorld(guardLocation, ownerLocation)) {
            if (plugin.shouldTeleportDifferentWorld()) {
                teleportNearOwner(data, mob, ownerLocation);
            } else {
                mob.setTarget(null);
                LocationUtil.stopHorizontal(mob);
            }
            return;
        }

        double distanceSquared = guardLocation.distanceSquared(ownerLocation);
        double teleportDistance = Math.min(
                plugin.getFollowTeleportDistance(), plugin.getTeleportMaxDistance());
        boolean activeCombat = target != null && data.isInCombat();
        if (distanceSquared >= teleportDistance * teleportDistance && !activeCombat) {
            teleportNearOwner(data, mob, ownerLocation);
            return;
        }

        double startDistance = plugin.getFollowStartDistance();
        if (distanceSquared >= startDistance * startDistance && !activeCombat) {
            LocationUtil.moveToward(mob, ownerLocation, plugin.getFollowMoveSpeed());
        } else if (!activeCombat) {
            LocationUtil.stopHorizontal(mob);
        }
    }

    private void tickRoleFollow(GuardData data, Mob mob, Player protectedTarget,
                                LivingEntity combatTarget) {
        Location targetLocation = protectedTarget.getLocation();
        if (!LocationUtil.sameWorld(mob.getLocation(), targetLocation)) {
            manager.setProtectionRuntimeState(data,
                    GuardData.ProtectionState.WORLD_TRANSFER_PENDING,
                    plugin.shouldTeleportDifferentWorld()
                            ? "対象ワールドへの移動を待機しています"
                            : "別ワールド移動は設定で無効です");
            data.clearCombat();
            mob.setTarget(null);
            LocationUtil.stopHorizontal(mob);
            if (plugin.shouldTeleportDifferentWorld()) {
                teleportNearTarget(data, mob, targetLocation);
            }
            return;
        }

        manager.setProtectionRuntimeState(data, GuardData.ProtectionState.ACTIVE, null);
        double distanceSquared = mob.getLocation().distanceSquared(targetLocation);
        double teleportDistance = Math.min(
                plugin.getFollowTeleportDistance(), plugin.getTeleportMaxDistance());
        boolean activeCombat = combatTarget != null && data.isInCombat();
        if (distanceSquared >= teleportDistance * teleportDistance && !activeCombat) {
            teleportNearTarget(data, mob, targetLocation);
            return;
        }
        double startDistance = plugin.getFollowStartDistance();
        if (distanceSquared >= startDistance * startDistance && !activeCombat) {
            LocationUtil.moveToward(mob, targetLocation, plugin.getFollowMoveSpeed());
        } else if (!activeCombat) {
            LocationUtil.stopHorizontal(mob);
        }
    }

    private void tickStay(GuardData data, Mob mob, LivingEntity target) {
        if (target != null && data.isInCombat()) {
            mob.setAware(true);
            return;
        }
        // Spigot exposes awareness but not a public pathfinder. Disabling awareness while
        // idle keeps stay guards at their post without replacing their combat AI permanently.
        mob.setAware(false);
        if (!plugin.stayReturnsToPosition()) {
            LocationUtil.stopHorizontal(mob);
            return;
        }
        Location anchor = data.getAnchorLocation();
        if (anchor == null || !LocationUtil.sameWorld(anchor, mob.getLocation())) {
            LocationUtil.stopHorizontal(mob);
            return;
        }
        double distanceSquared = mob.getLocation().distanceSquared(anchor);
        double returnDistance = plugin.getStayReturnDistance();
        if (distanceSquared >= returnDistance * returnDistance) {
            teleportToAnchor(data, mob, anchor);
        } else if (distanceSquared > 2.25) {
            LocationUtil.moveToward(mob, anchor, plugin.getFollowMoveSpeed());
        } else {
            LocationUtil.stopHorizontal(mob);
        }
    }

    private void tickGuard(GuardData data, Mob mob, LivingEntity target) {
        Location anchor = data.getAnchorLocation();
        if (anchor == null) {
            if (data.getSavedAnchor() != null) {
                // Keep an unresolved world/position intact. Replacing it with
                // the current entity location would silently lose the patrol
                // point and could attach the guard to the wrong world.
                mob.setTarget(null);
                LocationUtil.stopHorizontal(mob);
                return;
            }
            anchor = mob.getLocation();
            data.setAnchorLocation(anchor);
            manager.markDirty();
        }
        mob.setAware(true);

        if (!LocationUtil.sameWorld(anchor, mob.getLocation())) {
            teleportToAnchor(data, mob, anchor);
            return;
        }

        double distanceSquared = mob.getLocation().distanceSquared(anchor);
        double radius = plugin.getGuardRadius();
        double returnDistance = Math.max(plugin.getGuardReturnDistance(), radius + 2.0);

        // Returning to the assigned guard point takes priority over combat. Check
        // the teleport leash before any active-target handling so even a guard in
        // the middle of a fight is recalled as soon as it reaches this distance.
        if (distanceSquared >= returnDistance * returnDistance) {
            teleportToAnchor(data, mob, anchor);
            return;
        }

        // The patrol radius is a hard combat leash. Once crossed, abandon even an
        // owner-assist target so a guard cannot keep chasing indefinitely.
        if (distanceSquared > radius * radius) {
            data.clearCombat();
            mob.setTarget(null);
            LocationUtil.moveToward(mob, anchor, plugin.getFollowMoveSpeed());
            return;
        }

        if (target != null && data.isInCombat()) {
            return;
        }

        LivingEntity nearest = findNearestHostile(mob, anchor, plugin.getGuardRadius());
        if (nearest != null) {
            manager.assignCombatTarget(data, mob, nearest);
            return;
        }

        LocationUtil.stopHorizontal(mob);
    }

    private void tickRoleGuard(GuardData data, Mob mob, Player protectedTarget,
                               LivingEntity combatTarget) {
        Location targetLocation = protectedTarget.getLocation();
        if (!LocationUtil.sameWorld(mob.getLocation(), targetLocation)) {
            manager.setProtectionRuntimeState(data,
                    GuardData.ProtectionState.WORLD_TRANSFER_PENDING,
                    plugin.shouldTeleportDifferentWorld()
                            ? "対象ワールドへの移動を待機しています"
                            : "別ワールド移動は設定で無効です");
            data.clearCombat();
            mob.setTarget(null);
            LocationUtil.stopHorizontal(mob);
            if (plugin.shouldTeleportDifferentWorld()) {
                teleportNearTarget(data, mob, targetLocation);
            }
            return;
        }

        manager.updateRolePatrolAnchor(data, mob, targetLocation);
        manager.setProtectionRuntimeState(data, GuardData.ProtectionState.ACTIVE, null);
        Location anchor = data.getAnchorLocation();
        if (anchor == null || !LocationUtil.sameWorld(anchor, mob.getLocation())) {
            data.clearCombat();
            mob.setTarget(null);
            LocationUtil.stopHorizontal(mob);
            return;
        }

        mob.setAware(true);
        double distanceSquared = mob.getLocation().distanceSquared(anchor);
        double radius = plugin.getGuardRadius();
        double returnDistance = Math.max(plugin.getGuardReturnDistance(), radius + 2.0);
        if (distanceSquared >= returnDistance * returnDistance) {
            teleportToAnchor(data, mob, anchor);
            return;
        }
        if (distanceSquared > radius * radius) {
            data.clearCombat();
            mob.setTarget(null);
            LocationUtil.moveToward(mob, anchor, plugin.getFollowMoveSpeed());
            return;
        }
        if (combatTarget != null && data.isInCombat()) return;

        LivingEntity nearest = findNearestHostile(mob, anchor, radius);
        if (nearest != null) {
            manager.assignCombatTarget(data, mob, nearest);
            return;
        }
        LocationUtil.stopHorizontal(mob);
    }

    private LivingEntity findNearestHostile(Mob guard, Location anchor, double radius) {
        List<LivingEntity> candidates = new ArrayList<>();
        for (org.bukkit.entity.Entity nearby : guard.getNearbyEntities(radius, radius, radius)) {
            if (!(nearby instanceof Monster monster)
                    || monster == guard
                    || manager.isGuard(nearby)
                    || !EntityUtil.isAlive(nearby)
                    || !LocationUtil.sameWorld(anchor, nearby.getLocation())
                    || anchor.distanceSquared(nearby.getLocation()) > radius * radius
                    || manager.isForbiddenTarget(guard, monster)) {
                continue;
            }
            candidates.add(monster);
        }
        return candidates.stream()
                .min(Comparator.comparingDouble(entity -> guard.getLocation().distanceSquared(entity.getLocation())))
                .orElse(null);
    }

    private void teleportNearOwner(GuardData data, Mob mob, Location ownerLocation) {
        Location destination = LocationUtil.findSafeLocation(
                ownerLocation, Math.floorMod(data.getGuardId().hashCode(), 13), mob);
        if (destination == null || !mob.teleport(destination)) {
            return;
        }
        data.clearCombat();
        mob.setTarget(null);
        data.setLastLocation(destination);
        manager.markDirty();
    }

    private void teleportNearTarget(GuardData data, Mob mob, Location targetLocation) {
        if (targetLocation == null || targetLocation.getWorld() == null) return;
        Location destination = LocationUtil.findSafeLocation(
                targetLocation, Math.floorMod(data.getGuardId().hashCode(), 13), mob);
        if (destination == null || !mob.teleport(destination)) {
            manager.setProtectionRuntimeState(data, GuardData.ProtectionState.WAITING,
                    "対象ワールドの安全地点が見つかりません");
            manager.reportFailure("role-transfer", data.getGuardId(),
                    new IllegalStateException("役職対象付近の安全地点への移動に失敗しました"));
            return;
        }
        data.clearCombat();
        mob.setTarget(null);
        data.setLastLocation(destination);
        if (data.getMode() == GuardMode.GUARD) {
            manager.updateRolePatrolAnchor(data, mob, targetLocation);
        }
        manager.setProtectionRuntimeState(data, GuardData.ProtectionState.ACTIVE, null);
        manager.markDirty();
    }

    private void teleportToAnchor(GuardData data, Mob mob, Location anchor) {
        Location destination = LocationUtil.findSafeLocation(
                anchor, Math.floorMod(data.getGuardId().hashCode(), 13), mob);
        if (destination == null || !mob.teleport(destination)) {
            return;
        }
        data.clearCombat();
        mob.setTarget(null);
        data.setLastLocation(destination);
        manager.markDirty();
    }

    private void freeze(Mob mob, GuardData data) {
        data.clearCombat();
        mob.setTarget(null);
        mob.setAware(false);
        data.setOfflineFrozen(true);
    }

}
