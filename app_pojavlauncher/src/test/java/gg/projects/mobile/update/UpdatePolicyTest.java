package gg.projects.mobile.update;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * APK-UPD: the owner's card (30 October 2026). The check runs when the home screen opens, at most once a day
 * (a failed one again an hour later at the earliest); the card shows from the kept result while an update
 * waits and no snooze holds it: "Kapat" 2 hours, "7 gün sonra hatırlat" 7 days, a newer version at once.
 */
public class UpdatePolicyTest {
    private static final long H = UpdatePolicy.HOUR_MS;
    private static final long T = 1_793_620_800_000L;

    private static UpdateManifest manifest(int versionCode, int minSdk, String applicationId) throws UpdateException {
        String json = "{\"schema\":1,\"channel\":\"stable\",\"applicationId\":\"" + applicationId + "\",\"versionName\":\"0.4.6\","
                + "\"versionCode\":" + versionCode + ",\"minSdk\":" + minSdk + ",\"pubDate\":\"2026-11-02T12:00:00Z\",\"apk\":{\"urls\":"
                + "[\"https://cdn.projects.gg/v1/android/0.4.6/projects-launcher-0.4.6.apk\"],\"size\":10,"
                + "\"sha256\":\"5f2b0c1d9e8a7b6c5d4e3f2a1b0c9d8e7f6a5b4c3d2e1f0a9b8c7d6e5f4a3b2c\","
                + "\"signerSha256\":[\"0aab804584dfccff406c80f982d05b5ffec537a89132e2fb4fd3fedde8108ba3\"]}}";
        return UpdateManifest.parse(UpdateFixtures.utf8(json), "stable", false);
    }

    @Test
    public void theCheckRunsAtMostOnceADay() {
        assertTrue("first opening", UpdatePolicy.checkDue(true, T, 0, 0));
        assertFalse("switch off", UpdatePolicy.checkDue(false, T, 0, 0));
        assertFalse("checked this morning", UpdatePolicy.checkDue(true, T, T - 3 * H, T - 3 * H));
        assertFalse("a minute short of a day", UpdatePolicy.checkDue(true, T, T - 24 * H + 60_000, T - 24 * H + 60_000));
        assertTrue("a day later", UpdatePolicy.checkDue(true, T, T - 24 * H, T - 24 * H));
        assertFalse("switch off a week later", UpdatePolicy.checkDue(false, T, T - 7 * 24 * H, T - 7 * 24 * H));
    }

    @Test
    public void aFailedCheckWaitsAnHour() {
        // The day passed, but the last try (offline) was half an hour ago.
        assertFalse(UpdatePolicy.checkDue(true, T, T - 30 * H, T - H / 2));
        assertTrue(UpdatePolicy.checkDue(true, T, T - 30 * H, T - H));
        // Never succeeded, failed a minute ago: not at every opening.
        assertFalse(UpdatePolicy.checkDue(true, T, 0, T - 60_000));
        assertTrue(UpdatePolicy.checkDue(true, T, 0, T - 2 * H));
    }

    @Test
    public void aClockSetBackDoesNotBlockChecks() {
        assertTrue(UpdatePolicy.checkDue(true, T, T + 5 * H, T + 5 * H));
        assertTrue(UpdatePolicy.checkDue(true, T, T - 30 * H, T + H));
    }

    @Test
    public void onlyANewerVersionOfThisAppThePhoneCanRun() throws UpdateException {
        assertTrue(UpdatePolicy.offered(manifest(22, 21, "gg.projects.mobile"), "gg.projects.mobile", 21, 34));
        assertFalse("the same version", UpdatePolicy.offered(manifest(21, 21, "gg.projects.mobile"), "gg.projects.mobile", 21, 34));
        assertFalse("an older one", UpdatePolicy.offered(manifest(20, 21, "gg.projects.mobile"), "gg.projects.mobile", 21, 34));
        assertFalse("another app (a debug build)", UpdatePolicy.offered(manifest(22, 21, "gg.projects.mobile"), "gg.projects.mobile.debug", 21, 34));
        assertFalse("needs a newer Android", UpdatePolicy.offered(manifest(22, 26, "gg.projects.mobile"), "gg.projects.mobile", 21, 24));
        assertTrue("exactly the minimum", UpdatePolicy.offered(manifest(22, 26, "gg.projects.mobile"), "gg.projects.mobile", 21, 26));
        assertFalse("nothing known", UpdatePolicy.offered(null, "gg.projects.mobile", 21, 34));
    }

    @Test
    public void closeHoldsTheCardTwoHours() {
        UpdatePolicy.Snooze closed = UpdatePolicy.Snooze.close(22, T);
        assertFalse(UpdatePolicy.cardShows(true, 22, closed, T));
        assertFalse(UpdatePolicy.cardShows(true, 22, closed, T + 2 * H - 1));
        assertTrue(UpdatePolicy.cardShows(true, 22, closed, T + 2 * H));
        assertTrue(UpdatePolicy.cardShows(true, 22, null, T));
        assertFalse("no update, no card", UpdatePolicy.cardShows(false, 22, null, T));
    }

    @Test
    public void remindLaterHoldsItSevenDays() {
        UpdatePolicy.Snooze later = UpdatePolicy.Snooze.remindLater(22, T);
        assertFalse(UpdatePolicy.cardShows(true, 22, later, T + 3 * 24 * H));
        assertFalse(UpdatePolicy.cardShows(true, 22, later, T + 7 * 24 * H - 1));
        assertTrue(UpdatePolicy.cardShows(true, 22, later, T + 7 * 24 * H));
    }

    @Test
    public void aNewerVersionShowsAtOnce() {
        UpdatePolicy.Snooze later = UpdatePolicy.Snooze.remindLater(22, T);
        assertTrue(UpdatePolicy.cardShows(true, 23, later, T + H));
        assertTrue(UpdatePolicy.cardShows(true, 23, UpdatePolicy.Snooze.close(22, T), T + 1));
        // A snooze given for a newer version also holds an older one (a channel moved back).
        assertFalse(UpdatePolicy.cardShows(true, 21, later, T + H));
    }

    @Test
    public void aClockSetBackEndsASnooze() {
        UpdatePolicy.Snooze later = UpdatePolicy.Snooze.remindLater(22, T);
        assertTrue(UpdatePolicy.cardShows(true, 22, later, T - 24 * H));
    }
}
