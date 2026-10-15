package gg.projects.mobile.content;

import androidx.annotation.Keep;

import org.bouncycastle.util.encoders.Base64;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * The last verified snapshot on disk:
 * <pre>
 * current.json         exact index.json + index.json.sig bytes (base64), fetch time, mirror
 * docs/&lt;sha256&gt;.json  config, news, pack manifests (verified on every read)
 * media/&lt;sha256&gt;.ext  covers (verified on download, then trusted by size)
 * </pre>
 * Nothing here is trusted on its own: callers re-verify the signature of {@code current.json}.
 */
public final class ContentStore {
    private final File root;
    private final File docsDir;
    private final File mediaDir;

    @Keep
    static final class CurrentFile {
        String index;
        String signature;
        long fetchedAt;
        String mirror;
    }

    public static final class Current {
        public final byte[] index;
        public final byte[] signature;
        public final long fetchedAt;
        public final String mirror;

        public Current(byte[] index, byte[] signature, long fetchedAt, String mirror) {
            this.index = index;
            this.signature = signature;
            this.fetchedAt = fetchedAt;
            this.mirror = mirror;
        }
    }

    public ContentStore(File root) {
        this.root = root;
        this.docsDir = new File(root, "docs");
        this.mediaDir = new File(root, "media");
    }

    private File currentFile() {
        return new File(root, "current.json");
    }

    /** The stored snapshot bytes, or null when missing or unreadable. */
    public Current readCurrent() {
        File file = currentFile();
        if (!file.isFile()) return null;
        try {
            CurrentFile stored = ContentJson.parse(AtomicFiles.read(file, 4L << 20), CurrentFile.class, "current.json");
            byte[] index = Base64.decode(stored.index);
            byte[] signature = Base64.decode(stored.signature);
            return new Current(index, signature, stored.fetchedAt, stored.mirror);
        } catch (IOException | ContentException | RuntimeException e) {
            return null;
        }
    }

    public void writeCurrent(Current current) throws IOException {
        CurrentFile stored = new CurrentFile();
        stored.index = Base64.toBase64String(current.index);
        stored.signature = Base64.toBase64String(current.signature);
        stored.fetchedAt = current.fetchedAt;
        stored.mirror = current.mirror;
        AtomicFiles.write(currentFile(), ContentJson.GSON.toJson(stored).getBytes(StandardCharsets.UTF_8));
    }

    /** A cached document, verified by size and SHA-256; null when missing or corrupt. */
    public byte[] readDoc(RootIndex.DocRef ref) {
        String sha = ref.sha256();
        if (sha == null) return null;
        File file = new File(docsDir, sha + ".json");
        if (!file.isFile()) return null;
        try {
            byte[] bytes = AtomicFiles.read(file, ContentConfig.MAX_DOCUMENT_BYTES);
            if (bytes.length == ref.size && sha.equals(Hashes.sha256Hex(bytes))) return bytes;
        } catch (IOException ignored) {
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
        return null;
    }

    public void writeDoc(RootIndex.DocRef ref, byte[] bytes) throws IOException {
        AtomicFiles.write(new File(docsDir, ref.sha256() + ".json"), bytes);
    }

    /** The cached media file when its size matches, else null. */
    public File mediaFile(MediaRef ref) {
        File file = new File(mediaDir, ref.cacheFileName());
        return file.isFile() && file.length() == ref.size ? file : null;
    }

    public File writeMedia(MediaRef ref, byte[] bytes) throws IOException {
        File file = new File(mediaDir, ref.cacheFileName());
        AtomicFiles.write(file, bytes);
        return file;
    }

    /** Deletes cached documents and media whose SHA-256 is not in {@code keep}. */
    public void prune(Set<String> keep) {
        for (File dir : new File[]{docsDir, mediaDir}) {
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File file : files) {
                String name = file.getName();
                int dot = name.indexOf('.');
                String sha = dot > 0 ? name.substring(0, dot) : name;
                //noinspection ResultOfMethodCallIgnored
                if (!keep.contains(sha)) file.delete();
            }
        }
    }
}
