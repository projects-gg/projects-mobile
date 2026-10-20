package gg.projects.mobile.social;

import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * The phone's half of the Discord sign-in (services/social README "Sign-in"; the desktop's
 * {@code pl-social} auth.rs): the app makes a random {@code verifier} and opens the service's start
 * page with {@code challenge = BASE64URL(SHA-256(verifier))}; the browser comes back through
 * {@code projects://auth/done?c=<one-time code>}, and the app claims the session with both.
 *
 * <p>A deep link counts only while an attempt of this app waits and has not expired (the service's
 * state lasts 10 minutes): a link nobody here started is ignored. One claim per attempt: the
 * service uses the pending sign-in up on the first claim with the right verifier, whatever the
 * code, so a failed claim means starting again, never retrying with the same code. Pure Java.
 */
public final class SignInFlow {
    /** The service's OAuth state lasts this long. */
    public static final long ATTEMPT_SECONDS = 10 * 60;

    /** A started sign-in. */
    public static final class Attempt {
        public final String verifier;
        public final String challenge;
        public final long startedAt;

        public Attempt(String verifier, String challenge, long startedAt) {
            this.verifier = verifier;
            this.challenge = challenge;
            this.startedAt = startedAt;
        }

        public boolean expired(long nowSeconds) {
            return nowSeconds < startedAt - 60 || nowSeconds >= startedAt + ATTEMPT_SECONDS;
        }
    }

    public enum Phase { IDLE, STARTING, WAITING, CLAIMING }

    private Phase mPhase = Phase.IDLE;
    @Nullable
    private Attempt mAttempt;

    public Phase phase() {
        return mPhase;
    }

    @Nullable
    public Attempt attempt() {
        return mAttempt;
    }

    /** The service is being asked whether it is up (the browser opens only when it is). */
    public void starting() {
        mPhase = Phase.STARTING;
        mAttempt = null;
    }

    /** A new attempt; the browser opens {@link #startUrl}. */
    public Attempt begin(SecureRandom random, long nowSeconds) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String verifier = base64Url(bytes);
        mAttempt = new Attempt(verifier, challengeOf(verifier), nowSeconds);
        mPhase = Phase.WAITING;
        return mAttempt;
    }

    /** An attempt kept from before Android ended the app (the browser step may still come back). */
    public void restore(@Nullable Attempt attempt, long nowSeconds) {
        if (attempt != null && !attempt.expired(nowSeconds) && challengeOf(attempt.verifier).equals(attempt.challenge)) {
            mAttempt = attempt;
            mPhase = Phase.WAITING;
        }
    }

    /**
     * A {@code projects://auth/done} link came. Returns the attempt to claim with {@code code}, which
     * is then used up; null when nothing of this app waits (the link is ignored).
     */
    @Nullable
    public Attempt onDeepLink(@Nullable String code, long nowSeconds) {
        if (code == null || mPhase != Phase.WAITING || mAttempt == null) return null;
        if (mAttempt.expired(nowSeconds)) {
            reset();
            return null;
        }
        Attempt attempt = mAttempt;
        mAttempt = null;
        mPhase = Phase.CLAIMING;
        return attempt;
    }

    /** The claim ended (signed in, or failed: a new sign-in starts from the beginning). */
    public void reset() {
        mPhase = Phase.IDLE;
        mAttempt = null;
    }

    /** True while the screen should say "Tarayıcıda Discord'u onayla". */
    public boolean waiting(long nowSeconds) {
        if (mPhase != Phase.WAITING || mAttempt == null) return false;
        if (mAttempt.expired(nowSeconds)) {
            reset();
            return false;
        }
        return true;
    }

    public static String startUrl(String serviceBase, String challenge) {
        return (serviceBase.endsWith("/") ? serviceBase : serviceBase + "/") + "auth/discord/start?challenge=" + challenge;
    }

    /** {@code BASE64URL(SHA-256(verifier))} without padding (RFC 7636's S256). */
    public static String challengeOf(String verifier) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return base64Url(sha.digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final char[] URL_SAFE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();

    /** Base64url without padding (android.util.Base64 is not there in JVM tests). */
    public static String base64Url(byte[] data) {
        StringBuilder out = new StringBuilder((data.length * 4 + 2) / 3);
        int i = 0;
        while (i + 3 <= data.length) {
            int n = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8) | (data[i + 2] & 0xFF);
            out.append(URL_SAFE[(n >> 18) & 63]).append(URL_SAFE[(n >> 12) & 63])
                    .append(URL_SAFE[(n >> 6) & 63]).append(URL_SAFE[n & 63]);
            i += 3;
        }
        int rest = data.length - i;
        if (rest == 1) {
            int n = (data[i] & 0xFF) << 16;
            out.append(URL_SAFE[(n >> 18) & 63]).append(URL_SAFE[(n >> 12) & 63]);
        } else if (rest == 2) {
            int n = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8);
            out.append(URL_SAFE[(n >> 18) & 63]).append(URL_SAFE[(n >> 12) & 63]).append(URL_SAFE[(n >> 6) & 63]);
        }
        return out.toString();
    }
}
