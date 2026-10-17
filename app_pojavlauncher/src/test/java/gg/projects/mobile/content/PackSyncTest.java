package gg.projects.mobile.content;

import static gg.projects.mobile.content.IndexVerifierTest.expect;
import static gg.projects.mobile.content.TestPacks.configPolicy;
import static gg.projects.mobile.content.TestPacks.defaults;
import static gg.projects.mobile.content.TestPacks.deleted;
import static gg.projects.mobile.content.TestPacks.file;
import static gg.projects.mobile.content.TestPacks.group;
import static gg.projects.mobile.content.TestPacks.installed;
import static gg.projects.mobile.content.TestPacks.item;
import static gg.projects.mobile.content.TestPacks.kept;
import static gg.projects.mobile.content.TestPacks.list;
import static gg.projects.mobile.content.TestPacks.paths;
import static gg.projects.mobile.content.TestPacks.read;
import static gg.projects.mobile.content.TestPacks.rename;
import static gg.projects.mobile.content.TestPacks.select;
import static gg.projects.mobile.content.TestPacks.serverOnly;
import static gg.projects.mobile.content.TestPacks.sha1;
import static gg.projects.mobile.content.TestPacks.sha512;
import static gg.projects.mobile.content.TestPacks.state;
import static gg.projects.mobile.content.TestPacks.write;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Pack manifests, the sync planner and applier (mirror of the desktop's sync.rs tests, against
 * real temporary folders) and the verified downloader.
 */
public class PackSyncTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static PackSyncPlanner.Plan plan(PackManifest manifest, File game, PackState previous, PackSelection selection,
                                             PackSyncPlanner.Options options) throws ContentException {
        return PackSyncPlanner.plan(manifest, PackSyncPlanner.directoryProbe(game), previous, selection, options);
    }

    private static PackSyncPlanner.Plan plan(PackManifest manifest, File game, PackState previous) throws ContentException {
        return plan(manifest, game, previous, null, defaults());
    }

    private static PackSyncPlanner.Plan plan(PackManifest manifest, File game, PackState previous, PackSelection selection) throws ContentException {
        return plan(manifest, game, previous, selection, defaults());
    }

    private static PackSyncApplier.Report apply(File game, PackSyncPlanner.Plan plan, TestPacks.FakeTransport transport) throws IOException {
        return PackSyncApplier.apply(game, plan, transport, null, 2);
    }

    // ---- manifests ----

    private static String fileJson(String path, String content, String extra) {
        return "{\"path\": \"" + path + "\", \"hashes\": {\"sha1\": \"" + sha1(content) + "\", \"sha512\": \"" + sha512(content) + "\"},"
                + " \"downloads\": [\"https://cdn.test/" + path + "\"], \"fileSize\": " + content.length() + extra + "}";
    }

    private static PackManifest manifestJson(String files, String projects) throws ContentException {
        String json = "{\"formatVersion\": 1, \"game\": \"minecraft\", \"versionId\": \"2.0.0\", \"name\": \"Projects\","
                + " \"dependencies\": {\"minecraft\": \"1.21.1\", \"fabric-loader\": \"0.16.9\"}, \"files\": [" + files + "],"
                + " \"projects\": " + projects + "}";
        return PackManifest.parse(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void fixtureManifestParses() throws Exception {
        PackManifest manifest = PackManifest.parse(Fixtures.bytes("v1/" + Fixtures.PACK_PATH));
        assertEquals("2.3.0", manifest.versionId);
        assertEquals("1.21.1", manifest.minecraftVersion());
        assertEquals("0.16.9", manifest.dependencies.get("fabric-loader"));
        assertEquals("play.projects.gg", manifest.projects.servers.get(0).address);
        assertEquals("required", manifest.files.get(0).clientSupport());
        assertTrue("a manifest from before items has none", manifest.projects.items.isEmpty());
        assertEquals("projects", manifest.projects.familyId());
    }

    @Test
    public void manifestValidation() {
        String a = fileJson("mods/a.jar", "a", "");
        expect(ContentException.Kind.INVALID, () -> manifestJson(a + "," + fileJson("MODS/A.jar", "b", ""), "{}"));
        expect(ContentException.Kind.INVALID, () -> manifestJson(fileJson("mods", "x", "") + "," + fileJson("mods/b.jar", "b", ""), "{}"));
        expect(ContentException.Kind.INVALID, () -> manifestJson(fileJson("../evil.jar", "x", ""), "{}"));
        expect(ContentException.Kind.INVALID, () -> manifestJson(fileJson(".projects/pack-state.json", "x", ""), "{}"));
        expect(ContentException.Kind.INVALID, () -> manifestJson(a.replace("https://cdn.test", "ftp://cdn.test"), "{}"));
        expect(ContentException.Kind.INVALID, () -> manifestJson(a.replace(sha1("a"), "xyz"), "{}"));
        expect(ContentException.Kind.INVALID, () -> manifestJson(a, "{\"optionalGroups\": [{\"id\": \"g\"}, {\"id\": \"g\"}]}"));
        expect(ContentException.Kind.INVALID, () -> manifestJson(a, "{\"family\": \"bad/family\"}"));
        expect(ContentException.Kind.UNSUPPORTED_SCHEMA, () -> PackManifest.parse(
                "{\"formatVersion\": 2, \"versionId\": \"1\", \"name\": \"x\", \"dependencies\": {\"minecraft\": \"1.21.1\"}}".getBytes(StandardCharsets.UTF_8)));
        expect(ContentException.Kind.INVALID, () -> PackManifest.parse(
                "{\"formatVersion\": 1, \"game\": \"terraria\", \"versionId\": \"1\", \"name\": \"x\", \"dependencies\": {\"minecraft\": \"1\"}}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void itemsAreParsedWithTheirDefaults() throws Exception {
        PackManifest manifest = manifestJson(
                fileJson("mods/fabric-api.jar", "fabric", "") + "," + fileJson("mods/sodium.jar", "sodium", "") + ","
                        + fileJson("mods/zoom.jar", "zoom", ""),
                "{\"packId\": \"projects-1.21.4\", \"family\": \"projects\", \"futureField\": {\"x\": 1}, \"items\": ["
                        + "{\"id\": \"P7dR8mSH\", \"path\": \"mods/fabric-api.jar\", \"title\": \"Fabric API\", \"category\": \"library\", \"library\": true},"
                        + "{\"id\": \"AANobbMI\", \"path\": \"mods/sodium.jar\", \"title\": \"Sodium\", \"summary\": \"Daha yüksek FPS\","
                        + " \"category\": \"performance\", \"iconUrl\": \"https://cdn.modrinth.com/data/AANobbMI/icon.png\","
                        + " \"modrinth\": {\"projectId\": \"AANobbMI\", \"versionId\": \"u1OEbNKx\"}, \"requires\": [\"P7dR8mSH\"], \"shiny\": true},"
                        + "{\"id\": \"zoom\", \"path\": \"mods/zoom.jar\", \"title\": \"Zoom\", \"category\": \"telepathy\", \"defaultEnabled\": false}]}");
        assertEquals("projects", manifest.projects.familyId());
        PackManifest.PackItem fabric = manifest.projects.item("P7dR8mSH");
        assertTrue(fabric.library);
        assertTrue("items default to enabled", fabric.defaultEnabled);
        PackManifest.PackItem sodium = manifest.projects.itemForPath("mods/sodium.jar");
        assertEquals("AANobbMI", sodium.id);
        assertEquals("u1OEbNKx", sodium.modrinth.versionId);
        assertEquals(Collections.singletonList("P7dR8mSH"), sodium.requires);
        assertEquals("performance", sodium.categoryOrDefault());
        assertEquals("sodium.jar", sodium.fileName());
        PackManifest.PackItem zoom = manifest.projects.item("zoom");
        assertFalse(zoom.defaultEnabled);
        assertEquals("unknown categories show as other", "other", zoom.categoryOrDefault());
        assertTrue(zoom.requires.isEmpty());
        assertNull("no pageUrl, no link", zoom.pageUrl);
        assertNull(zoom.page());
    }

    @Test
    public void itemPagesAreHttpsOnly() throws Exception {
        String files = fileJson("mods/sodium.jar", "sodium", "") + "," + fileJson("mods/zoom.jar", "zoom", "");
        PackManifest manifest = manifestJson(files, "{\"items\": ["
                + "{\"id\": \"AANobbMI\", \"path\": \"mods/sodium.jar\", \"title\": \"Sodium\", \"pageUrl\": \"https://modrinth.com/project/AANobbMI\"},"
                + "{\"id\": \"wi-zoom\", \"path\": \"mods/zoom.jar\", \"title\": \"WI Zoom\", \"pageUrl\": \"HTTPS://www.wimods.net/wi-zoom/\"}]}");
        PackManifest.PackItem sodium = manifest.projects.item("AANobbMI");
        assertEquals("https://modrinth.com/project/AANobbMI", sodium.pageUrl);
        assertEquals("https://modrinth.com/project/AANobbMI", sodium.page());
        // The browser gets the scheme in lower case (intent filters match schemes case-sensitively).
        assertEquals("https://www.wimods.net/wi-zoom/", manifest.projects.item("wi-zoom").page());

        // Like the desktop (pl-content's InsecurePage), a manifest with any other page is refused.
        for (String page : new String[]{"http://modrinth.com/project/x", "javascript:alert(1)", "projects://mods",
                "intent://x#Intent;end", "file:///sdcard/x.html", "modrinth.com/project/x", "https://", ""}) {
            expect(ContentException.Kind.INVALID, () -> manifestJson(files, "{\"items\": [{\"id\": \"a\", \"path\": \"mods/sodium.jar\","
                    + " \"title\": \"A\", \"pageUrl\": \"" + page + "\"}]}"));
        }

        // Items that never went through validation still open nothing but https pages.
        PackManifest.PackItem unchecked = new PackManifest.PackItem();
        assertNull(unchecked.page());
        unchecked.pageUrl = "http://modrinth.com/project/x";
        assertNull(unchecked.page());
        unchecked.pageUrl = "javascript:alert(1)";
        assertNull(unchecked.page());
        unchecked.pageUrl = "https://modrinth.com/project/x";
        assertEquals("https://modrinth.com/project/x", unchecked.page());
    }

    @Test
    public void itemRulesAreEnforced() {
        String files = fileJson("mods/a.jar", "a", "") + "," + fileJson("mods/b.jar", "b", "") + ","
                + fileJson("mods/server.jar", "s", ", \"env\": {\"client\": \"unsupported\", \"server\": \"required\"}");
        String a = "{\"id\": \"a\", \"path\": \"mods/a.jar\", \"title\": \"A\"}";
        expect(ContentException.Kind.INVALID, () -> manifestJson(files, "{\"items\": [" + a + "," + a.replace("mods/a.jar", "mods/b.jar") + "]}"));
        expect(ContentException.Kind.INVALID, () -> manifestJson(files, "{\"items\": [" + a.replace("\"a\"", "\"bad id\"") + "]}"));
        expect(ContentException.Kind.INVALID, () -> manifestJson(files, "{\"items\": [" + a.replace("mods/a.jar", "mods/missing.jar") + "]}"));
        expect(ContentException.Kind.INVALID, () -> manifestJson(files, "{\"items\": [" + a + "," + a.replace("\"a\"", "\"b\"") + "]}"));
        expect(ContentException.Kind.INVALID, () -> manifestJson(files, "{\"items\": [" + a.replace("mods/a.jar", "mods/server.jar") + "]}"));
        expect(ContentException.Kind.INVALID, () -> manifestJson(files, "{\"items\": [" + a.replace("\"A\"", "\"  \"") + "]}"));
        expect(ContentException.Kind.INVALID, () -> manifestJson(files, "{\"items\": [{\"id\": \"a\", \"path\": \"mods/a.jar\", \"title\": \"A\", \"iconUrl\": \"http://x.test/i.png\"}]}"));
        expect(ContentException.Kind.INVALID, () -> manifestJson(files, "{\"items\": [{\"id\": \"a\", \"path\": \"mods/a.jar\", \"title\": \"A\", \"requires\": [\"ghost\"]}]}"));
        expect(ContentException.Kind.INVALID, () -> manifestJson(files, "{\"items\": ["
                + "{\"id\": \"a\", \"path\": \"mods/a.jar\", \"title\": \"A\", \"requires\": [\"b\"]},"
                + "{\"id\": \"b\", \"path\": \"mods/b.jar\", \"title\": \"B\", \"requires\": [\"a\"]}]}"));
    }

    @Test
    public void configPolicyRules() throws Exception {
        PackManifest manifest = manifestJson(fileJson("options.txt", "o", ""),
                "{\"configPolicy\": {\"default\": \"overwrite\", \"rules\": [{\"glob\": \"options.txt\", \"policy\": \"ifMissing\"},"
                        + " {\"glob\": \"config/**\", \"policy\": \"preserveUserChanges\"}, {\"glob\": \"weird/**\", \"policy\": \"sometimes\"}]}}");
        assertEquals(PackManifest.POLICY_IF_MISSING, manifest.policyFor("options.txt"));
        assertEquals(PackManifest.POLICY_PRESERVE, manifest.policyFor("config/a/b.toml"));
        assertEquals(PackManifest.POLICY_PRESERVE, manifest.policyFor("weird/x"));
        assertEquals(PackManifest.POLICY_OVERWRITE, manifest.policyFor("mods/x.jar"));
    }

    // ---- sync without items ----

    @Test
    public void freshInstallDownloadsEverything() throws Exception {
        File game = tmp.newFolder("game");
        PackManifest m = TestPacks.manifest(list(file("mods/a.jar", "a"), file("config/a.json", "{}")), configPolicy());
        PackSyncPlanner.Plan plan = plan(m, game, null);
        assertEquals(Arrays.asList("mods/a.jar", "config/a.json"), paths(plan));
        assertTrue(plan.deletes.isEmpty() && plan.unchanged.isEmpty());
        assertEquals(2, plan.nextState.files.size());
        assertEquals(PackManifest.POLICY_PRESERVE, plan.nextState.files.get("config/a.json").policy);
        assertEquals("1 mod eklenecek, 1 yapılandırma dosyası eklenecek", plan.summary().describeTr());
        assertTrue(plan.adopted.isEmpty() && !plan.reset && plan.issues.isEmpty());
        assertTrue(plan.stateChanged);
    }

    @Test
    public void updateRemoveAndUserFiles() throws Exception {
        File game = tmp.newFolder("game");
        write(game, "mods/keep.jar", "same");
        write(game, "mods/update.jar", "v1");
        write(game, "mods/removed.jar", "old");
        write(game, "mods/removed-but-edited.jar", "edited");
        write(game, "mods/user-added.jar", "mine");
        PackState previous = state(
                "mods/keep.jar", "same", "overwrite",
                "mods/update.jar", "v1", "overwrite",
                "mods/removed.jar", "old", "overwrite",
                "mods/removed-but-edited.jar", "original", "overwrite",
                "mods/already-gone.jar", "x", "overwrite");
        PackManifest m = TestPacks.manifest(list(file("mods/keep.jar", "same"), file("mods/update.jar", "v2"), file("mods/new.jar", "new")), TestPacks.ext());
        PackSyncPlanner.Plan plan = plan(m, game, previous);
        assertEquals(Arrays.asList("mods/update.jar (update)", "mods/new.jar"), paths(plan));
        assertEquals(Collections.singletonList("mods/keep.jar"), plan.unchanged);
        assertEquals(Collections.singletonList("mods/removed.jar"), deleted(plan));
        assertEquals(3, plan.deletes.get(0).size);
        assertEquals(Collections.singletonList("mods/removed-but-edited.jar USER_MODIFIED"), kept(plan));
        assertFalse(plan.nextState.files.containsKey("mods/removed.jar"));
        assertFalse(plan.nextState.files.containsKey("mods/user-added.jar"));
        assertEquals("1 mod eklenecek, 1 mod güncellenecek, 1 mod kaldırılacak, 1 değiştirilmiş dosya korunacak", plan.summary().describeTr());
        assertEquals(3, plan.summary().removeBytes);
        assertFalse("one missing file is not a wiped folder", plan.reset);

        // A manifest without items keeps the state free of item bookkeeping.
        assertTrue(plan.items.isEmpty());
        JsonObject json = JsonParser.parseString(plan.nextState.toJson()).getAsJsonObject();
        assertFalse(json.has("items") || json.has("seenItems") || json.has("manual"));
        assertEquals("projects", json.get("family").getAsString());
        assertEquals("1.21.1", json.get("minecraft").getAsString());
        JsonObject keep = json.getAsJsonObject("files").getAsJsonObject("mods/keep.jar");
        assertFalse(keep.has("item") || keep.has("disabled") || keep.has("modified"));
    }

    @Test
    public void configPolicies() throws Exception {
        File game = tmp.newFolder("game");
        write(game, "options.txt", "user options");
        write(game, "config/edited.json", "user edit");
        write(game, "config/untouched.json", "v1");
        write(game, "config/foreign.json", "theirs");
        write(game, "config/forced.toml", "tweaked");
        write(game, "mods/replaced.jar", "player build");
        PackManifest.Projects ext = configPolicy();
        ext.configPolicy.rules.add(0, TestPacks.rule("config/forced.toml", PackManifest.POLICY_OVERWRITE));
        PackState previous = state(
                "config/edited.json", "v1", "preserveUserChanges",
                "config/untouched.json", "v1", "preserveUserChanges",
                "config/forced.toml", "pack", "overwrite",
                "mods/replaced.jar", "good", "overwrite");
        PackManifest m = TestPacks.manifest(list(
                file("options.txt", "pack options"),
                file("config/edited.json", "v2"),
                file("config/untouched.json", "v2"),
                file("config/foreign.json", "pack"),
                file("config/missing.json", "pack"),
                file("config/forced.toml", "pack"),
                file("mods/replaced.jar", "good")), ext);
        PackSyncPlanner.Plan plan = plan(m, game, previous);
        assertEquals("overwrite configs follow the pack; a jar the player replaced does not",
                Arrays.asList("config/untouched.json (update)", "config/missing.json", "config/forced.toml (update)"), paths(plan));
        assertEquals(Arrays.asList("options.txt EXISTING_FILE", "config/edited.json USER_MODIFIED",
                "config/foreign.json USER_MODIFIED", "mods/replaced.jar USER_MODIFIED"), kept(plan));
        // Changed files keep the hash we installed, so they stay "modified" next time.
        assertEquals(sha1("v1"), plan.nextState.files.get("config/edited.json").sha1);
        PackState.InstalledFile replaced = plan.nextState.files.get("mods/replaced.jar");
        assertTrue(replaced.modified && replaced.sha1.equals(sha1("good")));
        assertFalse(plan.nextState.files.containsKey("config/foreign.json"));
        assertFalse(plan.nextState.files.containsKey("options.txt"));

        // Repair overrides the policies.
        PackSyncPlanner.Options repair = new PackSyncPlanner.Options();
        repair.repair = true;
        PackSyncPlanner.Plan repaired = plan(m, game, previous, null, repair);
        assertEquals(7, repaired.downloads.size());
        assertTrue(repaired.kept.isEmpty());
    }

    @Test
    public void optionalGroupsAndEnvironments() throws Exception {
        File game = tmp.newFolder("game");
        write(game, "shaderpacks/shaders.zip", "shaders");
        PackState previous = state("shaderpacks/shaders.zip", "shaders", "overwrite");
        PackManifest.Projects ext = TestPacks.ext();
        ext.optionalGroups = list(group("shaders", true, "shaderpacks/shaders.zip"), group("minimap", false, "mods/minimap.jar"));
        PackManifest m = TestPacks.manifest(list(file("shaderpacks/shaders.zip", "shaders"), file("mods/minimap.jar", "map"),
                serverOnly("mods/server.jar", "srv")), ext);

        // Defaults: shaders on (already installed), minimap off, server mod skipped.
        PackSyncPlanner.Plan plan = plan(m, game, previous);
        assertTrue(plan.downloads.isEmpty() && plan.deletes.isEmpty());
        assertEquals(Collections.singletonList("shaderpacks/shaders.zip"), plan.unchanged);
        assertEquals(Boolean.FALSE, plan.nextState.groups.get("minimap"));

        // The player turns shaders off and the minimap on.
        PackSelection selection = new PackSelection();
        selection.groups.put("shaders", false);
        selection.groups.put("minimap", true);
        plan = plan(m, game, previous, selection);
        assertEquals(Collections.singletonList("mods/minimap.jar"), paths(plan));
        assertEquals(Collections.singletonList("shaderpacks/shaders.zip"), deleted(plan));
        assertEquals(1, plan.summary().removed.shaderPacks);

        // Without an explicit selection the previous choice is remembered.
        plan = plan(m, game, plan.nextState);
        assertEquals(Boolean.TRUE, plan.nextState.groups.get("minimap"));
        assertEquals(Boolean.FALSE, plan.nextState.groups.get("shaders"));
    }

    @Test
    public void disabledModsStayDisabled() throws Exception {
        File game = tmp.newFolder("game");
        write(game, "mods/a.jar.disabled", "a");
        write(game, "mods/gone.jar.disabled", "gone");
        PackState previous = state("mods/a.jar", "a", "overwrite", "mods/gone.jar", "gone", "overwrite");
        PackManifest m = TestPacks.manifest(list(file("mods/a.jar", "a")), TestPacks.ext());
        PackSyncPlanner.Plan plan = plan(m, game, previous);
        assertTrue(plan.downloads.isEmpty() && plan.restores.isEmpty());
        assertEquals(PackSyncPlanner.KeepReason.USER_DISABLED, plan.kept.get(0).reason);
        assertTrue(plan.nextState.files.containsKey("mods/a.jar"));
        assertEquals(Collections.singletonList("mods/gone.jar.disabled"), deleted(plan));
    }

    @Test
    public void quickCheckSkipsHashing() throws Exception {
        File game = tmp.newFolder("game");
        // Same size as the expected content, different bytes.
        write(game, "config/a.txt", "xx");
        PackState previous = state("config/a.txt", "ok", "overwrite");
        PackManifest m = TestPacks.manifest(list(file("config/a.txt", "ok")), TestPacks.ext());
        PackSyncPlanner.Plan quick = plan(m, game, previous, null, new PackSyncPlanner.Options().quick(true));
        assertEquals(Collections.singletonList("config/a.txt"), quick.unchanged);
        PackSyncPlanner.Plan full = plan(m, game, previous);
        assertEquals(Collections.singletonList("config/a.txt (update)"), paths(full));
    }

    @Test
    public void unsafePathsAreNeverTouched() throws Exception {
        File root = tmp.newFolder("root");
        File outside = new File(root, "outside.txt");
        write(root, "outside.txt", "x");
        File game = new File(root, "game");
        assertTrue(game.mkdirs());
        // A tampered state file cannot make the planner delete outside the game dir.
        PackState previous = state("../outside.txt", "x", "overwrite");
        PackManifest m = TestPacks.manifest(new ArrayList<PackManifest.PackFile>(), TestPacks.ext());
        PackSyncPlanner.Plan plan = plan(m, game, previous);
        assertTrue(plan.deletes.isEmpty());

        // A forged plan is checked again before anything is applied.
        plan.deletes.add(new PackSyncPlanner.Delete("../outside.txt", FileCategory.OTHER, 1, null, false, null, null));
        try {
            apply(game, plan, new TestPacks.FakeTransport());
            fail("applied a plan that leaves the game dir");
        } catch (IOException expected) {
            assertTrue(outside.exists());
        }
    }

    @Test
    public void applyFollowsThePlan() throws Exception {
        File game = tmp.newFolder("game");
        TestPacks.FakeTransport transport = new TestPacks.FakeTransport()
                .serve("sodium v1", "old mod", "{\"v\":1}", "sodium v2", "{\"v\":2}");
        PackManifest m = TestPacks.manifest(list(file("mods/sodium.jar", "sodium v1"), file("mods/old.jar", "old mod"),
                file("config/sodium.json", "{\"v\":1}")), configPolicy());
        write(game, "mods/user.jar", "user mod");

        PackSyncPlanner.Plan plan = plan(m, game, null);
        final List<PackSyncApplier.Phase> phases = Collections.synchronizedList(new ArrayList<PackSyncApplier.Phase>());
        PackSyncApplier.Report report = PackSyncApplier.apply(game, plan, transport, (phase, done, total) -> phases.add(phase), 2);
        assertEquals(3, report.downloaded);
        assertTrue(report.issues.isEmpty());
        assertEquals("sodium v1", read(game, "mods/sodium.jar"));
        assertEquals(PackSyncApplier.Phase.FINISHED, phases.get(phases.size() - 1));
        PackState saved = PackState.load(game);
        assertTrue(saved.sameAs(plan.nextState));

        // Version 2: sodium updated, old.jar removed, config edited by the player.
        write(game, "config/sodium.json", "{\"v\":\"mine\"}");
        PackManifest m2 = TestPacks.manifest(list(file("mods/sodium.jar", "sodium v2"), file("config/sodium.json", "{\"v\":2}")), configPolicy());
        m2.versionId = "1.1.0";
        plan = plan(m2, game, saved);
        assertEquals("1 mod güncellenecek, 1 mod kaldırılacak, 1 değiştirilmiş dosya korunacak", plan.summary().describeTr());
        apply(game, plan, transport);
        assertEquals("sodium v2", read(game, "mods/sodium.jar"));
        assertFalse(new File(game, "mods/old.jar").exists());
        assertEquals("user mod", read(game, "mods/user.jar"));
        assertEquals("{\"v\":\"mine\"}", read(game, "config/sodium.json"));
        PackSyncPlanner.Plan again = plan(m2, game, PackState.load(game));
        assertTrue(again.isNoop());
        assertTrue(again.summary().describeTr().contains("korunacak"));
    }

    @Test
    public void failedDownloadChangesNothingElse() throws Exception {
        File game = tmp.newFolder("game");
        write(game, "mods/old.jar", "old");
        PackState previous = state("mods/old.jar", "old", "overwrite");
        PackManifest m = TestPacks.manifest(list(file("mods/new.jar", "new")), TestPacks.ext());
        PackSyncPlanner.Plan plan = plan(m, game, previous);
        assertEquals(1, plan.deletes.size());
        // The server serves different bytes than the manifest promises.
        TestPacks.FakeTransport transport = new TestPacks.FakeTransport();
        transport.files.put(TestPacks.url("new"), "evil");
        try {
            apply(game, plan, transport);
            fail("accepted a file with the wrong hash");
        } catch (IOException expected) {
            // nothing else may have happened
        }
        assertTrue("no deletes after a failed download", new File(game, "mods/old.jar").exists());
        assertFalse(new File(game, "mods/new.jar").exists());
        assertNull(PackState.load(game));
    }

    @Test
    public void stateRoundTripsAndCorruptStateIsMissing() throws Exception {
        File game = tmp.newFolder("game");
        assertNull(PackState.load(game));
        PackState state = state("mods/a.jar", "a", "overwrite");
        state.versionId = "2.3.0";
        state.groups.put("shaders", false);
        state.items.put("sodium", true);
        state.seenItems.add("sodium");
        state.manual.put("sodium", PackState.MANUAL_REMOVED);
        state.files.get("mods/a.jar").item = "sodium";
        state.files.get("mods/a.jar").disabled = true;
        state.save(game);
        PackState loaded = PackState.load(game);
        assertEquals("2.3.0", loaded.versionId);
        assertEquals(sha1("a"), loaded.files.get("mods/a.jar").sha1);
        assertEquals("sodium", loaded.files.get("mods/a.jar").item);
        assertTrue(loaded.files.get("mods/a.jar").disabled);
        assertEquals(Boolean.FALSE, loaded.groups.get("shaders"));
        assertEquals(PackState.MANUAL_REMOVED, loaded.manual.get("sodium"));
        assertTrue(loaded.sameAs(state));
        // A corrupt state is treated as absent.
        try (FileOutputStream out = new FileOutputStream(PackState.path(game))) {
            out.write("{not json".getBytes(StandardCharsets.UTF_8));
        }
        assertNull(PackState.load(game));
    }

    @Test
    public void desktopStatesAreReadAsTheyAre() throws Exception {
        // The pack-state example from content-format.md.
        String json = "{\"packId\": \"projects\", \"versionId\": \"2.3.0\", \"family\": \"projects\", \"minecraft\": \"1.21.1\","
                + " \"files\": {\"mods/iris.jar\": {\"sha1\": \"" + sha1("iris") + "\", \"policy\": \"overwrite\", \"item\": \"YL57xq9U\", \"disabled\": true},"
                + " \"options.txt\": {\"sha1\": \"" + sha1("o") + "\", \"policy\": \"ifMissing\"},"
                + " \"config/x.json\": {\"sha1\": \"" + sha1("x") + "\", \"policy\": \"somethingNew\"}},"
                + " \"groups\": {\"shaders\": false}, \"items\": {\"AANobbMI\": true, \"YL57xq9U\": false},"
                + " \"seenItems\": [\"YL57xq9U\", \"AANobbMI\"], \"manual\": {\"YL57xq9U\": \"disabled\"}, \"newerField\": 1}";
        PackState state = PackState.parse(json.getBytes(StandardCharsets.UTF_8));
        assertEquals("projects", state.family);
        assertEquals("1.21.1", state.minecraft);
        assertTrue(state.files.get("mods/iris.jar").disabled);
        assertEquals("unknown policies are the safest one", PackManifest.POLICY_PRESERVE, state.files.get("config/x.json").policy);
        assertEquals(Arrays.asList("AANobbMI", "YL57xq9U"), new ArrayList<>(state.seenItems));
        assertEquals(PackState.MANUAL_DISABLED, state.manual.get("YL57xq9U"));
        // Written back in the desktop's field order.
        String written = state.toJson();
        assertTrue(written, written.indexOf("\"packId\"") < written.indexOf("\"family\"")
                && written.indexOf("\"files\"") < written.indexOf("\"items\"")
                && written.indexOf("\"seenItems\"") < written.indexOf("\"manual\""));
        assertTrue(PackState.parse(written.getBytes(StandardCharsets.UTF_8)).sameAs(state));
    }

    @Test
    public void categoriesAndSummaryText() {
        assertEquals(FileCategory.MOD, FileCategory.of("mods/a.jar"));
        assertEquals(FileCategory.RESOURCE_PACK, FileCategory.of("resourcepacks/a.zip"));
        assertEquals(FileCategory.SHADER_PACK, FileCategory.of("shaderpacks/a.zip"));
        assertEquals(FileCategory.CONFIG, FileCategory.of("config/a/b.toml"));
        assertEquals(FileCategory.CONFIG, FileCategory.of("options.txt"));
        assertEquals(FileCategory.OTHER, FileCategory.of("servers.dat"));
        assertEquals(FileCategory.OTHER, FileCategory.of("kubejs/x.js"));
        assertEquals("Paket güncel", new PackSyncPlanner.Summary().describeTr());
        PackSyncPlanner.Summary summary = new PackSyncPlanner.Summary();
        summary.updated.mods = 3;
        summary.removed.resourcePacks = 1;
        assertEquals("3 mod güncellenecek, 1 kaynak paketi kaldırılacak", summary.describeTr());
        assertFalse(summary.isNoop());
    }

    // ---- items (mod screen) ----

    /** sodium (on) needs fabric-api (library); iris (off) needs sodium; plus a config outside items. */
    private static PackManifest itemManifest(String sodiumJar, Object... extraIdDefault) {
        List<PackManifest.PackFile> files = list(file("mods/fabric-api.jar", "fabric"), file(sodiumJar, "sodium"),
                file("mods/iris.jar", "iris"), file("config/sodium.json", "{}"));
        PackManifest.Projects ext = configPolicy();
        ext.items = list(TestPacks.library("fabric-api", "mods/fabric-api.jar"),
                item("sodium", sodiumJar, true, "fabric-api"),
                item("iris", "mods/iris.jar", false, "sodium"));
        for (int i = 0; i < extraIdDefault.length; i += 2) {
            String id = (String) extraIdDefault[i];
            files.add(file("mods/" + id + ".jar", id));
            ext.items.add(item(id, "mods/" + id + ".jar", (Boolean) extraIdDefault[i + 1]));
        }
        return TestPacks.manifest(files, ext);
    }

    /** Default selection of {@link #itemManifest} plus zoom and minimap (on), installed in {@code game}. */
    private PackState installedWithZoom(File game, PackManifest[] manifest) throws Exception {
        manifest[0] = itemManifest("mods/sodium.jar", "zoom", true, "minimap", true);
        PackSyncPlanner.Plan first = plan(manifest[0], game, null);
        return installed(game, first, "fabric", "sodium", "zoom", "minimap", "{}");
    }

    @Test
    public void itemsInstallDefaultsOnFirstSync() throws Exception {
        File game = tmp.newFolder("game");
        PackSyncPlanner.Plan plan = plan(itemManifest("mods/sodium.jar"), game, null);
        assertEquals("default items, the library they need and files outside items",
                Arrays.asList("mods/fabric-api.jar", "mods/sodium.jar", "config/sodium.json"), paths(plan));
        List<String> tagged = new ArrayList<>();
        for (PackSyncPlanner.Download download : plan.downloads) tagged.add(String.valueOf(download.item));
        assertEquals(Arrays.asList("fabric-api", "sodium", "null"), tagged);
        PackState state = plan.nextState;
        assertEquals("sodium", state.files.get("mods/sodium.jar").item);
        assertNull(state.files.get("config/sodium.json").item);
        assertEquals("{iris=false, sodium=true}", state.items.toString());
        assertEquals(3, state.seenItems.size());
        assertEquals("projects", state.family);
        assertEquals("1.21.1", state.minecraft);
        for (ItemResolver.ItemStatus status : plan.items) assertFalse(status.isNew);
    }

    @Test
    public void deselectingRemovesPackFilesOnly() throws Exception {
        File game = tmp.newFolder("game");
        PackManifest m = itemManifest("mods/sodium.jar", "zoom", true);
        PackState state = installed(game, plan(m, game, null), "fabric", "sodium", "zoom", "{}");
        write(game, "mods/player.jar", "mine");
        // The player edited the zoom jar: it is no longer the pack's copy.
        write(game, "mods/zoom.jar", "patched zoom");

        PackSyncPlanner.Plan plan = plan(m, game, state, select("sodium", false, "zoom", false));
        assertTrue(plan.downloads.isEmpty());
        assertEquals("the deselected item and the library nobody needs any more",
                Arrays.asList("mods/fabric-api.jar", "mods/sodium.jar"), deleted(plan));
        assertEquals(Collections.singletonList("mods/zoom.jar USER_MODIFIED"), kept(plan));
        assertEquals(Collections.singletonList("config/sodium.json"), plan.unchanged);
        PackSyncPlanner.Summary summary = plan.summary();
        assertEquals(2, summary.removed.mods);
        assertEquals(12, summary.removeBytes);
        assertEquals(Boolean.FALSE, plan.nextState.items.get("sodium"));

        // Applying it really deletes them and keeps the player's files.
        apply(game, plan, new TestPacks.FakeTransport());
        assertFalse(new File(game, "mods/sodium.jar").exists());
        assertFalse(new File(game, "mods/fabric-api.jar").exists());
        assertTrue(new File(game, "mods/player.jar").exists());
        assertTrue(new File(game, "mods/zoom.jar").exists());
    }

    @Test
    public void newItemsAreNotInstalledForExistingPlayers() throws Exception {
        File game = tmp.newFolder("game");
        PackManifest v1 = itemManifest("mods/sodium.jar");
        PackState state = installed(game, plan(v1, game, null), "fabric", "sodium", "{}");

        // Version 2 adds a default-on item.
        PackManifest v2 = itemManifest("mods/sodium.jar", "zoom", true);
        v2.versionId = "2.1.0";
        PackSyncPlanner.Plan plan = plan(v2, game, state);
        assertFalse(paths(plan).toString(), plan.changesFiles());
        ItemResolver.ItemStatus zoom = plan.status("zoom");
        assertTrue(zoom.isNew && !zoom.effectivelyEnabled);
        // Only the version moved; the item stays new until the mod screen is confirmed.
        assertTrue(plan.stateChanged && !plan.isNoop());
        assertFalse(plan.nextState.seenItems.contains("zoom"));

        // A brand-new install of the same version gets it.
        PackSyncPlanner.Plan fresh = plan(v2, tmp.newFolder("fresh"), null);
        assertTrue(paths(fresh).contains("mods/zoom.jar"));
    }

    @Test
    public void renamedItemFileIsAnUpdate() throws Exception {
        File game = tmp.newFolder("game");
        PackManifest v1 = itemManifest("mods/sodium-0.5.jar");
        PackState state = installed(game, plan(v1, game, null), "fabric", "sodium", "{}");

        PackManifest v2 = itemManifest("mods/sodium-0.6.jar");
        v2.files.set(1, file("mods/sodium-0.6.jar", "sodium 0.6"));
        PackSyncPlanner.Plan plan = plan(v2, game, state);
        assertEquals(Collections.singletonList("mods/sodium-0.6.jar (update)"), paths(plan));
        assertEquals(Collections.singletonList("mods/sodium-0.5.jar"), deleted(plan));
        assertTrue(plan.deletes.get(0).replaced);
        PackSyncPlanner.Summary summary = plan.summary();
        assertEquals("1 mod güncellenecek", summary.describeTr());
        assertEquals(0, summary.removed.total());
        assertEquals(6, summary.removeBytes);
        ItemResolver.ItemStatus sodium = plan.status("sodium");
        assertTrue(sodium.selected && sodium.installed && sodium.updateAvailable);

        // The choice follows the id: switched off, the renamed file is not installed and the old one goes away.
        PackState off = state.copy();
        off.items.put("sodium", false);
        plan = plan(v2, game, off);
        assertTrue(plan.downloads.isEmpty());
        assertEquals(Arrays.asList("mods/fabric-api.jar", "mods/sodium-0.5.jar"), deleted(plan));
    }

    @Test
    public void deletedItemFilesSwitchTheirItemsOff() throws Exception {
        File game = tmp.newFolder("game");
        PackManifest[] m = new PackManifest[1];
        PackState state = installedWithZoom(game, m);
        TestPacks.delete(game, "mods/zoom.jar");

        // A background sync adopts it: zoom is off, nothing is downloaded or deleted.
        PackSyncPlanner.Plan plan = plan(m[0], game, state);
        assertFalse(plan.changesFiles());
        assertFalse(plan.reset);
        assertEquals(1, plan.adopted.size());
        assertEquals("zoom", plan.adopted.get(0).item);
        assertEquals(PackState.MANUAL_REMOVED, plan.adopted.get(0).change);
        ItemResolver.ItemStatus zoom = plan.status("zoom");
        assertTrue(!zoom.selected && !zoom.effectivelyEnabled);
        assertEquals(PackState.MANUAL_REMOVED, zoom.manual);
        assertEquals(Boolean.FALSE, plan.nextState.items.get("zoom"));
        assertFalse(plan.nextState.files.containsKey("mods/zoom.jar"));
        assertTrue(plan.stateChanged);

        // The next sync has nothing new to say; the note stays until the mod screen is confirmed.
        PackSyncPlanner.Plan next = plan(m[0], game, plan.nextState);
        assertTrue(next.isNoop() && next.adopted.isEmpty());
        assertEquals(PackState.MANUAL_REMOVED, next.status("zoom").manual);
        PackSyncPlanner.Plan confirmed = plan(m[0], game, plan.nextState, new PackSelection());
        assertTrue(confirmed.nextState.manual.isEmpty());
        assertFalse(confirmed.changesFiles());

        // Picking it again in the mod screen downloads it.
        PackSyncPlanner.Plan back = plan(m[0], game, plan.nextState, select("zoom", true));
        assertEquals(Collections.singletonList("mods/zoom.jar"), paths(back));
    }

    @Test
    public void aRequiredItemThePlayerRemovedComesBack() throws Exception {
        File game = tmp.newFolder("game");
        PackManifest m = itemManifest("mods/sodium.jar");
        PackState state = installed(game, plan(m, game, null, select("iris", true)), "fabric", "sodium", "iris", "{}");
        // Iris needs Sodium (and Sodium the library); the player deleted Sodium and disabled the library.
        TestPacks.delete(game, "mods/sodium.jar");
        rename(game, "mods/fabric-api.jar", "mods/fabric-api.jar.disabled");
        PackSyncPlanner.Plan plan = plan(m, game, state);
        assertEquals(Collections.singletonList("mods/sodium.jar"), paths(plan));
        assertEquals(1, plan.restores.size());
        assertEquals("mods/fabric-api.jar", plan.restores.get(0).path);
        ItemResolver.ItemStatus sodium = plan.status("sodium");
        assertTrue(!sodium.selected && sodium.effectivelyEnabled);
        assertEquals(Collections.singletonList("iris"), sodium.requiredBy);
        assertEquals(PackState.MANUAL_REMOVED, sodium.manual);
        ItemResolver.ItemStatus fabric = plan.status("fabric-api");
        assertEquals(PackState.MANUAL_DISABLED, fabric.manual);
        assertEquals(Collections.singletonList("sodium"), fabric.requiredBy);
        List<String> changes = new ArrayList<>();
        for (PackSyncPlanner.Adopted adopted : plan.adopted) changes.add(adopted.item);
        assertEquals(Arrays.asList("sodium", "fabric-api"), changes);
    }

    @Test
    public void disabledItemFilesAreAdoptedAndKept() throws Exception {
        File game = tmp.newFolder("game");
        TestPacks.FakeTransport transport = new TestPacks.FakeTransport().serve("sodium v1", "iris v1", "iris v2");
        PackManifest.Projects ext = TestPacks.ext();
        ext.items = list(item("sodium", "mods/sodium.jar", true), item("iris", "mods/iris.jar", true));
        PackManifest m = TestPacks.manifest(list(file("mods/sodium.jar", "sodium v1"), file("mods/iris.jar", "iris v1")), ext);
        apply(game, plan(m, game, null), transport);
        // The player disabled both jars outside the launcher.
        rename(game, "mods/sodium.jar", "mods/sodium.jar.disabled");
        rename(game, "mods/iris.jar", "mods/iris.jar.disabled");

        // Both count as switched off; the disabled copies stay, even when Iris gets an update.
        PackManifest.Projects ext2 = TestPacks.ext();
        ext2.items = list(item("sodium", "mods/sodium.jar", true), item("iris", "mods/iris.jar", true));
        PackManifest m2 = TestPacks.manifest(list(file("mods/sodium.jar", "sodium v1"), file("mods/iris.jar", "iris v2")), ext2);
        PackSyncPlanner.Plan plan = plan(m2, game, PackState.load(game));
        assertFalse(plan.changesFiles());
        assertEquals(2, plan.adopted.size());
        for (String id : new String[]{"sodium", "iris"}) {
            ItemResolver.ItemStatus status = plan.status(id);
            assertTrue(!status.effectivelyEnabled && status.disabledCopy);
            assertEquals(PackState.MANUAL_DISABLED, status.manual);
            assertEquals(Boolean.FALSE, plan.nextState.items.get(id));
        }
        assertTrue(plan.nextState.files.get("mods/sodium.jar").disabled);
        apply(game, plan, transport);
        assertTrue(new File(game, "mods/sodium.jar.disabled").exists());
        assertTrue(new File(game, "mods/iris.jar.disabled").exists());

        // Confirming the mod screen with them off keeps the copies.
        PackSyncPlanner.Plan confirmed = plan(m2, game, PackState.load(game), select("sodium", false, "iris", false));
        assertFalse(confirmed.changesFiles());
        assertTrue(confirmed.nextState.manual.isEmpty());

        // The player renames Sodium back: adopted as switched on, nothing else happens.
        rename(game, "mods/sodium.jar.disabled", "mods/sodium.jar");
        plan = plan(m2, game, confirmed.nextState);
        assertFalse(plan.changesFiles());
        assertEquals(1, plan.adopted.size());
        assertEquals(PackState.MANUAL_ENABLED, plan.adopted.get(0).change);
        assertEquals(Boolean.TRUE, plan.nextState.items.get("sodium"));
        assertFalse(plan.nextState.files.get("mods/sodium.jar").disabled);

        // Iris selected in the mod screen: its stale copy is replaced by the update.
        plan = plan(m2, game, plan.nextState, select("iris", true));
        assertEquals(Collections.singletonList("mods/iris.jar"), paths(plan));
        assertEquals(Collections.singletonList("mods/iris.jar.disabled"), deleted(plan));
        PackSyncApplier.Report report = apply(game, plan, transport);
        assertEquals(1, report.downloaded);
        assertEquals(1, report.deleted);
        assertEquals("iris v2", read(game, "mods/iris.jar"));
        PackSyncPlanner.Plan again = plan(m2, game, PackState.load(game));
        assertTrue(again.isNoop());

        // An item selected while its copy sits disabled is renamed back, not downloaded.
        rename(game, "mods/iris.jar", "mods/iris.jar.disabled");
        plan = plan(m2, game, PackState.load(game), select("iris", true));
        assertEquals(1, plan.restores.size());
        assertEquals("mods/iris.jar", plan.restores.get(0).path);
        assertTrue(plan.downloads.isEmpty());
        report = apply(game, plan, transport);
        assertEquals(1, report.restored);
        assertEquals("iris v2", read(game, "mods/iris.jar"));
    }

    @Test
    public void replacedItemFilesAreKeptUntilReverted() throws Exception {
        File game = tmp.newFolder("game");
        PackManifest[] m = new PackManifest[1];
        PackState state = installedWithZoom(game, m);
        write(game, "mods/zoom.jar", "zoom, patched by the player");

        PackSyncPlanner.Plan plan = plan(m[0], game, state);
        assertFalse(plan.changesFiles());
        ItemResolver.ItemStatus zoom = plan.status("zoom");
        assertTrue(zoom.modified && zoom.effectivelyEnabled && zoom.manual == null);
        assertEquals(1, plan.adopted.size());
        assertEquals(PackState.MANUAL_REPLACED, plan.adopted.get(0).change);
        assertTrue(plan.nextState.files.get("mods/zoom.jar").modified);
        // Said once.
        PackSyncPlanner.Plan next = plan(m[0], game, plan.nextState);
        assertTrue(next.adopted.isEmpty() && next.status("zoom").modified);
        // A pack update does not overwrite it either.
        PackManifest updated = itemManifest("mods/sodium.jar", "zoom", true, "minimap", true);
        for (int i = 0; i < updated.files.size(); i++) {
            if (updated.files.get(i).path.equals("mods/zoom.jar")) updated.files.set(i, file("mods/zoom.jar", "zoom 2"));
        }
        assertTrue(plan(updated, game, next.nextState).downloads.isEmpty());

        // "Önerilene döndür" brings the pack's file back without touching the choice.
        PackSelection revert = new PackSelection();
        revert.revert.add("zoom");
        plan = plan(m[0], game, next.nextState, revert);
        assertEquals(Collections.singletonList("mods/zoom.jar (update)"), paths(plan));
        assertEquals(Boolean.TRUE, plan.nextState.items.get("zoom"));
        assertFalse(plan.nextState.files.get("mods/zoom.jar").modified);
    }

    @Test
    public void wipedModsFolderIsReinstalled() throws Exception {
        File game = tmp.newFolder("game");
        PackManifest[] m = new PackManifest[1];
        PackState state = installedWithZoom(game, m);
        write(game, "mods/player.jar", "mine");

        // Most pack mods gone at once: a reset, not four manual removals.
        for (String jar : new String[]{"fabric-api", "sodium", "zoom", "minimap"}) TestPacks.delete(game, "mods/" + jar + ".jar");
        PackSyncPlanner.Plan plan = plan(m[0], game, state);
        assertTrue(plan.reset && plan.adopted.isEmpty());
        assertEquals(4, plan.downloads.size());
        assertEquals(state.items, plan.nextState.items);

        // The folder itself gone: also a reset, even for a single mod.
        TestPacks.delete(game, "mods/player.jar");
        assertTrue(new File(game, "mods").delete());
        PackState small = state.copy();
        small.files.remove("mods/zoom.jar");
        small.files.remove("mods/minimap.jar");
        small.files.remove("mods/fabric-api.jar");
        plan = plan(m[0], game, small);
        assertTrue(plan.reset);
        assertTrue(paths(plan).contains("mods/sodium.jar"));

        // Two of four missing is a choice, not a wipe.
        File other = tmp.newFolder("other");
        state = installedWithZoom(other, m);
        TestPacks.delete(other, "mods/zoom.jar");
        TestPacks.delete(other, "mods/minimap.jar");
        plan = plan(m[0], other, state);
        assertTrue(!plan.reset && plan.downloads.isEmpty());
        assertEquals(2, plan.adopted.size());
    }

    @Test
    public void explicitSelectionBeatsAdoption() throws Exception {
        File game = tmp.newFolder("game");
        PackManifest[] m = new PackManifest[1];
        PackState state = installedWithZoom(game, m);
        TestPacks.delete(game, "mods/zoom.jar");
        PackSyncPlanner.Plan plan = plan(m[0], game, state, select("zoom", true));
        assertTrue(plan.adopted.isEmpty());
        assertEquals(Collections.singletonList("mods/zoom.jar"), paths(plan));
    }

    @Test
    public void unreadableFilesAreIssuesNotErrors() throws Exception {
        File game = tmp.newFolder("game");
        PackManifest[] m = new PackManifest[1];
        PackState state = installedWithZoom(game, m);
        // A folder where the pack's jar belongs.
        TestPacks.delete(game, "mods/zoom.jar");
        assertTrue(new File(game, "mods/zoom.jar").mkdirs());
        PackSyncPlanner.Plan plan = plan(m[0], game, state);
        assertEquals(1, plan.issues.size());
        assertEquals("mods/zoom.jar", plan.issues.get(0).path);
        assertEquals(PackSyncPlanner.IssueKind.NOT_A_FILE, plan.issues.get(0).kind);
        assertTrue("not a manual change", plan.adopted.isEmpty());
        assertTrue(plan.downloads.isEmpty());
        assertTrue("the record stays for the next sync", plan.nextState.files.containsKey("mods/zoom.jar"));

        // A deletion that fails is reported and its record kept.
        assertTrue(new File(game, "mods/stuck.jar").mkdirs());
        plan.deletes.add(new PackSyncPlanner.Delete("mods/stuck.jar", FileCategory.MOD, 1, null, false, "mods/stuck.jar",
                new PackState.InstalledFile(sha1("stuck"), PackManifest.POLICY_OVERWRITE)));
        PackSyncApplier.Report report = apply(game, plan, new TestPacks.FakeTransport());
        assertEquals(1, report.issues.size());
        assertEquals(PackSyncPlanner.IssueKind.BUSY, report.issues.get(0).kind);
        assertTrue(PackState.load(game).files.containsKey("mods/stuck.jar"));
    }

    @Test
    public void choicesWithoutFileChangesAreSaved() throws Exception {
        File game = tmp.newFolder("game");
        PackManifest m = itemManifest("mods/sodium.jar", "zoom", false);
        PackState state = installed(game, plan(m, game, null), "fabric", "sodium", "{}");
        state.save(game);
        assertTrue(plan(m, game, state).isNoop());

        // Turning off Iris (already off) changes nothing on disk, but confirming is still recorded.
        PackState unseen = state.copy();
        unseen.seenItems.remove("zoom");
        PackSyncPlanner.Plan plan = plan(m, game, unseen, select("iris", false));
        assertTrue(!plan.changesFiles() && plan.stateChanged && !plan.isNoop());
        assertTrue(plan.nextState.seenItems.contains("zoom"));

        // The same without a selection: nothing to apply.
        PackState seen = state.copy();
        seen.markItemsSeen(m, ItemResolver.MANIFEST_DEFAULTS);
        assertTrue("already confirmed", seen.sameAs(state));
    }

    // ---- downloader ----

    @Test
    public void downloaderFallsBackToTheNextUrlAndVerifies() throws Exception {
        final String content = "mod bytes";
        PackManifest.PackFile entry = file("mods/a.jar", content);
        entry.hashes.sha512 = sha512(content);
        entry.downloads = list("https://down.test/a.jar", "https://up.test/a.jar");
        PackManifest manifest = TestPacks.manifest(list(entry), TestPacks.ext());
        File game = tmp.newFolder("dl");
        final List<String> tried = new ArrayList<>();
        PackDownloader.Transport transport = (url, destination, maxBytes, progress) -> {
            tried.add(url);
            if (url.contains("down.test")) throw new HttpStatusException(503, url);
            try (FileOutputStream out = new FileOutputStream(destination)) {
                out.write(content.getBytes(StandardCharsets.UTF_8));
            }
            progress.onProgress(content.length(), maxBytes);
        };
        PackSyncPlanner.Plan plan = plan(manifest, game, null);
        final long[] last = {0};
        PackDownloader.downloadAll(game, plan.downloads, transport, (done, total) -> last[0] = done, 2);
        assertEquals("[https://down.test/a.jar, https://up.test/a.jar]", tried.toString());
        assertEquals(sha1(content), Hashes.fileHex(new File(game, "mods/a.jar"), "SHA-1"));
        assertEquals(content.length(), last[0]);
        assertFalse(new File(game, "mods/a.jar.projects-download").exists());
    }

    @Test
    public void downloaderRejectsWrongContent() throws Exception {
        PackManifest manifest = TestPacks.manifest(list(file("mods/a.jar", "expected")), TestPacks.ext());
        File game = tmp.newFolder("bad");
        PackDownloader.Transport transport = (url, destination, maxBytes, progress) -> {
            try (FileOutputStream out = new FileOutputStream(destination)) {
                out.write("tampered".getBytes(StandardCharsets.UTF_8));
            }
        };
        PackSyncPlanner.Plan plan = plan(manifest, game, null);
        try {
            PackDownloader.downloadAll(game, plan.downloads, transport, null, 1);
            fail("accepted a file with the wrong hash");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("mods/a.jar"));
        }
        assertFalse(new File(game, "mods/a.jar").exists());
    }
}
