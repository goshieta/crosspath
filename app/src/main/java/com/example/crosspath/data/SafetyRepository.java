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

    public enum Outcome { NEW_PERSON, DUPLICATE, ID_ALREADY_KNOWN, CAPACITY_REJECTED }

    public static final class BatchResult {
        /** Same order as input; CAPACITY_REJECTED is not a saved record. */
        public final List<Outcome> outcomes;
        public final int inserted;
        BatchResult(List<Outcome> outcomes, int inserted) {
            this.outcomes = Collections.unmodifiableList(new ArrayList<>(outcomes));
            this.inserted = inserted;
        }
    }

    public SafetyRepository(AppDatabase db, Executor executor) {
        this(db, executor, System::currentTimeMillis, MAX_RECORDS);
    }

    // Smaller capacity and deterministic clock allow boundary tests without allocating 13M rows.
    SafetyRepository(AppDatabase db, Executor executor, LongSupplier clock, long capacity) {
        if (capacity < 1 || capacity > MAX_RECORDS) throw new IllegalArgumentException("capacity");
        this.db = Objects.requireNonNull(db);
        this.executor = Objects.requireNonNull(executor);
        this.clock = Objects.requireNonNull(clock);
        this.capacity = capacity;
    }

    /** Atomically start a local period and save self; refuses changes during an active period. */
    public CompletableFuture<String> startSession(int userId, int municipalityCode) {
        WireRecord self = new WireRecord(userId, municipalityCode);
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

    public CompletableFuture<BatchResult> receive(String localSessionId, int userId, int municipalityCode) {
        return applyReceivedBatch(localSessionId,
                Collections.singletonList(new WireRecord(userId, municipalityCode)));
    }

    public CompletableFuture<BatchResult> applyReceivedBatch(String localSessionId, List<WireRecord> batch) {
        Objects.requireNonNull(localSessionId);
        Objects.requireNonNull(batch);
        if (batch.isEmpty() || batch.size() > MAX_BATCH_SIZE) throw new IllegalArgumentException("batch size 1..256");
        List<WireRecord> copy = new ArrayList<>(batch);
        for (WireRecord record : copy) Objects.requireNonNull(record);
        return CompletableFuture.supplyAsync(() -> db.runInTransaction(() -> {
            SafetyDao dao = db.safetyDao();
            ActiveSession session = requireSession(localSessionId);
            long now = clock.getAsLong();
            long count = dao.count();
            int inserted = 0;
            long revision = session.dataRevision + 1;
            List<Outcome> outcomes = new ArrayList<>(copy.size());
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
                        // Municipality names require the future municipality master integration.
                        history.firstReceivedAt = now;
                        dao.insertHistory(history);
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
            return new BatchResult(outcomes, inserted);
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

    private ActiveSession requireSession(String expectedId) {
        ActiveSession session = db.safetyDao().session();
        if (session == null || !session.sessionId.equals(expectedId)
                || !"ACTIVE".equals(session.state) || clock.getAsLong() >= session.endsAtWall) {
            throw new IllegalStateException("Inactive, stale or expired local session");
        }
        return session;
    }
}
