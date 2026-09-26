# Room storage API (stage 2)

Create one AppDatabase for the application lifetime and pass a background
ExecutorService to SafetyRepository. Do not call Room directly from UI or BLE.
The caller owns the executor and database and closes them after pending work ends.

```java
AppDatabase db = AppDatabase.open(context);
ExecutorService dbExecutor = Executors.newSingleThreadExecutor();
SafetyRepository repository = new SafetyRepository(db, dbExecutor);

// On municipality confirmation: creates the local session and self record atomically.
repository.startSession(myUserId, municipalityCode).thenAccept(localSessionId -> {
    // Keep this local token with the connection; never send it as a person field.
});

// BLE receiver: call with the token captured when this connection began.
repository.receive(localSessionId, userId, municipalityCode)
    .whenComplete((result, error) -> {
        // This runs on the completing thread. Dispatch to the protocol executor.
        // error != null: do NOT send a success ACK.
        // error == null: DB commit completed; inspect every result.outcomes entry.
    });

// Batch receive (1..256 immutable WireRecord values):
repository.applyReceivedBatch(localSessionId, records);

// Send in bounded pages. Use the last returned userId as the next cursor.
repository.pageAfter(localSessionId, 0, 256);
```

- WireRecord contains only userId and municipalityCode. No timestamps are exchanged.
- IDs are 1..16,777,215; 0 is reserved per the draft. Locations are integers 0..255.
  Municipality master membership validation awaits the team's code table.
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
- addWatchTarget() is insert-only and does not notify for earlier received records.
  Notification delivery/UI, municipality names, automatic expiry cleanup, robust
  clock rollback/reboot handling and synchronization digests belong to later stages.
- No UI or BLE implementation is included here. BLE ACK integration remains with
  the communication owner. Do not serialize Room entities.

Tests: WireRecordTest covers numeric boundaries and transport fields.
SafetyRepositoryTest uses a file-backed Room DB and covers duplicate/location
handling, capacity (a reduced test limit), concurrent receives, reopen persistence,
session isolation and an SQLite-trigger-induced rollback.
The 13-million-row physical storage/performance test is not included.
