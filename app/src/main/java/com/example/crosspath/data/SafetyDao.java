package com.example.crosspath.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;

/** Internal persistence access; callers use SafetyRepository to preserve transaction invariants. */
@Dao
abstract class SafetyDao {
    @Query("SELECT * FROM SafetyRecord WHERE userId = :id")
    abstract SafetyRecord find(int id);

    @Query("SELECT userId, municipalityCode FROM SafetyRecord WHERE localSessionId = :sessionId AND userId > :after ORDER BY userId LIMIT :limit")
    abstract List<WireRecord> page(String sessionId, int after, int limit);

    @Query("SELECT COUNT(*) FROM SafetyRecord")
    abstract long count();

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract long insert(SafetyRecord record);

    @Query("SELECT * FROM ActiveSession WHERE singletonId = 1")
    abstract ActiveSession session();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract void saveSession(ActiveSession session);

    @Query("DELETE FROM SafetyRecord")
    abstract void clearRecords();

    @Query("DELETE FROM SafetyRecord WHERE localSessionId = :sessionId")
    abstract int deleteSessionRecords(String sessionId);

    @Query("UPDATE ActiveSession SET state = 'ENDED', relayEnabled = 0 "
            + "WHERE singletonId = 1 AND sessionId = :sessionId AND state = 'ACTIVE'")
    abstract int endExpiredSession(String sessionId);

    @Query("SELECT * FROM ClockAnchor WHERE singletonId = 1")
    abstract ClockAnchor clockAnchor();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract void saveClockAnchor(ClockAnchor anchor);

    @Query("SELECT * FROM WatchTarget WHERE targetUserId = :id")
    abstract WatchTarget watch(int id);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract long insertWatch(WatchTarget target);

    @Query("SELECT * FROM WatchTarget ORDER BY targetUserId")
    abstract List<WatchTarget> watchTargets();

    static class WatchRow {
        public int targetUserId;
        public String displayName;
        public boolean received;
    }

    @Query("SELECT w.targetUserId, w.displayName, (r.userId IS NOT NULL) AS received "
            + "FROM WatchTarget w LEFT JOIN SafetyRecord r ON r.userId = w.targetUserId "
            + "AND r.localSessionId = :sessionId AND r.sourceKind = 'RECEIVED' ORDER BY w.targetUserId")
    abstract List<WatchRow> watchStatuses(String sessionId);

    @Query("DELETE FROM WatchTarget WHERE targetUserId = :id")
    abstract int deleteWatchTarget(int id);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract long insertHistory(NotificationHistory history);

    @Query("SELECT * FROM NotificationHistory WHERE historyExpiresAt > :now ORDER BY notificationId")
    abstract List<NotificationHistory> validHistories(long now);

    @Query("DELETE FROM NotificationHistory WHERE historyExpiresAt <= :now")
    abstract int deleteExpiredHistories(long now);

    @Query("SELECT * FROM NotificationHistory WHERE localSessionId = :sessionId "
            + "AND historyExpiresAt > :now AND deliveryState = 'PENDING' ORDER BY notificationId")
    abstract List<NotificationHistory> pendingNotifications(String sessionId, long now);

    @Query("UPDATE NotificationHistory SET deliveryState = :state WHERE notificationId = :id "
            + "AND deliveryState = 'PENDING' AND historyExpiresAt > :now")
    abstract int finishNotification(long id, String state, long now);
}
