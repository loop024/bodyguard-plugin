package plugin.test.com.bodyGuard.storage;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.java.JavaPlugin;

import plugin.test.com.bodyGuard.guard.GuardData;
import plugin.test.com.bodyGuard.guard.GuardMode;

/** YAML persistence for the small registry that complements Entity PDC data. */
public final class GuardStorage {

    private final JavaPlugin plugin;
    private final File file;
    private final SafeYamlFile safeFile;

    public GuardStorage(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "guards.yml");
        this.safeFile = new SafeYamlFile(plugin, "guards.yml");
    }

    public Map<UUID, GuardData> load() {
        Map<UUID, GuardData> result = new LinkedHashMap<>();
        YamlConfiguration configuration = safeFile.load();

        ConfigurationSection guards = configuration.getConfigurationSection("guards");
        if (guards == null) {
            return result;
        }

        for (String idText : guards.getKeys(false)) {
            UUID guardId;
            UUID ownerId;
            try {
                guardId = UUID.fromString(idText);
                ownerId = UUID.fromString(guards.getString(idText + ".owner", ""));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Ignoring invalid BodyGuard UUID entry: " + idText);
                continue;
            }

            EntityType mobType;
            try {
                mobType = EntityType.valueOf(guards.getString(idText + ".mob-type", "ZOMBIE")
                        .toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Ignoring guard with invalid mob type: " + idText);
                continue;
            }

            GuardMode mode = GuardMode.fromString(guards.getString(idText + ".mode", "follow"));
            String name = guards.getString(idText + ".name", "BodyGuard");
            String ownerName = guards.getString(idText + ".owner-name", "Player");
            int nameNumber = Math.max(0, guards.getInt(idText + ".name-number", 0));
            Location anchor = readLocation(guards, idText + ".anchor-location");
            Location last = readLocation(guards, idText + ".last-location");

            try {
                GuardData data = new GuardData(
                        guardId, ownerId, mobType, mode, name, ownerName, anchor, last, nameNumber);
                data.setReleasePending(guards.getBoolean(idText + ".release-pending", false));
                data.setDeletionPending(guards.getBoolean(idText + ".deletion-pending", false));
                data.setFavorite(guards.getBoolean(idText + ".favorite", false));
                data.setOperationCompleted(guards.getBoolean(idText + ".operation-completed", false));
                result.put(guardId, data);
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING,
                        "Ignoring malformed BodyGuard entry: " + idText, exception);
            }
        }
        return result;
    }

    public boolean save(Collection<GuardData> guardData) {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("version", 4);

        for (GuardData data : guardData) {
            if (data == null) {
                continue;
            }
            String path = "guards." + data.getGuardId();
            configuration.set(path + ".owner", data.getOwnerId().toString());
            configuration.set(path + ".owner-name", data.getOwnerName());
            configuration.set(path + ".mob-type", data.getMobType().name());
            configuration.set(path + ".mode", data.getMode().commandName());
            configuration.set(path + ".name", data.getName());
            configuration.set(path + ".name-number", data.getNameNumber());
            configuration.set(path + ".release-pending", data.isReleasePending());
            configuration.set(path + ".deletion-pending", data.isDeletionPending());
            configuration.set(path + ".favorite", data.isFavorite());
            configuration.set(path + ".operation-completed", data.isOperationCompleted());
            writeLocation(configuration, path + ".anchor-location", data.getAnchorLocation());
            writeLocation(configuration, path + ".last-location", data.getLastLocation());
        }

        return safeFile.save(configuration);
    }

    public boolean isHealthy() { return safeFile.isHealthy(); }
    public long getLastSaved() { return safeFile.getLastSaved(); }

    private Location readLocation(ConfigurationSection root, String path) {
        String worldName = root.getString(path + ".world");
        World world = null;
        String worldUuid = root.getString(path + ".world-uuid");
        if (worldUuid != null && !worldUuid.isBlank()) {
            try {
                world = Bukkit.getWorld(UUID.fromString(worldUuid));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Ignoring invalid world UUID for saved BodyGuard location: " + worldUuid);
            }
        }
        if (world == null && worldName != null && !worldName.isBlank()) {
            world = Bukkit.getWorld(worldName);
        }
        if (world == null) {
            plugin.getLogger().warning("World is not loaded for saved BodyGuard location: "
                    + (worldName == null ? "unknown" : worldName));
            return null;
        }
        double x = root.getDouble(path + ".x", Double.NaN);
        double y = root.getDouble(path + ".y", Double.NaN);
        double z = root.getDouble(path + ".z", Double.NaN);
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            return null;
        }
        float yaw = (float) root.getDouble(path + ".yaw", 0.0);
        float pitch = (float) root.getDouble(path + ".pitch", 0.0);
        return new Location(world, x, y, z, yaw, pitch);
    }

    private void writeLocation(YamlConfiguration configuration, String path, Location location) {
        if (location == null || location.getWorld() == null
                || !Double.isFinite(location.getX())
                || !Double.isFinite(location.getY())
                || !Double.isFinite(location.getZ())) {
            return;
        }
        configuration.set(path + ".world", location.getWorld().getName());
        configuration.set(path + ".world-uuid", location.getWorld().getUID().toString());
        configuration.set(path + ".x", location.getX());
        configuration.set(path + ".y", location.getY());
        configuration.set(path + ".z", location.getZ());
        configuration.set(path + ".yaw", location.getYaw());
        configuration.set(path + ".pitch", location.getPitch());
    }
}
