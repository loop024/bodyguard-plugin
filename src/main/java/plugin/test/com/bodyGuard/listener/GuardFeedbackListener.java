package plugin.test.com.bodyGuard.listener;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Mob;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;

import plugin.test.com.bodyGuard.BodyGuard;
import plugin.test.com.bodyGuard.BodyGuard.GuardFeedback;
import plugin.test.com.bodyGuard.guard.GuardManager;

/** Emits a throttled warning only when damage crosses the configured low-health threshold. */
public final class GuardFeedbackListener implements Listener {

    private final BodyGuard plugin;
    private final GuardManager manager;
    private final Map<UUID, Long> lastLowHealthEffects = new HashMap<>();

    public GuardFeedbackListener(BodyGuard plugin, GuardManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGuardDamaged(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Mob mob) || manager.getGuardData(mob) == null) {
            return;
        }
        AttributeInstance maximumAttribute = mob.getAttribute(Attribute.MAX_HEALTH);
        if (maximumAttribute == null) {
            return;
        }
        double maximum = maximumAttribute.getValue();
        double before = mob.getHealth();
        double after = Math.max(0.0, before - event.getFinalDamage());
        double threshold = plugin.getLowHealthEffectRatio();
        if (!Double.isFinite(maximum) || maximum <= 0.0 || after <= 0.0
                || before / maximum <= threshold || after / maximum > threshold) {
            return;
        }
        long now = System.currentTimeMillis();
        UUID guardId = mob.getUniqueId();
        long previous = lastLowHealthEffects.getOrDefault(guardId, 0L);
        if (now - previous < plugin.getLowHealthEffectCooldownMillis()) {
            return;
        }
        lastLowHealthEffects.put(guardId, now);
        plugin.playGuardFeedback(mob, GuardFeedback.LOW_HEALTH);

        if (lastLowHealthEffects.size() > 1024) {
            long oldestAllowed = now - plugin.getLowHealthEffectCooldownMillis();
            lastLowHealthEffects.entrySet().removeIf(entry -> entry.getValue() < oldestAllowed);
        }
    }
}
