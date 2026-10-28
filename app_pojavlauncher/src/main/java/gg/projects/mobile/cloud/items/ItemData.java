package gg.projects.mobile.cloud.items;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import gg.projects.mobile.cloud.plus.ItemFacts;
import gg.projects.mobile.social.Json;

/**
 * One Minecraft version's item data (FREE-ITEMS, "The file (format 1)" in crates/pl-items data.rs; the port of the launcher's
 * and the site's TypeScript reader, {@code item-data.ts} of {@code @pl/ui/items}): every item with its stack size, durability,
 * Turkish and English names, the grey line the game shows under the few names that repeat, our aliases and the enchantments
 * that fit it, and every enchantment with its top level, curse flag and the enchantments it cannot share an item with.
 *
 * <p>The reader, the version choice ({@link #pickVersion}) and the search ({@link #search}) are the same as the TypeScript
 * ones, folding included ({@link #fold}), so a name typed on the phone finds what it finds in the launcher. The data is
 * immutable and safe to share between threads; the search tables are built on first use ({@link #warm} builds them ahead,
 * for a background thread). It is also the order checks' {@link ItemFacts} (by id).
 */
public final class ItemData implements ItemFacts {
    /** The file format this reads; a newer one is not read. */
    public static final int FORMAT = 1;
    /** The largest file a reader takes (a version's file is about 150 KB). */
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    /** The most hits a search returns when it is not given a limit. */
    public static final int DEFAULT_LIMIT = 20;
    /** The {@code limit} that says "none given": {@link #DEFAULT_LIMIT} hits (every enchantment for an empty query). */
    public static final int NO_LIMIT = -1;

    private static final Locale TURKISH = Locale.forLanguageTag("tr");
    private static final int DOTLESS_I = 0x0131;
    private static final double MAX_SAFE_INTEGER = 9007199254740991.0;
    private static final int MAX_ALIASES = 24;
    private static final int MAX_NAME = 240;
    private static final String VANILLA = "minecraft:";
    private static final String[] ROMAN = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
    private static final String[] NO_WORDS = new String[0];
    /** The rank of a typo match: below every other. */
    private static final int TYPO = 9;

    /** A name in both languages. */
    public static final class Names {
        public final String tr;
        public final String en;

        Names(String tr, String en) {
            this.tr = tr;
            this.en = en;
        }

        /** The name in {@code lang}: {@code "en"} is English, anything else Turkish. */
        public String get(@Nullable String lang) {
            return isEnglish(lang) ? en : tr;
        }
    }

    /** One item of the version. */
    public static final class ItemInfo {
        /** {@code minecraft:diamond_sword}: the order's item reference and the icon's key. */
        public final String id;
        /** The most one stack holds (1, 16, 64). */
        public final int stack;
        /** Durability when new; 0 when the item takes no damage. */
        public final int maxDamage;
        public final Names names;
        /** The grey line the game shows under a name that repeats ("Music Disc": "C418 - cat"); null for most items. */
        @Nullable
        public final Names desc;
        /** Our aliases, folded ("elitra", "kanat", "dia kilic"). */
        public final List<String> aliases;
        /** An operator's item no one gets in survival (command blocks, barrier): pickers leave it out. */
        public final boolean operator;
        private final int mGroup;
        private final ItemFacts.Item mFacts;

        private ItemInfo(String id, int stack, int maxDamage, Names names, @Nullable Names desc, List<String> aliases,
                         boolean operator, int group, ItemFacts.Item facts) {
            this.id = id;
            this.stack = stack;
            this.maxDamage = maxDamage;
            this.names = names;
            this.desc = desc;
            this.aliases = aliases;
            this.operator = operator;
            mGroup = group;
            mFacts = facts;
        }

        /** The item's name in {@code lang} ({@code "en"} is English, anything else Turkish). */
        public String name(@Nullable String lang) {
            return names.get(lang);
        }

        /** The grey line under the name in {@code lang}, or null when the item has none. */
        @Nullable
        public String description(@Nullable String lang) {
            return desc == null ? null : desc.get(lang);
        }

        /** The item as the order checks read it ({@link ItemFacts}). */
        public ItemFacts.Item facts() {
            return mFacts;
        }
    }

    /** One enchantment of the version. */
    public static final class EnchantmentInfo {
        public final String id;
        public final int maxLevel;
        public final Names names;
        /** Binding and vanishing: an order says whether a cursed item may pass. */
        public final boolean curse;
        public final List<String> aliases;
        private final int mIndex;
        private final int[] mExcludes;
        private ItemFacts.Enchantment mFacts;

        private EnchantmentInfo(String id, int maxLevel, Names names, boolean curse, List<String> aliases, int index,
                                int[] excludes) {
            this.id = id;
            this.maxLevel = maxLevel;
            this.names = names;
            this.curse = curse;
            this.aliases = aliases;
            mIndex = index;
            mExcludes = excludes;
        }

        /** The enchantment's name in {@code lang} ({@code "en"} is English, anything else Turkish). */
        public String name(@Nullable String lang) {
            return names.get(lang);
        }

        /** The enchantment as the order checks read it ({@link ItemFacts}). */
        public ItemFacts.Enchantment facts() {
            return mFacts;
        }
    }

    /** Something else a picker searches with the items: one of the account's custom items. */
    public static final class Extra {
        /** {@code custom:<k>}. */
        public final String ref;
        public final List<String> names;

        /** A custom item found by {@code names} (any of them, each folded as the game's names are) and handed back as {@code ref}. */
        public Extra(String ref, List<String> names) {
            this.ref = ref;
            List<String> copy = new ArrayList<>(names.size());
            for (String name : names) copy.add(name == null ? "" : name);
            this.names = Collections.unmodifiableList(copy);
        }
    }

    /** One search hit: a game item ({@link #item}) or one of the extras ({@link #extra}). */
    public static final class Hit {
        /** The item's id, or the extra's ref. */
        public final String ref;
        @Nullable
        public final ItemInfo item;
        @Nullable
        public final Extra extra;

        Hit(String ref, @Nullable ItemInfo item, @Nullable Extra extra) {
            this.ref = ref;
            this.item = item;
            this.extra = extra;
        }
    }

    private final String mMinecraft;
    private final List<ItemInfo> mItems;
    private final List<EnchantmentInfo> mEnchantments;
    /** Each group's enchantment indexes, ascending. */
    private final int[][] mGroups;
    private final Map<String, ItemInfo> mItemById;
    private final Map<String, EnchantmentInfo> mEnchantmentById;
    private final Object mLock = new Object();
    // The search tables: derived from the above, built once on first use, never changed after.
    private volatile Folded[] mItemsTr;
    private volatile Folded[] mItemsEn;
    private volatile Folded[] mEnchantmentsTr;
    private volatile Folded[] mEnchantmentsEn;
    private volatile Set<String> mRepeatedTr;
    private volatile Set<String> mRepeatedEn;

    private ItemData(String minecraft, List<ItemInfo> items, List<EnchantmentInfo> enchantments, int[][] groups,
                     Map<String, ItemInfo> itemById, Map<String, EnchantmentInfo> enchantmentById) {
        mMinecraft = minecraft;
        mItems = Collections.unmodifiableList(items);
        mEnchantments = Collections.unmodifiableList(enchantments);
        mGroups = groups;
        mItemById = itemById;
        mEnchantmentById = enchantmentById;
    }

    // ---- reading the file ----

    /**
     * Reads an item data file's text; null when it is not format 1, is over {@link #MAX_BYTES} or any row is malformed: an id
     * outside {@code ^minecraft:[a-z0-9_]{1,64}$} or repeated, a stack outside 1 to 99, a name that is empty, padded or over 240
     * characters, an alias that is not folded words, an index out of range or not ascending, no item at all. Keys of a row's
     * extras this does not know are read past (format 1 grows only there).
     */
    @Nullable
    public static ItemData parse(@Nullable String text) {
        if (text == null || text.length() > MAX_BYTES) return null;
        try {
            JsonObject doc = Json.parseObject(text);
            return doc == null ? null : read(doc);
        } catch (RuntimeException | StackOverflowError e) {
            return null;
        }
    }

    @Nullable
    private static ItemData read(JsonObject doc) {
        Long format = integer(doc.get("format"));
        if (format == null || format.longValue() != FORMAT) return null;
        String mc = textOf(doc.get("mc"));
        if (mc == null || mc.length() > 32) return null;
        JsonElement itemRows = doc.get("items");
        JsonElement enchantmentRows = doc.get("ench");
        JsonElement groupRows = doc.get("groups");
        if (itemRows == null || !itemRows.isJsonArray() || enchantmentRows == null || !enchantmentRows.isJsonArray()
                || groupRows == null || !groupRows.isJsonArray()) {
            return null;
        }

        JsonArray enchantmentArray = enchantmentRows.getAsJsonArray();
        int count = enchantmentArray.size();
        List<EnchantmentInfo> enchantments = new ArrayList<>(count);
        Map<String, EnchantmentInfo> enchantmentById = new HashMap<>();
        for (int i = 0; i < count; i++) {
            EnchantmentInfo enchantment = parseEnchantment(enchantmentArray.get(i), count, i);
            if (enchantment == null || enchantmentById.containsKey(enchantment.id)) return null;
            enchantments.add(enchantment);
            enchantmentById.put(enchantment.id, enchantment);
        }
        for (EnchantmentInfo enchantment : enchantments) {
            List<String> excluded = new ArrayList<>(enchantment.mExcludes.length);
            for (int index : enchantment.mExcludes) excluded.add(enchantments.get(index).id);
            enchantment.mFacts = new ItemFacts.Enchantment(enchantment.id, enchantment.maxLevel, enchantment.curse, excluded);
        }

        JsonArray groupArray = groupRows.getAsJsonArray();
        int[][] groups = new int[groupArray.size()][];
        List<List<String>> groupIds = new ArrayList<>(groups.length);
        for (int g = 0; g < groups.length; g++) {
            int[] group = ascending(groupArray.get(g), count);
            if (group == null || group.length == 0) return null;
            groups[g] = group;
            List<String> ids = new ArrayList<>(group.length);
            for (int index : group) ids.add(enchantments.get(index).id);
            groupIds.add(ids);
        }

        JsonArray itemArray = itemRows.getAsJsonArray();
        List<ItemInfo> items = new ArrayList<>(itemArray.size());
        Map<String, ItemInfo> itemById = new HashMap<>();
        for (int i = 0; i < itemArray.size(); i++) {
            ItemInfo item = parseItem(itemArray.get(i), groups.length, groupIds);
            if (item == null || itemById.containsKey(item.id)) return null;
            items.add(item);
            itemById.put(item.id, item);
        }
        if (items.isEmpty()) return null;
        return new ItemData(mc, items, enchantments, groups, itemById, enchantmentById);
    }

    @Nullable
    private static EnchantmentInfo parseEnchantment(JsonElement rowElement, int count, int index) {
        if (!rowElement.isJsonArray()) return null;
        JsonArray row = rowElement.getAsJsonArray();
        if (row.size() < 4 || row.size() > 5) return null;
        String id = vanillaId(row.get(0));
        Long maxLevel = integer(row.get(1));
        String tr = textOf(row.get(2));
        String en = textOf(row.get(3));
        if (id == null || maxLevel == null || maxLevel < 1 || maxLevel > 255 || tr == null || en == null) return null;
        boolean curse = false;
        int[] excludes = new int[0];
        List<String> aliases = Collections.emptyList();
        if (row.size() == 5) {
            JsonElement extraElement = row.get(4);
            if (!extraElement.isJsonObject()) return null;
            JsonObject extra = extraElement.getAsJsonObject();
            aliases = aliasesOf(extra);
            if (aliases == null) return null;
            JsonElement c = extra.get("c");
            if (c != null) {
                Double flag = number(c);
                if (flag == null) return null;
                curse = flag == 1.0;
            }
            JsonElement x = extra.get("x");
            if (x != null) {
                excludes = ascending(x, count);
                if (excludes == null) return null;
                for (int other : excludes) {
                    if (other == index) return null;
                }
            }
        }
        return new EnchantmentInfo(id, (int) (long) maxLevel, new Names(tr, en), curse, aliases, index, excludes);
    }

    @Nullable
    private static ItemInfo parseItem(JsonElement rowElement, int groupCount, List<List<String>> groupIds) {
        if (!rowElement.isJsonArray()) return null;
        JsonArray row = rowElement.getAsJsonArray();
        if (row.size() < 6 || row.size() > 7) return null;
        String id = vanillaId(row.get(0));
        Long stack = integer(row.get(1));
        Long maxDamage = integer(row.get(2));
        String tr = textOf(row.get(3));
        String en = textOf(row.get(4));
        Long group = integer(row.get(5));
        if (id == null || stack == null || stack < 1 || stack > 99 || maxDamage == null || maxDamage < 0
                || maxDamage > Integer.MAX_VALUE || tr == null || en == null || group == null) {
            return null;
        }
        if (group != -1 && (group < 0 || group >= groupCount)) return null;
        List<String> aliases = Collections.emptyList();
        Names desc = null;
        boolean operator = false;
        if (row.size() == 7) {
            JsonElement extraElement = row.get(6);
            if (!extraElement.isJsonObject()) return null;
            JsonObject extra = extraElement.getAsJsonObject();
            aliases = aliasesOf(extra);
            if (aliases == null) return null;
            JsonElement d = extra.get("d");
            if (d != null) {
                if (!d.isJsonArray() || d.getAsJsonArray().size() != 2) return null;
                String descTr = textOf(d.getAsJsonArray().get(0));
                String descEn = textOf(d.getAsJsonArray().get(1));
                if (descTr == null || descEn == null) return null;
                desc = new Names(descTr, descEn);
            }
            JsonElement o = extra.get("o");
            if (o != null) {
                Double flag = number(o);
                if (flag == null) return null;
                operator = flag == 1.0;
            }
        }
        int groupIndex = (int) (long) group;
        List<String> fitting = groupIndex < 0 ? Collections.<String>emptyList() : groupIds.get(groupIndex);
        ItemFacts.Item facts = new ItemFacts.Item(id, (int) (long) stack, (int) (long) maxDamage, fitting, operator);
        return new ItemInfo(id, (int) (long) stack, (int) (long) maxDamage, new Names(tr, en), desc, aliases, operator,
                groupIndex, facts);
    }

    /** The aliases of a row's extras: none when there is no {@code a}; null when they are malformed. */
    @Nullable
    private static List<String> aliasesOf(JsonObject extra) {
        JsonElement a = extra.get("a");
        if (a == null) return Collections.emptyList();
        if (!a.isJsonArray() || a.getAsJsonArray().size() > MAX_ALIASES) return null;
        List<String> out = new ArrayList<>();
        for (JsonElement element : a.getAsJsonArray()) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) return null;
            String alias = element.getAsString();
            if (!aliasOk(alias) || out.contains(alias)) return null;
            out.add(alias);
        }
        return Collections.unmodifiableList(out);
    }

    /** An alias as stored: folded words of {@code [a-z0-9]} with single spaces, 2 to 40 characters. */
    private static boolean aliasOk(String alias) {
        int n = alias.length();
        if (n < 2 || n > 40) return false;
        boolean wordStart = true;
        for (int i = 0; i < n; i++) {
            char c = alias.charAt(i);
            if (c == ' ') {
                if (wordStart) return false;
                wordStart = true;
            } else if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                wordStart = false;
            } else {
                return false;
            }
        }
        return !wordStart;
    }

    /** Whether {@code id} is a vanilla item or enchantment reference an order may name: {@code ^minecraft:[a-z0-9_]{1,64}$}. */
    public static boolean isVanillaId(@Nullable String id) {
        if (id == null || !id.startsWith(VANILLA)) return false;
        int n = id.length() - VANILLA.length();
        if (n < 1 || n > 64) return false;
        for (int i = VANILLA.length(); i < id.length(); i++) {
            char c = id.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_')) return false;
        }
        return true;
    }

    @Nullable
    private static String vanillaId(@Nullable JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) return null;
        String id = element.getAsString();
        return isVanillaId(id) ? id : null;
    }

    /** A name: a string of 1 to 240 characters with no whitespace at either end. */
    @Nullable
    private static String textOf(@Nullable JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) return null;
        String text = element.getAsString();
        int n = text.length();
        if (n == 0 || n > MAX_NAME || isJsSpace(text.charAt(0)) || isJsSpace(text.charAt(n - 1))) return null;
        return text;
    }

    /** What JavaScript's {@code trim} strips: white space and line terminators. */
    private static boolean isJsSpace(char c) {
        switch (c) {
            case 0x09:
            case 0x0A:
            case 0x0B:
            case 0x0C:
            case 0x0D:
            case 0x20:
            case 0xA0:
            case 0x1680:
            case 0x2028:
            case 0x2029:
            case 0x202F:
            case 0x205F:
            case 0x3000:
            case 0xFEFF:
                return true;
            default:
                return c >= 0x2000 && c <= 0x200A;
        }
    }

    /** A JSON number with an integer value (JSON's {@code 64.0} is 64, as in JavaScript). */
    @Nullable
    private static Long integer(@Nullable JsonElement element) {
        Double d = number(element);
        if (d == null || Double.isNaN(d) || Double.isInfinite(d) || d != Math.rint(d) || Math.abs(d) > MAX_SAFE_INTEGER) return null;
        return (long) (double) d;
    }

    @Nullable
    private static Double number(@Nullable JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) return null;
        return element.getAsDouble();
    }

    /** Strictly ascending indexes below {@code below}; null when {@code list} is not such an array. */
    @Nullable
    private static int[] ascending(@Nullable JsonElement list, int below) {
        if (list == null || !list.isJsonArray()) return null;
        JsonArray array = list.getAsJsonArray();
        int[] out = new int[array.size()];
        long previous = -1;
        for (int i = 0; i < out.length; i++) {
            Long value = integer(array.get(i));
            if (value == null || value < 0 || value >= below || value <= previous) return null;
            out[i] = (int) (long) value;
            previous = value;
        }
        return out;
    }

    // ---- the data ----

    /** The Minecraft version this file is for ({@code mc}). */
    public String minecraft() {
        return mMinecraft;
    }

    /** Every item, in the game's registry order. */
    public List<ItemInfo> items() {
        return mItems;
    }

    /** Every enchantment, sorted by id. */
    public List<EnchantmentInfo> enchantments() {
        return mEnchantments;
    }

    /** The item with this id, or null. */
    @Nullable
    public ItemInfo itemInfo(String id) {
        return mItemById.get(id);
    }

    /** The enchantment with this id, or null. */
    @Nullable
    public EnchantmentInfo enchantmentInfo(String id) {
        return mEnchantmentById.get(id);
    }

    @Nullable
    @Override
    public Item item(String id) {
        ItemInfo info = mItemById.get(id);
        return info == null ? null : info.mFacts;
    }

    @Nullable
    @Override
    public Enchantment enchantment(String id) {
        EnchantmentInfo info = mEnchantmentById.get(id);
        return info == null ? null : info.mFacts;
    }

    /** An item's name by its id in {@code lang} ({@code "en"} is English, anything else Turkish); null when there is no such item. */
    @Nullable
    public String name(String id, @Nullable String lang) {
        ItemInfo info = mItemById.get(id);
        return info == null ? null : info.names.get(lang);
    }

    /** An enchantment's name by its id in {@code lang} (as {@link #name}); null when there is no such enchantment. */
    @Nullable
    public String enchantmentName(String id, @Nullable String lang) {
        EnchantmentInfo info = mEnchantmentById.get(id);
        return info == null ? null : info.names.get(lang);
    }

    /** The enchantments that fit an item, in the file's order (by id); a new list. */
    public List<EnchantmentInfo> enchantmentsFor(ItemInfo item) {
        int g = item.mGroup;
        if (g < 0 || g >= mGroups.length) return new ArrayList<>();
        int[] group = mGroups[g];
        List<EnchantmentInfo> out = new ArrayList<>(group.length);
        for (int index : group) out.add(mEnchantments.get(index));
        return out;
    }

    /** Whether an enchantment fits an item (both by id): the item's group names it. */
    public boolean fits(String itemId, String enchantmentId) {
        ItemInfo item = mItemById.get(itemId);
        EnchantmentInfo enchantment = mEnchantmentById.get(enchantmentId);
        if (item == null || enchantment == null || item.mGroup < 0 || item.mGroup >= mGroups.length) return false;
        return Arrays.binarySearch(mGroups[item.mGroup], enchantment.mIndex) >= 0;
    }

    /** Whether two enchantments of this data can be on one item: not the same, and neither excludes the other. */
    public boolean canCombine(EnchantmentInfo a, EnchantmentInfo b) {
        int ia = indexOf(a);
        int ib = indexOf(b);
        if (ia < 0 || ib < 0 || ia == ib) return false;
        return !contains(a.mExcludes, ib) && !contains(b.mExcludes, ia);
    }

    /** {@link #canCombine(EnchantmentInfo, EnchantmentInfo)} by id; false when either is not in the data. */
    public boolean canCombine(String a, String b) {
        EnchantmentInfo first = mEnchantmentById.get(a);
        EnchantmentInfo second = mEnchantmentById.get(b);
        return first != null && second != null && canCombine(first, second);
    }

    private int indexOf(EnchantmentInfo enchantment) {
        int index = enchantment.mIndex;
        return index >= 0 && index < mEnchantments.size() && mEnchantments.get(index) == enchantment ? index : -1;
    }

    private static boolean contains(int[] list, int value) {
        for (int v : list) {
            if (v == value) return true;
        }
        return false;
    }

    /** An enchantment level as the game writes it ("V"): Roman to X, digits above (and below 1). */
    public static String levelText(int level) {
        return level >= 1 && level <= ROMAN.length ? ROMAN[level - 1] : String.valueOf(level);
    }

    // ---- versions ----

    /**
     * The version whose file to read for a game version, from a manifest ({@code {format, default, versions: {...}}}): its own;
     * else the newest older one (the items it has); else, for a version older than every file, the oldest (the nearest newer);
     * with no version asked, the manifest's {@code default} (else the newest). Null when the manifest has no versions.
     */
    @Nullable
    public static String pickVersion(JsonObject manifest, @Nullable String mc) {
        JsonObject versions = Json.obj(manifest, "versions");
        List<String> keys = new ArrayList<>();
        if (versions != null) {
            for (Map.Entry<String, JsonElement> entry : versions.entrySet()) {
                if (entry.getValue().isJsonObject()) keys.add(entry.getKey());
            }
        }
        return pickVersion(keys, Json.str(manifest, "default"), mc);
    }

    /** {@link #pickVersion(JsonObject, String)} for a plain set of version names and the default among them. */
    @Nullable
    public static String pickVersion(Collection<String> versions, @Nullable String defaultVersion, @Nullable String mc) {
        List<String> sorted = new ArrayList<>(versions);
        Collections.sort(sorted, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                int order = compareVersions(a, b);
                return order != 0 ? order : a.compareTo(b);
            }
        });
        if (mc != null && !mc.isEmpty()) {
            if (sorted.contains(mc)) return mc;
            String older = null;
            for (String version : sorted) {
                if (compareVersions(version, mc) <= 0) older = version;
            }
            return older != null ? older : (sorted.isEmpty() ? null : sorted.get(0));
        }
        if (defaultVersion != null && sorted.contains(defaultVersion)) return defaultVersion;
        return sorted.isEmpty() ? null : sorted.get(sorted.size() - 1);
    }

    /** Orders Minecraft versions by their numbers ({@code 1.21.10} after {@code 1.21.9}); a part reads as its leading digits. */
    public static int compareVersions(String a, String b) {
        long[] pa = versionParts(a);
        long[] pb = versionParts(b);
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            long x = i < pa.length ? pa[i] : 0;
            long y = i < pb.length ? pb[i] : 0;
            if (x != y) return x < y ? -1 : 1;
        }
        return 0;
    }

    /** {@code 26.1.2} as {@code [26, 1, 2]}; each part's leading digits (none: 0). */
    private static long[] versionParts(String version) {
        int parts = 1;
        for (int i = 0; i < version.length(); i++) {
            if (version.charAt(i) == '.') parts++;
        }
        long[] out = new long[parts];
        int part = 0;
        boolean digits = true;
        for (int i = 0; i < version.length(); i++) {
            char c = version.charAt(i);
            if (c == '.') {
                part++;
                digits = true;
            } else if (digits && c >= '0' && c <= '9') {
                if (out[part] < Long.MAX_VALUE / 10 - 9) out[part] = out[part] * 10 + (c - '0');
            } else {
                digits = false;
            }
        }
        return out;
    }

    // ---- search ----

    /** Whether {@code lang} asks for English; anything else is Turkish, as the file's own language fallback. */
    private static boolean isEnglish(@Nullable String lang) {
        return "en".equals(lang);
    }

    /**
     * Search folding, the same as {@code fold} of {@code @pl/ui/search} and {@code pl_items::data::fold}: lower case the Turkish
     * way (upper-case I, dotted I and dotless i all become i), then canonical decomposition with the combining marks dropped
     * (cedilla, breve, diaeresis, circumflex and the rest), so "Elmas K[dotless i]l[dotless i]c" is "elmas kilic". Letters
     * without a decomposition stay, and so do spaces, digits and signs.
     */
    public static String fold(String text) {
        String decomposed = Normalizer.normalize(text.toLowerCase(TURKISH), Normalizer.Form.NFD);
        StringBuilder out = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); ) {
            int cp = decomposed.codePointAt(i);
            i += Character.charCount(cp);
            if (isMark(cp)) continue;
            out.appendCodePoint(cp == DOTLESS_I ? 'i' : cp);
        }
        return out.toString();
    }

    private static boolean isMark(int cp) {
        int type = Character.getType(cp);
        return type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK || type == Character.ENCLOSING_MARK;
    }

    /** A folded text as words: single spaces, letters and digits only (apostrophes join, other signs split). */
    private static final class Field {
        /** Folded, single spaces. */
        final String whole;
        /** Its words. */
        final String[] words;

        private Field(String whole, String[] words) {
            this.whole = whole;
            this.words = words;
        }

        static Field of(String text) {
            String folded = fold(text);
            StringBuilder whole = new StringBuilder(folded.length());
            boolean gap = false;
            int words = 0;
            for (int i = 0; i < folded.length(); ) {
                int cp = folded.codePointAt(i);
                i += Character.charCount(cp);
                if (isApostrophe(cp)) continue;
                if (isLetterOrNumber(cp)) {
                    if (gap && whole.length() > 0) whole.append(' ');
                    if (whole.length() == 0 || whole.charAt(whole.length() - 1) == ' ') words++;
                    gap = false;
                    whole.appendCodePoint(cp);
                } else {
                    gap = true;
                }
            }
            if (words == 0) return new Field("", NO_WORDS);
            String[] list = new String[words];
            int from = 0;
            int n = 0;
            for (int i = 0; i <= whole.length(); i++) {
                if (i == whole.length() || whole.charAt(i) == ' ') {
                    list[n++] = whole.substring(from, i);
                    from = i + 1;
                }
            }
            return new Field(whole.toString(), list);
        }

        /** The marks the game and our aliases drop instead of splitting at: right single quote, apostrophe, grave, acute, modifier. */
        private static boolean isApostrophe(int cp) {
            return cp == 0x2019 || cp == '\'' || cp == '`' || cp == 0x00B4 || cp == 0x02BC;
        }

        private static boolean isLetterOrNumber(int cp) {
            if (Character.isLetter(cp)) return true;
            int type = Character.getType(cp);
            return type == Character.DECIMAL_DIGIT_NUMBER || type == Character.LETTER_NUMBER || type == Character.OTHER_NUMBER;
        }
    }

    /** What one thing is matched on: the interface language's name and description line, the other language's, the aliases. */
    private static final class Folded {
        final Field[] own;
        final Field[] other;
        final Field[] aliases;
        /** All of them. */
        final Field[] all;

        Folded(Field[] own, Field[] other, Field[] aliases) {
            this.own = own;
            this.other = other;
            this.aliases = aliases;
            all = new Field[own.length + other.length + aliases.length];
            System.arraycopy(own, 0, all, 0, own.length);
            System.arraycopy(other, 0, all, own.length, other.length);
            System.arraycopy(aliases, 0, all, own.length + other.length, aliases.length);
        }
    }

    private static Field[] fieldsOf(List<String> texts) {
        Field[] out = new Field[texts.size()];
        for (int i = 0; i < out.length; i++) out[i] = Field.of(texts.get(i));
        return out;
    }

    /** The name's field, then the description line's when there is one, in one language. */
    private static Field[] namesOf(Names name, @Nullable Names desc, boolean english) {
        String first = english ? name.en : name.tr;
        if (desc == null) return new Field[]{Field.of(first)};
        return new Field[]{Field.of(first), Field.of(english ? desc.en : desc.tr)};
    }

    private Folded[] foldedItems(boolean english) {
        Folded[] built = english ? mItemsEn : mItemsTr;
        if (built != null) return built;
        synchronized (mLock) {
            built = english ? mItemsEn : mItemsTr;
            if (built == null) {
                built = new Folded[mItems.size()];
                for (int i = 0; i < built.length; i++) {
                    ItemInfo item = mItems.get(i);
                    built[i] = new Folded(namesOf(item.names, item.desc, english), namesOf(item.names, item.desc, !english),
                            fieldsOf(item.aliases));
                }
                if (english) mItemsEn = built;
                else mItemsTr = built;
            }
            return built;
        }
    }

    private Folded[] foldedEnchantments(boolean english) {
        Folded[] built = english ? mEnchantmentsEn : mEnchantmentsTr;
        if (built != null) return built;
        synchronized (mLock) {
            built = english ? mEnchantmentsEn : mEnchantmentsTr;
            if (built == null) {
                built = new Folded[mEnchantments.size()];
                for (int i = 0; i < built.length; i++) {
                    EnchantmentInfo e = mEnchantments.get(i);
                    built[i] = new Folded(new Field[]{Field.of(english ? e.names.en : e.names.tr)},
                            new Field[]{Field.of(english ? e.names.tr : e.names.en)}, fieldsOf(e.aliases));
                }
                if (english) mEnchantmentsEn = built;
                else mEnchantmentsTr = built;
            }
            return built;
        }
    }

    /**
     * Builds the search tables for {@code lang} now (the first search would do it): a call for a background thread after the
     * data is loaded, so the first keystroke does not wait for the folding of every name.
     */
    public void warm(@Nullable String lang) {
        boolean english = isEnglish(lang);
        foldedItems(english);
        foldedEnchantments(english);
        repeated(english);
    }

    /** Three rows of the distance table, reused by one search (it compares thousands of short words). */
    private static final class Aligner {
        private int[] mBefore = new int[32];
        private int[] mPrev = new int[32];
        private int[] mRow = new int[32];

        /** Optimal string alignment distance of {@code a} and {@code b}, or {@code max + 1} once it is certainly above {@code max}. */
        int distance(String a, String b, int max) {
            int n = b.length();
            if (Math.abs(a.length() - n) > max) return max + 1;
            if (n + 1 > mPrev.length) {
                mBefore = new int[n + 1];
                mPrev = new int[n + 1];
                mRow = new int[n + 1];
            }
            int[] before = mBefore;
            int[] prev = mPrev;
            int[] row = mRow;
            for (int j = 0; j <= n; j++) prev[j] = j;
            for (int i = 1; i <= a.length(); i++) {
                char ca = a.charAt(i - 1);
                row[0] = i;
                int best = i;
                for (int j = 1; j <= n; j++) {
                    char cb = b.charAt(j - 1);
                    int d = Math.min(Math.min(prev[j] + 1, row[j - 1] + 1), prev[j - 1] + (ca == cb ? 0 : 1));
                    if (i > 1 && j > 1 && ca == b.charAt(j - 2) && a.charAt(i - 2) == cb) d = Math.min(d, before[j - 2] + 1);
                    row[j] = d;
                    if (d < best) best = d;
                }
                if (best > max) return max + 1;
                int[] spare = before;
                before = prev;
                prev = row;
                row = spare;
            }
            return prev[n];
        }
    }

    /** Each token starts a word of the field (a word once; "elmas kil" in "elmas kilic"). */
    private static boolean wordsStart(Field field, String[] tokens) {
        if (tokens.length == 1) {
            for (String word : field.words) {
                if (word.startsWith(tokens[0])) return true;
            }
            return false;
        }
        boolean[] used = new boolean[field.words.length];
        for (String token : tokens) {
            int at = -1;
            for (int i = 0; i < field.words.length; i++) {
                if (!used[i] && field.words[i].startsWith(token)) {
                    at = i;
                    break;
                }
            }
            if (at < 0) return false;
            used[at] = true;
        }
        return true;
    }

    /** Each token (4 letters or more) is a typo away from a word or the word's beginning. */
    private static boolean wordsNear(Field field, String[] tokens, Aligner aligner) {
        for (String token : tokens) {
            if (!wordNear(field, token, aligner)) return false;
        }
        return true;
    }

    private static boolean wordNear(Field field, String token, Aligner aligner) {
        if (token.length() < 4) {
            for (String word : field.words) {
                if (word.startsWith(token)) return true;
            }
            return false;
        }
        int max = token.length() >= 7 ? 2 : 1;
        for (String word : field.words) {
            if (aligner.distance(token, word, max) <= max) return true;
            if (word.length() > token.length() && aligner.distance(token, word.substring(0, token.length()), max) <= max) {
                return true;
            }
        }
        return false;
    }

    /**
     * How well a thing's fields match the query, best (0) first, or -1 for no match: its name in the interface language exactly,
     * an alias exactly, that name's beginning, the beginnings of its words (and its description line's), the other language's
     * name the same three ways, the beginnings of an alias's words, the query inside any of them (3 letters or more).
     */
    private static int rank(Folded f, String query, String[] tokens) {
        Field name = f.own.length > 0 ? f.own[0] : null;
        if (name != null && name.whole.equals(query)) return 0;
        for (Field alias : f.aliases) {
            if (alias.whole.equals(query)) return 1;
        }
        if (name != null && name.whole.startsWith(query)) return 2;
        for (Field field : f.own) {
            if (wordsStart(field, tokens)) return 3;
        }
        Field otherName = f.other.length > 0 ? f.other[0] : null;
        if (otherName != null && otherName.whole.equals(query)) return 4;
        if (otherName != null && otherName.whole.startsWith(query)) return 5;
        for (Field field : f.other) {
            if (wordsStart(field, tokens)) return 6;
        }
        for (Field alias : f.aliases) {
            if (alias.whole.startsWith(query) || wordsStart(alias, tokens)) return 7;
        }
        if (query.length() >= 3) {
            for (Field field : f.all) {
                if (field.whole.contains(query)) return 8;
            }
        }
        return -1;
    }

    /**
     * The positions of the best matches, best first and the earlier position first within a rank: everything is ranked, then
     * (while there are fewer hits than {@code limit}) the typo matches, a typo in each word (1 letter; 2 from 7 letters), which
     * rank below every other.
     */
    private static int[] rankedOrder(Folded[] folded, int count, Field query, int limit) {
        if (limit <= 0 || count == 0) return new int[0];
        int[] hitAt = new int[count];
        int[] hitRank = new int[count];
        int[] misses = new int[count];
        int hits = 0;
        int missCount = 0;
        for (int i = 0; i < count; i++) {
            int rank = rank(folded[i], query.whole, query.words);
            if (rank < 0) {
                misses[missCount++] = i;
            } else {
                hitAt[hits] = i;
                hitRank[hits++] = rank;
            }
        }
        if (hits < limit && missCount > 0) {
            Aligner aligner = new Aligner();
            for (int m = 0; m < missCount; m++) {
                int i = misses[m];
                for (Field field : folded[i].all) {
                    if (wordsNear(field, query.words, aligner)) {
                        hitAt[hits] = i;
                        hitRank[hits++] = TYPO;
                        break;
                    }
                }
            }
        }
        int size = Math.min(hits, limit);
        int[] out = new int[size];
        int n = 0;
        // The hits were collected in position order, so taking them rank by rank keeps that order within a rank.
        for (int rank = 0; rank <= TYPO && n < size; rank++) {
            for (int h = 0; h < hits && n < size; h++) {
                if (hitRank[h] == rank) out[n++] = hitAt[h];
            }
        }
        return out;
    }

    /**
     * The items (and custom items) a picker's query finds, best first; within a rank custom items first, then the game's order.
     * Folded like every search in the app ({@link #fold}): "elmas kilic" finds the diamond sword, "dia kilic" finds it by our
     * alias, a smithing template is found by its grey line, "elitar" finds the Elytra despite the typo. An empty query finds
     * nothing. Operator items (command blocks, barrier) are left out.
     *
     * @param lang   {@code "tr"} or {@code "en"}: the language whose names rank first (anything else is Turkish)
     * @param extras the account's custom items, searched by their names (may be null)
     * @param limit  the most hits; {@link #NO_LIMIT} for {@link #DEFAULT_LIMIT}
     */
    public List<Hit> search(@Nullable String query, @Nullable String lang, @Nullable List<Extra> extras, int limit) {
        return searchItems(this, query, lang, extras, limit, false);
    }

    /** {@link #search(String, String, List, int)}, also finding operator items when {@code operatorItems} is true. */
    public List<Hit> search(@Nullable String query, @Nullable String lang, @Nullable List<Extra> extras, int limit,
                            boolean operatorItems) {
        return searchItems(this, query, lang, extras, limit, operatorItems);
    }

    /** {@link #search} without the game's items when {@code data} is null (the custom items are still found). */
    public static List<Hit> searchItems(@Nullable ItemData data, @Nullable String query, @Nullable String lang,
                                        @Nullable List<Extra> extras, int limit, boolean operatorItems) {
        Field q = Field.of(query == null ? "" : query);
        if (q.whole.isEmpty()) return new ArrayList<>();
        boolean english = isEnglish(lang);
        int extraCount = extras == null ? 0 : extras.size();
        int capacity = extraCount + (data == null ? 0 : data.mItems.size());
        Folded[] folded = new Folded[capacity];
        Object[] things = new Object[capacity];
        int count = 0;
        for (int i = 0; i < extraCount; i++) {
            Extra extra = extras.get(i);
            folded[count] = new Folded(fieldsOf(extra.names), new Field[0], new Field[0]);
            things[count++] = extra;
        }
        if (data != null) {
            Folded[] byItem = data.foldedItems(english);
            for (int i = 0; i < byItem.length; i++) {
                ItemInfo item = data.mItems.get(i);
                if (operatorItems || !item.operator) {
                    folded[count] = byItem[i];
                    things[count++] = item;
                }
            }
        }
        int[] order = rankedOrder(folded, count, q, limit < 0 ? DEFAULT_LIMIT : limit);
        List<Hit> hits = new ArrayList<>(order.length);
        for (int at : order) {
            Object thing = things[at];
            if (thing instanceof ItemInfo) {
                ItemInfo item = (ItemInfo) thing;
                hits.add(new Hit(item.id, item, null));
            } else {
                Extra extra = (Extra) thing;
                hits.add(new Hit(extra.ref, null, extra));
            }
        }
        return hits;
    }

    private Set<String> repeated(boolean english) {
        Set<String> built = english ? mRepeatedEn : mRepeatedTr;
        if (built != null) return built;
        synchronized (mLock) {
            built = english ? mRepeatedEn : mRepeatedTr;
            if (built == null) {
                Map<String, Integer> seen = new HashMap<>();
                String[] keys = new String[mItems.size()];
                for (int i = 0; i < keys.length; i++) {
                    ItemInfo item = mItems.get(i);
                    String line = item.desc == null ? "" : fold(item.desc.get(english ? "en" : "tr"));
                    keys[i] = fold(item.names.get(english ? "en" : "tr")) + "\n" + line;
                    Integer times = seen.get(keys[i]);
                    seen.put(keys[i], times == null ? 1 : times + 1);
                }
                built = new HashSet<>();
                for (int i = 0; i < keys.length; i++) {
                    if (seen.get(keys[i]) > 1) built.add(mItems.get(i).id);
                }
                if (english) mRepeatedEn = built;
                else mRepeatedTr = built;
            }
            return built;
        }
    }

    /**
     * The other language's name, when another item shows the same name and description line in {@code lang} (Turkish "Fener"
     * is both Beacon and Lantern): a picker writes it beside the name so the two rows differ. Null for a name only one item has.
     */
    @Nullable
    public String nameHint(ItemInfo item, @Nullable String lang) {
        boolean english = isEnglish(lang);
        if (!repeated(english).contains(item.id)) return null;
        return english ? item.names.tr : item.names.en;
    }

    /**
     * The enchantments a query finds (by name in either language or alias), best first; with an empty query, the pool in the
     * file's order. Only the enchantments that fit {@code item} when one is given.
     *
     * @param limit the most results; {@link #NO_LIMIT}: {@link #DEFAULT_LIMIT} for a query, the whole pool for an empty one
     */
    public List<EnchantmentInfo> searchEnchantments(@Nullable String query, @Nullable String lang, @Nullable ItemInfo item,
                                                    int limit) {
        Field q = Field.of(query == null ? "" : query);
        List<EnchantmentInfo> pool = item != null ? enchantmentsFor(item) : new ArrayList<>(mEnchantments);
        if (q.whole.isEmpty()) {
            int size = limit < 0 ? pool.size() : Math.min(limit, pool.size());
            return new ArrayList<>(pool.subList(0, size));
        }
        Folded[] all = foldedEnchantments(isEnglish(lang));
        Folded[] folded = new Folded[pool.size()];
        for (int i = 0; i < folded.length; i++) folded[i] = all[pool.get(i).mIndex];
        int[] order = rankedOrder(folded, folded.length, q, limit < 0 ? DEFAULT_LIMIT : limit);
        List<EnchantmentInfo> out = new ArrayList<>(order.length);
        for (int at : order) out.add(pool.get(at));
        return out;
    }
}
