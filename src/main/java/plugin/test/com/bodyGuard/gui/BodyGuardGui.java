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
import plugin.test.com.bodyGuard.util.EntityUtil;
import plugin.test.com.bodyGuard.util.LocationUtil;
import plugin.test.com.bodyGuard.util.MessageUtil;

/** Standard Bukkit inventory menus for browsing and operating owned guards. */
public final class BodyGuardGui implements Listener {

    private static final int CONTENT_START = 9;
    private static final int CONTENT_SLOTS = 36;
    private static final int MAIN_SIZE = 54;
    private static final int DETAIL_SIZE = 45;
    private static final int MANAGEMENT_SIZE = 27;
    private static final int CONFIRM_SIZE = 27;
    private static final int PREVIOUS_SLOT = 45;
    private static final int NEXT_SLOT = 52;
    private static final int CLOSE_SLOT = 53;

    private final BodyGuard plugin;
    private final GuardManager manager;
    private final MessageUtil messages;
    private final BodyGuardCommand command;
    private final Map<UUID, UiResult> results = new HashMap<>();
    private final Map<UUID, Long> resultTokens = new HashMap<>();
    private final Map<UUID, String> actionBarTexts = new HashMap<>();
    private BukkitTask refreshTask;
    private BukkitTask actionBarTask;

    public BodyGuardGui(BodyGuard plugin, GuardManager manager, MessageUtil messages,
                        BodyGuardCommand command) {
        this.plugin = plugin;
        this.manager = manager;
        this.messages = messages;
        this.command = command;
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
        openList(player, 0, BodyGuardMenuHolder.GuardFilter.ALL,
                BodyGuardMenuHolder.GuardSort.STANDARD);
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
        List<GuardData> filtered = filterAndSort(player, all, safeFilter, safeSort);
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
        fillHeader(inventory, player, false, safeFilter, safeSort, filtered.size());

        for (int index = start; index < end; index++) {
            inventory.setItem(CONTENT_START + index - start, guardIcon(player, filtered.get(index)));
        }
        if (all.isEmpty()) {
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
                    text("gui.filter-empty-title", "&e条件に合う護衛がいません"),
                    List.of(text("gui.filter-empty-lore", "&7「条件を解除」を押すと全員を表示します。"))));
            inventory.setItem(24, item(Material.ARROW,
                    text("gui.filter-clear", "&b条件を解除"),
                    List.of(text("gui.filter-clear-lore", "&7絞り込みを「すべて」に戻します。"))));
        }

        fillBottom(inventory);
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
        inventory.setItem(51, item(Material.CLOCK, text("gui.refresh", "&b手動更新"),
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
                text("gui.summon-title", "&9護衛を召喚") + " &8(" + (page + 1) + "/" + pages + ")");
        fillHeader(inventory, player, true, filter, sort, manager.countGuards(player.getUniqueId()));
        for (int index = 0; index < pageTypes.size(); index++) {
            inventory.setItem(CONTENT_START + index, summonIcon(pageTypes.get(index), player));
        }
        if (types.isEmpty()) {
            inventory.setItem(22, item(Material.BARRIER, text("gui.no-summonable-mobs",
                    "&c召喚できるMobがありません"),
                    List.of(text("gui.no-summonable-mobs-lore", "&7設定で許可されている対応Mobがありません。"))));
        }

        fillBottom(inventory);
        inventory.setItem(PREVIOUS_SLOT, navigationItem(Material.ARROW, "gui.previous",
                "&b前のページ", page > 0, text("gui.previous-lore", "&7前のページを表示します。")));
        inventory.setItem(46, item(Material.ARROW, text("gui.back", "&b一覧に戻る"),
                List.of(text("gui.back-lore", "&7護衛一覧に戻ります。"))));
        inventory.setItem(47, item(Material.BOOK, text("gui.page", "&fページ {page}/{pages}",
                Map.of("page", String.valueOf(page + 1), "pages", String.valueOf(pages))),
                List.of(text("gui.summon-page-lore", "&7候補の特徴を確認して選択してください。"))));
        inventory.setItem(48, item(Material.CHEST, text("gui.count", "&7護衛数: &f{count}/{limit}",
                Map.of("count", String.valueOf(manager.countGuards(player.getUniqueId())),
                        "limit", String.valueOf(plugin.getMaxGuardsPerPlayer()))),
                List.of(text("gui.count-lore", "&7上限に達すると召喚できません。"))));
        inventory.setItem(49, resultItem(player));
        inventory.setItem(50, item(Material.CLOCK, text("gui.refresh", "&b手動更新"),
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
        fillInventory(inventory);
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
        Inventory inventory = createInventory(holder, MANAGEMENT_SIZE, text("gui.management-title", "&9護衛管理"));
        fillInventory(inventory);
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
        inventory.setItem(18, item(Material.ARROW, text("gui.back", "&b一覧に戻る"),
                List.of(text("gui.back-lore", "&7表示条件を維持して一覧へ戻ります。"))));
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

    private void openReleaseConfirmation(Player player, Collection<UUID> guardIds,
                                          boolean releaseAll, int returnPage,
                                          String targetName, int targetCount,
                                          BodyGuardMenuHolder.GuardFilter filter,
                                          BodyGuardMenuHolder.GuardSort sort) {
        BodyGuardMenuHolder holder = new BodyGuardMenuHolder(
                BodyGuardMenuHolder.MenuType.RELEASE_CONFIRM, player.getUniqueId(), returnPage,
                releaseAll ? null : guardIds.iterator().next(), guardIds, null, releaseAll,
                filter, sort, -1, -1);
        Inventory inventory = createInventory(holder, CONFIRM_SIZE,
                text(releaseAll ? "gui.confirm-all-title" : "gui.confirm-single-title",
                        releaseAll ? "&c全解除の確認" : "&c解除の確認"));
        fillInventory(inventory);
        String target = releaseAll
                ? text("gui.confirm-target-count", "&f対象: &e{count}体",
                Map.of("count", String.valueOf(targetCount)))
                : text("gui.confirm-target-name", "&f対象: &e{name}",
                Map.of("name", plugin.color(targetName)));
        inventory.setItem(4, item(Material.BOOK, text("gui.confirm-target", "&e解除対象"),
                List.of(target, text("gui.confirm-warning", "&c通常のMobに戻り、敵対する可能性があります。"))));
        inventory.setItem(13, item(Material.REDSTONE, text("gui.confirm-warning-title", "&e注意"),
                List.of(text("gui.confirm-warning", "&c通常のMobに戻り、敵対する可能性があります。"),
                        text("gui.confirm-snapshot", "&7この画面を開いた時点のUUIDだけを対象にします。"),
                        text("gui.confirm-loaded-note", "&7現在読み込まれていない護衛は解除されません。"))));
        inventory.setItem(11, item(Material.RED_CONCRETE, text("gui.confirm-release", "&c解除する"),
                List.of(text("gui.confirm-release-lore", "&7解除を実行します。"))));
        inventory.setItem(15, item(Material.BLUE_CONCRETE, text("gui.confirm-cancel", "&bキャンセル"),
                List.of(text("gui.confirm-cancel-lore", "&7解除せずに戻ります。"))));
        inventory.setItem(22, item(Material.ARROW,
                text(releaseAll ? "gui.back-management" : "gui.back-detail",
                        releaseAll ? "&b管理に戻る" : "&b護衛詳細に戻る"),
                List.of(text("gui.confirm-cancel-lore", "&7解除せずに戻ります。"))));
        inventory.setItem(26, item(Material.BARRIER, text("gui.close", "&c閉じる"),
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
            case MANAGEMENT -> handleManagementClick(player, holder, slot);
            case RELEASE_CONFIRM -> handleConfirmationClick(player, holder, slot);
            case NAME_INPUT -> handleNameInputClick(player, holder, top, slot);
        }
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
                case LIST -> refreshList(player, holder, inventory);
                case SUMMON -> refreshSummon(player, holder, inventory);
                case DETAIL -> refreshDetail(player, holder, inventory);
                case MANAGEMENT -> refreshManagement(player, inventory);
                case RELEASE_CONFIRM, NAME_INPUT -> {
                    // These screens are deliberately not auto-refreshed.
                }
            }
        }
    }

    private void refreshList(Player player, BodyGuardMenuHolder holder, Inventory inventory) {
        List<GuardData> all = manager.getGuards(player.getUniqueId());
        int filteredCount = countMatching(player, all, holder.getFilter());
        fillHeader(inventory, player, false, holder.getFilter(), holder.getSort(), filteredCount);
        for (int index = 0; index < holder.getGuardIds().size(); index++) {
            GuardData data = ownedGuard(player, holder.getGuardIds().get(index));
            inventory.setItem(CONTENT_START + index, data == null ? invalidGuardIcon() : guardIcon(player, data));
        }
        inventory.setItem(49, resultItem(player));
    }

    private void refreshSummon(Player player, BodyGuardMenuHolder holder, Inventory inventory) {
        fillHeader(inventory, player, true, holder.getFilter(), holder.getSort(),
                manager.countGuards(player.getUniqueId()));
        for (int index = 0; index < holder.getMobTypes().size(); index++) {
            inventory.setItem(CONTENT_START + index, summonIcon(holder.getMobTypes().get(index), player));
        }
        inventory.setItem(48, item(Material.CHEST, text("gui.count", "&7護衛数: &f{count}/{limit}",
                Map.of("count", String.valueOf(manager.countGuards(player.getUniqueId())),
                        "limit", String.valueOf(plugin.getMaxGuardsPerPlayer()))),
                List.of(text("gui.count-lore", "&7上限に達すると召喚できません。"))));
        inventory.setItem(49, resultItem(player));
    }

    private void refreshDetail(Player player, BodyGuardMenuHolder holder, Inventory inventory) {
        renderDetail(inventory, player, ownedGuard(player, holder.getGuardId()));
    }

    private void refreshManagement(Player player, Inventory inventory) {
        int count = manager.countGuards(player.getUniqueId());
        inventory.setItem(4, item(Material.CHEST, text("gui.management-summary", "&e現在の護衛: &f{count}体",
                Map.of("count", String.valueOf(count))),
                List.of(text("gui.management-summary-lore", "&7解除対象は確認画面を開いた時点で固定されます。"))));
        inventory.setItem(22, resultItem(player));
    }

    private void handleListClick(Player player, BodyGuardMenuHolder holder, int slot) {
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
                        Map.of(), true);
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
                transition(player, () -> openDetails(player, holder.getGuardIds().get(index),
                        holder.getPage(), holder.getFilter(), holder.getSort()));
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

    private void handleSummonClick(Player player, BodyGuardMenuHolder holder, int slot) {
        if (slot < PREVIOUS_SLOT) {
            int index = slot - CONTENT_START;
            if (index >= 0 && index < holder.getMobTypes().size()) {
                EntityType type = holder.getMobTypes().get(index);
                boolean summoned = command.summonFromMenu(player, type);
                if (summoned) {
                    showResult(player, "gui-summon-result", "&a{mob}を召喚しました。",
                            Map.of("mob", mobName(type)), true);
                    transition(player, () -> openList(player, 0, holder.getFilter(), holder.getSort()));
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
        if (slot == 10 || slot == 13 || slot == 16) {
            GuardMode mode = slot == 10 ? GuardMode.FOLLOW : slot == 13 ? GuardMode.STAY : GuardMode.GUARD;
            GuardData data = ownedGuard(player, holder.getGuardId());
            if (data == null) {
                showUnavailableAndReturn(player, holder);
                return;
            }
            if (data.getMode() == mode) {
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
            if (!manager.setMode(player.getUniqueId(), holder.getGuardId(), mode)) {
                showUnavailableAndReturn(player, holder);
                return;
            }
            showResult(player, "gui-mode-changed", "&a{mode}に変更しました。",
                    Map.of("name", plugin.color(data.getName()), "mode", mode.japaneseName()), true);
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
            case 31 -> {
                if (hasPermission(player, "bodyguard.release")) {
                    transition(player, () -> openSingleReleaseConfirmation(player, holder));
                } else {
                    showResult(player, "gui-no-permission", "&cこの操作を使う権限がありません。", Map.of(), false);
                }
            }
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
            case 13 -> {
                if (hasPermission(player, "bodyguard.releaseall")) {
                    transition(player, () -> openAllReleaseConfirmation(player, holder.getPage(),
                            holder.getFilter(), holder.getSort()));
                } else {
                    showResult(player, "gui-no-permission", "&cこの操作を使う権限がありません。", Map.of(), false);
                }
            }
            case 18 -> transition(player, () -> openList(player, holder.getPage(),
                    holder.getFilter(), holder.getSort()));
            case 26 -> player.closeInventory();
            default -> {
                // Summary, result, and filler slots intentionally do nothing.
            }
        }
    }

    private void openSingleReleaseConfirmation(Player player, BodyGuardMenuHolder detailHolder) {
        GuardData data = ownedGuard(player, detailHolder.getGuardId());
        if (data == null || manager.getLoadedMob(data) == null) {
            showResult(player, "gui-unavailable-reason",
                    "&e現在この護衛の状態を確認できないため、解除できません。", Map.of(), false);
            return;
        }
        openReleaseConfirmation(player, List.of(detailHolder.getGuardId()), false,
                detailHolder.getPage(), data.getName(), 1,
                detailHolder.getFilter(), detailHolder.getSort());
    }

    private void handleConfirmationClick(Player player, BodyGuardMenuHolder holder, int slot) {
        if (slot == 11) {
            String permission = holder.isReleaseAll() ? "bodyguard.releaseall" : "bodyguard.release";
            if (!hasPermission(player, permission)) {
                showResult(player, "gui-no-permission", "&cこの操作を使う権限がありません。", Map.of(), false);
                return;
            }
            int released = manager.releaseGuards(player.getUniqueId(), holder.getGuardIds());
            if (released == 0) {
                showResult(player, "gui-release-none",
                        "&e解除できる護衛がありません。対象が未読み込み・死亡・解除済みの可能性があります。",
                        Map.of(), false);
            } else {
                showResult(player, "gui-release-result", "&a実際に解除できた護衛: &f{count}体。",
                        Map.of("count", String.valueOf(released)), true);
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
        return data != null && player != null && player.getUniqueId().equals(data.getOwnerId())
                ? data : null;
    }

    private List<EntityType> summonableTypes() {
        return plugin.getAllowedMobTypes().stream()
                .filter(plugin::isSupportedMobType)
                .sorted(Comparator.comparing((EntityType type) -> mobName(type))
                        .thenComparing(EntityType::name))
                .toList();
    }

    private List<GuardData> filterAndSort(Player player, List<GuardData> all,
                                          BodyGuardMenuHolder.GuardFilter filter,
                                          BodyGuardMenuHolder.GuardSort sort) {
        List<GuardData> filtered = new ArrayList<>();
        for (GuardData data : all) {
            if (matchesFilter(player, data, filter)) {
                filtered.add(data);
            }
        }
        if (sort == BodyGuardMenuHolder.GuardSort.STANDARD) {
            return filtered;
        }
        Map<UUID, Integer> originalOrder = new HashMap<>();
        for (int index = 0; index < all.size(); index++) {
            originalOrder.put(all.get(index).getGuardId(), index);
        }
        Comparator<GuardData> comparator = Comparator
                .comparingDouble((GuardData data) -> sortValue(player, data, sort))
                .thenComparingInt(data -> originalOrder.getOrDefault(data.getGuardId(), Integer.MAX_VALUE));
        filtered.sort(comparator);
        return filtered;
    }

    private int countMatching(Player player, List<GuardData> all,
                              BodyGuardMenuHolder.GuardFilter filter) {
        int count = 0;
        for (GuardData data : all) {
            if (matchesFilter(player, data, filter)) {
                count++;
            }
        }
        return count;
    }

    private boolean matchesFilter(Player player, GuardData data,
                                  BodyGuardMenuHolder.GuardFilter filter) {
        if (filter == null || filter == BodyGuardMenuHolder.GuardFilter.ALL) {
            return true;
        }
        if (filter == BodyGuardMenuHolder.GuardFilter.FOLLOW) {
            return data.getMode() == GuardMode.FOLLOW;
        }
        if (filter == BodyGuardMenuHolder.GuardFilter.STAY) {
            return data.getMode() == GuardMode.STAY;
        }
        if (filter == BodyGuardMenuHolder.GuardFilter.GUARD) {
            return data.getMode() == GuardMode.GUARD;
        }
        Mob mob = manager.getLoadedMob(data);
        if (filter == BodyGuardMenuHolder.GuardFilter.UNKNOWN) {
            return mob == null;
        }
        HealthInfo health = healthInfo(mob);
        return health != null && health.current() < health.maximum();
    }

    private double sortValue(Player player, GuardData data, BodyGuardMenuHolder.GuardSort sort) {
        Mob mob = manager.getLoadedMob(data);
        if (sort == BodyGuardMenuHolder.GuardSort.DISTANCE) {
            if (mob == null || player == null
                    || !LocationUtil.sameWorld(player.getLocation(), mob.getLocation())) {
                return Double.POSITIVE_INFINITY;
            }
            return player.getLocation().distanceSquared(mob.getLocation());
        }
        HealthInfo health = healthInfo(mob);
        return health == null ? Double.POSITIVE_INFINITY : health.ratio();
    }

    private GuardSummary summary(List<GuardData> all) {
        int injured = 0;
        int unknown = 0;
        for (GuardData data : all) {
            Mob mob = manager.getLoadedMob(data);
            if (mob == null) {
                unknown++;
                continue;
            }
            HealthInfo health = healthInfo(mob);
            if (health != null && health.current() < health.maximum()) {
                injured++;
            }
        }
        return new GuardSummary(all.size(), injured, unknown);
    }

    private ItemStack guardIcon(Player player, GuardData data) {
        Mob mob = manager.getLoadedMob(data);
        List<String> lore = new ArrayList<>();
        lore.add(text("gui.guard-name", "&7名前: &f{name}",
                Map.of("name", plugin.color(data.getName()))));
        lore.add(text("gui.guard-mob", "&7種類: &f{mob}",
                Map.of("mob", mobName(data.getMobType()))));
        lore.add(text("gui.guard-mode", "&7モード: &f{mode}",
                Map.of("mode", data.getMode().japaneseName())));
        if (mob == null) {
            addUnavailableLore(lore, data);
        } else {
            String health = EntityUtil.healthText(mob);
            lore.add(health == null
                    ? text("gui.guard-health-unknown", "&7HP: &f不明（推測しません）")
                    : text("gui.guard-health", "&7HP: &f{health}", Map.of("health", health)));
            lore.add(statusLine(player, mob));
            addHealthBar(lore, mob);
        }
        lore.add(" ");
        lore.add(text("gui.guard-click", "&bクリック: 詳細を開く"));
        return item(spawnEgg(data.getMobType()), plugin.color(data.getName()), lore);
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
        boolean full = count >= plugin.getMaxGuardsPerPlayer();
        boolean enabled = permission && allowed && !full;
        List<String> lore = new ArrayList<>();
        lore.add(text("gui.summon-mob", "&7Mob: &f{mob}", Map.of("mob", mobName(type))));
        lore.add(text("gui.summon-count", "&7現在: &f{count}/{limit}", Map.of(
                "count", String.valueOf(count), "limit", String.valueOf(plugin.getMaxGuardsPerPlayer()))));
        lore.addAll(mobFeatures(type));
        lore.add(" ");
        if (!permission) {
            lore.add(text("gui.permission-required", "&cこの操作を使う権限がありません。"));
        } else if (!allowed) {
            lore.add(text("gui.summon-not-allowed-now", "&c現在の設定では召喚できません。"));
        } else if (full) {
            lore.add(text("gui.summon-full", "&c上限に達しているため召喚できません。"));
        } else {
            lore.add(text("gui.summon-click", "&aクリック: このMobを召喚"));
        }
        return item(enabled ? spawnEgg(type) : Material.GRAY_DYE,
                (enabled ? ChatColor.AQUA : ChatColor.GRAY) + mobName(type), lore);
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
        lore.add(modeDescription(mode));
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
        } else if (!current) {
            lore.add(text("gui.click-mode", "&7クリックで変更"));
        }
        String name = current
                ? text("gui.mode-selected", "&a{mode}（選択中）", Map.of("mode", mode.japaneseName()))
                : !enabled
                ? text("gui.mode-disabled", "&7{mode}（操作不可）", Map.of("mode", mode.japaneseName()))
                : text("gui.mode-name", "&b{mode}", Map.of("mode", mode.japaneseName()));
        return item(icon, name, lore, current);
    }

    private String modeDescription(GuardMode mode) {
        return switch (mode) {
            case FOLLOW -> text("gui.mode-follow", "&7所有者の近くへ付いてきます。");
            case STAY -> text("gui.mode-stay", "&7その場で待機し、必要時に守ります。");
            case GUARD -> text("gui.mode-guard", "&7指定地点の周囲を警備します。");
        };
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
            inventory.setItem(4, item(spawnEgg(data.getMobType()), plugin.color(data.getName()), lore));
        }

        inventory.setItem(10, modeItem(player, data, mob, GuardMode.FOLLOW));
        inventory.setItem(13, modeItem(player, data, mob, GuardMode.STAY));
        inventory.setItem(16, modeItem(player, data, mob, GuardMode.GUARD));
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
        inventory.setItem(31, actionItem(player, data, mob, "bodyguard.release", Material.RED_DYE,
                "gui.release-single", "&cこの護衛の契約を解除",
                List.of(text("gui.release-single-lore", "&7この護衛だけを確認画面へ進めます。"))));
        inventory.setItem(36, item(Material.ARROW, text("gui.back", "&b一覧に戻る"),
                List.of(text("gui.back-lore", "&7表示条件を維持して一覧へ戻ります。"))));
        inventory.setItem(40, resultItem(player));
        inventory.setItem(42, item(Material.CLOCK, text("gui.refresh", "&b手動更新"),
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
        } else {
            fullLore.add(text("gui.click-to-use", "&bクリックして実行"));
        }
        String name = enabled ? text(key, fallback)
                : ChatColor.GRAY + ChatColor.stripColor(plugin.color(fallback)) + "（操作不可）";
        return item(enabled ? material : Material.GRAY_DYE, name, fullLore);
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
        lore.add(text("gui.guard-status-unknown", "&7状態: &e現在確認できません。"));
        lore.add(text("gui.unavailable-next", "&7状態を確認できる場所で手動更新してください。"));
        Location last = data == null ? null : data.getLastLocation();
        if (last != null && last.getWorld() != null) {
            lore.add(text("gui.last-confirmed-world", "&7最後に確認したワールド: &f{world}",
                    Map.of("world", last.getWorld().getName())));
        }
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
        ItemStack filler = item(Material.BLUE_STAINED_GLASS_PANE, " ", List.of());
        for (int slot = 0; slot < CONTENT_START; slot++) {
            inventory.setItem(slot, filler.clone());
        }
        List<GuardData> all = manager.getGuards(player.getUniqueId());
        GuardSummary summary = summary(all);
        int limit = plugin.getMaxGuardsPerPlayer();
        List<String> overviewLore = new ArrayList<>();
        overviewLore.add(text("gui.overview-injured", "&7負傷中: &f{count}体",
                Map.of("count", String.valueOf(summary.injured()))));
        overviewLore.add(text("gui.overview-unknown", "&7状態を確認できない護衛: &f{count}体",
                Map.of("count", String.valueOf(summary.unknown()))));
        overviewLore.add(text("gui.remaining", "&7あと &f{remaining}体 &7召喚できます。",
                Map.of("remaining", String.valueOf(Math.max(0, limit - summary.total()))));
        overviewLore.add(text("gui.overview-filter", "&7表示対象: &f{shown}体／全{total}体",
                Map.of("shown", String.valueOf(filteredCount), "total", String.valueOf(summary.total()))));
        overviewLore.add(snapshotNote());
        inventory.setItem(4, item(summary.total() >= limit ? Material.ORANGE_DYE : Material.CHEST,
                text("gui.overview-title", "&b&lあなたの護衛 &f{count}/{limit}体",
                        Map.of("count", String.valueOf(summary.total()), "limit", String.valueOf(limit))),
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

    private ItemStack resultItem(Player player) {
        UiResult result = results.get(player.getUniqueId());
        if (result == null || result.expiresAtMillis() <= System.currentTimeMillis()) {
            return item(Material.BOOK, text("gui.result-title", "&e直前の操作"),
                    List.of(text("gui.result-default", "&7操作結果がここに表示されます。")));
        }
        return item(result.success() ? Material.LIME_DYE : Material.RED_DYE,
                text(result.success() ? "gui.result-success-title" : "gui.result-failure-title",
                        result.success() ? "&a操作結果" : "&c操作結果"),
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
        if (player == null || !player.isOnline()) {
            return;
        }
        UUID id = player.getUniqueId();
        long token = resultTokens.getOrDefault(id, 0L) + 1L;
        resultTokens.put(id, token);
        UiResult result = new UiResult(messages.format(messages.get(key, fallback), placeholders),
                success, System.currentTimeMillis() + plugin.getGuiResultDurationTicks() * 50L);
        results.put(id, result);
        plugin.playGuiSound(player, success);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (resultTokens.getOrDefault(id, 0L) == token) {
                results.remove(id);
                refreshVisibleMenu(player);
            }
        }, plugin.getGuiResultDurationTicks());
        refreshVisibleMenu(player);
    }

    private void clearResult(Player player) {
        if (player == null) {
            return;
        }
        UUID id = player.getUniqueId();
        resultTokens.put(id, resultTokens.getOrDefault(id, 0L) + 1L);
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
            case LIST, SUMMON -> top.setItem(49, resultItem(player));
            case DETAIL -> top.setItem(40, resultItem(player));
            case MANAGEMENT -> top.setItem(22, resultItem(player));
            case RELEASE_CONFIRM, NAME_INPUT -> {
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
        return manager.countGuards(player.getUniqueId()) >= plugin.getMaxGuardsPerPlayer()
                ? "gui-summon-full" : "gui-summon-failed";
    }

    private String summonFailureFallback(Player player, EntityType type) {
        if (!hasPermission(player, "bodyguard.summon")) {
            return "&cこの操作を使う権限がありません。";
        }
        if (!plugin.isAllowedMobType(type) || !plugin.isSupportedMobType(type)) {
            return "&c現在の設定ではこのMobを召喚できません。";
        }
        return manager.countGuards(player.getUniqueId()) >= plugin.getMaxGuardsPerPlayer()
                ? "&c護衛数の上限（{limit}体）に達しています。"
                : "&c召喚に失敗しました。状態を確認して再試行してください。";
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

    private record GuardSummary(int total, int injured, int unknown) {
    }

    private record UiResult(String message, boolean success, long expiresAtMillis) {
    }
}
