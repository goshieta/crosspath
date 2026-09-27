package com.example.crosspath.sync;
import com.example.crosspath.data.WireRecord;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface DeltaSyncStore extends SyncStore {
    final class Summary {
        public final Snapshot snapshot;
        public final int[] counts;
        public Summary(Snapshot snapshot, int[] counts) { this.snapshot = snapshot; this.counts = counts.clone(); }
    }
    CompletableFuture<Summary> summary();
    CompletableFuture<byte[]> idDigest(Snapshot snapshot);
    CompletableFuture<byte[]> bitmap(Snapshot snapshot, int block);
    CompletableFuture<List<WireRecord>> records(Snapshot snapshot, int[] ids);
}
