package com.example.crosspath.data;

/** Internal clock seam; production callers cannot supply arbitrary time. */
interface SessionClock {
    Reading read();

    final class Reading {
        final long wall;
        final long elapsed;
        final String bootMarker;

        Reading(long wall, long elapsed, String bootMarker) {
            this.wall = wall;
            this.elapsed = elapsed;
            this.bootMarker = bootMarker;
        }
    }
}
