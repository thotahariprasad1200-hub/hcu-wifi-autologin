package edu.uoh.hcuwifi;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.util.Log;
import android.widget.Toast;

/**
 * Event-driven Network Receiver.
 * Triggers captive portal login when Wi-Fi connects to the HCU 10.4.*.* subnet.
 * Conforms to zero-idle-resource architecture: runs briefly on event, then terminates.
 */
public class NetworkReceiver extends BroadcastReceiver {

    private static final String TAG = "NetworkReceiver";
    public static final String ACTION_AUTO_LOGIN = "edu.uoh.hcuwifi.ACTION_AUTO_LOGIN";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (action == null) return;

        Log.d(TAG, "Received action: " + action);

        if (ConnectivityManager.CONNECTIVITY_ACTION.equals(action) || ACTION_AUTO_LOGIN.equals(action)) {
            // Check if user enabled auto-login
            if (!HcuAuthHelper.isAutoLoginEnabled(context)) {
                Log.d(TAG, "Auto-login disabled in preferences; skipping.");
                return;
            }

            // Check if connected to Wi-Fi
            if (!HcuAuthHelper.isWifiConnected(context)) {
                Log.d(TAG, "Not connected to Wi-Fi; skipping.");
                return;
            }

            // Check if local IP is in HCU Subnet (10.4.*.*)
            String localIp = HcuAuthHelper.getLocalIpAddress();
            if (!HcuAuthHelper.isHcuSubnet(localIp)) {
                Log.d(TAG, "Local IP " + localIp + " not in HCU subnet 10.4.*.*; skipping.");
                return;
            }

            Log.i(TAG, "HCU Subnet detected (" + localIp + "). Initiating captive portal auto-login...");

            // Trigger silent background login
            HcuAuthHelper.loginAsync(context, result -> {
                Log.i(TAG, "Auto-login result: " + result);
                if (result.isSuccess()) {
                    Toast.makeText(context, "HCU Wi-Fi Auto-Login: Connected!", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }
}
