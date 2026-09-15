package plugin.test.com.bodyGuard;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
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

import plugin.test.com.bodyGuard.command.BodyGuardCommand;
import plugin.test.com.bodyGuard.command.BodyGuardTabCompleter;
import plugin.test.com.bodyGuard.gui.BodyGuardGui;
import plugin.test.com.bodyGuard.guard.GuardManager;
import plugin.test.com.bodyGuard.guard.GuardTask;
import plugin.test.com.bodyGuard.listener.CombatListener;
import plugin.test.com.bodyGuard.listener.GuardDeathListener;
import plugin.test.com.bodyGuard.listener.PlayerListener;
import plugin.test.com.bodyGuard.listener.TargetListener;
import plugin.test.com.bodyGuard.storage.GuardStorage;
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
            EntityType.VINDICATOR
    );

    private NamespacedKeys keys;
    private MessageUtil messages;
    private GuardStorage storage;
    private GuardManager guardManager;
    private Set<EntityType> allowedMobTypes = Collections.emptySet();
    private GuardTask guardTask;
    private BodyGuardGui gui;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("messages.yml", false);

        keys = new NamespacedKeys(this);
        messages = new MessageUtil(this);
        reloadSettings();

        storage = new GuardStorage(this);
        guardManager = new GuardManager(this, storage, keys);
        guardManager.load(storage.load());

        PluginCommand command = getCommand("bodyguard");
        if (command == null) {
            getLogger().severe("Command 'bodyguard' is missing from plugin.yml. BodyGuard was disabled.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        BodyGuardCommand commandExecutor = new BodyGuardCommand(this, guardManager, messages);
        gui = new BodyGuardGui(this, guardManager, messages, commandExecutor);
        commandExecutor.setGui(gui);

        getServer().getPluginManager().registerEvents(new CombatListener(this, guardManager), this);
        getServer().getPluginManager().registerEvents(new TargetListener(guardManager), this);
        getServer().getPluginManager().registerEvents(new GuardDeathListener(this, guardManager), this);
        getServer().getPluginManager().registerEvents(new PlayerListener(this, guardManager, gui), this);
        getServer().getPluginManager().registerEvents(gui, this);
        command.setExecutor(commandExecutor);
        command.setTabCompleter(new BodyGuardTabCompleter(this));

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
            guardManager.save();
        }
        HandlerList.unregisterAll(this);
    }

    /** Reloads user-facing configuration without rebuilding the manager. */
    public void reloadSettings() {
        reloadConfig();
        if (messages != null) {
            messages.reload();
        }
        allowedMobTypes = readAllowedMobTypes();
        if (gui != null) {
            gui.restartTasks();
        }
    }

    private Set<EntityType> readAllowedMobTypes() {
        Set<EntityType> result = new LinkedHashSet<>();
        for (String value : getConfig().getStringList("allowed-mobs")) {
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

    public int getMaxGuardsPerPlayer() {
        return intSetting("limits.max-guards-per-player", 10, 1, 1000);
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
        return getConfig().getBoolean("display.show-name", true);
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
        if (!effectsEnabled() || entity == null || !entity.isValid()) {
            return;
        }
        Location location = entity.getLocation().clone().add(0.0, 1.0, 0.0);
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        try {
            if (created) {
                world.spawnParticle(Particle.HAPPY_VILLAGER, location, 10, 0.35, 0.45, 0.35, 0.02);
                world.playSound(location, Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.25f);
            } else {
                world.spawnParticle(Particle.SMOKE, location, 8, 0.3, 0.35, 0.3, 0.02);
                world.playSound(location, Sound.ENTITY_ITEM_BREAK, 0.7f, 0.85f);
            }
        } catch (RuntimeException exception) {
            getLogger().log(Level.FINE, "Could not play BodyGuard effect", exception);
        }
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
        private final org.bukkit.NamespacedKey anchorWorld;
        private final org.bukkit.NamespacedKey anchorX;
        private final org.bukkit.NamespacedKey anchorY;
        private final org.bukkit.NamespacedKey anchorZ;
        private final org.bukkit.NamespacedKey originalName;
        private final org.bukkit.NamespacedKey originalNameVisible;
        private final org.bukkit.NamespacedKey originalRemoveWhenFarAway;
        private final org.bukkit.NamespacedKey originalPersistent;
        private final org.bukkit.NamespacedKey originalAware;
        private final org.bukkit.NamespacedKey menuOpener;

        private NamespacedKeys(JavaPlugin plugin) {
            marker = new org.bukkit.NamespacedKey(plugin, "guard");
            owner = new org.bukkit.NamespacedKey(plugin, "owner_uuid");
            guardUuid = new org.bukkit.NamespacedKey(plugin, "guard_uuid");
            mobType = new org.bukkit.NamespacedKey(plugin, "mob_type");
            mode = new org.bukkit.NamespacedKey(plugin, "mode");
            name = new org.bukkit.NamespacedKey(plugin, "name");
            nameNumber = new org.bukkit.NamespacedKey(plugin, "name_number");
            anchorWorld = new org.bukkit.NamespacedKey(plugin, "anchor_world");
            anchorX = new org.bukkit.NamespacedKey(plugin, "anchor_x");
            anchorY = new org.bukkit.NamespacedKey(plugin, "anchor_y");
            anchorZ = new org.bukkit.NamespacedKey(plugin, "anchor_z");
            originalName = new org.bukkit.NamespacedKey(plugin, "original_name");
            originalNameVisible = new org.bukkit.NamespacedKey(plugin, "original_name_visible");
            originalRemoveWhenFarAway = new org.bukkit.NamespacedKey(plugin, "original_remove_when_far_away");
            originalPersistent = new org.bukkit.NamespacedKey(plugin, "original_persistent");
            originalAware = new org.bukkit.NamespacedKey(plugin, "original_aware");
            menuOpener = new org.bukkit.NamespacedKey(plugin, "menu_opener");
        }

        public org.bukkit.NamespacedKey marker() { return marker; }
        public org.bukkit.NamespacedKey owner() { return owner; }
        public org.bukkit.NamespacedKey guardUuid() { return guardUuid; }
        public org.bukkit.NamespacedKey mobType() { return mobType; }
        public org.bukkit.NamespacedKey mode() { return mode; }
        public org.bukkit.NamespacedKey name() { return name; }
        public org.bukkit.NamespacedKey nameNumber() { return nameNumber; }
        public org.bukkit.NamespacedKey anchorWorld() { return anchorWorld; }
        public org.bukkit.NamespacedKey anchorX() { return anchorX; }
        public org.bukkit.NamespacedKey anchorY() { return anchorY; }
        public org.bukkit.NamespacedKey anchorZ() { return anchorZ; }
        public org.bukkit.NamespacedKey originalName() { return originalName; }
        public org.bukkit.NamespacedKey originalNameVisible() { return originalNameVisible; }
        public org.bukkit.NamespacedKey originalRemoveWhenFarAway() { return originalRemoveWhenFarAway; }
        public org.bukkit.NamespacedKey originalPersistent() { return originalPersistent; }
        public org.bukkit.NamespacedKey originalAware() { return originalAware; }
        public org.bukkit.NamespacedKey menuOpener() { return menuOpener; }
    }
}
