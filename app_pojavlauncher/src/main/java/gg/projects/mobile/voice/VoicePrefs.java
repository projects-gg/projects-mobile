package gg.projects.mobile.voice;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Voice chat's choices on this phone (the desktop's Settings → Sesli sohbet, the part a phone has): "Sesli sohbeti
 * kullan", "Aramadan önce sor", "Sesli sohbet sesleri", and each person's volume and "Benim için sustur" (by the friends
 * service's id). The switches are the launcher process's to change; the voice process reads them when a stay begins and
 * hears changes through its link. Each person's choices are the voice process's (the room sheet asks it to change them),
 * in a file of their own: two processes writing one preferences file would each write back their own copy of it.
 */
public final class VoicePrefs implements VoiceMachine.Prefs {
    private static final String FILE = "projects_voice";
    private static final String PEOPLE_FILE = "projects_voice_people";
    private static final String ENABLED = "enabled";
    private static final String ASK = "ask_before_call";
    private static final String SOUNDS = "sounds";
    /** People kept at most (the oldest change goes first). */
    private static final int PEOPLE_MAX = 300;

    private final SharedPreferences mPrefs;
    private final Context mApp;
    private SharedPreferences mPeople;
    private boolean mSounds;

    public VoicePrefs(Context context) {
        mApp = context.getApplicationContext();
        mPrefs = mApp.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        mSounds = mPrefs.getBoolean(SOUNDS, true);
    }

    /** Each person's choices: read only in the voice process. */
    private SharedPreferences people() {
        if (mPeople == null) mPeople = mApp.getSharedPreferences(PEOPLE_FILE, Context.MODE_PRIVATE);
        return mPeople;
    }

    public boolean enabled() {
        return mPrefs.getBoolean(ENABLED, true);
    }

    public void setEnabled(boolean on) {
        mPrefs.edit().putBoolean(ENABLED, on).apply();
    }

    public boolean askBeforeCall() {
        return mPrefs.getBoolean(ASK, true);
    }

    public void setAskBeforeCall(boolean on) {
        mPrefs.edit().putBoolean(ASK, on).apply();
    }

    public boolean soundsSaved() {
        return mPrefs.getBoolean(SOUNDS, true);
    }

    public void setSoundsSaved(boolean on) {
        mPrefs.edit().putBoolean(SOUNDS, on).apply();
    }

    /** The voice process's copy of "Sesli sohbet sesleri" (the launcher process says when it changes). */
    public void setSounds(boolean on) {
        mSounds = on;
    }

    @Override
    public boolean sounds() {
        return mSounds;
    }

    @Override
    public int volume(String person) {
        return people().getInt("v:" + person, 100);
    }

    @Override
    public boolean mutedForMe(String person) {
        return people().getBoolean("m:" + person, false);
    }

    @Override
    public void setVolume(String person, int percent) {
        SharedPreferences.Editor edit = people().edit();
        if (percent == 100) edit.remove("v:" + person);
        else edit.putInt("v:" + person, percent);
        touched(edit, person).apply();
    }

    @Override
    public void setMutedForMe(String person, boolean on) {
        SharedPreferences.Editor edit = people().edit();
        if (on) edit.putBoolean("m:" + person, true);
        else edit.remove("m:" + person);
        touched(edit, person).apply();
    }

    /** Keeps the list of people with choices bounded: the one changed longest ago goes past {@link #PEOPLE_MAX}. */
    private SharedPreferences.Editor touched(SharedPreferences.Editor edit, String person) {
        edit.putLong("t:" + person, System.currentTimeMillis());
        java.util.Map<String, ?> all = people().getAll();
        int people = 0;
        String oldest = null;
        long oldestAt = Long.MAX_VALUE;
        for (java.util.Map.Entry<String, ?> e : all.entrySet()) {
            if (!e.getKey().startsWith("t:") || !(e.getValue() instanceof Long)) continue;
            people++;
            long at = (Long) e.getValue();
            String id = e.getKey().substring(2);
            if (at < oldestAt && !id.equals(person)) {
                oldestAt = at;
                oldest = id;
            }
        }
        if (people >= PEOPLE_MAX && oldest != null) edit.remove("t:" + oldest).remove("v:" + oldest).remove("m:" + oldest);
        return edit;
    }
}
