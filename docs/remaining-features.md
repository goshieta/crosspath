# 設計照合と接続契約

基点: origin/main `1b7a1f93f175d777cbaa76813189435b71840f5e` (2026-09-27 fetch)。
ブランチ: `codex/remaining-features`。元の72Timerブランチとステージ済み.ideaファイルは変更しない。
AGENTS.mdはリポジトリ・親ディレクトリに見つからなかった。
指定パスは存在せず、同じOneDriveのハッカソン直下にある
`災害時すれ違い通信_詳細設計書_草案.pdf` (v0.8, 2026-09-26) を使用。

## 実装前の分類

|設計箇所|分類|根拠・不足|
|---|---|---|
|1.1–1.3, 4, 5: 確定保存・72時間・人物単位保存|実装済み／UI部分実装|SafetyRepository.startSession、ClockAnchor、SessionStatus。SC03の非同期処理でFragment参照・選択値を遅延取得している。確定時刻もUIから実行キューへの遅延を含んでしまう|
|2, 3, 11.2: 本人登録・保持|部分実装／API仕様未確定|UserProfileが本番でランダムIDを発行、applyで保存失敗未確認。SC01は同期呼び出し。APIの接続先・認証・形式なし|
|5, 8.1, 11.6: 通知対象管理・今回の受信|実装済み／部分実装|WatchTarget、currentWatchStatuses、SC05に接続済み。入力例外・読込失敗・非同期後の画面寿命を補完する|
|8.2, 9.4, 11.1: Android通知|未実装|NotificationRequestとPENDING等のDB APIのみ。Dispatcher、権限、タップ、再試行・OS取消なし|
|8.3, 9.1–9.3: 期限・履歴削除|部分実装|Repositoryは単調時計、再起動、期間終了＋100時間を実装。UiDataとSC04のチェックのみで画面非依存の周期処理・OS取消なし|
|11: SC01–SC06・遷移・復元|部分実装|6画面あり。Navigator.currentがActivity再生成で復元されない。タイムアウト時に未確認の通常画面を表示。SC06読込エラーを期間なしと表示|
|11.11–11.14: テーマ|部分実装・設計との矛盾|mainはSC05/06も緊急時にダーク。設計書はSC04のみダークなので修正する|
|12: バックアップ・エラー|部分実装|allowBackup=true。保存エラー表示の不足|
|6, 7, 10、および他章のBLE・同期|他担当|広告、探索、GATT、フレーム、同期制御、差分、ダイジェストは変更しない|
|3, 9.4: RelayForegroundService|他担当との接続待ち|統合mainに通信Serviceなし。新規Serviceは作らず既存SessionStopHandlerを接続点とする|
|8.3, 11.7, 15: 過去期間の専用閲覧画面|仕様未確定|SC06は今回の受信状況。過去記録の別一覧を新設しない|
|14: BLE実機・容量・電池評価|他担当／実機検証|今回のユニットテストで達成扱いにしない|

登録用のネットワーク契約は捏造せず、debug専用の明示入力とreleaseの未接続エラーを分離する。
既存Repositoryの公開受信・送信・通知状態APIは保持する。

## 実装結果

|設計箇所|今回の補完|
|---|---|
|2, 3, 11.2|本人ID・名前の読み込みと保存を専用Executorへ移動。保存成功前に登録成功としない。request_idと要求内容を保存し、再試行でも同じ要求を使う。登録済みIDを再発行・変更しない|
|2, 15|RegistrationGatewayとビルド別RegistrationProviderを追加。debugのみ手動デモID、applicationIdも`.debug`で分離。releaseはAPI未接続エラーで停止。旧版の出所不明IDは本番の本人IDと認定せず、移行確認を必要とする|
|4.2, 11.4|確定クリックでConfirmationClock.Tokenを採取し、保存キューの遅延でt0を変更しない。選択とIDをキュー投入前に固定。確定前キャンセルはDBに触らない。二重操作をUIと既存DB制約で防止。保存中は地点選択と戻る操作を抑止。選択を画面再生成時に復元|
|8.2, 9.4, 11.1|Android通知Channel、POST_NOTIFICATIONS、immutable PendingIntent、SC06への遷移。今回の配信状態と許可・再試行UI。通知拒否時も受信と履歴を維持|
|8.2, 8.3|現在ACTIVE期間のPENDINGのみを最終期限確認後に発行。同じ履歴は安定したtag+IDとonlyAlertOnceで再試行。権限拒否はBLOCKED_PERMISSION、利用者が明示再試行したときだけ今回分をPENDINGへ戻す|
|9.1–9.3, 11.10|プロセス存続中の30秒周期の期限再評価と、期限直前の追加予約。画面から独立して停止要求・削除・通知再試行・OS通知取消。起動・復帰・SC06照会でも既存期限管理を利用|
|11.1–11.8|Navigatorの現在画面を復元し、未確認状態の1200msフォールバックを廃止。ホーム操作時は最新の期間を照会。画面保存後の非同期遷移を抑止。対象管理の読込・入力・保存エラーを表示。SC06は今回の受信だけを表示|
|11.11–11.14|当初はSC04のみダークへ修正。その後の利用者指定で、タイマー作動中は全画面をダークに変更。終了時は入力状態を保持してテーマを再適用。既存XMLのapp:tint指定も修正|
|12|クラウドバックアップと端末転送からアプリデータを除外。通信未接続を成功表示せず、SC04に理由を表示|

既存の受信処理、WireRecordの2フィールド、人物単位の先着地点保持、自治体コード表、
ClockAnchorの時計補正、DBスキーマ、履歴期限の計算は維持した。
SC06は§11.7の今回の〇／ーと配信状態を表示する。過去期間の専用一覧・期間切替は§15で未指定のため追加していない。

## 通信担当との接続

通信Serviceを新設していない。広告・探索・GATT・フレーム・同期・差分・ダイジェストにも変更なし。
通信を開始できるかは既存の `SafetyRepository.checkAndEndExpiredSession()` / `SessionStatus.canCommunicate(expectedSessionId)` で毎回確認する。

1. アプリ／所有Serviceの起動時に `UiData.init(applicationContext)` を呼ぶ。
2. 所有Serviceの停止処理を `UiData.setSessionStopHandler(handler)` へ接続する。
   引数は停止対象のsessionId。実装は冪等・非ブロッキングで、旧IDの停止で新期間を止めない。
   Repositoryはトランザクション外で呼び出し、次の期限照会時にも再試行する。
3. 市町村確定後と復帰時の期間は `UiData.checkSession(success, failure)` で取得できる。
   `UiData.addListener(listener)` / `removeListener(listener)` はmain threadへの期間状態通知。
   同じ状態は繰り返すため、Service開始の一回限りイベントと解釈しない。
   Service開始は通信所有者が表示中のActivityからOS制限・BLE権限を満たして行う。
4. 受信は既存の `receive(localSessionId, masterVersion, ...)` / `applyReceivedBatch(...)`。
   成功完了後に `UiData.onBatchCommitted(result)` を呼ぶと今回の状態表示と通知処理を促す。
   保存ACKの送出判断は従来どおり通信担当。失敗を成功ACKへ変換しない。
5. `UiData.reportCommunicationState(sessionId, running, reason)` はUI用の実状態報告。
   古いsessionIdは無視する。接続されるまでは「通信コンポーネント未接続」と表示する。
6. 送信データは既存 `pageAfter` のWireRecordのみ。氏名・端末内日時は追加しない。
   自治体マスター版は `kyushu-2026-09-26-v1`、通信専用コード1..233。

`UiData.execute(repo -> future)` は初期化・入力例外も含む非同期結果を返す。
UIは `UiData.onResult` でmain threadへ戻す。既存のwhenReadyも互換用に残すが、新規UIではexecuteを使う。

通知の発行は `dispatchCurrentNotifications(NotificationSink)` を使う。
最終期間ゲート・短いOS発行呼び出し・配信状態変更を同じDBトランザクション内で直列化し、
別Repositoryインスタンスによる新期間開始とも競合させない。Sinkでネットワーク、DB Future待機、BLE操作をしない。
既存 `findPendingNotifications` は過去期間も検索できる互換APIなので、取得結果を直接OS通知へ流さない。
OS通知とDBの厳密な原子性はない。OS発行後のクラッシュでは同じキーで再試行する。
1回の発行処理は最大100件。失敗はPENDINGを維持し、次回起動・復帰・周期処理で再試行する。
履歴削除後のクラッシュでも、OSに残る `history:<履歴ID>` とDBの有効性を再照合して取消を回復する。
通知タップは現在のSC06を開き、渡された過去IDから失効した詳細や〇を復元しない。

## 登録API担当との接続

`registration/RegistrationGateway.register(requestId, name, debugId)` はワーカー上で呼ばれる。
releaseのRegistrationProviderを実アダプターへ接続する。debugIdは本番では使用しない。
サーバーは同じrequest_idに同じ24bit IDを返す必要がある。返却IDは1..0xFFFFFFで検証する。
接続先・認証・HTTP形式・タイムアウト・ID復旧・本人性は未確定。INTERNET権限の宣言も実接続時に追加する。
通信結果不明／保存失敗では要求を保持する。IllegalArgumentExceptionは「ID未発行の入力拒否」に限る
（要求を取り消して入力修正を許すため）。その他の通信失敗は例外として返し、同じ要求で再試行する。
ID・名前・出所は一度に保存し、成功するまでUIの登録完了状態を更新しない。
旧版releaseが作ったランダムIDの自動移行は行わず、既存データを削除せずに利用を保留する。

## 検証結果（2026-09-27）

- `:app:assembleDebug` / `:app:assembleRelease`: 成功。
- `:app:testDebugUnitTest`: 39件、失敗0。
- `:app:connectedDebugAndroidTest`: Pixel_10 AVD / Android 17 / API 37、63件、失敗0。
- `:app:lintDebug`: エラー0、警告33。既存の依存更新、未使用資源、描画・最適化案内など。
- `git diff --check`: 成功。
- releaseのコンパイル済みRegistrationProviderは未接続エラーのみ。SC01のclassにデモ入力文言が含まれないことも確認。

既存の境界・時計・再オープン・移行・トランザクションテストに加え、NotificationDeliveryTestは
72時間直前／ちょうど、発行間での満了、旧PENDINGの新期間隔離、権限拒否と明示再試行、
通知後クラッシュの同一ID再試行、100時間失効境界、OS通知取消、確定処理の遅延と二重確定を検証した。

UiJourneyTestはSC01登録、SC03確定前の取消、未作成期間、選択復元、二重確定、SC04復元、
SC05登録、SC06復元、今回の受信状態、実Android通知とPendingIntentによるSC06遷移を検証した。
既存Espresso 3.5.1はAPI 37の入力注入と非互換のため、ActivityScenarioでViewの操作コールバックを実行した。
実機タッチやアクセシビリティ試験の代替とは扱わない。通知FixtureはandroidTestだけに存在する。
UiJourneyTestは専用.debugパッケージのDBとプロフィールを初期化するので、個人データの入ったdebug端末では実行しない。

Gradle試験終了後はテストAPKが削除されるため、手動でdebug/test APKを再インストールし、
UiJourneyTestをもう一度実行（1件成功）。続いてdebugアプリのみforce-stop→cold startを実施し、
本人ID1001、保存された期間の残り時間、SC04の受信済み〇、通信未接続表示の復元を確認した。
端末再起動は注入時計のテストで検証し、実機の電源再投入・省電力・BLE挙動を実施済みとは扱わない。

環境: Gradle 9.6.0、JDK 25、compile/target SDK 37、min SDK 31。
このWindows環境ではGradleのloopbackエラーを避けるため、書込可能な一時ディレクトリを作成し、
`JAVA_TOOL_OPTIONS` に `-Djava.io.tmpdir=<そのパス> -Djdk.net.unixdomain.tmpdir=<そのパス>` を指定した。
テスト結果は `app/build/test-results/testDebugUnitTest` と `app/build/outputs/androidTest-results/connected/debug`、
Lint結果は `app/build/reports/lint-results-debug.html` に出力される。

## 残件・制約

- 第6・7・10章と他章にまたがる通信領域は他担当。Service開始／停止、BLE権限・Bluetooth状態の実報告は接続待ち。
- 登録APIのネットワーク実装、本人性、ID復旧・移行は仕様未確定。releaseで架空IDを発行しない。
- 過去期間の専用履歴画面は未指定。現在の受信状態へ混ぜない。
- プロセス停止／電源OFF中に期限ぴったりの物理削除は保証しない。次回実行の最初に既存期限ゲートで削除する。
- OS通知の厳密なexactly-once、任意の時計改変下の厳密な実時間保証は不可。安定IDと保存済み時計の保守的判定を使う。
- Galaxy／Pixel実機BLE、Android 12–14、TalkBack、文字200%、大量対象者・1,300万人容量・電池評価は未実施。
