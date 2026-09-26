package com.example.crosspath.data;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.annotation.NonNull;

@Database(entities = {SafetyRecord.class, ActiveSession.class, WatchTarget.class,
        NotificationHistory.class, ClockAnchor.class}, version = 2, exportSchema = true)
public abstract class AppDatabase extends RoomDatabase {
    abstract SafetyDao safetyDao();

    public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS ClockAnchor (singletonId INTEGER NOT NULL, "
                    + "wall INTEGER NOT NULL, elapsed INTEGER NOT NULL, bootMarker TEXT NOT NULL, "
                    + "PRIMARY KEY(singletonId))");
        }
    };

    private SessionClock clock;

    SessionClock sessionClock() {
        if (clock == null) throw new IllegalStateException("Use AppDatabase.open(context) in production");
        return clock;
    }

    /** Keep one instance for the application lifetime; close only after pending work completes. */
    public static AppDatabase open(Context context) {
        return open(context, "crosspath.db");
    }

    static AppDatabase open(Context context, String name) {
        AppDatabase db = Room.databaseBuilder(context.getApplicationContext(), AppDatabase.class,
                name).addMigrations(MIGRATION_1_2).build();
        db.clock = new AndroidSessionClock(context);
        return db;
    }
}
