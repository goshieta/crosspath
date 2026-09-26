# Room storage API (stages 2 and 4)

## Stage 4 / SC04 integration

All APIs below return CompletableFuture and use the supplied background executor.
UI owners must dispatch results to their UI thread. SC04 renders `watchTargets()`;
screen layouts and Android notification delivery are outside this data-layer change.

```java
repository.addWatchTarget(0x123456, "Family"); // true: inserted; false: ID already registered
repository.watchTargets(); // immutable list of WatchTarget, ordered by targetUserId
repository.deleteWatchTarget(0x123456); // true: deleted; false: not registered
repository.deleteExpiredHistories(cutoffMillis); // expiry <= cutoff, returns deleted count
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
preserving the session, SafetyRecord and WatchTarget. Even early explicit history
cleanup does not make a stored person notify again in the current session.

Room's existing unique (localSessionId, targetUserId) index remains unchanged.
Unexpected history insert conflicts fail the whole transaction. Record inserts,
history inserts and session revision updates roll back together on DB failure.
Notification intents are not a delivery acknowledgement or an exactly-once
Android notification service; delivery and retry policy belong to the caller.

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
- Stage 2 scope excludes BLE communication, notification delivery/UI, automatic
  72-hour session completion/cleanup, scheduled history cleanup, robust reboot and
  clock-change handling, and synchronization protocols/digests. Basic wall-clock
  session guards and explicit history expiry queries/deletion are implemented here.
- No UI or BLE implementation is included here. BLE ACK integration remains with
  the communication owner. Do not serialize Room entities.

Tests: WireRecordTest covers numeric boundaries and transport fields.
MunicipalityMasterTest and WatchTargetTest cover master/name validation.
SafetyRepositoryTest uses a file-backed Room DB and covers duplicate/location
handling, capacity (a reduced test limit), concurrent receives, reopen persistence,
session isolation, unknown municipality rejection, resolved name snapshots, exact
history expiry boundaries, selective cleanup and an SQLite-trigger-induced rollback.
The 13-million-row physical storage/performance test is not included.
