package com.example.crosspath.sync;

/** Bounded circular keyset walk. A checkpoint is advanced only after an accepted ACK. */
public final class FullSyncCursor {
    private final int start;
    private boolean wrapped;
    private int after;
    public FullSyncCursor(int startAfter) {
        if (startAfter < 0 || startAfter > 0xFFFFFF) throw new IllegalArgumentException("cursor");
        start = startAfter;
        after = startAfter;
    }
    public int after() { return after; }
    public int through() { return wrapped ? start : 0xFFFFFF; }
    public void acknowledged(int lastId) { after = lastId; }
    public boolean wrap() {
        if (wrapped || start == 0) return false;
        wrapped = true; after = 0; return true;
    }
}
