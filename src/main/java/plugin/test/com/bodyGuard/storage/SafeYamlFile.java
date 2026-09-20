package plugin.test.com.bodyGuard.storage;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * YAML persistence with explicit outcomes and a fail-safe recovery path.
 * A malformed live file is never silently replaced with an empty document.
 */
public final class SafeYamlFile {

    public enum SaveResult {
        SUCCESS,
        READ_ONLY,
        VALIDATION_FAILED,
        IO_FAILED,
        UNKNOWN_RESULT
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
        this(plugin, name, name.equals("guards.yml") ? "guards"
                : name.equals("players.yml") ? "players" : "entries");
    }

    public SafeYamlFile(JavaPlugin plugin, String name, String expectedRoot) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), name);
        this.expectedRoot = expectedRoot;
    }

    public YamlConfiguration load() {
        File backup = backup();
        if (!file.exists() && !backup.exists()) {
            writable = true;
            healthy = true;
            return new YamlConfiguration();
        }

        Exception liveFailure;
        try {
            YamlConfiguration result = read(file);
            writable = true;
            healthy = true;
            return result;
        } catch (UnsupportedSchemaVersionException failure) {
            writable = false;
            healthy = false;
            throw new IllegalStateException(file.getName()
                    + " は未対応の新しい形式です。元ファイルを変更せず起動を中止します。", failure);
        } catch (Exception failure) {
            liveFailure = failure;
        }

        // Preserve the damaged source before considering recovery. Failure to
        // preserve it is itself a stop condition, not permission to overwrite it.
        try {
            if (file.exists()) {
                Path damaged = file.toPath().resolveSibling(
                        file.getName() + ".damaged-" + UUID.randomUUID());
                Files.copy(file.toPath(), damaged, StandardCopyOption.COPY_ATTRIBUTES);
            }
            YamlConfiguration recovered = read(backup);
            restoreAtomically(backup, file);
            writable = true;
            healthy = true;
            plugin.getLogger().warning(file.getName()
                    + ": バックアップから復旧しました。破損ファイルは保全されています。");
            return recovered;
        } catch (Exception recoveryFailure) {
            writable = false;
            healthy = false;
            IllegalStateException stop = new IllegalStateException(file.getName()
                    + " を復旧できません。元ファイルを保護するため起動を中止します。", liveFailure);
            stop.addSuppressed(recoveryFailure);
            throw stop;
        }
    }

    private void restoreAtomically(File source, File destination)
            throws IOException, InvalidConfigurationException {
        Path temporary = Files.createTempFile(destination.toPath().getParent(),
                "bodyguard-recovery-", ".tmp");
        try {
            Files.copy(source.toPath(), temporary, StandardCopyOption.REPLACE_EXISTING);
            read(temporary.toFile());
            moveReplacing(temporary, destination.toPath());
            read(destination);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private YamlConfiguration read(File source)
            throws IOException, InvalidConfigurationException {
        if (source == null || !source.exists()) {
            throw new IOException("File does not exist: " + file.getName());
        }
        YamlConfiguration result = new YamlConfiguration();
        result.load(source);
        if (!result.isConfigurationSection(expectedRoot)) {
            throw new IOException("Invalid section: " + expectedRoot);
        }
        validateVersion(result);
        validateRecordCount(result);
        return result;
    }

    private void validateVersion(YamlConfiguration result) throws IOException {
        if (!result.contains("version")) return;
        Object raw = result.get("version");
        if (!(raw instanceof Number number) || number.intValue() < 1
                || number.doubleValue() != number.intValue()) {
            throw new IOException("Invalid schema version");
        }
        int supported = StorageSchema.supportedVersion(expectedRoot);
        if (number.intValue() > supported) {
            throw new UnsupportedSchemaVersionException(number.intValue());
        }
    }

    private static final class UnsupportedSchemaVersionException extends IOException {
        private UnsupportedSchemaVersionException(int version) {
            super("Unsupported schema version: " + version);
        }
    }

    private void validateRecordCount(YamlConfiguration result) throws IOException {
        if (!result.contains("record-count")) return;
        Object raw = result.get("record-count");
        ConfigurationSection section = result.getConfigurationSection(expectedRoot);
        if (!(raw instanceof Number number) || number.longValue() < 0
                || number.doubleValue() != number.longValue() || section == null
                || number.longValue() != section.getKeys(false).size()) {
            throw new IOException("Record count does not match stored entries");
        }
    }

    public boolean save(YamlConfiguration configuration) {
        return saveWithResult(configuration) == SaveResult.SUCCESS;
    }

    public SaveResult saveWithResult(YamlConfiguration configuration) {
        lastAttempt = System.currentTimeMillis();
        if (!writable) {
            return fail(SaveResult.READ_ONLY, "読み込み時の障害により読み取り専用です", null);
        }
        if (configuration == null || !configuration.isConfigurationSection(expectedRoot)) {
            return fail(SaveResult.VALIDATION_FAILED, "保存対象のルートが不正です", null);
        }

        File temporary = null;
        boolean replacementStarted = false;
        try {
            Files.createDirectories(file.getParentFile().toPath());
            temporary = File.createTempFile("bodyguard-", ".tmp", file.getParentFile());
            configuration.save(temporary);

            // Validate the exact bytes that are about to replace the live file.
            read(temporary);
            if (file.exists()) {
                // A damaged live file is not a valid backup source. Keep it and
                // stop instead of overwriting the last known-good backup.
                read(file);
                Files.copy(file.toPath(), backup().toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            replacementStarted = true;
            moveReplacing(temporary.toPath(), file.toPath());
            // A successful move is not treated as final until the new live bytes
            // can be read back.
            read(file);
            healthy = true;
            lastSaved = System.currentTimeMillis();
            consecutiveFailures = 0;
            lastFailureReason = null;
            lastSaveResult = SaveResult.SUCCESS;
            return lastSaveResult;
        } catch (Exception failure) {
            SaveResult result = replacementStarted ? SaveResult.UNKNOWN_RESULT
                    : failure instanceof IOException ? SaveResult.IO_FAILED
                    : SaveResult.VALIDATION_FAILED;
            return fail(result, failure.getClass().getSimpleName() + ": "
                    + (failure.getMessage() == null ? "詳細なし" : failure.getMessage()), failure);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary.toPath());
                } catch (IOException ignored) {
                    // The temporary name is unique and can be removed manually.
                }
            }
        }
    }

    private SaveResult fail(SaveResult result, String reason, Exception failure) {
        healthy = false;
        if (result == SaveResult.UNKNOWN_RESULT) {
            // The destination may already contain the new bytes. Do not allow a
            // later retry to overwrite that uncertain state from stale memory.
            writable = false;
        }
        consecutiveFailures++;
        lastFailureReason = reason;
        lastSaveResult = result;
        long now = System.currentTimeMillis();
        if (now - lastWarning >= 30000L) {
            lastWarning = now;
            if (failure == null) {
                plugin.getLogger().severe(file.getName() + " の保存を受け付けられません。" + reason);
            } else {
                plugin.getLogger().log(Level.SEVERE,
                        file.getName() + " の保存に失敗しました。変更は未保存です。", failure);
            }
            for (org.bukkit.entity.Player player : plugin.getServer().getOnlinePlayers()) {
                if (player.hasPermission("bodyguard.admin")) {
                    player.sendMessage("§c[BodyGuard] " + file.getName()
                            + " の保存状態を確認できません。変更は保留されています。");
                }
            }
        }
        return result;
    }

    private void moveReplacing(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public boolean isHealthy() { return healthy && writable; }
    public boolean isWritable() { return writable; }
    public long getLastSaved() { return lastSaved; }
    public long getLastAttempt() { return lastAttempt; }
    public int getConsecutiveFailures() { return consecutiveFailures; }
    public String getLastFailureReason() { return lastFailureReason; }
    public SaveResult getLastSaveResult() { return lastSaveResult; }
    public String getFileName() { return file.getName(); }

    private File backup() {
        return new File(file.getParentFile(), file.getName() + ".bak");
    }
}
