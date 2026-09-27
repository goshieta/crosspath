package com.example.crosspath;

import android.app.Application;
import com.example.crosspath.data.*;
import java.util.concurrent.*;

/** One Room database and serial DB executor across Activity recreation. */
public final class CrosspathApplication extends Application {
    public SafetyRepository repository;
    public volatile boolean bleDebugActive;
    public final androidx.lifecycle.MutableLiveData<com.example.crosspath.service.RelayStatus> relayStatus =
            new androidx.lifecycle.MutableLiveData<>(new com.example.crosspath.service.RelayStatus(
                    com.example.crosspath.service.RelayStatus.State.STOPPED, "通信は開始されていません", ""));
    public final androidx.lifecycle.MutableLiveData<Long> relayDataChanges = new androidx.lifecycle.MutableLiveData<>(0L);
    public ExecutorService dbExecutor;
    public final SessionStopRegistry sessionStops = new SessionStopRegistry();
    public final com.example.crosspath.sync.EncounterPolicy encounters =
            new com.example.crosspath.sync.EncounterPolicy(new java.security.SecureRandom());
    @Override public void onCreate() {
        super.onCreate();
        dbExecutor = Executors.newSingleThreadExecutor();
        repository = new SafetyRepository(AppDatabase.open(this), dbExecutor,
                KyushuMunicipalities.load(), sessionStops);
        repository.checkAndEndExpiredSession().thenCompose(status -> status.canCommunicate
                ? repository.syncSummary(status.sessionId) : CompletableFuture.completedFuture(null));
    }
}
