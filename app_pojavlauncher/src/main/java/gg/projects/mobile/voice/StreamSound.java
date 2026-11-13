package gg.projects.mobile.voice;

import java.util.Collection;

/**
 * MOB-WATCH: a watched stream's sound apart from Android, so it is tested (the desktop's {@code share/stream-audio.ts},
 * the lead's {@code sqol.md} §1.1): its gain ("Yayın Sesi" 0–200 %, "Yayını Sessize Al", deafened), the duck
 * ("Biri Konuşunca Yayın Sesini Kıs": "Kısma Miktarı" 20–90 %, "Ben Konuşunca da Kıs") and the way both move.
 *
 * <p>The phone plays the stream through voice chat's own sound path (the echo canceller hears it too), where WebRTC
 * applies one gain per 10 ms frame: the gain moves in steps of that size, an exponential approach as the desktop's
 * {@code setTargetAtTime} (the duck down in about 80 ms, back up in about 700 ms; a volume change glides in about 15 ms).
 * The steps run only while something moves: nothing wakes while the sound holds still.
 */
public final class StreamSound {
    private StreamSound() {}

    public static final int VOLUME_MAX = 200;
    public static final int VOLUME_DEFAULT = 100;
    /** "Kısma Miktarı": how much quieter, percent (60 ≈ −8 dB). */
    public static final int DUCK_LEVEL_MIN = 20;
    public static final int DUCK_LEVEL_MAX = 90;
    public static final int DUCK_LEVEL_DEFAULT = 60;
    /** The duck goes down this fast (most of the way)… */
    public static final double DUCK_ATTACK_S = 0.08;
    /** …and comes back up this slowly (OBS guides: attack 50–200 ms, release 500–850 ms). */
    public static final double DUCK_RELEASE_S = 0.7;
    /** A volume change glides (no click). */
    public static final double GLIDE_S = 0.015;
    /** The ramp's step while it moves: WebRTC takes one gain per 10 ms frame. */
    public static final long STEP_MS = 10;
    /** Closer than this to its target, a ramp is there. */
    static final double SETTLED = 0.002;

    public static int clampVolume(int percent) {
        return Math.max(0, Math.min(VOLUME_MAX, percent));
    }

    public static int clampDuckLevel(int percent) {
        return Math.max(DUCK_LEVEL_MIN, Math.min(DUCK_LEVEL_MAX, percent));
    }

    /** The stream's gain: its volume (the cap at most); none muted or deafened. */
    public static double gain(int volume, boolean muted, boolean deaf) {
        if (muted || deaf) return 0;
        return clampVolume(volume) / 100.0;
    }

    /**
     * The duck the stream passes through now: lower while someone else in the room speaks ({@code othersSpeaking}: whom the
     * player silenced is never counted), or the player too with "Ben Konuşunca da Kıs".
     */
    public static double duck(boolean on, int level, boolean othersSpeaking, boolean self, boolean meTalking) {
        if (!on) return 1;
        if (!othersSpeaking && !(self && meTalking)) return 1;
        return 1 - clampDuckLevel(level) / 100.0;
    }

    /** Whether anyone the player hears speaks: {@code speaking} without the player and those in {@code silent}. */
    public static boolean othersSpeaking(Collection<String> speaking, String me, Collection<String> silent) {
        for (String id : speaking) if (!id.equals(me) && !silent.contains(id)) return true;
        return false;
    }

    /** The time constant of a duck's move (seconds): down with the attack, up with the release (a third of each). */
    static double duckTau(double from, double to) {
        return (to < from ? DUCK_ATTACK_S : DUCK_RELEASE_S) / 3;
    }

    /** One exponential step of {@code dtMs} toward {@code target} (time constant {@code tauS}); there once close. */
    static double step(double value, double target, double tauS, long dtMs) {
        if (Math.abs(target - value) < SETTLED || tauS <= 0) return target;
        double next = target + (value - target) * Math.exp(-(dtMs / 1000.0) / tauS);
        return Math.abs(target - next) < SETTLED ? target : next;
    }

    /**
     * The stream's two moving parts: its gain (gliding) and the duck (attack and release). {@link #set} gives the targets,
     * {@link #advance} moves both to a moment and says the product WebRTC plays at.
     */
    public static final class Ramp {
        private double level;
        private double levelTarget;
        private double duck = 1;
        private double duckTarget = 1;
        private double duckTau = DUCK_RELEASE_S / 3;
        private long at = -1;

        public Ramp(double level) {
            this.level = level;
            this.levelTarget = level;
        }

        /** New targets at {@code now} (milliseconds): the moves start from where they are. */
        public void set(double level, double duck, long now) {
            advance(now);
            levelTarget = Math.max(0, level);
            if (Math.abs(duck - duckTarget) > 1e-9) {
                duckTau = duckTau(this.duck, duck);
                duckTarget = duck;
            }
        }

        /** Both parts moved to {@code now}; the gain to play at. */
        public double advance(long now) {
            if (at >= 0 && now > at) {
                long dt = now - at;
                level = step(level, levelTarget, GLIDE_S, dt);
                duck = step(duck, duckTarget, duckTau, dt);
            }
            at = now;
            return gain();
        }

        /** The gain as it is now. */
        public double gain() {
            return level * duck;
        }

        /** Something still moves: another step is wanted in {@link #STEP_MS}. */
        public boolean moving() {
            return level != levelTarget || duck != duckTarget;
        }

        /** Jumps to the targets (a stream that starts, a deafen: no glide from silence). */
        public void settle(long now) {
            level = levelTarget;
            duck = duckTarget;
            at = now;
        }

        double duck() {
            return duck;
        }
    }
}
