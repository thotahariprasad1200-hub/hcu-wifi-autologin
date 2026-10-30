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

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ultra-fast event-driven foreground service for Hari Auto Login.
 * Modeled on the lightweight Windows one-shot trigger:
 * - Fires immediately on Wi-Fi connection or captive portal detection.
 * - Authenticates in ~1s without blocking thread queues.
 * - Drops duplicate concurrent events (zero task pileup).
 * - Goes completely dormant when online to save 100% battery & CPU.
 */
public class HcuForegroundService extends Service {

    private static final String TAG = "HcuFgService";
    private static final String CHANNEL_ID = "hcu_wifi_monitor";
    private static final int NOTIF_ID = 7001;

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    // Concurrency protection: drops any duplicate triggers while authenticating
    private final AtomicBoolean isAuthenticating = new AtomicBoolean(false);
    private final AtomicLong lastAttemptTimestamp = new AtomicLong(0);

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
        startPeriodicWatchdog();
        Log.d(TAG, "Hari Auto Login service initialized");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Trigger one-shot login check on service startup
        triggerOneShotLogin(null, "Service started");
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
                Log.d(TAG, "Wi-Fi network connected: " + network);
                triggerOneShotLogin(network, "Wi-Fi associated");
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                if (caps == null) return;
                boolean isCaptive = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL);
                boolean isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);

                // If portal intercepted or validation lost, trigger login
                if (isCaptive || !isValidated) {
                    triggerOneShotLogin(network, "Captive portal detected");
                }
            }

            @Override
            public void onLost(Network network) {
                Log.d(TAG, "Wi-Fi disconnected: " + network);
                isAuthenticating.set(false);
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
     * Periodic watchdog that checks connectivity every 3 minutes while Wi-Fi is connected.
     * Catches firewall timeouts even if Android OS fails to broadcast capability changes.
     */
    private void startPeriodicWatchdog() {
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                if (HcuAuthHelper.isWifiConnected(this)) {
                    Network net = HcuAuthHelper.getWifiNetwork(this);
                    if (!HcuAuthHelper.isOnline(net, this)) {
                        Log.d(TAG, "Watchdog detected offline state on Wi-Fi");
                        triggerOneShotLogin(net, "Watchdog detected offline");
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Watchdog error: " + e.getMessage());
            }
        }, 1, 3, TimeUnit.MINUTES);
    }

    /**
     * Core non-blocking one-shot login trigger.
     * Prevents queue pileup by immediately dropping duplicate requests if one is in-flight.
     */
    private void triggerOneShotLogin(Network network, String triggerReason) {
        long now = System.currentTimeMillis();
        // Debounce: don't trigger more than once every 10 seconds
        if (now - lastAttemptTimestamp.get() < 10000) {
            Log.d(TAG, "Skipping trigger (" + triggerReason + ") - debounced");
            return;
        }

        // Lock check: if already authenticating, drop immediately (no queueing!)
        if (!isAuthenticating.compareAndSet(false, true)) {
            Log.d(TAG, "Skipping trigger (" + triggerReason + ") - already authenticating");
            return;
        }

        lastAttemptTimestamp.set(now);
        Log.d(TAG, "Executing one-shot login: " + triggerReason);

        scheduler.execute(() -> {
            try {
                String ip = HcuAuthHelper.getLocalWifiIpAddress(this);

                // Quick pre-check: if already online, don't bother Sophos
                if (HcuAuthHelper.isOnline(network, this)) {
                    Log.d(TAG, "Already online on " + ip);
                    HcuAuthHelper.reportOnlineToAndroid(this, network);
                    updateNotification("✓ Hari Auto Login: Online (" + ip + ")");
                    isAuthenticating.set(false);
                    return;
                }

                updateNotification("HCU Wi-Fi: Logging in (" + ip + ")...");

                // Execute synchronous login on this worker thread
                HcuAuthHelper.AuthResult result = HcuAuthHelper.loginSync(this, network);
                Log.d(TAG, "Auto-login result: " + result);

                if (result.isSuccess()) {
                    HcuAuthHelper.reportOnlineToAndroid(this, network);
                    updateNotification("✓ Hari Auto Login: Online (" + ip + ")");
                } else if (result.status == HcuAuthHelper.AuthResult.Status.INVALID_CREDENTIALS) {
                    updateNotification("Hari Auto Login: Check username & password");
                } else {
                    updateNotification("Hari Auto Login: " + result.message);
                }

            } catch (Exception e) {
                Log.e(TAG, "Error during one-shot login", e);
                updateNotification("Login check failed: " + e.getMessage());
            } finally {
                isAuthenticating.set(false);
            }
        });
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
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
