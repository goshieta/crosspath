package com.example.crosspath.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import java.util.function.Function;
import java.util.concurrent.Callable;

/**
 * UI/BLE-independent async storage API. Supply a background Executor (preferably single-threaded).
 * Futures succeed only AFTER runInTransaction returns, including its commit.
 * Exceptional completion must never be translated into a successful BLE ACK.
 */
public final class SafetyRepository {
    public static final long MAX_RECORDS = 13_000_000L;
    public static final int MAX_BATCH_SIZE = 256;
    private static final long SESSION_MS = 72L * 60 * 60 * 1000;
    private static final long HISTORY_MS = 100L * 60 * 60 * 1000;
    private final AppDatabase db;
    private final Executor executor;
    private final SessionClock clock;
    private final SessionStopHandler stopHandler;
    private final long capacity;
    private final MunicipalityMaster municipalities;

    public enum Outcome { NEW_PERSON, DUPLICATE, ID_ALREADY_KNOWN, CAPACITY_REJECTED }

    /** ENDED is returned only after the state change AND record deletion commit. */
    public enum EndResult { NO_SESSION, STALE_SESSION, NOT_EXPIRED, CLOCK_UNCERTAIN, ENDED, ALREADY_ENDED }

    /** Immutable notification intent. Delivery belongs to the caller, after successful completion. */
    public static final class NotificationRequest {
        public final long notificationId;
        public final String localSessionId;
        public final int targetUserId;
        public final String displayName;
        public final int municipalityCode;
        public final String municipalityName;
        public final long historyExpiresAt;

        private NotificationRequest(long notificationId, NotificationHistory history) {
            this.notificationId = notificationId;
            localSessionId = history.localSessionId;
            targetUserId = history.targetUserId;
            displayName = history.displayNameSnapshot;
            municipalityCode = history.municipalityCode;
            municipalityName = history.municipalityNameSnapshot;
            historyExpiresAt = history.historyExpiresAt;
        }
    }

    public static final class BatchResult {
        /** Same order as input; CAPACITY_REJECTED is not a saved record. */
        public final List<Outcome> outcomes;
        public final int inserted;
        /** Only newly saved watched people, in input order; empty means no notification needed. */
        public final List<NotificationRequest> notifications;
        BatchResult(List<Outcome> outcomes, int inserted, List<NotificationRequest> notifications) {
            this.outcomes = Collections.unmodifiableList(new ArrayList<>(outcomes));
            this.inserted = inserted;
            this.notifications = Collections.unmodifiableList(new ArrayList<>(notifications));
        }

        public boolean isNotificationRequired() { return !notifications.isEmpty(); }
    }

    public SafetyRepository(AppDatabase db, Executor executor, MunicipalityMaster municipalities) {
        this(db, executor, db.sessionClock(), MAX_RECORDS, municipalities, id -> { });
    }

    /** Connect the communication owner's idempotent, session-scoped stop operation. */
    public SafetyRepository(AppDatabase db, Executor executor, MunicipalityMaster municipalities,
            SessionStopHandler stopHandler) {
        this(db, executor, db.sessionClock(), MAX_RECORDS, municipalities, stopHandler);
    }

    public SafetyRepository(AppDatabase db, Executor executor) {
        this(db, executor, KyushuMunicipalities.load());
    }

    public String municipalityMasterVersion() {
        return municipalities.version;
    }

    // Smaller capacity and deterministic clock allow boundary tests without allocating 13M rows.
    SafetyRepository(AppDatabase db, Executor executor, LongSupplier clock, long capacity,
            MunicipalityMaster municipalities) {
        this(db, executor, () -> {
            long now = clock.getAsLong();
            return new SessionClock.Reading(now, now, "test-boot");
        }, capacity, municipalities, id -> { });
    }

    SafetyRepository(AppDatabase db, Executor executor, SessionClock clock, long capacity,
            MunicipalityMaster municipalities, SessionStopHandler stopHandler) {
        if (capacity < 1 || capacity > MAX_RECORDS) throw new IllegalArgumentException("capacity");
        this.db = Objects.requireNonNull(db);
        this.executor = Objects.requireNonNull(executor);
        this.clock = Objects.requireNonNull(clock);
        this.stopHandler = Objects.requireNonNull(stopHandler);
        this.capacity = capacity;
        this.municipalities = Objects.requireNonNull(municipalities);
    }

    /** Atomically start a local period and save self; refuses changes during an active period. */
    public CompletableFuture<String> startSession(int userId, int municipalityCode) {
        return startSessionAtConfirmation(userId, municipalityCode, new ConfirmationClock.Token(clock.read()));
    }

    /** UI captures the operation time before waiting for initialization/queueing. */
    public CompletableFuture<String> startSessionAtConfirmation(int userId, int municipalityCode,
            ConfirmationClock.Token confirmation) {
        SessionClock.Reading confirmed = Objects.requireNonNull(confirmation).reading;
        WireRecord self = new WireRecord(userId, municipalityCode);
        self.requireMunicipalityName(municipalities);
        Objects.requireNonNull(confirmed);
        long confirmedAt = confirmed.wall;
        return checkAndEndExpiredSession().thenCompose(ignored ->
                CompletableFuture.supplyAsync(() -> db.runInTransaction(() -> {
            SafetyDao dao = db.safetyDao();
            SessionStatus old = maintainInTransaction();
            if (old.state == SessionStatus.State.ACTIVE || old.state == SessionStatus.State.CLOCK_UNCERTAIN) {
                throw new IllegalStateException("Session already active");
            }
            if (confirmed.bootMarker.isEmpty()) throw new IllegalStateException("Boot identity unavailable");
            ActiveSession session = new ActiveSession();
            session.sessionId = UUID.randomUUID().toString();
            session.startedAtWall = confirmedAt;
            session.endsAtWall = Math.addExact(confirmedAt, SESSION_MS);
            session.startedAtElapsed = confirmed.elapsed;
            session.bootMarker = confirmed.bootMarker;
            session.lastObservedWall = confirmedAt;
            session.dataRevision = 1;
            dao.clearRecords();
            dao.saveSession(session);
            dao.insert(new SafetyRecord(self.userId, self.municipalityCode, confirmedAt,
                    "SELF", session.sessionId, 1));
            requireSession(session.sessionId);
            return session.sessionId;
        }), executor));
    }

    public CompletableFuture<BatchResult> receive(String localSessionId, String peerMasterVersion,
            int userId, int municipalityCode) {
        return applyReceivedBatch(localSessionId, peerMasterVersion,
                Collections.singletonList(new WireRecord(userId, municipalityCode)));
    }

    public CompletableFuture<BatchResult> applyReceivedBatch(String localSessionId,
            String peerMasterVersion, List<WireRecord> batch) {
        municipalities.requireVersion(peerMasterVersion);
        Objects.requireNonNull(localSessionId);
        Objects.requireNonNull(batch);
        if (batch.isEmpty() || batch.size() > MAX_BATCH_SIZE) throw new IllegalArgumentException("batch size 1..256");
        List<WireRecord> copy = new ArrayList<>(batch);
        for (WireRecord record : copy) {
            Objects.requireNonNull(record).requireMunicipalityName(municipalities);
        }
        return transaction(localSessionId, () -> {
            SafetyDao dao = db.safetyDao();
            ActiveSession session = requireSession(localSessionId);
            long now = clock.read().wall;
            long count = dao.count();
            int inserted = 0;
            long revision = session.dataRevision + 1;
            List<Outcome> outcomes = new ArrayList<>(copy.size());
            List<NotificationRequest> notifications = new ArrayList<>();
            for (WireRecord record : copy) {
                SafetyRecord existing = dao.find(record.userId);
                if (existing != null) {
                    outcomes.add(existing.municipalityCode == record.municipalityCode
                            ? Outcome.DUPLICATE : Outcome.ID_ALREADY_KNOWN);
                } else if (count >= capacity) {
                    outcomes.add(Outcome.CAPACITY_REJECTED);
                } else {
                    long row = dao.insert(new SafetyRecord(record.userId, record.municipalityCode,
                            now, "RECEIVED", localSessionId, revision));
                    if (row == -1) throw new IllegalStateException("Unexpected insert conflict");
                    WatchTarget target = dao.watch(record.userId);
                    if (target != null) {
                        NotificationHistory history = new NotificationHistory();
                        history.localSessionId = localSessionId;
                        history.sessionStartedAt = session.startedAtWall;
                        history.sessionEndedAt = session.endsAtWall;
                        history.historyExpiresAt = Math.addExact(session.endsAtWall, HISTORY_MS);
                        history.targetUserId = record.userId;
                        history.municipalityCode = record.municipalityCode;
                        history.displayNameSnapshot = target.displayName;
                        history.municipalityNameSnapshot = record.requireMunicipalityName(municipalities);
                        history.firstReceivedAt = now;
                        long historyId = dao.insertHistory(history);
                        if (historyId == -1) throw new IllegalStateException("Unexpected history insert conflict");
                        notifications.add(new NotificationRequest(historyId, history));
                    }
                    count++;
                    inserted++;
                    outcomes.add(Outcome.NEW_PERSON);
                }
            }
            if (inserted > 0) {
                session.dataRevision = revision;
                session.lastObservedWall = Math.max(session.lastObservedWall, now);
                dao.saveSession(session);
            }
            // Check after ALL writes too: expiry during receipt rolls back the whole batch.
            requireSession(localSessionId);
            return new BatchResult(outcomes, inserted, notifications);
        });
    }

    /** Bounded keyset pagination; only the two transport fields leave the storage layer. */
    public CompletableFuture<List<WireRecord>> pageAfter(String localSessionId, int lastUserId, int limit) {
        if (lastUserId < 0 || lastUserId > 0xFFFFFF || limit < 1 || limit > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("Invalid page bounds");
        }
        return transaction(localSessionId, () -> {
            requireSession(localSessionId);
            List<WireRecord> page = db.safetyDao().page(localSessionId, lastUserId, limit);
            requireSession(localSessionId);
            return page;
        });
    }

    /** Raw persistence snapshot for compatibility; use sessionStatus() for communication/UI gating. */
    public CompletableFuture<ActiveSession> currentSession() {
        return CompletableFuture.supplyAsync(() -> db.safetyDao().session(), executor);
    }

    /** Maintains expiry and history retention before returning the gate; may write/delete. */
    public CompletableFuture<SessionStatus> sessionStatus() {
        return checkAndEndExpiredSession();
    }

    /**
     * End only the named expired session. Safe to retry and to call concurrently with receipt,
     * session creation or another expiry call. Never rewrites the scheduled endsAtWall.
     * DB errors complete exceptionally; neither the state change nor deletion then commits.
     */
    public CompletableFuture<EndResult> endExpiredSession(String expectedSessionId) {
        Objects.requireNonNull(expectedSessionId);
        return transaction(expectedSessionId, () -> endExpiredSessionInTransaction(expectedSessionId))
                .thenApply(result -> {
                    if (result != EndResult.NOT_EXPIRED) stopHandler.stopSession(expectedSessionId);
                    return result;
                });
    }

    /**
     * Startup/resume/communication-start hook. Checks and cleans up the current period in one
     * transaction, returning its post-cleanup gate after commit. Does not schedule background work.
     */
    public CompletableFuture<SessionStatus> checkAndEndExpiredSession() {
        return maintained(status -> status);
    }

    private EndResult endExpiredSessionInTransaction(String expectedSessionId) {
        SafetyDao dao = db.safetyDao();
        ActiveSession session = dao.session();
        if (session == null) return EndResult.NO_SESSION;
        if (!session.sessionId.equals(expectedSessionId)) return EndResult.STALE_SESSION;
        if (!"ACTIVE".equals(session.state)) return EndResult.ALREADY_ENDED;
        ClockAnchor.Observation time = observeTime();
        if (time.anchor.wall < session.endsAtWall) {
            return time.trusted ? EndResult.NOT_EXPIRED : EndResult.CLOCK_UNCERTAIN;
        }
        if (dao.endExpiredSession(expectedSessionId) != 1) {
            throw new IllegalStateException("Session changed during expiry transaction");
        }
        dao.deleteSessionRecords(expectedSessionId);
        return EndResult.ENDED;
    }

    private ClockAnchor.Observation observeTime() {
        SessionClock.Reading reading = clock.read();
        ActiveSession session = db.safetyDao().session();
        long minimum = session == null ? 0 : Math.max(session.startedAtWall, session.lastObservedWall);
        boolean elapsedValid = true;
        if (session != null && !reading.bootMarker.isEmpty()
                && reading.bootMarker.equals(session.bootMarker)) {
            if (reading.elapsed < session.startedAtElapsed) {
                elapsedValid = false;
            } else {
                long delta = reading.elapsed - session.startedAtElapsed;
                long projected = session.startedAtWall > Long.MAX_VALUE - delta
                        ? Long.MAX_VALUE : session.startedAtWall + delta;
                minimum = Math.max(minimum, projected);
            }
        }
        ClockAnchor.Observation time = ClockAnchor.observe(db.safetyDao().clockAnchor(), reading, minimum);
        db.safetyDao().saveClockAnchor(time.anchor);
        return new ClockAnchor.Observation(time.anchor, time.trusted && elapsedValid);
    }

    private SessionStatus maintainInTransaction() {
        ClockAnchor.Observation time = observeTime();
        ActiveSession session = db.safetyDao().session();
        SessionStatus status = SessionStatus.evaluate(session, time.anchor.wall, time.trusted);
        if (status.state == SessionStatus.State.EXPIRED) {
            if (db.safetyDao().endExpiredSession(session.sessionId) != 1) {
                throw new IllegalStateException("Session changed during expiry transaction");
            }
            db.safetyDao().deleteSessionRecords(session.sessionId);
            status = SessionStatus.evaluate(db.safetyDao().session(), time.anchor.wall, time.trusted);
        }
        db.safetyDao().deleteExpiredHistories(time.anchor.wall);
        return status;
    }

    /** Stop callbacks are outside the transaction and are retried by subsequent maintenance calls. */
    private <T> CompletableFuture<T> maintained(Function<SessionStatus, T> action) {
        return CompletableFuture.supplyAsync(() -> {
            String[] affected = new String[1];
            SessionStatus[] gate = new SessionStatus[1];
            T result;
            try {
                result = db.runInTransaction(() -> {
                    ActiveSession session = db.safetyDao().session();
                    affected[0] = session == null ? null : session.sessionId;
                    gate[0] = maintainInTransaction();
                    return action.apply(gate[0]);
                });
            } catch (RuntimeException failure) {
                stopAfterFailure(affected[0], failure);
                throw failure;
            }
            if (gate[0].sessionId != null && !gate[0].canCommunicate) {
                stopHandler.stopSession(gate[0].sessionId);
            }
            return result;
        }, executor);
    }

    private <T> CompletableFuture<T> transaction(String sessionId, Callable<T> action) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return db.runInTransaction(action);
            } catch (RuntimeException failure) {
                stopAfterFailure(sessionId, failure);
                throw failure;
            }
        }, executor);
    }

    private void stopAfterFailure(String sessionId, RuntimeException failure) {
        if (sessionId == null) return;
        try {
            stopHandler.stopSession(sessionId);
        } catch (RuntimeException stopFailure) {
            if (stopFailure != failure) failure.addSuppressed(stopFailure);
        }
    }

    public CompletableFuture<Boolean> addWatchTarget(WatchTarget target) {
        Objects.requireNonNull(target);
        return CompletableFuture.supplyAsync(
                () -> db.runInTransaction(() -> db.safetyDao().insertWatch(target) != -1), executor);
    }

    /** Insert-only: false means already registered; the original name is retained. */
    public CompletableFuture<Boolean> addWatchTarget(int userId, String displayName) {
        return addWatchTarget(new WatchTarget(userId, displayName, clock.read().wall));
    }

    /** SC04 display data, sorted by personal ID. Values and the returned list are immutable. */
    public CompletableFuture<List<WatchTarget>> watchTargets() {
        return CompletableFuture.supplyAsync(() -> Collections.unmodifiableList(
                new ArrayList<>(db.safetyDao().watchTargets())), executor);
    }

    /** Deletes only the registration, preserving previously saved records and history. */
    public CompletableFuture<Boolean> deleteWatchTarget(int userId) {
        WireRecord.requireUserId(userId);
        return CompletableFuture.supplyAsync(() -> db.runInTransaction(
                () -> db.safetyDao().deleteWatchTarget(userId) != 0), executor);
    }

    /**
     * SC04/SC06 current-period status, read atomically with the session and records.
     * Expired/inactive/missing sessions yield NO_ACTIVE_SESSION for every target.
     * SELF records are not received records. An empty list means no registered targets.
     */
    public CompletableFuture<List<WatchStatus>> currentWatchStatuses() {
        return maintained(status -> {
            ActiveSession session = db.safetyDao().session();
            List<SafetyDao.WatchRow> rows = db.safetyDao().watchStatuses(
                    session == null ? null : session.sessionId);
            boolean active = status.state == SessionStatus.State.ACTIVE;
            List<WatchStatus> statuses = new ArrayList<>();
            for (SafetyDao.WatchRow row : rows) {
                statuses.add(new WatchStatus(row.targetUserId, row.displayName,
                        !active ? WatchStatus.State.NO_ACTIVE_SESSION
                                : row.received ? WatchStatus.State.RECEIVED : WatchStatus.State.NOT_RECEIVED));
            }
            return Collections.unmodifiableList(statuses);
        });
    }

    /**
     * History display ONLY: includes unexpired histories across sessions.
     * Never use this to determine current receipt marks; use currentWatchStatuses().
     * Evaluate expiry when the queued read executes, not when it is enqueued.
     */
    public CompletableFuture<List<NotificationHistory>> validHistories() {
        return maintained(status -> db.safetyDao().validHistories(db.safetyDao().clockAnchor().wall));
    }

    /** Uses the repository clock at execution; no caller-controlled future cutoff is accepted. */
    public CompletableFuture<Integer> deleteExpiredHistories() {
        return transaction(null, () -> db.safetyDao().deleteExpiredHistories(observeTime().anchor.wall));
    }

    /** Recovery queue for any specified session (including past sessions), excluding expired rows. */
    public CompletableFuture<List<NotificationRequest>> findPendingNotifications(String sessionId) {
        Objects.requireNonNull(sessionId);
        return maintained(status -> {
            List<NotificationRequest> pending = new ArrayList<>();
            for (NotificationHistory history : db.safetyDao().pendingNotifications(sessionId,
                    db.safetyDao().clockAnchor().wall)) {
                pending.add(new NotificationRequest(history.notificationId, history));
            }
            return Collections.unmodifiableList(pending);
        });
    }

    /** Compare-and-set PENDING -> POSTED; false for missing, expired or already finalized rows. */
    public CompletableFuture<Boolean> markNotificationPosted(long notificationId) {
        return finishNotification(notificationId, "POSTED");
    }

    /** Compare-and-set PENDING -> BLOCKED_PERMISSION; no automatic retry after permission denial. */
    public CompletableFuture<Boolean> markNotificationBlocked(long notificationId) {
        return finishNotification(notificationId, "BLOCKED_PERMISSION");
    }

    private CompletableFuture<Boolean> finishNotification(long notificationId, String state) {
        if (notificationId <= 0) throw new IllegalArgumentException("notificationId must be positive");
        return maintained(status -> db.safetyDao().finishNotification(notificationId, state,
                db.safetyDao().clockAnchor().wall) == 1);
    }

    private ActiveSession requireSession(String expectedId) {
        ActiveSession session = db.safetyDao().session();
        ClockAnchor.Observation time = observeTime();
        if (!SessionStatus.evaluate(session, time.anchor.wall, time.trusted).canCommunicate(expectedId)) {
            throw new IllegalStateException("Inactive, stale or expired local session");
        }
        return session;
    }

    /**
     * Serializes the final gate, OS post and delivery update with session replacement.
     * OS + SQLite are not atomic: a crash retries the same stable OS notification key.
     * A failure leaves PENDING. Old public queue APIs remain compatible, but dispatchers
     * must use this operation rather than posting a previously fetched queue themselves.
     */
    public CompletableFuture<Integer> dispatchCurrentNotifications(NotificationSink sink) {
        Objects.requireNonNull(sink);
        return maintained(status -> {
            if (status.state != SessionStatus.State.ACTIVE) return 0;
            int count = 0;
            for (NotificationHistory history : db.safetyDao().deliveryPage(status.sessionId,
                    db.safetyDao().clockAnchor().wall)) {
                ClockAnchor.Observation now = observeTime();
                SessionStatus gate = SessionStatus.evaluate(db.safetyDao().session(), now.anchor.wall, now.trusted);
                if (gate.state != SessionStatus.State.ACTIVE || !Objects.equals(gate.sessionId, history.localSessionId)) break;
                NotificationSink.Delivery delivery = sink.post(new NotificationRequest(history.notificationId, history));
                if (delivery == null) throw new IllegalStateException("Missing delivery outcome");
                db.safetyDao().finishNotification(history.notificationId, delivery.name(), now.anchor.wall);
                count++;
            }
            return count;
        });
    }

    /** Explicit user retry after permission/settings change; never revives old periods. */
    public CompletableFuture<Integer> retryBlockedNotifications() {
        return maintained(status -> status.state != SessionStatus.State.ACTIVE ? 0
                : db.safetyDao().retryBlocked(status.sessionId, db.safetyDao().clockAnchor().wall));
    }

    /** OS reconciliation/tap lookup uses the same retained clock, even after DB deletion. */
    public CompletableFuture<Boolean> isHistoryValid(long id) {
        return maintained(status -> db.safetyDao().hasValidHistory(id, db.safetyDao().clockAnchor().wall) != 0);
    }

    public CompletableFuture<NotificationDeliveryStatus> notificationDeliveryStatus() {
        return maintained(status -> status.state != SessionStatus.State.ACTIVE
                ? new NotificationDeliveryStatus(0, 0, 0)
                : db.safetyDao().deliveryStatus(status.sessionId, db.safetyDao().clockAnchor().wall));
    }
}
