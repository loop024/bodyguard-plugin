package plugin.test.com.bodyGuard.command;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.EntityType;

import plugin.test.com.bodyGuard.BodyGuard;
import plugin.test.com.bodyGuard.guard.GuardMode;

/** Context-aware suggestions for the short beginner-friendly command syntax. */
public final class BodyGuardTabCompleter implements TabCompleter {

    private final BodyGuard plugin;

    public BodyGuardTabCompleter(BodyGuard plugin) {
        this.plugin = plugin;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return matching(args[0], List.of(
                    "help", "menu", "item", "summon", "recruit", "release", "releaseall", "list", "tp",
                    "mode", "rename", "heal", "reload"
            ));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("summon")) {
            List<String> mobNames = new ArrayList<>();
            for (EntityType type : plugin.getAllowedMobTypes()) {
                mobNames.add(type.name().toLowerCase(Locale.ROOT));
            }
            mobNames.sort(Comparator.naturalOrder());
            return matching(args[1], mobNames);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("mode")) {
            List<String> modes = new ArrayList<>();
            for (GuardMode mode : GuardMode.values()) {
                modes.add(mode.commandName());
            }
            return matching(args[1], modes);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("releaseall")) {
            return matching(args[1], List.of("confirm"));
        }
        return List.of();
    }

    private List<String> matching(String input, List<String> candidates) {
        String prefix = input == null ? "" : input.toLowerCase(Locale.ROOT);
        return candidates.stream()
                .filter(candidate -> candidate.toLowerCase(Locale.ROOT).startsWith(prefix))
                .sorted()
                .toList();
    }
}
