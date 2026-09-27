package com.example.crosspath.protocol;
import org.junit.Test;
import java.nio.ByteBuffer;
import java.util.*;
import static org.junit.Assert.*;

public class HierarchyCodecTest {
    @Test public void adaptiveCountsRoundTripEveryBlockAndCapacity() {
        Random random = new Random(3);
        for (int start = 0; start < 16384; start += 256) {
            int[] counts = new int[256];
            for (int i = 0; i < counts.length; i++) counts[i] = random.nextInt(HierarchyCodec.capacity(start + i) + 1);
            byte[] bytes = HierarchyCodec.counts(99, start, counts);
            HierarchyCodec.Counts decoded = HierarchyCodec.readCounts(ByteBuffer.wrap(bytes));
            assertEquals(start, decoded.start); assertEquals(99, decoded.token); assertArrayEquals(counts, decoded.values);
            assertTrue(bytes.length <= 521);
        }
        assertEquals(1, HierarchyCodec.counts(1, 0, new int[]{0, 1024, 3})[4]);
        assertEquals(0, HierarchyCodec.counts(1, 0, new int[]{1, 2, 3})[4]);
    }
    @Test public void reservedStatePaddingAndCapacityAreRejected() {
        byte[] reserved = HierarchyCodec.counts(1, 0, new int[]{0, 0, 0, 0}); reserved[9] = (byte) 0xC0;
        assertThrows(IllegalArgumentException.class, () -> HierarchyCodec.readCounts(ByteBuffer.wrap(reserved)));
        byte[] padding = HierarchyCodec.counts(1, 0, new int[]{0}); padding[9] = 1;
        assertThrows(IllegalArgumentException.class, () -> HierarchyCodec.readCounts(ByteBuffer.wrap(padding)));
        assertThrows(IllegalArgumentException.class, () -> HierarchyCodec.counts(1, 0, new int[]{1024}));
        assertThrows(IllegalArgumentException.class, () -> HierarchyCodec.counts(1, 16384, new int[]{0}));
    }
    @Test public void bitmapUsesMsbFirstAndChecksReservedBitAndPopcount() {
        BitSet bits = new BitSet(); bits.set(1); bits.set(1023); bits.set(1024); bits.set(0xFFFFFF);
        byte[] zero = HierarchyCodec.bitmap(bits, 0);
        assertEquals(64, zero[0]); assertEquals(1, zero[127]);
        assertEquals(2, HierarchyCodec.readBitmap(zero, 0, 2).cardinality());
        assertEquals(128, HierarchyCodec.bitmap(bits, 1)[0] & 255);
        assertEquals(1, HierarchyCodec.bitmap(bits, 16383)[127]);
        assertThrows(IllegalArgumentException.class, () -> HierarchyCodec.readBitmap(zero, 0, 1));
        zero[0] |= (byte) 128;
        assertThrows(IllegalArgumentException.class, () -> HierarchyCodec.readBitmap(zero, 0, 3));
    }
    @Test public void attAccountingMatchesEncodedFramesAtEveryMtu() {
        byte[] body = FrameCodec.withCrc(new byte[1039]);
        for (int mtu : new int[]{23, 247, 517}) {
            long actual = FrameCodec.fragment(Protocol.DATA, 1, body, mtu).stream().mapToLong(frame -> frame.length + 4).sum();
            assertEquals(actual, HierarchyCodec.attBytes(body.length, mtu));
        }
    }
    @Test public void hashIncludesCountAndOrderedIds() {
        java.security.MessageDigest a = HierarchyCodec.digest(2), b = HierarchyCodec.digest(2);
        HierarchyCodec.digestId(a, 1); HierarchyCodec.digestId(a, 2);
        HierarchyCodec.digestId(b, 3); HierarchyCodec.digestId(b, 4);
        assertFalse(Arrays.equals(a.digest(), b.digest()));
    }
}
