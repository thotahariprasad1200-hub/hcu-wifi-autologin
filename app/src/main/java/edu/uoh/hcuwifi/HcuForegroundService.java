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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Permanent zero-touch foreground service for Hari Auto Login.
 * 1. Binds per-socket to Wi-Fi so HTTP requests never get misrouted to Mobile Data.
 * 2. Listens for Wi-Fi association (onAvailable) and captive portal / session expiry (onCapabilitiesChanged).
 * 3. Runs a periodic 5-minute watchdog to recover from silent inactivity timeouts.
 */
public class HcuForegroundService extends Service {

    private static final String TAG = "HcuFgService";
    private static final String CHANNEL_ID = "hcu_wifi_monitor";
    private static final int NOTIF_ID = 7001;

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicLong lastAttemptTime = new AtomicLong(0);

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
        startWatchdog();
        Log.d(TAG, "Hari Auto Login service active, listening for Wi-Fi & session expiry events");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Immediate check when service starts
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
                executor.execute(() -> performAutoLogin(network));
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                if (caps == null) return;
                boolean isCaptive = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL);
                boolean isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);

                // Detect silent session expiry (e.g. idle timeout in library/hostel)
                if (isCaptive || !isValidated) {
                    long now = System.currentTimeMillis();
                    if (now - lastAttemptTime.get() > 8000) {
                        lastAttemptTime.set(now);
                        Log.d(TAG, "Wi-Fi session expired or captive portal active. Re-authenticating...");
                        executor.execute(() -> performAutoLogin(network));
                    }
                }
            }

            @Override
            public void onLost(Network network) {
                Log.d(TAG, "Wi-Fi disconnected: " + network);
                updateNotification("Wi-Fi disconnected. Waiting for campus Wi-Fi...");
            }
        };

        try {
            connectivityManager.registerNetworkCallback(request, networkCallback);
        } catch (Exception e) {
            Log.e(TAG, "Failed to register network callback", e);
        }
    }

    /**
     * Periodic watchdog that checks connectivity every 5 minutes while connected to Wi-Fi.
     * Recovers from firewall timeouts if Android fails to fire capability change callbacks.
     */
    private void startWatchdog() {
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                if (HcuAuthHelper.isWifiConnected(this)) {
                    Network net = HcuAuthHelper.getWifiNetwork(this);
                    if (!HcuAuthHelper.isOnline(net, this)) {
                        Log.d(TAG, "Watchdog: Wi-Fi offline / unauthenticated. Re-authenticating...");
                        performAutoLogin(net);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Watchdog check error: " + e.getMessage());
            }
        }, 2, 5, TimeUnit.MINUTES);
    }

    private void performAutoLogin(Network network) {
        try {
            // Wait 1.5 seconds for DHCP lease to assign IP address
            Thread.sleep(1500);

            String ip = HcuAuthHelper.getLocalWifiIpAddress(this);
            Log.d(TAG, "Active Wi-Fi IP: " + ip);

            // Probe internet status strictly over Wi-Fi
            if (HcuAuthHelper.isOnline(network, this)) {
                Log.d(TAG, "Already online on Wi-Fi IP: " + ip);
                HcuAuthHelper.reportOnlineToAndroid(this, network);
                updateNotification("✓ Hari Auto Login: Online (" + ip + ")");
                return;
            }

            updateNotification("HCU Wi-Fi detected (" + ip + "), authenticating...");

            // Execute Wi-Fi bound login
            HcuAuthHelper.AuthResult result = HcuAuthHelper.loginSync(this, network);
            Log.d(TAG, "Auto-login result: " + result);

            if (result.isSuccess()) {
                HcuAuthHelper.reportOnlineToAndroid(this, network);
                updateNotification("✓ Hari Auto Login: Online (" + ip + ")");
            } else if (result.status == HcuAuthHelper.AuthResult.Status.INVALID_CREDENTIALS) {
                updateNotification("Hari Auto Login: " + result.message);
            } else {
                // Retry once after 3 seconds for transient gateway delays
                Thread.sleep(3000);
                HcuAuthHelper.AuthResult retry = HcuAuthHelper.loginSync(this, network);
                if (retry.isSuccess()) {
                    HcuAuthHelper.reportOnlineToAndroid(this, network);
                    updateNotification("✓ Hari Auto Login: Online (" + ip + ")");
                } else {
                    updateNotification("Hari Auto Login: " + retry.message);
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
                    "Hari Auto Login Monitor",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Monitors campus Wi-Fi connection and logs into captive portal automatically");
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
                .setContentTitle("Hari Auto Login")
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
        super.onDestroy();
        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {}
        }
        scheduler.shutdownNow();
        executor.shutdownNow();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
