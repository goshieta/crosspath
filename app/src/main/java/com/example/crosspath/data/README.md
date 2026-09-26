# Room storage API (stages 2, 4 and 5)

## 72時間の期限管理 / 通信・UI担当との契約

セッションの正本はDBの `startedAtWall` と `endsAtWall` です。市町村確定時の
開始時刻＋72時間を一度だけ保存し、Repository再生成・DB再オープン・復帰・終了処理で
延長しません。`now >= endsAtWall`（終了時刻ちょうどを含む）で期限切れです。
カウントダウン値は表示用スナップショットで、期限判定の正本ではありません。

| API | 契約 |
|---|---|
| `sessionStatus()` | 現在の状態、sessionId、保存済みの開始・予定終了時刻、非負のremainingMillis、canCommunicateを返す。読み取りのみ |
| `checkAndEndExpiredSession()` | 起動・復帰・通信開始時の入口。現在セッションの期限確認と、必要な終了・配信データ削除を同一トランザクションで行い、コミット後の状態を返す |
| `endExpiredSession(expectedSessionId)` | 指定セッションだけを期限終了する。古いコールバックや再試行用。予定終了時刻は変更しない |
| `receive()` / `applyReceivedBatch()` | 入口と全書き込み後で通信可否・セッションIDを確認。途中で期限に達した場合はレコード・通知履歴・revisionをすべてロールバック |
| `pageAfter()` | ページ取得の前後で通信可否・セッションIDを確認。期限切れ等では空リストを返す代わりにFutureが例外完了 |
| `currentWatchStatuses()` | 同じ期限判定を利用。期限切れ・終了済み・時計不整合ではSC04／SC06用にNO_ACTIVE_SESSION（現在の通信期間なし）を返す |

`SessionStatus.State` は `NO_SESSION`（なし）、`ACTIVE`（有効）、
`EXPIRED`（期限切れ・終了処理前）、`ENDED`（終了済み）を区別します。
時計の巻き戻しを検出した場合は追加の `CLOCK_UNCERTAIN` を返し、通信を拒否します。
`remainingMillis` は有効期間だけ残りミリ秒、それ以外は0です。
`canCommunicate` はACTIVEかつ保存済みrelayEnabledがtrueの場合だけtrueです。
`canCommunicate(expectedSessionId)` は接続時のID一致も確認します。
通信権限・Bluetooth・接続状態の検査は通信側の責務です。relayEnabledがfalseでも
有効期間の受信表示は保持されます（期間終了とは区別）。

`EndResult` は `NO_SESSION`、`STALE_SESSION`、`NOT_EXPIRED`、`ENDED`、
`ALREADY_ENDED` です。前3つは変更なし。初回成功だけENDED、再実行はALREADY_ENDEDです。
状態をENDED／relayEnabled=falseへ更新し、対象IDのSafetyRecordを削除します。
WatchTargetとNotificationHistoryには触れません。並行受信・終了・次期間開始は
Roomの書き込みトランザクションで直列化されます。DB例外時は全体をロールバックし、
Futureは例外完了します。成功結果は `runInTransaction` のコミット完了後だけ返します。
失敗時は通信を止め、次回に同じIDで再試行してください。期限切れのACTIVE行が残っても
通常の時計下では期限ゲートが受信保存と送信ページ取得を拒否します。

### 呼び出し順序

1. アプリ起動・復帰時は `checkAndEndExpiredSession()` の完了を待ち、
   `deleteExpiredHistories()` を実行してから画面データを再取得します。
   `currentSession()` は互換性のため残す生のDBスナップショットです。
   通信開始やUIの期限判定に直接使わず、`SessionStatus` を使ってください。
2. 通信開始前にも `checkAndEndExpiredSession()` を呼び、canCommunicateがtrueの場合に
   そのsessionIdを接続・予約処理・受信コールバック・送信待ちデータへ付けます。
3. 各送信ページ取得前にも期限確認・終了処理を呼び、接続IDとの一致を確認してから
   `pageAfter(connectionSessionId, lastUserId, limit)` を呼びます。
4. **ページ取得後、実際の送信直前にも** `sessionStatus()` を呼び、
   `canCommunicate(connectionSessionId)` を確認します。各フラグメントも同様です。
   判定結果は通信の有効期限を確保するものではなく、取得時点のスナップショットです。
5. 受信は接続開始時に保持したIDをreceive／applyReceivedBatchへ渡します。
   新セッションのIDへ差し替えないでください。成功完了後だけ保存ACKを検討し、
   CAPACITY_REJECTEDを保存成功にしないでください。
6. 期限切れ・終了・例外を検出した通信側は、**対象sessionIdの**予約処理、送信待ちデータ、
   バッファ、広告・探索、実行中のBLE通信を停止・破棄してください。
   古いセッションの停止要求で新しいセッションを停止しないよう、通信側でもIDを照合します。
   DB削除だけではメモリ上のデータや実行中のBLE通信は停止しません。

以下は送信ページ取得までの例です。呼び出し側で例外完了を処理し、UI操作はUIスレッド、
通信操作は通信担当のExecutorへ戻してください。

```java
// 起動・復帰: この結果が通信の入口になる。
repository.checkAndEndExpiredSession().thenAccept(status -> {
    // status.state / status.remainingMillis を表示。
    // 通信を開始するなら status.canCommunicate と status.sessionId を使用。
});

// connectionSessionId は接続開始時に捕捉したID。
repository.checkAndEndExpiredSession().thenCompose(status -> {
    if (!status.canCommunicate(connectionSessionId)) {
        throw new IllegalStateException("Connection session is no longer active");
    }
    return repository.pageAfter(connectionSessionId, lastUserId, 256);
}).thenCompose(page -> repository.sessionStatus().thenApply(status -> {
    if (!status.canCommunicate(connectionSessionId)) {
        throw new IllegalStateException("Discard this connection's queued page");
    }
    return page; // 通信側へ渡す。実際のBLE呼び出し直前にもゲートを再確認する。
}));

// セッションを捕捉済みの期限コールバック・再試行:
repository.endExpiredSession(connectionSessionId);
```

通知履歴は予定終了時刻＋100時間まで保持し、終了処理の遅延で期限をずらしません。
既存の履歴削除、過去セッションを含むPENDING再取得、配信状態の条件付き更新契約を
維持しています（下記Stage 4参照）。設計草案には旧期間PENDINGの配信抑止案もありますが、
今回の依頼の「既存契約を維持」を優先し、配信担当の処理には変更を加えていません。

### 時刻・バックグラウンド実行の保証範囲

本番時計は `System.currentTimeMillis()`（UTC Unixミリ秒）です。
既存の `startedAtElapsed` は開始時に記録されていますが、`bootMarker` は未設定で、
再起動前後の単調時計を安全に比較する仕組みがありません。今回は永続化済み壁時計を
共通判定に使用します。設計草案9.1の「同一起動中はelapsedRealtimeを主時計にする」
方式は未実装です。時計注入はpackage-privateコンストラクターだけに限定し、
本番の公開APIに現在時刻・期限の任意指定は追加していません。

通常の時計下では、プロセス・端末再起動を経ても保存済みの期限が維持されます。
現在時刻が開始時刻／保存済みlastObservedWallより前なら通信を保守的に拒否します。
lastObservedWallは開始・新規受信の保存時に更新され、毎回の時刻観測を記録するものでは
ありません。この検出で捉えられない巻き戻しは、実時間に対する有効期間・履歴保持を
長くする場合があります。時計を進めると早期終了する場合があります。
一度コミット済みのENDEDは時計を戻しても復活しません。
任意の時計改変や再起動をまたぐ厳密な実時間72時間／100時間は保証しません。

DB処理では最後の読み書き後に再確認しますが、最終確認とSQLiteコミット／Future通知／
BLE送信を壁時計と原子的に同期させることはできません。その短い間に期限を迎える場合も
あるため、通信側の送信直前チェックとセッション別停止が必要です。
MainActivity接続、常駐サービス、OSスケジューラーは今回の範囲外です。
**停止中に72時間ちょうどでバックグラウンド実行・物理削除される保証はありません。**
次回のcheckAndEndExpiredSession呼び出しで終了処理します。画面の再描画や期限確認の
呼び出し予約もUI／通信担当が接続します。SQLite DELETEは復元不能な物理消去ではありません。

結合後は、送信待ち・BLE実行中の期限到達、古い停止イベントと新接続の競合、
省電力・強制停止・再起動からの復帰、SC04／SC06再描画、時計変更時の案内を検証してください。
このデータ層のテストだけでBLEの停止時刻やバックグラウンド動作を保証するものではありません。

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
across all sessions, filtered by retention expiry; NEVER derive current receipt
marks from it. Neither read changes or automatically ends a session.

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
  execution, robust clock-change handling, and synchronization protocols/digests.
  Explicit 72-hour completion/cleanup and wall-clock gates are implemented; see above.
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
