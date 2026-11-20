package com.southportsmoothies.menuboard;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

/**
 * Every few minutes, checks that the board is on screen and reopens it if not.
 * Turned off by the double-Back exit; turned back on when the app is opened
 * or the Fire TV restarts.
 */
final class Watchdog {

    private static final String PREFS = "menuboard";
    private static final String KEY_ENABLED = "auto_reopen";
    private static final int REQUEST_CODE = 1001;

    private Watchdog() { }

    static void enable(Context c) {
        prefs(c).edit().putBoolean(KEY_ENABLED, true).apply();
        schedule(c);
    }

    static void disable(Context c) {
        prefs(c).edit().putBoolean(KEY_ENABLED, false).apply();
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(pendingIntent(c));
    }

    static boolean isEnabled(Context c) {
        return prefs(c).getBoolean(KEY_ENABLED, true);
    }

    /** Book the next check. RTC (not RTC_WAKEUP) so it never wakes a sleeping Fire TV. */
    static void schedule(Context c) {
        if (!isEnabled(c)) return;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long minutes = Math.max(1, c.getResources().getInteger(R.integer.watchdog_minutes));
        long at = System.currentTimeMillis() + minutes * 60_000L;
        PendingIntent pi = pendingIntent(c);
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.set(AlarmManager.RTC, at, pi);
            } else {
                am.setExact(AlarmManager.RTC, at, pi);
            }
        } catch (SecurityException e) {
            am.set(AlarmManager.RTC, at, pi);
        }
    }

    static void openBoard(Context c) {
        Intent i = new Intent(c, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
        } catch (Exception ignored) {
            // Fire OS refused the launch; the next check will try again.
        }
    }

    private static PendingIntent pendingIntent(Context c) {
        Intent i = new Intent(c, WatchdogReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(c, REQUEST_CODE, i, flags);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
