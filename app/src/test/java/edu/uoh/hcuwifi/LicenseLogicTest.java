package edu.uoh.hcuwifi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import edu.uoh.hcuwifi.LicenseLogic.Snapshot;
import edu.uoh.hcuwifi.LicenseLogic.State;
import edu.uoh.hcuwifi.LicenseLogic.Stored;
import org.junit.Test;

/** Plain JVM tests: LicenseLogic has no Android dependencies. */
public class LicenseLogicTest {

    private static final String ID = "3f2b8c1e-5d4a-4e7b-9a10-0c6d8e2f1a34";
    private static final long T0 = 1_760_000_000_000L;   // arbitrary first-run time
    private static final int TRIAL_DAYS = 4;

    private static Snapshot fresh() { return Snapshot.fresh(ID, T0); }

    @Test public void freshInstallIsTrialWithFullDays() {
        assertEquals(State.TRIAL, LicenseLogic.evaluate(fresh(), T0, TRIAL_DAYS));
        assertEquals(4, LicenseLogic.daysLeft(fresh(), T0, TRIAL_DAYS));
    }

    @Test public void daysLeftRoundsUpAndCountsDown() {
        Snapshot s = fresh();
        assertEquals(4, LicenseLogic.daysLeft(s, T0 + 1, TRIAL_DAYS));
        assertEquals(3, LicenseLogic.daysLeft(s, T0 + LicenseLogic.DAY_MS + 1, TRIAL_DAYS));
        assertEquals(1, LicenseLogic.daysLeft(s, T0 + 3 * LicenseLogic.DAY_MS + 1, TRIAL_DAYS));
        assertEquals(1, LicenseLogic.daysLeft(s, T0 + 4 * LicenseLogic.DAY_MS - 1, TRIAL_DAYS));
        assertEquals(0, LicenseLogic.daysLeft(s, T0 + 4 * LicenseLogic.DAY_MS, TRIAL_DAYS));
    }

    @Test public void trialExpiresExactlyAtTheBoundary() {
        long expiry = T0 + 4 * LicenseLogic.DAY_MS;
        assertEquals(State.TRIAL, LicenseLogic.evaluate(fresh(), expiry - 1, TRIAL_DAYS));
        assertEquals(State.EXPIRED, LicenseLogic.evaluate(fresh(), expiry, TRIAL_DAYS));
        assertEquals(State.EXPIRED, LicenseLogic.evaluate(fresh(), expiry + 100 * LicenseLogic.DAY_MS, TRIAL_DAYS));
    }

    @Test public void trialLengthIsConfigurable() {
        assertEquals(State.EXPIRED, LicenseLogic.evaluate(fresh(), T0 + 3 * LicenseLogic.DAY_MS, 3));
        assertEquals(State.TRIAL, LicenseLogic.evaluate(fresh(), T0 + 3 * LicenseLogic.DAY_MS, 4));
    }

    @Test public void daysLeftNeverExceedsTrialLengthEvenIfClockStartsBeforeFirstRun() {
        assertEquals(4, LicenseLogic.daysLeft(fresh(), T0 - 30 * LicenseLogic.DAY_MS, TRIAL_DAYS));
    }

    // ---- clock rollback ----

    @Test public void windingTheClockBackDoesNotReviveAnExpiredTrial() {
        long expiry = T0 + 4 * LicenseLogic.DAY_MS;
        Snapshot seen = LicenseLogic.touched(fresh(), expiry + 5, TRIAL_DAYS);   // app notices expiry and records it
        assertEquals(State.EXPIRED, LicenseLogic.evaluate(seen, T0 + LicenseLogic.DAY_MS, TRIAL_DAYS));
        assertEquals(State.EXPIRED, LicenseLogic.evaluate(seen, T0 - LicenseLogic.DAY_MS, TRIAL_DAYS));
    }

    @Test public void lastSeenIsPersistedHourlyAndWhenExpiryFirstSeen() {
        Snapshot s = fresh();
        assertSame("no write needed within the hour", s, LicenseLogic.touched(s, T0 + 10 * 60 * 1000L, TRIAL_DAYS));
        Snapshot hourly = LicenseLogic.touched(s, T0 + 61 * 60 * 1000L, TRIAL_DAYS);
        assertEquals(T0 + 61 * 60 * 1000L, hourly.lastSeenMs);

        long expiry = T0 + 4 * LicenseLogic.DAY_MS;
        Snapshot almost = new Snapshot(ID, T0, Stored.TRIAL, 0, expiry - 1000, 0);   // last seen 1s before expiry
        Snapshot recorded = LicenseLogic.touched(almost, expiry + 1000, TRIAL_DAYS);
        assertTrue("expiry must be persisted immediately", recorded.lastSeenMs >= expiry);
    }

    // ---- unlock, revoke, server mapping ----

    @Test public void unlockedNeverExpiresAndWorksOfflineForever() {
        Snapshot unlocked = LicenseLogic.applyServerStatus(fresh(), "paid", T0 + 2 * LicenseLogic.DAY_MS);
        assertEquals(State.UNLOCKED, LicenseLogic.evaluate(unlocked, T0 + 3650 * LicenseLogic.DAY_MS, TRIAL_DAYS));
        assertEquals(0, LicenseLogic.daysLeft(unlocked, T0, TRIAL_DAYS));
    }

    @Test public void unlockAfterTrialEndedStillUnlocks() {
        long late = T0 + 10 * LicenseLogic.DAY_MS;
        assertEquals(State.EXPIRED, LicenseLogic.evaluate(fresh(), late, TRIAL_DAYS));
        Snapshot unlocked = LicenseLogic.applyServerStatus(fresh(), "paid", late);
        assertEquals(State.UNLOCKED, LicenseLogic.evaluate(unlocked, late, TRIAL_DAYS));
    }

    @Test public void serverSaysTrialNeverDowngradesAnUnlock() {
        Snapshot unlocked = LicenseLogic.applyServerStatus(fresh(), "paid", T0);
        Snapshot after = LicenseLogic.applyServerStatus(unlocked, "trial", T0 + 1000);
        assertEquals(Stored.UNLOCKED, after.stored);
        assertEquals(T0 + 1000, after.lastCheckMs);
    }

    @Test public void revokedBlocksEvenAnUnlockedInstallAndPaidRestoresIt() {
        Snapshot unlocked = LicenseLogic.applyServerStatus(fresh(), "paid", T0);
        Snapshot revoked = LicenseLogic.applyServerStatus(unlocked, "revoked", T0 + 5);
        assertEquals(State.REVOKED, LicenseLogic.evaluate(revoked, T0 + 5, TRIAL_DAYS));
        Snapshot repaid = LicenseLogic.applyServerStatus(revoked, "paid", T0 + 9);
        assertEquals(State.UNLOCKED, LicenseLogic.evaluate(repaid, T0 + 9, TRIAL_DAYS));
    }

    @Test public void unknownOrGarbageServerAnswersChangeNothing() {
        Snapshot s = fresh();
        assertSame(s, LicenseLogic.applyServerStatus(s, null, T0));
        assertSame(s, LicenseLogic.applyServerStatus(s, "PAID", T0));
        assertSame(s, LicenseLogic.applyServerStatus(s, "<html>captive portal</html>", T0));
    }

    @Test public void unlockKeepsInstallIdAndFirstRun() {
        Snapshot unlocked = LicenseLogic.applyServerStatus(fresh(), "paid", T0 + 7);
        assertEquals(ID, unlocked.installId);
        assertEquals(T0, unlocked.firstRunMs);
        assertEquals(T0 + 7, unlocked.paidAtMs);
    }

    // ---- storage format ----

    @Test public void encodeDecodeRoundTrips() {
        Snapshot s = new Snapshot(ID, T0, Stored.UNLOCKED, T0 + 5, T0 + 9, T0 + 7);
        Snapshot back = LicenseLogic.decode(LicenseLogic.encode(s));
        assertNotNull(back);
        assertEquals(ID, back.installId);
        assertEquals(T0, back.firstRunMs);
        assertEquals(Stored.UNLOCKED, back.stored);
        assertEquals(T0 + 5, back.paidAtMs);
        assertEquals(T0 + 9, back.lastSeenMs);
        assertEquals(T0 + 7, back.lastCheckMs);
    }

    @Test public void decodeRejectsTamperedOrMalformedRecords() {
        String good = LicenseLogic.encode(fresh());
        assertNull(LicenseLogic.decode(null));
        assertNull(LicenseLogic.decode(""));
        assertNull(LicenseLogic.decode("garbage"));
        assertNull(LicenseLogic.decode(good.replace("v1|", "v2|")));
        assertNull(LicenseLogic.decode(good.replace(ID, "not-a-uuid")));
        assertNull(LicenseLogic.decode(good.replace("TRIAL", "FREE_FOREVER")));
        assertNull(LicenseLogic.decode(good + "|extra"));
        assertNull(LicenseLogic.decode(good.replace("|" + T0 + "|TRIAL", "|abc|TRIAL")));
        assertNull(LicenseLogic.decode(good.replace("|" + T0 + "|TRIAL", "|-5|TRIAL")));
    }

    @Test public void installIdMustBeUuidV4() {
        assertTrue(LicenseLogic.isValidInstallId(ID));
        assertTrue(!LicenseLogic.isValidInstallId(null));
        assertTrue(!LicenseLogic.isValidInstallId("3F2B8C1E-5D4A-4E7B-9A10-0C6D8E2F1A34"));   // upper case: backend rejects it
        assertTrue(!LicenseLogic.isValidInstallId(ID + "x"));
        assertTrue(!LicenseLogic.isValidInstallId("3f2b8c1e-5d4a-1e7b-9a10-0c6d8e2f1a34"));   // not version 4
    }
}
