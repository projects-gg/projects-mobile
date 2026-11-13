package gg.projects.mobile.voice;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SQOL-M (after 0.5.4): the room chat's sends shown the moment the player sends them. A send goes to the voice process
 * and comes back as a waiting message (about 200 ms on a phone); until then the screen shows its own copy (an echo).
 * The echo, the waiting message ({@code "p<rid>"}) and the message the room keeps (its {@code was}) are one row for the
 * list ({@link #key}): it changes in place, never leaves and comes back. Pure Java (JVM-tested); the screens' thread.
 */
public final class RoomChatEchoes {
    /** An echo nothing answered this long goes (the voice process had no stay to send it in). */
    public static final long KEEP_MS = 5_000;

    private final List<RoomChat.Line> echoes = new ArrayList<>();
    private final List<Long> born = new ArrayList<>();
    /** A row's later ids → its first id. */
    private final Map<String, String> alias = new HashMap<>();
    /** The player's waiting or refused sends already seen in a view. */
    private final Set<String> seen = new HashSet<>();
    private int seq;

    /** An echo of a send ({@code text} or {@code sticker}) made now; the caller redraws. */
    public RoomChat.Line add(@Nullable String text, @Nullable String sticker, String me, long nowMs) {
        RoomChat.Line l = new RoomChat.Line();
        l.id = "e" + (++seq);
        l.u = me;
        l.text = sticker == null ? (text == null ? "" : text) : null;
        l.st = sticker;
        l.state = RoomChat.PENDING;
        l.mine = true;
        echoes.add(l);
        born.add(nowMs);
        return l;
    }

    /**
     * The rows to show for {@code view} at {@code nowMs}: its lines, then the echoes no send of the view answers yet. A
     * send the view shows for the first time takes the oldest echo's place (and its row).
     */
    public List<RoomChat.Line> rows(@Nullable RoomChat.View view, long nowMs) {
        List<RoomChat.Line> out = new ArrayList<>();
        if (view != null) {
            for (RoomChat.Line line : view.lines) {
                boolean send = line.mine && line.at == 0;
                if (send && !seen.contains(line.id)) {
                    seen.add(line.id);
                    if (!echoes.isEmpty()) {
                        alias.put(line.id, keyOf(echoes.get(0).id));
                        echoes.remove(0);
                        born.remove(0);
                    }
                }
                // The room's copy keeps the row of the send it was.
                if (line.was != null && !alias.containsKey(line.id)) alias.put(line.id, keyOf(line.was));
                out.add(line);
            }
        }
        expire(nowMs);
        out.addAll(echoes);
        return out;
    }

    /** Echoes nothing answered within {@link #KEEP_MS} go. True when one went. */
    public boolean expire(long nowMs) {
        boolean gone = false;
        Iterator<Long> times = born.iterator();
        Iterator<RoomChat.Line> lines = echoes.iterator();
        while (times.hasNext() && lines.hasNext()) {
            long at = times.next();
            lines.next();
            if (nowMs - at >= KEEP_MS) {
                times.remove();
                lines.remove();
                gone = true;
            }
        }
        return gone;
    }

    public boolean waiting() {
        return !echoes.isEmpty();
    }

    /** The row a line belongs to (an echo's, a send's or the room's copy's first id). */
    public String key(RoomChat.Line line) {
        return keyOf(line.id);
    }

    private String keyOf(String id) {
        String first = id;
        // Short chains (echo → send → kept); a loop guard all the same.
        for (int i = 0; i < 4; i++) {
            String up = alias.get(first);
            if (up == null || up.equals(first)) break;
            first = up;
        }
        return first;
    }

    /** A new room (a move): nothing of the last one's sends. */
    public void clear() {
        echoes.clear();
        born.clear();
        alias.clear();
        seen.clear();
    }
}
