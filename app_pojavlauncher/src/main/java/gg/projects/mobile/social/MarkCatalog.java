package gg.projects.mobile.social;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * BADGES on the phone (docs: badges.md "Tel biçimi" and "BADGES-UI için"; SOC-VERIFY's single mark grown into a
 * catalogue): what Projects gives an account, right of its name.
 *
 * <ul>
 * <li>Cards carry the keys of the badges they show ({@code marks}, in the catalogue's order: the first is the most
 * important) beside SOC-VERIFY's {@code mark}; a service from before badges sends only {@code mark}, read as a list of
 * one ({@link #keysOf}: the built-in catalogue knows both of its keys).</li>
 * <li>The catalogue ({@code GET /v1/marks}, no session) names each badge in Turkish and English and says how it looks.
 * The app carries the service's built-in one ({@link #BUILTIN}, revision 0), so a first view draws right before or
 * without the network, and keeps the last one it read ({@link MarkStore}).</li>
 * <li>Colours, glyphs and shapes are fixed sets drawn for each pixel grid ({@link MarkGeometry}); a badge with a look this
 * version does not know comes from a newer catalogue and is left out, as is a key the catalogue does not name.</li>
 * </ul>
 * Pure Java, with tests.
 */
public final class MarkCatalog {
    /** Badges a catalogue or a card holds at most (one bit each of the service's 31-bit set). */
    public static final int MAX_MARKS = 31;
    /** A key's characters at most: {@code admin}, {@code verified}, {@code m12}. */
    static final int MAX_KEY_CHARS = 24;
    /** A name's characters at most, in either language. */
    static final int MAX_NAME_CHARS = 32;
    private static final Pattern KEY = Pattern.compile("[a-z0-9_-]{1," + MAX_KEY_CHARS + "}");

    /** The palette ({@code --color-mark-<name>}): white glyphs ≥ 3.5:1, ≥ 4.4:1 on obsidian-900 (badges.md). */
    public static final List<String> COLORS = Collections.unmodifiableList(Arrays.asList(
            "blue", "red", "purple", "green", "orange", "teal", "indigo", "pink", "slate"));
    private static final int[] RGB = {
            0x2a8af0, 0xe0434a, 0x9b5eeb, 0x219453, 0xd95f1a, 0x138d89, 0x6673e8, 0xdf378b, 0x7f7a9f};
    public static final List<String> GLYPHS = Collections.unmodifiableList(Arrays.asList(
            "check", "star", "cloud", "bolt", "crown", "heart", "flask", "play", "trophy"));
    public static final List<String> SHAPES = Collections.unmodifiableList(Arrays.asList("seal", "round", "shield"));

    /** One badge of the catalogue: its key (what cards carry), its two names and its look. */
    public static final class Def {
        public final String key;
        public final String nameTr;
        public final String nameEn;
        public final String color;
        public final String glyph;
        public final String shape;

        public Def(String key, String nameTr, String nameEn, String color, String glyph, String shape) {
            this.key = key;
            this.nameTr = nameTr;
            this.nameEn = nameEn;
            this.color = color;
            this.glyph = glyph;
            this.shape = shape;
        }

        /** "Projects yöneticisi" / "Projects admin". */
        public String name(boolean turkish) {
            return turkish ? nameTr : nameEn;
        }

        /** The colour, opaque ARGB. */
        public int argb() {
            return 0xff000000 | RGB[COLORS.indexOf(color)];
        }

        /** A badge as the catalogue lists it; null when this version cannot show it (see {@link #read(JsonObject)}). */
        @Nullable
        static Def read(@Nullable JsonObject o) {
            String key = Json.str(o, "key");
            JsonObject name = Json.obj(o, "name");
            String tr = Json.str(name, "tr");
            String en = Json.str(name, "en");
            String color = Json.str(o, "color");
            String glyph = Json.str(o, "glyph");
            String shape = Json.str(o, "shape");
            if (!isKey(key) || !readable(tr) || !readable(en) || !COLORS.contains(color) || !GLYPHS.contains(glyph)
                    || !SHAPES.contains(shape)) {
                return null;
            }
            return new Def(key, tr, en, color, glyph, shape);
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("key", key);
            JsonObject name = new JsonObject();
            name.addProperty("tr", nameTr);
            name.addProperty("en", nameEn);
            o.add("name", name);
            o.addProperty("color", color);
            o.addProperty("glyph", glyph);
            o.addProperty("shape", shape);
            return o;
        }
    }

    /** The catalogue's revision ({@code marksRev} in the list, {@code rev} of {@code markdefs}). */
    public final long rev;
    /** The badges that show, in order: the first is the most important. */
    public final List<Def> defs;

    public MarkCatalog(long rev, List<Def> defs) {
        this.rev = rev;
        this.defs = Collections.unmodifiableList(new ArrayList<>(defs));
    }

    /**
     * The service's catalogue until Studio's owner first saves one (revision 0): the built-in badges that show, in its
     * order (its four extras ship switched off). Authority marks first: the badge beside a name says who someone is.
     */
    public static final MarkCatalog BUILTIN = new MarkCatalog(0, Arrays.asList(
            new Def("admin", "Projects yöneticisi", "Projects admin", "red", "check", "seal"),
            new Def("moderator", "Projects moderatörü", "Projects moderator", "purple", "check", "seal"),
            new Def("staff", "Projects personeli", "Projects staff", "green", "check", "seal"),
            new Def("verified", "Doğrulanmış hesap", "Verified account", "blue", "check", "seal"),
            new Def("premium", "Premium Club üyesi", "Premium Club member", "orange", "star", "seal")));

    /**
     * {@code GET /v1/marks}: {@code {"rev": 3, "marks": [{key, name: {tr, en}, color, glyph, shape}, …]}}. Badges this
     * version cannot draw (a newer colour, glyph or shape), with a malformed key or name, or named twice are left out;
     * null for anything that is not a catalogue.
     */
    @Nullable
    public static MarkCatalog read(@Nullable JsonObject o) {
        if (o == null || !isNumber(o.get("rev"))) return null;
        JsonElement list = o.get("marks");
        if (list == null || !list.isJsonArray()) return null;
        List<Def> defs = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        int seen = 0;
        for (JsonElement e : list.getAsJsonArray()) {
            if (seen++ >= MAX_MARKS) break;
            Def def = e.isJsonObject() ? Def.read(e.getAsJsonObject()) : null;
            if (def != null && keys.add(def.key)) defs.add(def);
        }
        return new MarkCatalog(o.get("rev").getAsLong(), defs);
    }

    /** The catalogue as the service sends it (what {@link MarkStore} keeps; {@link #read} reads it back). */
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("rev", rev);
        JsonArray list = new JsonArray();
        for (Def def : defs) list.add(def.toJson());
        o.add("marks", list);
        return o;
    }

    /** The badge with this key, when this catalogue shows it. */
    @Nullable
    public Def get(@Nullable String key) {
        if (key == null) return null;
        for (Def def : defs) if (def.key.equals(key)) return def;
        return null;
    }

    /** Whether every key names a badge of this catalogue (a card naming another was made with a newer catalogue). */
    public boolean knowsAll(List<String> keys) {
        for (String key : keys) if (get(key) == null) return false;
        return true;
    }

    /** The badges of {@code keys} this catalogue shows, in its order (the most important first). */
    public List<Def> shown(@Nullable List<String> keys) {
        if (keys == null || keys.isEmpty()) return Collections.emptyList();
        List<Def> out = new ArrayList<>();
        for (Def def : defs) if (keys.contains(def.key)) out.add(def);
        return out;
    }

    /** The badge right of a name: the first one {@link #shown} (null: none to draw). */
    @Nullable
    public Def first(@Nullable List<String> keys) {
        if (keys == null) return null;
        for (Def def : defs) if (keys.contains(def.key)) return def;
        return null;
    }

    // ---- cards and frames ----

    /** {@code admin}, {@code verified}, {@code m12}: lower-case letters, digits, {@code -} and {@code _}. */
    public static boolean isKey(@Nullable String key) {
        return key != null && KEY.matcher(key).matches();
    }

    /**
     * The badges a card (or a {@code marks} / {@code mark} frame) shows: its {@code marks}, else SOC-VERIFY's
     * {@code mark} from a service without badges. Anything unreadable reads as no badge; never null.
     */
    public static List<String> keysOf(@Nullable JsonObject card) {
        if (card == null) return Collections.emptyList();
        List<String> keys = readKeys(card.get("marks"));
        if (!keys.isEmpty()) return keys;
        String mark = Json.str(card, "mark");
        if ("admin".equals(mark) || "verified".equals(mark)) return Collections.singletonList(mark);
        return Collections.emptyList();
    }

    /**
     * The player's own hidden badges ({@code me.marksHidden}); null when the answer has none (a service before badges:
     * the player cannot hide anything there).
     */
    @Nullable
    public static List<String> hiddenOf(@Nullable JsonObject me) {
        if (me == null || !me.has("marksHidden")) return null;
        return readKeys(me.get("marksHidden"));
    }

    /** A list of keys as it came: readable keys only, at most {@link #MAX_MARKS}, no repeats, in its order. */
    static List<String> readKeys(@Nullable JsonElement value) {
        if (value == null || !value.isJsonArray()) return Collections.emptyList();
        List<String> keys = new ArrayList<>();
        int seen = 0;
        for (JsonElement e : value.getAsJsonArray()) {
            if (seen++ >= MAX_MARKS) break;
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) continue;
            String key = e.getAsString();
            if (isKey(key) && !keys.contains(key)) keys.add(key);
        }
        return keys.isEmpty() ? Collections.<String>emptyList() : Collections.unmodifiableList(keys);
    }

    /** What a change of shown badges means for a row (its diff key); "" without badges. */
    public static String key(@Nullable List<String> keys) {
        if (keys == null || keys.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (String key : keys) out.append(key).append(',');
        return out.toString();
    }

    // ---- words ----

    /**
     * What TalkBack says for a person: their name, then every badge's name in order ("Ayşe, Projects yöneticisi,
     * Premium Club üyesi"); the name alone without badges.
     */
    public static String spoken(String name, List<Def> marks, boolean turkish) {
        StringBuilder out = new StringBuilder(name);
        for (Def def : marks) out.append(", ").append(def.name(turkish));
        return out.toString();
    }

    // ---- the player's own badges ("Rozetlerim") ----

    /** One of the player's badges as "Rozetlerim" lists it: shown on their profile, or hidden by them. */
    public static final class Own {
        public final Def def;
        public final boolean shown;

        Own(Def def, boolean shown) {
            this.def = def;
            this.shown = shown;
        }
    }

    /**
     * The player's badges this catalogue shows, in its order: those others see ({@code me.marks}) and those the player
     * hides ({@code me.marksHidden}). Empty when there are none: "Rozetlerim" is not there then.
     */
    public List<Own> own(@Nullable List<String> shown, @Nullable List<String> hidden) {
        List<Own> out = new ArrayList<>();
        for (Def def : defs) {
            if (shown != null && shown.contains(def.key)) out.add(new Own(def, true));
            else if (hidden != null && hidden.contains(def.key)) out.add(new Own(def, false));
        }
        return out;
    }

    /** The hidden keys after showing ({@code show}) or hiding {@code key}; the others stay as they were. */
    public static List<String> hiddenAfter(@Nullable List<String> hidden, String key, boolean show) {
        List<String> next = new ArrayList<>();
        if (hidden != null) for (String k : hidden) if (!k.equals(key)) next.add(k);
        if (!show) next.add(key);
        return next;
    }

    /** {@code PATCH /v1/me}: {@code {"marksHidden": [...]}} (unknown keys are ignored there, {@code []} shows all). */
    public static JsonObject hiddenBody(List<String> hidden) {
        JsonArray list = new JsonArray();
        for (String key : hidden) list.add(new JsonPrimitive(key));
        JsonObject body = new JsonObject();
        body.add("marksHidden", list);
        return body;
    }

    // ---- helpers ----

    /** A name to show: 1–32 characters, no control characters. */
    static boolean readable(@Nullable String name) {
        if (name == null) return false;
        int count = name.codePointCount(0, name.length());
        if (count < 1 || count > MAX_NAME_CHARS || name.trim().isEmpty()) return false;
        for (int i = 0; i < name.length(); ) {
            int cp = name.codePointAt(i);
            if (Character.isISOControl(cp)) return false;
            i += Character.charCount(cp);
        }
        return true;
    }

    private static boolean isNumber(@Nullable JsonElement e) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return false;
        double v = e.getAsDouble();
        return v == Math.rint(v) && v >= 0;
    }
}
