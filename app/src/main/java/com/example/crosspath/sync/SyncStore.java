package com.example.crosspath.sync;

import com.example.crosspath.data.WireRecord;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/** Storage boundary: commit completes only after durable transaction completion. */
public interface SyncStore {
    final class Snapshot {
        public final String sessionId;
        public final long revision, count, endsAt;
        public Snapshot(String sessionId, long revision, long count, long endsAt) {
            this.sessionId = sessionId; this.revision = revision; this.count = count; this.endsAt = endsAt;
        }
    }
    CompletableFuture<Snapshot> snapshot();
    CompletableFuture<List<WireRecord>> page(Snapshot snapshot, int after, int through, int limit);
    CompletableFuture<int[]> commit(String peerMasterVersion, List<WireRecord> records, BooleanSupplier active);
}

