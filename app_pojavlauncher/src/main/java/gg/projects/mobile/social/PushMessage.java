package gg.projects.mobile.social;

import androidx.annotation.Nullable;

import java.util.Map;
import java.util.Set;

/**
 * A Firebase Cloud Messaging data message from the friends service (services/social README "Push
 * notifications", soc6.md) and what the app does with it. Every value is a string; {@code v} is the
 * format version ({@code "1"}; others are ignored) and {@code to} the recipient, so a message for an
 * account the app is no longer signed in to is dropped. Pure Java and JVM-tested; the Android side
 * only posts or cancels what {@link #decide} says.
 */
public final class PushMessage {
    public static final String REQUEST = "request";
    public static final String INVITE = "invite";
    public static final String CALL = "call";
    public static final String CALL_END = "call_end";

    public final String type;
    public final String to;
    /** The other player: the sender of a request or invite, the caller. */
    public final String sender;
    /** Their display name (plain text, bounded); may be empty. */
    public final String name;
    /** The invite's or call's id. */
    @Nullable
    public final String id;
    public final long at;
    /** Invite: {@code expires}; call: {@code until} (Unix seconds); 0 when it has none. */
    public final long until;
    /** Call: {@code join} or {@code ready}. */
    @Nullable
    public final String kind;
    /** Invite: {@code projects} or {@code other}. */
    @Nullable
    public final String where;
    @Nullable
    public final String host;

    private PushMessage(String type, String to, String sender, String name, @Nullable String id, long at, long until,
                        @Nullable String kind, @Nullable String where, @Nullable String host) {
        this.type = type;
        this.to = to;
        this.sender = sender;
        this.name = name;
        this.id = id;
        this.at = at;
        this.until = until;
        this.kind = kind;
        this.where = where;
        this.host = host;
    }

    /** The message in {@code data}, or null when it is not one this app reads. */
    @Nullable
    public static PushMessage parse(@Nullable Map<String, String> data) {
        if (data == null || !"1".equals(data.get("v"))) return null;
        String type = data.get("type");
        String to = data.get("to");
        if (type == null || !SocialModel.isUserId(to)) return null;
        String name = clean(data.get("name"));
        switch (type) {
            case REQUEST: {
                String sender = data.get("sender");
                if (!SocialModel.isUserId(sender)) return null;
                return new PushMessage(type, to, sender, name, null, number(data.get("at")), 0, null, null, null);
            }
            case INVITE: {
                String sender = data.get("sender");
                String id = data.get("invite");
                if (!SocialModel.isUserId(sender) || !isId(id)) return null;
                long at = number(data.get("at"));
                long expires = number(data.get("expires"));
                if (expires <= 0) expires = at > 0 ? at + SocialModel.Invite.LIFETIME_SECONDS : 0;
                String host = data.get("host");
                if (host != null && !host.matches("[A-Za-z0-9.-]{1,253}")) host = null;
                String where = data.get("where");
                where = where == null ? null : ("projects".equals(where) ? "projects" : "other");
                return new PushMessage(type, to, sender, name, id, at, expires, null, where, host);
            }
            case CALL: {
                String caller = data.get("caller");
                String id = data.get("call");
                if (!SocialModel.isUserId(caller) || !isId(id)) return null;
                long until = number(data.get("until"));
                if (until <= 0) return null;
                String kind = SocialModel.Call.JOIN.equals(data.get("kind")) ? SocialModel.Call.JOIN : SocialModel.Call.READY;
                return new PushMessage(type, to, caller, name, id, number(data.get("at")), until, kind, null, null);
            }
            case CALL_END: {
                String id = data.get("call");
                if (!isId(id)) return null;
                String caller = data.get("caller");
                return new PushMessage(type, to, SocialModel.isUserId(caller) ? caller : "", "", id, 0, 0, null, null, null);
            }
            default:
                return null;
        }
    }

    private static boolean isId(@Nullable String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{1,64}");
    }

    private static long number(@Nullable String value) {
        if (value == null) return 0;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** A display name as plain text: control and invisible characters out, at most 40 characters. */
    static String clean(@Nullable String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length() && out.length() < 40; ) {
            int cp = value.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            if (Character.isISOControl(cp) || type == Character.FORMAT || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR || type == Character.SURROGATE) {
                continue;
            }
            out.appendCodePoint(cp);
        }
        return out.toString().trim();
    }

    /** The notification tag: a newer message of the same tag takes the older one's place. */
    public String tag() {
        switch (type) {
            case REQUEST: return "request:" + sender;
            case INVITE: return "invite:" + sender;
            default: return "call:" + id;
        }
    }

    /** What to do with a message. */
    public static final class Decision {
        public enum Action { SHOW, CANCEL, DROP }

        public final Action action;
        /** DROP: why (for the log). */
        public final String why;
        /** SHOW: how long the notification stays (ms; 0: until opened). */
        public final long timeoutMs;

        private Decision(Action action, String why, long timeoutMs) {
            this.action = action;
            this.why = why;
            this.timeoutMs = timeoutMs;
        }

        static Decision show(long timeoutMs) {
            return new Decision(Action.SHOW, "", timeoutMs);
        }

        static Decision cancel() {
            return new Decision(Action.CANCEL, "", 0);
        }

        static Decision drop(String why) {
            return new Decision(Action.DROP, why, 0);
        }
    }

    /**
     * @param signedIn the account the app is signed in to (null: none)
     * @param nowSeconds the phone's clock
     * @param endedCalls calls whose end the app already saw (FCM does not keep order)
     * @param wanted the kinds the player keeps on ({@code request}, {@code invite}, {@code call})
     * @param crewAudible crew calls may notify (calls on, not muted), from the last known settings
     */
    public Decision decide(@Nullable String signedIn, long nowSeconds, Set<String> endedCalls, Set<String> wanted,
                           boolean crewAudible) {
        if (signedIn == null || !signedIn.equals(to)) return Decision.drop("not for the signed-in account");
        switch (type) {
            case REQUEST:
                if (!wanted.contains(REQUEST)) return Decision.drop("requests off");
                return Decision.show(0);
            case INVITE:
                if (!wanted.contains(INVITE)) return Decision.drop("invites off");
                if (until > 0 && until <= nowSeconds) return Decision.drop("expired");
                return Decision.show(until > 0 ? (until - nowSeconds) * 1000L : SocialModel.Invite.LIFETIME_SECONDS * 1000L);
            case CALL:
                if (!wanted.contains(CALL) || !crewAudible) return Decision.drop("crew calls off");
                if (id != null && endedCalls.contains(id)) return Decision.drop("already ended");
                if (until <= nowSeconds) return Decision.drop("expired");
                return Decision.show((until - nowSeconds) * 1000L);
            case CALL_END:
                return Decision.cancel();
            default:
                return Decision.drop("unknown");
        }
    }
}
