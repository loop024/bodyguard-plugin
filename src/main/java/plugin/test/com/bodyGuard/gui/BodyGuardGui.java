package plugin.test.com.bodyGuard.gui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import plugin.test.com.bodyGuard.BodyGuard;
import plugin.test.com.bodyGuard.command.BodyGuardCommand;
import plugin.test.com.bodyGuard.guard.GuardData;
import plugin.test.com.bodyGuard.guard.GuardManager;
import plugin.test.com.bodyGuard.guard.GuardMode;
import plugin.test.com.bodyGuard.util.EntityUtil;
import plugin.test.com.bodyGuard.util.LocationUtil;
import plugin.test.com.bodyGuard.util.MessageUtil;

/** Standard Bukkit inventory menus for browsing and operating owned guards. */
public final class BodyGuardGui implements Listener {

    private static final int CONTENT_SLOTS = 45;
    private static final int MAIN_SIZE = 54;
    private static final int DETAIL_SIZE = 27;
    private static final int PREVIOUS_SLOT = 45;
    private static final int NEXT_SLOT = 52;
    private static final int CLOSE_SLOT = 53;

    private final BodyGuard plugin;
    private final GuardManager manager;
    private final MessageUtil messages;
    private final BodyGuardCommand command;

    public BodyGuardGui(BodyGuard plugin, GuardManager manager, MessageUtil messages,
                        BodyGuardCommand command) {
        this.plugin = plugin;
        this.manager = manager;
        this.messages = messages;
        this.command = command;
    }

    public void openList(Player player) {
        openList(player, 0);
    }

    public void openList(Player player, int requestedPage) {
        if (!canUseMenu(player)) {
            return;
        }
        List<GuardData> guards = manager.getGuards(player.getUniqueId());
        int pages = pageCount(guards.size());
        int page = clampPage(requestedPage, pages);
        List<UUID> pageIds = new ArrayList<>();
        int start = page * CONTENT_SLOTS;
        int end = Math.min(start + CONTENT_SLOTS, guards.size());
        for (int index = start; index < end; index++) {
            pageIds.add(guards.get(index).getGuardId());
        }

        BodyGuardMenuHolder holder = new BodyGuardMenuHolder(
                BodyGuardMenuHolder.MenuType.LIST, player.getUniqueId(), page, null,
                pageIds, null, false);
        Inventory inventory = createInventory(holder, MAIN_SIZE,
                text("gui.list-title", "&9護衛一覧") + " &8(" + (page + 1) + "/" + pages + ")");

        for (int index = start; index < end; index++) {
            inventory.setItem(index - start, guardIcon(player, guards.get(index)));
        }
        if (guards.isEmpty()) {
            inventory.setItem(22, item(Material.BOOK,
                    text("gui.empty-title", "&b護衛がいません"),
                    List.of(text("gui.empty-line-1", "&7下の「召喚」から護衛を選べます。"),
                            text("gui.empty-line-2", "&7コマンドなら /bg summon <mob> でも召喚できます。"))));
        }

        fillBottom(inventory);
        inventory.setItem(PREVIOUS_SLOT, navigationItem(
                Material.ARROW, "gui.previous", "&b前のページ", page > 0,
                text("gui.previous-lore", "&7前のページを表示します。")));
        inventory.setItem(46, permissionItem(player, "bodyguard.summon", Material.NETHER_STAR,
                "gui.summon", "&b召喚", text("gui.summon-lore", "&7召喚できるMobを選びます。")));
        inventory.setItem(47, permissionItem(player, "bodyguard.teleport", Material.COMPASS,
                "gui.recall", "&b全員を呼び戻す", text("gui.recall-lore", "&7読み込み済みの護衛を周辺へ移動します。")));
        inventory.setItem(48, permissionItem(player, "bodyguard.heal", Material.GOLDEN_APPLE,
                "gui.heal", "&a全員を回復", text("gui.heal-lore", "&7読み込み済みの護衛を全回復します。")));
        inventory.setItem(49, permissionItem(player, "bodyguard.releaseall", Material.RED_DYE,
                "gui.release-all", "&c全員の護衛契約を解除", text("gui.release-all-lore", "&7確認画面を開きます。")));
        inventory.setItem(50, item(Material.CLOCK,
                text("gui.refresh", "&b更新"),
                List.of(text("gui.refresh-lore", "&7護衛の状態を読み直します。"))));
        inventory.setItem(51, item(Material.BOOK,
                text("gui.page", "&fページ {page}/{pages}", Map.of(
                        "page", String.valueOf(page + 1), "pages", String.valueOf(pages))),
                List.of(text("gui.count", "&7護衛数: &f{count}/{limit}", Map.of(
                                "count", String.valueOf(guards.size()),
                                "limit", String.valueOf(plugin.getMaxGuardsPerPlayer()))),
                        text("gui.page-lore", "&7上段をクリックすると詳細を開きます。"))));
        inventory.setItem(NEXT_SLOT, navigationItem(
                Material.ARROW, "gui.next", "&b次のページ", page + 1 < pages,
                text("gui.next-lore", "&7次のページを表示します。")));
        inventory.setItem(CLOSE_SLOT, item(Material.BARRIER,
                text("gui.close", "&c閉じる"),
                List.of(text("gui.close-lore", "&7メニューを閉じます。"))));
        player.openInventory(inventory);
    }

    private void openSummon(Player player, int requestedPage) {
        if (!canUseMenu(player)) {
            return;
        }
        if (!hasPermission(player, "bodyguard.summon")) {
            messages.send(player, "no-permission");
            return;
        }
        List<EntityType> types = summonableTypes();
        int pages = pageCount(types.size());
        int page = clampPage(requestedPage, pages);
        int start = page * CONTENT_SLOTS;
        int end = Math.min(start + CONTENT_SLOTS, types.size());
        List<EntityType> pageTypes = new ArrayList<>(types.subList(start, end));
        BodyGuardMenuHolder holder = new BodyGuardMenuHolder(
                BodyGuardMenuHolder.MenuType.SUMMON, player.getUniqueId(), page, null,
                null, pageTypes, false);
        Inventory inventory = createInventory(holder, MAIN_SIZE,
                text("gui.summon-title", "&9護衛を召喚") + " &8(" + (page + 1) + "/" + pages + ")");

        for (int index = 0; index < pageTypes.size(); index++) {
            inventory.setItem(index, summonIcon(pageTypes.get(index), player));
        }
        if (types.isEmpty()) {
            inventory.setItem(22, item(Material.BARRIER,
                    text("gui.no-summonable-mobs", "&c召喚できるMobがありません"),
                    List.of(text("gui.no-summonable-mobs-lore", "&7config.yml の allowed-mobs を確認してください。"))));
        }

        fillBottom(inventory);
        inventory.setItem(45, item(Material.ARROW,
                text("gui.back", "&b一覧に戻る"),
                List.of(text("gui.back-lore", "&7護衛一覧に戻ります。"))));
        inventory.setItem(46, navigationItem(
                Material.ARROW, "gui.previous", "&b前のページ", page > 0,
                text("gui.previous-lore", "&7前のページを表示します。")));
        inventory.setItem(47, item(Material.BOOK,
                text("gui.page", "&fページ {page}/{pages}", Map.of(
                        "page", String.valueOf(page + 1), "pages", String.valueOf(pages))),
                List.of(text("gui.summon-page-lore", "&7召喚するMobを選択してください。"))));
        inventory.setItem(48, item(Material.CHEST,
                text("gui.count", "&7護衛数: &f{count}/{limit}", Map.of(
                        "count", String.valueOf(manager.countGuards(player.getUniqueId())),
                        "limit", String.valueOf(plugin.getMaxGuardsPerPlayer()))),
                List.of(text("gui.count-lore", "&7上限に達すると召喚できません。"))));
        inventory.setItem(49, item(Material.CLOCK,
                text("gui.refresh", "&b更新"),
                List.of(text("gui.refresh-lore", "&7召喚可能なMobを読み直します。"))));
        inventory.setItem(50, navigationItem(
                Material.ARROW, "gui.next", "&b次のページ", page + 1 < pages,
                text("gui.next-lore", "&7次のページを表示します。")));
        inventory.setItem(51, item(Material.BOOK,
                text("gui.summon-guide", "&b召喚方法"),
                List.of(text("gui.summon-guide-lore", "&7Mobをクリックすると召喚します。"))));
        inventory.setItem(52, item(Material.BARRIER,
                text("gui.close", "&c閉じる"),
                List.of(text("gui.close-lore", "&7メニューを閉じます。"))));
        player.openInventory(inventory);
    }

    private void openDetails(Player player, UUID guardId, int returnPage) {
        if (!canUseMenu(player)) {
            return;
        }
        GuardData data = ownedGuard(player, guardId);
        if (data == null) {
            messages.send(player, "gui-guard-unavailable",
                    "&cその護衛は死亡・解除されたか、情報を確認できません。", Map.of());
            transition(player, () -> openList(player, returnPage));
            return;
        }

        Mob mob = manager.getLoadedMob(data);
        BodyGuardMenuHolder holder = new BodyGuardMenuHolder(
                BodyGuardMenuHolder.MenuType.DETAIL, player.getUniqueId(), returnPage, guardId,
                null, null, false);
        Inventory inventory = createInventory(holder, DETAIL_SIZE,
                text("gui.detail-title", "&9護衛詳細"));
        fillInventory(inventory);

        List<String> detailLore = new ArrayList<>();
        detailLore.add(text("gui.detail-mob", "&7種類: &f{mob}",
                Map.of("mob", mobName(data.getMobType()))));
        detailLore.add(text("gui.detail-mode", "&7モード: &f{mode}",
                Map.of("mode", data.getMode().japaneseName())));
        String health = mob == null ? null : EntityUtil.healthText(mob);
        detailLore.add(mob == null
                ? text("gui.detail-health-unavailable", "&7HP: &f取得不可（未読み込み）")
                : health == null
                ? text("gui.detail-health-unknown", "&7HP: &f取得不可")
                : text("gui.detail-health", "&7HP: &f{health}", Map.of("health", health)));
        detailLore.add(statusLine(player, mob));
        detailLore.add(" ");
        detailLore.add(text("gui.rename-guide", "&7名前変更: &f/bg rename <名前>"));
        inventory.setItem(4, item(Material.PLAYER_HEAD,
                plugin.color(data.getName()), detailLore));

        inventory.setItem(10, modeItem(data, GuardMode.FOLLOW));
        inventory.setItem(13, modeItem(data, GuardMode.STAY));
        inventory.setItem(16, modeItem(data, GuardMode.GUARD));

        boolean releaseEnabled = mob != null && hasPermission(player, "bodyguard.release");
        List<String> releaseLore = new ArrayList<>();
        releaseLore.add(text("gui.release-single-lore", "&7この護衛だけを確認画面へ進めます。"));
        if (mob == null) {
            releaseLore.add(text("gui.unloaded-action", "&e未読み込みのため操作できません。"));
        }
        if (!hasPermission(player, "bodyguard.release")) {
            releaseLore.add(text("gui.permission-required", "&c権限がありません。"));
        }
        inventory.setItem(20, item(releaseEnabled ? Material.RED_DYE : Material.GRAY_DYE,
                text("gui.release-single", "&cこの護衛の契約を解除"), releaseLore));
        inventory.setItem(22, item(Material.ARROW,
                text("gui.back", "&b一覧に戻る"),
                List.of(text("gui.back-lore", "&7護衛一覧に戻ります。"))));
        inventory.setItem(26, item(Material.BARRIER,
                text("gui.close", "&c閉じる"),
                List.of(text("gui.close-lore", "&7メニューを閉じます。"))));
        player.openInventory(inventory);
    }

    private void openReleaseConfirmation(Player player, UUID guardId, int returnPage) {
        if (!canUseMenu(player)) {
            return;
        }
        if (!hasPermission(player, "bodyguard.release")) {
            messages.send(player, "no-permission");
            return;
        }
        GuardData data = ownedGuard(player, guardId);
        if (data == null) {
            messages.send(player, "gui-guard-unavailable",
                    "&cその護衛は死亡・解除されたか、情報を確認できません。", Map.of());
            transition(player, () -> openList(player, returnPage));
            return;
        }
        if (manager.getLoadedMob(data) == null) {
            messages.send(player, "gui-unloaded-action",
                    "&eこの護衛は未読み込みのため操作できません。", Map.of());
            transition(player, () -> openDetails(player, guardId, returnPage));
            return;
        }
        openReleaseConfirmation(player, List.of(guardId), false, returnPage,
                data.getName(), 1);
    }

    private void openAllReleaseConfirmation(Player player, int returnPage) {
        if (!canUseMenu(player)) {
            return;
        }
        if (!hasPermission(player, "bodyguard.releaseall")) {
            messages.send(player, "no-permission");
            return;
        }
        List<UUID> snapshot = manager.getGuards(player.getUniqueId()).stream()
                .map(GuardData::getGuardId)
                .toList();
        if (snapshot.isEmpty()) {
            messages.send(player, "no-guards");
            transition(player, () -> openList(player, returnPage));
            return;
        }
        openReleaseConfirmation(player, snapshot, true, returnPage, null, snapshot.size());
    }

    private void openReleaseConfirmation(Player player, Collection<UUID> guardIds,
                                         boolean releaseAll, int returnPage,
                                         String targetName, int targetCount) {
        BodyGuardMenuHolder holder = new BodyGuardMenuHolder(
                BodyGuardMenuHolder.MenuType.RELEASE_CONFIRM, player.getUniqueId(), returnPage,
                releaseAll ? null : guardIds.iterator().next(), guardIds, null, releaseAll);
        Inventory inventory = createInventory(holder, DETAIL_SIZE,
                text(releaseAll ? "gui.confirm-all-title" : "gui.confirm-single-title",
                        releaseAll ? "&c全解除の確認" : "&c解除の確認"));
        fillInventory(inventory);

        String target = releaseAll
                ? text("gui.confirm-target-count", "&f対象: &e{count}体", Map.of("count", String.valueOf(targetCount)))
                : text("gui.confirm-target-name", "&f対象: &e{name}", Map.of("name", plugin.color(targetName)));
        inventory.setItem(4, item(Material.BOOK,
                text("gui.confirm-target", "&e解除対象"),
                List.of(target,
                        text("gui.confirm-warning", "&c通常のMobに戻り、敵対する可能性があります。"))));
        inventory.setItem(13, item(Material.REDSTONE,
                text("gui.confirm-warning-title", "&e注意"),
                List.of(text("gui.confirm-warning", "&c通常のMobに戻り、敵対する可能性があります。"),
                        text("gui.confirm-snapshot", "&7この画面を開いた時点の対象だけを解除します。"))));
        inventory.setItem(11, item(Material.RED_CONCRETE,
                text("gui.confirm-release", "&c解除する"),
                List.of(text("gui.confirm-release-lore", "&7解除を実行します。"))));
        inventory.setItem(15, item(Material.BLUE_CONCRETE,
                text("gui.confirm-cancel", "&bキャンセル"),
                List.of(text("gui.confirm-cancel-lore", "&7解除せずに戻ります。"))));
        inventory.setItem(22, item(Material.ARROW,
                text("gui.back", "&b一覧に戻る"),
                List.of(text("gui.confirm-cancel-lore", "&7解除せずに戻ります。"))));
        inventory.setItem(26, item(Material.BARRIER,
                text("gui.close", "&c閉じる"),
                List.of(text("gui.confirm-cancel-lore", "&7解除せずに戻ります。"))));
        player.openInventory(inventory);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof BodyGuardMenuHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || !player.getUniqueId().equals(holder.getOwnerId())) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= top.getSize()) {
            return;
        }
        switch (holder.getType()) {
            case LIST -> handleListClick(player, holder, slot);
            case SUMMON -> handleSummonClick(player, holder, slot);
            case DETAIL -> handleDetailClick(player, holder, slot);
            case RELEASE_CONFIRM -> handleConfirmationClick(player, holder, slot);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof BodyGuardMenuHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onQuit(PlayerQuitEvent event) {
        if (event.getPlayer().getOpenInventory().getTopInventory().getHolder()
                instanceof BodyGuardMenuHolder) {
            event.getPlayer().closeInventory();
        }
    }

    /** Closes any BodyGuard menu when the plugin is disabled; no session map is retained. */
    public void closeAllMenus() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof BodyGuardMenuHolder) {
                player.closeInventory();
            }
        }
    }

    private void handleListClick(Player player, BodyGuardMenuHolder holder, int slot) {
        if (slot < CONTENT_SLOTS) {
            if (slot < holder.getGuardIds().size()) {
                UUID guardId = holder.getGuardIds().get(slot);
                transition(player, () -> openDetails(player, guardId, holder.getPage()));
            }
            return;
        }
        switch (slot) {
            case PREVIOUS_SLOT -> transition(player, () -> openList(player, holder.getPage() - 1));
            case 46 -> {
                if (checkPermission(player, "bodyguard.summon")) {
                    transition(player, () -> openSummon(player, 0));
                }
            }
            case 47 -> {
                if (checkPermission(player, "bodyguard.teleport")) {
                    int count = manager.teleportGuards(player);
                    messages.send(player, count == 0 ? "nothing-teleported" : "teleported",
                            Map.of("count", String.valueOf(count)));
                    transition(player, () -> openList(player, holder.getPage()));
                }
            }
            case 48 -> {
                if (checkPermission(player, "bodyguard.heal")) {
                    int count = manager.healGuards(player);
                    messages.send(player, count == 0 ? "nothing-healed" : "healed",
                            Map.of("count", String.valueOf(count)));
                    transition(player, () -> openList(player, holder.getPage()));
                }
            }
            case 49 -> {
                if (checkPermission(player, "bodyguard.releaseall")) {
                    transition(player, () -> openAllReleaseConfirmation(player, holder.getPage()));
                }
            }
            case 50 -> transition(player, () -> openList(player, holder.getPage()));
            case NEXT_SLOT -> transition(player, () -> openList(player, holder.getPage() + 1));
            case CLOSE_SLOT -> player.closeInventory();
            default -> {
                // The filler and page-info slots intentionally do nothing.
            }
        }
    }

    private void handleSummonClick(Player player, BodyGuardMenuHolder holder, int slot) {
        if (slot < CONTENT_SLOTS) {
            if (slot < holder.getMobTypes().size()) {
                EntityType type = holder.getMobTypes().get(slot);
                boolean summoned = command.summonFromMenu(player, type);
                transition(player, () -> summoned
                        ? openList(player, 0)
                        : openSummon(player, holder.getPage()));
            }
            return;
        }
        switch (slot) {
            case 45 -> transition(player, () -> openList(player, 0));
            case 46 -> transition(player, () -> openSummon(player, holder.getPage() - 1));
            case 49 -> transition(player, () -> openSummon(player, holder.getPage()));
            case 50 -> transition(player, () -> openSummon(player, holder.getPage() + 1));
            case 52 -> player.closeInventory();
            default -> {
                // The filler and information slots intentionally do nothing.
            }
        }
    }

    private void handleDetailClick(Player player, BodyGuardMenuHolder holder, int slot) {
        if (slot == 10 || slot == 13 || slot == 16) {
            GuardMode mode = slot == 10 ? GuardMode.FOLLOW : slot == 13 ? GuardMode.STAY : GuardMode.GUARD;
            if (!checkPermission(player, "bodyguard.mode")) {
                return;
            }
            GuardData data = ownedGuard(player, holder.getGuardId());
            if (data == null) {
                showUnavailableAndReturn(player, holder.getPage());
                return;
            }
            if (manager.getLoadedMob(data) == null) {
                messages.send(player, "gui-unloaded-action",
                        "&eこの護衛は未読み込みのため操作できません。", Map.of());
                transition(player, () -> openDetails(player, holder.getGuardId(), holder.getPage()));
                return;
            }
            if (!manager.setMode(player.getUniqueId(), holder.getGuardId(), mode)) {
                showUnavailableAndReturn(player, holder.getPage());
                return;
            }
            messages.send(player, "gui-mode-changed",
                    "&a{name} のモードを &f{mode} &aに変更しました。",
                    Map.of("name", data.getName(), "mode", mode.japaneseName()));
            transition(player, () -> openDetails(player, holder.getGuardId(), holder.getPage()));
            return;
        }
        switch (slot) {
            case 20 -> {
                if (checkPermission(player, "bodyguard.release")) {
                    transition(player, () -> openReleaseConfirmation(
                            player, holder.getGuardId(), holder.getPage()));
                }
            }
            case 22 -> transition(player, () -> openList(player, holder.getPage()));
            case 26 -> player.closeInventory();
            default -> {
                // The information item and filler slots intentionally do nothing.
            }
        }
    }

    private void handleConfirmationClick(Player player, BodyGuardMenuHolder holder, int slot) {
        if (slot == 11) {
            String permission = holder.isReleaseAll() ? "bodyguard.releaseall" : "bodyguard.release";
            if (!checkPermission(player, permission)) {
                return;
            }
            int released = manager.releaseGuards(player.getUniqueId(), holder.getGuardIds());
            if (released == 0) {
                messages.send(player, "gui-release-none",
                        "&e解除できる護衛がありません。未読み込み・死亡・解除済みの可能性があります。", Map.of());
            } else {
                messages.send(player, "gui-release-result",
                        "&a実際に解除できた護衛: &f{count}体。",
                        Map.of("count", String.valueOf(released)));
            }
            transition(player, () -> openList(player, holder.getPage()));
            return;
        }
        if (slot == 15 || slot == 22) {
            transition(player, () -> openList(player, holder.getPage()));
            return;
        }
        if (slot == 26) {
            player.closeInventory();
        }
    }

    private void showUnavailableAndReturn(Player player, int returnPage) {
        messages.send(player, "gui-guard-unavailable",
                "&cその護衛は死亡・解除されたか、情報を確認できません。", Map.of());
        transition(player, () -> openList(player, returnPage));
    }

    private boolean canUseMenu(Player player) {
        if (player == null || !player.isOnline()) {
            return false;
        }
        if (player.hasPermission("bodyguard.use")) {
            return true;
        }
        messages.send(player, "no-permission");
        player.closeInventory();
        return false;
    }

    private boolean checkPermission(Player player, String permission) {
        if (hasPermission(player, permission)) {
            return true;
        }
        messages.send(player, "no-permission");
        return false;
    }

    private boolean hasPermission(Player player, String permission) {
        return player != null && (player.hasPermission(permission)
                || player.hasPermission("bodyguard.admin"));
    }

    private GuardData ownedGuard(Player player, UUID guardId) {
        GuardData data = manager.getGuardData(guardId);
        return data != null && player != null && player.getUniqueId().equals(data.getOwnerId())
                ? data : null;
    }

    private List<EntityType> summonableTypes() {
        return plugin.getAllowedMobTypes().stream()
                .filter(plugin::isSupportedMobType)
                .sorted(Comparator.comparing(this::mobName).thenComparing(EntityType::name))
                .toList();
    }

    private ItemStack guardIcon(Player player, GuardData data) {
        Mob mob = manager.getLoadedMob(data);
        List<String> lore = new ArrayList<>();
        lore.add(text("gui.guard-mob", "&7種類: &f{mob}",
                Map.of("mob", mobName(data.getMobType()))));
        lore.add(text("gui.guard-mode", "&7モード: &f{mode}",
                Map.of("mode", data.getMode().japaneseName())));
        String health = mob == null ? null : EntityUtil.healthText(mob);
        if (mob == null) {
            lore.add(text("gui.guard-status-unloaded", "&7状態: &e未読み込み"));
        } else {
            lore.add(health == null
                    ? text("gui.guard-health-unknown", "&7HP: &f取得不可")
                    : text("gui.guard-health", "&7HP: &f{health}", Map.of("health", health)));
            lore.add(statusLine(player, mob));
        }
        lore.add(" ");
        lore.add(text("gui.guard-click", "&bクリック: 詳細を開く"));
        return item(spawnEgg(data.getMobType()), plugin.color(data.getName()), lore);
    }

    private ItemStack summonIcon(EntityType type, Player player) {
        List<String> lore = new ArrayList<>();
        lore.add(text("gui.summon-mob", "&7Mob: &f{mob}", Map.of("mob", mobName(type))));
        lore.add(text("gui.summon-count", "&7現在: &f{count}/{limit}", Map.of(
                "count", String.valueOf(manager.countGuards(player.getUniqueId())),
                "limit", String.valueOf(plugin.getMaxGuardsPerPlayer()))));
        lore.add(" ");
        lore.add(text("gui.summon-click", "&aクリック: このMobを召喚"));
        return item(spawnEgg(type), mobName(type), lore);
    }

    private ItemStack modeItem(GuardData data, GuardMode mode) {
        boolean current = data.getMode() == mode;
        String color = current ? "&a" : "&b";
        String currentLabel = current ? text("gui.current-mode", "&a現在のモード")
                : text("gui.click-mode", "&7クリックで変更");
        List<String> lore = List.of(modeDescription(mode), currentLabel);
        return item(current ? Material.LIME_DYE : Material.BLUE_DYE,
                plugin.color(color + mode.japaneseName()), lore);
    }

    private String modeDescription(GuardMode mode) {
        return switch (mode) {
            case FOLLOW -> text("gui.mode-follow", "&7所有者の近くへ付いてきます。");
            case STAY -> text("gui.mode-stay", "&7その場で待機し、必要時に守ります。");
            case GUARD -> text("gui.mode-guard", "&7指定地点の周囲を警備します。");
        };
    }

    private String statusLine(Player player, Mob mob) {
        if (mob == null) {
            return text("gui.guard-status-unloaded", "&7状態: &e未読み込み");
        }
        Location playerLocation = player.getLocation();
        Location mobLocation = mob.getLocation();
        if (!LocationUtil.sameWorld(playerLocation, mobLocation)) {
            return text("gui.guard-status-world", "&7状態: &e別ワールド");
        }
        double distance = Math.sqrt(playerLocation.distanceSquared(mobLocation));
        return text("gui.guard-distance", "&7距離: &f{distance}m", Map.of(
                "distance", String.format(Locale.ROOT, "%.1f", distance)));
    }

    private String mobName(EntityType type) {
        String fallback = EntityUtil.prettyMobName(type);
        String key = "mob-names." + (type == null ? "mob" : type.name().toLowerCase(Locale.ROOT));
        return messages.color(messages.get(key, fallback));
    }

    private Material spawnEgg(EntityType type) {
        if (type != null) {
            Material material = Material.matchMaterial(type.name() + "_SPAWN_EGG");
            if (material != null && material.isItem()) {
                return material;
            }
        }
        return Material.EGG;
    }

    private ItemStack permissionItem(Player player, String permission, Material material,
                                     String key, String fallbackName, String lore) {
        boolean allowed = hasPermission(player, permission);
        return item(allowed ? material : Material.GRAY_DYE,
                allowed ? text(key, fallbackName)
                        : ChatColor.GRAY + ChatColor.stripColor(plugin.color(fallbackName)),
                List.of(lore, allowed ? text("gui.click-to-use", "&bクリックして実行")
                        : text("gui.permission-required", "&c権限がありません。")));
    }

    private ItemStack navigationItem(Material material, String key, String fallbackName,
                                     boolean enabled, String lore) {
        return item(enabled ? material : Material.GRAY_STAINED_GLASS_PANE,
                enabled ? text(key, fallbackName)
                        : ChatColor.DARK_GRAY + ChatColor.stripColor(plugin.color(fallbackName)),
                List.of(lore));
    }

    private void fillBottom(Inventory inventory) {
        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int slot = 45; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler.clone());
        }
    }

    private void fillInventory(Inventory inventory) {
        ItemStack filler = item(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler.clone());
        }
    }

    private Inventory createInventory(BodyGuardMenuHolder holder, int size, String title) {
        Inventory inventory = Bukkit.createInventory(holder, size, messages.color(title));
        holder.setInventory(inventory);
        return inventory;
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material == null ? Material.EGG : material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name == null ? "" : name);
            meta.setLore(lore == null ? List.of() : lore);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private String text(String key, String fallback) {
        return messages.color(messages.get(key, fallback));
    }

    private String text(String key, String fallback, Map<String, String> placeholders) {
        return messages.format(messages.get(key, fallback), placeholders);
    }

    private int pageCount(int size) {
        return Math.max(1, (size + CONTENT_SLOTS - 1) / CONTENT_SLOTS);
    }

    private int clampPage(int page, int pages) {
        return Math.max(0, Math.min(page, pages - 1));
    }

    private void transition(Player player, Runnable action) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player != null && player.isOnline()) {
                action.run();
            }
        });
    }
}
