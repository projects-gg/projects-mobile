package gg.projects.mobile.launch;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Tells from what a failed game start left behind whether Sodium (or a mod built on it) is the
 * likely cause, so the launcher can switch it off and offer to start again.
 *
 * <p>Sure signs: a crash report naming Sodium, Iris or one of their add-ons as the suspected mod or
 * in its stack trace, the last error of the game log coming from their code or mixins, Sodium's
 * own "cannot run here" errors, and a fatal JVM error whose Java frames are theirs. Likely: a fatal
 * native crash inside the graphics driver or renderer while Sodium is installed (Sodium drives the
 * renderer much harder than the vanilla game; starting once without it tells the rest).
 *
 * <p>A normal log is full of harmless exceptions (no network, missing optional natives), and a crash
 * report lists every loaded mod in its system details, so only the parts that describe the failure
 * are read.
 */
public final class SodiumCrash {
    private SodiumCrash() {}

    public enum Verdict {
        /** Nothing points at Sodium. */
        NONE,
        /** Sodium's code, mixins or own checks are in the failure. */
        SODIUM,
        /** The renderer crashed natively while Sodium was installed. */
        RENDERER_WITH_SODIUM
    }

    /** Packages of Sodium, its forks, Iris and the add-ons of the Projects pack. */
    private static final Pattern CODE = Pattern.compile(
            "net\\.caffeinemc\\.mods\\.sodium|me\\.jellysquid\\.mods\\.sodium|net\\.irisshaders\\.|net\\.coderbot\\.iris"
                    + "|me\\.flashyreese\\.mods\\.(sodiumextra|reeses_sodium_options)|net\\.anvian\\.sodiumextrainformation"
                    + "|org\\.embeddedt\\.embeddium|me\\.srrapero720\\.embeddiumplus"
                    + "|net/caffeinemc/mods/sodium|me/jellysquid/mods/sodium|net/irisshaders/");
    /** Mixin configs and mod ids as Mixin and Fabric Loader name them in errors. */
    private static final Pattern MIXINS = Pattern.compile(
            "(sodium[\\w-]*|iris[\\w-]*|reeses-sodium-options|sodiumextrainformation|embeddium[\\w-]*)\\.mixins\\.json"
                    + "|mixins\\.iris[\\w.]*\\.json"
                    + "|from mod (sodium|sodium-extra|reeses-sodium-options|sodiumextrainformation|iris|embeddium|rubidium|oculus)\\b",
            Pattern.CASE_INSENSITIVE);
    /** Sodium's own fatal checks (they end the game with a dialog Android cannot show). */
    private static final Pattern OWN_CHECKS = Pattern.compile(
            "Sodium Renderer - |which is not supported when using Sodium|"
                    + "The game failed to start because the currently active LWJGL version is not compatible");
    /** A crash report's "Suspected Mod(s)" line naming one of the family. */
    private static final Pattern SUSPECTED = Pattern.compile(
            "Suspected Mods?:.*\\b(Sodium|Iris|Embeddium|Rubidium|Oculus|Reese's Sodium Options|Sodium Extra)\\b",
            Pattern.CASE_INSENSITIVE);
    /** Libraries a native crash inside the renderer or the GPU driver happens in. */
    private static final Pattern GRAPHICS_LIBRARY = Pattern.compile(
            "lib(mobileglues|SimpleFPEWrapper|GLES\\w*|EGL\\w*|ltw|ng_gl4es|gl4es|OSMesa|glxshim|vulkan\\w*|gallium\\w*|zink\\w*"
                    + "|freedreno\\w*|gsl|llvm-glnext|mali\\w*|adreno\\w*)\\.so|vulkan\\.\\w+\\.so",
            Pattern.CASE_INSENSITIVE);
    /** Java frames of the game's rendering, for a native crash without a library name. */
    private static final Pattern RENDER_FRAMES = Pattern.compile("org\\.lwjgl\\.(opengl|opengles|egl)\\.|com\\.mojang\\.blaze3d\\.");

    /**
     * Lines Minecraft writes when its first resource load has brought up the sound engine (26.1.2 and
     * older versions alike), the last part of its start; or when sound could not start and it went
     * on without. Server connects are no sign: the Projects profile's Quick Play connects while the
     * resources are still loading.
     */
    private static final String[] SOUND_UP = {"Sound engine started", "Error starting SoundSystem"};
    /** Minecraft shutting down: whatever ended the game, it had started. */
    private static final String STOPPING = "Stopping!";
    /** The rest of the first resource load and the first moments of the menu still count as the start. */
    static final long SETTLE_MS = 90_000;
    /** A start that ends later than this after its launch was not a start-up failure. */
    static final long STARTUP_LIMIT_MS = 15 * 60_000;
    private static final Pattern TIME = Pattern.compile("^\\[(\\d{2}):(\\d{2}):(\\d{2})\\]", Pattern.MULTILINE);

    private static final String CRASH_REPORT_START = "---- Minecraft Crash Report ----";
    private static final String FATAL_ERROR = "A fatal error has been detected by the Java Runtime Environment";
    /** Lines at the end of the log searched for the error that ended the game. */
    private static final int LAST_ERROR_LINES = 400;

    /**
     * {@code log}: the end of the launcher's game log ({@code latestlog.txt}); {@code crashReport}:
     * a crash report the game wrote during this start, or null; {@code fatalReport}: the JVM's
     * {@code hs_err_pid*.log} from this start, or null; {@code sodiumInstalled}: the started profile
     * had Sodium or one of its family in its mods folder.
     */
    public static Verdict diagnose(String log, String crashReport, String fatalReport, boolean sodiumInstalled) {
        if (!sodiumInstalled) return Verdict.NONE;
        String text = log == null ? "" : log;
        if (OWN_CHECKS.matcher(text).find()) return Verdict.SODIUM;

        String report = crashReport != null ? crashReport : crashReportIn(text);
        if (report != null) return failurePart(report) ? Verdict.SODIUM : Verdict.NONE;

        boolean fatal = fatalReport != null || text.contains(FATAL_ERROR);
        if (fatal) {
            String fatalText = fatalReport != null ? fatalReport : text.substring(text.lastIndexOf(FATAL_ERROR));
            if (CODE.matcher(javaFrames(fatalText)).find()) return Verdict.SODIUM;
            String problematic = problematicFrame(fatalText);
            if (GRAPHICS_LIBRARY.matcher(problematic).find() || RENDER_FRAMES.matcher(javaFrames(fatalText)).find()) {
                return Verdict.RENDERER_WITH_SODIUM;
            }
            return Verdict.NONE;
        }
        return lastErrorIsSodium(text) ? Verdict.SODIUM : Verdict.NONE;
    }

    /**
     * True when a game that ended with an error ended during its start (REVIEW1 A1: a graphics driver
     * crash hours into play was taken for Sodium keeping the game from starting, and Sodium stayed
     * off for good). The start lasts until the first resource load has brought up the sound engine,
     * plus {@link #SETTLE_MS} (the rest of that load and the menu's first moments), measured with the
     * log's own clock; a game that was stopping, ended more than {@link #STARTUP_LIMIT_MS} after its
     * launch, or wrote more log than was read, had started.
     *
     * @param log          the end of the game log, or null when there is none
     * @param logTruncated the log is longer than the part that was read (its start is missing)
     * @param ageMs        time from the launch to now (the exit screen runs as the game ends)
     */
    public static boolean duringStartup(String log, boolean logTruncated, long ageMs) {
        if (logTruncated || ageMs < 0 || ageMs > STARTUP_LIMIT_MS) return false;
        if (log == null) return true;
        if (log.contains(STOPPING)) return false;
        int sound = -1;
        for (String marker : SOUND_UP) {
            int at = log.indexOf(marker);
            if (at >= 0 && (sound < 0 || at < sound)) sound = at;
        }
        if (sound < 0) return true;
        long soundAt = timeBefore(log, sound);
        long lastAt = lastTime(log);
        if (soundAt < 0 || lastAt < 0) return false;
        long since = lastAt - soundAt;
        if (since < 0) since += 24L * 60 * 60 * 1000; // past midnight
        return since <= SETTLE_MS;
    }

    /** The time of day ("[16:14:29]") of the log line holding {@code index}, in ms, or -1. */
    private static long timeBefore(String log, int index) {
        int lineStart = log.lastIndexOf('\n', index) + 1;
        java.util.regex.Matcher m = TIME.matcher(log);
        return m.find(lineStart) && m.start() == lineStart ? millis(m) : -1;
    }

    /** The time of day of the last timestamped line, in ms, or -1. */
    private static long lastTime(String log) {
        java.util.regex.Matcher m = TIME.matcher(log);
        long last = -1;
        while (m.find()) last = millis(m);
        return last;
    }

    private static long millis(java.util.regex.Matcher m) {
        return ((Integer.parseInt(m.group(1)) * 60L + Integer.parseInt(m.group(2))) * 60L + Integer.parseInt(m.group(3))) * 1000L;
    }

    /** The crash report printed into the log, if the game printed one. */
    static String crashReportIn(String log) {
        int start = log.lastIndexOf(CRASH_REPORT_START);
        return start < 0 ? null : log.substring(start);
    }

    /**
     * True when the part of a crash report that describes the failure (everything before the system
     * details, which list every mod) names the family.
     */
    static boolean failurePart(String report) {
        int details = report.indexOf("-- System Details --");
        String failure = details < 0 ? report : report.substring(0, details);
        return SUSPECTED.matcher(failure).find() || CODE.matcher(failure).find() || MIXINS.matcher(failure).find();
    }

    /** The error that ended the game: the last exception (with its causes) near the end of the log. */
    static boolean lastErrorIsSodium(String log) {
        String[] lines = log.split("\r?\n", -1);
        int from = Math.max(0, lines.length - LAST_ERROR_LINES);
        // Walk back to the last stack trace and take the whole block with its message line.
        int end = -1;
        for (int i = lines.length - 1; i >= from; i--) {
            if (isFrame(lines[i])) {
                end = i;
                break;
            }
        }
        if (end < 0) {
            // No stack trace: a loader or mixin error line may still name the family.
            for (int i = from; i < lines.length; i++) if (isErrorLine(lines[i]) && MIXINS.matcher(lines[i]).find()) return true;
            return false;
        }
        int start = end;
        while (start > from && (isFrame(lines[start - 1]) || lines[start - 1].trim().startsWith("Caused by:")
                || lines[start - 1].trim().startsWith("..."))) {
            start--;
        }
        if (start > from) start--; // the exception's own line
        // The logger's own message often comes right before the exception ("[main/ERROR]: Mixin apply
        // for mod iris failed ...").
        for (int i = 0; i < 3 && start > from && isErrorLogLine(lines[start - 1]); i++) start--;
        StringBuilder block = new StringBuilder();
        for (int i = start; i <= end; i++) block.append(lines[i]).append('\n');
        String text = block.toString();
        return CODE.matcher(text).find() || MIXINS.matcher(text).find();
    }

    private static boolean isFrame(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("at ") && (line.startsWith("\t") || line.startsWith(" "));
    }

    /** A log line written at error level ("[Render thread/ERROR]: ..."). */
    private static boolean isErrorLogLine(String line) {
        return line.contains("/ERROR]") || line.contains("/FATAL]");
    }

    private static boolean isErrorLine(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.contains("error") || lower.contains("exception") || lower.contains("failed") || lower.contains("fatal");
    }

    /** The "Problematic frame" lines of a fatal JVM error. */
    static String problematicFrame(String fatal) {
        int at = fatal.indexOf("Problematic frame:");
        if (at < 0) return "";
        int end = fatal.indexOf('\n', fatal.indexOf('\n', at) + 1);
        return end < 0 ? fatal.substring(at) : fatal.substring(at, end);
    }

    /** The Java frames of a fatal JVM error report (the crashing thread's stack). */
    static String javaFrames(String fatal) {
        int at = fatal.indexOf("Java frames:");
        if (at < 0) return problematicFrame(fatal);
        int end = fatal.indexOf("\n\n", at);
        return end < 0 ? fatal.substring(at) : fatal.substring(at, end);
    }
}
