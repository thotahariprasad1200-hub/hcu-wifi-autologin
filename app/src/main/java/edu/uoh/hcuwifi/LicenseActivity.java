package edu.uoh.hcuwifi;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Unlock screen: pay once, check payment, or restore a purchase on a new install. Built in code (no layout XML). */
public class LicenseActivity extends Activity {

    private static final long POLL_MS = 4000L;
    private static final int MAX_POLLS = 20;   // about 80 s after returning from the payment page

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView tvStatus;
    private TextView tvMessage;
    private Button btnPay;
    private Button btnCheck;
    private Button btnRestore;
    private EditText etPhone;
    private int polls;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshHeader();
        if (LicenseManager.isPaymentPending(this)) {
            polls = 0;
            checkPayment(true);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacksAndMessages(null);
    }

    private void buildUi() {
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundResource(R.color.bg_main);

        TextView title = text(getString(R.string.license_title), 20, true, R.color.primary);
        tvStatus = text("", 13, false, R.color.text_secondary);
        tvStatus.setPadding(0, dp(4), 0, dp(12));
        root.addView(title);
        root.addView(tvStatus);

        LinearLayout card = card();
        card.addView(text(getString(R.string.license_pitch), 13, false, R.color.text_primary));
        btnPay = button(getString(R.string.license_pay, LicenseManager.priceLabel(this)), R.drawable.bg_button);
        btnPay.setOnClickListener(v -> onPay());
        btnCheck = button(getString(R.string.license_check), R.drawable.bg_button_secondary);
        btnCheck.setOnClickListener(v -> { polls = 0; checkPayment(false); });
        card.addView(btnPay);
        card.addView(btnCheck);
        root.addView(card);

        LinearLayout restore = card();
        restore.addView(text(getString(R.string.license_restore_title), 13, true, R.color.text_primary));
        restore.addView(text(getString(R.string.license_restore_hint), 12, false, R.color.text_secondary));
        etPhone = new EditText(this);
        etPhone.setHint(R.string.license_phone_hint);
        etPhone.setInputType(InputType.TYPE_CLASS_PHONE);
        etPhone.setTextSize(13);
        etPhone.setSingleLine(true);
        restore.addView(etPhone);
        btnRestore = button(getString(R.string.license_restore), R.drawable.bg_button_secondary);
        btnRestore.setOnClickListener(v -> onRestore());
        restore.addView(btnRestore);
        root.addView(restore);

        tvMessage = text("", 13, true, R.color.primary);
        tvMessage.setGravity(Gravity.CENTER);
        tvMessage.setPadding(0, dp(8), 0, 0);
        root.addView(tvMessage);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);
    }

    private void refreshHeader() {
        LicenseLogic.State state = LicenseManager.state(this);
        switch (state) {
            case UNLOCKED:
                finish();
                return;
            case TRIAL:
                int days = LicenseManager.daysLeft(this);
                tvStatus.setText(getResources().getQuantityString(R.plurals.license_trial_left, days, days));
                break;
            case REVOKED:
                tvStatus.setText(R.string.license_revoked);
                break;
            default:
                tvStatus.setText(R.string.license_trial_ended);
        }
    }

    private void onPay() {
        if (busy) return;
        setBusy(true, getString(R.string.license_opening));
        LicenseManager.startPaymentAsync(this, result -> {
            setBusy(false, "");
            switch (result.outcome) {
                case UNLOCKED:
                    unlockedDone();
                    break;
                case STILL_TRIAL:
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(result.url)));
                    } catch (Exception e) {
                        tvMessage.setText(R.string.license_no_browser);
                    }
                    break;
                default:
                    tvMessage.setText(messageFor(result.outcome));
            }
        });
    }

    /** Asks the backend whether the payment has landed; keeps polling briefly because webhooks lag. */
    private void checkPayment(boolean automatic) {
        if (busy && automatic) return;
        setBusy(true, getString(R.string.license_checking));
        LicenseManager.refreshAsync(this, outcome -> {
            setBusy(false, "");
            if (outcome == LicenseManager.Outcome.UNLOCKED) {
                unlockedDone();
            } else if (outcome == LicenseManager.Outcome.STILL_TRIAL || outcome == LicenseManager.Outcome.REVOKED) {
                boolean waiting = LicenseManager.isPaymentPending(this) && ++polls < MAX_POLLS;
                tvMessage.setText(waiting ? R.string.license_waiting : R.string.license_not_paid_yet);
                if (waiting) handler.postDelayed(() -> checkPayment(true), POLL_MS);
            } else {
                tvMessage.setText(messageFor(outcome));
            }
        });
    }

    private void onRestore() {
        if (busy) return;
        String phone = etPhone.getText().toString().trim();
        if (phone.replaceAll("\\D", "").length() < 10) {
            tvMessage.setText(R.string.license_phone_invalid);
            return;
        }
        setBusy(true, getString(R.string.license_checking));
        LicenseManager.restoreAsync(this, phone, outcome -> {
            setBusy(false, "");
            switch (outcome) {
                case RESTORED: unlockedDone(); break;
                case NOT_FOUND: tvMessage.setText(R.string.license_restore_not_found); break;
                case LIMIT_REACHED: tvMessage.setText(R.string.license_restore_limit); break;
                case NO_INTERNET: tvMessage.setText(R.string.license_no_internet); break;
                case RATE_LIMITED: tvMessage.setText(R.string.license_rate_limited); break;
                default: tvMessage.setText(R.string.license_error);
            }
        });
    }

    private void unlockedDone() {
        tvMessage.setText(R.string.license_thanks);
        finish();
    }

    private int messageFor(LicenseManager.Outcome outcome) {
        switch (outcome) {
            case NO_INTERNET: return R.string.license_no_internet;
            case RATE_LIMITED: return R.string.license_rate_limited;
            default: return R.string.license_error;
        }
    }

    private void setBusy(boolean value, String message) {
        busy = value;
        btnPay.setEnabled(!value);
        btnCheck.setEnabled(!value);
        btnRestore.setEnabled(!value);
        if (value || !message.isEmpty()) tvMessage.setText(message);
    }

    // ---- tiny view helpers ----

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(String s, int sp, boolean bold, int colorRes) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(getResources().getColor(colorRes));
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.bg_card);
        c.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        c.setLayoutParams(lp);
        return c;
    }

    private Button button(String label, int bg) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(0xFFFFFFFF);
        b.setBackgroundResource(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }
}
