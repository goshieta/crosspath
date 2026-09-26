package com.example.crosspath.data;

import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;

final class AndroidSessionClock implements SessionClock {
    private final Context context;

    AndroidSessionClock(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override public Reading read() {
        String boot = "";
        try {
            int count = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT);
            if (count >= 0) boot = Integer.toString(count);
        } catch (Settings.SettingNotFoundException | SecurityException unavailable) {
            // Unknown boot identity must never authorize communication.
        }
        long elapsed = SystemClock.elapsedRealtime();
        return new Reading(System.currentTimeMillis(), elapsed, boot);
    }
}
