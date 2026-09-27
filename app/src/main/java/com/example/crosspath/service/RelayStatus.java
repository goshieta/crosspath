package com.example.crosspath.service;

/** Immutable UI state; a valid registration alone does not mean BLE is running. */
public final class RelayStatus {
    public enum State { STOPPED, STARTING, DISCOVERING, CONNECTING, SYNCING, COOLDOWN, BLOCKED }
    public final State state;
    public final String message, lastResult;
    public RelayStatus(State state, String message, String lastResult) {
        this.state = state; this.message = message; this.lastResult = lastResult;
    }
}
