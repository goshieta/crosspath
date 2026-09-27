# Room storage API (stages 2, 4 and 5)

## 72時間管理とアプリ接続契約

永続化済みの `startedAtWall` と `endsAtWall = startedAtWall + 72時間` は、
再起動・Repository再生成・終了処理で変更しません。期限ちょうどから通信を拒否します。
残り時間は表示用スナップショットで、カウントダウンの減算結果は期限判定に使いません。

### 状態取得は終了処理を伴う

| API | 契約 |
|---|---|
| `sessionStatus()` / `checkAndEndExpiredSession()` | 同じ入口。時計を確認し、期限終了・配信データ削除・失効履歴削除を行ってから状態を返す |
| `endExpiredSession(expectedSessionId)` | 指定IDだけを終了。古い要求で新セッションの状態・データを変更しない |
| `currentWatchStatuses()` | 先に同じ期限・履歴処理を行う。SC04／SC06に期限切れ・終了済み・時計不整合の受信マークを出さない |
| `validHistories()` / `findPendingNotifications(id)` / 配信状態更新 | 先に期限・失効履歴削除を実行。過去期間の未失効PENDING再取得と条件付き状態更新は維持 |
| `deleteExpiredHistories()` | 失効履歴だけを明示的に削除し件数を返す。先行する状態・表示APIが削除済みなら0 |
| `receive()` / `applyReceivedBatch()` | 保存前・全書き込み後に期限と接続IDを確認。失敗時はバッチ・履歴・revision・時計更新をロールバック |
| `pageAfter()` | 読み取り前後に期限と接続IDを確認。拒否時はFuture例外完了（空ページとは区別） |
| `currentSession()` | 互換用の生の保存状態。診断用であり、通信許可や画面の期限判定には使わない |

`SessionStatus` はNO_SESSION、ACTIVE、ENDED、CLOCK_UNCERTAINを返します。
EXPIREDは終了処理前の内部判定として残しますが、状態取得APIは終了処理後のENDEDを
返します。削除失敗なら例外完了し、成功した状態スナップショットを返しません。
remainingMillisはACTIVE以外では0、ACTIVEでは非負の残り時間です。
canCommunicateはACTIVEかつrelayEnabled=trueだけ、`canCommunicate(connectionSessionId)`
は接続開始時のID一致も確認します。Bluetooth・権限・接続状態は通信側で確認します。

EndResultはNO_SESSION、STALE_SESSION、NOT_EXPIRED、CLOCK_UNCERTAIN、ENDED、
ALREADY_ENDEDです。CLOCK_UNCERTAINでは期限未到来でも通信停止を要求し、データは
まだ削除しません。終了状態への変更と対象IDのSafetyRecord削除は同一トランザクションです。
WatchTargetと未失効NotificationHistoryは保持します。履歴期限は予定終了＋100時間で、
処理遅延・表示・再受信によって延長しません。成功結果はコミット後だけ返します。
受信中の期限到達はまず受信全体をロールバックします。通信側は成功ACKを返さず停止し、
`checkAndEndExpiredSession()` を再度呼んで終了処理を行います。

### 通信停止の接続口

本番DBは `AppDatabase.open(context)` で作成してください。Android時計とRoom移行を
構成します。独自にRoom.databaseBuilderを使う本番コードはこのファクトリーへ移してください。
時計注入はpackage-privateのテスト用コンストラクターだけです。

```java
AppDatabase db = AppDatabase.open(context);
SafetyRepository repository = new SafetyRepository(
    db, dbExecutor, KyushuMunicipalities.load(), sessionId -> {
        // 通信担当の実装へ接続する。
        // まず、このIDの送信許可を閉じる。
        // protocolExecutorへ「このIDだけ停止」を投入する。
        // 広告・スキャン・GATT・予約処理・送信キュー・受信バッファ・Serviceを停止する。
    });
```

SessionStopHandlerはDBトランザクションの外で呼ばれ、ハンドラーの呼び出しが戻る前に
APIは成功完了しません。DB処理が失敗した場合も、対象IDが分かれば停止を要求します。
ハンドラーの例外もFutureを失敗させますが、既にコミットした削除は元に戻りません。
次回の状態確認で停止要求を再送するため、ハンドラーは冪等・スレッド安全にしてください。
RepositoryのFuture完了をハンドラー内で待つとデッドロックし得るため禁止です。

非同期に投入した停止処理の完了まではRepositoryは保証しません。通信側で実際の停止を
追跡してください。従来のコンストラクターは互換性のため停止ハンドラーなしでも動作します。
その場合もDBゲートと削除は働きますが、実通信は停止されません。
**DB削除だけではメモリ上のデータや実行中のBLE通信は停止しません。**
古いIDの停止要求や受信失敗通知が新期間へ遅れて届く場合があります。通信側でもIDを比較し、
新期間を止めたり、古いキューを新期間のIDへ付け替えたりしないでください。

### 起動・復帰・送受信・画面表示の順序

1. アプリ起動・復帰、Foreground Service開始、BLE探索・GATT接続開始前に
   `checkAndEndExpiredSession()` の完了を待つ。失効履歴削除もこの入口で実行される。
2. canCommunicate=trueの場合だけ、そのsessionIdを接続・予約・送受信キューに捕捉する。
3. 各送信ページ取得前に `sessionStatus()` で期限と接続IDを確認してからpageAfterを呼ぶ。
4. **実際の送信直前・各フラグメント送信前にも** `sessionStatus()` を呼び、
   `canCommunicate(connectionSessionId)` を確認する。結果は通信許可の有効期間を確保する
   ものではない。完了通知を通信Executorへ戻した際にも古い接続・停止済みIDを破棄する。
5. 受信は接続開始時のIDをapplyReceivedBatchへ渡す。例外完了では成功ACKを返さない。
   成功完了でもCAPACITY_REJECTEDを保存成功にしない。
6. SC04／SC06表示前はcheckAndEndExpiredSessionを呼び、続けてcurrentWatchStatuses／
   validHistoriesを取得する。これらの表示API自体にも同じ前処理があり、接続忘れを防ぐ。
7. 通知参照前にもfindPendingNotifications／validHistoriesを通す。期限切れのOS通知を
   取り消す処理は通知担当が接続する（DB削除はAndroid通知を取り消さない）。

```java
repository.checkAndEndExpiredSession().thenAccept(status -> {
    // UIスレッドへ戻してstatus.state / remainingMillisを表示。
});
repository.sessionStatus().thenCompose(status -> {
    if (!status.canCommunicate(connectionSessionId)) {
        throw new IllegalStateException("Connection no longer active");
    }
    return repository.pageAfter(connectionSessionId, lastUserId, 256);
}); // 送信直前にも再確認する。
```

### 単調時計・再起動・Room移行

同一起動中は `SystemClock.elapsedRealtime()` を使用します。スリープ時間も含みます。
起動識別子には `Settings.Global.BOOT_COUNT` を読み取り、ActiveSession.bootMarkerへ保存します。
始点の単調時計はstartedAtElapsedへ保存します。ClockAnchorには論理UTC時刻・単調時計・
bootMarkerを永続化し、Repository再生成や新期間開始でも時計の進行を失わないようにします。
論理時刻は「壁時計」と「前回の論理時刻＋単調時計の経過」の大きい方です。開始時の
単調時計から算出する期限も使い、壁時計の巻き戻しで期限を延長しません。

bootMarkerが変わった場合は保存済みUTC時刻を基準に復元し、新起動の単調時計に結び付けます。
保存済み下限からの巻き戻し、単調時計の逆行、起動識別子の取得不能を検出した場合は
CLOCK_UNCERTAINとして通信を拒否し、停止を要求します。壁時計と単調時計を同時に
採取できないため1秒以内の差は許容しますが、論理時刻は戻さず、残り時間も延長しません。
時刻の信頼性が戻れば再確認で再開可能です。既にENDEDになった期間は復活しません。

オフラインで、再起動中に行われた時計改変が保存済み下限を下回らない場合は検出できません。
その場合の電源OFF時間を正確に復元することはできず、任意の時計改変・DB復元まで含む
厳密な実時間72時間／100時間は保証しません。壁時計の大幅な進みは早期終了・履歴失効に
つながります。信頼できる外部時刻の取得は別課題です。

DBバージョン2でClockAnchorを追加します。MIGRATION_1_2は既存4テーブルの時刻・データを
変更しません。旧版の空bootMarkerは再起動相当としてUTC時刻から復元し、以降の時計進行を
保存します。旧版で記録されなかった時計変更を後から検出することはできません。

公式仕様：[SystemClock](https://developer.android.com/reference/android/os/SystemClock)、
[BOOT_COUNT](https://developer.android.com/reference/android/provider/Settings.Global#BOOT_COUNT)。

### 後続PRの削除・結合テスト契約

PeerSyncState、BlockDigest、SyncProgressは現在のブランチに存在しません。実装するPRで
各行をlocalSessionIdへ所属させ、**SafetyRecord削除と同じ終了トランザクション**へ削除を
組み込んでください。新期間へ旧期間の要約・スナップショット・同期進捗を再利用しません。
新期間開始時の旧データ整理経路にも同じ対象を追加し、途中失敗時の全体ロールバックを
検証してください。未実装のテーブルやBLEをダミー実装して完了扱いにはしていません。

MainActivity、Service、BLE、SC04／SC06、OSスケジューラーへの接続は後続PRです。
定期実行や復帰イベントの接続がなければ、APIが呼ばれるまで物理削除・停止通知は実行されません。
**アプリ停止中の72時間ちょうどのバックグラウンド実行は保証しません。** SQLite DELETEは
復元不能な物理消去でもありません。最終期限確認・コミット・BLE送信は原子的に同期できないため、
通信側の停止ゲートが必要です。

追加のClockAnchorTest／MonotonicExpiryTestは時計巻き戻し、再起動、DB再オープン、
v1移行、状態・通知APIの物理削除、停止ハンドラーのID・失敗・再試行を検証します。
実際のService／BLE／画面の結合テストは接続後に以下を追加してください。

- Service稼働中・BLE接続中・送信待ち・受信バッファ保持中の期限到達で対象IDだけ停止。
- 受信中の期限到達では成功ACKなし。停止イベントと新セッション開始の競合でも新期間を維持。
- 期限後の画面復帰で通信期間なしを表示し、配信データ・失効履歴を実際に削除。
- 強制停止・電源OFF・省電力・時計変更からの復帰。実機BLE停止時刻はエミュレーターで代替しない。

再現用コマンド：`./gradlew :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:connectedDebugAndroidTest`。
WindowsでJDKのUnixソケットエラーが出る環境では、既存のapp/build/tmpディレクトリを使い
`JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\dev\crosspath\app\build\tmp` を実行時に指定します。
CIで実行する場合も同じタスクを使い、`app/build/test-results/testDebugUnitTest/`、
`app/build/outputs/androidTest-results/connected/`、`app/build/reports/androidTests/connected/`
を成果物として保存してください。端末テストのディレクトリにはテストごとのlogcatも出力されます。
CI設定・実機BLEログの追加は後続PRで、現時点でCI実行済みとは扱いません。

## Stage 4 / SC04 integration

All APIs below return CompletableFuture and use the supplied background executor.
UI owners must dispatch results to their UI thread. SC04/SC06 use `currentWatchStatuses()`;
screen layouts and Android notification delivery are outside this data-layer change.

```java
repository.addWatchTarget(0x123456, "Family"); // true: inserted; false: ID already registered
repository.watchTargets(); // immutable list of WatchTarget, ordered by targetUserId
repository.deleteWatchTarget(0x123456); // true: deleted; false: not registered
repository.currentWatchStatuses(); // current-period receipt marks, never history-derived
repository.deleteExpiredHistories(); // expiry <= repository clock at execution
```

Personal IDs are 1..0xFFFFFF (0 remains reserved). Duplicate registration preserves
the original name and timestamp. Removing a target preserves SafetyRecord and
NotificationHistory. Registration does not notify retroactively for stored records.

`receive()` and `applyReceivedBatch()` return `BatchResult.notifications`, an
immutable list of immutable `NotificationRequest` values, and
`isNotificationRequired()`. Each request contains the committed notificationId,
localSessionId, targetUserId, displayName, municipalityCode, municipalityName and
historyExpiresAt. Only successful future completion exposes these requests;
exceptional completion has no success result and must not trigger a notification.
The existing outcomes and inserted fields retain their meaning.

There is one saved history and notification intent per newly received watched ID
per local session. Repeated IDs (including within one batch or concurrent calls),
known IDs with changed locations, self records and capacity rejections do not
produce notification intents. A new session permits a new history for that ID.
Expiry is session end + 100 hours; just before expiry is valid, exactly at expiry
and afterwards is expired. Explicit cleanup deletes only NotificationHistory,
preserving the session, SafetyRecord and WatchTarget. There is no public cutoff
overload: callers cannot supply future timestamps to delete retained history early.
Tests advance the injected clock using the package-private repository constructor.

Room's existing unique (localSessionId, targetUserId) index remains unchanged.
Unexpected history insert conflicts fail the whole transaction. Record inserts,
history inserts and session revision updates roll back together on DB failure.
Notification intents are not a delivery acknowledgement or an exactly-once
Android notification service; delivery and retry policy belong to the caller.

### Current receipt state versus historical display

`currentWatchStatuses()` reads the session and a WatchTarget/SafetyRecord left join
in one transaction, ordered by targetUserId. It returns immutable WatchStatus values:
RECEIVED = 〇, NOT_RECEIVED = ー, NO_ACTIVE_SESSION = 現在の通信期間なし.
Only RECEIVED-source records linked to the current ACTIVE session count; self
registration does not count as receipt. Missing, ended, not-yet-started or expired
sessions yield NO_ACTIVE_SESSION for every target, even when history remains.
An empty list means no registered targets. Refresh on display and at session expiry.
Registration after receipt can show RECEIVED without creating notification history.
`watchTargets()` is registration data only. `validHistories()` is historical display
across all sessions, with expired rows deleted before reading; NEVER derive current receipt
marks from it. Display reads now maintain session expiry and physically delete expired histories before returning.

### Repository / NotificationDispatcher contract

1. The repository saves history as PENDING in the same transaction as receipt.
2. Only after commit succeeds does it expose BatchResult.notifications. Failure
   rolls back all writes and completes exceptionally, without a notification result.
3. Dispatcher treats results as wake-up hints and retrieves durable work with
   `findPendingNotifications(sessionId)`. This returns immutable notification
   requests for that session, including past sessions, excluding expired history.
   On startup, discover retained session IDs from `validHistories()`, deduplicate
   them, then query each session's pending queue. Do not restrict recovery to the
   current session. Removal of a watch registration does not cancel saved work.
4. After successful posting, call `markNotificationPosted(notificationId)`.
5. If notification permission is denied, call `markNotificationBlocked(notificationId)`.
   BLOCKED_PERMISSION is terminal in this contract; permission grants do not
   automatically replay old notifications. POSTED is also terminal.
6. Crashes before posting or transient delivery errors leave PENDING for retry.
   DB update failures complete exceptionally and leave PENDING for later recovery.
7. Updates are atomic compare-and-set operations on unexpired PENDING rows only.
   They return true after commit, false for absent, expired or finalized rows.
   Repeated/conflicting acknowledgements cannot overwrite a finalized state.

Dispatcher must serialize live and recovery work through one application-wide
consumer; queue reads do not claim work. Use the persisted long notificationId as
the stable identity: Android notification tag `crosspath-history:<notificationId>`
and fixed integer ID 0. Do not truncate the long to an integer or generate a fresh
identity on retry. Re-read pending work before issuing and recheck expiry. A crash
between Android posting and DB acknowledgement can require reposting; reusing the
same tag/ID replaces the existing notification instead of creating another entry.
Dispatcher should suppress repeat alerts for updates. The DB and Android service
cannot commit atomically, so exactly-once alerts are not guaranteed by this API.
Actual Android posting, permission checks and Dispatcher implementation are out
of scope; this section defines their required integration contract.

Create one AppDatabase for the application lifetime and pass a background
ExecutorService to SafetyRepository. Do not call Room directly from UI or BLE.
The caller owns the executor and database and closes them after pending work ends.

```java
AppDatabase db = AppDatabase.open(context);
ExecutorService dbExecutor = Executors.newSingleThreadExecutor();
// Uses the bundled e-Stat Kyushu municipality master.
SafetyRepository repository = new SafetyRepository(db, dbExecutor);

// On municipality confirmation: creates the local session and self record atomically.
repository.startSession(myUserId, municipalityCode).thenAccept(localSessionId -> {
    // Keep this local token with the connection; never send it as a person field.
});

// BLE receiver: call with the token captured when this connection began.
repository.receive(localSessionId, peerMasterVersion, userId, municipalityCode)
    .whenComplete((result, error) -> {
        // This runs on the completing thread. Dispatch to the protocol executor.
        // error != null: do NOT send a success ACK.
        // error == null: DB commit completed; inspect every result.outcomes entry.
    });

// Batch receive (1..256 immutable WireRecord values):
repository.applyReceivedBatch(localSessionId, peerMasterVersion, records);

// Send in bounded pages. Use the last returned userId as the next cursor.
repository.pageAfter(localSessionId, 0, 256);

// UI callers read only unexpired history (expiry evaluated on the DB executor).
repository.validHistories();
repository.deleteExpiredHistories();
```

- WireRecord contains only userId and municipalityCode. No timestamps are exchanged.
- IDs are 1..16,777,215; 0 is reserved per the draft. Locations are integers 0..255.
  WireRecord is a transport/Room projection and checks numeric bounds at construction.
  Its requireMunicipalityName(master) validates membership. startSession() and
  applyReceivedBatch() always call it before enqueueing, including for duplicates.
  A single unknown code rejects the entire batch without changing records or history.
- MunicipalityMaster requires a nonempty, immutable copy of an approved code/name
  table and rejects missing/blank names. Notification history stores the resolved
  name in the same transaction as the received record. No fallback names are saved.
  The default constructor loads the bundled 233-municipality Kyushu table from
  e-Stat as of 2026-09-26. Wire codes 1..233 are assigned in official-code order;
  0 and 234..255 are unassigned and rejected. Official five-digit codes are separate
  from the one-byte wire codes. See [master provenance and allocation rules](../../../../../resources/municipalities/README.md).
  The version is kyushu-2026-09-26-v1. Both receive APIs require peerMasterVersion
  from the connection handshake and reject null/unknown/different versions before
  any DB changes, even for duplicate records. Never substitute the local version
  for an unknown peer version. municipalityMasterVersion() exposes the local value.
  The actual BLE version handshake belongs to the communication layer; it adds no
  fields to the two-integer WireRecord. Existing allocations must never be renumbered.
- NEW_PERSON means inserted; DUPLICATE means already stored at the same location;
  ID_ALREADY_KNOWN keeps the first location. CAPACITY_REJECTED means NOT stored.
  Capacity is 13,000,000 people including self. A completed batch can contain
  capacity rejections; it is not a blanket successful-save acknowledgement.
- Invalid arguments throw before enqueueing. DB/session failures complete the
  future exceptionally. Receive rows, watch-target histories and revision updates
  commit or roll back together. Futures complete only after commit returns.
- Repeated self registration during an active period is rejected. New periods
  clear old relay records but retain history. A stale connection token is rejected.
- currentSession() restores the local token after restart. It may return null or
  an expired session; reading it does not activate communication.
- WatchTarget rejects null, empty and whitespace-only names, including full-width
  spaces. addWatchTarget() is insert-only and does not notify for earlier records.
- validHistories() returns only historyExpiresAt > now; deleteExpiredHistories()
  deletes historyExpiresAt <= now and returns the deleted count. There is no
  unconditional history-list API. The UI is not connected to history yet and must
  use validHistories(), refreshing when displayed or when expiry is reached.
- Scope excludes BLE communication, notification delivery/UI, scheduled background
  execution, arbitrary clock tampering across reboots, and synchronization protocols/digests.
  Explicit 72-hour completion/cleanup and monotonic clock gates are implemented; see above.
- No UI or BLE implementation is included here. BLE ACK integration remains with
  the communication owner. Do not serialize Room entities.

Tests: WireRecordTest covers numeric boundaries and transport fields.
MunicipalityMasterTest and WatchTargetTest cover master/name validation.
SafetyRepositoryTest uses a file-backed Room DB and covers duplicate/location
handling, capacity (a reduced test limit), concurrent receives, reopen persistence,
session isolation, unknown municipality rejection, resolved name snapshots, exact
history expiry boundaries, selective cleanup and an SQLite-trigger-induced rollback.
The 13-million-row physical storage/performance test is not included.

SessionStatusTest covers deadline boundaries, immutable snapshots, session-token
checks, relay blocking and clock rollback. SessionExpiryTest uses a file-backed
Room DB for expiry/reopen, delayed cleanup, retention, idempotency, concurrent
expiry/receipt/new-session creation, expiry during receipt/page reads and injected
SQLite state/deletion failures. It also checks commit visibility from another DB
instance and SC04/SC06 consistency. The tests inject time without waiting 72 hours.
