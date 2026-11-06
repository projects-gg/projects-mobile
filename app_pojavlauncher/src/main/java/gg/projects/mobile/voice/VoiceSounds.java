package gg.projects.mobile.voice;

/**
 * Voice chat's own short sounds, made here (no files to ship or license; the desktop's {@code sounds.ts}, note for note):
 * one family of soft sine notes with a little of their octave, a quick attack and a natural decay, quiet (peaks near
 * -12 dBFS and under), all in A major. Rising says "on" or "in", falling "off" or "out". {@code WAITING} is one cycle of
 * the caller's tone, looped until someone comes. The ring itself is {@link #ring} (the desktop's {@code ring-sound.ts}).
 * M051 (SHARE-LIVE): {@code STREAM_START} lands on an open fifth, an octave under the player's own stream sound: someone
 * else in the room went live.
 */
public final class VoiceSounds {
    private VoiceSounds() {}

    private static final double E4 = 329.63;
    private static final double A4 = 440;
    private static final double C5 = 523.25;
    private static final double CS5 = 554.37;
    private static final double E5 = 659.25;
    private static final double A5 = 880;
    private static final double B5 = 987.77;
    private static final double E6 = 1318.51;
    private static final double ATTACK_S = 0.006;
    private static final double RELEASE_S = 0.04;

    /** {freqs…}, at, length, peak, decay: one note. */
    private static final class Note {
        final double[] freqs;
        final double at;
        final double length;
        final double peak;
        final double decay;

        Note(double[] freqs, double at, double length, double peak, double decay) {
            this.freqs = freqs;
            this.at = at;
            this.length = length;
            this.peak = peak;
            this.decay = decay;
        }
    }

    private static Note n(double f, double at, double length, double peak, double decay) {
        return new Note(new double[] {f}, at, length, peak, decay);
    }

    private static double length(VoiceMachine.Sound sound) {
        switch (sound) {
            case MIC_OFF:
            case MIC_ON:
                return 0.13;
            case CONNECTED:
                return 0.42;
            case ENDED:
                return 0.5;
            case LOST:
                return 0.48;
            case WAITING:
                return 3;
            case STREAM_START:
                return 0.4;
            default:
                return 0.3;
        }
    }

    private static Note[] notes(VoiceMachine.Sound sound) {
        switch (sound) {
            case JOIN:
                return new Note[] {n(E5, 0, 0.14, 0.2, 0.05), n(A5, 0.085, 0.2, 0.22, 0.06)};
            case LEAVE:
                return new Note[] {n(A5, 0, 0.14, 0.2, 0.05), n(E5, 0.085, 0.2, 0.19, 0.06)};
            case MIC_OFF:
                return new Note[] {n(E6, 0, 0.05, 0.11, 0.014), n(B5, 0.048, 0.075, 0.12, 0.02)};
            case MIC_ON:
                return new Note[] {n(B5, 0, 0.05, 0.11, 0.014), n(E6, 0.048, 0.075, 0.12, 0.02)};
            case DEAF_ON:
                return new Note[] {n(A4, 0, 0.12, 0.17, 0.045), n(E4, 0.07, 0.22, 0.18, 0.07)};
            case DEAF_OFF:
                return new Note[] {n(E4, 0, 0.12, 0.17, 0.045), n(A4, 0.07, 0.22, 0.18, 0.07)};
            case CONNECTED:
                return new Note[] {n(CS5, 0, 0.12, 0.15, 0.045), n(E5, 0.07, 0.12, 0.16, 0.045), n(A5, 0.14, 0.26, 0.2, 0.08)};
            case ENDED:
                return new Note[] {n(A5, 0, 0.13, 0.16, 0.05), n(E5, 0.085, 0.13, 0.15, 0.05), n(CS5, 0.17, 0.3, 0.16, 0.09)};
            case LOST:
                return new Note[] {n(E5, 0, 0.16, 0.15, 0.06), n(C5, 0.12, 0.34, 0.16, 0.1)};
            case WAITING:
                return new Note[] {new Note(new double[] {A4, CS5}, 0, 0.32, 0.16, 0),
                        new Note(new double[] {A4, CS5}, 0.45, 0.32, 0.16, 0)};
            case STREAM_START:
                return new Note[] {n(A4, 0, 0.11, 0.13, 0.045), new Note(new double[] {E5, A5}, 0.075, 0.3, 0.17, 0.09)};
            default:
                return new Note[0];
        }
    }

    /** The sound's samples (mono, 16-bit) at {@code rate}. */
    public static short[] render(VoiceMachine.Sound sound, int rate) {
        float[] out = new float[(int) Math.round(length(sound) * rate)];
        for (Note note : notes(sound)) {
            int start = (int) Math.round(note.at * rate);
            int frames = Math.min((int) Math.round(note.length * rate), out.length - start);
            double share = note.peak / note.freqs.length;
            for (int i = 0; i < frames; i++) {
                double t = (double) i / rate;
                double attack = Math.min(1, t / ATTACK_S);
                double left = note.length - t;
                double release = Math.min(1, left / (note.decay > 0 ? 0.003 : RELEASE_S));
                double decay = note.decay > 0 ? Math.exp(-t / note.decay) : 1;
                double envelope = attack * release * decay;
                double v = 0;
                for (double freq : note.freqs) {
                    double phase = 2 * Math.PI * freq * t;
                    // A little of the octave for warmth.
                    v += Math.sin(phase) + 0.12 * Math.sin(2 * phase);
                }
                out[start + i] += (float) ((share / 1.12) * envelope * v);
            }
        }
        return pcm(out);
    }

    /** Whether the sound repeats until stopped. */
    public static boolean loops(VoiceMachine.Sound sound) {
        return sound == VoiceMachine.Sound.WAITING;
    }

    /**
     * Sounds of the player's own state play while deafened too; someone coming, leaving or going live and the caller's
     * tone do not.
     */
    public static boolean playsWhileDeaf(VoiceMachine.Sound sound) {
        return sound != VoiceMachine.Sound.JOIN && sound != VoiceMachine.Sound.LEAVE && sound != VoiceMachine.Sound.WAITING
                && sound != VoiceMachine.Sound.STREAM_START;
    }

    /**
     * One loop of the ring (2.6 s, its rest included): two rising pairs of a soft bell ("ding-dong, ding-dong"), a sine and
     * its quiet octave with a quick attack and a long fall, at 0.6 of full scale (a ring is meant to be heard).
     */
    public static short[] ring(int rate) {
        float[] out = new float[(int) Math.ceil(rate * 2.6)];
        chime(out, rate, 659.25, 0.02, 0.22, 0.5);
        chime(out, rate, 880, 0.22, 0.2, 0.6);
        chime(out, rate, 659.25, 0.72, 0.22, 0.5);
        chime(out, rate, 880, 0.92, 0.2, 0.7);
        for (int i = 0; i < out.length; i++) out[i] *= 0.6f;
        return pcm(out);
    }

    private static void chime(float[] out, int rate, double frequency, double at, double peak, double length) {
        double[][] partials = {{frequency, peak}, {frequency * 2, peak * 0.25}};
        int start = (int) Math.round(at * rate);
        int frames = (int) Math.round((length + 0.05) * rate);
        for (double[] partial : partials) {
            for (int i = 0; i < frames && start + i < out.length; i++) {
                double t = (double) i / rate;
                double gain;
                if (t < 0.012) gain = partial[1] * t / 0.012;
                else if (t < length) gain = partial[1] * Math.pow(0.0001 / partial[1], (t - 0.012) / (length - 0.012));
                else gain = 0;
                out[start + i] += (float) (gain * Math.sin(2 * Math.PI * partial[0] * t));
            }
        }
    }

    private static short[] pcm(float[] samples) {
        short[] out = new short[samples.length];
        for (int i = 0; i < samples.length; i++) {
            float v = Math.max(-1f, Math.min(1f, samples[i]));
            out[i] = (short) Math.round(v * 32767);
        }
        return out;
    }
}
