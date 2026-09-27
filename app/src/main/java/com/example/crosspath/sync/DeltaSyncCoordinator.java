package com.example.crosspath.sync;

import com.example.crosspath.data.*;
import com.example.crosspath.protocol.*;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;
import static com.example.crosspath.protocol.HierarchyCodec.check;

/** FLAT/HIERARCHICAL exchange. All state except the DB cancellation flag lives on one executor. */
public final class DeltaSyncCoordinator implements SyncExchange {
    public interface Link extends FullSyncCoordinator.Link {
        int mtu();
        default long issuedTxBytes() { return -1; }
        default long receivedRxBytes() { return -1; }
    }
    private final Link link;
    private final DeltaSyncStore store;
    private final MunicipalityMaster master;
    private final ScheduledExecutorService executor;
    private final LongSupplier clock;
    private final Random random;
    private final int preferredMode, token;
    private final long byteBudget, totalMs, ackMs;
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final List<ScheduledFuture<?>> timers = new ArrayList<>();
    private DeltaSyncStore.Summary summary;
    private SyncStore.Snapshot snapshot;
    private MessageAssembler.Message earlyHello;
    private boolean started, client, helloReceived, beginReceived, descriptorsStarted, descriptorsSent, peerDescriptors;
    private boolean transfers, myTurn, localEnded, peerEnded, reading, committing, pauseAck, doneSent, verified;
    private boolean bitmapReplyPending;
    private int mode, remoteToken, maxBody = Protocol.MAX_BODY;
    private long peerCount, txBytes, rxBytes, sentRecords, receivedRecords, newReceived, peerNew;
    private final int[] peerCounts = new int[HierarchyCodec.BLOCKS];
    private final int[] receivedCounts = new int[HierarchyCodec.BLOCKS];
    private final int[] expectedCounts = new int[HierarchyCodec.BLOCKS];
    private final BitSet exactIncoming = new BitSet(), exactBlocks = new BitSet();
    private boolean incomingVerified;
    private long listedPeerCount, uniqueReceivedCount;
    private int nextPeerBlock, pageNo, peerPageNo, lastPeerId;
    private final BitSet localIds = new BitSet(), peerIds = new BitSet(), receivedIds = new BitSet();
    private byte[] digest, peerDigest;
    private MessageDigest incomingDigest;
    private boolean summarySent, flatListing, fastEqual, requestsStarted, requestsSent, peerRequestsDone;
    private int requestNo = 1, peerRequestNo = 1, requestAfter, peerRequestAfter;
    private List<Integer> blocks;
    private int blockIndex, currentBlock = -1, waitingBitmap = -1;
    private BitSet localBlock, missing;
    private int nextBatch = 1, receivedBatch, lastAck, retries;
    private int[] pendingIds;
    private byte[] pending, lastReceived;
    private ScheduledFuture<?> ackTimer;

    public DeltaSyncCoordinator(Link link, DeltaSyncStore store, MunicipalityMaster master,
            ScheduledExecutorService executor, int preferredMode, int token, long byteBudget, long totalMs) {
        this(link, store, master, executor, preferredMode, token, byteBudget, totalMs, 10_000,
                System::currentTimeMillis, new Random());
    }
    public DeltaSyncCoordinator(Link link, DeltaSyncStore store, MunicipalityMaster master,
            ScheduledExecutorService executor, int preferredMode, int token, long byteBudget, long totalMs,
            long ackMs, LongSupplier clock, Random random) {
        check(preferredMode == Protocol.FLAT || preferredMode == Protocol.HIERARCHICAL);
        check(byteBudget >= 1024 && totalMs > 0 && ackMs > 0);
        this.link = link; this.store = store; this.master = master; this.executor = executor;
        this.preferredMode = preferredMode; this.token = token; this.byteBudget = byteBudget;
        this.totalMs = totalMs; this.ackMs = ackMs; this.clock = clock; this.random = random;
        master.requireVersion(KyushuMunicipalities.VERSION);
    }
    @Override public void pauseAckAfterCommitForDebug(boolean enabled) {
        if (started) throw new IllegalStateException("Configure before connection");
        pauseAck = com.example.crosspath.BuildConfig.DEBUG && enabled;
    }
    @Override public void ready(boolean client, long localToken) {
        if (started || !active.get()) return;
        started = true; this.client = client;
        later(totalMs, () -> fail("PARTIAL: 接続時間枠終了"));
        async(store.summary(), value -> {
            summary = value; snapshot = value.snapshot;
            check(snapshot.count >= 0 && snapshot.count <= 13_000_000 && value.counts.length == HierarchyCodec.BLOCKS);
            long total = 0;
            for (int i = 0; i < value.counts.length; i++) {
                check(value.counts[i] >= 0 && value.counts[i] <= HierarchyCodec.capacity(i)); total += value.counts[i];
            }
            check(total == snapshot.count && snapshot.endsAt > clock.getAsLong());
            later(snapshot.endsAt - clock.getAsLong(), () -> fail("SESSION_EXPIRED"));
            int caps = Protocol.CAP_FLAT | FullSyncCoordinator.CAP_DURABLE_FULL;
            if (preferredMode == Protocol.HIERARCHICAL) caps |= Protocol.CAP_HIERARCHICAL;
            send(Protocol.HELLO, ByteBuffer.allocate(24).put((byte) Protocol.MAJOR).put((byte) Protocol.MINOR)
                    .putInt(caps).putLong(localToken).putInt(Protocol.KYUSHU_TEST_MASTER)
                    .putInt((int) snapshot.count).putShort((short) Protocol.MAX_BODY).array(), () -> {});
            if (earlyHello != null) { MessageAssembler.Message m = earlyHello; earlyHello = null; handle(m); }
        });
    }
    @Override public void receive(MessageAssembler.Message message) {
        if (!active.get()) return;
        try {
            rxBytes += message.attBytes;
            if (rxBytes + txBytes > byteBudget) { fail("PARTIAL: 共有バイト予算終了"); return; }
            if (snapshot == null) {
                check(started && message.type == Protocol.HELLO && earlyHello == null);
                FrameCodec.verifiedContent(message.body); earlyHello = message; return;
            }
            check(clock.getAsLong() < snapshot.endsAt); handle(message);
        } catch (RuntimeException error) { fail("INVALID_DELTA_FRAME / INCOMPATIBLE_VERSION"); }
    }
    private void handle(MessageAssembler.Message message) {
        ByteBuffer b = ByteBuffer.wrap(FrameCodec.verifiedContent(message.body));
        if (message.type == Protocol.HELLO) {
            check(!helloReceived && b.remaining() == 24);
            int major = b.get() & 255, minor = b.get() & 255, caps = b.getInt();
            long peerToken = b.getLong(); int version = b.getInt();
            peerCount = Integer.toUnsignedLong(b.getInt()); maxBody = b.getShort() & 65535;
            check(major == Protocol.MAJOR && minor == Protocol.MINOR && version == Protocol.KYUSHU_TEST_MASTER
                    && (caps & FullSyncCoordinator.CAP_DURABLE_FULL) != 0 && link.matchesToken(peerToken)
                    && peerCount <= 13_000_000 && maxBody >= 525 && maxBody <= Protocol.MAX_BODY);
            mode = preferredMode == Protocol.HIERARCHICAL && (caps & Protocol.CAP_HIERARCHICAL) != 0
                    ? Protocol.HIERARCHICAL : Protocol.FLAT;
            check(mode != Protocol.FLAT || (caps & Protocol.CAP_FLAT) != 0);
            helloReceived = true;
            link.status("HELLO確認済み / " + (mode == Protocol.HIERARCHICAL ? "階層同期" : "差分同期") + " / 保存後ACK");
            send(Protocol.BEGIN, ByteBuffer.allocate(5).put((byte) mode).putInt(token).array(), () -> {});
            return;
        }
        check(helloReceived && message.body.length <= maxBody);
        if (message.type == Protocol.BEGIN) {
            check(!beginReceived && b.remaining() == 5 && (b.get() & 255) == mode);
            remoteToken = b.getInt(); beginReceived = true; startDescriptors(); return;
        }
        check(beginReceived);
        if (!transfers && (message.type == Protocol.DATA || message.type == Protocol.L2_REQUEST
                || message.type == Protocol.TURN_END)) tryTransfers();
        switch (message.type) {
            case Protocol.L1_PAGE: receiveCounts(b); break;
            case Protocol.SUMMARY: receiveSummary(b); break;
            case Protocol.ID_LIST: receiveIds(b); break;
            case Protocol.REQUEST: receiveRequest(b); break;
            case Protocol.L2_REQUEST: replyBitmap(b); break;
            case Protocol.L2_RESPONSE: receiveBitmap(b); break;
            case Protocol.DATA: receiveData(message.body); break;
            case Protocol.ACK: receiveAck(b); break;
            case Protocol.TURN_END: receiveTurn(b); break;
            case Protocol.DONE:
                verifyIncoming();
                check(transfers && localEnded && peerEnded && !committing && pending == null
                        && b.remaining() == 1 && (b.get() & 255) == result());
                if (client) finish(); else send(Protocol.DONE, new byte[]{(byte) result()}, this::finish);
                break;
            case 9: fail("PARTIAL: 相手ERROR"); break;
            default: throw new IllegalArgumentException("Unexpected delta message");
        }
    }
    private void startDescriptors() {
        check(!descriptorsStarted); descriptorsStarted = true;
        if (mode == Protocol.HIERARCHICAL) sendCounts(0);
        else async(store.idDigest(snapshot), value -> {
            check(value.length == 32); digest = value; summarySent = true;
            send(Protocol.SUMMARY, ByteBuffer.allocate(40).putInt(token).putInt((int) snapshot.count).put(digest).array(), this::startFlat);
            startFlat();
        });
    }
    private void sendCounts(int start) {
        if (start == HierarchyCodec.BLOCKS) { tryTransfers(); return; }
        int end = start + HierarchyCodec.PAGE_BLOCKS;
        if (end == HierarchyCodec.BLOCKS) descriptorsSent = true;
        send(Protocol.L1_PAGE, HierarchyCodec.counts(token, start, Arrays.copyOfRange(summary.counts, start, end)),
                () -> sendCounts(end));
    }
    private void receiveCounts(ByteBuffer b) {
        check(mode == Protocol.HIERARCHICAL && !peerDescriptors);
        HierarchyCodec.Counts page = HierarchyCodec.readCounts(b);
        check(page.token == remoteToken && page.start == nextPeerBlock && page.values.length == HierarchyCodec.PAGE_BLOCKS);
        System.arraycopy(page.values, 0, peerCounts, page.start, page.values.length);
        nextPeerBlock += page.values.length;
        if (nextPeerBlock == HierarchyCodec.BLOCKS) {
            check(Arrays.stream(peerCounts).asLongStream().sum() == peerCount); peerDescriptors = true; tryTransfers();
        }
    }
    private void receiveSummary(ByteBuffer b) {
        check(mode == Protocol.FLAT && peerDigest == null && b.remaining() == 40
                && b.getInt() == remoteToken && Integer.toUnsignedLong(b.getInt()) == peerCount);
        peerDigest = new byte[32]; b.get(peerDigest); startFlat();
    }
    private void startFlat() {
        if (!summarySent || peerDigest == null || flatListing) return;
        flatListing = true;
        if (snapshot.count == peerCount && Arrays.equals(digest, peerDigest)) {
            fastEqual = true; descriptorsSent = peerDescriptors = true; tryTransfers(); return;
        }
        incomingDigest = HierarchyCodec.digest(peerCount); sendIds(0);
    }
    private void sendIds(int after) {
        int limit = Math.min(256, (maxBody - 15) / 3);
        async(store.page(snapshot, after, 0xFFFFFF, limit), rows -> {
            check(rows.size() <= limit);
            boolean last = rows.size() < limit;
            ByteBuffer b = ByteBuffer.allocate(11 + 3 * rows.size()).putInt(token).putInt(pageNo++)
                    .put((byte) (last ? 1 : 0)).putShort((short) rows.size());
            int tail = after;
            for (WireRecord row : rows) { check(row.userId > tail); tail = row.userId; localIds.set(tail); HierarchyCodec.putId(b, tail); }
            if (last) { check(localIds.cardinality() == snapshot.count); descriptorsSent = true; }
            final int next = tail;
            send(Protocol.ID_LIST, b.array(), () -> { if (last) tryRequests(); else sendIds(next); });
        });
    }
    private void receiveIds(ByteBuffer b) {
        check(mode == Protocol.FLAT && flatListing && !fastEqual && !peerDescriptors && b.remaining() >= 11
                && b.getInt() == remoteToken && b.getInt() == peerPageNo++);
        int last = b.get() & 255, count = b.getShort() & 65535;
        check(last <= 1 && count <= 256 && (count > 0 || last == 1) && b.remaining() == count * 3);
        for (int i = 0; i < count; i++) {
            int id = HierarchyCodec.getId(b); check(id > lastPeerId); lastPeerId = id;
            peerIds.set(id); peerCounts[id >>> 10]++; HierarchyCodec.digestId(incomingDigest, id);
        }
        listedPeerCount += count; check(listedPeerCount <= peerCount);
        if (last == 1) {
            check(listedPeerCount == peerCount && Arrays.equals(peerDigest, incomingDigest.digest()));
            peerDescriptors = true; tryRequests();
        }
    }
    private static int nextMissing(BitSet have, BitSet other, int after) {
        for (int id = have.nextSetBit(after + 1); id >= 0; id = have.nextSetBit(id + 1)) if (!other.get(id)) return id;
        return -1;
    }
    private void tryRequests() {
        if (!descriptorsSent || !peerDescriptors || requestsStarted) return;
        requestsStarted = true; sendRequest();
    }
    private void sendRequest() {
        int[] ids = new int[Math.min(256, (maxBody - 14) / 3)]; int count = 0;
        while (count < ids.length) {
            int id = nextMissing(peerIds, localIds, requestAfter); if (id < 0) break;
            ids[count++] = id; requestAfter = id;
        }
        ByteBuffer b = ByteBuffer.allocate(10 + count * 3).putInt(remoteToken).putInt(requestNo++).putShort((short) count);
        for (int i = 0; i < count; i++) HierarchyCodec.putId(b, ids[i]);
        boolean last = count == 0; if (last) requestsSent = true;
        send(Protocol.REQUEST, b.array(), () -> { if (last) tryTransfers(); else sendRequest(); });
    }
    private void receiveRequest(ByteBuffer b) {
        check(mode == Protocol.FLAT && !fastEqual && descriptorsSent && peerDescriptors && !peerRequestsDone
                && b.remaining() >= 10 && b.getInt() == token && b.getInt() == peerRequestNo++);
        int count = b.getShort() & 65535; check(count <= 256 && b.remaining() == count * 3);
        for (int i = 0; i < count; i++) {
            int id = HierarchyCodec.getId(b);
            check(id == nextMissing(localIds, peerIds, peerRequestAfter)); peerRequestAfter = id;
        }
        if (count == 0) { check(nextMissing(localIds, peerIds, peerRequestAfter) < 0); peerRequestsDone = true; tryTransfers(); }
    }
    private void tryTransfers() {
        if (transfers || !descriptorsSent || !peerDescriptors
                || mode == Protocol.FLAT && !fastEqual && (!requestsSent || !peerRequestsDone)) return;
        transfers = true; myTurn = client; blocks = new ArrayList<>();
        if (!fastEqual) {
            for (int i = 0; i < HierarchyCodec.BLOCKS; i++) {
                int ours = summary.counts[i], theirs = peerCounts[i];
                if (mode == Protocol.FLAT) {
                    BitSet wanted = peerIds.get(i * 1024, (i + 1) * 1024);
                    wanted.andNot(localIds.get(i * 1024, (i + 1) * 1024)); expectedCounts[i] = wanted.cardinality();
                } else if (ours == 0) expectedCounts[i] = theirs;
                else if (ours == HierarchyCodec.capacity(i) || theirs == 0) expectedCounts[i] = 0;
                else if (theirs == HierarchyCodec.capacity(i)) expectedCounts[i] = theirs - ours;
                else expectedCounts[i] = -1; // Determined by L2, even when the counts match.
                if (summary.counts[i] > 0 && peerCounts[i] < HierarchyCodec.capacity(i)) blocks.add(i);
            }
            Collections.shuffle(blocks, random);
            blocks.sort(Comparator.comparingInt(block -> peerCounts[block]));
        }
        drive();
    }
    private void drive() {
        if (!active.get() || !transfers || !myTurn || localEnded || pending != null || reading || waitingBitmap >= 0) return;
        if (missing != null && !missing.isEmpty()) { sendMissing(); return; }
        if (blockIndex == blocks.size()) { endDirection(); return; }
        currentBlock = blocks.get(blockIndex++); final int block = currentBlock;
        reading = true;
        async(store.bitmap(snapshot, block), value -> {
            reading = false; localBlock = HierarchyCodec.readBitmap(value, block, summary.counts[block]);
            if (mode == Protocol.FLAT) {
                missing = (BitSet) localBlock.clone(); missing.andNot(peerIds.get(block * 1024, (block + 1) * 1024)); drive();
            } else if (peerCounts[block] == 0) { missing = (BitSet) localBlock.clone(); drive(); }
            else {
                waitingBitmap = block;
                send(Protocol.L2_REQUEST, ByteBuffer.allocate(6).putInt(remoteToken).putShort((short) block).array(), () -> {});
            }
        });
    }
    private void replyBitmap(ByteBuffer b) {
        check(mode == Protocol.HIERARCHICAL && transfers && !myTurn && !bitmapReplyPending
                && b.remaining() == 6 && b.getInt() == token);
        int block = b.getShort() & 65535; HierarchyCodec.capacity(block);
        check(summary.counts[block] > 0 && summary.counts[block] < HierarchyCodec.capacity(block));
        bitmapReplyPending = true;
        async(store.bitmap(snapshot, block), bits -> {
            HierarchyCodec.readBitmap(bits, block, summary.counts[block]);
            bitmapReplyPending = false;
            send(Protocol.L2_RESPONSE, ByteBuffer.allocate(134).putInt(token).putShort((short) block).put(bits).array(), () -> {});
        });
    }
    private void receiveBitmap(ByteBuffer b) {
        check(mode == Protocol.HIERARCHICAL && waitingBitmap >= 0 && b.remaining() == 134 && b.getInt() == remoteToken);
        int block = b.getShort() & 65535; check(block == waitingBitmap);
        byte[] bytes = new byte[128]; b.get(bytes);
        BitSet peer = HierarchyCodec.readBitmap(bytes, block, peerCounts[block]);
        BitSet incoming = (BitSet) peer.clone(); incoming.andNot(localBlock);
        expectedCounts[block] = incoming.cardinality(); exactBlocks.set(block);
        for (int bit = incoming.nextSetBit(0); bit >= 0; bit = incoming.nextSetBit(bit + 1)) exactIncoming.set(block * 1024 + bit);
        waitingBitmap = -1; missing = (BitSet) localBlock.clone(); missing.andNot(peer); drive();
    }
    private void sendMissing() {
        int limit = Math.min(256, (maxBody - 19) / 4);
        int[] ids = new int[Math.min(limit, missing.cardinality())]; int count = 0;
        for (int bit = missing.nextSetBit(0); bit >= 0 && count < ids.length; bit = missing.nextSetBit(bit + 1))
            ids[count++] = currentBlock * 1024 + bit;
        reading = true;
        async(store.records(snapshot, ids), records -> {
            reading = false; check(records.size() == ids.length);
            for (int i = 0; i < ids.length; i++) check(records.get(i).userId == ids[i]);
            pendingIds = ids; pending = DataCodec.encode(token, nextBatch, records, master); retries = 0; transmit();
        });
    }
    private void transmit() {
        int batch = nextBatch;
        sendBody(Protocol.DATA, pending, () -> {
            if (pending == null || batch != nextBatch) return;
            if (ackTimer != null) ackTimer.cancel(false);
            ackTimer = later(ackMs, () -> {
                if (pending == null || batch != nextBatch) return;
                if (retries++ < 2) transmit(); else fail("PARTIAL: ACKタイムアウト（再送2回）");
            });
        });
    }
    private void receiveData(byte[] body) {
        check(transfers && !peerEnded);
        DataCodec.Batch batch = DataCodec.decode(body, master); check(batch.snapshotToken == remoteToken);
        boolean repeat = batch.batchId == receivedBatch && lastReceived != null;
        if (repeat) check(Arrays.equals(body, lastReceived));
        else {
            check(!myTurn && !committing && batch.batchId == receivedBatch + 1);
            for (WireRecord record : batch.records) {
                check(!receivedIds.get(record.userId) && peerCounts[record.userId >>> 10] > 0);
                if (mode == Protocol.FLAT) check(peerIds.get(record.userId) && !localIds.get(record.userId));
                receivedIds.set(record.userId);
                check(++receivedCounts[record.userId >>> 10] <= peerCounts[record.userId >>> 10]);
            }
            uniqueReceivedCount += batch.records.size(); check(uniqueReceivedCount <= peerCount);
            receivedBatch = batch.batchId; lastReceived = body.clone();
        }
        if (committing) return;
        committing = true; receivedRecords += batch.records.size();
        int block = batch.records.get(0).userId >>> 10;
        async(store.bitmap(snapshot, block), bitmap -> {
            BitSet original = HierarchyCodec.readBitmap(bitmap, block, summary.counts[block]);
            for (WireRecord record : batch.records) check(!original.get(record.userId & 1023));
            commitData(batch);
        });
    }
    private void commitData(DataCodec.Batch batch) {
        async(store.commit(master.version, batch.records, active::get), outcomes -> {
            check(outcomes.length == batch.records.size());
            ByteBuffer ack = ByteBuffer.allocate(6 + outcomes.length).putInt(batch.batchId).putShort((short) outcomes.length);
            int inserted = 0, known = 0, rejected = 0;
            for (int outcome : outcomes) {
                check(outcome >= 0 && outcome <= 3); ack.put((byte) outcome);
                if (outcome == 0) inserted++; else if (outcome == 3) rejected++; else known++;
            }
            newReceived += inserted; committing = false;
            link.status("DBコミット完了: 新規=" + inserted + " / 既知=" + known + " / 拒否=" + rejected);
            if (pauseAck) { link.status("保存完了・ACK待機中（テスト用）"); return; }
            final boolean refused = rejected > 0;
            send(Protocol.ACK, ack.array(), () -> { if (refused) fail("PARTIAL: 保存容量不足"); });
        });
    }
    private void receiveAck(ByteBuffer b) {
        check(b.remaining() >= 7); int batch = b.getInt(), count = b.getShort() & 65535;
        check(count >= 1 && count <= 256 && b.remaining() == count);
        int[] codes = new int[count]; for (int i = 0; i < count; i++) { codes[i] = b.get() & 255; check(codes[i] <= 3); }
        if (batch == lastAck && lastAck != 0) return;
        check(myTurn && pending != null && batch == nextBatch && count == pendingIds.length);
        if (ackTimer != null) ackTimer.cancel(false);
        for (int i = 0; i < count; i++) {
            if (codes[i] == 3) { fail("PARTIAL: 相手の保存容量不足"); return; }
            if (codes[i] == 0) peerNew++;
            missing.clear(pendingIds[i] & 1023); // ACKed IDs never re-enter this encounter's candidates.
        }
        pending = null; pendingIds = null; lastAck = batch; nextBatch++;
        if (peerEnded) drive();
        else { myTurn = false; send(Protocol.TURN_END, ByteBuffer.allocate(5).putInt(token).put((byte) 2).array(), () -> {}); }
    }
    private void endDirection() {
        localEnded = true; myTurn = false;
        send(Protocol.TURN_END, ByteBuffer.allocate(5).putInt(token).put((byte) 0).array(), this::tryDone);
    }
    private void receiveTurn(ByteBuffer b) {
        check(transfers && !myTurn && !peerEnded && !committing && b.remaining() == 5 && b.getInt() == remoteToken);
        int result = b.get() & 255; check(result <= 2);
        if (result == 1) { fail("PARTIAL: 相手の時間／予算終了"); return; }
        if (result == 0) peerEnded = true; else check(!localEnded);
        myTurn = !localEnded; drive(); tryDone();
    }
    private int result() { return nextBatch == 1 && receivedBatch == 0 ? 0 : 2; }
    private void tryDone() {
        if (localEnded && peerEnded) verifyIncoming();
        if (client && localEnded && peerEnded && !doneSent) {
            doneSent = true; send(Protocol.DONE, new byte[]{(byte) result()}, () -> {});
        }
    }
    private void verifyIncoming() {
        check(transfers && localEnded && peerEnded && !committing);
        if (incomingVerified) return;
        if (!fastEqual) for (int block = 0; block < HierarchyCodec.BLOCKS; block++) {
            check(expectedCounts[block] >= 0 && receivedCounts[block] == expectedCounts[block]);
            if (exactBlocks.get(block)) check(receivedIds.get(block * 1024, (block + 1) * 1024)
                    .equals(exactIncoming.get(block * 1024, (block + 1) * 1024)));
        }
        incomingVerified = true;
    }
    private void send(int type, byte[] content, Runnable done) { sendBody(type, FrameCodec.withCrc(content), done); }
    private void sendBody(int type, byte[] body, Runnable done) {
        if (!active.get()) return;
        check(body.length <= maxBody && (snapshot == null || snapshot.endsAt > clock.getAsLong()));
        long bytes = HierarchyCodec.attBytes(body.length, link.mtu());
        long sendLimit = peerEnded ? byteBudget - rxBytes : byteBudget / 2;
        if (txBytes + bytes > sendLimit || txBytes + rxBytes + bytes > byteBudget) { fail("PARTIAL: 共有バイト予算終了"); return; }
        txBytes += bytes;
        if (type == Protocol.DATA) sentRecords += pendingIds.length;
        link.send(type, body, () -> {
            if (!active.get()) return;
            try { done.run(); } catch (RuntimeException error) { fail("DELTA_SEND_FAILED"); }
        });
    }
    private <T> void async(CompletableFuture<T> future, Consumer<T> action) {
        future.whenComplete((value, error) -> dispatch(() -> {
            if (!active.get()) return;
            if (error != null) { fail("DB_READ_OR_WRITE_FAILED / SESSION_EXPIRED（成功ACKなし）"); return; }
            try { check(snapshot == null || snapshot.endsAt > clock.getAsLong()); action.accept(value); }
            catch (RuntimeException invalid) { fail("INVALID_DELTA_STATE"); }
        }));
    }
    private void dispatch(Runnable action) {
        try { executor.execute(action); } catch (RejectedExecutionException ignored) { stop(); }
    }
    private ScheduledFuture<?> later(long delay, Runnable action) {
        timers.removeIf(timer -> timer.isDone() || timer.isCancelled());
        ScheduledFuture<?> timer = executor.schedule(() -> { if (active.get()) action.run(); }, Math.max(0, delay), TimeUnit.MILLISECONDS);
        timers.add(timer); return timer;
    }
    public boolean verifiedEqual() { return verified && result() == 0; }
    public boolean verifiedComplete() { return verified; }
    public long newReceived() { return newReceived; }
    public long comparedRevision() { return snapshot == null ? -1 : snapshot.revision; }
    public byte[] comparisonDigest() { return digest == null ? null : digest.clone(); }
    public long sentRecords() { return sentRecords; }
    public long txBytes() { return txBytes; }
    public long rxBytes() { return rxBytes; }
    private String statistics() {
        String effective = sentRecords == 0 ? "N/A" : String.format(Locale.ROOT, "%.3f", (double) peerNew / sentRecords);
        long issued = link.issuedTxBytes() < 0 ? txBytes : link.issuedTxBytes();
        long received = link.receivedRxBytes() < 0 ? rxBytes : link.receivedRxBytes();
        String efficiency = sentRecords == 0 || issued == 0 ? "N/A" : String.format(Locale.ROOT, "%.3f", peerNew * 4.0 / issued);
        return " / DATA送信=" + sentRecords + "件 / 新規受信=" + newReceived + "件 / ATT換算TX=" + issued
                + "B RX=" + received + "B / 有効レコード率=" + effective + " / ATT効率=" + efficiency;
    }
    private void finish() {
        verified = true;
        String text = (mode == Protocol.HIERARCHICAL ? "階層同期完了" : "差分同期完了")
                + (result() == 0 ? " / EQUAL（不足なし）" : " / EXCHANGED（不足分を保存）") + statistics();
        stop(); link.close(text);
    }
    private void fail(String reason) { if (active.get()) { String text = reason + statistics(); stop(); link.close(text); } }
    @Override public void stop() {
        active.set(false); for (ScheduledFuture<?> timer : timers) timer.cancel(false); timers.clear();
        earlyHello = null; pending = null; lastReceived = null;
    }
}
