package plugin.test.com.bodyGuard.gui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.bukkit.entity.EntityType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/** Identifies BodyGuard menus without relying on a translated inventory title. */
public final class BodyGuardMenuHolder implements InventoryHolder {

    public enum MenuType {
        LIST,
        SUMMON,
        DETAIL,
        RELEASE_CONFIRM
    }

    private final MenuType type;
    private final UUID ownerId;
    private final int page;
    private final UUID guardId;
    private final List<UUID> guardIds;
    private final List<EntityType> mobTypes;
    private final boolean releaseAll;
    private Inventory inventory;

    public BodyGuardMenuHolder(MenuType type, UUID ownerId, int page, UUID guardId,
                               Collection<UUID> guardIds, Collection<EntityType> mobTypes,
                               boolean releaseAll) {
        this.type = type;
        this.ownerId = ownerId;
        this.page = page;
        this.guardId = guardId;
        this.guardIds = immutableCopy(guardIds);
        this.mobTypes = immutableCopy(mobTypes);
        this.releaseAll = releaseAll;
    }

    private static <T> List<T> immutableCopy(Collection<T> values) {
        return values == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(values));
    }

    public MenuType getType() {
        return type;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public int getPage() {
        return page;
    }

    public UUID getGuardId() {
        return guardId;
    }

    /** UUIDs displayed on this page, or the UUID snapshot used for confirmation. */
    public List<UUID> getGuardIds() {
        return guardIds;
    }

    /** Mob types displayed on this summon page. */
    public List<EntityType> getMobTypes() {
        return mobTypes;
    }

    public boolean isReleaseAll() {
        return releaseAll;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
