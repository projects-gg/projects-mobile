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

    public void setWants(String kind, boolean on) {
        String key = PushMessage.REQUEST.equals(kind) ? KEY_WANT_REQUESTS
                : PushMessage.INVITE.equals(kind) ? KEY_WANT_INVITES : KEY_WANT_CALLS;
        mPrefs.edit().putBoolean(key, on).apply();
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

    /** The last push registration this phone made: "<user>|<token hash>|<kinds>|<day>", or "". */
    public String pushRegistered() {
        return mPrefs.getString(KEY_PUSH_REGISTERED, "");
    }

    public void setPushRegistered(String value) {
        mPrefs.edit().putString(KEY_PUSH_REGISTERED, value).apply();
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
     * The signed config's {@code features.friends} / {@code features.teams} as last seen: a process
     * started by a link or a push knows them before the content is read again.
     */
    public boolean[] lastConfig() {
        String value = mPrefs.getString(KEY_CONFIG, "00");
        if (value == null || value.length() != 2) value = "00";
        return new boolean[]{value.charAt(0) == '1', value.charAt(1) == '1'};
    }

    public void setLastConfig(boolean friends, boolean teams) {
        mPrefs.edit().putString(KEY_CONFIG, (friends ? "1" : "0") + (teams ? "1" : "0")).apply();
    }

    /** Signed out: the choices about other players go; the notification switches stay. */
    public void clearAccount() {
        mPrefs.edit().remove(KEY_FAVOURITES).remove(KEY_ENDED_CALLS).remove(KEY_PUSH_REGISTERED)
                .remove(KEY_CREW_AUDIBLE).remove(KEY_CALLED_AT).remove(KEY_OWN_CALL_UNTIL).apply();
    }
}
