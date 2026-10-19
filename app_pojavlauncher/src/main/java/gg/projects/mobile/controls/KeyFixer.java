package gg.projects.mobile.controls;

import android.app.ActivityManager;
import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import gg.projects.mobile.content.AtomicFiles;
import gg.projects.mobile.content.ContentPaths;

/**
 * M17: the key fix ({@link KeyFix}) on the phone, with the desktop's backups (KEYS2): before the
 * fix, options.txt is copied to {@code <game folder>/.projects/options-backups/options-<UTC time>.txt}
 * (the launcher's own folder, which a pack never writes; the newest 5 stay), and "Geri al" puts it
 * back from there.
 *
 * <p>The file is rewritten only while the game is not running: a running game keeps its keys in
 * memory and writes them back whenever it saves its options, and upstream's option helper rewrites
 * the file from its own copy before a start. A fix asked for in the game (the MODLAR key's reason
 * line) therefore waits in a small file of the app's own storage and is made once the game is
 * closed: when the home screen opens, or right before the next start in the game process,
 * whichever comes first.
 */
public final class KeyFixer {
    private KeyFixer() {}

    private static final String TAG = "ProjectsKeyFix";
    public static final String OPTIONS_NAME = "options.txt";
    /** Where the copies taken before a fix go, inside the launcher's own folder of the game folder. */
    public static final String BACKUP_DIR = "options-backups";
    private static final int KEPT_BACKUPS = 5;
    private static final Pattern BACKUP_NAME = Pattern.compile("options-(\\d{8}-\\d{6})(?:-(\\d{1,3}))?\\.txt");
    /** A fix asked for while the game ran: the game folder's path. */
    private static final String QUEUE_FILE = "projects/key-fix-queued";
    private static final long MAX_OPTIONS_BYTES = 1L << 20;

    private static volatile List<KeyFix.Pair> sPairs;
    /** One key-fix file operation at a time in a process (fix, undo, queue, a queued fix made). */
    private static final Object LOCK = new Object();
    /** The background thread of the key fix's file work: in order, one after another. */
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    /** Runs {@code job} on the key fix's own background thread, after the jobs before it. */
    public static void run(Runnable job) {
        EXECUTOR.execute(job);
    }

    /** What a fix did, for its undo. */
    public static final class Applied {
        public final File options;
        /** The copy taken before the fix. */
        public final File backup;
        public final List<KeyFix.Change> changes;

        Applied(File options, File backup, List<KeyFix.Change> changes) {
            this.options = options;
            this.backup = backup;
            this.changes = Collections.unmodifiableList(changes);
        }
    }

    /** The clashes the shared table solves ({@code assets/key-bindings.json}), read once per process. */
    static List<KeyFix.Pair> pairs(Context context) {
        List<KeyFix.Pair> pairs = sPairs;
        if (pairs != null) return pairs;
        try (InputStream in = context.getAssets().open("key-bindings.json")) {
            pairs = KeyFix.pairs(new String(AtomicFiles.readBounded(in, 64 * 1024), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "key table unavailable", e);
            pairs = Collections.emptyList();
        }
        sPairs = pairs;
        return pairs;
    }

    /** What the fix would change in {@code bindings} (a running game's view, {@link ModKeysLive}). */
    public static List<KeyFix.Change> plan(Context context, Map<String, String> bindings) {
        return KeyFix.plan(bindings, pairs(context));
    }

    /** What the fix would change in {@code gameDir}'s options.txt now; empty without a file or a clash. */
    @WorkerThread
    public static List<KeyFix.Change> plan(Context context, File gameDir) {
        File file = new File(gameDir, OPTIONS_NAME);
        if (!file.isFile()) return Collections.emptyList();
        try {
            return KeyFix.plan(KeyFix.bindings(read(file)), pairs(context));
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "options.txt not read", e);
            return Collections.emptyList();
        }
    }

    /**
     * Fixes {@code gameDir}'s options.txt now: a copy first ({@link #BACKUP_DIR}), then only the
     * clashing lines change. Null when nothing needed fixing. The caller makes sure the game is not
     * running ({@link #gameRunning}).
     */
    @WorkerThread
    @Nullable
    public static Applied apply(Context context, File gameDir) throws IOException {
        List<KeyFix.Pair> pairs = pairs(context);
        synchronized (LOCK) {
            File file = new File(gameDir, OPTIONS_NAME);
            if (!file.isFile()) return null;
            byte[] bytes = AtomicFiles.read(file, MAX_OPTIONS_BYTES);
            String text = new String(bytes, StandardCharsets.UTF_8);
            List<KeyFix.Change> changes = KeyFix.plan(KeyFix.bindings(text), pairs);
            if (changes.isEmpty()) return null;
            File dir = backupDir(gameDir);
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
            File backup = new File(dir, newBackupName(dir, System.currentTimeMillis()));
            AtomicFiles.write(backup, bytes);
            AtomicFiles.write(file, KeyFix.apply(text, changes).getBytes(StandardCharsets.UTF_8));
            prune(dir);
            Log.i(TAG, "fixed " + changes + " in " + file + "; backup " + backup.getName());
            return new Applied(file, backup, changes);
        }
    }

    /**
     * Takes a fix back from its backup ({@link KeyFix#undo}; a file that is gone meanwhile comes back
     * whole). The backup goes once it is used. False when the backup is gone: nothing changes then.
     */
    @WorkerThread
    public static boolean undo(Context context, Applied applied) throws IOException {
        List<KeyFix.Pair> pairs = pairs(context);
        synchronized (LOCK) {
            if (!applied.backup.isFile()) return false;
            String saved = read(applied.backup);
            String restored;
            String current = null;
            if (applied.options.isFile()) {
                current = read(applied.options);
                restored = KeyFix.undo(current, saved, pairs);
            } else {
                restored = saved;
            }
            if (!restored.equals(current)) AtomicFiles.write(applied.options, restored.getBytes(StandardCharsets.UTF_8));
            if (!applied.backup.delete()) Log.w(TAG, "used backup not removed: " + applied.backup.getName());
            Log.i(TAG, "fix taken back in " + applied.options);
            return true;
        }
    }

    // ---- backups ----

    static File backupDir(File gameDir) {
        return new File(new File(gameDir, ContentPaths.LAUNCHER_DIR), BACKUP_DIR);
    }

    /**
     * A name for a new backup in {@code dir}: the UTC time, so names sort by time, with a counter
     * above every backup of the same second (the desktop's {@code new_backup_name}).
     */
    static String newBackupName(File dir, long now) {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        String stamp = format.format(new Date(now));
        int last = 0;
        String[] names = dir.list();
        if (names != null) {
            for (String name : names) {
                Matcher m = BACKUP_NAME.matcher(name);
                if (!m.matches() || !m.group(1).equals(stamp)) continue;
                last = Math.max(last, m.group(2) == null ? 1 : Integer.parseInt(m.group(2)));
            }
        }
        return last == 0 ? "options-" + stamp + ".txt" : "options-" + stamp + "-" + (last + 1) + ".txt";
    }

    /** All but the newest {@link #KEPT_BACKUPS} backups go; failures only leave files. */
    static void prune(File dir) {
        String[] names = dir.list();
        if (names == null) return;
        List<String> backups = new ArrayList<>();
        for (String name : names) if (BACKUP_NAME.matcher(name).matches()) backups.add(name);
        Collections.sort(backups, (a, b) -> {
            Matcher x = BACKUP_NAME.matcher(a);
            Matcher y = BACKUP_NAME.matcher(b);
            x.matches();
            y.matches();
            int byStamp = x.group(1).compareTo(y.group(1));
            if (byStamp != 0) return byStamp;
            int cx = x.group(2) == null ? 1 : Integer.parseInt(x.group(2));
            int cy = y.group(2) == null ? 1 : Integer.parseInt(y.group(2));
            return Integer.compare(cx, cy);
        });
        for (int i = 0; i < backups.size() - KEPT_BACKUPS; i++) {
            if (!new File(dir, backups.get(i)).delete()) Log.w(TAG, "old backup not removed: " + backups.get(i));
        }
    }

    // ---- a fix asked for while the game runs ----

    private static File queueFile(Context context) {
        return new File(context.getFilesDir(), QUEUE_FILE);
    }

    /** The game process: the player asked for the fix; it is made once the game is closed. */
    public static void queue(Context context, File gameDir) throws IOException {
        synchronized (LOCK) {
            File file = queueFile(context);
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("cannot create " + parent);
            AtomicFiles.write(file, path(gameDir).getBytes(StandardCharsets.UTF_8));
            Log.i(TAG, "fix queued for " + gameDir);
        }
    }

    /** The player took the queued fix back before it was made. */
    public static void unqueue(Context context) {
        synchronized (LOCK) {
            File file = queueFile(context);
            if (file.exists() && !file.delete()) Log.w(TAG, "queued fix not removed");
        }
    }

    /** True while a fix of {@code gameDir} waits for the game to close. */
    public static boolean queued(Context context, File gameDir) {
        File file = queueFile(context);
        if (!file.isFile()) return false;
        try {
            String path = new String(AtomicFiles.read(file, 4096), StandardCharsets.UTF_8).trim();
            return path.equals(path(gameDir));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Makes a queued fix of {@code gameDir} now (the game is closed). Returns what it changed, or
     * null when nothing was queued or nothing clashes any more (the player rebound the keys in the
     * game meanwhile); the queue is cleared either way.
     */
    @WorkerThread
    @Nullable
    public static Applied applyQueued(Context context, File gameDir) {
        synchronized (LOCK) {
            if (!queued(context, gameDir)) return null;
            // Claimed by a rename, atomic across the launcher's and the game's processes: only the
            // process whose rename wins makes the fix.
            File queue = queueFile(context);
            File claimed = new File(queue.getParentFile(), "key-fix-claimed-" + android.os.Process.myPid());
            if (!queue.renameTo(claimed)) return null;
            try {
                return apply(context, gameDir);
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "queued fix not made", e);
                return null;
            } finally {
                if (!claimed.delete()) Log.w(TAG, "claimed queue not removed");
            }
        }
    }

    /**
     * True while the game's process runs: it holds its keys in memory, so options.txt is not
     * rewritten under it. Android lists an app's own processes only, so this asks about ours.
     */
    public static boolean gameRunning(Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) return false;
        List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
        if (processes == null) return false;
        String game = context.getPackageName() + ":game";
        for (ActivityManager.RunningAppProcessInfo process : processes) {
            if (game.equals(process.processName)) return true;
        }
        return false;
    }

    /** One spelling of a folder for both processes ("./custom_instances/projects" resolved). */
    private static String path(File dir) {
        try {
            return dir.getCanonicalPath();
        } catch (IOException e) {
            return dir.getAbsolutePath();
        }
    }

    private static String read(File file) throws IOException {
        return new String(AtomicFiles.read(file, MAX_OPTIONS_BYTES), StandardCharsets.UTF_8);
    }
}
