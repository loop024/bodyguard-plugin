package plugin.test.com.bodyGuard.guard;

import java.util.Objects;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.entity.EntityType;

import plugin.test.com.bodyGuard.util.LocationUtil;

/** Persistent information belonging to one BodyGuard entity. */
public final class GuardData {

    private final UUID guardId;
    private final UUID ownerId;
    private EntityType mobType;
    private GuardMode mode;
    private String name;
    private String ownerName;
    private Location anchorLocation;
    private Location lastLocation;
    private int nameNumber;
    private long combatUntilMillis;
    private UUID combatTargetId;
    private boolean offlineFrozen;

    public GuardData(UUID guardId, UUID ownerId, EntityType mobType, GuardMode mode,
                     String name, String ownerName, Location anchorLocation, Location lastLocation) {
        this(guardId, ownerId, mobType, mode, name, ownerName, anchorLocation, lastLocation, 0);
    }

    public GuardData(UUID guardId, UUID ownerId, EntityType mobType, GuardMode mode,
                     String name, String ownerName, Location anchorLocation, Location lastLocation,
                     int nameNumber) {
        this.guardId = Objects.requireNonNull(guardId, "guardId");
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.mobType = Objects.requireNonNull(mobType, "mobType");
        this.mode = mode == null ? GuardMode.FOLLOW : mode;
        this.name = name == null ? "BodyGuard" : name;
        this.ownerName = ownerName == null ? "Player" : ownerName;
        this.anchorLocation = LocationUtil.copy(anchorLocation);
        this.lastLocation = LocationUtil.copy(lastLocation);
        this.nameNumber = Math.max(0, nameNumber);
    }

    public UUID getGuardId() {
        return guardId;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public EntityType getMobType() {
        return mobType;
    }

    public void setMobType(EntityType mobType) {
        if (mobType != null) {
            this.mobType = mobType;
        }
    }

    public GuardMode getMode() {
        return mode;
    }

    public void setMode(GuardMode mode) {
        if (mode != null) {
            this.mode = mode;
        }
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        if (name != null && !name.isBlank()) {
            this.name = name;
        }
    }

    public int getNameNumber() {
        return nameNumber;
    }

    public void setNameNumber(int nameNumber) {
        this.nameNumber = Math.max(0, nameNumber);
    }

    public String getOwnerName() {
        return ownerName;
    }

    public void setOwnerName(String ownerName) {
        if (ownerName != null && !ownerName.isBlank()) {
            this.ownerName = ownerName;
        }
    }

    public Location getAnchorLocation() {
        return LocationUtil.copy(anchorLocation);
    }

    public void setAnchorLocation(Location anchorLocation) {
        this.anchorLocation = LocationUtil.copy(anchorLocation);
    }

    public Location getLastLocation() {
        return LocationUtil.copy(lastLocation);
    }

    public void setLastLocation(Location lastLocation) {
        this.lastLocation = LocationUtil.copy(lastLocation);
    }

    /** Updates the persisted position only after meaningful movement, reducing needless saves. */
    public boolean updateLastLocation(Location location, double minimumDistanceSquared) {
        Location next = LocationUtil.copy(location);
        if (next == null) {
            if (lastLocation == null) {
                return false;
            }
            lastLocation = null;
            return true;
        }
        if (lastLocation != null && LocationUtil.sameWorld(lastLocation, next)
                && lastLocation.distanceSquared(next) < Math.max(0.0, minimumDistanceSquared)) {
            return false;
        }
        lastLocation = next;
        return true;
    }

    public void markCombat(long durationMillis) {
        long safeDuration = Math.max(500L, durationMillis);
        combatUntilMillis = Math.max(combatUntilMillis, System.currentTimeMillis() + safeDuration);
    }

    public boolean isInCombat() {
        return System.currentTimeMillis() < combatUntilMillis;
    }

    public UUID getCombatTargetId() {
        return combatTargetId;
    }

    public void setCombatTargetId(UUID targetId) {
        combatTargetId = targetId;
    }

    public void clearCombat() {
        combatTargetId = null;
        combatUntilMillis = 0L;
    }

    public boolean isOfflineFrozen() {
        return offlineFrozen;
    }

    public void setOfflineFrozen(boolean offlineFrozen) {
        this.offlineFrozen = offlineFrozen;
    }
}
