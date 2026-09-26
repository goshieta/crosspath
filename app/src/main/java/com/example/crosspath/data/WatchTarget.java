package com.example.crosspath.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity
public final class WatchTarget {
    @PrimaryKey public final int targetUserId;
    @NonNull public final String displayName;
    public final long createdAt;

    public WatchTarget(int targetUserId, @NonNull String displayName, long createdAt) {
        WireRecord.requireUserId(targetUserId);
        MunicipalityMaster.requireName(displayName);
        this.targetUserId = targetUserId;
        this.displayName = displayName;
        this.createdAt = createdAt;
    }
}
