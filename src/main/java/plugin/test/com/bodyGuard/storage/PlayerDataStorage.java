package plugin.test.com.bodyGuard.storage;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** Stores owner-level choices that do not belong to an individual guard entity. */
public final class PlayerDataStorage {

    public enum State {
        COMPLETED,
        DISMISSED
    }

    private final JavaPlugin plugin;
    private final File file;
    private final Map<UUID, State> states = new HashMap<>();
    private final Map<UUID, UUID> companions = new HashMap<>();

    public PlayerDataStorage(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "players.yml");
        load();
    }

    public boolean hasChoice(UUID playerId) {
        return playerId != null && states.containsKey(playerId);
    }

    public void setState(UUID playerId, State state) {
        if (playerId == null || state == null) {
            return;
        }
        states.put(playerId, state);
        save();
    }

    public UUID getCompanion(UUID ownerId) {
        return ownerId == null ? null : companions.get(ownerId);
    }

    public Map<UUID, UUID> getCompanions() {
        return new HashMap<>(companions);
    }

    public Map<UUID, UUID> getCompanions() {
        return new HashMap<>(companions);
    }

    public void setCompanion(UUID ownerId, UUID guardId) {
        if (ownerId == null) {
            return;
        }
        if (guardId == null) {
            companions.remove(ownerId);
        } else {
            companions.put(ownerId, guardId);
        }
        save();
    }

    private void load() {
        states.clear();
        companions.clear();
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);
        for (String idText : configuration.getConfigurationSection("players") == null
                ? java.util.Set.<String>of()
                : configuration.getConfigurationSection("players").getKeys(false)) {
            try {
                UUID playerId = UUID.fromString(idText);
                State state = State.valueOf(configuration.getString(
                        "players." + idText + ".tutorial", "COMPLETED").toUpperCase(java.util.Locale.ROOT));
                states.put(playerId, state);
                String companionText = configuration.getString("players." + idText + ".companion");
                if (companionText != null && !companionText.isBlank()) {
                    companions.put(playerId, UUID.fromString(companionText));
                }
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Ignoring invalid tutorial player entry: " + idText);
            }
        }
    }

    private void save() {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("version", 1);
        for (Map.Entry<UUID, State> entry : states.entrySet()) {
            configuration.set("players." + entry.getKey() + ".tutorial", entry.getValue().name());
        }
        for (Map.Entry<UUID, UUID> entry : companions.entrySet()) {
            configuration.set("players." + entry.getKey() + ".companion", entry.getValue().toString());
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            plugin.getLogger().warning("Could not create plugin data folder for players.yml.");
        }
        File temporary = null;
        try {
            temporary = File.createTempFile("players-", ".tmp", parent);
            configuration.save(temporary);
            if (file.exists()) {
                Files.copy(file.toPath(), new File(parent, "players.yml.bak").toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(temporary.toPath(), file.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not save players.yml", exception);
        } finally {
            if (temporary != null && temporary.exists() && !temporary.delete()) {
                temporary.deleteOnExit();
            }
        }
    }
}
