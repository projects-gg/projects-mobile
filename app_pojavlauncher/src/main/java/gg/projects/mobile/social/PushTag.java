package gg.projects.mobile.social;

import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * SOC9 (soc9.md §2): the friends service's push tag. Once this phone asks for it ({@code "to": "tag"} in
 * {@code PUT /v1/push/device}), a push's {@code to} carries a tag instead of the Discord id, so the id no longer goes to
 * Google. The tag is 16 bytes of an HMAC over the session, base64url without padding (22 characters), and opaque: it is
 * never parsed. It belongs to one session (a new sign-in gets a new one, and so does a new server key), so it is kept
 * with a fingerprint of the session it came with and counts only for that session. Every registration's answer replaces
 * it; an answer without one clears it. A push is this phone's when its {@code to} is the signed-in account's id (an
 * older service, or one without its key) or this session's tag. Pure, with tests.
 */
public final class PushTag {
    private PushTag() {}

    private static final Pattern TAG = Pattern.compile("[A-Za-z0-9_-]{22}");

    public static boolean valid(@Nullable String tag) {
        return tag != null && TAG.matcher(tag).matches();
    }

    /**
     * A session's fingerprint for what this phone keeps beside the tag: 16 hex characters of SHA-256 over the
     * domain-separated session token. The token itself is never kept outside the session file.
     */
    static String fingerprint(String session) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(("projects-push-tag|" + session).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 8; i++) out.append(String.format(Locale.ROOT, "%02x", digest[i]));
            return out.toString();
        } catch (Exception e) {
            return Integer.toHexString(("projects-push-tag|" + session).hashCode());
        }
    }

    /** What is kept for a registration's answer: "<session fingerprint>|<tag>", or "" when there is no tag. */
    static String record(@Nullable String session, @Nullable String tag) {
        return session == null || !valid(tag) ? "" : fingerprint(session) + "|" + tag;
    }

    /** The tag kept for {@code session}, or null (none, another session's, or a damaged record). */
    @Nullable
    static String tagFor(@Nullable String record, @Nullable String session) {
        if (record == null || session == null) return null;
        int bar = record.indexOf('|');
        if (bar <= 0) return null;
        String tag = record.substring(bar + 1);
        return valid(tag) && record.substring(0, bar).equals(fingerprint(session)) ? tag : null;
    }

    /** A push's {@code to} is for this phone: the signed-in account's id, or this session's tag. Signed out: never. */
    public static boolean addressedTo(@Nullable String to, @Nullable String signedIn, @Nullable String ownTag) {
        if (to == null || signedIn == null) return false;
        return to.equals(signedIn) || (valid(ownTag) && to.equals(ownTag));
    }
}
