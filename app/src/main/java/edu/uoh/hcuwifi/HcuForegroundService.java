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

/**
 * Persistent, event-driven HCU captive-portal sign-in service.
 *
 * A Wi-Fi connection is not considered ready merely because onAvailable()
 * fired. The service waits for DHCP, checks the exact callback Network,
 * submits credentials on that Network, and verifies internet access before
 * reporting success. Retry delays grow to two minutes and continue while the
 * same HCU Wi-Fi network remains connected.
 */
public class HcuForegroundService extends Service {

    private static final String TAG = "HcuFgService";
    private static final String CHANNEL_ID = "hcu_wifi_monitor";
    private static final int NOTIF_ID = 7001;
    private static final int MAX_IP_WAIT_RETRIES = 30;
    private static final long[] AUTH_RETRY_DELAYS_MS = {
            1500L, 3000L, 6000L, 12000L, 20000L, 30000L, 60000L, 120000L
    };

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private BroadcastReceiver wifiStateReceiver;

    // Separate worker capacity prevents a delayed retry from blocking callbacks.
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final AtomicBoolean isAuthenticating = new AtomicBoolean(false);
    private final AtomicInteger networkGeneration = new AtomicInteger(0);

    private volatile Network currentWifiNetwork;

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
        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        registerWifiCallback();
        registerWifiBroadcastReceiver();
        startSessionWatchdog();
        Log.i(TAG, "Zero-touch HCU Wi-Fi monitor started.");
    }

    private void startAsForeground() {
        Notification notification = buildNotification("Waiting for HCU Wi-Fi");
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
            updateNotification("Monitoring active — waiting for Wi-Fi");
        }
        return START_STICKY;
    }

    private void registerWifiCallback() {
        if (connectivityManager == null) return;

        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                Log.i(TAG, "Wi-Fi available: " + network);
                acceptNetwork(network, "Wi-Fi connected");
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    acceptNetwork(network, caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                            ? "Wi-Fi capabilities updated" : "Captive portal / unvalidated Wi-Fi");
                }
            }

            @Override
            public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
                if (hasUsableIpv4(properties)) {
                    Log.i(TAG, "Wi-Fi DHCP address is ready on " + network);
                    acceptNetwork(network, "Wi-Fi address assigned");
                }
            }

            @Override
            public void onLost(Network network) {
                Log.i(TAG, "Wi-Fi lost: " + network);
                if (network != null && network.equals(currentWifiNetwork)) {
                    currentWifiNetwork = null;
                    networkGeneration.incrementAndGet();
                    isAuthenticating.set(false);
                    updateNotification("Wi-Fi disconnected — waiting for HCU Wi-Fi");
                }
            }
        };

        try {
            connectivityManager.registerNetworkCallback(request, networkCallback);
        } catch (Exception e) {
            Log.e(TAG, "Could not register Wi-Fi callback", e);
        }
    }

    private void registerWifiBroadcastReceiver() {
        wifiStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || !HcuAuthHelper.isAutoLoginEnabled(context)) return;
                if (HcuAuthHelper.isWifiConnected(context)) {
                    Network network = HcuAuthHelper.getWifiNetwork(context);
                    if (network != null) acceptNetwork(network, "Wi-Fi system broadcast");
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
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
            Log.i(TAG, "Tracking new Wi-Fi network: " + network);
        }

        triggerOneShotLogin(network, reason, networkGeneration.get(), 0);
    }

    private void triggerOneShotLogin(Network network, String reason, int generation, int ipWaitAttempt) {
        if (!isCurrentNetwork(network, generation)) return;

        LinkProperties properties = connectivityManager != null
                ? connectivityManager.getLinkProperties(network) : null;

        if (!hasUsableIpv4(properties)) {
            if (ipWaitAttempt < MAX_IP_WAIT_RETRIES) {
                scheduler.schedule(
                        () -> triggerOneShotLogin(network, reason, generation, ipWaitAttempt + 1),
                        500L, TimeUnit.MILLISECONDS);
            } else {
                updateNotification("Connected to Wi-Fi — waiting for DHCP address");
                Log.w(TAG, "No usable IPv4 address after DHCP wait window.");
            }
            return;
        }

        String ip = HcuAuthHelper.getLocalWifiIpAddress(this, network);
        if (!HcuAuthHelper.isHcuSubnet(ip)) {
            updateNotification("Connected to another Wi-Fi network — HCU login is idle");
            return;
        }

        if (!isAuthenticating.compareAndSet(false, true)) return;
        scheduler.execute(() -> runLoginAttempt(network, reason, generation, 0));
    }

    private void runLoginAttempt(Network network, String reason, int generation, int authRetry) {
        if (!isCurrentNetwork(network, generation)) {
            finishLoginFlow(generation);
            return;
        }

        try {
            String ip = HcuAuthHelper.getLocalWifiIpAddress(this, network);
            if (!HcuAuthHelper.isHcuSubnet(ip)) {
                updateNotification("HCU network not detected (" + ip + ")");
                finishLoginFlow(generation);
                return;
            }

            // Verify the actual Wi-Fi path, rather than trusting a stale Android
            // VALIDATED flag or accidentally testing the phone's mobile-data route.
            if (HcuAuthHelper.isOnline(network, this)) {
                HcuAuthHelper.reportOnlineToAndroid(this, network);
                updateNotification("HCU Wi-Fi is online — automatic login ready");
                Log.i(TAG, "Internet already works on HCU Wi-Fi.");
                finishLoginFlow(generation);
                return;
            }

            updateNotification("HCU Wi-Fi detected — signing in automatically");
            HcuAuthHelper.AuthResult result = HcuAuthHelper.loginSync(this, network);
            Log.i(TAG, "Login attempt " + (authRetry + 1) + " (" + reason + "): "
                    + result.status + " — " + result.message);

            if (!isCurrentNetwork(network, generation)) {
                finishLoginFlow(generation);
                return;
            }

            if (result.isSuccess()) {
                updateNotification("Sign-in submitted — verifying internet access");
                scheduleVerification(network, generation, authRetry, 0);
                return;
            }

            if (result.status == HcuAuthHelper.AuthResult.Status.INVALID_CREDENTIALS) {
                updateNotification("HCU rejected saved credentials — check username/password");
                finishLoginFlow(generation);
                return;
            }

            if (result.status == HcuAuthHelper.AuthResult.Status.NOT_IN_HCU_SUBNET) {
                updateNotification("HCU captive portal not detected — waiting for network change");
                finishLoginFlow(generation);
                return;
            }

            if (result.message != null && result.message.toLowerCase().contains("quota exceeded")) {
                updateNotification("HCU account data quota exceeded");
                finishLoginFlow(generation);
                return;
            }

            scheduleAuthRetry(network, reason, generation, authRetry, result.message);

        } catch (Exception e) {
            Log.e(TAG, "Automatic login attempt failed", e);
            scheduleAuthRetry(network, reason, generation, authRetry, e.getMessage());
        }
    }

    private void scheduleVerification(Network network, int generation, int authRetry, int verifyAttempt) {
        scheduler.schedule(() -> {
            if (!isCurrentNetwork(network, generation)) {
                finishLoginFlow(generation);
                return;
            }

            if (HcuAuthHelper.isOnline(network, this)) {
                HcuAuthHelper.reportOnlineToAndroid(this, network);
                updateNotification("✓ HCU Wi-Fi connected — internet verified");
                Log.i(TAG, "Zero-touch login verified on attempt " + (authRetry + 1));
                finishLoginFlow(generation);
                return;
            }

            if (verifyAttempt < 2) {
                updateNotification("Checking HCU internet connection…");
                scheduleVerification(network, generation, authRetry, verifyAttempt + 1);
            } else {
                scheduleAuthRetry(network, "Post-login verification", generation, authRetry,
                        "Portal response received but internet probe is not online");
            }
        }, 1200L, TimeUnit.MILLISECONDS);
    }

    private void scheduleAuthRetry(Network network, String reason, int generation,
                                   int authRetry, String detail) {
        if (!isCurrentNetwork(network, generation)) {
            finishLoginFlow(generation);
            return;
        }

        long delay = AUTH_RETRY_DELAYS_MS[Math.min(authRetry, AUTH_RETRY_DELAYS_MS.length - 1)];
        Log.w(TAG, "Retrying HCU sign-in in " + delay + "ms. Detail: " + detail);
        updateNotification("HCU portal not ready — retrying automatically");
        scheduler.schedule(
                () -> runLoginAttempt(network, reason, generation, authRetry + 1),
                delay, TimeUnit.MILLISECONDS);
    }

    private boolean isCurrentNetwork(Network network, int generation) {
        return network != null
                && generation == networkGeneration.get()
                && network.equals(currentWifiNetwork)
                && HcuAuthHelper.isAutoLoginEnabled(this)
                && HcuAuthHelper.isWifiNetwork(this, network);
    }

    private boolean hasUsableIpv4(LinkProperties properties) {
        if (properties == null) return false;
        for (LinkAddress linkAddress : properties.getLinkAddresses()) {
            InetAddress address = linkAddress.getAddress();
            if (address instanceof Inet4Address && !address.isAnyLocalAddress()
                    && !address.isLoopbackAddress() && !address.isLinkLocalAddress()) {
                return true;
            }
        }
        return false;
    }

    private void finishLoginFlow(int generation) {
        // An old network's completion must not unlock a newer network's login.
        if (generation == networkGeneration.get()) isAuthenticating.set(false);
    }

    private void startSessionWatchdog() {
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                Network network = currentWifiNetwork;
                if (network != null && isCurrentNetwork(network, networkGeneration.get())
                        && !HcuAuthHelper.isOnline(network, this)) {
                    acceptNetwork(network, "Periodic HCU session check");
                }
            } catch (Exception e) {
                Log.w(TAG, "Session watchdog failed", e);
            }
        }, 5, 5, TimeUnit.MINUTES);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "HCU Wi-Fi Auto-Login", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Automatically signs in when HCU Wi-Fi connects.");
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

        return builder.setContentTitle("HCU Wi-Fi Auto-Login")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_wifi)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSound(null)
                .setVibrate(new long[0])
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            try {
                manager.notify(NOTIF_ID, buildNotification(text));
            } catch (Exception e) {
                Log.w(TAG, "Could not update status notification", e);
            }
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
