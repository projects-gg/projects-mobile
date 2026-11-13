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

    /** M053: the voice process's copy of the ignore list (the launcher process says when it changes). */
    private final java.util.Set<String> mIgnored = new java.util.HashSet<>();

    public void setIgnored(java.util.Collection<String> ids) {
        mIgnored.clear();
        mIgnored.addAll(ids);
    }

    @Override
    public boolean ignored(String person) {
        return mIgnored.contains(person);
    }

    @Override
    public void setVolume(String person, int percent) {
        SharedPreferences.Editor edit = people().edit();
        if (percent == 100) edit.remove("v:" + person);
        else edit.putInt("v:" + person, percent);
        touched(edit, person).apply();
    }

    // ---- MOB-WATCH: a streamer's sound (the voice process's, with each person's choices) ----

    @Override
    public int streamVolume(String person) {
        return StreamSound.clampVolume(people().getInt("sv:" + person, StreamSound.VOLUME_DEFAULT));
    }

    @Override
    public boolean streamMuted(String person) {
        return people().getBoolean("sm:" + person, false);
    }

    @Override
    public void setStream(String person, int volume, boolean muted) {
        SharedPreferences.Editor edit = people().edit();
        if (volume == StreamSound.VOLUME_DEFAULT) edit.remove("sv:" + person);
        else edit.putInt("sv:" + person, StreamSound.clampVolume(volume));
        if (muted) edit.putBoolean("sm:" + person, true);
        else edit.remove("sm:" + person);
        touched(edit, person).apply();
    }

    // ---- MOB-WATCH: "Yayın İzlerken" (the launcher process's; the voice process hears them through its link) ----

    private static final String STREAM_QUALITY = "stream_quality";
    private static final String DUCK = "duck";
    private static final String DUCK_LEVEL = "duck_level";
    private static final String DUCK_SELF = "duck_self";
    private static final String CELL_HINT = "stream_cell_hint";

    /** "Yayın Kalitesi": {@link StreamView#AUTO} (default), {@link StreamView#HIGH} or {@link StreamView#SAVER}. */
    public String streamQuality() {
        return StreamView.quality(mPrefs.getString(STREAM_QUALITY, StreamView.AUTO));
    }

    public void setStreamQuality(String quality) {
        mPrefs.edit().putString(STREAM_QUALITY, StreamView.quality(quality)).apply();
    }

    /** "Biri Konuşunca Yayın Sesini Kıs" (off by default, the desktop's). */
    public boolean duck() {
        return mPrefs.getBoolean(DUCK, false);
    }

    public void setDuck(boolean on) {
        mPrefs.edit().putBoolean(DUCK, on).apply();
    }

    /** "Kısma Miktarı" (percent, 20–90, 60 by default). */
    public int duckLevel() {
        return StreamSound.clampDuckLevel(mPrefs.getInt(DUCK_LEVEL, StreamSound.DUCK_LEVEL_DEFAULT));
    }

    public void setDuckLevel(int percent) {
        mPrefs.edit().putInt(DUCK_LEVEL, StreamSound.clampDuckLevel(percent)).apply();
    }

    /** "Ben Konuşunca da Kıs". */
    public boolean duckSelf() {
        return mPrefs.getBoolean(DUCK_SELF, false);
    }

    public void setDuckSelf(boolean on) {
        mPrefs.edit().putBoolean(DUCK_SELF, on).apply();
    }

    /** The one sentence about mobile data was shown (once per install). */
    public boolean cellHintShown() {
        return mPrefs.getBoolean(CELL_HINT, false);
    }

    public void setCellHintShown() {
        mPrefs.edit().putBoolean(CELL_HINT, true).apply();
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
        if (people >= PEOPLE_MAX && oldest != null) {
            edit.remove("t:" + oldest).remove("v:" + oldest).remove("m:" + oldest).remove("sv:" + oldest).remove("sm:" + oldest);
        }
        return edit;
    }
}
