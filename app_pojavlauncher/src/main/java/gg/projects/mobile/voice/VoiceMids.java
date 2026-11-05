package gg.projects.mobile.voice;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which person each receiver plays (§7.4; the desktop's {@code mids.ts}): every SFU offer names its new receivers,
 * mid → person. A person who comes back gets a new receiver; the old one is dead from then on, and a mid the SFU hands
 * out again belongs to whoever it names last.
 */
public final class VoiceMids {
    private final Map<String, String> byMid = new HashMap<>();
    private final Map<String, String> newest = new HashMap<>();

    /** An offer's receivers; returns the mids whose person changed (new ones included). */
    public List<String> assign(Map<String, String> mids) {
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, String> e : mids.entrySet()) {
            String person = e.getValue();
            if (person == null || person.isEmpty()) continue;
            String before = byMid.put(e.getKey(), person);
            newest.put(person, e.getKey());
            if (!person.equals(before)) changed.add(e.getKey());
        }
        return changed;
    }

    @Nullable
    public String personOf(String mid) {
        return byMid.get(mid);
    }

    /** Whether {@code mid} is its person's newest receiver (an older one is dead). */
    public boolean current(String mid) {
        String person = byMid.get(mid);
        return person != null && mid.equals(newest.get(person));
    }

    public void clear() {
        byMid.clear();
        newest.clear();
    }
}
