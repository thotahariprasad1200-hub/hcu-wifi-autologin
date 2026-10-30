package edu.uoh.hcuwifi;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.net.Network;
import android.util.Log;

/**
 * OS-scheduled safety net that survives the app process being killed.
 *
 * The foreground service is fast and event-driven, but it only works while the
 * process is alive. Realme/Android battery management can kill that process,
 * and then nothing reacts to Wi-Fi until the user opens the app again.
 * JobScheduler jobs are held by the OS, not the app process, so they still
 * wake the app up:
 *
 *  - a network-triggered job runs shortly after any network connects;
 *  - a periodic job (Android minimum: 15 minutes) re-checks the session and
 *    revives the listener service;
 *  - a one-shot restart job re-arms the service if its task is removed.
 *
 * Each job performs the sign-in itself on a background thread, so automatic
 * login works even when the foreground service cannot be restarted.
 */
public class HcuWatchdogJobService extends JobService {

    private static final String TAG = "HcuWatchdogJob";
    private static final int JOB_ID_PERIODIC = 9001;
    private static final int JOB_ID_NETWORK = 9002;
    private static final int JOB_ID_RESTART = 9003;

    private static final long PERIODIC_INTERVAL_MS = 15 * 60 * 1000L; // Android minimum
    private final java.util.concurrent.ExecutorService executor =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    /** (Re)registers the periodic and network-triggered jobs. Safe to call often. */
    public static void scheduleAll(Context context) {
        if (context == null) return;
        try {
            JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (scheduler == null) return;
            ComponentName component = new ComponentName(context, HcuWatchdogJobService.class);

            JobInfo periodic = new JobInfo.Builder(JOB_ID_PERIODIC, component)
                    .setPeriodic(PERIODIC_INTERVAL_MS)
                    .setPersisted(true)
                    .build();
            scheduler.schedule(periodic);

            scheduler.schedule(buildNetworkJob(context));

            Log.i(TAG, "Watchdog jobs registered (periodic + network-triggered).");
        } catch (Exception e) {
            Log.w(TAG, "Could not register watchdog jobs", e);
        }
    }

    private static JobInfo buildNetworkJob(Context context) {
        return new JobInfo.Builder(JOB_ID_NETWORK,
                new ComponentName(context, HcuWatchdogJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build();
    }

    /** One-shot job used when the foreground service's task is removed. */
    public static void scheduleRestart(Context context) {
        if (context == null) return;
        try {
            JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (scheduler == null) return;
            JobInfo restart = new JobInfo.Builder(JOB_ID_RESTART,
                    new ComponentName(context, HcuWatchdogJobService.class))
                    .setMinimumLatency(30 * 1000L)
                    .setOverrideDeadline(5 * 60 * 1000L)
                    .setPersisted(true)
                    .build();
            scheduler.schedule(restart);
        } catch (Exception e) {
            Log.w(TAG, "Could not schedule service restart job", e);
        }
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        if (!HcuAuthHelper.isAutoLoginEnabled(this)) {
            rescheduleNetworkJob();
            return false;
        }

        executor.execute(() -> {
            try {
                runCheck();
            } catch (Exception e) {
                Log.w(TAG, "Watchdog check failed", e);
            } finally {
                rescheduleNetworkJob();
                jobFinished(params, false /* no retry: the periodic job is the next safety net */);
            }
        });
        return true; // work continues on the background thread
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        rescheduleNetworkJob();
        return false;
    }

    private void runCheck() {
        // Best effort: bring the event-driven listener back up. Whether or not
        // Android allows the background start, this job can sign in by itself.
        try {
            HcuForegroundService.start(this);
        } catch (Exception e) {
            Log.w(TAG, "Could not restart listener service from job", e);
        }

        Network wifi = HcuAuthHelper.getWifiNetwork(this);
        if (wifi == null || !HcuAuthHelper.isWifiNetwork(this, wifi)) {
            Log.d(TAG, "Watchdog: no Wi-Fi right now.");
            return;
        }

        String ip = HcuAuthHelper.getLocalWifiIpAddress(this, wifi);
        if (!HcuAuthHelper.isHcuSubnet(ip)) {
            Log.d(TAG, "Watchdog: not an HCU network (" + ip + ").");
            return;
        }

        if (HcuAuthHelper.isNetworkValidated(this, wifi) || HcuAuthHelper.isOnline(wifi, this)) {
            HcuAuthHelper.reportOnlineToAndroid(this, wifi);
            Log.d(TAG, "Watchdog: HCU Wi-Fi already online.");
            return;
        }

        Log.i(TAG, "Watchdog: HCU Wi-Fi detected without internet - signing in.");
        HcuAuthHelper.AuthResult result = HcuAuthHelper.loginSync(this, wifi);
        Log.i(TAG, "Watchdog login result: " + result);
        if (result.isSuccess()) {
            HcuAuthHelper.reportOnlineToAndroid(this, wifi);
        }
    }

    private void rescheduleNetworkJob() {
        // A network-constraint job fires once; re-register so the next
        // Wi-Fi connection wakes the app again.
        try {
            JobScheduler scheduler = (JobScheduler) getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (scheduler != null) scheduler.schedule(buildNetworkJob(this));
        } catch (Exception e) {
            Log.w(TAG, "Could not re-register network job", e);
        }
    }
}
