package plugin.test.com.bodyGuard.listener;

import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;

import plugin.test.com.bodyGuard.BodyGuard;
import plugin.test.com.bodyGuard.guard.GuardData;
import plugin.test.com.bodyGuard.guard.GuardManager;
import plugin.test.com.bodyGuard.util.EntityUtil;

/** Removes dead guards from the registry and notifies an online owner. */
public final class GuardDeathListener implements Listener {

    private final BodyGuard plugin;
    private final GuardManager manager;

    public GuardDeathListener(BodyGuard plugin, GuardManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        GuardData data = manager.removeGuard(event.getEntity().getUniqueId());
        if (data == null) {
            return;
        }
        Player owner = Bukkit.getPlayer(data.getOwnerId());
        if (owner != null && owner.isOnline()) {
            plugin.getMessages().sendChat(owner, "guard-died", Map.of(
                    "mob", EntityUtil.prettyMobName(data.getMobType()),
                    "name", data.getName()
            ));
        }
    }
}
