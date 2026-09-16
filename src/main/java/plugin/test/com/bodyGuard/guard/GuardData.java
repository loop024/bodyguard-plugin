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
    private SavedPosition anchorLocation;
    private SavedPosition lastLocation;
    private int nameNumber;
    private long combatUntilMillis;
    private UUID combatTargetId;
    private boolean offlineFrozen;
    private boolean releasePending;
    private boolean deletionPending;
    private boolean favorite;
    private boolean operationCompleted;
    private long lastSeen;
    private long missingSince;
    private boolean deathConfirmed;

    public enum Status {
        AVAILABLE("存在確認済み"), UNLOADED("遠方・未読み込み"), WORLD_UNAVAILABLE("ワールド復帰待ち"),
        CHECKING("所在を確認中"), MISSING("所在不明"), RELEASE_PENDING("解除待ち"),
        DELETE_PENDING("削除待ち"), RELEASED("解除済み"), DELETED("削除済み"), DEAD("死亡済み");
        private final String label;
        Status(String label) { this.label = label; }
        public String label() { return label; }
    }

    public long getLastSeen() { return lastSeen; }
    public void setLastSeen(long value) { lastSeen = Math.max(0, value); }
    public long getMissingSince() { return missingSince; }
    public void setMissingSince(long value) { missingSince = Math.max(0, value); }
    public boolean isDeathConfirmed() { return deathConfirmed; }
    public void setDeathConfirmed(boolean value) { deathConfirmed = value; }
    public boolean isRetired() { return releasePending || deletionPending || deathConfirmed; }
    public void observed() { lastSeen = System.currentTimeMillis(); missingSince = 0; }

    public boolean isOperationCompleted() { return operationCompleted; }
    public void setOperationCompleted(boolean value) { operationCompleted = value; }

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
        this.anchorLocation = SavedPosition.of(anchorLocation);
        this.lastLocation = SavedPosition.of(lastLocation);
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
        return anchorLocation == null ? null : anchorLocation.resolve();
    }

    public void setAnchorLocation(Location anchorLocation) {
        this.anchorLocation = SavedPosition.of(anchorLocation);
    }

    public Location getLastLocation() {
        return lastLocation == null ? null : lastLocation.resolve();
    }

    public void setLastLocation(Location lastLocation) {
        this.lastLocation = SavedPosition.of(lastLocation);
    }

    /** Updates the persisted position only after meaningful movement, reducing needless saves. */
    public boolean updateLastLocation(Location location, double minimumDistanceSquared) {
        if (location == null) return false;
        Location previous = getLastLocation();
        if (previous != null && LocationUtil.sameWorld(previous, location)
                && previous.distanceSquared(location) < Math.max(0.0, minimumDistanceSquared)) return false;
        lastLocation = SavedPosition.of(location);
        return true;
    }

    public SavedPosition getSavedAnchor() { return anchorLocation; }
    public SavedPosition getSavedLast() { return lastLocation; }
    public void setSavedPositions(SavedPosition anchor, SavedPosition last) {
        anchorLocation = anchor;
        lastLocation = last;
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

    public boolean isReleasePending() {
        return releasePending;
    }

    public void setReleasePending(boolean releasePending) {
        this.releasePending = releasePending;
    }

    public boolean isDeletionPending() {
        return deletionPending;
    }

    public void setDeletionPending(boolean deletionPending) {
        this.deletionPending = deletionPending;
    }

    public boolean isFavorite() {
        return favorite;
    }

    public void setFavorite(boolean favorite) {
        this.favorite = favorite;
    }
}
