package gg.projects.mobile.voice;

import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import gg.projects.mobile.social.Json;

/**
 * SQOL (room chat, {@code rchat1}; the lead's {@code sqol.md} §1.3, §2.2 and SQOL-S's wire notes): the text chat of the
 * room the player sits in, as the voice process holds it for the stay. Nothing is written to disk: the room keeps its
 * last messages and gives them again on every {@code hello} ({@code rchat_log}); a stay's chat goes with the stay.
 *
 * <p>The room's messages are kept in their order ({@link #lines}, by the room's clock), the player's own sends that wait
 * for their answer or did not go after them ({@link #outbox}). Messages of others that came after {@link #seenAt} are
 * unread; the first log of a stay is the room's past and counts as read.
 */
public final class RoomChat {
    /** Messages kept for the screens (the room gives a newcomer 50). */
    public static final int KEEP = 100;
    /** Sends waiting or refused at once (the room's own limit refuses more). */
    public static final int OUTBOX = 10;
    /** A report carries at most this many earlier messages of the room. */
    public static final int REPORT_BEFORE = 20;

    public static final String SENT = "sent";
    public static final String PENDING = "pending";
    public static final String FAILED = "failed";
    public static final String DELETED = "deleted";

    /** One message as the room sends it ({@code rchat.m}, {@code rchat_log.ms[]}, the sender's {@code ack.m}). */
    public static final class Line {
        /** The room's id (the decimal of {@link #at}); a send of the player's waiting for its answer: {@code "p<rid>"}. */
        public String id;
        public String u;
        /** Milliseconds on the room's clock; 0 for a send that has no answer yet. */
        public long at;
        @Nullable public String text;
        /** A sticker ({@code <set>/<id>}) in place of the text. */
        @Nullable public String st;
        /** The room's seal: never shown, a report sends it back ({@code mac}). */
        @Nullable public String seal;
        /** {@link #SENT}, {@link #PENDING}, {@link #FAILED} or {@link #DELETED}. */
        public String state = SENT;
        /** A send of the player's: its request on the room socket. */
        public int rid;
        /** {@link #DELETED}: {@code self}, {@code mod} or {@code staff}. */
        @Nullable public String by;
        /** {@link #FAILED}: the room's code ({@code chat_rejected}, {@code rate_limited}, …; {@code failed}: no answer). */
        @Nullable public String failure;
        /** {@code chat_rejected}: {@code link}, {@code phone} or {@code words}; with a link its rule. */
        @Nullable public String reason;
        @Nullable public String rule;
        /** {@code chat_muted} and {@code limited}: Unix seconds. */
        public long until;
        // Filled for the screens (RoomChat.View): the player's own, and whether the player may delete it.
        public boolean mine;
        public boolean canDelete;

        Line() {}

        Line copy() {
            Line l = new Line();
            l.id = id;
            l.u = u;
            l.at = at;
            l.text = text;
            l.st = st;
            l.seal = seal;
            l.state = state;
            l.rid = rid;
            l.by = by;
            l.failure = failure;
            l.reason = reason;
            l.rule = rule;
            l.until = until;
            l.mine = mine;
            l.canDelete = canDelete;
            return l;
        }

        public boolean sticker() {
            return st != null;
        }

        public boolean sent() {
            return SENT.equals(state);
        }

        /** A refused send that may go again as it is (a wait or a lost connection; never a refused text). */
        public boolean retriable() {
            return FAILED.equals(state) && ("rate_limited".equals(failure) || "failed".equals(failure));
        }

        /** A message as the room sends it, or null when it does not read (it changes nothing). */
        @Nullable
        public static Line read(@Nullable JsonObject o) {
            if (o == null) return null;
            String id = Json.str(o, "id");
            String u = Json.str(o, "u");
            Long at = Json.numOrNull(o, "at");
            String seal = Json.str(o, "seal");
            String text = Json.str(o, "text");
            String st = Json.str(o, "st");
            if (!isId(id) || u == null || u.isEmpty() || at == null || at <= 0 || seal == null || seal.isEmpty()) return null;
            if ((text == null) == (st == null)) return null;
            Line l = new Line();
            l.id = id;
            l.u = u;
            l.at = at;
            l.text = text;
            l.st = st;
            l.seal = seal;
            return l;
        }
    }

    /** A message id as the room names it: the decimal of a positive integer of at most 16 digits. */
    static boolean isId(@Nullable String id) {
        if (id == null || id.isEmpty() || id.length() > 16 || id.charAt(0) == '0') return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    private final String me;
    private final List<Line> lines = new ArrayList<>();
    private final List<Line> outbox = new ArrayList<>();
    /** The room offers its chat now ({@code hello.room.chat}, {@code room.chat}). */
    private boolean on;
    /** A friend room that once had three people or more: its chat is the room's for good. */
    private boolean grew;
    private long seenAt;
    /** The first log of the stay was read (it is the room's past: read). */
    private boolean primed;
    private long version;

    public RoomChat(String me) {
        this.me = me == null ? "" : me;
    }

    public boolean on() {
        return on;
    }

    public boolean grew() {
        return grew;
    }

    /** Grows with every change the screens should see. */
    public long version() {
        return version;
    }

    public long seenAt() {
        return seenAt;
    }

    /** Messages, oldest first. */
    public List<Line> lines() {
        return Collections.unmodifiableList(lines);
    }

    public List<Line> outbox() {
        return Collections.unmodifiableList(outbox);
    }

    private void changed() {
        version++;
    }

    /** The room says whether its chat is on; off takes its messages along (the room forgets them too). True on a change. */
    public boolean setOn(boolean value) {
        if (on == value) return false;
        on = value;
        if (!value) {
            lines.clear();
            outbox.clear();
            primed = false;
        }
        changed();
        return true;
    }

    /** True when it changed (once). */
    public boolean setGrew() {
        if (grew) return false;
        grew = true;
        changed();
        return true;
    }

    /** One new message ({@code rchat}); one the player has already is left as it is. */
    public boolean add(@Nullable Line line) {
        if (line == null || indexOf(line.id) >= 0) return false;
        int at = lines.size();
        while (at > 0 && lines.get(at - 1).at > line.at) at--;
        lines.add(at, line);
        trim();
        changed();
        return true;
    }

    /**
     * The room's last messages ({@code rchat_log}, after every {@code hello}): those the player has from the log's first
     * moment on are the log's, older ones stay; an empty log changes nothing (SQOL-S: a room whose sockets all dropped
     * forgot its past). The stay's first log is read.
     */
    public boolean merge(List<Line> log) {
        boolean first = !primed;
        primed = true;
        if (log.isEmpty()) return false;
        long from = log.get(0).at;
        for (Iterator<Line> it = lines.iterator(); it.hasNext(); ) {
            if (it.next().at >= from) it.remove();
        }
        for (Line line : log) {
            if (line != null && indexOf(line.id) < 0) lines.add(line);
        }
        Collections.sort(lines, (a, b) -> Long.compare(a.at, b.at));
        trim();
        if (first) {
            for (Line line : lines) seenAt = Math.max(seenAt, line.at);
        }
        changed();
        return true;
    }

    /** {@code rchat_deleted}: the message stays as a quiet line saying who took it; one the player does not have is nothing. */
    public boolean deleted(@Nullable String id, @Nullable String by) {
        int index = indexOf(id);
        if (index < 0) return false;
        Line line = lines.get(index);
        if (DELETED.equals(line.state)) return false;
        line.state = DELETED;
        line.by = "mod".equals(by) || "staff".equals(by) ? by : "self";
        line.text = null;
        line.st = null;
        changed();
        return true;
    }

    /** A send went out on {@code rid}: it shows at once, faded, until the room answers. */
    public void sending(int rid, @Nullable String text, @Nullable String sticker) {
        Line line = new Line();
        line.id = "p" + rid;
        line.u = me;
        line.rid = rid;
        line.text = sticker == null ? (text == null ? "" : text) : null;
        line.st = sticker;
        line.state = PENDING;
        outbox.add(line);
        while (outbox.size() > OUTBOX) outbox.remove(0);
        changed();
    }

    /** The room took the send of {@code rid}: its message as the room keeps it ({@code ack.m}) takes the send's place. */
    public boolean acked(int rid, @Nullable Line kept) {
        int index = outboxIndex(rid);
        if (index < 0) return false;
        outbox.remove(index);
        if (kept != null) add(kept);
        changed();
        return true;
    }

    /** The room refused the send of {@code rid}: it stays, marked, with the room's reason (a lost answer: {@code failed}). */
    public boolean failed(int rid, @Nullable String code, @Nullable String reason, @Nullable String rule, @Nullable Long until) {
        int index = outboxIndex(rid);
        if (index < 0) return false;
        Line line = outbox.get(index);
        line.state = FAILED;
        line.failure = code == null ? "failed" : code;
        line.reason = reason;
        line.rule = rule;
        line.until = until == null ? 0 : until;
        changed();
        return true;
    }

    /** The connection went: sends still waiting will never hear back (each may go again). Their rids. */
    public List<Integer> failWaiting() {
        List<Integer> rids = new ArrayList<>();
        for (Line line : outbox) {
            if (!PENDING.equals(line.state)) continue;
            line.state = FAILED;
            line.failure = "failed";
            rids.add(line.rid);
        }
        if (!rids.isEmpty()) changed();
        return rids;
    }

    /** A refused send, for "Tekrar dene"; null when there is none. */
    @Nullable
    public Line outboxLine(int rid) {
        int index = outboxIndex(rid);
        return index < 0 ? null : outbox.get(index);
    }

    /** "Sil" on a refused send. */
    public boolean discard(int rid) {
        int index = outboxIndex(rid);
        if (index < 0) return false;
        outbox.remove(index);
        changed();
        return true;
    }

    /** The player saw the chat up to {@code at} (the newest message shown). True when the unread count changed. */
    public boolean seen(long at) {
        if (at <= seenAt) return false;
        int before = unread();
        seenAt = at;
        changed();
        return unread() != before;
    }

    /** Messages of others the player has not seen (quiet lines and the player's own never count). */
    public int unread() {
        int n = 0;
        for (Line line : lines) {
            if (line.at > seenAt && line.sent() && !me.equals(line.u)) n++;
        }
        return n;
    }

    /** The message with {@code id}, or null. */
    @Nullable
    public Line line(@Nullable String id) {
        int index = indexOf(id);
        return index < 0 ? null : lines.get(index);
    }

    /** What a report about {@code line} carries before it: up to 20 earlier messages of the room that still read. */
    public List<Line> before(Line line) {
        List<Line> out = new ArrayList<>();
        for (Line l : lines) {
            if (l.at < line.at && l.sent() && l.seal != null) out.add(l);
        }
        return out.size() > REPORT_BEFORE ? new ArrayList<>(out.subList(out.size() - REPORT_BEFORE, out.size())) : out;
    }

    private int indexOf(@Nullable String id) {
        if (id == null) return -1;
        for (int i = 0; i < lines.size(); i++) if (id.equals(lines.get(i).id)) return i;
        return -1;
    }

    private int outboxIndex(int rid) {
        for (int i = 0; i < outbox.size(); i++) if (outbox.get(i).rid == rid) return i;
        return -1;
    }

    private void trim() {
        while (lines.size() > KEEP) lines.remove(0);
    }

    // ---- the screens' view ----

    /** Who may delete a message of {@code u} (the room's rule: the leader anyone's, a moderator a member's). */
    public interface Roles {
        boolean mayDelete(String u);
    }

    /** The chat as the screens show it ({@link #json}, {@link #read}): the stay's, null when there is none. */
    public static final class View {
        public String room;
        public String me = "";
        public boolean on;
        public boolean grew;
        public int unread;
        public long version;
        /** The room's messages, then the player's sends that wait or were refused. */
        public List<Line> lines = new ArrayList<>();

        private static final Gson GSON = new Gson();

        public String json() {
            return GSON.toJson(this);
        }

        @Nullable
        public static View read(@Nullable String json) {
            if (json == null || json.isEmpty()) return null;
            try {
                View view = GSON.fromJson(json, View.class);
                if (view == null || view.room == null) return null;
                if (view.lines == null) view.lines = new ArrayList<>();
                if (view.me == null) view.me = "";
                return view;
            } catch (RuntimeException e) {
                return null;
            }
        }

        /** The newest message of the room (not a send of the player's that waits), or null. */
        @Nullable
        public Line newest() {
            for (int i = lines.size() - 1; i >= 0; i--) {
                if (lines.get(i).at > 0) return lines.get(i);
            }
            return null;
        }

        @Nullable
        public Line line(String id) {
            for (Line l : lines) if (l.id.equals(id)) return l;
            return null;
        }

        /** Up to 20 earlier messages of the room that still read (a report's {@code before}). */
        public List<Line> before(Line line) {
            List<Line> out = new ArrayList<>();
            for (Line l : lines) {
                if (l.at > 0 && l.at < line.at && l.sent() && l.seal != null) out.add(l);
            }
            return out.size() > REPORT_BEFORE ? new ArrayList<>(out.subList(out.size() - REPORT_BEFORE, out.size())) : out;
        }
    }

    public View view(String room, Roles roles) {
        View v = new View();
        v.room = room;
        v.me = me;
        v.on = on;
        v.grew = grew;
        v.unread = unread();
        v.version = version;
        for (Line line : lines) {
            Line l = line.copy();
            l.mine = me.equals(l.u);
            l.canDelete = l.sent() && (l.mine || roles.mayDelete(l.u));
            v.lines.add(l);
        }
        for (Line line : outbox) {
            Line l = line.copy();
            l.mine = true;
            l.canDelete = false;
            v.lines.add(l);
        }
        return v;
    }
}
