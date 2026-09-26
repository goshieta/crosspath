package com.example.crosspath.data;

import android.os.SystemClock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

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
    private final LongSupplier clock;
    private final long capacity;
    private final MunicipalityMaster municipalities;

    public enum Outcome { NEW_PERSON, DUPLICATE, ID_ALREADY_KNOWN, CAPACITY_REJECTED }

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
        this(db, executor, System::currentTimeMillis, MAX_RECORDS, municipalities);
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
        if (capacity < 1 || capacity > MAX_RECORDS) throw new IllegalArgumentException("capacity");
        this.db = Objects.requireNonNull(db);
        this.executor = Objects.requireNonNull(executor);
        this.clock = Objects.requireNonNull(clock);
        this.capacity = capacity;
        this.municipalities = Objects.requireNonNull(municipalities);
    }

    /** Atomically start a local period and save self; refuses changes during an active period. */
    public CompletableFuture<String> startSession(int userId, int municipalityCode) {
        WireRecord self = new WireRecord(userId, municipalityCode);
        self.requireMunicipalityName(municipalities);
        long confirmedAt = clock.getAsLong();
        long confirmedElapsed = SystemClock.elapsedRealtime();
        return CompletableFuture.supplyAsync(() -> db.runInTransaction(() -> {
            SafetyDao dao = db.safetyDao();
            ActiveSession old = dao.session();
            if (old != null && "ACTIVE".equals(old.state) && clock.getAsLong() < old.endsAtWall) {
                throw new IllegalStateException("Session already active");
            }
            ActiveSession session = new ActiveSession();
            session.sessionId = UUID.randomUUID().toString();
            session.startedAtWall = confirmedAt;
            session.endsAtWall = Math.addExact(confirmedAt, SESSION_MS);
            if (clock.getAsLong() >= session.endsAtWall) throw new IllegalStateException("Session expired");
            session.startedAtElapsed = confirmedElapsed;
            session.lastObservedWall = confirmedAt;
            session.dataRevision = 1;
            dao.clearRecords();
            dao.saveSession(session);
            dao.insert(new SafetyRecord(self.userId, self.municipalityCode, confirmedAt,
                    "SELF", session.sessionId, 1));
            return session.sessionId;
        }), executor);
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
        return CompletableFuture.supplyAsync(() -> db.runInTransaction(() -> {
            SafetyDao dao = db.safetyDao();
            ActiveSession session = requireSession(localSessionId);
            long now = clock.getAsLong();
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
            requireSession(localSessionId);
            if (inserted > 0) {
                session.dataRevision = revision;
                session.lastObservedWall = Math.max(session.lastObservedWall, now);
                dao.saveSession(session);
            }
            return new BatchResult(outcomes, inserted, notifications);
        }), executor);
    }

    /** Bounded keyset pagination; only the two transport fields leave the storage layer. */
    public CompletableFuture<List<WireRecord>> pageAfter(String localSessionId, int lastUserId, int limit) {
        if (lastUserId < 0 || lastUserId > 0xFFFFFF || limit < 1 || limit > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("Invalid page bounds");
        }
        return CompletableFuture.supplyAsync(() -> db.runInTransaction(() -> {
            requireSession(localSessionId);
            return db.safetyDao().page(localSessionId, lastUserId, limit);
        }), executor);
    }

    public CompletableFuture<ActiveSession> currentSession() {
        return CompletableFuture.supplyAsync(() -> db.safetyDao().session(), executor);
    }

    public CompletableFuture<Boolean> addWatchTarget(WatchTarget target) {
        Objects.requireNonNull(target);
        return CompletableFuture.supplyAsync(
                () -> db.runInTransaction(() -> db.safetyDao().insertWatch(target) != -1), executor);
    }

    /** Insert-only: false means already registered; the original name is retained. */
    public CompletableFuture<Boolean> addWatchTarget(int userId, String displayName) {
        return addWatchTarget(new WatchTarget(userId, displayName, clock.getAsLong()));
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
        return CompletableFuture.supplyAsync(() -> db.runInTransaction(() -> {
            ActiveSession session = db.safetyDao().session();
            long now = clock.getAsLong();
            boolean active = session != null && "ACTIVE".equals(session.state)
                    && now >= session.startedAtWall && now < session.endsAtWall;
            List<WatchStatus> statuses = new ArrayList<>();
            for (SafetyDao.WatchRow row : db.safetyDao().watchStatuses(active ? session.sessionId : null)) {
                statuses.add(new WatchStatus(row.targetUserId, row.displayName,
                        !active ? WatchStatus.State.NO_ACTIVE_SESSION
                                : row.received ? WatchStatus.State.RECEIVED : WatchStatus.State.NOT_RECEIVED));
            }
            return Collections.unmodifiableList(statuses);
        }), executor);
    }

    /**
     * History display ONLY: includes unexpired histories across sessions.
     * Never use this to determine current receipt marks; use currentWatchStatuses().
     * Evaluate expiry when the queued read executes, not when it is enqueued.
     */
    public CompletableFuture<List<NotificationHistory>> validHistories() {
        return CompletableFuture.supplyAsync(
                () -> db.safetyDao().validHistories(clock.getAsLong()), executor);
    }

    /** Uses the repository clock at execution; no caller-controlled future cutoff is accepted. */
    public CompletableFuture<Integer> deleteExpiredHistories() {
        return CompletableFuture.supplyAsync(
                () -> db.safetyDao().deleteExpiredHistories(clock.getAsLong()), executor);
    }

    /** Recovery queue for any specified session (including past sessions), excluding expired rows. */
    public CompletableFuture<List<NotificationRequest>> findPendingNotifications(String sessionId) {
        Objects.requireNonNull(sessionId);
        return CompletableFuture.supplyAsync(() -> {
            List<NotificationRequest> pending = new ArrayList<>();
            for (NotificationHistory history : db.safetyDao().pendingNotifications(sessionId, clock.getAsLong())) {
                pending.add(new NotificationRequest(history.notificationId, history));
            }
            return Collections.unmodifiableList(pending);
        }, executor);
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
        return CompletableFuture.supplyAsync(() -> db.runInTransaction(
                () -> db.safetyDao().finishNotification(notificationId, state, clock.getAsLong()) == 1), executor);
    }

    private ActiveSession requireSession(String expectedId) {
        ActiveSession session = db.safetyDao().session();
        if (session == null || !session.sessionId.equals(expectedId)
                || !"ACTIVE".equals(session.state) || clock.getAsLong() >= session.endsAtWall) {
            throw new IllegalStateException("Inactive, stale or expired local session");
        }
        return session;
    }
}
