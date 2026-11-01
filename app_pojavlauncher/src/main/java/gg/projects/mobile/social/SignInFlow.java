package gg.projects.mobile.social;

import androidx.annotation.Nullable;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * The phone's half of the friends sign-in (services/social README "Sign-in"; the desktop's
 * {@code pl-social} auth.rs): the app makes a random {@code verifier} and opens the service's start
 * page with {@code challenge = BASE64URL(SHA-256(verifier))}; the browser comes back through
 * {@code projects://auth/done?c=<one-time code>}, and the app claims the session with both.
 *
 * <p>A deep link counts only while an attempt of this app waits and has not expired (the service's
 * state lasts 10 minutes): a link nobody here started is ignored. One claim per attempt: the
 * service uses the pending sign-in up on the first claim with the right verifier, whatever the
 * code, so a failed claim means starting again, never retrying with the same code. Pure Java.
 *
 * <p>SOC-ID: the same steps for every way in ({@link #DISCORD}, {@link #MICROSOFT}), and for linking a way in to the
 * signed-in account or removing one ({@link Purpose#LINK}, {@link Purpose#UNLINK}): the service then hands the page to
 * open ({@code {url}}), and the link that comes back is claimed at {@code v1/me/identities/claim} instead (only while
 * such an attempt waits; the same link while a sign-in waits goes to {@code v1/auth/claim}).
 */
public final class SignInFlow {
    /** The service's OAuth state lasts this long. */
    public static final long ATTEMPT_SECONDS = 10 * 60;

    /** SOC-ID: the ways in the service knows (its provider names). */
    public static final String DISCORD = "discord";
    public static final String MICROSOFT = "microsoft";

    /** Where the claim of an attempt goes. */
    public static final String CLAIM_SIGN_IN = "v1/auth/claim";
    public static final String CLAIM_IDENTITY = "v1/me/identities/claim";

    /** SOC-ID: what an attempt is for. */
    public enum Purpose {
        /** A new session (signed out). */
        SIGN_IN,
        /** Another way in for the signed-in account. */
        LINK,
        /** A way in leaves the signed-in account (the browser proves a remaining one first). */
        UNLINK;

        static Purpose of(@Nullable String name) {
            if ("link".equals(name)) return LINK;
            if ("unlink".equals(name)) return UNLINK;
            return SIGN_IN;
        }

        /** The name kept in the session file. */
        public String key() {
            return name().toLowerCase(Locale.ROOT).replace("_", "");
        }
    }

    public static boolean isProvider(@Nullable String provider) {
        return DISCORD.equals(provider) || MICROSOFT.equals(provider);
    }

    /** A started sign-in (or link, or removal). */
    public static final class Attempt {
        public final String verifier;
        public final String challenge;
        public final long startedAt;
        /** SOC-ID: the way in the browser signs in with, links or removes. */
        public final String provider;
        public final Purpose purpose;
        /** SOC-ID: the page the service handed for a link or a removal (null for a sign-in: its page is built). */
        @Nullable
        public final String url;

        public Attempt(String verifier, String challenge, long startedAt) {
            this(verifier, challenge, startedAt, DISCORD, Purpose.SIGN_IN, null);
        }

        public Attempt(String verifier, String challenge, long startedAt, @Nullable String provider, Purpose purpose,
                       @Nullable String url) {
            this.verifier = verifier;
            this.challenge = challenge;
            this.startedAt = startedAt;
            this.provider = isProvider(provider) ? provider : DISCORD;
            this.purpose = purpose == null ? Purpose.SIGN_IN : purpose;
            this.url = url;
        }

        /** The same attempt with the page the service handed for it. */
        public Attempt withUrl(String page) {
            return new Attempt(verifier, challenge, startedAt, provider, purpose, page);
        }

        public boolean expired(long nowSeconds) {
            return nowSeconds < startedAt - 60 || nowSeconds >= startedAt + ATTEMPT_SECONDS;
        }

        /** SOC-ID: a link or a removal of the signed-in account (not a new session). */
        public boolean forIdentity() {
            return purpose != Purpose.SIGN_IN;
        }

        /** Where the link's code is claimed: a new session, or the signed-in account's ways in. */
        public String claimPath() {
            return forIdentity() ? CLAIM_IDENTITY : CLAIM_SIGN_IN;
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
        return begin(random, nowSeconds, DISCORD);
    }

    /** SOC-ID: a new sign-in with {@code provider}; the browser opens {@link #startUrl(String, String, String)}. */
    public Attempt begin(SecureRandom random, long nowSeconds, String provider) {
        Attempt attempt = make(random, nowSeconds, provider, Purpose.SIGN_IN);
        waitFor(attempt);
        return attempt;
    }

    /** SOC-ID: an attempt that does not wait yet (a link or a removal asks the service for its page first). */
    public static Attempt make(SecureRandom random, long nowSeconds, String provider, Purpose purpose) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String verifier = base64Url(bytes);
        return new Attempt(verifier, challengeOf(verifier), nowSeconds, provider, purpose, null);
    }

    /** SOC-ID: the browser opens for {@code attempt} now: a link that comes back counts for it. */
    public void waitFor(Attempt attempt) {
        mAttempt = attempt;
        mPhase = Phase.WAITING;
    }

    /** An attempt kept from before Android ended the app (the browser step may still come back). */
    public void restore(@Nullable Attempt attempt, long nowSeconds) {
        if (attempt != null && !attempt.expired(nowSeconds) && challengeOf(attempt.verifier).equals(attempt.challenge)
                && (!attempt.forIdentity() || attempt.url != null)) {
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

    /** True while the screen should say "Tarayıcıda Discord'u onayla" (or Microsoft's words). */
    public boolean waiting(long nowSeconds) {
        if (mPhase != Phase.WAITING || mAttempt == null) return false;
        if (mAttempt.expired(nowSeconds)) {
            reset();
            return false;
        }
        return true;
    }

    /** SOC-ID: the attempt that waits for the browser and is about the signed-in account (null: none). */
    @Nullable
    public Attempt identityWaiting(long nowSeconds) {
        return waiting(nowSeconds) && mAttempt != null && mAttempt.forIdentity() ? mAttempt : null;
    }

    public static String startUrl(String serviceBase, String challenge) {
        return startUrl(serviceBase, DISCORD, challenge);
    }

    /** SOC-ID: {@code {base}/auth/<provider>/start?challenge=…} (Discord's page as before). */
    public static String startUrl(String serviceBase, String provider, String challenge) {
        String way = isProvider(provider) ? provider : DISCORD;
        return (serviceBase.endsWith("/") ? serviceBase : serviceBase + "/") + "auth/" + way + "/start?challenge=" + challenge;
    }

    /**
     * SOC-ID: the page a link or a removal opens ({@code {url}} of the service's answer), only on the service's own
     * host and scheme (an answer cannot send the browser anywhere else); null otherwise.
     */
    @Nullable
    public static String pageUrl(String serviceBase, @Nullable String url) {
        if (url == null || url.length() > 4096) return null;
        try {
            URI base = new URI(serviceBase);
            URI page = new URI(url);
            if (page.getScheme() == null || page.getHost() == null || base.getScheme() == null || base.getHost() == null) return null;
            if (!page.getScheme().equalsIgnoreCase(base.getScheme())) return null;
            if (!"https".equalsIgnoreCase(page.getScheme()) && !"http".equalsIgnoreCase(page.getScheme())) return null;
            if (!page.getHost().equalsIgnoreCase(base.getHost()) || page.getPort() != base.getPort()) return null;
            if (page.getUserInfo() != null) return null;
            return page.toString();
        } catch (Exception e) {
            return null;
        }
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
