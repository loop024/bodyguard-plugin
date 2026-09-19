package plugin.test.com.bodyGuard.gui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import plugin.test.com.bodyGuard.BodyGuard;
import plugin.test.com.bodyGuard.command.BodyGuardCommand;
import plugin.test.com.bodyGuard.guard.GuardData;
import plugin.test.com.bodyGuard.guard.GuardManager;
import plugin.test.com.bodyGuard.guard.GuardMode;
import plugin.test.com.bodyGuard.guard.RoleDefinition;
import plugin.test.com.bodyGuard.storage.PlayerDataStorage;
import plugin.test.com.bodyGuard.storage.PlayerDataStorage.State;
import plugin.test.com.bodyGuard.storage.SafeYamlFile;
import plugin.test.com.bodyGuard.util.EntityUtil;
import plugin.test.com.bodyGuard.util.LocationUtil;
import plugin.test.com.bodyGuard.util.MessageUtil;
import plugin.test.com.bodyGuard.util.MessageUtil.NoticeTone;

/** Standard Bukkit inventory menus for browsing and operating owned guards. */
public final class BodyGuardGui implements Listener {

    private static final int CONTENT_START = 9;
    private static final int CONTENT_SLOTS = 36;
    private static final int MAIN_SIZE = 54;
    private static final int DETAIL_SIZE = 45;
    private static final int MANAGEMENT_SIZE = 27;
    private static final int PREVIOUS_SLOT = 45;
    private static final int NEXT_SLOT = 52;
    private static final int CLOSE_SLOT = 53;

    private final BodyGuard plugin;
    private final GuardManager manager;
    private final MessageUtil messages;
    private final BodyGuardCommand command;
    private final BodyGuardReleaseMenu releaseMenu;
    private final GuardListQuery listQuery;
    private final PlayerDataStorage playerDataStorage;
    private final Map<UUID, UiResult> results = new HashMap<>();
    private final Map<UUID, Long> resultTokens = new HashMap<>();
    private final Map<UUID, String> actionBarTexts = new HashMap<>();
    private long resultSequence;
    private BukkitTask refreshTask;
    private BukkitTask actionBarTask;

    public BodyGuardGui(BodyGuard plugin, GuardManager manager, MessageUtil messages,
                        BodyGuardCommand command, PlayerDataStorage playerDataStorage) {
        this.plugin = plugin;
        this.manager = manager;
        this.messages = messages;
        this.command = command;
        this.releaseMenu = new BodyGuardReleaseMenu(messages);
        this.listQuery = new GuardListQuery(manager);
        this.playerDataStorage = playerDataStorage;
    }

    public void startTasks() {
        restartTasks();
    }

    public void restartTasks() {
        clearActionBars();
        cancelTasks();
        results.clear();
        resultTokens.clear();
        actionBarTexts.clear();

        int refreshInterval = plugin.getGuiRefreshIntervalTicks();
        if (refreshInterval > 0) {
            refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshOpenMenus,
                    refreshInterval, refreshInterval);
        }
        int actionBarInterval = plugin.getActionBarIntervalTicks();
        if (plugin.isGuiActionBarEnabled() && actionBarInterval > 0) {
            actionBarTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshActionBars,
                    actionBarInterval, actionBarInterval);
        }
    }

    public void stopTasks() {
        clearActionBars();
        cancelTasks();
        results.clear();
        resultTokens.clear();
        actionBarTexts.clear();
    }

    private void cancelTasks() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
        if (actionBarTask != null) {
            actionBarTask.cancel();
            actionBarTask = null;
        }
    }

    public void openList(Player player) {
        if (shouldAutoShowTutorial(player)) {
            openTutorial(player, 0, false);
            return;
        }
        openList(player, 0, BodyGuardMenuHolder.GuardFilter.ALL,
                BodyGuardMenuHolder.GuardSort.STANDARD);
    }

    /** Opens the compact daily-command hub used by the protected menu item. */
    public void openCommandMenu(Player player) {
        if (!canUseMenu(player)) {
            return;
        }
        if (shouldAutoShowTutorial(player)) {
            openTutorial(player, 0, true);
            return;
        }
        BodyGuardMenuHolder holder = new BodyGuardMenuHolder(
                BodyGuardMenuHolder.MenuType.COMMAND, player.getUniqueId(), 0, null,
                null, null, false, BodyGuardMenuHolder.GuardFilter.ALL,
                BodyGuardMenuHolder.GuardSort.STANDARD, -1, -1);
        Inventory inventory = createInventory(holder, MANAGEMENT_SIZE,
                text("gui.command-title", "&3護衛司令メニュー"));
        renderCommandMenu(inventory, player);
        player.openInventory(inventory);
    }

    private boolean shouldAutoShowTutorial(Player player) {
        if (player == null || playerDataStorage == null
                || !plugin.getConfig().getBoolean("tutorial.enabled", true)
                || playerDataStorage.hasChoice(player.getUniqueId())) {
            return false;
        }
        return plugin.getConfig().getBoolean("tutorial.show-to-existing-players", true)
                || !player.hasPlayedBefore();
    }

    private void openTutorial(Player player, int requestedPage, boolean returnToCommand) {
        if (!canUseMenu(player)) {
            return;
        }
        int page = Math.max(0, Math.min(2, requestedPage));
        BodyGuardMenuHolder holder = new BodyGuardMenuHolder(
                BodyGuardMenuHolder.MenuType.TUTORIAL, player.getUniqueId(), page, null,
                null, null, returnToCommand, BodyGuardMenuHolder.GuardFilter.ALL,
                BodyGuardMenuHolder.GuardSort.STANDARD, -1, -1);
        Inventory inventory = createInventory(holder, MANAGEMENT_SIZE,
                text("gui.tutorial-title", "&bはじめてのBodyGuard") + " &8(" + (page + 1) + "/3)");
        renderTutorial(inventory, player, page);
        player.openInventory(inventory);
    }

    private void renderTutorial(Inventory inventory, Player player, int page) {
        fillInventory(inventory, Material.LIGHT_BLUE_STAINED_GLASS_PANE);
        if (page == 0) {
            inventory.setItem(4, item(Material.NETHER_STAR, text("gui.tutorial-welcome", "&b&l護衛を迎えよう"),
                    List.of(text("gui.tutorial-welcome-1", "&7BodyGuardでは敵対Mobを護衛として連れ歩けます。"),
                            text("gui.tutorial-welcome-2", "&7まずは召喚か野生Mobの勧誘から始めます。"))));
            inventory.setItem(13, permissionItem(player, "bodyguard.summon", Material.ZOMBIE_SPAWN_EGG,
                    "gui.tutorial-open-summon", "&d召喚候補を見る",
                    text("gui.tutorial-open-summon-lore", "&7クリックするとガイドを終えて召喚画面を開きます。")));
        } else if (page == 1) {
            List<String> lore = new ArrayList<>();
            if (hasPermission(player, "bodyguard.recruit")) {
                lore.add(text("gui.tutorial-recruit-1", "&7対応Mobを5～10ブロック以内で見ます。"));
                lore.add(text("gui.tutorial-recruit-2", "&f/bg recruit &7を実行すると仲間にできます。"));
            } else {
                lore.add(text("gui.tutorial-recruit-disabled", "&8勧誘権限がないため、召喚を利用してください。"));
            }
            inventory.setItem(4, item(hasPermission(player, "bodyguard.recruit") ? Material.LEAD : Material.GRAY_DYE,
                    text("gui.tutorial-recruit-title", "&b野生Mobを仲間にする"), lore));
            inventory.setItem(13, item(Material.COMPASS, text("gui.tutorial-item-title", "&b専用コンパス"),
                    List.of(text("gui.tutorial-item-lore", "&7右クリックで司令メニューを開けます。"))));
        } else {
            inventory.setItem(4, item(Material.BOOK, text("gui.tutorial-operate-title", "&b護衛を操作する"),
                    List.of(text("gui.guard-click", "&b左クリック &7: 詳細を開く"),
                            text("gui.guard-right-click", "&b右クリック &7: 次のモードへ変更"),
                            text("gui.guard-shift-left", "&bShift＋左 &7: この護衛を呼ぶ"),
                            text("gui.guard-shift-right", "&bShift＋右 &7: この護衛を回復"))));
            inventory.setItem(11, item(Material.LEAD, text("gui.tutorial-follow", "&a追従"),
                    List.of(text("gui.mode-follow", "&7所有者の近くへ付いてきます。"))));
            inventory.setItem(13, item(Material.ANVIL, text("gui.tutorial-stay", "&e待機"),
                    List.of(text("gui.mode-stay", "&7その場で待機し、必要時に守ります。"))));
            inventory.setItem(15, item(Material.SHIELD, text("gui.tutorial-guard", "&b警備"),
                    List.of(text("gui.mode-guard", "&7指定地点の周囲を警備します。"),
                            text("gui.tutorial-follow-recommended", "&7迷った場合は追従がおすすめです。"))));
        }
        inventory.setItem(18, navigationItem(Material.ARROW, "gui.tutorial-previous", "&b前へ",
                page > 0, text("gui.tutorial-previous-lore", "&7前の説明へ戻ります。")));
        inventory.setItem(20, item(Material.OAK_DOOR, text("gui.tutorial-finish", "&aガイドを終了"),
                List.of(text("gui.tutorial-finish-lore", "&7読了として保存し、メニューへ進みます。"))));
        inventory.setItem(22, item(Material.GRAY_DYE, text("gui.tutorial-dismiss", "&7次回から表示しない"),
                List.of(text("gui.tutorial-dismiss-lore", "&7非表示として保存します。ガイドから再表示できます。"))));
        inventory.setItem(24, item(page < 2 ? Material.ARROW : Material.LIME_CONCRETE,
                page < 2 ? text("gui.tutorial-next", "&b次へ") : text("gui.tutorial-complete", "&a完了"),
                List.of(page < 2 ? text("gui.tutorial-next-lore", "&7次の説明へ進みます。")
                        : text("gui.tutorial-complete-lore", "&7ガイドを完了してメニューへ進みます。"))));
        inventory.setItem(26, item(Material.BARRIER, text("gui.close", "&c閉じる"),
                List.of(text("gui.tutorial-close-lore", "&7保存せず閉じます。次回も表示されます。"))));
    }

    public void openList(Player player, int page) {
        openList(player, page, BodyGuardMenuHolder.GuardFilter.ALL,
                BodyGuardMenuHolder.GuardSort.STANDARD);
    }

    public void openList(Player player, int requestedPage,
                         BodyGuardMenuHolder.GuardFilter filter,
                         BodyGuardMenuHolder.GuardSort sort) {
        if (!canUseMenu(player)) {
            return;
        }
        BodyGuardMenuHolder.GuardFilter safeFilter = filter == null
                ? BodyGuardMenuHolder.GuardFilter.ALL : filter;
        BodyGuardMenuHolder.GuardSort safeSort = sort == null
                ? BodyGuardMenuHolder.GuardSort.STANDARD : sort;
        List<GuardData> all = manager.getGuards(player.getUniqueId());
        List<GuardData> filtered = listQuery.filterAndSort(player, all, safeFilter, safeSort);
        if (safeFilter == BodyGuardMenuHolder.GuardFilter.HISTORY) {
            all = manager.getHistory(player.getUniqueId());
            filtered = listQuery.filterAndSort(player, all, safeFilter, safeSort);
        }
        int pages = pageCount(filtered.size());
        int page = clampPage(requestedPage, pages);
        List<UUID> pageIds = new ArrayList<>();
        int start = page * CONTENT_SLOTS;
        int end = Math.min(start + CONTENT_SLOTS, filtered.size());
        for (int index = start; index < end; index++) {
            pageIds.add(filtered.get(index).getGuardId());
        }

        BodyGuardMenuHolder holder = new BodyGuardMenuHolder(
                BodyGuardMenuHolder.MenuType.LIST, player.getUniqueId(), page, null,
                pageIds, null, false, safeFilter, safeSort, all.size(), filtered.size());
        Inventory inventory = createInventory(holder, MAIN_SIZE,
                text("gui.list-title", "&9護衛一覧") + " &8(" + (page + 1) + "/" + pages + ")");
        fillContentArea(inventory, Material.LIGHT_BLUE_STAINED_GLASS_PANE);
        fillHeader(inventory, player, false, safeFilter, safeSort, filtered.size());

        for (int index = start; index < end; index++) {
            inventory.setItem(CONTENT_START + index - start, guardIcon(player, filtered.get(index)));
        }
        if (all.isEmpty() && safeFilter == BodyGuardMenuHolder.GuardFilter.ALL) {
            inventory.setItem(20, item(Material.NETHER_STAR,
                    text("gui.first-summon-title", "&b最初の護衛を召喚"),
                    List.of(text("gui.first-summon-lore-1", "&7クリックして召喚候補を開きます。"),
                            text("gui.first-summon-lore-2", "&7召喚権限が必要です。"))));
            inventory.setItem(24, item(Material.LEAD,
                    text("gui.recruit-guide-title", "&b野生のMobを仲間にする方法"),
                    List.of(text("gui.recruit-guide-lore-1", "&7対応Mobを見て /bg recruit を実行します。"),
                            text("gui.recruit-guide-lore-2", "&7成功するとそのMobが護衛になります。"))));
        } else if (filtered.isEmpty()) {
            inventory.setItem(22, item(Material.BARRIER,
                    safeFilter == BodyGuardMenuHolder.GuardFilter.HISTORY
                            ? text("gui.history-empty-title", "&e処理待ち・履歴はありません")
                            : text("gui.filter-empty-title", "&e条件に合う護衛がいません"),
                    List.of(safeFilter == BodyGuardMenuHolder.GuardFilter.HISTORY
                            ? text("gui.history-empty-lore", "&7まだ処理待ち・履歴はありません。")
                            : text("gui.filter-empty-lore", "&7「条件を解除」を押すと全員を表示します。"))));
            if (safeFilter != BodyGuardMenuHolder.GuardFilter.HISTORY) {
                inventory.setItem(24, item(Material.ARROW,
                        text("gui.filter-clear", "&b条件を解除"),
                        List.of(text("gui.filter-clear-lore", "&7絞り込みを「すべて」に戻します。"))));
            }
        }

        fillBottom(inventory, Material.CYAN_STAINED_GLASS_PANE);
        inventory.setItem(PREVIOUS_SLOT, navigationItem(Material.ARROW, "gui.previous",
                "&b前のページ", page > 0, text("gui.previous-lore", "&7前のページを表示します。")));
        inventory.setItem(46, permissionItem(player, "bodyguard.summon", Material.NETHER_STAR,
                "gui.summon", "&b召喚", text("gui.summon-lore", "&7召喚できるMobを選びます。")));
        inventory.setItem(47, permissionItem(player, "bodyguard.teleport", Material.COMPASS,
                "gui.recall", "&b全員を呼び戻す",
                text("gui.recall-lore", "&7読み込み済みの護衛を周辺へ移動します。")));
        inventory.setItem(48, permissionItem(player, "bodyguard.heal", Material.GOLDEN_APPLE,
                "gui.heal", "&a全員を回復",
                text("gui.heal-lore", "&7読み込み済みで負傷している護衛を回復します。")));
        inventory.setItem(49, resultItem(player));
        inventory.setItem(50, item(Material.CHEST, text("gui.management", "&e管理"),
                List.of(text("gui.management-lore", "&7全員の契約解除などを管理します。"),
                        text("gui.click-to-use", "&bクリックして開く"))));
        inventory.setItem(51, item(Material.CLOCK, text("gui.refresh-organize", "&b一覧を整理・更新"),
                List.of(text("gui.refresh-lore", "&7護衛の状態と一覧を読み直します。"))));
        inventory.setItem(NEXT_SLOT, navigationItem(Material.ARROW, "gui.next", "&b次のページ",
                page + 1 < pages, text("gui.next-lore", "&7次のページを表示します。")));
        inventory.setItem(CLOSE_SLOT, item(Material.BARRIER, text("gui.close", "&c閉じる"),
                List.of(text("gui.close-lore", "&7メニューを閉じます。"))));
        player.openInventory(inventory);
    }

    private void openSummon(Player player, int requestedPage,
                            BodyGuardMenuHolder.GuardFilter filter,
                            BodyGuardMenuHolder.GuardSort sort) {
        if (!canUseMenu(player)) {
            return;
        }
        if (!hasPermission(player, "bodyguard.summon")) {
            showResult(player, "gui-no-permission", "&cこの操作を使う権限がありません。", Map.of(), false);
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
                null, pageTypes, false, filter, sort,
                manager.countGuards(player.getUniqueId()), manager.countGuards(player.getUniqueId()));
        Inventory inventory = createInventory(holder, MAIN_SIZE,
                text("gui.summon-title", "&5護衛を召喚") + " &8(" + (page + 1) + "/" + pages + ")");
        fillContentArea(inventory, Material.PURPLE_STAINED_GLASS_PANE);
        fillHeader(inventory, player, true, filter, sort, manager.countGuards(player.getUniqueId()));
        for (int index = 0; index < pageTypes.size(); index++) {
            inventory.setItem(CONTENT_START + index, summonIcon(pageTypes.get(index), player));
        }
        if (types.isEmpty()) {
            inventory.setItem(22, item(Material.BARRIER, text("gui.no-summonable-mobs",
                    "&c召喚できるMobがありません"),
                    List.of(text("gui.no-summonable-mobs-lore", "&7設定で許可されている対応Mobがありません。"))));
        }

        fillBottom(inventory, Material.MAGENTA_STAINED_GLASS_PANE);
        inventory.setItem(PREVIOUS_SLOT, navigationItem(Material.ARROW, "gui.previous",
                "&b前のページ", page > 0, text("gui.previous-lore", "&7前のページを表示します。")));
        inventory.setItem(46, item(Material.ARROW, text("gui.back", "&b一覧に戻る"),
                List.of(text("gui.back-lore", "&7護衛一覧に戻ります。"))));
        inventory.setItem(47, item(Material.BOOK, text("gui.page", "&fページ {page}/{pages}",
                Map.of("page", String.valueOf(page + 1), "pages", String.valueOf(pages))),
                List.of(text("gui.summon-page-lore", "&7候補の特徴を確認して選択してください。"))));
        inventory.setItem(48, item(Material.CHEST, text("gui.count", "&7護衛数: &f{count}/{limit}",
                Map.of("count", String.valueOf(manager.countGuards(player.getUniqueId())),
                        "limit", summonLimitLabel(player))),
                List.of(summonLimitLore(player))));
        inventory.setItem(49, resultItem(player));
        inventory.setItem(50, item(Material.CLOCK, text("gui.refresh-organize", "&b一覧を整理・更新"),
                List.of(text("gui.summon-refresh-lore", "&7召喚候補と設定を読み直します。"))));
        inventory.setItem(51, item(Material.BOOK, text("gui.summon-guide", "&b召喚方法"),
                List.of(text("gui.summon-guide-lore", "&7Mobをクリックすると召喚します。"))));
        inventory.setItem(NEXT_SLOT, navigationItem(Material.ARROW, "gui.next", "&b次のページ",
                page + 1 < pages, text("gui.next-lore", "&7次のページを表示します。")));
        inventory.setItem(CLOSE_SLOT, item(Material.BARRIER, text("gui.close", "&c閉じる"),
                List.of(text("gui.close-lore", "&7メニューを閉じます。"))));
        player.openInventory(inventory);
    }

    public void openDetails(Player player, UUID guardId) {
        openDetails(player, guardId, 0, BodyGuardMenuHolder.GuardFilter.ALL,
                BodyGuardMenuHolder.GuardSort.STANDARD);
    }

    private void openDetails(Player player, UUID guardId, int returnPage,
                             BodyGuardMenuHolder.GuardFilter filter,
                             BodyGuardMenuHolder.GuardSort sort) {
        if (!canUseMenu(player)) {
            return;
        }
        GuardData data = ownedGuard(player, guardId);
        if (data == null) {
            showResult(player, "gui-guard-unavailable",
                    "&cその護衛は解除されたか、現在の状態を確認できません。", Map.of(), false);
            transition(player, () -> openList(player, returnPage, filter, sort));
            return;
        }

        BodyGuardMenuHolder holder = new BodyGuardMenuHolder(
                BodyGuardMenuHolder.MenuType.DETAIL, player.getUniqueId(), returnPage, guardId,
                null, null, false, filter, sort, -1, -1);
        Inventory inventory = createInventory(holder, DETAIL_SIZE, detailTitle(data));
        fillInventory(inventory, detailBackground(data));
        renderDetail(inventory, player, data);
        player.openInventory(inventory);
    }

    private void openNameInput(Player player, UUID guardId, int returnPage,
                               BodyGuardMenuHolder.GuardFilter filter,
                               BodyGuardMenuHolder.GuardSort sort) {
        if (!canUseMenu(player)) {
            return;
        }
        if (!hasPermission(player, "bodyguard.rename")) {
            showResult(player, "gui-no-permission", "&cこの操作を使う権限がありません。", Map.of(), false);
            return;
        }
        GuardData data = ownedGuard(player, guardId);
        if (data == null || manager.getLoadedMob(data) == null) {
            messages.send(player, "gui-rename-unavailable",
                    "&eこの護衛の状態を確認できないため、名前を変更できません。");
            return;
        }

        BodyGuardMenuHolder holder = new BodyGuardMenuHolder(
                BodyGuardMenuHolder.MenuType.NAME_INPUT, player.getUniqueId(), returnPage, guardId,
                null, null, false, filter, sort, -1, -1);
        Inventory inventory = Bukkit.createInventory(holder, org.bukkit.event.inventory.InventoryType.ANVIL,
                messages.color(text("gui.rename-input-title", "&9名前変更") + " &8- "
                        + truncateLegacy(plugin.color(data.getName()), 24)));
        holder.setInventory(inventory);
        AnvilInventory anvil = (AnvilInventory) inventory;
        ItemStack input = new ItemStack(Material.NAME_TAG);
        ItemMeta meta = input.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(plugin.color(data.getName()));
            meta.setLore(List.of(text("gui.rename-input-current", "&7現在の名前を確認して入力してください。"),
                    text("gui.rename-input-limit", "&7標準金床の入力欄上限: &f50文字")));
            input.setItemMeta(meta);
        }
        anvil.setItem(0, input);
        anvil.setRepairCost(0);
        anvil.setMaximumRepairCost(0);
        player.openInventory(inventory);
    }

    private void openManagement(Player player, int returnPage,
                                BodyGuardMenuHolder.GuardFilter filter,
                                BodyGuardMenuHolder.GuardSort sort) {
        if (!canUseMenu(player)) {
            return;
        }
        BodyGuardMenuHolder holder = new BodyGuardMenuHolder(
                BodyGuardMenuHolder.MenuType.MANAGEMENT, player.getUniqueId(), returnPage, null,
                null, null, false, filter, sort,
                manager.countGuards(player.getUniqueId()), manager.countGuards(player.getUniqueId()));
        Inventory inventory = createInventory(holder, MANAGEMENT_SIZE, text("gui.management-title", "&6護衛管理"));
        fillInventory(inventory, Material.ORANGE_STAINED_GLASS_PANE);
        inventory.setItem(10, item(Material.SPYGLASS, "§e所在不明の護衛", List.of("§7最終確認場所と状態を表示します。")));
        inventory.setItem(11, item(Material.BOOK, "§b処理待ち・履歴", List.of("§7死亡・解除・削除の記録を確認します。")));
        int count = manager.countGuards(player.getUniqueId());
        inventory.setItem(4, item(Material.CHEST, text("gui.management-summary", "&e現在の護衛: &f{count}体",
                Map.of("count", String.valueOf(count))),
                List.of(text("gui.management-summary-lore", "&7一覧の表示条件は戻った後も維持されます。"))));
        boolean allowed = hasPermission(player, "bodyguard.releaseall");
        inventory.setItem(13, item(allowed ? Material.RED_DYE : Material.GRAY_DYE,
                allowed ? text("gui.release-all", "&c全員の護衛契約を解除")
                        : ChatColor.GRAY + "全員の護衛契約を解除",
                List.of(text("gui.release-all-lore", "&7対象数を確認してから解除します。"),
                        text("gui.confirm-warning", "&c通常のMobに戻り、敵対する可能性があります。"),
                        allowed ? text("gui.click-to-use", "&bクリックして確認")
                                : text("gui.permission-required", "&c権限がありません。"))));
        boolean deleteAllowed = hasPermission(player, "bodyguard.deleteall");
        inventory.setItem(15, item(deleteAllowed ? Material.LAVA_BUCKET : Material.GRAY_DYE,
                deleteAllowed ? text("gui.delete-all", "&4自分の護衛Mobをすべて完全削除")
                        : ChatColor.GRAY + "自分の護衛Mobをすべて完全削除",
                List.of(text("gui.delete-all-lore", "&cMob自体を消去します。この操作は元に戻せません。"),
                        deleteAllowed ? text("gui.click-to-use", "&bクリックして確認")
                                : text("gui.permission-required", "&c権限がありません。"))));
        inventory.setItem(18, item(Material.ARROW,
                returnPage < 0 ? text("gui.back-command", "&b司令メニューに戻る")
                        : text("gui.back", "&b一覧に戻る"),
                List.of(returnPage < 0 ? text("gui.back-command-lore", "&7司令メニューへ戻ります。")
                        : text("gui.back-lore", "&7表示条件を維持して一覧へ戻ります。"))));
        inventory.setItem(22, resultItem(player));
        inventory.setItem(26, item(Material.BARRIER, text("gui.close", "&c閉じる"),
                List.of(text("gui.close-lore", "&7メニューを閉じます。"))));
        player.openInventory(inventory);
    }

    private void openAllReleaseConfirmation(Player player, int returnPage,
                                             BodyGuardMenuHolder.GuardFilter filter,
                                             BodyGuardMenuHolder.GuardSort sort) {
        if (!canUseMenu(player)) {
            return;
        }
        if (!hasPermission(player, "bodyguard.releaseall")) {
            showResult(player, "gui-no-permission", "&cこの操作を使う権限がありません。", Map.of(), false);
            return;
        }
        List<UUID> snapshot = manager.getGuards(player.getUniqueId()).stream()
                .map(GuardData::getGuardId).toList();
        if (snapshot.isEmpty()) {
            showResult(player, "gui-release-none", "&e解除対象の護衛がいません。", Map.of(), false);
            transition(player, () -> openManagement(player, returnPage, filter, sort));
            return;
        }
        openReleaseConfirmation(player, snapshot, true, returnPage, null, snapshot.size(), filter, sort);
    }

    private void openAllDeleteConfirmation(Player player, int returnPage,
                                            BodyGuardMenuHolder.GuardFilter filter,
                                            BodyGuardMenuHolder.GuardSort sort) {
        if (!canUseMenu(player)) {
            return;
        }
        if (!hasPermission(player, "bodyguard.deleteall")) {
            showResult(player, "gui-no-permission", "&cこの操作を使う権限がありません。", Map.of(), false);
            return;
        }
        List<UUID> snapshot = manager.getGuards(player.getUniqueId()).stream()
                .map(GuardData::getGuardId).toList();
        if (snapshot.isEmpty()) {
            showResult(player, "gui-delete-none", "&e削除対象の護衛がいません。", Map.of(), false);
            transition(player, () -> openManagement(player, returnPage, filter, sort));
            return;
        }
        BodyGuardMenuHolder holder = new BodyGuardMenuHolder(
                BodyGuardMenuHolder.MenuType.RELEASE_CONFIRM, player.getUniqueId(), returnPage,
                null, snapshot, null, true, true, filter, sort, -1, -1);
        Inventory inventory = releaseMenu.create(holder, true, null, snapshot.size());
        player.openInventory(inventory);
    }

    private void openReleaseConfirmation(Player player, Collection<UUID> guardIds,
                                          boolean releaseAll, int returnPage,
                                          String targetName, int targetCount,
                                          BodyGuardMenuHolder.GuardFilter filter,
                                          BodyGuardMenuHolder.GuardSort sort) {
        BodyGuardMenuHolder holder = new BodyGuardMenuHolder(
                BodyGuardMenuHolder.MenuType.RELEASE_CONFIRM, player.getUniqueId(), returnPage,
                releaseAll ? null : guardIds.iterator().next(), guardIds, null, releaseAll,
                filter, sort, -1, -1);
        Inventory inventory = releaseMenu.create(holder, releaseAll,
                targetName == null ? null : plugin.color(targetName), targetCount);
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
        if (!isSupportedClick(event.getClick())) {
            return;
        }
        switch (holder.getType()) {
            case COMMAND -> handleCommandClick(player, slot);
            case TUTORIAL -> handleTutorialClick(player, holder, slot);
            case LIST -> handleListClick(player, holder, slot, event.getClick(), event.isShiftClick());
            case SUMMON -> handleSummonClick(player, holder, slot, event.getClick());
            case DETAIL -> handleDetailClick(player, holder, slot);
            case MANAGEMENT -> handleManagementClick(player, holder, slot);
            case RELEASE_CONFIRM -> handleConfirmationClick(player, holder, slot);
            case NAME_INPUT -> handleNameInputClick(player, holder, top, slot);
        }
    }

    private boolean isSupportedClick(ClickType click) {
        return click == ClickType.LEFT || click == ClickType.RIGHT
                || click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof BodyGuardMenuHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSwapHandItems(PlayerSwapHandItemsEvent event) {
        if (event.getPlayer().getOpenInventory().getTopInventory().getHolder()
                instanceof BodyGuardMenuHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof BodyGuardMenuHolder holder)
                || holder.getType() != BodyGuardMenuHolder.MenuType.NAME_INPUT) {
            return;
        }
        AnvilInventory anvil = event.getInventory();
        anvil.setRepairCost(0);
        anvil.setMaximumRepairCost(0);
        ItemStack input = anvil.getItem(0);
        if (input == null || input.getType().isAir()) {
            event.setResult(null);
            return;
        }
        String renameText = event.getView().getRenameText();
        if (renameText == null || renameText.isEmpty()) {
            event.setResult(null);
            return;
        }
        ItemStack result = input.clone();
        ItemMeta meta = result.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(plugin.color(renameText));
            result.setItemMeta(meta);
        }
        event.setResult(result);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof BodyGuardMenuHolder holder
                && holder.getType() == BodyGuardMenuHolder.MenuType.NAME_INPUT) {
            // Closing this input never saves the current text.
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        results.remove(playerId);
        resultTokens.remove(playerId);
        actionBarTexts.remove(playerId);
        if (event.getPlayer().getOpenInventory().getTopInventory().getHolder()
                instanceof BodyGuardMenuHolder) {
            event.getPlayer().closeInventory();
        }
    }

    public void closeAllMenus() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof BodyGuardMenuHolder) {
                player.closeInventory();
            }
        }
    }

    private void refreshOpenMenus() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof BodyGuardMenuHolder holder)
                    || !player.getUniqueId().equals(holder.getOwnerId())) {
                continue;
            }
            Inventory inventory = player.getOpenInventory().getTopInventory();
            switch (holder.getType()) {
                case COMMAND -> renderCommandMenu(inventory, player);
                case LIST -> refreshList(player, holder, inventory);
                case SUMMON -> refreshSummon(player, holder, inventory);
                case DETAIL -> refreshDetail(player, holder, inventory);
                case MANAGEMENT -> refreshManagement(player, inventory);
                case RELEASE_CONFIRM, NAME_INPUT, TUTORIAL -> {
                    // These screens are deliberately not auto-refreshed.
                }
            }
        }
    }

    private void refreshList(Player player, BodyGuardMenuHolder holder, Inventory inventory) {
        List<GuardData> all = manager.getGuards(player.getUniqueId());
        if (holder.getFilter() == BodyGuardMenuHolder.GuardFilter.HISTORY)
            all = manager.getHistory(player.getUniqueId());
        int filteredCount = listQuery.countMatching(all, holder.getFilter());
        fillHeader(inventory, player, false, holder.getFilter(), holder.getSort(), filteredCount);
        for (int index = 0; index < holder.getGuardIds().size(); index++) {
            GuardData data = manager.getGuardData(holder.getGuardIds().get(index));
            if (data != null && !player.getUniqueId().equals(data.getOwnerId())) data = null;
            inventory.setItem(CONTENT_START + index, data == null ? invalidGuardIcon() : guardIcon(player, data));
        }
        inventory.setItem(49, resultItem(player));
    }

    private void renderCommandMenu(Inventory inventory, Player player) {
        fillInventory(inventory, Material.CYAN_STAINED_GLASS_PANE);
        CommandCounts counts = commandCounts(player);
        inventory.setItem(4, item(Material.COMPASS,
                text("gui.command-summary", "&b&l護衛への一括指示"),
                List.of(text("gui.command-total", "&7護衛総数: &f{count}体",
                                Map.of("count", String.valueOf(counts.total()))),
                        text("gui.command-available", "&7操作可能: &a{count}体",
                                Map.of("count", String.valueOf(counts.available()))),
                        text("gui.command-unloaded", "&7未読み込み: &8{count}体",
                                Map.of("count", String.valueOf(counts.unavailable()))))));
        inventory.setItem(9, bulkModeItem(player, GuardMode.FOLLOW, Material.LEAD, counts));
        inventory.setItem(11, bulkModeItem(player, GuardMode.STAY, Material.ANVIL, counts));
        inventory.setItem(13, bulkModeItem(player, GuardMode.GUARD, Material.SHIELD, counts));
        inventory.setItem(15, commandActionItem(player, "bodyguard.teleport", Material.COMPASS,
                "gui.command-recall", "&b全員集合", counts));
        inventory.setItem(17, commandActionItem(player, "bodyguard.heal", Material.GOLDEN_APPLE,
                "gui.command-heal", "&a負傷者を回復", counts));
        inventory.setItem(18, item(Material.ARROW, text("gui.command-list", "&b護衛一覧"),
                List.of(text("gui.command-list-lore", "&7個別の状態確認と操作を開きます。"))));
        inventory.setItem(20, permissionItem(player, "bodyguard.summon", Material.NETHER_STAR,
                "gui.command-summon", "&d新規召喚", text("gui.command-summon-lore", "&7召喚候補を開きます。")));
        inventory.setItem(22, item(Material.CHEST, text("gui.command-management", "&6管理"),
                List.of(text("gui.command-management-lore", "&7契約解除などの管理画面を開きます。"))));
        inventory.setItem(23, item(Material.BOOK, text("gui.command-guide", "&b操作ガイド"),
                List.of(text("gui.command-guide-lore", "&7初回ガイドをいつでも読み直せます。"))));
        inventory.setItem(24, item(Material.GRAY_DYE, text("gui.command-settings-disabled", "&7設定（準備中）"),
                List.of(text("gui.command-settings-disabled-lore", "&7通知設定の実装後に利用できます。"))));
        inventory.setItem(25, resultItem(player));
        inventory.setItem(26, item(Material.BARRIER, text("gui.close", "&c閉じる"),
                List.of(text("gui.close-lore", "&7メニューを閉じます。"))));
    }

    private ItemStack bulkModeItem(Player player, GuardMode mode, Material material, CommandCounts counts) {
        boolean allowed = hasPermission(player, "bodyguard.mode");
        List<String> lore = new ArrayList<>();
        lore.add(modeDescription(mode));
        addCommandCounts(lore, counts);
        lore.add(allowed ? text("gui.click-to-use", "&bクリックして実行")
                : text("gui.permission-required", "&c権限がありません。"));
        return item(allowed ? material : Material.GRAY_DYE,
                allowed ? text("gui.command-mode", "&b全員{mode}", Map.of("mode", mode.japaneseName()))
                        : text("gui.command-mode-disabled", "&7全員{mode}（操作不可）",
                                Map.of("mode", mode.japaneseName())), lore);
    }

    private ItemStack commandActionItem(Player player, String permission, Material material,
                                        String key, String fallback, CommandCounts counts) {
        boolean allowed = hasPermission(player, permission);
        List<String> lore = new ArrayList<>();
        addCommandCounts(lore, counts);
        lore.add(allowed ? text("gui.click-to-use", "&bクリックして実行")
                : text("gui.permission-required", "&c権限がありません。"));
        return item(allowed ? material : Material.GRAY_DYE,
                allowed ? text(key, fallback)
                        : ChatColor.GRAY + ChatColor.stripColor(text(key, fallback)) + "（操作不可）", lore);
    }

    private void addCommandCounts(List<String> lore, CommandCounts counts) {
        lore.add(text("gui.command-target-counts", "&7対象: &f{total}体 &8/ &a操作可能: {available}体 &8/ &7未読み込み: {unavailable}体",
                Map.of("total", String.valueOf(counts.total()),
                        "available", String.valueOf(counts.available()),
                        "unavailable", String.valueOf(counts.unavailable()))));
    }

    private CommandCounts commandCounts(Player player) {
        List<GuardData> guards = manager.getGuards(player.getUniqueId());
        int available = 0;
        for (GuardData data : guards) {
            if (manager.getLoadedMob(data) != null) {
                available++;
            }
        }
        return new CommandCounts(guards.size(), available, guards.size() - available);
    }

    private void handleCommandClick(Player player, int slot) {
        switch (slot) {
            case 9 -> changeAllModes(player, GuardMode.FOLLOW);
            case 11 -> changeAllModes(player, GuardMode.STAY);
            case 13 -> changeAllModes(player, GuardMode.GUARD);
            case 15 -> recallFromCommandMenu(player);
            case 17 -> healFromCommandMenu(player);
            case 18 -> transition(player, () -> openList(player));
            case 20 -> {
                if (hasPermission(player, "bodyguard.summon")) {
                    transition(player, () -> openSummon(player, 0, BodyGuardMenuHolder.GuardFilter.ALL,
                            BodyGuardMenuHolder.GuardSort.STANDARD));
                } else {
                    showResult(player, "gui-no-permission", "&cこの操作を使う権限がありません。", Map.of(), false);
                }
            }
            case 22 -> transition(player, () -> openManagement(player, -1,
                    BodyGuardMenuHolder.GuardFilter.ALL, BodyGuardMenuHolder.GuardSort.STANDARD));
            case 23 -> transition(player, () -> openTutorial(player, 0, true));
            case 26 -> player.closeInventory();
            default -> {
                // Summary, settings placeholder, result, and filler slots do nothing.
            }
        }
    }

    private void changeAllModes(Player player, GuardMode mode) {
        if (!hasPermission(player, "bodyguard.mode")) {
            showResult(player, "gui-no-permission", "&cこの操作を使う権限がありません。", Map.of(), false);
            return;
        }
        CommandCounts before = commandCounts(player);
        int changed = 0;
        for (GuardData data : manager.getGuards(player.getUniqueId())) {
            try {
                if (manager.setMode(player.getUniqueId(), data.getGuardId(), mode, player.getLocation())) {
                    changed++;
                }
            } catch (RuntimeException failure) {
                manager.reportFailure("bulk-mode", data.getGuardId(), failure);
            }
        }
        showCommandResult(player, "gui-command-mode-result",
                "&a変更: {changed}体 &7/ &8操作不可: {unavailable}体 &7/ &e未変更: {failed}体 &7（{mode}）",
                changed, before.unavailable(), before.available() - changed, Map.of("mode", mode.japaneseName()));
    }

    private void recallFromCommandMenu(Player player) {
        if (!hasPermission(player, "bodyguard.teleport")) {
            showResult(player, "gui-no-permission", "&cこの操作を使う権限がありません。", Map.of(), false);
            return;
        }
        CommandCounts before = commandCounts(player);
        int changed = manager.teleportGuards(player);
        showCommandResult(player, "gui-command-recall-result",
                "&a移動: {changed}体 &7/ &8操作不可: {unavailable}体 &7/ &e未移動: {failed}体",
                changed, before.unavailable(), before.available() - changed, Map.of());
    }

    private void healFromCommandMenu(Player player) {
        if (!hasPermission(player, "bodyguard.heal")) {
            showResult(player, "gui-no-permission", "&cこの操作を使う権限がありません。", Map.of(), false);
            return;
        }
        CommandCounts before = commandCounts(player);
        int changed = manager.healGuards(player);
        showCommandResult(player, "gui-command-heal-result",
                "&a回復: {changed}体 &7/ &8操作不可: {unavailable}体 &7/ &e全回復済み・対象外: {failed}体",
                changed, before.unavailable(), before.available() - changed, Map.of());
    }

    private void showCommandResult(Player player, String key, String fallback,
                                   int changed, int unavailable, int failed, Map<String, String> extra) {
        Map<String, String> placeholders = new HashMap<>(extra);
        placeholders.put("changed", String.valueOf(changed));
        placeholders.put("unavailable", String.valueOf(Math.max(0, unavailable)));
        placeholders.put("failed", String.valueOf(Math.max(0, failed)));
        showResult(player, key, fallback, placeholders, changed > 0);
    }

    private void handleTutorialClick(Player player, BodyGuardMenuHolder holder, int slot) {
        int page = holder.getPage();
        if (page == 0 && slot == 13 && hasPermission(player, "bodyguard.summon")) {
            if (playerDataStorage.setState(player.getUniqueId(), State.COMPLETED)
                    != SafeYamlFile.SaveResult.SUCCESS) {
                showResult(player, "storage-unavailable",
                        "&c案内状態を保存できなかったため、もう一度試してください。", Map.of(), false);
                return;
            }
            transition(player, () -> openSummon(player, 0, BodyGuardMenuHolder.GuardFilter.ALL,
                    BodyGuardMenuHolder.GuardSort.STANDARD));
            return;
        }
        switch (slot) {
            case 18 -> {
                if (page > 0) {
                    transition(player, () -> openTutorial(player, page - 1, holder.isReleaseAll()));
                }
            }
            case 20 -> finishTutorial(player, holder, State.COMPLETED);
            case 22 -> finishTutorial(player, holder, State.DISMISSED);
            case 24 -> {
                if (page < 2) {
                    transition(player, () -> openTutorial(player, page + 1, holder.isReleaseAll()));
                } else {
                    finishTutorial(player, holder, State.COMPLETED);
                }
            }
            case 26 -> player.closeInventory();
            default -> {
                // Guide content and filler slots intentionally do nothing.
            }
        }
    }

    private void finishTutorial(Player player, BodyGuardMenuHolder holder, State state) {
        if (playerDataStorage.setState(player.getUniqueId(), state)
                != SafeYamlFile.SaveResult.SUCCESS) {
            showResult(player, "storage-unavailable",
                    "&c案内状態を保存できなかったため、今回は変更を保存していません。", Map.of(), false);
            return;
        }
        transition(player, () -> {
            if (holder.isReleaseAll()) {
                openCommandMenu(player);
            } else {
                openList(player, 0, BodyGuardMenuHolder.GuardFilter.ALL,
                        BodyGuardMenuHolder.GuardSort.STANDARD);
            }
        });
    }

    private void refreshSummon(Player player, BodyGuardMenuHolder holder, Inventory inventory) {
        fillHeader(inventory, player, true, holder.getFilter(), holder.getSort(),
                manager.countGuards(player.getUniqueId()));
        for (int index = 0; index < holder.getMobTypes().size(); index++) {
            inventory.setItem(CONTENT_START + index, summonIcon(holder.getMobTypes().get(index), player));
        }
        inventory.setItem(48, item(Material.CHEST, text("gui.count", "&7護衛数: &f{count}/{limit}",
                Map.of("count", String.valueOf(manager.countGuards(player.getUniqueId())),
                        "limit", summonLimitLabel(player))),
                List.of(summonLimitLore(player))));
        inventory.setItem(49, resultItem(player));
    }

    private void refreshDetail(Player player, BodyGuardMenuHolder holder, Inventory inventory) {
        GuardData data = ownedGuard(player, holder.getGuardId());
        fillInventory(inventory, detailBackground(data));
        renderDetail(inventory, player, data);
    }

    private void refreshManagement(Player player, Inventory inventory) {
        int count = manager.countGuards(player.getUniqueId());
        inventory.setItem(4, item(Material.CHEST, text("gui.management-summary", "&e現在の護衛: &f{count}体",
                Map.of("count", String.valueOf(count))),
                List.of(text("gui.management-summary-lore", "&7解除対象は確認画面を開いた時点で固定されます。"))));
        inventory.setItem(22, resultItem(player));
    }

    private void handleListClick(Player player, BodyGuardMenuHolder holder, int slot,
                                 ClickType click, boolean shiftClick) {
        if (slot == 0) {
            transition(player, () -> openTutorial(player, 0, false));
            return;
        }
        if (slot == 2) {
            clearResult(player);
            transition(player, () -> openList(player, 0, holder.getFilter().next(), holder.getSort()));
            return;
        }
        if (slot == 6) {
            clearResult(player);
            transition(player, () -> openList(player, 0, holder.getFilter(), holder.getSort().next()));
            return;
        }
        if (holder.getFilteredGuardCount() == 0) {
            if (holder.getTotalGuardCount() == 0 && slot == 20) {
                transition(player, () -> openSummon(player, 0, holder.getFilter(), holder.getSort()));
                return;
            }
            if (holder.getTotalGuardCount() == 0 && slot == 24) {
                if (!hasPermission(player, "bodyguard.recruit")) {
                    showResult(player, "gui-no-permission",
                            "&c野生のMobを仲間にする権限がありません。", Map.of(), false);
                    return;
                }
                showResult(player, "gui-recruit-guide-result",
                        "&b対象Mobを見て &f/bg recruit &bを実行します。成功すると護衛になります。",
                        Map.of(), true, false);
                return;
            }
            if (holder.getTotalGuardCount() > 0 && (slot == 22 || slot == 24)) {
                clearResult(player);
                transition(player, () -> openList(player, 0, BodyGuardMenuHolder.GuardFilter.ALL,
                        holder.getSort()));
                return;
            }
        }
        if (slot < PREVIOUS_SLOT) {
            int index = slot - CONTENT_START;
            if (index >= 0 && index < holder.getGuardIds().size()) {
                UUID guardId = holder.getGuardIds().get(index);
                if (holder.getFilter() == BodyGuardMenuHolder.GuardFilter.HISTORY) {
                    showResult(player, "gui-history-read-only",
                            "&e履歴カードは表示専用です。通常の護衛操作はできません。", Map.of(), false);
                    return;
                }
                if (shiftClick && click != null && click.isRightClick()) {
                    quickHeal(player, holder, guardId);
                } else if (shiftClick && click != null && click.isLeftClick()) {
                    quickTeleport(player, holder, guardId);
                } else if (click != null && click.isRightClick()) {
                    quickNextMode(player, holder, guardId);
                } else if (click == null || click.isLeftClick()) {
                    transition(player, () -> openDetails(player, guardId,
                            holder.getPage(), holder.getFilter(), holder.getSort()));
                }
            }
            return;
        }
        switch (slot) {
            case PREVIOUS_SLOT -> transition(player, () -> openList(player, holder.getPage() - 1,
                    holder.getFilter(), holder.getSort()));
            case 46 -> {
                if (hasPermission(player, "bodyguard.summon")) {
                    transition(player, () -> openSummon(player, 0, holder.getFilter(), holder.getSort()));
                } else {
                    showResult(player, "gui-no-permission", "&cこの操作を使う権限がありません。", Map.of(), false);
                }
            }
            case 47 -> {
                if (!hasPermission(player, "bodyguard.teleport")) {
                    showResult(player, "gui-no-permission", "&cこの操作を使う権限がありません。", Map.of(), false);
                    return;
                }
                int count = manager.teleportGuards(player);
                showResult(player, count == 0 ? "gui-recall-none" : "gui-recall-result",
                        count == 0 ? "&e現在呼び戻せる護衛がいません。" : "&a{count}体の護衛を呼び戻しました。",
                        Map.of("count", String.valueOf(count)), count > 0);
                transition(player, () -> openList(player, holder.getPage(), holder.getFilter(), holder.getSort()));
            }
            case 48 -> {
                if (!hasPermission(player, "bodyguard.heal")) {
                    showResult(player, "gui-no-permission", "&cこの操作を使う権限がありません。", Map.of(), false);
                    return;
                }
                int count = manager.healGuards(player);
                showResult(player, count == 0 ? "gui-heal-none" : "gui-heal-result",
                        count == 0 ? "&e現在回復できる護衛がいません。" : "&a{count}体の護衛を回復しました。",
                        Map.of("count", String.valueOf(count)), count > 0);
                transition(player, () -> openList(player, holder.getPage(), holder.getFilter(), holder.getSort()));
            }
            case 50 -> transition(player, () -> openManagement(player, holder.getPage(),
                    holder.getFilter(), holder.getSort()));
            case 51 -> transition(player, () -> openList(player, holder.getPage(),
                    holder.getFilter(), holder.getSort()));
            case NEXT_SLOT -> transition(player, () -> openList(player, holder.getPage() + 1,
                    holder.getFilter(), holder.getSort()));
            case CLOSE_SLOT -> player.closeInventory();
            default -> {
                // Information and filler slots intentionally do nothing.
            }
        }
    }

    private void quickNextMode(Player player, BodyGuardMenuHolder holder, UUID guardId) {
        GuardData data = ownedGuard(player, guardId);
        if (data == null) {
            showUnavailableAndReturn(player, holder);
            return;
        }
        if (!hasPermission(player, "bodyguard.mode")) {
            showResult(player, "gui-no-permission", "&cモードを変更する権限がありません。", Map.of(), false);
            return;
        }
        if (manager.getLoadedMob(data) == null) {
            showResult(player, "gui-unavailable-reason",
                    "&e現在この護衛の状態を確認できないため、モードを変更できません。",
                    Map.of(), false);
            return;
        }
        GuardMode next = switch (data.getMode()) {
            case FOLLOW -> GuardMode.STAY;
            case STAY -> GuardMode.GUARD;
            case GUARD -> GuardMode.FOLLOW;
        };
        if (!manager.setMode(player.getUniqueId(), guardId, next, player.getLocation())) {
            showUnavailableAndReturn(player, holder);
            return;
        }
        if (next == GuardMode.GUARD) {
            showResult(player, "gui-guard-point-set",
                    "&a{name}の警備地点を &f{world} {x}, {y}, {z} &aに設定しました。",
                    guardPointPlaceholders(player, data), true);
        } else {
            showResult(player, "gui-mode-changed", "&a{name}：&f{mode} &aに変更しました。",
                    Map.of("name", plugin.color(data.getName()), "mode", next.japaneseName()), true);
        }
        reopenList(player, holder);
    }

    private void quickHeal(Player player, BodyGuardMenuHolder holder, UUID guardId) {
        if (!hasPermission(player, "bodyguard.heal")) {
            showResult(player, "gui-no-permission", "&c回復する権限がありません。", Map.of(), false);
            return;
        }
        int outcome = manager.healGuard(player.getUniqueId(), guardId);
        if (outcome > 0) {
            showResult(player, "gui-single-heal-result", "&a{name}を回復しました。",
                    Map.of("name", currentGuardName(player, guardId)), true);
        } else if (outcome == 0) {
            showResult(player, "gui-single-heal-full", "&eすでに全回復しています。", Map.of(), false);
        } else {
            showResult(player, "gui-unavailable-reason",
                    "&e現在この護衛の状態を確認できないため、回復できません。", Map.of(), false);
        }
        reopenList(player, holder);
    }

    private void quickTeleport(Player player, BodyGuardMenuHolder holder, UUID guardId) {
        if (!hasPermission(player, "bodyguard.teleport")) {
            showResult(player, "gui-no-permission", "&c呼び戻す権限がありません。", Map.of(), false);
            return;
        }
        boolean teleported = manager.teleportGuard(player.getUniqueId(), guardId);
        if (teleported) {
            showResult(player, "gui-single-recall-result", "&a{name}を呼び戻しました。",
                    Map.of("name", currentGuardName(player, guardId)), true);
        } else {
            showResult(player, "gui-single-recall-none",
                    "&eこの護衛は現在呼び戻せません。状態を確認してから再試行してください。",
                    Map.of(), false);
        }
        reopenList(player, holder);
    }

    private void reopenList(Player player, BodyGuardMenuHolder holder) {
        transition(player, () -> openList(player, holder.getPage(), holder.getFilter(), holder.getSort()));
    }

    private void handleSummonClick(Player player, BodyGuardMenuHolder holder, int slot, ClickType click) {
        if (slot < PREVIOUS_SLOT) {
            int index = slot - CONTENT_START;
            if (index >= 0 && index < holder.getMobTypes().size()
                    && (click.isLeftClick() || click.isRightClick())) {
                EntityType type = holder.getMobTypes().get(index);
                boolean summoned = command.summonFromMenu(player, type);
                if (summoned) {
                    showResult(player, "gui-summon-result", "&a{mob}を召喚しました。",
                            Map.of("mob", mobName(type)), true);
                    if (click.isRightClick()) {
                        transition(player, () -> openList(player, 0, holder.getFilter(), holder.getSort()));
                    } else {
                        // Keep the candidate screen open so another type can be summoned immediately.
                        refreshSummon(player, holder, player.getOpenInventory().getTopInventory());
                    }
                } else {
                    showResult(player, summonFailureKey(player, type), summonFailureFallback(player, type),
                            Map.of("limit", String.valueOf(plugin.getMaxGuardsPerPlayer())), false);
                    transition(player, () -> openSummon(player, holder.getPage(),
                            holder.getFilter(), holder.getSort()));
                }
            }
            return;
        }
        switch (slot) {
            case 46 -> transition(player, () -> openList(player, 0, holder.getFilter(), holder.getSort()));
            case PREVIOUS_SLOT -> transition(player, () -> openSummon(player, holder.getPage() - 1,
                    holder.getFilter(), holder.getSort()));
            case 50 -> transition(player, () -> openSummon(player, holder.getPage(),
                    holder.getFilter(), holder.getSort()));
            case NEXT_SLOT -> transition(player, () -> openSummon(player, holder.getPage() + 1,
                    holder.getFilter(), holder.getSort()));
            case CLOSE_SLOT -> player.closeInventory();
            default -> {
                // Information and result slots intentionally do nothing.
            }
        }
    }

    private void handleDetailClick(Player player, BodyGuardMenuHolder holder, int slot) {
        if (slot == 7) {
            cycleProtection(player, holder);
            return;
        }
        if (slot == 10 || slot == 13 || slot == 16) {
            GuardMode mode = slot == 10 ? GuardMode.FOLLOW : slot == 13 ? GuardMode.STAY : GuardMode.GUARD;
            GuardData data = ownedGuard(player, holder.getGuardId());
            if (data == null) {
                showUnavailableAndReturn(player, holder);
                return;
            }
            if (data.getMode() == mode && mode != GuardMode.GUARD) {
                return;
            }
            if (!hasPermission(player, "bodyguard.mode")) {
                showResult(player, "gui-no-permission", "&cこの操作を使う権限がありません。", Map.of(), false);
                return;
            }
            if (manager.getLoadedMob(data) == null) {
                showResult(player, "gui-unavailable-reason",
                        "&e現在この護衛の状態を確認できません。更新してから再試行してください。",
                        Map.of(), false);
                return;
            }
            if (!manager.setMode(player.getUniqueId(), holder.getGuardId(), mode, player.getLocation())) {
                showUnavailableAndReturn(player, holder);
                return;
            }
            if (mode == GuardMode.GUARD) {
                showResult(player, "gui-guard-point-set",
                        "&a{name}の警備地点を &f{world} {x}, {y}, {z} &aに設定しました。",
                        guardPointPlaceholders(player, data), true);
            } else {
                showResult(player, "gui-mode-changed", "&a{name}：&f{mode} &aに変更しました。",
                        Map.of("name", plugin.color(data.getName()), "mode", mode.japaneseName()), true);
            }
            transition(player, () -> openDetails(player, holder.getGuardId(), holder.getPage(),
                    holder.getFilter(), holder.getSort()));
            return;
        }
        switch (slot) {
            case 19 -> {
                if (hasPermission(player, "bodyguard.rename")) {
                    transition(player, () -> openNameInput(player, holder.getGuardId(), holder.getPage(),
                            holder.getFilter(), holder.getSort()));
                } else {
                    showResult(player, "gui-no-permission", "&cこの操作を使う権限がありません。", Map.of(), false);
                }
            }
            case 22 -> singleHeal(player, holder);
            case 25 -> singleTeleport(player, holder);
            case 28 -> toggleFavorite(player, holder);
            case 31 -> {
                if (hasPermission(player, "bodyguard.release")) {
                    transition(player, () -> openSingleReleaseConfirmation(player, holder));
                } else {
                    showResult(player, "gui-no-permission", "&cこの操作を使う権限がありません。", Map.of(), false);
                }
            }
            case 34 -> toggleCompanion(player, holder);
            case 36 -> transition(player, () -> openList(player, holder.getPage(),
                    holder.getFilter(), holder.getSort()));
            case 42 -> transition(player, () -> openDetails(player, holder.getGuardId(), holder.getPage(),
                    holder.getFilter(), holder.getSort()));
            case 44 -> player.closeInventory();
            default -> {
                // Information and filler slots intentionally do nothing.
            }
        }
    }

    private void cycleProtection(Player player, BodyGuardMenuHolder holder) {
        if (!hasPermission(player, "bodyguard.protect")) {
            showResult(player, "gui-no-permission", "&c保護対象を変更する権限がありません。", Map.of(), false);
            return;
        }
        GuardData data = ownedGuard(player, holder.getGuardId());
        if (data == null) {
            showUnavailableAndReturn(player, holder);
            return;
        }
        List<String> choices = new ArrayList<>();
        choices.add("owner");
        if (plugin.isRoleProtectionEnabled()) {
            choices.addAll(plugin.getRoleDefinitions().stream()
                    .map(RoleDefinition::id).toList());
        }
        String current = data.isRoleProtection() ? data.getRoleId() : "owner";
        int currentIndex = choices.indexOf(current);
        String next = choices.get((currentIndex < 0 ? 0 : currentIndex + 1) % choices.size());
        GuardManager.ProtectionChangeResult result = manager.setProtection(
                player.getUniqueId(), data.getGuardId(),
                next.equals("owner") ? GuardData.ProtectionKind.OWNER : GuardData.ProtectionKind.ROLE,
                next.equals("owner") ? null : next);
        switch (result) {
            case SUCCESS -> showResult(player, "protect-changed",
                    "&a{name}の保護対象を &f{setting} &aに設定しました。",
                    Map.of("name", plugin.color(data.getName()),
                            "setting", next.equals("owner") ? "所有者" : next), true);
            case ROLE_NOT_CONFIGURED -> showResult(player, "protect-role-not-configured",
                    "&cその役職IDは現在の設定にありません。", Map.of(), false);
            case NOT_LOADED -> showResult(player, "protect-unavailable",
                    "&e護衛のEntityが未読み込みのため変更できません。", Map.of(), false);
            case SAVE_FAILED -> showResult(player, "storage-unavailable",
                    "&c保護設定を保存できなかったため、変更していません。", Map.of(), false);
            case NOT_OWNER, NOT_FOUND -> {
                showUnavailableAndReturn(player, holder);
                return;
            }
        }
        transition(player, () -> openDetails(player, holder.getGuardId(), holder.getPage(),
                holder.getFilter(), holder.getSort()));
    }

    private void singleHeal(Player player, BodyGuardMenuHolder holder) {
        if (!hasPermission(player, "bodyguard.heal")) {
            showResult(player, "gui-no-permission", "&cこの操作を使う権限がありません。", Map.of(), false);
            return;
        }
        int outcome = manager.healGuard(player.getUniqueId(), holder.getGuardId());
        if (outcome > 0) {
            showResult(player, "gui-single-heal-result", "&a{name}を回復しました。",
                    Map.of("name", currentGuardName(player, holder.getGuardId())), true);
        } else if (outcome == 0) {
            showResult(player, "gui-single-heal-full", "&eすでに全回復しています。", Map.of(), false);
        } else {
            showResult(player, "gui-unavailable-reason",
                    "&e現在この護衛の状態を確認できません。護衛の状態を確認できる場所で更新してください。",
                    Map.of(), false);
        }
        transition(player, () -> openDetails(player, holder.getGuardId(), holder.getPage(),
                holder.getFilter(), holder.getSort()));
    }

    private void toggleFavorite(Player player, BodyGuardMenuHolder holder) {
        GuardData data = ownedGuard(player, holder.getGuardId());
        if (data == null || !manager.toggleFavorite(player.getUniqueId(), holder.getGuardId())) {
            showUnavailableAndReturn(player, holder);
            return;
        }
        boolean favorite = data.isFavorite();
        showResult(player, favorite ? "gui-favorite-added" : "gui-favorite-removed",
                favorite ? "&a{name}をお気に入りに登録しました。" : "&e{name}のお気に入りを解除しました。",
                Map.of("name", plugin.color(data.getName())), true);
        transition(player, () -> openDetails(player, holder.getGuardId(), holder.getPage(),
                holder.getFilter(), holder.getSort()));
    }

    private void toggleCompanion(Player player, BodyGuardMenuHolder holder) {
        GuardData data = ownedGuard(player, holder.getGuardId());
        if (data == null || !manager.toggleCompanion(player.getUniqueId(), holder.getGuardId())) {
            showUnavailableAndReturn(player, holder);
            return;
        }
        boolean companion = manager.isCompanion(data);
        showResult(player, companion ? "gui-companion-set" : "gui-companion-removed",
                companion ? "&a{name}を相棒に設定しました。" : "&e相棒設定を解除しました。",
                Map.of("name", plugin.color(data.getName())), true);
        transition(player, () -> openDetails(player, holder.getGuardId(), holder.getPage(),
                holder.getFilter(), holder.getSort()));
    }

    private void singleTeleport(Player player, BodyGuardMenuHolder holder) {
        if (!hasPermission(player, "bodyguard.teleport")) {
            showResult(player, "gui-no-permission", "&cこの操作を使う権限がありません。", Map.of(), false);
            return;
        }
        boolean teleported = manager.teleportGuard(player.getUniqueId(), holder.getGuardId());
        if (teleported) {
            showResult(player, "gui-single-recall-result", "&a{name}を呼び戻しました。",
                    Map.of("name", currentGuardName(player, holder.getGuardId())), true);
        } else {
            showResult(player, "gui-single-recall-none",
                    "&eこの護衛は現在呼び戻せません。状態を確認してから再試行してください。",
                    Map.of(), false);
        }
        transition(player, () -> openDetails(player, holder.getGuardId(), holder.getPage(),
                holder.getFilter(), holder.getSort()));
    }

    private void handleManagementClick(Player player, BodyGuardMenuHolder holder, int slot) {
        switch (slot) {
            case 10 -> transition(player, () -> openList(player, 0,
                    BodyGuardMenuHolder.GuardFilter.MISSING, holder.getSort()));
            case 11 -> transition(player, () -> openList(player, 0,
                    BodyGuardMenuHolder.GuardFilter.HISTORY, holder.getSort()));
            case 13 -> {
                if (hasPermission(player, "bodyguard.releaseall")) {
                    transition(player, () -> openAllReleaseConfirmation(player, holder.getPage(),
                            holder.getFilter(), holder.getSort()));
                } else {
                    showResult(player, "gui-no-permission", "&cこの操作を使う権限がありません。", Map.of(), false);
                }
            }
            case 15 -> {
                if (hasPermission(player, "bodyguard.deleteall")) {
                    transition(player, () -> openAllDeleteConfirmation(player, holder.getPage(),
                            holder.getFilter(), holder.getSort()));
                } else {
                    showResult(player, "gui-no-permission", "&cこの操作を使う権限がありません。", Map.of(), false);
                }
            }
            case 18 -> transition(player, () -> {
                if (holder.getPage() < 0) {
                    openCommandMenu(player);
                } else {
                    openList(player, holder.getPage(), holder.getFilter(), holder.getSort());
                }
            });
            case 26 -> player.closeInventory();
            default -> {
                // Summary, result, and filler slots intentionally do nothing.
            }
        }
    }

    private void openSingleReleaseConfirmation(Player player, BodyGuardMenuHolder detailHolder) {
        GuardData data = ownedGuard(player, detailHolder.getGuardId());
        if (data == null) {
            showResult(player, "gui-unavailable-reason",
                    "&e対象の護衛を確認できないため、解除できません。", Map.of(), false);
            return;
        }
        openReleaseConfirmation(player, List.of(detailHolder.getGuardId()), false,
                detailHolder.getPage(), data.getName(), 1,
                detailHolder.getFilter(), detailHolder.getSort());
    }

    private void handleConfirmationClick(Player player, BodyGuardMenuHolder holder, int slot) {
        if (slot == 11) {
            String permission = holder.isDeleteAll() ? "bodyguard.deleteall"
                    : holder.isReleaseAll() ? "bodyguard.releaseall" : "bodyguard.release";
            if (!hasPermission(player, permission)) {
                showResult(player, "gui-no-permission", "&cこの操作を使う権限がありません。", Map.of(), false);
                return;
            }
            if (!holder.consumeConfirmation()) {
                showResult(player, "gui-already-processed",
                        "&eこの確認画面はすでに処理済みです。最新の一覧を開いてください。", Map.of(), false);
                return;
            }
            if (holder.isDeleteAll()) {
                GuardManager.DeleteResult result = manager.deleteGuards(
                        player.getUniqueId(), holder.getGuardIds());
                if (result.affected() == 0) {
                    showResult(player, "gui-delete-none", "&e完全削除または削除予約にできる護衛がありません。",
                            Map.of(), false);
                } else {
                    showResult(player, "gui-delete-summary",
                            "&c完全削除: &f{deleted}体 &7/ &e削除予約: &f{queued}体 &7/ &c失敗: &f{failed}体",
                            Map.of("deleted", String.valueOf(result.deleted()),
                                    "queued", String.valueOf(result.queued()),
                                    "failed", String.valueOf(result.failed())),
                            result.queued() == 0 && result.failed() == 0);
                }
                transition(player, () -> openList(player, holder.getPage(),
                        holder.getFilter(), holder.getSort()));
                return;
            }
            GuardManager.ReleaseResult result = manager.releaseGuards(
                    player.getUniqueId(), holder.getGuardIds());
            if (result.affected() == 0) {
                showResult(player, "gui-release-none",
                        "&e解除または解除予約にできる護衛がありません。死亡・解除済みの可能性があります。",
                        Map.of(), false);
            } else {
                showResult(player, "gui-release-summary",
                        "&a解除済み: &f{released}体 &7/ &e解除予約: &f{queued}体 &7/ &c失敗: &f{failed}体",
                        Map.of("released", String.valueOf(result.released()),
                                "queued", String.valueOf(result.queued()),
                                "failed", String.valueOf(result.failed())),
                        result.queued() == 0 && result.failed() == 0);
            }
            transition(player, () -> openList(player, holder.getPage(),
                    holder.getFilter(), holder.getSort()));
            return;
        }
        if (slot == 15 || slot == 22) {
            transition(player, () -> {
                if (holder.isReleaseAll()) {
                    openManagement(player, holder.getPage(), holder.getFilter(), holder.getSort());
                } else if (ownedGuard(player, holder.getGuardId()) != null) {
                    openDetails(player, holder.getGuardId(), holder.getPage(),
                            holder.getFilter(), holder.getSort());
                } else {
                    openList(player, holder.getPage(), holder.getFilter(), holder.getSort());
                }
            });
            return;
        }
        if (slot == 26) {
            player.closeInventory();
        }
    }

    private void handleNameInputClick(Player player, BodyGuardMenuHolder holder,
                                       Inventory top, int slot) {
        if (slot != 2) {
            return;
        }
        if (!hasPermission(player, "bodyguard.rename")) {
            messages.send(player, "no-permission");
            player.closeInventory();
            return;
        }
        if (!(top instanceof AnvilInventory anvil)) {
            return;
        }
        ItemStack result = anvil.getItem(2);
        if (result == null || result.getType().isAir()) {
            return;
        }
        String rawName = anvil.getRenameText();
        if (rawName == null || rawName.isBlank()) {
            messages.send(player, "gui-rename-invalid",
                    "&c名前は空白だけにできません。1～50文字で入力してください。");
            return;
        }
        if (rawName.length() > 50) {
            messages.send(player, "gui-rename-invalid", "&c標準金床の入力欄は1～50文字です。");
            return;
        }
        GuardData data = ownedGuard(player, holder.getGuardId());
        if (data == null || manager.getLoadedMob(data) == null) {
            messages.send(player, "gui-rename-unavailable",
                    "&e対象の護衛が死亡・解除されたか、現在の状態を確認できません。変更は保存されません。");
            player.closeInventory();
            return;
        }
        if (!manager.renameGuard(player.getUniqueId(), holder.getGuardId(), plugin.color(rawName))) {
            messages.send(player, "gui-rename-unavailable",
                    "&e所有者・権限・護衛の状態を再確認できなかったため、変更を保存しませんでした。");
            player.closeInventory();
            return;
        }
        showResult(player, "gui-renamed", "&a護衛の名前を変更しました。", Map.of(), true);
        transition(player, () -> openDetails(player, holder.getGuardId(), holder.getPage(),
                holder.getFilter(), holder.getSort()));
    }

    private void showUnavailableAndReturn(Player player, BodyGuardMenuHolder holder) {
        showResult(player, "gui-guard-unavailable",
                "&cその護衛は解除されたか、現在の状態を確認できません。", Map.of(), false);
        transition(player, () -> openList(player, holder.getPage(), holder.getFilter(), holder.getSort()));
    }

    private boolean canUseMenu(Player player) {
        if (player == null || !player.isOnline()) {
            return false;
        }
        if (hasPermission(player, "bodyguard.use")) {
            return true;
        }
        messages.send(player, "no-permission");
        player.closeInventory();
        return false;
    }

    private boolean hasPermission(Player player, String permission) {
        return player != null && (player.hasPermission(permission)
                || player.hasPermission("bodyguard.admin"));
    }

    private GuardData ownedGuard(Player player, UUID guardId) {
        GuardData data = manager.getGuardData(guardId);
        return data != null && !data.isRetired() && player != null && player.getUniqueId().equals(data.getOwnerId())
                ? data : null;
    }

    private List<EntityType> summonableTypes() {
        return plugin.getAllowedMobTypes().stream()
                .filter(plugin::isSupportedMobType)
                .sorted(Comparator.comparing((EntityType type) -> mobName(type))
                        .thenComparing(EntityType::name))
                .toList();
    }

    private ItemStack guardIcon(Player player, GuardData data) {
        if (data.isRetired()) {
            return item(Material.BARRIER, "§7" + ChatColor.stripColor(plugin.color(data.getName()))
                    + " §8— §e" + manager.status(data).label(),
                    List.of("§7通常の護衛数には含まれません。", "§7このカードからは操作できません。",
                            "§7一覧を整理すると通常一覧から取り除きます。"));
        }
        Mob mob = manager.getLoadedMob(data);
        List<String> lore = new ArrayList<>();
        lore.add(text("gui.guard-mob", "&7種類: &f{mob}",
                Map.of("mob", mobName(data.getMobType()))));
        if (mob == null) {
            addUnavailableLore(lore, data);
        } else {
            String health = EntityUtil.healthText(mob);
            lore.add(health == null
                    ? text("gui.guard-health-unknown", "&7HP: &f不明（推測しません）")
                    : text("gui.guard-health", "&7HP: &f{health}", Map.of("health", health)));
            addHealthBar(lore, mob);
        }
        lore.add(text("gui.guard-mode", "&7行動: &f{mode}",
                Map.of("mode", data.getMode().japaneseName())));
        if (mob != null) {
            lore.add(statusLine(player, mob));
        }
        lore.add(" ");
        lore.add(text("gui.guard-click", "&b左クリック &7: 詳細を開く"));
        lore.add(text("gui.guard-right-click", "&b右クリック &7: 次のモードへ変更"));
        lore.add(text("gui.guard-shift-left", "&bShift＋左 &7: この護衛を呼ぶ"));
        lore.add(text("gui.guard-shift-right", "&bShift＋右 &7: この護衛を回復"));
        boolean companion = manager.isCompanion(data);
        boolean highlighted = companion || data.isFavorite();
        String prefix = companion ? text("gui.guard-companion-prefix", "&6★ &e相棒 &8｜ ")
                : data.isFavorite() ? text("gui.guard-favorite-prefix", "&e☆ ") : "";
        if (companion) {
            lore.add(0, text("gui.guard-companion", "&6★ 相棒"));
        } else if (data.isFavorite()) {
            lore.add(0, text("gui.guard-favorite", "&e☆ お気に入り"));
        }
        return item(spawnEgg(data.getMobType()), prefix + plugin.color(data.getName()), lore, highlighted);
    }

    private ItemStack invalidGuardIcon() {
        return item(Material.BARRIER, text("gui.guard-invalid-title", "&cこの護衛は現在利用できません"),
                List.of(text("gui.guard-invalid-lore-1", "&7死亡・解除などで対象が無効になりました。"),
                        text("gui.guard-invalid-lore-2", "&7「手動更新」で一覧を読み直してください。")));
    }

    private ItemStack summonIcon(EntityType type, Player player) {
        int count = manager.countGuards(player.getUniqueId());
        boolean permission = hasPermission(player, "bodyguard.summon");
        boolean allowed = plugin.isAllowedMobType(type) && plugin.isSupportedMobType(type);
        boolean full = !player.isOp() && count >= plugin.getMaxGuardsPerPlayer();
        boolean enabled = permission && allowed && !full;
        List<String> lore = new ArrayList<>();
        lore.add(text("gui.summon-mob", "&7Mob: &f{mob}", Map.of("mob", mobName(type))));
        lore.add(text("gui.summon-count", "&7現在: &f{count}/{limit}", Map.of(
                "count", String.valueOf(count), "limit", summonLimitLabel(player))));
        lore.addAll(mobFeatures(type));
        lore.add(" ");
        if (!permission) {
            lore.add(text("gui.permission-required", "&cこの操作を使う権限がありません。"));
        } else if (!allowed) {
            lore.add(text("gui.summon-not-allowed-now", "&c現在の設定では召喚できません。"));
        } else if (full) {
            lore.add(text("gui.summon-full", "&c上限に達しているため召喚できません。"));
        } else {
            lore.add(text("gui.summon-left-click", "&a左クリック: 召喚して候補画面に残る"));
            lore.add(text("gui.summon-right-click", "&b右クリック: 召喚して一覧へ戻る"));
        }
        return item(enabled ? spawnEgg(type) : Material.GRAY_DYE,
                (enabled ? ChatColor.AQUA : ChatColor.GRAY) + mobName(type), lore);
    }

    private ItemStack modeItem(Player player, GuardData data, Mob mob, GuardMode mode) {
        boolean current = data != null && data.getMode() == mode;
        boolean allowed = hasPermission(player, "bodyguard.mode");
        boolean enabled = data != null && mob != null && allowed;
        Material icon = switch (mode) {
            case FOLLOW -> Material.LEAD;
            case STAY -> Material.ANVIL;
            case GUARD -> Material.SHIELD;
        };
        List<String> lore = new ArrayList<>();
        lore.add(modeDescription(data, mode));
        if (mode == GuardMode.GUARD) {
            lore.add(text("gui.guard-point-how", "&bクリック時に、あなたが立っている場所を警備地点にします。"));
            Location anchor = data == null ? null : data.getAnchorLocation();
            if (data != null && data.getMode() == GuardMode.GUARD && anchor != null) {
                lore.add(text("gui.guard-point-current", "&7現在の地点: &f{world} {x}, {y}, {z}", Map.of(
                        "world", anchor.getWorld() == null ? "不明" : anchor.getWorld().getName(),
                        "x", String.valueOf(anchor.getBlockX()),
                        "y", String.valueOf(anchor.getBlockY()),
                        "z", String.valueOf(anchor.getBlockZ()))));
                lore.add(text("gui.guard-point-reset", "&e選択中でもクリックすると地点を更新できます。"));
            }
        }
        lore.add(" ");
        if (current) {
            lore.add(text("gui.current-mode", "&a選択中のモードです。"));
        }
        if (!allowed) {
            lore.add(text("gui.permission-required", "&c権限がありません。"));
        }
        if (data == null || mob == null) {
            lore.add(text("gui.unloaded-action",
                    "&e現在この護衛の状態を確認できないため操作できません。"));
            lore.add(text("gui.unavailable-next",
                    "&7状態を確認できる場所で手動更新してください。"));
        } else if (!current) {
            lore.add(text("gui.click-mode", "&7クリックで変更"));
        }
        String name = current && enabled
                ? text("gui.mode-selected", "&a{mode}（選択中）", Map.of("mode", mode.japaneseName()))
                : !enabled
                ? text("gui.mode-disabled", "&7{mode}（操作不可）", Map.of("mode", mode.japaneseName()))
                : text("gui.mode-name", "&b{mode}", Map.of("mode", mode.japaneseName()));
        return item(enabled ? icon : Material.GRAY_DYE, name, lore, current && enabled);
    }

    private String modeDescription(GuardMode mode) {
        return switch (mode) {
            case FOLLOW -> text("gui.mode-follow", "&7所有者の近くへ付いてきます。");
            case STAY -> text("gui.mode-stay", "&7その場で待機し、必要時に守ります。");
            case GUARD -> text("gui.mode-guard", "&7あなたが立っている地点を中心に、範囲内だけを警備します。");
        };
    }

    private String modeDescription(GuardData data, GuardMode mode) {
        if (data != null && data.isRoleProtection() && mode == GuardMode.GUARD) {
            return text("gui.mode-guard-role",
                    "&7保護対象の周囲を中心に、範囲内だけを移動警備します。");
        }
        return modeDescription(mode);
    }

    private Map<String, String> guardPointPlaceholders(Player player, GuardData data) {
        plugin.test.com.bodyGuard.guard.SavedPosition anchor = data.getSavedAnchor();
        if (anchor == null) {
            return Map.of("name", plugin.color(data.getName()), "world", "未設定",
                    "x", "-", "y", "-", "z", "-");
        }
        return Map.of(
                "name", plugin.color(data.getName()),
                "world", anchor.worldName() == null || anchor.worldName().isBlank()
                        ? (anchor.worldId() == null ? "不明" : anchor.worldId().toString())
                        : anchor.worldName(),
                "x", String.valueOf((int) Math.floor(anchor.x())),
                "y", String.valueOf((int) Math.floor(anchor.y())),
                "z", String.valueOf((int) Math.floor(anchor.z())));
    }

    private void renderDetail(Inventory inventory, Player player, GuardData data) {
        Mob mob = data == null ? null : manager.getLoadedMob(data);
        if (data == null) {
            inventory.setItem(4, item(Material.BARRIER, text("gui.detail-invalid-title", "&c護衛を確認できません"),
                    List.of(text("gui.guard-invalid-lore-2", "&7一覧へ戻って更新してください。"))));
        } else {
            List<String> lore = new ArrayList<>();
            lore.add(text("gui.detail-name", "&7名前: &f{name}", Map.of("name", plugin.color(data.getName()))));
            lore.add(text("gui.detail-mob", "&7種類: &f{mob}", Map.of("mob", mobName(data.getMobType()))));
            lore.add(text("gui.detail-mode", "&7モード: &f{mode}",
                    Map.of("mode", data.getMode().japaneseName())));
            if (mob == null) {
                addUnavailableLore(lore, data);
            } else {
                String health = EntityUtil.healthText(mob);
                lore.add(health == null
                        ? text("gui.detail-health-unknown", "&7HP: &f不明（推測しません）")
                        : text("gui.detail-health", "&7HP: &f{health}", Map.of("health", health)));
                lore.add(statusLine(player, mob));
                addHealthBar(lore, mob);
            }
            lore.add(" ");
            lore.add(text("gui.detail-name-note", "&7長い名前も説明欄で確認できます。"));
            boolean companion = manager.isCompanion(data);
            if (companion) {
                lore.add(0, text("gui.guard-companion", "&6★ 相棒"));
            } else if (data.isFavorite()) {
                lore.add(0, text("gui.guard-favorite", "&e☆ お気に入り"));
            }
            inventory.setItem(4, item(spawnEgg(data.getMobType()), plugin.color(data.getName()), lore,
                    companion || data.isFavorite()));
        }

        inventory.setItem(10, modeItem(player, data, mob, GuardMode.FOLLOW));
        inventory.setItem(13, modeItem(player, data, mob, GuardMode.STAY));
        inventory.setItem(16, modeItem(player, data, mob, GuardMode.GUARD));
        inventory.setItem(7, protectionItem(player, data, mob));
        inventory.setItem(19, actionItem(player, data, mob, "bodyguard.rename", Material.NAME_TAG,
                "gui.rename-title", "&b名前変更",
                List.of(text("gui.rename-input-current", "&7クリックすると標準金床の入力画面を開きます。"),
                        text("gui.rename-input-limit", "&7標準金床の入力欄上限: &f50文字"))));
        inventory.setItem(22, actionItem(player, data, mob, "bodyguard.heal", Material.GOLDEN_APPLE,
                "gui.single-heal", "&aこの護衛を回復",
                List.of(text("gui.single-heal-lore", "&7この護衛1体だけを回復します。"))));
        inventory.setItem(25, actionItem(player, data, mob, "bodyguard.teleport", Material.COMPASS,
                "gui.single-recall", "&bこの護衛を呼ぶ",
                List.of(text("gui.single-recall-lore", "&7この護衛1体だけを安全な場所へ呼び戻します。"))));
        inventory.setItem(28, favoriteItem(data));
        inventory.setItem(34, companionItem(data));
        inventory.setItem(31, releaseActionItem(player, data, mob));
        inventory.setItem(36, item(Material.ARROW, text("gui.back", "&b一覧に戻る"),
                List.of(text("gui.back-lore", "&7表示条件を維持して一覧へ戻ります。"))));
        inventory.setItem(40, resultItem(player));
        inventory.setItem(42, item(Material.CLOCK, text("gui.refresh-organize", "&b一覧を整理・更新"),
                List.of(text("gui.refresh-lore", "&7HP・距離・状態を読み直します。"))));
        inventory.setItem(44, item(Material.BARRIER, text("gui.close", "&c閉じる"),
                List.of(text("gui.close-lore", "&7メニューを閉じます。"))));
    }

    private ItemStack actionItem(Player player, GuardData data, Mob mob, String permission,
                                 Material material, String key, String fallback, List<String> lore) {
        boolean allowed = hasPermission(player, permission);
        boolean enabled = data != null && mob != null && allowed;
        List<String> fullLore = new ArrayList<>(lore);
        if (!allowed) {
            fullLore.add(text("gui.permission-required", "&c権限がありません。"));
        } else if (data == null || mob == null) {
            fullLore.add(text("gui.unloaded-action",
                    "&e現在この護衛の状態を確認できないため操作できません。"));
            fullLore.add(text("gui.unavailable-next",
                    "&7状態を確認できる場所で手動更新してください。"));
        } else {
            fullLore.add(text("gui.click-to-use", "&bクリックして実行"));
        }
        String name = enabled ? text(key, fallback)
                : ChatColor.GRAY + ChatColor.stripColor(plugin.color(fallback)) + "（操作不可）";
        return item(enabled ? material : Material.GRAY_DYE, name, fullLore);
    }

    private ItemStack protectionItem(Player player, GuardData data, Mob mob) {
        if (data == null) {
            return item(Material.GRAY_DYE, text("gui.protection-disabled", "&7保護対象（操作不可）"),
                    List.of(text("gui.unavailable-next", "&7一覧へ戻って手動更新してください。")));
        }
        boolean allowed = hasPermission(player, "bodyguard.protect");
        String setting = data.isRoleProtection() ? data.getRoleId() : "owner（所有者）";
        List<String> lore = new ArrayList<>();
        lore.add(text("gui.protection-setting", "&7設定: &f{setting}",
                Map.of("setting", setting)));
        String targetName = "未選択";
        if (data.isRoleProtection() && data.getSelectedTargetUuid() != null) {
            Player target = Bukkit.getPlayer(data.getSelectedTargetUuid());
            if (manager.isSelectedProtectionTarget(data, target)) {
                targetName = target.getName() + "（" + target.getUniqueId() + "）";
            } else {
                targetName = "未選択（最終UUID: " + data.getSelectedTargetUuid() + "）";
            }
        } else if (!data.isRoleProtection()) {
            targetName = player.getName() + "（所有者）";
        }
        lore.add(text("gui.protection-target", "&7対象: &f{target}",
                Map.of("target", targetName)));
        lore.add(text("gui.protection-state", "&7状態: &f{state}",
                Map.of("state", data.getProtectionState().name())));
        if (data.getProtectionFailureReason() != null) {
            lore.add(text("gui.protection-reason", "&e保留理由: &f{reason}",
                    Map.of("reason", data.getProtectionFailureReason())));
        }
        if (data.isRoleProtection() && !plugin.shouldTeleportDifferentWorld()) {
            lore.add(text("gui.protection-world-disabled", "&e別ワールド移動は設定で無効です。"));
        }
        lore.add(" ");
        if (!allowed) {
            lore.add(text("gui.permission-required", "&c権限がありません。"));
        } else if (mob == null) {
            lore.add(text("gui.unloaded-action", "&e現在この護衛の状態を確認できないため操作できません。"));
        } else {
            lore.add(text("gui.protection-cycle", "&bクリックで次の保護対象へ切り替え"));
        }
        return item(allowed && mob != null ? Material.TOTEM_OF_UNDYING : Material.GRAY_DYE,
                text("gui.protection-title", "&d保護対象"), lore,
                data.isRoleProtection());
    }

    private ItemStack favoriteItem(GuardData data) {
        if (data == null) {
            return item(Material.GRAY_DYE, text("gui.favorite-disabled", "&7お気に入り（操作不可）"),
                    List.of(text("gui.unavailable-next", "&7一覧へ戻って手動更新してください。")));
        }
        boolean favorite = data.isFavorite();
        return item(favorite ? Material.GOLD_NUGGET : Material.IRON_NUGGET,
                favorite ? text("gui.favorite-remove", "&eお気に入りを解除")
                        : text("gui.favorite-add", "&eお気に入りに登録"),
                List.of(text("gui.favorite-lore", "&7標準一覧でお気に入りを先頭に表示します。"),
                        text("gui.click-to-use", "&bクリックして実行")), favorite);
    }

    private ItemStack companionItem(GuardData data) {
        if (data == null) {
            return item(Material.GRAY_DYE, text("gui.companion-disabled", "&7相棒（操作不可）"),
                    List.of(text("gui.unavailable-next", "&7一覧へ戻って手動更新してください。")));
        }
        boolean companion = manager.isCompanion(data);
        return item(companion ? Material.NETHER_STAR : Material.AMETHYST_SHARD,
                companion ? text("gui.companion-remove", "&6相棒を解除")
                        : text("gui.companion-set", "&d相棒に設定"),
                List.of(text("gui.companion-lore", "&7相棒は所有者ごとに1体だけ設定できます。"),
                        companion ? text("gui.companion-current", "&a現在の相棒です。")
                                : text("gui.click-to-use", "&bクリックして実行")), companion);
    }

    private ItemStack releaseActionItem(Player player, GuardData data, Mob mob) {
        boolean allowed = hasPermission(player, "bodyguard.release");
        boolean enabled = data != null && !data.isDeletionPending() && allowed;
        List<String> lore = new ArrayList<>();
        lore.add(text("gui.release-single-lore", "&7この護衛だけを確認画面へ進めます。"));
        if (!allowed) {
            lore.add(text("gui.permission-required", "&c権限がありません。"));
        } else if (data == null) {
            lore.add(text("gui.unavailable-next", "&7一覧へ戻って手動更新してください。"));
        } else if (data.isDeletionPending()) {
            lore.add(text("gui.delete-already-pending", "&eこの護衛はすでに削除予約中です。"));
        } else if (data.isReleasePending()) {
            lore.add(text("gui.release-already-pending", "&eこの護衛はすでに解除予約中です。"));
        } else if (mob == null) {
            lore.add(text("gui.release-will-queue", "&e未読み込みのため、確定すると解除予約になります。"));
        } else {
            lore.add(text("gui.click-to-use", "&bクリックして実行"));
        }
        String name = enabled ? text("gui.release-single", "&cこの護衛の契約を解除")
                : text("gui.release-single-disabled", "&7この護衛の契約を解除（操作不可）");
        return item(enabled ? Material.RED_DYE : Material.GRAY_DYE, name, lore);
    }

    private String statusLine(Player player, Mob mob) {
        if (mob == null) {
            return text("gui.guard-status-unknown", "&7状態: &e現在確認できません。");
        }
        Location playerLocation = player.getLocation();
        Location mobLocation = mob.getLocation();
        if (!LocationUtil.sameWorld(playerLocation, mobLocation)) {
            return text("gui.guard-status-world", "&7状態: &e別ワールドにいます: &f{world}",
                    Map.of("world", mobLocation.getWorld() == null ? "不明" : mobLocation.getWorld().getName()));
        }
        double distance = Math.sqrt(playerLocation.distanceSquared(mobLocation));
        return text("gui.guard-distance", "&7距離: &f{distance}m",
                Map.of("distance", String.format(Locale.ROOT, "%.1f", distance)));
    }

    private void addUnavailableLore(List<String> lore, GuardData data) {
        if (data == null) return;
        lore.add("§7状態: §e" + manager.status(data).label());
        plugin.test.com.bodyGuard.guard.SavedPosition last = data.getSavedLast();
        if (last != null) {
            lore.add("§7最終位置: §f" + last.worldName() + " " + (int) Math.floor(last.x())
                    + ", " + (int) Math.floor(last.y()) + ", " + (int) Math.floor(last.z()));
        }
        lore.add("§7最終確認: §f" + (data.getLastSeen() == 0 ? "記録なし"
                : java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")
                .withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.ofEpochMilli(data.getLastSeen()))));
        lore.add(switch (manager.status(data)) {
            case WORLD_UNAVAILABLE -> "§e管理者にワールドの読み込みを依頼してください。";
            case MISSING -> "§e最終位置で確認できません。近くを探し、不要なら契約解除してください。";
            case UNLOADED -> "§7最終位置に近づくと再確認できます。チャンク維持の上限・設定も確認してください。";
            default -> "§7少し待って「一覧を整理」を押してください。";
        });
    }
    private void addHealthBar(List<String> lore, Mob mob) {
        HealthInfo health = healthInfo(mob);
        if (health == null) {
            return;
        }
        int filled = (int) Math.ceil(health.ratio() * 10.0);
        String color = health.ratio() <= 0.25 ? "&c" : health.ratio() <= 0.5 ? "&e" : "&a";
        lore.add(text("gui.health-bar", "&7体力 &8[{bar}&8] &f{percent}%", Map.of(
                "bar", color + "|".repeat(filled) + "&8" + "|".repeat(10 - filled),
                "percent", String.valueOf(Math.round(health.ratio() * 100.0)))));
        if (health.ratio() <= 0.25) {
            lore.add(text("gui.health-low", "&c体力が少なくなっています。回復を検討してください。"));
        }
    }

    private HealthInfo healthInfo(Mob mob) {
        if (mob == null || !EntityUtil.isAlive(mob)) {
            return null;
        }
        AttributeInstance maximumAttribute = mob.getAttribute(Attribute.MAX_HEALTH);
        if (maximumAttribute == null) {
            return null;
        }
        double current = mob.getHealth();
        double maximum = maximumAttribute.getValue();
        if (!Double.isFinite(current) || !Double.isFinite(maximum) || maximum <= 0.0) {
            return null;
        }
        return new HealthInfo(current, maximum, Math.max(0.0, Math.min(1.0, current / maximum)));
    }

    private String currentGuardName(Player player, UUID guardId) {
        GuardData data = ownedGuard(player, guardId);
        return data == null ? "護衛" : plugin.color(data.getName());
    }

    private String mobName(EntityType type) {
        return plugin.getMobDisplayName(type);
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

    private List<String> mobFeatures(EntityType type) {
        String key = "mob-features." + (type == null ? "mob" : type.name().toLowerCase(Locale.ROOT));
        List<String> configured = messages.getList(key);
        if (configured == null || configured.isEmpty()) {
            return List.of(text("gui.mob-feature-generic-1", "&7攻撃タイプ: &f通常AIに従う"),
                    text("gui.mob-feature-generic-2", "&7特徴: &fこのMobの標準動作で攻撃します。"));
        }
        return configured.stream().map(messages::color).toList();
    }

    private ItemStack permissionItem(Player player, String permission, Material material,
                                     String key, String fallbackName, String lore) {
        boolean allowed = hasPermission(player, permission);
        return item(allowed ? material : Material.GRAY_DYE,
                allowed ? text(key, fallbackName)
                        : ChatColor.GRAY + ChatColor.stripColor(plugin.color(fallbackName)) + "（操作不可）",
                List.of(lore, allowed ? text("gui.click-to-use", "&bクリックして実行")
                        : text("gui.permission-required", "&c権限がありません。")));
    }

    private ItemStack navigationItem(Material material, String key, String fallbackName,
                                     boolean enabled, String lore) {
        return item(enabled ? material : Material.GRAY_STAINED_GLASS_PANE,
                enabled ? text(key, fallbackName)
                        : ChatColor.DARK_GRAY + ChatColor.stripColor(text(key, fallbackName)),
                List.of(enabled ? lore : text("gui.no-page", "&7この方向にページはありません。")));
    }

    private void fillHeader(Inventory inventory, Player player, boolean summon,
                            BodyGuardMenuHolder.GuardFilter filter,
                            BodyGuardMenuHolder.GuardSort sort, int filteredCount) {
        Material headerMaterial = summon
                ? Material.PURPLE_STAINED_GLASS_PANE : Material.BLUE_STAINED_GLASS_PANE;
        ItemStack filler = item(headerMaterial, " ", List.of());
        for (int slot = 0; slot < CONTENT_START; slot++) {
            inventory.setItem(slot, filler.clone());
        }
        List<GuardData> all = filter == BodyGuardMenuHolder.GuardFilter.HISTORY
                ? manager.getHistory(player.getUniqueId())
                : manager.getGuards(player.getUniqueId());
        GuardListQuery.Summary summary = listQuery.summary(all);
        GuardManager.DiagnosticSnapshot diagnostics = manager.diagnostics(player.getUniqueId());
        int limit = plugin.getMaxGuardsPerPlayer();
        boolean unlimited = player.isOp();
        List<String> overviewLore = new ArrayList<>();
        overviewLore.add(text("gui.overview-injured", "&7負傷中: &f{count}体",
                Map.of("count", String.valueOf(summary.injured()))));
        overviewLore.add(text("gui.overview-unknown", "&7状態を確認できない護衛: &f{count}体",
                Map.of("count", String.valueOf(summary.unknown()))));
        overviewLore.add("§7契約中: §f" + diagnostics.active()
                + " §7/ 利用可能: §a" + diagnostics.available()
                + " §7/ 未読み込み: §8" + diagnostics.unloaded()
                + " §7/ ワールド不在: §e" + diagnostics.worldUnavailable()
                + " §7/ 確認中: §e" + diagnostics.checking()
                + " §7/ 所在不明: §c" + diagnostics.missing());
        if (unlimited) {
            overviewLore.add(text("gui.remaining-unlimited", "&dOPのため無制限に召喚できます。"));
        } else {
            overviewLore.add(text("gui.remaining", "&7あと &f{remaining}体 &7召喚できます。",
                    Map.of("remaining", String.valueOf(Math.max(0, limit - diagnostics.active())))));
        }
        overviewLore.add(text("gui.overview-filter", "&7表示対象: &f{shown}体／全{total}体",
                Map.of("shown", String.valueOf(filteredCount), "total", String.valueOf(summary.total()))));
        overviewLore.add(snapshotNote());
        int displayedCount = filter == BodyGuardMenuHolder.GuardFilter.HISTORY
                ? all.size() : diagnostics.active();
        inventory.setItem(4, item(!unlimited && diagnostics.active() >= limit ? Material.ORANGE_DYE : Material.CHEST,
                text("gui.overview-title", "&b&lあなたの護衛 &f{count}/{limit}体",
                        Map.of("count", String.valueOf(displayedCount),
                                "limit", filter == BodyGuardMenuHolder.GuardFilter.HISTORY
                                        ? "履歴" : summonLimitLabel(player))),
                overviewLore));
        inventory.setItem(0, item(Material.BOOK, text("gui.guide-title", "&b&l操作ガイド"),
                List.of(summon ? text("gui.summon-guide-lore", "&7Mobをクリックすると召喚します。")
                                : text("gui.list-guide", "&7護衛をクリック → 詳細で行動を選択"),
                        text("gui.direct-detail-guide", "&7空手でしゃがみ＋右クリックでも詳細を開けます。"),
                        text("gui.toolbar-guide", "&7下段には移動・更新などの操作があります。"))));
        if (summon) {
            inventory.setItem(2, item(Material.BOOK, text("gui.summon-candidates", "&b召喚候補"),
                    List.of(text("gui.summon-candidates-lore", "&7特徴と召喚条件を確認できます。"))));
            inventory.setItem(6, item(Material.BOOK, text("gui.summon-choice", "&b選択のポイント"),
                    List.of(text("gui.summon-choice-lore", "&7Mob本来の通常AIと装備条件を表示します。"))));
        } else {
            inventory.setItem(2, item(Material.HOPPER, text("gui.filter-button", "&b絞り込み: &f{filter}",
                    Map.of("filter", filter == null ? "すべて" : filter.japaneseName())),
                    List.of(text("gui.filter-button-lore", "&7クリックで条件を切り替えます。"))));
            inventory.setItem(6, item(Material.HOPPER, text("gui.sort-button", "&b並べ替え: &f{sort}",
                    Map.of("sort", sort == null ? "標準" : sort.japaneseName())),
                    List.of(text("gui.sort-button-lore", "&7クリックで並び順を切り替えます。"))));
        }
        inventory.setItem(8, item(Material.COMPASS, text("gui.mode-guide-title", "&b行動モードの違い"),
                List.of(text("gui.mode-guide-follow", "&f追従 &7: あなたと一緒に移動"),
                        text("gui.mode-guide-stay", "&f待機 &7: その場で待ち、必要時に防衛"),
                        text("gui.mode-guide-guard", "&f警備 &7: 拠点の周囲を警戒"))));
    }

    private String snapshotNote() {
        int ticks = plugin.getGuiRefreshIntervalTicks();
        return ticks <= 0
                ? text("gui.snapshot-note-disabled", "&8自動更新は無効です。手動更新で最新にします。")
                : text("gui.snapshot-note-auto", "&8状態は約{seconds}秒ごとに自動更新。並び順は固定です。",
                Map.of("seconds", String.format(Locale.ROOT, "%.1f", ticks / 20.0)));
    }

    private void fillBottom(Inventory inventory, Material material) {
        ItemStack filler = item(material, " ", List.of());
        for (int slot = 45; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler.clone());
        }
    }

    private void fillContentArea(Inventory inventory, Material material) {
        ItemStack filler = item(material, " ", List.of());
        for (int slot = CONTENT_START; slot < PREVIOUS_SLOT; slot++) {
            inventory.setItem(slot, filler.clone());
        }
    }

    private void fillInventory(Inventory inventory, Material material) {
        ItemStack filler = item(material, " ", List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler.clone());
        }
    }

    private Material detailBackground(GuardData data) {
        if (data == null) {
            return Material.GRAY_STAINED_GLASS_PANE;
        }
        HealthInfo health = healthInfo(manager.getLoadedMob(data));
        if (health == null) {
            return Material.GRAY_STAINED_GLASS_PANE;
        }
        if (health.ratio() <= 0.25) {
            return Material.RED_STAINED_GLASS_PANE;
        }
        if (health.ratio() <= 0.5) {
            return Material.YELLOW_STAINED_GLASS_PANE;
        }
        return Material.GREEN_STAINED_GLASS_PANE;
    }

    private Inventory createInventory(BodyGuardMenuHolder holder, int size, String title) {
        Inventory inventory = Bukkit.createInventory(holder, size, messages.color(title));
        holder.setInventory(inventory);
        return inventory;
    }

    private ItemStack resultItem(Player player) {
        UiResult result = results.get(player.getUniqueId());
        if (result == null || result.expiresAtMillis() <= System.currentTimeMillis()) {
            return item(Material.GRAY_DYE, text("gui.result-title", "&7直前の操作"),
                    List.of(text("gui.result-default", "&7操作結果がここに表示されます。")));
        }
        Material material = result.tone() == ResultTone.SUCCESS ? Material.LIME_DYE
                : result.tone() == ResultTone.WARNING ? Material.YELLOW_DYE : Material.RED_DYE;
        String titleKey = result.tone() == ResultTone.SUCCESS ? "gui.result-success-title"
                : result.tone() == ResultTone.WARNING ? "gui.result-warning-title" : "gui.result-failure-title";
        String fallback = result.tone() == ResultTone.SUCCESS ? "&a成功"
                : result.tone() == ResultTone.WARNING ? "&e注意" : "&c失敗";
        return item(material, text(titleKey, fallback),
                List.of(result.message(), text("gui.result-short-lived", "&8数秒後に通常の案内へ戻ります。")));
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        return item(material, name, lore, false);
    }

    private ItemStack item(Material material, String name, List<String> lore, boolean glint) {
        ItemStack stack = new ItemStack(material == null ? Material.EGG : material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name == null ? "" : name);
            meta.setLore(lore == null ? List.of() : lore);
            if (glint) {
                meta.setEnchantmentGlintOverride(true);
            }
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

    private void showResult(Player player, String key, String fallback,
                            Map<String, String> placeholders, boolean success) {
        showResult(player, key, fallback, placeholders, success, true);
    }

    private void showResult(Player player, String key, String fallback,
                            Map<String, String> placeholders, boolean success,
                            boolean playSound) {
        if (player == null || !player.isOnline()) {
            return;
        }
        UUID id = player.getUniqueId();
        long token = ++resultSequence;
        resultTokens.put(id, token);
        ResultTone tone = success ? ResultTone.SUCCESS
                : isWarningResult(key) ? ResultTone.WARNING : ResultTone.FAILURE;
        UiResult result = new UiResult(messages.format(messages.get(key, fallback), placeholders),
                tone, System.currentTimeMillis() + plugin.getGuiResultDurationTicks() * 50L);
        results.put(id, result);
        messages.showOperationNotice(player, result.message(), switch (tone) {
            case SUCCESS -> NoticeTone.SUCCESS;
            case WARNING -> NoticeTone.WARNING;
            case FAILURE -> NoticeTone.FAILURE;
        });
        if (playSound) {
            plugin.playGuiSound(player, success);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (resultTokens.getOrDefault(id, 0L) == token) {
                results.remove(id);
                refreshVisibleMenu(player);
            }
        }, plugin.getGuiResultDurationTicks());
        refreshVisibleMenu(player);
    }

    private boolean isWarningResult(String key) {
        return key != null && (key.endsWith("-none")
                || key.endsWith("-full")
                || key.contains("unavailable")
                || key.contains("unloaded")
                || key.startsWith("gui-command-")
                || key.equals("gui-release-none"));
    }

    private void clearResult(Player player) {
        if (player == null) {
            return;
        }
        UUID id = player.getUniqueId();
        resultTokens.put(id, ++resultSequence);
        results.remove(id);
    }

    private void refreshVisibleMenu(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        Inventory top = player.getOpenInventory().getTopInventory();
        if (!(top.getHolder() instanceof BodyGuardMenuHolder holder)) {
            return;
        }
        switch (holder.getType()) {
            case COMMAND -> top.setItem(25, resultItem(player));
            case LIST, SUMMON -> top.setItem(49, resultItem(player));
            case DETAIL -> top.setItem(40, resultItem(player));
            case MANAGEMENT -> top.setItem(22, resultItem(player));
            case RELEASE_CONFIRM, NAME_INPUT, TUTORIAL -> {
                // Confirmation and input screens are unaffected by transient results.
            }
        }
    }

    private String summonFailureKey(Player player, EntityType type) {
        if (!hasPermission(player, "bodyguard.summon")) {
            return "gui-no-permission";
        }
        if (!plugin.isAllowedMobType(type) || !plugin.isSupportedMobType(type)) {
            return "gui-summon-not-allowed-now";
        }
        return !player.isOp() && manager.countGuards(player.getUniqueId()) >= plugin.getMaxGuardsPerPlayer()
                ? "gui-summon-full" : "gui-summon-failed";
    }

    private String summonFailureFallback(Player player, EntityType type) {
        if (!hasPermission(player, "bodyguard.summon")) {
            return "&cこの操作を使う権限がありません。";
        }
        if (!plugin.isAllowedMobType(type) || !plugin.isSupportedMobType(type)) {
            return "&c現在の設定ではこのMobを召喚できません。";
        }
        return !player.isOp() && manager.countGuards(player.getUniqueId()) >= plugin.getMaxGuardsPerPlayer()
                ? "&c護衛数の上限（{limit}体）に達しています。"
                : "&c召喚に失敗しました。状態を確認して再試行してください。";
    }

    private String summonLimitLabel(Player player) {
        return player != null && player.isOp()
                ? text("gui.unlimited", "無制限")
                : String.valueOf(plugin.getMaxGuardsPerPlayer());
    }

    private String summonLimitLore(Player player) {
        return player != null && player.isOp()
                ? text("gui.count-lore-unlimited", "&dOPは護衛数の上限なく召喚できます。")
                : text("gui.count-lore", "&7上限に達すると召喚できません。");
    }

    private void refreshActionBars() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof BodyGuardMenuHolder) {
                clearActionBar(player);
                continue;
            }
            LivingEntity lookedAt = EntityUtil.findLookedAtLivingEntity(player, plugin.getActionBarRange());
            GuardData data = lookedAt == null ? null : manager.getGuardData(lookedAt);
            if (!(lookedAt instanceof Mob mob) || data == null
                    || !player.getUniqueId().equals(data.getOwnerId())) {
                clearActionBar(player);
                continue;
            }
            String actionBar = actionBarText(data, mob);
            if (!actionBar.equals(actionBarTexts.get(player.getUniqueId()))) {
                sendActionBar(player, actionBar);
                actionBarTexts.put(player.getUniqueId(), actionBar);
            }
        }
    }

    private String actionBarText(GuardData data, Mob mob) {
        HealthInfo health = healthInfo(mob);
        String healthText = health == null ? text("gui.actionbar-health-unknown", "不明")
                : EntityUtil.healthText(mob);
        String color = health != null && health.ratio() <= 0.25 ? "&c"
                : health != null && health.ratio() <= 0.5 ? "&e" : "&f";
        return messages.format(messages.get("gui.actionbar",
                "&b{name} &8｜ &f{mode} &8｜ {healthColor}HP {health}"),
                Map.of("name", truncateLegacy(plugin.color(data.getName()), 18),
                        "mode", data.getMode().japaneseName(), "healthColor", color,
                        "health", healthText == null ? "不明" : healthText));
    }

    private void clearActionBar(Player player) {
        UUID id = player.getUniqueId();
        if (actionBarTexts.remove(id) != null) {
            sendActionBar(player, "");
        }
    }

    private void clearActionBars() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            clearActionBar(player);
        }
    }

    private void sendActionBar(Player player, String text) {
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(text));
    }

    private String detailTitle(GuardData data) {
        return truncateLegacy(text("gui.detail-title", "&9護衛詳細") + " &8- "
                + plugin.color(data.getName()), 32);
    }

    private String truncateLegacy(String value, int maxVisibleCharacters) {
        if (value == null || maxVisibleCharacters <= 0) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        int visible = 0;
        for (int index = 0; index < value.length() && visible < maxVisibleCharacters;) {
            char character = value.charAt(index);
            if (character == ChatColor.COLOR_CHAR && index + 1 < value.length()) {
                result.append(character).append(value.charAt(index + 1));
                index += 2;
                continue;
            }
            int codePoint = value.codePointAt(index);
            result.appendCodePoint(codePoint);
            index += Character.charCount(codePoint);
            visible++;
        }
        if (visible < value.codePointCount(0, value.length())) {
            result.append(ChatColor.COLOR_CHAR).append(ChatColor.GRAY).append("...");
        }
        return result.toString();
    }

    private record HealthInfo(double current, double maximum, double ratio) {
    }

    private record CommandCounts(int total, int available, int unavailable) {
    }

    private enum ResultTone {
        SUCCESS,
        WARNING,
        FAILURE
    }

    private record UiResult(String message, ResultTone tone, long expiresAtMillis) {
    }
}
