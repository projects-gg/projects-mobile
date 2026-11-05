package gg.projects.mobile.voice;

import androidx.annotation.Nullable;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import gg.projects.mobile.social.Json;

/**
 * Voice chat as the presence socket tells it on the phone: Studio's switch and numbers ({@code hello.voice},
 * {@code voice_state}), rings and missed calls ({@code voice_ring}, {@code voice_ring_end}), the crews' rooms
 * ({@code hello.voiceRooms}, {@code voice_rooms}) and who is in voice ({@code presence.voice}). The ring part of the
 * desktop's {@code machine.rs}, without I/O: each input returns {@link Effect}s and {@link #nextDeadline} names the one
 * moment it wants {@link #tick} (a ring that runs out). Pure Java, tested on the JVM.
 *
 * <p>Times: {@code now} is milliseconds on the caller's monotonic clock, {@code unix} is Unix seconds.
 */
public final class VoiceRings {
    /** An invite's ticket holds this long after its ring began ({@code TICKET_TTL.invite}). */
    public static final long INVITE_TICKET_SECONDS = 600;
    /** Missed calls kept at most. */
    public static final int MISSED_MAX = 10;
    public static final int IGNORED_MAX = 200;
    /** The presence capability of clients that speak voice chat ({@code ?caps=…,voice1}). */
    public static final String VOICE_CAP = "voice1";

    /** {@code hello.voice} / {@code voice_state}: Studio's switch and what clients apply. */
    public static final class Service {
        public boolean on;
        public int friendMax = 10;
        public int crewMax = 15;
        public int crewRooms = 3;
        public int ringSeconds = VoiceMachine.DEFAULT_RING_SECONDS;
        public int idleMinutes = VoiceMachine.DEFAULT_IDLE_MINUTES;
        public int kbps = VoiceMachine.DEFAULT_KBPS;
        public boolean manage;

        @Nullable
        public static Service read(@Nullable JsonObject o) {
            if (o == null) return null;
            Service s = new Service();
            s.on = Json.bool(o, "on", false);
            s.friendMax = (int) Json.num(o, "friendMax", s.friendMax);
            s.crewMax = (int) Json.num(o, "crewMax", s.crewMax);
            s.crewRooms = (int) Json.num(o, "crewRooms", s.crewRooms);
            s.ringSeconds = (int) Json.num(o, "ringSeconds", s.ringSeconds);
            s.idleMinutes = (int) Json.num(o, "idleMinutes", s.idleMinutes);
            s.kbps = (int) Json.num(o, "kbps", s.kbps);
            s.manage = Json.bool(o, "manage", false);
            return s;
        }

        public VoiceMachine.Service forStay() {
            return new VoiceMachine.Service(kbps, ringSeconds, idleMinutes);
        }
    }

    /** A ring for the player. */
    public static final class Ring {
        public final String room;
        public final String from;
        /** Unix seconds. */
        public final long at;
        public final long until;
        /** Null for a ring that came as a push (the app asks the service for its ticket at "Katıl"). */
        @Nullable public final String ticket;
        public final List<String> with;

        public Ring(String room, String from, long at, long until, @Nullable String ticket, List<String> with) {
            this.room = room;
            this.from = from;
            this.at = at;
            this.until = until;
            this.ticket = ticket;
            this.with = with;
        }

        /** {@code voice_ring.ring}, or null when it does not read. */
        @Nullable
        public static Ring read(@Nullable JsonObject o) {
            String room = Json.str(o, "room");
            String from = Json.str(o, "from");
            String ticket = Json.str(o, "ticket");
            if (!VoiceWire.isRoomId(room) || from == null || from.isEmpty() || ticket == null) return null;
            return new Ring(room, from, Json.num(o, "at", 0), Json.num(o, "until", 0), ticket, Json.strings(o, "with"));
        }

        public boolean crew() {
            return !VoiceWire.isFriendRoom(room);
        }
    }

    /** A call the player did not answer ("Cevapsız arama"). */
    public static final class Missed {
        public final String room;
        public final String from;
        public final long at;
        @Nullable public final String ticket;
        public boolean ended;

        Missed(Ring ring) {
            room = ring.room;
            from = ring.from;
            at = ring.at;
            ticket = ring.ticket;
        }

        /** Still joinable ("Katıl" for 10 minutes) or not ("Geri ara"). */
        public boolean joinable(long unix) {
            return !ended && ticket != null && unix < at + INVITE_TICKET_SECONDS;
        }
    }

    /** One of a crew's rooms as the hub lists it. */
    public static final class CrewRoom {
        public final String id;
        public final String name;
        public final List<String> ids;
        /** VOICE-ROOMS: people at most, when the crew set fewer than the crew room maximum (0: none). */
        public final int max;
        /** VOICE-ROOMS: the player opened it. */
        public final boolean own;

        public CrewRoom(String id, String name, List<String> ids, int max, boolean own) {
            this.id = id;
            this.name = name;
            this.ids = ids;
            this.max = max;
            this.own = own;
        }

        public boolean general() {
            return id.startsWith("g");
        }

        @Nullable
        static CrewRoom read(@Nullable JsonObject o) {
            String id = Json.str(o, "id");
            if (!VoiceWire.isRoomId(id)) return null;
            return new CrewRoom(id, Json.str(o, "name", "Genel"), Json.strings(o, "ids"), (int) Json.num(o, "max", 0),
                    Json.bool(o, "own", false));
        }
    }

    /** What the caller does. */
    public static final class Effect {
        public enum Kind {
            /** {@code voice_decline} on the presence socket. */
            HUB_DECLINE,
            /** {@code status.voice}: the player's switch. */
            HUB_SWITCH,
            /** A ring to show (a card on screen, else a notification with Katıl and Reddet). */
            RING,
            /** The ring is over: take its card and notification away. */
            RING_GONE,
            /** A missed call to tell ("Cevapsız arama"). */
            MISSED,
            CHANGED
        }

        public final Kind kind;
        @Nullable public final String room;
        @Nullable public final Ring ring;
        public final boolean on;

        Effect(Kind kind, @Nullable String room, @Nullable Ring ring, boolean on) {
            this.kind = kind;
            this.room = room;
            this.ring = ring;
            this.on = on;
        }

        @Override
        public String toString() {
            return kind + (room == null ? "" : " " + room);
        }
    }

    private boolean flag;
    @Nullable private Service service;
    private boolean hubUp;
    private boolean enabled = true;
    private String me = "";
    /** The room the player is in on this phone (no ring for it). */
    @Nullable private String stayRoom;
    /** Ringing now, with the moment (caller's clock) each runs out. */
    private final LinkedHashMap<String, Ring> rings = new LinkedHashMap<>();
    private final Map<String, Long> ringDeadlines = new TreeMap<>();
    private final List<Missed> missed = new ArrayList<>();
    private final Map<String, List<CrewRoom>> crews = new TreeMap<>();
    /** {@code presence.voice} of the people the player sees ({@code ready} or {@code in}). */
    private final Map<String, String> presence = new TreeMap<>();
    private List<String> ignored = Collections.emptyList();

    // ---- switches ----

    /** The signed config's {@code features.voice} (with {@code friends}). */
    public List<Effect> setFlag(boolean on) {
        List<Effect> out = new ArrayList<>();
        if (flag == on) return out;
        flag = on;
        if (!on) endRings(out);
        out.add(changed());
        return out;
    }

    public List<Effect> setMe(@Nullable String id) {
        List<Effect> out = new ArrayList<>();
        String value = id == null ? "" : id;
        if (value.equals(me)) return out;
        me = value;
        if (value.isEmpty()) {
            service = null;
            hubUp = false;
            crews.clear();
            presence.clear();
            endRings(out);
            missed.clear();
        }
        out.add(changed());
        return out;
    }

    /** The player's own "Sesli sohbeti kullan" switch (off: no ring, nobody can call). */
    public List<Effect> setEnabled(boolean on) {
        List<Effect> out = new ArrayList<>();
        if (enabled == on) return out;
        enabled = on;
        if (hubUp) out.add(new Effect(Effect.Kind.HUB_SWITCH, null, null, on));
        if (!on) endRings(out);
        out.add(changed());
        return out;
    }

    public boolean enabled() {
        return enabled;
    }

    /** The presence socket said hello: whether it took {@code voice1} (its {@code hello.voice} there), the crews' rooms. */
    public List<Effect> hubHello(JsonObject hello) {
        List<Effect> out = new ArrayList<>();
        Service read = Service.read(Json.obj(hello, "voice"));
        hubUp = read != null && Json.strings(hello, "caps").contains(VOICE_CAP);
        service = hubUp ? read : null;
        crews.clear();
        for (JsonObject crew : Json.objects(hello, "voiceRooms")) putCrew(crew);
        presence.clear();
        // The hello's presences: friends, then crewmates.
        for (JsonObject p : Json.objects(hello, "friends")) readPresence(p);
        for (JsonObject p : Json.objects(hello, "team")) readPresence(p);
        // A `voice1` socket counts as "on" until told otherwise.
        if (hubUp && !enabled) out.add(new Effect(Effect.Kind.HUB_SWITCH, null, null, false));
        out.add(changed());
        return out;
    }

    /** The presence socket dropped: rings and calls wait (a room goes on by itself). */
    public List<Effect> hubDown() {
        hubUp = false;
        presence.clear();
        List<Effect> out = new ArrayList<>();
        out.add(changed());
        return out;
    }

    /** Voice chat may be offered: the signed config and Studio's switch agree. */
    public boolean available() {
        return flag && service != null && service.on && !me.isEmpty();
    }

    /** New calls and joins may start now: available, the player's switch on, the hub connected. */
    public boolean usable() {
        return available() && enabled && hubUp;
    }

    public Service service() {
        return service == null ? new Service() : service;
    }

    /** One frame of the presence socket; true when it was voice chat's. */
    public boolean onFrame(JsonObject message, long now, long unix, List<Effect> out) {
        String type = Json.str(message, "t", "");
        switch (type) {
            case "voice_ring": {
                Ring ring = Ring.read(Json.obj(message, "ring"));
                if (ring != null) out.addAll(ring(ring, Json.bool(message, "quiet", false), now, unix));
                return true;
            }
            case "voice_ring_end": {
                String room = Json.str(message, "room");
                if (room != null) out.addAll(ringEnd(room, Json.str(message, "reason", "ended")));
                return true;
            }
            case "voice_rooms":
                putCrew(message);
                out.add(changed());
                return true;
            case "voice_state": {
                Service read = Service.read(Json.obj(message, "voice"));
                if (read != null && service != null) {
                    service = read;
                    out.add(changed());
                }
                return true;
            }
            case "presence":
                readPresence(Json.obj(message, "presence"));
                // Not only voice chat's: the caller applies it too.
                return false;
            default:
                return false;
        }
    }

    private void putCrew(@Nullable JsonObject o) {
        String crew = Json.str(o, "crew");
        if (crew == null) return;
        List<CrewRoom> list = new ArrayList<>();
        for (JsonObject room : Json.objects(o, "rooms")) {
            CrewRoom r = CrewRoom.read(room);
            if (r != null) list.add(r);
        }
        if (list.isEmpty()) crews.remove(crew);
        else crews.put(crew, list);
    }

    private void readPresence(@Nullable JsonObject p) {
        String id = Json.str(p, "id");
        if (id == null) return;
        String voice = Json.str(p, "voice");
        if ("ready".equals(voice) || "in".equals(voice)) presence.put(id, voice);
        else presence.remove(id);
    }

    // ---- rings ----

    /** A ring for the player ({@code quiet} on do-not-disturb). Someone the player ignores waits quietly in missed. */
    public List<Effect> ring(Ring ring, boolean quiet, long now, long unix) {
        List<Effect> out = new ArrayList<>();
        if (!available() || !enabled || !VoiceWire.isRoomId(ring.room) || ring.room.equals(stayRoom)) return out;
        rings.remove(ring.room);
        ringDeadlines.remove(ring.room);
        out.add(changed());
        if (ignores(ring.from)) {
            keepMissed(ring);
            return out;
        }
        if (quiet || ring.until <= unix) {
            addMissed(ring, out);
            return out;
        }
        rings.put(ring.room, ring);
        ringDeadlines.put(ring.room, now + Math.max(0, ring.until - unix) * 1000);
        out.add(new Effect(Effect.Kind.RING, ring.room, ring, false));
        return out;
    }

    /** {@code voice_ring_end}: {@code joined} or {@code declined} on another device, or the room {@code ended}. */
    public List<Effect> ringEnd(String room, String reason) {
        List<Effect> out = new ArrayList<>();
        if (rings.remove(room) != null) {
            ringDeadlines.remove(room);
            out.add(new Effect(Effect.Kind.RING_GONE, room, null, false));
            out.add(changed());
        }
        if ("joined".equals(reason) || "declined".equals(reason)) {
            if (removeMissed(room)) out.add(changed());
        } else {
            for (Missed m : missed) {
                if (m.room.equals(room) && !m.ended) {
                    m.ended = true;
                    out.add(changed());
                }
            }
        }
        dedup(out);
        return out;
    }

    /** "Reddet": the ring stops on every device; the caller hears nothing more than a time-out. */
    public List<Effect> decline(String room) {
        List<Effect> out = new ArrayList<>();
        if (rings.remove(room) == null) return out;
        ringDeadlines.remove(room);
        out.add(new Effect(Effect.Kind.HUB_DECLINE, room, null, false));
        out.add(new Effect(Effect.Kind.RING_GONE, room, null, false));
        out.add(changed());
        return out;
    }

    /** The player joins {@code room} on this phone (a ring's "Katıl", a call, a crew room): its ring and missed calls end. */
    public List<Effect> joining(String room, @Nullable String calling) {
        List<Effect> out = new ArrayList<>();
        stayRoom = room;
        boolean was = rings.remove(room) != null;
        ringDeadlines.remove(room);
        int before = missed.size();
        for (Iterator<Missed> it = missed.iterator(); it.hasNext(); ) {
            Missed m = it.next();
            if (m.room.equals(room) || m.from.equals(calling)) it.remove();
        }
        if (was || missed.size() != before) out.add(new Effect(Effect.Kind.RING_GONE, room, null, false));
        out.add(changed());
        return out;
    }

    /** The stay on this phone ended (or none). */
    public void setStayRoom(@Nullable String room) {
        stayRoom = room;
    }

    /** Takes a missed call off the list. */
    public List<Effect> dismissMissed(String room) {
        List<Effect> out = new ArrayList<>();
        if (removeMissed(room)) out.add(changed());
        return out;
    }

    /** The player blocked {@code user}: their rings and missed calls go. */
    public List<Effect> blocked(String user) {
        List<Effect> out = new ArrayList<>();
        for (Iterator<Map.Entry<String, Ring>> it = rings.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Ring> e = it.next();
            if (e.getValue().from.equals(user)) {
                it.remove();
                ringDeadlines.remove(e.getKey());
                out.add(new Effect(Effect.Kind.RING_GONE, e.getKey(), null, false));
            }
        }
        for (Iterator<Missed> it = missed.iterator(); it.hasNext(); ) {
            if (it.next().from.equals(user)) it.remove();
        }
        out.add(changed());
        return out;
    }

    /** Whom the player ignores (SOC-NEXT §3.7, the phone's list; empty while its switch is off). */
    public List<Effect> setIgnored(List<String> ids) {
        List<String> list = new ArrayList<>();
        for (String id : ids) {
            if (id != null && !id.isEmpty() && id.length() <= 64 && !list.contains(id)) list.add(id);
        }
        Collections.sort(list);
        if (list.size() > IGNORED_MAX) list = list.subList(0, IGNORED_MAX);
        List<Effect> out = new ArrayList<>();
        if (list.equals(ignored)) return out;
        ignored = list;
        for (Iterator<Map.Entry<String, Ring>> it = rings.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Ring> e = it.next();
            if (!ignores(e.getValue().from)) continue;
            it.remove();
            ringDeadlines.remove(e.getKey());
            out.add(new Effect(Effect.Kind.RING_GONE, e.getKey(), null, false));
            keepMissed(e.getValue());
            out.add(changed());
        }
        dedup(out);
        return out;
    }

    private boolean ignores(String user) {
        return Collections.binarySearch(ignored, user) >= 0;
    }

    private void addMissed(Ring ring, List<Effect> out) {
        keepMissed(ring);
        out.add(new Effect(Effect.Kind.MISSED, ring.room, ring, false));
    }

    private void keepMissed(Ring ring) {
        removeMissed(ring.room);
        missed.add(0, new Missed(ring));
        while (missed.size() > MISSED_MAX) missed.remove(missed.size() - 1);
    }

    private boolean removeMissed(String room) {
        boolean removed = false;
        for (Iterator<Missed> it = missed.iterator(); it.hasNext(); ) {
            if (it.next().room.equals(room)) {
                it.remove();
                removed = true;
            }
        }
        return removed;
    }

    private void endRings(List<Effect> out) {
        for (String room : new ArrayList<>(rings.keySet())) out.add(new Effect(Effect.Kind.RING_GONE, room, null, false));
        rings.clear();
        ringDeadlines.clear();
    }

    /** "Katıl" on a ring or a missed call that is still joinable: its ticket (null: none, or a push's ring). */
    @Nullable
    public String ticketFor(String room, long unix) {
        Ring ring = rings.get(room);
        if (ring != null) return ring.ticket;
        for (Missed m : missed) if (m.room.equals(room) && m.joinable(unix)) return m.ticket;
        return null;
    }

    @Nullable
    public Ring ringOf(String room) {
        return rings.get(room);
    }

    // ---- time ----

    @Nullable
    public Long nextDeadline() {
        Long next = null;
        for (long at : ringDeadlines.values()) next = next == null ? at : Math.min(next, at);
        return next;
    }

    /** Rings that ran out become missed calls. */
    public List<Effect> tick(long now) {
        List<Effect> out = new ArrayList<>();
        for (String room : new ArrayList<>(ringDeadlines.keySet())) {
            if (ringDeadlines.get(room) > now) continue;
            ringDeadlines.remove(room);
            Ring ring = rings.remove(room);
            if (ring == null) continue;
            out.add(new Effect(Effect.Kind.RING_GONE, room, null, false));
            addMissed(ring, out);
            out.add(changed());
        }
        dedup(out);
        return out;
    }

    // ---- what the screens read ----

    public List<Ring> rings() {
        return new ArrayList<>(rings.values());
    }

    public List<Missed> missed() {
        return new ArrayList<>(missed);
    }

    /** The newest missed call from {@code friend}, or null. */
    @Nullable
    public Missed missedFrom(String friend) {
        for (Missed m : missed) if (m.from.equals(friend)) return m;
        return null;
    }

    /** {@code presence.voice} of someone: {@code ready}, {@code in} or null (cannot be called). */
    @Nullable
    public String presenceOf(String id) {
        return presence.get(id);
    }

    /** Whether someone the player sees is in a voice room (a friend's {@code in}, a crewmate in a crew room). */
    public boolean inVoice(String id) {
        if ("in".equals(presence.get(id))) return true;
        for (List<CrewRoom> rooms : crews.values()) {
            for (CrewRoom room : rooms) if (!id.equals(me) && room.ids.contains(id)) return true;
        }
        return false;
    }

    /**
     * One crew's rooms as the screens show them, "Genel" always first (VOICE-ROOMS: an older service left an empty
     * "Genel" out while another room was live), the rest in the service's order.
     */
    public List<CrewRoom> crewRooms(String crew) {
        String generalId = VoiceWire.generalRoomOf(crew);
        List<CrewRoom> listed = crews.get(crew);
        List<CrewRoom> out = new ArrayList<>();
        CrewRoom general = null;
        if (listed != null) for (CrewRoom r : listed) if (r.id.equals(generalId)) general = r;
        out.add(general != null ? general : new CrewRoom(generalId, "Genel", Collections.<String>emptyList(), 0, false));
        if (listed != null) for (CrewRoom r : listed) if (!r.id.equals(generalId)) out.add(r);
        return out;
    }

    /** Whether the crew may open another room now ("Yeni oda"). */
    public boolean canAddRoom(String crew) {
        return crewRooms(crew).size() < service().crewRooms;
    }

    @Nullable
    public CrewRoom crewRoom(String room) {
        for (List<CrewRoom> rooms : crews.values()) for (CrewRoom r : rooms) if (r.id.equals(room)) return r;
        return null;
    }

    private static Effect changed() {
        return new Effect(Effect.Kind.CHANGED, null, null, false);
    }

    private static void dedup(List<Effect> out) {
        boolean seen = false;
        for (Iterator<Effect> it = out.iterator(); it.hasNext(); ) {
            if (it.next().kind != Effect.Kind.CHANGED) continue;
            if (seen) it.remove();
            seen = true;
        }
    }
}
