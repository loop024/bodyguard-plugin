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

プレイヤーが引数なしで `/bg` を実行すると護衛一覧GUIが開きます。`/bg menu` でも同じ画面を開けます。コンソールで引数なしの `/bg` を実行した場合は、従来どおりヘルプを表示します。

```text
/bg summon zombie
/bg summon skeleton
```

プレイヤーの前に護衛が出現します。自分が攻撃されたときや、自分が敵を攻撃したとき、護衛も対象を攻撃します。

## コマンド

| コマンド | 説明 |
| --- | --- |
| `/bg help` | コマンド一覧を表示 |
| `/bg` / `/bg menu` | 護衛一覧GUIを開く（プレイヤーのみ） |
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

## GUI操作

GUIはSpigot標準のチェスト型インベントリだけで構成されています。護衛一覧は54スロットで、上段45スロットに護衛、下段9スロットに操作ボタンを表示します。護衛が45体を超える設定でもページ移動で確認できます。

### 護衛一覧

- 護衛のスポーンエッグをクリックすると、その護衛の詳細画面を開きます。アイコン名にはカスタム名、説明には種類・「追従」「待機」「警備」・HP・距離または状態を表示します。
- 同じワールドで読み込み済みの場合だけ距離とHPを表示します。別ワールドは「別ワールド」、未読み込みは「未読み込み」と表示し、未取得のHPや距離は推測しません。
- 「召喚」から、現在の `allowed-mobs` で許可されているMobを選びます。召喚直前にも権限・許可設定・上限を確認します。
- 「全員を呼び戻す」「全員を回復」は、既存コマンドと同じく操作可能な読み込み済み護衛を対象にします。
- 「全員の護衛契約を解除」は確認画面を開きます。確認画面を開いた後に増えた護衛は、確認なしで解除されません。
- 「更新」はその時点の台帳とEntity状態を読み直します。常時更新タスクは追加していません。

### 護衛詳細

「追従」「待機」「警備」をクリックしてモードを変更できます。現在のモードは緑色と「現在のモード」の表示で区別されます。名前変更用の入力GUIは追加していないため、詳細画面の案内どおり `/bg rename <名前>` を使用してください。

詳細画面から単体解除を選ぶと確認画面が開きます。「通常のMobに戻り、敵対する可能性があります」と表示され、「解除する」と「キャンセル」は離して配置しています。画面を閉じるだけでは解除されません。

GUIを開いている間は、クリック、Shiftクリック、数字キー、ダブルクリック、ドラッグなどでGUIのアイテムを持ち出したり持ち込んだりできません。GUI以外のインベントリ操作は変更していません。

既存の `messages.yml` は上書きしません。新しいGUIキーがまだない既存サーバーでは、プラグイン内の同梱デフォルト文言へフォールバックします。Mobの日本語名を `messages.yml` の `mob-names` で追加・変更することもできます。

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

Mavenがインストールされ、`mvn` がPATHに登録されているWindows 10環境では、プロジェクトフォルダの `build.bat` をダブルクリックしてください。コマンドプロンプトから実行する場合は次のとおりです。

```bat
cd /d C:\Users\USER\Documents\Plugin\BodyGuard
build.bat
```

`Maven was not found` と表示される場合は、Apache Mavenをインストールして新しいコマンドプロンプトを開き、次で確認してください。

```bat
mvn -version
```

PATHを変更したくない場合は、Mavenを展開したフォルダを `MAVEN_HOME` に設定してください。例えばMavenを `C:\Tools\apache-maven` に展開した場合は、コマンドプロンプトで次を実行してから `build.bat` を実行します。

```bat
set MAVEN_HOME=C:\Tools\apache-maven
build.bat
```

同じ処理をMavenコマンドで直接実行する場合は次のとおりです。

```bat
mvn clean package
```

完成したJarは `target/BodyGuard.jar` です。

## 初心者向け動作確認

サーバーを起動し、OPまたは必要な権限を持つプレイヤーで次の「操作」と「期待する結果」を確認してください。GUIの確認では、設定を変更した場合は「更新」を押してから確認します。

1. **操作:** 護衛0体で `/bg` を実行する。<br>
   **期待する結果:** 空白だけの画面ではなく、召喚方法の案内と「護衛数: 0/上限」が表示される。
2. **操作:** 一覧の「召喚」からMobをクリックする。<br>
   **期待する結果:** 許可されたMobだけが表示され、召喚成功メッセージの後に護衛数が増える。
3. **操作:** 一覧で護衛アイコンを確認する。<br>
   **期待する結果:** カスタム名、Mobの日本語名、現在HP/最大HP、モード、距離または状態が説明欄に表示される。
4. **操作:** 一覧で選んだ護衛を開き、「追従」「待機」「警備」を順にクリックする。<br>
   **期待する結果:** 選択した護衛だけのモードが切り替わり、現在のモードが文字と緑色の表示で分かる。
5. **操作:** 詳細画面から解除を選び、確認画面で「キャンセル」、次にもう一度開いて「解除する」を押す。<br>
   **期待する結果:** キャンセルまたは画面を閉じた場合は解除されず、確定した場合だけ通常Mobに戻る。解除できた件数も表示される。
6. **操作:** 護衛数を設定上限まで増やし、召喚画面を開く。<br>
   **期待する結果:** 現在数/上限が表示され、Mobをクリックしても上限到達の理由が表示される。
7. **操作:** 護衛を別ワールドへ移動する、または対象チャンクを未読み込みにして一覧を更新する。<br>
   **期待する結果:** 別ワールドは「別ワールド」、未読み込みは「未読み込み」と表示され、存在しないHPや距離が推測表示されない。
8. **操作:** `bodyguard.summon`、`bodyguard.mode`、`bodyguard.release` などを持たないテスト権限で各ボタンを押す。<br>
   **期待する結果:** 権限不足として拒否される。ボタンが表示されていても操作できない。
9. **操作:** GUI上でアイテムをShiftクリック、数字キー、ダブルクリック、ドラッグする。<br>
   **期待する結果:** GUIのアイコンをプレイヤーインベントリへ持ち出せず、アイテムの持ち込み・複製も起きない。
10. **操作:** `/bg help`、`/bg list`、`/bg summon zombie`、`/bg mode follow`、`/bg heal`、`/bg releaseall confirm` など既存コマンドを実行する。<br>
    **期待する結果:** GUI追加前と同じ仕様で利用でき、`/bg help` にはGUIの開き方も表示される。

この改良では自動テスト、サーバー起動による動作テスト、Mavenビルドは実行していません。上記の確認はサーバー管理者が実施してください。

## 既知の制限

- プラグイン停止中やチャンクが未読み込みのEntityを、Spigot APIだけで毎回強制ロードすることは避けています。通常はEntity自身のPDCと `guards.yml` で復元されます。
- オーナーがログアウトしたときの挙動は `owner-offline.freeze-guards` で設定します。
- Mobごとの特殊AIをNMSで改造してはいないため、Minecraft本体や他プラグインのAI変更によって追従感が変わる場合があります。
