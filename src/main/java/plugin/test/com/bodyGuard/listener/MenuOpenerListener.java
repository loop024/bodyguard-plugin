package plugin.test.com.bodyGuard.listener;

import java.util.UUID;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;

import plugin.test.com.bodyGuard.BodyGuard;
import plugin.test.com.bodyGuard.gui.BodyGuardMenuHolder;
import plugin.test.com.bodyGuard.util.MessageUtil;

/** Prevents the optional menu opener from becoming a transferable vanilla material. */
public final class MenuOpenerListener implements Listener {

    private static final long MESSAGE_COOLDOWN_MILLIS = 1500L;

    private final BodyGuard plugin;
    private final MessageUtil messages;
    private UUID lastPlayerId;
    private long lastMessageMillis;

    public MenuOpenerListener(BodyGuard plugin, MessageUtil messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!plugin.isMenuOpenerItem(event.getItemDrop().getItemStack())) {
            return;
        }
        event.setCancelled(true);
        notifyProtected(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(plugin::isMenuOpenerItem);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)
                || event.getView().getTopInventory().getHolder() instanceof BodyGuardMenuHolder) {
            return;
        }

        int topSize = event.getView().getTopInventory().getSize();
        int rawSlot = event.getRawSlot();
        boolean currentIsOpener = plugin.isMenuOpenerItem(event.getCurrentItem());
        boolean cursorIsOpener = plugin.isMenuOpenerItem(event.getCursor());
        boolean hotbarIsOpener = event.getHotbarButton() >= 0
                && plugin.isMenuOpenerItem(player.getInventory().getItem(event.getHotbarButton()));

        boolean movingIntoTop = rawSlot >= 0 && rawSlot < topSize
                && (cursorIsOpener || hotbarIsOpener);
        boolean shiftMovingFromPlayer = event.isShiftClick() && rawSlot >= topSize && currentIsOpener;
        boolean droppingFromInventory = currentIsOpener && event.getAction().name().startsWith("DROP_");
        boolean movingTaggedTopItem = currentIsOpener && rawSlot >= 0 && rawSlot < topSize;
        if (movingIntoTop || shiftMovingFromPlayer || droppingFromInventory || movingTaggedTopItem) {
            event.setCancelled(true);
            notifyProtected(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!plugin.isMenuOpenerItem(event.getOldCursor())) {
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        if (event.getRawSlots().stream().anyMatch(slot -> slot < topSize)) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) {
                notifyProtected(player);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        for (ItemStack ingredient : event.getInventory().getMatrix()) {
            if (plugin.isMenuOpenerItem(ingredient)) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    private void notifyProtected(Player player) {
        long now = System.currentTimeMillis();
        UUID playerId = player.getUniqueId();
        if (playerId.equals(lastPlayerId) && now - lastMessageMillis < MESSAGE_COOLDOWN_MILLIS) {
            return;
        }
        lastPlayerId = playerId;
        lastMessageMillis = now;
        messages.send(player, "menu-item-protected",
                "&eBodyGuardメニューアイテムは移動・破棄・材料利用できません。");
    }
}
