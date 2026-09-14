# BodyGuard

敵対モブをプレイヤー専属の護衛にして連れ歩ける、Spigot用プラグインです。

## 対応環境

- Minecraft Java Edition 1.21.8
- Spigot 1.21.8
- Java 21
- Maven

## インストール方法

1. `target/BodyGuard.jar` をサーバーの `plugins` フォルダへ入れます。
2. Spigotサーバーを再起動します。
3. ゲーム内で `/bg help` を実行します。

初回起動時に `plugins/BodyGuard/config.yml`、`messages.yml`、`guards.yml` が作成されます。初期設定のままでも遊べます。

## 基本的な使い方

```text
/bg summon zombie
/bg summon skeleton
```

プレイヤーの前に護衛が出現します。自分が攻撃されたときや、自分が敵を攻撃したとき、護衛も対象を攻撃します。

## コマンド

| コマンド | 説明 |
| --- | --- |
| `/bg help` | コマンド一覧を表示 |
| `/bg summon <mob>` | 対応Mobを召喚して護衛にする |
| `/bg recruit` | 見ている既存の対応Mobを護衛にする |
| `/bg release` | 見ている自分の護衛を通常Mobに戻す |
| `/bg releaseall confirm` | 自分の護衛をすべて解除 |
| `/bg list` | 自分の護衛一覧を表示 |
| `/bg tp` | 自分の護衛を周辺へテレポート |
| `/bg mode follow` | プレイヤーを追従 |
| `/bg mode stay` | 現在位置で待機 |
| `/bg mode guard` | 現在位置を中心に警備 |
| `/bg rename <名前>` | 見ている護衛の名前を変更 |
| `/bg heal` | 自分の護衛を全回復 |
| `/bg reload` | 設定を再読み込み（管理者） |

`release`、`mode`、`rename` は対象の護衛を5～10ブロック程度の範囲で見て実行してください。

## 初期対応Mob

Zombie、Skeleton、Husk、Stray、Drowned、Bogged、Wither Skeleton、Zombified Piglin、Spider、Cave Spider、Pillager、Vindicatorです。`config.yml` の `allowed-mobs` で変更できます。

Creeper、Enderman、Witch、Ghast、Blaze、Wither、Ender Dragon、Wardenは初期設定では対象外です。

## Permissions

- `bodyguard.use` - `/bg` の使用
- `bodyguard.summon` - 護衛の召喚
- `bodyguard.recruit` - 既存Mobの勧誘
- `bodyguard.release` - 護衛の解除
- `bodyguard.releaseall` - 全護衛の解除
- `bodyguard.mode` - モード変更
- `bodyguard.rename` - 名前変更
- `bodyguard.teleport` - 護衛のテレポート
- `bodyguard.heal` - 護衛の回復
- `bodyguard.reload` - 設定再読み込み（初期値はOPのみ）
- `bodyguard.admin` - 管理者用権限

## config.ymlの主な設定

- `limits.max-guards-per-player` - プレイヤーごとの護衛上限
- `follow.start-distance` / `follow.teleport-distance` - 追従開始距離と遠距離テレポート距離
- `teleport.different-world` - ワールド移動時の追従テレポート
- `combat.*` - 防衛、攻撃支援、プレイヤーへの反撃、追跡距離
- `friendly-fire.*` - 所有者・同じ所有者の護衛へのダメージ許可
- `stay-mode.*` / `guard-mode.*` - 待機・警備の動作
- `owner-offline.freeze-guards` - 所有者ログアウト時に護衛AIを停止
- `display.*` / `effects.enabled` - 表示名とエフェクト

数値設定が不正または範囲外の場合は、安全な初期値へフォールバックします。

## データ保存

護衛にはPersistentDataContainerでBodyGuard識別情報、Owner UUID、Guard UUID、モード、名前を保存します。さらに `guards.yml` に管理情報と待機地点を保存します。護衛Entity UUIDはサーバー再起動後に読み込まれ、チャンクが読み込まれた時点で管理へ復元されます。死亡した護衛のデータは削除されます。

## Spigot APIだけでの実装について

NMSやCraftBukkit内部クラスは使用していません。Spigot APIにはMobのネイティブ経路探索先を直接指定するAPIがないため、follow/stay/guardの移動補助は10tick間隔の速度制御と、設定距離を超えた場合のテレポートで実装しています。攻撃方法はMobの通常AIを基本的に残し、ターゲット制御と味方判定をイベントで行います。そのためSkeletonの弓やPillagerのクロスボウなど、元Mobの特徴を維持できます。

## ビルド方法（Windows）

コマンドプロンプトでプロジェクトフォルダを開き、次を実行します。

```bat
mvn clean package
```

完成したJarは `target/BodyGuard.jar` です。

## 初心者向け動作確認

サーバーを起動して、次の順に確認してください。

1. `/bg summon zombie` で自分の前にZombieが出る。
2. `/bg list` にZombieが表示される。
3. 少し歩いて、Zombieがついてくる。
4. `/bg summon skeleton` でSkeletonを追加する。
5. 敵Mobに自分を攻撃させ、護衛が反撃する。
6. 自分が敵Mobを一度攻撃し、護衛も攻撃する。
7. `/bg mode stay`、`/bg mode guard`、`/bg mode follow` を見ている護衛に実行する。
8. `/bg tp`、`/bg heal`、`/bg release` を実行する。

## 既知の制限

- プラグイン停止中やチャンクが未読み込みのEntityを、Spigot APIだけで毎回強制ロードすることは避けています。通常はEntity自身のPDCと `guards.yml` で復元されます。
- オーナーがログアウトしたときの挙動は `owner-offline.freeze-guards` で設定します。
- Mobごとの特殊AIをNMSで改造してはいないため、Minecraft本体や他プラグインのAI変更によって追従感が変わる場合があります。
