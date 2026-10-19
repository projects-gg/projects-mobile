package gg.projects.mobile.launch;

/**
 * When the launch screen lifts without a picture (M14). Plain Java, JVM-tested; LaunchCurtain feeds
 * it times (SystemClock.uptimeMillis) and the stage. A picture on the game's surface lifts the screen
 * at once ({@link FrameContent#hasPicture}); these rules cover everything else.
 *
 * <p>The game's surface is the only honest signal: TextureView reports every frame it puts on screen
 * (its content changes in no other way) and PixelCopy reads the SurfaceView mode on Android 7+. While
 * the screen can see frames it waits for a picture: the first emulator run (19 October) lifted 3 s
 * after Minecraft began loading its resources and the player looked at 7 s of black, because the
 * Projects pack hides Minecraft's loading screen (Remove Reloading Screen) and the game showed
 * nothing but black until its title screen. Log-based fallbacks apply where frames cannot be seen.
 *
 * <p>Without a picture the time limits only take a game that has gone quiet: one that still writes to
 * its log is working (a slow phone, a first start with many mods: a start on a busy emulator wrote
 * nothing for 89 s while loading mods), and the "Kaydı göster" / "Oyunu kapat" way out is on screen
 * meanwhile. Where frames cannot be seen the limits apply as they are, and nothing outlasts
 * {@link #HARD_LIMIT_MS}.
 */
public final class LiftRules {
    private LiftRules() {}

    /**
     * Frames keep coming but stay black while the game writes nothing: it draws a dark screen of its
     * own by now. While it still writes it is loading behind black frames (the Projects pack).
     */
    public static final long STEADY_MS = 10_000;
    /** No way to see frames: Minecraft's own loading screen is up this long after it loads resources. */
    public static final long RESOURCES_FALLBACK_MS = 3000;
    /** No picture this long after Minecraft itself started (and the game has gone quiet): out of the way. */
    public static final long MINECRAFT_FALLBACK_MS = 150_000;
    /** A start that has not reached Minecraft itself by now (and has gone quiet) is stuck (REVIEW1 M14-1). */
    public static final long BEFORE_MINECRAFT_LIMIT_MS = 5 * 60_000;
    public static final long ABSOLUTE_FALLBACK_MS = 8 * 60_000;
    /** No line in the game's log for this long: the game is stuck, not slow. */
    public static final long QUIET_MS = 120_000;
    /** Whatever the log says. */
    public static final long HARD_LIMIT_MS = 20 * 60_000;
    /**
     * A title screen shown early (Remove Reloading Screen) while the game still loads behind it: the
     * screen stays until the game says it finished, the game wrote nothing for this long, or ...
     */
    public static final long SETTLE_QUIET_MS = 10_000;
    /** ... this long after that first picture. */
    public static final long SETTLE_LIMIT_MS = 40_000;

    /** What LaunchCurtain knows at one moment; times are uptime milliseconds, 0 for "not yet". */
    public static final class State {
        public long startedAt;
        public int stage = LaunchStages.PREPARING;
        /** Minecraft itself started (the status line reached {@link LaunchStages#MINECRAFT}). */
        public long minecraftAt;
        /** Minecraft began loading its resources ({@link LaunchStages#loadingResources}). */
        public long resourcesAt;
        /** The last line the game wrote to its log. */
        public long lastLogAt;
        /** The first frame that reached the game's surface, black or not. */
        public long firstFrameAt;
        /** Frames on the game's surface can be seen (TextureView, or PixelCopy that works). */
        public boolean seesFrames;
        /** A picture came while the game still loads behind its early title screen ({@link LaunchStages#earlyTitle}). */
        public long pictureAt;
        /** The game finished loading behind its early title screen ({@link LaunchStages#loadDone}). */
        public long loadDoneAt;
    }

    /**
     * The reason to lift at {@code now}, or null to stay. A picture lifts the screen at once
     * (LaunchCurtain) unless the game showed its title screen early: then {@link State#pictureAt} is
     * set and the screen waits for the game to finish loading behind it. On the emulator (19 October)
     * the early title screen ran at 9 to 17 frames a second, froze for 7 s and was smooth 16 s after
     * its first frame; the game wrote "Overlay is removed!" 20 s after it.
     */
    public static String reason(State s, long now) {
        if (s.pictureAt > 0) {
            if (s.loadDoneAt > 0) return "loaded";
            if (now - s.pictureAt >= SETTLE_LIMIT_MS) return "settle timeout";
            if (now - Math.max(s.pictureAt, s.lastLogAt) >= SETTLE_QUIET_MS) return "settled";
            return null;
        }
        if (FrameContent.steadyFrames(s.firstFrameAt, now, s.stage, STEADY_MS) && now - s.lastLogAt >= STEADY_MS) {
            return "steady frames";
        }
        if (!s.seesFrames && s.resourcesAt > 0 && now - s.resourcesAt >= RESOURCES_FALLBACK_MS) return "resources";
        long since = now - s.startedAt;
        if (since > HARD_LIMIT_MS) return "hard timeout";
        boolean stuck = !s.seesFrames || now - Math.max(Math.max(s.lastLogAt, s.minecraftAt), s.startedAt) > QUIET_MS;
        if (!stuck) return null;
        if (s.minecraftAt > 0 && now - s.minecraftAt > MINECRAFT_FALLBACK_MS) return "minecraft timeout";
        if (s.minecraftAt == 0 && since > BEFORE_MINECRAFT_LIMIT_MS) return "timeout before Minecraft";
        if (since > ABSOLUTE_FALLBACK_MS) return "timeout";
        return null;
    }
}
