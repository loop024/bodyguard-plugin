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
        COMMAND,
        TUTORIAL,
        LIST,
        SUMMON,
        DETAIL,
        MANAGEMENT,
        RELEASE_CONFIRM,
        NAME_INPUT
    }

    public enum GuardFilter {
        ALL("すべて"),
        INJURED("負傷中"),
        FOLLOW("追従中"),
        STAY("待機中"),
        GUARD("警備中"),
        UNKNOWN("状態を確認できない護衛");

        private final String japaneseName;

        GuardFilter(String japaneseName) {
            this.japaneseName = japaneseName;
        }

        public String japaneseName() {
            return japaneseName;
        }

        public GuardFilter next() {
            GuardFilter[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    public enum GuardSort {
        STANDARD("標準"),
        DISTANCE("近い順"),
        HEALTH_RATIO("HP割合が少ない順");

        private final String japaneseName;

        GuardSort(String japaneseName) {
            this.japaneseName = japaneseName;
        }

        public String japaneseName() {
            return japaneseName;
        }

        public GuardSort next() {
            GuardSort[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    private final MenuType type;
    private final UUID ownerId;
    private final int page;
    private final UUID guardId;
    private final List<UUID> guardIds;
    private final List<EntityType> mobTypes;
    private final boolean releaseAll;
    private final GuardFilter filter;
    private final GuardSort sort;
    private final int totalGuardCount;
    private final int filteredGuardCount;
    private Inventory inventory;

    public BodyGuardMenuHolder(MenuType type, UUID ownerId, int page, UUID guardId,
                               Collection<UUID> guardIds, Collection<EntityType> mobTypes,
                               boolean releaseAll) {
        this(type, ownerId, page, guardId, guardIds, mobTypes, releaseAll,
                GuardFilter.ALL, GuardSort.STANDARD, -1, -1);
    }

    public BodyGuardMenuHolder(MenuType type, UUID ownerId, int page, UUID guardId,
                               Collection<UUID> guardIds, Collection<EntityType> mobTypes,
                               boolean releaseAll, GuardFilter filter, GuardSort sort,
                               int totalGuardCount, int filteredGuardCount) {
        this.type = type;
        this.ownerId = ownerId;
        this.page = page;
        this.guardId = guardId;
        this.guardIds = immutableCopy(guardIds);
        this.mobTypes = immutableCopy(mobTypes);
        this.releaseAll = releaseAll;
        this.filter = filter == null ? GuardFilter.ALL : filter;
        this.sort = sort == null ? GuardSort.STANDARD : sort;
        this.totalGuardCount = totalGuardCount;
        this.filteredGuardCount = filteredGuardCount;
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

    public GuardFilter getFilter() {
        return filter;
    }

    public GuardSort getSort() {
        return sort;
    }

    public int getTotalGuardCount() {
        return totalGuardCount;
    }

    public int getFilteredGuardCount() {
        return filteredGuardCount;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
