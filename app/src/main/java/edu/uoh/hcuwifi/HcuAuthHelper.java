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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.net.URI;
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
    public static final String AUTH_PATH = "/login.xml";

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
            if (cm == null) return null;

            // Prefer Android's default network when it is Wi-Fi. This prevents selecting
            // an old/stale Wi-Fi Network while the phone is using another transport.
            Network active = cm.getActiveNetwork();
            NetworkCapabilities activeCaps = active != null ? cm.getNetworkCapabilities(active) : null;
            if (activeCaps != null && activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return active;
            }

            // A captive Wi-Fi network may not be the default route while mobile data is
            // validated. Select only a currently-present Wi-Fi network with an IPv4 address.
            Network fallback = null;
            for (Network candidate : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(candidate);
                if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
                LinkProperties lp = cm.getLinkProperties(candidate);
                if (lp == null) continue;
                boolean hasIpv4 = false;
                for (LinkAddress address : lp.getLinkAddresses()) {
                    InetAddress ip = address.getAddress();
                    if (ip instanceof Inet4Address && !ip.isLoopbackAddress()
                            && !ip.isLinkLocalAddress() && !"0.0.0.0".equals(ip.getHostAddress())) {
                        hasIpv4 = true;
                        break;
                    }
                }
                if (!hasIpv4) continue;
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return candidate;
                if (fallback == null) fallback = candidate;
            }
            return fallback;
        } catch (Exception e) {
            Log.w(TAG, "Error resolving Wi-Fi network", e);
            return null;
        }
    }

    public static boolean isWifiNetwork(Context context, Network network) {
        if (context == null || network == null) return false;
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities caps = cm != null ? cm.getNetworkCapabilities(network) : null;
            return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isNetworkValidated(Context context, Network network) {
        if (context == null || network == null) return false;
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities caps = cm != null ? cm.getNetworkCapabilities(network) : null;
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Exception e) {
            return false;
        }
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

    /**
     * Compatibility helper. Requests are bound individually with Network.openConnection();
     * process-wide binding is deliberately avoided so background app traffic is not trapped
     * on a captive Wi-Fi network or sent over the wrong network after a handoff.
     */
    public static boolean bindToWifi(Context context) {
        return getWifiNetwork(context) != null;
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
        return getLocalWifiIpAddress(context, getWifiNetwork(context));
    }

    /** Resolve the address from the exact Wi-Fi network that raised the callback. */
    public static String getLocalWifiIpAddress(Context context, Network preferredNetwork) {
        if (context != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                Network wifiNet = preferredNetwork != null ? preferredNetwork : getWifiNetwork(context);
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

        // Fallback only inspects Wi-Fi interfaces; never returns a cellular address.
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                if (intf.isLoopback() || !intf.isUp()) continue;
                String name = intf.getName().toLowerCase(Locale.US);
                if (!name.contains("wlan") && !name.contains("wifi")) continue;
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

    /**
     * Known address ranges observed on HCU Wi-Fi devices. This is only a UI hint;
     * authentication is gated by the actual HCU portal response, not by a broad
     * RFC1918/10.x address test.
     */
    public static boolean isHcuSubnet(String ip) {
        if (ip == null) return false;
        return ip.startsWith("10.1.6.")
                || ip.startsWith("10.4.")
                || ip.startsWith("192.168.56.");
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
            conn.setConnectTimeout(1200);
            conn.setReadTimeout(1200);
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
        Network wifi = network != null ? network : getWifiNetwork(context);
        String ip = getLocalWifiIpAddress(context, wifi);
        boolean inHcu = isHcuSubnet(ip);
        boolean online = isNetworkValidated(context, wifi) || isOnline(wifi, context);

        if (online) {
            reportOnlineToAndroid(context, wifi);
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

    private static final String PORTAL_PAGE_PATH = "/httpclient.html";
    private static final String LOGIN_PATH = "/login.xml";
    private static final String LIVE_PATH = "/live";

    private static final class HttpResult {
        final int code;
        final String body;
        final String cookies;
        final String location;

        HttpResult(int code, String body, String cookies, String location) {
            this.code = code;
            this.body = body != null ? body : "";
            this.cookies = cookies != null ? cookies : "";
            this.location = location != null ? location : "";
        }
    }

    private static HttpResult request(Network network, Context context, URL url,
                                      String method, String body, String cookies,
                                      String referer) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = openWifiConnection(network, context, url);
            conn.setRequestMethod(method);
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(1800);
            conn.setReadTimeout(1800);
            conn.setUseCaches(false);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36");
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml,text/xml,*/*");
            conn.setRequestProperty("Connection", "close");
            if (referer != null && !referer.isEmpty()) {
                conn.setRequestProperty("Referer", referer);
            }
            if (cookies != null && !cookies.isEmpty()) {
                conn.setRequestProperty("Cookie", cookies);
            }
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                conn.setRequestProperty("Content-Length", String.valueOf(bytes.length));
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bytes);
                    os.flush();
                }
            }

            int code = conn.getResponseCode();
            InputStream stream = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
            StringBuilder response = new StringBuilder();
            if (stream != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line).append('\n');
                        if (response.length() > 65536) break;
                    }
                }
            }

            // Header map key casing is implementation-dependent; find Set-Cookie
            // case-insensitively and retain only cookie name/value pairs.
            StringBuilder cookieBuilder = new StringBuilder();
            for (java.util.Map.Entry<String, List<String>> header : conn.getHeaderFields().entrySet()) {
                if (header.getKey() == null || !"set-cookie".equalsIgnoreCase(header.getKey())) continue;
                List<String> values = header.getValue();
                if (values == null) continue;
                for (String setCookie : values) {
                    if (setCookie == null) continue;
                    int semicolon = setCookie.indexOf(';');
                    String pair = (semicolon >= 0 ? setCookie.substring(0, semicolon) : setCookie).trim();
                    if (pair.contains("=")) {
                        if (cookieBuilder.length() > 0) cookieBuilder.append("; ");
                        cookieBuilder.append(pair);
                    }
                }
            }
            return new HttpResult(code, response.toString(), cookieBuilder.toString(), conn.getHeaderField("Location"));
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String mergeCookies(String first, String second) {
        // Replace cookies by name instead of sending duplicate stale values.
        java.util.LinkedHashMap<String, String> values = new java.util.LinkedHashMap<>();
        String combined = (first == null ? "" : first) + ";" + (second == null ? "" : second);
        for (String item : combined.split(";")) {
            String pair = item.trim();
            int equals = pair.indexOf('=');
            if (equals > 0) {
                String name = pair.substring(0, equals).trim();
                String value = pair.substring(equals + 1).trim();
                values.put(name, value);
            }
        }
        StringBuilder result = new StringBuilder();
        for (java.util.Map.Entry<String, String> entry : values.entrySet()) {
            if (result.length() > 0) result.append("; ");
            result.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return result.toString();
    }

    private static String extractXmlTag(String xml, String tag) {
        if (xml == null || xml.isEmpty()) return "";
        Pattern pattern = Pattern.compile("(?is)<" + Pattern.quote(tag) + "(?:\\s[^>]*)?>(.*?)</" + Pattern.quote(tag) + ">");
        Matcher matcher = pattern.matcher(xml);
        if (!matcher.find()) return "";
        String value = matcher.group(1).trim();
        if (value.startsWith("<![CDATA[") && value.endsWith("]]>")) {
            value = value.substring(9, value.length() - 3).trim();
        }
        return value.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");
    }

    private static String chooseLoginPath(String pageHtml, String pageUrl) {
        String lower = pageHtml == null ? "" : pageHtml.toLowerCase(Locale.US);
        // Sophos/Cyberoam client pages commonly submit JavaScript requests to login.xml.
        if (lower.contains("login.xml")) return LOGIN_PATH;

        if (pageHtml != null) {
            Pattern actionPattern = Pattern.compile("(?is)<form\\b[^>]*\\baction\\s*=\\s*(['\"])(.*?)\\1");
            Matcher actionMatcher = actionPattern.matcher(pageHtml);
            if (actionMatcher.find()) {
                String action = actionMatcher.group(2).trim();
                if (!action.isEmpty() && !action.startsWith("#") && !action.toLowerCase(Locale.US).startsWith("javascript:")) {
                    try {
                        URI page = new URI(pageUrl);
                        URI target = page.resolve(action);
                        if (page.getHost().equalsIgnoreCase(target.getHost()) && page.getPort() == target.getPort()) {
                            String path = target.getRawPath();
                            if (path == null || path.isEmpty()) path = LOGIN_PATH;
                            if (target.getRawQuery() != null) path += "?" + target.getRawQuery();
                            return path;
                        }
                    } catch (Exception ignored) {}
                }
            }
        }
        return LOGIN_PATH;
    }

    private static boolean looksLikeInvalidCredentials(String response) {
        String lower = response == null ? "" : response.toLowerCase(Locale.US);
        return lower.contains("invalid username or password")
                || lower.contains("authentication failed")
                || lower.contains("wrong username")
                || lower.contains("invalid password")
                || lower.contains("maximum login limit")
                || lower.contains("maximum login");
    }

    private static boolean isQuotaExceeded(String response) {
        String lower = response == null ? "" : response.toLowerCase(Locale.US);
        return lower.contains("data transfer has been exceeded") || lower.contains("quota exceeded");
    }

    /** Campus VLANs differ by building; order gateways by the address the phone actually got. */
    private static String[] gatewayBasesFor(String localIp) {
        String primary = "http://" + PRIMARY_GATEWAY + ":" + PORT;
        String fallback = "http://" + FALLBACK_GATEWAY + ":" + PORT;
        if (localIp != null && localIp.startsWith("10.4.")) {
            return new String[]{fallback, primary};
        }
        return new String[]{primary, fallback};
    }

    private static final class PortalPage {
        final String base;
        final String pageUrl;
        final HttpResult http;

        PortalPage(String base, String pageUrl, HttpResult http) {
            this.base = base;
            this.pageUrl = pageUrl;
            this.http = http;
        }
    }

    /**
     * Finds the reachable HCU portal among the known gateways. Follows only
     * same-origin redirects; never forwards credentials or cookies elsewhere.
     */
    private static PortalPage fetchPortalPage(Network wifi, Context context, String localIp) {
        for (String base : gatewayBasesFor(localIp)) {
            try {
                String pageUrl = base + PORTAL_PAGE_PATH;
                HttpResult page = request(wifi, context, new URL(pageUrl), "GET", null, "", null);

                if (page.code >= 300 && page.code < 400 && !page.location.isEmpty()) {
                    URL original = new URL(pageUrl);
                    URL redirected = new URL(original, page.location);
                    if (samePortalOrigin(original, redirected)) {
                        pageUrl = redirected.toString();
                        page = request(wifi, context, redirected, "GET", null, page.cookies, original.toString());
                    }
                }

                if (page.code >= 200 && page.code < 300 && looksLikeHcuPortal(page.body)) {
                    return new PortalPage(base, pageUrl, page);
                }
            } catch (Exception e) {
                Log.d(TAG, "Portal not reachable at " + base + ": " + e.getMessage());
            }
        }
        return null;
    }

    public static AuthResult loginSync(Context context) {
        return loginSync(context, null, null, null);
    }

    public static AuthResult loginSync(Context context, Network network) {
        return loginSync(context, network, null, null);
    }

    /**
     * HCU/Sophos flow:
     * 1) GET the portal page to establish cookies and inspect its form/scripts.
     * 2) POST credentials to the authentication endpoint (normally /login.xml).
     * 3) Complete a CHALLENGE response when the portal supplies a state token.
     * 4) Verify access using the actual Wi-Fi network's HTTP 204 probe.
     *
     * A HTTP 200 page alone is never treated as a successful login.
     */
    private static boolean looksLikeHcuPortal(String html) {
        if (html == null || html.trim().isEmpty()) return false;
        String lower = html.toLowerCase(Locale.US);
        boolean vendorMarker = lower.contains("sophos") || lower.contains("cyberoam")
                || lower.contains("login.xml") || lower.contains("mode=191");
        boolean credentialForm = lower.contains("username") && lower.contains("password")
                && lower.contains("login");
        return vendorMarker || credentialForm;
    }

    private static boolean samePortalOrigin(URL original, URL redirect) {
        return original != null && redirect != null
                && "http".equalsIgnoreCase(redirect.getProtocol())
                && original.getHost().equalsIgnoreCase(redirect.getHost())
                && original.getPort() == redirect.getPort();
    }

    public static AuthResult loginSync(Context context, Network network, String customUsername, String customPassword) {
        if (context == null) {
            return new AuthResult(AuthResult.Status.ERROR, "App context is unavailable", null, null);
        }
        if (!IS_AUTHENTICATING.compareAndSet(false, true)) {
            return new AuthResult(AuthResult.Status.BUSY, "Authentication already in progress", null, null);
        }

        try {
            Network wifi = network != null ? network : getWifiNetwork(context);
            if (!isWifiNetwork(context, wifi)) {
                return new AuthResult(AuthResult.Status.GATEWAY_UNREACHABLE,
                        "Wi-Fi is not connected yet", null, null);
            }

            String username = (customUsername != null && !customUsername.isEmpty())
                    ? customUsername.trim() : getSavedUsername(context);
            String password = (customPassword != null && !customPassword.isEmpty())
                    ? customPassword : getSavedPassword(context);
            if (username.isEmpty() || password.isEmpty()) {
                return new AuthResult(AuthResult.Status.INVALID_CREDENTIALS,
                        "Saved username or password is missing. Open the app once to save them.", null, null);
            }

            String ip = getLocalWifiIpAddress(context, wifi);
            // This fast OS signal avoids an unnecessary HTTP exchange on already validated Wi-Fi.
            if (isNetworkValidated(context, wifi)) {
                reportOnlineToAndroid(context, wifi);
                return new AuthResult(AuthResult.Status.ALREADY_ONLINE,
                        "Internet is already active (Wi-Fi IP: " + ip + ")", null, PRIMARY_GATEWAY);
            }

            // Probe the known HCU captive-portal endpoints first. Do not send credentials
            // merely because an unrelated Wi-Fi network happens to use a 10.x address.
            PortalPage portal = fetchPortalPage(wifi, context, ip);
            if (portal == null) {
                return new AuthResult(AuthResult.Status.NOT_IN_HCU_SUBNET,
                        "This Wi-Fi does not expose the HCU login portal. No credentials were sent.",
                        "Portal probe: no HCU/Sophos login page on the known gateways.",
                        PRIMARY_GATEWAY + "," + FALLBACK_GATEWAY);
            }

            String base = portal.base;
            String pageUrl = portal.pageUrl;
            HttpResult page = portal.http;
            String cookies = page.cookies;
            String loginPath = chooseLoginPath(page.body, pageUrl);
            URL pageUri = new URL(pageUrl);
            URL loginUrl = new URL(pageUri, loginPath);
            if (!samePortalOrigin(pageUri, loginUrl)) {
                return new AuthResult(AuthResult.Status.ERROR,
                        "Portal supplied an unexpected login destination; credentials were not sent.",
                        null, base);
            }

            String form = "mode=191"
                    + "&username=" + URLEncoder.encode(username, "UTF-8")
                    + "&password=" + URLEncoder.encode(password, "UTF-8")
                    + "&a=" + System.currentTimeMillis()
                    + "&producttype=0";

            HttpResult login = request(wifi, context, loginUrl, "POST", form, cookies, pageUrl);
            cookies = mergeCookies(cookies, login.cookies);

            String status = extractXmlTag(login.body, "status");
            String state = extractXmlTag(login.body, "state");
            String message = extractXmlTag(login.body, "message");

            if ("CHALLENGE".equalsIgnoreCase(status) && !state.isEmpty()) {
                String challengeForm = form + "&state=" + URLEncoder.encode(state, "UTF-8");
                login = request(wifi, context, loginUrl, "POST", challengeForm, cookies, pageUrl);
                cookies = mergeCookies(cookies, login.cookies);
                status = extractXmlTag(login.body, "status");
                message = extractXmlTag(login.body, "message");
            } else if ("CHALLENGE".equalsIgnoreCase(status)) {
                return new AuthResult(AuthResult.Status.ERROR,
                        "Portal requested a challenge but did not return its state token.",
                        "HTTP " + login.code + "; status=CHALLENGE", base);
            }

            if (looksLikeInvalidCredentials(login.body) || "AUTH_FAILED".equalsIgnoreCase(status)
                    || "FAILED".equalsIgnoreCase(status)) {
                return new AuthResult(AuthResult.Status.INVALID_CREDENTIALS,
                        "HCU portal rejected the saved credentials. Check them in the app.",
                        "HTTP " + login.code + "; status=" + status + "; " + message, base);
            }
            if (isQuotaExceeded(login.body)) {
                return new AuthResult(AuthResult.Status.ERROR, "HCU portal reports a data quota limit",
                        "HTTP " + login.code + "; " + message, base);
            }

            // A portal XML status is not proof of Internet access. Confirm the Wi-Fi path
            // itself; the short bounded check avoids a long fixed sleep after a fast login.
            boolean internetVerified = isNetworkValidated(context, wifi);
            for (int i = 0; !internetVerified && i < 4; i++) {
                internetVerified = isOnline(wifi, context);
                if (!internetVerified && i < 3) {
                    try {
                        Thread.sleep(250);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            String diagnostic = "portal=HTTP " + page.code
                    + ", auth=HTTP " + login.code
                    + ", status=" + (status.isEmpty() ? "n/a" : status)
                    + (message.isEmpty() ? "" : ", message=" + message);

            if (internetVerified) {
                reportOnlineToAndroid(context, wifi);
                return new AuthResult(AuthResult.Status.SUCCESS,
                        "HCU sign-in complete; Internet access verified (IP: " + ip + ")",
                        diagnostic, base);
            }

            return new AuthResult(AuthResult.Status.ERROR,
                    "The portal accepted a request, but Android could not verify Internet access. " + diagnostic,
                    diagnostic, base);

        } catch (Exception e) {
            Log.w(TAG, "HCU authentication failed: " + e.getMessage());
            return new AuthResult(AuthResult.Status.GATEWAY_UNREACHABLE,
                    "HCU portal is not reachable yet: "
                            + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()),
                    null, PRIMARY_GATEWAY + "," + FALLBACK_GATEWAY);
        } finally {
            IS_AUTHENTICATING.set(false);
        }
    }



    public static AuthResult logoutSync(Context context) {
        return logoutSync(context, null);
    }

    public static AuthResult logoutSync(Context context, Network network) {
        String username = getSavedUsername(context);
        if (username.isEmpty()) {
            return new AuthResult(AuthResult.Status.ERROR, "No saved username is available for logout", null, PRIMARY_GATEWAY);
        }
        String ip = getLocalWifiIpAddress(context, network);
        String lastError = null;
        for (String base : gatewayBasesFor(ip)) {
            try {
                String body = "mode=192"
                        + "&username=" + URLEncoder.encode(username, "UTF-8")
                        + "&a=" + System.currentTimeMillis()
                        + "&producttype=0";
                String url = base + AUTH_PATH;
                HttpResult result = request(network, context, new URL(url), "POST", body, "", url);
                return new AuthResult(AuthResult.Status.SUCCESS, "Logout request sent (HTTP " + result.code + ")",
                        result.body, base);
            } catch (Exception e) {
                lastError = e.getMessage();
            }
        }
        return new AuthResult(AuthResult.Status.GATEWAY_UNREACHABLE,
                "Logout request failed: " + lastError, null, PRIMARY_GATEWAY + "," + FALLBACK_GATEWAY);
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
