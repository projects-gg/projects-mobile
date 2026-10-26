package gg.projects.mobile.cloud.items;

import androidx.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import gg.projects.mobile.social.Json;

/**
 * Item pictures (ITEMART, docs/design.md §6 "Item sprites"): the index of an atlas drawn from the player's own Minecraft
 * client jar by {@code pl-items} (index format 1, the launcher's and the site's), the order the pictures are chosen in,
 * and the quiet stand-ins (one flat silhouette per category: a block's three faces, an apple, a book, else a tied sack)
 * for an item without a picture. Pure, so the tests read the same index the drawing does.
 */
public final class ItemIndex {
    /** The index format this app reads; a newer one is refused (the stand-ins show). */
    public static final int FORMAT = 1;
    /** The largest 3D icon drawn (bigger ones are scaled up by whole numbers): keeps an atlas within a few megabytes. */
    public static final int MAX_BLOCK = 48;

    /**
     * The size 3D icons are drawn at for items {@code itemPx} device pixels wide: {@code itemPx} itself when it is at most
     * {@link #MAX_BLOCK}, else its largest whole fraction that is (80 → 40, 96 → 48, 128 → 32), so a drawing is scaled
     * by a whole number and its pixels stay square.
     */
    public static int drawingSize(int itemPx) {
        if (itemPx <= 16) return 16;
        if (itemPx <= MAX_BLOCK) return itemPx;
        for (int k = 2; k <= itemPx; k++) {
            if (itemPx % k == 0 && itemPx / k <= MAX_BLOCK) return Math.max(16, itemPx / k);
        }
        return 32;
    }

    public static final class Layer {
        /** x, y, width, height in the atlas. */
        public final int[] rect;
        /** What colours it ({@code potion}, {@code dye}, …), or null for a plain layer. */
        @Nullable
        public final String tint;
        /** The colour when the server sends none (0xRRGGBB), or -1: the layer is then not drawn. */
        public final int tintDefault;

        Layer(int[] rect, @Nullable String tint, int tintDefault) {
            this.rect = rect;
            this.tint = tint;
            this.tintDefault = tintDefault;
        }
    }

    public static final class Icon {
        /** {@code flat}, {@code block} or {@code special}. */
        public final String kind;
        public final List<Layer> layers;
        public final boolean dark;
        /** The larger drawing of a 3D icon in the large atlas, or null. */
        @Nullable
        public final int[] large;

        Icon(String kind, List<Layer> layers, boolean dark, @Nullable int[] large) {
            this.kind = kind;
            this.layers = layers;
            this.dark = dark;
            this.large = large;
        }

        public boolean flat() {
            return "flat".equals(kind);
        }
    }

    public final String minecraft;
    public final String image;
    public final int[] size;
    @Nullable
    public final int[] glint;
    @Nullable
    public final String largeImage;
    public final int largeIcon;
    private final Map<String, Icon> mIcons;

    private ItemIndex(String minecraft, String image, int[] size, @Nullable int[] glint, @Nullable String largeImage, int largeIcon,
                      Map<String, Icon> icons) {
        this.minecraft = minecraft;
        this.image = image;
        this.size = size;
        this.glint = glint;
        this.largeImage = largeImage;
        this.largeIcon = largeIcon;
        mIcons = icons;
    }

    @Nullable
    private static int[] ints(@Nullable JsonElement e, int n) {
        if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() != n) return null;
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            JsonElement v = e.getAsJsonArray().get(i);
            if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) return null;
            out[i] = v.getAsInt();
            if (out[i] < 0) return null;
        }
        return out;
    }

    /** Reads an index; null when it is not one this app can draw from (another format, no image, broken). */
    @Nullable
    public static ItemIndex read(@Nullable JsonObject o) {
        if (o == null || Json.num(o, "format", 0) != FORMAT) return null;
        String image = Json.str(o, "image");
        int[] size = ints(o.get("size"), 2);
        JsonObject icons = Json.obj(o, "icons");
        if (image == null || size == null || icons == null) return null;
        Map<String, Icon> map = new HashMap<>();
        for (Map.Entry<String, JsonElement> e : icons.entrySet()) {
            if (!e.getValue().isJsonObject()) continue;
            JsonObject io = e.getValue().getAsJsonObject();
            List<Layer> layers = new ArrayList<>();
            JsonElement le = io.get("layers");
            if (le == null || !le.isJsonArray()) continue;
            boolean ok = true;
            for (JsonElement l : le.getAsJsonArray()) {
                if (!l.isJsonObject()) {
                    ok = false;
                    break;
                }
                int[] rect = ints(l.getAsJsonObject().get("rect"), 4);
                if (rect == null || rect[0] + rect[2] > size[0] || rect[1] + rect[3] > size[1]) {
                    ok = false;
                    break;
                }
                JsonObject tint = Json.obj(l.getAsJsonObject(), "tint");
                layers.add(new Layer(rect, Json.str(tint, "source"), color(Json.str(tint, "default"))));
            }
            if (!ok || layers.isEmpty()) continue;
            map.put(e.getKey(), new Icon(Json.str(io, "kind", "flat"), Collections.unmodifiableList(layers), Json.bool(io, "dark", false),
                    ints(io.get("large"), 4)));
        }
        JsonObject large = Json.obj(o, "large");
        return new ItemIndex(Json.str(o, "minecraft", ""), image, size, ints(o.get("glint"), 4), Json.str(large, "image"),
                (int) Json.num(large, "icon", 0), Collections.unmodifiableMap(map));
    }

    /** {@code #rrggbb} → 0xRRGGBB, or -1. */
    static int color(@Nullable String hex) {
        if (hex == null || !hex.matches("^#[0-9a-fA-F]{6}$")) return -1;
        return Integer.parseInt(hex.substring(1), 16);
    }

    @Nullable
    public Icon icon(String id) {
        return mIcons.get(fullId(id));
    }

    public int count() {
        return mIcons.size();
    }

    /** "elytra" → "minecraft:elytra"; ids are compared in lower case. */
    public static String fullId(String id) {
        String lower = id.trim().toLowerCase(Locale.ROOT);
        return lower.indexOf(':') >= 0 ? lower : "minecraft:" + lower;
    }

    // ---- colours the server gives ----

    private static final int SLOWNESS = 0x8bafe0;
    private static final int RESISTANCE = 0x9146f0;

    private static int mix(int a, int wa, int b, int wb) {
        int out = 0;
        for (int shift = 16; shift >= 0; shift -= 8) {
            int ch = Math.round((((a >> shift) & 255) * wa + ((b >> shift) & 255) * wb) / (float) (wa + wb));
            out |= ch << shift;
        }
        return out;
    }

    private static final Map<String, Integer> POTION_COLORS = new HashMap<>();

    static {
        POTION_COLORS.put("night_vision", 0xc2ff66);
        POTION_COLORS.put("invisibility", 0xf6f6f6);
        POTION_COLORS.put("leaping", 0xfdff84);
        POTION_COLORS.put("fire_resistance", 0xff9900);
        POTION_COLORS.put("swiftness", 0x33ebff);
        POTION_COLORS.put("slowness", SLOWNESS);
        POTION_COLORS.put("turtle_master", mix(SLOWNESS, 4, RESISTANCE, 3));
        POTION_COLORS.put("water_breathing", 0x98dac0);
        POTION_COLORS.put("healing", 0xf82423);
        POTION_COLORS.put("harming", 0xa9656a);
        POTION_COLORS.put("poison", 0x87a363);
        POTION_COLORS.put("regeneration", 0xcd5cab);
        POTION_COLORS.put("strength", 0xffc700);
        POTION_COLORS.put("weakness", 0x484d48);
        POTION_COLORS.put("luck", 0x59c106);
        POTION_COLORS.put("slow_falling", 0xf3cfb9);
        POTION_COLORS.put("wind_charged", 0xbdc9ff);
        POTION_COLORS.put("weaving", 0x78695a);
        POTION_COLORS.put("oozing", 0x99ffa3);
        POTION_COLORS.put("infested", 0x8c9b8c);
    }

    /** A potion's colour from its id ({@code long_}/{@code strong_} ignored), or -1. */
    public static int potionColor(@Nullable String potion) {
        if (potion == null) return -1;
        String path = fullId(potion).substring(fullId(potion).indexOf(':') + 1).replaceFirst("^(long|strong)_", "");
        Integer c = POTION_COLORS.get(path);
        return c == null ? -1 : c;
    }

    /** A tinted layer's colour for an item: what the server sent, else the layer's default; -1: not drawn. */
    public static int layerColor(Layer layer, @Nullable Integer itemColor, @Nullable String potion) {
        int custom = itemColor == null ? -1 : itemColor & 0xffffff;
        int picked = "potion".equals(layer.tint) ? (custom >= 0 ? custom : potionColor(potion)) : custom;
        return picked >= 0 ? picked : layer.tintDefault;
    }

    private static final Set<String> ALWAYS_GLINT = new HashSet<>(Arrays.asList("minecraft:enchanted_book",
            "minecraft:enchanted_golden_apple", "minecraft:experience_bottle", "minecraft:nether_star", "minecraft:end_crystal",
            "minecraft:written_book", "minecraft:debug_stick"));

    /** Whether the item shows the glint: as the server says, else as the game draws that item. */
    public static boolean glints(String id, boolean enchanted) {
        return enchanted || ALWAYS_GLINT.contains(fullId(id));
    }

    // ---- stand-ins ----

    public enum Category { BLOCK, FOOD, BOOK, OTHER }

    private static final Pattern BOOK = Pattern.compile("book$");
    private static final Pattern FOOD = Pattern.compile("apple|bread|beef|porkchop|chicken|mutton|rabbit|cod|salmon|fish|potato|carrot|"
            + "beetroot|melon_slice|berries|cookie|cake$|_pie$|_stew$|_soup$|kelp|^chorus_fruit$|^honey_bottle$|^rotten_flesh$|^spider_eye$");
    private static final Pattern NOT_FOOD = Pattern.compile("potion$|_(helmet|chestplate|leggings|boots|horse_armor)$|^elytra$|^wolf_armor$"
            + "|_(sword|spear)$|arrow$|^(bow|crossbow|trident|mace)$|_(pickaxe|axe|shovel|hoe|bucket|on_a_stick)$"
            + "|^(shears|brush|fishing_rod|flint_and_steel|spyglass|compass|clock|lead)$");
    private static final Pattern BLOCK = Pattern.compile("_(block|planks|log|wood|stem|hyphae|ore|bricks|tiles|slab|stairs|wall|fence|"
            + "fence_gate|door|trapdoor|wool|carpet|concrete|concrete_powder|terracotta|glass|glass_pane|leaves|pillar|lantern|bars|button|"
            + "pressure_plate|table|bed|shulker_box|copper)$|stone$|sand$|dirt$|deepslate$|^(gravel|obsidian|netherrack|pumpkin|melon|sponge|glass)$");

    /**
     * The stand-in's shape for an id (the launcher's rules, in their order): tools, weapons, armour and potions draw the
     * sack like anything else (their own silhouettes are the game's empty-slot hints).
     */
    public static Category category(String id) {
        String full = fullId(id);
        String path = full.substring(full.indexOf(':') + 1);
        if (path.endsWith("potion")) return Category.OTHER;
        if (BOOK.matcher(path).find()) return Category.BOOK;
        if (NOT_FOOD.matcher(path).find()) return Category.OTHER;
        if (FOOD.matcher(path).find()) return Category.FOOD;
        if (BLOCK.matcher(path).find()) return Category.BLOCK;
        return Category.OTHER;
    }

    private static final String[] SACK = {
            "................",
            ".....##..##.....",
            "......####......",
            ".......##.......",
            "......####......",
            ".....######.....",
            "....########....",
            "...##########...",
            "..############..",
            "..############..",
            "..############..",
            "..############..",
            "..############..",
            "...##########...",
            "....########....",
            "................"};
    private static final String[] CUBE = {
            ".......##.......",
            ".....######.....",
            "...##########...",
            ".##############.",
            ".#..########..#.",
            ".###..####..###.",
            ".#####....#####.",
            ".######.#######.",
            ".######.#######.",
            ".##############.",
            ".##############.",
            ".##############.",
            "...##########...",
            ".....######.....",
            ".......##.......",
            "................"};
    private static final String[] APPLE = {
            "................",
            ".........#......",
            "........#.......",
            "...####.#.####..",
            "..############..",
            ".##############.",
            ".##############.",
            ".##############.",
            ".##############.",
            ".##############.",
            "..############..",
            "..############..",
            "...##########...",
            "....###..###....",
            "................",
            "................"};
    private static final String[] BOOK_ROWS = {
            "................",
            "................",
            "...#########....",
            "..##.########...",
            "..##.#########..",
            "..##.#########..",
            "..##.#########..",
            "..##.#########..",
            "..##.#########..",
            "..##.#########..",
            "..##.#########..",
            "..##.#########..",
            "..##.#########..",
            "..##.#########..",
            "...###########..",
            "................"};

    /** The stand-in's 16 × 16 silhouette, "#" lit. */
    public static String[] standIn(Category category) {
        switch (category) {
            case BLOCK: return CUBE;
            case FOOD: return APPLE;
            case BOOK: return BOOK_ROWS;
            default: return SACK;
        }
    }
}
