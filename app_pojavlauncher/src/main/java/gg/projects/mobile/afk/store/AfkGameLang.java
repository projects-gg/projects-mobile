package gg.projects.mobile.afk.store;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import gg.projects.mobile.afk.core.AfkLang;
import gg.projects.mobile.content.AtomicFiles;

/**
 * MOB-NEXT: the game's language files on this phone for {@link AfkLang}, from its {@code .minecraft} folder: the
 * installed versions with a client jar, the most recently installed first. English is inside the jar
 * ({@code assets/minecraft/lang/en_us.json}), Turkish is an object of the version's asset index
 * ({@code minecraft/lang/tr_tr.json} under {@code assets/objects}); a loader's version (Fabric, Forge) names the one it
 * extends ({@code inheritsFrom}). Nothing is downloaded: without the game the console's keys stay as they were. Read
 * once per process (AfkLang keeps what it read). Pure Java.
 */
public final class AfkGameLang implements AfkLang.Source {
    private static final long MAX_VERSION_JSON = 2L << 20;
    private static final String ENGLISH = "en_us";

    private final File gameDir;

    /** {@code gameDir}: the game's {@code .minecraft} folder. */
    public AfkGameLang(File gameDir) {
        this.gameDir = gameDir;
    }

    @Override
    public InputStream open(String code) throws IOException {
        for (Installed version : installed()) {
            InputStream in = ENGLISH.equals(code) ? fromJar(version.jar) : fromAssets(version.assetIndex, code);
            if (in != null) return in;
        }
        return null;
    }

    /** A version that can run: its client jar and its asset index's id. */
    static final class Installed {
        final File jar;
        final String assetIndex;

        Installed(File jar, String assetIndex) {
            this.jar = jar;
            this.assetIndex = assetIndex;
        }
    }

    /** The installed versions with their jar, the most recently installed (its jar written last) first. */
    List<Installed> installed() {
        File versions = new File(gameDir, "versions");
        File[] dirs = versions.listFiles();
        if (dirs == null) return Collections.emptyList();
        List<Installed> out = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (File dir : dirs) {
            if (!dir.isDirectory()) continue;
            Installed found = resolve(versions, dir.getName(), 0);
            if (found == null || seen.contains(found.jar.getPath())) continue;
            seen.add(found.jar.getPath());
            out.add(found);
        }
        Collections.sort(out, (a, b) -> Long.compare(b.jar.lastModified(), a.jar.lastModified()));
        return out;
    }

    /** {@code id}'s jar and asset index, following {@code inheritsFrom} (a loader's version has neither of its own). */
    private static Installed resolve(File versions, String id, int depth) {
        if (depth > 4 || id.isEmpty() || id.contains("/") || id.contains("\\") || id.startsWith(".")) return null;
        File json = new File(new File(versions, id), id + ".json");
        if (!json.isFile()) return null;
        JsonObject o;
        try {
            JsonElement root = JsonParser.parseString(new String(AtomicFiles.read(json, MAX_VERSION_JSON), StandardCharsets.UTF_8));
            if (!root.isJsonObject()) return null;
            o = root.getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            return null;
        }
        File jar = new File(new File(versions, id), id + ".jar");
        String index = assetIndexOf(o);
        if (jar.isFile() && index != null) return new Installed(jar, index);
        JsonElement parent = o.get("inheritsFrom");
        if (parent == null || !parent.isJsonPrimitive()) return null;
        Installed base = resolve(versions, parent.getAsString(), depth + 1);
        if (base == null) return null;
        // A loader's version may name its own asset index; its jar is the base's.
        return new Installed(jar.isFile() ? jar : base.jar, index != null ? index : base.assetIndex);
    }

    private static String assetIndexOf(JsonObject version) {
        JsonElement index = version.get("assetIndex");
        if (index != null && index.isJsonObject()) {
            JsonElement id = index.getAsJsonObject().get("id");
            if (id != null && id.isJsonPrimitive()) return id.getAsString();
        }
        JsonElement assets = version.get("assets");
        return assets != null && assets.isJsonPrimitive() ? assets.getAsString() : null;
    }

    private static InputStream fromJar(File jar) throws IOException {
        final ZipFile zip;
        try {
            zip = new ZipFile(jar);
        } catch (IOException e) {
            return null;
        }
        ZipEntry entry = zip.getEntry("assets/minecraft/lang/en_us.json");
        if (entry == null) {
            zip.close();
            return null;
        }
        return new FilterInputStream(zip.getInputStream(entry)) {
            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    zip.close();
                }
            }
        };
    }

    /** {@code minecraft/lang/<code>.json} of the asset index {@code index}, from the objects the game downloaded. */
    private InputStream fromAssets(String index, String code) throws IOException {
        if (index.contains("/") || index.contains("\\") || index.startsWith(".")) return null;
        File assets = new File(gameDir, "assets");
        File indexFile = new File(new File(assets, "indexes"), index + ".json");
        if (!indexFile.isFile()) return null;
        String hash = objectHash(indexFile, "minecraft/lang/" + code + ".json");
        if (hash == null || hash.length() < 3 || !hash.matches("[0-9a-f]+")) return null;
        File object = new File(new File(new File(assets, "objects"), hash.substring(0, 2)), hash);
        return object.isFile() ? new FileInputStream(object) : null;
    }

    /** The hash the asset index gives {@code name} (read as a stream: an index lists thousands of objects). */
    static String objectHash(File indexFile, String name) throws IOException {
        try (JsonReader json = new JsonReader(new InputStreamReader(new FileInputStream(indexFile), StandardCharsets.UTF_8))) {
            json.beginObject();
            while (json.hasNext()) {
                if (!"objects".equals(json.nextName()) || json.peek() != JsonToken.BEGIN_OBJECT) {
                    json.skipValue();
                    continue;
                }
                json.beginObject();
                while (json.hasNext()) {
                    String object = json.nextName();
                    if (!object.equals(name) || json.peek() != JsonToken.BEGIN_OBJECT) {
                        json.skipValue();
                        continue;
                    }
                    String hash = null;
                    json.beginObject();
                    while (json.hasNext()) {
                        if ("hash".equals(json.nextName()) && json.peek() == JsonToken.STRING) hash = json.nextString();
                        else json.skipValue();
                    }
                    return hash;
                }
                return null;
            }
            return null;
        } catch (IllegalStateException | NumberFormatException e) {
            return null;
        }
    }
}
