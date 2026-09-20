package plugin.test.com.bodyGuard.guard;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Mob;
import plugin.test.com.bodyGuard.BodyGuard;
import plugin.test.com.bodyGuard.util.LocationUtil;

/** Main-thread, runtime-only recovery for an explicit non-combat movement order. */
public final class GuardMovementRecovery {
    public enum State { NORMAL, SIDESTEP, NO_SAFE_DESTINATION, TELEPORT_REJECTED }

    static final class Progress {
        final GuardMode mode;
        final UUID target;
        final Location anchor;
        Location observed;
        Location sidestep;
        long since;
        long retryAt;
        int samples;
        State state = State.NORMAL;

        Progress(GuardData data, Location current, Location destination, long tick) {
            mode = data.getMode();
            target = data.isRoleProtection() ? data.getSelectedTargetUuid() : data.getOwnerId();
            anchor = destination.clone();
            resetObservation(current, tick);
        }

        void resetObservation(Location current, long tick) {
            observed = current.clone();
            since = tick;
            samples = 0;
        }
    }

    private final BodyGuard plugin;
    private final GuardManager manager;
    private boolean requested;
    private int remainingAttempts;

    public GuardMovementRecovery(BodyGuard plugin, GuardManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public void begin() { requested = false; }
    public void beginCycle() { remainingAttempts = 2; }

    public void finish(GuardData data) {
        if (!requested) data.movementProgress = null;
    }

    public void move(GuardData data, Mob mob, Location destination, long tick) {
        if (!plugin.isMovementRecoveryEnabled()) {
            data.movementProgress = null;
            LocationUtil.moveToward(mob, destination, plugin.getFollowMoveSpeed());
            return;
        }
        requested = true;
        Location current = mob.getLocation();
        UUID target = data.isRoleProtection() ? data.getSelectedTargetUuid() : data.getOwnerId();
        Progress p = data.movementProgress;
        if (p == null || p.mode != data.getMode() || !Objects.equals(p.target, target)
                || !LocationUtil.sameWorld(p.observed, current)
                || (!data.isRoleProtection() && data.getMode() != GuardMode.FOLLOW
                    && LocationUtil.distanceSquared(p.anchor, destination) > 0.01)) {
            p = new Progress(data, current, destination, tick);
            data.movementProgress = p;
        }

        if (p.state == State.SIDESTEP) {
            if (tick - p.since < 40L && p.sidestep != null
                    && current.distanceSquared(p.sidestep) > 0.09
                    && LocationUtil.isSafeStep(mob, p.sidestep)) {
                LocationUtil.moveToward(mob, p.sidestep, Math.min(0.2, plugin.getFollowMoveSpeed()));
                return;
            }
            // Try normal movement for another observation window before teleporting.
            p.state = State.NORMAL;
            p.resetObservation(current, tick);
        }

        if (current.distanceSquared(p.observed) >= 0.5625) {
            p.resetObservation(current, tick);
            p.sidestep = null;
            p.state = State.NORMAL;
        }
        p.samples++;
        if (tick >= p.retryAt && p.samples >= 3 && tick - p.since >= plugin.getMovementStuckTicks()) {
            if (remainingAttempts <= 0) {
                LocationUtil.moveToward(mob, destination, plugin.getFollowMoveSpeed());
                return;
            }
            remainingAttempts--;
            if (p.sidestep == null) {
                Location side = findSidestep(mob, destination, data.getGuardId().hashCode());
                // A sentinel also records that a sidestep was attempted but no safe path existed.
                p.sidestep = side == null ? current.clone() : side;
                p.resetObservation(current, tick);
                if (side != null) {
                    p.state = State.SIDESTEP;
                    LocationUtil.moveToward(mob, side, Math.min(0.2, plugin.getFollowMoveSpeed()));
                    return;
                }
            }
            double radius = switch (data.getMode()) {
                case FOLLOW -> Math.max(1.5, plugin.getFollowStartDistance());
                case STAY -> 1.5;
                case GUARD -> plugin.getGuardRadius();
            };
            Location safe = LocationUtil.findSafeLocationWithin(destination,
                    data.getGuardId().hashCode(), mob, radius);
            p.retryAt = tick + plugin.getMovementRetryTicks();
            p.resetObservation(current, tick);
            if (safe == null) {
                p.state = State.NO_SAFE_DESTINATION;
            } else if (!mob.teleport(safe)) {
                p.state = State.TELEPORT_REJECTED;
            } else {
                data.clearCombat();
                mob.setTarget(null);
                LocationUtil.stopHorizontal(mob);
                data.setLastLocation(mob.getLocation());
                manager.markDirty();
                p.resetObservation(mob.getLocation(), tick);
                p.sidestep = null;
                p.state = State.NORMAL;
                return;
            }
        }
        LocationUtil.moveToward(mob, destination, plugin.getFollowMoveSpeed());
    }

    private Location findSidestep(Mob mob, Location destination, int seed) {
        Location current = mob.getLocation();
        double x = destination.getX() - current.getX();
        double z = destination.getZ() - current.getZ();
        double length = Math.hypot(x, z);
        if (length < 0.15) return null;
        int preferred = Math.floorMod(seed, 2) == 0 ? 1 : -1;
        for (int side : new int[] { preferred, -preferred }) {
            Location candidate = current.clone().add(-z / length * side, 0, x / length * side);
            if (LocationUtil.isSafeStep(mob, candidate)) return candidate;
        }
        return null;
    }
}
