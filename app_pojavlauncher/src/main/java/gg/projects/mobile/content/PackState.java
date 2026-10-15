package gg.projects.mobile.content;

import androidx.annotation.Keep;

import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/**
 * What the launcher installed into a game directory: {@code <game dir>/.projects/pack-state.json}
 * (same shape as the desktop launcher's).
 */
@Keep
public final class PackState {
    public static final String FILE = "pack-state.json";

    public String packId = "";
    public String versionId = "";
    /** Relative path to the file as we last installed it. */
    public Map<String, InstalledFile> files = new TreeMap<>();
    /** Optional group id to enabled, as chosen at the last sync. */
    public Map<String, Boolean> groups = new TreeMap<>();

    @Keep
    public static final class InstalledFile {
        public String sha1;
        public String policy;

        public InstalledFile() {}

        public InstalledFile(String sha1, String policy) {
            this.sha1 = sha1;
            this.policy = policy;
        }
    }

    public static File path(File gameDir) {
        return new File(new File(gameDir, ContentPaths.LAUNCHER_DIR), FILE);
    }

    /** The recorded state, or null when there is none or it is corrupt (then nothing is deleted). */
    public static PackState load(File gameDir) {
        File file = path(gameDir);
        if (!file.isFile()) return null;
        try {
            PackState state = ContentJson.parse(AtomicFiles.read(file, 16L << 20), PackState.class, FILE);
            if (state.files == null) state.files = new TreeMap<>();
            if (state.groups == null) state.groups = new TreeMap<>();
            return state;
        } catch (IOException | ContentException e) {
            return null;
        }
    }

    public void save(File gameDir) throws IOException {
        String json = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(this);
        AtomicFiles.write(path(gameDir), json.getBytes(StandardCharsets.UTF_8));
    }
}
