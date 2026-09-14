package plugin.test.com.bodyGuard.listener;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;

import plugin.test.com.bodyGuard.guard.GuardManager;

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
        if (target == null || !manager.isForbiddenTarget(guard, target)) {
            return;
        }

        event.setTarget(null);
        event.setCancelled(true);
        guard.setTarget(null);
    }
}
