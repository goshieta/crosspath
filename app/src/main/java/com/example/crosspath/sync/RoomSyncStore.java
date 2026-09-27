package com.example.crosspath.sync;

import com.example.crosspath.data.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

public final class RoomSyncStore implements SyncStore {
    private final SafetyRepository repository;
    private final String sessionId;
    public RoomSyncStore(SafetyRepository repository, String sessionId) {
        this.repository = repository; this.sessionId = sessionId;
    }
    @Override public CompletableFuture<Snapshot> snapshot() { return repository.snapshot(sessionId); }
    @Override public CompletableFuture<List<WireRecord>> page(Snapshot s, int after, int through, int limit) {
        return repository.snapshotPage(s, after, through, limit);
    }
    @Override public CompletableFuture<int[]> commit(String version, List<WireRecord> records, BooleanSupplier active) {
        return repository.applyReceivedBatch(sessionId, version, records, active).thenApply(result -> {
            int[] codes = new int[result.outcomes.size()];
            for (int i = 0; i < codes.length; i++) {
                switch (result.outcomes.get(i)) {
                    case NEW_PERSON: codes[i] = 0; break;
                    case DUPLICATE: codes[i] = 1; break;
                    case ID_ALREADY_KNOWN: codes[i] = 2; break;
                    case CAPACITY_REJECTED: codes[i] = 3; break;
                    default: throw new IllegalStateException("Unknown persistence outcome");
                }
            }
            return codes;
        });
    }
}

