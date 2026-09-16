package plugin.test.com.bodyGuard.storage;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
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
    private final SafeYamlFile safeFile;
    private boolean dirty;
    private final Map<UUID, State> states = new HashMap<>();
    private final Map<UUID, UUID> companions = new HashMap<>();
    private final Map<UUID, Set<UUID>> friends = new HashMap<>();

    public PlayerDataStorage(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "players.yml");
        this.safeFile = new SafeYamlFile(plugin, "players.yml");
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

    public boolean isFriend(UUID ownerId, UUID playerId) {
        return ownerId != null && playerId != null
                && friends.getOrDefault(ownerId, Set.of()).contains(playerId);
    }

    public Set<UUID> getFriends(UUID ownerId) {
        return ownerId == null ? Set.of()
                : Set.copyOf(friends.getOrDefault(ownerId, Set.of()));
    }

    public boolean addFriend(UUID ownerId, UUID playerId) {
        if (ownerId == null || playerId == null || ownerId.equals(playerId)) {
            return false;
        }
        boolean changed = friends.computeIfAbsent(ownerId, ignored -> new HashSet<>()).add(playerId);
        if (changed) save();
        return changed;
    }

    public boolean removeFriend(UUID ownerId, UUID playerId) {
        Set<UUID> entries = friends.get(ownerId);
        boolean changed = entries != null && entries.remove(playerId);
        if (entries != null && entries.isEmpty()) friends.remove(ownerId);
        if (changed) save();
        return changed;
    }

    private void load() {
        states.clear();
        companions.clear();
        friends.clear();
        YamlConfiguration configuration = safeFile.load();
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
                for (String friendText : configuration.getStringList("players." + idText + ".friends")) {
                    try {
                        UUID friendId = UUID.fromString(friendText);
                        if (!friendId.equals(playerId)) {
                            friends.computeIfAbsent(playerId, ignored -> new HashSet<>()).add(friendId);
                        }
                    } catch (IllegalArgumentException exception) {
                        plugin.getLogger().warning("Ignoring invalid friend UUID for " + idText + ": " + friendText);
                    }
                }
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Ignoring invalid tutorial player entry: " + idText);
            }
        }
    }

    public boolean isHealthy() { return safeFile.isHealthy(); }

    public void retrySave() { if (dirty) save(); }

    private void save() {
        dirty = true;
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("version", 2);
        for (Map.Entry<UUID, State> entry : states.entrySet()) {
            configuration.set("players." + entry.getKey() + ".tutorial", entry.getValue().name());
        }
        for (Map.Entry<UUID, UUID> entry : companions.entrySet()) {
            configuration.set("players." + entry.getKey() + ".companion", entry.getValue().toString());
        }
        for (Map.Entry<UUID, Set<UUID>> entry : friends.entrySet()) {
            configuration.set("players." + entry.getKey() + ".friends",
                    entry.getValue().stream().map(UUID::toString).sorted().toList());
        }
        if (safeFile.save(configuration)) dirty = false;
    }
}
