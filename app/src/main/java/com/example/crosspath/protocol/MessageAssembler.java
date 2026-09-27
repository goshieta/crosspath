package com.example.crosspath.protocol;

import java.nio.ByteBuffer;

/** One bounded, strictly ordered message per connection/direction. Reset on disconnect. */
public final class MessageAssembler {
    public static final class Message {
        public final int type, id;
        public final byte[] body;
        public final long attBytes;
        Message(int type, int id, byte[] body, long attBytes) { this.type = type; this.id = id; this.body = body; this.attBytes = attBytes; }
    }
    private byte[] body;
    private int type, id, count, next, written, chunkSize;

    public Message accept(byte[] frame, int mtu) {
        try {
            int max = Protocol.payloadSize(mtu);
            if (frame.length <= Protocol.HEADER || frame.length > Protocol.HEADER + max) fail();
            ByteBuffer b = ByteBuffer.wrap(frame);
            int t = b.get() & 255, flags = b.get() & 255, mid = b.getShort() & 65535;
            int index = b.getShort() & 65535, n = b.getShort() & 65535, total = b.getShort() & 65535;
            Protocol.requireType(t);
            int length = b.remaining();
            if (flags != 0 || total < 4 || total > Protocol.MAX_BODY || n < 1 || n > total || index >= n) fail();
            if (body == null) {
                if (index != 0 || n != (total + length - 1) / length) fail();
                body = new byte[total]; type = t; id = mid; count = n; chunkSize = length;
            }
            if (t != type || mid != id || n != count || total != body.length || index != next
                    || length != Math.min(chunkSize, total - written)) fail();
            b.get(body, written, length);
            written += length;
            next++;
            if (next != count) return null;
            if (written != body.length) fail();
            FrameCodec.verifiedContent(body);
            Message result = new Message(type, id, body, body.length + 14L * count);
            reset();
            return result;
        } catch (RuntimeException error) { reset(); throw error; }
    }
    public void reset() { body = null; next = written = 0; }
    private static void fail() { throw new IllegalArgumentException("Invalid fragment sequence"); }
}
