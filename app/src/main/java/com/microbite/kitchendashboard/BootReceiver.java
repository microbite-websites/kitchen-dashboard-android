package com.microbite.kitchendashboard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.PreferenceManager;

/**
 * Brings the dashboard back up after the phone/tablet reboots (e.g. a power
 * blip in the kitchen), so staff don't have to find and tap the app icon.
 *
 * Best-effort: starting an Activity from the background is restricted on newer
 * Android, so this may be a no-op on some devices. It is gated by the
 * "auto_start_boot" Settings toggle and only fires once a dashboard URL exists.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "KitchenDashboard";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (!prefs.getBoolean("auto_start_boot", true)) return;
        if (prefs.getString("dashboard_url", "").isEmpty()) return;

        Intent launch = new Intent(context, MainActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(launch);
        } catch (Exception e) {
            // Background activity starts can be blocked by the OS — nothing we
            // can safely do here, so fail quietly rather than crash on boot.
            Log.w(TAG, "Could not auto-start on boot", e);
        }
    }
}
