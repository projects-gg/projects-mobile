package gg.projects.mobile.launch;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import gg.projects.mobile.content.AtomicFiles;

/**
 * The mod ids a jar declares and the ids it depends on (Fabric, Quilt, Forge and NeoForge
 * metadata), and from them the Sodium family of a mods folder: Sodium, its forks, Iris and every
 * jar that depends on one of them. Used for profiles the player made themselves, whose jars carry
 * no pack data.
 */
public final class ModIds {
    private ModIds() {}

    /** Mod ids of Sodium, its forks and the shader mods built on them. */
    static final Set<String> SODIUM_IDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "sodium", "embeddium", "rubidium", "iris", "oculus")));
    private static final long MAX_METADATA_BYTES = 256 * 1024;
    private static final Pattern TOML_SECTION = Pattern.compile("^\\s*\\[\\[\\s*([\\w.\\-]+)\\s*]]");
    private static final Pattern TOML_MOD_ID = Pattern.compile("^\\s*modId\\s*=\\s*\"([^\"]+)\"");

    /** What a jar declares. */
    public static final class Info {
        public final Set<String> ids = new LinkedHashSet<>();
        public final Set<String> depends = new LinkedHashSet<>();
    }

    /** The metadata of a jar; empty when it has none or it cannot be read. */
    public static Info read(File jar) {
        Info info = new Info();
        try (ZipFile zip = new ZipFile(jar)) {
            for (String name : new String[]{"fabric.mod.json", "quilt.mod.json"}) {
                String text = entry(zip, name);
                if (text != null) readJson(text, info);
            }
            for (String name : new String[]{"META-INF/mods.toml", "META-INF/neoforge.mods.toml"}) {
                String text = entry(zip, name);
                if (text != null) readToml(text, info);
            }
        } catch (IOException | RuntimeException e) {
            // Not a jar, or a broken one: it declares nothing.
        }
        return info;
    }

    private static String entry(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null || entry.getSize() > MAX_METADATA_BYTES) return null;
        try (InputStream in = zip.getInputStream(entry)) {
            return new String(AtomicFiles.readBounded(in, MAX_METADATA_BYTES), StandardCharsets.UTF_8);
        }
    }

    /** {@code fabric.mod.json} ({@code id}, {@code depends}) or {@code quilt.mod.json} ({@code quilt_loader.id}, {@code depends}). */
    static void readJson(String text, Info info) {
        JsonElement root;
        try {
            root = JsonParser.parseString(text);
        } catch (RuntimeException e) {
            return;
        }
        if (!root.isJsonObject()) return;
        JsonObject object = root.getAsJsonObject();
        JsonElement quilt = object.get("quilt_loader");
        if (quilt != null && quilt.isJsonObject()) object = quilt.getAsJsonObject();
        JsonElement id = object.get("id");
        if (id != null && id.isJsonPrimitive()) info.ids.add(id.getAsString().toLowerCase(Locale.ROOT));
        JsonElement depends = object.get("depends");
        if (depends != null && depends.isJsonObject()) {
            for (String key : depends.getAsJsonObject().keySet()) info.depends.add(key.toLowerCase(Locale.ROOT));
        } else if (depends != null && depends.isJsonArray()) {
            for (JsonElement dependency : depends.getAsJsonArray()) {
                if (dependency.isJsonPrimitive()) info.depends.add(dependency.getAsString().toLowerCase(Locale.ROOT));
                else if (dependency.isJsonObject() && dependency.getAsJsonObject().get("id") != null) {
                    info.depends.add(dependency.getAsJsonObject().get("id").getAsString().toLowerCase(Locale.ROOT));
                }
            }
        }
    }

    /**
     * {@code mods.toml}: the ids under {@code [[mods]]}, and the required ones under
     * {@code [[dependencies.x]]} (not {@code mandatory=false}, not {@code type="optional"} and the like).
     */
    static void readToml(String text, Info info) {
        String section = "";
        String dependency = null;
        boolean required = true;
        for (String line : (text + "\n[[end]]").split("\r?\n")) {
            Matcher header = TOML_SECTION.matcher(line);
            if (header.find()) {
                if (dependency != null && required) info.depends.add(dependency);
                section = header.group(1);
                dependency = null;
                required = true;
                continue;
            }
            Matcher modId = TOML_MOD_ID.matcher(line);
            if (modId.find()) {
                String id = modId.group(1).toLowerCase(Locale.ROOT);
                if (section.equals("mods")) info.ids.add(id);
                else if (section.startsWith("dependencies")) dependency = id;
                continue;
            }
            String compact = line.replace(" ", "").toLowerCase(Locale.ROOT);
            if (compact.startsWith("mandatory=false") || compact.startsWith("type=\"optional\"")
                    || compact.startsWith("type=\"incompatible\"") || compact.startsWith("type=\"discouraged\"")) {
                required = false;
            }
        }
    }

    /**
     * The jars (keys of {@code jars}) of the Sodium family: the ones declaring Sodium, a fork or a
     * shader mod built on them, and every jar depending on a family member, transitively.
     */
    public static Set<String> sodiumFamily(Map<String, Info> jars) {
        Set<String> familyIds = new HashSet<>(SODIUM_IDS);
        Set<String> family = new LinkedHashSet<>();
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Map.Entry<String, Info> jar : jars.entrySet()) {
                if (family.contains(jar.getKey())) continue;
                Info info = jar.getValue();
                boolean member = !Collections.disjoint(info.ids, familyIds) || !Collections.disjoint(info.depends, familyIds);
                if (!member) continue;
                family.add(jar.getKey());
                familyIds.addAll(info.ids);
                grew = true;
            }
        }
        return family;
    }

    /** {@link #sodiumFamily} of the jars in a mods folder (file names). */
    public static Set<String> sodiumFamily(File modsDir) {
        Map<String, Info> jars = new LinkedHashMap<>();
        File[] files = modsDir.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".jar"));
        if (files != null) for (File file : files) if (file.isFile()) jars.put(file.getName(), read(file));
        return sodiumFamily(jars);
    }
}
