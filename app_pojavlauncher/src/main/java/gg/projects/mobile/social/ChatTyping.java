package gg.projects.mobile.social;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CHAT2 "Yazıyor…" (README "Chat extras"; the desktop's typing.ts): who writes where, for 6 seconds
 * after their last frame; their message arriving ends it at once, as does their going offline. The
 * player's own frames go at most every 4 seconds and only while there is something to say: the
 * screen decides when (the box has text and the focus, not while editing, the app in front, someone
 * else in the conversation online). Never stored; one timer at most, for the earliest end, and only
 * while someone writes. Pure Java.
 */
public final class ChatTyping {
    /** A writer shows this long after their last frame. */
    public static final long SHOW_MS = 6_000;
    /** The player's own frames go at most this often. */
    public static final long SEND_MS = 4_000;

    /** conv → writer → until (the phone's ms), in the order they started. */
    private final Map<String, LinkedHashMap<String, Long>> mWriters = new HashMap<>();
    /** conv → when the player's last frame went (ms). */
    private final Map<String, Long> mSent = new HashMap<>();

    /** {@code {"t":"chat_typing","conv","by"}} came: {@code by} writes in {@code conv}. */
    public void saw(String conv, String by, long nowMs) {
        LinkedHashMap<String, Long> people = mWriters.get(conv);
        if (people == null) {
            people = new LinkedHashMap<>();
            mWriters.put(conv, people);
        }
        people.put(by, nowMs + SHOW_MS);
    }

    /** Their message came: they are done writing. True when they were shown. */
    public boolean stop(String conv, String by) {
        LinkedHashMap<String, Long> people = mWriters.get(conv);
        if (people == null || people.remove(by) == null) return false;
        if (people.isEmpty()) mWriters.remove(conv);
        return true;
    }

    /** They went offline: they write nowhere now. True when they were shown somewhere. */
    public boolean offline(String by) {
        boolean changed = false;
        for (Iterator<Map.Entry<String, LinkedHashMap<String, Long>>> it = mWriters.entrySet().iterator(); it.hasNext(); ) {
            LinkedHashMap<String, Long> people = it.next().getValue();
            if (people.remove(by) != null) changed = true;
            if (people.isEmpty()) it.remove();
        }
        return changed;
    }

    /** The writers of a conversation now, in the order they started (the offline are taken out by {@link #offline}). */
    public List<String> writers(String conv, long nowMs) {
        List<String> out = new ArrayList<>();
        LinkedHashMap<String, Long> people = mWriters.get(conv);
        if (people == null) return out;
        for (Map.Entry<String, Long> e : people.entrySet()) if (e.getValue() > nowMs) out.add(e.getKey());
        return out;
    }

    /** Whether anyone writes in {@code conv} now (the list's "yazıyor…"). */
    public boolean anyone(String conv, long nowMs) {
        return !writers(conv, nowMs).isEmpty();
    }

    /** Drops the writers whose time is up; true when something went. */
    public boolean prune(long nowMs) {
        boolean changed = false;
        for (Iterator<Map.Entry<String, LinkedHashMap<String, Long>>> it = mWriters.entrySet().iterator(); it.hasNext(); ) {
            LinkedHashMap<String, Long> people = it.next().getValue();
            for (Iterator<Map.Entry<String, Long>> p = people.entrySet().iterator(); p.hasNext(); ) {
                if (p.next().getValue() <= nowMs) {
                    p.remove();
                    changed = true;
                }
            }
            if (people.isEmpty()) it.remove();
        }
        return changed;
    }

    /** When the next writer's time is up (ms), or 0 when nobody writes (no timer then). */
    public long nextEnd() {
        long next = 0;
        for (LinkedHashMap<String, Long> people : mWriters.values()) {
            for (long until : people.values()) if (next == 0 || until < next) next = until;
        }
        return next;
    }

    /** Signed out or the socket went: nobody is shown writing. */
    public void clear() {
        mWriters.clear();
        mSent.clear();
    }

    // ---- the player's own ----

    /**
     * The player typed in {@code conv}: whether a frame goes now (the first key, then at most every
     * {@link #SEND_MS}). The screen asks only while the box has text and the focus, not while editing.
     */
    public boolean wrote(String conv, long nowMs) {
        Long last = mSent.get(conv);
        if (last != null && nowMs - last < SEND_MS) return false;
        mSent.put(conv, nowMs);
        return true;
    }

    /** A message of the player's went: the next key starts a new frame at once. */
    public void sent(String conv) {
        mSent.remove(conv);
    }

    /**
     * The words a conversation shows, as name lists: one, two, or the first two and how many more
     * (the screen words them: "Ayşe yazıyor", "Ayşe ve Mert yazıyor", "Ayşe, Mert ve 2 kişi daha
     * yazıyor"). Null when nobody writes.
     */
    @Nullable
    public static int[] shape(List<String> names) {
        if (names.isEmpty()) return null;
        if (names.size() == 1) return new int[]{1, 0};
        if (names.size() == 2) return new int[]{2, 0};
        return new int[]{3, names.size() - 2};
    }
}
