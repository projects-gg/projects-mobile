package gg.projects.mobile.voice;

import androidx.annotation.Nullable;

/**
 * What a voice action got (the desktop's {@code VoiceAnswer}): {@link #refused} null means it went through. The screens
 * map each refusal to the desktop's words ({@code tr-voice.ts} {@code refused.*}).
 */
public final class VoiceAnswer {
    /** Why an action was refused. */
    public enum Refusal {
        /** Voice chat is off (Studio, the month's share, not signed in): "Sesli sohbet şu an kullanılamıyor." */
        OFF,
        /** The friend cannot be called now (offline, voice chat off, or anything they need not tell). */
        UNREACHABLE,
        RATE_LIMITED,
        /** A limited (new) account's smaller quota. */
        ACCOUNT_LIMITED,
        /** A staff voice ban runs ({@link #until}). */
        STAFF_MUTED,
        ROOM_FULL,
        ROOMS_FULL,
        /** That room ended, or the ring's ticket expired. */
        ROOM_GONE,
        /** Removed from that room and not allowed back yet. */
        KICKED,
        NOT_MEMBER,
        NOT_ALLOWED,
        NOT_FOUND,
        INVALID_NAME,
        NAME_REJECTED,
        /** The voice service did not answer: "Bağlanamadı." with "Tekrar dene". */
        FAILED,
        /** Nothing to do that on (not in a room, no such ring). */
        NOTHING
    }

    public static final VoiceAnswer OK = new VoiceAnswer(null, null, null);

    @Nullable
    public final Refusal refused;
    /** Seconds to wait ({@code RATE_LIMITED}, {@code ACCOUNT_LIMITED}). */
    @Nullable
    public final Long retryAfter;
    /** Unix seconds ({@code ACCOUNT_LIMITED}, {@code STAFF_MUTED}). */
    @Nullable
    public final Long until;

    public VoiceAnswer(@Nullable Refusal refused, @Nullable Long retryAfter, @Nullable Long until) {
        this.refused = refused;
        this.retryAfter = retryAfter;
        this.until = until;
    }

    public static VoiceAnswer refused(Refusal why) {
        return new VoiceAnswer(why, null, null);
    }

    public boolean ok() {
        return refused == null;
    }

    /** The service's error code as a refusal ({@code voice_off}, {@code friend_not_found}, …), as the desktop reads it. */
    public static VoiceAnswer fromCode(@Nullable String code, @Nullable Long retryAfter, @Nullable Long until) {
        Refusal why;
        switch (code == null ? "" : code) {
            case "voice_off":
            case "unauthorized":
            case "account_restricted":
                why = Refusal.OFF;
                break;
            case "friend_not_found":
                why = Refusal.UNREACHABLE;
                break;
            case "rate_limited":
                why = Refusal.RATE_LIMITED;
                break;
            case "account_limited":
                why = Refusal.ACCOUNT_LIMITED;
                break;
            case "voice_muted":
                why = Refusal.STAFF_MUTED;
                break;
            case "room_full":
                why = Refusal.ROOM_FULL;
                break;
            case "voice_rooms_full":
                why = Refusal.ROOMS_FULL;
                break;
            case "room_not_found":
            case "room_closed":
            case "invalid_ticket":
                why = Refusal.ROOM_GONE;
                break;
            case "voice_kicked":
                why = Refusal.KICKED;
                break;
            case "team_not_found":
            case "not_member":
                why = Refusal.NOT_MEMBER;
                break;
            case "not_allowed":
                why = Refusal.NOT_ALLOWED;
                break;
            case "not_found":
                why = Refusal.NOT_FOUND;
                break;
            case "invalid_name":
                why = Refusal.INVALID_NAME;
                break;
            case "name_rejected":
                why = Refusal.NAME_REJECTED;
                break;
            case "room_not_empty":
                why = Refusal.NOTHING;
                break;
            default:
                why = Refusal.FAILED;
                break;
        }
        return new VoiceAnswer(why, retryAfter, until);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof VoiceAnswer)) return false;
        VoiceAnswer a = (VoiceAnswer) other;
        return refused == a.refused && eq(retryAfter, a.retryAfter) && eq(until, a.until);
    }

    @Override
    public int hashCode() {
        return refused == null ? 0 : refused.hashCode();
    }

    private static boolean eq(@Nullable Object a, @Nullable Object b) {
        return a == null ? b == null : a.equals(b);
    }

    @Override
    public String toString() {
        return refused == null ? "ok" : refused.name();
    }
}
