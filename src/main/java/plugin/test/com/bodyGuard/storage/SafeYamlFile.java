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
    public enum SaveResult {
        SUCCESS,
        READ_ONLY,
        VALIDATION_FAILED,
        IO_FAILED,
        OUTCOME_UNCERTAIN
    }

    private final JavaPlugin plugin;
    private final File file;
    private final String expectedRoot;
    private boolean writable;
    private boolean healthy = true;
    private long lastWarning;
    private long lastSaved;
    private long lastAttempt;
    private int consecutiveFailures;
    private String lastFailureReason;
    private SaveResult lastSaveResult = SaveResult.SUCCESS;

    public SafeYamlFile(JavaPlugin plugin, String name) {
        this(plugin, name, name.equals("guards.yml") ? "guards" : "players");
    }

    public SafeYamlFile(JavaPlugin plugin, String name, String expectedRoot) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), name);
        this.expectedRoot = expectedRoot;
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
        if (result.contains(expectedRoot) && !result.isConfigurationSection(expectedRoot)) {
            throw new IOException("Invalid section: " + expectedRoot);
        }
        return result;
    }

    public boolean save(YamlConfiguration configuration) {
        return saveWithResult(configuration) == SaveResult.SUCCESS;
    }

    public SaveResult saveWithResult(YamlConfiguration configuration) {
        lastAttempt = System.currentTimeMillis();
        if (!writable) {
            lastSaveResult = SaveResult.READ_ONLY;
            lastFailureReason = "読み込み時の障害により読み取り専用です";
            consecutiveFailures++;
            return lastSaveResult;
        }
        File temporary = null;
        boolean replacementStarted = false;
        try {
            Files.createDirectories(file.getParentFile().toPath());
            temporary = File.createTempFile("bodyguard-", ".tmp", file.getParentFile());
            configuration.save(temporary);
            // Validate the exact bytes that will replace the live file.
            read(temporary);
            if (file.exists()) {
                // Do not overwrite the last good backup with externally damaged YAML.
                read(file);
                Files.copy(file.toPath(), backup().toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            replacementStarted = true;
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            healthy = true;
            lastSaved = System.currentTimeMillis();
            consecutiveFailures = 0;
            lastFailureReason = null;
            lastSaveResult = SaveResult.SUCCESS;
            return lastSaveResult;
        } catch (Exception failure) {
            healthy = false;
            consecutiveFailures++;
            lastFailureReason = failure.getClass().getSimpleName() + ": "
                    + (failure.getMessage() == null ? "詳細なし" : failure.getMessage());
            lastSaveResult = failure instanceof IOException
                    ? (replacementStarted ? SaveResult.OUTCOME_UNCERTAIN : SaveResult.IO_FAILED)
                    : SaveResult.VALIDATION_FAILED;
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
            return lastSaveResult;
        } finally {
            if (temporary != null && temporary.exists()) temporary.delete();
        }
    }

    public boolean isHealthy() { return healthy && writable; }
    public long getLastSaved() { return lastSaved; }
    public long getLastAttempt() { return lastAttempt; }
    public int getConsecutiveFailures() { return consecutiveFailures; }
    public String getLastFailureReason() { return lastFailureReason; }
    public SaveResult getLastSaveResult() { return lastSaveResult; }
    private File backup() { return new File(file.getParentFile(), file.getName() + ".bak"); }
}
