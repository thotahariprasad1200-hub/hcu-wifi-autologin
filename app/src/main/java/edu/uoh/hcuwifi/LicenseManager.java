package edu.uoh.hcuwifi;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Android-facing licence entry point. All rules live in {@link LicenseLogic}; this class adds
 * storage (Keystore-encrypted), the clock, and the backend calls.
 *
 * Switch: when licensing is not enabled in res/values/license_config.xml (or no HTTPS API base is
 * configured) every method behaves as "licensed", so v1.7.1 behaviour is untouched.
 */
public final class LicenseManager {

    private static final String TAG = "HcuLicense";
    private static final String SECRET_NAME = "license_v1";
    private static final String FALLBACK_PREFS = "hcu_license_fallback";
    private static final String META_PREFS = "hcu_license_meta";
    private static final String META_PRICE = "price_paise";
    private static final String META_PAY_STARTED = "pay_started_ms";

    private static final long UNLOCKED_RECHECK_MS = 7L * LicenseLogic.DAY_MS;
    private static final long PAY_WINDOW_MS = 60L * 60L * 1000L;       // keep polling for an hour after the buyer left for the payment page
    private static final long MIN_REFRESH_GAP_MS = 5L * 60L * 1000L;   // never hit the backend more than every 5 min on its own

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static LicenseLogic.Snapshot cached;     // guarded by LicenseManager.class
    private static long lastAutoRefreshMs;           // guarded by LicenseManager.class

    private LicenseManager() {}

    // ------------------------------------------------------------------ configuration

    static String apiBase(Context context) {
        String base = context.getString(R.string.license_api_base).trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base;
    }

    public static boolean isEnabled(Context context) {
        return context != null
                && context.getResources().getBoolean(R.bool.license_enabled)
                && apiBase(context).startsWith("https://");
    }

    private static int trialDays(Context context) {
        return context.getResources().getInteger(R.integer.license_trial_days);
    }

    // ------------------------------------------------------------------ state

    /** The state the rest of the app acts on. Cheap: safe to call from services and jobs. */
    public static LicenseLogic.State state(Context context) {
        if (!isEnabled(context)) return LicenseLogic.State.UNLOCKED;
        long now = System.currentTimeMillis();
        LicenseLogic.Snapshot snapshot = touch(context.getApplicationContext(), now);
        return LicenseLogic.evaluate(snapshot, now, trialDays(context));
    }

    /** True while automatic login is allowed to run (licensing off, trial running, or unlocked). */
    public static boolean canRun(Context context) {
        LicenseLogic.State s = state(context);
        return s == LicenseLogic.State.TRIAL || s == LicenseLogic.State.UNLOCKED;
    }

    public static int daysLeft(Context context) {
        if (!isEnabled(context)) return 0;
        return LicenseLogic.daysLeft(load(context.getApplicationContext()), System.currentTimeMillis(), trialDays(context));
    }

    public static String installId(Context context) {
        return load(context.getApplicationContext()).installId;
    }

    /** "₹50", from the last price the backend reported, else the configured fallback. */
    public static String priceLabel(Context context) {
        int paise = context.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE).getInt(META_PRICE, 0);
        if (paise < 100) return context.getString(R.string.license_price_fallback);
        return paise % 100 == 0
                ? "₹" + (paise / 100)
                : String.format(Locale.US, "₹%.2f", paise / 100.0);
    }

    // ------------------------------------------------------------------ storage

    private static synchronized LicenseLogic.Snapshot load(Context app) {
        if (cached != null) return cached;

        String record = SecureStorage.getSecret(app, SECRET_NAME);
        if (record == null) {
            record = app.getSharedPreferences(FALLBACK_PREFS, Context.MODE_PRIVATE).getString("rec", null);
        }
        LicenseLogic.Snapshot snapshot = LicenseLogic.decode(record);
        if (snapshot == null) {
            // First launch with licensing on (or the record was lost/corrupted): start a fresh trial.
            snapshot = LicenseLogic.Snapshot.fresh(UUID.randomUUID().toString(), System.currentTimeMillis());
            save(app, snapshot);
            Log.i(TAG, "New licence record created.");
        }
        cached = snapshot;
        return snapshot;
    }

    private static synchronized void save(Context app, LicenseLogic.Snapshot snapshot) {
        cached = snapshot;
        String record = LicenseLogic.encode(snapshot);
        SharedPreferences fallback = app.getSharedPreferences(FALLBACK_PREFS, Context.MODE_PRIVATE);
        if (SecureStorage.putSecret(app, SECRET_NAME, record)) {
            fallback.edit().remove("rec").apply();
        } else {
            // Keystore unavailable on this device: keep working rather than lock a customer out.
            fallback.edit().putString("rec", record).apply();
        }
    }

    private static synchronized LicenseLogic.Snapshot touch(Context app, long nowMs) {
        LicenseLogic.Snapshot current = load(app);
        LicenseLogic.Snapshot next = LicenseLogic.touched(current, nowMs, trialDays(app));
        if (next != current) save(app, next);
        return next;
    }

    // ------------------------------------------------------------------ backend refresh

    /** Result of a backend round trip, delivered on the main thread. */
    public interface Listener {
        void onDone(Outcome outcome);
    }

    public enum Outcome { UNLOCKED, REVOKED, STILL_TRIAL, NO_INTERNET, RATE_LIMITED, ERROR }

    /**
     * Asks the backend for this install's status and applies it. Used after the buyer returns from
     * the payment page and on a slow schedule. {@code listener} may be null.
     */
    public static void refreshAsync(Context context, Listener listener) {
        final Context app = context.getApplicationContext();
        if (!isEnabled(app)) {
            deliver(listener, Outcome.UNLOCKED);
            return;
        }
        IO.execute(() -> deliver(listener, refreshBlocking(app)));
    }

    /** Fire-and-forget: only talks to the backend when a check is actually due. */
    public static void refreshIfDue(Context context) {
        final Context app = context.getApplicationContext();
        if (!isEnabled(app)) return;
        long now = System.currentTimeMillis();
        LicenseLogic.State state = LicenseLogic.evaluate(load(app), now, trialDays(app));
        boolean due;
        synchronized (LicenseManager.class) {
            if (now - lastAutoRefreshMs < MIN_REFRESH_GAP_MS) return;
            switch (state) {
                case UNLOCKED:
                    due = now - load(app).lastCheckMs > UNLOCKED_RECHECK_MS;
                    break;
                case TRIAL:
                    due = isPaymentPending(app);      // a trial install has nothing to learn unless it just paid
                    break;
                default:
                    due = true;                      // EXPIRED or REVOKED: look for a newly completed payment
            }
            if (!due) return;
            lastAutoRefreshMs = now;
        }
        IO.execute(() -> refreshBlocking(app));
    }

    private static Outcome refreshBlocking(Context app) {
        LicenseLogic.Snapshot snapshot = load(app);
        LicenseApi.Reply reply = LicenseApi.status(app, snapshot.installId);
        if (reply.isNoInternet()) return Outcome.NO_INTERNET;
        if (reply.isRateLimited()) return Outcome.RATE_LIMITED;
        if (!reply.isOk()) return Outcome.ERROR;

        rememberPrice(app, reply.json.optInt("amount_paise", 0));
        String status = reply.string("status");
        long now = System.currentTimeMillis();
        LicenseLogic.Snapshot updated = LicenseLogic.applyServerStatus(snapshot, status, now);
        if (updated != snapshot) save(app, updated);

        if ("paid".equals(status)) {
            clearPaymentPending(app);
            onUnlocked(app);
            return Outcome.UNLOCKED;
        }
        if ("revoked".equals(status)) return Outcome.REVOKED;
        if ("trial".equals(status)) return Outcome.STILL_TRIAL;
        return Outcome.ERROR;
    }

    // ------------------------------------------------------------------ payment + restore

    public static final class PayResult {
        public final Outcome outcome;      // UNLOCKED when the backend says already paid, otherwise ERROR/NO_INTERNET/RATE_LIMITED/STILL_TRIAL
        public final String url;           // payment page to open when outcome == STILL_TRIAL and url != null

        PayResult(Outcome outcome, String url) {
            this.outcome = outcome;
            this.url = url;
        }
    }

    public interface PayListener {
        void onDone(PayResult result);
    }

    public static void startPaymentAsync(Context context, PayListener listener) {
        final Context app = context.getApplicationContext();
        IO.execute(() -> {
            PayResult result = startPaymentBlocking(app);
            MAIN.post(() -> { if (listener != null) listener.onDone(result); });
        });
    }

    private static PayResult startPaymentBlocking(Context app) {
        LicenseLogic.Snapshot snapshot = load(app);
        LicenseApi.Reply reply = LicenseApi.pay(app, snapshot.installId);
        if (reply.isNoInternet()) return new PayResult(Outcome.NO_INTERNET, null);
        if (reply.isRateLimited()) return new PayResult(Outcome.RATE_LIMITED, null);
        if (!reply.isOk()) return new PayResult(Outcome.ERROR, null);

        rememberPrice(app, reply.json.optInt("amount_paise", 0));
        String status = reply.string("status");
        if ("paid".equals(status)) {
            // The backend already has this install as paid (for example the webhook arrived earlier).
            LicenseLogic.Snapshot updated = LicenseLogic.applyServerStatus(snapshot, "paid", System.currentTimeMillis());
            if (updated != snapshot) save(app, updated);
            onUnlocked(app);
            return new PayResult(Outcome.UNLOCKED, null);
        }
        String url = reply.string("url");
        if ("link".equals(status) && url.startsWith("https://")) {
            markPaymentPending(app);
            return new PayResult(Outcome.STILL_TRIAL, url);
        }
        return new PayResult(Outcome.ERROR, null);
    }

    public enum RestoreOutcome { RESTORED, NOT_FOUND, LIMIT_REACHED, NO_INTERNET, RATE_LIMITED, ERROR }

    public interface RestoreListener {
        void onDone(RestoreOutcome outcome);
    }

    public static void restoreAsync(Context context, String phone, RestoreListener listener) {
        final Context app = context.getApplicationContext();
        IO.execute(() -> {
            RestoreOutcome outcome = restoreBlocking(app, phone);
            MAIN.post(() -> { if (listener != null) listener.onDone(outcome); });
        });
    }

    private static RestoreOutcome restoreBlocking(Context app, String phone) {
        LicenseLogic.Snapshot snapshot = load(app);
        LicenseApi.Reply reply = LicenseApi.restore(app, snapshot.installId, phone);
        if (reply.isNoInternet()) return RestoreOutcome.NO_INTERNET;
        if (reply.isRateLimited()) return RestoreOutcome.RATE_LIMITED;
        if (!reply.isOk()) return RestoreOutcome.ERROR;

        switch (reply.string("result")) {
            case "restored":
                // Confirm through /status rather than trusting the restore answer alone.
                return refreshBlocking(app) == Outcome.UNLOCKED ? RestoreOutcome.RESTORED : RestoreOutcome.ERROR;
            case "not_found": return RestoreOutcome.NOT_FOUND;
            case "limit_reached": return RestoreOutcome.LIMIT_REACHED;
            default: return RestoreOutcome.ERROR;
        }
    }

    // ------------------------------------------------------------------ helpers

    /** The monitor was idle (or stopped) while locked; wake it now that login is allowed again. */
    private static void onUnlocked(Context app) {
        MAIN.post(() -> {
            try {
                if (HcuAuthHelper.isAutoLoginEnabled(app)) HcuForegroundService.start(app);
            } catch (Exception e) {
                Log.w(TAG, "Could not restart monitor after unlock: " + e.getMessage());
            }
        });
    }

    private static void rememberPrice(Context app, int paise) {
        if (paise >= 100) {
            app.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE).edit().putInt(META_PRICE, paise).apply();
        }
    }

    private static void markPaymentPending(Context app) {
        app.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE).edit()
                .putLong(META_PAY_STARTED, System.currentTimeMillis()).apply();
    }

    private static void clearPaymentPending(Context app) {
        app.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE).edit().remove(META_PAY_STARTED).apply();
    }

    /** True for an hour after the buyer was sent to the payment page. */
    public static boolean isPaymentPending(Context context) {
        long started = context.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE).getLong(META_PAY_STARTED, 0L);
        return started > 0 && System.currentTimeMillis() - started < PAY_WINDOW_MS;
    }

    private static void deliver(Listener listener, Outcome outcome) {
        if (listener != null) MAIN.post(() -> listener.onDone(outcome));
    }
}
