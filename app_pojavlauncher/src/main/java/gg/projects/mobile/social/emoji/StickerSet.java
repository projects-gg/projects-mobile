package gg.projects.mobile.social.emoji;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A Projects sticker set (CHAT2 section 8; the desktop's packages/stickers): set pl1 is "Kup" in
 * 32 stickers and four themes. A message carries only the reference ("pl1/gg"); the phone draws
 * the sticker from {@code res/drawable-nodpi/pl1_<id>.webp}. The manifest
 * ({@code assets/stickers/pl1.json}) is a copy of the desktop's and is checked whole, as the
 * desktop's parseStickerSet does: a broken one throws, so the app never meets half a set.
 */
public final class StickerSet {
    /** The manifest of the one set that comes with this build. */
    public static final String PL1_ASSET = "stickers/pl1.json";

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    /** A theme, in the set's order. */
    public static final class Theme {
        public final String id;
        public final String nameTr;
        public final String nameEn;

        Theme(String id, String nameTr, String nameEn) {
            this.id = id;
            this.nameTr = nameTr;
            this.nameEn = nameEn;
        }

        /** Its name in "tr" or "en" (anything else is Turkish). */
        public String name(String lang) {
            return EmojiData.ENGLISH.equals(lang) ? nameEn : nameTr;
        }
    }

    /** One sticker. */
    public static final class Sticker {
        /** What a message carries: "pl1/gg". */
        public final String ref;
        public final String set;
        public final String id;
        public final String theme;
        /** Place in the set, from 1. */
        public final int order;
        public final String nameTr;
        public final String nameEn;
        public final List<String> keywordsTr;
        public final List<String> keywordsEn;
        /** The words on its plate, if any ("GG"). */
        @Nullable
        public final String caption;
        /** Its pattern's name ({@link StickerMotion#of}). */
        public final String motion;
        /** Its cell in the overlay's atlas. */
        public final int atlasColumn;
        public final int atlasRow;
        /** Its drawable's name ("pl1_gg"). */
        public final String drawable;

        Sticker(String ref, String set, String id, String theme, int order, String nameTr,
                String nameEn, List<String> keywordsTr, List<String> keywordsEn,
                @Nullable String caption, String motion, int atlasColumn, int atlasRow,
                String drawable) {
            this.ref = ref;
            this.set = set;
            this.id = id;
            this.theme = theme;
            this.order = order;
            this.nameTr = nameTr;
            this.nameEn = nameEn;
            this.keywordsTr = keywordsTr;
            this.keywordsEn = keywordsEn;
            this.caption = caption;
            this.motion = motion;
            this.atlasColumn = atlasColumn;
            this.atlasRow = atlasRow;
            this.drawable = drawable;
        }

        /** Its name in "tr" or "en" (anything else is Turkish). */
        public String name(String lang) {
            return EmojiData.ENGLISH.equals(lang) ? nameEn : nameTr;
        }

        public List<String> keywords(String lang) {
            return EmojiData.ENGLISH.equals(lang) ? keywordsEn : keywordsTr;
        }

        @Override
        public String toString() {
            return ref;
        }
    }

    private final String id;
    private final int version;
    private final String drawablePrefix;
    private final List<Theme> themes;
    private final List<Sticker> stickers;
    private final Map<String, String> motions;
    private final Map<String, Sticker> byRef = new HashMap<>();

    private StickerSet(String id, int version, String drawablePrefix, List<Theme> themes,
            List<Sticker> stickers, Map<String, String> motions) {
        this.id = id;
        this.version = version;
        this.drawablePrefix = drawablePrefix;
        this.themes = Collections.unmodifiableList(themes);
        this.stickers = Collections.unmodifiableList(stickers);
        this.motions = Collections.unmodifiableMap(motions);
        for (Sticker sticker : stickers) byRef.put(sticker.ref, sticker);
    }

    /** The set's name ("pl1"). */
    public String id() {
        return id;
    }

    public int version() {
        return version;
    }

    /** The themes in the set's order. */
    public List<Theme> themes() {
        return themes;
    }

    /** The stickers in the set's order. */
    public List<Sticker> stickers() {
        return stickers;
    }

    /** One theme's stickers, in order. */
    public List<Sticker> inTheme(String themeId) {
        List<Sticker> out = new ArrayList<>();
        for (Sticker sticker : stickers) {
            if (sticker.theme.equals(themeId)) out.add(sticker);
        }
        return out;
    }

    /** What each pattern does, in words (the manifest's {@code motions}). */
    public Map<String, String> motions() {
        return motions;
    }

    /** The sticker a message names, or null for one this build does not have (a newer set). */
    @Nullable
    public Sticker byRef(@Nullable String ref) {
        return ref == null ? null : byRef.get(ref);
    }

    /** Whether the reference names a sticker of this set (the service's allow list). */
    public boolean isKnown(@Nullable String ref) {
        return isRef(ref) && byRef.containsKey(ref);
    }

    /** The recent references that name a sticker of this set, in their order (unknown ones left out). */
    public List<Sticker> recent(List<String> refs) {
        List<Sticker> out = new ArrayList<>();
        for (String ref : refs) {
            Sticker sticker = byRef(ref);
            if (sticker != null) out.add(sticker);
        }
        return out;
    }

    /** The drawable's name of a sticker id ("pl1_gg"; Resources.getIdentifier takes it). */
    public String drawableName(String stickerId) {
        return drawablePrefix + stickerId.replace('-', '_');
    }

    /**
     * A sticker reference as the service accepts one (chat2.md section 8.4):
     * {@code ^[a-z0-9]{1,8}/[a-z0-9-]{1,24}$}.
     */
    public static boolean isRef(@Nullable String ref) {
        if (ref == null) return false;
        int slash = ref.indexOf('/');
        if (slash < 1 || slash > 8 || ref.length() - slash - 1 < 1 || ref.length() - slash - 1 > 24) {
            return false;
        }
        for (int i = 0; i < ref.length(); i++) {
            char c = ref.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || (i > slash && c == '-') || i == slash;
            if (!ok) return false;
        }
        return true;
    }

    private static boolean isSetName(String set) {
        if (set.isEmpty() || set.length() > 8) return false;
        for (int i = 0; i < set.length(); i++) {
            char c = set.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))) return false;
        }
        return true;
    }

    private static boolean isStickerId(String id) {
        if (id.isEmpty() || id.length() > 24) return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-')) return false;
        }
        return true;
    }

    // --- Reading a manifest --------------------------------------------------------------------

    /** Reads a set's manifest (UTF-8; the caller closes the stream). See {@link #parse(JsonElement)}. */
    public static StickerSet parse(InputStream in) throws IOException {
        return parse(new InputStreamReader(in, UTF_8));
    }

    public static StickerSet parse(Reader reader) throws IOException {
        JsonElement json;
        try {
            json = JsonParser.parseReader(reader);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Sticker manifest: not JSON", e);
        }
        return parse(json);
    }

    public static StickerSet parse(String text) {
        JsonElement json;
        try {
            json = JsonParser.parseString(text);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Sticker manifest: not JSON", e);
        }
        return parse(json);
    }

    private static IllegalArgumentException fail(String message) {
        return new IllegalArgumentException("Sticker manifest: " + message);
    }

    @Nullable
    private static JsonObject record(@Nullable JsonElement value) {
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static String text(@Nullable JsonElement value, String what) {
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            String text = value.getAsString();
            if (!EmojiChars.jsTrim(text).isEmpty()) return text;
        }
        throw fail(what + " is not a text");
    }

    private static List<String> texts(@Nullable JsonElement value, String what) {
        if (value == null || !value.isJsonArray()) throw fail(what + " is not a list of texts");
        List<String> out = new ArrayList<>();
        for (JsonElement item : value.getAsJsonArray()) {
            if (item == null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()
                    || EmojiChars.jsTrim(item.getAsString()).isEmpty()) {
                throw fail(what + " is not a list of texts");
            }
            out.add(item.getAsString());
        }
        return Collections.unmodifiableList(out);
    }

    private static int count(@Nullable JsonElement value, String what) {
        if (value != null && value.isJsonPrimitive()) {
            JsonPrimitive primitive = value.getAsJsonPrimitive();
            if (primitive.isNumber()) {
                double number = primitive.getAsDouble();
                if (number >= 0 && number == Math.floor(number) && number <= Integer.MAX_VALUE) {
                    return (int) number;
                }
            }
        }
        throw fail(what + " is not a count");
    }

    /**
     * Reads a set's manifest and checks it whole (the desktop's checks, and the phone's drawable
     * names): a broken one throws {@link IllegalArgumentException} ("Sticker manifest: ...").
     */
    public static StickerSet parse(@Nullable JsonElement json) {
        JsonObject root = record(json);
        if (root == null) throw fail("not an object");
        String set = text(root.get("set"), "set");
        if (!isSetName(set)) throw fail("set \"" + set + "\" is not a set name");
        int version = count(root.get("version"), "version");
        if (version != 1) throw fail("version " + version + " is unknown");

        JsonElement themesJson = root.get("themes");
        if (themesJson == null || !themesJson.isJsonArray() || themesJson.getAsJsonArray().size() == 0) {
            throw fail("no themes");
        }
        List<Theme> themes = new ArrayList<>();
        Set<String> themeIds = new HashSet<>();
        JsonArray themeArray = themesJson.getAsJsonArray();
        for (int index = 0; index < themeArray.size(); index++) {
            JsonObject theme = record(themeArray.get(index));
            if (theme == null) throw fail("theme " + index + " is not an object");
            Theme read = new Theme(text(theme.get("id"), "theme " + index + " id"),
                    text(theme.get("tr"), "theme " + index + " tr"),
                    text(theme.get("en"), "theme " + index + " en"));
            themes.add(read);
            themeIds.add(read.id);
        }
        if (themeIds.size() != themes.size()) throw fail("two themes share an id");

        JsonObject formats = record(root.get("formats"));
        JsonObject overlay = formats == null ? null : record(formats.get("overlay"));
        if (overlay == null) throw fail("no overlay format");
        int columns = count(overlay.get("columns"), "atlas columns");
        int rows = count(overlay.get("rows"), "atlas rows");
        JsonObject android = record(formats.get("android"));
        String prefix = set + "_";
        if (android != null && android.has("prefix")) prefix = text(android.get("prefix"), "android prefix");
        JsonObject motionsJson = record(root.get("motions"));
        if (motionsJson == null) throw fail("no motions");
        Map<String, String> motions = new LinkedHashMap<>();
        for (String motion : StickerMotion.NAMES) {
            motions.put(motion, text(motionsJson.get(motion), "motion " + motion));
        }

        JsonElement stickersJson = root.get("stickers");
        if (stickersJson == null || !stickersJson.isJsonArray()
                || stickersJson.getAsJsonArray().size() == 0) {
            throw fail("no stickers");
        }
        JsonArray stickerArray = stickersJson.getAsJsonArray();
        Set<String> cells = new HashSet<>();
        List<Sticker> stickers = new ArrayList<>();
        for (int index = 0; index < stickerArray.size(); index++) {
            JsonObject raw = record(stickerArray.get(index));
            if (raw == null) throw fail("sticker " + index + " is not an object");
            String id = text(raw.get("id"), "sticker " + index + " id");
            if (!isStickerId(id)) throw fail("id \"" + id + "\" is not a sticker id");
            String ref = text(raw.get("ref"), id + " ref");
            if (!ref.equals(set + "/" + id) || !isRef(ref)) throw fail(id + ": ref \"" + ref + "\"");
            String theme = text(raw.get("theme"), id + " theme");
            if (!themeIds.contains(theme)) throw fail(id + ": unknown theme \"" + theme + "\"");
            JsonObject name = record(raw.get("name"));
            JsonObject keywords = record(raw.get("keywords"));
            if (name == null || keywords == null) throw fail(id + ": no name or keywords");
            String motion = text(raw.get("motion"), id + " motion");
            if (!StickerMotion.isKnown(motion)) throw fail(id + ": unknown motion \"" + motion + "\"");
            JsonElement captionJson = raw.get("caption");
            String caption = null;
            if (captionJson == null || !captionJson.isJsonNull()) caption = text(captionJson, id + " caption");
            JsonObject files = record(raw.get("files"));
            JsonObject launcher = files == null ? null : record(files.get("launcher"));
            if (launcher == null) throw fail(id + ": no launcher image");
            JsonElement launcherPath = launcher.get("path");
            if (launcherPath == null || !launcherPath.isJsonPrimitive()
                    || !("launcher/" + id + ".webp").equals(launcherPath.getAsString())) {
                throw fail(id + ": launcher image path");
            }
            String drawable = prefix + id.replace('-', '_');
            JsonObject androidFile = record(files.get("android"));
            if (androidFile != null) {
                JsonElement path = androidFile.get("path");
                if (path == null || !path.isJsonPrimitive()
                        || !("android/" + drawable + ".webp").equals(path.getAsString())) {
                    throw fail(id + ": android image path");
                }
            }
            JsonObject atlas = record(raw.get("atlas"));
            if (atlas == null) throw fail(id + ": no atlas cell");
            int column = count(atlas.get("column"), id + " atlas column");
            int row = count(atlas.get("row"), id + " atlas row");
            if (column >= columns || row >= rows) throw fail(id + ": atlas cell outside the atlas");
            if (!cells.add(column + "," + row)) throw fail(id + ": atlas cell taken");
            stickers.add(new Sticker(ref, set, id, theme, count(raw.get("order"), id + " order"),
                    text(name.get("tr"), id + " name tr"), text(name.get("en"), id + " name en"),
                    texts(keywords.get("tr"), id + " keywords tr"),
                    texts(keywords.get("en"), id + " keywords en"),
                    caption, motion, column, row, drawable));
        }
        Set<String> ids = new HashSet<>();
        for (Sticker sticker : stickers) ids.add(sticker.id);
        if (ids.size() != stickers.size()) throw fail("two stickers share an id");
        List<Integer> orders = new ArrayList<>();
        for (Sticker sticker : stickers) orders.add(sticker.order);
        Collections.sort(orders);
        for (int i = 0; i < orders.size(); i++) {
            if (orders.get(i) != i + 1) throw fail("the order is not 1, 2, 3...");
        }
        Collections.sort(stickers, new Comparator<Sticker>() {
            @Override
            public int compare(Sticker a, Sticker b) {
                return a.order - b.order;
            }
        });
        return new StickerSet(set, version, prefix, themes, stickers, motions);
    }
}
