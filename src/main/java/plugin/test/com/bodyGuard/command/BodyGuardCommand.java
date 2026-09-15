package plugin.test.com.bodyGuard.command;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import plugin.test.com.bodyGuard.BodyGuard;
import plugin.test.com.bodyGuard.gui.BodyGuardGui;
import plugin.test.com.bodyGuard.guard.GuardData;
import plugin.test.com.bodyGuard.guard.GuardManager;
import plugin.test.com.bodyGuard.guard.GuardMode;
import plugin.test.com.bodyGuard.util.EntityUtil;
import plugin.test.com.bodyGuard.util.LocationUtil;
import plugin.test.com.bodyGuard.util.MessageUtil;

/** Main /bodyguard (/bg) command implementation. */
public final class BodyGuardCommand implements CommandExecutor {

    private final BodyGuard plugin;
    private final GuardManager manager;
    private final MessageUtil messages;
    private BodyGuardGui gui;

    public BodyGuardCommand(BodyGuard plugin, GuardManager manager, MessageUtil messages) {
        this.plugin = plugin;
        this.manager = manager;
        this.messages = messages;
    }

    public void setGui(BodyGuardGui gui) {
        this.gui = gui;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!hasPermission(sender, "bodyguard.use")) {
            messages.send(sender, "no-permission");
            return true;
        }
        if (args.length == 0) {
            if (sender instanceof Player player && gui != null) {
                gui.openList(player);
            } else {
                sendHelp(sender);
            }
            return true;
        }

        String subcommand = args[0].toLowerCase(java.util.Locale.ROOT);
        return switch (subcommand) {
            case "help" -> {
                sendHelp(sender);
                yield true;
            }
            case "menu" -> menu(sender, args);
            case "command" -> commandMenu(sender, args);
            case "item" -> item(sender, args);
            case "summon" -> summon(sender, args);
            case "recruit" -> recruit(sender, args);
            case "release" -> release(sender, args);
            case "releaseall" -> releaseAll(sender, args);
            case "deleteall" -> deleteAll(sender, args);
            case "list" -> list(sender, args);
            case "tp" -> teleport(sender, args);
            case "mode" -> mode(sender, args);
            case "rename" -> rename(sender, args);
            case "heal" -> heal(sender, args);
            case "reload" -> reload(sender, args);
            default -> {
                messages.send(sender, "unknown-command");
                yield true;
            }
        };
    }

    private void sendHelp(CommandSender sender) {
        messages.sendLines(sender, "help", Collections.emptyMap());
        messages.send(sender, "help-menu", "&f/bg &7- 護衛一覧のGUIを開く");
        messages.send(sender, "help-command", "&f/bg command &7- 簡易司令メニューを開く");
        messages.send(sender, "help-item", "&f/bg item &7- 右クリックでメニューを開く専用アイテムを受け取る");
    }

    private boolean menu(CommandSender sender, String[] args) {
        if (args.length != 1) {
            usage(sender, "/bg menu");
            return true;
        }
        Player player = requirePlayer(sender);
        if (player != null && gui != null) {
            gui.openList(player);
        }
        return true;
    }

    private boolean item(CommandSender sender, String[] args) {
        if (!requirePermission(sender, "bodyguard.item")) {
            return true;
        }
        if (args.length != 1) {
            usage(sender, "/bg item");
            return true;
        }
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }
        if (plugin.hasMenuOpenerItem(player)) {
            messages.send(player, "menu-item-already-owned",
                    "&eBodyGuardメニューアイテムはすでに持っています。");
            return true;
        }

        ItemStack opener = plugin.createMenuOpenerItem();
        Map<Integer, ItemStack> remaining = player.getInventory().addItem(opener);
        if (!remaining.isEmpty()) {
            messages.send(player, "menu-item-inventory-full",
                    "&cインベントリに空きがないため、メニューアイテムを渡せませんでした。");
            return true;
        }
        messages.send(player, "menu-item-received",
                "&aBodyGuardメニューアイテムを受け取りました。右クリックで使えます。");
        return true;
    }

    private boolean commandMenu(CommandSender sender, String[] args) {
        if (args.length != 1) {
            usage(sender, "/bg command");
            return true;
        }
        Player player = requirePlayer(sender);
        if (player != null && gui != null) {
            gui.openCommandMenu(player);
        }
        return true;
    }

    private boolean summon(CommandSender sender, String[] args) {
        if (!requirePermission(sender, "bodyguard.summon")) {
            return true;
        }
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }
        if (args.length != 2) {
            usage(sender, "/bg summon <mob>");
            return true;
        }

        EntityType type = plugin.parseEntityType(args[1]);
        summonPlayer(player, type, true);
        return true;
    }

    /** Shared summon path used by both /bg summon and the inventory summon menu. */
    public boolean summonFromMenu(Player player, EntityType type) {
        if (player == null || !hasPermission(player, "bodyguard.summon")) {
            return false;
        }
        return summonPlayer(player, type, false);
    }

    private boolean summonPlayer(Player player, EntityType type, boolean notify) {
        if (type == null || !plugin.isSupportedMobType(type)) {
            if (notify) {
                messages.send(player, "invalid-mob");
            }
            return false;
        }
        if (!plugin.isAllowedMobType(type)) {
            if (notify) {
                messages.send(player, "mob-not-allowed");
            }
            return false;
        }
        if (!player.isOp()
                && manager.countGuards(player.getUniqueId()) >= plugin.getMaxGuardsPerPlayer()) {
            if (notify) {
                messages.send(player, "guard-limit", Map.of("limit", String.valueOf(plugin.getMaxGuardsPerPlayer())));
            }
            return false;
        }

        Location spawnLocation = summonLocation(player);
        World world = player.getWorld();
        Mob mob;
        try {
            Entity entity = world.spawnEntity(spawnLocation, type);
            if (!(entity instanceof Mob spawnedMob)) {
                entity.remove();
                if (notify) {
                    messages.send(player, "spawn-failed");
                }
                return false;
            }
            mob = spawnedMob;
        } catch (RuntimeException exception) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "Could not summon BodyGuard " + type, exception);
            if (notify) {
                messages.send(player, "spawn-failed");
            }
            return false;
        }

        GuardData data = manager.registerGuard(mob, player);
        if (data == null) {
            mob.remove();
            if (notify) {
                messages.send(player, "spawn-failed");
            }
            return false;
        }
        plugin.playGuardEffect(mob, true);
        if (notify) {
            messages.send(player, "guard-created", Map.of(
                    "mob", mobName(data.getMobType()),
                    "name", data.getName()
            ));
        }
        return true;
    }

    private boolean recruit(CommandSender sender, String[] args) {
        if (!requirePermission(sender, "bodyguard.recruit")) {
            return true;
        }
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }
        if (args.length != 1) {
            usage(sender, "/bg recruit");
            return true;
        }

        LivingEntity target = findLookedAt(player);
        if (!(target instanceof Mob mob)) {
            messages.send(sender, "not-looking-at-mob");
            return true;
        }
        GuardData existing = manager.getGuardData(mob);
        if (existing != null) {
            if (player.getUniqueId().equals(existing.getOwnerId())) {
                messages.send(sender, "already-your-guard");
            } else {
                messages.send(sender, "already-owned");
            }
            return true;
        }
        if (!plugin.isAllowedMobType(mob.getType())) {
            messages.send(sender, "mob-not-allowed");
            return true;
        }
        if (manager.countGuards(player.getUniqueId()) >= plugin.getMaxGuardsPerPlayer()) {
            messages.send(sender, "guard-limit", Map.of("limit", String.valueOf(plugin.getMaxGuardsPerPlayer())));
            return true;
        }

        GuardData data = manager.registerGuard(mob, player);
        if (data == null) {
            messages.send(sender, "spawn-failed");
            return true;
        }
        plugin.playGuardEffect(mob, true);
        messages.send(sender, "guard-created", Map.of(
                "mob", mobName(data.getMobType()),
                "name", data.getName()
        ));
        return true;
    }

    private boolean release(CommandSender sender, String[] args) {
        if (!requirePermission(sender, "bodyguard.release")) {
            return true;
        }
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }
        if (args.length != 1) {
            usage(sender, "/bg release");
            return true;
        }

        LivingEntity target = findLookedAt(player);
        GuardData data = target == null ? null : manager.getGuardData(target);
        if (data == null) {
            messages.send(sender, "not-looking-at-mob");
            return true;
        }
        if (!player.getUniqueId().equals(data.getOwnerId())) {
            messages.send(sender, "not-your-guard");
            return true;
        }
        if (!(target instanceof Mob mob) || !manager.releaseGuard(data, mob)) {
            messages.send(sender, "not-looking-at-mob");
            return true;
        }
        plugin.playGuardEffect(mob, false);
        messages.send(sender, "guard-released");
        return true;
    }

    private boolean releaseAll(CommandSender sender, String[] args) {
        if (!requirePermission(sender, "bodyguard.releaseall")) {
            return true;
        }
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }
        if (args.length != 2 || !args[1].equalsIgnoreCase("confirm")) {
            messages.send(sender, "releaseall-confirm");
            return true;
        }
        GuardManager.ReleaseResult result = manager.releaseAll(player.getUniqueId());
        if (result.queued() > 0 || result.failed() > 0) {
            messages.send(sender, "release-summary",
                    "&e解除済み: &f{released}体 &7/ &e解除予約: &f{queued}体 &7/ &c失敗: &f{failed}体",
                    Map.of("released", String.valueOf(result.released()),
                            "queued", String.valueOf(result.queued()),
                            "failed", String.valueOf(result.failed())));
        } else {
            messages.send(sender, "released-all", Map.of("count", String.valueOf(result.released())));
        }
        return true;
    }

    private boolean deleteAll(CommandSender sender, String[] args) {
        boolean global = args.length >= 2 && args[1].equalsIgnoreCase("server");
        if (global) {
            if (!sender.hasPermission("bodyguard.admin")) {
                messages.send(sender, "no-permission");
                return true;
            }
            if (args.length != 3 || !args[2].equalsIgnoreCase("confirm")) {
                messages.send(sender, "deleteall-server-confirm");
                return true;
            }
            GuardManager.DeleteResult result = manager.deleteAllGlobally();
            sendDeleteResult(sender, result, true);
            return true;
        }

        if (!requirePermission(sender, "bodyguard.deleteall")) {
            return true;
        }
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }
        if (args.length != 2 || !args[1].equalsIgnoreCase("confirm")) {
            messages.send(sender, "deleteall-confirm");
            return true;
        }
        sendDeleteResult(sender, manager.deleteAll(player.getUniqueId()), false);
        return true;
    }

    private void sendDeleteResult(CommandSender sender, GuardManager.DeleteResult result, boolean global) {
        messages.send(sender, global ? "deleteall-server-summary" : "deleteall-summary",
                global
                        ? "&cサーバー全体の削除完了: &f{deleted}体 &7/ &e削除予約: &f{queued}体 &7/ &c失敗: &f{failed}体"
                        : "&c完全削除: &f{deleted}体 &7/ &e削除予約: &f{queued}体 &7/ &c失敗: &f{failed}体",
                Map.of("deleted", String.valueOf(result.deleted()),
                        "queued", String.valueOf(result.queued()),
                        "failed", String.valueOf(result.failed())));
    }

    private boolean list(CommandSender sender, String[] args) {
        if (!requirePermission(sender, "bodyguard.use")) {
            return true;
        }
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }
        if (args.length != 1) {
            usage(sender, "/bg list");
            return true;
        }
        java.util.List<GuardData> guards = manager.getGuards(player.getUniqueId());
        if (guards.isEmpty()) {
            messages.send(sender, "no-guards");
            return true;
        }
        messages.send(sender, "list-header");
        for (int index = 0; index < guards.size(); index++) {
            GuardData data = guards.get(index);
            Mob loadedMob = manager.getLoadedMob(data);
            String health = loadedMob == null ? "" : EntityUtil.healthText(loadedMob);
            String status;
            if (data.isDeletionPending()) {
                status = messages.get("guard-status-deletion-pending", "削除予約中");
            } else if (data.isReleasePending()) {
                status = messages.get("guard-status-release-pending", "解除予約中");
            } else if (loadedMob == null) {
                status = messages.get("gui.guard-status-unknown", "状態を確認できません");
            } else if (!LocationUtil.sameWorld(player.getLocation(), loadedMob.getLocation())) {
                status = messages.format(messages.get("gui.guard-status-world",
                                "別ワールドにいます: {world}"),
                        Map.of("world", loadedMob.getWorld() == null
                                ? "不明" : loadedMob.getWorld().getName()));
            } else {
                double distance = Math.sqrt(player.getLocation().distanceSquared(loadedMob.getLocation()));
                status = messages.format(messages.get("gui.guard-distance", "距離: {distance}m"),
                        Map.of("distance", String.format(java.util.Locale.ROOT, "%.1f", distance)));
            }
            String healthText = health == null ? "" : " &7/ HP &f" + health;
            messages.send(sender, "list-entry-details",
                    "&f{index}. {name} &7- {mob} / {mode} / {status}{health}", Map.of(
                    "index", String.valueOf(index + 1),
                    "name", data.getName(),
                    "mob", mobName(data.getMobType()),
                    "mode", data.getMode().japaneseName(),
                    "status", status,
                    "health", healthText
            ));
        }
        messages.send(sender, "list-total", Map.of("count", String.valueOf(guards.size())));
        return true;
    }

    private boolean teleport(CommandSender sender, String[] args) {
        if (!requirePermission(sender, "bodyguard.teleport")) {
            return true;
        }
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }
        if (args.length != 1) {
            usage(sender, "/bg tp");
            return true;
        }
        int count = manager.teleportGuards(player);
        messages.send(sender, count == 0 ? "nothing-teleported" : "teleported",
                Map.of("count", String.valueOf(count)));
        return true;
    }

    private boolean mode(CommandSender sender, String[] args) {
        if (!requirePermission(sender, "bodyguard.mode")) {
            return true;
        }
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }
        if (args.length != 2) {
            usage(sender, "/bg mode <follow|stay|guard>");
            return true;
        }
        GuardMode mode = GuardMode.fromString(args[1]);
        if (mode == null) {
            messages.send(sender, "invalid-mode");
            return true;
        }
        LivingEntity target = findLookedAt(player);
        GuardData data = target == null ? null : manager.getGuardData(target);
        if (data == null) {
            messages.send(sender, "not-looking-at-mob");
            return true;
        }
        if (!player.getUniqueId().equals(data.getOwnerId())) {
            messages.send(sender, "not-your-guard");
            return true;
        }
        if (!(target instanceof Mob mob)) {
            messages.send(sender, "not-looking-at-mob");
            return true;
        }
        manager.setMode(data, mob, mode, player.getLocation());
        if (mode == GuardMode.GUARD) {
            Location point = data.getAnchorLocation();
            messages.send(sender, "guard-point-set", Map.of(
                    "name", data.getName(),
                    "world", point.getWorld() == null ? "不明" : point.getWorld().getName(),
                    "x", String.valueOf(point.getBlockX()),
                    "y", String.valueOf(point.getBlockY()),
                    "z", String.valueOf(point.getBlockZ())));
        } else {
            messages.send(sender, "mode-changed", Map.of(
                    "name", data.getName(),
                    "mode", mode.displayName()
            ));
        }
        return true;
    }

    private boolean rename(CommandSender sender, String[] args) {
        if (!requirePermission(sender, "bodyguard.rename")) {
            return true;
        }
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }
        if (args.length < 2) {
            usage(sender, "/bg rename <名前>");
            return true;
        }
        String name = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        if (name.isBlank() || name.length() > 64) {
            messages.send(sender, "invalid-name");
            return true;
        }
        LivingEntity target = findLookedAt(player);
        GuardData data = target == null ? null : manager.getGuardData(target);
        if (data == null) {
            messages.send(sender, "not-looking-at-mob");
            return true;
        }
        if (!player.getUniqueId().equals(data.getOwnerId())) {
            messages.send(sender, "not-your-guard");
            return true;
        }
        if (!(target instanceof Mob mob)) {
            messages.send(sender, "not-looking-at-mob");
            return true;
        }
        String coloredName = plugin.color(name);
        manager.rename(data, mob, coloredName);
        messages.send(sender, "renamed", Map.of("name", coloredName));
        return true;
    }

    private boolean heal(CommandSender sender, String[] args) {
        if (!requirePermission(sender, "bodyguard.heal")) {
            return true;
        }
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }
        if (args.length != 1) {
            usage(sender, "/bg heal");
            return true;
        }
        int count = manager.healGuards(player);
        messages.send(sender, count == 0 ? "nothing-healed" : "healed",
                Map.of("count", String.valueOf(count)));
        return true;
    }

    private boolean reload(CommandSender sender, String[] args) {
        if (args.length != 1) {
            usage(sender, "/bg reload");
            return true;
        }
        if (!sender.hasPermission("bodyguard.reload") && !sender.hasPermission("bodyguard.admin")) {
            messages.send(sender, "no-permission");
            return true;
        }
        plugin.reloadSettings();
        messages.send(sender, "config-reloaded");
        return true;
    }

    private LivingEntity findLookedAt(Player player) {
        return EntityUtil.findLookedAtLivingEntity(player, 10.0);
    }

    private String mobName(EntityType type) {
        String fallback = EntityUtil.prettyMobName(type);
        String key = "mob-names." + (type == null
                ? "mob"
                : type.name().toLowerCase(java.util.Locale.ROOT));
        return messages.color(messages.get(key, fallback));
    }

    private Location summonLocation(Player player) {
        Location base = player.getLocation().clone();
        Vector direction = base.getDirection();
        direction.setY(0.0);
        if (direction.lengthSquared() < 0.000001) {
            direction = new Vector(0.0, 0.0, 1.0);
        } else {
            direction.normalize();
        }
        Location requested = base.add(direction.multiply(2.5));
        Location safe = LocationUtil.findSafeLocation(requested, 0);
        return safe == null ? requested : safe;
    }

    private Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        messages.send(sender, "player-only");
        return null;
    }

    private boolean requirePermission(CommandSender sender, String permission) {
        if (hasPermission(sender, permission)) {
            return true;
        }
        messages.send(sender, "no-permission");
        return false;
    }

    private void usage(CommandSender sender, String usage) {
        messages.send(sender, "invalid-usage", Map.of("usage", usage));
    }

    private boolean hasPermission(CommandSender sender, String permission) {
        return sender != null && (sender.hasPermission(permission)
                || sender.hasPermission("bodyguard.admin"));
    }
}
