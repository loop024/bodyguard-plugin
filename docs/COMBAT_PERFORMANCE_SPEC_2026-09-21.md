# BodyGuard 大項目1・2 実装仕様書

作成日: 2026-09-21。対象: `C:\Users\USER\Documents\Plugin\BodyGuard`。

この文書は実装担当者向けの決定済み作業仕様。実装完了報告ではない。利用者の指定は「1. 護衛の動きと戦闘」「2. 処理負荷と安定性」の全項目である。初期値など利用者が個別指定していない部分は、本計画の設計判断として定めた。新しい利用者指示があればそちらを優先する。

関連文書:
- [計画・進捗の入口](COMBAT_PERFORMANCE_PLAN_2026-09-21.md)
- [Solへの引き継ぎと実装順序](SOL_IMPLEMENTATION_HANDOFF_2026-09-21.md)
- [利用者の手動確認手順](COMBAT_PERFORMANCE_MANUAL_2026-09-21.md)
- [既存の自動復帰と状態案内](MOVEMENT_STATUS_PLAN_2026-09-21.md)

## 0. 作業ルールと完了の意味

1. Java 21 / Spigot 1.21.8 / Windows 10が対象。PythonやXAMPPを導入する作業ではない。
2. 担当AIはテスト、Mavenビルド、サーバー起動、実機確認を実行しない。利用者が行う。ソース読解・参照検索・差分確認は行う。
3. NMS、CraftBukkit内部実装、Paper専用API、外部必須プラグインを追加しない。既存の通常Mob AIを利用する。
4. コード上の実装完了と動作確認済みを分ける。「完璧」「全テスト合格」など未確認の保証はしない。
5. 12項目すべてを要求IDで管理する。実装の接続・保存復旧・画面表示・説明まで揃って初めて「実装済み・利用者確認待ち」とする。
6. 作業中のコンテキスト切替前・各段階終了時に入口文書を更新し、次に変更するメソッドまで残す。
7. 利用者の依頼が「計画書の作成」の間は実装を再開しない。引き継ぎ後の実装指示で再開する。

## 1. 要求一覧

| ID | 要求 | 到達する状態 |
|---|---|---|
| M1 | 引っかかりからの自動復帰 | 既存の復帰を保持し、新しい巡回・配置と競合しない |
| M2 | 集合時の配置分散 | 一括・単体呼び戻し、非戦闘追従で安定した別々の配置を使う |
| M3 | 戦闘方針 | 反撃・支援・迎撃・非戦闘を移動モードとは別に選択できる |
| M4 | 追いかけすぎの防止 | 戦闘時間・保護中心からの距離・視認喪失で撤退し、即再突入しない |
| M5 | 遠距離型の立ち位置 | 対応Mobと装備を確認して安全な短距離後退を補助する |
| M6 | 巡回警備 | 同一ワールドの2～16地点を順番に巡回する |
| P1 | 管理処理量の制限 | 候補収集・整合・未発見確認・掃除を分割して進める |
| P2 | 処理時間の制限 | 協調的な時間予算を持ち、区切りで次周期へ繰り越す |
| P3 | 保存負荷の削減 | 通常観測の保存要求を集約し、重要操作の順序・即時保存を維持 |
| P4 | 優先度付き更新 | 戦闘中・移動中を優先し、遠方待機にも順番が必ず回る |
| P5 | 診断値 | 直近・平均・最大、処理待ち、更新間隔、保存待ちを区別して確認できる |
| P6 | 管理者向け負荷制限 | 全体上限、警告値、OPの個人上限免除を設定できる |

要求外: 成長・レベル・経済課金・新Mob追加・部隊・大規模GUI再設計・完全な経路探索・他保護プラグインとの新規連携・保存方式のDB移行。

## 2. 現在地と最初に直す問題

2026-09-21の計画作成時のHEADは `1c74009`（環境によるauto-backup）。`git status`が空でも、途中のコードはコミット内に入っている。差分だけを見て「未実装の変更なし」と判断しない。

| ファイル | 現在の内容 | 判断 |
|---|---|---|
| `guard/GuardMovementRecovery.java`、GuardTaskの5箇所 | 前回の自動復帰を接続済み | 実機未確認。保持する |
| `guard/GuardStatusGuidance.java` | GUI・listの共通案内、日本語の状態表示 | 実機未確認。保持する |
| `guard/CombatPolicy.java` | LEGACY/RETALIATE/ASSIST/INTERCEPT/PASSIVE | 列挙型のみ。攻撃許可へ未接続 |
| `guard/GuardTactics.java` | 不変設定、YAML read/write、未使用encode/decode | 保存へ一部接続。操作・巡回未接続 |
| `guard/GuardData.java` | tactics、巡回番号、追跡タイマー、AI時刻 | 定期処理やイベントへ未接続 |
| `storage/GuardStorage.java` | tactics読込・書込、巡回の組合せ検証 | 再構成経路まで完成していない |
| `storage/StorageSchema.java` | GUARDSを6→7へ変更済み | 途中段階。旧形式読込は維持する |
| `guard/GuardFormation.java` | 安定スロット案、safePoint | 生成・使用箇所なし。下記欠点を修正してから接続 |
| `BodyGuard.java`、`config.yml` | 新設定の検証・getter・既定値 | getter追加だけで機能が有効になったと思わない |
| GuardManager、Command、GUI、Combat/TargetListener | 大項目1・2の主要接続先 | 前回状態案内以外は未接続 |

### 2.1 必須修正リスト

- **T01: 設定消失。** `GuardManager.trackLoadedEntity()`は既存データから新しいGuardDataを作るが、tacticsをコピーしていない。チャンク読み込み等で新設定がLEGACYへ戻り得る。名前・モード・世代・お気に入り・保護設定と合わせて新しい保存項目を保持する。
- **T02: 巡回とモードの矛盾。** 保存は「patrol=trueならGUARDかつOWNER」を要求する。`setMode()`、`setProtection()`、呼び戻しを完成させずに巡回を公開すると、保存後の再起動で隔離され得る。
- **T03: 配置点の重複。** 現在のGuardFormationは8方向×環、半径12で頭打ち。同じ方向の外側の複数環が同座標になる。有限領域で収容不能な場合は明示的に空き不足とし、重複座標を「割当成功」にしない。
- **T04: 体格の混在。** 現在は各Mobの幅で半径を別計算しているため、小型と大型で環が一致し得る。共通の配置格子＋実体の安全判定へ修正する。
- **T05: 無効時の挙動。** formation.enabled=falseでは現在のsafePointを通さず、従来の探索・追従へ戻す。名前だけ無効で移動先が変わる実装をしない。
- **T06: 戦闘時計のリセット。** `GuardData.clearCombat()`は新しい追跡時計も消す。短い命令更新や同じ敵の再設定で無期限追跡にならないよう、実際の戦闘終了と単なる命令更新を分離する。
- **T07: 時間基準。** 新しい追跡時計は現状System.currentTimeMillis案。システム時刻変更に影響されないmonotonic tickまたはnanoTimeへ統一する。永続の実日時は従来どおりepoch millis。
- **T08: 上限表示未接続。** コマンド・GUIの複数箇所が直接player.isOp()を使用している。isOpSummonUnlimitedの追加だけでは実行・表示とも変わらない。
- **T09: 全件処理残存。** GuardTaskの全件コピー、updateManagedChunks、cleanup、handleEntitiesLoadの全レジストリ走査は残っている。
- **T10: 安全保存の非同期化禁止。** SafeYamlFileは失敗時にBukkitのオンラインプレイヤー通知を直接行う。現状のクラスをそのまま別スレッドから呼ばない。

## 3. 共通の不変条件

### 3.1 契約と所有権

- UUID＋contractGeneration＋owner UUIDを照合する。同名Mob、古いPDC、表示スロットの番号だけで操作しない。
- ACTIVEのみが通常護衛。MISSING・WORLD_UNAVAILABLE・QUARANTINEDでもACTIVEなら個人・全体の枠を数える。
- RELEASE_PENDING/DELETE_PENDING/RELEASED/DELETED/DEADは既存の契約数規則を維持。別途予約・履歴の件数へ表示する。
- 古いPDCだけから契約を復活・移譲しない。UNKNOWN_RESULTや隔離を勝手に解除しない。
- 「状態表示を開く」ことでチャンクを読み込まない。表示から保存・ターゲット選択を実行しない。

### 3.2 攻撃と移動

- 所有者、選択中の役職対象、仲間、同所有者護衛、PvP制限など既存の禁止判定を新方針で緩めない。
- 他プラグインがキャンセルしたダメージ・ターゲット・テレポートを、再設定で強制的に通さない。
- 安全な床・頭上空間・体格・ワールド境界・未読み込み・危険ブロック判定を維持する。
- `mob.teleport()`がfalseなら位置更新・成功件数・成功演出を付けない。成功時は実際のmob.getLocation()を記録する。
- 他プラグインが移動先を書き換える場合、完全な保証はしない。最終位置を実測し、次周期でモード制約を再確認する。無限再テレポートを避ける。
- 待機・警備・巡回の帰還目的地を所有者付近へ無断で置き換えない。

### 3.3 保存

- guards.ymlは7、players.ymlは4、operations.ymlは1。7より新しい形式を古い実装で上書きしない。
- 新フィールドのない護衛はLEGACY・巡回停止・地点なしとして読む。
- 新しいtacticsの正本は**guards.yml**。この版ではtacticsを新たにPDCへ二重保存しない。GuardTacticsの未使用encode/decodeと誤解を招くコメントは整理する。
- 既存のmode/anchor/protection等のPDCは維持し、レジストリ再構成時にもtacticsを保持する。
- 不正な巡回データは既存のquarantine保全手順へ。空ルートへ黙って置換して成功扱いしない。
- 位置・確認日時の集約と、契約操作の保存を混同しない。台帳の受理→Entity操作→完了記録の既存順序を変更しない。

## 4. M3 戦闘方針の詳細仕様

### 4.1 方針表

すべて既存の味方・PvP・ワールド・モード半径の制限を通過した場合に限る。

| 方針 | 護衛自身が被弾 | 所有者/選択対象が被弾 | 保護対象が敵を攻撃 | 自発的な敵索敵 | 通常AIが勝手に選ぶ敵 |
|---|---|---|---|---|---|
| LEGACY 従来どおり | 既存仕様 | 既存設定に従う | 既存設定に従う | 既存の警備のみ | 既存の禁止判定に従う |
| RETALIATE 反撃のみ | 反撃可 | 防衛設定が許可なら反撃 | 不可 | 不可 | 承認済み反撃対象のみ |
| ASSIST 攻撃を支援 | 反撃可 | 防衛設定が許可なら反撃 | 支援設定が許可なら可 | 不可 | 承認済み対象のみ |
| INTERCEPT 周辺迎撃 | 反撃可 | 防衛設定が許可なら反撃 | 支援設定が許可なら可 | 可 | 共通判定で承認した敵のみ |
| PASSIVE 戦闘しない | 攻撃しない | 攻撃しない | 攻撃しない | 不可 | 不可 |

「所有者/選択対象」はOWNERなら所有者、ROLEなら現在適格な選択対象。ROLE護衛が所有者の攻撃に便乗する既存の誤経路を新設しない。RETALIATEは護衛自身への攻撃にも対応するため、通常Mob AI任せではなく被弾イベントで承認する。

### 4.2 中央判定

`GuardCombatController`等に次を集約する。名称は変更可、責任分担は維持する。

```text
CombatIntent = SELF_DEFENSE | PROTECT_DEFENSE | ASSIST | INTERCEPT | VANILLA
canAcquire(data, mob, target, intent) -> allowed + reason
requestTarget(data, mob, target, intent) -> accepted
validateCurrentTarget(data, mob, protectionAnchor, tick) -> target or retreat
```

- Command/GUIは方針設定を変更し、攻撃判定を直接実装しない。
- CombatListenerは既存の味方ダメージ防止を実行後、意図を付けてcontrollerへ渡す。
- TargetListenerは**event.setTarget()**を使う。リスナー内でmob.setTarget()を再帰呼出ししない。
- 方針の許可判定は現在対象の検証、命令、自然AI取得、周辺索敵、実ダメージのすべてへ接続する。GUARDだけに実装しない。
- PASSIVEへ変更した後に命中する既存の矢・間接ダメージも、発射元が護衛と特定できる場合は防止する。EntityUtil.resolveDamageSourceとDamageSourceの既存経路を利用する。
- PASSIVEを理由に相手から護衛へのダメージまで無効にしない。
- キャンセル済みダメージは反撃開始の根拠にしない。同じ攻撃がByEntity/汎用イベントで二重命令にならないよう既存return条件を維持する。
- 適格対象がいない役職護衛、オフライン凍結、退役・隔離は方針に関係なく攻撃停止。

### 4.3 自発索敵の範囲

- INTERCEPTは既存のMonsterを基本対象とする。一般プレイヤー・中立動物を単に近いだけで攻撃しない。
- FOLLOWは保護対象中心、STAYは待機アンカー中心、GUARDは警備アンカー中心、巡回は現在の巡回地点中心。
- FOLLOW/STAYの半径はcombat.target-range、GUARD/巡回はmin(target-range, guard-mode.radius)。既存のSTAY防衛設定も尊重する。
- 戦闘中・撤退中に全候補索敵し直さない。同じ敵を保持できる間は保持する。
- 近傍取得は1回のAI更新に最大1回。複数候補の最短距離を走査中に求め、全候補のソートをしない。

## 5. M4 撤退の詳細仕様

### 5.1 打ち切り条件

combat.pursuit.enabled=trueのとき、次のいずれかで撤退する。

1. **同じ戦闘エピソード**がmax-seconds以上継続。
2. 護衛が保護中心からmax-protection-distanceを超える。敵自体が保護中心の外へ出た場合も追跡継続しない。
3. 最後に敵を視認してからunseen-seconds以上継続。hasLineOfSightは処理対象護衛の現在敵にだけ照会する。

既存のGUARD半径・帰還距離・異世界・味方化・敵死亡の終了条件は追加条件より先に有効。GUARDの実効追跡範囲は既存半径と新上限の小さい方。

### 5.2 時計と状態遷移

```text
IDLE → ENGAGED → RETREATING → IDLE
ENGAGED: episodeStart, lastVisible, activeTarget
RETREATING: notBefore, returnAnchor, reason
```

- 経過時間はサーバーtickへ統一する（10tick管理周期）。実時間が遅延する旨を文書化する。追跡中は高優先度。
- 同じ敵のsetTarget、支援命令の再送、敵A→Bへの短時間切替でepisodeStartをリセットしない。
- 真に非戦闘となったとき、対象資格が失われたとき、明示的にモード/方針を変えたときは時計を破棄する。
- lastVisibleは実際に視認したときだけ更新する。命令を受けたことを視認とみなさない。
- 撤退開始時にcombatTargetIdとMob targetを解除し、既存の戦闘猶予も終了させる。
- 撤退中は新規索敵、自然AIの再取得、支援・反撃命令を抑制する。最短retreat-secondsが過ぎ、保護中心の帰還許容距離へ戻った後に終了する。
- 撤退先が不在・危険なら停止して案内し、敵へ戻らない。戻れない間も追跡状態を無期限に保持しない。
- 復帰はFOLLOW→配置先、STAY→待機地点、GUARD→警備地点、巡回→現在巡回地点。役職対象が変わったら新しい適格対象へ安全に切り替える。
- 引っかかり復帰を使うのは戦闘解除後の帰還移動。戦闘中に二重の速度補助を掛けない。

## 6. M2 配置分散

### 6.1 対象とグループ

- 一括/単体呼び戻し、OWNER FOLLOW、ROLE FOLLOW、追従の遠距離復帰を対象にする。
- 自動追従は保護対象UUID、手動呼び戻しは操作所有者UUIDをグループとする。
- 同じ役職対象を守る別所有者の護衛も、同じ配置グループ内で割り当てる。所有者判定は別途維持する。
- STAYの定位置、GUARDの警備中心、巡回地点は配置グループで書き換えない。
- 役職護衛を所有者が手動で呼び戻しても、保護設定を変えず、次の自動追従では役職対象を守る。GUIにその旨を説明する。

### 6.2 割当アルゴリズム

- 固定8方向＋半径clamp案を廃止。整数格子の外周を順番にたどる一意な候補番号→(dx,dz)を使用する。
- 格子間隔はformation.spacing。Mobの幅を各個体の半径へ乗算しない。大型Mobは実体・予約領域の衝突判定で複数格子を占有する。
- 実行中は護衛ごとに安定した候補番号を保持。HP順の並べ替え、画面再描画、所有者の視線方向で再配置しない。
- 新規候補の探索は1回最大64候補、中心から水平12ブロック以内を標準上限とする。収容不能は失敗/待機を返す。有限領域に無制限の体数を詰め込めると保証しない。
- 候補で足場・高さ・Mob体積・他Entityとの衝突を確認。狭い場合は小さい半径の未使用候補を探索し、それでも無理なら移動しない。
- 一括操作中は予約済みBoundingBoxを持ち、先行個体の実体インデックス反映前でも同じ場所を選ばない。移動失敗時は予約を解放する。
- グループ単位の割当解放は死亡・解除・削除・保護対象変更・プラグイン停止時。チャンク一時未読込では直ちに再番号付けしない。
- 形成不能時の再探索には間隔を設け、毎tick64候補を全護衛で探索しない。既存の復帰探索上限と共通の重い移動予算を使う。

### 6.3 追従の距離

- 遠距離復帰の閾値判定は保護対象中心との距離を維持する。
- 通常追従の到着判定は割当地点との距離で行う。中心から5m以内というだけで全員が中心に集まらないようにする。
- 到着/再開にはヒステリシスを設ける（到着1.25m、再開2.0mを既定設計値）。所有者の小さな揺れで左右往復させない。
- 地面の高さが異なる場所では安全候補のYを利用する。崖越しの直線速度を新しく強化しない。
- `formation.enabled=false`は従来方式へ。配置枠の確保・予約はしない。

### 6.4 手動呼び戻しと巡回

- 既存手動呼び戻しはFOLLOW以外のアンカーも移動先へ更新する。この既存仕様は通常のSTAY/GUARDで維持する。
- 巡回中の手動呼び戻しは**巡回を停止し、地点リストは残し、移動先でGUARD**にする。巡回経路を暗黙で書き換えたり、直後に遠方へ走り出したりしない。
- 移動と設定保存は別結果。移動済みなのに保存失敗で移動件数を0へ戻さない。

## 7. M5 遠距離型の立ち位置

- 対応をSkeleton/Stray/Bogged＋弓、Pillager＋クロスボウに限定して開始する。種類と実際の手持ち装備を両方確認する。Snow Golem等はこの版の補助対象外。
- 対象装備が外された/壊れた場合はそのAI更新から補助停止。装備を自動付与しない。
- 現在の合法な敵がいて、minimum-distance未満に接近されたときだけ短い後退を試す。距離が十分なら通常の射撃AIへ任せる。
- 補助移動量は1回0.5～1ブロック程度、速度は通常追従以下。真後ろ→左右斜めの最大3候補を探す。
- 足場と経路上の体積を確認し、落下、水、溶岩、壁、他Entity、警備半径外への後退を避ける。安全候補なしならその場で通常AIへ任せる。
- retreat状態・PASSIVE・敵不在・別ワールド・対象不適格のときは補助しない。
- Awarenessやtargetを後退のたびに切り替えず、クロスボウの準備や射撃を阻害しない。実機確認で射撃不能になる場合は補助を短くする。
- 「射線を必ず確保」「通常AIに勝つ完全な距離維持」は保証しない。GUI特徴説明も補助であると記す。

## 8. M6 巡回警備

### 8.1 永続データ

既存の途中実装に合わせ、`guards.<uuid>.tactics`へ保存する。

```yaml
tactics:
  policy: LEGACY
  patrol: true
  points:
    '0':
      world: world
      world-uuid: '<実際のワールドUUID>'
      x: 10.5
      y: 64.0
      z: 20.5
      yaw: 0.0
      pitch: 0.0
    '1':
      world: world
      world-uuid: '<同じワールドUUID>'
      x: 20.5
      y: 64.0
      z: 20.5
      yaw: 0.0
      pitch: 0.0
```

- 地点数2～16、すべて同一の実在ワールドUUID、有限座標、番号0から連続。ワールド名一致だけで別UUIDへ接続しない。
- 保存を読む時点でワールド未読込でも地点を保持し、実行は保留する。UUIDがない地点は新規巡回として受け付けない。
- 連続する地点が1ブロック未満なら追加を拒否。全地点同じ場所で高速周回する設定を防ぐ。
- 現在の巡回番号は一時状態。再起動後は地点1から再開すると明記する。既に到着していれば次へ進める。
- 設定変更でpolicyだけを変えた場合、巡回番号まで不必要に0へ戻さない。現在のsetTacticsの一律リセットを分ける。

### 8.2 動作

- `patrol=true && mode=GUARD && protection=OWNER`のみ有効。
- 各地点へ非戦闘移動し、1.5m以内へ到着したら20tick待って次へ。最後は先頭へ戻る。1更新で複数地点を進めない。
- 実行中のアンカーは現在地点。保存された地点リストは変更しない。戦闘半径・撤退距離はこのアンカー基準。
- 戦闘中は巡回番号を進めない。終了後は同じ地点への帰還・巡回を再開する。
- 自動復帰は現在地点付近の安全範囲へ。安全先なしは番号を進めず保留し、理由を表示する。
- 未読み込みの遠い経路を巡回のために一括ロードしない。既存の管理上限内の読み込みだけを利用し、目的地を確認できないなら保留する。
- 地点間が離れていてもワールドを強制ロードしない。保留時はプレイヤーに現地確認を案内する。読み込み不要の区間だけを通常移動する。
- stopは地点リストを保持し、その護衛の現在位置を固定GUARD地点にする。clearは巡回停止＋地点リスト削除。FOLLOW/STAYへ切替時も巡回停止＋地点保持。
- ROLEへ切替時は巡回停止＋地点保持。OWNERへ戻しても勝手に巡回を再開しない。

### 8.3 編集対象の指定

遠い地点を登録する際に毎回護衛を見る方式だけでは使えないため、次のコマンドを正式仕様とする。

| コマンド | 対象・動作 |
|---|---|
| `/bg policy <legacy|retaliate|assist|intercept|passive> [護衛UUID]` | UUID省略時は見ている自分の護衛 |
| `/bg patrol <add|remove|start|stop|clear|list> [護衛UUID]` | 同上。addは実行者の足元を追加、removeは末尾1点を削除 |

- UUID指定を許すのは自分のACTIVE契約のみ。UUIDを知っていても他人の護衛を操作できない。
- listは未読み込みでも利用可。変更は読み込み済みの自分の護衛のみ。編集対象が遠くても管理チャンクで読み込み済みなら利用可。
- `/bg list`の詳細/クリック可能な案内からUUIDを取得できるようにし、Tab補完は自分の護衛UUIDを返す。全所有者のIDを列挙しない。
- 読み込み済みの対象を現在地へ呼ぶ必要はない。未読み込みなら変更を保留せず失敗理由を表示する。
- removeで2地点未満になったら巡回停止。clear/remove/start/stopは保存成功後に成功通知。変更なしはNO_CHANGE。

## 9. 操作サービス・GUI・権限

### 9.1 中央の変更サービス

GuardManagerに薄い入口を置き、必要ならGuardTacticsServiceへ委譲する。

```text
changePolicy(actor, guardUuid, policy) -> TacticsResult
editPatrol(actor, guardUuid, action, actorLocation) -> TacticsResult
TacticsResult: SUCCESS | NO_CHANGE | NOT_OWNER | NOT_FOUND | NOT_LOADED
              | NO_PERMISSION | INVALID_ROUTE | ROLE_CONFLICT | SAVE_FAILED
```

- 呼出し時点で権限、所有者、世代、ACTIVE、隔離、Entity一致、設定を再確認する。
- 新設定を完全に検証してから状態を変更する。無効入力で戦闘だけ解除される等の部分変更を避ける。
- rollback用にtactics、mode、anchor、必要なPDC対象フィールドを保存する。メモリ変更→必要PDC反映→即時保存→成功という既存設定変更方式と整合させる。
- 保存失敗時はメモリとPDCを復元して失敗を返す。UNKNOWN_RESULTは保存層の書込停止を保持し、成功扱い・自動再上書きしない。
- mode/protection/recallと巡回停止を同じ変更の単位として扱う。先にmodeだけ保存して矛盾した状態をディスクへ出さない。
- Entity自体の移動はrollbackできない場合があるため、RecallResultの「移動済み・保存未確認」を維持する。

### 9.2 GUIの割当

既存45スロット詳細画面を維持する。既存の7=保護、28=お気に入り、31=解除、34=相棒は変えない。

- 新規スロット1: 戦闘方針（IRON_SWORD等）。左クリックで次の方針。現在値と1～2行の意味を表示する。
- 新規スロット37: 巡回状態（MAP等）。左クリックで開始/停止。地点が不足ならコマンド案内を表示する。
- 新規スロット38: 巡回地点情報（PAPER等）。地点一覧・UUID・追加/削除コマンドをチャットへ表示する読み取り操作。
- GUIからclearをワンクリックで実行させない。地点編集はコマンドで明示する。
- リフレッシュでUUID・スロットを維持し、護衛の変更・死亡後も別護衛へ操作が流れないようにする。
- GUIに長文を詰めない。方針・巡回番号・撤退理由の短文を出し、詳細はlist/コマンドへ。
- 今のDETAILクリック処理はClickTypeを渡していない。新規操作は左クリックだけを許可し、必要な部分へClickTypeを渡す。既存クリック操作を一律で変えない。

### 9.3 権限と翻訳

- `bodyguard.policy`と`bodyguard.patrol`をplugin.ymlへ追加（default:true、既存bodyguard.adminによる利用を維持）。
- GUI表示・コマンド・実行サービスで同じ権限を使う。bodyguard.useの入口も維持。
- help・Tab補完・messages.yml・READMEを更新する。既存messagesにキーがなくても日本語fallbackを使う。
- 数字キー、ドロップ、持ち替え、ドラッグから新規操作を実行しない。

## 10. P1/P2/P4 定期処理の設計

### 10.1 最低限の分割

```text
GuardTask                 : 10tick周期の統括だけ
GuardUpdateScheduler      : 次回AI時刻・優先順・公平性
GuardMaintenance          : チャンク計画・整合・未発見確認・掃除の継続位置
GuardPerformanceMetrics   : 数値集約。診断のために毎周期全護衛を走査しない
```

ファイル名は提案。既存GuardManagerの契約管理まで一度に全面改築する必要はない。新しい長大クラスへ単に移すだけで完了にしない。

### 10.2 AIキュー

- 毎周期`new ArrayList<>(getAllGuardData())`を作らない。
- 推奨は1護衛1エントリーのTreeSet等（dueTick, priority, stableSequence, UUID, contractGeneration）とUUID→entryの索引。
- エントリー更新は旧entryをremoveして新entryをadd。PriorityQueueへ毎回追加するだけで古いentryを無制限に溜めない。
- 登録・ロード・再構成・解除・削除・死亡・隔離・再契約を中央のinsert/replace/removeフックへ接続する。起動時の1回の構築は許可。
- queued GuardData参照を真実にしない。実行直前に現レジストリのUUID/世代を確認。再構成でオブジェクトが変わっても古い設定へ戻らない。
- 更新後のdueTickは「現在tick＋間隔」。古いdueTickに間隔を足し続けて高優先が過去に残る構造にしない。
- 最も古いdueTickを先に取り、priorityは同期限で優先する。期限の過ぎた低優先護衛も高優先の再投入より先に進む。
- 戦闘/命令/被弾/対象資格変化/モード変更は次周期へ前倒しする。キュー移動自体も重複させない。

| 状態 | 標準の更新間隔 |
|---|---|
| 戦闘、撤退、引っかかり復帰、移動中の追従/巡回 | 10tick |
| 同ワールドの所有者/対象から32m以内 | 10tick |
| 遠方で静止するSTAY/GUARD | performance.idle-interval-ticks（既定40） |
| 未読み込み・オフライン凍結 | 維持・観測側へ。AIキューで重い探索を繰り返さない |

- classificationは当該護衛処理時または関連イベントで更新。距離分類のための全件先行走査は禁止。
- guard countの上限は「更新した件数」、無効エントリー除去などの軽作業にも別のexamined上限を設ける。スキップを数えず無限ループしない。
- 定期AIから外れた護衛が再読み込み/再開時に再登録されることを必ず確認する。

### 10.3 時間予算

- 1周期の目安はcycle-budget-ms（既定5ms）。開始と経過の計測はSystem.nanoTime。
- AI件数上限100、管理レコード上限64、重い復帰探索上限2、チャンク新規ロード上限2を併用する。
- AIと管理の双方へ時間枠を配分する（既定半分ずつ、余りは他方へ）。開始順は周期ごとに交代し、一方が恒常的に先取りしない。
- AIの1護衛処理、管理の1レコード/1候補処理の境界で時間を確認。途中でEntity操作や台帳処理を中断しない。
- 単一のチャンクロード・YAML保存・Bukkitコールバックはこの時間内に完了する保証がない。超過を診断し、残りを次へ送る。
- 周期末の保存を含めた実際の全体時間も計測し、予算対象の仕事だけ測って「5ms以内」と見せない。

### 10.4 チャンク計画の段階化

現在のupdateManagedChunksは毎周期全護衛を分類・候補化している。以下の状態機械へ分割する。

```text
COLLECT（対象契約・所有者枠）
→ RETAIN（有効な既存ticket優先）
→ SELECT（空き枠へ候補を割当）
→ RELEASE（不要ticket解放）
→ LOAD（上限内で取得）
→ COMPLETE（次の計画へ）
```

- 各段階にcursor/iteratorとremainingを持たせる。段階終了処理で全候補のcopy/sortをして負荷を戻さない。
- COLLECT用の登録順/巡回キューを維持。走査開始時の有限件数を対象にし、追加が続いても永遠にCOLLECTが終わらない構造にしない。
- 同所有者が既に使っているチャンクを再要求しても個人枠を追加消費しない。
- 前回の有効ticketを優先する既存方針を維持。満杯で新規候補が待つことと、更新スケジューラの不公平を混同しない。
- 計画中は旧ticketを維持。候補未走査というだけで未使用と判断して解放しない。
- RELEASE/LOAD前に所有者オンライン、契約ACTIVE、現在位置、ワールド、設定上限を再検証する。古い計画から不要なロードを起こさない。
- reloadで計画revisionを更新。旧計画を無効化し、新上限超過分は段階的に解放する。停止時は全解放。
- 読み込み時の同期イベントは索引更新・軽い通知または重複排除キューへの投入に留め、管理計画を再帰実行しない。
- 表示する上限待ち理由は実際の計画結果とrevisionを持つ。未走査は「確認中」であり上限と断定しない。

### 10.5 整合・ロードイベント・掃除

- cleanupも継続位置を持ち、1周期の管理枠を使う。退役・隔離・完了履歴を毎回全件コピーしない。
- last-positionのチャンク→護衛UUIDの索引を持つ。移動・登録・再構成・保存位置変更・退役の更新経路を中央へ寄せる。
- handleEntitiesLoadの「このチャンクにいるはずの護衛」は索引から引く。全レジストリを走査しない。
- EntitiesLoadEventのempty collectionは未発見判断に使う証拠。ChunkLoadEventだけで未発見としない。
- イベント由来の未発見確認を遅延実行するなら、world UUID/chunk/読み込みepoch/対象の観測revisionを保存する。アンロード・移動・後からの発見があれば古い未発見証拠を捨てる。
- Entityリストの取り込みはイベントが提供する範囲内の不可分処理として計測する。大きいイベント配列の重いtrack処理はUUIDキューに分離し、処理時に現在Entityを再取得する。
- 作業キューはUUIDやchunk keyで重複排除し、世代変更・退役時に古いジョブを無効化する。削除台帳は作業キュー整理で消さない。
- 役職対象選択の全オンラインプレイヤー探索も負荷源。候補一覧を役職/周期単位で共有し、対象資格の再確認は各利用時に行う。権限変更を永久キャッシュしない。
- 起動時の既存全チャンク走査は実際の対象と件数を記録する。可能ならworld/entityの復旧を段階化するが、初期ロード完了前に契約が消えたと判断しない。

## 11. P3 保存負荷の削減

### 11.1 この版で実装する範囲

この版は**同期保存を維持し、通常観測の要求をまとめて書く**。可変データやSafeYamlFileを無造作に非同期化しない。非同期化・DB移行は必須条件ではない。

| 保存の原因 | 方針 |
|---|---|
| 新規召喚・勧誘、戦闘方針・巡回・mode/protect/name/favorite等の明示設定 | 成功通知の前に即時保存。既存失敗処理を維持 |
| 解除・削除・死亡の台帳 | 既存の即時受理/完了順序を維持 |
| 手動呼び戻し | 実移動件数を維持し、最後に1回保存、保存結果を別表示 |
| 通常の位置同期・最終確認日時 | dirty、通常autosaveの対象 |
| チャンク/Entity読込による通常復旧・観測 | 保存要求を集約、最短minimum-auto-save-seconds間隔 |
| 保存失敗の再試行 | autosave無効でも独立に継続。READ_ONLY/UNKNOWN_RESULTは安全層の規則に従う |
| 正常停止 | 残件を強制保存、結果をログへ |

### 11.2 実装する状態

- `dirtyRevision`（変更ごと）、`lastSavedRevision`、`eventSaveRequested`、`lastSaveAttempt`、`lastSaveSuccess`、`pendingSince`を別々に持つ。
- dirty=trueだから毎周期saveするのではない。event要求、autosave期限、失敗再試行期限を判別する。
- `autosave-seconds:0`は通常位置の定期保存を無効にする。明示操作、従来イベントから要求された保存、失敗再試行、正常停止は残す。
- clean時はguardsのYAMLを組み立てない。players等の独立した未保存状態はそれぞれ必要時に扱う。
- handleChunkLoad/handleEntitiesLoadの通常観測save()をrequestへ置換する一方、そこから呼ばれた解除・削除finalizeの台帳saveは置換しない。
- lastSeenだけを更新するたび即時保存を誘発しない。最終確認日時の記録粒度と自動保存の粒度を分ける。
- 一括操作の内側で全レジストリ保存を繰り返さない。ただし台帳による個別受理を省略する最適化は行わない。
- YAMLの一時ファイル→検証→正常な旧ファイルのbackup→置換→読戻しの既存安全性を維持する。
- 「保存要求済み」を「保存成功」と表示しない。平均保存時間には実際の書き込み試行のみ含め、成功/失敗件数は別にする。
- 同期のままなので大きなファイルの単発保存遅延は残る。この制約を診断値とREADMEへ記す。

### 11.3 原子的操作の注意

現在のacceptOperationは台帳受理に成功すれば、レジストリ保存を確認できなくても予約を保持し、台帳で再起動復元する設計。この分岐を「保存falseなので予約を取り消す」へ変えない。死亡・削除・解除の再実行耐性は既存の契約世代と台帳で守る。

## 12. P5 診断値

`/bg status server`と管理GUIに同じMetricsSnapshotを利用する。権限のないプレイヤーへ他所有者の情報を出さない。

| 値 | 定義 |
|---|---|
| 周期: 直近/平均/最大ms | 最後の周期、起動後の全周期平均、起動後最大。保存を含む実時間 |
| AI/管理/保存ms | 直近の内訳。未測定は記録なし |
| 今周期更新数/上限 | 実際にAIを実行した数 |
| 次回AI登録数 | 生きたキューエントリー数。履歴や古い重複を含めない |
| 最古の期限超過tick | 先頭dueTickと現在tickの差。0と記録なしを区別 |
| 最大観測更新間隔ms | 実際に同じ護衛を2回更新した間隔の起動後最大。未更新の護衛はこの値で保証しない |
| 管理処理残件/段階 | plan段階、未処理の件数または「収集中」。未確定総数を正確な数と断定しない |
| 予算超過回数 | 周期の実測が予算を超えた累積回数 |
| 保存: 直近/平均/最大ms | 保存試行の実測。成功数と失敗数を別表示 |
| 保存待ち | 変更revision差、最古の要求からの経過、最終成功日時、最後の失敗理由 |
| 護衛/上限/警告値 | ACTIVE数、0上限は無制限、予約/履歴は別欄 |
| 復帰/撤退 | 今周期の復帰探索数、撤退中など。全件走査して毎周期再集計しない |

- メトリクス追加のために全件streamやsortを復活させない。
- 平均は累積値/件数または安全な逐次平均。直近1件を平均と表示しない。restartで初期化、reloadでは保持。
- 数値表示のmsは小数2桁程度、日時は既存の日本語日時形式。YAML永続化は不要。

## 13. P6 新規契約の制限

### 13.1 適用表

| 操作 | 個人上限 | 全体上限 |
|---|---|---|
| 通常プレイヤー召喚 | 適用 | 適用 |
| OP召喚、op-unlimited-summon=true | 免除 | 適用 |
| OP召喚、同false | 適用 | 適用 |
| 勧誘（OPを含む） | 適用 | 適用 |
| 再起動復元・チャンク復旧 | 新規上限で拒否しない | 新規上限で拒否しない |

- 全体上限0は無制限。上限を既存数より下げても既存護衛を削除・解除しない。増加だけ止める。
- ACTIVEの所在不明等も数える。予約を含めるかは個人枠と一致させる（本版では既存規則通り予約は通常枠から除外）。
- 召喚前の表示だけでなく、登録直前に中央サービスで再確認。summon/recruitの出所を明示するRegistrationSourceを導入する。
- registerGuardを無条件に新しい個人上限で囲って、既存復旧まで失敗させない。
- 召喚でEntityを作った後に登録失敗した場合は今回作ったEntityだけを削除する。勧誘失敗で元の野生Mobを消さない。
- 登録が成功/失敗/rollbackするたび、ACTIVE数索引を正しく更新する。

### 13.2 表示と警告

- GUI概要、召喚候補、召喚可能数、エラー理由、summonLimitLabel、コマンドを共通のAdmissionResultへ接続する。
- OPの「無制限」は個人枠についての表示。全体残数が有限なら別に表示する。
- warning-guards-server=0は警告なし。既定200。
- 閾値未満→以上で管理者とコンソールへ1回通知。継続中は60秒以上の間隔を空け、毎召喚/毎周期に鳴らさない。
- 閾値未満へ戻ったら再武装。reloadで閾値を変えた場合も1回だけ現在状況を再評価する。
- 警告値が上限以上でも構文エラーにはせず、到達しにくい設定として説明する。勝手に値を修正しない。

## 14. 設定契約

以下は途中コードに存在するキー。値を変えるなら設定・getter・検証・READMEを同時更新する。

| キー | 既定値 | 許容範囲/意味 |
|---|---|---|
| formation.enabled | true | falseで従来配置 |
| formation.spacing | 2.0 | 1.0～6.0 |
| combat.pursuit.enabled | true | 撤退制限の追加 |
| combat.pursuit.max-seconds | 30 | 5～300 |
| combat.pursuit.unseen-seconds | 5 | 2～60 |
| combat.pursuit.max-protection-distance | 24.0 | 3.0～128.0 |
| combat.pursuit.retreat-seconds | 5 | 2～60 |
| combat.ranged-spacing.enabled | true | 遠距離補助 |
| combat.ranged-spacing.minimum-distance | 5.0 | 2.0～12.0 |
| performance.max-guards-per-cycle | 100 | 10～1000（既存） |
| performance.management-records-per-cycle | 64 | 1～1000 |
| performance.cycle-budget-ms | 5.0 | 1.0～50.0 |
| performance.idle-interval-ticks | 40 | 10～200 |
| storage.minimum-auto-save-seconds | 10 | 5～300 |
| limits.max-guards-server | 0 | 0～100000、0は無制限 |
| limits.warning-guards-server | 200 | 0～100000、0は通知なし |
| limits.op-unlimited-summon | true | OPの召喚だけ個人枠を免除 |

- 追跡秒数は最終的にtick換算。現getPursuitMaxMillis等は改名・接続して文書と一致させる。
- LEGACYは「標的取得方針の互換」。formation/pursuit等の新設定がtrueなら移動や撤退は改善される。全挙動不変と説明しない。
- configとmessagesの候補を両方検証して一括反映する既存reloadを保持する。不正値なら旧値を使い続ける。
- 新キーなしの既存ファイルにも既定値が効く。ファイル丸ごと上書きしない。
- reload時: 配置キャッシュを安全に再評価、管理planを無効化、次回間隔を再計算、無効にした補助の一時状態を解除。戦闘方針やルート自体は設定reloadで消さない。

## 15. 変更箇所の対応表

Javaパスの基点は `src/main/java/plugin/test/com/bodyGuard/`。行番号は実装で変わるのでメソッド名で検索する。

| ファイル/メソッド | 必要な接続 |
|---|---|
| BodyGuard.onEnable/onDisable/reloadSettings | サービス生成、キュー初期化、停止処理、reload通知 |
| GuardData、GuardTactics、CombatPolicy | 設定と一時状態の分離、時計・変更snapshot |
| GuardStorage.readState/saveWithResult | schema7の旧形式互換、新項目の検証・保存 |
| GuardManager.load/registerGuard/trackLoadedEntity | 設定保持、登録元、索引・世代・カウントの整合 |
| GuardManager.setMode/setProtection/teleportGuard | 巡回停止の一体化、配置、安全移動と保存結果 |
| GuardManager.assignCombatTarget/getCombatTarget/isForbiddenTarget | 方針・意図・撤退の中央判定 |
| GuardManager.commandGuardsToTarget/commandRoleGuardsToTarget | 所有者・役職防衛と方針の接続 |
| GuardTask.tickFollow/tickRoleFollow/tickStay/tickGuard/tickRoleGuard | 配置・巡回・撤退・射手補助・復帰の優先順 |
| GuardTask.run | 全件コピー除去、予算、スケジューラ、管理、保存要求、診断 |
| GuardManager.updateManagedChunks/cleanup | 段階実行と継続位置 |
| GuardManager.handleChunkLoad/handleEntitiesLoad/handleChunkUnload | UUID/チャンク索引、遅延観測の正確さ、保存要求 |
| PlayerListenerの接続イベント | キュー前倒し・再開・ロードepoch |
| CombatListener/TargetListener | 方針の全経路、PASSIVEの発射済み攻撃、キャンセル尊重 |
| GuardFormation/LocationUtil/GuardMovementRecovery | 重複しない配置、安全性、検索予算、巡回帰還 |
| GuardStatusGuidance | 方針、巡回番号、撤退/復帰待ちの短文 |
| BodyGuardCommand/BodyGuardTabCompleter | policy/patrol、UUID補完、ヘルプ、上限の共通判定 |
| BodyGuardGui.renderDetail/handleDetailClick/summonIcon等 | スロット1/37/38、権限、上限、結果表示 |
| GuardManager.diagnosticsと管理GUI | メトリクス共通化、過度な診断走査を避ける |
| plugin.yml/config.yml/messages.yml/README | コマンド案内、権限、設定、制約、保存形式 |

## 16. 静的確認と完了条件

担当AIが行うのはソースと差分の確認。実行テストの代替と称さない。

- 新規メソッド/設定getterに実際の呼出し元がある。未接続クラスを完成に数えない。
- enumを追加した全switch、import、コンストラクタ、record引数、GUI画面分岐、YAMLキーの参照を確認する。
- `plugin.test.com...`を式中で直接書くと、フィールド名pluginがパッケージ名を隠す場合がある。通常のimportを使う。
- `guards.put/remove/clear`、GuardData再構成、契約状態更新、位置変更の全箇所を検索し、索引・キュー・配置枠を対応させる。
- 全件stream/toList/new ArrayListの残る周期経路を一覧化し、必要性と上限を記録する。
- policyの自然取得、命令、防衛、間接ダメージ、ROLE、PASSIVEの全経路を追う。
- 巡回→FOLLOW/STAY/ROLE/recall、SAVE_FAILED/UNKNOWN_RESULT、再ロードの組合せで不正なtacticsを保存しない。
- 診断の値が測定範囲と合い、未測定を0成功と偽らない。
- 手動確認表の全IDに実装箇所を結び付ける。
- テスト・ビルド・実機確認は未実施と最終報告し、利用者が実行する手順をリンクする。

「実装完了」の条件: M1～M6、P1～P6がすべて接続済み、T01～T10に対応済み、保存・権限・GUI・文書が一致し、静的確認記録があること。利用者の動作確認欄は別に未実施のまま残す。
