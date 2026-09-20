package plugin.test.com.bodyGuard.guard;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import plugin.test.com.bodyGuard.BodyGuard;
import plugin.test.com.bodyGuard.util.LocationUtil;
import plugin.test.com.bodyGuard.storage.SafeYamlFile;

/** Shared read-only explanations for GUI cards and commands. */
public final class GuardStatusGuidance {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private GuardStatusGuidance() { }

    public static String time(long millis) {
        return millis <= 0 ? "記録なし" : DATE.format(Instant.ofEpochMilli(millis));
    }

    public static String saveResult(SafeYamlFile.SaveResult result) {
        return switch (result) {
            case SUCCESS -> "保存成功";
            case READ_ONLY -> "保護のため書き込み停止中";
            case VALIDATION_FAILED -> "保存内容の検証失敗";
            case IO_FAILED -> "ファイルの読み書きに失敗";
            case UNKNOWN_RESULT -> "保存結果を確認できません";
        };
    }

    public static String protectionState(GuardData.ProtectionState state) {
        return switch (state) {
            case ACTIVE -> "保護中";
            case SAME_WORLD -> "同じワールドで保護中";
            case TARGET_SELECTED -> "対象選択済み・護衛の読み込み待ち";
            case TARGET_UNAVAILABLE -> "保護対象を選べません";
            case WORLD_TRANSFER_PENDING -> "対象ワールドへの移動待ち";
            case WAITING -> "保留中";
        };
    }

    public static List<String> lines(BodyGuard plugin, GuardData data, Mob mob) {
        List<String> result = new ArrayList<>();
        GuardManager manager = plugin.getGuardManager();
        GuardData.Status status = manager.status(data);
        if (status != GuardData.Status.AVAILABLE) {
            add(plugin, result, "status", "&7状態: &e{value}", status.label());
            SavedPosition last = data.getSavedLast();
            String position = last == null ? "記録なし"
                    : (last.worldName() == null || last.worldName().isBlank()
                        ? String.valueOf(last.worldId()) : last.worldName()) + " "
                        + (int) Math.floor(last.x()) + ", " + (int) Math.floor(last.y()) + ", "
                        + (int) Math.floor(last.z());
            add(plugin, result, "last-position", "&7最終位置: &f{value}", position);
            add(plugin, result, "last-seen", "&7最終確認: &f{value}", time(data.getLastSeen()));
            String action = switch (status) {
                case WORLD_UNAVAILABLE -> "管理者に元のワールドの読み込み・保存ワールドUUIDの確認を依頼してください。";
                case CHECKING -> "所在の確認中です。少し待つか、最終位置に近づいて手動更新してください。";
                case MISSING -> "死亡は未確認です。最終位置の周囲を探し、見つからなければ管理者へ相談してください。";
                case QUARANTINED -> "データの不一致を確認するため操作を止めています。管理者に /bg status server の確認を依頼してください。";
                case RELEASE_PENDING -> "解除予約済みです。護衛の読み込みと保存が成功すると通常Mobへ戻ります。";
                case DELETE_PENDING -> "削除予約済みです。護衛の読み込みと保存が成功すると削除されます。";
                case RELEASED, DELETED, DEAD -> "この契約は終了しています。手動更新で一覧を整理してください。";
                case UNLOADED -> "最後に確認した場所へ近づいて手動更新してください。読み込み前は回復・呼び戻しできません。";
                default -> "手動更新で状態を確認してください。";
            };
            if (status == GuardData.Status.UNLOADED) {
                GuardManager.ChunkWaitReason reason = manager.getChunkWaitReason(data);
                String explanation = switch (reason) {
                    case DISABLED -> "護衛のチャンク維持は設定で無効です。";
                    case OWNER_OFFLINE -> "所有者がオフラインのため、自動読み込みの対象外です。";
                    case OWNER_LIMIT -> "前回の管理処理では、所有者ごとのチャンク上限で待機しています。";
                    case SERVER_LIMIT -> "前回の管理処理では、サーバー全体のチャンク上限で待機しています。";
                    case SCHEDULED -> "読み込み候補です。順番待ちまたは読み込み結果の確認中です。";
                    case UNKNOWN -> "未読み込みです。自動読み込みの待機理由はまだ確認できません。";
                };
                add(plugin, result, "chunk-" + reason.name().toLowerCase(java.util.Locale.ROOT), explanation);
            }
            add(plugin, result, "action-" + status.name().toLowerCase(java.util.Locale.ROOT), "&e" + action);
        } else if (mob != null) {
            Player owner = Bukkit.getPlayer(data.getOwnerId());
            if (owner == null && plugin.freezeOfflineGuards()) {
                add(plugin, result, "owner-offline", "&e所有者がオフラインのため停止中です。ログインすると再開します。");
            } else if (data.isRoleProtection() && data.getProtectionFailureReason() != null) {
                add(plugin, result, "protection-reason", "&e保護の状態: {value}", data.getProtectionFailureReason());
                add(plugin, result, "protection-action", "&7保護対象欄と /bg protect status を確認してください。役職の設定・権限は管理者へ相談できます。");
            } else if (!data.isRoleProtection() && data.getMode() == GuardMode.FOLLOW
                    && owner != null && !LocationUtil.sameWorld(owner.getLocation(), mob.getLocation())
                    && !plugin.shouldTeleportDifferentWorld()) {
                add(plugin, result, "world-disabled", "&e別ワールドへの自動移動は無効です。護衛と同じワールドへ戻るか管理者へ相談してください。");
            } else if (data.getMode() != GuardMode.FOLLOW && data.getSavedAnchor() != null
                    && data.getAnchorLocation() == null) {
                add(plugin, result, "anchor-unavailable", "&e待機・警備地点のワールドを確認できません。管理者に元のワールドの確認を依頼してください。");
            }
            switch (data.getMovementRecoveryState()) {
                case SIDESTEP -> add(plugin, result, "recovery-sidestep", "&e進めないため横移動を試しています。少し待ってください。");
                case NO_SAFE_DESTINATION -> add(plugin, result, "recovery-no-safe", "&e探索範囲に安全な復帰先が見つかりません。目的地の周囲の足場を広くし、再試行を待ってください。");
                case TELEPORT_REJECTED -> add(plugin, result, "recovery-rejected", "&e復帰の移動が許可されませんでした。管理者に移動制限の確認を依頼してください。");
                case NORMAL -> { }
            }
        }
        if (!manager.isStorageHealthy()) {
            add(plugin, result, "storage", "&c保存状態を確認できません。/bg status を確認し、管理者に保存先・ログの確認を依頼してください。");
        }
        return result;
    }

    private static void add(BodyGuard plugin, List<String> lines, String key, String fallback) {
        lines.add(plugin.color(plugin.getMessages().get("guidance." + key, fallback)));
    }

    private static void add(BodyGuard plugin, List<String> lines, String key, String fallback, String value) {
        lines.add(plugin.color(plugin.getMessages().format(
                plugin.getMessages().get("guidance." + key, fallback), Map.of("value", value))));
    }
}
