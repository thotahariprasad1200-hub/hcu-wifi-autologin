package edu.uoh.hcuwifi;

import java.util.regex.Pattern;

/**
 * Licence rules with NO Android dependencies, so they run in plain JVM unit tests (PRD 15.1).
 *
 * The app keeps one small immutable {@link Snapshot} per installation. It is "TRIAL" until the
 * server says the install is paid; "UNLOCKED" is only ever written after the backend confirmed
 * a captured payment, and the app then works offline forever (PRD 10.5).
 */
public final class LicenseLogic {

    /** What the rest of the app cares about. */
    public enum State { TRIAL, EXPIRED, UNLOCKED, REVOKED }

    /** What is persisted. EXPIRED is never stored: it is derived from the clock. */
    public enum Stored { TRIAL, UNLOCKED, REVOKED }

    public static final long DAY_MS = 24L * 60L * 60L * 1000L;

    /** Persist the "last time we saw" clock at most this often (it only guards against clock rollback). */
    static final long PERSIST_GAP_MS = 60L * 60L * 1000L;

    private static final Pattern INSTALL_ID =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

    private LicenseLogic() {}

    /** Immutable licence record for one installation. */
    public static final class Snapshot {
        public final String installId;
        public final long firstRunMs;
        public final Stored stored;
        public final long paidAtMs;
        public final long lastSeenMs;
        public final long lastCheckMs;

        public Snapshot(String installId, long firstRunMs, Stored stored,
                        long paidAtMs, long lastSeenMs, long lastCheckMs) {
            this.installId = installId;
            this.firstRunMs = firstRunMs;
            this.stored = stored;
            this.paidAtMs = paidAtMs;
            this.lastSeenMs = lastSeenMs;
            this.lastCheckMs = lastCheckMs;
        }

        public static Snapshot fresh(String installId, long nowMs) {
            return new Snapshot(installId, nowMs, Stored.TRIAL, 0L, nowMs, 0L);
        }

        Snapshot withLastSeen(long ms) {
            return new Snapshot(installId, firstRunMs, stored, paidAtMs, ms, lastCheckMs);
        }

        Snapshot withLastCheck(long ms) {
            return new Snapshot(installId, firstRunMs, stored, paidAtMs, lastSeenMs, ms);
        }

        Snapshot unlocked(long nowMs) {
            return new Snapshot(installId, firstRunMs, Stored.UNLOCKED, nowMs, Math.max(lastSeenMs, nowMs), nowMs);
        }

        Snapshot revoked(long nowMs) {
            return new Snapshot(installId, firstRunMs, Stored.REVOKED, paidAtMs, Math.max(lastSeenMs, nowMs), nowMs);
        }
    }

    public static boolean isValidInstallId(String id) {
        return id != null && INSTALL_ID.matcher(id).matches();
    }

    /** The clock the licence trusts: it never runs backwards past the latest time we have seen. */
    static long effectiveNow(Snapshot s, long nowMs) {
        return Math.max(nowMs, s.lastSeenMs);
    }

    static long trialMs(int trialDays) {
        return Math.max(0, trialDays) * DAY_MS;
    }

    public static State evaluate(Snapshot s, long nowMs, int trialDays) {
        switch (s.stored) {
            case UNLOCKED: return State.UNLOCKED;
            case REVOKED: return State.REVOKED;
            default:
                long elapsed = effectiveNow(s, nowMs) - s.firstRunMs;
                return elapsed >= trialMs(trialDays) ? State.EXPIRED : State.TRIAL;
        }
    }

    /** Whole days left in the trial, rounded up (1 day left until the last second). 0 when not on trial. */
    public static int daysLeft(Snapshot s, long nowMs, int trialDays) {
        if (s.stored != Stored.TRIAL) return 0;
        long remaining = trialMs(trialDays) - (effectiveNow(s, nowMs) - s.firstRunMs);
        if (remaining <= 0) return 0;
        long days = (remaining + DAY_MS - 1) / DAY_MS;
        return (int) Math.min(days, Math.max(0, trialDays));
    }

    /**
     * Returns the same instance when nothing needs saving, or a copy with an advanced "last seen"
     * clock when it should be persisted: hourly, and immediately when expiry is first observed so
     * that winding the clock back afterwards cannot revive the trial.
     */
    public static Snapshot touched(Snapshot s, long nowMs, int trialDays) {
        long now = effectiveNow(s, nowMs);
        if (nowMs > s.lastSeenMs + PERSIST_GAP_MS) return s.withLastSeen(nowMs);
        boolean expiredNow = s.stored == Stored.TRIAL && now - s.firstRunMs >= trialMs(trialDays);
        boolean expiryRecorded = s.lastSeenMs - s.firstRunMs >= trialMs(trialDays);
        if (expiredNow && !expiryRecorded) return s.withLastSeen(now);
        return s;
    }

    /**
     * Applies the backend's answer from GET /status. Only a confirmed "paid" unlocks; "trial" never
     * downgrades an unlock (a lost server row must not lock out a paying user); "revoked" locks.
     */
    public static Snapshot applyServerStatus(Snapshot s, String status, long nowMs) {
        if ("paid".equals(status)) {
            return s.stored == Stored.UNLOCKED ? s.withLastCheck(nowMs) : s.unlocked(nowMs);
        }
        if ("revoked".equals(status)) {
            return s.stored == Stored.REVOKED ? s.withLastCheck(nowMs) : s.revoked(nowMs);
        }
        if ("trial".equals(status)) return s.withLastCheck(nowMs);
        return s;   // unknown or malformed answer: change nothing
    }

    // ---- storage format: "v1|installId|firstRun|stored|paidAt|lastSeen|lastCheck" ----

    public static String encode(Snapshot s) {
        return "v1|" + s.installId + "|" + s.firstRunMs + "|" + s.stored.name() + "|"
                + s.paidAtMs + "|" + s.lastSeenMs + "|" + s.lastCheckMs;
    }

    /** Returns null for anything that is not a well-formed v1 record. */
    public static Snapshot decode(String text) {
        if (text == null) return null;
        String[] p = text.split("\\|", -1);
        if (p.length != 7 || !"v1".equals(p[0]) || !isValidInstallId(p[1])) return null;
        try {
            long firstRun = Long.parseLong(p[2]);
            Stored stored = Stored.valueOf(p[3]);
            long paidAt = Long.parseLong(p[4]);
            long lastSeen = Long.parseLong(p[5]);
            long lastCheck = Long.parseLong(p[6]);
            if (firstRun <= 0 || lastSeen <= 0 || paidAt < 0 || lastCheck < 0) return null;
            return new Snapshot(p[1], firstRun, stored, paidAt, lastSeen, lastCheck);
        } catch (IllegalArgumentException e) {   // NumberFormatException is a subclass
            return null;
        }
    }
}
