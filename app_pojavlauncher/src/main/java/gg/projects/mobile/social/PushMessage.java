package gg.projects.mobile.social;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A Firebase Cloud Messaging data message from the friends service (services/social README "Push
 * notifications", soc6.md) and what the app does with it. Every value is a string; {@code v} is the
 * format version ({@code "1"}; others are ignored) and {@code to} the recipient, so a message for an
 * account the app is no longer signed in to is dropped. Pure Java and JVM-tested; the Android side
 * only posts or cancels what {@link #decide} says.
 *
 * <p>M20: {@code message} (a direct message; never its text: "Ayşe sana mesaj gönderdi"), and a
 * call's {@code where}, {@code host} and {@code crews}.
 *
 * <p>SOC9: {@code to} is this session's push tag once the phone asked for one ({@link PushTag}), or the
 * Discord id (an older service, or one without its key); {@link #decide} takes either.
 */
public final class PushMessage {
    public static final String REQUEST = "request";
    public static final String INVITE = "invite";
    public static final String CALL = "call";
    public static final String CALL_END = "call_end";
    public static final String MESSAGE = "message";

    public final String type;
    /** The recipient: the Discord id, or (SOC9) the push tag of the session the phone registered with. */
    public final String to;
    /** The other player: the sender of a request, invite or message, the caller. */
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
    /** Invite and call: {@code projects} or {@code other}. */
    @Nullable
    public final String where;
    @Nullable
    public final String host;
    /** Message: its conversation ({@code d:<id>:<id>}); null when a tagged push left it out ({@link #conversation}). */
    @Nullable
    public final String conv;
    /** Call: the crews it names (the player keeps those they are in). */
    public final List<String> crews;

    private PushMessage(String type, String to, String sender, String name, @Nullable String id, long at, long until,
                        @Nullable String kind, @Nullable String where, @Nullable String host, @Nullable String conv,
                        List<String> crews) {
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
        this.conv = conv;
        this.crews = Collections.unmodifiableList(crews);
    }

    /** The message in {@code data}, or null when it is not one this app reads. */
    @Nullable
    public static PushMessage parse(@Nullable Map<String, String> data) {
        if (data == null || !"1".equals(data.get("v"))) return null;
        String type = data.get("type");
        String to = data.get("to");
        if (type == null || (!SocialModel.isUserId(to) && !PushTag.valid(to))) return null;
        String name = clean(data.get("name"));
        List<String> none = Collections.emptyList();
        switch (type) {
            case REQUEST: {
                String sender = data.get("sender");
                if (!SocialModel.isUserId(sender)) return null;
                return new PushMessage(type, to, sender, name, null, number(data.get("at")), 0, null, null, null, null, none);
            }
            case INVITE: {
                String sender = data.get("sender");
                String id = data.get("invite");
                if (!SocialModel.isUserId(sender) || !isId(id)) return null;
                long at = number(data.get("at"));
                long expires = number(data.get("expires"));
                if (expires <= 0) expires = at > 0 ? at + SocialModel.Invite.LIFETIME_SECONDS : 0;
                return new PushMessage(type, to, sender, name, id, at, expires, null, where(data.get("where")),
                        host(data.get("host")), null, none);
            }
            case CALL: {
                String caller = data.get("caller");
                String id = data.get("call");
                if (!SocialModel.isUserId(caller) || !isId(id)) return null;
                long until = number(data.get("until"));
                if (until <= 0) return null;
                String kind = SocialModel.Call.JOIN.equals(data.get("kind")) ? SocialModel.Call.JOIN : SocialModel.Call.READY;
                List<String> crews = new ArrayList<>();
                String list = data.get("crews");
                if (list != null) {
                    for (String crew : list.split(",")) {
                        String c = crew.trim();
                        if (SocialModel.isCrewId(c) && !crews.contains(c) && crews.size() < 10) crews.add(c);
                    }
                }
                return new PushMessage(type, to, caller, name, id, number(data.get("at")), until, kind,
                        where(data.get("where")), host(data.get("host")), null, crews);
            }
            case CALL_END: {
                String id = data.get("call");
                if (!isId(id)) return null;
                String caller = data.get("caller");
                return new PushMessage(type, to, SocialModel.isUserId(caller) ? caller : "", "", id, 0, 0, null, null, null,
                        null, none);
            }
            case MESSAGE: {
                String sender = data.get("sender");
                String conv = data.get("conv");
                if (!SocialModel.isUserId(sender)) return null;
                if (conv != null) {
                    // Only direct messages push, and only between the two players of the conversation (with a tag in
                    // "to", decide checks the signed-in player's side; the sender must be in it here already).
                    if (!ChatConv.isDirect(conv) || ChatConv.other(conv, sender) == null) return null;
                    if (SocialModel.isUserId(to) && !sender.equals(ChatConv.other(conv, to))) return null;
                } else if (SocialModel.isUserId(to)) {
                    if (sender.equals(to)) return null;
                    conv = ChatConv.direct(to, sender);
                }
                // SOC9: a tagged push may leave conv out (it would name the recipient's id): conversation() makes it from
                // the signed-in id and the sender.
                return new PushMessage(type, to, sender, name, null, number(data.get("at")), 0, null, null, null, conv, none);
            }
            default:
                return null;
        }
    }

    @Nullable
    private static String where(@Nullable String where) {
        return where == null ? null : ("projects".equals(where) ? "projects" : "other");
    }

    @Nullable
    private static String host(@Nullable String host) {
        return host != null && host.matches("[A-Za-z0-9.-]{1,253}") ? host : null;
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

    /**
     * A message's conversation: as the push names it, or (SOC9: a tagged push may leave it out, since it would name the
     * recipient's id) the signed-in player's direct conversation with the sender; null when there is none.
     */
    @Nullable
    public String conversation(@Nullable String signedIn) {
        if (conv != null) return conv;
        if (!MESSAGE.equals(type) || signedIn == null || signedIn.equals(sender) || !SocialModel.isUserId(signedIn)) return null;
        return ChatConv.direct(signedIn, sender);
    }

    /** The notification tag: a newer message of the same tag takes the older one's place. */
    public String tag() {
        switch (type) {
            case REQUEST: return "request:" + sender;
            case INVITE: return "invite:" + sender;
            case MESSAGE: return "message:" + conv;
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
        return decide(signedIn, nowSeconds, endedCalls, wanted, crewAudible, Collections.<String>emptySet());
    }

    /** @param mutedConversations conversations muted on this phone now: their messages notify nothing (the desktop's rule) */
    public Decision decide(@Nullable String signedIn, long nowSeconds, Set<String> endedCalls, Set<String> wanted,
                           boolean crewAudible, Set<String> mutedConversations) {
        return decide(signedIn, null, nowSeconds, endedCalls, wanted, crewAudible, mutedConversations);
    }

    /**
     * SOC9: {@code ownTag} is the push tag this session's registration got back (null: none); a push is this phone's when
     * its {@code to} is that tag or the signed-in account's id ({@link PushTag#addressedTo}).
     */
    public Decision decide(@Nullable String signedIn, @Nullable String ownTag, long nowSeconds, Set<String> endedCalls,
                           Set<String> wanted, boolean crewAudible, Set<String> mutedConversations) {
        if (!PushTag.addressedTo(to, signedIn, ownTag)) return Decision.drop("not for the signed-in account");
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
            case MESSAGE: {
                String conversation = conversation(signedIn);
                // The conversation's other player sent it to the signed-in one (an extra check: "to" may be a tag).
                if (conversation == null || !sender.equals(ChatConv.other(conversation, signedIn))) {
                    return Decision.drop("not this account's conversation");
                }
                if (!wanted.contains(MESSAGE)) return Decision.drop("messages off");
                // A message push lives a day at FCM: one far older than that is not news.
                if (at > 0 && nowSeconds - at > 86_400) return Decision.drop("expired");
                // "Sessize al" on a conversation: no notification at all; the unread count shows it in the app.
                return mutedConversations.contains(conversation) ? Decision.drop("conversation muted") : Decision.show(0);
            }
            default:
                return Decision.drop("unknown");
        }
    }
}
