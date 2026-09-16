package plugin.test.com.bodyGuard.storage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.logging.Level;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** Never replaces unreadable source data with an empty registry. */
public final class SafeYamlFile {
    private final JavaPlugin plugin;
    private final File file;
    private boolean writable;
    private boolean healthy = true;
    private long lastWarning;
    private long lastSaved;

    public SafeYamlFile(JavaPlugin plugin, String name) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), name);
    }

    public YamlConfiguration load() {
        if (!file.exists() && !backup().exists()) {
            writable = true;
            return new YamlConfiguration();
        }
        try {
            YamlConfiguration result = read(file);
            writable = true;
            return result;
        } catch (Exception failure) {
            try {
                if (file.exists()) {
                    Files.copy(file.toPath(), new File(file.getParentFile(),
                            file.getName() + ".damaged-" + java.util.UUID.randomUUID()).toPath());
                }
                YamlConfiguration recovered = read(backup());
                Files.copy(backup().toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                writable = true;
                plugin.getLogger().warning(file.getName() + ": バックアップから復旧しました。破損ファイルは保全されています。");
                return recovered;
            } catch (Exception recoveryFailure) {
                writable = false;
                healthy = false;
                throw new IllegalStateException(file.getName()
                        + " を復旧できません。元ファイルを保護するため起動を中止します。", failure);
            }
        }
    }

    private YamlConfiguration read(File source) throws Exception {
        YamlConfiguration result = new YamlConfiguration();
        result.load(source);
        String root = file.getName().equals("guards.yml") ? "guards" : "players";
        if (result.contains(root) && !result.isConfigurationSection(root)) {
            throw new IOException("Invalid section: " + root);
        }
        return result;
    }

    public boolean save(YamlConfiguration configuration) {
        if (!writable) return false;
        File temporary = null;
        try {
            Files.createDirectories(file.getParentFile().toPath());
            temporary = File.createTempFile("bodyguard-", ".tmp", file.getParentFile());
            configuration.save(temporary);
            if (file.exists()) {
                // Do not overwrite the last good backup with externally damaged YAML.
                read(file);
                Files.copy(file.toPath(), backup().toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            healthy = true;
            lastSaved = System.currentTimeMillis();
            return true;
        } catch (Exception failure) {
            healthy = false;
            long now = System.currentTimeMillis();
            if (now - lastWarning >= 30000L) {
                lastWarning = now;
                plugin.getLogger().log(Level.SEVERE, file.getName() + " の保存に失敗しました。変更は未保存です。", failure);
                for (org.bukkit.entity.Player player : plugin.getServer().getOnlinePlayers()) {
                    if (player.hasPermission("bodyguard.admin")) {
                        player.sendMessage("§c[BodyGuard] " + file.getName() + " の保存に失敗。空き容量・アクセス権・サーバーログを確認してください。");
                    }
                }
            }
            return false;
        } finally {
            if (temporary != null && temporary.exists()) temporary.delete();
        }
    }

    public boolean isHealthy() { return healthy && writable; }
    public long getLastSaved() { return lastSaved; }
    private File backup() { return new File(file.getParentFile(), file.getName() + ".bak"); }
}
