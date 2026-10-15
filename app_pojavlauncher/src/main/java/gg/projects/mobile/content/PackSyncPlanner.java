package gg.projects.mobile.content;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plans how a game directory follows a pack manifest (mirror of the desktop sync rules in
 * docs/content-format.md "Sync semantics and pack state"):
 *
 * <ul>
 * <li>a missing file is downloaded, unless the player disabled it ({@code <path>.disabled});</li>
 * <li>a file that differs is replaced for {@code overwrite}, kept for {@code ifMissing}, and for
 * {@code preserveUserChanges} only replaced while it is still the copy we installed;</li>
 * <li>files we installed that the pack no longer wants are deleted only while unchanged;</li>
 * <li>files the player added are never touched.</li>
 * </ul>
 */
public final class PackSyncPlanner {
    private PackSyncPlanner() {}

    public static final String DISABLED_SUFFIX = ".disabled";

    /** Disk access, so the planner can be tested without a device. */
    public interface FileProbe {
        /** True when the relative path is an existing regular file. */
        boolean isFile(String relativePath);

        /** Lowercase SHA-1 of the file at the relative path. */
        String sha1(String relativePath) throws IOException;
    }

    public static final class Download {
        public final PackManifest.PackFile file;
        public final boolean update;

        Download(PackManifest.PackFile file, boolean update) {
            this.file = file;
            this.update = update;
        }
    }

    public static final class Plan {
        public final List<Download> downloads = new ArrayList<>();
        /** Relative paths to delete (possibly with the {@code .disabled} suffix). */
        public final List<String> deletes = new ArrayList<>();
        /** Pack files left as the player has them. */
        public final List<String> kept = new ArrayList<>();
        public final PackState nextState = new PackState();
    }

    public static Plan plan(PackManifest manifest, PackState previous, FileProbe probe) throws IOException {
        PackState prev = previous == null ? new PackState() : previous;
        Plan plan = new Plan();
        plan.nextState.packId = manifest.projects.packId == null ? "" : manifest.projects.packId;
        plan.nextState.versionId = manifest.versionId;

        // Group choices: what the player chose last time, else the group's default.
        Map<String, Boolean> groups = plan.nextState.groups;
        Map<String, List<String>> membership = new HashMap<>();
        for (PackManifest.OptionalGroup group : manifest.projects.optionalGroups) {
            Boolean chosen = prev.groups.get(group.id);
            groups.put(group.id, chosen != null ? chosen : group.defaultEnabled);
            for (String path : group.files) {
                List<String> ids = membership.get(path);
                if (ids == null) membership.put(path, ids = new ArrayList<>());
                ids.add(group.id);
            }
        }

        Set<String> desiredPaths = new HashSet<>();
        for (PackManifest.PackFile file : manifest.files) {
            if ("unsupported".equals(file.clientSupport())) continue;
            List<String> ids = membership.get(file.path);
            if (ids != null) {
                boolean enabled = false;
                for (String id : ids) enabled |= Boolean.TRUE.equals(groups.get(id));
                if (!enabled) continue;
            }
            desiredPaths.add(file.path);
            decide(plan, file, manifest.policyFor(file.path), prev.files.get(file.path), probe);
        }

        for (Map.Entry<String, PackState.InstalledFile> entry : prev.files.entrySet()) {
            String path = entry.getKey();
            if (desiredPaths.contains(path)) continue;
            // The state file is local and could be edited: never touch unsafe paths.
            try {
                ContentPaths.checkRelativePath(path);
            } catch (ContentException e) {
                continue;
            }
            String installed = entry.getValue() == null ? null : Hashes.normalizeHex(entry.getValue().sha1, 40);
            if (installed == null) continue;
            if (probe.isFile(path)) {
                if (installed.equals(probe.sha1(path))) plan.deletes.add(path);
                else plan.kept.add(path);
            } else if (probe.isFile(path + DISABLED_SUFFIX) && installed.equals(probe.sha1(path + DISABLED_SUFFIX))) {
                plan.deletes.add(path + DISABLED_SUFFIX);
            }
        }
        return plan;
    }

    private static void decide(Plan plan, PackManifest.PackFile file, String policy,
                               PackState.InstalledFile previous, FileProbe probe) throws IOException {
        String expected = file.sha1();
        PackState.InstalledFile record = new PackState.InstalledFile(expected, policy);
        if (!probe.isFile(file.path)) {
            if (probe.isFile(file.path + DISABLED_SUFFIX)) {
                keep(plan, file, previous);
            } else {
                plan.downloads.add(new Download(file, false));
                plan.nextState.files.put(file.path, record);
            }
            return;
        }
        String current = probe.sha1(file.path);
        if (expected.equals(current)) {
            plan.nextState.files.put(file.path, record);
            return;
        }
        boolean replace;
        if (PackManifest.POLICY_OVERWRITE.equals(policy)) {
            replace = true;
        } else if (PackManifest.POLICY_IF_MISSING.equals(policy)) {
            replace = false;
        } else {
            // preserveUserChanges: only while it is still the copy we installed.
            replace = previous != null && current != null
                    && current.equals(Hashes.normalizeHex(previous.sha1, 40));
        }
        if (replace) {
            plan.downloads.add(new Download(file, true));
            plan.nextState.files.put(file.path, record);
        } else {
            keep(plan, file, previous);
        }
    }

    private static void keep(Plan plan, PackManifest.PackFile file, PackState.InstalledFile previous) {
        plan.kept.add(file.path);
        if (previous != null) plan.nextState.files.put(file.path, previous);
    }

    /** A {@link FileProbe} over a real directory. */
    public static FileProbe directoryProbe(final File root) {
        return new FileProbe() {
            @Override
            public boolean isFile(String relativePath) {
                return new File(root, relativePath).isFile();
            }

            @Override
            public String sha1(String relativePath) throws IOException {
                return Hashes.fileHex(new File(root, relativePath), "SHA-1");
            }
        };
    }
}
