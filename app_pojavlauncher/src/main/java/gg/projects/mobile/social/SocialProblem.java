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

    public SocialProblem(Kind kind, int status, @Nullable String code, long retryAfter) {
        super(kind + (code == null ? "" : " (" + code + ")") + " HTTP " + status);
        this.kind = kind;
        this.status = status;
        this.code = code;
        this.retryAfter = retryAfter;
    }

    public static SocialProblem unavailable(@Nullable String why) {
        return new SocialProblem(Kind.UNAVAILABLE, 0, why, 0);
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
        return new SocialProblem(kindOf(code, status, teamText), status, code, retry);
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
            default: return status >= 500 ? Kind.UNAVAILABLE : Kind.FAILED;
        }
    }
}
