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

    @Query("SELECT userId, municipalityCode FROM SafetyRecord WHERE localSessionId = :sessionId AND insertRevision <= :revision AND userId > :after AND userId <= :through ORDER BY userId LIMIT :limit")
    abstract List<WireRecord> snapshotPage(String sessionId, long revision, int after, int through, int limit);

    @Query("SELECT userId, municipalityCode FROM SafetyRecord WHERE localSessionId = :sessionId AND sourceKind = 'SELF' LIMIT 1")
    abstract WireRecord self(String sessionId);

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

    @Query("SELECT * FROM WatchTarget WHERE targetUserId = :id")
    abstract WatchTarget watch(int id);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract long insertWatch(WatchTarget target);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract long insertHistory(NotificationHistory history);

    @Query("SELECT * FROM NotificationHistory WHERE historyExpiresAt > :now ORDER BY notificationId")
    abstract List<NotificationHistory> validHistories(long now);

    @Query("DELETE FROM NotificationHistory WHERE historyExpiresAt <= :now")
    abstract int deleteExpiredHistories(long now);
}
