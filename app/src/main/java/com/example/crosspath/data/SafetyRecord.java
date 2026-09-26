package com.example.crosspath.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity
public final class SafetyRecord {
    @PrimaryKey public final int userId;
    public final int municipalityCode;
    public final long firstReceivedAt;
    @NonNull public final String sourceKind;
    @NonNull public final String localSessionId;
    public final long insertRevision;

    public SafetyRecord(int userId, int municipalityCode, long firstReceivedAt,
            @NonNull String sourceKind, @NonNull String localSessionId, long insertRevision) {
        new WireRecord(userId, municipalityCode);
        this.userId = userId;
        this.municipalityCode = municipalityCode;
        this.firstReceivedAt = firstReceivedAt;
        this.sourceKind = sourceKind;
        this.localSessionId = localSessionId;
        this.insertRevision = insertRevision;
    }
}
