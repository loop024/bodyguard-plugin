package plugin.test.com.bodyGuard.gui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;

import plugin.test.com.bodyGuard.guard.GuardData;
import plugin.test.com.bodyGuard.guard.GuardManager;
import plugin.test.com.bodyGuard.guard.GuardMode;
import plugin.test.com.bodyGuard.util.LocationUtil;

/** Selects and summarizes guards for list screens without rendering inventory items. */
public final class GuardListQuery {

    private final GuardManager manager;

    public GuardListQuery(GuardManager manager) {
        this.manager = manager;
    }

    public List<GuardData> filterAndSort(Player player, List<GuardData> all,
                                         BodyGuardMenuHolder.GuardFilter filter,
                                         BodyGuardMenuHolder.GuardSort sort) {
        List<GuardData> filtered = new ArrayList<>();
        for (GuardData data : all) {
            if (matchesFilter(data, filter)) {
                filtered.add(data);
            }
        }
        if (sort == BodyGuardMenuHolder.GuardSort.STANDARD) {
            filtered.sort(Comparator.comparing(GuardData::isFavorite).reversed());
            return filtered;
        }
        Map<UUID, Integer> originalOrder = new HashMap<>();
        for (int index = 0; index < all.size(); index++) {
            originalOrder.put(all.get(index).getGuardId(), index);
        }
        Comparator<GuardData> comparator = Comparator
                .comparingDouble((GuardData data) -> sortValue(player, data, sort))
                .thenComparingInt(data -> originalOrder.getOrDefault(data.getGuardId(), Integer.MAX_VALUE));
        filtered.sort(comparator);
        return filtered;
    }

    public int countMatching(List<GuardData> all, BodyGuardMenuHolder.GuardFilter filter) {
        int count = 0;
        for (GuardData data : all) {
            if (matchesFilter(data, filter)) {
                count++;
            }
        }
        return count;
    }

    public Summary summary(List<GuardData> all) {
        int injured = 0;
        int unknown = 0;
        for (GuardData data : all) {
            Mob mob = manager.getLoadedMob(data);
            if (mob == null) {
                unknown++;
                continue;
            }
            Health health = health(mob);
            if (health != null && health.current() < health.maximum()) {
                injured++;
            }
        }
        return new Summary(all.size(), injured, unknown);
    }

    private boolean matchesFilter(GuardData data, BodyGuardMenuHolder.GuardFilter filter) {
        if (filter == null || filter == BodyGuardMenuHolder.GuardFilter.ALL) {
            return true;
        }
        if (filter == BodyGuardMenuHolder.GuardFilter.FAVORITE) {
            return data.isFavorite();
        }
        if (filter == BodyGuardMenuHolder.GuardFilter.FOLLOW) {
            return data.getMode() == GuardMode.FOLLOW;
        }
        if (filter == BodyGuardMenuHolder.GuardFilter.STAY) {
            return data.getMode() == GuardMode.STAY;
        }
        if (filter == BodyGuardMenuHolder.GuardFilter.GUARD) {
            return data.getMode() == GuardMode.GUARD;
        }
        Mob mob = manager.getLoadedMob(data);
        if (filter == BodyGuardMenuHolder.GuardFilter.UNKNOWN) {
            return mob == null;
        }
        Health health = health(mob);
        return health != null && health.current() < health.maximum();
    }

    private double sortValue(Player player, GuardData data, BodyGuardMenuHolder.GuardSort sort) {
        Mob mob = manager.getLoadedMob(data);
        if (sort == BodyGuardMenuHolder.GuardSort.DISTANCE) {
            if (mob == null || player == null
                    || !LocationUtil.sameWorld(player.getLocation(), mob.getLocation())) {
                return Double.POSITIVE_INFINITY;
            }
            return player.getLocation().distanceSquared(mob.getLocation());
        }
        Health health = health(mob);
        return health == null ? Double.POSITIVE_INFINITY : health.ratio();
    }

    private Health health(Mob mob) {
        if (mob == null) {
            return null;
        }
        AttributeInstance maxHealth = mob.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth == null || !Double.isFinite(maxHealth.getValue()) || maxHealth.getValue() <= 0.0) {
            return null;
        }
        double maximum = maxHealth.getValue();
        double current = Math.max(0.0, Math.min(mob.getHealth(), maximum));
        return new Health(current, maximum, current / maximum);
    }

    public record Summary(int total, int injured, int unknown) {
    }

    private record Health(double current, double maximum, double ratio) {
    }
}
