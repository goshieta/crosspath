package com.example.crosspath.sync;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/** Memory-only suppression/diagnostics. A new process or local period starts fresh. */
public final class EncounterPolicy {
    private static final class Entry { long revision, ended, until; int failures; byte[] digest; }
    private final Map<Long, Entry> peers = new LinkedHashMap<Long, Entry>(128, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, EncounterPolicy.Entry> eldest) { return size() > 256; }
    };
    private final Random random;
    private String session;
    private long localToken, lastEmptyPeer;
    private boolean hasEmptyPeer;
    private int consecutiveEmpty, convergenceThreshold;
    public EncounterPolicy(Random random) { this.random = random; }
    public synchronized long begin(String sessionId) {
        if (!sessionId.equals(session)) {
            peers.clear(); session = sessionId; localToken = random.nextLong();
            consecutiveEmpty = 0; hasEmptyPeer = false;
        }
        return localToken;
    }
    public synchronized long delay(long peer, long revision, long now) {
        Entry entry = peers.get(peer); if (entry == null) return 0;
        long until = entry.revision == revision ? entry.until : Math.min(entry.until, entry.ended + 5000);
        return Math.max(0, until - now);
    }
    public synchronized void record(long peer, long revision, boolean success, long now) {
        record(peer, revision, null, success, now);
    }
    public synchronized void record(long peer, long revision, byte[] digest, boolean success, long now) {
        Entry entry = peers.computeIfAbsent(peer, key -> new Entry());
        entry.digest = digest == null ? null : digest.clone();
        entry.failures = success ? 0 : Math.min(7, entry.failures + 1);
        long base = success ? 60_000 : Math.min(300_000, 5000L << (entry.failures - 1));
        long jittered = Math.min(300_000, Math.round(base * (0.8 + 0.4 * random.nextDouble())));
        entry.ended = now; entry.until = now + jittered; entry.revision = revision;
    }
    public synchronized void convergence(long peer, boolean verified, boolean equal, long newlyReceived) {
        if (newlyReceived > 0 || !verified || !equal) { consecutiveEmpty = 0; hasEmptyPeer = false; return; }
        if (!hasEmptyPeer || peer != lastEmptyPeer) consecutiveEmpty++;
        lastEmptyPeer = peer; hasEmptyPeer = true;
    }
    public synchronized void setConvergenceThreshold(int value) {
        if (value < 0) throw new IllegalArgumentException("K"); convergenceThreshold = value;
    }
    public synchronized boolean estimatedConvergence() { return convergenceThreshold > 0 && consecutiveEmpty >= convergenceThreshold; }
    public synchronized int consecutiveEmpty() { return consecutiveEmpty; }
    public synchronized void reset() { session = null; peers.clear(); consecutiveEmpty = 0; hasEmptyPeer = false; }
}
