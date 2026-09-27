package com.example.crosspath.sync;

import com.example.crosspath.data.*;
import com.example.crosspath.protocol.*;
import org.junit.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

public class FullSyncCoordinatorTest {
    static final class MemoryStore implements SyncStore {
        final TreeMap<Integer, WireRecord> rows = new TreeMap<>();
        final Map<Integer, Long> revisions = new HashMap<>();
        long revision = 1;
        int commits, capacity = 13_000_000;
        boolean fail, hold;
        final CountDownLatch commitStarted = new CountDownLatch(1);
        Runnable release;
        void seed(int id) { rows.put(id, new WireRecord(id, 1)); revisions.put(id, revision); }
        @Override public CompletableFuture<Snapshot> snapshot() {
            return CompletableFuture.completedFuture(new Snapshot("test", revision, rows.size(), Long.MAX_VALUE));
        }
        @Override public CompletableFuture<List<WireRecord>> page(Snapshot s, int after, int through, int limit) {
            List<WireRecord> page = new ArrayList<>();
            for (WireRecord r : rows.tailMap(after, false).values()) {
                if (r.userId > through) break;
                if (revisions.get(r.userId) <= s.revision) page.add(r);
                if (page.size() == limit) break;
            }
            return CompletableFuture.completedFuture(page);
        }
        @Override public CompletableFuture<int[]> commit(String version, List<WireRecord> records, BooleanSupplier active) {
            commits++; CompletableFuture<int[]> result = new CompletableFuture<>();
            Runnable action = () -> {
                if (!active.getAsBoolean() || fail) { result.completeExceptionally(new IllegalStateException("injected")); return; }
                int[] codes = new int[records.size()];
                revision++;
                for (int i = 0; i < codes.length; i++) {
                    WireRecord r = records.get(i), old = rows.get(r.userId);
                    if (old != null) codes[i] = old.municipalityCode == r.municipalityCode ? 1 : 2;
                    else if (rows.size() >= capacity) codes[i] = 3;
                    else { rows.put(r.userId, r); revisions.put(r.userId, revision); }
                }
                result.complete(codes);
            };
            if (hold) release = action; else action.run();
            commitStarted.countDown(); return result;
        }
    }
    static final class Pair implements AutoCloseable {
        final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        final FullSyncCoordinator[] peers = new FullSyncCoordinator[2];
        final MemoryStore[] stores;
        final List<String> closed = new CopyOnWriteArrayList<>();
        final List<Integer> dataCounts = new CopyOnWriteArrayList<>();
        final List<byte[]> sentFromClient = new CopyOnWriteArrayList<>();
        final CountDownLatch finished = new CountDownLatch(2);
        final CountDownLatch anyClosed = new CountDownLatch(1);
        final CountDownLatch ackPaused = new CountDownLatch(1);
        final int[] sequence = new int[2], acknowledgements = new int[2];
        boolean dropAck, dropAllAcks, duplicateData, corruptData, badToken, memoryOnly;
        int maxDataBody;
        Pair(int mtu, MemoryStore a, MemoryStore b, int startBlock) {
            stores = new MemoryStore[]{a, b};
            MessageAssembler[] assemblers = {new MessageAssembler(), new MessageAssembler()};
            for (int i = 0; i < 2; i++) {
                int sender = i, receiver = 1 - i;
                peers[i] = new FullSyncCoordinator(new FullSyncCoordinator.Link() {
                    @Override public void send(int type, byte[] body, Runnable delivered) {
                        if (type == Protocol.DATA) {
                            DataCodec.Batch batch = DataCodec.decode(body, KyushuMunicipalities.load());
                            dataCounts.add(batch.records.size());
                            maxDataBody = Math.max(maxDataBody, body.length);
                            if (sender == 0) sentFromClient.add(body.clone());
                        }
                        boolean drop = type == Protocol.ACK && sender == 1 && (dropAck || dropAllAcks);
                        if (drop) dropAck = false;
                        if (type == Protocol.ACK) acknowledgements[sender]++;
                        byte[] transmitted = body.clone();
                        if (corruptData && type == Protocol.DATA) { transmitted[0] ^= 1; corruptData = false; }
                        // Corruption below framing must be detected by assembler. Use raw frames made from good body.
                        List<byte[]> frames = FrameCodec.fragment(type, sequence[sender]++, body, mtu);
                        if (memoryOnly && type == Protocol.HELLO) {
                            byte[] content = FrameCodec.verifiedContent(body);
                            java.nio.ByteBuffer.wrap(content).putInt(2, Protocol.CAP_FULL);
                            frames = FrameCodec.fragment(type, sequence[sender]++, FrameCodec.withCrc(content), mtu);
                        }
                        if (!Arrays.equals(transmitted, body)) frames.get(0)[10] ^= 1;
                        final List<byte[]> sentFrames = frames;
                        executor.execute(() -> {
                            if (!drop) {
                                try {
                                    for (byte[] frame : sentFrames) {
                                        MessageAssembler.Message m = assemblers[receiver].accept(frame, mtu);
                                        if (m != null) {
                                            peers[receiver].receive(m);
                                            if (duplicateData && type == Protocol.DATA) peers[receiver].receive(m);
                                        }
                                    }
                                } catch (IllegalArgumentException e) {
                                    closed.add("INVALID_FRAME"); peers[receiver].stop(); finished.countDown(); anyClosed.countDown();
                                }
                            }
                            delivered.run();
                        });
                    }
                    @Override public boolean matchesToken(long token) { return !badToken && token == receiver + 1L; }
                    @Override public void status(String text) { if (text.startsWith("保存完了・ACK待機中")) ackPaused.countDown(); }
                    @Override public void close(String text) { closed.add(text); finished.countDown(); anyClosed.countDown(); }
                }, stores[i], KyushuMunicipalities.load(), executor, startBlock, i + 100, block -> { },
                        System::currentTimeMillis, 30, 3000, 5000);
            }
        }
        void start() throws Exception {
            executor.submit(() -> { peers[0].ready(true, 1); peers[1].ready(false, 2); }).get();
        }
        void success() throws Exception {
            assertTrue("timeout: " + closed, finished.await(7, TimeUnit.SECONDS));
            assertEquals(closed.toString(), 2, closed.size());
            assertTrue(closed.toString(), closed.stream().allMatch(s -> s.startsWith("双方向FULL交換完了")));
        }
        @Override public void close() throws Exception {
            executor.submit(() -> { peers[0].stop(); peers[1].stop(); }).get();
            executor.shutdownNow();
        }
    }
    private static MemoryStore store(int... ids) { MemoryStore s = new MemoryStore(); for (int id : ids) s.seed(id); return s; }

    @Test public void debugPauseCommitsButDoesNotAckAndReconnectRetainsData() throws Exception {
        MemoryStore a = store(1), b = store(2);
        try (Pair p = new Pair(23, a, b, 0)) {
            p.peers[1].pauseAckAfterCommitForDebug(true);
            p.start(); assertTrue(p.ackPaused.await(2, TimeUnit.SECONDS));
            p.executor.submit(() -> { p.peers[0].stop(); p.peers[1].stop(); }).get();
            assertEquals(2, b.rows.size()); assertEquals(0, p.acknowledgements[1]);
            assertTrue(p.closed.isEmpty());
        }
        try (Pair p = new Pair(23, a, b, 0)) { p.start(); p.success(); }
        assertEquals(a.rows.keySet(), b.rows.keySet());
    }

    @Test public void debugPauseStillAllowsRetryTimeoutWithoutFalseSuccess() throws Exception {
        try (Pair p = new Pair(23, store(1), store(2), 0)) {
            p.peers[1].pauseAckAfterCommitForDebug(true);
            p.start(); assertTrue(p.anyClosed.await(2, TimeUnit.SECONDS));
            assertEquals(0, p.acknowledgements[1]);
            assertEquals(3, p.stores[1].commits);
            assertEquals(2, p.stores[1].rows.size());
            assertTrue(p.closed.get(0).contains("再送2回"));
        }
    }

    @Test public void debugPauseCannotBeChangedMidConnection() throws Exception {
        try (Pair p = new Pair(23, store(1), store(2), 0)) {
            p.start();
            p.executor.submit(() -> assertThrows(IllegalStateException.class,
                    () -> p.peers[1].pauseAckAfterCommitForDebug(true))).get();
            p.success();
        }
    }

    @Test public void fullExchangeAtAllMtusIsBoundedAndBlockAligned() throws Exception {
        for (int mtu : new int[]{23, 247, 517}) {
            MemoryStore a = store(1, 1023, 1024, 0xFFFFFF), b = store(2, 2048);
            for (int id = 3072; id < 3372; id++) a.seed(id);
            try (Pair p = new Pair(mtu, a, b, 3)) {
                p.start(); p.success();
                assertEquals(306, a.rows.size()); assertEquals(a.rows.keySet(), b.rows.keySet());
                assertEquals(1043, p.maxDataBody);
                assertTrue(p.dataCounts.contains(256));
                assertTrue(p.dataCounts.stream().allMatch(n -> n > 0 && n <= 256));
            }
        }
    }
    @Test public void lostAckResendsIdenticalBatchAndRequeriesStorage() throws Exception {
        MemoryStore a = store(1), b = store(2);
        try (Pair p = new Pair(23, a, b, 0)) {
            p.dropAck = true; p.start(); p.success();
            assertEquals(2, b.commits);
            assertEquals(2, b.rows.size());
            assertArrayEquals(p.sentFromClient.get(0), p.sentFromClient.get(1));
        }
    }
    @Test public void duplicateDataDuringCommitDoesNotCreateAnotherRow() throws Exception {
        try (Pair p = new Pair(23, store(1), store(2), 0)) {
            p.duplicateData = true; p.start(); p.success();
            assertEquals(2, p.stores[0].rows.size()); assertEquals(2, p.stores[1].rows.size());
        }
    }
    @Test public void receiverFailureNeverSendsSuccessfulAck() throws Exception {
        MemoryStore a = store(1), b = store(2); b.fail = true;
        try (Pair p = new Pair(23, a, b, 0)) {
            p.start(); assertTrue(b.commitStarted.await(2, TimeUnit.SECONDS));
            p.executor.submit(() -> { }).get();
            assertEquals(0, p.acknowledgements[1]); assertEquals(1, b.rows.size());
            assertTrue(p.closed.toString(), p.closed.stream().anyMatch(s -> s.startsWith("DB_READ_OR_WRITE_FAILED")));
        }
    }
    @Test public void disconnectBeforeCommitSuppressesWriteAndLateAck() throws Exception {
        MemoryStore a = store(1), b = store(2); b.hold = true;
        try (Pair p = new Pair(23, a, b, 0)) {
            p.start(); assertTrue(b.commitStarted.await(2, TimeUnit.SECONDS));
            p.executor.submit(() -> { p.peers[1].stop(); b.release.run(); }).get();
            p.executor.submit(() -> { }).get();
            assertEquals(1, b.rows.size()); assertEquals(0, p.acknowledgements[1]);
        }
    }
    @Test public void capacityRejectionProducesPartialNotSuccess() throws Exception {
        MemoryStore a = store(1), b = store(2); b.capacity = 1;
        try (Pair p = new Pair(23, a, b, 0)) {
            p.start(); assertTrue(p.finished.await(3, TimeUnit.SECONDS));
            assertEquals(p.closed.toString(), 2, p.closed.size());
            assertTrue(p.closed.toString(), p.closed.stream().allMatch(s -> s.startsWith("部分同期終了")));
            assertEquals(1, b.rows.size()); assertEquals(2, a.rows.size());
        }
    }
    @Test public void relaysPreviouslyReceivedRecordsToThirdStore() throws Exception {
        MemoryStore a = store(1), b = store(2), c = store(3);
        try (Pair p = new Pair(23, a, b, 0)) { p.start(); p.success(); }
        try (Pair p = new Pair(23, b, c, 0)) { p.start(); p.success(); }
        assertTrue(c.rows.containsKey(1)); assertEquals(3, c.rows.size());
    }
    @Test public void snapshotExcludesRecordsAddedDuringExchange() throws Exception {
        MemoryStore a = store(1), b = store(2);
        try (Pair p = new Pair(23, a, b, 0)) {
            p.start(); p.success();
            // B's initial snapshot contains only its own ID, not the ID it just received.
            assertEquals(Arrays.asList(1, 1), p.dataCounts);
        }
    }
    @Test public void tokenMismatchRejectsBeforeAnySave() throws Exception {
        try (Pair p = new Pair(23, store(1), store(2), 0)) {
            p.badToken = true; p.start(); assertTrue(p.finished.await(2, TimeUnit.SECONDS));
            assertEquals(0, p.stores[0].commits + p.stores[1].commits);
        }
    }
    @Test public void cursorWrapsOnceAndIncludesBoundaryBlocks() {
        FullSyncCursor c = new FullSyncCursor(16776191);
        assertEquals(16776191, c.after()); assertEquals(0xFFFFFF, c.through());
        c.acknowledged(0xFFFFFF); assertEquals(0xFFFFFF, c.after());
        assertTrue(c.wrap()); assertEquals(0, c.after()); assertEquals(16776191, c.through());
        assertFalse(c.wrap()); assertFalse(new FullSyncCursor(0).wrap());
    }

    @Test public void memoryAckPeerIsRejectedBeforeData() throws Exception {
        try (Pair p = new Pair(23, store(1), store(2), 0)) {
            p.memoryOnly = true; p.start(); assertTrue(p.finished.await(2, TimeUnit.SECONDS));
            assertTrue(p.dataCounts.isEmpty());
        }
    }
    @Test public void corruptFrameCannotReachStorage() throws Exception {
        try (Pair p = new Pair(23, store(1), store(2), 0)) {
            p.corruptData = true; p.start(); assertTrue(p.anyClosed.await(2, TimeUnit.SECONDS));
            assertEquals(0, p.stores[1].commits); assertTrue(p.closed.contains("INVALID_FRAME"));
        }
    }
    @Test public void stopsAfterTwoRetriesWhenAllAcksAreLost() throws Exception {
        try (Pair p = new Pair(23, store(1), store(2), 0)) {
            p.dropAllAcks = true; p.start(); assertTrue(p.anyClosed.await(2, TimeUnit.SECONDS));
            assertEquals(3, p.sentFromClient.size());
            assertEquals(2, p.stores[1].rows.size());
            assertTrue(p.closed.toString(), p.closed.get(0).contains("再送2回"));
        }
    }
    @Test public void expiredSnapshotCannotSendHello() throws Exception {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        try {
            CountDownLatch closed = new CountDownLatch(1);
            List<Integer> sends = new CopyOnWriteArrayList<>();
            SyncStore expired = new SyncStore() {
                public CompletableFuture<Snapshot> snapshot() { return CompletableFuture.completedFuture(new Snapshot("old", 1, 1, 100)); }
                public CompletableFuture<List<WireRecord>> page(Snapshot s, int a, int t, int n) { throw new AssertionError(); }
                public CompletableFuture<int[]> commit(String v, List<WireRecord> r, BooleanSupplier a) { throw new AssertionError(); }
            };
            FullSyncCoordinator c = new FullSyncCoordinator(new FullSyncCoordinator.Link() {
                public void send(int type, byte[] body, Runnable delivered) { sends.add(type); }
                public boolean matchesToken(long token) { return true; }
                public void status(String text) { }
                public void close(String reason) { closed.countDown(); }
            }, expired, KyushuMunicipalities.load(), executor, 0, 1, value -> { }, () -> 100, 30, 3000, 5000);
            executor.submit(() -> c.ready(true, 1)).get();
            assertTrue(closed.await(2, TimeUnit.SECONDS)); assertTrue(sends.isEmpty());
        } finally { executor.shutdownNow(); }
    }
}
