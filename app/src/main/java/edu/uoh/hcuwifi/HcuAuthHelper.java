package edu.uoh.hcuwifi;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
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

    // Default Credentials
    public static final String DEFAULT_USERNAME = "26mnms10";
    public static final String DEFAULT_PASSWORD = "01smnm6230102025@#dP";

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
     * Determines whether the active network is connected to Wi-Fi.
     */
    public static boolean isWifiConnected(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            NetworkInfo active = cm.getActiveNetworkInfo();
            return active != null && active.isConnected() && active.getType() == ConnectivityManager.TYPE_WIFI;
        } catch (Exception e) {
            Log.e(TAG, "Error checking Wi-Fi connection", e);
            return false;
        }
    }

    /**
     * Gets the device's local IPv4 address across all active network interfaces.
     */
    public static String getLocalIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                if (intf.isLoopback() || !intf.isUp()) continue;
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress()) {
                        String sAddr = addr.getHostAddress();
                        if (sAddr != null && sAddr.indexOf(':') < 0) {
                            // IPv4 address found
                            return sAddr;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error retrieving local IP", e);
        }
        return "Unknown";
    }

    /**
     * Checks if given IP matches HCU Subnet pattern (10.4.*.*).
     */
    public static boolean isHcuSubnet(String ip) {
        return ip != null && (ip.startsWith("10.") || ip.startsWith("192.168.56."));
    }

    /**
     * Checks if current device IP is in the HCU Wi-Fi subnet.
     */
    public static boolean isConnectedToHcuSubnet() {
        String ip = getLocalIpAddress();
        return isHcuSubnet(ip);
    }

    /**
     * Probes Google's generate_204 endpoint to verify internet connectivity.
     * Plain HTTP is used; captive portals intercept or redirect this request.
     * Returns true if genuine HTTP 204 No Content is received.
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

    /**
     * Synchronous probe returning an AuthResult.
     */
    public static AuthResult probeConnectivity() {
        String ip = getLocalIpAddress();
        boolean inHcu = isHcuSubnet(ip);
        boolean online = isOnline();

        if (online) {
            return new AuthResult(AuthResult.Status.ALREADY_ONLINE,
                    "Online (HTTP 204 OK) - Local IP: " + ip, null, null);
        } else if (inHcu) {
            return new AuthResult(AuthResult.Status.NOT_IN_HCU_SUBNET,
                    "Captive Portal Login Required (Subnet 10.4.*.* detected, IP: " + ip + ")", null, null);
        } else {
            return new AuthResult(AuthResult.Status.NOT_IN_HCU_SUBNET,
                    "Not in HCU Subnet (Local IP: " + ip + ")", null, null);
        }
    }

    /**
     * Synchronous login routine.
     * 1. Checks if already online.
     * 2. Encodes credentials (URL-encoding special characters like @, #).
     * 3. Sends POST to primary gateway (192.168.56.2:8090).
     * 4. Fails over to fallback gateway (10.4.0.1:8090) if primary times out.
     * 5. Parses response and re-probes connectivity.
     */
    public static AuthResult loginSync(Context context) {
        String username = getSavedUsername(context);
        String password = getSavedPassword(context);
        return loginSync(username, password);
    }

    public static AuthResult loginSync(String username, String password) {
        // Fast path: already authenticated
        if (isOnline()) {
            return new AuthResult(AuthResult.Status.ALREADY_ONLINE,
                    "Already signed in and online", null, null);
        }

        String[] gateways = new String[]{PRIMARY_GATEWAY, FALLBACK_GATEWAY};
        String lastError = "";

        for (String gateway : gateways) {
            try {
                AuthResult res = executeAuthRequest(gateway, username, password, true);
                if (res != null) {
                    return res;
                }
            } catch (Exception e) {
                lastError = e.getMessage();
                Log.w(TAG, "Gateway " + gateway + " failed: " + lastError);
            }
        }

        // Final check in case connection succeeded despite HTTP status quirks
        if (isOnline()) {
            return new AuthResult(AuthResult.Status.SUCCESS,
                    "Login verified via internet probe (204 OK)", null, PRIMARY_GATEWAY);
        }

        return new AuthResult(AuthResult.Status.GATEWAY_UNREACHABLE,
                "Both gateways (192.168.56.2 & 10.4.0.1) unreachable: " + lastError, null, null);
    }

    /**
     * Synchronous logout routine.
     */
    public static AuthResult logoutSync(Context context) {
        String username = getSavedUsername(context);
        return logoutSync(username);
    }

    public static AuthResult logoutSync(String username) {
        String[] gateways = new String[]{PRIMARY_GATEWAY, FALLBACK_GATEWAY};
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
     * Note: Plain HTTP on port 8090 is strictly required (HTTPS causes 502).
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
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:109.0) Gecko/109.0 Firefox/119.0");

            // Build POST payload with proper URL encoding for special characters
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

            Log.d(TAG, "Portal response (" + responseCode + "): " + responseBody);

            if (isLogin) {
                // Check response tokens
                if (responseBody.contains("You are signed in") ||
                    responseBody.contains("signed in") ||
                    responseBody.contains("<status>LIVE</status>") ||
                    responseBody.contains("LIVE") ||
                    responseBody.contains("Login Successful")) {
                    return new AuthResult(AuthResult.Status.SUCCESS, "Signed in successfully!", responseBody, gatewayIp);
                } else if (responseBody.contains("Invalid username or password") ||
                           responseBody.contains("Authentication failed") ||
                           responseBody.contains("Credentials")) {
                    return new AuthResult(AuthResult.Status.INVALID_CREDENTIALS,
                            "Invalid HCU credentials. Please check username/password.", responseBody, gatewayIp);
                } else if (responseBody.contains("Maximum Login Limit") || responseBody.contains("data transfer has been exceeded")) {
                    return new AuthResult(AuthResult.Status.ERROR,
                            "HCU Portal Error: Quota or session limit reached.", responseBody, gatewayIp);
                }

                // If response wasn't conclusive, test connectivity probe
                if (isOnline()) {
                    return new AuthResult(AuthResult.Status.SUCCESS, "Signed in successfully (probe verified)!", responseBody, gatewayIp);
                }

                return new AuthResult(AuthResult.Status.ERROR, "Unexpected portal response: " + responseCode, responseBody, gatewayIp);
            } else {
                // Logout response
                return new AuthResult(AuthResult.Status.SUCCESS, "Logged out successfully.", responseBody, gatewayIp);
            }

        } finally {
            conn.disconnect();
        }
    }

    /**
     * Asynchronous login on background executor, delivers result on UI thread.
     */
    public static void loginAsync(Context context, AuthCallback callback) {
        EXECUTOR.execute(() -> {
            AuthResult result = loginSync(context);
            MAIN_HANDLER.post(() -> {
                if (callback != null) callback.onComplete(result);
            });
        });
    }

    /**
     * Asynchronous logout on background executor, delivers result on UI thread.
     */
    public static void logoutAsync(Context context, AuthCallback callback) {
        EXECUTOR.execute(() -> {
            AuthResult result = logoutSync(context);
            MAIN_HANDLER.post(() -> {
                if (callback != null) callback.onComplete(result);
            });
        });
    }

    /**
     * Asynchronous connectivity probe on background executor, delivers result on UI thread.
     */
    public static void probeAsync(AuthCallback callback) {
        EXECUTOR.execute(() -> {
            AuthResult result = probeConnectivity();
            MAIN_HANDLER.post(() -> {
                if (callback != null) callback.onComplete(result);
            });
        });
    }
}
