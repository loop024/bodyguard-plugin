package plugin.test.com.bodyGuard.listener;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.entity.EntityType;

import plugin.test.com.bodyGuard.guard.GuardManager;
import plugin.test.com.bodyGuard.guard.GuardData;
import plugin.test.com.bodyGuard.guard.CombatPolicy;

/** Prevents only registered BodyGuards from selecting protected targets. */
public final class TargetListener implements Listener {

    private final GuardManager manager;

    public TargetListener(GuardManager manager) {
        this.manager = manager;
    }

    /** Keep silverfish guards from entering blocks and losing their tracked entity. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGuardChangeBlock(EntityChangeBlockEvent event) {
        if (event.getEntityType() == EntityType.SILVERFISH
                && manager.getGuardData(event.getEntity()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (!(event.getEntity() instanceof Mob guard)
                || manager.getGuardData(guard) == null) {
            return;
        }
        if (event.isCancelled()) return;
        LivingEntity target = event.getTarget();
        GuardData data = manager.getGuardData(guard);
        LivingEntity commanded = manager.getCombatTarget(guard, data);
        CombatPolicy policy = data.getTactics().policy();
        if (target != null && policy == CombatPolicy.PASSIVE) {
            event.setTarget(null);
            event.setCancelled(true);
            return;
        }
        if (commanded != null && !commanded.equals(target)) {
            // Change the event, not the entity: setTarget here would nest target events.
            event.setTarget(commanded);
            return;
        }
        if (target != null && commanded == null
                && (policy == CombatPolicy.RETALIATE || policy == CombatPolicy.ASSIST)) {
            event.setTarget(null);
            event.setCancelled(true);
            return;
        }
        if (target == null || !manager.isForbiddenTarget(guard, target)) {
            return;
        }

        event.setTarget(null);
        event.setCancelled(true);
    }
}
