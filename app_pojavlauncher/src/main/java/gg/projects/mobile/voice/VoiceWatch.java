package gg.projects.mobile.voice;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * MOB-WATCH: watching a screen share on the phone, the machine's part of it (the desktop's {@code share_machine.rs},
 * its "Watching" half, rule for rule; the lead's {@code share.md} §4, {@code sqol.md} §1.1). A child of
 * {@link VoiceMachine}: it shares the stay's socket, its request numbers and its sounds through {@link Host}.
 *
 * <ul>
 *   <li>One watch session per player (the room's, never seen here): {@code watch} asks for a share, the room's
 *       {@code woffer} goes to the watch engine, its answer back as {@code wanswer}; {@code layer} switches between the
 *       full and the small picture; {@code unwatch} lets go.</li>
 *   <li>The phone watches one stream at a time: watching another lets the first go (a small screen shows one picture
 *       well, data and battery count double, and low-end phones have few hardware decoders).</li>
 *   <li>The stream screen out of sight for {@value #HIDDEN_GRACE_MS} ms (the screen off, the app in the background
 *       without picture-in-picture) lets go of everything and asks again when it is back.</li>
 *   <li>Half an hour without a touch of the stream screen asks "Hâlâ izliyor musun?"; a minute without an answer stops
 *       watching. The phone's other use does not count: a phone with its screen on is not a player watching.</li>
 *   <li>A connection lost is asked for anew, {@value #LOSSES_MAX} times in a row at most.</li>
 * </ul>
 */
final class VoiceWatch {
    /** The stream screen may be out of sight this long before watching lets go (a quick look elsewhere keeps it). */
    static final long HIDDEN_GRACE_MS = 3_000;
    /** A {@code watch} with no offer by then is as a lost connection. */
    static final long OFFER_WAIT_MS = 20_000;
    /** "Hâlâ izliyor musun?" after this long without a touch… */
    static final long STILL_ASK_MS = 30 * 60_000L;
    /** …and watching stops this long after it asked. */
    static final long STILL_ANSWER_MS = 60_000;
    /** Lost connections in a row before watching gives up ("Yayına bağlanılamadı."). */
    static final int LOSSES_MAX = 3;

    /** What the screen hears about watching (the stream screen's own words; {@link VoiceState#watchNotice}). */
    static final String NOTICE_ENDED = "ended";
    static final String NOTICE_FAILED = "failed";
    static final String NOTICE_STOPPED = "stopped";

    /** The machine's side of watching. */
    interface Host {
        /** The socket a frame may go on now (open and welcomed), or null. */
        @Nullable
        Long openGeneration();

        /** A new request number (the machine's own counter). */
        int rid();

        /** The player's id. */
        String me();

        /** The shares the room lists for {@code u} now (empty: none). */
        List<VoiceWire.Share> shares(String u);

        /** One of voice chat's sounds, as the machine plays them. */
        void cue(VoiceMachine.Sound sound, List<VoiceMachine.Effect> out);
    }

    /** Machine → watch engine (the desktop's {@code WatchCmd}). */
    public static final class Cmd {
        public enum Kind { OFFER, ICE, RESET }

        public final Kind kind;
        @Nullable public final String sdp;
        public final Map<String, VoiceWire.WatchMid> mids;
        /** The first offer of a new watch session: a new peer connection. */
        public final boolean fresh;
        @Nullable public final VoiceWire.Ice ice;
        /**
         * RESET: the TURN credentials stay (the session is asked anew after a loss, which is what they were asked for);
         * otherwise nothing is watched any more and they go.
         */
        public final boolean keepTurn;

        private Cmd(Kind kind, @Nullable String sdp, Map<String, VoiceWire.WatchMid> mids, boolean fresh,
                    @Nullable VoiceWire.Ice ice, boolean keepTurn) {
            this.kind = kind;
            this.sdp = sdp;
            this.mids = mids;
            this.fresh = fresh;
            this.ice = ice;
            this.keepTurn = keepTurn;
        }

        static Cmd offer(String sdp, Map<String, VoiceWire.WatchMid> mids, boolean fresh) {
            return new Cmd(Kind.OFFER, sdp, mids, fresh, null, false);
        }

        static Cmd ice(VoiceWire.Ice ice) {
            return new Cmd(Kind.ICE, null, Collections.<String, VoiceWire.WatchMid>emptyMap(), false, ice, false);
        }

        static Cmd reset(boolean keepTurn) {
            return new Cmd(Kind.RESET, null, Collections.<String, VoiceWire.WatchMid>emptyMap(), false, null, keepTurn);
        }

        @Override
        public String toString() {
            // SDP and credentials stay out of logs.
            return kind == Kind.OFFER ? "OFFER" + (fresh ? "(fresh)" : "") + mids.values() : kind.toString();
        }
    }

    /** The watch engine's report (the desktop's {@code WatchReport}). */
    enum Report { ANSWER, NEED_TURN, CONNECTED, LOST }

    private static final class Watching {
        final String u;
        final int n;
        String layer;
        /** {@code watch} went out on this session (the room knows it). */
        boolean sent;
        /** Its {@code watch} still waits for the room's offer. */
        @Nullable Integer rid;
        /** When that {@code watch} went out (-1: none waits). */
        long askedAt = -1;

        Watching(String u, int n, String layer) {
            this.u = u;
            this.n = n;
            this.layer = layer;
        }

        boolean is(String u, int n) {
            return this.u.equals(u) && this.n == n;
        }
    }

    /** A request of watching's own: a {@code watch} (its share) or TURN for the watch connection. */
    private static final class Asked {
        @Nullable final String u;
        final int n;

        Asked(@Nullable String u, int n) {
            this.u = u;
            this.n = n;
        }

        boolean turn() {
            return u == null;
        }
    }

    private final Host host;
    private final List<Watching> watching = new ArrayList<>();
    private final Map<Integer, Asked> pending = new TreeMap<>();
    /** The watch engine is there (the voice engine is open). */
    private boolean engine;
    /** The stream screen is on screen (picture-in-picture counts). */
    private boolean visible = true;
    private long hiddenSince = -1;
    /** Watching let go while the screen is out of sight. */
    private boolean held;
    /** The room's last offer, while the engine is not there yet. */
    @Nullable private Cmd pendingOffer;
    /** The watch connection works. */
    private boolean connected;
    private int losses;
    /** The stream screen's last touch (a watch, an answer, the screen coming back). */
    private long touched;
    private long askingSince = -1;
    // What the engine sees of the picture (the screen's "Düşük Kalite", its size, the first frame).
    private boolean weak;
    private int width;
    private int height;
    private boolean playing;
    private int noticeSeq;
    @Nullable private String notice;
    /** "Hâlâ izliyor musun?" after this long (a debug build's emulator pass may shorten it). */
    private long stillAskMs = STILL_ASK_MS;

    VoiceWatch(Host host) {
        this.host = host;
    }

    void stillAskMs(long ms) {
        stillAskMs = ms > 0 ? ms : STILL_ASK_MS;
    }

    boolean active() {
        return !watching.isEmpty();
    }

    /** Watching now and not let go: the stream's sound plays (the duck needs who speaks). */
    boolean live() {
        return !watching.isEmpty() && !held;
    }

    @Nullable
    String watchedPerson() {
        return watching.isEmpty() ? null : watching.get(0).u;
    }

    int watchedN() {
        return watching.isEmpty() ? 0 : watching.get(0).n;
    }

    boolean owns(int rid) {
        return pending.containsKey(rid);
    }

    // ---- the player's choices ----

    /**
     * "İzle": whether it is watched now. A share the room does not list says {@code gone} to the screen. Another share
     * watched lets go first (one at a time on the phone).
     */
    boolean watch(String u, int n, String layer, long now, List<VoiceMachine.Effect> out) {
        if (u.equals(host.me()) || !listed(u, n)) {
            notice("gone", out);
            return false;
        }
        for (Watching w : watching) {
            if (w.is(u, n)) {
                setLayer(u, n, layer, out);
                still(now, out);
                return true;
            }
        }
        for (Watching other : new ArrayList<>(watching)) dropWatch(other.u, other.n, true, out);
        Watching w = new Watching(u, n, StreamView.layer(layer));
        watching.add(w);
        losses = 0;
        resetFacts();
        touched = now;
        askingSince = -1;
        if (!visible && hiddenSince < 0 && !held) hiddenSince = now;
        if (mayAsk()) sendWatch(w, now, out);
        out.add(changed());
        return true;
    }

    /** "İzlemeyi Bırak" (the stream screen closed). */
    void unwatch(String u, int n, List<VoiceMachine.Effect> out) {
        for (Watching w : watching) {
            if (w.is(u, n)) {
                dropWatch(u, n, true, out);
                return;
            }
        }
    }

    /** Another layer for the watched share (the quality choice, the view's size, mobile data, a weak connection). */
    void setLayer(String u, int n, String layer, List<VoiceMachine.Effect> out) {
        String wanted = StreamView.layer(layer);
        for (Watching w : watching) {
            if (!w.is(u, n) || w.layer.equals(wanted)) continue;
            w.layer = wanted;
            Long generation = host.openGeneration();
            if (w.sent && !held && generation != null) out.add(send(generation, VoiceWire.layer(u, n, wanted)));
            out.add(changed());
        }
    }

    /** "Evet" on "Hâlâ izliyor musun?", and any touch of the stream screen. */
    void still(long now, List<VoiceMachine.Effect> out) {
        touched = Math.max(touched, now);
        if (askingSince >= 0) {
            askingSince = -1;
            out.add(changed());
        }
    }

    /** The stream screen went out of sight (the screen off, the app away without picture-in-picture) or came back. */
    void visible(boolean on, long now, List<VoiceMachine.Effect> out) {
        if (visible == on) return;
        visible = on;
        if (on) {
            hiddenSince = -1;
            if (held) {
                held = false;
                touched = now;
                askingSince = -1;
                rewatch(now, out);
                out.add(changed());
            }
        } else if (!watching.isEmpty() && !held) {
            hiddenSince = now;
        }
    }

    // ---- the engine ----

    /** The watch engine came (the voice engine opened) or went. */
    void engine(boolean there, long now, List<VoiceMachine.Effect> out) {
        if (engine == there) return;
        engine = there;
        if (!there) {
            connected = false;
            return;
        }
        if (pendingOffer != null) {
            Cmd offer = pendingOffer;
            pendingOffer = null;
            out.add(watchEffect(offer));
            return;
        }
        // A new engine has no connection: the session starts anew there.
        if (!watching.isEmpty() && !held) {
            restart(now, out);
            out.add(changed());
        }
    }

    /** The watch engine's report. */
    void report(Report report, @Nullable String sdp, long now, List<VoiceMachine.Effect> out) {
        switch (report) {
            case ANSWER: {
                Long generation = host.openGeneration();
                if (generation != null && sdp != null && sdp.length() <= VoiceWire.MAX_FRAME_CHARS) {
                    out.add(send(generation, VoiceWire.wanswer(sdp)));
                }
                break;
            }
            case NEED_TURN: {
                Long generation = host.openGeneration();
                if (generation == null || watching.isEmpty()) {
                    lost(now, out);
                    break;
                }
                int rid = host.rid();
                pending.put(rid, new Asked(null, 0));
                out.add(send(generation, VoiceWire.turn(rid)));
                break;
            }
            case CONNECTED:
                if (!connected) {
                    connected = true;
                    losses = 0;
                    out.add(changed());
                }
                break;
            case LOST:
                lost(now, out);
                break;
            default:
                break;
        }
    }

    /** What the engine sees of the picture now; whether the screen should hear it. */
    void facts(boolean weak, int width, int height, boolean playing, List<VoiceMachine.Effect> out) {
        if (watching.isEmpty()) return;
        if (this.weak == weak && this.width == width && this.height == height && this.playing == playing) return;
        this.weak = weak;
        this.width = width;
        this.height = height;
        this.playing = playing;
        out.add(changed());
    }

    // ---- the room ----

    /**
     * {@code hello}: the room's watch session still carries {@code kept} (its {@code me.watch}). Those go on as they are;
     * the rest are asked for again; with none of them kept the session is new (and the engine's connection too). What
     * the room carries that the phone does not watch (a watch left from the desktop, an {@code unwatch} lost meanwhile,
     * all of it while held) is let go there.
     */
    void hello(Map<String, List<Integer>> kept, long now, List<VoiceMachine.Effect> out) {
        prune(false, now, out);
        Long generation = host.openGeneration();
        if (generation != null) {
            for (Map.Entry<String, List<Integer>> e : kept.entrySet()) {
                for (int n : e.getValue()) {
                    // Kept there but not as this phone left it (let go meanwhile, its unwatch lost with the socket, or
                    // its offer lost on the way): the room lets it go; a share still wanted is asked for anew below.
                    Watching w = find(e.getKey(), n);
                    if (held || w == null || !carried(kept, w)) {
                        out.add(send(generation, VoiceWire.unwatch(e.getKey(), n)));
                        if (w != null) w.sent = false;
                    }
                }
            }
        }
        if (watching.isEmpty() || held) return;
        boolean anyCarried = false;
        for (Watching w : watching) anyCarried |= carried(kept, w);
        if (!anyCarried) {
            restart(now, out);
            return;
        }
        clearPending();
        for (Watching w : watching) {
            if (carried(kept, w)) {
                w.rid = null;
                w.askedAt = -1;
            } else {
                w.sent = false;
                sendWatch(w, now, out);
            }
        }
    }

    /** Someone's shares changed or they left: watches of shares the room no longer lists go (a sound for the player). */
    void sharesChanged(long now, List<VoiceMachine.Effect> out) {
        prune(true, now, out);
    }

    /** The room's {@code woffer}. */
    void offer(VoiceWire.Message message, List<VoiceMachine.Effect> out) {
        // Nothing asked for now (an offer that crossed an unwatch, or watching let go).
        if (watching.isEmpty() || held || message.sdp == null) return;
        for (Watching w : watching) {
            for (VoiceWire.WatchMid mid : message.watchMids.values()) {
                if (w.is(mid.u, mid.n)) {
                    w.rid = null;
                    w.askedAt = -1;
                }
            }
        }
        deliver(Cmd.offer(message.sdp, message.watchMids, message.fresh), out);
        out.add(changed());
    }

    /** The room's {@code ack} to one of watching's requests. */
    void ack(int rid, @Nullable VoiceWire.Ice ice, long now, List<VoiceMachine.Effect> out) {
        Asked asked = pending.remove(rid);
        if (asked == null || !asked.turn()) return; // A watch's offer follows its ack.
        if (ice != null && !ice.urls.isEmpty()) deliver(Cmd.ice(ice), out);
        else lost(now, out);
    }

    /** The room's {@code error} to one of watching's requests ({@code watch_full}, {@code budget}, {@code share_off}…). */
    void error(int rid, @Nullable String code, long now, List<VoiceMachine.Effect> out) {
        Asked asked = pending.remove(rid);
        if (asked == null) return;
        if (asked.turn()) {
            lost(now, out);
            return;
        }
        dropWatch(asked.u, asked.n, false, out);
        notice(code == null || code.isEmpty() ? NOTICE_FAILED : code, out);
    }

    /** The socket went away: a TURN answer can only come on the socket that asked (the next hello settles watches). */
    void socketAway() {
        for (Iterator<Map.Entry<Integer, Asked>> it = pending.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue().turn()) it.remove();
        }
    }

    /** The stay ended: nothing is watched, the engine closes its connection. */
    void end(List<VoiceMachine.Effect> out) {
        if (!watching.isEmpty() || connected || pendingOffer != null) out.add(watchEffect(Cmd.reset(false)));
        boolean had = !watching.isEmpty();
        watching.clear();
        pending.clear();
        pendingOffer = null;
        connected = false;
        held = false;
        hiddenSince = -1;
        askingSince = -1;
        losses = 0;
        resetFacts();
        if (had) out.add(changed());
    }

    // ---- time ----

    /** The moments watching wants a tick at (the earliest, or -1). */
    long deadline() {
        long next = -1;
        if (hiddenSince >= 0) next = earliest(next, hiddenSince + HIDDEN_GRACE_MS);
        for (Watching w : watching) if (w.askedAt >= 0) next = earliest(next, w.askedAt + OFFER_WAIT_MS);
        if (!watching.isEmpty() && !held) {
            next = earliest(next, askingSince >= 0 ? askingSince + STILL_ANSWER_MS : touched + stillAskMs);
        }
        return next;
    }

    void tick(long now, List<VoiceMachine.Effect> out) {
        // A watch with no offer in time: as a lost connection (asked again, a few times).
        for (Watching w : watching) {
            if (w.askedAt >= 0 && now >= w.askedAt + OFFER_WAIT_MS) {
                lost(now, out);
                break;
            }
        }
        // Out of sight long enough: let go.
        if (hiddenSince >= 0 && now >= hiddenSince + HIDDEN_GRACE_MS) {
            hiddenSince = -1;
            if (!watching.isEmpty()) {
                held = true;
                askingSince = -1;
                unwatchRoom(out);
                resetEngine(false, out);
                out.add(changed());
            }
        }
        // "Hâlâ izliyor musun?".
        if (!watching.isEmpty() && !held) {
            if (askingSince >= 0) {
                if (now >= askingSince + STILL_ANSWER_MS) stopWatching(NOTICE_STOPPED, out);
            } else if (now >= touched + stillAskMs) {
                askingSince = now;
                out.add(changed());
            }
        }
    }

    // ---- the screen's view ----

    /** The watch as the screens show it (null: nothing watched); {@code volume}, {@code muted}: the streamer's sound. */
    @Nullable
    VoiceState.Watch view(int volume, boolean muted) {
        if (watching.isEmpty()) return null;
        Watching w = watching.get(0);
        VoiceState.Watch v = new VoiceState.Watch();
        v.u = w.u;
        v.n = w.n;
        v.layer = w.layer;
        v.phase = held ? VoiceState.Watch.HELD
                : connected && w.sent && w.rid == null ? VoiceState.Watch.LIVE : VoiceState.Watch.CONNECTING;
        v.asking = askingSince >= 0;
        v.weak = weak;
        v.width = width;
        v.height = height;
        v.playing = playing && !held;
        v.volume = volume;
        v.muted = muted;
        for (VoiceWire.Share s : host.shares(w.u)) {
            if (s.n != w.n) continue;
            v.src = s.src;
            v.watchers = s.w;
            v.st = s.st;
        }
        return v;
    }

    int noticeSeq() {
        return noticeSeq;
    }

    @Nullable
    String notice() {
        return notice;
    }

    // ---- inside ----

    private boolean listed(String u, int n) {
        for (VoiceWire.Share s : host.shares(u)) if (s.n == n) return true;
        return false;
    }

    @Nullable
    private Watching find(String u, int n) {
        for (Watching w : watching) if (w.is(u, n)) return w;
        return null;
    }

    /** The room's new socket still carries {@code w} as this phone left it: asked for there and its offer came. */
    private static boolean carried(Map<String, List<Integer>> kept, Watching w) {
        List<Integer> ns = kept.get(w.u);
        return w.sent && w.rid == null && ns != null && ns.contains(w.n);
    }

    /** Watching may ask the room now: the engine is there and the screen is not let go. */
    private boolean mayAsk() {
        return !held && engine;
    }

    private void sendWatch(Watching w, long now, List<VoiceMachine.Effect> out) {
        Long generation = host.openGeneration();
        if (generation == null) return;
        int rid = host.rid();
        pending.put(rid, new Asked(w.u, w.n));
        w.sent = true;
        w.rid = rid;
        w.askedAt = now;
        out.add(send(generation, VoiceWire.watch(w.u, w.n, w.layer, rid)));
    }

    /** A share leaves the list: {@code tell} sends {@code unwatch} (the room closed it already otherwise). */
    private void dropWatch(String u, int n, boolean tell, List<VoiceMachine.Effect> out) {
        Watching gone = null;
        for (Iterator<Watching> it = watching.iterator(); it.hasNext(); ) {
            Watching w = it.next();
            if (w.is(u, n)) {
                gone = w;
                it.remove();
                break;
            }
        }
        if (gone == null) return;
        Long generation = host.openGeneration();
        if (tell && gone.sent && generation != null) out.add(send(generation, VoiceWire.unwatch(u, n)));
        for (Iterator<Asked> it = pending.values().iterator(); it.hasNext(); ) {
            Asked a = it.next();
            if (!a.turn() && u.equals(a.u) && a.n == n) it.remove();
        }
        if (watching.isEmpty()) {
            resetEngine(false, out);
            askingSince = -1;
            hiddenSince = -1;
            held = false;
            resetFacts();
        }
        out.add(changed());
    }

    /** The stream screen is gone for good (its process ended): nothing more is watched, nothing to say. */
    void quit(List<VoiceMachine.Effect> out) {
        stopWatching(null, out);
    }

    /** Nothing more is watched ({@code why}: the screen's word for it, or none). */
    private void stopWatching(@Nullable String why, List<VoiceMachine.Effect> out) {
        if (watching.isEmpty()) return;
        unwatchRoom(out);
        watching.clear();
        resetEngine(false, out);
        askingSince = -1;
        hiddenSince = -1;
        held = false;
        resetFacts();
        if (why != null) notice(why, out);
        out.add(changed());
    }

    /** Lets the room's watch session go (every share it carries); the list stays. */
    private void unwatchRoom(List<VoiceMachine.Effect> out) {
        Long generation = host.openGeneration();
        for (Watching w : watching) {
            if (w.sent && generation != null) out.add(send(generation, VoiceWire.unwatch(w.u, w.n)));
            w.sent = false;
            w.rid = null;
            w.askedAt = -1;
        }
        clearPending();
    }

    private void clearPending() {
        pending.clear();
    }

    /** Asks again for every share on the list (a new session). */
    private void rewatch(long now, List<VoiceMachine.Effect> out) {
        if (!mayAsk()) return;
        for (Watching w : watching) if (!w.sent) sendWatch(w, now, out);
    }

    /** The session anew: the room lets the old go, the engine closes its connection, the list is asked for again. */
    private void restart(long now, List<VoiceMachine.Effect> out) {
        unwatchRoom(out);
        resetEngine(true, out);
        rewatch(now, out);
    }

    /** The engine closes its connection ({@code keepTurn}: the session is asked anew, TURN stays). */
    private void resetEngine(boolean keepTurn, List<VoiceMachine.Effect> out) {
        pendingOffer = null;
        connected = false;
        playing = false;
        if (engine) out.add(watchEffect(Cmd.reset(keepTurn)));
    }

    /** To the engine, or kept until it is there. */
    private void deliver(Cmd cmd, List<VoiceMachine.Effect> out) {
        if (engine) out.add(watchEffect(cmd));
        else pendingOffer = cmd;
    }

    /** The watch connection is gone: asked for anew, a few times in a row at most. */
    private void lost(long now, List<VoiceMachine.Effect> out) {
        if (watching.isEmpty()) return;
        connected = false;
        losses++;
        if (losses >= LOSSES_MAX) {
            stopWatching(NOTICE_FAILED, out);
        } else {
            restart(now, out);
            out.add(changed());
        }
    }

    /** Watches of shares the room no longer lists go (the room closed them already). */
    private void prune(boolean sound, long now, List<VoiceMachine.Effect> out) {
        List<Watching> gone = new ArrayList<>();
        for (Watching w : watching) if (!listed(w.u, w.n)) gone.add(w);
        for (Watching w : gone) dropWatch(w.u, w.n, false, out);
        if (!gone.isEmpty()) {
            notice(NOTICE_ENDED, out);
            if (sound) host.cue(VoiceMachine.Sound.STREAM_END, out);
        }
    }

    private void notice(String code, List<VoiceMachine.Effect> out) {
        noticeSeq++;
        notice = code;
        out.add(changed());
    }

    private void resetFacts() {
        weak = false;
        width = 0;
        height = 0;
        playing = false;
    }

    /** The ICE ufrag an SDP carries (a new watch session has a new one; the desktop's watch.ts {@code ufragOf}). */
    @Nullable
    static String ufragOf(@Nullable String sdp) {
        if (sdp == null) return null;
        int at = sdp.indexOf("a=ice-ufrag:");
        if (at < 0) return null;
        int start = at + "a=ice-ufrag:".length();
        int end = start;
        while (end < sdp.length() && !Character.isWhitespace(sdp.charAt(end))) end++;
        return end > start ? sdp.substring(start, end) : null;
    }

    private static long earliest(long next, long at) {
        return next < 0 ? at : Math.min(next, at);
    }

    private static VoiceMachine.Effect send(long generation, String frame) {
        VoiceMachine.Effect e = new VoiceMachine.Effect(VoiceMachine.Effect.Kind.SEND);
        e.generation = generation;
        e.frame = frame;
        return e;
    }

    private static VoiceMachine.Effect watchEffect(Cmd cmd) {
        VoiceMachine.Effect e = new VoiceMachine.Effect(VoiceMachine.Effect.Kind.WATCH);
        e.watch = cmd;
        return e;
    }

    private static VoiceMachine.Effect changed() {
        return new VoiceMachine.Effect(VoiceMachine.Effect.Kind.CHANGED);
    }
}
