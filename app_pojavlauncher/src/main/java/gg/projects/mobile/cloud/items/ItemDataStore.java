package gg.projects.mobile.cloud.items;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import gg.projects.mobile.social.Json;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * The phone's loader of {@link ItemData} (the launcher's {@code plus_items.rs} in Java): the game's own item data of the
 * account's Minecraft version, from our CDN. The manifest ({@code manifest.json}) names each version's file by its SHA-256;
 * the bytes are checked against the manifest's size and hash, and the file's own {@code mc}, before anything reads them, and are
 * kept in the cache folder ({@code <cacheDir>/items/data-<version>-<first 12 hex>.json}, the newest file per version) so a panel
 * opened again, or without a connection, has them at once. The last good manifest is kept as
 * {@code <cacheDir>/itemdata-manifest.json}.
 *
 * <p>Blocking: call {@link #load} on a worker thread. It never throws for network trouble: it returns what the cache holds,
 * or null (the caller then says the list could not be loaded and offers to try again, {@link #refresh}). Calls are serialized,
 * so two panels asking at once download a file once. Pure Java with OkHttp: no Android classes.
 */
public final class ItemDataStore {
    /** Where the item data lives: {@code manifest.json} and one folder per version (the format is in the path). */
    public static final String BASE = "https://cdn.projects.gg/itemdata/v1/";

    /** The store's clock: milliseconds, only differences matter (tests move it). */
    public interface Clock {
        /** The time in milliseconds on any steady scale (only differences are used). */
        long millis();
    }

    private static final Clock SYSTEM = new Clock() {
        @Override
        public long millis() {
            return System.nanoTime() / 1000000L;
        }
    };
    /** The manifest is read again after this (its own max-age). */
    private static final long MANIFEST_FRESH_MS = 5 * 60 * 1000L;
    /** A manifest that could not be read at all is not asked again sooner. */
    private static final long MANIFEST_FAILED_MS = 30 * 1000L;
    private static final int MAX_MANIFEST_BYTES = 256 * 1024;
    private static final String MANIFEST_FILE = "manifest.json";
    private static final String MANIFEST_COPY = "itemdata-manifest.json";
    private static final String ITEMS_DIR = "items";
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final AtomicLong PARTS = new AtomicLong();

    /** One version's file in the manifest. */
    private static final class Entry {
        /** Relative to the manifest: {@code 26.3/items.<first 12 hex of its SHA-256>.json}. */
        final String file;
        final String sha256;
        final long bytes;

        Entry(String file, String sha256, long bytes) {
            this.file = file;
            this.sha256 = sha256;
            this.bytes = bytes;
        }
    }

    /** The manifest as read: each version's file, and the version to use when the account's is unknown. */
    private static final class Manifest {
        final String defaultVersion;
        final Map<String, Entry> versions;

        Manifest(String defaultVersion, Map<String, Entry> versions) {
            this.defaultVersion = defaultVersion;
            this.versions = versions;
        }
    }

    private final File mCacheDir;
    private final File mItemsDir;
    private final OkHttpClient mHttp;
    private final String mBase;
    private final Clock mClock;
    // Guarded by this.
    @Nullable
    private Manifest mManifest;
    private long mManifestAt;
    private boolean mManifestRead;
    /** The data read this session, by cache file name (one file per version). */
    private final Map<String, ItemData> mMemory = new HashMap<>();

    /** A store that keeps its files under {@code cacheDir} and asks {@link #BASE} through {@code http} (timeouts are set here). */
    public ItemDataStore(File cacheDir, OkHttpClient http) {
        this(cacheDir, http, BASE, SYSTEM);
    }

    /** {@link #ItemDataStore(File, OkHttpClient)} for another base ({@code manifest.json} and the version folders under it). */
    public ItemDataStore(File cacheDir, OkHttpClient http, String base) {
        this(cacheDir, http, base, SYSTEM);
    }

    /** {@link #ItemDataStore(File, OkHttpClient, String)} with the clock the manifest's freshness is measured by (tests). */
    public ItemDataStore(File cacheDir, OkHttpClient http, String base, Clock clock) {
        mCacheDir = cacheDir;
        mItemsDir = new File(cacheDir, ITEMS_DIR);
        mHttp = http.newBuilder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .build();
        mBase = base.endsWith("/") ? base : base + "/";
        mClock = clock;
    }

    /**
     * The item data for a Minecraft version (the account's; null: the manifest's default), checked and cached: the version's own
     * file, else the newest older one, else the oldest ({@link ItemData#pickVersion}). Null when neither the CDN nor the cache
     * has it. The manifest is read at most every five minutes (the last good copy when the CDN cannot be reached), a file is
     * downloaded once (a size over 4 MB, a wrong hash or a file that is not the version's is refused), and a newer file that
     * cannot be fetched leaves the one kept. Offline, the kept files answer.
     */
    @Nullable
    @WorkerThread
    public synchronized ItemData load(@Nullable String mc) {
        try {
            return loadNow(mc);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Forgets that the manifest was read, so the next {@link #load} asks the CDN again (a "try again" after a failed load). */
    public synchronized void refresh() {
        mManifestRead = false;
    }

    @Nullable
    private ItemData loadNow(@Nullable String mc) {
        Manifest manifest = manifest();
        if (manifest == null) return offline(mc);
        String version = ItemData.pickVersion(manifest.versions.keySet(), manifest.defaultVersion, mc);
        Entry entry = version == null ? null : manifest.versions.get(version);
        if (entry == null) return null;
        String name = cacheName(version, entry);
        ItemData known = mMemory.get(name);
        if (known != null) return known;
        if (entry.bytes <= ItemData.MAX_BYTES) {
            ItemData data = fromDisk(version, name, entry);
            if (data == null) data = fromNetwork(version, name, entry);
            if (data != null) return data;
        }
        // The manifest's file is out of reach (or does not check): the one kept for this version still names the items.
        return kept(version, name);
    }

    // ---- the manifest ----

    /** The manifest: read again after five minutes; the last good copy on disk when the CDN cannot be reached. */
    @Nullable
    private Manifest manifest() {
        if (mManifestRead) {
            long fresh = mManifest != null ? MANIFEST_FRESH_MS : MANIFEST_FAILED_MS;
            if (mClock.millis() - mManifestAt < fresh) return mManifest;
        }
        Manifest manifest = null;
        byte[] bytes = fetch(mBase + MANIFEST_FILE, MAX_MANIFEST_BYTES);
        if (bytes != null) {
            manifest = parseManifest(bytes);
            if (manifest != null) writeWhole(new File(mCacheDir, MANIFEST_COPY), bytes);
        }
        if (manifest == null) {
            byte[] copy = readFile(new File(mCacheDir, MANIFEST_COPY), MAX_MANIFEST_BYTES);
            if (copy != null) manifest = parseManifest(copy);
        }
        mManifest = manifest;
        mManifestAt = mClock.millis();
        mManifestRead = true;
        return manifest;
    }

    /**
     * Reads a manifest: format 1, and every version a safe file-name part whose entry names a file next to the manifest
     * ({@code <version folder>/items.<12 hex>.json}) by the first 12 hex of its own SHA-256 (64 hex) and a size. Null when
     * anything is off: a manifest is taken whole or not at all (the last good copy then stays in use).
     */
    @Nullable
    private static Manifest parseManifest(byte[] bytes) {
        try {
            JsonObject doc = Json.parseObject(new String(bytes, StandardCharsets.UTF_8));
            if (doc == null) return null;
            JsonElement format = doc.get("format");
            if (format == null || !format.isJsonPrimitive() || !format.getAsJsonPrimitive().isNumber()
                    || format.getAsDouble() != ItemData.FORMAT) {
                return null;
            }
            JsonObject versions = Json.obj(doc, "versions");
            if (versions == null) return null;
            Map<String, Entry> out = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> row : versions.entrySet()) {
                String version = row.getKey();
                if (!safeVersion(version) || !row.getValue().isJsonObject()) return null;
                JsonObject entry = row.getValue().getAsJsonObject();
                String file = Json.str(entry, "file");
                String sha256 = Json.str(entry, "sha256");
                JsonElement size = entry.get("bytes");
                if (file == null || sha256 == null || size == null || !size.isJsonPrimitive()
                        || !size.getAsJsonPrimitive().isNumber()) {
                    return null;
                }
                double length = size.getAsDouble();
                if (!lowerHex(sha256, 64) || !fileOk(file, sha256) || !(length >= 1) || length != Math.rint(length)
                        || length > 9007199254740991.0) {
                    return null;
                }
                out.put(version, new Entry(file, sha256, (long) length));
            }
            return new Manifest(Json.str(doc, "default", ""), out);
        } catch (RuntimeException | StackOverflowError e) {
            return null;
        }
    }

    /** {@code <version folder>/items.<12 hex>.json} with those 12 hex the start of {@code sha256}: only a file named by its hash. */
    private static boolean fileOk(String file, String sha256) {
        int slash = file.indexOf('/');
        if (slash < 1 || file.indexOf('/', slash + 1) >= 0) return false;
        for (int i = 0; i < slash; i++) {
            char c = file.charAt(i);
            boolean letter = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
            if (!(letter || c == '_' || c == '-' || (i > 0 && c == '.'))) return false;
        }
        String name = file.substring(slash + 1);
        String prefix = "items.";
        String suffix = ".json";
        if (name.length() != prefix.length() + 12 + suffix.length() || !name.startsWith(prefix) || !name.endsWith(suffix)) {
            return false;
        }
        return name.substring(prefix.length(), prefix.length() + 12).equals(sha256.substring(0, 12));
    }

    // ---- the data files ----

    /** A version as a file-name part: {@code [0-9A-Za-z._-]}, 1 to 32 characters, not starting with a dot. */
    private static boolean safeVersion(String version) {
        if (version.isEmpty() || version.length() > 32 || version.charAt(0) == '.') return false;
        for (int i = 0; i < version.length(); i++) {
            char c = version.charAt(i);
            boolean letter = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
            if (!(letter || c == '.' || c == '_' || c == '-')) return false;
        }
        return true;
    }

    private static boolean lowerHex(String text, int length) {
        if (text.length() != length) return false;
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
        }
        return true;
    }

    /** {@code data-<version>-<first 12 hex of its SHA-256>.json}. */
    private static String cacheName(String version, Entry entry) {
        return "data-" + version + "-" + entry.sha256.substring(0, 12) + ".json";
    }

    /** A cached file's version and hash, read from the end: {@code data-26.1-...} is never {@code data-26.1-rc1-...}'s. */
    @Nullable
    private static String[] cachedName(String name) {
        if (!name.startsWith("data-") || !name.endsWith(".json") || name.length() < 11) return null;
        String middle = name.substring(5, name.length() - 5);
        int dash = middle.lastIndexOf('-');
        if (dash < 1) return null;
        String version = middle.substring(0, dash);
        String hash = middle.substring(dash + 1);
        return safeVersion(version) && lowerHex(hash, 12) ? new String[]{version, hash} : null;
    }

    private static boolean verified(byte[] bytes, Entry entry) {
        return bytes.length == entry.bytes && sha256Hex(bytes).equals(entry.sha256);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) out.append(HEX[(b >> 4) & 15]).append(HEX[b & 15]);
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The cached file the manifest names, if it is still that file (checked again: a file changed on disk is not read). */
    @Nullable
    private ItemData fromDisk(String version, String name, Entry entry) {
        byte[] bytes = readFile(new File(mItemsDir, name), entry.bytes);
        if (bytes == null || !verified(bytes, entry)) return null;
        ItemData data = ItemData.parse(new String(bytes, StandardCharsets.UTF_8));
        if (data == null || !version.equals(data.minecraft())) return null;
        remember(version, name, data);
        return data;
    }

    /** Downloads the manifest's file; kept on disk (older files of the version go) only once it has checked out. */
    @Nullable
    private ItemData fromNetwork(String version, String name, Entry entry) {
        byte[] bytes = fetch(mBase + entry.file, entry.bytes);
        if (bytes == null || !verified(bytes, entry)) return null;
        ItemData data = ItemData.parse(new String(bytes, StandardCharsets.UTF_8));
        if (data == null || !version.equals(data.minecraft())) return null;
        keep(version, name, bytes);
        remember(version, name, data);
        return data;
    }

    /** Writes a version's checked file whole or not at all; the older files of that version go. */
    private void keep(String version, String name, byte[] bytes) {
        if (!writeWhole(new File(mItemsDir, name), bytes)) return;
        File[] files = mItemsDir.listFiles();
        if (files == null) return;
        for (File other : files) {
            String[] parts = cachedName(other.getName());
            if (parts != null && parts[0].equals(version) && !other.getName().equals(name)) {
                //noinspection ResultOfMethodCallIgnored
                other.delete();
            }
        }
    }

    /** Keeps the parsed data under its file's name; the version's earlier name goes. */
    private void remember(String version, String name, ItemData data) {
        Iterator<String> names = mMemory.keySet().iterator();
        while (names.hasNext()) {
            String[] parts = cachedName(names.next());
            if (parts != null && parts[0].equals(version)) names.remove();
        }
        mMemory.put(name, data);
    }

    /**
     * What this session or the cache folder holds of a version, newest first (checked by the hash its name carries): read when
     * the manifest or the file it names cannot be had (offline). {@code skip} is a file name that was just refused.
     */
    @Nullable
    private ItemData kept(String version, @Nullable String skip) {
        for (Map.Entry<String, ItemData> held : mMemory.entrySet()) {
            String[] parts = cachedName(held.getKey());
            if (parts != null && parts[0].equals(version) && !held.getKey().equals(skip)) return held.getValue();
        }
        File[] files = mItemsDir.listFiles();
        if (files == null) return null;
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        for (File file : files) {
            String name = file.getName();
            String[] parts = cachedName(name);
            if (parts == null || !parts[0].equals(version) || name.equals(skip)) continue;
            byte[] bytes = readFile(file, ItemData.MAX_BYTES);
            if (bytes == null || !sha256Hex(bytes).startsWith(parts[1])) continue;
            ItemData data = ItemData.parse(new String(bytes, StandardCharsets.UTF_8));
            if (data == null || !version.equals(data.minecraft())) continue;
            remember(version, name, data);
            return data;
        }
        return null;
    }

    /** No manifest at all (never read, its copy gone): the versions this session or the cache folder holds, chosen the same way. */
    @Nullable
    private ItemData offline(@Nullable String mc) {
        Set<String> versions = new HashSet<>();
        for (String name : mMemory.keySet()) {
            String[] parts = cachedName(name);
            if (parts != null) versions.add(parts[0]);
        }
        File[] files = mItemsDir.listFiles();
        if (files != null) {
            for (File file : files) {
                String[] parts = cachedName(file.getName());
                if (parts != null) versions.add(parts[0]);
            }
        }
        String version = ItemData.pickVersion(versions, null, mc);
        return version == null ? null : kept(version, null);
    }

    // ---- bytes ----

    /** One GET; the body (at most {@code limit} bytes), or null for any trouble: no connection, a status, a body too long. */
    @Nullable
    private byte[] fetch(String url, long limit) {
        try {
            Request request = new Request.Builder().url(url).header("Accept", "application/json").build();
            try (Response response = mHttp.newCall(request).execute()) {
                ResponseBody body = response.body();
                if (!response.isSuccessful() || body == null || body.contentLength() > limit) return null;
                return readAtMost(body.byteStream(), limit);
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** All of a stream, or null when it is longer than {@code limit}. */
    @Nullable
    private static byte[] readAtMost(InputStream in, long limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        byte[] buffer = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buffer)) != -1) {
            total += n;
            if (total > limit) return null;
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    /** A file's bytes, or null when it is missing, longer than {@code limit} or unreadable. */
    @Nullable
    private static byte[] readFile(File file, long limit) {
        try {
            if (!file.isFile() || file.length() > limit) return null;
            try (FileInputStream in = new FileInputStream(file)) {
                return readAtMost(in, limit);
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Writes a file whole or not at all: its own temporary name (two writers never write into each other's), then a rename. */
    private static boolean writeWhole(File target, byte[] bytes) {
        File dir = target.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) return false;
        File temp = new File(dir, target.getName() + "." + PARTS.incrementAndGet() + ".part");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(bytes);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            return false;
        }
        if (temp.renameTo(target)) return true;
        // A file system that will not rename onto an existing file (Windows'): the old one goes first.
        //noinspection ResultOfMethodCallIgnored
        target.delete();
        if (temp.renameTo(target)) return true;
        //noinspection ResultOfMethodCallIgnored
        temp.delete();
        return false;
    }
}
