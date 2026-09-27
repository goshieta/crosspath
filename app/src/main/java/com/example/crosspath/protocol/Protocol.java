package com.example.crosspath.protocol;

import java.util.UUID;

/** Draft v0.8 interoperability profile; changes require agreement with the other implementation. */
public final class Protocol {
    public static final UUID SERVICE = UUID.fromString("9c2f0001-7c0b-4a1e-9b6d-0ce341a42160");
    public static final UUID RX = UUID.fromString("9c2f0002-7c0b-4a1e-9b6d-0ce341a42160");
    public static final UUID TX = UUID.fromString("9c2f0003-7c0b-4a1e-9b6d-0ce341a42160");
    public static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    public static final int MAJOR = 4, MINOR = 0, MAX_BODY = 4096, HEADER = 10;
    public static final int HELLO = 1, BEGIN = 2, DATA = 5, ACK = 6, TURN_END = 7, DONE = 8;
    public static final int FULL = 1, CAP_FULL = 1;
    // Explicit TEST profile mapping to kyushu-2026-09-26-v1; not a hash or official master ID.
    public static final int KYUSHU_TEST_MASTER = 0x20260926;
    public static final long OP_TIMEOUT_MS = 10_000, CONNECTION_TIMEOUT_MS = 45_000;
    private Protocol() { }
    public static int payloadSize(int mtu) {
        if (mtu < 23 || mtu > 517) throw new IllegalArgumentException("Invalid ATT MTU");
        return Math.min(mtu - 3, 512) - HEADER;
    }
    public static void requireType(int type) {
        if (type < 1 || type > 13) throw new IllegalArgumentException("Unknown message type");
    }
}
