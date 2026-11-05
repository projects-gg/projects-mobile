package gg.projects.mobile.voice;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.AudioTrackSink;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RTCStats;
import org.webrtc.RtpParameters;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpSender;
import org.webrtc.RtpTransceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.audio.JavaAudioDeviceModule;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The phone's audio engine for one stay (the desktop's voice page: {@code apps/launcher/src/voice/engine.ts} and
 * {@code call.ts}): Google's libwebrtc for Android ({@code io.github.webrtc-sdk:android}), one peer connection per SFU
 * session with one send-only audio track, the SFU's answer and its later offers (answered here), each person's receiver
 * played at their volume. Always through the SFU, never peer to peer (design §7.1).
 *
 * <p>Capture is the phone's own voice path ({@code VOICE_COMMUNICATION}) with the platform's echo canceller and noise
 * suppressor where the phone has them, and WebRTC's own processing otherwise; Opus mono at Studio's rate with DTX and
 * FEC ({@link VoiceSdp}). Playback goes the media way (the volume keys the game uses, the loudspeaker or headphones,
 * never the earpiece). While the microphone may not send (muted, alone, deafened) recording stops entirely, so the
 * phone's microphone mark goes out and the game's own voice chat may have it.
 *
 * <p>Connection watch (the desktop's {@code call.ts}): not connected 5 s after the first answer: ask for TURN (or, already
 * with TURN, lost); connected, then gone for 3 s or failed: lost. The machine answers both with a new join.
 *
 * <p>Measuring: the quality every 2 s during a stay (local {@code getStats}); who speaks only while a room surface shows
 * ({@link #meters}); the player's own speech for the inactivity rule from the microphone's samples. Nothing else runs.
 * Video receivers are left for the screen share's viewing (SHARE): an offer's video m-lines are answered, not played.
 *
 * <p>Every method runs on the main thread; WebRTC's callbacks are posted to it.
 */
public final class VoiceEngine {
    private static final String TAG = "ProjectsVoice";
    /** Cloudflare's free STUN (design §7.5). */
    private static final String STUN = "stun:stun.cloudflare.com:3478";
    private static final long CONNECT_MS = 5_000;
    private static final long LOST_MS = 3_000;
    private static final long STATS_MS = 2_000;
    private static final long METERS_MS = 120;
    /** A speaker keeps their ring this long after their last loud frame (the desktop's 300 ms tail). */
    private static final long SPEAKING_TAIL_MS = 300;
    /** Frames under this RMS (of full scale) are silence (about -45 dBFS). */
    private static final double SPEAKING_RMS = 0.0056;
    /** "The player spoke" reaches the inactivity rule at most this often. */
    private static final long SPOKE_EVERY_MS = 5_000;

    public interface Listener {
        void onOffer(@Nullable String sdp, @Nullable String mid);

        void onAnswer(String sdp);

        void onConnected();

        void onLost();

        void onNeedTurn();

        void onQuality(int quality);

        void onSpeaking(List<String> ids, boolean me);

        /** The player's microphone sent speech (for the inactivity rule). */
        void onSpoke();

        void onMic(VoiceMachine.Mic mic);
    }

    private final Context mContext;
    private final Listener mListener;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final VoiceSoundPlayer mSounds;

    private static boolean sInitialized;
    @Nullable private PeerConnectionFactory mFactory;
    @Nullable private JavaAudioDeviceModule mAdm;
    @Nullable private AudioSource mSource;
    @Nullable private AudioTrack mLocal;
    @Nullable private Call mCall;
    @Nullable private VoiceWire.Ice mIce;
    private int mKbps = VoiceMachine.DEFAULT_KBPS;
    private boolean mTalk;
    private boolean mDeaf;
    private boolean mMeters;
    private final Map<String, VoiceMachine.EnginePerson> mPeople = new HashMap<>();
    private final VoiceMids mMids = new VoiceMids();
    /** Receivers' tracks by mid. */
    private final Map<String, AudioTrack> mTracks = new HashMap<>();
    private final Map<String, Level> mLevels = new HashMap<>();
    private volatile long mMeLoudAt;
    private volatile long mSpokeSaidAt;
    private List<String> mSpeakingSaid = Collections.emptyList();
    private boolean mMeSaid;
    private final VoiceQuality.Tracker mQuality = new VoiceQuality.Tracker();
    @Nullable private VoiceQuality.Raw mLastStats;

    public VoiceEngine(Context context, Listener listener) {
        mContext = context.getApplicationContext();
        mListener = listener;
        mSounds = new VoiceSoundPlayer();
    }

    // ---- life ----

    /** The factory and the audio device: once per engine (a stay and the minute after it). */
    @MainThread
    public void open() {
        if (mFactory != null) return;
        if (!sInitialized) {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(mContext)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions());
            sInitialized = true;
        }
        mAdm = JavaAudioDeviceModule.builder(mContext)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                // Muted, alone or deafened: the microphone is not held at all.
                .setStopRecordingOnMute(true)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setSamplesReadyCallback(this::onMicSamples)
                .setAudioRecordErrorCallback(new JavaAudioDeviceModule.AudioRecordErrorCallback() {
                    @Override
                    public void onWebRtcAudioRecordInitError(String message) {
                        micMissing();
                    }

                    @Override
                    public void onWebRtcAudioRecordStartError(JavaAudioDeviceModule.AudioRecordStartErrorCode code, String message) {
                        micMissing();
                    }

                    @Override
                    public void onWebRtcAudioRecordError(String message) {
                        micMissing();
                    }
                })
                .createAudioDeviceModule();
        mFactory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(mAdm)
                .createPeerConnectionFactory();
    }

    @MainThread
    public void close() {
        leave();
        mSounds.release();
        if (mFactory != null) {
            mFactory.dispose();
            mFactory = null;
        }
        if (mAdm != null) {
            mAdm.release();
            mAdm = null;
        }
    }

    // ---- commands ----

    @MainThread
    public void run(VoiceMachine.Command command) {
        switch (command.kind) {
            case CONFIGURE:
                mKbps = command.kbps;
                if (mCall != null) mCall.capBitrate();
                break;
            case JOIN:
                join();
                break;
            case ANSWER:
                if (mCall != null && command.sdp != null) mCall.applyAnswer(command.sdp);
                break;
            case OFFER:
                if (command.sdp != null) {
                    for (String mid : mMids.assign(command.mids)) {
                        AudioTrack track = mTracks.get(mid);
                        if (track != null) applyPerson(mid, track);
                    }
                    if (mCall != null) mCall.applyOffer(command.sdp);
                }
                break;
            case TALK:
                mTalk = command.on;
                if (mLocal != null) mLocal.setEnabled(mTalk);
                break;
            case DEAF:
                mDeaf = command.on;
                if (mDeaf) mSounds.stopLoop();
                for (Map.Entry<String, AudioTrack> e : mTracks.entrySet()) applyPerson(e.getKey(), e.getValue());
                break;
            case PEOPLE:
                mPeople.clear();
                for (VoiceMachine.EnginePerson p : command.people) mPeople.put(p.id, p);
                for (Map.Entry<String, AudioTrack> e : mTracks.entrySet()) applyPerson(e.getKey(), e.getValue());
                break;
            case ICE:
                // TURN credentials: a new connection with them (the room takes no offer but a join).
                mIce = command.ice;
                join();
                break;
            case SOUND:
                if (command.sound == VoiceMachine.Sound.STOP) {
                    mSounds.stopLoop();
                } else if (command.sound != null && (!mDeaf || VoiceSounds.playsWhileDeaf(command.sound))) {
                    mSounds.play(command.sound);
                }
                break;
            case METERS:
                meters(command.on);
                break;
            case LEAVE:
                leave();
                break;
            default:
                break;
        }
    }

    @MainThread
    private void join() {
        PeerConnectionFactory factory = mFactory;
        if (factory == null) return;
        closeCall();
        boolean mic = ContextCompat.checkSelfPermission(mContext, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        mListener.onMic(mic ? VoiceMachine.Mic.OK : VoiceMachine.Mic.DENIED);
        if (mic && mLocal == null) {
            MediaConstraints constraints = new MediaConstraints();
            // WebRTC's own processing where the phone has none (the platform's wins where it has them).
            constraints.mandatory.add(new MediaConstraints.KeyValuePair("googEchoCancellation", "true"));
            constraints.mandatory.add(new MediaConstraints.KeyValuePair("googNoiseSuppression", "true"));
            constraints.mandatory.add(new MediaConstraints.KeyValuePair("googAutoGainControl", "true"));
            constraints.mandatory.add(new MediaConstraints.KeyValuePair("googHighpassFilter", "true"));
            mSource = factory.createAudioSource(constraints);
            mLocal = factory.createAudioTrack("m", mSource);
            mLocal.setEnabled(mTalk);
        }
        mCall = new Call(factory, mic ? mLocal : null, mIce);
        mCall.offer();
    }

    @MainThread
    private void leave() {
        closeCall();
        mIce = null;
        mSounds.stopLoop();
        meters(false);
        if (mLocal != null) {
            mLocal.dispose();
            mLocal = null;
        }
        if (mSource != null) {
            mSource.dispose();
            mSource = null;
        }
        mPeople.clear();
        mQuality.reset();
        mLastStats = null;
    }

    private void closeCall() {
        Call call = mCall;
        mCall = null;
        for (AudioTrack track : mTracks.values()) removeSink(track);
        mTracks.clear();
        mLevels.clear();
        mMids.clear();
        mMain.removeCallbacks(mStats);
        if (call != null) call.close();
    }

    private void applyPerson(String mid, AudioTrack track) {
        String person = mMids.personOf(mid);
        VoiceMachine.EnginePerson p = person == null ? null : mPeople.get(person);
        // A dead receiver (its person came back on a newer one), someone unknown, silenced or deafened: not played.
        boolean play = !mDeaf && p != null && !p.silent && mMids.current(mid);
        try {
            track.setEnabled(play);
            if (p != null) track.setVolume(Math.max(0, p.volume) / 100.0);
        } catch (IllegalStateException e) {
            // A track WebRTC disposed meanwhile.
        }
    }

    private void micMissing() {
        mMain.post(() -> mListener.onMic(VoiceMachine.Mic.MISSING));
    }

    // ---- measuring ----

    private void meters(boolean on) {
        if (mMeters == on) return;
        mMeters = on;
        mMain.removeCallbacks(mMeterTick);
        for (Map.Entry<String, AudioTrack> e : mTracks.entrySet()) {
            if (on) addSink(e.getKey(), e.getValue());
            else removeSink(e.getValue());
        }
        if (on) {
            mMain.postDelayed(mMeterTick, METERS_MS);
        } else {
            mLevels.clear();
            if (!mSpeakingSaid.isEmpty() || mMeSaid) {
                mSpeakingSaid = Collections.emptyList();
                mMeSaid = false;
                mListener.onSpeaking(mSpeakingSaid, false);
            }
        }
    }

    /** One receiver's loudness as its sink sees it (on WebRTC's audio thread). */
    private static final class Level implements AudioTrackSink {
        final String mid;
        volatile long loudAt;

        Level(String mid) {
            this.mid = mid;
        }

        @Override
        public void onData(ByteBuffer data, int bitsPerSample, int sampleRate, int channels, int frames, long captureMs) {
            if (bitsPerSample != 16 || frames <= 0) return;
            if (rms(data.duplicate().order(ByteOrder.LITTLE_ENDIAN), frames * channels) > SPEAKING_RMS) {
                loudAt = SystemClock.elapsedRealtime();
            }
        }
    }

    private void addSink(String mid, AudioTrack track) {
        if (mLevels.containsKey(mid)) return;
        Level level = new Level(mid);
        mLevels.put(mid, level);
        try {
            track.addSink(level);
        } catch (IllegalStateException e) {
            mLevels.remove(mid);
        }
    }

    private void removeSink(AudioTrack track) {
        for (Map.Entry<String, AudioTrack> e : mTracks.entrySet()) {
            if (e.getValue() != track) continue;
            Level level = mLevels.remove(e.getKey());
            if (level == null) return;
            try {
                track.removeSink(level);
            } catch (IllegalStateException ignored) {
                // Disposed meanwhile.
            }
            return;
        }
    }

    private final Runnable mMeterTick = new Runnable() {
        @Override
        public void run() {
            if (!mMeters) return;
            long now = SystemClock.elapsedRealtime();
            List<String> speaking = new ArrayList<>();
            for (Level level : mLevels.values()) {
                String person = mMids.personOf(level.mid);
                if (person != null && mMids.current(level.mid) && now - level.loudAt <= SPEAKING_TAIL_MS && !speaking.contains(person)) {
                    speaking.add(person);
                }
            }
            Collections.sort(speaking);
            boolean me = mTalk && now - mMeLoudAt <= SPEAKING_TAIL_MS;
            if (!speaking.equals(mSpeakingSaid) || me != mMeSaid) {
                mSpeakingSaid = speaking;
                mMeSaid = me;
                mListener.onSpeaking(speaking, me);
            }
            mMain.postDelayed(this, METERS_MS);
        }
    };

    /** The microphone's samples (WebRTC's recording thread): loud frames mark the player as speaking. */
    private void onMicSamples(JavaAudioDeviceModule.AudioSamples samples) {
        byte[] data = samples.getData();
        if (data == null || data.length < 2 || samples.getAudioFormat() != android.media.AudioFormat.ENCODING_PCM_16BIT) return;
        if (rms(ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN), data.length / 2) <= SPEAKING_RMS) return;
        long now = SystemClock.elapsedRealtime();
        mMeLoudAt = now;
        if (now - mSpokeSaidAt >= SPOKE_EVERY_MS) {
            mSpokeSaidAt = now;
            mMain.post(mListener::onSpoke);
        }
    }

    static double rms(ByteBuffer data, int samples) {
        int n = Math.min(samples, data.remaining() / 2);
        if (n <= 0) return 0;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double v = data.getShort() / 32768.0;
            sum += v * v;
        }
        return Math.sqrt(sum / n);
    }

    private final Runnable mStats = new Runnable() {
        @Override
        public void run() {
            Call call = mCall;
            if (call == null) return;
            call.pc.getStats(report -> {
                VoiceQuality.Raw raw = new VoiceQuality.Raw();
                for (RTCStats stat : report.getStatsMap().values()) {
                    Map<String, Object> members = new HashMap<>(stat.getMembers());
                    members.put("id", stat.getId());
                    VoiceQuality.read(raw, stat.getType(), members);
                }
                mMain.post(() -> {
                    if (mCall != call) return;
                    int grade = VoiceQuality.grade(mLastStats, raw);
                    mLastStats = raw;
                    if (mQuality.push(grade)) mListener.onQuality(mQuality.quality);
                    mMain.postDelayed(mStats, STATS_MS);
                });
            });
        }
    };

    // ---- one peer connection ----

    /** One SFU session's connection (the desktop's {@code Call}). */
    private final class Call implements PeerConnection.Observer {
        final PeerConnection pc;
        @Nullable final RtpSender sender;
        final boolean withTurn;
        boolean answered;
        boolean wasConnected;
        boolean interrupted;
        boolean done;
        private final Runnable connectTimeout = () -> {
            if (!done && !wasConnected) finish(Call.this.withTurn ? "lost" : "needTurn");
        };
        private final Runnable lostTimeout = () -> {
            if (!done && !connected()) finish("lost");
        };

        Call(PeerConnectionFactory factory, @Nullable AudioTrack local, @Nullable VoiceWire.Ice ice) {
            List<PeerConnection.IceServer> servers = new ArrayList<>();
            servers.add(PeerConnection.IceServer.builder(STUN).createIceServer());
            if (ice != null) {
                servers.add(PeerConnection.IceServer.builder(ice.urls).setUsername(ice.username).setPassword(ice.credential)
                        .createIceServer());
            }
            withTurn = ice != null;
            PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(servers);
            config.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
            config.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE;
            config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
            config.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE;
            pc = factory.createPeerConnection(config, this);
            RtpSender s = null;
            if (local != null && pc != null) {
                RtpTransceiver t = pc.addTransceiver(local,
                        new RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY));
                s = t.getSender();
            }
            sender = s;
        }

        boolean connected() {
            return pc.connectionState() == PeerConnection.PeerConnectionState.CONNECTED;
        }

        /** The join offer: one send-only audio m-line (no sender: listen only, no SDP). */
        void offer() {
            if (sender == null) {
                mListener.onOffer(null, null);
                return;
            }
            pc.createOffer(new Sdp() {
                @Override
                public void onCreateSuccess(SessionDescription offer) {
                    pc.setLocalDescription(new Sdp() {
                        @Override
                        public void onSetSuccess() {
                            mMain.post(() -> {
                                if (done) return;
                                String mid = null;
                                for (RtpTransceiver t : pc.getTransceivers()) {
                                    if (t.getSender() != null && sender != null && t.getSender().id().equals(sender.id())) mid = t.getMid();
                                }
                                SessionDescription local = pc.getLocalDescription();
                                mListener.onOffer(local == null ? null : local.description, mid);
                            });
                        }
                    }, offer);
                }
            }, new MediaConstraints());
        }

        void applyAnswer(String sdp) {
            if (done || pc.signalingState() != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) return;
            pc.setRemoteDescription(new Sdp() {
                @Override
                public void onSetSuccess() {
                    mMain.post(() -> {
                        if (done) return;
                        capBitrate();
                        watch();
                    });
                }

                @Override
                public void onSetFailure(String error) {
                    super.onSetFailure(error);
                    // An answer this connection cannot take: a new join.
                    mMain.post(() -> finish("lost"));
                }
            }, new SessionDescription(SessionDescription.Type.ANSWER, tune(sdp)));
        }

        void applyOffer(String sdp) {
            // A join waits for its answer; an offer now belongs to the session it replaced.
            if (done || pc.signalingState() != PeerConnection.SignalingState.STABLE) return;
            pc.setRemoteDescription(new Sdp() {
                @Override
                public void onSetSuccess() {
                    pc.createAnswer(new Sdp() {
                        @Override
                        public void onCreateSuccess(SessionDescription answer) {
                            pc.setLocalDescription(new Sdp() {
                                @Override
                                public void onSetSuccess() {
                                    mMain.post(() -> {
                                        if (done) return;
                                        watch();
                                        SessionDescription local = pc.getLocalDescription();
                                        if (local != null) mListener.onAnswer(local.description);
                                    });
                                }
                            }, answer);
                        }
                    }, new MediaConstraints());
                }
            }, new SessionDescription(SessionDescription.Type.OFFER, tune(sdp)));
        }

        private String tune(String sdp) {
            return VoiceSdp.tuneOpus(sdp, mKbps * 1000, true, true);
        }

        /** The description's own cap holds even if the encoder's cannot be set: never a lost call. */
        void capBitrate() {
            if (sender == null || done) return;
            try {
                RtpParameters parameters = sender.getParameters();
                if (parameters.encodings.isEmpty()) return;
                for (RtpParameters.Encoding encoding : parameters.encodings) encoding.maxBitrateBps = mKbps * 1000;
                sender.setParameters(parameters);
            } catch (RuntimeException e) {
                Log.i(TAG, "bitrate cap not set: " + e.getClass().getSimpleName());
            }
        }

        /** From the first answer on: connected within 5 s, or TURN (or lost). */
        void watch() {
            if (answered || done) return;
            answered = true;
            mMain.removeCallbacks(mStats);
            mMain.postDelayed(mStats, STATS_MS);
            if (connected()) {
                onState(PeerConnection.PeerConnectionState.CONNECTED);
                return;
            }
            mMain.postDelayed(connectTimeout, CONNECT_MS);
        }

        void onState(PeerConnection.PeerConnectionState state) {
            if (done) return;
            if (state == PeerConnection.PeerConnectionState.CONNECTED) {
                mMain.removeCallbacks(connectTimeout);
                mMain.removeCallbacks(lostTimeout);
                if (!wasConnected) {
                    wasConnected = true;
                    mListener.onConnected();
                } else if (interrupted) {
                    interrupted = false;
                }
                return;
            }
            if (state == PeerConnection.PeerConnectionState.FAILED) {
                finish(wasConnected || withTurn ? "lost" : "needTurn");
                return;
            }
            if (state == PeerConnection.PeerConnectionState.DISCONNECTED && wasConnected && !interrupted) {
                interrupted = true;
                mMain.postDelayed(lostTimeout, LOST_MS);
            }
        }

        /** The connection is over: one word to the machine, nothing after it. */
        void finish(String why) {
            if (done) return;
            if (mCall == this) closeCall();
            else close();
            if ("lost".equals(why)) mListener.onLost();
            else mListener.onNeedTurn();
        }

        void close() {
            if (done) return;
            done = true;
            mMain.removeCallbacks(connectTimeout);
            mMain.removeCallbacks(lostTimeout);
            pc.dispose();
        }

        // ---- PeerConnection.Observer (WebRTC's signaling thread) ----

        @Override
        public void onConnectionChange(PeerConnection.PeerConnectionState state) {
            mMain.post(() -> onState(state));
        }

        @Override
        public void onTrack(RtpTransceiver transceiver) {
            final String mid = transceiver.getMid();
            final MediaStreamTrack track = transceiver.getReceiver().track();
            if (mid == null || !(track instanceof AudioTrack)) return;
            mMain.post(() -> {
                if (done || mCall != this) return;
                AudioTrack audio = (AudioTrack) track;
                AudioTrack before = mTracks.put(mid, audio);
                if (before != null && before != audio) removeSink(before);
                applyPerson(mid, audio);
                if (mMeters) addSink(mid, audio);
            });
        }

        @Override
        public void onRemoveTrack(RtpReceiver receiver) {
            final MediaStreamTrack track = receiver.track();
            mMain.post(() -> {
                if (done || mCall != this) return;
                for (Map.Entry<String, AudioTrack> e : new ArrayList<>(mTracks.entrySet())) {
                    if (e.getValue() == track) {
                        removeSink(e.getValue());
                        mTracks.remove(e.getKey());
                    }
                }
            });
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
            // The room drives every negotiation (a join, or its offers).
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

    /** Thread-safe set of the pending work for tests and the service: whether a peer connection exists. */
    public boolean inCall() {
        return mCall != null;
    }
}
