package com.example.crosspath.ble;

import org.junit.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class GattOperationQueueTest {
    @Test public void serializesAndWaitsForMatchingCallback() throws Exception {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        try {
            executor.submit(() -> {
                List<String> events = new ArrayList<>();
                GattOperationQueue q = new GattOperationQueue(executor, 1000, () -> events.add("fail"));
                q.add("first", () -> { events.add("first"); return true; }, () -> events.add("done"));
                q.add("second", () -> { events.add("second"); return true; }, () -> { });
                assertEquals(Collections.singletonList("first"), events);
                q.complete("first", true);
                assertEquals(Arrays.asList("first", "done", "second"), events);
                q.complete("wrong", true);
                assertEquals("fail", events.get(3));
                q.clear();
            }).get(2, TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
    }
    @Test public void timesOutAndDropsQueuedOperations() throws Exception {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        try {
            CountDownLatch failure = new CountDownLatch(1);
            List<String> started = new CopyOnWriteArrayList<>();
            executor.submit(() -> {
                GattOperationQueue q = new GattOperationQueue(executor, 20, failure::countDown);
                q.add("first", () -> { started.add("first"); return true; }, () -> { });
                q.add("never", () -> { started.add("never"); return true; }, () -> { });
            }).get(2, TimeUnit.SECONDS);
            assertTrue(failure.await(2, TimeUnit.SECONDS));
            assertEquals(Collections.singletonList("first"), started);
        } finally { executor.shutdownNow(); }
    }
    @Test public void immediateFailureAndClearDoNotCompleteOperations() throws Exception {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        try {
            executor.submit(() -> {
                List<String> events = new ArrayList<>();
                GattOperationQueue q = new GattOperationQueue(executor, 1000, () -> events.add("fail"));
                q.add("rejected", () -> false, () -> events.add("done"));
                assertEquals(Collections.singletonList("fail"), events);
                q.add("pending", () -> true, () -> events.add("done")); q.clear();
                assertEquals(Collections.singletonList("fail"), events);
            }).get(2, TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
    }
}
