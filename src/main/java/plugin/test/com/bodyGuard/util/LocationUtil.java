package plugin.test.com.bodyGuard.util;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/** Main-thread-only helpers for conservative movement and safe destinations. */
public final class LocationUtil {

    private static final int[][] TELEPORT_OFFSETS = {
            {0, 0}, {2, 0}, {-2, 0}, {0, 2}, {0, -2},
            {2, 2}, {-2, 2}, {2, -2}, {-2, -2},
            {4, 0}, {-4, 0}, {0, 4}, {0, -4},
            {4, 2}, {4, -2}, {-4, 2}, {-4, -2},
            {2, 4}, {-2, 4}, {2, -4}, {-2, -4},
            {4, 4}, {-4, 4}, {4, -4}, {-4, -4},
            {6, 0}, {-6, 0}, {0, 6}, {0, -6}
    };

    private LocationUtil() {
    }

    public static Location copy(Location location) {
        return location == null ? null : location.clone();
    }

    public static boolean sameWorld(Location first, Location second) {
        return first != null && second != null
                && first.getWorld() != null && second.getWorld() != null
                && first.getWorld().getUID().equals(second.getWorld().getUID());
    }

    public static double distanceSquared(Location first, Location second) {
        if (!sameWorld(first, second)) return Double.POSITIVE_INFINITY;
        return first.distanceSquared(second);
    }

    public static Location findSafeLocation(Location center, int preferredIndex) {
        return findSafeLocation(center, preferredIndex, 1.4, 2.5, null);
    }

    public static Location findSafeLocation(Location center, int preferredIndex, Entity entity) {
        if (entity == null) return findSafeLocation(center, preferredIndex);
        return findSafeLocation(center, preferredIndex, entity.getWidth(), entity.getHeight(), entity);
    }

    private static Location findSafeLocation(Location center, int preferredIndex,
                                             double width, double height, Entity ignoredEntity) {
        if (center == null || center.getWorld() == null || !isFinite(center)
                || !Double.isFinite(width) || !Double.isFinite(height)
                || width <= 0.0 || height <= 0.0) return null;

        int start = Math.floorMod(preferredIndex, TELEPORT_OFFSETS.length);
        for (int attempt = 0; attempt < TELEPORT_OFFSETS.length; attempt++) {
            int[] offset = TELEPORT_OFFSETS[(start + attempt) % TELEPORT_OFFSETS.length];
            int x = center.getBlockX() + offset[0];
            int z = center.getBlockZ() + offset[1];
            int baseY = center.getBlockY();
            for (int yOffset = -3; yOffset <= 3; yOffset++) {
                int y = baseY + yOffset;
                if (y < center.getWorld().getMinHeight() + 1
                        || y + Math.ceil(height) >= center.getWorld().getMaxHeight()) continue;
                Location result = new Location(center.getWorld(), x + 0.5, y, z + 0.5,
                        center.getYaw(), center.getPitch());
                if (isSafeVolume(result, width, height, ignoredEntity)) return result;
            }
        }
        return null;
    }

    private static boolean isSafeVolume(Location location, double width, double height,
                                        Entity ignoredEntity) {
        org.bukkit.World world = location.getWorld();
        double halfWidth = Math.max(0.3, width / 2.0) + 0.05;
        int minX = (int) Math.floor(location.getX() - halfWidth);
        int maxX = (int) Math.floor(location.getX() + halfWidth);
        int minZ = (int) Math.floor(location.getZ() - halfWidth);
        int maxZ = (int) Math.floor(location.getZ() + halfWidth);
        int baseY = location.getBlockY();

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)
                        || !world.getWorldBorder().isInside(new Location(world, x + 0.5, baseY, z + 0.5))
                        || !world.getWorldBorder().isInside(new Location(world, x + 1.5, baseY, z + 1.5))) {
                    return false;
                }
                Block floor = world.getBlockAt(x, baseY - 1, z);
                if (!isSafeFloor(floor, location.getY())) return false;
                for (int bodyY = baseY; bodyY < baseY + Math.ceil(height); bodyY++) {
                    if (!isSafeBodyBlock(world.getBlockAt(x, bodyY, z))) return false;
                }
            }
        }

        BoundingBox candidate = BoundingBox.of(
                location.getX() - halfWidth, location.getY(),
                location.getZ() - halfWidth,
                location.getX() + halfWidth, location.getY() + height,
                location.getZ() + halfWidth);
        for (Entity nearby : world.getNearbyEntities(location, halfWidth + 0.6,
                Math.max(1.0, height), halfWidth + 0.6)) {
            if (nearby == ignoredEntity || !(nearby instanceof LivingEntity)
                    || !nearby.isValid() || nearby.isDead()) continue;
            if (candidate.overlaps(nearby.getBoundingBox())) return false;
        }
        return true;
    }

    private static boolean isSafeFloor(Block floor, double destinationY) {
        Material type = floor.getType();
        if (type.isAir() || floor.isPassable() || floor.isLiquid() || isHazard(type)) return false;
        // A full-height collision is not required, but a partial block must have
        // a top surface at the requested feet height.
        return floor.getBoundingBox().getMaxY() >= destinationY - 0.05;
    }

    private static boolean isSafeBodyBlock(Block block) {
        return block.isPassable() && !block.isLiquid() && !isHazard(block.getType());
    }

    private static boolean isHazard(Material type) {
        return switch (type) {
            case LAVA, WATER, FIRE, SOUL_FIRE, CAMPFIRE, SOUL_CAMPFIRE,
                    MAGMA_BLOCK, CACTUS, SWEET_BERRY_BUSH, POWDER_SNOW,
                    WITHER_ROSE, POINTED_DRIPSTONE, NETHER_PORTAL, END_PORTAL,
                    END_GATEWAY, COBWEB -> true;
            default -> false;
        };
    }

    public static boolean moveToward(Entity entity, Location destination, double speed) {
        if (entity == null || destination == null || !sameWorld(entity.getLocation(), destination)) {
            return false;
        }
        Location current = entity.getLocation();
        double x = destination.getX() - current.getX();
        double z = destination.getZ() - current.getZ();
        double horizontalLength = Math.sqrt(x * x + z * z);
        if (horizontalLength < 0.15) {
            stopHorizontal(entity);
            return false;
        }

        double limitedSpeed = Math.max(0.05, Math.min(1.5, speed));
        Vector currentVelocity = entity.getVelocity();
        double yVelocity = Math.max(-0.25, Math.min(0.42, currentVelocity.getY()));
        if (entity.isOnGround()) yVelocity = destination.getY() - current.getY() > 0.6 ? 0.42 : 0.0;
        entity.setVelocity(new Vector(x / horizontalLength * limitedSpeed, yVelocity,
                z / horizontalLength * limitedSpeed));
        return true;
    }

    public static void stopHorizontal(Entity entity) {
        if (entity == null) return;
        Vector velocity = entity.getVelocity();
        entity.setVelocity(new Vector(0.0, velocity.getY(), 0.0));
    }

    private static boolean isFinite(Location location) {
        return Double.isFinite(location.getX()) && Double.isFinite(location.getY())
                && Double.isFinite(location.getZ());
    }
}
