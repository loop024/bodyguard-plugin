package plugin.test.com.bodyGuard.listener;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;

import plugin.test.com.bodyGuard.guard.GuardManager;
import plugin.test.com.bodyGuard.guard.GuardData;

/** Prevents only registered BodyGuards from selecting protected targets. */
public final class TargetListener implements Listener {

    private final GuardManager manager;

    public TargetListener(GuardManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (!(event.getEntity() instanceof Mob guard)
                || manager.getGuardData(guard) == null) {
            return;
        }
        LivingEntity target = event.getTarget();
        GuardData data = manager.getGuardData(guard);
        LivingEntity commanded = manager.getCombatTarget(guard, data);
        if (!event.isCancelled() && commanded != null && !commanded.equals(target)) {
            // Change the event, not the entity: setTarget here would nest target events.
            event.setTarget(commanded);
            return;
        }
        if (target == null || !manager.isForbiddenTarget(guard, target)) {
            return;
        }

        event.setTarget(null);
        event.setCancelled(true);
    }
}
