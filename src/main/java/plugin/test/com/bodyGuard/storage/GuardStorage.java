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
import plugin.test.com.bodyGuard.guard.SavedPosition;

/** YAML persistence for the small registry that complements Entity PDC data. */
public final class GuardStorage {

    private final JavaPlugin plugin;
    private final File file;
    private final SafeYamlFile safeFile;
    private final SafeYamlFile quarantineFile;

    public GuardStorage(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "guards.yml");
        this.safeFile = new SafeYamlFile(plugin, "guards.yml");
        this.quarantineFile = new SafeYamlFile(plugin, "quarantine.yml", "entries");
    }

    public Map<UUID, GuardData> load() {
        Map<UUID, GuardData> result = new LinkedHashMap<>();
        YamlConfiguration configuration = safeFile.load();
        int version = configuration.getInt("version", 0);
        if (version > 4) {
            throw new IllegalStateException("guards.yml は未対応の新しい形式です (version="
                    + version + ")。古いプラグインで上書きしません。");
        }
        YamlConfiguration quarantine = null;

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
                quarantine = quarantine(quarantine, guards, idText, "護衛または所有者UUIDが不正です");
                continue;
            }

            EntityType mobType;
            try {
                mobType = EntityType.valueOf(guards.getString(idText + ".mob-type", "ZOMBIE")
                        .toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                quarantine = quarantine(quarantine, guards, idText, "mob-typeが不正です");
                continue;
            }

            GuardMode mode = GuardMode.fromString(guards.getString(idText + ".mode", "follow"));
            String name = guards.getString(idText + ".name", "BodyGuard");
            String ownerName = guards.getString(idText + ".owner-name", "Player");
            int nameNumber = Math.max(0, guards.getInt(idText + ".name-number", 0));

            try {
                GuardData data = new GuardData(
                        guardId, ownerId, mobType, mode, name, ownerName, null, null, nameNumber);
                data.setSavedPositions(SavedPosition.read(guards, idText + ".anchor-location"),
                        SavedPosition.read(guards, idText + ".last-location"));
                data.setReleasePending(guards.getBoolean(idText + ".release-pending", false));
                data.setDeletionPending(guards.getBoolean(idText + ".deletion-pending", false));
                data.setFavorite(guards.getBoolean(idText + ".favorite", false));
                data.setOperationCompleted(guards.getBoolean(idText + ".operation-completed", false));
                data.setLastSeen(guards.getLong(idText + ".last-seen", 0));
                data.setMissingSince(guards.getLong(idText + ".missing-since", 0));
                data.setDeathConfirmed(guards.getBoolean(idText + ".death-confirmed", false));
                result.put(guardId, data);
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING,
                        "Malformed BodyGuard entry will be quarantined: " + idText, exception);
                quarantine = quarantine(quarantine, guards, idText,
                        exception.getClass().getSimpleName() + ": "
                                + (exception.getMessage() == null ? "詳細なし" : exception.getMessage()));
            }
        }
        if (quarantine != null && !quarantineFile.save(quarantine)) {
            throw new IllegalStateException("不正な護衛データをquarantine.ymlへ保全できません。"
                    + "元データを守るため読み込みを中止します。");
        }
        return result;
    }

    private YamlConfiguration quarantine(YamlConfiguration target, ConfigurationSection guards,
                                         String sourceId, String reason) {
        if (target == null) {
            target = quarantineFile.load();
            if (!target.isConfigurationSection("entries")) target.createSection("entries");
        }
        String entry = "entries." + System.currentTimeMillis() + "-" + UUID.randomUUID();
        target.set(entry + ".source-file", "guards.yml");
        target.set(entry + ".source-id", sourceId);
        target.set(entry + ".reason", reason);
        target.set(entry + ".quarantined-at", System.currentTimeMillis());
        ConfigurationSection source = guards.getConfigurationSection(sourceId);
        if (source != null) {
            for (Map.Entry<String, Object> value : source.getValues(true).entrySet()) {
                if (!(value.getValue() instanceof ConfigurationSection)) {
                    target.set(entry + ".raw." + value.getKey(), value.getValue());
                }
            }
        } else {
            target.set(entry + ".raw-value", guards.get(sourceId));
        }
        return target;
    }

    public boolean save(Collection<GuardData> guardData) {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("version", 4);
        configuration.set("record-count", guardData.stream().filter(java.util.Objects::nonNull).count());
        configuration.createSection("guards");

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
            configuration.set(path + ".last-seen", data.getLastSeen());
            configuration.set(path + ".missing-since", data.getMissingSince());
            configuration.set(path + ".death-confirmed", data.isDeathConfirmed());
            if (data.getSavedAnchor() != null) data.getSavedAnchor().write(configuration, path + ".anchor-location");
            if (data.getSavedLast() != null) data.getSavedLast().write(configuration, path + ".last-location");
        }

        return safeFile.save(configuration);
    }

    public boolean isHealthy() { return safeFile.isHealthy(); }
    public long getLastSaved() { return safeFile.getLastSaved(); }

}
