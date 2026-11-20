package com.southportsmoothies.menuboard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Runs every few minutes: if the board isn't on screen, bring it back. */
public class WatchdogReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Watchdog.isEnabled(context)) return;
        if (!MainActivity.isInForeground) Watchdog.openBoard(context);
        Watchdog.schedule(context);
    }
}
