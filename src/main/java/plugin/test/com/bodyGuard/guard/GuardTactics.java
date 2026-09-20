package plugin.test.com.bodyGuard.guard;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Immutable settings shared by registry and PDC; runtime pursuit/route progress is not saved. */
public record GuardTactics(CombatPolicy policy, boolean patrol, List<SavedPosition> points) {
    public static final GuardTactics DEFAULT = new GuardTactics(CombatPolicy.LEGACY, false, List.of());
    public GuardTactics {
        if (policy == null || points == null || points.size() > 16) throw new IllegalArgumentException("戦闘・巡回設定が不正です");
        points = List.copyOf(points);
        if (patrol && points.size() < 2) throw new IllegalArgumentException("巡回には2地点以上必要です");
        if (!points.isEmpty()) {
            java.util.UUID world = points.get(0).worldId();
            if (world == null || points.stream().anyMatch(p -> !world.equals(p.worldId()))) {
                throw new IllegalArgumentException("巡回地点は同じワールドで指定してください");
            }
        }
    }
    public GuardTactics withPolicy(CombatPolicy next) { return new GuardTactics(next, patrol, points); }
    public GuardTactics stopped() { return new GuardTactics(policy, false, points); }
    public void write(ConfigurationSection configuration, String path) {
        configuration.set(path + ".policy", policy.name());
        configuration.set(path + ".patrol", patrol);
        configuration.set(path + ".points", null);
        for (int i = 0; i < points.size(); i++) points.get(i).write(configuration, path + ".points." + i);
    }
    public String encode() {
        YamlConfiguration yaml = new YamlConfiguration();
        write(yaml, "tactics");
        return yaml.saveToString();
    }
    public static GuardTactics decode(String text) {
        if (text == null) return DEFAULT;
        if (text.length() > 32768) throw new IllegalArgumentException("巡回データが大きすぎます");
        YamlConfiguration yaml = new YamlConfiguration();
        try { yaml.loadFromString(text); }
        catch (Exception invalid) { throw new IllegalArgumentException("戦闘・巡回データを読めません", invalid); }
        return read(yaml, "tactics");
    }
    public static GuardTactics read(ConfigurationSection configuration, String path) {
        if (!configuration.contains(path)) return DEFAULT;
        if (!configuration.isConfigurationSection(path)) throw new IllegalArgumentException("tacticsの形式が不正です");
        CombatPolicy policy = CombatPolicy.parse(configuration.getString(path + ".policy", "LEGACY"));
        Object raw = configuration.get(path + ".patrol", false);
        if (!(raw instanceof Boolean enabled)) throw new IllegalArgumentException("patrolはbooleanで指定してください");
        List<SavedPosition> points = new ArrayList<>();
        ConfigurationSection section = configuration.getConfigurationSection(path + ".points");
        if (configuration.contains(path + ".points") && section == null) throw new IllegalArgumentException("巡回地点の形式が不正です");
        if (section != null) {
            int count = section.getKeys(false).size();
            if (count > 16) throw new IllegalArgumentException("巡回地点は16個までです");
            for (int i = 0; i < count; i++) {
                SavedPosition point = SavedPosition.read(section, String.valueOf(i));
                if (point == null) throw new IllegalArgumentException("巡回地点の番号が不正です");
                points.add(point);
            }
        }
        return new GuardTactics(policy, enabled, points);
    }
}
