package plugin.test.com.bodyGuard.guard;

import java.util.Objects;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.entity.EntityType;

import plugin.test.com.bodyGuard.util.LocationUtil;

/** Persistent information belonging to one BodyGuard contract generation. */
public final class GuardData {

    public enum ContractStatus {
        ACTIVE,
        RELEASE_PENDING,
        RELEASED,
        DELETE_PENDING,
        DELETED,
        DEAD
    }

    /** Selects whether this guard protects its owner or one configured role target. */
    public enum ProtectionKind {
        OWNER,
        ROLE
    }

    /** Runtime state of the role-target resolver and cross-world transfer. */
    public enum ProtectionState {
        TARGET_UNAVAILABLE,
        TARGET_SELECTED,
        SAME_WORLD,
        WORLD_TRANSFER_PENDING,
        ACTIVE,
        WAITING
    }

    public enum ObservationStatus {
        AVAILABLE,
        UNLOADED,
        WORLD_UNAVAILABLE,
        CHECKING,
        MISSING,
        QUARANTINED
    }

    public enum OperationType {
        RELEASE,
        DELETE,
        DEATH
    }

    /** Combined display status retained for the existing GUI and command API. */
    public enum Status {
        AVAILABLE("存在確認済み"),
        UNLOADED("遠方・未読み込み"),
        WORLD_UNAVAILABLE("ワールド復帰待ち"),
        CHECKING("所在を確認中"),
        MISSING("所在不明"),
        QUARANTINED("隔離・管理者確認待ち"),
        RELEASE_PENDING("解除待ち"),
        DELETE_PENDING("削除待ち"),
        RELEASED("解除済み"),
        DELETED("削除済み"),
        DEAD("死亡済み");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

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
    private boolean favorite;

    private ProtectionKind protectionKind = ProtectionKind.OWNER;
    private String roleId;
    /** The last selected target is retained so a returning player can be preferred. */
    private UUID selectedTargetUuid;
    private long selectionRevision;
    private ProtectionState protectionState = ProtectionState.ACTIVE;
    private String protectionFailureReason;

    private ContractStatus contractStatus = ContractStatus.ACTIVE;
    private ObservationStatus observationStatus = ObservationStatus.CHECKING;
    private UUID operationId;
    private OperationType operationType;
    private long operationAcceptedAt;
    private long operationCompletedAt;
    private String operationLastFailureReason;
    private long contractGeneration = 1L;
    private long saveRevision;
    private long lastSeen;
    private long missingSince;
    private int missingObservations;

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

    public UUID getGuardId() { return guardId; }
    public UUID getOwnerId() { return ownerId; }
    public EntityType getMobType() { return mobType; }

    public void setMobType(EntityType mobType) {
        if (mobType != null) this.mobType = mobType;
    }

    public GuardMode getMode() { return mode; }

    public void setMode(GuardMode mode) {
        if (mode != null) this.mode = mode;
    }

    public String getName() { return name; }

    public void setName(String name) {
        if (name != null && !name.isBlank()) this.name = name;
    }

    public int getNameNumber() { return nameNumber; }
    public void setNameNumber(int nameNumber) { this.nameNumber = Math.max(0, nameNumber); }
    public String getOwnerName() { return ownerName; }

    public void setOwnerName(String ownerName) {
        if (ownerName != null && !ownerName.isBlank()) this.ownerName = ownerName;
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

    /** Updates the persisted position only after meaningful movement. */
    public boolean updateLastLocation(Location location, double minimumDistanceSquared) {
        if (location == null) return false;
        Location previous = getLastLocation();
        if (previous != null && LocationUtil.sameWorld(previous, location)
                && previous.distanceSquared(location) < Math.max(0.0, minimumDistanceSquared)) {
            return false;
        }
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

    public boolean isInCombat() { return System.currentTimeMillis() < combatUntilMillis; }
    public UUID getCombatTargetId() { return combatTargetId; }
    public void setCombatTargetId(UUID targetId) { combatTargetId = targetId; }

    public void clearCombat() {
        combatTargetId = null;
        combatUntilMillis = 0L;
    }

    public boolean isOfflineFrozen() { return offlineFrozen; }
    public void setOfflineFrozen(boolean offlineFrozen) { this.offlineFrozen = offlineFrozen; }
    public boolean isFavorite() { return favorite; }
    public void setFavorite(boolean favorite) { this.favorite = favorite; }

    public ProtectionKind getProtectionKind() { return protectionKind; }

    public boolean isRoleProtection() { return protectionKind == ProtectionKind.ROLE; }

    public String getRoleId() { return roleId; }

    public UUID getSelectedTargetUuid() { return selectedTargetUuid; }

    public long getSelectionRevision() { return selectionRevision; }

    public ProtectionState getProtectionState() { return protectionState; }

    public String getProtectionFailureReason() { return protectionFailureReason; }

    /** Changes the user-selected protection kind while preserving the last target for the same role. */
    public void setProtection(ProtectionKind kind, String newRoleId) {
        ProtectionKind nextKind = kind == null ? ProtectionKind.OWNER : kind;
        String normalizedRole = newRoleId == null || newRoleId.isBlank() ? null : newRoleId;
        boolean changed = protectionKind != nextKind || !Objects.equals(roleId, normalizedRole);
        protectionKind = nextKind;
        roleId = nextKind == ProtectionKind.ROLE ? normalizedRole : null;
        if (nextKind == ProtectionKind.OWNER) {
            selectedTargetUuid = null;
            protectionState = ProtectionState.ACTIVE;
            protectionFailureReason = null;
        } else if (changed) {
            selectedTargetUuid = null;
            selectionRevision = selectionRevision == Long.MAX_VALUE
                    ? Long.MAX_VALUE : selectionRevision + 1L;
            protectionState = ProtectionState.TARGET_UNAVAILABLE;
            protectionFailureReason = null;
        }
    }

    /** Updates the remembered target and advances the monotonic selection revision. */
    public boolean setSelectedTargetUuid(UUID targetUuid) {
        if (Objects.equals(selectedTargetUuid, targetUuid)) return false;
        selectedTargetUuid = targetUuid;
        selectionRevision = selectionRevision == Long.MAX_VALUE
                ? Long.MAX_VALUE : selectionRevision + 1L;
        return true;
    }

    public void setSelectionRevision(long revision) {
        selectionRevision = Math.max(0L, revision);
    }

    public void setProtectionState(ProtectionState state, String reason) {
        protectionState = state == null ? ProtectionState.WAITING : state;
        protectionFailureReason = reason == null || reason.isBlank() ? null : reason;
    }

    /** Restores a complete protection snapshot when YAML/PDC persistence fails. */
    public void restoreProtection(ProtectionSnapshot snapshot) {
        if (snapshot == null) return;
        protectionKind = snapshot.kind() == null ? ProtectionKind.OWNER : snapshot.kind();
        roleId = snapshot.roleId();
        selectedTargetUuid = snapshot.selectedTargetUuid();
        selectionRevision = Math.max(0L, snapshot.selectionRevision());
        protectionState = snapshot.state() == null ? ProtectionState.WAITING : snapshot.state();
        protectionFailureReason = snapshot.failureReason();
    }

    public ProtectionSnapshot snapshotProtection() {
        return new ProtectionSnapshot(protectionKind, roleId, selectedTargetUuid,
                selectionRevision, protectionState, protectionFailureReason);
    }

    public ContractStatus getContractStatus() { return contractStatus; }

    public void setContractStatus(ContractStatus status) {
        contractStatus = status == null ? ContractStatus.ACTIVE : status;
        if (contractStatus != ContractStatus.ACTIVE) {
            observationStatus = ObservationStatus.CHECKING;
        }
    }

    public ObservationStatus getObservationStatus() { return observationStatus; }

    public void setObservationStatus(ObservationStatus status) {
        observationStatus = status == null ? ObservationStatus.CHECKING : status;
    }

    public boolean isActiveContract() { return contractStatus == ContractStatus.ACTIVE; }
    public boolean isRetired() { return !isActiveContract(); }
    public boolean isQuarantined() { return observationStatus == ObservationStatus.QUARANTINED; }
    public boolean isReleasePending() { return contractStatus == ContractStatus.RELEASE_PENDING; }
    public boolean isDeletionPending() { return contractStatus == ContractStatus.DELETE_PENDING; }
    public boolean isDeathConfirmed() { return contractStatus == ContractStatus.DEAD; }

    /** Compatibility bridge for legacy YAML readers and old callers. */
    public void setReleasePending(boolean value) {
        if (value) {
            contractStatus = ContractStatus.RELEASE_PENDING;
        } else if (contractStatus == ContractStatus.RELEASE_PENDING) {
            contractStatus = ContractStatus.ACTIVE;
        }
    }

    /** Compatibility bridge for legacy YAML readers and old callers. */
    public void setDeletionPending(boolean value) {
        if (value) {
            contractStatus = ContractStatus.DELETE_PENDING;
        } else if (contractStatus == ContractStatus.DELETE_PENDING) {
            contractStatus = ContractStatus.ACTIVE;
        }
    }

    /** Compatibility bridge for legacy YAML readers and old callers. */
    public void setDeathConfirmed(boolean value) {
        if (value) contractStatus = ContractStatus.DEAD;
    }

    /** Compatibility bridge for legacy YAML readers and old callers. */
    public boolean isOperationCompleted() {
        return contractStatus == ContractStatus.RELEASED
                || contractStatus == ContractStatus.DELETED
                || contractStatus == ContractStatus.DEAD
                || operationCompletedAt > 0L;
    }

    /** Compatibility bridge for legacy YAML readers and old callers. */
    public void setOperationCompleted(boolean value) {
        if (!value) {
            operationCompletedAt = 0L;
            if (contractStatus == ContractStatus.RELEASED || contractStatus == ContractStatus.DELETED) {
                contractStatus = ContractStatus.ACTIVE;
            }
            return;
        }
        if (contractStatus == ContractStatus.RELEASE_PENDING) contractStatus = ContractStatus.RELEASED;
        if (contractStatus == ContractStatus.DELETE_PENDING) contractStatus = ContractStatus.DELETED;
        if (contractStatus == ContractStatus.DEAD || contractStatus == ContractStatus.RELEASED
                || contractStatus == ContractStatus.DELETED) {
            operationCompletedAt = operationCompletedAt == 0L
                    ? System.currentTimeMillis() : operationCompletedAt;
        }
    }

    public UUID getOperationId() { return operationId; }
    public OperationType getOperationType() { return operationType; }
    public long getOperationAcceptedAt() { return operationAcceptedAt; }
    public long getOperationCompletedAt() { return operationCompletedAt; }
    public String getOperationLastFailureReason() { return operationLastFailureReason; }
    public long getContractGeneration() { return contractGeneration; }
    public long getSaveRevision() { return saveRevision; }

    public void setContractGeneration(long generation) { contractGeneration = Math.max(1L, generation); }
    public void setSaveRevision(long revision) { saveRevision = Math.max(0L, revision); }

    public void setOperationData(UUID id, OperationType type, long acceptedAt,
                                 long completedAt, String failureReason) {
        operationId = id;
        operationType = type;
        operationAcceptedAt = Math.max(0L, acceptedAt);
        operationCompletedAt = Math.max(0L, completedAt);
        operationLastFailureReason = failureReason;
    }

    public void setOperationLastFailureReason(String reason) {
        operationLastFailureReason = reason == null || reason.isBlank() ? null : reason;
    }

    public void beginOperation(UUID id, OperationType type, long acceptedAt) {
        operationId = Objects.requireNonNull(id, "operationId");
        operationType = Objects.requireNonNull(type, "operationType");
        operationAcceptedAt = Math.max(0L, acceptedAt);
        operationCompletedAt = 0L;
        operationLastFailureReason = null;
        contractStatus = switch (type) {
            case RELEASE -> ContractStatus.RELEASE_PENDING;
            case DELETE -> ContractStatus.DELETE_PENDING;
            case DEATH -> ContractStatus.DEAD;
        };
        observationStatus = ObservationStatus.CHECKING;
    }

    public void completeOperation(long completedAt) {
        if (operationType == null) return;
        contractStatus = switch (operationType) {
            case RELEASE -> ContractStatus.RELEASED;
            case DELETE -> ContractStatus.DELETED;
            case DEATH -> ContractStatus.DEAD;
        };
        operationCompletedAt = Math.max(1L, completedAt);
        operationLastFailureReason = null;
        observationStatus = ObservationStatus.CHECKING;
    }

    public void recordOperationFailure(String reason) {
        operationLastFailureReason = reason == null || reason.isBlank() ? "不明な失敗" : reason;
    }

    public long getLastSeen() { return lastSeen; }
    public void setLastSeen(long value) { lastSeen = Math.max(0L, value); }
    public long getMissingSince() { return missingSince; }
    public void setMissingSince(long value) { missingSince = Math.max(0L, value); }
    public int getMissingObservations() { return missingObservations; }
    public void setMissingObservations(int value) { missingObservations = Math.max(0, value); }

    public void observed() {
        lastSeen = System.currentTimeMillis();
        missingSince = 0L;
        missingObservations = 0;
        observationStatus = ObservationStatus.AVAILABLE;
    }

    public void markMissingObservation(long now) {
        if (missingSince == 0L) missingSince = Math.max(1L, now);
        missingObservations = Math.min(Integer.MAX_VALUE, missingObservations + 1);
        observationStatus = ObservationStatus.CHECKING;
    }

    public void markWorldUnavailable() { observationStatus = ObservationStatus.WORLD_UNAVAILABLE; }
    public void markUnloaded() { observationStatus = ObservationStatus.UNLOADED; }
    public void markChecking() { observationStatus = ObservationStatus.CHECKING; }

    public void markQuarantined(String reason) {
        observationStatus = ObservationStatus.QUARANTINED;
        recordOperationFailure(reason);
    }

    public record ProtectionSnapshot(ProtectionKind kind, String roleId,
                                     UUID selectedTargetUuid, long selectionRevision,
                                     ProtectionState state, String failureReason) {
    }
}
