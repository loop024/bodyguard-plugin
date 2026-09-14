package plugin.test.com.bodyGuard.util;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/** Entity and ray-tracing helpers. */
public final class EntityUtil {

    private EntityUtil() {
    }

    public static LivingEntity findLookedAtLivingEntity(Player player, double maxDistance) {
        if (player == null || !player.isValid() || maxDistance <= 0.0) {
            return null;
        }
        Location eye = player.getEyeLocation();
        if (eye.getWorld() == null) {
            return null;
        }
        Vector direction = eye.getDirection();
        if (direction.lengthSquared() < 0.000001) {
            return null;
        }

        double limit = Math.min(maxDistance, 32.0);
        RayTraceResult blockHit = eye.getWorld().rayTraceBlocks(
                eye, direction, limit, FluidCollisionMode.NEVER);
        if (blockHit != null && blockHit.getHitPosition() != null) {
            limit = Math.min(limit, eye.toVector().distance(blockHit.getHitPosition()));
        }
        if (limit <= 0.05) {
            return null;
        }

        RayTraceResult entityHit = eye.getWorld().rayTraceEntities(
                eye,
                direction,
                limit,
                0.3,
                entity -> entity instanceof LivingEntity && entity != player
        );
        if (entityHit == null || !(entityHit.getHitEntity() instanceof LivingEntity living)) {
            return null;
        }
        return living;
    }

    /** Returns the entity that caused a direct or projectile damage event. */
    public static Entity resolveDamageSource(Entity damager) {
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            return shooter instanceof Entity entity ? entity : null;
        }
        return damager;
    }

    public static LivingEntity resolveLivingDamageSource(Entity damager) {
        Entity source = resolveDamageSource(damager);
        return source instanceof LivingEntity living ? living : null;
    }

    public static String prettyMobName(EntityType type) {
        if (type == null) {
            return "Mob";
        }
        String[] parts = type.name().toLowerCase(java.util.Locale.ROOT).split("_");
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return result.toString();
    }

    public static boolean isAlive(Entity entity) {
        return entity != null && entity.isValid() && !entity.isDead();
    }
}
