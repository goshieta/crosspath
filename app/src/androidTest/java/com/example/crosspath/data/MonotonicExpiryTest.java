package com.example.crosspath.data;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Data/transport-stop boundary tests. Does not simulate an Android BLE stack or Service. */
@RunWith(AndroidJUnit4.class)
public class MonotonicExpiryTest {
    private static final long HOUR = TimeUnit.HOURS.toMillis(1);
    private static final class FakeClock implements SessionClock {
        long wall = 1_000_000;
        long elapsed = 10_000;
        String boot = "A";
        @Override public Reading read() { return new Reading(wall, elapsed, boot); }
        void advance(long millis) { wall += millis; elapsed += millis; }
    }

    private Context context;
    private AppDatabase db;
    private String file;
    private ExecutorService executor;
    private SafetyRepository repository;
    private String session;
    private long end;
    private final FakeClock clock = new FakeClock();
    private final List<String> stops = Collections.synchronizedList(new ArrayList<>());
    private final MunicipalityMaster master = new MunicipalityMaster("test-clock",
            Collections.singletonMap(1, "Test"));

    @Before public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        file = "monotonic-" + UUID.randomUUID() + ".db";
        db = Room.databaseBuilder(context, AppDatabase.class, file).addMigrations(AppDatabase.MIGRATION_1_2).build();
        executor = Executors.newFixedThreadPool(3);
        repository = repositoryWith(id -> {
            assertFalse("stop callback must be outside DB transaction", db.inTransaction());
            stops.add(id);
        });
        session = await(repository.startSession(1, 1));
        end = db.safetyDao().session().endsAtWall;
    }

    private SafetyRepository repositoryWith(SessionStopHandler handler) {
        return new SafetyRepository(db, executor, clock, SafetyRepository.MAX_RECORDS, master, handler);
    }

    @After public void tearDown() throws Exception {
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        db.close();
        context.deleteDatabase(file);
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(10, TimeUnit.SECONDS);
    }

    private void watchedReceipt() throws Exception {
        await(repository.addWatchTarget(2, "Target"));
        await(repository.receive(session, master.version, 2, 1));
    }

    private void reopen() {
        db.close();
        db = Room.databaseBuilder(context, AppDatabase.class, file).addMigrations(AppDatabase.MIGRATION_1_2).build();
        repository = repositoryWith(stops::add);
    }

    @Test public void wallRollbackBlocksButElapsedDeadlineStillEndsAndDeletes() throws Exception {
        watchedReceipt();
        clock.advance(71 * HOUR);
        assertEquals(HOUR, await(repository.sessionStatus()).remainingMillis);
        clock.wall -= 24 * HOUR;
        SessionStatus uncertain = await(repository.sessionStatus());
        assertEquals(SessionStatus.State.CLOCK_UNCERTAIN, uncertain.state);
        assertFalse(uncertain.canCommunicate);
        assertEquals(session, stops.get(0));
        assertEquals(2, db.safetyDao().count());
        assertThrows(ExecutionException.class,
                () -> await(repository.receive(session, master.version, 3, 1)));
        assertThrows(ExecutionException.class, () -> await(repository.pageAfter(session, 0, 256)));
        clock.elapsed += HOUR;
        assertEquals(SessionStatus.State.ENDED, await(repository.sessionStatus()).state);
        assertEquals(0, db.safetyDao().count());
        assertEquals(end, db.safetyDao().session().endsAtWall);
        assertEquals(end + 100 * HOUR, db.safetyDao().validHistories(0).get(0).historyExpiresAt);
    }

    @Test public void originalElapsedDeadlineWorksWithoutIntermediateObservations() throws Exception {
        clock.elapsed += 72 * HOUR;
        assertEquals(SessionStatus.State.ENDED, await(repository.sessionStatus()).state);
        assertEquals(0, db.safetyDao().count());
        assertEquals(Collections.singletonList(session), stops);
    }

    @Test public void smallClockAdjustmentCannotIncreaseRemainingTime() throws Exception {
        clock.advance(HOUR);
        long remaining = await(repository.sessionStatus()).remainingMillis;
        clock.elapsed += 500;
        clock.wall -= 100;
        SessionStatus status = await(repository.sessionStatus());
        assertEquals(remaining - 500, status.remainingMillis);
        assertTrue(status.canCommunicate);
    }

    @Test public void clockUncertaintySurvivesRepositoryAndDatabaseReopen() throws Exception {
        clock.advance(20 * HOUR);
        await(repository.sessionStatus());
        clock.wall -= 10 * HOUR;
        assertEquals(SessionStatus.State.CLOCK_UNCERTAIN, await(repository.sessionStatus()).state);
        reopen();
        assertEquals(SessionStatus.State.CLOCK_UNCERTAIN, await(repository.sessionStatus()).state);
        clock.elapsed = 10_000 + 72 * HOUR;
        assertEquals(SessionStatus.State.ENDED, await(repository.sessionStatus()).state);
    }

    @Test public void normalRebootFallsBackToSavedUtcDeadlineAndReanchors() throws Exception {
        clock.advance(20 * HOUR);
        await(repository.sessionStatus());
        clock.boot = "B";
        clock.wall += 10 * HOUR;
        clock.elapsed = 5_000;
        reopen();
        assertEquals(42 * HOUR, await(repository.sessionStatus()).remainingMillis);
        assertEquals(end, db.safetyDao().session().endsAtWall);
        assertEquals("A", db.safetyDao().session().bootMarker); // Original start is never rewritten.
        assertEquals("B", db.safetyDao().clockAnchor().bootMarker);
        clock.elapsed += 42 * HOUR;
        assertEquals(SessionStatus.State.ENDED, await(repository.sessionStatus()).state);
    }

    @Test public void rebootWithRollbackAndUnavailableBootIdentityAreBlocked() throws Exception {
        clock.advance(20 * HOUR);
        await(repository.sessionStatus());
        clock.boot = "B";
        clock.elapsed = 1_000;
        clock.wall -= HOUR;
        reopen();
        assertEquals(SessionStatus.State.CLOCK_UNCERTAIN, await(repository.sessionStatus()).state);
        assertEquals(SafetyRepository.EndResult.CLOCK_UNCERTAIN,
                await(repository.endExpiredSession(session)));
        clock.boot = "";
        clock.wall = end - 1;
        assertEquals(SessionStatus.State.CLOCK_UNCERTAIN, await(repository.sessionStatus()).state);
        assertThrows(ExecutionException.class, () -> await(repository.startSession(1, 1)));
        assertTrue(stops.contains(session));
    }

    @Test public void forwardClockJumpCannotBeUndoneByRebootOrRollback() throws Exception {
        clock.wall = end;
        await(repository.sessionStatus());
        clock.wall = 1_000_000;
        clock.boot = "B";
        clock.elapsed = 0;
        reopen();
        assertEquals(SessionStatus.State.ENDED, await(repository.sessionStatus()).state);
        assertThrows(ExecutionException.class, () -> await(repository.startSession(1, 1)));
        assertEquals(0, db.safetyDao().count());
    }

    @Test public void displayAndPendingEntryPointsPerformPhysicalCleanup() throws Exception {
        watchedReceipt();
        clock.advance(72 * HOUR);
        assertEquals(WatchStatus.State.NO_ACTIVE_SESSION, await(repository.currentWatchStatuses()).get(0).state);
        assertEquals("ENDED", db.safetyDao().session().state);
        assertEquals(0, db.safetyDao().count());
        assertEquals(1, await(repository.findPendingNotifications(session)).size());
        clock.advance(100 * HOUR);
        assertTrue(await(repository.findPendingNotifications(session)).isEmpty());
        assertTrue(db.safetyDao().validHistories(0).isEmpty());
        assertEquals(1, await(repository.watchTargets()).size());
    }

    @Test public void historyDeadlineAdvancesWithElapsedTimeAfterWallRollback() throws Exception {
        watchedReceipt();
        clock.elapsed += 172 * HOUR;
        assertTrue(await(repository.validHistories()).isEmpty());
        assertTrue(db.safetyDao().validHistories(0).isEmpty());
        assertEquals(end, db.safetyDao().session().endsAtWall);
    }

    @Test public void cleanupFailureStillRequestsStopAndNeverReturnsSuccess() throws Exception {
        watchedReceipt();
        clock.advance(72 * HOUR);
        db.getOpenHelper().getWritableDatabase().execSQL("CREATE TRIGGER fail_cleanup AFTER DELETE ON SafetyRecord "
                + "BEGIN SELECT RAISE(ABORT, 'failure'); END");
        assertThrows(ExecutionException.class, () -> await(repository.sessionStatus()));
        assertEquals(Collections.singletonList(session), stops);
        assertEquals("ACTIVE", db.safetyDao().session().state);
        assertEquals(2, db.safetyDao().count());
        db.getOpenHelper().getWritableDatabase().execSQL("DROP TRIGGER fail_cleanup");
        assertEquals(SessionStatus.State.ENDED, await(repository.sessionStatus()).state);
        assertEquals(0, db.safetyDao().count());
    }

    @Test public void stopHandlerFailureIsReportedAndRetriedAfterCommittedCleanup() throws Exception {
        clock.advance(72 * HOUR);
        SafetyRepository failing = repositoryWith(id -> { throw new IllegalStateException("transport stop failed"); });
        assertThrows(ExecutionException.class, () -> await(failing.sessionStatus()));
        assertEquals("ENDED", db.safetyDao().session().state);
        assertEquals(0, db.safetyDao().count());
        await(repository.sessionStatus());
        assertTrue(stops.contains(session));
    }

    @Test public void staleStopAndReceiveFailureCarryOnlyOldSessionId() throws Exception {
        clock.advance(72 * HOUR);
        String next = await(repository.startSession(1, 1));
        stops.clear();
        assertEquals(SafetyRepository.EndResult.STALE_SESSION, await(repository.endExpiredSession(session)));
        CompletableFuture<SafetyRepository.BatchResult> received = repository.receive(session, master.version, 2, 1);
        assertThrows(ExecutionException.class, () -> await(received));
        assertTrue(received.isCompletedExceptionally()); // No BatchResult for a success ACK.
        assertEquals(java.util.Arrays.asList(session, session), stops);
        assertTrue(await(repository.sessionStatus()).canCommunicate(next));
        assertEquals(next, db.safetyDao().find(1).localSessionId);
    }

    @Test public void migrationKeepsV1DeadlineRecordsTargetsAndPendingHistory() throws Exception {
        watchedReceipt();
        db.close();
        // Reconstruct the exact v1 shape: the only v2 schema addition is ClockAnchor.
        SQLiteDatabase legacy = SQLiteDatabase.openDatabase(context.getDatabasePath(file).getPath(), null,
                SQLiteDatabase.OPEN_READWRITE);
        legacy.execSQL("DROP TABLE ClockAnchor");
        legacy.execSQL("UPDATE ActiveSession SET bootMarker = ''");
        legacy.execSQL("UPDATE room_master_table SET identity_hash = '840c311f022ecb45242e45edee3caa89' WHERE id = 42");
        legacy.setVersion(1);
        legacy.close();
        db = AppDatabase.open(context, file); // Production factory must register the migration.
        repository = repositoryWith(stops::add);
        assertEquals(end, db.safetyDao().session().endsAtWall);
        assertEquals(2, db.safetyDao().count());
        assertEquals(1, await(repository.watchTargets()).size());
        assertEquals(1, await(repository.findPendingNotifications(session)).size());
        clock.advance(72 * HOUR);
        assertEquals(SessionStatus.State.ENDED, await(repository.sessionStatus()).state);
        assertEquals(end + 100 * HOUR, db.safetyDao().validHistories(0).get(0).historyExpiresAt);
    }

    @Test public void productionClockUsesStableBootMarkerAndNondecreasingElapsedTime() {
        SessionClock real = new AndroidSessionClock(context);
        SessionClock.Reading first = real.read();
        SessionClock.Reading second = real.read();
        assertFalse(first.bootMarker.isEmpty());
        assertEquals(first.bootMarker, second.bootMarker);
        assertTrue(second.elapsed >= first.elapsed);
        assertTrue(first.wall > 0);
    }
}
