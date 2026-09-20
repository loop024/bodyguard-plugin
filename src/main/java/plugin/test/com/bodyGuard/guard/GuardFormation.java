package plugin.test.com.bodyGuard.guard;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Mob;
import plugin.test.com.bodyGuard.BodyGuard;
import plugin.test.com.bodyGuard.util.LocationUtil;

/** Stable slots per protection target; angular slots do not rotate with the player's view. */
public final class GuardFormation {
    private record Slot(UUID group, int index) { }
    private final Map<UUID, Slot> slots = new HashMap<>();
    private final Map<UUID, Set<Integer>> used = new HashMap<>();
    private final BodyGuard plugin;
    public GuardFormation(BodyGuard plugin) { this.plugin = plugin; }
    public void forget(UUID guard) {
        Slot old = slots.remove(guard);
        if (old == null) return;
        Set<Integer> group = used.get(old.group());
        if (group != null) {
            group.remove(old.index());
            if (group.isEmpty()) used.remove(old.group());
        }
    }
    public Location point(GuardData data, UUID group, Location center, Mob mob) {
        if (!plugin.isFormationEnabled()) return center.clone();
        Slot slot = slots.get(data.getGuardId());
        if (slot == null || !slot.group().equals(group)) {
            forget(data.getGuardId());
            Set<Integer> occupied = used.computeIfAbsent(group, ignored -> new HashSet<>());
            int index = 0;
            while (occupied.contains(index)) index++;
            occupied.add(index);
            slot = new Slot(group, index);
            slots.put(data.getGuardId(), slot);
        }
        int ring = slot.index() / 8 + 1;
        double angle = (slot.index() % 8) * Math.PI / 4.0;
        double spacing = Math.max(plugin.getFormationSpacing(), mob.getWidth() + 0.6);
        // Keep large parties within the recall range instead of extending rings indefinitely.
        double radius = Math.min(12.0, ring * spacing);
        return center.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
    }
    public Location safePoint(GuardData data, UUID group, Location center, Mob mob) {
        Location preferred = point(data, group, center, mob);
        Location safe = LocationUtil.findSafeLocationWithin(preferred, 0, mob, 1.75);
        if (safe != null) return safe;
        // Narrow places use a smaller radius, then the existing conservative search.
        Location compact = center.clone().add(preferred.toVector().subtract(center.toVector()).multiply(0.5));
        safe = LocationUtil.findSafeLocationWithin(compact, data.getGuardId().hashCode(), mob, 2.5);
        return safe != null ? safe : LocationUtil.findSafeLocation(center, data.getGuardId().hashCode(), mob);
    }
}
