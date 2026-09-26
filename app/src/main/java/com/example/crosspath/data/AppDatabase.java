package com.example.crosspath.data;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(entities = {SafetyRecord.class, ActiveSession.class, WatchTarget.class,
        NotificationHistory.class}, version = 1, exportSchema = true)
public abstract class AppDatabase extends RoomDatabase {
    abstract SafetyDao safetyDao();

    /** Keep one instance for the application lifetime; close only after pending work completes. */
    public static AppDatabase open(Context context) {
        return Room.databaseBuilder(context.getApplicationContext(), AppDatabase.class,
                "crosspath.db").build();
    }
}
