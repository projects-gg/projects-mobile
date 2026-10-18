package gg.projects.mobile.launch;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import net.kdt.pojavlaunch.Tools;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import gg.projects.mobile.content.AtomicFiles;
import gg.projects.mobile.join.ProjectsProfile;
import gg.projects.mobile.mods.SodiumState;

/**
 * After the game ended with an error: did Sodium keep it from starting ({@link SodiumCrash})? If
 * so, the Projects profile holds the Sodium family off from now on on this phone (the next start
 * syncs it away; the player's choices stay theirs, see {@link SodiumState}); a profile the player
 * made is only offered to have the family's jars renamed to {@code .disabled}, since those files
 * are theirs. Upstream used to offer one button before every start instead: delete every jar whose
 * name looked like Sodium's, pack files included.
 */
public final class SodiumFallback {
    private SodiumFallback() {}

    private static final String TAG = "SodiumFallback";
    /** The end of the game log that is read; the error that ended the game is near the end. */
    private static final int LOG_TAIL_BYTES = 768 * 1024;
    private static final int REPORT_BYTES = 512 * 1024;
    /** A record older than this is not the start that just ended. */
    private static final long MAX_RECORD_AGE_MS = 12L * 60 * 60 * 1000;

    public enum Outcome {
        /** Nothing to do: show the game's exit as before. */
        NONE,
        /** The Projects profile: Sodium is switched off; tell the player and offer to start again. */
        PROJECTS,
        /** A profile the player made: offer to switch Sodium off (rename its jars) and start again. */
        OWN_PROFILE
    }

    public static final class Result {
        public final Outcome outcome;
        @Nullable public final File modsDir;

        Result(Outcome outcome, @Nullable File modsDir) {
            this.outcome = outcome;
            this.modsDir = modsDir;
        }
    }

    /** File names in a mods folder that look like Sodium's family (for the launch record). */
    public static List<String> sodiumJarNames(File modsDir) {
        List<String> names = new ArrayList<>();
        File[] files = modsDir.listFiles();
        if (files == null) return names;
        for (File file : files) {
            String name = file.getName().toLowerCase(Locale.ROOT);
            if (!file.isFile() || !name.endsWith(".jar")) continue;
            if (name.contains("sodium") || name.contains("embeddium") || name.contains("rubidium")
                    || name.startsWith("iris") || name.contains("oculus")) {
                names.add(file.getName());
            }
        }
        return names;
    }

    /** Looks at the start that just ended with an error (exit {@code code}); runs once per start. */
    @WorkerThread
    public static Result inspect(Context context, int code, boolean signal) {
        LaunchRecord record = LaunchRecord.read(context);
        LaunchRecord.consume(context);
        if (record == null || record.sodiumJars.isEmpty()) return new Result(Outcome.NONE, null);
        long age = System.currentTimeMillis() - record.startedAt;
        if (age < 0 || age > MAX_RECORD_AGE_MS) return new Result(Outcome.NONE, null);
        File gameDir = new File(record.gameDir);
        String log = Tools.DIR_GAME_HOME == null ? null : tail(new File(Tools.DIR_GAME_HOME, "latestlog.txt"), LOG_TAIL_BYTES);
        String crash = newest(new File(gameDir, "crash-reports"), "crash-", ".txt", record.startedAt);
        String fatal = newest(gameDir, "hs_err_pid", ".log", record.startedAt);
        SodiumCrash.Verdict verdict = SodiumCrash.diagnose(log, crash, fatal, true);
        Log.i(TAG, "game exit " + code + (signal ? " (signal)" : "") + " with Sodium installed: " + verdict);
        if (verdict == SodiumCrash.Verdict.NONE) return new Result(Outcome.NONE, null);
        if (ProjectsProfile.KEY.equals(record.profileKey)) {
            SodiumState.recordCrash(context, record.renderer);
            SodiumState.recordCrash(context, record.profileRenderer);
            return new Result(Outcome.PROJECTS, null);
        }
        return new Result(Outcome.OWN_PROFILE, new File(gameDir, "mods"));
    }

    /**
     * A profile the player made: the Sodium family's jars renamed to {@code <name>.disabled} (the
     * player renames them back to use them again). Returns how many were renamed.
     */
    @WorkerThread
    public static int disableOwn(File modsDir) {
        Set<String> family = ModIds.sodiumFamily(modsDir);
        int renamed = 0;
        for (String name : family) {
            File jar = new File(modsDir, name);
            File disabled = new File(modsDir, name + ".disabled");
            if (disabled.exists()) continue;
            if (jar.renameTo(disabled)) renamed++;
            else Log.w(TAG, "could not disable " + name);
        }
        return renamed;
    }

    /** The last {@code bytes} of a text file, or null. */
    @Nullable
    static String tail(File file, int bytes) {
        if (!file.isFile()) return null;
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            long length = in.length();
            long start = Math.max(0, length - bytes);
            byte[] buffer = new byte[(int) (length - start)];
            in.seek(start);
            in.readFully(buffer);
            return new String(buffer, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /** The newest {@code prefix…suffix} file in {@code dir} written since {@code since}, read, or null. */
    @Nullable
    static String newest(File dir, String prefix, String suffix, long since) {
        File[] files = dir.listFiles((d, name) -> name.startsWith(prefix) && name.endsWith(suffix));
        if (files == null) return null;
        File best = null;
        for (File file : files) {
            // A few seconds of slack: the clock of the file system and ours may not agree exactly.
            if (file.lastModified() + 5000 < since) continue;
            if (best == null || file.lastModified() > best.lastModified()) best = file;
        }
        if (best == null) return null;
        try {
            return new String(AtomicFiles.read(best, REPORT_BYTES), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return tail(best, REPORT_BYTES);
        }
    }
}
