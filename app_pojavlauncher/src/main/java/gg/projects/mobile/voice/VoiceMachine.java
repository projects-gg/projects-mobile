package gg.projects.mobile.voice;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;

import gg.projects.mobile.voice.VoiceAnswer.Refusal;
import gg.projects.mobile.voice.VoiceWire.Message;
import gg.projects.mobile.voice.VoiceWire.ModAct;
import gg.projects.mobile.voice.VoiceWire.Person;
import gg.projects.mobile.voice.VoiceWire.Role;

/**
 * The player's stay in a room on the phone: a faithful port of the desktop's state machine
 * ({@code crates/pl-voice/src/machine.rs}, design {@code voice.md} §3, §5, §6) for its stay part. It does no I/O: every
 * input returns {@link Effect}s the voice service carries out (the room socket, the audio engine, the notices), and
 * {@link #nextDeadline} names the one moment it wants a {@link #tick}. Without a stay there is no deadline at all.
 *
 * <p>Rings, missed calls and the crews' rooms are the launcher process's ({@link VoiceRings}): the presence socket is
 * there. The phone has no push-to-talk key, no device choice and no microphone test (decisions in {@code mob-voice.md}).
 * The engine is the phone's WebRTC in the voice process: "the page" of the desktop is {@code VoiceEngine} here, opened
 * when a stay begins and closed a minute after it ended.
 *
 * <p>Times: {@code now} is milliseconds on the caller's monotonic clock, {@code unix} is Unix seconds.
 */
public final class VoiceMachine {
    /** The connection may be away this long before the stay ends ("Bağlanamadı."). */
    public static final long GIVE_UP_MS = 60_000;
    /** "Yeniden bağlanıyor…" shows after the connection was away this long. */
    public static final long RECONNECTING_SHOWN_MS = 2_000;
    /** After a 4008 close the socket waits this long (the seat holds 120 s). */
    public static final long RATE_LIMITED_WAIT_MS = 60_000;
    /** Rejoins after lost audio connections wait 0, then 1, 2, 4, at most 8 s. */
    public static final long REJOIN_MAX_MS = 8_000;
    /** The engine stays this long after a stay ended (a quick way back). */
    public static final long LINGER_MS = 60_000;
    /** The first answer a join's caller waits for. */
    public static final int JOIN_RID = 0;
    /** A person's volume tops out here (the phone's mixer amplifies; the desktop's WebAudio path). */
    public static final int VOLUME_MAX = 200;
    public static final int DEFAULT_KBPS = 24;
    public static final int DEFAULT_RING_SECONDS = 30;
    public static final int DEFAULT_IDLE_MINUTES = 30;

    // ---- what goes out ----

    /** One of voice chat's own short sounds (the desktop's {@code EngineSound}). */
    public enum Sound { JOIN, LEAVE, MIC_OFF, MIC_ON, DEAF_ON, DEAF_OFF, CONNECTED, ENDED, LOST, WAITING, STOP }

    /** Why the player's stay ended (the desktop's {@code VoiceLeftReason}). */
    public enum LeftReason { CLOSED, ALONE, NO_ANSWER, ELSEWHERE, KICKED, CREW, IDLE, UNAVAILABLE, FAILED, RESTRICTED }

    /** The microphone as the engine found it. */
    public enum Mic { OK, DENIED, MISSING }

    /** A moment worth a word to the player. */
    public static final class Notice {
        public enum Kind { LEFT, MOVED, MUTED_BY_MODERATOR, MIC }

        public final Kind kind;
        @Nullable public final LeftReason reason;
        /** {@code NO_ANSWER}: the friend who did not join (for "Tekrar ara"). */
        @Nullable public final String friend;
        /** {@code RESTRICTED}: the ban's end (Unix seconds), when said. */
        @Nullable public final Long until;
        /** {@code MOVED}: the room. */
        @Nullable public final String room;
        @Nullable public final Mic mic;

        private Notice(Kind kind, @Nullable LeftReason reason, @Nullable String friend, @Nullable Long until,
                       @Nullable String room, @Nullable Mic mic) {
            this.kind = kind;
            this.reason = reason;
            this.friend = friend;
            this.until = until;
            this.room = room;
            this.mic = mic;
        }

        public static Notice left(LeftReason reason, @Nullable String friend, @Nullable Long until) {
            return new Notice(Kind.LEFT, reason, friend, until, null, null);
        }

        public static Notice moved(String room) {
            return new Notice(Kind.MOVED, null, null, null, room, null);
        }

        public static Notice mutedByModerator() {
            return new Notice(Kind.MUTED_BY_MODERATOR, null, null, null, null, null);
        }

        public static Notice mic(Mic mic) {
            return new Notice(Kind.MIC, null, null, null, null, mic);
        }

        /** Worth a system notification when no screen of the app shows (the desktop's {@code SystemNotice}). */
        public boolean system() {
            if (kind == Kind.MUTED_BY_MODERATOR) return true;
            return kind == Kind.LEFT && (reason == LeftReason.KICKED || reason == LeftReason.CLOSED
                    || reason == LeftReason.UNAVAILABLE || reason == LeftReason.RESTRICTED);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Notice)) return false;
            Notice n = (Notice) other;
            return kind == n.kind && reason == n.reason && eq(friend, n.friend) && eq(until, n.until) && eq(room, n.room)
                    && mic == n.mic;
        }

        @Override
        public int hashCode() {
            return kind.hashCode();
        }

        @Override
        public String toString() {
            return kind + (reason == null ? "" : "(" + reason + ")");
        }
    }

    /** One person as the engine plays them. */
    public static final class EnginePerson {
        public final String id;
        /** Percent, 0 … {@link #VOLUME_MAX}. */
        public final int volume;
        /** Not played: muted for the player, or muted for everyone. */
        public final boolean silent;

        public EnginePerson(String id, int volume, boolean silent) {
            this.id = id;
            this.volume = volume;
            this.silent = silent;
        }
    }

    /** Machine → engine (the desktop's {@code EngineCommand}). */
    public static final class Command {
        public enum Kind { CONFIGURE, JOIN, ANSWER, OFFER, TALK, DEAF, PEOPLE, ICE, SOUND, METERS, LEAVE }

        public final Kind kind;
        public int kbps;
        @Nullable public String sdp;
        public Map<String, String> mids = Collections.emptyMap();
        public boolean on;
        public List<EnginePerson> people = Collections.emptyList();
        @Nullable public VoiceWire.Ice ice;
        @Nullable public Sound sound;

        Command(Kind kind) {
            this.kind = kind;
        }

        @Override
        public String toString() {
            // SDP and credentials stay out of logs.
            switch (kind) {
                case TALK:
                case DEAF:
                case METERS:
                    return kind + "(" + on + ")";
                case SOUND:
                    return "SOUND(" + sound + ")";
                default:
                    return kind.toString();
            }
        }
    }

    /** What the caller does. */
    public static final class Effect {
        public enum Kind { CONNECT, SEND, DISCONNECT, ENGINE_OPEN, ENGINE_CLOSE, ENGINE, NOTICE, SPEAKING, ANSWER, CHANGED }

        public final Kind kind;
        public long generation;
        @Nullable public String ticket;
        @Nullable public String frame;
        @Nullable public Command command;
        @Nullable public Notice notice;
        public List<String> speaking = Collections.emptyList();
        public boolean meSpeaking;
        public int rid;
        @Nullable public VoiceAnswer answer;

        Effect(Kind kind) {
            this.kind = kind;
        }

        @Override
        public String toString() {
            switch (kind) {
                case ENGINE:
                    return "ENGINE " + command;
                case NOTICE:
                    return "NOTICE " + notice;
                case ANSWER:
                    return "ANSWER " + rid + " " + answer;
                case SEND:
                    // Only the frame's kind (the frame carries SDP and tickets).
                    String text = frame == null ? "" : frame;
                    int at = text.indexOf("\"t\":\"");
                    String type = at < 0 ? "?" : text.substring(at + 5, Math.max(at + 5, text.indexOf('"', at + 5)));
                    return "SEND " + type;
                default:
                    return kind.toString();
            }
        }
    }

    /** Per-person choices kept on the phone (by the friends service's id). */
    public interface Prefs {
        /** Percent (100 by default). */
        int volume(String person);

        boolean mutedForMe(String person);

        void setVolume(String person, int percent);

        void setMutedForMe(String person, boolean on);

        /** "Sesli sohbet sesleri". */
        boolean sounds();
    }

    /** How a stay begins. */
    public static final class Start {
        public final String room;
        public final String ticket;
        /** The friend the player called (the room rings them; "Aranıyor…"). */
        @Nullable public final String calling;
        /** A crew room the player opened ("Yeni oda": they may rename it). */
        public final boolean openedByMe;

        public Start(String room, String ticket, @Nullable String calling, boolean openedByMe) {
            this.room = room;
            this.ticket = ticket;
            this.calling = calling;
            this.openedByMe = openedByMe;
        }
    }

    /** Studio's numbers the stay follows (from the hub's {@code hello.voice}, carried in with the join). */
    public static final class Service {
        public final int kbps;
        public final int ringSeconds;
        public final int idleMinutes;

        public Service(int kbps, int ringSeconds, int idleMinutes) {
            this.kbps = kbps <= 0 ? DEFAULT_KBPS : kbps;
            this.ringSeconds = ringSeconds <= 0 ? DEFAULT_RING_SECONDS : ringSeconds;
            this.idleMinutes = idleMinutes;
        }

        public static final Service DEFAULT = new Service(DEFAULT_KBPS, DEFAULT_RING_SECONDS, DEFAULT_IDLE_MINUTES);
    }

    /** The reconnect backoff's shape (tests make it quick and fixed). */
    public static final class Backoff {
        final long initial;
        final long max;
        final long spread;

        public Backoff(long initial, long max, long spread) {
            this.initial = initial;
            this.max = max;
            this.spread = spread;
        }
    }

    // ---- inside ----

    private enum Link { CONNECTING, OPEN, WAITING }

    private enum Pending { TURN, INVITE, CANCEL, MOD }

    /** The inactivity rule (the desktop's {@code idle.rs}). */
    static final class Idle {
        static final int MIN_MINUTES = 10;
        static final int MAX_MINUTES = 240;
        long limitMs;
        long lastSpeech;

        Idle(long now, int minutes) {
            lastSpeech = now;
            setMinutes(minutes);
        }

        void setMinutes(int minutes) {
            int m = minutes == 0 ? DEFAULT_IDLE_MINUTES : Math.max(MIN_MINUTES, Math.min(MAX_MINUTES, minutes));
            limitMs = m * 60_000L;
        }

        void spoke(long now) {
            lastSpeech = Math.max(lastSpeech, now);
        }

        long deadline(long lastInput) {
            return Math.max(lastSpeech, lastInput) + limitMs;
        }

        boolean due(long now, long lastInput) {
            return now >= deadline(lastInput);
        }
    }

    private final class Stay {
        final String roomId;
        final boolean crew;
        String ticket;
        long generation;
        Link link = Link.CONNECTING;
        long waitingAt;
        @Nullable Long awaySince;
        boolean awayShown;
        @Nullable Long giveUpAt;
        int attempt;
        boolean welcomed;
        boolean joinWaiting = true;
        @Nullable VoiceRoom room;
        /** The engine's offer, waiting for the socket ({@code [sdp, mid]}). */
        @Nullable String[] offer;
        boolean joinSent;
        boolean offerAsked;
        boolean audioOk;
        int losses;
        @Nullable Long rejoinAt;
        @Nullable String calling;
        long callingUntil;
        final boolean openedByMe;
        TreeSet<String> speaking = new TreeSet<>();
        boolean talking;
        int quality;
        final Idle idle;
        @Nullable Long togetherSince;

        Stay(Start start, long generation, long now, long unix) {
            roomId = start.room;
            crew = !VoiceWire.isFriendRoom(start.room);
            ticket = start.ticket;
            this.generation = generation;
            openedByMe = start.openedByMe;
            idle = new Idle(now, service.idleMinutes);
            if (start.calling != null) {
                calling = start.calling;
                callingUntil = unix + service.ringSeconds;
            }
        }

        long nextBackoff() {
            if (attempt == 0 && backoff.spread > 0) {
                attempt = 1;
                return jitter(0, backoff.spread);
            }
            int exponent = Math.min(16, Math.max(0, attempt - 1));
            long ceiling = Math.min(backoff.max, backoff.initial << exponent);
            attempt++;
            return jitter(ceiling / 2, ceiling);
        }
    }

    private final Prefs prefs;
    private final Random random;
    private String me = "";
    private Service service = Service.DEFAULT;
    @Nullable private Stay stay;
    /** Kept between stays of this run (Discord keeps them too). */
    private boolean mute;
    private boolean deaf;
    private boolean attached;
    private boolean engineWanted;
    private boolean engineOpen;
    @Nullable private Long engineCloseAt;
    private Mic mic = Mic.OK;
    private long nextGeneration = 1;
    private int nextRid = 1;
    private final TreeMap<Integer, Pending> pending = new TreeMap<>();
    /** The room the last failed stay was in ("Tekrar dene"): room, crew, ticket. */
    @Nullable private String[] retry;
    @Nullable private Long restrictedUntil;
    private Backoff backoff = new Backoff(1_000, 15_000, 1_500);
    /** The screens show live speaking rings now (a room surface on screen). */
    private boolean meters;
    private boolean metersSent;

    public VoiceMachine(Prefs prefs, Random random) {
        this.prefs = prefs;
        this.random = random;
    }

    public void setBackoff(Backoff backoff) {
        this.backoff = backoff;
    }

    public void setMe(String id) {
        me = id == null ? "" : id;
    }

    public String me() {
        return me;
    }

    public boolean inRoom() {
        return stay != null;
    }

    @Nullable
    public String roomId() {
        return stay == null ? null : stay.roomId;
    }

    public boolean mute() {
        return mute;
    }

    public boolean deaf() {
        return deaf;
    }

    /** Whether the engine is open (a stay, or the minute after one). */
    public boolean engineOpen() {
        return engineOpen;
    }

    // ---- per-person choices ----

    /** A person's volume (percent) for the player. */
    public List<Effect> setVolume(String person, int percent) {
        int value = Math.max(0, Math.min(VOLUME_MAX, percent));
        if (prefs.volume(person) == value) return new ArrayList<>();
        prefs.setVolume(person, value);
        List<Effect> out = changed();
        out.addAll(playback());
        return out;
    }

    /** "Benim için sustur". */
    public List<Effect> setMutedForMe(String person, boolean on) {
        if (prefs.mutedForMe(person) == on) return new ArrayList<>();
        prefs.setMutedForMe(person, on);
        List<Effect> out = changed();
        out.addAll(playback());
        return out;
    }

    // ---- stays ----

    /** Joins a room with a ticket (a call's, a ring's, a crew room's, a move's). A stay elsewhere ends first, quietly. */
    public List<Effect> join(Start start, Service service, long now, long unix) {
        List<Effect> out = new ArrayList<>();
        if (me.isEmpty() || !VoiceWire.isRoomId(start.room)) {
            out.add(answer(JOIN_RID, VoiceAnswer.refused(Refusal.OFF)));
            return out;
        }
        this.service = service;
        if (stay != null) {
            endStay(now, null, null, out);
            // A move to another room is no ending (VOICE-CALL): its "Connected" says it.
            for (Iterator<Effect> it = out.iterator(); it.hasNext(); ) {
                if (isSound(it.next(), Sound.ENDED)) it.remove();
            }
        }
        retry = null;
        long generation = generation();
        stay = new Stay(start, generation, now, unix);
        Effect connect = new Effect(Effect.Kind.CONNECT);
        connect.generation = generation;
        connect.ticket = start.ticket;
        out.add(connect);
        wantEngine(now, out);
        if (attached) askOffer(out);
        syncMeters(out);
        out.add(new Effect(Effect.Kind.CHANGED));
        return out;
    }

    /** "Ayrıl" (also "İptal" while calling). */
    public List<Effect> leave(long now) {
        List<Effect> out = new ArrayList<>();
        if (stay != null) endStay(now, null, null, out);
        return out;
    }

    /** "Tekrar dene" after "Bağlanamadı.": room, crew (null for a friend room), ticket; null when none. */
    @Nullable
    public String[] retryTicket() {
        return retry == null ? null : retry.clone();
    }

    /** The player blocked {@code user}: a two-person call with them ends at once. */
    public List<Effect> blocked(String user, long now) {
        List<Effect> out = new ArrayList<>();
        VoiceRoom room = stay == null ? null : stay.room;
        if (room != null && !room.crewRoom && room.people.size() == 1 && room.people.containsKey(user)) {
            endStay(now, null, null, out);
        }
        return out;
    }

    private long generation() {
        return nextGeneration++;
    }

    private int rid() {
        int rid = nextRid;
        nextRid = nextRid == Integer.MAX_VALUE ? 1 : nextRid + 1;
        return rid;
    }

    private void endStay(long now, @Nullable LeftReason reason, @Nullable String friend, List<Effect> out) {
        Long until = restrictedUntil;
        restrictedUntil = null;
        Stay s = stay;
        if (s == null) return;
        stay = null;
        if (s.link == Link.OPEN) out.add(send(s.generation, VoiceWire.leave()));
        Effect disconnect = new Effect(Effect.Kind.DISCONNECT);
        disconnect.generation = s.generation;
        out.add(disconnect);
        if (s.joinWaiting) {
            VoiceAnswer answer;
            if (reason == null) answer = VoiceAnswer.refused(Refusal.NOTHING);
            else if (reason == LeftReason.UNAVAILABLE) answer = VoiceAnswer.refused(Refusal.OFF);
            else if (reason == LeftReason.RESTRICTED) answer = new VoiceAnswer(Refusal.STAFF_MUTED, null, until);
            else answer = VoiceAnswer.refused(Refusal.FAILED);
            out.add(answer(JOIN_RID, answer));
        }
        for (Integer rid : new ArrayList<>(pending.keySet())) out.add(answer(rid, VoiceAnswer.refused(Refusal.NOTHING)));
        pending.clear();
        if (attached) {
            // The stay's end is heard before the engine closes it; never for a join refused before it began.
            if (s.welcomed) cue(Sound.ENDED, out);
            out.add(engine(new Command(Command.Kind.LEAVE)));
            metersSent = false;
        }
        if (reason != null) {
            if (reason == LeftReason.FAILED) {
                String crew = s.room == null ? null : s.room.crew;
                retry = new String[] {s.roomId, crew, s.ticket};
            }
            Effect notice = new Effect(Effect.Kind.NOTICE);
            notice.notice = Notice.left(reason, friend, reason == LeftReason.RESTRICTED ? until : null);
            out.add(notice);
        }
        wantEngine(now, out);
        out.add(new Effect(Effect.Kind.CHANGED));
    }

    // ---- the room socket ----

    @Nullable
    private Stay current(long generation) {
        return stay != null && stay.generation == generation ? stay : null;
    }

    /** The socket of {@code generation} opened. */
    public List<Effect> socketOpen(long generation, long now) {
        List<Effect> out = new ArrayList<>();
        Stay s = current(generation);
        if (s == null) return out;
        s.link = Link.OPEN;
        if (s.joinWaiting) {
            s.joinWaiting = false;
            out.add(answer(JOIN_RID, VoiceAnswer.OK));
        }
        if (s.welcomed && s.audioOk) {
            // Back with the resume ticket and the audio still flowing.
            s.joinSent = true;
            out.add(send(generation, VoiceWire.sync()));
        } else {
            s.joinSent = false;
            if (s.offer != null) {
                String[] offer = s.offer;
                s.offer = null;
                s.joinSent = true;
                out.add(send(generation, VoiceWire.join(offer[0], offer[1])));
            } else if (attached) {
                askOffer(out);
            }
        }
        return out;
    }

    /** The socket could not open: an HTTP refusal ({@code status}, {@code code}) or none (status 0: the network). */
    public List<Effect> socketRefused(long generation, int status, @Nullable String code, long now) {
        List<Effect> out = new ArrayList<>();
        Stay s = current(generation);
        if (s == null) return out;
        boolean transient_ = status == 0 || status == 429 || (status >= 500 && status <= 599);
        if (transient_ && (s.welcomed || s.awaySince != null || !s.joinWaiting)) {
            connectionAway(now, null, out);
            return out;
        }
        if (transient_) {
            // The first connect: once more, then "Bağlanamadı.".
            s.joinWaiting = false;
            out.add(answer(JOIN_RID, VoiceAnswer.OK));
            connectionAway(now, null, out);
            return out;
        }
        VoiceAnswer answer = VoiceAnswer.fromCode(code == null ? "bad_request" : code, null, null);
        if (s.joinWaiting) {
            s.joinWaiting = false;
            out.add(answer(JOIN_RID, answer));
            // Refused before it began: no notice beyond the answer.
            endStay(now, null, null, out);
        } else {
            LeftReason reason;
            if (answer.refused == Refusal.OFF) reason = LeftReason.UNAVAILABLE;
            else if (answer.refused == Refusal.KICKED) reason = LeftReason.KICKED;
            else if (answer.refused == Refusal.STAFF_MUTED) reason = LeftReason.RESTRICTED;
            else reason = LeftReason.FAILED;
            endStay(now, reason, null, out);
        }
        return out;
    }

    /** The socket closed ({@code code}: the close frame's, 0 when it just dropped). */
    public List<Effect> socketClosed(long generation, int code, long now) {
        List<Effect> out = new ArrayList<>();
        if (current(generation) == null) return out;
        switch (code) {
            case VoiceWire.CLOSE_SESSION_ENDED:
                endStay(now, LeftReason.UNAVAILABLE, null, out);
                break;
            case VoiceWire.CLOSE_PROTOCOL:
                endStay(now, LeftReason.FAILED, null, out);
                break;
            case VoiceWire.CLOSE_REPLACED:
                // A newer socket of this device took the seat: nothing to come back to here.
                endStay(now, null, null, out);
                break;
            case VoiceWire.CLOSE_RATE_LIMITED:
                connectionAway(now, RATE_LIMITED_WAIT_MS, out);
                break;
            default:
                connectionAway(now, null, out);
                break;
        }
        return out;
    }

    /** The socket is gone for now: back with backoff ({@code wait}: at least this long), give up a minute after it went. */
    private void connectionAway(long now, @Nullable Long wait, List<Effect> out) {
        Stay s = stay;
        if (s == null) return;
        Effect disconnect = new Effect(Effect.Kind.DISCONNECT);
        disconnect.generation = s.generation;
        out.add(disconnect);
        s.generation = generation();
        if (s.awaySince == null) s.awaySince = now;
        long since = s.awaySince;
        long delay = s.nextBackoff();
        s.link = Link.WAITING;
        s.waitingAt = now + Math.max(wait == null ? 0 : wait, delay);
        long limit = wait != null
                ? Math.min(now + wait + 30_000, since + VoiceWire.SEAT_SECONDS * 1_000 - 5_000)
                : since + GIVE_UP_MS;
        s.giveUpAt = s.giveUpAt == null ? limit : Math.max(s.giveUpAt, limit);
        s.joinSent = false;
        // A TURN answer can only come on the socket that asked.
        for (Iterator<Map.Entry<Integer, Pending>> it = pending.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue() == Pending.TURN) it.remove();
        }
        out.add(new Effect(Effect.Kind.CHANGED));
    }

    /** A message from the room on {@code generation}'s socket. */
    public List<Effect> socketMessage(long generation, Message message, long now, long unix) {
        List<Effect> out = new ArrayList<>();
        if (current(generation) == null) return out;
        Stay s = stay;
        switch (message.kind) {
            case HELLO: {
                VoiceRoom before = s.room;
                VoiceRoom model = VoiceRoom.fromHello(me, message);
                if (message.resume != null) s.ticket = message.resume;
                boolean first = !s.welcomed;
                s.welcomed = true;
                // With an answer the audio is still on its way: a connection lost before stays away until the
                // engine says connected, so rejoins that never connect cannot hold the stay forever.
                boolean back = message.sdp == null && s.awayShown;
                if (message.sdp == null) {
                    s.awaySince = null;
                    s.awayShown = false;
                    s.giveUpAt = null;
                }
                s.attempt = 0;
                boolean someoneNew = false;
                if (before != null) {
                    for (String id : model.people.keySet()) {
                        if (!before.people.containsKey(id)) someoneNew = true;
                    }
                }
                boolean alone = model.people.isEmpty();
                // VOICE-CALL: the friend the player calls is here already: the call is answered.
                boolean answered = s.calling != null && model.people.containsKey(s.calling);
                if (answered) s.calling = null;
                if (!alone && s.togetherSince == null) s.togetherSince = unix;
                s.room = model;
                if (message.sdp != null) {
                    s.audioOk = false;
                    if (attached) {
                        Command c = new Command(Command.Kind.ANSWER);
                        c.sdp = message.sdp;
                        out.add(engine(c));
                    }
                }
                out.add(send(generation, VoiceWire.set(mute, deaf)));
                boolean calling = s.calling != null;
                if (calling && alone && attached) out.add(sound(Sound.WAITING));
                if (answered && attached) out.add(sound(Sound.STOP));
                if ((first && !calling) || back) {
                    cue(Sound.CONNECTED, out);
                } else if (someoneNew) {
                    cue(Sound.JOIN, out);
                }
                out.addAll(playback());
                talk(out);
                syncMeters(out);
                out.add(new Effect(Effect.Kind.CHANGED));
                break;
            }
            case OFFER:
                if (attached) {
                    Command c = new Command(Command.Kind.OFFER);
                    c.sdp = message.sdp;
                    c.mids = message.mids;
                    out.add(engine(c));
                }
                break;
            case KICKED:
                endStay(now, LeftReason.KICKED, null, out);
                break;
            case CLOSED: {
                String friend = s.calling;
                LeftReason reason;
                switch (message.closedWhy) {
                    case LEADER: reason = LeftReason.CLOSED; break;
                    case ALONE: reason = LeftReason.ALONE; break;
                    case NO_ANSWER: reason = LeftReason.NO_ANSWER; break;
                    case ELSEWHERE: reason = LeftReason.ELSEWHERE; break;
                    case CREW: reason = LeftReason.CREW; break;
                    case MUTED: reason = LeftReason.RESTRICTED; break;
                    default: reason = LeftReason.UNAVAILABLE; break;
                }
                if (reason == LeftReason.RESTRICTED) restrictedUntil = message.closedUntil;
                endStay(now, reason, reason == LeftReason.NO_ANSWER ? friend : null, out);
                break;
            }
            case MOVED: {
                if (!VoiceWire.isRoomId(message.roomId)) {
                    endStay(now, LeftReason.FAILED, null, out);
                    break;
                }
                List<Effect> moved = join(new Start(message.roomId, message.ticket, null, false), service, now, unix);
                // Nobody waits for this join's answer.
                for (Iterator<Effect> it = moved.iterator(); it.hasNext(); ) {
                    Effect e = it.next();
                    if (e.kind == Effect.Kind.ANSWER && e.rid == JOIN_RID) it.remove();
                }
                if (stay != null) stay.joinWaiting = false;
                out.addAll(moved);
                Effect notice = new Effect(Effect.Kind.NOTICE);
                notice.notice = Notice.moved(message.roomId);
                out.add(notice);
                break;
            }
            case ACK: {
                if (message.rid == null) break;
                Pending p = pending.remove(message.rid);
                if (p == Pending.TURN) {
                    if (attached && message.ice != null && !message.ice.urls.isEmpty()) {
                        Command c = new Command(Command.Kind.ICE);
                        c.ice = message.ice;
                        out.add(engine(c));
                    } else {
                        // No credentials after all: as a lost connection.
                        audioLost(now, out);
                    }
                } else if (p != null) {
                    out.add(answer(message.rid, VoiceAnswer.OK));
                }
                break;
            }
            case ERROR: {
                if (message.rid == null) break;
                Pending p = pending.remove(message.rid);
                if (p == Pending.TURN) {
                    // `turn_unavailable` or `rate_limited`: no stay without a connection, so as lost.
                    audioLost(now, out);
                } else if (p != null) {
                    out.add(answer(message.rid, VoiceAnswer.fromCode(message.code, message.retryAfter, message.errorUntil)));
                }
                break;
            }
            case UNKNOWN:
                break;
            default: {
                VoiceRoom room = s.room;
                if (room == null) break;
                boolean micChosen = !mute && !deaf;
                boolean cutBefore = room.mySm;
                VoiceRoom.Applied applied = room.apply(message);
                boolean cut = room.mySm;
                if (applied.left != null) s.speaking.remove(applied.left);
                // VOICE-CALL: the call's timer starts with the first one to share the room.
                if (applied.joined != null && s.togetherSince == null) s.togetherSince = unix;
                // Someone came: the call is answered.
                boolean answered = applied.joined != null && s.calling != null;
                if (answered) s.calling = null;
                if (answered && attached) out.add(sound(Sound.STOP));
                if (answered) {
                    cue(Sound.CONNECTED, out);
                } else if (applied.joined != null) {
                    cue(Sound.JOIN, out);
                } else if (applied.left != null) {
                    cue(Sound.LEAVE, out);
                }
                if (applied.mutedMe) {
                    Effect notice = new Effect(Effect.Kind.NOTICE);
                    notice.notice = Notice.mutedByModerator();
                    out.add(notice);
                }
                // A moderator's mute (or its end) turns a microphone the player keeps open.
                if (cut != cutBefore && micChosen) cue(cut ? Sound.MIC_OFF : Sound.MIC_ON, out);
                if (applied.playback) out.addAll(playback());
                if ((message.kind == VoiceWire.Kind.STATE && applied.changed) || applied.joined != null
                        || applied.left != null) {
                    talk(out);
                }
                if (applied.changed) out.add(new Effect(Effect.Kind.CHANGED));
                break;
            }
        }
        return out;
    }

    // ---- requests on the room socket ----

    @Nullable
    private Long openGeneration() {
        return stay != null && stay.link == Link.OPEN && stay.welcomed ? stay.generation : null;
    }

    /** A request that waits for its answer: its {@code rid} and the effects, or the refusal. */
    public static final class Request {
        public final int rid;
        public final List<Effect> effects;
        @Nullable public final VoiceAnswer refused;

        Request(int rid, List<Effect> effects, @Nullable VoiceAnswer refused) {
            this.rid = rid;
            this.effects = effects;
            this.refused = refused;
        }
    }

    /** "Davet et": {@code rid} to wait on (its answer comes as an {@code ANSWER} effect). */
    public Request invite(String to) {
        return request(Pending.INVITE, rid -> VoiceWire.invite(to, rid));
    }

    /** Takes the player's ringing invite back. */
    public Request cancelInvite(String to) {
        return request(Pending.CANCEL, rid -> VoiceWire.cancel(to, rid));
    }

    /** A leader's or moderator's action ({@code person} for those on someone, {@code room} for a move, {@code name}). */
    public Request moderate(ModAct act, @Nullable String person, @Nullable String room, @Nullable String name) {
        if (act == ModAct.MOVE && !VoiceWire.isRoomId(room)) {
            return new Request(0, new ArrayList<>(), VoiceAnswer.refused(Refusal.ROOM_GONE));
        }
        String trimmed = null;
        if (act == ModAct.NAME) {
            trimmed = name == null ? "" : name.trim();
            int chars = trimmed.codePointCount(0, trimmed.length());
            if (chars < 3 || chars > 24) return new Request(0, new ArrayList<>(), VoiceAnswer.refused(Refusal.INVALID_NAME));
        }
        final String finalName = trimmed;
        final String to = act == ModAct.NAME || act == ModAct.CLOSE ? null : person;
        final String target = act == ModAct.MOVE ? room : null;
        return request(Pending.MOD, rid -> VoiceWire.mod(act, to, target, finalName, rid));
    }

    private interface FrameOf {
        String frame(int rid);
    }

    private Request request(Pending kind, FrameOf frame) {
        Long generation = openGeneration();
        if (generation == null) {
            return new Request(0, new ArrayList<>(), VoiceAnswer.refused(stay != null ? Refusal.FAILED : Refusal.NOTHING));
        }
        int rid = rid();
        pending.put(rid, kind);
        List<Effect> out = new ArrayList<>();
        out.add(send(generation, frame.frame(rid)));
        return new Request(rid, out, null);
    }

    /** A request's caller gave up waiting: forget its {@code rid}. */
    public void forget(int rid) {
        pending.remove(rid);
    }

    // ---- the player's own state ----

    public List<Effect> setMute(boolean on) {
        List<Effect> out = new ArrayList<>();
        if (mute == on && !(deaf && !on)) return out;
        boolean wasDeaf = deaf;
        mute = on;
        // Opening the microphone ends deafness too (Discord's way).
        if (!on) {
            deaf = false;
            if (attached) out.add(engine(flag(Command.Kind.DEAF, false)));
        }
        sendSet(out);
        talk(out);
        if (stay != null) {
            if (on && !wasDeaf) cue(Sound.MIC_OFF, out);
            else if (!on && wasDeaf) cue(Sound.DEAF_OFF, out);
            else if (!on) cue(Sound.MIC_ON, out);
        }
        out.add(new Effect(Effect.Kind.CHANGED));
        return out;
    }

    public List<Effect> setDeaf(boolean on) {
        List<Effect> out = new ArrayList<>();
        if (deaf == on) return out;
        deaf = on;
        if (attached) out.add(engine(flag(Command.Kind.DEAF, on)));
        sendSet(out);
        talk(out);
        if (stay != null) cue(on ? Sound.DEAF_ON : Sound.DEAF_OFF, out);
        out.add(new Effect(Effect.Kind.CHANGED));
        return out;
    }

    /** The notification's and the call bar's "Mikrofon": deafened, the microphone shows as off, so it opens both. */
    public List<Effect> toggleMic() {
        return setMute(!(mute || deaf));
    }

    private void sendSet(List<Effect> out) {
        Long generation = openGeneration();
        if (generation != null) out.add(send(generation, VoiceWire.set(mute, deaf)));
    }

    /** Whether the microphone may send now: not muted, deafened or muted for everyone, and someone to hear it. */
    private boolean talkOpen() {
        Stay s = stay;
        if (s == null) return false;
        boolean mutedForAll = s.room != null && s.room.mySm;
        // VOICE-CALL: nobody else in the room hears it, so nothing goes out until someone comes.
        boolean alone = s.room == null || s.room.people.isEmpty();
        return !mute && !deaf && !mutedForAll && !alone && mic == Mic.OK;
    }

    private void talk(List<Effect> out) {
        if (attached && stay != null) out.add(engine(flag(Command.Kind.TALK, talkOpen())));
    }

    private void cue(Sound sound, List<Effect> out) {
        if (attached && prefs.sounds()) out.add(sound(sound));
    }

    /** Live speaking rings while a room surface shows (and nothing measured otherwise). */
    public List<Effect> setMeters(boolean on) {
        List<Effect> out = new ArrayList<>();
        if (meters == on) return out;
        meters = on;
        syncMeters(out);
        return out;
    }

    private void syncMeters(List<Effect> out) {
        if (!attached) return;
        boolean wanted = stay != null && meters;
        if (wanted == metersSent) return;
        metersSent = wanted;
        out.add(engine(flag(Command.Kind.METERS, wanted)));
    }

    /** How each person plays now. */
    private List<Effect> playback() {
        List<Effect> out = new ArrayList<>();
        VoiceRoom room = stay == null ? null : stay.room;
        if (room == null || !attached) return out;
        List<EnginePerson> people = new ArrayList<>();
        for (Person person : room.peopleInOrder()) {
            people.add(new EnginePerson(person.u, Math.max(0, Math.min(VOLUME_MAX, prefs.volume(person.u))),
                    prefs.mutedForMe(person.u) || person.sm));
        }
        Command c = new Command(Command.Kind.PEOPLE);
        c.people = people;
        out.add(engine(c));
        return out;
    }

    // ---- the engine ----

    private void wantEngine(long now, List<Effect> out) {
        boolean wanted = stay != null;
        if (wanted == engineWanted) return;
        engineWanted = wanted;
        if (wanted) {
            engineCloseAt = null;
            if (!engineOpen) {
                engineOpen = true;
                out.add(new Effect(Effect.Kind.ENGINE_OPEN));
            }
        } else if (engineOpen) {
            engineCloseAt = now + LINGER_MS;
        }
    }

    /** The engine is ready (after {@code ENGINE_OPEN}): configured, and asked for an offer during a stay. */
    public List<Effect> engineAttached() {
        attached = true;
        metersSent = false;
        List<Effect> out = new ArrayList<>();
        Command configure = new Command(Command.Kind.CONFIGURE);
        configure.kbps = service.kbps;
        out.add(engine(configure));
        if (stay != null) {
            out.add(engine(flag(Command.Kind.DEAF, deaf)));
            // A new engine means a new peer connection.
            stay.audioOk = false;
            stay.joinSent = false;
            stay.offer = null;
            askOffer(out);
            out.addAll(playback());
            talk(out);
            syncMeters(out);
        }
        return out;
    }

    private void askOffer(List<Effect> out) {
        Stay s = stay;
        if (s != null && !s.offerAsked) {
            s.offerAsked = true;
            s.audioOk = false;
            s.rejoinAt = null;
            out.add(engine(new Command(Command.Kind.JOIN)));
        }
    }

    /** The audio connection is gone (lost, or TURN could not be had): no stay without a connection. */
    private void audioLost(long now, List<Effect> out) {
        Stay s = stay;
        if (s == null) return;
        s.audioOk = false;
        if (s.awaySince == null) s.awaySince = now;
        if (s.giveUpAt == null) s.giveUpAt = now + GIVE_UP_MS;
        s.offerAsked = false;
        s.offer = null;
        long wait = rejoinWait(s.losses);
        s.losses++;
        out.add(new Effect(Effect.Kind.CHANGED));
        if (!attached) return;
        if (wait == 0) askOffer(out);
        else s.rejoinAt = now + wait;
    }

    /** The engine's offer: a first join, or a new peer connection during the stay (same socket). */
    public List<Effect> engineOffer(@Nullable String sdp, @Nullable String mid) {
        List<Effect> out = new ArrayList<>();
        Stay s = stay;
        if (s == null) return out;
        s.offerAsked = false;
        boolean sendNow = s.link == Link.OPEN && (!s.joinSent || s.welcomed);
        if (sendNow) {
            s.joinSent = true;
            out.add(send(s.generation, VoiceWire.join(sdp, mid)));
        } else {
            s.offer = new String[] {sdp, mid};
        }
        return out;
    }

    /** The engine's answer to the SFU's offer. */
    public List<Effect> engineAnswer(String sdp) {
        List<Effect> out = new ArrayList<>();
        Long generation = openGeneration();
        if (generation != null) out.add(send(generation, VoiceWire.answer(sdp)));
        return out;
    }

    /** Who makes sound now ({@code ids}), and whether the player speaks ({@code me}). */
    public List<Effect> engineSpeaking(List<String> ids, boolean meTalking, long now) {
        List<Effect> out = new ArrayList<>();
        Stay s = stay;
        if (s == null) return out;
        TreeSet<String> known = new TreeSet<>();
        for (String id : ids) if (s.room != null && s.room.people.containsKey(id)) known.add(id);
        if (meTalking) s.idle.spoke(now);
        if (!known.equals(s.speaking) || meTalking != s.talking) {
            s.speaking = known;
            s.talking = meTalking;
            Effect e = new Effect(Effect.Kind.SPEAKING);
            e.speaking = new ArrayList<>(known);
            e.meSpeaking = meTalking;
            out.add(e);
        }
        return out;
    }

    /** The connection's quality (0 good, 1 weak, 2 bad) when it changes. */
    public List<Effect> engineQuality(int q) {
        List<Effect> out = new ArrayList<>();
        int value = VoiceWire.quality(q);
        Long generation = openGeneration();
        if (stay != null && stay.quality != value) {
            stay.quality = value;
            if (generation != null) out.add(send(generation, VoiceWire.qualityFrame(value)));
            out.add(new Effect(Effect.Kind.CHANGED));
        }
        return out;
    }

    /** Never connected: voice's own TURN may help. */
    public List<Effect> engineNeedTurn(long now) {
        List<Effect> out = new ArrayList<>();
        Stay s = stay;
        if (s != null) {
            if (s.awaySince == null) s.awaySince = now;
            if (s.giveUpAt == null) s.giveUpAt = now + GIVE_UP_MS;
            out.add(new Effect(Effect.Kind.CHANGED));
        }
        Request request = request(Pending.TURN, VoiceWire::turn);
        if (request.refused == null) out.addAll(request.effects);
        else audioLost(now, out);
        return out;
    }

    public List<Effect> engineLost(long now) {
        List<Effect> out = new ArrayList<>();
        audioLost(now, out);
        return out;
    }

    public List<Effect> engineConnected() {
        List<Effect> out = new ArrayList<>();
        boolean back = false;
        Stay s = stay;
        if (s != null) {
            s.audioOk = true;
            s.losses = 0;
            s.rejoinAt = null;
            if (s.link == Link.OPEN) {
                back = s.awayShown;
                s.awaySince = null;
                s.awayShown = false;
                s.giveUpAt = null;
            }
            out.add(new Effect(Effect.Kind.CHANGED));
        }
        // VOICE-CALL: back after "Yeniden bağlanıyor…".
        if (back) cue(Sound.CONNECTED, out);
        return out;
    }

    /** The microphone as the engine found it (no permission, none at all): the player listens only. */
    public List<Effect> engineMic(Mic found) {
        List<Effect> out = new ArrayList<>();
        if (mic == found) return out;
        mic = found;
        if (found != Mic.OK && stay != null) {
            Effect notice = new Effect(Effect.Kind.NOTICE);
            notice.notice = Notice.mic(found);
            out.add(notice);
        }
        talk(out);
        out.add(new Effect(Effect.Kind.CHANGED));
        return out;
    }

    // ---- time ----

    /** The moment the machine wants {@link #tick}, or null ({@code lastInput}: the phone's last touch). */
    @Nullable
    public Long nextDeadline(long lastInput) {
        Long next = null;
        if (engineCloseAt != null) next = min(next, engineCloseAt);
        Stay s = stay;
        if (s != null) {
            if (s.link == Link.WAITING) next = min(next, s.waitingAt);
            if (s.giveUpAt != null) next = min(next, s.giveUpAt);
            if (s.rejoinAt != null) next = min(next, s.rejoinAt);
            if (s.awaySince != null && !s.awayShown) next = min(next, s.awaySince + RECONNECTING_SHOWN_MS);
            if (s.welcomed) next = min(next, s.idle.deadline(lastInput));
        }
        return next;
    }

    public List<Effect> tick(long now, long unix, long lastInput) {
        List<Effect> out = new ArrayList<>();
        if (engineCloseAt != null && now >= engineCloseAt && !engineWanted) {
            engineCloseAt = null;
            engineOpen = false;
            attached = false;
            metersSent = false;
            out.add(new Effect(Effect.Kind.ENGINE_CLOSE));
        }
        LeftReason end = null;
        Long reconnectGeneration = null;
        String reconnectTicket = null;
        boolean rejoin = false;
        boolean lost = false;
        Stay s = stay;
        if (s != null) {
            if (s.giveUpAt != null && now >= s.giveUpAt) {
                end = LeftReason.FAILED;
            } else if (s.welcomed && s.idle.due(now, lastInput)) {
                end = LeftReason.IDLE;
            } else {
                if (s.link == Link.WAITING && now >= s.waitingAt) {
                    s.link = Link.CONNECTING;
                    reconnectGeneration = s.generation;
                    reconnectTicket = s.ticket;
                }
                if (s.rejoinAt != null && now >= s.rejoinAt) {
                    s.rejoinAt = null;
                    rejoin = true;
                }
                if (!s.awayShown && s.awaySince != null && now >= s.awaySince + RECONNECTING_SHOWN_MS) {
                    s.awayShown = true;
                    lost = true;
                    out.add(new Effect(Effect.Kind.CHANGED));
                }
                if (s.room != null && s.room.expireRings(unix)) out.add(new Effect(Effect.Kind.CHANGED));
            }
        }
        // VOICE-CALL: "Yeniden bağlanıyor…" shows now; heard too.
        if (lost) cue(Sound.LOST, out);
        if (end != null) endStay(now, end, null, out);
        if (reconnectGeneration != null) {
            Effect connect = new Effect(Effect.Kind.CONNECT);
            connect.generation = reconnectGeneration;
            connect.ticket = reconnectTicket;
            out.add(connect);
        }
        if (rejoin && attached) askOffer(out);
        dedupChanged(out);
        return out;
    }

    // ---- the screens' view ----

    /** The stay as the screens show it, or null when there is none. */
    @Nullable
    public VoiceState view(long now, long unix) {
        Stay s = stay;
        if (s == null) return null;
        VoiceState v = new VoiceState();
        v.room = s.roomId;
        v.crewRoom = s.crew;
        v.general = s.roomId.startsWith("g");
        if (s.awayShown || (s.awaySince != null && now >= s.awaySince + RECONNECTING_SHOWN_MS)) {
            v.phase = VoiceState.RECONNECTING;
        } else if (s.welcomed) {
            v.phase = VoiceState.LIVE;
        } else {
            v.phase = VoiceState.JOINING;
        }
        v.calling = s.calling;
        v.callingUntil = s.callingUntil;
        v.togetherSince = s.togetherSince;
        v.mic = mic == Mic.DENIED ? "denied" : mic == Mic.MISSING ? "missing" : null;
        v.me.id = me;
        v.me.mute = mute;
        v.me.deaf = deaf;
        v.me.talking = s.talking;
        v.me.quality = s.quality;
        VoiceRoom room = s.room;
        if (room == null) return v;
        v.crew = room.crew;
        v.name = room.name;
        v.max = room.max;
        v.me.role = roleName(room.myRole);
        v.me.mutedByModerator = room.mySm;
        for (Person person : room.peopleInOrder()) {
            VoiceState.Person p = new VoiceState.Person();
            Role theirs = room.roleOf(person.u);
            boolean acts = room.myRole == Role.LEADER || (room.myRole == Role.MODERATOR && theirs == Role.MEMBER);
            p.id = person.u;
            p.role = roleName(theirs);
            p.mute = person.mute;
            p.deaf = person.deaf;
            p.mutedByModerator = person.sm;
            p.quality = person.q;
            p.speaking = s.speaking.contains(person.u);
            p.volume = Math.max(0, Math.min(VOLUME_MAX, prefs.volume(person.u)));
            p.mutedForMe = prefs.mutedForMe(person.u);
            p.joinedAt = person.j;
            p.canMute = acts;
            p.canKick = acts;
            // The screens list the crew's other rooms (they hold the crew's list).
            p.canMove = acts && room.crewRoom;
            p.canModerator = !room.crewRoom && room.myRole == Role.LEADER;
            if (person.card != null) {
                p.cardName = person.card.name;
                p.cardAvatar = person.card.avatar;
                p.cardAvatarHash = person.card.avatarHash;
                p.cardDiscordId = person.card.discordId;
            }
            v.people.add(p);
        }
        for (Map.Entry<String, Long> ring : room.ringing.entrySet()) {
            VoiceState.OutRing r = new VoiceState.OutRing();
            r.to = ring.getKey();
            r.until = ring.getValue();
            v.ringing.add(r);
        }
        boolean general = room.isGeneral();
        v.canRename = !general && (room.crewRoom ? room.myRole != Role.MEMBER || s.openedByMe : room.myRole == Role.LEADER);
        v.canClose = !general && room.myRole == Role.LEADER;
        return v;
    }

    // ---- helpers ----

    private static String roleName(Role role) {
        return role == Role.LEADER ? VoiceState.LEADER : role == Role.MODERATOR ? VoiceState.MODERATOR : VoiceState.MEMBER;
    }

    private long jitter(long low, long high) {
        if (high <= low) return low;
        return low + (long) (random.nextDouble() * (high - low + 1));
    }

    static long rejoinWait(int losses) {
        if (losses == 0) return 0;
        return Math.min(REJOIN_MAX_MS, 1_000L << Math.min(3, losses - 1));
    }

    private static Long min(@Nullable Long a, long b) {
        return a == null ? b : Math.min(a, b);
    }

    private static Effect send(long generation, String frame) {
        Effect e = new Effect(Effect.Kind.SEND);
        e.generation = generation;
        e.frame = frame;
        return e;
    }

    private static Effect engine(Command command) {
        Effect e = new Effect(Effect.Kind.ENGINE);
        e.command = command;
        return e;
    }

    private static Effect sound(Sound sound) {
        Command c = new Command(Command.Kind.SOUND);
        c.sound = sound;
        return engine(c);
    }

    private static Command flag(Command.Kind kind, boolean on) {
        Command c = new Command(kind);
        c.on = on;
        return c;
    }

    private static Effect answer(int rid, VoiceAnswer answer) {
        Effect e = new Effect(Effect.Kind.ANSWER);
        e.rid = rid;
        e.answer = answer;
        return e;
    }

    private static List<Effect> changed() {
        List<Effect> out = new ArrayList<>();
        out.add(new Effect(Effect.Kind.CHANGED));
        return out;
    }

    static boolean isSound(Effect effect, Sound sound) {
        return effect.kind == Effect.Kind.ENGINE && effect.command != null && effect.command.kind == Command.Kind.SOUND
                && effect.command.sound == sound;
    }

    /** One {@code CHANGED} is enough. */
    static void dedupChanged(List<Effect> out) {
        boolean seen = false;
        for (Iterator<Effect> it = out.iterator(); it.hasNext(); ) {
            if (it.next().kind != Effect.Kind.CHANGED) continue;
            if (seen) it.remove();
            seen = true;
        }
    }

    private static boolean eq(@Nullable Object a, @Nullable Object b) {
        return a == null ? b == null : a.equals(b);
    }
}
