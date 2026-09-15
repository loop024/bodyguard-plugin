package plugin.test.com.bodyGuard.util;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Loads and formats all player-facing messages from messages.yml. */
public final class MessageUtil {

    private final JavaPlugin plugin;
    private final File file;
    private YamlConfiguration configuration;

    public MessageUtil(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "messages.yml");
        reload();
    }

    public void reload() {
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        configuration = YamlConfiguration.loadConfiguration(file);
    }

    public void send(CommandSender sender, String key) {
        send(sender, key, Collections.emptyMap());
    }

    /** Sends a message while retaining a bundled fallback for older message files. */
    public void send(CommandSender sender, String key, String fallback) {
        send(sender, key, fallback, Collections.emptyMap());
    }

    public void send(CommandSender sender, String key, Map<String, String> placeholders) {
        String body = format(get(key, key), placeholders);
        sender.sendMessage(color(prefix()) + body);
        showOperationNotice(sender, body, noticeTone(body));
    }

    /** Sends a chat-only message for passive events that were not player operations. */
    public void sendChat(CommandSender sender, String key, Map<String, String> placeholders) {
        String body = format(get(key, key), placeholders);
        sender.sendMessage(color(prefix()) + body);
    }

    /** Sends a message while retaining a bundled fallback for older message files. */
    public void send(CommandSender sender, String key, String fallback,
                     Map<String, String> placeholders) {
        String body = format(get(key, fallback), placeholders);
        sender.sendMessage(color(prefix()) + body);
        showOperationNotice(sender, body, noticeTone(body));
    }

    /** Shows the same unmistakable on-screen acknowledgement for command and GUI operations. */
    public void showOperationNotice(CommandSender sender, String body, NoticeTone tone) {
        if (!(sender instanceof Player player)
                || !plugin.getConfig().getBoolean("notifications.operation-titles.enabled", true)) {
            return;
        }
        NoticeTone actualTone = tone == null ? NoticeTone.SUCCESS : tone;
        String title = switch (actualTone) {
            case SUCCESS -> get("notification.success", "&a&l✓ 実行しました");
            case WARNING -> get("notification.warning", "&e&l! 確認してください");
            case FAILURE -> get("notification.failure", "&c&l✕ 実行できませんでした");
        };
        int stay = Math.max(20, Math.min(100, plugin.getConfig()
                .getInt("notifications.operation-titles.duration-ticks", 50)));
        player.sendTitle(color(title), body == null ? "" : body, 5, stay, 10);
    }

    private NoticeTone noticeTone(String body) {
        if (body != null && body.startsWith(ChatColor.RED.toString())) {
            return NoticeTone.FAILURE;
        }
        if (body != null && (body.startsWith(ChatColor.YELLOW.toString())
                || body.startsWith(ChatColor.GOLD.toString()))) {
            return NoticeTone.WARNING;
        }
        return NoticeTone.SUCCESS;
    }

    public enum NoticeTone {
        SUCCESS,
        WARNING,
        FAILURE
    }

    public void sendLines(CommandSender sender, String key, Map<String, String> placeholders) {
        for (String line : getList(key)) {
            sender.sendMessage(format(prefix() + line, placeholders));
        }
    }

    public String get(String key, String fallback) {
        String value = configuration.getString(key);
        return value == null ? fallback : value;
    }

    public List<String> getList(String key) {
        List<String> values = configuration.getStringList(key);
        return values == null ? List.of() : values;
    }

    public String format(String text, Map<String, String> placeholders) {
        String result = text == null ? "" : text;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            String value = entry.getValue() == null ? "" : entry.getValue();
            result = result.replace("{" + entry.getKey() + "}", value);
        }
        return color(result);
    }

    public String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    private String prefix() {
        return get("prefix", "&9[BodyGuard]&r ");
    }
}
