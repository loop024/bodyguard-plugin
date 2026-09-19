package plugin.test.com.bodyGuard.listener;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Event.Result;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import plugin.test.com.bodyGuard.BodyGuard;
import plugin.test.com.bodyGuard.gui.BodyGuardGui;
import plugin.test.com.bodyGuard.guard.GuardData;
import plugin.test.com.bodyGuard.guard.GuardManager;

/** Handles owner online state and lightweight chunk-based guard restoration. */
public final class PlayerListener implements Listener {

    private final GuardManager manager;
    private final BodyGuard plugin;
    private final BodyGuardGui gui;

    public PlayerListener(BodyGuard plugin, GuardManager manager, BodyGuardGui gui) {
        this.plugin = plugin;
        this.manager = manager;
        this.gui = gui;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        manager.requestProtectionRefresh(event.getPlayer().getUniqueId());
        manager.freezeOwner(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        manager.requestProtectionRefresh(event.getPlayer().getUniqueId());
        manager.resumeOwner(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        // The next shared GuardTask tick reads the final target location.
        manager.requestProtectionRefresh(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        // Respawn location is finalized by Bukkit before the next 10-tick task.
        manager.requestProtectionRefresh(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        manager.requestProtectionRefresh(event.getEntity().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        manager.requestProtectionRefresh(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        manager.handleChunkLoad(event.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        manager.handleEntitiesLoad(event.getChunk(), event.getEntities());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent event) {
        manager.handleChunkUnload(event.getChunk());
    }

    /** Opens the main menu by right-clicking the configured item in the main hand. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onRightClickMenuOpener(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || (event.getAction() != Action.RIGHT_CLICK_AIR
                    && event.getAction() != Action.RIGHT_CLICK_BLOCK)) {
            return;
        }

        Player player = event.getPlayer();
        if ((!player.hasPermission("bodyguard.use") && !player.hasPermission("bodyguard.admin"))
                || gui == null) {
            return;
        }

        ItemStack item = event.getItem();
        if (!plugin.isMenuOpenerItem(item)) {
            return;
        }

        event.setCancelled(true);
        event.setUseInteractedBlock(Result.DENY);
        event.setUseItemInHand(Result.DENY);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && plugin.isMenuOpenerItem(player.getInventory().getItemInMainHand())) {
                gui.openCommandMenu(player);
            }
        });
    }

    /** Opens a personally owned guard's detail screen without changing ordinary right-clicks. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSneakRightClickGuard(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.isSneaking() || !isEmptyHand(player.getInventory().getItemInMainHand())
                || !isEmptyHand(player.getInventory().getItemInOffHand())) {
            return;
        }
        Entity clicked = event.getRightClicked();
        GuardData data = manager.getGuardData(clicked);
        if (data == null || !player.getUniqueId().equals(data.getOwnerId())
                || (!player.hasPermission("bodyguard.use")
                    && !player.hasPermission("bodyguard.admin")) || gui == null) {
            return;
        }

        event.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                gui.openDetails(player, data.getGuardId());
            }
        });
    }

    private boolean isEmptyHand(ItemStack stack) {
        return stack == null || stack.getType().isAir();
    }
}
