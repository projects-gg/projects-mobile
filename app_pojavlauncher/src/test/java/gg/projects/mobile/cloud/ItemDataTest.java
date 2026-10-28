package gg.projects.mobile.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.sun.net.httpserver.HttpServer;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import gg.projects.mobile.cloud.items.ItemData;
import gg.projects.mobile.cloud.items.ItemDataStore;
import gg.projects.mobile.cloud.plus.ItemFacts;
import okhttp3.OkHttpClient;

/**
 * FREE-M-ITEMS: the item data reader and search (a port of {@code @pl/ui/items} item-data.ts; the cases of its test, on the same
 * small hand-written file) and the loader ({@code plus_items.rs}'s rules: a manifest fresh for five minutes with the last good
 * copy kept, files checked by SHA-256 and size, nothing unchecked kept, the old file kept when a new one fails), the loader
 * against a local {@code HttpServer}.
 */
public class ItemDataTest {
    private static final String SMALL = resource("cloud/itemdata/small.json");
    private static final long MINUTE = 60 * 1000L;

    @Rule
    public TemporaryFolder mTmp = new TemporaryFolder();

    private final List<Cdn> mCdns = new ArrayList<>();
    private final OkHttpClient mClient = new OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build();

    @After
    public void tearDown() {
        for (Cdn cdn : mCdns) cdn.stop();
        mClient.dispatcher().executorService().shutdown();
        mClient.connectionPool().evictAll();
    }

    // ---- helpers ----

    private static String resource(String path) {
        try (InputStream in = ItemDataTest.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing test resource " + path);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ItemData data() {
        ItemData data = ItemData.parse(SMALL);
        assertNotNull("the test file parses", data);
        return data;
    }

    private static JsonObject json(String singleQuoted) {
        return JsonParser.parseString(singleQuoted.replace('\'', '"')).getAsJsonObject();
    }

    private static JsonArray array(Object... values) {
        JsonArray array = new JsonArray();
        for (Object value : values) {
            if (value instanceof JsonElement) array.add((JsonElement) value);
            else if (value instanceof Number) array.add(new JsonPrimitive((Number) value));
            else array.add(new JsonPrimitive((String) value));
        }
        return array;
    }

    private static String repeat(char c, int times) {
        char[] chars = new char[times];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    private interface Edit {
        void apply(JsonObject doc);
    }

    /** The small file with one change; what {@link ItemData#parse} makes of it. */
    private static ItemData changed(Edit edit) {
        JsonObject copy = JsonParser.parseString(SMALL).getAsJsonObject();
        edit.apply(copy);
        return ItemData.parse(copy.toString());
    }

    private static void addItem(JsonObject doc, Object... row) {
        doc.getAsJsonArray("items").add(array(row));
    }

    /** A data file of just these item rows (no enchantments), parsed. */
    private static ItemData tiny(Object[]... rows) {
        JsonObject doc = json("{'format':1,'mc':'26.3','items':[],'ench':[],'groups':[]}");
        for (Object[] row : rows) addItem(doc, row);
        ItemData data = ItemData.parse(doc.toString());
        assertNotNull(data);
        return data;
    }

    private static List<String> refs(List<ItemData.Hit> hits) {
        List<String> out = new ArrayList<>();
        for (ItemData.Hit hit : hits) out.add(hit.ref.replace("minecraft:", ""));
        return out;
    }

    private static List<String> ids(List<ItemData.EnchantmentInfo> list) {
        List<String> out = new ArrayList<>();
        for (ItemData.EnchantmentInfo e : list) out.add(e.id);
        return out;
    }

    private static List<String> tr(ItemData data, String query) {
        return refs(data.search(query, "tr", null, ItemData.NO_LIMIT));
    }

    private static JsonObject manifestOf(String defaultVersion, String... versions) {
        JsonObject list = new JsonObject();
        for (String version : versions) {
            list.add(version, json("{'file':'v/items.0123456789ab.json','sha256':'" + repeat('0', 64) + "','bytes':1}"));
        }
        JsonObject manifest = new JsonObject();
        manifest.addProperty("format", 1);
        if (defaultVersion != null) manifest.addProperty("default", defaultVersion);
        manifest.add("versions", list);
        return manifest;
    }

    // ---- the file ----

    @Test
    public void readsRowsExtrasAndEnchantmentGroups() {
        ItemData data = data();
        assertEquals("26.3", data.minecraft());
        assertEquals(13, data.items().size());
        assertEquals(4, data.enchantments().size());
        ItemData.ItemInfo sword = data.itemInfo("minecraft:diamond_sword");
        assertNotNull(sword);
        assertEquals(1, sword.stack);
        assertEquals(1561, sword.maxDamage);
        assertEquals(Collections.singletonList("dia kilic"), sword.aliases);
        assertEquals("Elmas Kılıç", sword.name("tr"));
        assertEquals("Diamond Sword", sword.name("en"));
        assertEquals(Arrays.asList("minecraft:mending", "minecraft:sharpness", "minecraft:smite"), ids(data.enchantmentsFor(sword)));
        ItemData.ItemInfo book = data.itemInfo("minecraft:enchanted_book");
        assertNotNull(book);
        assertEquals(4, data.enchantmentsFor(book).size());
        ItemData.ItemInfo stone = data.itemInfo("minecraft:stone");
        assertNotNull(stone);
        assertTrue(data.enchantmentsFor(stone).isEmpty());
        assertTrue(stone.aliases.isEmpty());
        assertNull(stone.desc);
        assertNull(stone.description("tr"));
        ItemData.ItemInfo disc = data.itemInfo("minecraft:music_disc_cat");
        assertNotNull(disc);
        assertNotNull(disc.desc);
        assertEquals("C418 - cat", disc.desc.tr);
        assertEquals("C418 - cat", disc.desc.en);
        assertEquals("C418 - cat", disc.description("en"));
        ItemData.EnchantmentInfo sharpness = data.enchantmentInfo("minecraft:sharpness");
        ItemData.EnchantmentInfo smite = data.enchantmentInfo("minecraft:smite");
        ItemData.EnchantmentInfo mending = data.enchantmentInfo("minecraft:mending");
        ItemData.EnchantmentInfo curse = data.enchantmentInfo("minecraft:binding_curse");
        assertNotNull(sharpness);
        assertNotNull(smite);
        assertNotNull(mending);
        assertNotNull(curse);
        assertTrue(curse.curse);
        assertFalse(sharpness.curse);
        assertEquals(5, sharpness.maxLevel);
        assertEquals("Keskinlik", sharpness.name("tr"));
        assertEquals("Sharpness", sharpness.name("en"));
        assertEquals(Collections.singletonList("sharp"), sharpness.aliases);
        assertFalse(data.canCombine(sharpness, smite));
        assertFalse(data.canCombine(smite, sharpness));
        assertTrue(data.canCombine(sharpness, mending));
        assertFalse(data.canCombine(sharpness, sharpness));
        assertFalse(data.canCombine("minecraft:sharpness", "minecraft:smite"));
        assertTrue(data.canCombine("minecraft:sharpness", "minecraft:mending"));
        assertFalse(data.canCombine("minecraft:sharpness", "minecraft:nothing"));
        assertTrue(data.fits("minecraft:elytra", "minecraft:mending"));
        assertFalse(data.fits("minecraft:elytra", "minecraft:sharpness"));
        assertFalse(data.fits("minecraft:stone", "minecraft:mending"));
        assertTrue(data.fits("minecraft:enchanted_book", "minecraft:binding_curse"));
        assertFalse(data.fits("minecraft:nothing", "minecraft:mending"));
        assertNull(data.itemInfo("minecraft:nothing"));
        assertNull(data.enchantmentInfo("minecraft:nothing"));
    }

    @Test
    public void namesByIdAndLanguage() {
        ItemData data = data();
        assertEquals("Elmas", data.name("minecraft:diamond", "tr"));
        assertEquals("Diamond", data.name("minecraft:diamond", "en"));
        // Any other language is Turkish, as in the file's own fallback; so is none.
        assertEquals("Elmas", data.name("minecraft:diamond", "de"));
        assertEquals("Elmas", data.name("minecraft:diamond", null));
        assertNull(data.name("minecraft:nothing", "tr"));
        assertEquals("Keskinlik", data.enchantmentName("minecraft:sharpness", "tr"));
        assertEquals("Sharpness", data.enchantmentName("minecraft:sharpness", "en"));
        assertNull(data.enchantmentName("minecraft:nothing", "en"));
    }

    @Test
    public void isTheOrderChecksItemFacts() {
        ItemFacts facts = data();
        ItemFacts.Item sword = facts.item("minecraft:diamond_sword");
        assertNotNull(sword);
        assertEquals("minecraft:diamond_sword", sword.id);
        assertEquals(1, sword.stack);
        assertEquals(1561, sword.maxDamage);
        assertEquals(Arrays.asList("minecraft:mending", "minecraft:sharpness", "minecraft:smite"), sword.enchantments);
        assertFalse(sword.operator);
        ItemFacts.Item stone = facts.item("minecraft:stone");
        assertNotNull(stone);
        assertEquals(64, stone.stack);
        assertEquals(0, stone.maxDamage);
        assertTrue(stone.enchantments.isEmpty());
        ItemFacts.Item book = facts.item("minecraft:enchanted_book");
        assertNotNull(book);
        assertEquals(4, book.enchantments.size());
        ItemFacts.Item block = facts.item("minecraft:command_block");
        assertNotNull(block);
        assertTrue(block.operator);
        assertNull(facts.item("minecraft:nothing"));
        ItemFacts.Enchantment sharpness = facts.enchantment("minecraft:sharpness");
        assertNotNull(sharpness);
        assertEquals(5, sharpness.maxLevel);
        assertFalse(sharpness.curse);
        assertEquals(Collections.singletonList("minecraft:smite"), sharpness.excludes);
        ItemFacts.Enchantment curse = facts.enchantment("minecraft:binding_curse");
        assertNotNull(curse);
        assertTrue(curse.curse);
        assertTrue(curse.excludes.isEmpty());
        assertNull(facts.enchantment("minecraft:nothing"));
        // The same objects the richer view hands out.
        ItemData data = data();
        ItemData.ItemInfo info = data.itemInfo("minecraft:elytra");
        assertNotNull(info);
        assertSame(data.item("minecraft:elytra"), info.facts());
        ItemData.EnchantmentInfo mending = data.enchantmentInfo("minecraft:mending");
        assertNotNull(mending);
        assertSame(data.enchantment("minecraft:mending"), mending.facts());
    }

    @Test
    public void refusesBrokenOrNewerFilesAndReadsPastUnknownExtras() {
        assertNull(changed(d -> d.addProperty("format", 2)));
        assertNull(changed(d -> d.addProperty("format", 0)));
        assertNull(changed(d -> d.addProperty("format", "1")));
        assertNull(changed(d -> d.remove("format")));
        // A group that does not exist.
        assertNull(changed(d -> d.getAsJsonArray("items").get(0).getAsJsonArray().set(5, new JsonPrimitive(9))));
        assertNull(changed(d -> d.getAsJsonArray("items").get(0).getAsJsonArray().set(5, new JsonPrimitive(-2))));
        assertNull(changed(d -> addItem(d, "minecraft:stone", 64, 0, "Taş", "Stone", -1)));
        assertNull(changed(d -> addItem(d, "minecraft:x", 64, 0, "X", "X", -1, json("{'a':['Dia']}"))));
        assertNull(changed(d -> addItem(d, "minecraft:x", 0, 0, "X", "X", -1)));
        assertNull(changed(d -> addItem(d, "minecraft:x", 100, 0, "X", "X", -1)));
        assertNull(changed(d -> addItem(d, "minecraft:x", 64, -1, "X", "X", -1)));
        assertNull(changed(d -> addItem(d, "minecraft:x", 1.5, 0, "X", "X", -1)));
        assertNull(changed(d -> d.getAsJsonArray("ench").add(array("minecraft:x", 1, "X", "X", json("{'x':[4]}")))));
        assertNull(changed(d -> d.getAsJsonArray("groups").add(array(2, 1))));
        assertNull(changed(d -> d.getAsJsonArray("groups").add(array(1, 1))));
        assertNull(changed(d -> d.getAsJsonArray("groups").add(array(4))));
        assertNull(changed(d -> d.getAsJsonArray("groups").add(new JsonArray())));
        assertNull(changed(d -> d.add("items", new JsonArray())));
        assertNull(changed(d -> d.remove("items")));
        assertNull(changed(d -> d.remove("ench")));
        assertNull(changed(d -> d.remove("groups")));
        assertNotNull(changed(d -> addItem(d, "minecraft:x", 64, 0, "X", "X", -1, json("{'a':['x x'],'z':1}"))));
        assertNotNull(changed(d -> d.remove("src")));
        assertNull(ItemData.parse("{"));
        assertNull(ItemData.parse(""));
        assertNull(ItemData.parse(null));
        assertNull(ItemData.parse("[]"));
        assertNull(ItemData.parse("null"));
        assertNull(ItemData.parse("{\"format\":1}"));
        assertNull(ItemData.parse(SMALL + "garbage"));
        ItemData whole = ItemData.parse(SMALL);
        assertNotNull(whole);
        assertEquals(13, whole.items().size());
    }

    @Test
    public void checksIdsNamesAliasesAndExtrasStrictly() {
        // Ids: minecraft: and 1 to 64 of [a-z0-9_].
        assertNull(changed(d -> addItem(d, "mod:thing", 64, 0, "X", "X", -1)));
        assertNull(changed(d -> addItem(d, "minecraft:Upper", 64, 0, "X", "X", -1)));
        assertNull(changed(d -> addItem(d, "minecraft:a/b", 64, 0, "X", "X", -1)));
        assertNull(changed(d -> addItem(d, "minecraft:", 64, 0, "X", "X", -1)));
        assertNull(changed(d -> addItem(d, "stone", 64, 0, "X", "X", -1)));
        assertNull(changed(d -> addItem(d, "minecraft:" + repeat('a', 65), 64, 0, "X", "X", -1)));
        assertNotNull(changed(d -> addItem(d, "minecraft:" + repeat('a', 64), 64, 0, "X", "X", -1)));
        assertNull(changed(d -> d.getAsJsonArray("ench").add(array("mod:thing", 1, "X", "X"))));
        assertTrue(ItemData.isVanillaId("minecraft:a_1"));
        assertFalse(ItemData.isVanillaId("minecraft:a-1"));
        assertFalse(ItemData.isVanillaId("minecraft:a\n"));
        assertFalse(ItemData.isVanillaId(null));
        // Names: 1 to 240 characters, no space at either end.
        assertNull(changed(d -> addItem(d, "minecraft:x", 64, 0, " X", "X", -1)));
        assertNull(changed(d -> addItem(d, "minecraft:x", 64, 0, "X", "X ", -1)));
        assertNull(changed(d -> addItem(d, "minecraft:x", 64, 0, "X", "", -1)));
        assertNull(changed(d -> addItem(d, "minecraft:x", 64, 0, "X", JsonNull.INSTANCE, -1)));
        assertNull(changed(d -> addItem(d, "minecraft:x", 64, 0, repeat('x', 241), "X", -1)));
        assertNotNull(changed(d -> addItem(d, "minecraft:x", 64, 0, repeat('x', 240), "X", -1)));
        assertNull(changed(d -> d.addProperty("mc", repeat('9', 33))));
        assertNotNull(changed(d -> d.addProperty("mc", repeat('9', 32))));
        assertNull(changed(d -> d.addProperty("mc", "")));
        // Descriptions: exactly two names.
        assertNull(changed(d -> addItem(d, "minecraft:x", 64, 0, "X", "X", -1, json("{'d':['only one']}"))));
        assertNull(changed(d -> addItem(d, "minecraft:x", 64, 0, "X", "X", -1, json("{'d':['a','b','c']}"))));
        assertNull(changed(d -> addItem(d, "minecraft:x", 64, 0, "X", "X", -1, json("{'d':'a'}"))));
        assertNotNull(changed(d -> addItem(d, "minecraft:x", 64, 0, "X", "X", -1, json("{'d':['a','b']}"))));
        // Aliases: folded words, 2 to 40 characters, once each, at most 24.
        assertNull(changed(d -> addItem(d, "minecraft:x", 64, 0, "X", "X", -1, json("{'a':['a']}"))));
        assertNull(changed(d -> addItem(d, "minecraft:x", 64, 0, "X", "X", -1, json("{'a':['x  y']}"))));
        assertNull(changed(d -> addItem(d, "minecraft:x", 64, 0, "X", "X", -1, json("{'a':[' xy']}"))));
        assertNull(changed(d -> addItem(d, "minecraft:x", 64, 0, "X", "X", -1, json("{'a':['xy ']}"))));
        assertNull(changed(d -> addItem(d, "minecraft:x", 64, 0, "X", "X", -1, json("{'a':['x-y']}"))));
        assertNull(changed(d -> addItem(d, "minecraft:x", 64, 0, "X", "X", -1, json("{'a':['xy','xy']}"))));
        assertNull(changed(d -> addItem(d, "minecraft:x", 64, 0, "X", "X", -1, json("{'a':null}"))));
        assertNull(changed(d -> addItem(d, "minecraft:x", 64, 0, "X", "X", -1, json("{'a':'xy'}"))));
        assertNull(changed(d -> addItem(d, "minecraft:x", 64, 0, "X", "X", -1, json("{'a':[41]}"))));
        assertNull(changed(d -> addItem(d, "minecraft:x", 64, 0, "X", "X", -1, json("{'a':['" + repeat('x', 41) + "']}"))));
        assertNotNull(changed(d -> addItem(d, "minecraft:x", 64, 0, "X", "X", -1, json("{'a':['" + repeat('x', 40) + "']}"))));
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 25; i++) many.append(i == 0 ? "" : ",").append("'a").append(i).append("'");
        final String twentyFive = many.toString();
        assertNull(changed(d -> addItem(d, "minecraft:x", 64, 0, "X", "X", -1, json("{'a':[" + twentyFive + "]}"))));
        assertNotNull(changed(d -> addItem(d, "minecraft:x", 64, 0, "X", "X", -1,
                json("{'a':[" + twentyFive.substring(0, twentyFive.lastIndexOf(',')) + "]}"))));
        // Extras are an object (or absent); the flags are numbers.
        assertNull(changed(d -> addItem(d, "minecraft:x", 64, 0, "X", "X", -1, new JsonArray())));
        assertNull(changed(d -> addItem(d, "minecraft:x", 64, 0, "X", "X", -1, JsonParser.parseString("null"))));
        assertNull(changed(d -> addItem(d, "minecraft:x", 64, 0, "X", "X", -1, json("{'o':'1'}"))));
        assertNull(changed(d -> d.getAsJsonArray("ench").add(array("minecraft:x", 1, "X", "X", json("{'c':true}")))));
        assertNull(changed(d -> d.getAsJsonArray("ench").add(array("minecraft:x", 1, "X", "X", json("{'x':null}")))));
        // An enchantment: 4 or 5 columns, level 1 to 255, exclusions below the count and not its own.
        assertNull(changed(d -> d.getAsJsonArray("ench").add(array("minecraft:x", 1, "X"))));
        assertNull(changed(d -> d.getAsJsonArray("ench").add(array("minecraft:x", 0, "X", "X"))));
        assertNull(changed(d -> d.getAsJsonArray("ench").add(array("minecraft:x", 256, "X", "X"))));
        assertNotNull(changed(d -> d.getAsJsonArray("ench").add(array("minecraft:x", 255, "X", "X"))));
        assertNull(changed(d -> d.getAsJsonArray("ench").add(array("minecraft:x", 1, "X", "X", json("{'x':[5]}")))));
        assertNotNull(changed(d -> d.getAsJsonArray("ench").add(array("minecraft:x", 1, "X", "X", json("{'x':[0,3]}")))));
        assertNull(changed(d -> d.getAsJsonArray("ench").add(array("minecraft:x", 1, "X", "X", json("{'x':[3,0]}")))));
        // The flags: only 1 is true.
        ItemData flags = changed(d -> {
            addItem(d, "minecraft:flag0", 64, 0, "X0", "X0", -1, json("{'o':0}"));
            addItem(d, "minecraft:flag2", 64, 0, "X2", "X2", -1, json("{'o':2}"));
            addItem(d, "minecraft:flag1", 64, 0, "X1", "X1", -1, json("{'o':1.0}"));
        });
        assertNotNull(flags);
        assertFalse(flags.item("minecraft:flag0").operator);
        assertFalse(flags.item("minecraft:flag2").operator);
        assertTrue(flags.item("minecraft:flag1").operator);
        // Numbers written as 64.0 are 64.
        ItemData floats = changed(d -> addItem(d, "minecraft:f", 64.0, 3.0, "F", "F", -1.0));
        assertNotNull(floats);
        assertEquals(3, floats.item("minecraft:f").maxDamage);
        // A file over the limit is not read.
        assertNull(ItemData.parse(repeat(' ', ItemData.MAX_BYTES + 1) + SMALL));
    }

    // ---- versions ----

    @Test
    public void picksTheNearestVersionsFile() {
        JsonObject manifest = manifestOf("26.3", "1.20.5", "1.21.4", "1.21.10", "26.1.2", "26.3");
        assertEquals("26.1.2", ItemData.pickVersion(manifest, "26.1.2"));
        assertEquals("26.1.2", ItemData.pickVersion(manifest, "26.2"));
        assertEquals("1.21.10", ItemData.pickVersion(manifest, "1.21.11"));
        assertEquals("26.3", ItemData.pickVersion(manifest, "26.4-snapshot-5"));
        assertEquals("1.20.5", ItemData.pickVersion(manifest, "1.8.9"));
        assertEquals("26.3", ItemData.pickVersion(manifest, null));
        // 1.21.10 is after 1.21.4 and 1.21.9, not between 1.21.1 and 1.21.2.
        assertEquals("1.21.4", ItemData.pickVersion(manifest, "1.21.9"));
        assertEquals("1.20.5", ItemData.pickVersion(manifest, "1.21.1"));
    }

    @Test
    public void picksWhenTheManifestIsOdd() {
        // An empty version is no version; the default must be one of the versions, else the newest.
        assertEquals("26.3", ItemData.pickVersion(manifestOf("26.3", "1.21.4", "26.3"), ""));
        assertEquals("26.3", ItemData.pickVersion(manifestOf("1.99", "1.21.4", "26.3"), null));
        assertEquals("26.3", ItemData.pickVersion(manifestOf(null, "26.3", "1.21.4"), null));
        assertEquals("1.21.4", ItemData.pickVersion(manifestOf("1.21.4", "26.3", "1.21.4"), null));
        assertNull(ItemData.pickVersion(manifestOf("26.3"), null));
        assertNull(ItemData.pickVersion(manifestOf("26.3"), "26.3"));
        assertNull(ItemData.pickVersion(new JsonObject(), "26.3"));
        // An entry that is not an object is no version.
        JsonObject odd = manifestOf("26.3", "1.21.4");
        odd.getAsJsonObject("versions").add("26.9", new JsonPrimitive("x"));
        assertEquals("1.21.4", ItemData.pickVersion(odd, "26.9"));
        // A release and its release candidate read as the same number: the sorted names break the tie, as in Rust.
        JsonObject tie = manifestOf("26.1", "26.1-rc1", "26.1");
        assertEquals("26.1-rc1", ItemData.pickVersion(tie, "26.1-rc1"));
        assertEquals("26.1", ItemData.pickVersion(tie, "26.1"));
        assertEquals("26.1-rc1", ItemData.pickVersion(tie, "26.1-rc2"));
        assertTrue(ItemData.compareVersions("1.21.10", "1.21.9") > 0);
        assertTrue(ItemData.compareVersions("26.1", "26.1.0") == 0);
        assertTrue(ItemData.compareVersions("26.1", "26.1.1") < 0);
        assertTrue(ItemData.compareVersions("26w14a", "26.0") == 0);
        assertTrue(ItemData.compareVersions("26w14a", "26.1") < 0);
        assertTrue(ItemData.compareVersions("1.8.9", "1.20.5") < 0);
    }

    // ---- search ----

    @Test
    public void findsItemsTheWayPlayersWriteThem() {
        ItemData data = data();
        assertEquals(Arrays.asList("diamond", "diamond_block", "diamond_sword"), tr(data, "elmas"));
        assertEquals("diamond_sword", tr(data, "ELMAS KILIÇ").get(0));
        assertEquals("diamond_sword", tr(data, "elmas kilic").get(0));
        assertEquals(Collections.singletonList("diamond_sword"), tr(data, "elmas kil"));
        assertEquals("diamond_sword", tr(data, "dia kılıç").get(0));
        assertEquals("diamond", tr(data, "dia").get(0));
        assertEquals("diamond_sword", tr(data, "Diamond Sword").get(0));
        assertEquals(Collections.singletonList("elytra"), tr(data, "kanat"));
        assertEquals(Collections.singletonList("elytra"), tr(data, "elytra"));
        assertEquals(Collections.singletonList("elytra"), tr(data, "elitar"));
        assertEquals(Collections.singletonList("sentry_armor_trim_smithing_template"), tr(data, "nöbetçi"));
        assertEquals(Collections.singletonList("music_disc_pigstep"), tr(data, "pigstep"));
        assertEquals(Arrays.asList("music_disc_cat", "music_disc_pigstep"), tr(data, "müzik"));
        assertEquals(Arrays.asList("beacon", "lantern"), tr(data, "fener"));
        assertEquals(Collections.emptyList(), tr(data, "komut"));
        assertEquals(Collections.singletonList("command_block"), refs(data.search("komut", "tr", null, ItemData.NO_LIMIT, true)));
        assertEquals(Collections.singletonList("obsidian"), tr(data, "obsi"));
        assertEquals(2, data.search("elmas", "tr", null, 2).size());
        assertEquals(Arrays.asList("diamond", "diamond_block"), refs(data.search("elmas", "tr", null, 2)));
        assertEquals(Collections.emptyList(), tr(data, ""));
        assertEquals(Collections.emptyList(), tr(data, "   "));
        assertEquals(Collections.emptyList(), tr(data, null));
        assertEquals(Collections.emptyList(), refs(data.search("elmas", "tr", null, 0)));
        assertEquals(Collections.singletonList("diamond_block"), refs(data.search("block of", "en", null, ItemData.NO_LIMIT)));
        // A digit and a word, with the dash between them gone.
        assertEquals(Collections.singletonList("music_disc_cat"), tr(data, "c418"));
        assertEquals(Collections.singletonList("music_disc_cat"), tr(data, "C418 - cat"));
        // No language, or another one, is Turkish.
        assertEquals(tr(data, "elmas"), refs(data.search("elmas", null, null, ItemData.NO_LIMIT)));
        assertEquals(tr(data, "elmas"), refs(data.search("elmas", "de", null, ItemData.NO_LIMIT)));
    }

    @Test
    public void ranksFromTheNameExactlyToATypo() {
        ItemData data = data();
        // "dia": the alias exactly (1), the other language's name's beginning (5), the beginning of one of its words (6), a
        // name with it inside (8): not the file's order.
        assertEquals(Arrays.asList("diamond", "diamond_sword", "diamond_block", "obsidian"), tr(data, "dia"));
        // Two typos in a word of 7 letters or more, a transposition for one: "obsidyan", "elitar".
        assertEquals(Collections.singletonList("obsidian"), tr(data, "obsidyan"));
        // Three letters or fewer: no typos, only the beginning of a word; each longer word of a query may be a typo.
        assertEquals(Collections.emptyList(), tr(data, "elx"));
        assertEquals(Collections.singletonList("diamond_sword"), tr(data, "elmas kilix"));
        // The rank table, built so each item lands on exactly one rank for "star" (the typo comes last, and only while there is
        // room: a full page of better hits asks for no typos).
        // The rows are in the opposite order of their ranks, so two ranks that tied would show.
        ItemData ranks = tiny(
                new Object[]{"minecraft:r9", 64, 0, "Stor", "Zed Nine", -1},
                new Object[]{"minecraft:r8", 64, 0, "Bigstarry", "Zed Eight", -1},
                new Object[]{"minecraft:r7", 64, 0, "Zed Seven", "Zed Seven", -1, json("{'a':['big star']}")},
                new Object[]{"minecraft:r6", 64, 0, "Zed Six", "Big Star Plate", -1},
                new Object[]{"minecraft:r5", 64, 0, "Zed Five", "Star Plate", -1},
                new Object[]{"minecraft:r4", 64, 0, "Zed Four", "Star", -1},
                new Object[]{"minecraft:r3", 64, 0, "Big Star", "Zed Three", -1},
                new Object[]{"minecraft:r2", 64, 0, "Star Block", "Zed Two", -1},
                new Object[]{"minecraft:r1", 64, 0, "Zed One", "Zed One", -1, json("{'a':['star']}")},
                new Object[]{"minecraft:r0", 64, 0, "Star", "Zed Zero", -1});
        assertEquals(Arrays.asList("r0", "r1", "r2", "r3", "r4", "r5", "r6", "r7", "r8", "r9"), tr(ranks, "star"));
        assertEquals(Arrays.asList("r0", "r1", "r2", "r3", "r4", "r5", "r6", "r7", "r8"),
                refs(ranks.search("star", "tr", null, 9)));
        assertEquals(10, ranks.search("star", "tr", null, 10).size());
        // In English the names swap sides.
        assertEquals(Arrays.asList("r4", "r1", "r5", "r6", "r0", "r2", "r3", "r7", "r8", "r9"),
                refs(ranks.search("star", "en", null, ItemData.NO_LIMIT)));
    }

    @Test
    public void foldsApostrophesAndSignsInNames() {
        String quote = String.valueOf((char) 0x2019);
        ItemData data = tiny(
                new Object[]{"minecraft:jack_o_lantern", 64, 0, "Jack o" + quote + "Lantern", "Jack o'Lantern", -1},
                new Object[]{"minecraft:tnt_minecart", 1, 0, "TNT" + quote + "li Vagon", "Minecart with TNT", -1});
        assertEquals(Collections.singletonList("jack_o_lantern"), tr(data, "jack olantern"));
        assertEquals(Collections.singletonList("jack_o_lantern"), tr(data, "jack o" + quote + "lantern"));
        assertEquals(Collections.singletonList("jack_o_lantern"), tr(data, "jack o'lantern"));
        assertEquals(Collections.singletonList("jack_o_lantern"), tr(data, "olantern"));
        assertEquals(Collections.singletonList("tnt_minecart"), tr(data, "tntli"));
        assertEquals(Collections.singletonList("tnt_minecart"), tr(data, "tnt" + quote + "li vagon"));
        // A dash splits a word where an apostrophe joins it: "tnt li" is not "tntli".
        assertEquals(Collections.emptyList(), tr(data, "tnt-li vagon"));
        // Every mark that joins: right single quote, apostrophe, grave, acute and the modifier letter apostrophe. A typo could make
        // up for a mark that was left in (or split the word), so the rank shows it: the joined name is an exact match and comes
        // first, ahead of the name that merely starts with the query (which the file lists first).
        for (char mark : new char[]{(char) 0x2019, '\'', '`', (char) 0x00B4, (char) 0x02BC}) {
            ItemData two = tiny(
                    new Object[]{"minecraft:b", 64, 0, "Alphabeta Plus", "Alphabeta Plus", -1},
                    new Object[]{"minecraft:a", 64, 0, "Alpha" + mark + "Beta", "Alpha" + mark + "Beta", -1});
            assertEquals("mark " + (int) mark, Arrays.asList("a", "b"), tr(two, "alphabeta"));
            assertEquals("mark " + (int) mark, Arrays.asList("a", "b"), tr(two, "alpha" + mark + "beta"));
        }
    }

    @Test
    public void customItemsAreFoundFirstAmongEquals() {
        ItemData data = data();
        List<ItemData.Extra> extras = Collections.singletonList(new ItemData.Extra("custom:c1", Collections.singletonList("Yakut")));
        List<ItemData.Hit> hits = data.search("yak", "tr", extras, ItemData.NO_LIMIT);
        assertEquals("custom:c1", hits.get(0).ref);
        assertSame(extras.get(0), hits.get(0).extra);
        assertNull(hits.get(0).item);
        assertEquals(Collections.singletonList("custom:c1"),
                refs(ItemData.searchItems(null, "yakut", "tr", extras, ItemData.NO_LIMIT, false)));
        assertEquals(Collections.emptyList(), refs(ItemData.searchItems(null, "yakut", "tr", null, ItemData.NO_LIMIT, false)));
        // The same rank: the custom item first, then the game's order.
        List<ItemData.Extra> twin = Collections.singletonList(new ItemData.Extra("custom:elmas", Collections.singletonList("Elmas")));
        List<ItemData.Hit> found = data.search("elmas", "tr", twin, ItemData.NO_LIMIT);
        assertEquals(Arrays.asList("custom:elmas", "diamond", "diamond_block", "diamond_sword"), refs(found));
        assertNotNull(found.get(1).item);
        assertNull(found.get(1).extra);
        assertEquals("minecraft:diamond", found.get(1).item.id);
    }

    @Test
    public void saysWhichOfTwoSameNamedItemsIsWhich() {
        ItemData data = data();
        ItemData.ItemInfo beacon = data.itemInfo("minecraft:beacon");
        ItemData.ItemInfo lantern = data.itemInfo("minecraft:lantern");
        assertNotNull(beacon);
        assertNotNull(lantern);
        assertEquals("Beacon", data.nameHint(beacon, "tr"));
        assertEquals("Lantern", data.nameHint(lantern, "tr"));
        assertNull(data.nameHint(beacon, "en"));
        ItemData.ItemInfo cat = data.itemInfo("minecraft:music_disc_cat");
        ItemData.ItemInfo diamond = data.itemInfo("minecraft:diamond");
        assertNotNull(cat);
        assertNotNull(diamond);
        // The same name, but a different grey line: nothing to tell apart.
        assertNull(data.nameHint(cat, "tr"));
        assertNull(data.nameHint(diamond, "tr"));
    }

    @Test
    public void findsEnchantmentsOnlyTheOnesThatFitWhenAnItemIsGiven() {
        ItemData data = data();
        assertEquals(Collections.singletonList("minecraft:sharpness"),
                ids(data.searchEnchantments("sharp", "tr", null, ItemData.NO_LIMIT)));
        assertEquals(Collections.singletonList("minecraft:sharpness"), ids(data.searchEnchantments("kes", "tr", null, ItemData.NO_LIMIT)));
        assertEquals(Collections.singletonList("minecraft:mending"), ids(data.searchEnchantments("onarim", "tr", null, ItemData.NO_LIMIT)));
        assertEquals(Collections.singletonList("minecraft:mending"), ids(data.searchEnchantments("ONARIM", "en", null, ItemData.NO_LIMIT)));
        ItemData.ItemInfo elytra = data.itemInfo("minecraft:elytra");
        assertNotNull(elytra);
        assertEquals(Collections.singletonList("minecraft:mending"), ids(data.searchEnchantments("", "tr", elytra, ItemData.NO_LIMIT)));
        assertEquals(Collections.emptyList(), ids(data.searchEnchantments("sharp", "tr", elytra, ItemData.NO_LIMIT)));
        // An empty query lists the pool in the file's order, as far as the limit goes.
        assertEquals(4, data.searchEnchantments("", "tr", null, ItemData.NO_LIMIT).size());
        assertEquals(Arrays.asList("minecraft:binding_curse", "minecraft:mending"), ids(data.searchEnchantments(null, "tr", null, 2)));
        assertEquals(Collections.emptyList(), ids(data.searchEnchantments("", "tr", null, 0)));
        // Either language and the aliases find them.
        assertEquals(Collections.singletonList("minecraft:smite"), ids(data.searchEnchantments("smite", "tr", null, ItemData.NO_LIMIT)));
        assertEquals(Collections.singletonList("minecraft:binding_curse"),
                ids(data.searchEnchantments("curse of", "tr", null, ItemData.NO_LIMIT)));
        assertEquals(Arrays.asList("I", "IV", "V", "X", "11", "0", "-3"), Arrays.asList(ItemData.levelText(1), ItemData.levelText(4),
                ItemData.levelText(5), ItemData.levelText(10), ItemData.levelText(11), ItemData.levelText(0), ItemData.levelText(-3)));
    }

    @Test
    public void aPairIsRefusedWhenEitherOnesExclusionsNamesTheOther() {
        // Only one of the two lists the other: the game refuses the pair either way round.
        ItemData onlySharpness = changed(d -> d.getAsJsonArray("ench").set(3, array("minecraft:smite", 5, "Çarpma", "Smite")));
        assertNotNull(onlySharpness);
        assertFalse(onlySharpness.canCombine("minecraft:sharpness", "minecraft:smite"));
        assertFalse(onlySharpness.canCombine("minecraft:smite", "minecraft:sharpness"));
        assertTrue(onlySharpness.enchantment("minecraft:smite").excludes.isEmpty());
        ItemData onlySmite = changed(d -> d.getAsJsonArray("ench").set(2, array("minecraft:sharpness", 5, "Keskinlik", "Sharpness")));
        assertNotNull(onlySmite);
        assertFalse(onlySmite.canCombine("minecraft:sharpness", "minecraft:smite"));
        assertFalse(onlySmite.canCombine("minecraft:smite", "minecraft:sharpness"));
        assertTrue(onlySmite.canCombine("minecraft:sharpness", "minecraft:mending"));
    }

    @Test
    public void asksForTwentyHitsWhenNoLimitIsGiven() {
        Object[][] rows = new Object[25][];
        List<String> first = new ArrayList<>();
        for (int i = 0; i < rows.length; i++) {
            rows[i] = new Object[]{"minecraft:stone_" + i, 64, 0, "Stone " + i, "Stone " + i, -1};
            if (i < 20) first.add("stone_" + i);
        }
        ItemData data = tiny(rows);
        assertEquals(first, refs(data.search("stone", "tr", null, ItemData.NO_LIMIT)));
        assertEquals(25, data.search("stone", "tr", null, 25).size());
        assertEquals(25, data.search("stone", "tr", null, 1000).size());
        assertEquals(first.subList(0, 5), refs(data.search("stone", "tr", null, 5)));
    }

    @Test
    public void foldsLikeThePlExactFold() {
        // The same vectors as crates/pl-items data.rs folds_like_the_interface and the launcher's item-data test.
        String quote = String.valueOf((char) 0x2019);
        String cedilla = String.valueOf((char) 0x0327);
        String[][] vectors = {
            {"Elmas Kılıç", "elmas kilic"},
            {"IŞIK", "isik"},
            {"İnci", "inci"},
            {"Ölümsüzlük Totemi", "olumsuzluk totemi"},
            {"Rüzgâr Patlaması", "ruzgar patlamasi"},
            {"ÇĞİÖŞÜ çğıöşü", "cgiosu cgiosu"},
            {"Iron Ingot", "iron ingot"},
            {"Café crème", "cafe creme"},
            {"s" + cedilla + "apka", "sapka"},
            {"Jack o" + quote + "Lantern", "jack o" + quote + "lantern"},
            {"TNT" + quote + "li Vagon", "tnt" + quote + "li vagon"},
            {"æøł", "æøł"},
            {"", ""},
            {"Iris", "iris"},
            {"C418 - cat", "c418 - cat"},
        };
        for (String[] vector : vectors) assertEquals(vector[0], vector[1], ItemData.fold(vector[0]));
    }

    @Test
    public void searchesAreSafeFromSeveralThreadsAtOnce() throws Exception {
        final ItemData data = data();
        final CountDownLatch go = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread[] threads = new Thread[6];
        for (int i = 0; i < threads.length; i++) {
            final String lang = i % 2 == 0 ? "tr" : "en";
            threads[i] = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        go.await();
                        for (int n = 0; n < 40; n++) {
                            assertEquals("diamond", refs(data.search("diamond", lang, null, 1)).get(0));
                            assertEquals(Arrays.asList("beacon", "lantern"), refs(data.search("fener", "tr", null, 5)));
                            assertNotNull(data.nameHint(data.itemInfo("minecraft:beacon"), "tr"));
                            assertEquals(1, data.searchEnchantments("sharp", lang, null, 5).size());
                        }
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                    }
                }
            });
            threads[i].start();
        }
        go.countDown();
        for (Thread thread : threads) thread.join(30000);
        assertNull(String.valueOf(failure.get()), failure.get());
        data.warm("en");
        data.warm(null);
    }

    // ---- the loader ----

    /** A local stand-in for the CDN: serves {@code files} under {@code /itemdata/v1/} and notes every path asked. */
    private static final class Cdn {
        static final String PREFIX = "/itemdata/v1/";
        final HttpServer server;
        final Map<String, byte[]> files = new ConcurrentHashMap<>();
        final Set<String> chunked = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
        final List<String> asked = new CopyOnWriteArrayList<>();
        volatile int failWith;

        Cdn() throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                asked.add(path);
                try {
                    if (failWith != 0) {
                        exchange.sendResponseHeaders(failWith, -1);
                        return;
                    }
                    byte[] body = files.get(path);
                    if (body == null) {
                        exchange.sendResponseHeaders(404, -1);
                        return;
                    }
                    exchange.sendResponseHeaders(200, chunked.contains(path) ? 0 : body.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                } finally {
                    exchange.close();
                }
            });
            server.start();
        }

        String base() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + PREFIX;
        }

        void put(String path, byte[] bytes) {
            files.put(PREFIX + path, bytes);
        }

        void remove(String path) {
            files.remove(PREFIX + path);
        }

        int count(String path) {
            int n = 0;
            for (String asked : this.asked) {
                if (asked.equals(PREFIX + path)) n++;
            }
            return n;
        }

        void stop() {
            server.stop(0);
        }
    }

    private static final class Time implements ItemDataStore.Clock {
        private long mNow = 1000000L;

        @Override
        public long millis() {
            return mNow;
        }

        void advance(long millis) {
            mNow += millis;
        }
    }

    private Cdn cdn() throws IOException {
        Cdn cdn = new Cdn();
        mCdns.add(cdn);
        return cdn;
    }

    private ItemDataStore store(Cdn cdn, File cache) {
        return new ItemDataStore(cache, mClient, cdn.base());
    }

    private File cache() throws IOException {
        return mTmp.newFolder();
    }

    /** A version's file: the small data file with {@code mc} and, for another variant, one more item. */
    private static byte[] dataFile(String mc, boolean moreItems) {
        JsonObject doc = JsonParser.parseString(SMALL).getAsJsonObject();
        doc.addProperty("mc", mc);
        if (moreItems) addItem(doc, "minecraft:extra", 64, 0, "Ek", "Extra", -1);
        return doc.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] dataFile(String mc) {
        return dataFile(mc, false);
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out = new StringBuilder();
            for (byte b : digest) out.append(String.format("%02x", b & 0xFF));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String pathOf(String version, byte[] bytes) {
        return version + "/items." + sha256(bytes).substring(0, 12) + ".json";
    }

    private static Map<String, byte[]> versions(Object... pairs) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) out.put((String) pairs[i], (byte[]) pairs[i + 1]);
        return out;
    }

    /** The manifest naming these files by their hashes ({@code bytes} as given); only the manifest is served. */
    private static byte[] manifestFor(String defaultVersion, Map<String, byte[]> files, Long claimedBytes) {
        JsonObject list = new JsonObject();
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("file", pathOf(file.getKey(), file.getValue()));
            entry.addProperty("sha256", sha256(file.getValue()));
            entry.addProperty("bytes", claimedBytes != null ? claimedBytes : (long) file.getValue().length);
            list.add(file.getKey(), entry);
        }
        JsonObject manifest = new JsonObject();
        manifest.addProperty("format", 1);
        manifest.addProperty("default", defaultVersion);
        manifest.add("versions", list);
        return manifest.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Serves each file under its hash-named path and the manifest naming them. */
    private static void publish(Cdn cdn, String defaultVersion, Map<String, byte[]> files) {
        for (Map.Entry<String, byte[]> file : files.entrySet()) cdn.put(pathOf(file.getKey(), file.getValue()), file.getValue());
        cdn.put("manifest.json", manifestFor(defaultVersion, files, null));
    }

    private static List<String> names(File folder) {
        String[] list = folder.list();
        List<String> out = new ArrayList<>(list == null ? Collections.<String>emptyList() : Arrays.asList(list));
        Collections.sort(out);
        return out;
    }

    private static List<String> dataFiles(File cache) {
        List<String> out = new ArrayList<>();
        for (String name : names(new File(cache, "items"))) {
            if (name.startsWith("data-")) out.add(name);
        }
        return out;
    }

    private static byte[] bytesOf(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    /** The same data file with one letter changed ("Stone" is "Stond"): as long, as good, and not the file its hash names. */
    private static byte[] altered(byte[] file) {
        byte[] out = new String(file, StandardCharsets.UTF_8).replace("\"Stone\"", "\"Stond\"").getBytes(StandardCharsets.UTF_8);
        assertEquals(file.length, out.length);
        assertFalse(Arrays.equals(file, out));
        assertNotNull("only its hash can tell", ItemData.parse(new String(out, StandardCharsets.UTF_8)));
        return out;
    }

    @Test
    public void loadsAVersionFromTheManifestAfterCheckingItsSha256() throws Exception {
        byte[] body = SMALL.getBytes(StandardCharsets.UTF_8);
        Cdn cdn = cdn();
        File cache = cache();
        publish(cdn, "26.3", versions("26.3", body));
        String path = pathOf("26.3", body);
        // The first answer for the file is a corrupt one blank: the manifest's size and hash do not match it.
        cdn.put(path, " ".getBytes(StandardCharsets.UTF_8));
        ItemDataStore store = store(cdn, cache);
        assertNull(store.load("26.3.1"));
        assertTrue("nothing unchecked is kept", dataFiles(cache).isEmpty());
        // A miss is asked again next time.
        cdn.put(path, body);
        ItemData loaded = store.load("26.3.1");
        assertNotNull(loaded);
        assertEquals("26.3", loaded.minecraft());
        assertEquals(13, loaded.items().size());
        // Then it is the one read this session: no new request.
        assertSame(loaded, store.load("26.3.1"));
        assertSame(loaded, store.load("26.3"));
        assertSame(loaded, store.load(null));
        assertEquals("the manifest once, the file twice (the corrupt answer, the good one)", 2, cdn.count(path));
        assertEquals(1, cdn.count("manifest.json"));
        // Another base has nothing.
        Cdn empty = cdn();
        assertNull(new ItemDataStore(cache(), mClient, empty.base()).load("26.3"));
    }

    @Test
    public void refusesAFileWhoseBytesAreNotTheManifestsSha256() throws Exception {
        byte[] good = SMALL.getBytes(StandardCharsets.UTF_8);
        byte[] altered = altered(good);
        Cdn cdn = cdn();
        File cache = cache();
        publish(cdn, "26.3", versions("26.3", good));
        // Same length, a good data file too, one letter different: only the hash can tell.
        cdn.put(pathOf("26.3", good), altered);
        assertNull(store(cdn, cache).load("26.3"));
        assertTrue("nothing unchecked is kept", dataFiles(cache).isEmpty());
        // A file changed on disk is not read either, not even a good data file of the same length: it is fetched again.
        cdn.put(pathOf("26.3", good), good);
        ItemDataStore store = store(cdn, cache);
        assertNotNull(store.load("26.3"));
        List<String> kept = dataFiles(cache);
        assertEquals(1, kept.size());
        File file = new File(new File(cache, "items"), kept.get(0));
        for (byte[] changed : new byte[][]{"{}".getBytes(StandardCharsets.UTF_8), altered}) {
            try (OutputStream out = new FileOutputStream(file)) {
                out.write(changed);
            }
            ItemData again = store(cdn, cache).load("26.3");
            assertNotNull(again);
            assertEquals(13, again.items().size());
            assertEquals("Stone", again.name("minecraft:stone", "en"));
            assertEquals("the file is the checked one again", sha256(good), sha256(bytesOf(file)));
        }
    }

    @Test
    public void followsOnlyAFileNextToTheManifestNamedByItsOwnHash() throws Exception {
        byte[] body = SMALL.getBytes(StandardCharsets.UTF_8);
        String sha = sha256(body);
        String twelve = sha.substring(0, 12);
        String[] odd = {
            "https://evil.test/items." + twelve + ".json",
            "26.3/items." + repeat('0', 12) + ".json",
            "../items." + twelve + ".json",
            "26.3/../items." + twelve + ".json",
            "/26.3/items." + twelve + ".json",
            "26.3/sub/items." + twelve + ".json",
            "26.3/items." + twelve.toUpperCase() + ".json",
            "26.3/items." + twelve + ".json.bak",
            "26.3/data." + twelve + ".json",
            ".3/items." + twelve + ".json",
        };
        for (String file : odd) {
            Cdn cdn = cdn();
            JsonObject entry = new JsonObject();
            entry.addProperty("file", file);
            entry.addProperty("sha256", sha);
            entry.addProperty("bytes", body.length);
            JsonObject list = new JsonObject();
            list.add("26.3", entry);
            JsonObject manifest = new JsonObject();
            manifest.addProperty("format", 1);
            manifest.addProperty("default", "26.3");
            manifest.add("versions", list);
            cdn.put("manifest.json", manifest.toString().getBytes(StandardCharsets.UTF_8));
            // The real bytes are there, under a good name and under the odd one: neither is asked.
            cdn.put(pathOf("26.3", body), body);
            cdn.put(file.startsWith("/") ? file.substring(1) : file, body);
            assertNull(file, store(cdn, cache()).load("26.3"));
            assertEquals(file, Collections.singletonList(Cdn.PREFIX + "manifest.json"), cdn.asked);
        }
        // The versions themselves are file-name parts.
        for (String version : new String[]{"../x", ".hidden", "a/b", "", repeat('9', 33), "a b"}) {
            Cdn cdn = cdn();
            JsonObject entry = new JsonObject();
            entry.addProperty("file", pathOf("26.3", body));
            entry.addProperty("sha256", sha);
            entry.addProperty("bytes", body.length);
            JsonObject list = new JsonObject();
            list.add(version, entry);
            JsonObject manifest = new JsonObject();
            manifest.addProperty("format", 1);
            manifest.add("versions", list);
            cdn.put("manifest.json", manifest.toString().getBytes(StandardCharsets.UTF_8));
            cdn.put(pathOf("26.3", body), body);
            assertNull(version, store(cdn, cache()).load(version.isEmpty() ? null : version));
            assertEquals(version, 1, cdn.asked.size());
        }
    }

    @Test
    public void keepsTheFilesAndTheManifestCopyForAnOfflineStart() throws Exception {
        byte[] body = dataFile("26.3");
        Cdn cdn = cdn();
        File cache = cache();
        publish(cdn, "26.3", versions("26.3", body));
        ItemData online = store(cdn, cache).load("26.3");
        assertNotNull(online);
        String twelve = sha256(body).substring(0, 12);
        assertEquals(Collections.singletonList("data-26.3-" + twelve + ".json"), names(new File(cache, "items")));
        assertTrue(new File(cache, "itemdata-manifest.json").isFile());
        assertEquals(body.length, new File(new File(cache, "items"), "data-26.3-" + twelve + ".json").length());
        // A new session without the CDN reads the kept manifest and file.
        String base = cdn.base();
        cdn.stop();
        ItemDataStore offline = new ItemDataStore(cache, mClient, base);
        ItemData again = offline.load("26.3");
        assertNotNull(again);
        assertEquals(13, again.items().size());
        assertEquals("26.3", again.minecraft());
        assertNotNull(again.item("minecraft:diamond_sword"));
        // The same session keeps answering from memory, and a version between files takes the newest older one.
        assertSame(again, offline.load("26.3"));
        assertSame(again, offline.load("26.4"));
        // A kept file that is no longer the one its name says (its hash) is not read, with or without the manifest.
        File file = new File(new File(cache, "items"), "data-26.3-" + twelve + ".json");
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(altered(body));
        }
        assertNull(new ItemDataStore(cache, mClient, base).load("26.3"));
        assertTrue(new File(cache, "itemdata-manifest.json").delete());
        assertNull(new ItemDataStore(cache, mClient, base).load("26.3"));
        assertNull(new ItemDataStore(cache, mClient, base).load(null));
    }

    @Test
    public void picksTheVersionTheWayTheManifestDoes() throws Exception {
        byte[] old = dataFile("26.1.2");
        byte[] current = dataFile("26.3");
        Cdn cdn = cdn();
        publish(cdn, "26.3", versions("26.1.2", old, "26.3", current));
        ItemDataStore store = store(cdn, cache());
        ItemData one = store.load("26.1.2");
        assertNotNull(one);
        assertEquals("26.1.2", one.minecraft());
        ItemData between = store.load("26.2");
        assertNotNull(between);
        assertEquals("the newest older file", "26.1.2", between.minecraft());
        ItemData none = store.load(null);
        assertNotNull(none);
        assertEquals("the manifest's default", "26.3", none.minecraft());
        ItemData ancient = store.load("1.8.9");
        assertNotNull(ancient);
        assertEquals("the oldest, when all are newer", "26.1.2", ancient.minecraft());
        assertEquals("the manifest once, each file once", 1, cdn.count("manifest.json"));
        assertEquals(1, cdn.count(pathOf("26.1.2", old)));
        assertEquals(1, cdn.count(pathOf("26.3", current)));
    }

    @Test
    public void readsTheManifestAgainAfterFiveMinutesAndKeepsTheLastGoodCopy() throws Exception {
        byte[] body = dataFile("26.3");
        Cdn cdn = cdn();
        File cache = cache();
        Time time = new Time();
        publish(cdn, "26.3", versions("26.3", body));
        byte[] goodManifest = cdn.files.get(Cdn.PREFIX + "manifest.json");
        ItemDataStore store = new ItemDataStore(cache, mClient, cdn.base(), time);
        assertNotNull(store.load("26.3"));
        assertEquals(1, cdn.count("manifest.json"));
        time.advance(4 * MINUTE);
        assertNotNull(store.load("26.3"));
        assertEquals("still fresh", 1, cdn.count("manifest.json"));
        time.advance(2 * MINUTE);
        assertNotNull(store.load("26.3"));
        assertEquals("read again after five minutes", 2, cdn.count("manifest.json"));
        // The CDN loses the manifest: the last good copy answers, and it counts as read for five minutes.
        cdn.remove("manifest.json");
        time.advance(6 * MINUTE);
        assertNotNull(store.load("26.3"));
        assertEquals(3, cdn.count("manifest.json"));
        time.advance(MINUTE);
        assertNotNull(store.load("26.3"));
        assertEquals("not asked again at once", 3, cdn.count("manifest.json"));
        time.advance(5 * MINUTE);
        assertNotNull(store.load("26.3"));
        assertEquals(4, cdn.count("manifest.json"));
        // A manifest of a newer format, or garbage, is no manifest: the copy stays in use.
        cdn.put("manifest.json", "{\"format\":2,\"versions\":{}}".getBytes(StandardCharsets.UTF_8));
        time.advance(6 * MINUTE);
        assertNotNull(store.load("26.3"));
        cdn.put("manifest.json", "<html>maintenance</html>".getBytes(StandardCharsets.UTF_8));
        time.advance(6 * MINUTE);
        assertNotNull(store.load("26.3"));
        assertEquals(6, cdn.count("manifest.json"));
        cdn.failWith = 503;
        time.advance(6 * MINUTE);
        assertNotNull(store.load("26.3"));
        assertEquals(7, cdn.count("manifest.json"));
        // The copy on disk is still the good one, byte for byte (what was fetched in between was not kept).
        assertEquals(sha256(goodManifest), sha256(bytesOf(new File(cache, "itemdata-manifest.json"))));
        ItemDataStore later = new ItemDataStore(cache, mClient, "http://127.0.0.1:1/", time);
        assertNotNull(later.load("26.3"));
    }

    @Test
    public void aManifestThatCouldNotBeReadAtAllIsNotAskedAgainSoon() throws Exception {
        byte[] body = dataFile("26.3");
        Cdn cdn = cdn();
        Time time = new Time();
        ItemDataStore store = new ItemDataStore(cache(), mClient, cdn.base(), time);
        assertNull("nothing published yet", store.load("26.3"));
        assertEquals(1, cdn.count("manifest.json"));
        publish(cdn, "26.3", versions("26.3", body));
        time.advance(20 * 1000L);
        assertNull("not asked again within 30 seconds", store.load("26.3"));
        assertEquals(1, cdn.count("manifest.json"));
        time.advance(11 * 1000L);
        assertNotNull(store.load("26.3"));
        assertEquals(2, cdn.count("manifest.json"));
    }

    @Test
    public void refreshAsksTheCdnAgainAtOnce() throws Exception {
        byte[] body = dataFile("26.3");
        Cdn cdn = cdn();
        ItemDataStore store = store(cdn, cache());
        assertNull(store.load("26.3"));
        publish(cdn, "26.3", versions("26.3", body));
        assertNull("the failed manifest is not asked again at once", store.load("26.3"));
        store.refresh();
        assertNotNull(store.load("26.3"));
        assertEquals(2, cdn.count("manifest.json"));
    }

    @Test
    public void aNewerFileOutOfReachLeavesTheKeptOne() throws Exception {
        byte[] old = dataFile("26.3");
        byte[] newer = dataFile("26.3", true);
        Cdn cdn = cdn();
        File cache = cache();
        publish(cdn, "26.3", versions("26.3", old));
        ItemData first = store(cdn, cache).load("26.3");
        assertNotNull(first);
        assertEquals(13, first.items().size());
        // The manifest names a newer file the CDN cannot give now: the one kept here is read.
        cdn.put("manifest.json", manifestFor("26.3", versions("26.3", newer), null));
        ItemData later = store(cdn, cache).load("26.3");
        assertNotNull(later);
        assertEquals(13, later.items().size());
        assertEquals(Collections.singletonList("data-26.3-" + sha256(old).substring(0, 12) + ".json"), dataFiles(cache));
        // The same in one session, when the manifest changes under it.
        Time time = new Time();
        ItemDataStore session = new ItemDataStore(cache, mClient, cdn.base(), time);
        assertEquals(13, session.load("26.3").items().size());
        // Once it can be fetched, the newer file replaces the old one, here and on disk.
        cdn.put(pathOf("26.3", newer), newer);
        ItemData fresh = store(cdn, cache).load("26.3");
        assertNotNull(fresh);
        assertEquals(14, fresh.items().size());
        assertEquals(Collections.singletonList("data-26.3-" + sha256(newer).substring(0, 12) + ".json"), dataFiles(cache));
        // A session that holds the old file reads the new one at the next manifest, and drops the old.
        time.advance(6 * MINUTE);
        assertEquals(14, session.load("26.3").items().size());
        assertEquals(1, dataFiles(cache).size());
    }

    @Test
    public void aVersionNeverTakesAnotherVersionsFile() throws Exception {
        byte[] rc = dataFile("26.1-rc1");
        byte[] release = dataFile("26.1", true);
        Cdn cdn = cdn();
        File cache = cache();
        publish(cdn, "26.1", versions("26.1-rc1", rc, "26.1", release));
        ItemDataStore store = store(cdn, cache);
        ItemData candidate = store.load("26.1-rc1");
        ItemData released = store.load("26.1");
        assertNotNull(candidate);
        assertNotNull(released);
        assertEquals("26.1-rc1", candidate.minecraft());
        assertEquals("26.1", released.minecraft());
        assertEquals(13, candidate.items().size());
        assertEquals(14, released.items().size());
        // Writing 26.1's file did not drop 26.1-rc1's (the names are read from the end).
        List<String> kept = dataFiles(cache);
        assertEquals(2, kept.size());
        assertTrue(kept.contains("data-26.1-rc1-" + sha256(rc).substring(0, 12) + ".json"));
        assertTrue(kept.contains("data-26.1-" + sha256(release).substring(0, 12) + ".json"));
        // Without the manifest (its copy gone, the CDN gone) each version still gets its own file.
        String base = cdn.base();
        cdn.stop();
        assertTrue(new File(cache, "itemdata-manifest.json").delete());
        ItemDataStore offline = new ItemDataStore(cache, mClient, base);
        ItemData one = offline.load("26.1");
        ItemData two = offline.load("26.1-rc1");
        assertNotNull(one);
        assertNotNull(two);
        assertEquals("26.1", one.minecraft());
        assertEquals("26.1-rc1", two.minecraft());
        // With no version asked, the newest kept; one between, the newest older.
        ItemDataStore third = new ItemDataStore(cache, mClient, base);
        ItemData newest = third.load(null);
        assertNotNull(newest);
        assertEquals("26.1-rc1", newest.minecraft());
        ItemData none = new ItemDataStore(cache(), mClient, base).load("26.1");
        assertNull(none);
    }

    @Test
    public void refusesAFileOverFourMegabytesAndABodyLongerThanTheManifestSays() throws Exception {
        byte[] body = dataFile("26.3");
        String path = pathOf("26.3", body);
        // The manifest says the file is 5 MB: not even asked.
        Cdn cdn = cdn();
        File cache = cache();
        cdn.put(path, body);
        cdn.put("manifest.json", manifestFor("26.3", versions("26.3", body), 5L * 1024 * 1024));
        assertNull(store(cdn, cache).load("26.3"));
        assertEquals(0, cdn.count(path));
        // The manifest says 4 MB (the most) but the answer goes on past it, with no length to go by: cut, and nothing kept.
        Cdn long1 = cdn();
        byte[] huge = new byte[ItemData.MAX_BYTES + 100];
        Arrays.fill(huge, (byte) ' ');
        System.arraycopy(body, 0, huge, 0, body.length);
        long1.put(path, huge);
        long1.chunked.add(Cdn.PREFIX + path);
        long1.put("manifest.json", manifestFor("26.3", versions("26.3", body), (long) ItemData.MAX_BYTES));
        File cache1 = cache();
        assertNull(store(long1, cache1).load("26.3"));
        assertTrue(dataFiles(cache1).isEmpty());
        // The manifest says the true size; the answer is longer (and says so): refused as well.
        Cdn long2 = cdn();
        long2.put(path, Arrays.copyOf(huge, body.length + 5000));
        long2.put("manifest.json", manifestFor("26.3", versions("26.3", body), null));
        File cache2 = cache();
        assertNull(store(long2, cache2).load("26.3"));
        assertTrue(dataFiles(cache2).isEmpty());
        // Short of the claimed size is refused too.
        Cdn shorter = cdn();
        shorter.put(path, Arrays.copyOf(body, body.length - 1));
        shorter.put("manifest.json", manifestFor("26.3", versions("26.3", body), null));
        assertNull(store(shorter, cache()).load("26.3"));
    }

    @Test
    public void refusesAFileThatIsNotTheVersionsOwn() throws Exception {
        // Checked by the manifest (size, hash) but its mc says another version: not read.
        byte[] other = dataFile("26.2");
        Cdn cdn = cdn();
        File cache = cache();
        cdn.put(pathOf("26.3", other), other);
        cdn.put("manifest.json", manifestFor("26.3", versions("26.3", other), null));
        assertNull(store(cdn, cache).load("26.3"));
        assertTrue(dataFiles(cache).isEmpty());
        // Checked by the manifest but not a data file at all (valid JSON, wrong shape): not read, not kept.
        byte[] odd = "{\"format\":1,\"mc\":\"26.3\",\"items\":[],\"ench\":[],\"groups\":[]}".getBytes(StandardCharsets.UTF_8);
        Cdn cdn2 = cdn();
        File cache2 = cache();
        cdn2.put(pathOf("26.3", odd), odd);
        cdn2.put("manifest.json", manifestFor("26.3", versions("26.3", odd), null));
        assertNull(store(cdn2, cache2).load("26.3"));
        assertTrue(dataFiles(cache2).isEmpty());
    }

    @Test
    public void neverThrowsForNetworkTrouble() throws Exception {
        File cache = cache();
        // Nothing listens there.
        assertNull(new ItemDataStore(cache, mClient, "http://127.0.0.1:1/itemdata/v1/").load("26.3"));
        // Not a URL.
        assertNull(new ItemDataStore(cache, mClient, "not a url").load("26.3"));
        assertNull(new ItemDataStore(cache, mClient, "").load(null));
        // Errors, and rubbish.
        Cdn cdn = cdn();
        cdn.failWith = 500;
        assertNull(store(cdn, cache).load("26.3"));
        cdn.failWith = 0;
        cdn.put("manifest.json", "<html>".getBytes(StandardCharsets.UTF_8));
        assertNull(store(cdn, cache).load("26.3"));
        cdn.put("manifest.json", "{\"format\":1,\"versions\":{\"26.3\":{\"file\":1}}}".getBytes(StandardCharsets.UTF_8));
        assertNull(store(cdn, cache).load("26.3"));
        assertNull(store(cdn, cache).load("a b c"));
        // A cache folder that cannot be written to: the data still comes, just not kept.
        File blocked = mTmp.newFile("not-a-folder");
        byte[] body = dataFile("26.3");
        Cdn good = cdn();
        publish(good, "26.3", versions("26.3", body));
        ItemData loaded = new ItemDataStore(blocked, mClient, good.base()).load("26.3");
        assertNotNull(loaded);
        assertEquals(13, loaded.items().size());
    }

    @Test
    public void twoCallersAtOnceDownloadAFileOnce() throws Exception {
        byte[] body = dataFile("26.3");
        Cdn cdn = cdn();
        publish(cdn, "26.3", versions("26.3", body));
        final ItemDataStore store = store(cdn, cache());
        final ItemData[] results = new ItemData[4];
        final CountDownLatch go = new CountDownLatch(1);
        Thread[] threads = new Thread[results.length];
        for (int i = 0; i < threads.length; i++) {
            final int at = i;
            threads[i] = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        return;
                    }
                    results[at] = store.load("26.3");
                }
            });
            threads[i].start();
        }
        go.countDown();
        for (Thread thread : threads) thread.join(30000);
        assertNotNull(results[0]);
        for (ItemData result : results) assertSame(results[0], result);
        assertEquals(1, cdn.count(pathOf("26.3", body)));
        assertEquals(1, cdn.count("manifest.json"));
    }

    @Test
    public void leavesNoTemporaryFilesBehind() throws Exception {
        byte[] body = dataFile("26.3");
        Cdn cdn = cdn();
        File cache = cache();
        Time time = new Time();
        publish(cdn, "26.3", versions("26.3", body));
        ItemDataStore store = new ItemDataStore(cache, mClient, cdn.base(), time);
        assertNotNull(store.load("26.3"));
        time.advance(6 * MINUTE);
        assertNotNull(store.load("26.3"));
        Set<String> all = new HashSet<>(names(cache));
        all.addAll(names(new File(cache, "items")));
        for (String name : all) assertFalse(name, name.endsWith(".part"));
        assertTrue(all.contains("itemdata-manifest.json"));
        assertTrue(all.contains("items"));
    }
}
