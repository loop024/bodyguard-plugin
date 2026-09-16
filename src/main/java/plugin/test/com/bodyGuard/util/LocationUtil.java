package plugin.test.com.bodyGuard.util;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;

/** Small, main-thread-only location helpers used by the guard task. */
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
                && first.getWorld() != null
                && second.getWorld() != null
                && first.getWorld().equals(second.getWorld());
    }

    public static double distanceSquared(Location first, Location second) {
        if (!sameWorld(first, second)) {
            return Double.POSITIVE_INFINITY;
        }
        return first.distanceSquared(second);
    }

    /** Finds a simple two-block-high, non-liquid position near the center. */
    public static Location findSafeLocation(Location center, int preferredIndex) {
        return findSafeLocation(center, preferredIndex, 1.4, 2.5);
    }

    public static Location findSafeLocation(Location center, int preferredIndex, Entity entity) {
        return findSafeLocation(center, preferredIndex, entity.getWidth(), entity.getHeight());
    }

    private static Location findSafeLocation(Location center, int preferredIndex, double width, double height) {
        if (center == null || center.getWorld() == null || !isFinite(center)) {
            return null;
        }

        int start = Math.floorMod(preferredIndex, TELEPORT_OFFSETS.length);
        for (int attempt = 0; attempt < TELEPORT_OFFSETS.length; attempt++) {
            int[] offset = TELEPORT_OFFSETS[(start + attempt) % TELEPORT_OFFSETS.length];
            int x = center.getBlockX() + offset[0];
            int z = center.getBlockZ() + offset[1];
            int baseY = center.getBlockY();

            for (int yOffset = -3; yOffset <= 3; yOffset++) {
                int y = baseY + yOffset;
                if (y <= center.getWorld().getMinHeight()
                        || y + height >= center.getWorld().getMaxHeight()) {
                    continue;
                }
                Location result = new Location(center.getWorld(), x + 0.5, y, z + 0.5,
                        center.getYaw(), center.getPitch());
                if (isSafeVolume(result, width, height)) return result;
            }
        }
        return null;
    }

    private static boolean isSafeVolume(Location location, double width, double height) {
        org.bukkit.World world = location.getWorld();
        double radius = Math.max(0.3, width / 2.0) + 0.05;
        int minX = (int) Math.floor(location.getX() - radius);
        int maxX = (int) Math.floor(location.getX() + radius);
        int minZ = (int) Math.floor(location.getZ() - radius);
        int maxZ = (int) Math.floor(location.getZ() + radius);
        int y = location.getBlockY();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)
                        || !world.getWorldBorder().isInside(new Location(world, x, y, z))
                        || !world.getWorldBorder().isInside(new Location(world, x + 1, y, z + 1))) return false;
                Block floor = world.getBlockAt(x, y - 1, z);
                if (!floor.getType().isSolid() || isHazard(floor.getType())) return false;
                for (int bodyY = y; bodyY < Math.ceil(y + height); bodyY++) {
                    if (!isSafeBlock(world.getBlockAt(x, bodyY, z))) return false;
                }
            }
        }
        return true;
    }

    private static boolean isHazard(Material type) {
        return switch (type) {
            case LAVA, WATER, FIRE, SOUL_FIRE, CAMPFIRE, SOUL_CAMPFIRE,
                    MAGMA_BLOCK, CACTUS, SWEET_BERRY_BUSH, POWDER_SNOW,
                    WITHER_ROSE, POINTED_DRIPSTONE, NETHER_PORTAL, END_PORTAL -> true;
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
        if (entity.isOnGround()) {
            yVelocity = destination.getY() - current.getY() > 0.6 ? 0.42 : 0.0;
        }
        entity.setVelocity(new Vector(
                x / horizontalLength * limitedSpeed,
                yVelocity,
                z / horizontalLength * limitedSpeed
        ));
        return true;
    }

    public static void stopHorizontal(Entity entity) {
        if (entity == null) {
            return;
        }
        Vector velocity = entity.getVelocity();
        entity.setVelocity(new Vector(0.0, velocity.getY(), 0.0));
    }

    private static boolean isSafeBlock(Block block) {
        Material type = block.getType();
        return block.isPassable() && !isHazard(type);
    }

    private static boolean isFinite(Location location) {
        return Double.isFinite(location.getX())
                && Double.isFinite(location.getY())
                && Double.isFinite(location.getZ());
    }
}
