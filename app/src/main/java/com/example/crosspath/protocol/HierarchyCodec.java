package com.example.crosspath.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.BitSet;

/** Chapter 10 content codecs (CRC is added by the caller). */
public final class HierarchyCodec {
    public static final int BLOCKS = 16384, PAGE_BLOCKS = 256;
    private HierarchyCodec() { }
    public static int capacity(int block) { check(block >= 0 && block < BLOCKS); return block == 0 ? 1023 : 1024; }
    public static void check(boolean ok) { if (!ok) throw new IllegalArgumentException("Invalid delta message"); }
    public static byte[] counts(int token, int start, int[] counts) {
        check(counts.length >= 1 && counts.length <= PAGE_BLOCKS && start >= 0 && start + counts.length <= BLOCKS);
        int partial = 0;
        for (int i = 0; i < counts.length; i++) {
            check(counts[i] >= 0 && counts[i] <= capacity(start + i));
            if (counts[i] > 0 && counts[i] < capacity(start + i)) partial++;
        }
        int flags = (counts.length + 3) / 4;
        boolean compressed = flags + 2 * partial < 2 * counts.length;
        ByteBuffer b = ByteBuffer.allocate(9 + (compressed ? flags + 2 * partial : 2 * counts.length));
        b.putInt(token).put((byte) (compressed ? 1 : 0)).putShort((short) start).putShort((short) counts.length);
        if (!compressed) { for (int count : counts) b.putShort((short) count); }
        else {
            byte[] states = new byte[flags];
            for (int i = 0; i < counts.length; i++) {
                int state = counts[i] == 0 ? 0 : counts[i] == capacity(start + i) ? 1 : 2;
                states[i / 4] |= (byte) (state << (6 - 2 * (i % 4)));
            }
            b.put(states);
            for (int i = 0; i < counts.length; i++)
                if (counts[i] > 0 && counts[i] < capacity(start + i)) b.putShort((short) counts[i]);
        }
        return b.array();
    }
    public static final class Counts {
        public final int token, start;
        public final int[] values;
        Counts(int token, int start, int[] values) { this.token = token; this.start = start; this.values = values; }
    }
    public static Counts readCounts(ByteBuffer b) {
        check(b.remaining() >= 9);
        int token = b.getInt(), mode = b.get() & 255, start = b.getShort() & 65535, n = b.getShort() & 65535;
        check(n >= 1 && n <= PAGE_BLOCKS && start + n <= BLOCKS && mode <= 1);
        int[] values = new int[n];
        if (mode == 0) {
            check(b.remaining() == n * 2);
            for (int i = 0; i < n; i++) { values[i] = b.getShort() & 65535; check(values[i] <= capacity(start + i)); }
        } else {
            byte[] flags = new byte[(n + 3) / 4]; check(b.remaining() >= flags.length); b.get(flags);
            if (n % 4 != 0) check((flags[flags.length - 1] & ((1 << (2 * (4 - n % 4))) - 1)) == 0);
            for (int i = 0; i < n; i++) {
                int state = (flags[i / 4] >>> (6 - 2 * (i % 4))) & 3; check(state != 3);
                if (state == 1) values[i] = capacity(start + i);
                else if (state == 2) {
                    check(b.remaining() >= 2); values[i] = b.getShort() & 65535;
                    check(values[i] > 0 && values[i] < capacity(start + i));
                }
            }
            check(!b.hasRemaining());
        }
        return new Counts(token, start, values);
    }
    public static byte[] bitmap(BitSet ids, int block) {
        capacity(block); byte[] bytes = new byte[128];
        for (int bit = 0; bit < 1024; bit++) if (ids.get(block * 1024 + bit)) bytes[bit / 8] |= (byte) (128 >>> (bit % 8));
        check(block != 0 || (bytes[0] & 128) == 0); return bytes;
    }
    public static BitSet readBitmap(byte[] bytes, int block, int count) {
        check(bytes.length == 128 && count >= 0 && count <= capacity(block));
        check(block != 0 || (bytes[0] & 128) == 0);
        BitSet bits = new BitSet(1024);
        for (int i = 0; i < 1024; i++) if ((bytes[i / 8] & (128 >>> (i % 8))) != 0) bits.set(i);
        check(bits.cardinality() == count); return bits;
    }
    public static void putId(ByteBuffer b, int id) {
        check(id > 0 && id <= 0xFFFFFF); b.put((byte) (id >>> 16)).put((byte) (id >>> 8)).put((byte) id);
    }
    public static int getId(ByteBuffer b) {
        check(b.remaining() >= 3); int id = (b.get() & 255) << 16 | (b.get() & 255) << 8 | b.get() & 255;
        check(id != 0); return id;
    }
    public static MessageDigest digest(long count) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("crosspath/id-set/v1\0".getBytes(StandardCharsets.US_ASCII));
            digest.update(ByteBuffer.allocate(9).put((byte) Protocol.MAJOR).putInt(Protocol.KYUSHU_TEST_MASTER)
                    .putInt((int) count).array());
            return digest;
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    public static void digestId(MessageDigest digest, int id) {
        ByteBuffer bytes = ByteBuffer.allocate(3); putId(bytes, id); digest.update(bytes.array());
    }
    /** ATT value + framing + request/indication opcode+handle and its 1B acknowledgement. */
    public static long attBytes(int bodyLength, int mtu) {
        int payload = Protocol.payloadSize(mtu);
        return bodyLength + 14L * ((bodyLength + payload - 1) / payload);
    }
}
