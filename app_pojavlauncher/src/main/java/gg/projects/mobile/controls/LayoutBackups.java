package gg.projects.mobile.controls;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import gg.projects.mobile.content.AtomicFiles;

/**
 * CTRL-SAVE (1 Nov 2026): the controls editor's "Kaydet" writes the layout the game reads in place, and the version it
 * replaces goes first to {@code controlmap/.backups/<name>-<UTC time>.json} (a dot folder: the editor's file lists
 * never show it; the newest 5 of each layout stay), so "Geri al" can put it back. The key fix's backups (KEYS2,
 * {@link KeyFixer}) work the same way. Plain Java: the tests run it on the JVM.
 */
public final class LayoutBackups {
    private LayoutBackups() {}

    /** The folder beside the layouts that holds the copies taken before a save. */
    public static final String DIR = ".backups";
    static final int KEPT = 5;
    private static final long MAX_LAYOUT_BYTES = 4L << 20;
    private static final Object LOCK = new Object();
    /** Saves and undos in the order they were asked for, off the main thread. */
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    /** Runs {@code job} on the layouts' own background thread, after the jobs before it. */
    public static void run(Runnable job) {
        EXECUTOR.execute(job);
    }

    /** What a save did, for its "Geri al". */
    public static final class Saved {
        public final File target;
        /** The version the save replaced; null when there was none or nothing changed. */
        public final File backup;
        /** True when the save made the file (there was none before). */
        public final boolean created;
        /** True when the file already held exactly these bytes: nothing was written. */
        public final boolean unchanged;

        Saved(File target, File backup, boolean created, boolean unchanged) {
            this.target = target;
            this.backup = backup;
            this.created = created;
            this.unchanged = unchanged;
        }

        /** True when "Geri al" has something to bring back. */
        public boolean undoable() {
            return backup != null || created;
        }
    }

    /**
     * Writes {@code bytes} to {@code target} (write-to-temporary-then-rename), after copying the version there into
     * {@link #DIR}. A file that already holds these bytes is left alone.
     */
    public static Saved save(File target, byte[] bytes, long now) throws IOException {
        synchronized (LOCK) {
            if (!target.isFile()) {
                AtomicFiles.write(target, bytes);
                return new Saved(target, null, true, false);
            }
            byte[] before = AtomicFiles.read(target, MAX_LAYOUT_BYTES);
            if (Arrays.equals(before, bytes)) return new Saved(target, null, false, true);
            File dir = backupDir(target);
            if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) throw new IOException("cannot create " + dir);
            String base = baseName(target);
            File backup = new File(dir, newBackupName(dir, base, now));
            AtomicFiles.write(backup, before);
            AtomicFiles.write(target, bytes);
            prune(dir, base);
            return new Saved(target, backup, false, false);
        }
    }

    /**
     * Takes a save back: the version before it returns from its copy (the copy goes once it is used), or a file the
     * save made is removed. False when there is nothing to bring back (the copy is gone): nothing changes then.
     */
    public static boolean undo(Saved saved) throws IOException {
        synchronized (LOCK) {
            if (saved.backup == null) {
                if (!saved.created) return false;
                return !saved.target.exists() || saved.target.delete();
            }
            if (!saved.backup.isFile()) return false;
            AtomicFiles.write(saved.target, AtomicFiles.read(saved.backup, MAX_LAYOUT_BYTES));
            //noinspection ResultOfMethodCallIgnored
            saved.backup.delete();
            return true;
        }
    }

    static File backupDir(File target) {
        return new File(target.getAbsoluteFile().getParentFile(), DIR);
    }

    /** The layout's name without ".json": "default" for default.json. */
    static String baseName(File target) {
        String name = target.getName();
        return name.toLowerCase(Locale.ROOT).endsWith(".json") ? name.substring(0, name.length() - 5) : name;
    }

    private static Pattern backupPattern(String base) {
        return Pattern.compile(Pattern.quote(base) + "-(\\d{8}-\\d{6})(?:-(\\d{1,3}))?\\.json");
    }

    /**
     * A name for a new copy of {@code base} in {@code dir}: the UTC time, so names sort by time, with a counter above
     * every copy of the same second.
     */
    static String newBackupName(File dir, String base, long now) {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        String stamp = format.format(new Date(now));
        Pattern pattern = backupPattern(base);
        int last = 0;
        String[] names = dir.list();
        if (names != null) {
            for (String name : names) {
                Matcher m = pattern.matcher(name);
                if (!m.matches() || !m.group(1).equals(stamp)) continue;
                last = Math.max(last, m.group(2) == null ? 1 : Integer.parseInt(m.group(2)));
            }
        }
        return last == 0 ? base + "-" + stamp + ".json" : base + "-" + stamp + "-" + (last + 1) + ".json";
    }

    /** All but the newest {@link #KEPT} copies of {@code base} go; other layouts' copies stay. Failures only leave files. */
    static void prune(File dir, String base) {
        String[] names = dir.list();
        if (names == null) return;
        final Pattern pattern = backupPattern(base);
        List<String> copies = new ArrayList<>();
        for (String name : names) if (pattern.matcher(name).matches()) copies.add(name);
        Collections.sort(copies, (a, b) -> {
            Matcher x = pattern.matcher(a);
            Matcher y = pattern.matcher(b);
            x.matches();
            y.matches();
            int byStamp = x.group(1).compareTo(y.group(1));
            if (byStamp != 0) return byStamp;
            int cx = x.group(2) == null ? 1 : Integer.parseInt(x.group(2));
            int cy = y.group(2) == null ? 1 : Integer.parseInt(y.group(2));
            return Integer.compare(cx, cy);
        });
        for (int i = 0; i < copies.size() - KEPT; i++) {
            //noinspection ResultOfMethodCallIgnored
            new File(dir, copies.get(i)).delete();
        }
    }
}
