package com.example.crosspath.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(indices = {
        @Index(value = {"localSessionId", "targetUserId"}, unique = true),
        @Index("firstReceivedAt"), @Index("historyExpiresAt")})
public final class NotificationHistory {
    @PrimaryKey(autoGenerate = true) public long notificationId;
    @NonNull public String localSessionId = "";
    public long sessionStartedAt;
    public long sessionEndedAt;
    public long historyExpiresAt;
    public int targetUserId;
    public int municipalityCode;
    @NonNull public String displayNameSnapshot = "";
    @NonNull public String municipalityNameSnapshot = "";
    public long firstReceivedAt;
    @NonNull public String deliveryState = "PENDING";
    public boolean isRead;
}
