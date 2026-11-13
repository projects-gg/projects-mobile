package gg.projects.mobile.voice;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import gg.projects.mobile.social.Json;

/**
 * The room socket's frames ({@code GET /v1/voice?ticket=…}; the friends service's README "Voice (VOICE-S1)" → Room
 * socket, its types in {@code services/social/src/voice-wire.ts}), as the phone sends and reads them. A port of the
 * desktop's {@code crates/pl-voice/src/wire.rs}: the same frames, the same readings of unknown values.
 *
 * <p>Tickets, SDP and TURN credentials never reach a log line: {@link Message#toString()} names only the kind.
 */
public final class VoiceWire {
    private VoiceWire() {}

    // ---- ids ----

    /** Whether {@code room} is a room id the service gives ({@code [fgc]} + 16 hex digits). */
    public static boolean isRoomId(@Nullable String room) {
        if (room == null || room.length() != 17) return false;
        char kind = room.charAt(0);
        if (kind != 'f' && kind != 'g' && kind != 'c') return false;
        for (int i = 1; i < 17; i++) {
            char c = room.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
        }
        return true;
    }

    /** A room's kind from its id: {@code f…} a friend room, {@code g…} and {@code c…} crew rooms. */
    public static boolean isFriendRoom(String room) {
        return room.startsWith("f");
    }

    /** The id of a crew's "Genel" room. */
    public static String generalRoomOf(String crew) {
        return "g" + crew;
    }

    /** A connection quality as clients report it: 0 good, 1 weak, 2 bad. */
    public static int quality(long value) {
        return (int) Math.max(0, Math.min(2, value));
    }

    // ---- roles ----

    public enum Role {
        LEADER("l"), MODERATOR("m"), MEMBER("u");

        public final String wire;

        Role(String wire) {
            this.wire = wire;
        }

        /** Anything unknown is a member (the desktop's {@code #[serde(other)]}). */
        public static Role of(@Nullable String wire) {
            if ("l".equals(wire)) return LEADER;
            if ("m".equals(wire)) return MODERATOR;
            return MEMBER;
        }
    }

    // ---- client → room ----

    /** {@code mod.act}. */
    public enum ModAct {
        MUTE, UNMUTE, KICK, UNKICK, MOVE, MOD, UNMOD, NAME, CLOSE,
        /** M053: "Paylaşımı durdur" (the leader or a moderator stops someone's screen share; the kick's rule). */
        UNSHARE;

        public String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * M051 (SHARE-LIVE on the phone): the room socket's capabilities. {@code share1} lets the phone hear who shares their
     * screen ({@code state.sh}, {@code hello.people[].sh}), {@code live1} each share's picture time ({@code th}). The phone
     * never shares or watches: it sends no share frame but {@link #unwatch} for a watch left over from the desktop.
     */
    static final String[] CAPS = {"share1", "live1", "share2", "rchat1"};
    // SQOL: share2 lets the phone hear a share's state ({@code st}: hold, min; it still sends no share frame), rchat1 the
    // room's text chat ({@code rchat}, {@code rchat_log}, {@code rchat_deleted}; hello.room.chat and .grew).

    /** SQOL: a text message to the room ({@code rchat}, 300 characters, the chat's own checks on the room's side). */
    public static String roomChat(String text, int rid) {
        JsonObject o = frame("rchat");
        o.addProperty("rid", rid);
        o.addProperty("text", text);
        return o.toString();
    }

    /** SQOL: a sticker ({@code <set>/<id>}) to the room. */
    public static String roomSticker(String sticker, int rid) {
        JsonObject o = frame("rchat");
        o.addProperty("rid", rid);
        o.addProperty("st", sticker);
        return o.toString();
    }

    /** SQOL: deletes a message of the room ({@code rchat_del}: the player's own, or as the room's leader or moderator). */
    public static String roomChatDelete(String id, int rid) {
        JsonObject o = frame("rchat_del");
        o.addProperty("rid", rid);
        o.addProperty("id", id);
        return o.toString();
    }

    /** {@code join}: {@code sdp} publishes the microphone (null: listen only), {@code mid} its m-line. */
    public static String join(@Nullable String sdp, @Nullable String mid) {
        JsonObject o = frame("join");
        if (sdp != null) o.addProperty("sdp", sdp);
        if (mid != null) o.addProperty("mid", mid);
        o.add("caps", caps());
        return o.toString();
    }

    public static String sync() {
        JsonObject o = frame("sync");
        o.add("caps", caps());
        return o.toString();
    }

    /** {@code unwatch}: no longer watch {@code u}'s share {@code n} (the phone shows no stream). */
    public static String unwatch(String u, int n) {
        JsonObject o = frame("unwatch");
        o.addProperty("u", u);
        o.addProperty("n", n);
        return o.toString();
    }

    private static JsonArray caps() {
        JsonArray caps = new JsonArray();
        for (String cap : CAPS) caps.add(cap);
        return caps;
    }

    public static String answer(String sdp) {
        JsonObject o = frame("answer");
        o.addProperty("sdp", sdp);
        return o.toString();
    }

    public static String set(boolean mute, boolean deaf) {
        JsonObject o = frame("set");
        o.addProperty("mute", mute);
        o.addProperty("deaf", deaf);
        return o.toString();
    }

    public static String qualityFrame(int value) {
        JsonObject o = frame("q");
        o.addProperty("v", quality((long) value));
        return o.toString();
    }

    public static String turn(int rid) {
        JsonObject o = frame("turn");
        o.addProperty("rid", rid);
        return o.toString();
    }

    public static String invite(String to, int rid) {
        JsonObject o = frame("invite");
        o.addProperty("to", to);
        o.addProperty("rid", rid);
        return o.toString();
    }

    public static String cancel(String to, int rid) {
        JsonObject o = frame("cancel");
        o.addProperty("to", to);
        o.addProperty("rid", rid);
        return o.toString();
    }

    public static String mod(ModAct act, @Nullable String to, @Nullable String room, @Nullable String name, int rid) {
        JsonObject o = frame("mod");
        o.addProperty("act", act.wire());
        if (to != null) o.addProperty("to", to);
        if (room != null) o.addProperty("room", room);
        if (name != null) o.addProperty("name", name);
        o.addProperty("rid", rid);
        return o.toString();
    }

    /** M053: {@code {"t":"mod","act":"unshare","to","n","rid"}}: stops share {@code n} of {@code to} (needs {@code share1}). */
    public static String unshare(String to, int n, int rid) {
        JsonObject o = frame("mod");
        o.addProperty("act", ModAct.UNSHARE.wire());
        o.addProperty("to", to);
        o.addProperty("n", n);
        o.addProperty("rid", rid);
        return o.toString();
    }

    public static String leave() {
        return frame("leave").toString();
    }

    private static JsonObject frame(String type) {
        JsonObject o = new JsonObject();
        o.addProperty("t", type);
        return o;
    }

    // ---- room → client ----

    /** A person in a room as others see them ({@code hello.people} holds the player too). */
    public static final class Person {
        public final String u;
        /** When they came in (Unix seconds): the order of "longest present". */
        public final long j;
        public final boolean mute;
        public final boolean deaf;
        /** Muted for everyone: nobody plays them. */
        public final boolean sm;
        public final int q;
        /** VOICE-S2: their card (names a friend's friend), or null. */
        @Nullable
        public final Card card;
        /** M051 (SHARE-LIVE): their screen shares now, by number (empty: none). */
        public final List<Share> sh;

        public Person(String u, long j, boolean mute, boolean deaf, boolean sm, int q, @Nullable Card card) {
            this(u, j, mute, deaf, sm, q, card, Collections.<Share>emptyList());
        }

        public Person(String u, long j, boolean mute, boolean deaf, boolean sm, int q, @Nullable Card card, List<Share> sh) {
            this.u = u;
            this.j = j;
            this.mute = mute;
            this.deaf = deaf;
            this.sm = sm;
            this.q = q;
            this.card = card;
            this.sh = sh;
        }

        public Person with(@Nullable Boolean mute, @Nullable Boolean deaf, @Nullable Boolean sm, @Nullable Integer q) {
            return new Person(u, j, mute == null ? this.mute : mute, deaf == null ? this.deaf : deaf,
                    sm == null ? this.sm : sm, q == null ? this.q : q, card, sh);
        }

        public Person withShares(List<Share> shares) {
            return new Person(u, j, mute, deaf, sm, q, card, shares);
        }

        @Nullable
        static Person read(@Nullable JsonObject o) {
            String u = Json.str(o, "u");
            if (u == null || u.isEmpty()) return null;
            return new Person(u, Json.num(o, "j", 0), Json.bool(o, "mute", false), Json.bool(o, "deaf", false),
                    Json.bool(o, "sm", false), quality(Json.num(o, "q", 0)), Card.read(Json.obj(o, "card")),
                    Share.list(Json.arr(o, "sh")));
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Person)) return false;
            Person p = (Person) other;
            return u.equals(p.u) && j == p.j && mute == p.mute && deaf == p.deaf && sm == p.sm && q == p.q && sh.equals(p.sh);
        }

        @Override
        public int hashCode() {
            return u.hashCode();
        }
    }

    /**
     * VOICE-S2: a person's card as the friends list shows one (no crew tag), so a friend's friend is named and drawn. A
     * card that does not read is no card: it never costs the person.
     */
    public static final class Card {
        public final String id;
        public final String name;
        @Nullable
        public final String avatar;
        @Nullable
        public final String avatarHash;
        @Nullable
        public final String discordId;

        public Card(String id, String name, @Nullable String avatar, @Nullable String avatarHash, @Nullable String discordId) {
            this.id = id;
            this.name = name;
            this.avatar = avatar;
            this.avatarHash = avatarHash;
            this.discordId = discordId;
        }

        @Nullable
        static Card read(@Nullable JsonObject o) {
            if (o == null) return null;
            String id = Json.str(o, "id");
            String username = Json.str(o, "username");
            if (id == null || username == null) return null;
            String display = Json.str(o, "displayName");
            return new Card(id, display == null || display.isEmpty() ? username : display, Json.str(o, "avatar"),
                    Json.str(o, "avatarHash"), Json.str(o, "discordId"));
        }
    }

    /**
     * M051 (SHARE-LIVE): one screen share as the room shows it ({@code sh}; share.md §4, share-live.md §2): its number,
     * what it shows ({@code game}, {@code window}, {@code screen}), how many watch it and, with {@code live1}, its last
     * picture's time (Unix seconds; 0: none yet). The rest of it is the desktop's.
     */
    public static final class Share {
        public final int n;
        public final String src;
        public final int w;
        public final long th;
        /** SQOL ({@code share2}): {@code hold} (paused) or {@code min} (the shared window minimized); null while live. */
        @Nullable public final String st;

        public Share(int n, String src, int w, long th) {
            this(n, src, w, th, null);
        }

        public Share(int n, String src, int w, long th, @Nullable String st) {
            this.n = n;
            this.src = src;
            this.w = w;
            this.th = th;
            this.st = "hold".equals(st) || "min".equals(st) ? st : null;
        }

        @Nullable
        static Share read(@Nullable JsonObject o) {
            Long n = Json.numOrNull(o, "n");
            if (n == null || n < 1 || n > 255) return null;
            return new Share(n.intValue(), Json.str(o, "src", "screen"), (int) Math.max(0, Json.num(o, "w", 0)),
                    Math.max(0, Json.num(o, "th", 0)), Json.str(o, "st"));
        }

        /** Shares by number; one that does not read is left out, a list that does not read is none. */
        static List<Share> list(@Nullable JsonArray array) {
            if (array == null || array.size() == 0) return Collections.emptyList();
            List<Share> out = new ArrayList<>();
            for (JsonElement e : array) {
                if (!e.isJsonObject()) continue;
                Share s = read(e.getAsJsonObject());
                if (s != null) out.add(s);
            }
            Collections.sort(out, (a, b) -> Integer.compare(a.n, b.n));
            return out;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Share)) return false;
            Share s = (Share) other;
            return n == s.n && src.equals(s.src) && w == s.w && th == s.th && (st == null ? s.st == null : st.equals(s.st));
        }

        @Override
        public int hashCode() {
            return n;
        }
    }

    /** Voice's own TURN credentials ({@code turn} → {@code ack.ice}): never logged. */
    public static final class Ice {
        public final List<String> urls;
        public final String username;
        public final String credential;

        public Ice(List<String> urls, String username, String credential) {
            this.urls = urls;
            this.username = username;
            this.credential = credential;
        }

        @Override
        public String toString() {
            return "<ice " + urls.size() + " urls>";
        }
    }

    /** {@code left.why}: someone else left. */
    public enum LeftWhy { GONE, KICKED, MOVED, LEFT }

    /** {@code closed.why}: the player's stay ended. Anything unknown reads like {@code off}. */
    public enum ClosedWhy { LEADER, ALONE, NO_ANSWER, ELSEWHERE, CREW, BUDGET, STAFF, ACCOUNT, MUTED, OFF }

    /** {@code ring_end.why} (to the one who invited); anything unknown reads like {@code no_answer}. */
    public enum RingEndWhy { JOINED, CANCELLED, NO_ANSWER }

    public enum Kind { HELLO, OFFER, JOINED, LEFT, STATE, ROOM, ME, RING, RING_END, KICKED, MOVED, CLOSED, ACK, ERROR, UNKNOWN,
        /** SQOL: a room chat message, the room's last messages, a message taken away. */
        RCHAT, RCHAT_LOG, RCHAT_DELETED }

    /**
     * One message from the room. Only the fields of its kind are set; a message the phone does not know reads as
     * {@link Kind#UNKNOWN} and changes nothing (a newer service may say more).
     */
    public static final class Message {
        public Kind kind = Kind.UNKNOWN;
        // hello
        @Nullable public String roomId;
        public boolean crewRoom;
        @Nullable public String crew;
        @Nullable public String name;
        @Nullable public String leader;
        public List<String> mods = Collections.emptyList();
        /** hello: the room's limit; room: the new limit or -1. */
        public int max = -1;
        public Role role = Role.MEMBER;
        public List<Person> people = Collections.emptyList();
        /** hello: the player's own invites that ring (person → until). */
        public Map<String, Long> rings = Collections.emptyMap();
        @Nullable public String sdp;
        @Nullable public String resume;
        @Nullable public Long now;
        /** hello ({@code share1}): the shares this account watches here ({@code me.watch}: person → numbers). */
        public Map<String, List<Integer>> watch = Collections.emptyMap();
        // offer
        public Map<String, String> mids = Collections.emptyMap();
        // joined
        @Nullable public Person person;
        // left, state
        @Nullable public String u;
        public LeftWhy leftWhy = LeftWhy.LEFT;
        @Nullable public Boolean mute;
        @Nullable public Boolean deaf;
        @Nullable public Boolean sm;
        @Nullable public Integer q;
        /** state ({@code share1}): their shares now (null: the frame does not say). */
        @Nullable public List<Share> sh;
        // ring, ring_end
        @Nullable public String to;
        public long until;
        public RingEndWhy ringEndWhy = RingEndWhy.NO_ANSWER;
        // moved
        @Nullable public String ticket;
        // closed
        public ClosedWhy closedWhy = ClosedWhy.OFF;
        @Nullable public Long closedUntil;
        // ack, error
        @Nullable public Integer rid;
        @Nullable public Ice ice;
        @Nullable public String code;
        @Nullable public Long retryAfter;
        @Nullable public Long errorUntil;
        // SQOL (rchat1)
        /** hello, room: the room's chat is on (absent: off). */
        public boolean chat;
        /** hello: a friend room that once had three people or more. */
        public boolean grew;
        /** rchat, ack of a send: the message as the room keeps it. */
        @Nullable public RoomChat.Line line;
        /** rchat_log: the room's last messages, oldest first. */
        public List<RoomChat.Line> lines = Collections.emptyList();
        /** rchat_deleted: the message and who took it ({@code self}, {@code mod}, {@code staff}). */
        @Nullable public String chatId;
        @Nullable public String chatBy;
        /** error of a send: {@code chat_rejected}'s reason ({@code link}, {@code phone}, {@code words}) and link rule. */
        @Nullable public String reason;
        @Nullable public String rule;

        @Override
        public String toString() {
            // Only the kind: frames carry SDP, tickets and ids.
            return kind.name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** A text frame from the room; {@code pong} and anything unreadable read as {@link Kind#UNKNOWN}. */
    public static Message parse(@Nullable String text) {
        Message m = new Message();
        JsonObject o = Json.parseObject(text);
        if (o == null) return m;
        try {
            switch (Json.str(o, "t", "")) {
                case "hello": {
                    JsonObject room = Json.obj(o, "room");
                    String id = Json.str(room, "id");
                    if (id == null) return m;
                    m.roomId = id;
                    m.crewRoom = "c".equals(Json.str(room, "kind"));
                    m.crew = Json.str(room, "crew");
                    m.name = Json.str(room, "name");
                    m.leader = Json.str(room, "leader");
                    m.mods = Json.strings(room, "mods");
                    m.max = (int) Json.num(room, "max", 0);
                    m.chat = Json.bool(room, "chat", false);
                    m.grew = Json.bool(room, "grew", false);
                    m.role = Role.of(Json.str(Json.obj(o, "me"), "role"));
                    m.people = people(Json.arr(o, "people"));
                    Map<String, Long> rings = new TreeMap<>();
                    for (JsonObject ring : Json.objects(o, "rings")) {
                        String to = Json.str(ring, "to");
                        if (to != null) rings.put(to, Json.num(ring, "until", 0));
                    }
                    m.rings = rings;
                    m.sdp = Json.str(o, "sdp");
                    m.resume = Json.str(o, "resume");
                    m.now = Json.numOrNull(o, "now");
                    Map<String, List<Integer>> watch = new TreeMap<>();
                    for (JsonObject w : Json.objects(Json.obj(o, "me"), "watch")) {
                        String wu = Json.str(w, "u");
                        Long wn = Json.numOrNull(w, "n");
                        if (wu == null || wn == null) continue;
                        List<Integer> ns = watch.get(wu);
                        if (ns == null) {
                            ns = new ArrayList<>();
                            watch.put(wu, ns);
                        }
                        ns.add(wn.intValue());
                    }
                    m.watch = watch;
                    m.kind = Kind.HELLO;
                    return m;
                }
                case "offer": {
                    m.sdp = Json.str(o, "sdp");
                    if (m.sdp == null) return m;
                    Map<String, String> mids = new TreeMap<>();
                    JsonObject raw = Json.obj(o, "mids");
                    if (raw != null) {
                        for (Map.Entry<String, JsonElement> e : raw.entrySet()) {
                            if (e.getValue().isJsonPrimitive()) mids.put(e.getKey(), e.getValue().getAsString());
                        }
                    }
                    m.mids = mids;
                    m.kind = Kind.OFFER;
                    return m;
                }
                case "joined":
                    m.person = Person.read(Json.obj(o, "person"));
                    if (m.person != null) m.kind = Kind.JOINED;
                    return m;
                case "left": {
                    m.u = Json.str(o, "u");
                    if (m.u == null) return m;
                    String why = Json.str(o, "why", "left");
                    m.leftWhy = "gone".equals(why) ? LeftWhy.GONE : "kicked".equals(why) ? LeftWhy.KICKED
                            : "moved".equals(why) ? LeftWhy.MOVED : LeftWhy.LEFT;
                    m.kind = Kind.LEFT;
                    return m;
                }
                case "state": {
                    m.u = Json.str(o, "u");
                    if (m.u == null) return m;
                    m.mute = optBool(o, "mute");
                    m.deaf = optBool(o, "deaf");
                    m.sm = optBool(o, "sm");
                    Long q = Json.numOrNull(o, "q");
                    m.q = q == null ? null : quality(q);
                    JsonElement sh = o.get("sh");
                    if (sh != null && sh.isJsonArray()) m.sh = Share.list(sh.getAsJsonArray());
                    m.kind = Kind.STATE;
                    return m;
                }
                case "room":
                    m.name = Json.str(o, "name");
                    m.leader = Json.str(o, "leader");
                    m.mods = Json.strings(o, "mods");
                    Long max = Json.numOrNull(o, "max");
                    m.max = max == null ? -1 : max.intValue();
                    // SQOL: every `room` to an rchat1 socket says `chat` while it is on; without it the chat is off.
                    m.chat = Json.bool(o, "chat", false);
                    m.kind = Kind.ROOM;
                    return m;
                case "me":
                    m.role = Role.of(Json.str(o, "role"));
                    m.kind = Kind.ME;
                    return m;
                case "ring":
                    m.to = Json.str(o, "to");
                    if (m.to == null) return m;
                    m.until = Json.num(o, "until", 0);
                    m.kind = Kind.RING;
                    return m;
                case "ring_end": {
                    m.to = Json.str(o, "to");
                    if (m.to == null) return m;
                    String why = Json.str(o, "why", "");
                    m.ringEndWhy = "joined".equals(why) ? RingEndWhy.JOINED : "cancelled".equals(why) ? RingEndWhy.CANCELLED
                            : RingEndWhy.NO_ANSWER;
                    m.kind = Kind.RING_END;
                    return m;
                }
                case "kicked":
                    m.kind = Kind.KICKED;
                    return m;
                case "moved":
                    m.roomId = Json.str(o, "room");
                    m.ticket = Json.str(o, "ticket");
                    if (m.roomId == null || m.ticket == null) return m;
                    m.kind = Kind.MOVED;
                    return m;
                case "closed":
                    m.closedWhy = closedWhy(Json.str(o, "why", ""));
                    m.closedUntil = Json.numOrNull(o, "until");
                    m.kind = Kind.CLOSED;
                    return m;
                case "ack": {
                    Long rid = Json.numOrNull(o, "rid");
                    m.rid = rid == null ? null : rid.intValue();
                    JsonObject ice = Json.obj(o, "ice");
                    if (ice != null) {
                        m.ice = new Ice(Json.strings(ice, "urls"), Json.str(ice, "username", ""), Json.str(ice, "credential", ""));
                    }
                    // SQOL: a room chat send's answer carries the message as the room keeps it.
                    m.line = RoomChat.Line.read(Json.obj(o, "m"));
                    m.kind = Kind.ACK;
                    return m;
                }
                case "error": {
                    m.code = Json.str(o, "code");
                    if (m.code == null) return m;
                    Long rid = Json.numOrNull(o, "rid");
                    m.rid = rid == null ? null : rid.intValue();
                    m.retryAfter = Json.numOrNull(o, "retryAfter");
                    m.errorUntil = Json.numOrNull(o, "until");
                    m.reason = Json.str(o, "reason");
                    m.rule = Json.str(o, "rule");
                    m.kind = Kind.ERROR;
                    return m;
                }
                case "rchat":
                    m.line = RoomChat.Line.read(Json.obj(o, "m"));
                    if (m.line != null) m.kind = Kind.RCHAT;
                    return m;
                case "rchat_log": {
                    List<RoomChat.Line> lines = new ArrayList<>();
                    for (JsonObject entry : Json.objects(o, "ms")) {
                        RoomChat.Line line = RoomChat.Line.read(entry);
                        // One that does not read is left out, oldest first as the room keeps them.
                        if (line != null && (lines.isEmpty() || lines.get(lines.size() - 1).at <= line.at)) lines.add(line);
                    }
                    m.lines = lines;
                    m.kind = Kind.RCHAT_LOG;
                    return m;
                }
                case "rchat_deleted":
                    m.chatId = Json.str(o, "id");
                    if (!RoomChat.isId(m.chatId)) return m;
                    m.chatBy = Json.str(o, "by", "self");
                    m.kind = Kind.RCHAT_DELETED;
                    return m;
                default:
                    return m;
            }
        } catch (RuntimeException e) {
            // A frame of an unexpected shape changes nothing.
            return new Message();
        }
    }

    static ClosedWhy closedWhy(String why) {
        switch (why) {
            case "leader": return ClosedWhy.LEADER;
            case "alone": return ClosedWhy.ALONE;
            case "no_answer": return ClosedWhy.NO_ANSWER;
            case "elsewhere": return ClosedWhy.ELSEWHERE;
            case "crew": return ClosedWhy.CREW;
            case "budget": return ClosedWhy.BUDGET;
            case "staff": return ClosedWhy.STAFF;
            case "account": return ClosedWhy.ACCOUNT;
            case "muted": return ClosedWhy.MUTED;
            default: return ClosedWhy.OFF;
        }
    }

    private static List<Person> people(@Nullable JsonArray array) {
        List<Person> out = new ArrayList<>();
        if (array == null) return out;
        for (JsonElement e : array) {
            if (!e.isJsonObject()) continue;
            Person p = Person.read(e.getAsJsonObject());
            if (p != null) out.add(p);
        }
        return out;
    }

    @Nullable
    private static Boolean optBool(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) return null;
        return e.getAsBoolean();
    }

    /** The room socket's close codes ({@code ROOM_CLOSE}; 1000 follows {@code kicked}, {@code moved} and {@code closed}). */
    public static final int CLOSE_REPLACED = 4000;
    public static final int CLOSE_SESSION_ENDED = 4001;
    public static final int CLOSE_PROTOCOL = 4003;
    public static final int CLOSE_RATE_LIMITED = 4008;

    /** A dropped socket keeps its seat this long; then the stay is over. */
    public static final long SEAT_SECONDS = 120;
    /** Client frames: at most this many characters (SDP). */
    public static final int MAX_FRAME_CHARS = 16_384;
}
