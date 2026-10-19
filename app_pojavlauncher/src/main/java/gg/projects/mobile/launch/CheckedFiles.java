package gg.projects.mobile.launch;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import gg.projects.mobile.content.AtomicFiles;
import gg.projects.mobile.content.Hashes;

/**
 * What the start's file check already knows (M16). Upstream hashed every game file (assets,
 * libraries, the client jar: some 600 MB) before every start. Now a file whose SHA-1 matched is
 * remembered with its {@link Stamp} (size, modification and status-change times, inode), and while
 * it still has exactly that stamp it is not read again. Any write changes the status-change time,
 * which no app can set back, so a file that was replaced or edited, even with its size and
 * modification time restored, is hashed again; so is every new download. Bit rot that changes
 * nothing of that goes unseen, as with the desktop's size check before a start.
 *
 * <p>Kept in the app's own storage as one line per file ({@code <sha1> <size> <mtime> <ctime>
 * <inode> <day> <path>}, paths under the game folder written as {@code ~/...}); a line that cannot
 * be read is dropped, and a store that cannot be read is an empty one (everything is hashed once).
 * Files not seen for {@link #KEEP_DAYS} days are dropped when it is saved. Safe for the download
 * pool's threads. Plain Java, JVM-tested.
 */
public final class CheckedFiles {
    static final String HEADER = "projects-checked-files 1";
    /** A file of a version not started for this long is forgotten (hashed again if it comes back). */
    static final int KEEP_DAYS = 60;
    private static final long MAX_STORE_BYTES = 16L << 20;

    /** What identifies a file's content without reading it. Zero means unknown (never trusted). */
    public static final class Stamp {
        public final long size;
        public final long modified;
        public final long changed;
        public final long inode;

        public Stamp(long size, long modified, long changed, long inode) {
            this.size = size;
            this.modified = modified;
            this.changed = changed;
            this.inode = inode;
        }

        /** Usable to skip a hash: every time is known. */
        boolean trusted() {
            return size >= 0 && modified != 0 && changed != 0;
        }

        boolean same(Stamp other) {
            return other != null && size == other.size && modified == other.modified && changed == other.changed
                    && inode == other.inode;
        }

        @Override
        public String toString() {
            return size + " " + modified + " " + changed + " " + inode;
        }
    }

    private static final class Entry {
        final Stamp stamp;
        final String sha1;
        volatile int seen;

        Entry(Stamp stamp, String sha1, int seen) {
            this.stamp = stamp;
            this.sha1 = sha1;
            this.seen = seen;
        }
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final String base;
    private final int today;
    private volatile boolean changed;

    private CheckedFiles(File base, int today) {
        this.base = base == null ? null : base.getAbsolutePath();
        this.today = today;
    }

    /** An empty store (nothing known). */
    public static CheckedFiles empty(File base, int today) {
        return new CheckedFiles(base, today);
    }

    /**
     * Reads {@code store}; {@code base} is the folder whose files are written relative to it,
     * {@code today} the day number (days since 1970) entries are marked seen with.
     */
    public static CheckedFiles load(File store, File base, int today) {
        CheckedFiles files = new CheckedFiles(base, today);
        if (store == null || !store.isFile()) return files;
        String text;
        try {
            text = new String(AtomicFiles.read(store, MAX_STORE_BYTES), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return files;
        }
        String[] lines = text.split("\n");
        if (lines.length == 0 || !lines[0].trim().equals(HEADER)) return files;
        for (int i = 1; i < lines.length; i++) files.parse(lines[i]);
        return files;
    }

    private void parse(String line) {
        String[] parts = line.split(" ", 7);
        if (parts.length != 7) return;
        String sha1 = Hashes.normalizeHex(parts[0], 40);
        if (sha1 == null || parts[6].isEmpty()) return;
        try {
            Stamp stamp = new Stamp(Long.parseLong(parts[1]), Long.parseLong(parts[2]), Long.parseLong(parts[3]),
                    Long.parseLong(parts[4]));
            int seen = Integer.parseInt(parts[5]);
            if (!stamp.trusted()) return;
            entries.put(absolute(parts[6]), new Entry(stamp, sha1, seen));
        } catch (NumberFormatException e) {
            // A broken line is a file that is hashed again.
        }
    }

    /**
     * The SHA-1 {@code file} had when it was checked, as long as it still has the same
     * {@code stamp}; null when it is not known or changed since (then it has to be hashed).
     */
    public String known(File file, Stamp stamp) {
        if (file == null || stamp == null || !stamp.trusted()) return null;
        Entry entry = entries.get(file.getAbsolutePath());
        if (entry == null || !entry.stamp.same(stamp)) return null;
        if (entry.seen != today) {
            entry.seen = today;
            changed = true;
        }
        return entry.sha1;
    }

    /** Remembers that {@code file}, with {@code stamp} (taken before it was read), has {@code sha1}. */
    public void put(File file, Stamp stamp, String sha1) {
        String hex = Hashes.normalizeHex(sha1, 40);
        if (file == null || stamp == null || !stamp.trusted() || hex == null) return;
        Entry old = entries.put(file.getAbsolutePath(), new Entry(stamp, hex, today));
        if (old == null || !old.stamp.same(stamp) || !old.sha1.equals(hex) || old.seen != today) changed = true;
    }

    /** Forgets {@code file} (it failed its check or is being downloaded again). */
    public void forget(File file) {
        if (file != null && entries.remove(file.getAbsolutePath()) != null) changed = true;
    }

    public int size() {
        return entries.size();
    }

    /**
     * Writes the store when anything changed, without the files not seen for {@link #KEEP_DAYS}
     * days. Returns true when it wrote.
     */
    public boolean save(File store) throws IOException {
        if (!changed && store.isFile()) return false;
        List<String> keys = new ArrayList<>(entries.keySet());
        Collections.sort(keys);
        StringBuilder out = new StringBuilder(64 + keys.size() * 120);
        out.append(HEADER).append('\n');
        for (String path : keys) {
            Entry entry = entries.get(path);
            if (entry == null || today - entry.seen > KEEP_DAYS) continue;
            out.append(entry.sha1).append(' ').append(entry.stamp).append(' ').append(entry.seen).append(' ')
                    .append(relative(path)).append('\n');
        }
        File parent = store.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("cannot create " + parent);
        AtomicFiles.write(store, out.toString().getBytes(StandardCharsets.UTF_8));
        changed = false;
        return true;
    }

    private String relative(String path) {
        if (base != null && path.startsWith(base + File.separator)) return "~" + path.substring(base.length());
        return path;
    }

    private String absolute(String path) {
        if (base != null && path.startsWith("~" + File.separator)) return base + path.substring(1);
        return path;
    }

    /** The SHA-1 (lowercase hex) of {@code file}, read through {@code buffer}. */
    public static String sha1(File file, byte[] buffer) throws IOException {
        return Hashes.fileHex(file, "SHA-1", buffer).toLowerCase(Locale.ROOT);
    }

    /** Days since 1970 of {@code millis} (UTC), the stamp entries are kept by. */
    public static int day(long millis) {
        return (int) (millis / 86_400_000L);
    }
}
