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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SafetyRepositoryTest {
    private Context context;
    private AppDatabase db;
    private SafetyRepository repository;
    private ExecutorService executor;
    private String file;
    private String session;
    private final AtomicLong now = new AtomicLong(1_000_000);
    // Synthetic test fixture, not a production municipality table.
    private final MunicipalityMaster master = new MunicipalityMaster("test-v1", testMunicipalities());

    private static java.util.Map<Integer, String> testMunicipalities() {
        java.util.Map<Integer, String> names = new java.util.HashMap<>();
        for (int code : new int[]{0, 10, 11, 20, 30, 99, 255}) {
            names.put(code, "テスト自治体" + code);
        }
        return names;
    }

    @Before public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        file = "room-test-" + UUID.randomUUID() + ".db";
        db = Room.databaseBuilder(context, AppDatabase.class, file).build();
        executor = Executors.newFixedThreadPool(3);
        repository = new SafetyRepository(db, executor, now::get, SafetyRepository.MAX_RECORDS, master);
        session = await(repository.startSession(1, 10));
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

    @Test public void duplicatesAndDifferentLocationsKeepFirstIncludingSelf() throws Exception {
        await(repository.addWatchTarget(new WatchTarget(2, "target", now.get())));
        assertEquals(SafetyRepository.Outcome.NEW_PERSON, await(repository.receive(session, master.version, 2, 20)).outcomes.get(0));
        for (int i = 0; i < 10; i++) {
            assertEquals(SafetyRepository.Outcome.DUPLICATE, await(repository.receive(session, master.version, 2, 20)).outcomes.get(0));
        }
        assertEquals(SafetyRepository.Outcome.ID_ALREADY_KNOWN, await(repository.receive(session, master.version, 2, 99)).outcomes.get(0));
        assertEquals(SafetyRepository.Outcome.ID_ALREADY_KNOWN, await(repository.receive(session, master.version, 1, 99)).outcomes.get(0));
        assertEquals(2, db.safetyDao().count());
        assertEquals(20, db.safetyDao().find(2).municipalityCode);
        assertEquals(10, db.safetyDao().find(1).municipalityCode);
        assertEquals(1, db.safetyDao().validHistories(now.get()).size());
        assertEquals(2, db.safetyDao().session().dataRevision);
    }

    @Test public void storesBoundaryValuesAndPagesWithoutMetadata() throws Exception {
        await(repository.applyReceivedBatch(session, master.version, Arrays.asList(new WireRecord(2, 0), new WireRecord(0xFFFFFF, 255))));
        List<WireRecord> page = await(repository.pageAfter(session, 1, 1));
        assertEquals(1, page.size());
        assertEquals(0, page.get(0).municipalityCode);
        assertEquals(0xFFFFFF, await(repository.pageAfter(session, 2, 1)).get(0).userId);
    }

    @Test public void capacityRejectsOnlyUnknownIds() throws Exception {
        repository = new SafetyRepository(db, executor, now::get, 2, master);
        SafetyRepository.BatchResult result = await(repository.applyReceivedBatch(session, master.version,
                Arrays.asList(new WireRecord(2, 20), new WireRecord(3, 30), new WireRecord(2, 20), new WireRecord(2, 99))));
        assertEquals(Arrays.asList(SafetyRepository.Outcome.NEW_PERSON, SafetyRepository.Outcome.CAPACITY_REJECTED,
                SafetyRepository.Outcome.DUPLICATE, SafetyRepository.Outcome.ID_ALREADY_KNOWN), result.outcomes);
        assertEquals(2, db.safetyDao().count());
    }

    @Test public void historyFailureRollsBackEntireBatchAndRevision() throws Exception {
        await(repository.addWatchTarget(2, "earlier target"));
        await(repository.addWatchTarget(new WatchTarget(3, "target", now.get())));
        db.getOpenHelper().getWritableDatabase().execSQL(
                "CREATE TRIGGER fail_history BEFORE INSERT ON NotificationHistory WHEN NEW.targetUserId = 3 BEGIN SELECT RAISE(ABORT, 'injected failure'); END");
        CompletableFuture<SafetyRepository.BatchResult> future = repository.applyReceivedBatch(session, master.version,
                Arrays.asList(new WireRecord(2, 20), new WireRecord(3, 30)));
        try {
            await(future);
            fail("DB failure must not report success");
        } catch (java.util.concurrent.ExecutionException expected) {
            assertNotNull(expected.getCause());
        }
        assertTrue(future.isCompletedExceptionally());
        assertEquals(1, db.safetyDao().count());
        assertNull(db.safetyDao().find(2));
        assertNull(db.safetyDao().find(3));
        assertEquals(0, db.safetyDao().validHistories(now.get()).size());
        assertEquals(1, db.safetyDao().session().dataRevision);
        db.getOpenHelper().getWritableDatabase().execSQL("DROP TRIGGER fail_history");
        assertEquals(2, await(repository.applyReceivedBatch(session, master.version,
                Arrays.asList(new WireRecord(2, 20), new WireRecord(3, 30)))).notifications.size());
    }

    @Test public void concurrentReceivesInsertOnce() throws Exception {
        CompletableFuture<SafetyRepository.BatchResult> first = repository.receive(session, master.version, 2, 20);
        CompletableFuture<SafetyRepository.BatchResult> second = repository.receive(session, master.version, 2, 99);
        assertEquals(1, await(first).inserted + await(second).inserted);
        assertEquals(2, db.safetyDao().count());
    }

    @Test public void committedRecordsSurviveDatabaseReopen() throws Exception {
        await(repository.receive(session, master.version, 2, 20));
        db.close();
        db = Room.databaseBuilder(context, AppDatabase.class, file).build();
        repository = new SafetyRepository(db, executor, now::get, SafetyRepository.MAX_RECORDS, master);
        assertEquals(2, db.safetyDao().count());
        assertEquals(SafetyRepository.Outcome.DUPLICATE, await(repository.receive(session, master.version, 2, 20)).outcomes.get(0));
    }

    @Test public void staleAndExpiredSessionsCannotWriteAndNewPeriodStartsFresh() throws Exception {
        await(repository.receive(session, master.version, 2, 20));
        try {
            await(repository.receive(UUID.randomUUID().toString(), master.version, 3, 30));
            fail("stale session");
        } catch (java.util.concurrent.ExecutionException expected) { }
        now.set(db.safetyDao().session().endsAtWall);
        try {
            await(repository.receive(session, master.version, 3, 30));
            fail("expired session");
        } catch (java.util.concurrent.ExecutionException expected) { }
        String next = await(repository.startSession(1, 11));
        assertNotEquals(session, next);
        assertEquals(1, db.safetyDao().count());
        assertEquals(SafetyRepository.Outcome.NEW_PERSON, await(repository.receive(next, master.version, 2, 99)).outcomes.get(0));
    }

    @Test public void unknownMunicipalityCannotStartOrReceiveEvenForKnownPerson() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> repository.startSession(2, 42));
        assertThrows(IllegalArgumentException.class, () -> repository.receive(session, master.version, 2, 42));
        assertThrows(IllegalArgumentException.class, () -> repository.receive(session, master.version, 1, 42));
        assertEquals(session, db.safetyDao().session().sessionId);
        assertEquals(1, db.safetyDao().count());
        assertEquals(1, db.safetyDao().session().dataRevision);
    }

    @Test public void unresolvedNameRejectsEntireBatchWithoutHistoryOrRecords() throws Exception {
        await(repository.addWatchTarget(new WatchTarget(3, "対象者", now.get())));
        assertThrows(IllegalArgumentException.class, () -> repository.applyReceivedBatch(session, master.version,
                Arrays.asList(new WireRecord(2, 20), new WireRecord(3, 42))));
        assertEquals(1, db.safetyDao().count());
        assertNull(db.safetyDao().find(2));
        assertNull(db.safetyDao().find(3));
        assertTrue(await(repository.validHistories()).isEmpty());
        assertEquals(1, db.safetyDao().session().dataRevision);
    }

    @Test public void watchRegistrationIsInsertOnlySortedAndDeletionIsSelective() throws Exception {
        assertTrue(await(repository.addWatchTarget(0xFFFFFF, "last")));
        assertTrue(await(repository.addWatchTarget(2, "original")));
        assertFalse(await(repository.addWatchTarget(2, "replacement")));
        List<WatchTarget> targets = await(repository.watchTargets());
        assertEquals(2, targets.size());
        assertEquals(2, targets.get(0).targetUserId);
        assertEquals("original", targets.get(0).displayName);
        assertThrows(UnsupportedOperationException.class, () -> targets.clear());
        await(repository.receive(session, master.version, 2, 20));
        assertTrue(await(repository.deleteWatchTarget(2)));
        assertFalse(await(repository.deleteWatchTarget(2)));
        assertEquals(0xFFFFFF, await(repository.watchTargets()).get(0).targetUserId);
        assertEquals(1, await(repository.validHistories()).size());
        assertNotNull(db.safetyDao().find(2));
        assertEquals(session, await(repository.currentSession()).sessionId);
        assertTrue(await(repository.addWatchTarget(2, "registered again")));
        assertFalse(await(repository.receive(session, master.version, 2, 20)).isNotificationRequired());
        assertThrows(IllegalArgumentException.class, () -> repository.addWatchTarget(0, "invalid"));
        assertThrows(IllegalArgumentException.class, () -> repository.deleteWatchTarget(0x1000000));
    }

    @Test public void notificationsAreCommittedImmutableAndOncePerSession() throws Exception {
        assertFalse(await(repository.receive(session, master.version, 3, 30)).isNotificationRequired());
        await(repository.addWatchTarget(2, "target"));
        SafetyRepository.BatchResult first = await(repository.applyReceivedBatch(session, master.version,
                Arrays.asList(new WireRecord(2, 20), new WireRecord(2, 20), new WireRecord(2, 99))));
        assertTrue(first.isNotificationRequired());
        assertEquals(1, first.notifications.size());
        SafetyRepository.NotificationRequest notification = first.notifications.get(0);
        NotificationHistory saved = await(repository.validHistories()).get(0);
        assertEquals(saved.notificationId, notification.notificationId);
        assertEquals("target", notification.displayName);
        assertEquals("テスト自治体20", notification.municipalityName);
        assertEquals(2, notification.targetUserId);
        assertEquals(session, notification.localSessionId);
        assertThrows(UnsupportedOperationException.class, () -> first.notifications.clear());
        assertFalse(await(repository.receive(session, master.version, 2, 20)).isNotificationRequired());
        assertEquals(1, await(repository.validHistories()).size());
        now.set(db.safetyDao().session().endsAtWall);
        String next = await(repository.startSession(1, 10));
        assertTrue(await(repository.receive(next, master.version, 2, 30)).isNotificationRequired());
        assertEquals(2, await(repository.validHistories()).size());
    }

    @Test public void concurrentWatchedReceivesNotifyOnlyOnce() throws Exception {
        await(repository.addWatchTarget(2, "target"));
        CompletableFuture<SafetyRepository.BatchResult> first = repository.receive(session, master.version, 2, 20);
        CompletableFuture<SafetyRepository.BatchResult> second = repository.receive(session, master.version, 2, 30);
        assertEquals(1, await(first).notifications.size() + await(second).notifications.size());
        assertEquals(1, await(repository.validHistories()).size());
    }

    @Test public void cleanupUsesRepositoryClockAndPreservesOtherTables() throws Exception {
        await(repository.addWatchTarget(2, "target"));
        await(repository.receive(session, master.version, 2, 20));
        long end = db.safetyDao().session().endsAtWall;
        long expiry = end + TimeUnit.HOURS.toMillis(100);
        assertEquals(expiry, await(repository.validHistories()).get(0).historyExpiresAt);
        now.set(end);
        assertEquals(0, (int) await(repository.deleteExpiredHistories()));
        now.set(expiry - 1);
        assertEquals(0, (int) await(repository.deleteExpiredHistories()));
        now.set(expiry);
        assertEquals(1, (int) await(repository.deleteExpiredHistories()));
        now.set(expiry + 1);
        assertEquals(0, (int) await(repository.deleteExpiredHistories()));
        assertEquals(session, await(repository.currentSession()).sessionId);
        assertEquals(2, db.safetyDao().count());
        assertEquals(1, await(repository.watchTargets()).size());
        assertThrows(NoSuchMethodException.class,
                () -> SafetyRepository.class.getMethod("deleteExpiredHistories", long.class));
    }

    @Test public void currentStatusesNeverUseOldHistoryAndDistinguishMissingPeriod() throws Exception {
        await(repository.addWatchTarget(1, "self"));
        await(repository.addWatchTarget(2, "target"));
        assertEquals(WatchStatus.State.NOT_RECEIVED, await(repository.currentWatchStatuses()).get(0).state);
        await(repository.receive(session, master.version, 2, 20));
        assertEquals(WatchStatus.State.RECEIVED, await(repository.currentWatchStatuses()).get(1).state);
        now.set(db.safetyDao().session().endsAtWall);
        assertEquals(WatchStatus.State.NO_ACTIVE_SESSION, await(repository.currentWatchStatuses()).get(1).state);
        String next = await(repository.startSession(1, 10));
        assertEquals(1, await(repository.validHistories()).size());
        assertEquals(WatchStatus.State.NOT_RECEIVED, await(repository.currentWatchStatuses()).get(1).state);
        await(repository.receive(next, master.version, 2, 20));
        assertEquals(WatchStatus.State.RECEIVED, await(repository.currentWatchStatuses()).get(1).state);
        ActiveSession inactive = db.safetyDao().session();
        inactive.state = "ENDED";
        db.safetyDao().saveSession(inactive);
        assertEquals(WatchStatus.State.NO_ACTIVE_SESSION, await(repository.currentWatchStatuses()).get(1).state);
        db.getOpenHelper().getWritableDatabase().execSQL("DELETE FROM ActiveSession");
        assertEquals(WatchStatus.State.NO_ACTIVE_SESSION, await(repository.currentWatchStatuses()).get(1).state);
    }

    @Test public void currentStatusIncludesReceiptBeforeWatchRegistration() throws Exception {
        await(repository.receive(session, master.version, 2, 20));
        await(repository.addWatchTarget(2, "late registration"));
        assertTrue(await(repository.validHistories()).isEmpty());
        assertEquals(WatchStatus.State.RECEIVED, await(repository.currentWatchStatuses()).get(0).state);
    }

    @Test public void pendingRecoveryAndConditionalDeliveryUpdates() throws Exception {
        await(repository.addWatchTarget(2, "posted"));
        await(repository.addWatchTarget(3, "blocked"));
        await(repository.applyReceivedBatch(session, master.version,
                Arrays.asList(new WireRecord(2, 20), new WireRecord(3, 30))));
        db.close();
        db = Room.databaseBuilder(context, AppDatabase.class, file).build();
        repository = new SafetyRepository(db, executor, now::get, SafetyRepository.MAX_RECORDS, master);
        List<SafetyRepository.NotificationRequest> pending = await(repository.findPendingNotifications(session));
        assertEquals(2, pending.size());
        assertTrue(await(repository.findPendingNotifications("other-session")).isEmpty());
        long posted = pending.get(0).notificationId;
        long blocked = pending.get(1).notificationId;
        assertTrue(await(repository.markNotificationPosted(posted)));
        assertFalse(await(repository.markNotificationPosted(posted)));
        assertFalse(await(repository.markNotificationBlocked(posted)));
        assertTrue(await(repository.markNotificationBlocked(blocked)));
        assertFalse(await(repository.markNotificationPosted(blocked)));
        assertFalse(await(repository.markNotificationPosted(Long.MAX_VALUE)));
        assertTrue(await(repository.findPendingNotifications(session)).isEmpty());
        assertEquals("POSTED", await(repository.validHistories()).get(0).deliveryState);
        assertEquals("BLOCKED_PERMISSION", await(repository.validHistories()).get(1).deliveryState);
    }

    @Test public void pendingSurvivesSessionChangeButExpiresAtRetentionBoundary() throws Exception {
        await(repository.addWatchTarget(2, "target"));
        long id = await(repository.receive(session, master.version, 2, 20)).notifications.get(0).notificationId;
        long expiry = await(repository.validHistories()).get(0).historyExpiresAt;
        now.set(db.safetyDao().session().endsAtWall);
        String next = await(repository.startSession(1, 10));
        assertTrue(await(repository.findPendingNotifications(next)).isEmpty());
        assertEquals(1, await(repository.findPendingNotifications(session)).size());
        now.set(expiry);
        assertTrue(await(repository.findPendingNotifications(session)).isEmpty());
        assertFalse(await(repository.markNotificationPosted(id)));
        assertFalse(await(repository.markNotificationBlocked(id)));
    }

    @Test public void deliveryUpdateFailurePreservesPendingForRetry() throws Exception {
        await(repository.addWatchTarget(2, "target"));
        long id = await(repository.receive(session, master.version, 2, 20)).notifications.get(0).notificationId;
        db.getOpenHelper().getWritableDatabase().execSQL(
                "CREATE TRIGGER fail_delivery BEFORE UPDATE ON NotificationHistory BEGIN SELECT RAISE(ABORT, 'failure'); END");
        assertThrows(java.util.concurrent.ExecutionException.class, () -> await(repository.markNotificationPosted(id)));
        assertEquals(1, await(repository.findPendingNotifications(session)).size());
    }

    @Test public void failureAfterHistoryInsertRollsBackNotificationAndRecord() throws Exception {
        await(repository.addWatchTarget(2, "target"));
        db.getOpenHelper().getWritableDatabase().execSQL(
                "CREATE TRIGGER fail_revision BEFORE INSERT ON ActiveSession BEGIN SELECT RAISE(ABORT, 'revision failure'); END");
        CompletableFuture<SafetyRepository.BatchResult> future = repository.receive(session, master.version, 2, 20);
        assertThrows(java.util.concurrent.ExecutionException.class, () -> await(future));
        assertTrue(future.isCompletedExceptionally());
        assertNull(db.safetyDao().find(2));
        assertTrue(await(repository.validHistories()).isEmpty());
        assertEquals(1, db.safetyDao().session().dataRevision);
    }

    @Test public void historyStoresResolvedNameAndExpiresExactlyAtBoundary() throws Exception {
        await(repository.addWatchTarget(new WatchTarget(2, "対象者", now.get())));
        await(repository.receive(session, master.version, 2, 20));
        NotificationHistory history = await(repository.validHistories()).get(0);
        assertEquals("テスト自治体20", history.municipalityNameSnapshot);
        assertEquals("対象者", history.displayNameSnapshot);
        now.set(history.historyExpiresAt - 1);
        assertEquals(1, await(repository.validHistories()).size());
        assertEquals(0, (int) await(repository.deleteExpiredHistories()));
        now.incrementAndGet();
        assertTrue(await(repository.validHistories()).isEmpty());
        assertEquals(0, db.safetyDao().validHistories(0).size());
        assertEquals(0, (int) await(repository.deleteExpiredHistories()));
        assertTrue(db.safetyDao().validHistories(0).isEmpty());
        assertEquals(0, (int) await(repository.deleteExpiredHistories()));
    }

    @Test public void bundledMasterIsPackagedAndNamesAreSavedOnDevice() throws Exception {
        now.set(db.safetyDao().session().endsAtWall);
        repository = new SafetyRepository(db, executor, now::get,
                SafetyRepository.MAX_RECORDS, KyushuMunicipalities.load());
        String next = await(repository.startSession(1, 1));
        assertThrows(IllegalArgumentException.class, () -> repository.startSession(3, 0));
        assertThrows(IllegalArgumentException.class,
                () -> repository.receive(next, KyushuMunicipalities.VERSION, 2, 234));
        await(repository.addWatchTarget(new WatchTarget(2, "対象者", now.get())));
        await(repository.receive(next, KyushuMunicipalities.VERSION, 2, 102));
        assertEquals("熊本県熊本市", await(repository.validHistories()).get(0).municipalityNameSnapshot);
    }

    @Test public void versionMismatchRejectsReceiveAndBatchWithoutWrites() {
        for (String version : new String[]{null, "", "other-v1"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> repository.receive(session, version, 2, 20));
            assertThrows(IllegalArgumentException.class,
                    () -> repository.applyReceivedBatch(session, version,
                            Arrays.asList(new WireRecord(2, 20))));
        }
        assertEquals(1, db.safetyDao().count());
        assertEquals(1, db.safetyDao().session().dataRevision);
    }

    @Test public void cleanupKeepsUnexpiredHistory() throws Exception {
        await(repository.addWatchTarget(new WatchTarget(2, "first", now.get())));
        await(repository.receive(session, master.version, 2, 20));
        long firstExpiry = await(repository.validHistories()).get(0).historyExpiresAt;
        now.set(db.safetyDao().session().endsAtWall);
        String next = await(repository.startSession(1, 10));
        await(repository.receive(next, master.version, 2, 30));
        now.set(firstExpiry + 1);
        assertEquals(1, await(repository.validHistories()).size());
        assertEquals(1, db.safetyDao().validHistories(0).size());
        assertEquals(0, (int) await(repository.deleteExpiredHistories()));
        List<NotificationHistory> remaining = await(repository.validHistories());
        assertEquals(1, remaining.size());
        assertEquals(next, remaining.get(0).localSessionId);
    }
}
