package com.example.crosspath.data;

import android.content.Context;
import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

/** SQLite + fake OS sink: clocks, crash recovery, permission and stale-period isolation. */
@RunWith(AndroidJUnit4.class)
public class NotificationDeliveryTest {
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final MunicipalityMaster master = new MunicipalityMaster("delivery-test", Collections.singletonMap(1, "Test"));
    private Context context;
    private String file;
    private AppDatabase db;
    private ExecutorService executor;
    private SafetyRepository repo;
    private String session;
    private long end;
    private static final long HOUR = 3_600_000L;

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        file = "delivery-" + UUID.randomUUID() + ".db";
        executor = Executors.newFixedThreadPool(3);
        open();
        session = await(repo.startSession(1, 1));
        end = await(repo.currentSession()).endsAtWall;
        await(repo.addWatchTarget(2, "Target"));
    }
    private void open() {
        db = Room.databaseBuilder(context, AppDatabase.class, file).build();
        repo = new SafetyRepository(db, executor, now::get, SafetyRepository.MAX_RECORDS, master);
    }
    @After public void cleanup() throws Exception {
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        db.close();
        context.deleteDatabase(file);
    }
    private static <T> T await(CompletableFuture<T> future) throws Exception { return future.get(10, TimeUnit.SECONDS); }
    private long receive() throws Exception {
        return await(repo.receive(session, master.version, 2, 1)).notifications.get(0).notificationId;
    }

    @Test public void boundaryPreventsOSPostAt72Hours() throws Exception {
        receive();
        now.set(end);
        assertEquals(0, (int) await(repo.dispatchCurrentNotifications(request -> { fail("expired post"); return NotificationSink.Delivery.POSTED; })));
        assertEquals(1, await(repo.validHistories()).size());
    }

    @Test public void postBeforeBoundaryIsOnceAndStable() throws Exception {
        long id = receive();
        now.set(end - 1);
        List<Long> posted = new ArrayList<>();
        NotificationSink sink = request -> { posted.add(request.notificationId); return NotificationSink.Delivery.POSTED; };
        assertEquals(1, (int) await(repo.dispatchCurrentNotifications(sink)));
        assertEquals(0, (int) await(repo.dispatchCurrentNotifications(sink)));
        assertEquals(Collections.singletonList(id), posted);
        assertEquals("POSTED", await(repo.validHistories()).get(0).deliveryState);
    }

    @Test public void crashAfterOSPostRetriesSameIdAfterDatabaseReopen() throws Exception {
        long id = receive();
        List<Long> calls = new ArrayList<>();
        assertThrows(ExecutionException.class, () -> await(repo.dispatchCurrentNotifications(request -> {
            calls.add(request.notificationId);
            throw new IllegalStateException("crash after notify before DB commit");
        })));
        db.close();
        open();
        await(repo.dispatchCurrentNotifications(request -> { calls.add(request.notificationId); return NotificationSink.Delivery.POSTED; }));
        assertEquals(Arrays.asList(id, id), calls);
        assertEquals(1, await(repo.validHistories()).size());
    }

    @Test public void permissionDenialPreservesReceiptAndExplicitRetryDoesNotExtendHistory() throws Exception {
        receive();
        await(repo.dispatchCurrentNotifications(request -> NotificationSink.Delivery.BLOCKED_PERMISSION));
        assertEquals(WatchStatus.State.RECEIVED, await(repo.currentWatchStatuses()).get(0).state);
        assertEquals("BLOCKED_PERMISSION", await(repo.validHistories()).get(0).deliveryState);
        assertEquals(0, await(repo.findPendingNotifications(session)).size());
        now.incrementAndGet();
        assertEquals(1, (int) await(repo.retryBlockedNotifications()));
        await(repo.dispatchCurrentNotifications(request -> NotificationSink.Delivery.POSTED));
        assertEquals(end + 100 * HOUR, await(repo.validHistories()).get(0).historyExpiresAt);
    }

    @Test public void oldPendingIsNeverDispatchedInNewPeriod() throws Exception {
        long old = receive();
        now.set(end);
        session = await(repo.startSession(1, 1));
        assertEquals(WatchStatus.State.NOT_RECEIVED, await(repo.currentWatchStatuses()).get(0).state);
        assertEquals(0, (int) await(repo.dispatchCurrentNotifications(request -> { fail("old pending"); return NotificationSink.Delivery.POSTED; })));
        long fresh = receive();
        assertNotEquals(old, fresh);
        List<Long> posted = new ArrayList<>();
        await(repo.dispatchCurrentNotifications(request -> { posted.add(request.notificationId); return NotificationSink.Delivery.POSTED; }));
        assertEquals(Collections.singletonList(fresh), posted);
    }

    @Test public void blockedOldPeriodIsNotRevivedByPermissionRetry() throws Exception {
        receive();
        await(repo.dispatchCurrentNotifications(request -> NotificationSink.Delivery.BLOCKED_PERMISSION));
        now.set(end);
        session = await(repo.startSession(1, 1));
        assertEquals(0, (int) await(repo.retryBlockedNotifications()));
    }

    @Test public void retainedHistoryLookupUsesExact100HourBoundary() throws Exception {
        long id = receive();
        now.set(end + 100 * HOUR - 1);
        assertTrue(await(repo.isHistoryValid(id)));
        now.incrementAndGet();
        assertFalse(await(repo.isHistoryValid(id)));
        assertTrue(await(repo.validHistories()).isEmpty());
        now.incrementAndGet();
        assertFalse(await(repo.isHistoryValid(id)));
    }

    @Test public void delayedConfirmationKeepsOperationTimeAndDoubleConfirmIsRejected() throws Exception {
        now.set(end);
        long confirmed = now.get();
        ConfirmationClock.Token token = new ConfirmationClock.Token(new SessionClock.Reading(confirmed, confirmed, "test-boot"));
        now.addAndGet(10 * 60_000);
        await(repo.startSessionAtConfirmation(1, 1, token));
        assertEquals(confirmed, await(repo.currentSession()).startedAtWall);
        assertEquals(72 * HOUR - 10 * 60_000, await(repo.sessionStatus()).remainingMillis);
        assertThrows(ExecutionException.class, () -> await(repo.startSessionAtConfirmation(1, 1, token)));
    }

    @Test public void queuedOldDispatchCannotCrossReplacement() throws Exception {
        receive();
        now.set(end);
        String replacement = await(repo.startSession(1, 1));
        // A dispatcher requested before replacement but delayed until now sees only the new period.
        assertEquals(0, (int) await(repo.dispatchCurrentNotifications(request -> { fail("stale callback"); return NotificationSink.Delivery.POSTED; })));
        assertEquals(replacement, await(repo.currentSession()).sessionId);
        assertThrows(ExecutionException.class, () -> await(repo.receive(session, master.version, 3, 1)));
    }

    @Test public void expiryBetweenNotificationsPreventsNextPost() throws Exception {
        receive();
        await(repo.addWatchTarget(3, "Other"));
        await(repo.receive(session, master.version, 3, 1));
        assertEquals(1, (int) await(repo.dispatchCurrentNotifications(request -> {
            now.set(end);
            return NotificationSink.Delivery.POSTED;
        })));
        assertEquals(1, await(repo.findPendingNotifications(session)).size());
    }

    @Test public void osNotificationIsCancelledAfterHistoryDeletionAndNotRestoredByLookup() throws Exception {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            try (android.os.ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .executeShellCommand("pm grant " + context.getPackageName() + " android.permission.POST_NOTIFICATIONS")) {
                try (java.io.InputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
                    while (input.read() != -1) { }
                }
            }
        }
        com.example.crosspath.notification.NotificationDispatcher sink = new com.example.crosspath.notification.NotificationDispatcher(context);
        long id = receive();
        await(repo.dispatchCurrentNotifications(sink));
        android.app.NotificationManager manager = context.getSystemService(android.app.NotificationManager.class);
        assertTrue(Arrays.stream(manager.getActiveNotifications()).anyMatch(item -> ("history:" + id).equals(item.getTag())));
        now.set(end + 100 * HOUR);
        await(repo.deleteExpiredHistories()); // Simulate DB deletion before a process interruption.
        await(sink.reconcile(repo));
        assertFalse(Arrays.stream(manager.getActiveNotifications()).anyMatch(item -> ("history:" + id).equals(item.getTag())));
        assertFalse(await(repo.isHistoryValid(id)));
    }
}
