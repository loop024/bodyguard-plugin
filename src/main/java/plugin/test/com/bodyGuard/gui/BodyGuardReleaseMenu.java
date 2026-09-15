package plugin.test.com.bodyGuard.gui;

import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import plugin.test.com.bodyGuard.util.MessageUtil;

/** Builds the release confirmation screen without owning navigation or click handling. */
public final class BodyGuardReleaseMenu {

    private static final int SIZE = 27;

    private final MessageUtil messages;

    public BodyGuardReleaseMenu(MessageUtil messages) {
        this.messages = messages;
    }

    public Inventory create(BodyGuardMenuHolder holder, boolean releaseAll,
                            String targetName, int targetCount) {
        String title = text(releaseAll ? "gui.confirm-all-title" : "gui.confirm-single-title",
                releaseAll ? "&c全解除の確認" : "&c解除の確認");
        Inventory inventory = Bukkit.createInventory(holder, SIZE, title);
        holder.setInventory(inventory);
        fill(inventory);

        String target = releaseAll
                ? text("gui.confirm-target-count", "&f対象: &e{count}体",
                Map.of("count", String.valueOf(targetCount)))
                : text("gui.confirm-target-name", "&f対象: &e{name}",
                Map.of("name", targetName == null ? "" : targetName));
        inventory.setItem(4, item(Material.BOOK, text("gui.confirm-target", "&e解除対象"),
                List.of(target, text("gui.confirm-warning", "&c通常のMobに戻り、敵対する可能性があります。"))));
        inventory.setItem(13, item(Material.REDSTONE, text("gui.confirm-warning-title", "&e注意"),
                List.of(text("gui.confirm-warning", "&c通常のMobに戻り、敵対する可能性があります。"),
                        text("gui.confirm-snapshot", "&7この画面を開いた時点のUUIDだけを対象にします。"),
                        text("gui.confirm-loaded-note", "&7未読み込みの護衛は解除予約になり、読み込み時に解除されます。"))));
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
        return inventory;
    }

    private void fill(Inventory inventory) {
        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler.clone());
        }
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
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
}
