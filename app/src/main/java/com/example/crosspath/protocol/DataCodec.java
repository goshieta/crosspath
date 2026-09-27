package com.example.crosspath.protocol;

import com.example.crosspath.data.MunicipalityMaster;
import com.example.crosspath.data.WireRecord;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class DataCodec {
    public static final class Batch {
        /** 32-bit wire tokens are opaque bit patterns, not signed counters. */
        public final int snapshotToken, batchId;
        public final List<WireRecord> records;
        Batch(int snapshotToken, int batchId, List<WireRecord> records) {
            this.snapshotToken = snapshotToken; this.batchId = batchId;
            this.records = Collections.unmodifiableList(records);
        }
    }
    private DataCodec() { }
    public static byte[] encode(int snapshot, int batchId, List<WireRecord> records,
            MunicipalityMaster master) {
        if (records.isEmpty() || records.size() > 256) throw new IllegalArgumentException("Record count");
        ByteBuffer b = ByteBuffer.allocate(15 + 4 * records.size());
        int first = records.get(0).userId;
        b.putInt(snapshot).putInt(batchId).putShort((short) (first >>> 10));
        putId(b, first); b.putShort((short) records.size());
        int previous = 0;
        for (WireRecord r : records) {
            r.requireMunicipalityName(master);
            if (r.userId <= previous || (r.userId >>> 10) != (first >>> 10)) {
                throw new IllegalArgumentException("Records must ascend within one block");
            }
            putId(b, r.userId); b.put((byte) r.municipalityCode); previous = r.userId;
        }
        return FrameCodec.withCrc(b.array());
    }
    public static Batch decode(byte[] body, MunicipalityMaster master) {
        ByteBuffer b = ByteBuffer.wrap(FrameCodec.verifiedContent(body));
        if (b.remaining() < 19) throw new IllegalArgumentException("Short DATA");
        int snapshot = b.getInt(), batch = b.getInt(), block = b.getShort() & 65535;
        int first = getId(b), count = b.getShort() & 65535;
        if (block >= 16384 || count < 1 || count > 256 || b.remaining() != count * 4) {
            throw new IllegalArgumentException("DATA length or block");
        }
        List<WireRecord> records = new ArrayList<>(count);
        int previous = 0;
        for (int i = 0; i < count; i++) {
            WireRecord r = new WireRecord(getId(b), b.get() & 255);
            r.requireMunicipalityName(master);
            if (r.userId <= previous || (r.userId >>> 10) != block || (i == 0 && r.userId != first)) {
                throw new IllegalArgumentException("DATA record order, first ID or block");
            }
            records.add(r); previous = r.userId;
        }
        return new Batch(snapshot, batch, records);
    }
    private static void putId(ByteBuffer b, int id) { b.put((byte) (id >>> 16)).put((byte) (id >>> 8)).put((byte) id); }
    private static int getId(ByteBuffer b) { return ((b.get() & 255) << 16) | ((b.get() & 255) << 8) | (b.get() & 255); }
}
