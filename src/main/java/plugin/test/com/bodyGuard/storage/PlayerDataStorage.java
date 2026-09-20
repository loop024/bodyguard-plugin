package plugin.test.com.bodyGuard.storage;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** Stores owner-level choices that do not belong to an individual guard entity. */
public final class PlayerDataStorage {

    public enum State {
        COMPLETED,
        DISMISSED
    }

    private static final int CURRENT_VERSION = StorageSchema.PLAYERS;

    private final JavaPlugin plugin;
    private final SafeYamlFile safeFile;
    private final Map<UUID, State> states = new HashMap<>();
    private final Map<UUID, UUID> companions = new HashMap<>();
    private final Map<UUID, Set<UUID>> friends = new HashMap<>();
    private boolean dirty;
    private SafeYamlFile.SaveResult lastMutationResult = SafeYamlFile.SaveResult.SUCCESS;

    public PlayerDataStorage(JavaPlugin plugin) {
        this.plugin = plugin;
        this.safeFile = new SafeYamlFile(plugin, "players.yml", "players");
        load();
    }

    public boolean hasChoice(UUID playerId) {
        return playerId != null && states.containsKey(playerId);
    }

    public SafeYamlFile.SaveResult setState(UUID playerId, State state) {
        if (playerId == null || state == null) {
            lastMutationResult = SafeYamlFile.SaveResult.VALIDATION_FAILED;
            return lastMutationResult;
        }
        State previous = states.get(playerId);
        states.put(playerId, state);
        SafeYamlFile.SaveResult result = save();
        lastMutationResult = result;
        if (result != SafeYamlFile.SaveResult.SUCCESS) {
            if (previous == null) states.remove(playerId);
            else states.put(playerId, previous);
        }
        return result;
    }

    public UUID getCompanion(UUID ownerId) {
        return ownerId == null ? null : companions.get(ownerId);
    }

    public Map<UUID, UUID> getCompanions() {
        return new HashMap<>(companions);
    }

    public SafeYamlFile.SaveResult setCompanion(UUID ownerId, UUID guardId) {
        if (ownerId == null) {
            lastMutationResult = SafeYamlFile.SaveResult.VALIDATION_FAILED;
            return lastMutationResult;
        }
        UUID previous = companions.get(ownerId);
        if (guardId == null) companions.remove(ownerId);
        else companions.put(ownerId, guardId);
        SafeYamlFile.SaveResult result = save();
        lastMutationResult = result;
        if (result != SafeYamlFile.SaveResult.SUCCESS) {
            if (previous == null) companions.remove(ownerId);
            else companions.put(ownerId, previous);
        }
        return result;
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
            lastMutationResult = SafeYamlFile.SaveResult.VALIDATION_FAILED;
            return false;
        }
        boolean changed = friends.computeIfAbsent(ownerId, ignored -> new HashSet<>()).add(playerId);
        lastMutationResult = SafeYamlFile.SaveResult.SUCCESS;
        if (changed) {
            lastMutationResult = save();
        }
        if (changed && lastMutationResult != SafeYamlFile.SaveResult.SUCCESS) {
            friends.getOrDefault(ownerId, new HashSet<>()).remove(playerId);
            if (friends.getOrDefault(ownerId, Set.of()).isEmpty()) friends.remove(ownerId);
        }
        return changed;
    }

    public boolean removeFriend(UUID ownerId, UUID playerId) {
        Set<UUID> entries = friends.get(ownerId);
        boolean changed = entries != null && entries.remove(playerId);
        if (entries != null && entries.isEmpty()) friends.remove(ownerId);
        lastMutationResult = SafeYamlFile.SaveResult.SUCCESS;
        if (changed) {
            lastMutationResult = save();
        }
        if (changed && lastMutationResult != SafeYamlFile.SaveResult.SUCCESS) {
            friends.computeIfAbsent(ownerId, ignored -> new HashSet<>()).add(playerId);
        }
        return changed;
    }

    private void load() {
        states.clear();
        companions.clear();
        friends.clear();
        YamlConfiguration configuration = safeFile.load();
        ConfigurationSection players = configuration.getConfigurationSection("players");
        if (players == null) return;

        for (String idText : players.getKeys(false)) {
            UUID playerId;
            try {
                playerId = UUID.fromString(idText);
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("プレイヤー設定のUUIDを無視します: " + idText);
                continue;
            }
            String path = idText;
            String tutorial = players.getString(path + ".tutorial");
            if (tutorial != null && !tutorial.isBlank()) {
                try {
                    states.put(playerId, State.valueOf(tutorial.trim().toUpperCase(java.util.Locale.ROOT)));
                } catch (IllegalArgumentException exception) {
                    plugin.getLogger().warning("チュートリアル設定を無視します: " + idText);
                }
            }

            String companionText = players.getString(path + ".companion");
            if (companionText != null && !companionText.isBlank()) {
                try {
                    companions.put(playerId, UUID.fromString(companionText));
                } catch (IllegalArgumentException exception) {
                    plugin.getLogger().warning("相棒UUIDを無視します: " + idText);
                }
            }

            Object rawFriends = players.get(path + ".friends");
            if (rawFriends != null && !(rawFriends instanceof java.util.List<?>)) {
                plugin.getLogger().warning("仲間一覧を無視します（配列ではありません）: " + idText);
                continue;
            }
            for (String friendText : players.getStringList(path + ".friends")) {
                try {
                    UUID friendId = UUID.fromString(friendText);
                    if (!friendId.equals(playerId)) {
                        friends.computeIfAbsent(playerId, ignored -> new HashSet<>()).add(friendId);
                    }
                } catch (IllegalArgumentException exception) {
                    plugin.getLogger().warning("仲間UUIDを無視します: " + idText);
                }
            }
        }
        dirty = false;
    }

    public boolean isHealthy() { return safeFile.isHealthy(); }
    public boolean isDirty() { return dirty; }
    public SafeYamlFile.SaveResult getLastSaveResult() { return safeFile.getLastSaveResult(); }
    public SafeYamlFile.SaveResult getLastMutationResult() { return lastMutationResult; }
    public long getLastSaved() { return safeFile.getLastSaved(); }
    public long getLastAttempt() { return safeFile.getLastAttempt(); }
    public int getConsecutiveSaveFailures() { return safeFile.getConsecutiveFailures(); }
    public String getLastFailureReason() { return safeFile.getLastFailureReason(); }

    public SafeYamlFile.SaveResult retrySave() {
        return dirty ? save() : safeFile.getLastSaveResult();
    }

    private SafeYamlFile.SaveResult save() {
        dirty = true;
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("version", CURRENT_VERSION);
        configuration.createSection("players");
        Set<UUID> playerIds = new HashSet<>();
        playerIds.addAll(states.keySet());
        playerIds.addAll(companions.keySet());
        playerIds.addAll(friends.keySet());
        configuration.set("record-count", playerIds.size());
        for (UUID playerId : playerIds) {
            String path = "players." + playerId;
            State state = states.get(playerId);
            if (state != null) configuration.set(path + ".tutorial", state.name());
            UUID companion = companions.get(playerId);
            if (companion != null) configuration.set(path + ".companion", companion.toString());
            Set<UUID> friendSet = friends.get(playerId);
            if (friendSet != null) {
                configuration.set(path + ".friends", friendSet.stream()
                        .map(UUID::toString).sorted().toList());
            }
        }
        SafeYamlFile.SaveResult result = safeFile.saveWithResult(configuration);
        if (result == SafeYamlFile.SaveResult.SUCCESS) dirty = false;
        return result;
    }
}
