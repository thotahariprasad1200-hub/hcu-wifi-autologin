package edu.uoh.hcuwifi;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Pure Android Activity for Hari Auto Login (HCU Wi-Fi management and diagnostics).
 * Designed and developed with 🤍 by Hari 🎀.
 * Uses zero external libraries (no androidx) for minimal RAM footprint and sub-50 KB APK.
 */
public class MainActivity extends Activity {

    private TextView tvStatusBadge;
    private TextView tvStatusDetail;
    private TextView tvLocalIp;
    private TextView tvGatewayIp;
    private ProgressBar progressBar;
    private Button btnLogin;
    private Button btnLogout;
    private Button btnProbe;
    private EditText etUsername;
    private EditText etPassword;
    private Switch swAutoLogin;
    private Button btnSaveCreds;
    private TextView tvConsoleLog;
    private TextView btnClearLog;
    private ScrollView scrollLog;

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.US);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        loadSavedPreferences();
        setupListeners();
        setupNetworkCallback();

        // Request notification permission on Android 13+ (API 33+)
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 101);
            }
        }

        // Start permanent background service if auto-login is enabled
        if (HcuAuthHelper.isAutoLoginEnabled(this)) {
            HcuForegroundService.start(this);
            appendLog("Zero-intervention background service active.");
        }

        appendLog("Hari Auto Login started on " + Build.MANUFACTURER + " " + Build.MODEL + " (Android " + Build.VERSION.RELEASE + ")");

        // Check battery optimization for Realme 24/7 reliability
        checkBatteryOptimization();

        HcuAuthHelper.bindToWifi(this);
        refreshStatus();
    }

    private void checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                appendLog("[Note] For uninterrupted background auto-login on Realme/ColorOS, grant battery unrestricted access.");
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        HcuAuthHelper.bindToWifi(this);
        refreshStatus();
    }

    private void bindViews() {
        tvStatusBadge = findViewById(R.id.tv_status_badge);
        tvStatusDetail = findViewById(R.id.tv_status_detail);
        tvLocalIp = findViewById(R.id.tv_local_ip);
        tvGatewayIp = findViewById(R.id.tv_gateway_ip);
        progressBar = findViewById(R.id.progress_bar);
        btnLogin = findViewById(R.id.btn_login);
        btnLogout = findViewById(R.id.btn_logout);
        btnProbe = findViewById(R.id.btn_probe);
        etUsername = findViewById(R.id.et_username);
        etPassword = findViewById(R.id.et_password);
        swAutoLogin = findViewById(R.id.sw_auto_login);
        btnSaveCreds = findViewById(R.id.btn_save_creds);
        tvConsoleLog = findViewById(R.id.tv_console_log);
        btnClearLog = findViewById(R.id.btn_clear_log);
        scrollLog = findViewById(R.id.scroll_log);
    }

    private void loadSavedPreferences() {
        etUsername.setText(HcuAuthHelper.getSavedUsername(this));
        etPassword.setText(HcuAuthHelper.getSavedPassword(this));
        swAutoLogin.setChecked(HcuAuthHelper.isAutoLoginEnabled(this));
        tvGatewayIp.setText(HcuAuthHelper.PRIMARY_GATEWAY + ":" + HcuAuthHelper.PORT);
    }

    private void setupListeners() {
        btnSaveCreds.setOnClickListener(v -> {
            String u = etUsername.getText().toString().trim();
            String p = etPassword.getText().toString();
            boolean auto = swAutoLogin.isChecked();

            if (u.isEmpty()) {
                Toast.makeText(this, "Username cannot be empty", Toast.LENGTH_SHORT).show();
                return;
            }

            HcuAuthHelper.saveConfig(this, u, p, auto);
            if (auto) {
                HcuForegroundService.start(this);
            } else {
                HcuForegroundService.stop(this);
            }
            appendLog("Credentials saved. User: " + u + ", Auto-Login: " + auto);
            Toast.makeText(this, "Settings saved! Authenticating...", Toast.LENGTH_SHORT).show();
            performLogin();
        });

        btnLogin.setOnClickListener(v -> performLogin());

        btnLogout.setOnClickListener(v -> performLogout());

        btnProbe.setOnClickListener(v -> {
            appendLog("Running internet probe (generate_204)...");
            refreshStatus();
        });

        btnClearLog.setOnClickListener(v -> tvConsoleLog.setText(""));
    }

    private void setupNetworkCallback() {
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) return;

        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                runOnUiThread(() -> {
                    appendLog("Wi-Fi connected. Refreshing status...");
                    HcuAuthHelper.bindToWifi(MainActivity.this);
                    refreshStatus();
                    if (swAutoLogin.isChecked() && HcuAuthHelper.isConnectedToHcuSubnet(MainActivity.this)) {
                        appendLog("Auto-login triggered by Wi-Fi connection...");
                        performLogin();
                    }
                });
            }

            @Override
            public void onLost(Network network) {
                runOnUiThread(() -> {
                    appendLog("Wi-Fi network disconnected.");
                    updateStatusDisplay(false, "Wi-Fi Disconnected", "0.0.0.0");
                });
            }
        };
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.registerNetworkCallback(
                        new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                        networkCallback);
            } catch (Exception e) {
                appendLog("Warning: Could not register network callback: " + e.getMessage());
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception e) {
                // Ignore
            }
        }
    }

    private void refreshStatus() {
        setBusy(true);
        HcuAuthHelper.bindToWifi(this);
        HcuAuthHelper.probeAsync(this, result -> {
            setBusy(false);
            String ip = HcuAuthHelper.getLocalWifiIpAddress(this);
            tvLocalIp.setText(ip);

            boolean isOnline = (result.status == HcuAuthHelper.AuthResult.Status.ALREADY_ONLINE);
            boolean inHcu = HcuAuthHelper.isHcuSubnet(ip);

            if (isOnline) {
                HcuAuthHelper.reportOnlineToAndroid(this, null);
                updateStatusDisplay(true, "Internet Active (HTTP 204 OK)", ip);
                appendLog("[Status] Online. Subnet: " + (inHcu ? "HCU Campus" : "Other (" + ip + ")"));
            } else if (inHcu) {
                updateStatusDisplay(false, "HCU Subnet Detected - Login Required", ip);
                appendLog("[Status] Offline on HCU Wi-Fi (IP: " + ip + "). Ready to login.");
            } else {
                updateStatusDisplay(false, "Not in HCU Subnet (" + ip + ")", ip);
                appendLog("[Status] Offline / Non-HCU IP: " + ip);
            }
        });
    }

    private void updateStatusDisplay(boolean online, String detail, String ip) {
        tvLocalIp.setText(ip);
        tvStatusDetail.setText(detail);

        if (online) {
            tvStatusBadge.setText("[ ONLINE ]");
            tvStatusBadge.setTextColor(Color.parseColor("#16A34A"));
        } else {
            tvStatusBadge.setText("[ OFFLINE ]");
            tvStatusBadge.setTextColor(Color.parseColor("#DC2626"));
        }
    }

    private void performLogin() {
        String u = etUsername.getText().toString().trim();
        String p = etPassword.getText().toString();

        if (u.isEmpty()) {
            Toast.makeText(this, "Username required", Toast.LENGTH_SHORT).show();
            return;
        }

        // Auto-save so background service always has current valid credentials
        HcuAuthHelper.saveConfig(this, u, p, swAutoLogin.isChecked());
        if (swAutoLogin.isChecked()) {
            HcuForegroundService.start(this);
        }

        HcuAuthHelper.bindToWifi(this);
        setBusy(true);
        appendLog("Initiating login for user: " + u + "...");
        appendLog("Target gateway: " + HcuAuthHelper.PRIMARY_GATEWAY + ":" + HcuAuthHelper.PORT);

        HcuAuthHelper.loginAsync(this, null, u, p, result -> {
            setBusy(false);
            appendLog("Login response: " + result.toString());
            if (!result.rawResponse.isEmpty()) {
                appendLog("Portal snippet: " + truncate(result.rawResponse, 120));
            }

            String currentIp = HcuAuthHelper.getLocalWifiIpAddress(this);
            if (result.isSuccess()) {
                HcuAuthHelper.reportOnlineToAndroid(this, null);
                Toast.makeText(this, "Success: " + result.message, Toast.LENGTH_SHORT).show();
                updateStatusDisplay(true, "Signed in successfully!", currentIp);
            } else {
                Toast.makeText(this, "Failed: " + result.message, Toast.LENGTH_LONG).show();
                updateStatusDisplay(false, result.message, currentIp);
            }
        });
    }

    private void performLogout() {
        setBusy(true);
        appendLog("Sending logout request to Sophos...");

        HcuAuthHelper.logoutAsync(this, result -> {
            setBusy(false);
            appendLog("Logout result: " + result.toString());
            Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show();
            refreshStatus();
        });
    }

    private void setBusy(boolean busy) {
        progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        btnLogin.setEnabled(!busy);
        btnLogout.setEnabled(!busy);
        btnProbe.setEnabled(!busy);
    }

    private void appendLog(String line) {
        String timestamp = timeFormat.format(new Date());
        String entry = "[" + timestamp + "] " + line + "\n";
        tvConsoleLog.append(entry);

        scrollLog.post(() -> scrollLog.fullScroll(View.FOCUS_DOWN));
    }

    private String truncate(String text, int max) {
        if (text == null) return "";
        String clean = text.replace("\n", " ").trim();
        return clean.length() <= max ? clean : clean.substring(0, max) + "...";
    }
}
