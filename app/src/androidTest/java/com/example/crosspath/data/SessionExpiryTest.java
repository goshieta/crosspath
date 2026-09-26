package com.example.crosspath.data;

import android.content.Context;
import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import static org.junit.Assert.*;

/** Real SQLite transactions, file reopen, injected failures and deterministic expiry races. */
@RunWith(AndroidJUnit4.class)
public class SessionExpiryTest {
    private Context context;
    private AppDatabase db;
    private ExecutorService executor;
    private SafetyRepository repository;
    private String file;
    private String sessionId;
    private long start;
    private long end;
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final MunicipalityMaster master = new MunicipalityMaster("expiry-test",
            Collections.singletonMap(1, "Test municipality"));

    @Before public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        file = "expiry-" + UUID.randomUUID() + ".db";
        db = Room.databaseBuilder(context, AppDatabase.class, file).build();
        executor = Executors.newFixedThreadPool(4);
        repository = withClock(now::get);
        sessionId = await(repository.startSession(1, 1));
        start = db.safetyDao().session().startedAtWall;
        end = db.safetyDao().session().endsAtWall;
    }

    @After public void tearDown() throws Exception {
        executor.shutdown();
        assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));
        db.close();
        context.deleteDatabase(file);
    }

    private SafetyRepository withClock(LongSupplier clock) {
        return new SafetyRepository(db, executor, clock, SafetyRepository.MAX_RECORDS, master);
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(10, TimeUnit.SECONDS);
    }

    private static void rejected(CompletableFuture<?> future) {
        ExecutionException error = assertThrows(ExecutionException.class, () -> await(future));
        assertTrue(error.getCause() instanceof IllegalStateException);
        assertTrue(future.isCompletedExceptionally());
    }

    private void watchedReceipt() throws Exception {
        await(repository.addWatchTarget(2, "Target"));
        await(repository.receive(sessionId, master.version, 2, 1));
    }

    @Test public void exactBoundaryAndPrematureCleanupAgreeWithDisplayAndTransport() throws Exception {
        watchedReceipt();
        assertEquals(TimeUnit.HOURS.toMillis(72), end - start);
        now.set(end - 1);
        assertEquals(SafetyRepository.EndResult.NOT_EXPIRED,
                await(repository.endExpiredSession(sessionId)));
        SessionStatus before = await(repository.checkAndEndExpiredSession());
        assertEquals(SessionStatus.State.ACTIVE, before.state);
        assertEquals(1, before.remainingMillis);
        assertTrue(before.canCommunicate(sessionId));
        assertEquals(2, db.safetyDao().count());
        assertEquals(WatchStatus.State.RECEIVED, await(repository.currentWatchStatuses()).get(0).state);
        assertEquals(2, await(repository.pageAfter(sessionId, 0, 256)).size());
        await(repository.receive(sessionId, master.version, 3, 1));
        for (long time : new long[]{end, end + 1}) {
            now.set(time);
            SessionStatus expired = await(repository.sessionStatus());
            assertEquals(SessionStatus.State.ENDED, expired.state);
            assertEquals(0, expired.remainingMillis);
            assertFalse(expired.canCommunicate);
            assertEquals(WatchStatus.State.NO_ACTIVE_SESSION,
                    await(repository.currentWatchStatuses()).get(0).state);
            rejected(repository.receive(sessionId, master.version, 4, 1));
            rejected(repository.applyReceivedBatch(sessionId, master.version,
                    Arrays.asList(new WireRecord(4, 1), new WireRecord(5, 1))));
            rejected(repository.pageAfter(sessionId, 0, 256));
        }
        now.set(end);
        assertEquals(SafetyRepository.EndResult.ALREADY_ENDED, await(repository.endExpiredSession(sessionId)));
        assertEquals(0, db.safetyDao().count());
        assertEquals(SessionStatus.State.ENDED, await(repository.sessionStatus()).state);
        assertFalse(db.safetyDao().session().relayEnabled);
        assertEquals(end, db.safetyDao().session().endsAtWall);
        assertEquals(WatchStatus.State.NO_ACTIVE_SESSION,
                await(repository.currentWatchStatuses()).get(0).state);
        rejected(repository.receive(sessionId, master.version, 4, 1));
        rejected(repository.applyReceivedBatch(sessionId, master.version,
                Collections.singletonList(new WireRecord(4, 1))));
        rejected(repository.pageAfter(sessionId, 0, 256));
    }

    @Test public void reopenAndRepositoryRecreationPreserveOriginalDeadline() throws Exception {
        watchedReceipt();
        now.set(end - 1);
        db.close();
        db = Room.databaseBuilder(context, AppDatabase.class, file).build();
        repository = withClock(now::get);
        SessionStatus restored = await(repository.checkAndEndExpiredSession());
        assertEquals(sessionId, restored.sessionId);
        assertEquals(start, restored.startedAtWall);
        assertEquals(end, restored.endsAtWall);
        assertEquals(1, restored.remainingMillis);
        // No process ran at the deadline; resume performs the delayed cleanup.
        now.set(end + TimeUnit.HOURS.toMillis(3));
        db.close();
        db = Room.databaseBuilder(context, AppDatabase.class, file).build();
        repository = withClock(now::get);
        assertEquals(SessionStatus.State.ENDED, await(repository.checkAndEndExpiredSession()).state);
        assertEquals(0, db.safetyDao().count());
        assertEquals(end, db.safetyDao().session().endsAtWall);
        assertEquals(1, await(repository.validHistories()).size());
    }

    @Test public void concurrentExpiryIsIdempotentAcrossRepositoryInstances() throws Exception {
        watchedReceipt();
        now.set(end);
        SafetyRepository other = withClock(now::get);
        CompletableFuture<SafetyRepository.EndResult> first = repository.endExpiredSession(sessionId);
        CompletableFuture<SafetyRepository.EndResult> second = other.endExpiredSession(sessionId);
        java.util.List<SafetyRepository.EndResult> results = Arrays.asList(await(first), await(second));
        assertEquals(1, Collections.frequency(results, SafetyRepository.EndResult.ENDED));
        assertEquals(1, Collections.frequency(results, SafetyRepository.EndResult.ALREADY_ENDED));
        assertEquals(SafetyRepository.EndResult.ALREADY_ENDED,
                await(repository.endExpiredSession(sessionId)));
        assertEquals(SessionStatus.State.ENDED, await(repository.checkAndEndExpiredSession()).state);
        assertEquals(0, db.safetyDao().count());
        assertEquals(1, await(repository.watchTargets()).size());
        assertEquals(1, await(repository.validHistories()).size());
    }

    @Test public void oldCleanupAndCallbacksCannotChangeNewSession() throws Exception {
        watchedReceipt();
        now.set(end);
        await(repository.endExpiredSession(sessionId));
        String next = await(repository.startSession(1, 1));
        await(repository.receive(next, master.version, 2, 1));
        ActiveSession before = db.safetyDao().session();
        assertEquals(SafetyRepository.EndResult.STALE_SESSION,
                await(repository.endExpiredSession(sessionId)));
        rejected(repository.receive(sessionId, master.version, 3, 1));
        rejected(repository.applyReceivedBatch(sessionId, master.version,
                Collections.singletonList(new WireRecord(3, 1))));
        rejected(repository.pageAfter(sessionId, 0, 256));
        assertFalse(await(repository.sessionStatus()).canCommunicate(sessionId));
        assertTrue(await(repository.sessionStatus()).canCommunicate(next));
        assertEquals(before.dataRevision, db.safetyDao().session().dataRevision);
        assertEquals(before.endsAtWall, db.safetyDao().session().endsAtWall);
        assertEquals(2, db.safetyDao().count());
        assertEquals(2, await(repository.validHistories()).size());
        assertEquals(WatchStatus.State.RECEIVED, await(repository.currentWatchStatuses()).get(0).state);
    }

    @Test public void delayedCleanupKeepsHistoryDeadlinePendingAndDeliveryContracts() throws Exception {
        watchedReceipt();
        await(repository.addWatchTarget(3, "Other"));
        await(repository.receive(sessionId, master.version, 3, 1));
        long expiry = end + TimeUnit.HOURS.toMillis(100);
        now.set(end + TimeUnit.HOURS.toMillis(20));
        await(repository.checkAndEndExpiredSession());
        assertEquals(end, db.safetyDao().session().endsAtWall);
        assertEquals(2, await(repository.watchTargets()).size());
        for (NotificationHistory history : await(repository.validHistories())) {
            assertEquals(end, history.sessionEndedAt);
            assertEquals(expiry, history.historyExpiresAt);
        }
        java.util.List<SafetyRepository.NotificationRequest> pending =
                await(repository.findPendingNotifications(sessionId));
        assertEquals(2, pending.size());
        assertTrue(await(repository.markNotificationPosted(pending.get(0).notificationId)));
        assertFalse(await(repository.markNotificationBlocked(pending.get(0).notificationId)));
        assertTrue(await(repository.markNotificationBlocked(pending.get(1).notificationId)));
        assertTrue(await(repository.findPendingNotifications(sessionId)).isEmpty());
        now.set(expiry - 1);
        assertEquals(0, (int) await(repository.deleteExpiredHistories()));
        assertEquals(2, await(repository.validHistories()).size());
        now.set(expiry);
        assertTrue(await(repository.validHistories()).isEmpty());
        assertTrue(db.safetyDao().validHistories(0).isEmpty());
        assertEquals(0, (int) await(repository.deleteExpiredHistories()));
        assertEquals(2, await(repository.watchTargets()).size());
    }

    @Test public void deletionFailureRollsBackStateAndAllDeletedRowsThenRetryCommits() throws Exception {
        watchedReceipt();
        now.set(end);
        db.getOpenHelper().getWritableDatabase().execSQL(
                "CREATE TRIGGER fail_expiry AFTER DELETE ON SafetyRecord "
                + "BEGIN SELECT RAISE(ABORT, 'expiry delete failure'); END");
        CompletableFuture<SafetyRepository.EndResult> failed = repository.endExpiredSession(sessionId);
        assertThrows(ExecutionException.class, () -> await(failed));
        assertTrue(failed.isCompletedExceptionally());
        assertEquals("ACTIVE", db.safetyDao().session().state);
        assertTrue(db.safetyDao().session().relayEnabled);
        assertEquals(2, db.safetyDao().count());
        assertNotNull(db.safetyDao().find(1));
        assertNotNull(db.safetyDao().find(2));
        assertEquals(1, db.safetyDao().validHistories(now.get()).size());
        assertThrows(ExecutionException.class, () -> await(repository.sessionStatus()));
        rejected(repository.receive(sessionId, master.version, 3, 1));
        rejected(repository.pageAfter(sessionId, 0, 256));
        assertThrows(ExecutionException.class, () -> await(repository.checkAndEndExpiredSession()));
        db.getOpenHelper().getWritableDatabase().execSQL("DROP TRIGGER fail_expiry");
        // A separate DB instance sees the committed state when the future completes.
        AppDatabase observer = Room.databaseBuilder(context, AppDatabase.class, file).build();
        try {
            await(repository.endExpiredSession(sessionId).thenAccept(result -> {
                assertEquals(SafetyRepository.EndResult.ENDED, result);
                assertEquals("ENDED", observer.safetyDao().session().state);
                assertEquals(0, observer.safetyDao().count());
            }));
        } finally {
            observer.close();
        }
    }

    @Test public void stateUpdateFailureDoesNotDeleteRecords() throws Exception {
        watchedReceipt();
        now.set(end);
        db.getOpenHelper().getWritableDatabase().execSQL(
                "CREATE TRIGGER fail_end BEFORE UPDATE ON ActiveSession "
                + "BEGIN SELECT RAISE(ABORT, 'state failure'); END");
        assertThrows(ExecutionException.class, () -> await(repository.endExpiredSession(sessionId)));
        assertEquals("ACTIVE", db.safetyDao().session().state);
        assertEquals(2, db.safetyDao().count());
        assertEquals(1, db.safetyDao().pendingNotifications(sessionId, now.get()).size());
    }

    @Test public void expiryDuringBatchRollsBackRecordsHistoryAndRevision() throws Exception {
        await(repository.addWatchTarget(2, "Target"));
        now.set(end - 1);
        AtomicInteger calls = new AtomicInteger();
        SafetyRepository crossing = withClock(() -> calls.incrementAndGet() >= 3 ? end : now.get());
        rejected(crossing.applyReceivedBatch(sessionId, master.version,
                Arrays.asList(new WireRecord(2, 1), new WireRecord(3, 1))));
        assertEquals(1, db.safetyDao().count());
        assertEquals(1, db.safetyDao().session().dataRevision);
        assertTrue(await(repository.validHistories()).isEmpty());
        assertTrue(await(repository.findPendingNotifications(sessionId)).isEmpty());
        now.set(end);
        await(repository.checkAndEndExpiredSession());
        assertEquals(0, db.safetyDao().count());
    }

    @Test public void expiryDuringSingleReceiptAlsoRollsBack() throws Exception {
        await(repository.addWatchTarget(2, "Target"));
        now.set(end - 1);
        AtomicInteger calls = new AtomicInteger();
        SafetyRepository crossing = withClock(() -> calls.incrementAndGet() >= 3 ? end : now.get());
        rejected(crossing.receive(sessionId, master.version, 2, 1));
        assertEquals(1, db.safetyDao().count());
        assertTrue(await(repository.validHistories()).isEmpty());
    }

    @Test public void expiryDuringPageReadDoesNotReturnPage() {
        now.set(end - 1);
        AtomicInteger calls = new AtomicInteger();
        SafetyRepository crossing = withClock(() -> calls.incrementAndGet() >= 2 ? end : now.get());
        rejected(crossing.pageAfter(sessionId, 0, 256));
    }

    @Test public void expiryCompetingWithInFlightReceiptLeavesNoLateDataOrHistory() throws Exception {
        await(repository.addWatchTarget(2, "Target"));
        now.set(end - 1);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch writesFinished = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SafetyRepository receiver = withClock(() -> {
            if (calls.incrementAndGet() == 3) {
                writesFinished.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("release timeout");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(error);
                }
            }
            return now.get();
        });
        CompletableFuture<SafetyRepository.BatchResult> receive = receiver.receive(sessionId, master.version, 2, 1);
        CompletableFuture<SafetyRepository.EndResult> finish;
        try {
            assertTrue(writesFinished.await(5, TimeUnit.SECONDS));
            now.set(end);
            finish = repository.endExpiredSession(sessionId);
            assertFalse(receive.isDone());
        } finally {
            release.countDown();
        }
        rejected(receive);
        assertEquals(SafetyRepository.EndResult.ENDED, await(finish));
        assertEquals(0, db.safetyDao().count());
        assertTrue(await(repository.validHistories()).isEmpty());
        assertEquals(1, db.safetyDao().session().dataRevision);
    }

    @Test public void expiryRacingWithNewSessionCannotDeleteItsRecords() throws Exception {
        now.set(end);
        CompletableFuture<String> replacement = repository.startSession(1, 1);
        CompletableFuture<SafetyRepository.EndResult> finish = repository.endExpiredSession(sessionId);
        String next = await(replacement);
        SafetyRepository.EndResult result = await(finish);
        assertTrue(result == SafetyRepository.EndResult.ENDED || result == SafetyRepository.EndResult.STALE_SESSION
                || result == SafetyRepository.EndResult.ALREADY_ENDED);
        assertTrue(await(repository.sessionStatus()).canCommunicate(next));
        assertEquals(next, db.safetyDao().find(1).localSessionId);
        assertEquals(1, db.safetyDao().count());
    }

    @Test public void missingSessionAndClockRollbackAreBlockedWithoutReset() throws Exception {
        watchedReceipt();
        now.set(start - 1);
        assertEquals(SessionStatus.State.CLOCK_UNCERTAIN, await(repository.sessionStatus()).state);
        assertEquals(0, await(repository.sessionStatus()).remainingMillis);
        assertEquals(SafetyRepository.EndResult.CLOCK_UNCERTAIN, await(repository.endExpiredSession(sessionId)));
        assertEquals(WatchStatus.State.NO_ACTIVE_SESSION, await(repository.currentWatchStatuses()).get(0).state);
        rejected(repository.receive(sessionId, master.version, 3, 1));
        rejected(repository.pageAfter(sessionId, 0, 256));
        rejected(repository.startSession(1, 1));
        assertEquals(end, db.safetyDao().session().endsAtWall);
        db.getOpenHelper().getWritableDatabase().execSQL("DELETE FROM ActiveSession");
        assertEquals(SessionStatus.State.NO_SESSION, await(repository.checkAndEndExpiredSession()).state);
        assertEquals(SafetyRepository.EndResult.NO_SESSION, await(repository.endExpiredSession(sessionId)));
        rejected(repository.receive(sessionId, master.version, 3, 1));
        rejected(repository.pageAfter(sessionId, 0, 256));
    }
}
