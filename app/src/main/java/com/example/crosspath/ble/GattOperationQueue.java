package com.example.crosspath.ble;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** All methods and completions must run on the same protocol executor. */
public final class GattOperationQueue {
    private static final class Operation {
        final String key;
        final BooleanSupplier start;
        final Runnable complete;
        Operation(String key, BooleanSupplier start, Runnable complete) {
            this.key = key; this.start = start; this.complete = complete;
        }
    }
    private final Queue<Operation> waiting = new ArrayDeque<>();
    private final ScheduledExecutorService executor;
    private final Runnable failure;
    private final long timeoutMs;
    private Operation active;
    private ScheduledFuture<?> timeout;
    public GattOperationQueue(ScheduledExecutorService executor, long timeoutMs, Runnable failure) {
        this.executor = executor; this.timeoutMs = timeoutMs; this.failure = failure;
    }
    public void add(String key, BooleanSupplier start, Runnable complete) {
        if (waiting.size() >= 512) { fail(); return; }
        waiting.add(new Operation(key, start, complete));
        pump();
    }
    private void pump() {
        if (active != null || waiting.isEmpty()) return;
        active = waiting.remove();
        Operation current = active;
        timeout = executor.schedule(() -> { if (active == current) fail(); }, timeoutMs, TimeUnit.MILLISECONDS);
        try { if (!current.start.getAsBoolean()) fail(); }
        catch (RuntimeException error) { fail(); }
    }
    public void complete(String key, boolean success) {
        if (active == null || !active.key.equals(key)) { fail(); return; }
        if (!success) { fail(); return; }
        Operation done = active;
        if (timeout != null) timeout.cancel(false);
        active = null;
        done.complete.run();
        pump();
    }
    public void clear() {
        if (timeout != null) timeout.cancel(false);
        active = null; waiting.clear();
    }
    private void fail() { clear(); failure.run(); }
}
