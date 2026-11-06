package gg.projects.mobile.afk.core;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * MOB-NEXT: the game's own words for the translation keys {@link AfkComponents}' built-in table lacks (the console
 * showed "command.unknown.command", "command.context.here" and "entity.minecraft.slime" raw). They come from the game
 * files on this phone ({@link Source}: the newest installed version's English inside its client jar, its Turkish among
 * its assets), never from the app: Mojang's text is not ours to ship. Read once per process and language, only when a
 * key misses the built-in table, without the families only the game's own screens use (Realms, options, subtitles,
 * narration...): about half of the file stays, some 1 MB in the {@code :afk} process. Without game files a creature's,
 * item's or block's key shows its id as words ("Slime", "Cave Spider"). Pure Java.
 */
public final class AfkLang {
    private AfkLang() {}

    /** The game's language files on this phone. */
    public interface Source {
        /** {@code code}'s file ("en_us", "tr_tr") as the game ships it (a JSON object of key to text), or null. */
        InputStream open(String code) throws IOException;
    }

    /** Longest language file read (vanilla's are about 500 KB). */
    static final long MAX_BYTES = 4L << 20;

    /** Families only the game's own screens use: no server sends them to a player. */
    private static final String[] SCREENS_ONLY = {"mco.", "subtitles.", "narrator.", "narration.", "accessibility.",
            "gui.", "menu.", "controls.", "key.", "selectWorld.", "createWorld.", "soundCategory.", "telemetry.",
            "telemetry_info.", "title.", "resourcePack.", "resourcepack.", "dataPack.", "datapackFailure.", "structure_block.",
            "jigsaw_block.", "debug.", "lanServer.", "demo.", "spectatorMenu.", "tutorial.", "recipe.", "book.",
            "credits_and_attribution.", "editGamerule.", "symlink_warning.", "language.", "pack.", "quickplay.", "report.",
            "chat_screen.", "addServer.", "selectServer.", "editServer.", "stat.", "stat_type.", "advMode.", "screenshot.",
            "outOfMemory.", "optimizeWorld.", "recover_world.", "known_server_link.", "server_links.", "deathScreen.",
            "connect.", "multiplayerWarning.", "multiplayer.status.", "multiplayer.socialInteractions", "authentication.",
            "realms.", "skinCustomisation.", "flat_world_preset.", "generator."};

    private static Source sSource;
    /** Per language (0 English, 1 Turkish): null not read yet, empty when this phone has no such file. */
    private static final Map<String, String>[] sLoaded = newTables();

    @SuppressWarnings("unchecked")
    private static Map<String, String>[] newTables() {
        return (Map<String, String>[]) new Map[2];
    }

    /** Where the game files are (the service sets it once it knows the game's folder); null: none. */
    public static synchronized void setSource(Source source) {
        sSource = source;
        sLoaded[0] = null;
        sLoaded[1] = null;
    }

    /** The game's text for {@code key} in English (0) or Turkish (1), or null when this phone has none. */
    static String get(String key, int lang) {
        return table(lang).get(key);
    }

    private static synchronized Map<String, String> table(int lang) {
        Map<String, String> table = sLoaded[lang];
        if (table != null) return table;
        table = Collections.emptyMap();
        Source source = sSource;
        if (source != null) {
            try (InputStream in = source.open(lang == 1 ? "tr_tr" : "en_us")) {
                if (in != null) table = read(in);
            } catch (IOException | RuntimeException e) {
                // A file that cannot be read is no file: the keys show as before.
                table = Collections.emptyMap();
            }
        }
        sLoaded[lang] = table;
        return table;
    }

    /** A language file's keys and texts, without {@link #SCREENS_ONLY}; at most {@link #MAX_BYTES} are read. */
    static Map<String, String> read(InputStream in) throws IOException {
        Map<String, String> out = new HashMap<>();
        JsonReader json = new JsonReader(new InputStreamReader(new Capped(in, MAX_BYTES), StandardCharsets.UTF_8));
        json.beginObject();
        while (json.hasNext()) {
            String key = json.nextName();
            if (json.peek() != JsonToken.STRING || screensOnly(key)) {
                json.skipValue();
                continue;
            }
            out.put(key, json.nextString());
        }
        json.endObject();
        return out;
    }

    static boolean screensOnly(String key) {
        for (String family : SCREENS_ONLY) {
            if (key.startsWith(family)) return true;
        }
        return false;
    }

    /**
     * Without the game's words, a creature's, item's or block's name from its key's id: "entity.minecraft.cave_spider"
     * is "Cave Spider" (vanilla's English names are its ids in words); null for any other key.
     */
    static String nameOfId(String key) {
        int dot = key.indexOf('.');
        if (dot < 0) return null;
        String family = key.substring(0, dot);
        if (!family.equals("entity") && !family.equals("item") && !family.equals("block")) return null;
        String id = key.substring(key.lastIndexOf('.') + 1);
        if (id.isEmpty()) return null;
        StringBuilder out = new StringBuilder();
        for (String word : id.split("_")) {
            if (word.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
        }
        return out.length() == 0 ? null : out.toString();
    }

    /** An input stream that ends with an error after {@code max} bytes (a language file is never that large). */
    private static final class Capped extends java.io.FilterInputStream {
        private long left;

        Capped(InputStream in, long max) {
            super(in);
            left = max;
        }

        @Override
        public int read() throws IOException {
            if (left <= 0) throw new IOException("language file too large");
            int b = super.read();
            if (b >= 0) left--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) throw new IOException("language file too large");
            int n = super.read(b, off, (int) Math.min(len, left));
            if (n > 0) left -= n;
            return n;
        }
    }
}
