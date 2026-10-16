package gg.projects.mobile.content;

import androidx.annotation.Keep;

import org.bouncycastle.util.encoders.Base64;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The last verified Discord feed on disk, kept apart from the content cache:
 * <pre>
 * current.json         exact feed.json + feed.json.sig bytes (base64), mirror, fetch time,
 *                      covers of the feed it replaced (kept by the cleanup)
 * media/&lt;sha256&gt;.ext  covers (verified on download, then trusted by size)
 * </pre>
 * Nothing here is trusted on its own: the signature is verified again on every read against the
 * keys the current config delegates.
 */
public final class FeedStore {
    private final File root;
    private final File mediaDir;

    @Keep
    static final class CurrentFile {
        String feed;
        String signature;
        String mirror;
        long fetchedAt;
        List<String> previousMedia;
    }

    public static final class Current {
        public final byte[] feed;
        public final byte[] signature;
        public final String mirror;
        public final long fetchedAt;
        /** SHA-256 names of the covers of the feed this one replaced. */
        public final Set<String> previousMedia;

        public Current(byte[] feed, byte[] signature, String mirror, long fetchedAt, Set<String> previousMedia) {
            this.feed = feed;
            this.signature = signature;
            this.mirror = mirror;
            this.fetchedAt = fetchedAt;
            this.previousMedia = previousMedia;
        }
    }

    public FeedStore(File root) {
        this.root = root;
        this.mediaDir = new File(root, "media");
    }

    private File currentFile() {
        return new File(root, "current.json");
    }

    public boolean exists() {
        return root.exists();
    }

    /** The stored feed bytes, or null when missing or unreadable. */
    public Current readCurrent() {
        File file = currentFile();
        if (!file.isFile()) return null;
        try {
            CurrentFile stored = ContentJson.parse(AtomicFiles.read(file, 2L << 20), CurrentFile.class, "feed current.json");
            Set<String> previous = new HashSet<>();
            if (stored.previousMedia != null) previous.addAll(stored.previousMedia);
            return new Current(Base64.decode(stored.feed), Base64.decode(stored.signature), stored.mirror,
                    stored.fetchedAt, previous);
        } catch (IOException | ContentException | RuntimeException e) {
            return null;
        }
    }

    public void writeCurrent(Current current) throws IOException {
        CurrentFile stored = new CurrentFile();
        stored.feed = Base64.toBase64String(current.feed);
        stored.signature = Base64.toBase64String(current.signature);
        stored.mirror = current.mirror;
        stored.fetchedAt = current.fetchedAt;
        stored.previousMedia = new ArrayList<>(current.previousMedia);
        AtomicFiles.write(currentFile(), ContentJson.GSON.toJson(stored).getBytes(StandardCharsets.UTF_8));
    }

    /** The cached cover when its size matches, else null. */
    public File mediaFile(MediaRef ref) {
        File file = new File(mediaDir, ref.cacheFileName());
        return file.isFile() && file.length() == ref.size ? file : null;
    }

    public File writeMedia(MediaRef ref, byte[] bytes) throws IOException {
        File file = new File(mediaDir, ref.cacheFileName());
        AtomicFiles.write(file, bytes);
        return file;
    }

    /** Deletes cached covers whose SHA-256 is not in {@code keep}. */
    public void pruneMedia(Set<String> keep) {
        File[] files = mediaDir.listFiles();
        if (files == null) return;
        for (File file : files) {
            String name = file.getName();
            int dot = name.indexOf('.');
            String sha = dot > 0 ? name.substring(0, dot) : name;
            //noinspection ResultOfMethodCallIgnored
            if (!keep.contains(sha)) file.delete();
        }
    }

    /** Deletes the whole cache (the config turned the feed off). */
    public void clear() {
        delete(root);
    }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
