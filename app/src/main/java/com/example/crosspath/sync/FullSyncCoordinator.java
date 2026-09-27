package com.example.crosspath.sync;

import com.example.crosspath.data.*;
import com.example.crosspath.protocol.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;

/**
 * FULL exchange, serialized on protocolExecutor. One unacknowledged DATA and one DB
 * transaction per direction. Create a new instance for every BLE connection.
 */
public final class FullSyncCoordinator implements SyncExchange {
    private boolean debugPauseAck;

    /** Configure before ready(); intentionally no release in the same connection. */
    public void pauseAckAfterCommitForDebug(boolean enabled) {
        if (started) throw new IllegalStateException("Configure before connection start");
        debugPauseAck = com.example.crosspath.BuildConfig.DEBUG && enabled;
    }
    public interface Link {
        void send(int type, byte[] body, Runnable delivered);
        boolean matchesToken(long token);
        void status(String text);
        void close(String reason);
    }
    public static final int CAP_DURABLE_FULL = 2; // Draft extension: excludes old memory-ACK demo.
    private enum State { HELLO, WAIT_BEGIN, RECEIVING, SENDING, WAIT_REVERSE, WAIT_DONE, CLOSING }
    private final Link link;
    private final SyncStore store;
    private final MunicipalityMaster master;
    private final ScheduledExecutorService executor;
    private final LongSupplier clock;
    private final IntConsumer checkpoint;
    private final FullSyncCursor cursor;
    private final int snapshotToken;
    private final long ackMs, turnMs, totalMs;
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final List<ScheduledFuture<?>> timers = new ArrayList<>();
    private State state = State.HELLO;
    private SyncStore.Snapshot snapshot;
    private MessageAssembler.Message earlyHello;
    private MessageAssembler.Message deferred;
    private boolean client, started, helloReceived, localHelloSent, committing, partial, remoteEnded;
    private long localToken, peerCount, receivedCount;
    private int maxBody = Protocol.MAX_BODY, remoteSnapshot, nextBatch = 1, lastAck, receivedBatch;
    private int pendingCount, pendingLastId, retries;
    private byte[] pending, lastReceivedBody;
    private ScheduledFuture<?> ackTimer, turnTimer;
    private long txBytes, rxBytes;

    public FullSyncCoordinator(Link link, SyncStore store, MunicipalityMaster master,
            ScheduledExecutorService executor, int startBlock, int snapshotToken, IntConsumer checkpoint) {
        this(link, store, master, executor, startBlock, snapshotToken, checkpoint,
                System::currentTimeMillis, 10_000, 15_000, 45_000);
    }
    // Configurable clocks/timeouts for deterministic fault-injection tests.
    public FullSyncCoordinator(Link link, SyncStore store, MunicipalityMaster master,
            ScheduledExecutorService executor, int startBlock, int snapshotToken, IntConsumer checkpoint,
            LongSupplier clock, long ackMs, long turnMs, long totalMs) {
        this.link = link; this.store = store; this.master = master; this.executor = executor;
        this.cursor = new FullSyncCursor(startBlock); this.snapshotToken = snapshotToken;
        this.checkpoint = checkpoint; this.clock = clock;
        this.ackMs = ackMs; this.turnMs = turnMs; this.totalMs = totalMs;
        master.requireVersion(KyushuMunicipalities.VERSION);
    }
    public void ready(boolean client, long token) {
        if (started || !active.get()) return;
        started = true; this.client = client; localToken = token;
        later(totalMs, () -> fail("PARTIAL: 接続時間枠終了"));
        async(store.snapshot(), s -> {
            snapshot = s;
            require(s.count >= 0 && s.count <= 13_000_000 && s.endsAt > clock.getAsLong());
            later(s.endsAt - clock.getAsLong(), () -> fail("SESSION_EXPIRED"));
            localHelloSent = true;
            send(Protocol.HELLO, ByteBuffer.allocate(24).put((byte) Protocol.MAJOR).put((byte) Protocol.MINOR)
                    .putInt(Protocol.CAP_FULL | CAP_DURABLE_FULL).putLong(localToken)
                    .putInt(Protocol.KYUSHU_TEST_MASTER).putInt((int) s.count)
                    .putShort((short) Protocol.MAX_BODY).array(), () -> { });
            if (earlyHello != null) { MessageAssembler.Message m = earlyHello; earlyHello = null; handle(m); }
        });
    }
    public void receive(MessageAssembler.Message message) {
        if (!active.get()) return;
        try {
            rxBytes += message.body.length;
            if (snapshot == null) {
                require(started && message.type == Protocol.HELLO && earlyHello == null);
                FrameCodec.verifiedContent(message.body);
                earlyHello = message; return;
            }
            require(clock.getAsLong() < snapshot.endsAt);
            handle(message);
        } catch (RuntimeException error) { fail("INVALID_FRAME / INCOMPATIBLE_VERSION"); }
    }
    private void handle(MessageAssembler.Message m) {
        ByteBuffer b = ByteBuffer.wrap(FrameCodec.verifiedContent(m.body));
        require(m.body.length <= Protocol.MAX_BODY);
        if (m.type == Protocol.HELLO) {
            require(state == State.HELLO && !helloReceived && localHelloSent && b.remaining() == 24);
            int major = b.get() & 255, minor = b.get() & 255, caps = b.getInt();
            long token = b.getLong(); int version = b.getInt();
            peerCount = Integer.toUnsignedLong(b.getInt()); maxBody = b.getShort() & 65535;
            require(major == Protocol.MAJOR && minor == Protocol.MINOR
                    && (caps & (Protocol.CAP_FULL | CAP_DURABLE_FULL)) == (Protocol.CAP_FULL | CAP_DURABLE_FULL)
                    && link.matchesToken(token) && version == Protocol.KYUSHU_TEST_MASTER
                    && peerCount <= 13_000_000 && maxBody >= 28 && maxBody <= Protocol.MAX_BODY);
            helloReceived = true; link.status("HELLO確認済み / Room保存ACK対応");
            if (client) startTurn(); else state = State.WAIT_BEGIN;
            return;
        }
        require(helloReceived);
        if (committing) {
            if (m.type == Protocol.DATA && Arrays.equals(lastReceivedBody, m.body)) return;
            require(deferred == null && (m.type == Protocol.DATA || m.type == Protocol.TURN_END));
            deferred = m; return;
        }
        switch (m.type) {
            case Protocol.BEGIN:
                require((!client && state == State.WAIT_BEGIN || client && state == State.WAIT_REVERSE)
                        && b.remaining() == 5 && (b.get() & 255) == Protocol.FULL);
                remoteSnapshot = b.getInt(); state = State.RECEIVING; break;
            case Protocol.DATA: receiveData(m.body); break;
            case Protocol.ACK: receiveAck(b); break;
            case Protocol.TURN_END:
                require(state == State.RECEIVING && !committing && b.remaining() == 5
                        && b.getInt() == remoteSnapshot);
                int result = b.get() & 255; require(result <= 1);
                if (result == 0) require(receivedCount == peerCount);
                partial |= result == 1; remoteEnded = true;
                if (!client) startTurn();
                else { state = State.WAIT_DONE; send(Protocol.DONE, new byte[]{(byte) doneResult()}, () -> { }); }
                break;
            case Protocol.DONE:
                require(state == State.WAIT_DONE && remoteEnded && b.remaining() == 1);
                int done = b.get() & 255; require(done == doneResult());
                if (client) finish();
                else {
                    state = State.CLOSING;
                    send(Protocol.DONE, new byte[]{(byte) done}, this::finish);
                }
                break;
            case 9:
                require(b.remaining() == 4); fail("相手がERRORで同期を終了"); break;
            default: throw new IllegalArgumentException("Unsupported FULL message");
        }
    }
    private int doneResult() { return partial ? 1 : 2; } // PARTIAL / EXCHANGED; never claims EQUAL.
    private void receiveData(byte[] body) {
        require(state == State.RECEIVING);
        DataCodec.Batch batch = DataCodec.decode(body, master);
        require(batch.snapshotToken == remoteSnapshot);
        boolean repeat = batch.batchId == receivedBatch && lastReceivedBody != null;
        if (repeat) require(Arrays.equals(lastReceivedBody, body));
        else require(batch.batchId == receivedBatch + 1 && !committing);
        if (committing) { require(repeat); return; } // No ACK before the pending commit.
        if (!repeat) {
            require(receivedCount + batch.records.size() <= peerCount);
            receivedCount += batch.records.size(); receivedBatch = batch.batchId;
            lastReceivedBody = body.clone();
        }
        committing = true;
        // Retry always re-enters storage; no cached successful ACK.
        async(store.commit(master.version, batch.records, active::get), outcomes -> {
            require(outcomes.length == batch.records.size());
            ByteBuffer ack = ByteBuffer.allocate(6 + outcomes.length).putInt(batch.batchId).putShort((short) outcomes.length);
            int inserted = 0, known = 0, rejected = 0;
            for (int code : outcomes) {
                require(code >= 0 && code <= 3); ack.put((byte) code);
                if (code == 0) inserted++; else if (code == 3) rejected++; else known++;
            }
            partial |= rejected != 0; committing = false;
            link.status("DBコミット完了: 新規=" + inserted + " / 既知=" + known + " / 拒否=" + rejected);
            if (debugPauseAck) {
                link.status("保存完了・ACK待機中（テスト用）。停止してからチェックをOFFにし、再接続してください。");
                return; // Do not block the executor; retries, disconnect and deadlines still run.
            }
            send(Protocol.ACK, ack.array(), () -> { });
            if (deferred != null) { MessageAssembler.Message next = deferred; deferred = null; handle(next); }
        });
    }
    private void startTurn() {
        state = State.SENDING;
        turnTimer = later(turnMs, () -> {
            if (state != State.SENDING) return;
            if (pending != null) fail("PARTIAL: ACK未確定のまま片方向時間枠終了");
            else endTurn(true);
        });
        send(Protocol.BEGIN, ByteBuffer.allocate(5).put((byte) Protocol.FULL).putInt(snapshotToken).array(), this::readPage);
    }
    private void readPage() {
        if (!active.get() || state != State.SENDING || pending != null) return;
        int limit = Math.min(256, (maxBody - 19) / 4);
        async(store.page(snapshot, cursor.after(), cursor.through(), limit), page -> {
            if (state != State.SENDING) return;
            if (page.isEmpty()) {
                if (cursor.wrap()) readPage(); else endTurn(false);
                return;
            }
            require(page.size() <= limit && page.get(0).userId > cursor.after());
            int block = page.get(0).userId >>> 10, count = 0;
            while (count < page.size() && (page.get(count).userId >>> 10) == block) count++;
            List<WireRecord> batch = page.subList(0, count);
            pendingCount = count; pendingLastId = batch.get(count - 1).userId;
            require(pendingLastId <= cursor.through());
            pending = DataCodec.encode(snapshotToken, nextBatch, batch, master);
            retries = 0; transmit();
        });
    }
    private void transmit() {
        final int batch = nextBatch;
        sendBody(Protocol.DATA, pending, () -> {
            if (pending == null || nextBatch != batch) return;
            if (ackTimer != null) ackTimer.cancel(false);
            ackTimer = later(ackMs, () -> {
                if (pending == null || nextBatch != batch) return;
                if (retries++ < 2) transmit(); else fail("PARTIAL: ACKタイムアウト（再送2回）");
            });
        });
    }
    private void receiveAck(ByteBuffer b) {
        require(b.remaining() >= 7);
        int id = b.getInt(), count = b.getShort() & 65535;
        require(count >= 1 && count <= 256 && b.remaining() == count);
        int[] results = new int[count];
        for (int i = 0; i < count; i++) { results[i] = b.get() & 255; require(results[i] <= 3); }
        if (id == lastAck && lastAck != 0) return; // Late ACK from a retransmission.
        require(state == State.SENDING && pending != null && id == nextBatch && count == pendingCount);
        boolean rejected = false;
        for (int code : results) rejected |= code == 3;
        if (ackTimer != null) ackTimer.cancel(false);
        lastAck = id; nextBatch++; pending = null;
        if (rejected) { endTurn(true); return; } // Keep checkpoint at the unaccepted block.
        cursor.acknowledged(pendingLastId); checkpoint.accept(cursor.after());
        readPage();
    }
    private void endTurn(boolean incomplete) {
        partial |= incomplete;
        if (turnTimer != null) turnTimer.cancel(false);
        state = client ? State.WAIT_REVERSE : State.WAIT_DONE;
        send(Protocol.TURN_END, ByteBuffer.allocate(5).putInt(snapshotToken).put((byte) (incomplete ? 1 : 0)).array(), () -> { });
    }
    private void send(int type, byte[] content, Runnable delivered) { sendBody(type, FrameCodec.withCrc(content), delivered); }
    private void sendBody(int type, byte[] body, Runnable delivered) {
        if (!active.get()) return;
        require(snapshot == null || clock.getAsLong() < snapshot.endsAt);
        require(body != null && body.length <= (helloReceived ? maxBody : Protocol.MAX_BODY));
        txBytes += body.length; // Logical bytes including retries; not ATT/header overhead.
        link.send(type, body, () -> {
            if (!active.get()) return;
            try { delivered.run(); } catch (RuntimeException e) { fail("同期送信エラー"); }
        });
    }
    private <T> void async(CompletableFuture<T> future, Consumer<T> success) {
        future.whenComplete((value, error) -> dispatch(() -> {
            if (!active.get()) return;
            if (error != null) { fail("DB_READ_OR_WRITE_FAILED / SESSION_EXPIRED（成功ACKなし）"); return; }
            try {
                require(snapshot == null || clock.getAsLong() < snapshot.endsAt);
                success.accept(value);
            } catch (RuntimeException e) { fail("同期データ／期間エラー"); }
        }));
    }
    private void dispatch(Runnable work) {
        try { executor.execute(work); } catch (RejectedExecutionException ignored) { stop(); }
    }
    private ScheduledFuture<?> later(long ms, Runnable work) {
        timers.removeIf(t -> t.isDone() || t.isCancelled());
        ScheduledFuture<?> timer = executor.schedule(() -> {
            if (!active.get()) return;
            try { work.run(); } catch (RuntimeException e) { fail("同期タイムアウト"); }
        }, Math.max(0, ms), TimeUnit.MILLISECONDS);
        timers.add(timer); return timer;
    }
    private void finish() {
        String result = partial ? "部分同期終了" : "双方向FULL交換完了";
        stop(); link.close(result + " / 論理TX=" + txBytes + "B RX=" + rxBytes + "B");
    }
    private void fail(String reason) { if (active.getAndSet(false)) { cancelTimers(); link.close(reason); } }
    public void stop() { active.set(false); cancelTimers(); earlyHello = null; pending = null; lastReceivedBody = null; }
    private void cancelTimers() { for (ScheduledFuture<?> timer : timers) timer.cancel(false); timers.clear(); }
    private static void require(boolean condition) { if (!condition) throw new IllegalArgumentException("Invalid FULL state/message"); }
}
