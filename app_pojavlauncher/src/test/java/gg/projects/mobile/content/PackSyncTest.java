package gg.projects.mobile.content;

import static gg.projects.mobile.content.IndexVerifierTest.expect;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Pack manifests, the sync planner (desktop rules) and the verified downloader. */
public class PackSyncTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static String sha1(String content) {
        return Hashes.hex(Hashes.digest("SHA-1").digest(content.getBytes(StandardCharsets.UTF_8)));
    }

    private static String sha512(String content) {
        return Hashes.hex(Hashes.digest("SHA-512").digest(content.getBytes(StandardCharsets.UTF_8)));
    }

    private static String file(String path, String content, String extra) {
        return "{\"path\": \"" + path + "\", \"hashes\": {\"sha1\": \"" + sha1(content) + "\", \"sha512\": \"" + sha512(content) + "\"},"
                + " \"downloads\": [\"https://cdn.test/" + path + "\"], \"fileSize\": " + content.length() + extra + "}";
    }

    private static PackManifest manifest(String files, String projects) throws ContentException {
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
    }

    @Test
    public void manifestValidation() {
        String a = file("mods/a.jar", "a", "");
        expect(ContentException.Kind.INVALID, () -> manifest(a + "," + file("MODS/A.jar", "b", ""), "{}"));
        expect(ContentException.Kind.INVALID, () -> manifest(file("mods", "x", "") + "," + file("mods/b.jar", "b", ""), "{}"));
        expect(ContentException.Kind.INVALID, () -> manifest(file("../evil.jar", "x", ""), "{}"));
        expect(ContentException.Kind.INVALID, () -> manifest(file(".projects/pack-state.json", "x", ""), "{}"));
        expect(ContentException.Kind.INVALID, () -> manifest(a.replace("https://cdn.test", "ftp://cdn.test"), "{}"));
        expect(ContentException.Kind.INVALID, () -> manifest(a.replace(sha1("a"), "xyz"), "{}"));
        expect(ContentException.Kind.INVALID, () -> manifest(a, "{\"optionalGroups\": [{\"id\": \"g\"}, {\"id\": \"g\"}]}"));
        expect(ContentException.Kind.UNSUPPORTED_SCHEMA, () -> PackManifest.parse(
                "{\"formatVersion\": 2, \"versionId\": \"1\", \"name\": \"x\", \"dependencies\": {\"minecraft\": \"1.21.1\"}}".getBytes(StandardCharsets.UTF_8)));
        expect(ContentException.Kind.INVALID, () -> PackManifest.parse(
                "{\"formatVersion\": 1, \"game\": \"terraria\", \"versionId\": \"1\", \"name\": \"x\", \"dependencies\": {\"minecraft\": \"1\"}}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void configPolicyRules() throws Exception {
        PackManifest manifest = manifest(file("options.txt", "o", ""),
                "{\"configPolicy\": {\"default\": \"overwrite\", \"rules\": [{\"glob\": \"options.txt\", \"policy\": \"ifMissing\"},"
                        + " {\"glob\": \"config/**\", \"policy\": \"preserveUserChanges\"}, {\"glob\": \"weird/**\", \"policy\": \"sometimes\"}]}}");
        assertEquals(PackManifest.POLICY_IF_MISSING, manifest.policyFor("options.txt"));
        assertEquals(PackManifest.POLICY_PRESERVE, manifest.policyFor("config/a/b.toml"));
        assertEquals(PackManifest.POLICY_PRESERVE, manifest.policyFor("weird/x"));
        assertEquals(PackManifest.POLICY_OVERWRITE, manifest.policyFor("mods/x.jar"));
    }

    /** A fake game directory: relative path to file content. */
    private static final class Disk implements PackSyncPlanner.FileProbe {
        final Map<String, String> files = new HashMap<>();

        @Override
        public boolean isFile(String relativePath) {
            return files.containsKey(relativePath);
        }

        @Override
        public String sha1(String relativePath) {
            return PackSyncTest.sha1(files.get(relativePath));
        }
    }

    private static List<String> downloads(PackSyncPlanner.Plan plan) {
        List<String> out = new ArrayList<>();
        for (PackSyncPlanner.Download d : plan.downloads) out.add(d.file.path + (d.update ? " (update)" : ""));
        Collections.sort(out);
        return out;
    }

    @Test
    public void freshInstallHonoursEnvAndGroups() throws Exception {
        PackManifest manifest = manifest(
                file("mods/a.jar", "a", "") + ","
                        + file("mods/server-only.jar", "s", ", \"env\": {\"client\": \"unsupported\", \"server\": \"required\"}") + ","
                        + file("mods/optional.jar", "o", ", \"env\": {\"client\": \"optional\"}") + ","
                        + file("shaderpacks/s.zip", "z", ", \"env\": {\"client\": \"optional\"}") + ","
                        + file("mods/minimap.jar", "m", ""),
                "{\"packId\": \"projects\", \"optionalGroups\": ["
                        + "{\"id\": \"shaders\", \"name\": \"Shaders\", \"defaultEnabled\": false, \"files\": [\"shaderpacks/s.zip\"]},"
                        + "{\"id\": \"minimap\", \"name\": \"Minimap\", \"defaultEnabled\": true, \"files\": [\"mods/minimap.jar\"]}]}");
        PackSyncPlanner.Plan plan = PackSyncPlanner.plan(manifest, null, new Disk());
        assertEquals("[mods/a.jar, mods/minimap.jar, mods/optional.jar]", downloads(plan).toString());
        assertTrue(plan.deletes.isEmpty());
        assertEquals("projects", plan.nextState.packId);
        assertEquals("2.0.0", plan.nextState.versionId);
        assertEquals(Boolean.FALSE, plan.nextState.groups.get("shaders"));
        assertEquals(Boolean.TRUE, plan.nextState.groups.get("minimap"));
        assertEquals(3, plan.nextState.files.size());
    }

    @Test
    public void updatesFollowThePolicyTable() throws Exception {
        String policies = "{\"packId\": \"projects\", \"configPolicy\": {\"default\": \"overwrite\", \"rules\": ["
                + "{\"glob\": \"options.txt\", \"policy\": \"ifMissing\"}, {\"glob\": \"config/**\", \"policy\": \"preserveUserChanges\"}]}}";
        PackManifest manifest = manifest(
                file("mods/same.jar", "same", "") + ","
                        + file("mods/overwritten.jar", "new", "") + ","
                        + file("options.txt", "pack options", "") + ","
                        + file("config/untouched.json", "v2", "") + ","
                        + file("config/edited.json", "v2", "") + ","
                        + file("config/foreign.json", "v2", "") + ","
                        + file("config/missing.json", "v2", "") + ","
                        + file("mods/disabled.jar", "d2", ""),
                policies);
        PackState previous = new PackState();
        previous.files.put("mods/same.jar", new PackState.InstalledFile(sha1("same"), "overwrite"));
        previous.files.put("mods/overwritten.jar", new PackState.InstalledFile(sha1("old"), "overwrite"));
        previous.files.put("config/untouched.json", new PackState.InstalledFile(sha1("v1"), "preserveUserChanges"));
        previous.files.put("config/edited.json", new PackState.InstalledFile(sha1("v1"), "preserveUserChanges"));
        previous.files.put("mods/removed.jar", new PackState.InstalledFile(sha1("r"), "overwrite"));
        previous.files.put("mods/removed-but-edited.jar", new PackState.InstalledFile(sha1("r"), "overwrite"));
        previous.files.put("mods/removed-disabled.jar", new PackState.InstalledFile(sha1("rd"), "overwrite"));
        previous.files.put("../outside.jar", new PackState.InstalledFile(sha1("o"), "overwrite"));

        Disk disk = new Disk();
        disk.files.put("mods/same.jar", "same");
        disk.files.put("mods/overwritten.jar", "player changed it");
        disk.files.put("options.txt", "player options");
        disk.files.put("config/untouched.json", "v1");
        disk.files.put("config/edited.json", "player edit");
        disk.files.put("config/foreign.json", "someone else's");
        disk.files.put("mods/disabled.jar.disabled", "d1");
        disk.files.put("mods/removed.jar", "r");
        disk.files.put("mods/removed-but-edited.jar", "changed");
        disk.files.put("mods/removed-disabled.jar.disabled", "rd");
        disk.files.put("mods/player-added.jar", "mine");
        disk.files.put("../outside.jar", "o");

        PackSyncPlanner.Plan plan = PackSyncPlanner.plan(manifest, previous, disk);
        assertEquals("[config/missing.json, config/untouched.json (update), mods/overwritten.jar (update)]", downloads(plan).toString());
        List<String> deletes = new ArrayList<>(plan.deletes);
        Collections.sort(deletes);
        assertEquals("[mods/removed-disabled.jar.disabled, mods/removed.jar]", deletes.toString());
        List<String> kept = new ArrayList<>(plan.kept);
        Collections.sort(kept);
        assertEquals("[config/edited.json, config/foreign.json, mods/disabled.jar, mods/removed-but-edited.jar, options.txt]", kept.toString());
        // Kept files keep their old record (or none); installed files record the pack's hash.
        assertEquals(sha1("v1"), plan.nextState.files.get("config/edited.json").sha1);
        assertFalse(plan.nextState.files.containsKey("config/foreign.json"));
        assertFalse(plan.nextState.files.containsKey("options.txt"));
        assertEquals(sha1("v2"), plan.nextState.files.get("config/untouched.json").sha1);
        assertEquals(sha1("same"), plan.nextState.files.get("mods/same.jar").sha1);
        assertFalse(plan.nextState.files.containsKey("mods/removed.jar"));
    }

    @Test
    public void groupChoicesAreRemembered() throws Exception {
        PackManifest manifest = manifest(file("shaderpacks/s.zip", "z", ""),
                "{\"optionalGroups\": [{\"id\": \"shaders\", \"name\": \"Shaders\", \"defaultEnabled\": false, \"files\": [\"shaderpacks/s.zip\"]}]}");
        PackState previous = new PackState();
        previous.groups.put("shaders", true);
        assertEquals("[shaderpacks/s.zip]", downloads(PackSyncPlanner.plan(manifest, previous, new Disk())).toString());
    }

    @Test
    public void stateRoundTripsThroughDisk() throws Exception {
        File game = tmp.newFolder("game");
        assertEquals(null, PackState.load(game));
        PackState state = new PackState();
        state.packId = "projects";
        state.versionId = "2.3.0";
        state.files.put("mods/a.jar", new PackState.InstalledFile(sha1("a"), "overwrite"));
        state.groups.put("shaders", false);
        state.save(game);
        PackState loaded = PackState.load(game);
        assertEquals("2.3.0", loaded.versionId);
        assertEquals(sha1("a"), loaded.files.get("mods/a.jar").sha1);
        assertEquals(Boolean.FALSE, loaded.groups.get("shaders"));
        // A corrupt state is treated as absent.
        try (FileOutputStream out = new FileOutputStream(PackState.path(game))) {
            out.write("{not json".getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(null, PackState.load(game));
    }

    @Test
    public void downloaderFallsBackToTheNextUrlAndVerifies() throws Exception {
        final String content = "mod bytes";
        String json = "{\"path\": \"mods/a.jar\", \"hashes\": {\"sha1\": \"" + sha1(content) + "\", \"sha512\": \"" + sha512(content) + "\"},"
                + " \"downloads\": [\"https://down.test/a.jar\", \"https://up.test/a.jar\"], \"fileSize\": " + content.length() + "}";
        PackManifest manifest = manifest(json, "{}");
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
        PackSyncPlanner.Plan plan = PackSyncPlanner.plan(manifest, null, PackSyncPlanner.directoryProbe(game));
        final long[] last = {0};
        PackDownloader.downloadAll(game, plan.downloads, transport, (done, total) -> last[0] = done, 2);
        assertEquals("[https://down.test/a.jar, https://up.test/a.jar]", tried.toString());
        assertEquals(sha1(content), Hashes.fileHex(new File(game, "mods/a.jar"), "SHA-1"));
        assertEquals(content.length(), last[0]);
        assertFalse(new File(game, "mods/a.jar.projects-download").exists());
    }

    @Test
    public void downloaderRejectsWrongContent() throws Exception {
        String json = "{\"path\": \"mods/a.jar\", \"hashes\": {\"sha1\": \"" + sha1("expected") + "\"},"
                + " \"downloads\": [\"https://cdn.test/a.jar\"], \"fileSize\": 8}";
        PackManifest manifest = manifest(json, "{}");
        File game = tmp.newFolder("bad");
        PackDownloader.Transport transport = (url, destination, maxBytes, progress) -> {
            try (FileOutputStream out = new FileOutputStream(destination)) {
                out.write("tampered".getBytes(StandardCharsets.UTF_8));
            }
        };
        PackSyncPlanner.Plan plan = PackSyncPlanner.plan(manifest, null, PackSyncPlanner.directoryProbe(game));
        try {
            PackDownloader.downloadAll(game, plan.downloads, transport, null, 1);
            fail("accepted a file with the wrong hash");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("mods/a.jar"));
        }
        assertFalse(new File(game, "mods/a.jar").exists());
    }
}
