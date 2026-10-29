package edu.uoh.hcuwifi;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.RouteInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ultra-lightweight authentication helper for University of Hyderabad (HCU) Sophos Captive Portal.
 * Uses zero external libraries (pure java.net.HttpURLConnection) to keep the app sub-100 KB.
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

    // Default Credentials (empty for fresh installs so any student can enter their own credentials)
    public static final String DEFAULT_USERNAME = "";
    public static final String DEFAULT_PASSWORD = "";

    // SharedPreferences Config
    public static final String PREFS_NAME = "hcu_wifi_prefs";
    public static final String KEY_USERNAME = "pref_username";
    public static final String KEY_PASSWORD = "pref_password";
    public static final String KEY_AUTOLOGIN = "pref_autologin";

    // Single background worker thread
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private HcuAuthHelper() {
        // Utility class
    }

    public static class AuthResult {
        public enum Status {
            SUCCESS,
            ALREADY_ONLINE,
            INVALID_CREDENTIALS,
            GATEWAY_UNREACHABLE,
            NOT_IN_HCU_SUBNET,
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
        return getPrefs(context).getString(KEY_USERNAME, DEFAULT_USERNAME);
    }

    public static String getSavedPassword(Context context) {
        return getPrefs(context).getString(KEY_PASSWORD, DEFAULT_PASSWORD);
    }

    public static boolean isAutoLoginEnabled(Context context) {
        return getPrefs(context).getBoolean(KEY_AUTOLOGIN, true);
    }

    public static void saveConfig(Context context, String username, String password, boolean autoLogin) {
        getPrefs(context).edit()
                .putString(KEY_USERNAME, username.trim())
                .putString(KEY_PASSWORD, password)
                .putBoolean(KEY_AUTOLOGIN, autoLogin)
                .apply();
    }

    /**
     * Binds all sockets from this application strictly to the Wi-Fi interface.
     * Prevents Android from routing HTTP traffic over Cellular Mobile Data (4G/5G).
     */
    public static boolean bindToWifi(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && context != null) {
            try {
                ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    for (Network net : cm.getAllNetworks()) {
                        NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                            cm.bindProcessToNetwork(net);
                            Log.d(TAG, "Process successfully bound to Wi-Fi: " + net);
                            return true;
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to bind to Wi-Fi: " + e.getMessage());
            }
        }
        return false;
    }

    /**
     * Checks if Wi-Fi network is currently connected.
     */
    public static boolean isWifiConnected(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network net = cm.getActiveNetwork();
            if (net == null) {
                // Check all networks in case active network is cellular while Wi-Fi is captive
                for (Network n : cm.getAllNetworks()) {
                    NetworkCapabilities c = cm.getNetworkCapabilities(n);
                    if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return true;
                }
                return false;
            }
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } else {
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected() && info.getType() == ConnectivityManager.TYPE_WIFI;
        }
    }

    /**
     * Gets the Wi-Fi IPv4 address specifically, ignoring cellular (rmnet) interfaces.
     */
    public static String getLocalWifiIpAddress(Context context) {
        if (context != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    for (Network net : cm.getAllNetworks()) {
                        NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                            LinkProperties lp = cm.getLinkProperties(net);
                            if (lp != null) {
                                for (LinkAddress la : lp.getLinkAddresses()) {
                                    InetAddress addr = la.getAddress();
                                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                                        return addr.getHostAddress();
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Error getting Wi-Fi IP via ConnectivityManager: " + e.getMessage());
            }
        }

        // Fallback: iterate interfaces prioritizing wlan/ap
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

        return getLocalIpAddress();
    }

    public static String getLocalIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                if (intf.isLoopback() || !intf.isUp()) continue;
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error retrieving local IP", e);
        }
        return "Unknown";
    }

    /**
     * Resolves the default router / gateway IP assigned to the Wi-Fi connection.
     */
    public static String getWifiGatewayIp(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null;
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                for (Network net : cm.getAllNetworks()) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        LinkProperties lp = cm.getLinkProperties(net);
                        if (lp != null) {
                            for (RouteInfo route : lp.getRoutes()) {
                                if (route.isDefaultRoute() && route.getGateway() != null) {
                                    String gw = route.getGateway().getHostAddress();
                                    if (gw != null && !gw.equals("0.0.0.0")) {
                                        return gw;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error resolving Wi-Fi gateway: " + e.getMessage());
        }
        return null;
    }

    /**
     * Checks if given IP matches any HCU campus subnet (10.*.*.* or 192.168.56.*).
     */
    public static boolean isHcuSubnet(String ip) {
        return ip != null && (ip.startsWith("10.") || ip.startsWith("192.168.56."));
    }

    public static boolean isConnectedToHcuSubnet(Context context) {
        String ip = getLocalWifiIpAddress(context);
        return isHcuSubnet(ip);
    }

    public static boolean isConnectedToHcuSubnet() {
        return isHcuSubnet(getLocalIpAddress());
    }

    /**
     * Probes Google's generate_204 endpoint to verify internet connectivity.
     * Note: Plain HTTP is used; captive portals intercept or redirect this request.
     */
    public static boolean isOnline() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(PROBE_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setUseCaches(false);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:109.0)");
            int code = conn.getResponseCode();
            return code == 204;
        } catch (Exception e) {
            Log.d(TAG, "isOnline probe failed: " + e.getMessage());
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    public static AuthResult probeConnectivity(Context context) {
        bindToWifi(context);
        String ip = getLocalWifiIpAddress(context);
        boolean inHcu = isHcuSubnet(ip);
        boolean online = isOnline();

        if (online) {
            return new AuthResult(AuthResult.Status.ALREADY_ONLINE,
                    "Online (HTTP 204 OK) - Wi-Fi IP: " + ip, null, null);
        } else if (inHcu) {
            return new AuthResult(AuthResult.Status.NOT_IN_HCU_SUBNET,
                    "Captive Portal Login Required (IP: " + ip + ")", null, null);
        } else {
            return new AuthResult(AuthResult.Status.NOT_IN_HCU_SUBNET,
                    "Wi-Fi Connected (IP: " + ip + ") - Portal authentication needed", null, null);
        }
    }

    /**
     * Primary synchronous login pipeline.
     * 1. Binds process to Wi-Fi so traffic never leaks through cellular mobile data.
     * 2. Sends HTTP POST to primary gateway (192.168.56.2:8090).
     * 3. Falls back to 10.4.0.1:8090 and dynamic router gateway if needed.
     * 4. Treats "LIVE", "successfully logged in", and "Maximum login limit" as success.
     */
    public static AuthResult loginSync(Context context) {
        bindToWifi(context);
        String username = getSavedUsername(context);
        String password = getSavedPassword(context);

        Set<String> gatewaysToTry = new LinkedHashSet<>();
        gatewaysToTry.add(PRIMARY_GATEWAY);
        gatewaysToTry.add(FALLBACK_GATEWAY);

        String dynamicGw = getWifiGatewayIp(context);
        if (dynamicGw != null && !dynamicGw.isEmpty()) {
            gatewaysToTry.add(dynamicGw);
        }

        String lastError = "";

        for (String gateway : gatewaysToTry) {
            try {
                Log.d(TAG, "Attempting login against gateway: " + gateway + ":" + PORT);
                AuthResult res = executeAuthRequest(gateway, username, password, true);
                if (res != null && res.isSuccess()) {
                    return res;
                }
                if (res != null && res.status == AuthResult.Status.INVALID_CREDENTIALS) {
                    return res;
                }
                if (res != null) {
                    lastError = res.message;
                }
            } catch (Exception e) {
                lastError = e.getMessage();
                Log.w(TAG, "Gateway " + gateway + " failed: " + lastError);
            }
        }

        // Final verification check
        if (isOnline()) {
            return new AuthResult(AuthResult.Status.SUCCESS,
                    "Login verified via internet probe (204 OK)", null, PRIMARY_GATEWAY);
        }

        return new AuthResult(AuthResult.Status.GATEWAY_UNREACHABLE,
                "Could not reach Sophos portal (tried: " + gatewaysToTry + "): " + lastError, null, null);
    }

    public static AuthResult logoutSync(Context context) {
        bindToWifi(context);
        String username = getSavedUsername(context);

        Set<String> gateways = new LinkedHashSet<>();
        gateways.add(PRIMARY_GATEWAY);
        gateways.add(FALLBACK_GATEWAY);
        String dynamicGw = getWifiGatewayIp(context);
        if (dynamicGw != null) gateways.add(dynamicGw);

        for (String gateway : gateways) {
            try {
                AuthResult res = executeAuthRequest(gateway, username, "", false);
                if (res != null) {
                    return res;
                }
            } catch (Exception e) {
                Log.w(TAG, "Logout on " + gateway + " failed: " + e.getMessage());
            }
        }
        return new AuthResult(AuthResult.Status.GATEWAY_UNREACHABLE,
                "Failed to reach Sophos gateway for logout", null, null);
    }

    /**
     * Executes the HTTP POST request to Sophos captive portal endpoint.
     */
    private static AuthResult executeAuthRequest(String gatewayIp, String username, String password, boolean isLogin) throws Exception {
        String targetUrl = "http://" + gatewayIp + ":" + PORT + AUTH_PATH;
        URL url = new URL(targetUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setDoInput(true);
            conn.setUseCaches(false);
            conn.setConnectTimeout(3500);
            conn.setReadTimeout(3500);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("Referer", targetUrl);
            conn.setRequestProperty("Origin", "http://" + gatewayIp + ":" + PORT);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:109.0) Gecko/109.0 Firefox/119.0");

            long timestamp = System.currentTimeMillis();
            StringBuilder body = new StringBuilder();
            if (isLogin) {
                body.append("mode=191")
                    .append("&username=").append(URLEncoder.encode(username, "UTF-8"))
                    .append("&password=").append(URLEncoder.encode(password, "UTF-8"))
                    .append("&a=").append(timestamp)
                    .append("&producttype=0");
            } else {
                body.append("mode=193")
                    .append("&username=").append(URLEncoder.encode(username, "UTF-8"))
                    .append("&a=").append(timestamp)
                    .append("&producttype=0");
            }

            byte[] postData = body.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(postData.length);

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

            Log.d(TAG, "Portal response (" + responseCode + ") from " + gatewayIp + ": " + responseBody);

            if (isLogin) {
                String lower = responseBody.toLowerCase(Locale.US);

                // Success tokens matching Sophos XG / Cyberoam firmware
                if (lower.contains("live") ||
                    lower.contains("you are signed in") ||
                    lower.contains("successfully logged in") ||
                    lower.contains("login successful") ||
                    lower.contains("status>live")) {
                    return new AuthResult(AuthResult.Status.SUCCESS, "Signed in successfully!", responseBody, gatewayIp);
                }

                // Session already active / maximum login limit reached
                if (lower.contains("maximum login limit") || lower.contains("maximum login")) {
                    return new AuthResult(AuthResult.Status.SUCCESS, "Session active (Maximum login limit).", responseBody, gatewayIp);
                }

                if (lower.contains("invalid username or password") ||
                    lower.contains("authentication failed") ||
                    lower.contains("wrong username")) {
                    return new AuthResult(AuthResult.Status.INVALID_CREDENTIALS,
                            "Invalid credentials: Check username & password", responseBody, gatewayIp);
                }

                if (lower.contains("data transfer has been exceeded")) {
                    return new AuthResult(AuthResult.Status.ERROR,
                            "HCU Portal Error: Data quota exceeded", responseBody, gatewayIp);
                }

                // If response code is 200 and probe passes, consider success
                if (responseCode == 200 && isOnline()) {
                    return new AuthResult(AuthResult.Status.SUCCESS, "Signed in (HTTP 200 confirmed)!", responseBody, gatewayIp);
                }

                return new AuthResult(AuthResult.Status.ERROR, "Portal response code: " + responseCode, responseBody, gatewayIp);
            } else {
                return new AuthResult(AuthResult.Status.SUCCESS, "Logged out successfully.", responseBody, gatewayIp);
            }

        } finally {
            conn.disconnect();
        }
    }

    public static void loginAsync(Context context, AuthCallback callback) {
        EXECUTOR.execute(() -> {
            AuthResult result = loginSync(context);
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
        EXECUTOR.execute(() -> {
            AuthResult result = probeConnectivity(context);
            MAIN_HANDLER.post(() -> {
                if (callback != null) callback.onComplete(result);
            });
        });
    }
}
