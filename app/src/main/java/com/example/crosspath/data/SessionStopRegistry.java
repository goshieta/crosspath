package com.example.crosspath.data;

import java.util.concurrent.CopyOnWriteArraySet;

/** Application-wide bridge from repository expiry to active communication owners. */
public final class SessionStopRegistry implements SessionStopHandler {
    private final CopyOnWriteArraySet<SessionStopHandler> handlers = new CopyOnWriteArraySet<>();

    public void add(SessionStopHandler handler) { handlers.add(handler); }
    public void remove(SessionStopHandler handler) { handlers.remove(handler); }

    @Override public void stopSession(String sessionId) {
        for (SessionStopHandler handler : handlers) handler.stopSession(sessionId);
    }
}
