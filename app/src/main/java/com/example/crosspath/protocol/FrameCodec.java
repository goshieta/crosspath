package com.example.crosspath.protocol;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

/** Pure Java wire codec. Bodies include exactly one trailing CRC, including DATA. */
public final class FrameCodec {
    private FrameCodec() { }
    public static byte[] withCrc(byte[] content) {
        if (content.length > Protocol.MAX_BODY - 4) throw new IllegalArgumentException("Body too large");
        CRC32 crc = new CRC32();
        crc.update(content);
        return ByteBuffer.allocate(content.length + 4).put(content).putInt((int) crc.getValue()).array();
    }
    public static byte[] verifiedContent(byte[] body) {
        if (body.length < 4 || body.length > Protocol.MAX_BODY) throw new IllegalArgumentException("Body length");
        CRC32 crc = new CRC32();
        crc.update(body, 0, body.length - 4);
        if ((int) crc.getValue() != ByteBuffer.wrap(body, body.length - 4, 4).getInt()) {
            throw new IllegalArgumentException("CRC mismatch");
        }
        return Arrays.copyOf(body, body.length - 4);
    }
    public static List<byte[]> fragment(int type, int id, byte[] body, int mtu) {
        Protocol.requireType(type);
        if (id < 0 || id > 65535) throw new IllegalArgumentException("Message ID");
        verifiedContent(body);
        int size = Protocol.payloadSize(mtu);
        int count = (body.length + size - 1) / size;
        List<byte[]> frames = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int start = i * size, length = Math.min(size, body.length - start);
            frames.add(ByteBuffer.allocate(Protocol.HEADER + length).put((byte) type).put((byte) 0)
                    .putShort((short) id).putShort((short) i).putShort((short) count)
                    .putShort((short) body.length).put(body, start, length).array());
        }
        return frames;
    }
}
