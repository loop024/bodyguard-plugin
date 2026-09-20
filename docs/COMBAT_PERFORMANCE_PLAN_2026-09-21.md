# 護衛の動き・戦闘と処理負荷・安定性 — 計画・進捗の入口

## 対象と前提
2026-09-21、利用者が指定した大項目1・2の全12項目。前回は末尾の番号と取り違えたため、既存の自動復帰を維持しつつ残りを対象とする。Java21 / Spigot1.21.8 / Windows10。

**最新の指示は「計画書を書き、Solへ引き継げるようにする」。実装は途中で停止し、この段階では文書だけを完成させた。** テスト・ビルド・サーバー起動は利用者が担当する。

## 読む順番

1. この文書: 対象・途中状態・次の着手先。
2. [実装仕様書](COMBAT_PERFORMANCE_SPEC_2026-09-21.md): 全12項目の振る舞い、例外、変更箇所、保存互換性。
3. [Sol用引き継ぎ](SOL_IMPLEMENTATION_HANDOFF_2026-09-21.md): そのまま渡せる開始プロンプトとS0～S10の作業チェックリスト。
4. [利用者向け手動確認](COMBAT_PERFORMANCE_MANUAL_2026-09-21.md): ビルド手順、最小確認、戦闘・巡回・負荷・保存の確認表。
5. [前回の自動復帰・状態案内](MOVEMENT_STATUS_PLAN_2026-09-21.md): 保持する既存改善。

現在地: 計画完成／大項目1・2の実装途中／ビルド・実機確認未実施。文書を読むだけで実装を再開しない。利用者から実装指示を受けた担当者がS0から再開する。

## 計画と完了条件
| 段階 | 内容 | 完了条件 | 状態 |
|---|---|---|---|
| 1 | 戦闘方針・巡回の保存 | 旧護衛の標的取得方針を維持する既定値、保存・復旧・操作権限を一元化 | データ構造・YAMLを途中まで追加。再構成・操作は未接続 |
| 2 | 配置分散、撤退、遠距離補助、巡回 | 非戦闘の配置、戦闘時間・距離・視線による撤退、装備確認、複数地点巡回 | 配置クラス案・一時フィールドのみ。動作未接続 |
| 3 | 管理処理の分割・時間制限・更新優先度 | 全件リスト作成を定期AIから除去、有限量ずつ管理し、低優先も巡回 | 未着手 |
| 4 | 保存負荷・診断・運営制限 | イベント保存を集約、重要操作の即時保存順序を維持、平均最大と待ち時間、全体上限 | 未着手 |
| 5 | 計画・引き継ぎ文書 | 全コマンド・設定・制約・利用者確認手順を記録 | 完了。実装後の静的確認は別途必要 |

## 設計方針
- 戦闘方針は移動モードから独立。互換モードを既定とし、反撃のみ・支援・迎撃・非戦闘を追加。
- 巡回は所有者保護の警備モードを使う。役職の移動警備とは同時使用しない。地点は同じワールドに最大16個。
- 配置と遠距離補助はMobの体格・足場・既存AIを尊重。攻撃力や装備の自動変更は行わない。
- 時間制限は処理の区切りで繰り越す。単一のチャンクロード、ファイル保存、Bukkitイベントの全仕事を指定時間内に止める保証はしない。
- 保存はまず不要な反復を集約する。非同期で生きたEntityや可変GuardDataを読み書きしない。解除・削除・新規登録の台帳順序を維持する。

## コード上の現在地（計画作成時）

- 前回のGuardMovementRecoveryとGuardStatusGuidanceは接続済み、実機未確認。
- CombatPolicy、GuardTactics、GuardFormationを追加済みだが、戦闘・配置・巡回の完成機能ではない。
- GuardDataにtactics、巡回番号、追跡時計、AI時刻の途中フィールドを追加済み。
- GuardStorageにtactics読込/書込を追加し、StorageSchema.GUARDSを7に変更済み。
- BodyGuard/configにformation、pursuit、ranged-spacing、管理予算、全体上限等の設定を追加済み。大半はgetterへの参照がなく、効果は未接続。
- GuardManager、GuardTask、Combat/TargetListener、Command、GUIの主要接続作業は残っている。
- 中断時のコードを破棄/巻き戻ししていない。計画作成ターンではJavaや設定を追加変更していない。
- 自動バックアップにより途中変更がコミットされている。計画作成時のHEAD: `1c74009`。cleanなgit statusでも完成を意味しない。

## まず直すもの

実装仕様書のT01～T10を確認する。特に次の4点を先行する。

1. `GuardManager.trackLoadedEntity()`のGuardData再構成でtacticsが失われないようにする。
2. mode/protection/recallと巡回フラグを同一操作で更新し、保存後の不正な組合せを防ぐ。
3. GuardFormationの8方向・半径12clampによる配置重複を廃止する。
4. 戦闘episode時計と一時命令解除を分離し、時間基準をtickへ揃える。

## 要求別進捗

| ID | 状態 | 次の接続 |
|---|---|---|
| M1 自動復帰 | 前回接続済み・実機未確認 | 配置/巡回/撤退帰還との統合 |
| M2 配置 | 案のみ | 重複を修正してFOLLOW/recallへ |
| M3 戦闘方針 | enum/保存案のみ | controller、イベント、サービス、GUI/command |
| M4 撤退 | フィールド案のみ | episode、視認、距離、帰還状態 |
| M5 射手位置 | 設定のみ | 種類/装備/安全な後退 |
| M6 巡回 | 保存案のみ | 編集、移動、状態遷移、UI |
| P1 管理分割 | 未実装 | 索引・計画段階・イベント観測 |
| P2 時間予算 | 設定のみ | 周期予算・公平な配分 |
| P3 保存集約 | 設定のみ | 通常イベント要求と即時操作の分離 |
| P4 優先更新 | フィールド/設定のみ | 1護衛1entryの期限順キュー |
| P5 診断充実 | 既存の直近値のみ | 平均/最大/待ち/保存要求 |
| P6 全体上限 | 設定/getterのみ | 登録元・中央判定・GUI・警告 |

## 実装記録の運用

Sol用引き継ぎのテンプレートを使い、S0～S10の各終了時にここへ追記する。実装前は上表を完了へ変えない。

### 計画作成 — 2026-09-21
- 対象全12項目を仕様化した。操作サービス、中央の攻撃判定、配置容量、撤退条件、巡回編集、管理キュー、保存分類、診断の定義を記録。
- コードの途中状態を読んで、T01～T10の修正対象を記録した。
- 同期保存を維持した集約をこの版の必須範囲とした。危険な丸ごと非同期化を実装手順に含めていない。
- S0～S10の実装順序、引き継ぎプロンプト、最小8件と追加の手動確認表を作成した。
- 文書参照とコード上のメソッド・設定・GUI既存スロットを照合した。
- 未実施: テスト、ビルド、サーバー起動、実機確認。
- 次の着手先（実装指示後）: S0、GuardManager.trackLoadedEntity / GuardData.setTactics / GuardData.clearCombat。

## 利用者確認結果
未実施。[手動確認文書](COMBAT_PERFORMANCE_MANUAL_2026-09-21.md)に手順と結果欄を準備済み。実装完了後に利用者が日時・成否・ログを記録する。

### S0 — 2026-09-21（実装開始）
- 状態: 実装中。M1～M6/P1～P6の完了判定はまだ行わない。
- 対象要求: M3、M4、M6のデータ基盤。T01、T06、T07を一部修正。
- 変更ファイル・メソッド: `GuardManager.trackLoadedEntity`で既存契約のtacticsとsaveRevisionを再構成後へ引き継ぐ。`assignCombatTarget`の拒否時は命令だけ解除。`GuardData.setTactics`は方針のみ変更した際に巡回番号を保持し、PASSIVE時に命令を解除。`GuardData.pursuitExpired/retreat/isRetreating`の一時時計をtick引数へ変更し、標的切替だけでepisode開始時刻をリセットしない。`GuardTactics`から未使用のPDC向け文字列変換を削除。
- 仕様上の判断と理由: 追跡episodeの開始時刻は標的変更・命令拒否で保持する。YAMLをtacticsの正本とし、Entity PDCには追加しない。
- 既存挙動への影響: 再読み込み後も戦闘方針・巡回地点が保持される。方針変更だけでは巡回の現在番号を先頭に戻さない。戦闘時計の新APIはまだ戦闘処理へ接続されていない。
- 保存・rollback・権限の扱い: 保存形式7は変更なし。操作サービス、権限、rollbackは今後のS1/S4で接続する。
- 静的に確認した呼出し経路: GuardStorageのtactics読込→GuardData→GuardManager.trackLoadedEntity。新しい追跡APIの呼出し元は現時点で存在しない。`git diff --check`は実施したが、テスト・ビルドではない。
- 未実施: テスト、Mavenビルド、サーバー起動、実機確認。
- 残件: S0の全経路精査、S1～S10。GuardFormationの半径clampによる重複は未修正。GuardTask/GuardManagerの全件処理も未修正。
- 次の着手先: `GuardManager.setMode/setProtection/teleportGuard`の巡回停止と保存rollback整合、その後`GuardCombatController`とCombatListener/TargetListenerへ戦闘方針を接続する。

### S0 続き — 2026-09-21
- 状態: 実装中。T02の通常操作経路を修正。巡回機能自体は未公開・未接続。
- 対象要求: M6、T02。呼び戻し位置の記録についてT03以降の配置作業前に実測へ変更。
- 変更ファイル・メソッド: `GuardManager.setMode`は明示的モード操作で巡回を停止し、保存失敗時に以前のtacticsを復元する。`setProtection`はROLEへの切替で巡回を停止し、保存失敗・例外時に復元する。`teleportGuard`は成功した移動の後に巡回を停止してGUARDへ固定し、Entityの実際の到着位置を保存する。`GuardData.setTactics`は地点が不変なら巡回停止/再開でもruntime番号を保持する。
- 仕様上の判断と理由: モードを手動設定する操作はGUARDの再指定も含めて巡回を停止する。巡回中の呼び戻しはその場で固定警備となる。地点一覧は削除しない。
- 既存挙動への影響: 巡回中の操作後に不正なGUARD/OWNER以外の巡回組合せを保存しない。呼び戻しの移動自体は保存失敗時に戻せないため、失敗を返しdirtyを維持する既存の意味を保つ。
- 保存・rollback・権限の扱い: mode/protectionは共通Manager入口の既存所有者照合を維持。即時保存失敗はtacticsを復元する。呼び戻しは移動後保存失敗を失敗として報告する。
- 静的に確認した呼出し経路: Command/GUIのmodeとprotection呼出しはManagerへ集中。`GuardData.setMode/setProtection`の直接呼出しはManager内だけ。`git diff --check`による差分形式確認のみ実施。
- 未実施: テスト、Mavenビルド、サーバー起動、実機確認。
- 残件: S0の異常系精査、S1～S10。特にS1の方針はまだ戦闘処理へ未接続、S4の巡回編集と移動も未実装。
- 次の着手先: `CombatListener`、`TargetListener`、`GuardManager.assignCombatTarget/getCombatTarget/isForbiddenTarget`を読み、中央の方針判定を接続する。

### S1 着手 — 2026-09-21
- 状態: 実装中。戦闘方針の全経路接続・操作UIは未完了。
- 対象要求: M3。
- 変更ファイル・メソッド: `GuardManager.commandGuardsToTarget/commandRoleGuardsToTarget`で方針別の防衛/支援命令可否を適用。`CombatListener`でPASSIVE護衛を発射元と特定できる直接・投射・間接ダメージをキャンセルし、非LEGACYの護衛自身が被弾した際の反撃命令を追加。`TargetListener`でPASSIVE/RETALIATE/ASSISTの自然取得を制限し、他プラグインが既にキャンセルしたイベントには触れない。`GuardTask.validateCurrentTarget`と警備索敵で同じ基本制限を適用。
- 仕様上の判断と理由: LEGACYの既存索敵経路を維持し、INTERCEPTも索敵可とした。RETALIATE/ASSISTは承認された命令の対象に限定する。
- 既存挙動への影響: tacticsをYAMLで持つ既存護衛は方針に応じて標的取得・ダメージを抑止する。現状GUI/コマンドから方針を変更する入口は未実装。
- 保存・rollback・権限の扱い: この段階で新しい保存操作は追加していない。設定変更サービスの所有者・権限・即時保存は次工程。
- 静的に確認した呼出し経路: CombatListener→Manager命令→TargetListener→GuardTask、GuardTaskのGUARD/ROLE GUARD索敵。`git diff --check`による差分形式確認のみ実施。
- 未実施: テスト、Mavenビルド、サーバー起動、実機確認。
- 残件: 中央controllerによる全攻撃経路の統合、policyコマンド/GUI/権限/messages、標的の距離・撤退・巡回・配置・負荷改善。M3は完了扱いしない。
- 次の着手先: `GuardCombatController`またはManager共通判定へ現在の分散条件を集約し、policy変更サービスとコマンド/GUI操作を接続する。
