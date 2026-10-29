package gg.projects.mobile.social;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * The friends feature's choices on this phone (never sent anywhere but the push registration's
 * {@code kinds}): favourites, which notifications the player wants, the one-time notification
 * offer, calls whose end was seen (FCM keeps no order) and the last push registration.
 */
public final class SocialPrefs {
    private static final String FILE = "projects_social";
    private static final String KEY_FAVOURITES = "favourites";
    private static final String KEY_WANT_REQUESTS = "notify_requests";
    private static final String KEY_WANT_INVITES = "notify_invites";
    /** Crew calls on this phone while the service has no {@code mute} (with it, the service's {@code calls}). */
    private static final String KEY_WANT_CALLS = "notify_calls";
    private static final String KEY_OFFER_DONE = "notify_offer_done";
    private static final String KEY_ENDED_CALLS = "ended_calls";
    private static final String KEY_PUSH_REGISTERED = "push_registered";
    /** SOC9: the push tag of the last registration's answer, with its session's fingerprint ({@link PushTag#record}). */
    private static final String KEY_PUSH_TAG = "push_tag";
    private static final String KEY_CREW_AUDIBLE = "crew_audible";
    private static final String KEY_CALLED_AT = "called_at";
    /** M19: the player's own crew call runs until then (seconds, this phone's clock); the game's process keeps it. */
    private static final String KEY_OWN_CALL_UNTIL = "own_call_until";

    private final SharedPreferences mPrefs;

    public SocialPrefs(Context context) {
        mPrefs = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public Set<String> favourites() {
        return Collections.unmodifiableSet(new HashSet<>(mPrefs.getStringSet(KEY_FAVOURITES, Collections.<String>emptySet())));
    }

    public void setFavourite(String id, boolean favourite) {
        Set<String> next = new HashSet<>(favourites());
        if (favourite) next.add(id);
        else next.remove(id);
        mPrefs.edit().putStringSet(KEY_FAVOURITES, next).apply();
    }

    public boolean wantsRequests() {
        return mPrefs.getBoolean(KEY_WANT_REQUESTS, true);
    }

    public boolean wantsInvites() {
        return mPrefs.getBoolean(KEY_WANT_INVITES, true);
    }

    public boolean wantsCallsLocally() {
        return mPrefs.getBoolean(KEY_WANT_CALLS, true);
    }

    /** M20: direct messages notify this phone ("Mesajlar"). */
    public boolean wantsMessages() {
        return mPrefs.getBoolean(KEY_WANT_MESSAGES, true);
    }

    private static final String KEY_WANT_PLAN_REMINDERS = "notify_plan_reminders";

    /** M21: "Plan hatırlatmaları": 10 minutes before a plan the player is coming to (local; nothing is sent). */
    public boolean wantsPlanReminders() {
        return mPrefs.getBoolean(KEY_WANT_PLAN_REMINDERS, true);
    }

    public void setWantsPlanReminders(boolean on) {
        mPrefs.edit().putBoolean(KEY_WANT_PLAN_REMINDERS, on).apply();
    }

    public void setWants(String kind, boolean on) {
        String key = PushMessage.REQUEST.equals(kind) ? KEY_WANT_REQUESTS
                : PushMessage.INVITE.equals(kind) ? KEY_WANT_INVITES
                : PushMessage.MESSAGE.equals(kind) ? KEY_WANT_MESSAGES : KEY_WANT_CALLS;
        mPrefs.edit().putBoolean(key, on).apply();
    }

    private static final String KEY_WANT_MESSAGES = "notify_messages";
    /** M20: conversations muted on this phone ("conv|until"), for pushes that come while the app sleeps. */
    private static final String KEY_MUTED_CONVERSATIONS = "muted_conversations";
    /** M20: the player's crews' names ("id|name"), so a call's notification can name its crew while the app sleeps. */
    private static final String KEY_CREW_NAMES = "crew_names";
    /**
     * FIX2: friends' and crewmates' names ("id|name"), so the game's own socket (which reads no list) names
     * who invites or calls.
     */
    private static final String KEY_PEOPLE_NAMES = "people_names";

    /** Conversations muted at {@code nowSeconds}. */
    public Set<String> mutedConversations(long nowSeconds) {
        Set<String> out = new HashSet<>();
        for (String entry : mPrefs.getStringSet(KEY_MUTED_CONVERSATIONS, Collections.<String>emptySet())) {
            int bar = entry.lastIndexOf('|');
            if (bar <= 0) continue;
            try {
                long until = Long.parseLong(entry.substring(bar + 1));
                if (until == ChatBook.MUTED_FOREVER || nowSeconds < until) out.add(entry.substring(0, bar));
            } catch (NumberFormatException ignored) {
                // A broken entry is forgotten.
            }
        }
        return out;
    }

    /** The chat's own record of a conversation's mute, kept here too (0: unmuted). */
    public void setConversationMuted(String conv, long untilSeconds, long nowSeconds) {
        Set<String> next = new HashSet<>();
        for (String entry : mPrefs.getStringSet(KEY_MUTED_CONVERSATIONS, Collections.<String>emptySet())) {
            int bar = entry.lastIndexOf('|');
            if (bar <= 0 || entry.substring(0, bar).equals(conv)) continue;
            try {
                long until = Long.parseLong(entry.substring(bar + 1));
                if (until == ChatBook.MUTED_FOREVER || nowSeconds < until) next.add(entry);
            } catch (NumberFormatException ignored) {
                // dropped
            }
        }
        if (untilSeconds == ChatBook.MUTED_FOREVER || untilSeconds > nowSeconds) next.add(conv + "|" + untilSeconds);
        mPrefs.edit().putStringSet(KEY_MUTED_CONVERSATIONS, next).apply();
    }

    /** The crews' names as last known (id → name). */
    public java.util.Map<String, String> crewNames() {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        for (String entry : mPrefs.getStringSet(KEY_CREW_NAMES, Collections.<String>emptySet())) {
            int bar = entry.indexOf('|');
            if (bar > 0) out.put(entry.substring(0, bar), entry.substring(bar + 1));
        }
        return out;
    }

    public void setCrewNames(java.util.Map<String, String> names) {
        Set<String> next = new HashSet<>();
        for (java.util.Map.Entry<String, String> e : names.entrySet()) next.add(e.getKey() + "|" + e.getValue());
        if (next.equals(mPrefs.getStringSet(KEY_CREW_NAMES, Collections.<String>emptySet()))) return;
        mPrefs.edit().putStringSet(KEY_CREW_NAMES, next).apply();
    }

    /** FIX2: friends' and crewmates' names as last known (id → name). */
    public java.util.Map<String, String> peopleNames() {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        for (String entry : mPrefs.getStringSet(KEY_PEOPLE_NAMES, Collections.<String>emptySet())) {
            int bar = entry.indexOf('|');
            if (bar > 0) out.put(entry.substring(0, bar), entry.substring(bar + 1));
        }
        return out;
    }

    public void setPeopleNames(java.util.Map<String, String> names) {
        Set<String> next = new HashSet<>();
        for (java.util.Map.Entry<String, String> e : names.entrySet()) next.add(e.getKey() + "|" + e.getValue());
        if (next.equals(mPrefs.getStringSet(KEY_PEOPLE_NAMES, Collections.<String>emptySet()))) return;
        mPrefs.edit().putStringSet(KEY_PEOPLE_NAMES, next).apply();
    }

    public boolean offerDone() {
        return mPrefs.getBoolean(KEY_OFFER_DONE, false);
    }

    public void setOfferDone() {
        mPrefs.edit().putBoolean(KEY_OFFER_DONE, true).apply();
    }

    /** Crew calls may notify, as last known (calls on and not muted), for pushes that come while the app sleeps. */
    public boolean crewAudible(long nowSeconds) {
        String value = mPrefs.getString(KEY_CREW_AUDIBLE, "1");
        if (value == null || value.equals("1")) return true;
        if (value.equals("0")) return false;
        // "u<seconds>": muted until then.
        try {
            return nowSeconds >= Long.parseLong(value.substring(1));
        } catch (RuntimeException e) {
            return true;
        }
    }

    public void setCrewSettings(SocialModel.TeamNotifications notifications) {
        String value;
        if (!notifications.calls) value = "0";
        else if (!notifications.muted) value = "1";
        else if (notifications.muteUntil == null) value = "0";
        else value = "u" + notifications.muteUntil;
        mPrefs.edit().putString(KEY_CREW_AUDIBLE, value).apply();
    }

    /** Calls whose end was seen and that may still be open by their {@code until} ("id:until"). */
    public Set<String> endedCalls(long nowSeconds) {
        Set<String> out = new HashSet<>();
        for (String entry : mPrefs.getStringSet(KEY_ENDED_CALLS, Collections.<String>emptySet())) {
            int colon = entry.lastIndexOf(':');
            if (colon <= 0) continue;
            try {
                if (Long.parseLong(entry.substring(colon + 1)) > nowSeconds) out.add(entry.substring(0, colon));
            } catch (NumberFormatException ignored) {
                // A broken entry is forgotten.
            }
        }
        return out;
    }

    public void addEndedCall(String callId, long untilSeconds, long nowSeconds) {
        Set<String> next = new HashSet<>();
        for (String entry : mPrefs.getStringSet(KEY_ENDED_CALLS, Collections.<String>emptySet())) {
            int colon = entry.lastIndexOf(':');
            try {
                if (colon > 0 && Long.parseLong(entry.substring(colon + 1)) > nowSeconds) next.add(entry);
            } catch (NumberFormatException ignored) {
                // dropped
            }
        }
        // A call lasts 20 minutes at most: kept that long when its end time is not known.
        next.add(callId + ":" + Math.max(untilSeconds, nowSeconds + 20 * 60));
        mPrefs.edit().putStringSet(KEY_ENDED_CALLS, next).apply();
    }

    /** The last push registration this phone made: "<user>|<session fingerprint>|<token hash>|<kinds>|<day>|tag", or "". */
    public String pushRegistered() {
        return mPrefs.getString(KEY_PUSH_REGISTERED, "");
    }

    public void setPushRegistered(String value) {
        mPrefs.edit().putString(KEY_PUSH_REGISTERED, value).apply();
    }

    /** SOC9: the push tag this session's registration got back, or null (none yet, or another session's). */
    @androidx.annotation.Nullable
    public String pushTag(@androidx.annotation.Nullable String session) {
        return PushTag.tagFor(mPrefs.getString(KEY_PUSH_TAG, ""), session);
    }

    /** SOC9: a registration's answer: its tag kept for this session, or none (an answer without one clears it). */
    public void setPushTag(@androidx.annotation.Nullable String session, @androidx.annotation.Nullable String tag) {
        String record = PushTag.record(session, tag);
        if (record.isEmpty()) mPrefs.edit().remove(KEY_PUSH_TAG).apply();
        else mPrefs.edit().putString(KEY_PUSH_TAG, record).apply();
    }

    /** When the player last called the crew from this phone (Unix seconds), for the 5-minute wait. */
    public long ownCallUntil() {
        return mPrefs.getLong(KEY_OWN_CALL_UNTIL, 0);
    }

    /** Written at once (commit): the game's process reads it right after the launcher's goes. */
    public void setOwnCallUntil(long seconds) {
        mPrefs.edit().putLong(KEY_OWN_CALL_UNTIL, seconds).commit();
    }

    public long calledAt() {
        return mPrefs.getLong(KEY_CALLED_AT, 0);
    }

    public void setCalledAt(long seconds) {
        mPrefs.edit().putLong(KEY_CALLED_AT, seconds).apply();
    }

    private static final String KEY_CONFIG = "config_features";

    /**
     * The signed config's {@code features.friends} / {@code features.teams} / {@code features.chat} as
     * last seen: a process started by a link or a push knows them before the content is read again.
     */
    public boolean[] lastConfig() {
        String value = mPrefs.getString(KEY_CONFIG, "000");
        if (value == null || value.length() < 2) value = "000";
        // 0.2.0 kept two flags; chat was not there then.
        if (value.length() == 2) value = value + "0";
        return new boolean[]{value.charAt(0) == '1', value.charAt(1) == '1', value.charAt(2) == '1'};
    }

    public void setLastConfig(boolean friends, boolean teams, boolean chat) {
        mPrefs.edit().putString(KEY_CONFIG, (friends ? "1" : "0") + (teams ? "1" : "0") + (chat ? "1" : "0")).apply();
    }

    /** Signed out: the choices about other players go; the notification switches stay. */
    public void clearAccount() {
        mPrefs.edit().remove(KEY_FAVOURITES).remove(KEY_ENDED_CALLS).remove(KEY_PUSH_REGISTERED).remove(KEY_PUSH_TAG)
                .remove(KEY_CREW_AUDIBLE).remove(KEY_CALLED_AT).remove(KEY_OWN_CALL_UNTIL)
                .remove(KEY_MUTED_CONVERSATIONS).remove(KEY_CREW_NAMES).remove(KEY_PEOPLE_NAMES).apply();
    }
}
