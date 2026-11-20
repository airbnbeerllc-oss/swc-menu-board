package com.southportsmoothies.menuboard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Opens the board when the Fire TV finishes starting, and after an app update. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            Watchdog.enable(context);
            Watchdog.openBoard(context);
        } else if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            if (Watchdog.isEnabled(context)) {
                Watchdog.schedule(context);
                Watchdog.openBoard(context);
            }
        }
    }
}
