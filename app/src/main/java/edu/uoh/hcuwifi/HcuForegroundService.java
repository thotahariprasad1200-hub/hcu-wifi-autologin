package edu.uoh.hcuwifi;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Event-driven HCU Wi-Fi monitor.
 *
 * It stays in a low-importance foreground service so Android can deliver Wi-Fi
 * network callbacks while the app is not visible. It performs no periodic polling:
 * when idle, it waits for Wi-Fi/network events and uses no scheduled wakeups.
 */
public class HcuForegroundService extends Service {

    private static final String TAG = "HcuFgService";
    private static final String CHANNEL_ID = "hcu_wifi_monitor";
    private static final int NOTIF_ID = 7001;
    private static final int MAX_IP_WAIT_RETRIES = 10;

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private BroadcastReceiver wifiStateReceiver;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean isAuthenticating = new AtomicBoolean(false);
    private final AtomicLong lastAttemptTimestamp = new AtomicLong(0);
    private final AtomicInteger networkGeneration = new AtomicInteger(0);

    private volatile Network currentWifiNetwork;
    private volatile Network lastAttemptNetwork;

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
            Log.e(TAG, "Could not start Wi-Fi monitor service", e);
        }
    }

    public static void stop(Context ctx) {
        if (ctx != null) ctx.stopService(new Intent(ctx, HcuForegroundService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startAsForeground();
        registerWifiCallback();
        registerWifiBroadcastReceiver();
        Log.i(TAG, "Event-driven Wi-Fi monitor is active; no periodic polling is scheduled.");
    }

    private void startAsForeground() {
        Notification notification = buildNotification("Monitoring Wi-Fi connections");
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, notification);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!HcuAuthHelper.isAutoLoginEnabled(this)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        Network network = HcuAuthHelper.getWifiNetwork(this);
        if (network != null) {
            acceptNetwork(network, "Service startup");
        } else {
            updateNotification("Waiting for a saved Wi-Fi network");
        }
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
                Log.i(TAG, "Wi-Fi network available: " + network);
                acceptNetwork(network, "Wi-Fi connected");
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                if (caps == null) return;
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    if (network.equals(currentWifiNetwork)) {
                        updateNotification("Wi-Fi is online — automatic login is standing by");
                    }
                    return;
                }
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
                        || caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    acceptNetwork(network, "Wi-Fi needs portal validation");
                }
            }

            @Override
            public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
                if (hasUsableIpv4(properties)) {
                    acceptNetwork(network, "Wi-Fi address assigned");
                }
            }

            @Override
            public void onLost(Network network) {
                Log.i(TAG, "Wi-Fi network lost: " + network);
                if (network.equals(currentWifiNetwork)) {
                    currentWifiNetwork = null;
                    networkGeneration.incrementAndGet();
                    isAuthenticating.set(false);
                    lastAttemptTimestamp.set(0);
                    lastAttemptNetwork = null;
                    updateNotification("Wi-Fi disconnected — waiting for the next connection");
                }
            }
        };

        try {
            connectivityManager.registerNetworkCallback(request, networkCallback);
        } catch (Exception e) {
            Log.e(TAG, "Could not register Wi-Fi network callback", e);
        }
    }

    private void registerWifiBroadcastReceiver() {
        wifiStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || !HcuAuthHelper.isAutoLoginEnabled(context)) return;
                String action = intent.getAction();
                if (HcuAuthHelper.isWifiConnected(context)) {
                    Network network = HcuAuthHelper.getWifiNetwork(context);
                    if (network != null) acceptNetwork(network, "Wi-Fi system broadcast");
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(wifiStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(wifiStateReceiver, filter);
            }
        } catch (Exception e) {
            Log.e(TAG, "Could not register Wi-Fi broadcast receiver", e);
        }
    }

    private void acceptNetwork(Network network, String reason) {
        if (network == null || !HcuAuthHelper.isWifiNetwork(this, network)
                || !HcuAuthHelper.isAutoLoginEnabled(this)) return;

        if (!network.equals(currentWifiNetwork)) {
            currentWifiNetwork = network;
            networkGeneration.incrementAndGet();
            isAuthenticating.set(false);
            lastAttemptTimestamp.set(0);
            lastAttemptNetwork = null;
        }
        triggerOneShotLogin(network, reason, networkGeneration.get(), 0);
    }

    private void triggerOneShotLogin(Network network, String reason, int generation, int ipWaitAttempt) {
        if (network == null || generation != networkGeneration.get()
                || !network.equals(currentWifiNetwork)
                || !HcuAuthHelper.isAutoLoginEnabled(this)) return;

        if (!hasUsableIpv4(connectivityManager != null ? connectivityManager.getLinkProperties(network) : null)) {
            if (ipWaitAttempt < MAX_IP_WAIT_RETRIES) {
                scheduler.schedule(
                        () -> triggerOneShotLogin(network, reason, generation, ipWaitAttempt + 1),
                        200, TimeUnit.MILLISECONDS);
            } else {
                Log.d(TAG, "Wi-Fi has not received an IPv4 address yet; waiting for link callback.");
            }
            return;
        }

        if (HcuAuthHelper.isNetworkValidated(this, network)) {
            updateNotification("Wi-Fi is already online — no login needed");
            return;
        }

        long now = System.currentTimeMillis();
        if (network.equals(lastAttemptNetwork) && now - lastAttemptTimestamp.get() < 900) return;
        if (!isAuthenticating.compareAndSet(false, true)) return;

        lastAttemptNetwork = network;
        lastAttemptTimestamp.set(now);
        scheduler.execute(() -> runLoginAttempt(network, reason, generation, 0));
    }

    private void runLoginAttempt(Network network, String reason, int generation, int authRetry) {
        try {
            if (generation != networkGeneration.get() || !network.equals(currentWifiNetwork)
                    || !HcuAuthHelper.isWifiNetwork(this, network)
                    || !HcuAuthHelper.isAutoLoginEnabled(this)) {
                finishLoginFlow();
                return;
            }

            if (HcuAuthHelper.isNetworkValidated(this, network)) {
                updateNotification("Wi-Fi is online — no sign-in required");
                finishLoginFlow();
                return;
            }

            updateNotification("Checking HCU portal…");
            HcuAuthHelper.AuthResult result = HcuAuthHelper.loginSync(this, network);
            Log.i(TAG, "Automatic login result (" + reason + "): " + result.status + " — " + result.message);

            if (result.isSuccess()) {
                updateNotification("HCU Wi-Fi connected — automatic login is standing by");
                finishLoginFlow();
                return;
            }

            boolean retryable = result.status == HcuAuthHelper.AuthResult.Status.GATEWAY_UNREACHABLE
                    || result.status == HcuAuthHelper.AuthResult.Status.ERROR
                    || result.status == HcuAuthHelper.AuthResult.Status.BUSY;

            if (retryable && authRetry < 2 && generation == networkGeneration.get()
                    && HcuAuthHelper.isWifiNetwork(this, network)) {
                long delayMs = authRetry == 0 ? 1200L : 2400L;
                updateNotification("Portal is not ready yet — retrying automatically");
                scheduler.schedule(
                        () -> runLoginAttempt(network, reason, generation, authRetry + 1),
                        delayMs, TimeUnit.MILLISECONDS);
                return;
            }

            if (result.status == HcuAuthHelper.AuthResult.Status.NOT_IN_HCU_SUBNET) {
                updateNotification("Wi-Fi connected — no HCU portal detected; left unchanged");
            } else if (result.status == HcuAuthHelper.AuthResult.Status.INVALID_CREDENTIALS) {
                updateNotification("HCU portal rejected saved credentials — check them in the app");
            } else {
                updateNotification("HCU sign-in could not be verified — will retry on the next Wi-Fi event");
            }
            finishLoginFlow();
        } catch (Exception e) {
            Log.e(TAG, "Automatic Wi-Fi login failed", e);
            updateNotification("Automatic login error — waiting for the next Wi-Fi event");
            finishLoginFlow();
        }
    }

    private boolean hasUsableIpv4(LinkProperties properties) {
        if (properties == null) return false;
        for (LinkAddress linkAddress : properties.getLinkAddresses()) {
            InetAddress address = linkAddress.getAddress();
            if (address instanceof Inet4Address && !address.isAnyLocalAddress()
                    && !address.isLoopbackAddress() && !address.isLinkLocalAddress()) return true;
        }
        return false;
    }

    private void finishLoginFlow() {
        isAuthenticating.set(false);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "HCU Wi-Fi Auto-Login", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps the automatic campus Wi-Fi sign-in listener available.");
            channel.setShowBadge(false);
            channel.setSound(null, null);
            channel.enableVibration(false);
            channel.enableLights(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent tapIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, tapIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        builder.setContentTitle("HCU Wi-Fi Auto-Login")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_wifi)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) builder.setSilent(true);
        return builder.build();
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
            try { connectivityManager.unregisterNetworkCallback(networkCallback); }
            catch (Exception ignored) {}
        }
        if (wifiStateReceiver != null) {
            try { unregisterReceiver(wifiStateReceiver); }
            catch (Exception ignored) {}
        }
        scheduler.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
