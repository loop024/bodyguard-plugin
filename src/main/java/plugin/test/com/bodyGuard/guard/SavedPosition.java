package plugin.test.com.bodyGuard.guard;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

/** Coordinates survive world unloads and delayed world creation at startup. */
public record SavedPosition(UUID worldId, String worldName, double x, double y, double z,
                            float yaw, float pitch) {
    public static SavedPosition of(Location location) {
        if (location == null || location.getWorld() == null) return null;
        return new SavedPosition(location.getWorld().getUID(), location.getWorld().getName(),
                location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
    }

    public Location resolve() {
        // A different world with a reused name must never inherit this position.
        // Legacy records without a UUID remain unresolved until an administrator
        // repairs them; the display still retains worldName and coordinates.
        World world = worldId == null ? null : Bukkit.getWorld(worldId);
        return world == null ? null : new Location(world, x, y, z, yaw, pitch);
    }

    public static SavedPosition read(ConfigurationSection section, String path) {
        if (!section.isConfigurationSection(path)) return null;
        String name = section.getString(path + ".world", "");
        String idText = section.getString(path + ".world-uuid");
        UUID id;
        try {
            id = idText == null || idText.isBlank() ? null : UUID.fromString(idText);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid world UUID: " + path, exception);
        }
        double x = section.getDouble(path + ".x", Double.NaN);
        double y = section.getDouble(path + ".y", Double.NaN);
        double z = section.getDouble(path + ".z", Double.NaN);
        float yaw = (float) section.getDouble(path + ".yaw", 0);
        float pitch = (float) section.getDouble(path + ".pitch", 0);
        if ((id == null && name.isBlank()) || !Double.isFinite(x) || !Double.isFinite(y)
                || !Double.isFinite(z) || !Float.isFinite(yaw) || !Float.isFinite(pitch)
                || Math.abs(x) > 30000000 || Math.abs(z) > 30000000) {
            throw new IllegalArgumentException("Invalid saved location: " + path);
        }
        return new SavedPosition(id, name, x, y, z, yaw, pitch);
    }

    public void write(ConfigurationSection section, String path) {
        section.set(path + ".world", worldName);
        section.set(path + ".world-uuid", worldId == null ? null : worldId.toString());
        section.set(path + ".x", x);
        section.set(path + ".y", y);
        section.set(path + ".z", z);
        section.set(path + ".yaw", yaw);
        section.set(path + ".pitch", pitch);
    }
}
