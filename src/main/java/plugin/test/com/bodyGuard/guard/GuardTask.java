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

    public GuardTask(BodyGuard plugin, GuardManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public void run() {
        executions++;
        for (GuardData data : manager.getAllGuardData()) {
            Mob mob = manager.getLoadedMob(data);
            if (mob == null) {
                continue;
            }

            data.setLastLocation(mob.getLocation());
            manager.refreshLoadedGuard(mob, data);

            Player owner = org.bukkit.Bukkit.getPlayer(data.getOwnerId());
            if (owner == null) {
                if (plugin.freezeOfflineGuards()) {
                    freeze(mob, data);
                    continue;
                }
                if (data.isOfflineFrozen()) {
                    mob.setAware(true);
                    data.setOfflineFrozen(false);
                }
            } else if (data.isOfflineFrozen()) {
                mob.setAware(true);
                data.setOfflineFrozen(false);
            }

            LivingEntity target = validateCurrentTarget(mob, data);
            switch (data.getMode()) {
                case FOLLOW -> tickFollow(data, mob, owner, target);
                case STAY -> tickStay(data, mob, target);
                case GUARD -> tickGuard(data, mob, target);
            }
        }

        if (executions % 20L == 0L) {
            manager.cleanup();
        }
        if (executions % 12L == 0L) {
            manager.save();
        }
    }

    private LivingEntity validateCurrentTarget(Mob mob, GuardData data) {
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
            anchor = mob.getLocation();
            data.setAnchorLocation(anchor);
        }
        if (target != null && data.isInCombat()) {
            // Owner-defense and owner-assist targets may temporarily leave the patrol radius.
            // They are allowed to finish combat; the guard returns after the target disappears.
            return;
        }

        mob.setAware(true);

        LivingEntity nearest = findNearestHostile(mob, anchor, plugin.getGuardRadius());
        if (nearest != null) {
            mob.setTarget(nearest);
            data.markCombat(plugin.getCombatGraceMillis());
            return;
        }

        if (!LocationUtil.sameWorld(anchor, mob.getLocation())) {
            teleportToAnchor(data, mob, anchor);
            return;
        }
        double distanceSquared = mob.getLocation().distanceSquared(anchor);
        double radius = plugin.getGuardRadius();
        double returnDistance = Math.max(plugin.getGuardReturnDistance(), radius * 2.0);
        if (distanceSquared >= returnDistance * returnDistance) {
            teleportToAnchor(data, mob, anchor);
        } else if (distanceSquared > radius * radius) {
            LocationUtil.moveToward(mob, anchor, plugin.getFollowMoveSpeed());
        } else {
            LocationUtil.stopHorizontal(mob);
        }
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
                ownerLocation, Math.floorMod(data.getGuardId().hashCode(), 13));
        if (destination == null || !mob.teleport(destination)) {
            return;
        }
        mob.setTarget(null);
        data.setLastLocation(destination);
    }

    private void teleportToAnchor(GuardData data, Mob mob, Location anchor) {
        Location destination = LocationUtil.findSafeLocation(
                anchor, Math.floorMod(data.getGuardId().hashCode(), 13));
        if (destination == null || !mob.teleport(destination)) {
            return;
        }
        mob.setTarget(null);
        data.setLastLocation(destination);
    }

    private void freeze(Mob mob, GuardData data) {
        mob.setTarget(null);
        mob.setAware(false);
        data.setOfflineFrozen(true);
    }

}
