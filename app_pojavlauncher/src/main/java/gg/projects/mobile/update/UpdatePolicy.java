package gg.projects.mobile.update;

/**
 * APK-UPD: when the app asks the channel and when the home card shows the update (the owner, 30 October
 * 2026). The network check and the card are separate: the check runs only when the home screen opens, at
 * most once a day; the card shows from the last verified result on every opening while an update waits
 * and no snooze holds it. "Kapat" holds it 2 hours, "7 gün sonra hatırlat" 7 days; a snooze belongs to the
 * version it was given for, so a newer version shows at once. Plain Java, JVM-tested.
 */
public final class UpdatePolicy {
    private UpdatePolicy() {}

    public static final long HOUR_MS = 60L * 60 * 1000;
    /** At most one successful check a day. */
    public static final long CHECK_EVERY_MS = 24 * HOUR_MS;
    /** A failed check (offline, a server problem) is tried again at an opening at least this much later. */
    public static final long RETRY_AFTER_MS = HOUR_MS;
    /** "Kapat". */
    public static final long CLOSE_MS = 2 * HOUR_MS;
    /** "7 gün sonra hatırlat". */
    public static final long REMIND_MS = 7 * 24 * HOUR_MS;

    /**
     * Whether an opening of the home screen asks the channel. A clock set back (a time in the future) never
     * blocks a check for longer than it would have.
     *
     * @param lastSuccess when the last check that read and verified the channel finished (0: never)
     * @param lastAttempt when the last check finished, successful or not (0: never)
     */
    public static boolean checkDue(boolean enabled, long now, long lastSuccess, long lastAttempt) {
        if (!enabled) return false;
        boolean dayPassed = lastSuccess <= 0 || now < lastSuccess || now - lastSuccess >= CHECK_EVERY_MS;
        boolean retryPassed = lastAttempt <= 0 || now < lastAttempt || now - lastAttempt >= RETRY_AFTER_MS;
        return dayPassed && retryPassed;
    }

    /**
     * Whether {@code manifest} is an update for this app: the same application id, a higher version code,
     * and a minimum Android version the phone has.
     */
    public static boolean offered(UpdateManifest manifest, String applicationId, long installedVersionCode, int sdk) {
        return manifest != null && manifest.applicationId.equals(applicationId)
                && manifest.versionCode > installedVersionCode && manifest.minSdk <= sdk;
    }

    /** A snooze: the version it was given for, when, and until when. */
    public static final class Snooze {
        public final int versionCode;
        public final long setAt;
        public final long until;

        public Snooze(int versionCode, long setAt, long until) {
            this.versionCode = versionCode;
            this.setAt = setAt;
            this.until = until;
        }

        /** "Kapat": 2 hours. */
        public static Snooze close(int versionCode, long now) {
            return new Snooze(versionCode, now, now + CLOSE_MS);
        }

        /** "7 gün sonra hatırlat": 7 days. */
        public static Snooze remindLater(int versionCode, long now) {
            return new Snooze(versionCode, now, now + REMIND_MS);
        }

        /**
         * Whether this snooze hides the card for {@code versionCode} now: only for its own version (or an
         * older one), only until it runs out, and not when the clock went back past the moment it was given.
         */
        public boolean holds(int versionCode, long now) {
            return versionCode <= this.versionCode && now >= setAt && now < until;
        }
    }

    /** Whether the card shows: an update waits and no snooze holds it. */
    public static boolean cardShows(boolean offered, int versionCode, Snooze snooze, long now) {
        return offered && (snooze == null || !snooze.holds(versionCode, now));
    }
}
