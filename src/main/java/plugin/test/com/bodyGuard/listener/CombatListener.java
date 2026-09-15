package plugin.test.com.bodyGuard.listener;

import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.entity.Projectile;

import plugin.test.com.bodyGuard.BodyGuard;
import plugin.test.com.bodyGuard.guard.GuardData;
import plugin.test.com.bodyGuard.guard.GuardManager;
import plugin.test.com.bodyGuard.util.EntityUtil;

/** Handles owner defense, owner attack assistance, and every friendly-fire path. */
public final class CombatListener implements Listener {

    private final BodyGuard plugin;
    private final GuardManager manager;

    public CombatListener(BodyGuard plugin, GuardManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        Entity victim = event.getEntity();
        Entity source = EntityUtil.resolveDamageSource(event.getDamager());
        GuardData victimGuard = manager.getGuardData(victim);
        GuardData sourceGuard = manager.getGuardData(source);

        if (victim instanceof Player player && sourceGuard != null
                && manager.isFriend(sourceGuard.getOwnerId(), player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }

        if (victim instanceof Player player && sourceGuard != null
                && player.getUniqueId().equals(sourceGuard.getOwnerId())
                && !plugin.guardsCanDamageOwner()) {
            event.setCancelled(true);
            return;
        }

        if (victimGuard != null && source instanceof Player player
                && player.getUniqueId().equals(victimGuard.getOwnerId())
                && !plugin.ownerCanDamageGuards()) {
            event.setCancelled(true);
            return;
        }

        if (victimGuard != null && sourceGuard != null
                && victimGuard.getOwnerId().equals(sourceGuard.getOwnerId())
                && !plugin.guardsCanDamageEachOther()) {
            event.setCancelled(true);
            return;
        }

        if (event.isCancelled()) {
            return;
        }

        LivingEntity livingSource = source instanceof LivingEntity living ? living : null;
        if (victim instanceof Player owner
                && plugin.shouldDefendOwner()
                && livingSource != null) {
            boolean ownGuardSource = sourceGuard != null
                    && owner.getUniqueId().equals(sourceGuard.getOwnerId());
            if (!ownGuardSource
                    && (!(livingSource instanceof Player) || plugin.shouldDefendAgainstPlayers())) {
                manager.commandGuardsToTarget(owner.getUniqueId(), livingSource, true);
            }
        }

        if (source instanceof Player owner
                && plugin.shouldAssistOwnerAttacks()
                && victim instanceof LivingEntity target) {
            manager.commandGuardsToTarget(owner.getUniqueId(), target, false);
        }
    }

    /** Lets a guard's projectile continue through another guard belonging to the same owner. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onProjectileHit(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();
        if (event.getHitEntity() == null) {
            return;
        }
        Entity source = projectile.getShooter() instanceof Entity entity ? entity : null;
        GuardData sourceGuard = manager.getGuardData(source);
        GuardData hitGuard = manager.getGuardData(event.getHitEntity());
        if (sourceGuard != null && hitGuard != null
                && sourceGuard.getOwnerId().equals(hitGuard.getOwnerId())) {
            event.setCancelled(true);
        }
    }

    /** Covers indirect damage sources such as an explosion caused by a configurable guard type. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent) {
            return;
        }

        Entity source = event.getDamageSource() == null
                ? null : event.getDamageSource().getCausingEntity();
        GuardData victimGuard = manager.getGuardData(event.getEntity());
        GuardData sourceGuard = manager.getGuardData(source);

        if (event.getEntity() instanceof Player player && sourceGuard != null
                && manager.isFriend(sourceGuard.getOwnerId(), player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        if (event.getEntity() instanceof Player player && sourceGuard != null
                && player.getUniqueId().equals(sourceGuard.getOwnerId())
                && !plugin.guardsCanDamageOwner()) {
            event.setCancelled(true);
            return;
        }
        if (victimGuard != null && source instanceof Player player
                && player.getUniqueId().equals(victimGuard.getOwnerId())
                && !plugin.ownerCanDamageGuards()) {
            event.setCancelled(true);
            return;
        }
        if (victimGuard != null && sourceGuard != null
                && victimGuard.getOwnerId().equals(sourceGuard.getOwnerId())
                && !plugin.guardsCanDamageEachOther()) {
            event.setCancelled(true);
            return;
        }

        if (event.getEntity() instanceof Player owner
                && plugin.shouldDefendOwner()
                && source instanceof LivingEntity livingSource) {
            boolean ownGuardSource = sourceGuard != null
                    && owner.getUniqueId().equals(sourceGuard.getOwnerId());
            if (!ownGuardSource
                    && (!(livingSource instanceof Player) || plugin.shouldDefendAgainstPlayers())) {
                manager.commandGuardsToTarget(owner.getUniqueId(), livingSource, true);
            }
        }
    }
}
