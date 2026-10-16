package gg.projects.mobile.content;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Builders for pack manifests, states and game folders in tests (the desktop tests' helpers). */
final class TestPacks {
    private TestPacks() {}

    static String sha1(String content) {
        return Hashes.hex(Hashes.digest("SHA-1").digest(content.getBytes(StandardCharsets.UTF_8)));
    }

    static String sha512(String content) {
        return Hashes.hex(Hashes.digest("SHA-512").digest(content.getBytes(StandardCharsets.UTF_8)));
    }

    static String url(String content) {
        return "https://cdn.test/files/" + sha1(content);
    }

    static PackManifest.PackFile file(String path, String content) {
        PackManifest.PackFile file = new PackManifest.PackFile();
        file.path = path;
        file.hashes = new PackManifest.FileHashes();
        file.hashes.sha1 = sha1(content);
        file.downloads = new ArrayList<>(Arrays.asList(url(content)));
        file.fileSize = content.getBytes(StandardCharsets.UTF_8).length;
        return file;
    }

    static PackManifest.PackFile serverOnly(String path, String content) {
        PackManifest.PackFile file = file(path, content);
        file.env = new PackManifest.Env();
        file.env.client = "unsupported";
        file.env.server = "required";
        return file;
    }

    static PackManifest.PackItem item(String id, String path, boolean defaultEnabled, String... requires) {
        PackManifest.PackItem item = new PackManifest.PackItem();
        item.id = id;
        item.path = path;
        item.title = id.toUpperCase(java.util.Locale.ROOT);
        item.category = PackManifest.CATEGORY_PERFORMANCE;
        item.defaultEnabled = defaultEnabled;
        item.requires = new ArrayList<>(Arrays.asList(requires));
        return item;
    }

    static PackManifest.PackItem library(String id, String path) {
        PackManifest.PackItem item = item(id, path, false);
        item.library = true;
        item.category = PackManifest.CATEGORY_LIBRARY;
        return item;
    }

    static PackManifest.Projects ext() {
        PackManifest.Projects ext = new PackManifest.Projects();
        ext.packId = "projects";
        return ext;
    }

    /** options.txt ifMissing, config/** preserveUserChanges, everything else overwrite. */
    static PackManifest.Projects configPolicy() {
        PackManifest.Projects ext = ext();
        ext.configPolicy = new PackManifest.ConfigPolicy();
        ext.configPolicy.defaultPolicy = PackManifest.POLICY_OVERWRITE;
        ext.configPolicy.rules = new ArrayList<>();
        ext.configPolicy.rules.add(rule("options.txt", PackManifest.POLICY_IF_MISSING));
        ext.configPolicy.rules.add(rule("config/**", PackManifest.POLICY_PRESERVE));
        return ext;
    }

    static PackManifest.PolicyRule rule(String glob, String policy) {
        PackManifest.PolicyRule rule = new PackManifest.PolicyRule();
        rule.glob = glob;
        rule.policy = policy;
        return rule;
    }

    static PackManifest.OptionalGroup group(String id, boolean defaultEnabled, String... files) {
        PackManifest.OptionalGroup group = new PackManifest.OptionalGroup();
        group.id = id;
        group.name = id;
        group.defaultEnabled = defaultEnabled;
        group.files = new ArrayList<>(Arrays.asList(files));
        return group;
    }

    static PackManifest manifest(List<PackManifest.PackFile> files, PackManifest.Projects ext) {
        PackManifest manifest = new PackManifest();
        manifest.formatVersion = 1;
        manifest.game = "minecraft";
        manifest.versionId = "2.0.0";
        manifest.name = "Projects";
        manifest.files = files;
        manifest.dependencies = new HashMap<>();
        manifest.dependencies.put("minecraft", "1.21.1");
        manifest.projects = ext;
        try {
            manifest.validate();
        } catch (ContentException e) {
            throw new AssertionError(e);
        }
        return manifest;
    }

    @SafeVarargs
    static <T> List<T> list(T... values) {
        return new ArrayList<>(Arrays.asList(values));
    }

    /** A state recording files as installed with these contents. */
    static PackState state(String... pathContentPolicy) {
        PackState state = new PackState();
        state.packId = "projects";
        state.versionId = "1.0.0";
        for (int i = 0; i < pathContentPolicy.length; i += 3) {
            state.files.put(pathContentPolicy[i], new PackState.InstalledFile(sha1(pathContentPolicy[i + 1]), pathContentPolicy[i + 2]));
        }
        return state;
    }

    static void write(File game, String path, String content) {
        File target = new File(game, path);
        File parent = target.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new AssertionError("cannot create " + parent);
        try (FileOutputStream out = new FileOutputStream(target)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    static String read(File game, String path) {
        try {
            return new String(AtomicFiles.read(new File(game, path), 1 << 20), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    static void rename(File game, String from, String to) {
        if (!new File(game, from).renameTo(new File(game, to))) throw new AssertionError("cannot rename " + from);
    }

    static void delete(File game, String path) {
        if (!new File(game, path).delete()) throw new AssertionError("cannot delete " + path);
    }

    /** Serves file contents by URL (see {@link #url}); records requests. */
    static final class FakeTransport implements PackDownloader.Transport {
        final Map<String, String> files = new HashMap<>();
        final List<String> requests = new ArrayList<>();

        FakeTransport serve(String... contents) {
            for (String content : contents) files.put(url(content), content);
            return this;
        }

        @Override
        public synchronized void download(String url, File destination, long maxBytes, PackDownloader.Progress progress) throws IOException {
            requests.add(url);
            String content = files.get(url);
            if (content == null) throw new HttpStatusException(404, url);
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream out = new FileOutputStream(destination)) {
                out.write(bytes);
            }
            progress.onProgress(bytes.length, maxBytes);
        }
    }

    /** Writes what {@code plan} would download (content found by hash) and returns the state an apply would save. */
    static PackState installed(File game, PackSyncPlanner.Plan plan, String... contents) {
        Map<String, String> bySha = new HashMap<>();
        for (String content : contents) bySha.put(sha1(content), content);
        for (PackSyncPlanner.Download download : plan.downloads) {
            String content = bySha.get(download.sha1);
            if (content == null) throw new AssertionError("no content for " + download.path);
            write(game, download.path, content);
        }
        return plan.nextState.copy();
    }

    static List<String> paths(PackSyncPlanner.Plan plan) {
        List<String> out = new ArrayList<>();
        for (PackSyncPlanner.Download download : plan.downloads) {
            out.add(download.path + (download.action == PackSyncPlanner.Action.UPDATE ? " (update)" : ""));
        }
        return out;
    }

    static List<String> deleted(PackSyncPlanner.Plan plan) {
        List<String> out = new ArrayList<>();
        for (PackSyncPlanner.Delete delete : plan.deletes) out.add(delete.path);
        return out;
    }

    static List<String> kept(PackSyncPlanner.Plan plan) {
        List<String> out = new ArrayList<>();
        for (PackSyncPlanner.Kept kept : plan.kept) out.add(kept.path + " " + kept.reason);
        return out;
    }

    static PackSelection select(Object... idOn) {
        PackSelection selection = new PackSelection();
        for (int i = 0; i < idOn.length; i += 2) selection.items.put((String) idOn[i], (Boolean) idOn[i + 1]);
        return selection;
    }

    static PackSyncPlanner.Options defaults() {
        return new PackSyncPlanner.Options();
    }
}
