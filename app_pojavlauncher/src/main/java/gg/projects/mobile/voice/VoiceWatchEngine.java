package gg.projects.mobile.voice;

import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import org.webrtc.AudioTrack;
import org.webrtc.EglBase;
import org.webrtc.GlRectDrawer;
import org.webrtc.HardwareVideoDecoderFactory;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.PlatformSoftwareVideoDecoderFactory;
import org.webrtc.RTCStats;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpTransceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SoftwareVideoDecoderFactory;
import org.webrtc.VideoCodecInfo;
import org.webrtc.VideoDecoder;
import org.webrtc.VideoDecoderFactory;
import org.webrtc.VideoDecoderFallback;
import org.webrtc.VideoFrame;
import org.webrtc.VideoFrameDrawer;
import org.webrtc.VideoSink;
import org.webrtc.VideoTrack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * MOB-WATCH: the watch connection in the voice process (the desktop's {@code share/watch.ts}): one receive-only peer
 * connection per watch session on the voice engine's own factory, so a stream's sound plays on voice chat's path (the
 * echo canceller hears it too: watching on the loudspeaker sends no stream back into the room). The room's offers come
 * from {@link VoiceWatch} (a new connection when the session is new, the same one when it grew or shrank), the answer goes
 * back as a report. Hardware decoders draw into textures; {@link Renderer} draws the newest frame into the stream screen's
 * surface (another process's: Binder carries it), and nothing at all while that screen is not there.
 *
 * <p>Nothing runs without a watch: no connection, no timer, no thread. Its only timers are the connection's own waits
 * (ICE 5 s, a dropped connection 3 s), the quality look every 2 s while a picture shows ({@code getStats} on this phone,
 * for "Düşük Kalite"), and the sound's ramp while it moves ({@link StreamSound.Ramp}).
 *
 * <p>Every method runs on the main thread; WebRTC's callbacks are posted to it.
 */
final class VoiceWatchEngine {
    private static final String TAG = "ProjectsWatch";
    private static final String STUN = "stun:stun.cloudflare.com:3478";
    /** ICE that has not connected by then asks for TURN (the voice engine's rule). */
    static final long ICE_WAIT_MS = 5_000;
    /** A connection that dropped and did not come back by then is lost. */
    static final long DROP_WAIT_MS = 3_000;
    /** How often a showing share's quality is looked at. */
    static final long STATS_MS = 2_000;
    /** A loss above this share of packets reads as weak (as a frozen picture does). */
    static final double LOSS_WEAK = 0.05;

    interface Listener {
        void onWatchReport(VoiceWatch.Report report, @Nullable String sdp);

        /** What arrives of the picture changed: weak, its size, frames on their way. */
        void onWatchFacts(boolean weak, int width, int height, boolean playing);
    }

    private final VoiceEngine mVoice;
    private final Listener mListener;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    @Nullable private Watch mPc;
    private final List<PeerConnection.IceServer> mTurn = new ArrayList<>();
    private boolean mAskedTurn;
    /** The offers, one after another (the room may send two quickly). */
    private final ArrayDeque<VoiceWatch.Cmd> mOffers = new ArrayDeque<>();
    /** The connection whose negotiation runs now (null: none). */
    @Nullable private Watch mTakingFor;
    /** Let go by the service: nothing more is reported. */
    private boolean mReleased;
    /** Each quality look belongs to one run of looks (a look of a stopped run changes nothing and asks no next one). */
    private int mStatsGeneration;
    /** Each mid of the session: whose share it carries. */
    private final Map<String, VoiceWire.WatchMid> mMids = new HashMap<>();
    /** The share shown now (one at a time on the phone). */
    @Nullable private String mTargetU;
    private int mTargetN;
    @Nullable private VideoTrack mVideo;
    @Nullable private String mVideoMid;
    @Nullable private AudioTrack mAudio;
    @Nullable private String mAudioMid;
    private final Renderer mRenderer;
    private final StreamSound.Ramp mRamp = new StreamSound.Ramp(0);
    private double mLevel;
    private double mDuck = 1;
    private boolean mStatsOn;
    private boolean mWeak;
    private long mLost = -1;
    private long mReceived = -1;
    private long mFreezes = -1;
    private int mWidth;
    private int mHeight;
    private boolean mPlaying;

    VoiceWatchEngine(VoiceEngine voice, Listener listener) {
        mVoice = voice;
        mListener = listener;
        mRenderer = new Renderer(this::onPicture);
    }

    // ---- the machine's commands ----

    @MainThread
    void run(VoiceWatch.Cmd cmd) {
        switch (cmd.kind) {
            case OFFER:
                mOffers.add(cmd);
                takeNext();
                break;
            case ICE:
                mTurn.clear();
                if (cmd.ice != null) {
                    mTurn.add(PeerConnection.IceServer.builder(cmd.ice.urls).setUsername(cmd.ice.username)
                            .setPassword(cmd.ice.credential).createIceServer());
                }
                // The next connection goes through TURN: this one ends and the machine asks anew (a connection that came
                // up meanwhile stays; TURN waits for the next one).
                if (mPc == null || mPc.done || !mPc.connected()) lost();
                break;
            case RESET:
                // Nothing watched: TURN credentials expire, the next session asks again if it must. A session asked anew
                // after a loss keeps them (that is what they were asked for).
                if (!cmd.keepTurn) mTurn.clear();
                close();
                break;
            default:
                break;
        }
    }

    /** The share the screen shows (null: none); others the session still carries stay dark and silent. */
    @MainThread
    void setTarget(@Nullable String u, int n) {
        if (eq(u, mTargetU) && n == mTargetN) return;
        mTargetU = u;
        mTargetN = n;
        pick();
    }

    /** The stream screen's surface (null: it went); the picture is drawn there and nowhere else. */
    @MainThread
    void setSurface(@Nullable Surface surface) {
        mRenderer.setSurface(surface);
        syncStats();
        if (surface == null) setPlaying(false);
    }

    /** The stream's gain ({@link VoiceMachine#streamGain}) and the duck ({@link StreamSound#duck}) now. */
    @MainThread
    void setSound(double level, double duck) {
        boolean jump = mLevel == 0 && level > 0 && mDuck == duck;
        mLevel = level;
        mDuck = duck;
        long now = SystemClock.elapsedRealtime();
        mRamp.set(level, duck, now);
        // A stream that starts (or comes back from silence) starts at its level: no glide up from nothing.
        if (jump) mRamp.settle(now);
        applyGain(mRamp.advance(now));
        mMain.removeCallbacks(mStep);
        if (mRamp.moving()) mMain.postDelayed(mStep, StreamSound.STEP_MS);
    }

    @MainThread
    void close() {
        closePc(true);
        mMain.removeCallbacks(mStep);
    }

    /** The engine is done (the voice engine closes): the render thread and its EGL go too; nothing more is reported. */
    @MainThread
    void release() {
        mReleased = true;
        close();
        mRenderer.release();
    }

    // ---- the sound ----

    private final Runnable mStep = new Runnable() {
        @Override
        public void run() {
            applyGain(mRamp.advance(SystemClock.elapsedRealtime()));
            if (mRamp.moving()) mMain.postDelayed(this, StreamSound.STEP_MS);
        }
    };

    private void applyGain(double gain) {
        AudioTrack audio = mAudio;
        if (audio == null) return;
        try {
            audio.setVolume(Math.max(0, gain));
        } catch (IllegalStateException e) {
            // A track WebRTC disposed meanwhile.
        }
    }

    // ---- the connection ----

    private void takeNext() {
        // One negotiation at a time on the connection that runs it (a closed one's late answer frees nothing).
        if (mTakingFor != null && mTakingFor == mPc && !mTakingFor.done) return;
        mTakingFor = null;
        VoiceWatch.Cmd cmd = mOffers.poll();
        if (cmd == null || cmd.sdp == null) return;
        PeerConnectionFactory factory = mVoice.factory();
        if (factory == null) {
            mOffers.clear();
            return;
        }
        String offered = VoiceWatch.ufragOf(cmd.sdp);
        Watch pc = mPc;
        boolean same = !cmd.fresh && pc != null && !pc.done && offered != null && offered.equals(pc.ufrag);
        if (!same) pc = open(factory);
        if (pc == null) {
            report(VoiceWatch.Report.LOST, null);
            return;
        }
        pc.ufrag = offered;
        // An offer names the m-lines it adds; the session's earlier ones stay.
        mMids.putAll(cmd.mids);
        mTakingFor = pc;
        final Watch taking = pc;
        pc.pc.setRemoteDescription(new Sdp() {
            @Override
            public void onSetSuccess() {
                taking.pc.createAnswer(new Sdp() {
                    @Override
                    public void onCreateSuccess(SessionDescription answer) {
                        // A stream's sound in stereo (the receiver's own description says it; voice stays mono).
                        SessionDescription stereo = new SessionDescription(answer.type, VoiceSdp.stereoOpus(answer.description));
                        taking.pc.setLocalDescription(new Sdp() {
                            @Override
                            public void onSetSuccess() {
                                mMain.post(() -> {
                                    if (mTakingFor == taking) mTakingFor = null;
                                    if (taking.done || mPc != taking) {
                                        takeNext();
                                        return;
                                    }
                                    SessionDescription local = taking.pc.getLocalDescription();
                                    if (local != null) report(VoiceWatch.Report.ANSWER, local.description);
                                    pick();
                                    takeNext();
                                });
                            }

                            @Override
                            public void onSetFailure(String error) {
                                super.onSetFailure(error);
                                failed(taking);
                            }
                        }, stereo);
                    }

                    @Override
                    public void onCreateFailure(String error) {
                        super.onCreateFailure(error);
                        failed(taking);
                    }
                }, new MediaConstraints());
            }

            @Override
            public void onSetFailure(String error) {
                super.onSetFailure(error);
                failed(taking);
            }
        }, new SessionDescription(SessionDescription.Type.OFFER, cmd.sdp));
    }

    /** A description this connection could not take: as lost (the machine asks anew); a closed one's failure changes nothing. */
    private void failed(Watch which) {
        mMain.post(() -> {
            if (mTakingFor == which) mTakingFor = null;
            if (mPc == which && !which.done) lost();
            else takeNext();
        });
    }

    /** To the machine, unless the engine is being let go (the service is closing it). */
    private void report(VoiceWatch.Report report, @Nullable String sdp) {
        if (!mReleased) mListener.onWatchReport(report, sdp);
    }

    private void facts() {
        if (!mReleased) mListener.onWatchFacts(mWeak, mWidth, mHeight, mPlaying);
    }

    @Nullable
    private Watch open(PeerConnectionFactory factory) {
        // The offers queued behind this one belong to the new session: they stay.
        closePc(false);
        List<PeerConnection.IceServer> servers = new ArrayList<>();
        servers.add(PeerConnection.IceServer.builder(STUN).createIceServer());
        servers.addAll(mTurn);
        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(servers);
        config.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
        config.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE;
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        config.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE;
        Watch watch = new Watch(!mTurn.isEmpty());
        PeerConnection pc = factory.createPeerConnection(config, watch);
        if (pc == null) return null;
        watch.pc = pc;
        mPc = watch;
        mMain.postDelayed(watch.iceTimeout, ICE_WAIT_MS);
        return watch;
    }

    /** The connection goes ({@code dropOffers}: the offers waiting behind it too). */
    private void closePc(boolean dropOffers) {
        Watch pc = mPc;
        mPc = null;
        mTakingFor = null;
        if (dropOffers) mOffers.clear();
        mMids.clear();
        // The renderer leaves the picture's track before the connection's dispose lets its wrapper go.
        detachVideo();
        mAudio = null;
        mAudioMid = null;
        mAskedTurn = false;
        mStatsOn = false;
        mStatsGeneration++;
        mMain.removeCallbacks(mStats);
        mLost = -1;
        mReceived = -1;
        mFreezes = -1;
        mWeak = false;
        if (pc != null) pc.close();
        setPlaying(false);
    }

    private void lost() {
        closePc(true);
        report(VoiceWatch.Report.LOST, null);
    }

    /**
     * The target share's tracks, of all the session carries: its picture to the renderer, its sound at its gain. Only
     * the tracks {@code onTrack} handed over are used: {@code getTransceivers()} would dispose every wrapper handed out
     * before it (and a disposed video track drops its renderer).
     */
    private void pick() {
        Watch pc = mPc;
        VideoTrack video = null;
        String videoMid = null;
        AudioTrack audio = null;
        String audioMid = null;
        if (pc != null && !pc.done && mTargetU != null) {
            for (Map.Entry<String, MediaStreamTrack> e : pc.tracks.entrySet()) {
                VoiceWire.WatchMid entry = mMids.get(e.getKey());
                if (entry == null || !entry.u.equals(mTargetU) || entry.n != mTargetN) continue;
                MediaStreamTrack track = e.getValue();
                if (entry.video && track instanceof VideoTrack) {
                    video = (VideoTrack) track;
                    videoMid = e.getKey();
                } else if (!entry.video && track instanceof AudioTrack) {
                    audio = (AudioTrack) track;
                    audioMid = e.getKey();
                }
            }
            // Others' sound stays silent (a share the phone no longer shows, until the room closes it).
            for (MediaStreamTrack track : pc.tracks.values()) {
                if (track instanceof AudioTrack && track != audio) {
                    try {
                        ((AudioTrack) track).setVolume(0);
                    } catch (IllegalStateException ignored) {
                        // Disposed meanwhile.
                    }
                }
            }
        }
        if (video != mVideo || !eq(videoMid, mVideoMid)) {
            detachVideo();
            if (video != null) {
                try {
                    video.addSink(mRenderer);
                    mVideo = video;
                    mVideoMid = videoMid;
                } catch (IllegalStateException e) {
                    mVideo = null;
                }
            }
        }
        if (audio != mAudio) {
            mAudio = audio;
            mAudioMid = audioMid;
            applyGain(mRamp.gain());
        }
        syncStats();
    }

    private void detachVideo() {
        VideoTrack video = mVideo;
        mVideo = null;
        mVideoMid = null;
        if (video == null) return;
        try {
            video.removeSink(mRenderer);
        } catch (IllegalStateException ignored) {
            // Disposed with its connection.
        }
        mRenderer.clear();
    }

    // ---- what arrives ----

    private void onPicture(int width, int height) {
        mMain.post(() -> {
            if (mPc == null || mVideo == null) return;
            boolean changed = width != mWidth || height != mHeight || !mPlaying;
            mWidth = width;
            mHeight = height;
            mPlaying = true;
            if (changed) facts();
        });
    }

    private void setPlaying(boolean playing) {
        if (mPlaying == playing && (playing || (mWidth == 0 && mHeight == 0))) return;
        mPlaying = playing;
        if (!playing) {
            mWidth = 0;
            mHeight = 0;
        }
        facts();
    }

    /** The quality look runs only while a picture shows. */
    private void syncStats() {
        boolean wanted = mPc != null && mRenderer.hasSurface() && mVideo != null;
        if (wanted == mStatsOn) return;
        mStatsOn = wanted;
        mStatsGeneration++;
        mMain.removeCallbacks(mStats);
        if (wanted) mMain.postDelayed(mStats, STATS_MS);
    }

    private final Runnable mStats = new Runnable() {
        @Override
        public void run() {
            final Watch pc = mPc;
            if (pc == null || pc.done || !mStatsOn) return;
            final int generation = mStatsGeneration;
            pc.pc.getStats(report -> {
                // WebRTC's thread: each incoming picture's counters by mid; the main thread picks the shown one.
                final Map<String, long[]> byMid = new HashMap<>();
                for (RTCStats stat : report.getStatsMap().values()) {
                    if (!"inbound-rtp".equals(stat.getType())) continue;
                    Map<String, Object> m = stat.getMembers();
                    Object mid = str(m.get("mid"));
                    if (!"video".equals(m.get("kind")) || mid == null) continue;
                    byMid.put((String) mid, new long[] {num(m.get("packetsLost")), num(m.get("packetsReceived")),
                            num(m.get("freezeCount"))});
                }
                mMain.post(() -> {
                    if (mPc != pc || !mStatsOn || generation != mStatsGeneration) return;
                    long[] shown = mVideoMid == null ? null : byMid.get(mVideoMid);
                    if (shown != null) look(shown[0], shown[1], shown[2]);
                    mMain.postDelayed(mStats, STATS_MS);
                });
            });
        }
    };

    /** One look: weak when the picture froze or lost more than a twentieth of its packets since the last one. */
    private void look(long lost, long received, long freezes) {
        if (received < 0) return;
        boolean weak = false;
        if (mReceived >= 0) {
            long lostDelta = Math.max(0, lost - mLost);
            long receivedDelta = Math.max(0, received - mReceived);
            double loss = lostDelta + receivedDelta > 0 ? (double) lostDelta / (lostDelta + receivedDelta) : 0;
            // Few frames are no sign (a still window of text sends few); a picture that froze or lost packets is.
            weak = (freezes >= 0 && mFreezes >= 0 && freezes > mFreezes) || loss > LOSS_WEAK;
        }
        mLost = Math.max(0, lost);
        mReceived = received;
        mFreezes = freezes;
        if (weak != mWeak) {
            mWeak = weak;
            facts();
        }
    }

    @Nullable
    private static Object str(@Nullable Object o) {
        return o instanceof String ? o : null;
    }

    private static long num(@Nullable Object o) {
        return o instanceof Number ? ((Number) o).longValue() : -1;
    }

    private static boolean eq(@Nullable Object a, @Nullable Object b) {
        return a == null ? b == null : a.equals(b);
    }

    // ---- one watch connection ----

    private final class Watch implements PeerConnection.Observer {
        PeerConnection pc;
        @Nullable String ufrag;
        final boolean withTurn;
        boolean connectedOnce;
        boolean dropping;
        boolean done;
        /** The transceivers {@code onTrack} handed over, by mid, and their tracks (main thread only). */
        final Map<String, RtpTransceiver> transceivers = new HashMap<>();
        final Map<String, MediaStreamTrack> tracks = new HashMap<>();
        /** Each receiver's id, by mid ({@code onRemoveTrack} names a receiver). */
        final Map<String, String> receivers = new HashMap<>();
        final Runnable iceTimeout = new Runnable() {
            @Override
            public void run() {
                if (done || connected() || mPc != Watch.this) return;
                // Not connected in 5 s: TURN once, then lost.
                if (!mAskedTurn && !withTurn) {
                    mAskedTurn = true;
                    report(VoiceWatch.Report.NEED_TURN, null);
                } else {
                    lost();
                }
            }
        };
        final Runnable dropTimeout = new Runnable() {
            @Override
            public void run() {
                dropping = false;
                if (!done && mPc == Watch.this && !connected()) lost();
            }
        };

        Watch(boolean withTurn) {
            this.withTurn = withTurn;
        }

        boolean connected() {
            return pc.connectionState() == PeerConnection.PeerConnectionState.CONNECTED;
        }

        void close() {
            if (done) return;
            done = true;
            mMain.removeCallbacks(iceTimeout);
            mMain.removeCallbacks(dropTimeout);
            // The wrappers onTrack handed over are only dropped: libwebrtc's own observer keeps each one and disposes it
            // with the connection (a second dispose throws inside a native callback and ends the process). The renderer
            // left the picture's track already.
            transceivers.clear();
            tracks.clear();
            receivers.clear();
            pc.dispose();
        }

        /** A track came (main thread): kept by its mid (the wrapper stays libwebrtc's to dispose). */
        void took(String mid, RtpTransceiver t, MediaStreamTrack track, String receiver) {
            if (done || mPc != this) return;
            transceivers.put(mid, t);
            MediaStreamTrack old = tracks.put(mid, track);
            receivers.put(mid, receiver);
            if (old == mVideo && old != track) detachVideo();
            if (old == mAudio && old != track) mAudio = null;
            pick();
        }

        /** A track went (main thread): its mid is forgotten. */
        void lostTrack(String receiver) {
            if (done || mPc != this) return;
            String mid = null;
            for (Map.Entry<String, String> e : receivers.entrySet()) if (e.getValue().equals(receiver)) mid = e.getKey();
            if (mid == null) return;
            MediaStreamTrack track = tracks.remove(mid);
            receivers.remove(mid);
            transceivers.remove(mid);
            if (track == mVideo) detachVideo();
            if (track == mAudio) mAudio = null;
            pick();
        }

        void onState(PeerConnection.PeerConnectionState state) {
            if (done || mPc != this) return;
            switch (state) {
                case CONNECTED:
                    mMain.removeCallbacks(iceTimeout);
                    mMain.removeCallbacks(dropTimeout);
                    dropping = false;
                    if (!connectedOnce) {
                        connectedOnce = true;
                        report(VoiceWatch.Report.CONNECTED, null);
                    }
                    break;
                case DISCONNECTED:
                    if (!dropping) {
                        dropping = true;
                        mMain.postDelayed(dropTimeout, DROP_WAIT_MS);
                    }
                    break;
                case FAILED:
                    lost();
                    break;
                default:
                    break;
            }
        }

        // ---- PeerConnection.Observer (WebRTC's signaling thread) ----

        @Override
        public void onConnectionChange(PeerConnection.PeerConnectionState state) {
            mMain.post(() -> onState(state));
        }

        @Override
        public void onTrack(RtpTransceiver transceiver) {
            // WebRTC's thread: this fresh wrapper is read here and kept (never asked for again: getTransceivers()
            // would dispose it).
            final String mid = transceiver.getMid();
            final RtpReceiver receiver = transceiver.getReceiver();
            final MediaStreamTrack track = receiver == null ? null : receiver.track();
            final String id = receiver == null ? null : receiver.id();
            if (mid == null || track == null || id == null) return;
            mMain.post(() -> took(mid, transceiver, track, id));
        }

        @Override
        public void onRemoveTrack(RtpReceiver receiver) {
            final String id = receiver.id();
            mMain.post(() -> lostTrack(id));
        }

        @Override
        public void onSignalingChange(PeerConnection.SignalingState state) {}

        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState state) {}

        @Override
        public void onIceConnectionReceivingChange(boolean receiving) {}

        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState state) {}

        @Override
        public void onIceCandidate(org.webrtc.IceCandidate candidate) {
            // The SFU is ICE-lite with its candidates in its descriptions: nothing is trickled.
        }

        @Override
        public void onIceCandidatesRemoved(org.webrtc.IceCandidate[] candidates) {}

        @Override
        public void onAddStream(org.webrtc.MediaStream stream) {}

        @Override
        public void onRemoveStream(org.webrtc.MediaStream stream) {}

        @Override
        public void onDataChannel(org.webrtc.DataChannel channel) {}

        @Override
        public void onRenegotiationNeeded() {
            // The room drives every negotiation.
        }
    }

    /** An SdpObserver whose failures are logged by kind only (descriptions never reach a log). */
    private abstract static class Sdp implements SdpObserver {
        @Override
        public void onCreateSuccess(SessionDescription description) {}

        @Override
        public void onSetSuccess() {}

        @Override
        public void onCreateFailure(String error) {
            Log.w(TAG, "a description could not be made");
        }

        @Override
        public void onSetFailure(String error) {
            Log.w(TAG, "a description could not be applied");
        }
    }

    // ---- the decoders: EGL only once something is watched ----

    /**
     * The root EGL context hardware decoders and the renderer share: made with the first watch, released with the voice
     * engine (a player who never watches never has one).
     */
    static final class Egl {
        @Nullable private EglBase mBase;
        private boolean mReleased;

        @Nullable
        synchronized EglBase.Context context() {
            if (mReleased) return null;
            if (mBase == null) {
                try {
                    mBase = EglBase.create();
                } catch (RuntimeException e) {
                    Log.w(TAG, "no EGL context: decoders draw into memory");
                    mReleased = true;
                    return null;
                }
            }
            return mBase.getEglBaseContext();
        }

        synchronized void release() {
            mReleased = true;
            if (mBase != null) {
                mBase.release();
                mBase = null;
            }
        }
    }

    /**
     * The voice factory's video decoders (WebRTC's {@code DefaultVideoDecoderFactory}, with its EGL context asked for
     * only when a decoder is made): hardware first with software behind it, the platform's software codecs where WebRTC
     * has none of its own.
     */
    static final class Decoders implements VideoDecoderFactory {
        private final Egl mEgl;
        private final SoftwareVideoDecoderFactory mSoftware = new SoftwareVideoDecoderFactory();
        @Nullable private VideoDecoderFactory mHardware;
        @Nullable private VideoDecoderFactory mPlatform;
        @Nullable private VideoCodecInfo[] mCodecs;

        Decoders(Egl egl) {
            mEgl = egl;
        }

        @Override
        public synchronized VideoCodecInfo[] getSupportedCodecs() {
            if (mCodecs == null) {
                LinkedHashSet<VideoCodecInfo> all = new LinkedHashSet<>();
                all.addAll(Arrays.asList(mSoftware.getSupportedCodecs()));
                all.addAll(Arrays.asList(new HardwareVideoDecoderFactory(null).getSupportedCodecs()));
                all.addAll(Arrays.asList(new PlatformSoftwareVideoDecoderFactory(null).getSupportedCodecs()));
                mCodecs = all.toArray(new VideoCodecInfo[0]);
            }
            return mCodecs;
        }

        @Nullable
        @Override
        public synchronized VideoDecoder createDecoder(VideoCodecInfo info) {
            if (mHardware == null) {
                EglBase.Context context = mEgl.context();
                mHardware = new HardwareVideoDecoderFactory(context);
                mPlatform = new PlatformSoftwareVideoDecoderFactory(context);
            }
            VideoDecoder software = mSoftware.createDecoder(info);
            VideoDecoder hardware = mHardware.createDecoder(info);
            if (software == null && mPlatform != null) software = mPlatform.createDecoder(info);
            if (hardware != null && software != null) return new VideoDecoderFallback(software, hardware);
            return hardware != null ? hardware : software;
        }
    }

    // ---- the renderer ----

    /** Says the size of each frame drawn (on the render thread). */
    interface PictureListener {
        void onPicture(int width, int height);
    }

    /**
     * Draws the newest frame into the stream screen's surface on a thread of its own (WebRTC's {@code EglRenderer}, with
     * every EGL failure caught: a surface the other process let go must never end the voice process). Frames that come
     * while no surface is there are let go at once; older frames waiting to be drawn are dropped for newer ones.
     */
    final class Renderer implements VideoSink {
        private final PictureListener mPictures;
        private final Object mLock = new Object();
        @Nullable private HandlerThread mThread;
        @Nullable private Handler mHandler;
        @Nullable private EglBase mEgl;
        @Nullable private GlRectDrawer mDrawer;
        @Nullable private VideoFrameDrawer mFrames;
        @Nullable private VideoFrame mPending;
        /** Read on WebRTC's decoder thread: a surface to draw into is there. */
        private volatile boolean mHasSurface;
        private int mLastW;
        private int mLastH;
        /** Grows when the track changes: the next frame drawn says its size again (the picture plays anew). */
        private volatile int mEpoch;
        private int mDrawnEpoch = -1;

        Renderer(PictureListener pictures) {
            mPictures = pictures;
        }

        boolean hasSurface() {
            return mHasSurface;
        }

        @MainThread
        void setSurface(@Nullable final Surface surface) {
            if (surface == null && mHandler == null) return;
            final Handler handler = handler();
            if (handler == null) return;
            mHasSurface = surface != null && surface.isValid();
            handler.post(() -> attachOnThread(surface));
        }

        /** No frame waits any more (the track went); the next track's first frame counts as a new picture. */
        @MainThread
        void clear() {
            mEpoch++;
            synchronized (mLock) {
                if (mPending != null) {
                    mPending.release();
                    mPending = null;
                }
            }
        }

        @MainThread
        void release() {
            mHasSurface = false;
            clear();
            final Handler handler = mHandler;
            final HandlerThread thread = mThread;
            mHandler = null;
            mThread = null;
            if (handler == null || thread == null) return;
            handler.post(() -> {
                releaseOnThread();
                thread.quit();
            });
        }

        @Nullable
        private Handler handler() {
            if (mHandler == null) {
                HandlerThread thread = new HandlerThread("projects-watch-render");
                thread.start();
                mThread = thread;
                mHandler = new Handler(thread.getLooper());
            }
            return mHandler;
        }

        @Override
        public void onFrame(VideoFrame frame) {
            if (!mHasSurface) return;
            final Handler handler = mHandler;
            if (handler == null) return;
            boolean post;
            synchronized (mLock) {
                post = mPending == null;
                if (mPending != null) mPending.release();
                frame.retain();
                mPending = frame;
            }
            if (post) handler.post(this::drawOnThread);
        }

        private void attachOnThread(@Nullable Surface surface) {
            EglBase egl = mEgl;
            if (egl != null && egl.hasSurface()) {
                try {
                    GLES20.glClearColor(0, 0, 0, 1);
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                    egl.detachCurrent();
                } catch (RuntimeException ignored) {
                    // The surface is gone already.
                }
                egl.releaseSurface();
            }
            if (surface == null || !surface.isValid()) {
                mHasSurface = false;
                return;
            }
            try {
                if (egl == null) {
                    EglBase.Context shared = mVoice.watchEgl() == null ? null : mVoice.watchEgl().context();
                    egl = shared == null ? EglBase.create() : EglBase.create(shared, EglBase.CONFIG_PLAIN);
                    mEgl = egl;
                    mDrawer = new GlRectDrawer();
                    mFrames = new VideoFrameDrawer();
                }
                egl.createSurface(surface);
                egl.makeCurrent();
                mLastW = 0;
                mLastH = 0;
                mHasSurface = true;
            } catch (RuntimeException e) {
                Log.w(TAG, "the stream screen's surface could not be used");
                mHasSurface = false;
                if (egl != null && egl.hasSurface()) egl.releaseSurface();
            }
        }

        private void drawOnThread() {
            VideoFrame frame;
            synchronized (mLock) {
                frame = mPending;
                mPending = null;
            }
            if (frame == null) return;
            try {
                EglBase egl = mEgl;
                if (egl == null || !egl.hasSurface() || mFrames == null || mDrawer == null) return;
                int w = frame.getRotatedWidth();
                int h = frame.getRotatedHeight();
                GLES20.glClearColor(0, 0, 0, 1);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                mFrames.drawFrame(frame, mDrawer, null, 0, 0, egl.surfaceWidth(), egl.surfaceHeight());
                egl.swapBuffers();
                int epoch = mEpoch;
                if (w != mLastW || h != mLastH || epoch != mDrawnEpoch) {
                    mLastW = w;
                    mLastH = h;
                    mDrawnEpoch = epoch;
                    mPictures.onPicture(w, h);
                }
            } catch (RuntimeException e) {
                // A surface the stream screen let go meanwhile: nothing more is drawn until a new one comes.
                Log.w(TAG, "a frame could not be drawn");
                mHasSurface = false;
            } finally {
                frame.release();
            }
        }

        private void releaseOnThread() {
            synchronized (mLock) {
                if (mPending != null) {
                    mPending.release();
                    mPending = null;
                }
            }
            try {
                if (mFrames != null) mFrames.release();
                if (mDrawer != null) mDrawer.release();
            } catch (RuntimeException ignored) {
                // The context went with its surface.
            }
            mFrames = null;
            mDrawer = null;
            if (mEgl != null) {
                try {
                    mEgl.release();
                } catch (RuntimeException ignored) {
                    // Nothing more to let go.
                }
                mEgl = null;
            }
        }
    }
}
