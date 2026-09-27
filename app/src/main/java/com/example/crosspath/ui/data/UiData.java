package com.example.crosspath.ui.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.example.crosspath.data.*;
import com.example.crosspath.notification.NotificationDispatcher;
import com.example.crosspath.ui.ScreenPolicy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

/** Process lifetime data gateway. All DB/initialization/OS notification work is off main. */
public final class UiData {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static CompletableFuture<SafetyRepository> ready;
    private static volatile SessionStatus last;
    private static volatile ConfirmationClock confirmationClock;
    private static volatile String communicationSession;
    private static volatile String communicationReason = "通信コンポーネント未接続";
    private static volatile boolean communicationRunning;

    /** UI-only status event from the communication owner; does not start/stop BLE. */
    public static void reportCommunicationState(String sessionId, boolean running, String reason) {
        MAIN.post(() -> {
            if (last == null || !java.util.Objects.equals(last.sessionId, sessionId)) return;
            communicationSession = sessionId;
            communicationRunning = running;
            communicationReason = reason == null ? "通信停止中" : reason;
            for (Consumer<SessionStatus> listener : listeners) listener.accept(last);
        });
    }
    public static boolean communicationRunning(SessionStatus status) {
        return status != null && status.canCommunicate && status.sessionId.equals(communicationSession) && communicationRunning;
    }
    public static String communicationReason(SessionStatus status) {
        return status != null && status.sessionId != null && status.sessionId.equals(communicationSession)
                ? communicationReason : "通信コンポーネント未接続";
    }
    private static volatile NotificationDispatcher dispatcher;
    private static volatile SessionStopHandler communicationStop = id -> {};
    private static final AtomicBoolean maintaining = new AtomicBoolean();
    private static final AtomicBoolean maintenanceRequested = new AtomicBoolean();
    private static final CopyOnWriteArrayList<Consumer<SessionStatus>> listeners = new CopyOnWriteArrayList<>();

    public static synchronized void init(Context context) {
        if (ready != null && !ready.isCompletedExceptionally()) return;
        Context app = context.getApplicationContext();
        ready = UserProfile.load(app).thenApplyAsync(ignored -> {
            confirmationClock = new ConfirmationClock(app);
            SafetyRepository repo = new SafetyRepository(AppDatabase.open(app), IO,
                    KyushuMunicipalities.load(), id -> communicationStop.stopSession(id));
            dispatcher = new NotificationDispatcher(app);
            return repo;
        }, IO);
        ready.whenComplete((repo, error) -> {
            if (error == null) maintain();
            else Log.e("UiData", "Initialization failed", error);
        });
    }

    public static synchronized <T> CompletableFuture<T> execute(Function<SafetyRepository, CompletableFuture<T>> operation) {
        if (ready == null) return CompletableFuture.failedFuture(new IllegalStateException("Not initialized"));
        // thenComposeAsync also turns synchronous validation exceptions into a failed Future.
        return ready.thenComposeAsync(operation, IO);
    }

    public static synchronized boolean isReady() {
        return ready != null && ready.isDone() && !ready.isCompletedExceptionally();
    }
    /** Compatibility boundary; new UI calls should use execute to receive initialization errors. */
    public static void whenReady(Consumer<SafetyRepository> callback) {
        execute(repo -> { callback.accept(repo); return CompletableFuture.completedFuture(null); })
                .exceptionally(error -> { Log.e("UiData", "Data callback failed", error); return null; });
    }
    public static ConfirmationClock.Token confirmationTime() {
        return confirmationClock.capture();
    }
    public static <T> void onResult(CompletableFuture<T> future, Consumer<T> success, Consumer<Throwable> failure) {
        future.whenComplete((value, error) -> MAIN.post(() -> {
            if (error != null) { if (failure != null) failure.accept(error); }
            else if (success != null) success.accept(value);
        }));
    }
    public static void post(Runnable action) { MAIN.post(action); }
    public static void checkSession(Consumer<SessionStatus> success, Consumer<Throwable> failure) {
        onResult(execute(SafetyRepository::checkAndEndExpiredSession), status -> {
            last = status;
            if (success != null) success.accept(status);
        }, failure);
    }
    public static SessionStatus lastSessionStatus() { return last; }
    public static boolean isActivePeriod() { return last != null && ScreenPolicy.isActivePeriod(last.state); }
    public static void refreshTimerState() { maintain(); }
    public static void addListener(Consumer<SessionStatus> listener) { listeners.addIfAbsent(listener); }
    public static void removeListener(Consumer<SessionStatus> listener) { listeners.remove(listener); }

    /** Communication owner installs its existing idempotent, session-scoped stop hook. */
    public static void setSessionStopHandler(SessionStopHandler handler) {
        communicationStop = java.util.Objects.requireNonNull(handler);
        maintain();
    }
    /** Call only after receive/applyReceivedBatch completes successfully. No fabricated records. */
    public static void onBatchCommitted(SafetyRepository.BatchResult result) { maintain(); }

    /** Periodic maintenance while the process lives; no separate foreground service. */
    public static void maintain() {
        maintenanceRequested.set(true);
        drainMaintenance();
    }
    private static void drainMaintenance() {
        if (!maintaining.compareAndSet(false, true)) return;
        maintenanceRequested.set(false);
        execute(repo -> repo.checkAndEndExpiredSession().thenCompose(status -> {
            last = status;
            if (status.state == SessionStatus.State.ACTIVE && status.remainingMillis <= 30_000) {
                TIMER.schedule(UiData::maintain, Math.max(1, status.remainingMillis), TimeUnit.MILLISECONDS);
            }
            MAIN.post(() -> { for (Consumer<SessionStatus> listener : listeners) listener.accept(status); });
            return dispatcher.reconcile(repo).thenCompose(ignored -> repo.dispatchCurrentNotifications(dispatcher))
                    .thenApply(count -> {
                        MAIN.post(() -> { for (Consumer<SessionStatus> listener : listeners) listener.accept(status); });
                        return count;
                    });
        })).whenComplete((count, error) -> {
            maintaining.set(false);
            if (error != null) Log.e("UiData", "Maintenance failed; retry scheduled", error);
            if (maintenanceRequested.get()) drainMaintenance();
        });
    }
    public static CompletableFuture<Integer> retryNotifications() {
        return execute(SafetyRepository::retryBlockedNotifications).whenComplete((count, error) -> {
            if (error == null) maintain();
        });
    }
    static {
        TIMER.scheduleWithFixedDelay(() -> {
            synchronized (UiData.class) { if (ready == null || ready.isCompletedExceptionally()) return; }
            maintain();
        }, 30, 30, TimeUnit.SECONDS);
    }
    private UiData() {}
}
