package com.example.crosspath.data;

/** SC04/SC06 snapshot; never derived from notification history. */
public final class WatchStatus {
    public enum State { RECEIVED, NOT_RECEIVED, NO_ACTIVE_SESSION }
    public final int targetUserId;
    public final String displayName;
    public final State state;

    WatchStatus(int targetUserId, String displayName, State state) {
        this.targetUserId = targetUserId;
        this.displayName = displayName;
        this.state = state;
    }
}
