package com.example.crosspath.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(indices = @Index(value = "sessionId", unique = true))
public final class ActiveSession {
    @PrimaryKey public int singletonId = 1;
    @NonNull public String sessionId = "";
    public long startedAtWall;
    public long endsAtWall;
    public long startedAtElapsed;
    public long lastObservedWall;
    @NonNull public String bootMarker = "";
    @NonNull public String state = "ACTIVE";
    public boolean relayEnabled = true;
    public long dataRevision;
}
