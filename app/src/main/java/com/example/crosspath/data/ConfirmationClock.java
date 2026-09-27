package com.example.crosspath.data;

import android.content.Context;
import android.os.SystemClock;

/** Construct on a worker to read boot identity, then capture the UI confirmation without I/O. */
public final class ConfirmationClock {
    private final String bootMarker;
    public ConfirmationClock(Context context) {
        bootMarker = new AndroidSessionClock(context).read().bootMarker;
    }
    public Token capture() {
        return new Token(new SessionClock.Reading(System.currentTimeMillis(), SystemClock.elapsedRealtime(), bootMarker));
    }
    public static final class Token {
        final SessionClock.Reading reading;
        Token(SessionClock.Reading reading) { this.reading = reading; }
    }
}
