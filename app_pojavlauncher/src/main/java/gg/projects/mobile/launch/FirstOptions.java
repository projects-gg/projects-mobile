package gg.projects.mobile.launch;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import gg.projects.mobile.content.AtomicFiles;

/**
 * The options a new game folder starts with, as on the desktop (pl-mc's {@code seed_options}):
 * right before a launch, a folder without an {@code options.txt} gets {@code version:<data
 * version>} and the app's language as the game language (Turkish or English, see
 * {@code AppLanguage}), plus {@code tutorialStep:none} (the keyboard tutorial means nothing on a
 * phone); the Projects profile's folder also gets the pack's conflict-free key bindings
 * ({@link #keyLines}, the desktop's table). The game fills in everything else, and a file the
 * player, the game or a pack wrote is never touched, so later changes stay the player's.
 *
 * <p>The {@code version} line matters: without it the game takes the file for one from before
 * data versions existed and runs its options data fixers from the start, and one of them (1.19.3
 * on) switches off the accessibility onboarding a new player should see. When the data version
 * cannot be read the file is written without it for older versions (they have no onboarding) and
 * not at all from 1.19.3 on.
 *
 * <p>Android only: upstream creates an empty {@code options.txt} and writes the window size into
 * it before the game starts ({@code MCOptionUtils}, {@code MinecraftGLSurface}). A file that holds
 * nothing but those lines and the two written here was never the game's, so it counts as missing
 * (an earlier launch that stopped before the game saved its options leaves one behind). The GUI
 * scale is left to the game: its "Auto" picks the largest scale that fits the window, which keeps
 * buttons finger-sized at any resolution (the desktop's fixed 2 suits a monitor, not a phone).
 */
public final class FirstOptions {
    private FirstOptions() {}

    public static final String FILE_NAME = "options.txt";

    /** What {@link #seed} did. */
    public enum Result {
        /** {@code options.txt} was written. */
        WRITTEN,
        /** {@code options.txt} is the player's, the game's or a pack's: left as it is. */
        EXISTS,
        /**
         * Not written: the data version is unknown and this version has an accessibility
         * onboarding the game would otherwise switch off.
         */
        SKIPPED
    }

    /** Lines upstream writes before every launch: the window size, not a setting of the player's. */
    private static final Set<String> WINDOW_KEYS = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList("fullscreen", "overrideWidth", "overrideHeight")));
    /**
     * Every new game folder on a phone (0.1.6): Minecraft's keyboard tutorial ("Move with W, A, S and
     * D") does not apply to touch controls. The game has the option from 1.12 on and ignores it before.
     */
    static final String PHONE_LINE = "tutorialStep:none";
    /** Lines {@link #seed} writes. */
    private static final Set<String> SEEDED_KEYS = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList("version", "lang", "tutorialStep")));
    private static final long MAX_OPTIONS_BYTES = 1024 * 1024;
    /** {@code version.json} inside a client jar is a few hundred bytes; anything far bigger is not it. */
    private static final long MAX_VERSION_JSON_BYTES = 64 * 1024;
    /** The shared key table is a few kilobytes. */
    private static final long MAX_KEY_TABLE_BYTES = 64 * 1024;

    /**
     * Writes the first options into {@code gameDir} when it has none (see the class comment).
     * {@code minecraft} is the vanilla version (a modded version's {@code inheritsFrom}); the data
     * version comes from the first readable jar of {@code clientJars}.
     */
    public static Result seed(File gameDir, String minecraft, List<File> clientJars, boolean turkish) throws IOException {
        return seed(gameDir, minecraft, clientJars, turkish, Collections.<String>emptyList());
    }

    /**
     * {@link #seed} with {@code extra} lines after the language, the Projects profile's key
     * bindings ({@link #keyLines}). They go only into a file this class creates: the player's, the
     * game's or the pack's own {@code options.txt} keeps its keys.
     */
    public static Result seed(File gameDir, String minecraft, List<File> clientJars, boolean turkish,
                              List<String> extra) throws IOException {
        File file = new File(gameDir, FILE_NAME);
        List<String> kept = new ArrayList<>();
        if (file.exists()) {
            String existing = new String(AtomicFiles.read(file, MAX_OPTIONS_BYTES), StandardCharsets.UTF_8);
            if (!launcherOnly(existing, kept, keysOf(extra))) return Result.EXISTS;
        }
        Integer dataVersion = null;
        for (File jar : clientJars) {
            dataVersion = clientDataVersion(jar);
            if (dataVersion != null) break;
        }
        if (dataVersion == null && hasAccessibilityOnboarding(minecraft)) return Result.SKIPPED;
        StringBuilder content = new StringBuilder();
        if (dataVersion != null) content.append("version:").append(dataVersion).append('\n');
        content.append("lang:").append(gameLanguage(minecraft, turkish)).append('\n');
        content.append(PHONE_LINE).append('\n');
        for (String line : extra) content.append(line).append('\n');
        for (String line : kept) content.append(line).append('\n');
        AtomicFiles.write(file, content.toString().getBytes(StandardCharsets.UTF_8));
        return Result.WRITTEN;
    }

    static boolean launcherOnly(String content, List<String> windowLines) {
        return launcherOnly(content, windowLines, Collections.<String>emptySet());
    }

    /**
     * True when {@code content} holds nothing but upstream's window lines and lines this class
     * writes (also {@code extraKeys}); the window lines go into {@code windowLines}. A line without
     * a colon is not ours.
     */
    static boolean launcherOnly(String content, List<String> windowLines, Set<String> extraKeys) {
        for (String raw : content.split("\r?\n", -1)) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            int colon = line.indexOf(':');
            if (colon <= 0) return false;
            String key = line.substring(0, colon);
            if (WINDOW_KEYS.contains(key)) windowLines.add(line);
            else if (!SEEDED_KEYS.contains(key) && !extraKeys.contains(key)) return false;
        }
        return true;
    }

    private static Set<String> keysOf(List<String> lines) {
        Set<String> keys = new HashSet<>();
        for (String line : lines) {
            int colon = line.indexOf(':');
            if (colon > 0) keys.add(line.substring(0, colon));
        }
        return keys;
    }

    /**
     * The {@code options.txt} lines of the shared key table ({@code assets/key-bindings.json}, a copy
     * of ProLauncher2's {@code crates/pl-mc/data/key-bindings.json}: the desktop writes the same
     * lines). Each binding is {@code key_<name>:<key>}; a malformed entry is left out, a malformed
     * table gives none.
     */
    public static List<String> keyLines(String json) {
        List<String> lines = new ArrayList<>();
        if (json == null || json.length() > MAX_KEY_TABLE_BYTES) return lines;
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) return lines;
            JsonElement bindings = ((JsonObject) root).get("bindings");
            if (bindings == null || !bindings.isJsonArray()) return lines;
            for (JsonElement entry : bindings.getAsJsonArray()) {
                if (!entry.isJsonObject()) continue;
                String name = string((JsonObject) entry, "name");
                String key = string((JsonObject) entry, "key");
                if (name == null || key == null || !KEY_PART.matcher(name).matches() || !KEY_PART.matcher(key).matches()) continue;
                lines.add("key_" + name + ":" + key);
            }
        } catch (RuntimeException e) {
            lines.clear();
        }
        return lines;
    }

    /** A key binding's name or key: letters, digits, dots and underscores (no line breaks, no colons). */
    private static final java.util.regex.Pattern KEY_PART = java.util.regex.Pattern.compile("[A-Za-z0-9_.]{1,100}");

    private static String string(JsonObject object, String field) {
        JsonElement value = object.get(field);
        return value != null && value.isJsonPrimitive() && ((JsonPrimitive) value).isString() ? value.getAsString() : null;
    }

    /**
     * The data version ({@code world_version}) of a client jar's embedded {@code version.json};
     * null when the jar is missing, has none or it cannot be read (versions before 1.14 have none).
     */
    public static Integer clientDataVersion(File jar) {
        if (jar == null || !jar.isFile()) return null;
        try (ZipFile zip = new ZipFile(jar)) {
            ZipEntry entry = zip.getEntry("version.json");
            if (entry == null || entry.getSize() > MAX_VERSION_JSON_BYTES) return null;
            String text;
            try (InputStream in = zip.getInputStream(entry)) {
                text = new String(AtomicFiles.readBounded(in, MAX_VERSION_JSON_BYTES), StandardCharsets.UTF_8);
            }
            JsonElement root = JsonParser.parseString(text);
            if (!root.isJsonObject()) return null;
            JsonElement value = ((JsonObject) root).get("world_version");
            if (value == null || !value.isJsonPrimitive() || !((JsonPrimitive) value).isNumber()) return null;
            long number = value.getAsLong();
            if (number < 0 || number > Integer.MAX_VALUE || value.getAsDouble() != number) return null;
            return (int) number;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * The {@code lang} value Minecraft {@code version} uses for Turkish: {@code tr_tr} from 1.11 on
     * (its snapshots from 16w32a and the year-numbered releases included), {@code tr_TR} before.
     */
    public static String turkishLanguage(String version) {
        return gameLanguage(version, true);
    }

    /**
     * The {@code lang} value for Turkish or English: {@code tr_tr} / {@code en_us} from 1.11 on (its
     * snapshots from 16w32a and the year-numbered releases included), {@code tr_TR} / {@code en_US}
     * before.
     */
    public static String gameLanguage(String version, boolean turkish) {
        boolean lower = atLeast(version, 1, 11, 0, 16, 32);
        if (turkish) return lower ? "tr_tr" : "tr_TR";
        return lower ? "en_us" : "en_US";
    }

    /**
     * Upstream's {@code assets/options.txt} for the default game folder with its {@code lang} line in
     * the app's language. The file has old key codes the game's data fixers convert, so it carries
     * no {@code version} line and keeps the old {@code tr_TR} / {@code en_US} form (the fixers lower
     * it). A missing {@code lang} line is added at the end.
     */
    public static String withLanguage(String options, boolean turkish) {
        String lang = "lang:" + (turkish ? "tr_TR" : "en_US");
        StringBuilder out = new StringBuilder();
        boolean found = false;
        String[] lines = options.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            boolean cr = line.endsWith("\r");
            String bare = cr ? line.substring(0, line.length() - 1) : line;
            if (bare.startsWith("lang:")) {
                line = lang + (cr ? "\r" : "");
                found = true;
            }
            out.append(line);
            if (i < lines.length - 1) out.append('\n');
        }
        if (!found) {
            if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
            out.append(lang).append('\n');
        }
        return out.toString();
    }

    /** Versions from 1.19.3 (snapshot 22w42a) on show new players an accessibility onboarding. */
    public static boolean hasAccessibilityOnboarding(String version) {
        return atLeast(version, 1, 19, 3, 22, 42);
    }

    /**
     * True for versions from release {@code major.minor.patch} on, given the first snapshot
     * ({@code year}, {@code week}) of that release. Year-numbered versions ({@code 26.1}) come
     * after every gate; classic, alpha and beta versions before.
     */
    static boolean atLeast(String version, int major, int minor, int patch, int year, int week) {
        Era era = era(version);
        switch (era.kind) {
            case RELEASE:
                return compare(new int[]{era.a, era.b, era.c}, new int[]{major, minor, patch}) >= 0;
            case SNAPSHOT:
                return compare(new int[]{era.a, era.b}, new int[]{year, week}) >= 0;
            case YEAR_NUMBERED:
                return true;
            default:
                return false;
        }
    }

    private static int compare(int[] a, int[] b) {
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) return a[i] < b[i] ? -1 : 1;
        }
        return 0;
    }

    /** Where a version sits on the release timeline (pl-mc's {@code era}). */
    static final class Era {
        enum Kind { ANCIENT, RELEASE, SNAPSHOT, YEAR_NUMBERED }

        final Kind kind;
        final int a;
        final int b;
        final int c;

        Era(Kind kind, int a, int b, int c) {
            this.kind = kind;
            this.a = a;
            this.b = b;
            this.c = c;
        }
    }

    static Era era(String version) {
        String v = version == null ? "" : version.trim();
        int w = v.indexOf('w');
        if (w >= 0) {
            Integer year = number(v.substring(0, w));
            String rest = v.substring(w + 1);
            Integer week = rest.length() >= 2 ? number(rest.substring(0, 2)) : null;
            if (year != null && week != null) return new Era(Era.Kind.SNAPSHOT, year, week, 0);
        }
        int end = 0;
        while (end < v.length() && (isAsciiDigit(v.charAt(end)) || v.charAt(end) == '.')) end++;
        List<Integer> numbers = new ArrayList<>();
        for (String part : v.substring(0, end).split("\\.", -1)) {
            Integer n = number(part);
            if (n == null) break;
            numbers.add(n);
        }
        if (numbers.size() >= 2 && numbers.get(0) == 1) {
            return new Era(Era.Kind.RELEASE, 1, numbers.get(1), numbers.size() > 2 ? numbers.get(2) : 0);
        }
        if (!numbers.isEmpty() && numbers.get(0) >= 2) return new Era(Era.Kind.YEAR_NUMBERED, 0, 0, 0);
        return new Era(Era.Kind.ANCIENT, 0, 0, 0);
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /** A non-negative ASCII number, or null. */
    private static Integer number(String text) {
        if (text.isEmpty() || text.length() > 9) return null;
        for (int i = 0; i < text.length(); i++) {
            if (!isAsciiDigit(text.charAt(i))) return null;
        }
        return Integer.parseInt(text);
    }
}
