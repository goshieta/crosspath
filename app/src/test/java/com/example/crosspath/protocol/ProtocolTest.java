package com.example.crosspath.protocol;

import com.example.crosspath.data.*;
import org.junit.Test;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

public class ProtocolTest {
    private final MunicipalityMaster master = KyushuMunicipalities.load();
    @Test public void crcKnownVector() {
        byte[] body = FrameCodec.withCrc("123456789".getBytes(StandardCharsets.US_ASCII));
        assertEquals(0xCBF43926, ByteBuffer.wrap(body, 9, 4).getInt());
    }
    @Test public void roundTripAllMtuSizesAndMaximumBody() {
        byte[] raw = new byte[4092]; new Random(42).nextBytes(raw);
        byte[] body = FrameCodec.withCrc(raw);
        for (int mtu : new int[]{23, 247, 517}) {
            MessageAssembler assembler = new MessageAssembler();
            List<byte[]> frames = FrameCodec.fragment(5, 65535, body, mtu);
            MessageAssembler.Message message = null;
            for (int i = 0; i < frames.size(); i++) {
                assertTrue(frames.get(i).length <= Math.min(mtu - 3, 512));
                message = assembler.accept(frames.get(i), mtu);
                if (i < frames.size() - 1) assertNull(message);
            }
            assertNotNull(message); assertEquals(65535, message.id); assertArrayEquals(body, message.body);
        }
    }
    @Test public void oneRecordIsFourBytesWithNoLocalMetadata() {
        byte[] data = DataCodec.encode(1, 2, Collections.singletonList(new WireRecord(0x012345, 0x67)), master);
        assertEquals(23, data.length);
        assertArrayEquals(new byte[]{1, 0x23, 0x45, 0x67}, Arrays.copyOfRange(data, 15, 19));
        assertEquals(0x012345, DataCodec.decode(data, master).records.get(0).userId);
    }
    @Test public void maximumDataBatchIs1043Bytes() {
        List<WireRecord> records = new ArrayList<>();
        for (int id = 1024; id < 1280; id++) records.add(new WireRecord(id, 1));
        byte[] data = DataCodec.encode(-1, -2, records, master);
        assertEquals(1043, data.length);
        DataCodec.Batch decoded = DataCodec.decode(data, master);
        assertEquals(-1, decoded.snapshotToken); assertEquals(-2, decoded.batchId);
        assertEquals(256, decoded.records.size()); assertEquals(1279, decoded.records.get(255).userId);
    }
    @Test public void boundaryIdsRoundTrip() {
        for (int id : new int[]{1, 1023, 1024, 0xFFFFFF}) {
            assertEquals(id, DataCodec.decode(DataCodec.encode(0, 0,
                    Collections.singletonList(new WireRecord(id, 233)), master), master).records.get(0).userId);
        }
    }
    @Test public void encoderRejectsCrossBlockAndDuplicates() {
        assertThrows(IllegalArgumentException.class, () -> DataCodec.encode(0, 0,
                Arrays.asList(new WireRecord(1023, 1), new WireRecord(1024, 1)), master));
        assertThrows(IllegalArgumentException.class, () -> DataCodec.encode(0, 0,
                Arrays.asList(new WireRecord(1, 1), new WireRecord(1, 1)), master));
        assertThrows(IllegalArgumentException.class, () -> DataCodec.encode(0, 0,
                Collections.singletonList(new WireRecord(1, 255)), master));
    }
    @Test public void decoderRejectsFirstIdBlockCountZeroAndUnknownMunicipality() {
        byte[] good = FrameCodec.verifiedContent(DataCodec.encode(1, 1,
                Collections.singletonList(new WireRecord(1024, 1)), master));
        for (int offset : new int[]{8, 10, 13, 18}) {
            byte[] bad = good.clone(); bad[offset] = (byte) 255;
            assertThrows(IllegalArgumentException.class, () -> DataCodec.decode(FrameCodec.withCrc(bad), master));
        }
        byte[] zero = good.clone(); zero[15] = zero[16] = zero[17] = 0;
        assertThrows(IllegalArgumentException.class, () -> DataCodec.decode(FrameCodec.withCrc(zero), master));
    }
    @Test public void rejectsCrcCorruption() {
        byte[] body = FrameCodec.withCrc(new byte[]{1, 2, 3}); body[1] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> FrameCodec.verifiedContent(body));
    }
    @Test public void rejectsOversizeUnknownTypeAndReservedFlags() {
        assertThrows(IllegalArgumentException.class, () -> FrameCodec.withCrc(new byte[4093]));
        for (int offset : new int[]{0, 1, 8}) {
            byte[] f = FrameCodec.fragment(1, 0, FrameCodec.withCrc(new byte[20]), 23).get(0).clone();
            f[offset] = (byte) 255;
            assertThrows(IllegalArgumentException.class, () -> new MessageAssembler().accept(f, 23));
        }
    }
    @Test public void rejectsMissingOutOfOrderDuplicatedOrMixedFragmentsAndResets() {
        List<byte[]> frames = FrameCodec.fragment(5, 1, FrameCodec.withCrc(new byte[30]), 23);
        MessageAssembler a = new MessageAssembler();
        assertThrows(IllegalArgumentException.class, () -> a.accept(frames.get(1), 23));
        assertNull(a.accept(frames.get(0), 23));
        assertThrows(IllegalArgumentException.class, () -> a.accept(frames.get(2), 23));
        assertNull(a.accept(frames.get(0), 23));
        assertThrows(IllegalArgumentException.class, () -> a.accept(frames.get(0), 23));
        assertNull(a.accept(frames.get(0), 23));
        byte[] mixed = frames.get(1).clone(); mixed[3] = 2;
        assertThrows(IllegalArgumentException.class, () -> a.accept(mixed, 23));
        for (int i = 0; i < frames.size(); i++) {
            MessageAssembler.Message m = a.accept(frames.get(i), 23);
            if (i == frames.size() - 1) assertNotNull(m);
        }
    }
    @Test public void checksCrcOnlyAfterFinalFragment() {
        List<byte[]> frames = FrameCodec.fragment(5, 1, FrameCodec.withCrc(new byte[10]), 23);
        frames.get(0)[10] ^= 1;
        MessageAssembler a = new MessageAssembler();
        assertNull(a.accept(frames.get(0), 23));
        assertThrows(IllegalArgumentException.class, () -> a.accept(frames.get(1), 23));
    }
    @Test public void rejectsInconsistentFragmentCountAndTotalLength() {
        byte[] f = FrameCodec.fragment(5, 1, FrameCodec.withCrc(new byte[10]), 23).get(0);
        f[7] = 3;
        assertThrows(IllegalArgumentException.class, () -> new MessageAssembler().accept(f, 23));
    }
}
