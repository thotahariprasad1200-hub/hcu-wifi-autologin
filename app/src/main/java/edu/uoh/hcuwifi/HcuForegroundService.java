package edu.uoh.hcuwifi;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lightweight foreground service that survives Realme UI background kills.
 * Registers a NetworkCallback that fires ONLY when Wi-Fi connects/disconnects.
 * Zero polling, zero wake locks, zero CPU when idle.
 * The persistent notification is the trade-off Android 12/13 requires for background survival.
 */
public class HcuForegroundService extends Service {

    private static final String TAG = "HcuFgService";
    private static final String CHANNEL_ID = "hcu_wifi_monitor";
    private static final int NOTIF_ID = 7001;

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public static void start(Context ctx) {
        Intent intent = new Intent(ctx, HcuForegroundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent);
        } else {
            ctx.startService(intent);
        }
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, HcuForegroundService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification("Monitoring HCU Wi-Fi..."));
        registerWifiCallback();
        Log.d(TAG, "Service created, NetworkCallback registered");
    }

    private void registerWifiCallback() {
        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);

        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                Log.d(TAG, "Wi-Fi connected, triggering auto-login pipeline...");
                executor.execute(() -> {
                    try {
                        // Wait for DHCP to assign IP
                        Thread.sleep(3000);

                        String ip = HcuAuthHelper.getLocalIpAddress();
                        if (ip == null || !HcuAuthHelper.isHcuSubnet(ip)) {
                            Log.d(TAG, "Not HCU subnet: " + ip);
                            updateNotification("Wi-Fi connected (" + ip + ") — not HCU");
                            return;
                        }

                        Log.d(TAG, "HCU subnet detected: " + ip + ", authenticating...");
                        updateNotification("HCU detected (" + ip + "), logging in...");

                        HcuAuthHelper.AuthResult result = HcuAuthHelper.loginSync(
                                HcuForegroundService.this);

                        if (result.isSuccess()) {
                            updateNotification("✓ HCU Wi-Fi: Online (" + ip + ")");
                        } else {
                            // Retry once after 5 seconds
                            Thread.sleep(5000);
                            HcuAuthHelper.AuthResult retry = HcuAuthHelper.loginSync(
                                    HcuForegroundService.this);
                            if (retry.isSuccess()) {
                                updateNotification("✓ HCU Wi-Fi: Online (retry) (" + ip + ")");
                            } else {
                                updateNotification("✗ Login failed: " + retry.message);
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }

            @Override
            public void onLost(Network network) {
                Log.d(TAG, "Wi-Fi disconnected");
                updateNotification("Wi-Fi disconnected. Waiting...");
            }
        };

        connectivityManager.registerNetworkCallback(request, networkCallback);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "HCU Wi-Fi Monitor",
                    NotificationManager.IMPORTANCE_MIN  // Silent, no sound, no vibration
            );
            channel.setDescription("Monitors Wi-Fi and auto-logs into HCU captive portal");
            channel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent tapIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, tapIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        return builder
                .setContentTitle("HCU Wi-Fi")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_wifi)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIF_ID, buildNotification(text));
        }
    }

    @Override
    public void onDestroy() {
        if (connectivityManager != null && networkCallback != null) {
            connectivityManager.unregisterNetworkCallback(networkCallback);
        }
        Log.d(TAG, "Service destroyed");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
