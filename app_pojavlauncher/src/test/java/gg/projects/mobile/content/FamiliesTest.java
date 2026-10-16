package gg.projects.mobile.content;

import static gg.projects.mobile.content.IndexVerifierTest.expect;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Pack families in the index and which pack the Projects profile follows (desktop index.rs and official.rs rules). */
public class FamiliesTest {
    private static final String DOC = "\"path\": \"config/x.json\", \"sha256\": \"" + ModelTest.repeat("0", 64) + "\", \"size\": 1";

    private static String pack(String id, String family, String minecraft, String latest, boolean isDefault) {
        return "{\"id\": \"" + id + "\", \"name\": \"Projects\", \"latest\": \"" + latest + "\","
                + " \"manifest\": {\"path\": \"packs/" + id + "/" + latest + ".json\", \"sha256\": \"" + ModelTest.repeat("1", 64) + "\", \"size\": 10}"
                + (family == null ? "" : ", \"family\": \"" + family + "\"")
                + (minecraft == null ? "" : ", \"minecraft\": \"" + minecraft + "\"")
                + (isDefault ? ", \"default\": true" : "") + "}";
    }

    private static RootIndex index(String... packs) throws ContentException {
        StringBuilder list = new StringBuilder();
        for (String pack : packs) {
            if (list.length() > 0) list.append(',');
            list.append(pack);
        }
        String json = "{\"schema\": 1, \"sequence\": 1, \"generatedAt\": \"2026-10-15T12:00:00Z\", \"config\": {" + DOC + "},"
                + " \"news\": {" + DOC + "}, \"packs\": [" + list + "]}";
        return RootIndex.parse(json.getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> ids(List<RootIndex.PackRef> packs) {
        List<String> out = new ArrayList<>();
        for (RootIndex.PackRef pack : packs) out.add(pack.id);
        return out;
    }

    @Test
    public void minecraftVersionOrder() {
        List<String> versions = new ArrayList<>(Arrays.asList(
                "1.21.10", "1.21.4", "26.1", "1.21.4-pre1", "1.21", "1.21.4-rc1", "1.20.6", "1.21.9", "unknown", "1.21.1"));
        Collections.sort(versions, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return MinecraftVersions.compare(a, b);
            }
        });
        assertEquals(Arrays.asList("unknown", "1.20.6", "1.21", "1.21.1", "1.21.4-pre1", "1.21.4-rc1", "1.21.4", "1.21.9", "1.21.10", "26.1"), versions);
        assertEquals(0, MinecraftVersions.compare("1.21", "1.21.0"));
    }

    @Test
    public void semverPrecedence() {
        assertTrue(Semver.compare("1.10.0", "1.9.0") > 0);
        assertTrue(Semver.compare("2.0.0", "2.0.0-rc.1") > 0);
        assertTrue(Semver.compare("2.0.0-rc.2", "2.0.0-rc.10") < 0);
        assertTrue(Semver.compare("2.0.0-alpha", "2.0.0-alpha.1") < 0);
        assertTrue(Semver.compare("2.0.0-1", "2.0.0-alpha") < 0);
        assertEquals(0, Semver.compare("2.0.0+build.1", "2.0.0"));
        assertEquals("not semver: keep the order", 0, Semver.compare("v2", "1.0.0"));
        assertEquals(0, Semver.compare("01.0.0", "1.0.0"));
    }

    @Test
    public void familiesAreParsed() throws Exception {
        RootIndex index = index(
                pack("projects", null, "1.21.1", "2.3.0", true),
                pack("projects-1.21.4", "projects", "1.21.4", "1.0.0", false));
        RootIndex.PackRef first = index.pack("projects");
        assertEquals("projects", first.familyId());
        assertTrue(first.isDefault);
        assertEquals("1.21.1", first.minecraft());
        RootIndex.PackRef member = index.pack("projects-1.21.4");
        assertEquals("projects", member.familyId());
        assertFalse(member.isDefault);
        assertEquals(Collections.singletonList("projects"), index.families());
        // Old indexes without the fields keep their meaning.
        RootIndex old = index("{\"id\": \"projects\", \"name\": \"Projects\", \"latest\": \"2.3.0\","
                + " \"manifest\": {\"path\": \"packs/projects/2.3.0.json\", \"sha256\": \"" + ModelTest.repeat("1", 64) + "\", \"size\": 10}}");
        assertEquals("projects", old.pack("projects").familyId());
        assertNull(old.pack("projects").minecraft());
        assertEquals("projects", old.familyDefault("projects").id);
    }

    @Test
    public void familySelection() throws Exception {
        // "projects" stands alone in its own family name; the other members name it.
        String[] packs = {
                pack("projects", null, "1.21.1", "2.3.0", false),
                pack("projects-1.21.4", "projects", "1.21.4", "1.0.0", false),
                pack("projects-1.20.6", "projects", "1.20.6", "3.0.0", false),
                pack("lobby", null, "1.21.4", "1.0.0", false)};
        RootIndex plain = index(packs);
        assertEquals(Arrays.asList("projects", "lobby"), plain.families());
        assertEquals("newest Minecraft first", Arrays.asList("projects-1.21.4", "projects", "projects-1.20.6"), ids(plain.familyMembers("projects")));
        // Without a default, new installs get the newest member.
        assertEquals("projects-1.21.4", plain.familyDefault("projects").id);
        assertEquals("projects-1.20.6", plain.familyMember("projects", "1.20.6").id);
        assertNull(plain.familyMember("projects", "1.19.2"));
        assertNull(plain.familyMember("lobby", "1.21.1"));

        // An explicit default wins over the newest.
        packs[0] = pack("projects", null, "1.21.1", "2.3.0", true);
        RootIndex withDefault = index(packs);
        assertEquals("projects", withDefault.familyDefault("projects").id);

        // Members without a Minecraft version (older Studio) sort last; latest breaks ties.
        RootIndex legacy = index(pack("a", "f", null, "1.0.0", false), pack("b", "f", null, "1.10.0", false),
                pack("c", "f", "1.21.1", "0.1.0", false));
        assertEquals(Arrays.asList("c", "b", "a"), ids(legacy.familyMembers("f")));
    }

    @Test
    public void familyRulesAreValidated() {
        expect(ContentException.Kind.INVALID, () -> index(pack("projects", null, "1.21.1", "2.3.0", true),
                pack("projects-1.21.4", "projects", "1.21.4", "1.0.0", true)));
        expect(ContentException.Kind.INVALID, () -> index(pack("projects", null, "1.21.1", "2.3.0", false),
                pack("projects-b", "projects", "1.21.1", "1.0.0", false)));
        expect(ContentException.Kind.INVALID, () -> index(pack("projects", "../x", "1.21.1", "2.3.0", false)));
        expect(ContentException.Kind.INVALID, () -> index(pack("projects", "", "1.21.1", "2.3.0", false)));
    }

    @Test
    public void defaultsInDifferentFamiliesAreFine() throws Exception {
        index(pack("projects", null, "1.21.1", "2.3.0", true), pack("lobby", null, "1.21.1", "1.0.0", true));
    }

    @Test
    public void recommendedPackIsAPackId() throws Exception {
        RootIndex index = index(
                pack("projects-1.20.6", "projects", "1.20.6", "3.0.0", false),
                pack("projects-1.21.1", "projects", "1.21.1", "2.3.0", true),
                pack("projects-1.21.4", "projects", "1.21.4", "1.0.0", false),
                pack("lobby", null, "1.21.4", "1.0.0", false));
        assertEquals("the named pack", "projects-1.21.4", index.newInstallPack("projects-1.21.4").id);
        assertEquals("lobby", index.newInstallPack("lobby").id);
        // A family id is not a pack id: the first pack's family default is used instead.
        assertEquals("projects-1.21.1", index.newInstallPack("projects").id);
        assertEquals("projects-1.21.1", index.newInstallPack("missing").id);
        assertEquals("projects-1.21.1", index.newInstallPack(null).id);
    }

    private static PackState installed(String packId, String family, String minecraft) {
        PackState state = new PackState();
        state.packId = packId;
        state.family = family;
        state.minecraft = minecraft;
        return state;
    }

    @Test
    public void theProfileFollowsItsFamilyAtItsVersion() throws Exception {
        RootIndex index = index(
                pack("projects", null, "1.21.1", "2.3.0", true),
                pack("projects-1.21.4", "projects", "1.21.4", "1.0.0", false));

        PackChoice none = PackChoice.choose(index("{\"id\": \"x\", \"name\": \"X\", \"latest\": \"1.0.0\","
                + " \"manifest\": {\"path\": \"packs/x/1.0.0.json\", \"sha256\": \"" + ModelTest.repeat("1", 64) + "\", \"size\": 10}}"), null, null);
        assertEquals(PackChoice.Kind.NEW_INSTALL, none.kind);
        assertEquals(PackChoice.Kind.NONE, PackChoice.choose(index(), "projects", null).kind);
        assertEquals(PackChoice.Kind.NONE, PackChoice.choose(null, "projects", null).kind);

        // A new install takes the recommended pack.
        PackChoice fresh = PackChoice.choose(index, "projects", null);
        assertEquals(PackChoice.Kind.NEW_INSTALL, fresh.kind);
        assertEquals("projects", fresh.pack.id);

        // An installed 1.21.4 set stays on 1.21.4 although the default is 1.21.1.
        PackChoice member = PackChoice.choose(index, "projects", installed("projects-1.21.4", "projects", "1.21.4"));
        assertEquals(PackChoice.Kind.MEMBER, member.kind);
        assertEquals("projects-1.21.4", member.pack.id);

        // Studio split the family: the 1.21.1 set moves to the new pack id of its version.
        RootIndex split = index(
                pack("projects-1.21.1", "projects", "1.21.1", "1.0.0", true),
                pack("projects-1.21.4", "projects", "1.21.4", "1.0.0", false));
        PackChoice moved = PackChoice.choose(split, "projects-1.21.1", installed("projects", "projects", "1.21.1"));
        assertEquals(PackChoice.Kind.MEMBER, moved.kind);
        assertEquals("projects-1.21.1", moved.pack.id);

        // The same pack id moved to another Minecraft version in place: an ordinary update.
        RootIndex inPlace = index(pack("projects", null, "1.21.4", "3.0.0", true));
        PackChoice update = PackChoice.choose(inPlace, "projects", installed("projects", "projects", "1.21.1"));
        assertEquals(PackChoice.Kind.IN_PLACE, update.kind);
        assertEquals("projects", update.pack.id);

        // A state from before families (no family, no Minecraft version) follows its pack id.
        PackChoice old = PackChoice.choose(index, "projects", installed("projects", "", ""));
        assertEquals(PackChoice.Kind.IN_PLACE, old.kind);

        // The family no longer offers the version: nothing syncs and the version stays.
        RootIndex dropped = index(pack("projects-1.21.4", "projects", "1.21.4", "1.0.0", true));
        PackChoice gone = PackChoice.choose(dropped, "projects-1.21.4", installed("projects-1.20.6", "projects", "1.20.6"));
        assertEquals(PackChoice.Kind.DROPPED, gone.kind);
        assertFalse(gone.syncs());
        assertNull(gone.pack);
        assertEquals("1.21.4", gone.recommended.minecraft());
        assertEquals("1.20.6", gone.installedMinecraft);

        // A state without a pack id (never synced) is a new install that keeps the state's choices.
        assertEquals(PackChoice.Kind.NEW_INSTALL, PackChoice.choose(index, "projects", installed("", "", "")).kind);
    }
}
