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
 * Permanent zero-touch foreground service for HCU Wi-Fi auto-login.
 * 1. Binds process to Wi-Fi so HTTP requests never get misrouted to Mobile Data.
 * 2. Listens for Wi-Fi association events and triggers Sophos authentication automatically.
 * 3. Survives background memory reclamation via START_STICKY.
 */
public class HcuForegroundService extends Service {

    private static final String TAG = "HcuFgService";
    private static final String CHANNEL_ID = "hcu_wifi_monitor";
    private static final int NOTIF_ID = 7001;

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public static void start(Context ctx) {
        if (ctx == null) return;
        Intent intent = new Intent(ctx, HcuForegroundService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent);
            } else {
                ctx.startService(intent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error starting HcuForegroundService: " + e.getMessage());
        }
    }

    public static void stop(Context ctx) {
        if (ctx == null) return;
        ctx.stopService(new Intent(ctx, HcuForegroundService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification("Monitoring HCU Wi-Fi (Auto-login active)"));
        registerWifiCallback();
        Log.d(TAG, "Service created, Wi-Fi callback registered");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Trigger immediate check in case Wi-Fi is already connected when service starts
        executor.execute(() -> performAutoLogin(null));
        return START_STICKY;
    }

    private void registerWifiCallback() {
        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (connectivityManager == null) return;

        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                Log.d(TAG, "Wi-Fi network available: " + network);

                // CRITICAL: Force all sockets in this process to use this Wi-Fi network
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    connectivityManager.bindProcessToNetwork(network);
                }

                executor.execute(() -> performAutoLogin(network));
            }

            @Override
            public void onLost(Network network) {
                Log.d(TAG, "Wi-Fi disconnected: " + network);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    connectivityManager.bindProcessToNetwork(null);
                }
                updateNotification("Wi-Fi disconnected. Waiting for HCU network...");
            }
        };

        try {
            connectivityManager.registerNetworkCallback(request, networkCallback);
        } catch (Exception e) {
            Log.e(TAG, "Failed to register network callback", e);
        }
    }

    private void performAutoLogin(Network network) {
        try {
            // Wait 2 seconds for DHCP lease to assign IP address
            Thread.sleep(2000);

            // Bind to Wi-Fi if network object is provided
            if (network != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                connectivityManager.bindProcessToNetwork(network);
            } else {
                HcuAuthHelper.bindToWifi(this);
            }

            String ip = HcuAuthHelper.getLocalWifiIpAddress(this);
            Log.d(TAG, "Active Wi-Fi IP: " + ip);

            // Probe current status
            if (HcuAuthHelper.isOnline()) {
                Log.d(TAG, "Already online on Wi-Fi IP: " + ip);
                updateNotification("✓ HCU Wi-Fi: Online (" + ip + ")");
                return;
            }

            updateNotification("HCU Wi-Fi detected (" + ip + "), authenticating...");

            // Execute login
            HcuAuthHelper.AuthResult result = HcuAuthHelper.loginSync(this);
            Log.d(TAG, "Auto-login result: " + result);

            if (result.isSuccess()) {
                updateNotification("✓ HCU Wi-Fi: Signed In (" + ip + ")");
            } else {
                // Retry once after 3 seconds
                Thread.sleep(3000);
                HcuAuthHelper.AuthResult retry = HcuAuthHelper.loginSync(this);
                if (retry.isSuccess()) {
                    updateNotification("✓ HCU Wi-Fi: Signed In (Retry) (" + ip + ")");
                } else {
                    updateNotification("HCU Login: " + retry.message);
                }
            }

        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Log.e(TAG, "Error in performAutoLogin", e);
            updateNotification("Login check failed: " + e.getMessage());
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "HCU Wi-Fi Auto-Login",
                    NotificationManager.IMPORTANCE_LOW // Low priority, silent, no sound/vibration
            );
            channel.setDescription("Monitors Wi-Fi connection and logs into HCU captive portal automatically");
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
            try {
                manager.notify(NOTIF_ID, buildNotification(text));
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onDestroy() {
        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {}
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && connectivityManager != null) {
            connectivityManager.bindProcessToNetwork(null);
        }
        executor.shutdown();
        Log.d(TAG, "Service destroyed");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
