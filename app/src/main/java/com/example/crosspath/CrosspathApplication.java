package com.example.crosspath;

import android.app.Application;
import com.example.crosspath.data.*;
import java.util.concurrent.*;

/** One Room database and serial DB executor across Activity recreation. */
public final class CrosspathApplication extends Application {
    public SafetyRepository repository;
    public ExecutorService dbExecutor;
    public final SessionStopRegistry sessionStops = new SessionStopRegistry();
    @Override public void onCreate() {
        super.onCreate();
        dbExecutor = Executors.newSingleThreadExecutor();
        repository = new SafetyRepository(AppDatabase.open(this), dbExecutor,
                KyushuMunicipalities.load(), sessionStops);
    }
}
