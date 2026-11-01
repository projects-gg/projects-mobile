package gg.projects.mobile.social;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import gg.projects.mobile.social.SocialModel.Me;
import gg.projects.mobile.social.SocialModel.Member;
import gg.projects.mobile.social.SocialModel.Profile;

/**
 * BADGES (badges.md "Tel biçimi", "BADGES-UI için"): the catalogue as the service sends it and as the phone keeps it,
 * a card's badges (SOC-VERIFY's single {@code mark} from an older service), the live frames, what TalkBack says, and
 * "Rozetler"'s hidden list, a second switch before the first answer included.
 */
public class MarksTest {
    private static final String ME = "100000000000000021";
    private static final String B = "100000000000000022";
    private static final String C = "100000000000000023";
    private static final String GK = "0a1b2c3d4e5f6a7b";
    private static final long NOW = 1_792_521_000L;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    // ---- fixtures ----

    private static JsonObject def(String key, String tr, String en, String color, String glyph, String shape) {
        JsonObject o = new JsonObject();
        o.addProperty("key", key);
        JsonObject name = new JsonObject();
        name.addProperty("tr", tr);
        name.addProperty("en", en);
        o.add("name", name);
        o.addProperty("color", color);
        o.addProperty("glyph", glyph);
        o.addProperty("shape", shape);
        return o;
    }

    private static JsonObject catalog(long rev, JsonObject... defs) {
        JsonObject o = new JsonObject();
        o.addProperty("rev", rev);
        JsonArray list = new JsonArray();
        for (JsonObject d : defs) list.add(d);
        o.add("marks", list);
        return o;
    }

    /** The catalogue Studio's owner saved: the built-in badges and the AFK+ extra switched on. */
    private static MarkCatalog saved() {
        return MarkCatalog.read(catalog(3,
                def("admin", "Projects yöneticisi", "Projects admin", "red", "check", "seal"),
                def("moderator", "Projects moderatörü", "Projects moderator", "purple", "check", "seal"),
                def("staff", "Projects personeli", "Projects staff", "green", "check", "seal"),
                def("verified", "Doğrulanmış hesap", "Verified account", "blue", "check", "seal"),
                def("premium", "Premium Club üyesi", "Premium Club member", "orange", "star", "seal"),
                def("afkplus", "AFK+ kullanıcısı", "AFK+ user", "indigo", "cloud", "seal")));
    }

    private static JsonArray keys(String... keys) {
        JsonArray list = new JsonArray();
        for (String k : keys) list.add(new JsonPrimitive(k));
        return list;
    }

    private static JsonObject profile(String id, String name, String... marks) {
        JsonObject p = new JsonObject();
        p.addProperty("id", id);
        p.addProperty("username", name.toLowerCase());
        p.addProperty("displayName", name);
        p.addProperty("avatar", "discord");
        if (marks.length > 0) p.add("marks", keys(marks));
        return p;
    }

    private static JsonObject frame(String type, String id, String... marks) {
        JsonObject f = new JsonObject();
        f.addProperty("t", type);
        f.addProperty("id", id);
        f.add("marks", keys(marks));
        return f;
    }

    private static JsonObject hello(String... caps) {
        JsonObject h = new JsonObject();
        h.addProperty("t", "hello");
        h.addProperty("now", NOW);
        h.add("caps", keys(caps));
        return h;
    }

    /** The player (badges {@code mine}, hiding {@code hidden}), friend B and crewmate C, all three in one crew. */
    private static SocialState signedIn(String[] mine, String[] hidden, String... friendMarks) {
        SocialState state = new SocialState();
        JsonObject me = profile(ME, "KorAtesi", mine);
        if (hidden != null) me.add("marksHidden", keys(hidden));
        state.setMe(Me.read(me));
        JsonObject o = new JsonObject();
        JsonArray friends = new JsonArray();
        friends.add(profile(B, "Pixel_Ezgi", friendMarks));
        o.add("friends", friends);
        JsonObject team = new JsonObject();
        team.addProperty("id", GK);
        team.addProperty("name", "Gece Kartallari");
        team.addProperty("tag", "GK");
        team.addProperty("emblem", "bolt");
        team.addProperty("code", "ABCD1234");
        JsonArray members = new JsonArray();
        for (JsonObject m : new JsonObject[]{profile(C, "Can_Blok", "verified"), profile(B, "Pixel_Ezgi", friendMarks),
                profile(ME, "KorAtesi", mine)}) {
            m.addProperty("role", "member");
            m.addProperty("joinedAt", NOW - 1000);
            members.add(m);
        }
        team.add("members", members);
        JsonObject crew = new JsonObject();
        crew.add("team", team);
        JsonArray crews = new JsonArray();
        crews.add(crew);
        o.add("crews", crews);
        o.addProperty("crewLimit", 5);
        state.applyFriends(SocialModel.FriendsData.read(o));
        return state;
    }

    // ---- the catalogue ----

    @Test
    public void theCatalogueReadsAsTheServiceSendsIt() {
        MarkCatalog c = saved();
        assertNotNull(c);
        assertEquals(3, c.rev);
        assertEquals(6, c.defs.size());
        assertEquals("admin", c.defs.get(0).key);
        assertEquals("Projects yöneticisi", c.defs.get(0).name(true));
        assertEquals("Projects admin", c.defs.get(0).name(false));
        assertEquals(0xffe0434a, c.defs.get(0).argb());
        assertEquals(0xff6673e8, c.get("afkplus").argb());
        assertNull(c.get("cloud"));
        assertNull(c.get(null));
        // What the phone keeps reads back the same.
        MarkCatalog again = MarkCatalog.read(c.toJson());
        assertEquals(3, again.rev);
        assertEquals(6, again.defs.size());
        for (int i = 0; i < 6; i++) {
            assertEquals(c.defs.get(i).key, again.defs.get(i).key);
            assertEquals(c.defs.get(i).nameTr, again.defs.get(i).nameTr);
            assertEquals(c.defs.get(i).nameEn, again.defs.get(i).nameEn);
            assertEquals(c.defs.get(i).color, again.defs.get(i).color);
            assertEquals(c.defs.get(i).glyph, again.defs.get(i).glyph);
            assertEquals(c.defs.get(i).shape, again.defs.get(i).shape);
        }
    }

    @Test
    public void badgesThisVersionCannotDrawAreLeftOut() {
        MarkCatalog c = MarkCatalog.read(catalog(5,
                def("admin", "Projects yöneticisi", "Projects admin", "red", "check", "seal"),
                def("gold", "Altın", "Gold", "gold", "check", "seal"),
                def("rocket", "Roket", "Rocket", "blue", "rocket", "seal"),
                def("hex", "Altıgen", "Hexagon", "blue", "check", "hexagon"),
                def("Bad Key", "Kötü", "Bad", "blue", "check", "seal"),
                def("long", "abcdefghijabcdefghijabcdefghijabc", "Long", "blue", "check", "seal"),
                def("blank", "   ", "Blank", "blue", "check", "seal"),
                def("ctrl", "a\u0007b", "Control", "blue", "check", "seal"),
                def("admin", "İkinci", "Second", "blue", "star", "round"),
                def("m12", "Yaz turnuvası", "Summer cup", "pink", "trophy", "shield")));
        assertNotNull(c);
        List<String> kept = new ArrayList<>();
        for (MarkCatalog.Def d : c.defs) kept.add(d.key);
        assertEquals(Arrays.asList("admin", "m12"), kept);
        // The first of a key named twice stays.
        assertEquals("red", c.get("admin").color);
    }

    @Test
    public void aCatalogueHoldsAtMost31Badges() {
        JsonObject[] defs = new JsonObject[40];
        for (int i = 0; i < defs.length; i++) defs[i] = def("m" + (i + 1), "Rozet " + i, "Badge " + i, "slate", "star", "round");
        assertEquals(MarkCatalog.MAX_MARKS, MarkCatalog.read(catalog(9, defs)).defs.size());
    }

    @Test
    public void anythingElseIsNoCatalogue() {
        assertNull(MarkCatalog.read(null));
        assertNull(MarkCatalog.read(new JsonObject()));
        JsonObject noList = new JsonObject();
        noList.addProperty("rev", 2);
        assertNull(MarkCatalog.read(noList));
        JsonObject negative = catalog(1);
        negative.addProperty("rev", -1);
        assertNull(MarkCatalog.read(negative));
        JsonObject fraction = catalog(1);
        fraction.addProperty("rev", 1.5);
        assertNull(MarkCatalog.read(fraction));
        JsonObject words = catalog(1);
        words.addProperty("rev", "3");
        assertNull(MarkCatalog.read(words));
        // An empty catalogue is one: nothing shows.
        assertEquals(0, MarkCatalog.read(catalog(4)).defs.size());
    }

    @Test
    public void theBuiltInCatalogueIsTheServicesRevisionZero() {
        assertEquals(0, MarkCatalog.BUILTIN.rev);
        List<String> order = new ArrayList<>();
        for (MarkCatalog.Def d : MarkCatalog.BUILTIN.defs) order.add(d.key);
        // Authority first: the badge beside a name says who someone is (badges.md "Katalog").
        assertEquals(Arrays.asList("admin", "moderator", "staff", "verified", "premium"), order);
        for (MarkCatalog.Def d : MarkCatalog.BUILTIN.defs) {
            for (int grid : new int[]{12, 14, 16}) assertNotNull(d.key + " at " + grid, MarkGeometry.drawing(grid, d.shape, d.glyph));
        }
        // SOC-VERIFY's two colours stay as they were.
        assertEquals(0xff2a8af0, MarkCatalog.BUILTIN.get("verified").argb());
        assertEquals(0xffe0434a, MarkCatalog.BUILTIN.get("admin").argb());
    }

    // ---- cards ----

    @Test
    public void aCardsBadgesShowInTheCataloguesOrder() {
        Profile p = Profile.read(profile(B, "Pixel_Ezgi", "premium", "staff", "admin"));
        assertEquals(Arrays.asList("premium", "staff", "admin"), p.marks);
        MarkCatalog c = saved();
        List<String> shown = new ArrayList<>();
        for (MarkCatalog.Def d : c.shown(p.marks)) shown.add(d.key);
        assertEquals(Arrays.asList("admin", "staff", "premium"), shown);
        assertEquals("admin", c.first(p.marks).key);
        assertNull(c.first(Collections.<String>emptyList()));
        assertNull(c.first(null));
        // No badges: an empty list, never null, and no marks on a reply's key.
        Profile none = Profile.read(profile(C, "Can_Blok"));
        assertTrue(none.marks.isEmpty());
        assertNull(none.marksHidden);
        assertEquals("", none.marksKey());
        assertEquals("premium,staff,admin,", p.marksKey());
    }

    @Test
    public void anOlderServicesMarkReadsAsOneBadge() {
        JsonObject verified = profile(B, "Pixel_Ezgi");
        verified.addProperty("mark", "verified");
        assertEquals(Collections.singletonList("verified"), MarkCatalog.keysOf(verified));
        JsonObject admin = profile(B, "Pixel_Ezgi");
        admin.addProperty("mark", "admin");
        assertEquals(Collections.singletonList("admin"), MarkCatalog.keysOf(admin));
        JsonObject other = profile(B, "Pixel_Ezgi");
        other.addProperty("mark", "staff");
        assertTrue(MarkCatalog.keysOf(other).isEmpty());
        // A service with badges sends both: the list wins.
        JsonObject both = profile(B, "Pixel_Ezgi", "admin", "premium");
        both.addProperty("mark", "admin");
        assertEquals(Arrays.asList("admin", "premium"), MarkCatalog.keysOf(both));
        assertTrue(MarkCatalog.keysOf(null).isEmpty());
    }

    @Test
    public void keysAreReadWithCare() {
        JsonArray list = new JsonArray();
        list.add(new JsonPrimitive("admin"));
        list.add(new JsonPrimitive(7));
        list.add(new JsonPrimitive("Admin"));
        list.add(new JsonPrimitive("admin"));
        list.add(new JsonPrimitive("m12"));
        list.add(new JsonPrimitive("a b"));
        list.add(new JsonObject());
        assertEquals(Arrays.asList("admin", "m12"), MarkCatalog.readKeys(list));
        JsonArray many = new JsonArray();
        for (int i = 1; i <= 40; i++) many.add(new JsonPrimitive("m" + i));
        assertEquals(MarkCatalog.MAX_MARKS, MarkCatalog.readKeys(many).size());
        assertTrue(MarkCatalog.readKeys(new JsonPrimitive("admin")).isEmpty());
        assertTrue(MarkCatalog.isKey("verified"));
        assertTrue(MarkCatalog.isKey("m31"));
        assertFalse(MarkCatalog.isKey("ADMIN"));
        assertFalse(MarkCatalog.isKey(""));
        assertFalse(MarkCatalog.isKey("abcdefghijabcdefghijabcde"));
        assertFalse(MarkCatalog.isKey(null));
    }

    @Test
    public void unknownBadgesAreNotDrawnAndCanBeTold() {
        MarkCatalog c = saved();
        List<String> keys = Arrays.asList("m12", "admin");
        assertEquals(1, c.shown(keys).size());
        assertFalse(c.knowsAll(keys));
        assertTrue(c.knowsAll(Arrays.asList("admin", "afkplus")));
        assertFalse(MarkCatalog.BUILTIN.knowsAll(Collections.singletonList("afkplus")));
    }

    @Test
    public void talkBackNamesEveryBadgeAfterTheName() {
        MarkCatalog c = saved();
        List<MarkCatalog.Def> shown = c.shown(Arrays.asList("premium", "admin"));
        assertEquals("Pixel_Ezgi, Projects yöneticisi, Premium Club üyesi", MarkCatalog.spoken("Pixel_Ezgi", shown, true));
        assertEquals("Pixel_Ezgi, Projects admin, Premium Club member", MarkCatalog.spoken("Pixel_Ezgi", shown, false));
        assertEquals("Pixel_Ezgi", MarkCatalog.spoken("Pixel_Ezgi", Collections.<MarkCatalog.Def>emptyList(), true));
    }

    // ---- the copy kept on the phone ----

    @Test
    public void thePhoneKeepsTheCatalogueWithItsEtag() throws Exception {
        File file = new File(folder.getRoot(), "projects-social/marks.json");
        MarkStore store = new MarkStore(file);
        assertNull(store.load());
        assertTrue(store.save(saved(), "\"3\""));
        MarkStore.Kept kept = store.load();
        assertNotNull(kept);
        assertEquals(3, kept.catalog.rev);
        assertEquals(6, kept.catalog.defs.size());
        assertEquals("\"3\"", kept.etag);
        // An ETag that cannot go back as a header is not kept.
        assertTrue(store.save(saved(), "bad\netag"));
        assertNull(store.load().etag);
        assertNull(MarkStore.etag("x y"));
        assertNull(MarkStore.etag(""));
        assertEquals("W/\"3\"", MarkStore.etag("W/\"3\""));
        // Another format, a broken file or a file far too large: none (the built-in catalogue draws).
        Files.write(file.toPath(), "{\"v\":2,\"catalog\":{\"rev\":1,\"marks\":[]}}".getBytes(StandardCharsets.UTF_8));
        assertNull(store.load());
        Files.write(file.toPath(), "{\"v\":1,\"catalog\":".getBytes(StandardCharsets.UTF_8));
        assertNull(store.load());
        byte[] big = new byte[(int) MarkStore.MAX_FILE + 10];
        Arrays.fill(big, (byte) ' ');
        Files.write(file.toPath(), big);
        assertNull(store.load());
    }

    // ---- live frames ----

    @Test
    public void aMarksFrameChangesEveryCopyOfThatPerson() {
        SocialState state = signedIn(new String[0], new String[0], "verified");
        state.apply(hello("marks", "marks2"), NOW);
        assertEquals(Collections.singletonList("verified"), state.friend(B).profile.marks);
        state.apply(frame("marks", B, "staff", "verified"), NOW);
        assertEquals(Arrays.asList("staff", "verified"), state.friend(B).profile.marks);
        assertEquals(Arrays.asList("staff", "verified"), state.crew(GK).team.member(B).profile.marks);
        // The crewmate who is no friend changes in the crew.
        state.apply(frame("marks", C), NOW);
        assertTrue(state.crew(GK).team.member(C).profile.marks.isEmpty());
        // Every key the lists show (a newer catalogue may name one this version does not know).
        assertEquals(new HashSet<>(Arrays.asList("staff", "verified")), state.markKeys());
        // Someone else's card never carries a hidden list.
        assertNull(state.friend(B).profile.marksHidden);
    }

    @Test
    public void theOldSingleMarkIsTakenOnlyFromAnOlderService() {
        SocialState state = signedIn(new String[0], new String[0], "staff", "premium");
        state.apply(hello("marks", "marks2"), NOW);
        JsonObject old = new JsonObject();
        old.addProperty("t", "mark");
        old.addProperty("id", B);
        old.addProperty("mark", "admin");
        // With marks2 its one mark would take the other badges away.
        state.apply(old, NOW);
        assertEquals(Arrays.asList("staff", "premium"), state.friend(B).profile.marks);
        // A service before badges: SOC-VERIFY's frame says it all.
        state.apply(hello("marks"), NOW);
        state.apply(old, NOW);
        assertEquals(Collections.singletonList("admin"), state.friend(B).profile.marks);
        old.addProperty("mark", "none");
        state.apply(old, NOW);
        assertTrue(state.friend(B).profile.marks.isEmpty());
    }

    @Test
    public void thePlayersOwnFrameKeepsTheirHiddenBadgesRight() {
        SocialState state = signedIn(new String[]{"admin", "premium"}, new String[0]);
        state.apply(hello("marks", "marks2"), NOW);
        assertEquals(Arrays.asList("admin", "premium"), state.crew(GK).team.member(ME).profile.marks);
        // Premium went: hidden on another device (or taken); it reads as hidden and the profile is asked once.
        state.apply(frame("marks", ME, "admin"), NOW);
        assertEquals(Collections.singletonList("admin"), state.me().profile.marks);
        assertEquals(Collections.singletonList("premium"), state.me().profile.marksHidden);
        assertEquals(Collections.singletonList("admin"), state.crew(GK).team.member(ME).profile.marks);
        assertTrue(state.takeMyMarksUnsure());
        assertFalse(state.takeMyMarksUnsure());
        // Shown again elsewhere: no longer hidden, nothing to ask.
        state.apply(frame("marks", ME, "admin", "premium"), NOW);
        assertEquals(Arrays.asList("admin", "premium"), state.me().profile.marks);
        assertTrue(state.me().profile.marksHidden.isEmpty());
        assertFalse(state.takeMyMarksUnsure());
    }

    @Test
    public void hiddenBadgesFollowTheFrames() {
        List<String> before = Arrays.asList("admin", "premium");
        // One the player hid here went: it was said.
        assertEquals(Collections.singletonList("premium"),
                MarkCatalog.hiddenAfterFrame(before, Collections.singletonList("premium"), Collections.singletonList("admin")));
        assertFalse(MarkCatalog.wentUnsaid(before, Collections.singletonList("premium"), Collections.singletonList("admin")));
        // One that went unsaid reads as hidden until the profile says.
        assertEquals(Collections.singletonList("premium"),
                MarkCatalog.hiddenAfterFrame(before, Collections.<String>emptyList(), Collections.singletonList("admin")));
        assertTrue(MarkCatalog.wentUnsaid(before, Collections.<String>emptyList(), Collections.singletonList("admin")));
        // A service before badges hides nothing.
        assertNull(MarkCatalog.hiddenAfterFrame(before, null, Collections.singletonList("admin")));
        assertFalse(MarkCatalog.wentUnsaid(before, null, Collections.singletonList("admin")));
    }

    @Test
    public void marksFramesChangeWhatTheKeptListShows() {
        assertTrue(RelVersion.changesRelations("marks"));
        assertTrue(RelVersion.changesRelations("mark"));
        // The catalogue's revision is in the hello's summary: no list read for it.
        assertFalse(RelVersion.changesRelations("markdefs"));
    }

    // ---- "Rozetler" ----

    @Test
    public void rozetlerListsTheHeldBadgesThisVersionDraws() {
        MarkCatalog c = saved();
        List<MarkCatalog.Own> own = c.own(Arrays.asList("premium", "admin"), Arrays.asList("afkplus", "m30"));
        assertEquals(3, own.size());
        assertEquals("admin", own.get(0).def.key);
        assertTrue(own.get(0).shown);
        assertEquals("premium", own.get(1).def.key);
        assertTrue(own.get(1).shown);
        assertEquals("afkplus", own.get(2).def.key);
        assertFalse(own.get(2).shown);
        assertTrue(c.own(Collections.<String>emptyList(), Collections.<String>emptyList()).isEmpty());
        // A service before badges sends no hidden list: nothing to switch.
        Me before = Me.read(profile(ME, "KorAtesi", "admin"));
        assertNull(before.profile.marksHidden);
        assertNull(MarkCatalog.hiddenOf(profile(ME, "KorAtesi", "admin")));
    }

    @Test
    public void theWholeHiddenListGoesWithEverySwitchOnItsWay() {
        MarkCatalog c = saved();
        List<String> hidden = Arrays.asList("premium", "m30");
        List<MarkCatalog.Own> own = c.own(Collections.singletonList("admin"), hidden);
        Map<String, Boolean> pending = new LinkedHashMap<>();
        // Nothing turned: as the profile says, the unknown one kept.
        assertEquals(Arrays.asList("premium", "m30"), MarkCatalog.hiddenToSend(own, pending, hidden));
        // Admin turned off, then Premium on before the first answer: both go.
        pending.put("admin", false);
        assertEquals(Arrays.asList("admin", "premium", "m30"), MarkCatalog.hiddenToSend(own, pending, hidden));
        pending.put("premium", true);
        assertEquals(Arrays.asList("admin", "m30"), MarkCatalog.hiddenToSend(own, pending, hidden));
        JsonObject body = MarkCatalog.hiddenBody(MarkCatalog.hiddenToSend(own, pending, hidden));
        assertEquals("{\"marksHidden\":[\"admin\",\"m30\"]}", body.toString());
        assertEquals("{\"marksHidden\":[]}", MarkCatalog.hiddenBody(Collections.<String>emptyList()).toString());
    }

    @Test
    public void anAnswerThatAgreesSettlesItsSwitch() {
        MarkCatalog c = saved();
        Map<String, Boolean> pending = new HashMap<>();
        pending.put("admin", false);
        pending.put("premium", true);
        pending.put("gone", false);
        // The answer hid Admin; Premium's is still on its way; a badge no longer held is forgotten.
        List<MarkCatalog.Own> own = c.own(Collections.<String>emptyList(), Arrays.asList("admin", "premium"));
        MarkCatalog.settle(pending, own);
        assertEquals(Collections.singletonMap("premium", true), pending);
        MarkCatalog.settle(pending, c.own(Collections.singletonList("premium"), Collections.singletonList("admin")));
        assertTrue(pending.isEmpty());
    }

    // ---- copies made with other badges keep everything else ----

    /**
     * {@code Me.withMarks} (a frame about the player) must carry every other field: a field added to {@code Me} or
     * {@code Profile} later (a merge) and not passed on breaks this, as every field here holds a value of its own.
     */
    @Test
    public void meWithMarksKeepsEveryOtherField() throws Exception {
        JsonObject json = profile(ME, "KorAtesi", "admin");
        json.add("marksHidden", keys("premium"));
        Me before = Me.read(json);
        markEveryField(before, Collections.singleton("profile"));
        markEveryField(before.profile, new HashSet<>(Arrays.asList("marks", "marksHidden")));
        Me after = before.withMarks(Collections.singletonList("staff"), Collections.singletonList("admin"));
        assertEquals(Collections.singletonList("staff"), after.profile.marks);
        assertEquals(Collections.singletonList("admin"), after.profile.marksHidden);
        assertSameFields(before, after, Collections.singleton("profile"));
        assertSameFields(before.profile, after.profile, new HashSet<>(Arrays.asList("marks", "marksHidden")));
    }

    @Test
    public void aMembersCopyWithMarksKeepsEveryOtherField() throws Exception {
        SocialState state = signedIn(new String[0], new String[0], "verified");
        Member before = state.crew(GK).team.member(B);
        markEveryField(before, Collections.singleton("profile"));
        markEveryField(before.profile, new HashSet<>(Arrays.asList("marks", "marksHidden")));
        Member after = before.withMarks(Collections.singletonList("staff"));
        assertEquals(Collections.singletonList("staff"), after.profile.marks);
        assertSameFields(before, after, Collections.singleton("profile"));
        assertSameFields(before.profile, after.profile, new HashSet<>(Arrays.asList("marks", "marksHidden")));
    }

    /** Gives every instance field (but {@code skip}) a value no constructor would make up. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void markEveryField(Object target, Set<String> skip) throws Exception {
        for (Field f : target.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || skip.contains(f.getName())) continue;
            f.setAccessible(true);
            Class<?> type = f.getType();
            Object now = f.get(target);
            String mark = "field-" + f.getName();
            if (type == boolean.class) f.setBoolean(target, !f.getBoolean(target));
            else if (type == int.class) f.setInt(target, f.getInt(target) + 7);
            else if (type == long.class) f.setLong(target, f.getLong(target) + 7);
            else if (type == double.class) f.setDouble(target, f.getDouble(target) + 7);
            else if (type == String.class) f.set(target, mark);
            else if (type.isEnum()) {
                Object[] values = type.getEnumConstants();
                f.set(target, values[values.length - 1] == now ? values[0] : values[values.length - 1]);
            } else if (List.class.isAssignableFrom(type) || type == Collection.class) {
                f.set(target, new ArrayList(Collections.singletonList(mark)));
            } else if (Set.class.isAssignableFrom(type)) {
                f.set(target, new HashSet(Collections.singletonList(mark)));
            } else if (Map.class.isAssignableFrom(type)) {
                f.set(target, new HashMap(Collections.singletonMap(mark, mark)));
            } else if (now == null) {
                f.set(target, allocate(type));
            }
            // Any other value read from the fixture is already an object of its own.
        }
    }

    /** An instance of {@code type} made without a constructor (only its identity matters here). */
    private static Object allocate(Class<?> type) throws Exception {
        Field unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        unsafe.setAccessible(true);
        Object u = unsafe.get(null);
        return u.getClass().getMethod("allocateInstance", Class.class).invoke(u, type);
    }

    private static void assertSameFields(Object before, Object after, Set<String> skip) throws Exception {
        for (Field f : before.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || skip.contains(f.getName())) continue;
            f.setAccessible(true);
            assertEquals(before.getClass().getSimpleName() + "." + f.getName() + " is passed on", f.get(before), f.get(after));
        }
    }
}
