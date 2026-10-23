package gg.projects.mobile.social;

import androidx.annotation.Nullable;

import com.google.gson.JsonObject;

/**
 * What went wrong with a request to the friends service, in the words the player sees (the
 * desktop's error.rs texts; codes are never shown). Built from the HTTP status and the service's
 * {@code {"error": {"code", "message", "retryAfter"}}} envelope; a Cloudflare page without the
 * envelope counts as the service being away.
 */
public final class SocialProblem extends Exception {
    public enum Kind {
        /** No connection, a timeout, 5xx, 503 or a platform page. */
        UNAVAILABLE,
        /** 401: the session ended (sign in again). */
        SIGNED_OUT,
        RATE_LIMITED,
        NOT_A_CODE,
        NO_ONE_WITH_CODE,
        OWN_CODE,
        ALREADY_FRIENDS,
        ALREADY_REQUESTED,
        YOU_BLOCKED,
        LIST_FULL,
        THEIR_LIST_FULL,
        TOO_MANY_REQUESTS,
        THEY_HAVE_TOO_MANY_REQUESTS,
        REQUEST_GONE,
        FRIEND_GONE,
        NOT_A_CREW_CODE,
        NO_CREW_WITH_CODE,
        CREW_INVITE_GONE,
        MEMBER_GONE,
        ALREADY_IN_CREW,
        ALREADY_MEMBER,
        CREW_FULL,
        LEADER_ONLY,
        NOT_PERMITTED,
        HAND_OVER_FIRST,
        BLOCKED_SOMEONE_IN_CREW,
        CREW_INVITES_FULL,
        CREW_NAME,
        CREW_NAME_REJECTED,
        CREW_TAG,
        CREW_MOTTO,
        CREW_MOTTO_REJECTED,
        CREW_TEXT_TODAY,
        CALL_ACTIVE,
        CALL_ENDED,
        NOT_IN_CREW,
        /** TEAM4: as many crews as the service allows ({@code limit_crews}). */
        CREW_LIMIT,
        /** TEAM4: the order named other crews than the player's ({@code crews_changed}: read the list again). */
        CREWS_CHANGED,
        /** CALL1: not on a multiplayer server. */
        CALL_NEEDS_SERVER,
        /** CALL1: on another server while its address is not shown to friends. */
        CALL_SERVER_HIDDEN,
        /** SPAM1: a new Discord account may not do this yet ({@link #item}, {@link #until}). */
        ACCOUNT_LIMITED,
        /** MOD1: the account is suspended ({@link #until}) or banned ({@link #until} null). */
        ACCOUNT_RESTRICTED,
        /** SPAM1: no new accounts today. */
        SIGNUPS_CLOSED,
        /** MOD1: moderators reset the crew's texts; locked until {@link #until}. */
        TEXT_LOCKED,
        /** MOD1: the report's target is not one the player sees ({@code target_not_found}). */
        TARGET_GONE,
        /** 1,000 blocks. */
        BLOCKS_FULL,
        /** CHAT1 refusals (socket). */
        CHAT_OFF,
        CHAT_MUTED,
        CHAT_REJECTED,
        MESSAGE_GONE,
        CHAT_NOT_PERMITTED,
        CHAT_TOO_OLD,
        /** TEAM5: the crew approves newcomers and the join went without {@code request} ({@code team_approval_required}). */
        APPROVAL_REQUIRED,
        /** TEAM5: no such waiting join request any more ({@code team_request_not_found}). */
        JOIN_REQUEST_GONE,
        /** TEAM5: the crew has 20 join requests waiting ({@code limit_team_requests}). */
        CREW_REQUESTS_FULL,
        /** TEAM5: the player has 3 join requests waiting ({@code limit_outgoing_team_requests}). */
        TOO_MANY_JOIN_REQUESTS,
        /** TEAM5 plans (socket): not a quarter hour, past, or more than 7 days ahead. */
        PLAN_TIME,
        /** TEAM5: the crew has 3 upcoming plans. */
        PLANS_FULL,
        /** TEAM5: the crew has a plan at that time ({@link #plan}: its id). */
        PLAN_EXISTS,
        /** TEAM5: not a plan the player sees, or it is over. */
        PLAN_GONE,
        /** FIX2: the plan's note was turned away ({@code invalid_plan_note}; {@link #reason}: link, phone, words, or none). */
        PLAN_NOTE,
        /** TEAM6: the crew's leader hid it: its tag cannot be worn ({@code team_hidden}). */
        TEAM_HIDDEN,
        /** TEAM6: Studio switched crew tags off ({@code badges_off}). */
        BADGES_OFF,
        /** TEAM6: the tag's card was stale: no requests from it now ({@code badge_request_closed}). */
        BADGE_REQUEST_CLOSED,
        /** TEAM6: the crew took its 10 requests from its tag today ({@code limit_badge_requests}). */
        BADGE_REQUESTS_TODAY,
        /** CHAT2: five of the player's on the message ({@link #reason} {@code mine}) or ten kinds ({@code kinds}). */
        CHAT_REACTIONS_FULL,
        /** CHAT2: Studio switched the extra off ({@code chat_feature_off}, {@link #item}). */
        CHAT_FEATURE_OFF,
        /** Anything else: "Bir sorun oluştu." */
        FAILED
    }

    public final Kind kind;
    public final int status;
    /** The service's code, for the log only. */
    @Nullable
    public final String code;
    /** Seconds to wait ({@code retryAfter} / {@code Retry-After}), or 0. */
    public final long retryAfter;
    /** {@code until} of a limited, restricted or locked answer and of a chat mute (Unix seconds); null when none. */
    @Nullable
    public final Long until;
    /** {@code item} of {@code account_limited} (which limit); null otherwise. */
    @Nullable
    public final String item;
    /** {@code reason} of a {@code chat_rejected} ({@code link}, {@code phone}, {@code words}). */
    @Nullable
    public final String reason;
    /** TEAM5: {@code plan} of a {@code plan_exists} (the plan at that time); null otherwise. */
    @Nullable
    public final String plan;

    public SocialProblem(Kind kind, int status, @Nullable String code, long retryAfter) {
        this(kind, status, code, retryAfter, null, null, null);
    }

    public SocialProblem(Kind kind, int status, @Nullable String code, long retryAfter, @Nullable Long until,
                         @Nullable String item, @Nullable String reason) {
        this(kind, status, code, retryAfter, until, item, reason, null);
    }

    public SocialProblem(Kind kind, int status, @Nullable String code, long retryAfter, @Nullable Long until,
                         @Nullable String item, @Nullable String reason, @Nullable String plan) {
        super(kind + (code == null ? "" : " (" + code + ")") + " HTTP " + status);
        this.kind = kind;
        this.status = status;
        this.code = code;
        this.retryAfter = retryAfter;
        this.until = until;
        this.item = item;
        this.reason = reason;
        this.plan = plan;
    }

    public static SocialProblem unavailable(@Nullable String why) {
        return new SocialProblem(Kind.UNAVAILABLE, 0, why, 0);
    }

    /** A refusal on the presence socket ({@code {"t":"error","code",…}}; TEAM5: {@code item} and {@code plan} too). */
    public static SocialProblem ofSocket(JsonObject error) {
        String code = Json.str(error, "code", "bad_request");
        return new SocialProblem(socketKind(code), 0, code, Json.num(error, "retryAfter", 0), Json.numOrNull(error, "until"),
                Json.str(error, "item"), Json.str(error, "reason"), Json.str(error, "plan"));
    }

    /** What a socket refusal means; the crew and chat codes differ from the HTTP ones. */
    static Kind socketKind(String code) {
        switch (code) {
            case "rate_limited": return Kind.RATE_LIMITED;
            case "call_active": return Kind.CALL_ACTIVE;
            case "call_not_found": return Kind.CALL_ENDED;
            case "team_not_permitted": return Kind.NOT_PERMITTED;
            case "team_not_found": return Kind.NOT_IN_CREW;
            case "call_needs_server": return Kind.CALL_NEEDS_SERVER;
            case "call_server_hidden": return Kind.CALL_SERVER_HIDDEN;
            case "friend_not_found": return Kind.FRIEND_GONE;
            case "chat_off": return Kind.CHAT_OFF;
            case "chat_muted": return Kind.CHAT_MUTED;
            case "chat_rejected": return Kind.CHAT_REJECTED;
            case "message_not_found": return Kind.MESSAGE_GONE;
            case "chat_not_permitted": return Kind.CHAT_NOT_PERMITTED;
            case "chat_too_old": return Kind.CHAT_TOO_OLD;
            case "account_limited": return Kind.ACCOUNT_LIMITED;
            case "plan_time": return Kind.PLAN_TIME;
            case "limit_team_plans": return Kind.PLANS_FULL;
            case "plan_exists": return Kind.PLAN_EXISTS;
            case "plan_not_found": return Kind.PLAN_GONE;
            case "invalid_plan_note": return Kind.PLAN_NOTE;
            case "chat_reactions_full": return Kind.CHAT_REACTIONS_FULL;
            case "chat_feature_off": return Kind.CHAT_FEATURE_OFF;
            case "unavailable":
            case "internal": return Kind.UNAVAILABLE;
            default: return Kind.FAILED;
        }
    }

    /**
     * The problem of an answer with {@code status} and {@code body}; {@code retryAfterHeader} is the
     * {@code Retry-After} header (or null). {@code teamText} says the request changed a crew's name,
     * tag or motto (a 429 then means the crew's ten text changes a day).
     */
    public static SocialProblem of(int status, @Nullable String body, @Nullable String retryAfterHeader, boolean teamText) {
        JsonObject error = Json.obj(Json.parseObject(body), "error");
        String code = Json.str(error, "code");
        long retry = Json.num(error, "retryAfter", 0);
        if (retry <= 0 && retryAfterHeader != null) {
            try {
                retry = Long.parseLong(retryAfterHeader.trim());
            } catch (NumberFormatException ignored) {
                retry = 0;
            }
        }
        if (code == null) {
            // A platform page without our envelope: a limit of the platform, like a 503.
            if (status == 401) return new SocialProblem(Kind.SIGNED_OUT, status, null, 0);
            if (status == 429) return new SocialProblem(Kind.RATE_LIMITED, status, null, retry);
            return new SocialProblem(status >= 500 || status == 0 ? Kind.UNAVAILABLE : Kind.FAILED, status, null, retry);
        }
        // until: null means "for good" on account_restricted, so a present null stays null and a missing one too.
        return new SocialProblem(kindOf(code, status, teamText), status, code, retry, Json.numOrNull(error, "until"),
                Json.str(error, "item"), Json.str(error, "reason"));
    }

    static Kind kindOf(String code, int status, boolean teamText) {
        switch (code) {
            case "unauthorized": return Kind.SIGNED_OUT;
            case "rate_limited": return teamText ? Kind.CREW_TEXT_TODAY : Kind.RATE_LIMITED;
            case "unavailable":
            case "internal": return Kind.UNAVAILABLE;
            case "invalid_code": return Kind.NOT_A_CODE;
            case "code_not_found":
            case "user_not_found": return Kind.NO_ONE_WITH_CODE;
            case "self": return Kind.OWN_CODE;
            case "already_friends": return Kind.ALREADY_FRIENDS;
            case "already_requested": return Kind.ALREADY_REQUESTED;
            case "blocked": return Kind.YOU_BLOCKED;
            case "limit_friends": return Kind.LIST_FULL;
            case "limit_friends_target": return Kind.THEIR_LIST_FULL;
            case "limit_outgoing_requests": return Kind.TOO_MANY_REQUESTS;
            case "limit_incoming_requests": return Kind.THEY_HAVE_TOO_MANY_REQUESTS;
            case "request_not_found": return Kind.REQUEST_GONE;
            case "friend_not_found": return Kind.FRIEND_GONE;
            case "team_not_found": return Kind.NO_CREW_WITH_CODE;
            case "team_invite_not_found": return Kind.CREW_INVITE_GONE;
            case "member_not_found": return Kind.MEMBER_GONE;
            case "already_in_team": return Kind.ALREADY_IN_CREW;
            case "already_member": return Kind.ALREADY_MEMBER;
            case "limit_team_members": return Kind.CREW_FULL;
            case "team_founder_only": return Kind.LEADER_ONLY;
            case "team_not_permitted": return Kind.NOT_PERMITTED;
            case "founder_must_transfer": return Kind.HAND_OVER_FIRST;
            case "blocked_member": return Kind.BLOCKED_SOMEONE_IN_CREW;
            case "limit_team_invites": return Kind.CREW_INVITES_FULL;
            case "invalid_team_name": return Kind.CREW_NAME;
            case "team_name_rejected": return Kind.CREW_NAME_REJECTED;
            case "invalid_team_tag": return Kind.CREW_TAG;
            case "invalid_team_motto": return Kind.CREW_MOTTO;
            case "team_motto_rejected": return Kind.CREW_MOTTO_REJECTED;
            case "call_active": return Kind.CALL_ACTIVE;
            case "call_not_found": return Kind.CALL_ENDED;
            case "limit_crews": return Kind.CREW_LIMIT;
            case "crews_changed": return Kind.CREWS_CHANGED;
            case "account_limited": return Kind.ACCOUNT_LIMITED;
            case "account_restricted": return Kind.ACCOUNT_RESTRICTED;
            case "signups_closed": return Kind.SIGNUPS_CLOSED;
            case "team_text_locked": return Kind.TEXT_LOCKED;
            case "target_not_found": return Kind.TARGET_GONE;
            case "limit_blocks": return Kind.BLOCKS_FULL;
            case "team_approval_required": return Kind.APPROVAL_REQUIRED;
            case "team_request_not_found": return Kind.JOIN_REQUEST_GONE;
            case "limit_team_requests": return Kind.CREW_REQUESTS_FULL;
            case "limit_outgoing_team_requests": return Kind.TOO_MANY_JOIN_REQUESTS;
            case "team_hidden": return Kind.TEAM_HIDDEN;
            case "badges_off": return Kind.BADGES_OFF;
            case "badge_request_closed": return Kind.BADGE_REQUEST_CLOSED;
            case "limit_badge_requests": return Kind.BADGE_REQUESTS_TODAY;
            default: return status >= 500 ? Kind.UNAVAILABLE : Kind.FAILED;
        }
    }

    /** The account may not use the friends service now (suspended or banned): the session ends. */
    public boolean restricted() {
        return kind == Kind.ACCOUNT_RESTRICTED;
    }
}
