package com.example.crosspath.sync;
import com.example.crosspath.data.*;
import com.example.crosspath.protocol.*;
import org.junit.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

public class DeltaSyncCoordinatorTest {
    static final class Store implements DeltaSyncStore {
        final FullSyncCoordinatorTest.MemoryStore memory = new FullSyncCoordinatorTest.MemoryStore();
        Store(int... ids) { for (int id : ids) memory.seed(id); }
        @Override public CompletableFuture<Snapshot> snapshot() { return memory.snapshot(); }
        @Override public CompletableFuture<List<WireRecord>> page(Snapshot s, int after, int through, int limit) { return memory.page(s, after, through, limit); }
        @Override public CompletableFuture<int[]> commit(String version, List<WireRecord> records, BooleanSupplier active) { return memory.commit(version, records, active); }
        @Override public CompletableFuture<Summary> summary() {
            return snapshot().thenApply(s -> {
                int[] counts = new int[16384]; for (int id : memory.rows.keySet()) counts[id >>> 10]++;
                return new Summary(s, counts);
            });
        }
        BitSet ids(Snapshot s) {
            BitSet ids = new BitSet();
            for (int id : memory.rows.keySet()) if (memory.revisions.get(id) <= s.revision) ids.set(id);
            return ids;
        }
        @Override public CompletableFuture<byte[]> idDigest(Snapshot s) {
            java.security.MessageDigest digest = HierarchyCodec.digest(s.count); BitSet ids = ids(s);
            for (int id = ids.nextSetBit(0); id >= 0; id = ids.nextSetBit(id + 1)) HierarchyCodec.digestId(digest, id);
            return CompletableFuture.completedFuture(digest.digest());
        }
        @Override public CompletableFuture<byte[]> bitmap(Snapshot s, int block) {
            return CompletableFuture.completedFuture(HierarchyCodec.bitmap(ids(s), block));
        }
        @Override public CompletableFuture<List<WireRecord>> records(Snapshot s, int[] ids) {
            List<WireRecord> records = new ArrayList<>();
            for (int id : ids) if (memory.revisions.get(id) <= s.revision) records.add(memory.rows.get(id));
            return CompletableFuture.completedFuture(records);
        }
    }
    static final class Pair implements AutoCloseable {
        final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        final DeltaSyncCoordinator[] peers = new DeltaSyncCoordinator[2];
        final Store[] stores;
        final List<String> closed = new CopyOnWriteArrayList<>();
        final List<Integer> senders = new CopyOnWriteArrayList<>();
        final CountDownLatch finished = new CountDownLatch(2), anyClosed = new CountDownLatch(1);
        final int[] sequence = new int[2];
        final long[] bytes = new long[2];
        int data, bitmaps, acks, summaries, lists, requests;
        boolean dropAck, dropAllAcks, corruptBitmap, corruptCrc, staleToken, lateInsert;
        Pair(int mode, int mtu, Store a, Store b) { this(mode, mtu, a, b, 2_000_000, 5000); }
        Pair(int mode, int mtu, Store a, Store b, long budget, long time) {
            stores = new Store[]{a, b};
            MessageAssembler[] assemblers = {new MessageAssembler(), new MessageAssembler()};
            for (int side = 0; side < 2; side++) {
                int sender = side, receiver = 1 - side;
                peers[side] = new DeltaSyncCoordinator(new DeltaSyncCoordinator.Link() {
                    public int mtu() { return mtu; }
                    public boolean matchesToken(long token) { return token == receiver + 1; }
                    public void status(String text) { }
                    public void close(String text) { closed.add(text); finished.countDown(); anyClosed.countDown(); }
                    public void send(int type, byte[] body, Runnable delivered) {
                        if (type == Protocol.DATA) {
                            data++; senders.add(sender);
                            if (lateInsert && sender == 0) { lateInsert = false; a.memory.revision++; a.memory.seed(7000); }
                        }
                        if (type == Protocol.L2_REQUEST) bitmaps++;
                        if (type == Protocol.SUMMARY) summaries++;
                        if (type == Protocol.ID_LIST) lists++;
                        if (type == Protocol.REQUEST) requests++;
                        if (type == Protocol.ACK) acks++;
                        boolean drop = type == Protocol.ACK && sender == 1 && (dropAck || dropAllAcks);
                        if (drop) dropAck = false;
                        byte[] wire = body.clone();
                        if (corruptBitmap && type == Protocol.L2_RESPONSE) {
                            byte[] content = FrameCodec.verifiedContent(wire); content[6] ^= (byte) 128;
                            wire = FrameCodec.withCrc(content); corruptBitmap = false;
                        }
                        if (staleToken && type == Protocol.L1_PAGE) {
                            byte[] content = FrameCodec.verifiedContent(wire); content[0] ^= 1;
                            wire = FrameCodec.withCrc(content); staleToken = false;
                        }
                        List<byte[]> frames = FrameCodec.fragment(type, sequence[sender]++ & 65535, wire, mtu);
                        bytes[sender] += frames.stream().mapToLong(frame -> frame.length + 4).sum();
                        if (corruptCrc && type == Protocol.DATA) { frames.get(0)[10] ^= 1; corruptCrc = false; }
                        executor.execute(() -> {
                            if (!drop) {
                                try {
                                    for (byte[] frame : frames) {
                                        MessageAssembler.Message m = assemblers[receiver].accept(frame, mtu);
                                        if (m != null) peers[receiver].receive(m);
                                    }
                                } catch (IllegalArgumentException invalid) {
                                    closed.add("CRC_FAILED"); peers[receiver].stop(); anyClosed.countDown();
                                }
                            }
                            delivered.run();
                        });
                    }
                }, stores[side], KyushuMunicipalities.load(), executor, mode, 100 + side, budget, time,
                        100, System::currentTimeMillis, new Random(7));
            }
        }
        void start() throws Exception { executor.submit(() -> { peers[0].ready(true, 1); peers[1].ready(false, 2); }).get(); }
        void success() throws Exception {
            assertTrue(closed.toString(), finished.await(7, TimeUnit.SECONDS));
            assertTrue(closed.toString(), peers[0].verifiedComplete() && peers[1].verifiedComplete());
            assertEquals(stores[0].memory.rows.keySet(), stores[1].memory.rows.keySet());
            assertEquals(bytes[0], peers[0].txBytes()); assertEquals(bytes[1], peers[1].txBytes());
        }
        public void close() throws Exception {
            executor.submit(() -> { peers[0].stop(); peers[1].stop(); }).get(); executor.shutdownNow();
        }
    }
    @Test public void bothModesExchangeOnlyMissingAtEveryMtu() throws Exception {
        for (int mode : new int[]{Protocol.FLAT, Protocol.HIERARCHICAL}) for (int mtu : new int[]{23, 247, 517})
            try (Pair p = new Pair(mode, mtu, new Store(1, 2), new Store(2, 3))) {
                p.start(); p.success(); assertEquals(2, p.data);
                assertEquals(1, p.peers[0].sentRecords()); assertEquals(1, p.peers[1].sentRecords());
                if (mode == Protocol.HIERARCHICAL) assertEquals(2, p.bitmaps);
                else { assertEquals(2, p.summaries); assertTrue(p.requests >= 2); }
            }
    }
    @Test public void equalIdsWithDifferentLocationsSendNoData() throws Exception {
        for (int mode : new int[]{Protocol.FLAT, Protocol.HIERARCHICAL}) {
            Store a = new Store(1, 2), b = new Store(1, 2); b.memory.rows.put(2, new WireRecord(2, 2));
            try (Pair p = new Pair(mode, 23, a, b)) {
                p.start(); p.success(); assertEquals(0, p.data); assertTrue(p.peers[0].verifiedEqual());
                assertEquals(2, b.memory.rows.get(2).municipalityCode);
                if (mode == Protocol.FLAT) assertEquals(0, p.lists);
            }
        }
    }
    @Test public void equalCountsDifferentIdsAndBoundaryIdsAreExchanged() throws Exception {
        try (Pair p = new Pair(Protocol.HIERARCHICAL, 517, new Store(1, 2, 1023, 0xFFFFFF), new Store(3, 4, 1024))) {
            p.start(); p.success(); assertEquals(7, p.stores[0].memory.rows.size()); assertFalse(p.peers[0].verifiedEqual());
        }
    }
    @Test public void fullAndEmptyBlocksNeedNoBitmapAndFullBlockZeroReservesZero() throws Exception {
        Store a = new Store(), b = new Store(); for (int id = 1; id <= 1023; id++) a.memory.seed(id);
        try (Pair p = new Pair(Protocol.HIERARCHICAL, 247, a, b)) {
            p.start(); p.success(); assertEquals(0, p.bitmaps); assertEquals(1023, p.peers[0].sentRecords());
        }
    }
    @Test public void batchesAlternateAndDoNotResendAcknowledgedIds() throws Exception {
        Store a = new Store(), b = new Store();
        for (int i = 1; i <= 600; i++) { a.memory.seed(i); b.memory.seed(i + 1024); }
        try (Pair p = new Pair(Protocol.HIERARCHICAL, 517, a, b)) {
            p.start(); p.success(); assertEquals(Arrays.asList(0, 1, 0, 1, 0, 1), p.senders);
        }
        try (Pair p = new Pair(Protocol.HIERARCHICAL, 517, a, b)) { p.start(); p.success(); assertEquals(0, p.data); }
    }
    @Test public void lostAckRetriesThroughStoreAndCountsRetryBytes() throws Exception {
        try (Pair p = new Pair(Protocol.HIERARCHICAL, 23, new Store(1), new Store(2))) {
            p.dropAck = true; p.start(); p.success(); assertEquals(2, p.stores[1].memory.commits);
            assertEquals(2, p.peers[0].sentRecords()); assertEquals(2, p.stores[1].memory.rows.size());
        }
    }
    @Test public void budgetAndCapacityFailuresNeverClaimCompletion() throws Exception {
        try (Pair p = new Pair(Protocol.HIERARCHICAL, 23, new Store(1), new Store(2), 1024, 3000)) {
            p.start(); assertTrue(p.anyClosed.await(4, TimeUnit.SECONDS)); assertFalse(p.peers[0].verifiedComplete());
            assertTrue(p.closed.toString(), p.closed.stream().anyMatch(s -> s.contains("予算")));
        }
        Store b = new Store(2); b.memory.capacity = 1;
        try (Pair p = new Pair(Protocol.HIERARCHICAL, 517, new Store(1), b)) {
            p.start(); assertTrue(p.anyClosed.await(4, TimeUnit.SECONDS)); assertFalse(p.peers[0].verifiedComplete());
            assertEquals(1, b.memory.rows.size());
        }
    }
    @Test public void corruptBitmapAndOldSnapshotAndCrcAreRejected() throws Exception {
        for (int fault = 0; fault < 3; fault++) try (Pair p = new Pair(Protocol.HIERARCHICAL, 23, new Store(1), new Store(2))) {
            p.corruptBitmap = fault == 0; p.staleToken = fault == 1; p.corruptCrc = fault == 2;
            p.start(); assertTrue(p.anyClosed.await(4, TimeUnit.SECONDS));
            assertFalse(p.peers[0].verifiedComplete()); assertFalse(p.peers[1].verifiedComplete());
            assertEquals(1, p.stores[1].memory.rows.size());
        }
    }
    @Test public void databaseFailureOrPausedAckNeverProducesSuccess() throws Exception {
        Store b = new Store(2); b.memory.fail = true;
        try (Pair p = new Pair(Protocol.FLAT, 517, new Store(1), b)) {
            p.start(); assertTrue(p.anyClosed.await(4, TimeUnit.SECONDS)); assertEquals(0, p.acks);
        }
        try (Pair p = new Pair(Protocol.HIERARCHICAL, 517, new Store(1), new Store(2))) {
            p.peers[1].pauseAckAfterCommitForDebug(true); p.start();
            assertTrue(p.anyClosed.await(4, TimeUnit.SECONDS)); assertEquals(0, p.acks);
            assertEquals(2, p.stores[1].memory.rows.size()); assertFalse(p.peers[0].verifiedComplete());
        }
    }

    @Test public void flatPagesAndRequestsHandleMoreThan256Ids() throws Exception {
        Store a = new Store(), b = new Store();
        for (int id = 1; id <= 600; id++) { if (id <= 300) a.memory.seed(id); else b.memory.seed(id); }
        try (Pair p = new Pair(Protocol.FLAT, 23, a, b)) {
            p.start(); p.success(); assertEquals(4, p.data); assertTrue(p.lists >= 4); assertTrue(p.requests >= 6);
        }
    }
    @Test public void frozenSnapshotExcludesNewInsertionUntilNextEncounter() throws Exception {
        Store a = new Store(1), b = new Store(2);
        try (Pair p = new Pair(Protocol.HIERARCHICAL, 517, a, b)) {
            p.lateInsert = true; p.start(); assertTrue(p.finished.await(7, TimeUnit.SECONDS));
            assertTrue(p.closed.toString(), p.peers[0].verifiedComplete() && p.peers[1].verifiedComplete());
            assertTrue(a.memory.rows.containsKey(7000)); assertFalse(b.memory.rows.containsKey(7000));
        }
        try (Pair p = new Pair(Protocol.HIERARCHICAL, 517, a, b)) { p.start(); p.success(); assertEquals(1, p.peers[0].sentRecords()); }
    }
    @Test public void noAckBeforeCommitAndDisconnectCancelsQueuedSave() throws Exception {
        Store b = new Store(2); b.memory.hold = true;
        try (Pair p = new Pair(Protocol.HIERARCHICAL, 517, new Store(1), b)) {
            p.start(); assertTrue(b.memory.commitStarted.await(3, TimeUnit.SECONDS));
            p.executor.submit(() -> {
                assertEquals(0, p.acks); p.peers[1].stop(); b.memory.release.run();
            }).get();
            assertEquals(1, b.memory.rows.size()); assertFalse(p.peers[1].verifiedComplete());
        }
    }
    @Test public void equalLargeSetSavesBytesAgainstFullRecordPayload() throws Exception {
        Store a = new Store(), b = new Store();
        for (int id = 1; id <= 8192; id++) { a.memory.seed(id); b.memory.seed(id); }
        for (int mode : new int[]{Protocol.FLAT, Protocol.HIERARCHICAL}) {
            try (Pair p = new Pair(mode, 517, a, b)) {
                p.start(); p.success(); assertEquals(0, p.data);
                assertTrue(p.peers[0].txBytes() < 8192 * 4L); assertTrue(p.peers[1].txBytes() < 8192 * 4L);
            }
        }
    }
    @Test public void contactTimeoutWhileCommitIsPendingIsPartial() throws Exception {
        Store b = new Store(2); b.memory.hold = true;
        try (Pair p = new Pair(Protocol.HIERARCHICAL, 517, new Store(1), b, 2_000_000, 120)) {
            p.start(); assertTrue(p.anyClosed.await(3, TimeUnit.SECONDS));
            assertFalse(p.peers[0].verifiedComplete()); assertFalse(p.peers[1].verifiedComplete());
            assertTrue(p.closed.toString(), p.closed.stream().anyMatch(s -> s.contains("時間枠")));
        }
    }
}
