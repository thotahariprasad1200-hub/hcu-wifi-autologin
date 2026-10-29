package edu.uoh.hcuwifi;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ultra-lightweight authentication helper for University of Hyderabad (HCU) Sophos Captive Portal.
 * Uses zero external libraries (pure java.net.HttpURLConnection + Android Keystore) to keep the app sub-50 KB.
 * Enforces strict per-request Wi-Fi Network socket binding so requests never route over Mobile Data.
 */
public final class HcuAuthHelper {

    private static final String TAG = "HcuAuthHelper";

    // Sophos Gateway endpoints
    public static final String PRIMARY_GATEWAY = "192.168.56.2";
    public static final String FALLBACK_GATEWAY = "10.4.0.1";
    public static final int PORT = 8090;
    public static final String AUTH_PATH = "/httpclient.html";

    // Connectivity probe endpoint (Google captive portal probe)
    public static final String PROBE_URL = "http://connectivitycheck.gstatic.com/generate_204";

    // SharedPreferences Config
    public static final String PREFS_NAME = "hcu_wifi_prefs";
    public static final String KEY_USERNAME = "pref_username";
    public static final String KEY_PASSWORD = "pref_password";
    public static final String KEY_AUTOLOGIN = "pref_autologin";

    // Concurrency control to debounce duplicate login calls
    private static final AtomicBoolean IS_AUTHENTICATING = new AtomicBoolean(false);

    // Single background worker thread
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private HcuAuthHelper() {}

    public static class AuthResult {
        public enum Status {
            SUCCESS,
            ALREADY_ONLINE,
            INVALID_CREDENTIALS,
            GATEWAY_UNREACHABLE,
            NOT_IN_HCU_SUBNET,
            BUSY,
            ERROR
        }

        public final Status status;
        public final String message;
        public final String rawResponse;
        public final String gatewayUsed;

        public AuthResult(Status status, String message, String rawResponse, String gatewayUsed) {
            this.status = status;
            this.message = message;
            this.rawResponse = rawResponse != null ? rawResponse : "";
            this.gatewayUsed = gatewayUsed != null ? gatewayUsed : "";
        }

        public boolean isSuccess() {
            return status == Status.SUCCESS || status == Status.ALREADY_ONLINE;
        }

        @Override
        public String toString() {
            return "[" + status + "] " + message + (gatewayUsed.isEmpty() ? "" : " (via " + gatewayUsed + ")");
        }
    }

    public interface AuthCallback {
        void onComplete(AuthResult result);
    }

    public static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static String getSavedUsername(Context context) {
        return getPrefs(context).getString(KEY_USERNAME, "");
    }

    public static String getSavedPassword(Context context) {
        return SecureStorage.getPassword(context);
    }

    public static boolean isAutoLoginEnabled(Context context) {
        return getPrefs(context).getBoolean(KEY_AUTOLOGIN, true);
    }

    public static void saveConfig(Context context, String username, String password, boolean autoLogin) {
        getPrefs(context).edit()
                .putString(KEY_USERNAME, username.trim())
                .putBoolean(KEY_AUTOLOGIN, autoLogin)
                .apply();
        SecureStorage.savePassword(context, password);
    }

    /**
     * Resolves the active Wi-Fi Network object.
     */
    public static Network getWifiNetwork(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null;
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                for (Network net : cm.getAllNetworks()) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        return net;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error resolving Wi-Fi network: " + e.getMessage());
        }
        return null;
    }

    /**
     * Opens an HttpURLConnection bound strictly to the Wi-Fi Network interface.
     * Throws IOException if Wi-Fi is not connected — NEVER falls back to Mobile Data.
     */
    public static HttpURLConnection openWifiConnection(Network network, Context context, URL url) throws IOException {
        if (network != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return (HttpURLConnection) network.openConnection(url);
        }
        if (context != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network wifiNet = getWifiNetwork(context);
            if (wifiNet != null) {
                return (HttpURLConnection) wifiNet.openConnection(url);
            }
        }
        throw new IOException("No active Wi-Fi connection found. Refusing to route traffic over Mobile Data.");
    }

    public static boolean bindToWifi(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && context != null) {
            try {
                ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    Network wifiNet = getWifiNetwork(context);
                    if (wifiNet != null) {
                        cm.bindProcessToNetwork(wifiNet);
                        return true;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to bind to Wi-Fi: " + e.getMessage());
            }
        }
        return false;
    }

    public static boolean isWifiConnected(Context context) {
        if (context == null) return false;
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return getWifiNetwork(context) != null;
        } else {
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected() && info.getType() == ConnectivityManager.TYPE_WIFI;
        }
    }

    public static String getLocalWifiIpAddress(Context context) {
        if (context != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                Network wifiNet = getWifiNetwork(context);
                if (cm != null && wifiNet != null) {
                    LinkProperties lp = cm.getLinkProperties(wifiNet);
                    if (lp != null) {
                        for (LinkAddress la : lp.getLinkAddresses()) {
                            InetAddress addr = la.getAddress();
                            if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                                return addr.getHostAddress();
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Error getting Wi-Fi IP: " + e.getMessage());
            }
        }

        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                if (intf.isLoopback() || !intf.isUp()) continue;
                String name = intf.getName().toLowerCase(Locale.US);
                if (!name.contains("wlan") && !name.contains("ap")) continue;
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}

        return "0.0.0.0";
    }

    public static boolean isHcuSubnet(String ip) {
        return ip != null && (ip.startsWith("10.") || ip.startsWith("192.168.56."));
    }

    public static boolean isConnectedToHcuSubnet(Context context) {
        String ip = getLocalWifiIpAddress(context);
        return isHcuSubnet(ip);
    }

    /**
     * Tells Android OS that network connectivity has been established.
     * This prompts Android to dismiss the system "Sign in to a Wi-Fi network" notification.
     */
    public static void reportOnlineToAndroid(Context context, Network network) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network net = (network != null) ? network : getWifiNetwork(context);
            if (cm != null && net != null) {
                cm.reportNetworkConnectivity(net, true);
                Log.d(TAG, "Reported network connectivity=true to Android OS for " + net);
            }
        } catch (Exception e) {
            Log.w(TAG, "reportNetworkConnectivity error: " + e.getMessage());
        }
    }

    /**
     * Probes internet status strictly bound to the Wi-Fi network interface.
     */
    public static boolean isOnline(Network network, Context context) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(PROBE_URL);
            conn = openWifiConnection(network, context, url);
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setUseCaches(false);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:109.0)");
            int code = conn.getResponseCode();
            return code == 204;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    public static boolean isOnline() {
        return isOnline(null, null);
    }

    public static AuthResult probeConnectivity(Context context) {
        return probeConnectivity(context, null);
    }

    public static AuthResult probeConnectivity(Context context, Network network) {
        String ip = getLocalWifiIpAddress(context);
        boolean inHcu = isHcuSubnet(ip);
        boolean online = isOnline(network, context);

        if (online) {
            reportOnlineToAndroid(context, network);
            return new AuthResult(AuthResult.Status.ALREADY_ONLINE,
                    "Online (Internet Active) - Wi-Fi IP: " + ip, null, null);
        } else if (inHcu) {
            return new AuthResult(AuthResult.Status.NOT_IN_HCU_SUBNET,
                    "HCU Captive Portal - Login Required (IP: " + ip + ")", null, null);
        } else {
            return new AuthResult(AuthResult.Status.NOT_IN_HCU_SUBNET,
                    "Wi-Fi Connected (IP: " + ip + ") - Portal login needed", null, null);
        }
    }

    public static AuthResult loginSync(Context context) {
        return loginSync(context, null, null, null);
    }

    public static AuthResult loginSync(Context context, Network network) {
        return loginSync(context, network, null, null);
    }

    /**
     * Core synchronous login execution.
     * Prevents overlapping requests using IS_AUTHENTICATING lock.
     */
    public static AuthResult loginSync(Context context, Network network, String customUsername, String customPassword) {
        if (!IS_AUTHENTICATING.compareAndSet(false, true)) {
            return new AuthResult(AuthResult.Status.BUSY, "Authentication already in progress", null, null);
        }

        try {
            String username = (customUsername != null && !customUsername.isEmpty())
                    ? customUsername : getSavedUsername(context);
            String password = (customPassword != null && !customPassword.isEmpty())
                    ? customPassword : getSavedPassword(context);

            if (username.isEmpty() || password.isEmpty()) {
                return new AuthResult(AuthResult.Status.INVALID_CREDENTIALS,
                        "Credentials missing: Enter username and password", null, null);
            }

            Set<String> gatewaysToTry = new LinkedHashSet<>();
            gatewaysToTry.add(PRIMARY_GATEWAY);  // 192.168.56.2 (Active Sophos Portal)
            gatewaysToTry.add(FALLBACK_GATEWAY); // 10.4.0.1 (Secondary Gateway)

            String lastError = "";

            for (String gateway : gatewaysToTry) {
                try {
                    Log.d(TAG, "Attempting Wi-Fi login against: " + gateway + ":" + PORT);
                    AuthResult res = executeAuthRequest(network, context, gateway, username, password, true);

                    if (res != null) {
                        if (res.isSuccess()) {
                            // Tell Android OS that internet is active so "Sign-in required" is cleared
                            reportOnlineToAndroid(context, network);
                            return res;
                        }

                        // Stop immediately on invalid credentials to prevent account lockout
                        if (res.status == AuthResult.Status.INVALID_CREDENTIALS) {
                            return res;
                        }

                        lastError = res.message;
                    }
                } catch (Exception e) {
                    lastError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    Log.w(TAG, "Gateway " + gateway + " connection failed: " + lastError);
                }
            }

            return new AuthResult(AuthResult.Status.GATEWAY_UNREACHABLE,
                    "Could not connect to portal. Last error: " + lastError, null, null);

        } finally {
            IS_AUTHENTICATING.set(false);
        }
    }

    public static AuthResult logoutSync(Context context) {
        return logoutSync(context, null);
    }

    public static AuthResult logoutSync(Context context, Network network) {
        String username = getSavedUsername(context);
        return executeAuthRequest(network, context, PRIMARY_GATEWAY, username, "", false);
    }

    private static AuthResult executeAuthRequest(Network network, Context context, String gatewayIp,
                                                String username, String password, boolean isLogin) {
        HttpURLConnection conn = null;
        try {
            long timestamp = System.currentTimeMillis();
            String postBody;
            if (isLogin) {
                postBody = "mode=191"
                        + "&username=" + URLEncoder.encode(username, "UTF-8")
                        + "&password=" + URLEncoder.encode(password, "UTF-8")
                        + "&a=" + timestamp
                        + "&producttype=0";
            } else {
                postBody = "mode=192"
                        + "&username=" + URLEncoder.encode(username, "UTF-8")
                        + "&a=" + timestamp
                        + "&producttype=0";
            }

            byte[] postData = postBody.getBytes(StandardCharsets.UTF_8);
            URL url = new URL("http://" + gatewayIp + ":" + PORT + AUTH_PATH);

            conn = openWifiConnection(network, context, url);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(3500);
            conn.setReadTimeout(3500);
            conn.setUseCaches(false);

            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("Content-Length", String.valueOf(postData.length));
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:109.0)");
            conn.setRequestProperty("Origin", "http://" + gatewayIp + ":" + PORT);
            conn.setRequestProperty("Referer", "http://" + gatewayIp + ":" + PORT + "/httpclient.html");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(postData);
                os.flush();
            }

            int responseCode = conn.getResponseCode();
            InputStream is = (responseCode >= 200 && responseCode < 400) ? conn.getInputStream() : conn.getErrorStream();
            String responseBody = "";
            if (is != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append("\n");
                    }
                    responseBody = sb.toString();
                }
            }

            if (isLogin) {
                String lower = responseBody.toLowerCase(Locale.US);

                if (lower.contains("invalid username or password") ||
                    lower.contains("authentication failed") ||
                    lower.contains("wrong username") ||
                    lower.contains("invalid password")) {
                    return new AuthResult(AuthResult.Status.INVALID_CREDENTIALS,
                            "Invalid credentials: Check username & password", responseBody, gatewayIp);
                }

                // Explicit rejection: student has reached maximum simultaneous devices
                if (lower.contains("maximum login limit") || lower.contains("maximum login")) {
                    return new AuthResult(AuthResult.Status.INVALID_CREDENTIALS,
                            "Login rejected: Maximum login limit reached on another device", responseBody, gatewayIp);
                }

                if (lower.contains("data transfer has been exceeded") || lower.contains("quota exceeded")) {
                    return new AuthResult(AuthResult.Status.ERROR,
                            "HCU Portal: Data quota exceeded", responseBody, gatewayIp);
                }

                // Verified successful authentication markers from Sophos CDATA
                if (lower.contains("status><![cdata[live]]>") ||
                    lower.contains("status>live") ||
                    lower.contains("welcome! you have successfully") ||
                    lower.contains("successfully logged in") ||
                    lower.contains("you are signed in")) {
                    return new AuthResult(AuthResult.Status.SUCCESS, "Signed in successfully!", responseBody, gatewayIp);
                }

                if (responseCode == 200 && isOnline(network, context)) {
                    return new AuthResult(AuthResult.Status.SUCCESS, "Signed in (HTTP 200 confirmed)!", responseBody, gatewayIp);
                }

                return new AuthResult(AuthResult.Status.ERROR, "Portal response: HTTP " + responseCode, responseBody, gatewayIp);
            } else {
                return new AuthResult(AuthResult.Status.SUCCESS, "Logged out successfully.", responseBody, gatewayIp);
            }

        } catch (Exception e) {
            return new AuthResult(AuthResult.Status.GATEWAY_UNREACHABLE,
                    "Connection error to " + gatewayIp + ": " + e.getMessage(), null, gatewayIp);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    public static void loginAsync(Context context, AuthCallback callback) {
        loginAsync(context, null, null, null, callback);
    }

    public static void loginAsync(Context context, Network network, String u, String p, AuthCallback callback) {
        EXECUTOR.execute(() -> {
            AuthResult result = loginSync(context, network, u, p);
            MAIN_HANDLER.post(() -> {
                if (callback != null) callback.onComplete(result);
            });
        });
    }

    public static void logoutAsync(Context context, AuthCallback callback) {
        EXECUTOR.execute(() -> {
            AuthResult result = logoutSync(context);
            MAIN_HANDLER.post(() -> {
                if (callback != null) callback.onComplete(result);
            });
        });
    }

    public static void probeAsync(Context context, AuthCallback callback) {
        probeAsync(context, null, callback);
    }

    public static void probeAsync(Context context, Network network, AuthCallback callback) {
        EXECUTOR.execute(() -> {
            AuthResult result = probeConnectivity(context, network);
            MAIN_HANDLER.post(() -> {
                if (callback != null) callback.onComplete(result);
            });
        });
    }
}
