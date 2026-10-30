package edu.uoh.hcuwifi;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Starts the foreground service automatically on device boot and app update.
 * Zero user interaction required after initial setup.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "HcuBootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : "";
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)) {

            SharedPreferences prefs = HcuAuthHelper.getPrefs(context);
            boolean autoLogin = prefs.getBoolean(HcuAuthHelper.KEY_AUTOLOGIN, true);

            if (autoLogin) {
                Log.d(TAG, "Boot/update detected, starting HCU Wi-Fi service");
                HcuForegroundService.start(context);
                // OS-held jobs are the reliable path; the service is best effort here.
                HcuWatchdogJobService.scheduleAll(context);
            }
        }
    }
}
