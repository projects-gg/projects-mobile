package gg.projects.mobile.voice;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;

import androidx.annotation.Nullable;

import java.util.EnumMap;
import java.util.Map;

/**
 * Plays {@link VoiceSounds} in the voice process, the media way (the volume the game and the voices use). Each sound is
 * drawn once into a static track when first needed; the caller's tone loops until stopped. Nothing plays without a stay.
 */
final class VoiceSoundPlayer {
    private static final int RATE = 48_000;

    private final Map<VoiceMachine.Sound, AudioTrack> mTracks = new EnumMap<>(VoiceMachine.Sound.class);
    @Nullable private AudioTrack mLoop;

    void play(VoiceMachine.Sound sound) {
        AudioTrack track = track(sound);
        if (track == null) return;
        try {
            if (VoiceSounds.loops(sound)) {
                if (mLoop == track && track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) return;
                stopLoop();
                mLoop = track;
            }
            if (track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) track.stop();
            track.reloadStaticData();
            track.play();
        } catch (IllegalStateException e) {
            // A sound is a nicety: a track the system took back says nothing.
        }
    }

    void stopLoop() {
        AudioTrack loop = mLoop;
        mLoop = null;
        if (loop == null) return;
        try {
            loop.pause();
            loop.flush();
            loop.stop();
        } catch (IllegalStateException ignored) {
            // Already stopped.
        }
    }

    void release() {
        stopLoop();
        for (AudioTrack track : mTracks.values()) track.release();
        mTracks.clear();
    }

    @Nullable
    private AudioTrack track(VoiceMachine.Sound sound) {
        AudioTrack track = mTracks.get(sound);
        if (track != null) return track;
        short[] samples = VoiceSounds.render(sound, RATE);
        if (samples.length == 0) return null;
        try {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            AudioFormat format = new AudioFormat.Builder()
                    .setSampleRate(RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build();
            int bytes = samples.length * 2;
            track = Build.VERSION.SDK_INT >= 23
                    ? new AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(format)
                            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(bytes).build()
                    : new AudioTrack(attributes, format, bytes, AudioTrack.MODE_STATIC, 0);
            track.write(samples, 0, samples.length);
            if (VoiceSounds.loops(sound)) track.setLoopPoints(0, samples.length, -1);
            mTracks.put(sound, track);
            return track;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
