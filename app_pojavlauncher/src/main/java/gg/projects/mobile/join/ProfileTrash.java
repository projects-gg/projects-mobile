package gg.projects.mobile.join;

import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import gg.projects.mobile.content.AtomicFiles;

/**
 * MOB2: the profiles the player deleted, kept 30 days so "Geri al" and "Resmî paketi kur" bring them
 * back with the player's own settings (renderer, Java, icon, controls), as the desktop's trash does.
 * An entry is a profile's key and its JSON as launcher_profiles.json stored it, with the time it was
 * deleted. Entries older than 30 days count as gone and are dropped whenever the trash is written
 * (lazily: nothing runs in the background). Game folders are never touched here: a deleted profile's
 * worlds and mods stay where they are. Plain Java (java.io and Gson), JVM-tested.
 */
public final class ProfileTrash {
    /** How long a deleted profile can come back (the desktop's trash keeps 30 days too). */
    public static final long KEEP_MS = 30L * 24 * 60 * 60 * 1000;
    /** At most this many entries, the newest (a custom icon makes an entry some tens of KB). */
    static final int MAX_ENTRIES = 24;
    /** Next to launcher_profiles.json. */
    public static final String FILE_NAME = "projects_profile_trash.json";
    private static final long MAX_BYTES = 4L * 1024 * 1024;
    private static final Gson GSON = new Gson();
    /** One reader or writer of the trash at a time (the editor, "Geri al" and a join may meet). */
    private static final Object LOCK = new Object();

    /** One deleted profile. */
    public static final class Entry {
        public final String key;
        /** The profile as launcher_profiles.json stored it. */
        public final JsonObject profile;
        /** When it was deleted (epoch milliseconds). */
        public final long deletedAt;

        Entry(String key, JsonObject profile, long deletedAt) {
            this.key = key;
            this.profile = profile;
            this.deletedAt = deletedAt;
        }

        /** Older than {@link #KEEP_MS} at {@code now}. */
        public boolean expired(long now) {
            return now - deletedAt >= KEEP_MS;
        }
    }

    private final File mFile;

    public ProfileTrash(File file) {
        mFile = file;
    }

    /** The trash beside {@code profilesFile} (launcher_profiles.json): it goes wherever that file goes. */
    public static ProfileTrash beside(File profilesFile) {
        return new ProfileTrash(new File(profilesFile.getAbsoluteFile().getParentFile(), FILE_NAME));
    }

    public File file() {
        return mFile;
    }

    /**
     * Keeps {@code profile} under {@code key}, deleted at {@code now}. An entry the key had already is
     * replaced: the newest delete is the one that comes back.
     */
    public void put(String key, JsonObject profile, long now) throws IOException {
        if (key == null || profile == null) throw new IllegalArgumentException("key and profile are needed");
        synchronized (LOCK) {
            List<Entry> entries = live(read(), now);
            drop(entries, key);
            entries.add(0, new Entry(key, profile, now));
            while (entries.size() > MAX_ENTRIES) entries.remove(entries.size() - 1);
            write(entries);
        }
    }

    /** {@code key}'s entry, or null when there is none or it is older than 30 days. */
    @Nullable
    public Entry find(String key, long now) {
        if (key == null) return null;
        synchronized (LOCK) {
            for (Entry entry : live(read(), now)) {
                if (entry.key.equals(key)) return entry;
            }
            return null;
        }
    }

    /** Whether {@code key} can come back from the trash at {@code now}. */
    public boolean contains(String key, long now) {
        return find(key, now) != null;
    }

    /**
     * Takes {@code key}'s entry out (once its profile is back). Expired entries leave with it. Returns
     * true when the key had a live entry.
     */
    public boolean remove(String key, long now) throws IOException {
        synchronized (LOCK) {
            List<Entry> all = read();
            List<Entry> entries = live(all, now);
            boolean had = drop(entries, key);
            if (had || entries.size() != all.size()) write(entries);
            return had;
        }
    }

    /** Drops the entries older than 30 days; returns how many went. */
    public int purge(long now) throws IOException {
        synchronized (LOCK) {
            List<Entry> all = read();
            List<Entry> entries = live(all, now);
            int gone = all.size() - entries.size();
            if (gone > 0) write(entries);
            return gone;
        }
    }

    /** The live entries, newest first. */
    public List<Entry> entries(long now) {
        synchronized (LOCK) {
            return Collections.unmodifiableList(live(read(), now));
        }
    }

    private static List<Entry> live(List<Entry> entries, long now) {
        List<Entry> live = new ArrayList<>(entries.size());
        for (Entry entry : entries) if (!entry.expired(now)) live.add(entry);
        return live;
    }

    private static boolean drop(List<Entry> entries, String key) {
        boolean dropped = false;
        for (Iterator<Entry> it = entries.iterator(); it.hasNext(); ) {
            if (it.next().key.equals(key)) {
                it.remove();
                dropped = true;
            }
        }
        return dropped;
    }

    /**
     * The entries on disk, newest first. A missing, oversized or unreadable file reads as an empty
     * trash (the next write replaces it): the trash only ever adds a way back, it never blocks one.
     */
    private List<Entry> read() {
        List<Entry> entries = new ArrayList<>();
        if (!mFile.isFile()) return entries;
        try {
            String text = new String(AtomicFiles.read(mFile, MAX_BYTES), StandardCharsets.UTF_8);
            JsonElement root = JsonParser.parseString(text);
            if (!root.isJsonObject()) return entries;
            JsonElement list = root.getAsJsonObject().get("entries");
            if (list == null || !list.isJsonArray()) return entries;
            for (JsonElement item : list.getAsJsonArray()) {
                Entry entry = entryOf(item);
                if (entry != null) entries.add(entry);
            }
        } catch (IOException | JsonParseException | IllegalStateException e) {
            return new ArrayList<>();
        }
        Collections.sort(entries, (a, b) -> Long.compare(b.deletedAt, a.deletedAt));
        return entries;
    }

    /** One entry of the file, or null when it lacks its key, its profile or a time (the others still count). */
    @Nullable
    private static Entry entryOf(JsonElement item) {
        if (item == null || !item.isJsonObject()) return null;
        JsonObject object = item.getAsJsonObject();
        JsonElement key = object.get("key");
        JsonElement profile = object.get("profile");
        JsonElement deletedAt = object.get("deletedAt");
        if (key == null || !key.isJsonPrimitive() || profile == null || !profile.isJsonObject()
                || deletedAt == null || !deletedAt.isJsonPrimitive()) return null;
        try {
            return new Entry(key.getAsString(), profile.getAsJsonObject(), deletedAt.getAsLong());
        } catch (NumberFormatException | IllegalStateException | UnsupportedOperationException e) {
            return null;
        }
    }

    private void write(List<Entry> entries) throws IOException {
        if (entries.isEmpty()) {
            if (mFile.exists() && !mFile.delete() && mFile.exists()) throw new IOException("cannot delete " + mFile);
            return;
        }
        JsonArray list = new JsonArray();
        for (Entry entry : entries) {
            JsonObject object = new JsonObject();
            object.addProperty("key", entry.key);
            object.addProperty("deletedAt", entry.deletedAt);
            object.add("profile", entry.profile);
            list.add(object);
        }
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.add("entries", list);
        AtomicFiles.write(mFile, GSON.toJson(root).getBytes(StandardCharsets.UTF_8));
    }
}
