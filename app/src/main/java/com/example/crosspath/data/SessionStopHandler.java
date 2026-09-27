package com.example.crosspath.data;

/**
 * Communication integration boundary. Called outside DB transactions, before the API future
 * succeeds, whenever a named period cannot communicate. Must be idempotent and thread-safe.
 * Stop only queues/connections bearing this ID; an old request must not stop a newer period.
 * Dispatch to the protocol executor without blocking on repository futures. Stop advertisements,
 * scans, GATT, queued sends, receive buffers and the matching service. DB deletion cannot do this.
 * Throwing fails the API future (committed DB cleanup remains committed); the next check retries.
 */
@FunctionalInterface
public interface SessionStopHandler {
    void stopSession(String sessionId);
}
