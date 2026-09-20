package plugin.test.com.bodyGuard;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.logging.Level;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.command.PluginCommand;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.ConfigurationSection;

import plugin.test.com.bodyGuard.command.BodyGuardCommand;
import plugin.test.com.bodyGuard.command.BodyGuardTabCompleter;
import plugin.test.com.bodyGuard.gui.BodyGuardGui;
import plugin.test.com.bodyGuard.guard.GuardManager;
import plugin.test.com.bodyGuard.guard.GuardData;
import plugin.test.com.bodyGuard.guard.GuardTask;
import plugin.test.com.bodyGuard.guard.RoleDefinition;
import plugin.test.com.bodyGuard.listener.CombatListener;
import plugin.test.com.bodyGuard.listener.GuardDeathListener;
import plugin.test.com.bodyGuard.listener.GuardFeedbackListener;
import plugin.test.com.bodyGuard.listener.MenuOpenerListener;
import plugin.test.com.bodyGuard.listener.PlayerListener;
import plugin.test.com.bodyGuard.listener.TargetListener;
import plugin.test.com.bodyGuard.storage.GuardStorage;
import plugin.test.com.bodyGuard.storage.PlayerDataStorage;
import plugin.test.com.bodyGuard.storage.PlayerSettings;
import plugin.test.com.bodyGuard.storage.OperationLedgerStorage;
import plugin.test.com.bodyGuard.util.EntityUtil;
import plugin.test.com.bodyGuard.util.MessageUtil;

public final class BodyGuard extends JavaPlugin {

    private static final Set<EntityType> BUILT_IN_ALLOWED_MOBS = Set.of(
            EntityType.ZOMBIE,
            EntityType.SKELETON,
            EntityType.HUSK,
            EntityType.STRAY,
            EntityType.DROWNED,
            EntityType.BOGGED,
            EntityType.WITHER_SKELETON,
            EntityType.ZOMBIFIED_PIGLIN,
            EntityType.SPIDER,
            EntityType.CAVE_SPIDER,
            EntityType.PILLAGER,
            EntityType.VINDICATOR,
            EntityType.IRON_GOLEM,
            EntityType.SNOW_GOLEM,
            EntityType.RAVAGER,
            EntityType.ZOGLIN,
            EntityType.WOLF,
            EntityType.POLAR_BEAR,
            EntityType.SILVERFISH,
            EntityType.ENDERMITE,
            EntityType.ZOMBIE_VILLAGER
    );

    private NamespacedKeys keys;
    private MessageUtil messages;
    private GuardStorage storage;
    private PlayerDataStorage playerDataStorage;
    private GuardManager guardManager;
    private OperationLedgerStorage operationLedger;
    private Set<EntityType> allowedMobTypes = Collections.emptySet();
    private Map<String, RoleDefinition> roleDefinitions = Collections.emptyMap();
    private GuardTask guardTask;
    private BodyGuardGui gui;
    private boolean registryInitialized;
    private FileConfiguration activeConfiguration;

    @Override
    public FileConfiguration getConfig() {
        return activeConfiguration == null ? super.getConfig() : activeConfiguration;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("messages.yml", false);

        keys = new NamespacedKeys(this);
        messages = new MessageUtil(this);
        if (!reloadSettings()) {
            getLogger().severe("設定またはメッセージが不正なため BodyGuard を停止します。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        storage = new GuardStorage(this);
        try {
            operationLedger = new OperationLedgerStorage(this);
            operationLedger.load();
            java.util.Map<java.util.UUID, GuardData> saved = storage.load();
            playerDataStorage = new PlayerDataStorage(this);
            guardManager = new GuardManager(this, storage, keys, playerDataStorage, operationLedger);
            guardManager.load(saved);
            registryInitialized = true;
        } catch (RuntimeException failure) {
            getLogger().log(Level.SEVERE, "保存データを安全に読み込めないため BodyGuard を停止します。", failure);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        PluginCommand command = getCommand("bodyguard");
        if (command == null) {
            getLogger().severe("Command 'bodyguard' is missing from plugin.yml. BodyGuard was disabled.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        BodyGuardCommand commandExecutor = new BodyGuardCommand(this, guardManager, messages);
        gui = new BodyGuardGui(this, guardManager, messages, commandExecutor, playerDataStorage);
        commandExecutor.setGui(gui);

        getServer().getPluginManager().registerEvents(new CombatListener(this, guardManager), this);
        getServer().getPluginManager().registerEvents(new TargetListener(guardManager), this);
        getServer().getPluginManager().registerEvents(new GuardDeathListener(this, guardManager), this);
        getServer().getPluginManager().registerEvents(new GuardFeedbackListener(this, guardManager), this);
        getServer().getPluginManager().registerEvents(new PlayerListener(this, guardManager, gui), this);
        getServer().getPluginManager().registerEvents(new MenuOpenerListener(this, messages), this);
        getServer().getPluginManager().registerEvents(gui, this);
        command.setExecutor(commandExecutor);
        command.setTabCompleter(new BodyGuardTabCompleter(this));

        int recoveredGuards = guardManager.reconcileAlreadyLoadedEntities();
        if (recoveredGuards > 0) {
            getLogger().info("Recovered " + recoveredGuards
                    + " BodyGuard(s) from already-loaded chunks.");
        }

        guardTask = new GuardTask(this, guardManager);
        guardTask.runTaskTimer(this, 20L, 10L);
        gui.startTasks();

        getLogger().info("BodyGuard v1.0.0 enabled.");
    }

    @Override
    public void onDisable() {
        if (gui != null) {
            gui.closeAllMenus();
        }
        if (guardTask != null) {
            guardTask.cancel();
        }
        if (gui != null) {
            gui.stopTasks();
        }
        if (guardManager != null) {
            try {
                guardManager.releaseManagedChunks();
            } catch (RuntimeException failure) {
                getLogger().log(Level.SEVERE, "管理チャンクの解放に失敗しました。", failure);
            }
            if (registryInitialized) {
                try {
                    if (!guardManager.forceSave()) {
                        getLogger().severe("停止時の護衛データ保存に失敗しました。guards.yml の保存状態を確認してください。");
                    }
                } catch (RuntimeException failure) {
                    getLogger().log(Level.SEVERE, "停止時の護衛データ保存に失敗しました。", failure);
                }
            }
        }
        if (registryInitialized && playerDataStorage != null) {
            try {
                playerDataStorage.retrySave();
            } catch (RuntimeException failure) {
                getLogger().log(Level.SEVERE, "停止時のプレイヤーデータ保存に失敗しました。", failure);
            }
        }
        HandlerList.unregisterAll(this);
    }

    /** Reloads config and messages as one validated transaction. */
    public boolean reloadSettings() {
        YamlConfiguration candidate = new YamlConfiguration();
        try {
            candidate.load(new java.io.File(getDataFolder(), "config.yml"));
        } catch (Exception failure) {
            getLogger().log(Level.SEVERE, "config.ymlの検証に失敗しました。現在の設定を維持します。", failure);
            return false;
        }
        YamlConfiguration messageCandidate;
        try {
            messageCandidate = messages == null ? null : messages.loadCandidate();
        } catch (Exception failure) {
            getLogger().log(Level.SEVERE, "messages.ymlの検証に失敗しました。現在の設定を維持します。", failure);
            return false;
        }
        org.bukkit.configuration.Configuration defaults = super.getConfig().getDefaults();
        if (defaults != null) candidate.setDefaults(defaults);
        if (!validateConfiguration(candidate)) {
            getLogger().severe("config.ymlまたはmessages.ymlが不正です。現在の設定を維持します。");
            return false;
        }
        Set<EntityType> nextAllowedMobTypes = readAllowedMobTypes(candidate);
        Map<String, RoleDefinition> nextRoleDefinitions = readRoleDefinitions(candidate);
        activeConfiguration = candidate;
        if (messageCandidate != null) messages.applyCandidate(messageCandidate);
        allowedMobTypes = nextAllowedMobTypes;
        roleDefinitions = nextRoleDefinitions;
        if (guardManager != null) {
            guardManager.requestProtectionRefreshAll();
        }
        if (gui != null) {
            gui.restartTasks();
        }
        return true;
    }

    private boolean validateConfiguration(YamlConfiguration configuration) {
        try {
            requireInteger(configuration, "storage.autosave-seconds", 0, 3600);
            requireInteger(configuration, "limits.max-guards-per-player", 1, 1000);
            requireBoolean(configuration, "guard-management.keep-chunks-loaded-while-owner-online");
            requireInteger(configuration, "guard-management.max-loaded-chunks", 1, 1024);
            requireInteger(configuration, "guard-management.max-loaded-chunks-per-owner", 1, 256);
            requireInteger(configuration, "guard-management.chunk-loads-per-cycle", 1, 16);
            requireInteger(configuration, "guard-management.search-chunks-per-cycle", 1, 256);
            requireInteger(configuration, "performance.max-guards-per-cycle", 10, 1000);
            int maxChunks = configuration.getInt("guard-management.max-loaded-chunks", 64);
            int ownerChunks = configuration.getInt("guard-management.max-loaded-chunks-per-owner", 16);
            if (ownerChunks > maxChunks) {
                throw new IllegalArgumentException(
                        "guard-management.max-loaded-chunks-per-ownerが全体上限を超えています");
            }
            requireFinite(configuration, "follow.start-distance", 0.0, 1024.0);
            requireFinite(configuration, "follow.teleport-distance", 1.0, 4096.0);
            requireFinite(configuration, "follow.move-speed", 0.05, 1.5);
            requireFinite(configuration, "teleport.max-distance", 1.0, 4096.0);
            requireBoolean(configuration, "teleport.different-world");
            requireBoolean(configuration, "role-protection.enabled");
            validateRoleDefinitions(configuration);
            requireString(configuration, "menu-opener.material");
            String menuMaterial = configuration.getString("menu-opener.material", "NETHER_STAR");
            if (Material.matchMaterial(menuMaterial) == null) {
                throw new IllegalArgumentException("menu-opener.materialが不正です");
            }
            requireString(configuration, "display.nameplate.mode");
            NameplateMode.valueOf(configuration.getString("display.nameplate.mode", "NAME_HEALTH_MODE")
                    .trim().toUpperCase(Locale.ROOT));
            requireString(configuration, "effects.gui-sounds.success-sound");
            Sound.valueOf(configuration.getString("effects.gui-sounds.success-sound", "UI_BUTTON_CLICK")
                    .trim().toUpperCase(Locale.ROOT));
            requireString(configuration, "effects.gui-sounds.failure-sound");
            Sound.valueOf(configuration.getString("effects.gui-sounds.failure-sound", "BLOCK_NOTE_BLOCK_BASS")
                    .trim().toUpperCase(Locale.ROOT));
            java.util.List<String> configuredMobs = configuration.getStringList("allowed-mobs");
            if (configuredMobs.isEmpty() && configuration.contains("allowed-mobs")) {
                throw new IllegalArgumentException("allowed-mobsが空です");
            }
            for (String value : configuredMobs) {
                EntityType type = parseEntityType(value);
                if (type == null || !isSupportedMobType(type)) {
                    throw new IllegalArgumentException("allowed-mobsに未対応の種類があります: " + value);
                }
            }
            return true;
        } catch (RuntimeException failure) {
            getLogger().warning("設定の検証に失敗しました: " + failure.getMessage());
            return false;
        }
    }

    private void requireBoolean(YamlConfiguration configuration, String path) {
        Object value = configuration.get(path);
        if (value == null) return;
        if (!(value instanceof Boolean)) throw new IllegalArgumentException(path + " はbooleanではありません");
    }

    private void requireInteger(YamlConfiguration configuration, String path, int minimum, int maximum) {
        Object value = configuration.get(path);
        if (value == null) return;
        if (!(value instanceof Number number) || number.doubleValue() != number.intValue()
                || number.intValue() < minimum || number.intValue() > maximum) {
            throw new IllegalArgumentException(path + " の範囲または型が不正です");
        }
    }

    private void requireFinite(YamlConfiguration configuration, String path,
                               double minimum, double maximum) {
        Object value = configuration.get(path);
        if (value == null) return;
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() < minimum || number.doubleValue() > maximum) {
            throw new IllegalArgumentException(path + " の範囲または型が不正です");
        }
    }

    private void requireString(YamlConfiguration configuration, String path) {
        Object value = configuration.get(path);
        if (value == null) return;
        if (!(value instanceof String string) || string.isBlank()) {
            throw new IllegalArgumentException(path + " は文字列ではありません");
        }
    }

    private Set<EntityType> readAllowedMobTypes(FileConfiguration configuration) {
        Set<EntityType> result = new LinkedHashSet<>();
        for (String value : configuration.getStringList("allowed-mobs")) {
            if (value == null || value.isBlank()) {
                continue;
            }
            EntityType type = parseEntityType(value);
            if (type == null || !isSupportedMobType(type)) {
                getLogger().warning("Ignoring unsupported allowed-mobs entry: " + value);
                continue;
            }
            result.add(type);
        }

        if (result.isEmpty()) {
            getLogger().warning("allowed-mobs is empty or invalid. Restoring the built-in mob list.");
            result.addAll(BUILT_IN_ALLOWED_MOBS);
        }
        return Collections.unmodifiableSet(result);
    }

    private void validateRoleDefinitions(YamlConfiguration configuration) {
        ConfigurationSection roles = configuration.getConfigurationSection("role-protection.roles");
        if (roles == null) {
            if (configuration.contains("role-protection.roles")) {
                throw new IllegalArgumentException(
                        "role-protection.roles はセクションで指定してください");
            }
            return;
        }
        for (String roleId : roles.getKeys(false)) {
            if (roleId == null || !roleId.matches("[a-z0-9_-]{1,32}")) {
                throw new IllegalArgumentException(
                        "role-protection.rolesの役職IDが不正です: " + roleId);
            }
            ConfigurationSection role = roles.getConfigurationSection(roleId);
            if (role == null) {
                throw new IllegalArgumentException("role-protection.roles." + roleId
                        + " はセクションではありません");
            }
            Object permission = role.get("permission");
            if (!(permission instanceof String value) || value.isBlank()) {
                throw new IllegalArgumentException("role-protection.roles." + roleId
                        + ".permission は空にできません");
            }
            if (value.length() > 128) {
                throw new IllegalArgumentException("role-protection.roles." + roleId
                        + ".permission が長すぎます");
            }
            Object priority = role.get("priority", 0);
            if (!(priority instanceof Number number)
                    || number.doubleValue() != number.intValue()) {
                throw new IllegalArgumentException("role-protection.roles." + roleId
                        + ".priority は整数で指定してください");
            }
        }
    }

    private Map<String, RoleDefinition> readRoleDefinitions(FileConfiguration configuration) {
        ConfigurationSection roles = configuration.getConfigurationSection("role-protection.roles");
        if (roles == null) return Collections.emptyMap();
        Map<String, RoleDefinition> result = new LinkedHashMap<>();
        for (String roleId : roles.getKeys(false)) {
            ConfigurationSection role = roles.getConfigurationSection(roleId);
            if (role == null) continue;
            String permission = role.getString("permission");
            if (permission == null || permission.isBlank()) continue;
            result.put(roleId, new RoleDefinition(roleId, permission,
                    role.getInt("priority", 0)));
        }
        return Collections.unmodifiableMap(result);
    }

    public EntityType parseEntityType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (normalized.startsWith("MINECRAFT:")) {
            normalized = normalized.substring("MINECRAFT:".length());
        }
        try {
            return EntityType.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public boolean isSupportedMobType(EntityType type) {
        if (type == null || !type.isAlive() || !type.isSpawnable()) {
            return false;
        }
        Class<? extends Entity> entityClass = type.getEntityClass();
        return entityClass != null && Mob.class.isAssignableFrom(entityClass);
    }

    public boolean isAllowedMobType(EntityType type) {
        return type != null && allowedMobTypes.contains(type);
    }

    public Set<EntityType> getAllowedMobTypes() {
        return allowedMobTypes;
    }

    public boolean isRoleProtectionEnabled() {
        return getConfig().getBoolean("role-protection.enabled", true);
    }

    public RoleDefinition getRoleDefinition(String roleId) {
        return roleId == null ? null : roleDefinitions.get(roleId);
    }

    public List<RoleDefinition> getRoleDefinitions() {
        return roleDefinitions.values().stream()
                .sorted(java.util.Comparator.comparingInt(RoleDefinition::priority).reversed()
                        .thenComparing(RoleDefinition::id))
                .toList();
    }

    public NamespacedKeys getKeys() {
        return keys;
    }

    public MessageUtil getMessages() {
        return messages;
    }

    public GuardStorage getStorage() {
        return storage;
    }

    public GuardManager getGuardManager() {
        return guardManager;
    }

    public OperationLedgerStorage getOperationLedger() {
        return operationLedger;
    }

    public boolean isRegistryInitialized() {
        return registryInitialized;
    }

    public int getMaxGuardsPerPlayer() {
        return intSetting("limits.max-guards-per-player", 10, 1, 1000);
    }

    public int getAutosaveIntervalTicks() {
        return intSetting("storage.autosave-seconds", 60, 0, 3600) * 20;
    }

    public int getMaxGuardsPerCycle() {
        return intSetting("performance.max-guards-per-cycle", 100, 10, 1000);
    }

    public double getFollowStartDistance() {
        return doubleSetting("follow.start-distance", 5.0, 0.0, 1024.0);
    }

    public double getFollowTeleportDistance() {
        double fallback = 30.0;
        double value = doubleSetting("follow.teleport-distance", fallback, 1.0, 4096.0);
        return Math.max(value, getFollowStartDistance());
    }

    public double getFollowMoveSpeed() {
        return doubleSetting("follow.move-speed", 0.32, 0.05, 1.5);
    }

    public boolean shouldTeleportDifferentWorld() {
        return getConfig().getBoolean("teleport.different-world", true);
    }

    public double getTeleportMaxDistance() {
        return doubleSetting("teleport.max-distance", 30.0, 1.0, 4096.0);
    }

    public boolean shouldDefendAgainstPlayers() {
        return getConfig().getBoolean("combat.defend-against-players", true);
    }

    public boolean shouldAssistOwnerAttacks() {
        return getConfig().getBoolean("combat.assist-owner-attacks", true);
    }

    public boolean shouldDefendOwner() {
        return getConfig().getBoolean("combat.defend-owner", true);
    }

    public double getTargetRange() {
        return doubleSetting("combat.target-range", 25.0, 1.0, 4096.0);
    }

    public long getCombatGraceMillis() {
        double seconds = doubleSetting("combat.combat-grace-seconds", 8.0, 0.5, 300.0);
        return Math.round(seconds * 1000.0);
    }

    public boolean ownerCanDamageGuards() {
        return getConfig().getBoolean("friendly-fire.owner-can-damage-guards", false);
    }

    public boolean guardsCanDamageOwner() {
        return getConfig().getBoolean("friendly-fire.guards-can-damage-owner", false);
    }

    public boolean guardsCanDamageEachOther() {
        return getConfig().getBoolean("friendly-fire.guards-can-damage-each-other", false);
    }

    /** Keeps guards owned by online players loaded so every operation stays available. */
    public boolean keepGuardChunksLoaded() {
        return getConfig().getBoolean("guard-management.keep-chunks-loaded-while-owner-online", true);
    }

    public int getManagedChunkLimit() { return intSetting("guard-management.max-loaded-chunks", 64, 1, 1024); }
    public int getOwnerChunkLimit() { return intSetting("guard-management.max-loaded-chunks-per-owner", 16, 1, 256); }
    public int getChunkLoadsPerCycle() { return intSetting("guard-management.chunk-loads-per-cycle", 2, 1, 16); }
    public int getSearchChunksPerCycle() { return intSetting("guard-management.search-chunks-per-cycle", 32, 1, 256); }

    public boolean stayGuardsDefendOwner() {
        return getConfig().getBoolean("stay-mode.defend-owner", true);
    }

    public boolean stayReturnsToPosition() {
        return getConfig().getBoolean("stay-mode.return-to-position", true);
    }

    public double getStayReturnDistance() {
        return doubleSetting("stay-mode.return-distance", 24.0, 2.0, 4096.0);
    }

    public double getGuardRadius() {
        return doubleSetting("guard-mode.radius", 15.0, 2.0, 4096.0);
    }

    public double getGuardReturnDistance() {
        return doubleSetting("guard-mode.return-distance", 30.0, 2.0, 4096.0);
    }

    public boolean showNames() {
        return isNameplateEnabled() && getNameplateMode() != NameplateMode.HIDDEN;
    }

    public boolean isNameplateEnabled() {
        return getConfig().contains("display.nameplate.enabled", true)
                ? getConfig().getBoolean("display.nameplate.enabled", true)
                : getConfig().getBoolean("display.show-name", true);
    }

    public NameplateMode getNameplateMode() {
        String configured = getConfig().getString("display.nameplate.mode", "NAME_HEALTH_MODE");
        if (configured != null) {
            try {
                return NameplateMode.valueOf(configured.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // Use the safe default below.
            }
        }
        return NameplateMode.NAME_HEALTH_MODE;
    }

    public String guardNameplate(GuardData data, Mob mob, boolean companion) {
        if (!isNameplateEnabled() || getNameplateMode() == NameplateMode.HIDDEN
                || data == null || mob == null) {
            return null;
        }
        String name = truncateLegacy(color(data.getName()), 24);
        if (companion) {
            name = color("&6★ &f") + name;
        } else if (data.isFavorite()) {
            name = color("&e☆ &f") + name;
        }
        if (getNameplateMode() == NameplateMode.NAME) {
            return name;
        }
        AttributeInstance maximumAttribute = mob.getAttribute(Attribute.MAX_HEALTH);
        String health = "不明";
        String healthColor = "&7";
        if (maximumAttribute != null && Double.isFinite(maximumAttribute.getValue())
                && maximumAttribute.getValue() > 0.0 && Double.isFinite(mob.getHealth())) {
            double maximum = maximumAttribute.getValue();
            double ratio = Math.max(0.0, Math.min(1.0, mob.getHealth() / maximum));
            double lowRatio = doubleSetting("display.nameplate.low-health-ratio", 0.25, 0.05, 0.95);
            healthColor = ratio <= lowRatio ? "&c" : ratio <= 0.5 ? "&e" : "&a";
            health = String.format(Locale.ROOT, "%.0f/%.0f", mob.getHealth(), maximum);
        }
        String result = name + color(" &8｜ " + healthColor + "❤ " + health);
        if (getNameplateMode() == NameplateMode.NAME_HEALTH_MODE) {
            result += color(" &8｜ &f" + data.getMode().japaneseName());
        }
        return result;
    }

    private String truncateLegacy(String value, int maximumVisible) {
        if (value == null) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        int visible = 0;
        for (int index = 0; index < value.length() && visible < maximumVisible;) {
            char character = value.charAt(index);
            if (character == ChatColor.COLOR_CHAR && index + 1 < value.length()) {
                result.append(character).append(value.charAt(index + 1));
                index += 2;
            } else {
                int codePoint = value.codePointAt(index);
                result.appendCodePoint(codePoint);
                index += Character.charCount(codePoint);
                visible++;
            }
        }
        if (visible < ChatColor.stripColor(value).codePointCount(0, ChatColor.stripColor(value).length())) {
            result.append(ChatColor.GRAY).append("...");
        }
        return result.toString();
    }

    public enum NameplateMode {
        NAME,
        NAME_HEALTH,
        NAME_HEALTH_MODE,
        HIDDEN
    }

    public String getDefaultNameTemplate() {
        return stringSetting("display.default-name", "{mob_name}護衛 {number}");
    }

    public boolean effectsEnabled() {
        return getConfig().getBoolean("effects.enabled", true);
    }

    public boolean freezeOfflineGuards() {
        return getConfig().getBoolean("owner-offline.freeze-guards", true);
    }

    public boolean isGuiActionBarEnabled() {
        return getConfig().getBoolean("display.action-bar.enabled", true);
    }

    public PlayerSettings getPlayerSettings(java.util.UUID playerId) {
        return playerDataStorage == null ? PlayerSettings.DEFAULT
                : playerDataStorage.getSettings(playerId);
    }

    public int getGuiRefreshIntervalTicks() {
        return intSetting("display.gui-refresh-interval-ticks", 20, 0, 1200);
    }

    public int getActionBarIntervalTicks() {
        return intSetting("display.action-bar.interval-ticks", 5, 0, 1200);
    }

    public double getActionBarRange() {
        return doubleSetting("display.action-bar.range", 10.0, 1.0, 32.0);
    }

    public int getGuiResultDurationTicks() {
        return intSetting("display.gui-result-seconds", 5, 1, 30) * 20;
    }

    public double getLowHealthEffectRatio() {
        return doubleSetting("effects.guard-feedback.low-health-ratio", 0.25, 0.05, 0.95);
    }

    public long getLowHealthEffectCooldownMillis() {
        return intSetting("effects.guard-feedback.low-health-cooldown-seconds", 30, 1, 3600) * 1000L;
    }

    /** Item that opens the main menu when right-clicked. */
    public Material getMenuOpenerMaterial() {
        String configured = getConfig().getString("menu-opener.material", "NETHER_STAR");
        Material material = configured == null ? null : Material.matchMaterial(configured.trim());
        return material == null || material.isAir() ? Material.NETHER_STAR : material;
    }

    /** Creates the tagged item that players use to open the main menu. */
    public ItemStack createMenuOpenerItem() {
        ItemStack item = new ItemStack(getMenuOpenerMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color("&b&lBodyGuard メニュー"));
            meta.setLore(java.util.List.of(
                    color("&7右クリックで護衛メニューを開く"),
                    color("&8BodyGuard 専用アイテム")
            ));
            meta.getPersistentDataContainer().set(keys.menuOpener(), PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Returns whether an item was issued as a BodyGuard menu opener. */
    public boolean isMenuOpenerItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        Byte marker = item.getItemMeta().getPersistentDataContainer()
                .get(keys.menuOpener(), PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }

    public boolean hasMenuOpenerItem(Player player) {
        if (player == null) {
            return false;
        }
        for (ItemStack item : player.getInventory().getContents()) {
            if (isMenuOpenerItem(item)) {
                return true;
            }
        }
        return false;
    }

    public boolean guiSoundsEnabled() {
        return getConfig().getBoolean("effects.gui-sounds.enabled", true);
    }

    public Sound guiSuccessSound() {
        return soundSetting("effects.gui-sounds.success-sound", Sound.UI_BUTTON_CLICK);
    }

    public Sound guiFailureSound() {
        return soundSetting("effects.gui-sounds.failure-sound", Sound.BLOCK_NOTE_BLOCK_BASS);
    }

    public float guiSoundVolume() {
        return (float) doubleSetting("effects.gui-sounds.volume", 0.35, 0.0, 1.0);
    }

    public float guiSoundPitch(boolean success) {
        return (float) doubleSetting(success
                ? "effects.gui-sounds.success-pitch"
                : "effects.gui-sounds.failure-pitch", success ? 1.25 : 0.75, 0.5, 2.0);
    }

    public void playGuiSound(Player player, boolean success) {
        if (!guiSoundsEnabled() || player == null || !player.isOnline()) {
            return;
        }
        if (getPlayerSettings(player.getUniqueId()).guiSound() == PlayerSettings.Toggle.OFF) return;
        try {
            player.playSound(player.getLocation(), success ? guiSuccessSound() : guiFailureSound(),
                    guiSoundVolume(), guiSoundPitch(success));
        } catch (RuntimeException exception) {
            getLogger().log(Level.FINE, "Could not play BodyGuard GUI sound", exception);
        }
    }

    /** Japanese display name used only by the new-name template placeholder. */
    public String getMobDisplayName(EntityType type) {
        String fallback = EntityUtil.prettyMobName(type);
        if (messages == null) {
            return fallback;
        }
        String key = "mob-names." + (type == null ? "mob" : type.name().toLowerCase(Locale.ROOT));
        return messages.color(messages.get(key, fallback));
    }

    public String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value);
    }

    public void playGuardEffect(Entity entity, boolean created) {
        playGuardFeedback(entity, created ? GuardFeedback.SUMMON : GuardFeedback.RELEASE);
    }

    /** Plays one bounded world feedback effect for a successful guard operation. */
    public void playGuardFeedback(Entity entity, GuardFeedback feedback) {
        if (!effectsEnabled() || feedback == null || entity == null || !entity.isValid()
                || !getConfig().getBoolean("effects.guard-feedback.enabled", true)
                || !getConfig().getBoolean("effects.guard-feedback." + feedback.configKey(), true)) {
            return;
        }
        Location location = entity.getLocation().clone().add(0.0, 1.0, 0.0);
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        try {
            int count = intSetting("effects.guard-feedback.particle-count", 10, 1, 40);
            double range = doubleSetting("effects.guard-feedback.range", 24.0, 1.0, 64.0);
            for (Player viewer : world.getPlayers()) {
                if (viewer.getLocation().distanceSquared(location) > range * range) {
                    continue;
                }
                viewer.spawnParticle(feedback.particle(), location, count,
                        feedback.spread(), 0.35, feedback.spread(), 0.02);
                viewer.playSound(location, feedback.sound(), 0.65f, feedback.pitch());
            }
        } catch (RuntimeException exception) {
            getLogger().log(Level.FINE, "Could not play BodyGuard feedback", exception);
        }
    }

    public enum GuardFeedback {
        SUMMON("summon", Particle.WITCH, Sound.ENTITY_PLAYER_LEVELUP, 1.25f, 0.45),
        MODE_FOLLOW("mode-change", Particle.HAPPY_VILLAGER, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.35f, 0.35),
        MODE_STAY("mode-change", Particle.COMPOSTER, Sound.BLOCK_NOTE_BLOCK_HAT, 1.0f, 0.35),
        MODE_GUARD("mode-change", Particle.END_ROD, Sound.ITEM_SHIELD_BLOCK, 1.15f, 0.45),
        HEAL("heal", Particle.HEART, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.2f, 0.35),
        RECALL("recall", Particle.PORTAL, Sound.ENTITY_ENDERMAN_TELEPORT, 1.15f, 0.45),
        RELEASE("release", Particle.SMOKE, Sound.ENTITY_ITEM_BREAK, 0.85f, 0.4),
        LOW_HEALTH("low-health.enabled", Particle.DAMAGE_INDICATOR, Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.25);

        private final String configKey;
        private final Particle particle;
        private final Sound sound;
        private final float pitch;
        private final double spread;

        GuardFeedback(String configKey, Particle particle, Sound sound, float pitch, double spread) {
            this.configKey = configKey;
            this.particle = particle;
            this.sound = sound;
            this.pitch = pitch;
            this.spread = spread;
        }

        public String configKey() { return configKey; }
        public Particle particle() { return particle; }
        public Sound sound() { return sound; }
        public float pitch() { return pitch; }
        public double spread() { return spread; }
    }

    private int intSetting(String path, int fallback, int minimum, int maximum) {
        Object raw = getConfig().get(path);
        if (raw instanceof Number number) {
            int value = number.intValue();
            if (value >= minimum && value <= maximum) {
                return value;
            }
        }
        return fallback;
    }

    private double doubleSetting(String path, double fallback, double minimum, double maximum) {
        Object raw = getConfig().get(path);
        double value;
        if (raw instanceof Number number) {
            value = number.doubleValue();
        } else if (raw instanceof String string) {
            try {
                value = Double.parseDouble(string);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        } else {
            return fallback;
        }
        return Double.isFinite(value) && value >= minimum && value <= maximum ? value : fallback;
    }

    private Sound soundSetting(String path, Sound fallback) {
        String value = getConfig().getString(path);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Sound.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private String stringSetting(String path, String fallback) {
        String value = getConfig().getString(path);
        return value == null || value.isBlank() ? fallback : value;
    }

    public static final class NamespacedKeys {
        private final org.bukkit.NamespacedKey marker;
        private final org.bukkit.NamespacedKey owner;
        private final org.bukkit.NamespacedKey guardUuid;
        private final org.bukkit.NamespacedKey mobType;
        private final org.bukkit.NamespacedKey mode;
        private final org.bukkit.NamespacedKey name;
        private final org.bukkit.NamespacedKey nameNumber;
        private final org.bukkit.NamespacedKey favorite;
        private final org.bukkit.NamespacedKey anchorWorld;
        private final org.bukkit.NamespacedKey anchorWorldUuid;
        private final org.bukkit.NamespacedKey anchorX;
        private final org.bukkit.NamespacedKey anchorY;
        private final org.bukkit.NamespacedKey anchorZ;
        private final org.bukkit.NamespacedKey originalName;
        private final org.bukkit.NamespacedKey originalNameVisible;
        private final org.bukkit.NamespacedKey originalRemoveWhenFarAway;
        private final org.bukkit.NamespacedKey originalPersistent;
        private final org.bukkit.NamespacedKey originalAware;
        private final org.bukkit.NamespacedKey originalTarget;
        private final org.bukkit.NamespacedKey contractGeneration;
        private final org.bukkit.NamespacedKey protectionKind;
        private final org.bukkit.NamespacedKey roleId;
        private final org.bukkit.NamespacedKey selectedTargetUuid;
        private final org.bukkit.NamespacedKey selectionRevision;
        private final org.bukkit.NamespacedKey menuOpener;

        private NamespacedKeys(JavaPlugin plugin) {
            marker = new org.bukkit.NamespacedKey(plugin, "guard");
            owner = new org.bukkit.NamespacedKey(plugin, "owner_uuid");
            guardUuid = new org.bukkit.NamespacedKey(plugin, "guard_uuid");
            mobType = new org.bukkit.NamespacedKey(plugin, "mob_type");
            mode = new org.bukkit.NamespacedKey(plugin, "mode");
            name = new org.bukkit.NamespacedKey(plugin, "name");
            nameNumber = new org.bukkit.NamespacedKey(plugin, "name_number");
            favorite = new org.bukkit.NamespacedKey(plugin, "favorite");
            anchorWorld = new org.bukkit.NamespacedKey(plugin, "anchor_world");
            anchorWorldUuid = new org.bukkit.NamespacedKey(plugin, "anchor_world_uuid");
            anchorX = new org.bukkit.NamespacedKey(plugin, "anchor_x");
            anchorY = new org.bukkit.NamespacedKey(plugin, "anchor_y");
            anchorZ = new org.bukkit.NamespacedKey(plugin, "anchor_z");
            originalName = new org.bukkit.NamespacedKey(plugin, "original_name");
            originalNameVisible = new org.bukkit.NamespacedKey(plugin, "original_name_visible");
            originalRemoveWhenFarAway = new org.bukkit.NamespacedKey(plugin, "original_remove_when_far_away");
            originalPersistent = new org.bukkit.NamespacedKey(plugin, "original_persistent");
            originalAware = new org.bukkit.NamespacedKey(plugin, "original_aware");
            originalTarget = new org.bukkit.NamespacedKey(plugin, "original_target");
            contractGeneration = new org.bukkit.NamespacedKey(plugin, "contract_generation");
            protectionKind = new org.bukkit.NamespacedKey(plugin, "protection_kind");
            roleId = new org.bukkit.NamespacedKey(plugin, "role_id");
            selectedTargetUuid = new org.bukkit.NamespacedKey(plugin, "selected_target_uuid");
            selectionRevision = new org.bukkit.NamespacedKey(plugin, "selection_revision");
            menuOpener = new org.bukkit.NamespacedKey(plugin, "menu_opener");
        }

        public org.bukkit.NamespacedKey marker() { return marker; }
        public org.bukkit.NamespacedKey owner() { return owner; }
        public org.bukkit.NamespacedKey guardUuid() { return guardUuid; }
        public org.bukkit.NamespacedKey mobType() { return mobType; }
        public org.bukkit.NamespacedKey mode() { return mode; }
        public org.bukkit.NamespacedKey name() { return name; }
        public org.bukkit.NamespacedKey nameNumber() { return nameNumber; }
        public org.bukkit.NamespacedKey favorite() { return favorite; }
        public org.bukkit.NamespacedKey anchorWorld() { return anchorWorld; }
        public org.bukkit.NamespacedKey anchorWorldUuid() { return anchorWorldUuid; }
        public org.bukkit.NamespacedKey anchorX() { return anchorX; }
        public org.bukkit.NamespacedKey anchorY() { return anchorY; }
        public org.bukkit.NamespacedKey anchorZ() { return anchorZ; }
        public org.bukkit.NamespacedKey originalName() { return originalName; }
        public org.bukkit.NamespacedKey originalNameVisible() { return originalNameVisible; }
        public org.bukkit.NamespacedKey originalRemoveWhenFarAway() { return originalRemoveWhenFarAway; }
        public org.bukkit.NamespacedKey originalPersistent() { return originalPersistent; }
        public org.bukkit.NamespacedKey originalAware() { return originalAware; }
        public org.bukkit.NamespacedKey originalTarget() { return originalTarget; }
        public org.bukkit.NamespacedKey contractGeneration() { return contractGeneration; }
        public org.bukkit.NamespacedKey protectionKind() { return protectionKind; }
        public org.bukkit.NamespacedKey roleId() { return roleId; }
        public org.bukkit.NamespacedKey selectedTargetUuid() { return selectedTargetUuid; }
        public org.bukkit.NamespacedKey selectionRevision() { return selectionRevision; }
        public org.bukkit.NamespacedKey menuOpener() { return menuOpener; }
    }
}
