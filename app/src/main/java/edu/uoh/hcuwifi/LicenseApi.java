package edu.uoh.hcuwifi;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Thin HTTPS client for the licensing backend (PRD 11.2). Plain HttpURLConnection, no libraries.
 *
 * Rules that keep licensing from ever hurting the login path:
 *  - it is only used from licence code and the unlock screen, never from the Wi-Fi sign-in flow;
 *  - every call is bound to a network that has VALIDATED internet, so a captive campus Wi-Fi that is
 *    not signed in yet is never used (mobile data is picked instead), and with no such network the
 *    call returns {@link Reply#noInternet()} immediately instead of hanging;
 *  - short timeouts, bounded response size, HTTPS only, no redirects.
 */
final class LicenseApi {

    private static final String TAG = "HcuLicenseApi";
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 10000;
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;

    private LicenseApi() {}

    /** Result of one call. */
    static final class Reply {
        static final int NO_INTERNET = -1;
        static final int NETWORK_ERROR = -2;
        static final int NOT_CONFIGURED = -3;

        /** HTTP status, or one of the negative codes above. */
        final int code;
        /** Parsed JSON body, or null when absent or not JSON (for example a captive-portal page). */
        final JSONObject json;

        Reply(int code, JSONObject json) {
            this.code = code;
            this.json = json;
        }

        static Reply noInternet() { return new Reply(NO_INTERNET, null); }

        boolean isOk() { return code == 200 && json != null; }
        boolean isNoInternet() { return code == NO_INTERNET; }
        boolean isRateLimited() { return code == 429; }

        String string(String key) { return json == null ? "" : json.optString(key, ""); }
    }

    static Reply status(Context context, String installId) {
        return call(context, "GET", "/status?device=" + encode(installId), null);
    }

    static Reply pay(Context context, String installId) {
        return call(context, "GET", "/pay?device=" + encode(installId), null);
    }

    static Reply restore(Context context, String installId, String phone) {
        try {
            JSONObject body = new JSONObject().put("device", installId).put("phone", phone);
            return call(context, "POST", "/restore", body.toString());
        } catch (JSONException e) {
            return new Reply(Reply.NETWORK_ERROR, null);
        }
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static Reply call(Context context, String method, String path, String jsonBody) {
        String base = LicenseManager.apiBase(context);
        if (!base.startsWith("https://")) return new Reply(Reply.NOT_CONFIGURED, null);

        Network network = pickInternetNetwork(context);
        if (network == null) return Reply.noInternet();

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) network.openConnection(new URL(base + path));
            connection.setRequestMethod(method);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "WifiAutoLogin-App");
            if (jsonBody != null) {
                byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(bytes);
                }
            }
            int code = connection.getResponseCode();
            InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            return new Reply(code, parse(readLimited(stream)));
        } catch (IOException e) {
            Log.w(TAG, method + " " + path.split("\\?")[0] + " failed: " + e.getClass().getSimpleName());
            return new Reply(Reply.NETWORK_ERROR, null);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String readLimited(InputStream stream) throws IOException {
        if (stream == null) return "";
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (out.size() > MAX_RESPONSE_BYTES) throw new IOException("response too large");
            }
            return out.toString("UTF-8");
        }
    }

    private static JSONObject parse(String text) {
        if (text == null || text.isEmpty()) return null;
        try {
            return new JSONObject(text);
        } catch (JSONException e) {
            return null;   // e.g. an HTML page from a captive portal: treated as "no usable answer"
        }
    }

    /**
     * A network with working, validated internet: the default one when it qualifies, otherwise any
     * other (typically mobile data while campus Wi-Fi is still waiting for portal sign-in).
     */
    @SuppressWarnings("deprecation")
    private static Network pickInternetNetwork(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return null;
            Network active = cm.getActiveNetwork();
            if (hasValidatedInternet(cm, active)) return active;
            for (Network candidate : cm.getAllNetworks()) {
                if (hasValidatedInternet(cm, candidate)) return candidate;
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not look up a validated network: " + e.getMessage());
        }
        return null;
    }

    private static boolean hasValidatedInternet(ConnectivityManager cm, Network network) {
        if (network == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        return caps != null
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }
}
