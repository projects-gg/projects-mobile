package gg.projects.mobile.content;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Plans how a game directory follows a pack manifest: a port of the desktop's plan_sync
 * (pl-content sync.rs, "Sync semantics and pack state" in content-format.md). Nothing on disk
 * changes here; {@link PackSyncApplier} applies a plan.
 *
 * <ul>
 * <li>only files the pack installed earlier and that are no longer wanted are deleted, and only
 * while they still have the hash we installed (files the player added are never touched);</li>
 * <li>{@code overwrite} files end up matching the pack, except jars in {@code mods/}: a jar the
 * player replaced is kept (the mod screen offers to bring the pack's file back);
 * {@code ifMissing} files are installed only when absent; {@code preserveUserChanges} files are
 * updated only while unchanged since we installed them;</li>
 * <li>items are installed while effectively enabled ({@link ItemResolver}); changes the player
 * made to item files are adopted, never fought: a deleted or disabled file switches its item off,
 * a disabled copy renamed back switches it on; a wiped mods folder installs the selection
 * again;</li>
 * <li>any other pack file the player disabled ({@code <path>.disabled}) stays disabled; optional
 * groups switch the files no item covers; server-only files are skipped;</li>
 * <li>files that cannot be read never stop a sync: they are left alone and reported.</li>
 * </ul>
 */
public final class PackSyncPlanner {
    private PackSyncPlanner() {}

    public static final String DISABLED_SUFFIX = ".disabled";
    /** At least this many pack mods gone at once, and more than half of them: the folder was wiped. */
    static final int RESET_MIN_MISSING = 3;

    /** Disk access, so the planner can be tested without a device. */
    public interface FileProbe {
        /** What is at the relative path. */
        Stat stat(String relativePath);

        /** True when the relative path is an existing folder. */
        boolean isDirectory(String relativePath);

        /** Lowercase SHA-1 of the file at the relative path. */
        String sha1(String relativePath) throws IOException;
    }

    public enum Kind { FILE, OTHER, MISSING, UNREADABLE }

    public static final class Stat {
        public final Kind kind;
        public final long size;
        public final String detail;

        private Stat(Kind kind, long size, String detail) {
            this.kind = kind;
            this.size = size;
            this.detail = detail;
        }

        public static Stat file(long size) {
            return new Stat(Kind.FILE, size, null);
        }

        /** Something that is not a regular file (a folder). */
        public static Stat other() {
            return new Stat(Kind.OTHER, 0, null);
        }

        public static Stat missing() {
            return new Stat(Kind.MISSING, 0, null);
        }

        public static Stat unreadable(String detail) {
            return new Stat(Kind.UNREADABLE, 0, detail);
        }
    }

    /** Knobs for {@link #plan}. */
    public static final class Options {
        /** Make every pack file match the pack again (player-added and disabled files stay). */
        public boolean repair;
        /** Skip hashing files whose size matches and whose recorded hash equals the manifest's. */
        public boolean quickCheck;
        /** Defaults of a fresh set. */
        public ItemResolver.Defaults defaults = ItemResolver.MANIFEST_DEFAULTS;

        public Options quick(boolean quickCheck) {
            this.quickCheck = quickCheck;
            return this;
        }

        public Options defaults(ItemResolver.Defaults defaults) {
            this.defaults = defaults;
            return this;
        }
    }

    public enum Action {
        /** The file does not exist yet. */
        ADD,
        /** An existing file is replaced (or, for an item, its old file by a new one). */
        UPDATE
    }

    public static final class Download {
        public final String path;
        public final List<String> urls;
        public final String sha1;
        /** Lowercase SHA-512, or null. */
        public final String sha512;
        public final long size;
        public Action action;
        public final FileCategory category;
        /** The item the file belongs to, or null. */
        public final String item;

        Download(String path, List<String> urls, String sha1, String sha512, long size, Action action,
                 FileCategory category, String item) {
            this.path = path;
            this.urls = urls;
            this.sha1 = sha1;
            this.sha512 = sha512;
            this.size = size;
            this.action = action;
            this.category = category;
            this.item = item;
        }
    }

    /** An enabled item whose disabled copy is exactly the pack's file: renamed back. */
    public static final class Restore {
        public final String path;
        public final long size;
        public final FileCategory category;
        public final String item;

        Restore(String path, long size, FileCategory category, String item) {
            this.path = path;
            this.size = size;
            this.category = category;
            this.item = item;
        }
    }

    public static final class Delete {
        /** The file to delete (possibly {@code <path>.disabled}). */
        public final String path;
        public final FileCategory category;
        /** Size on disk when planned. */
        public final long size;
        /** The item that installed the file. */
        public final String item;
        /** Superseded by a download of the same item (an update, not a removal). */
        public boolean replaced;
        /** The state record behind the file, put back when the deletion fails. */
        final String recordPath;
        final PackState.InstalledFile record;

        Delete(String path, FileCategory category, long size, String item, boolean replaced,
               String recordPath, PackState.InstalledFile record) {
            this.path = path;
            this.category = category;
            this.size = size;
            this.item = item;
            this.replaced = replaced;
            this.recordPath = recordPath;
            this.record = record;
        }
    }

    public enum KeepReason {
        /** The player changed a file the pack manages (or owns a file at that path). */
        USER_MODIFIED,
        /** {@code ifMissing}: the file exists. */
        EXISTING_FILE,
        /** The player disabled it ({@code <path>.disabled}; files outside the catalog only). */
        USER_DISABLED
    }

    public static final class Kept {
        public final String path;
        public final KeepReason reason;
        public final FileCategory category;

        Kept(String path, KeepReason reason, FileCategory category) {
            this.path = path;
            this.reason = reason;
            this.category = category;
        }
    }

    public enum IssueKind {
        /** The file could not be read. */
        UNREADABLE,
        /** Something other than a file (a folder) is where the pack's file belongs. */
        NOT_A_FILE,
        /** The file could not be renamed or deleted. */
        BUSY
    }

    /** A file the sync could not handle; it stays as it is and the next sync looks again. */
    public static final class Issue {
        public final String path;
        public final IssueKind kind;
        /** Technical details for logs and support. */
        public final String detail;

        public Issue(String path, IssueKind kind, String detail) {
            this.path = path;
            this.kind = kind;
            this.detail = detail;
        }
    }

    /** A manual change a sync adopted (new since the last sync). */
    public static final class Adopted {
        public final String item;
        /** One of the {@code PackState.MANUAL_*} values. */
        public final String change;

        Adopted(String item, String change) {
            this.item = item;
            this.change = change;
        }
    }

    public static final class Counts {
        public int mods;
        public int resourcePacks;
        public int shaderPacks;
        public int configs;
        public int other;

        void add(FileCategory category) {
            switch (category) {
                case MOD: mods++; break;
                case RESOURCE_PACK: resourcePacks++; break;
                case SHADER_PACK: shaderPacks++; break;
                case CONFIG: configs++; break;
                default: other++; break;
            }
        }

        public int total() {
            return mods + resourcePacks + shaderPacks + configs + other;
        }
    }

    /** Dry-run summary for the mod screen's apply bar. */
    public static final class Summary {
        /** Files that appear (downloads of new files and re-enabled items). */
        public final Counts added = new Counts();
        /** Files replaced by a newer version (including an item's new file name). */
        public final Counts updated = new Counts();
        /** Files that disappear (updates are not counted here). */
        public final Counts removed = new Counts();
        public int kept;
        public int unchanged;
        public long downloadBytes;
        /** Bytes deleted from the disk (removals and old files of updated items). */
        public long removeBytes;

        /** True when applying would not change any file. */
        public boolean isNoop() {
            return added.total() + updated.total() + removed.total() == 0;
        }

        /** Turkish one-liner for logs, e.g. "3 mod güncellenecek, 1 mod kaldırılacak". */
        public String describeTr() {
            List<String> parts = new ArrayList<>();
            describe(parts, added, "eklenecek");
            describe(parts, updated, "güncellenecek");
            describe(parts, removed, "kaldırılacak");
            if (kept > 0) parts.add(kept + " değiştirilmiş dosya korunacak");
            if (parts.isEmpty()) return "Paket güncel";
            StringBuilder out = new StringBuilder();
            for (String part : parts) {
                if (out.length() > 0) out.append(", ");
                out.append(part);
            }
            return out.toString();
        }

        private static void describe(List<String> parts, Counts counts, String verb) {
            int[] values = {counts.mods, counts.resourcePacks, counts.shaderPacks, counts.configs, counts.other};
            String[] nouns = {"mod", "kaynak paketi", "shader paketi", "yapılandırma dosyası", "dosya"};
            for (int i = 0; i < values.length; i++) if (values[i] > 0) parts.add(values[i] + " " + nouns[i] + " " + verb);
        }
    }

    /** The difference between a manifest and a game directory. */
    public static final class Plan {
        public String packId = "";
        public String versionId = "";
        public final List<Download> downloads = new ArrayList<>();
        /** Disabled copies of enabled items renamed back to their path. */
        public final List<Restore> restores = new ArrayList<>();
        public final List<Delete> deletes = new ArrayList<>();
        public final List<String> unchanged = new ArrayList<>();
        public final List<Kept> kept = new ArrayList<>();
        /** The mod screen's view of the manifest's items under this plan's selection. */
        public List<ItemResolver.ItemStatus> items = new ArrayList<>();
        /** Manual changes this plan found that earlier syncs had not seen. */
        public List<Adopted> adopted = new ArrayList<>();
        /** The mods folder looked wiped: the selection is installed again. */
        public boolean reset;
        public final List<Issue> issues = new ArrayList<>();
        /** State written after a successful apply. */
        public final PackState nextState = new PackState();
        /** {@link #nextState} differs from the state the plan started from. */
        public boolean stateChanged;

        public Summary summary() {
            Summary summary = new Summary();
            summary.kept = kept.size();
            summary.unchanged = unchanged.size();
            for (Download download : downloads) {
                summary.downloadBytes += download.size;
                if (download.action == Action.ADD) summary.added.add(download.category);
                else summary.updated.add(download.category);
            }
            for (Restore restore : restores) summary.added.add(restore.category);
            for (Delete delete : deletes) {
                summary.removeBytes += delete.size;
                if (!delete.replaced) summary.removed.add(delete.category);
            }
            return summary;
        }

        /** True when the plan downloads, renames or deletes something. */
        public boolean changesFiles() {
            return !(downloads.isEmpty() && restores.isEmpty() && deletes.isEmpty());
        }

        /** True when applying would change neither files nor the pack state. */
        public boolean isNoop() {
            return !changesFiles() && !stateChanged;
        }

        public ItemResolver.ItemStatus status(String id) {
            for (ItemResolver.ItemStatus status : items) if (status.id.equals(id)) return status;
            return null;
        }
    }

    /** What became of a file the last sync installed. */
    enum Seen { PRESENT, DISABLED, MISSING, UNKNOWN }

    private static final class Adoption {
        final Map<String, Boolean> choices = new TreeMap<>();
        final List<Adopted> changes = new ArrayList<>();
    }

    private static final class Desired {
        final PackManifest.PackFile file;
        final String policy;
        final String item;

        Desired(PackManifest.PackFile file, String policy, String item) {
            this.file = file;
            this.policy = policy;
            this.item = item;
        }
    }

    /**
     * Computes what a sync would do. {@code previous} is the state of the last sync;
     * {@code selection} carries the mod screen's choices (missing entries keep the recorded or
     * adopted choice), null for syncs before a launch.
     */
    public static Plan plan(PackManifest manifest, FileProbe probe, PackState previous, PackSelection selection,
                            Options options) throws ContentException {
        manifest.validate();
        Options opts = options != null ? options : new Options();
        PackManifest.Projects ext = manifest.projects;
        PackState prev = previous != null ? previous : new PackState();

        // Manual changes: what became of the files the last sync installed.
        Map<String, Seen> disk = observeRecords(probe, ext, prev);
        boolean reset = looksWiped(probe, prev, disk);
        Adoption adoption = adopt(ext, prev, selection, disk, reset);

        ItemResolver.Resolution resolution = ItemResolver.resolve(manifest, previous, selection, adoption.choices, opts.defaults);
        Set<String> enabledItems = new HashSet<>();
        for (ItemResolver.ItemStatus status : resolution.items) if (status.effectivelyEnabled) enabledItems.add(status.id);

        // Libraries are not choices: one the player removed comes back because something needs it.
        for (PackManifest.PackItem item : ext.items) {
            if (!item.library || !enabledItems.contains(item.id)) continue;
            Map.Entry<String, PackState.InstalledFile> record = ItemResolver.installedRecord(prev, item);
            if (record == null || record.getValue().disabled) continue;
            Seen seen = disk.get(record.getKey());
            if (seen == Seen.DISABLED) adoption.changes.add(new Adopted(item.id, PackState.MANUAL_DISABLED));
            else if (seen == Seen.MISSING && !reset) adoption.changes.add(new Adopted(item.id, PackState.MANUAL_REMOVED));
        }

        Map<String, Boolean> groups = groupChoices(ext, previous, selection);
        List<Desired> desired = desiredFiles(manifest, enabledItems, groups);
        Set<String> desiredPaths = new HashSet<>();
        for (Desired d : desired) desiredPaths.add(d.file.path);

        Plan plan = new Plan();
        plan.packId = ext.packId == null ? "" : ext.packId;
        plan.versionId = manifest.versionId;
        PackState next = plan.nextState;
        next.packId = plan.packId;
        next.versionId = manifest.versionId;
        next.family = ext.familyId();
        next.minecraft = manifest.minecraftVersion();
        next.groups.putAll(groups);
        next.items.putAll(resolution.choices);
        next.seenItems.addAll(resolution.seen);

        Set<String> modifiedItems = new HashSet<>();
        for (Desired d : desired) {
            String path = d.file.path;
            FileCategory category = FileCategory.of(path);
            boolean reverted = d.item != null && selection != null && selection.revert.contains(d.item);
            PackState.InstalledFile known = prev.files.get(path);
            Decision decision = inspectDesired(probe, d.file, d.policy, known, d.item, opts.repair || reverted, opts.quickCheck);
            switch (decision.type) {
                case UNCHANGED:
                    plan.unchanged.add(path);
                    next.files.put(path, decision.record);
                    break;
                case DOWNLOAD:
                    plan.downloads.add(new Download(path, d.file.downloads, decision.record.sha1, d.file.sha512(),
                            d.file.fileSize, decision.action, category, d.item));
                    if (decision.stalePath != null) {
                        plan.deletes.add(new Delete(decision.stalePath, category, decision.staleSize, d.item, true, null, null));
                    }
                    next.files.put(path, decision.record);
                    break;
                case RESTORE:
                    plan.restores.add(new Restore(path, d.file.fileSize, category, d.item));
                    next.files.put(path, decision.record);
                    break;
                case KEEP:
                    plan.kept.add(new Kept(path, decision.reason, category));
                    if (decision.reason == KeepReason.USER_MODIFIED && d.item != null) {
                        // Tell the player once, when the replaced file first shows up.
                        if (known != null && !known.modified) adoption.changes.add(new Adopted(d.item, PackState.MANUAL_REPLACED));
                        modifiedItems.add(d.item);
                    }
                    if (decision.record != null) next.files.put(path, decision.record);
                    break;
                default:
                    plan.issues.add(new Issue(path, decision.issue, decision.detail));
                    if (known != null) next.files.put(path, known.copy());
                    break;
            }
        }

        // Records of files no longer wanted. An item that ends up off keeps the disabled copy the
        // player made of it; everything else is deleted while unchanged.
        Set<String> keptDisabled = new HashSet<>();
        for (Map.Entry<String, PackState.InstalledFile> entry : prev.files.entrySet()) {
            String path = entry.getKey();
            PackState.InstalledFile record = entry.getValue();
            if (desiredPaths.contains(path)) continue;
            String id = recordItem(ext, path, record);
            if (id != null && !enabledItems.contains(id) && disk.get(path) == Seen.DISABLED) {
                PackState.InstalledFile disabled = record.copy();
                disabled.disabled = true;
                next.files.put(path, disabled);
                keptDisabled.add(id);
                continue;
            }
            Removal removal = inspectRemoved(probe, path, record);
            switch (removal.type) {
                case DELETE:
                    plan.deletes.add(new Delete(removal.path, FileCategory.of(path), removal.size, record.item, false, path, record.copy()));
                    break;
                case KEEP:
                    plan.kept.add(new Kept(path, KeepReason.USER_MODIFIED, FileCategory.of(path)));
                    break;
                case ISSUE:
                    plan.issues.add(new Issue(path, removal.issue, removal.detail));
                    next.files.put(path, record.copy());
                    break;
                default:
                    break;
            }
        }

        // An enabled item whose file name changed: deleting its old file completes an update.
        for (Delete delete : plan.deletes) {
            if (delete.replaced || delete.item == null) continue;
            for (Download download : plan.downloads) {
                if (download.action == Action.ADD && delete.item.equals(download.item)) {
                    download.action = Action.UPDATE;
                    delete.replaced = true;
                    break;
                }
            }
        }

        // Adopted changes stay visible in the mod screen until the player confirms it.
        Map<String, String> manual = selection != null ? new TreeMap<String, String>() : new TreeMap<>(prev.manual);
        for (Adopted change : adoption.changes) {
            if (!PackState.MANUAL_REPLACED.equals(change.change)) manual.put(change.item, change.change);
        }
        Iterator<String> ids = manual.keySet().iterator();
        while (ids.hasNext()) if (ext.item(ids.next()) == null) ids.remove();
        next.manual = manual;
        for (ItemResolver.ItemStatus status : resolution.items) {
            status.manual = manual.get(status.id);
            status.modified = modifiedItems.contains(status.id);
            status.disabledCopy = keptDisabled.contains(status.id);
        }
        plan.items = resolution.items;
        plan.adopted = adoption.changes;
        plan.reset = reset;
        plan.stateChanged = previous == null || !previous.sameAs(next);
        return plan;
    }

    /** Group choices: the selection, else the recorded choice, else the group's default. */
    private static Map<String, Boolean> groupChoices(PackManifest.Projects ext, PackState previous, PackSelection selection) {
        Map<String, Boolean> groups = new TreeMap<>();
        for (PackManifest.OptionalGroup group : ext.optionalGroups) {
            Boolean enabled = selection != null ? selection.groups.get(group.id) : null;
            if (enabled == null && previous != null) enabled = previous.groups.get(group.id);
            groups.put(group.id, enabled != null ? enabled : group.defaultEnabled);
        }
        return groups;
    }

    /**
     * The files the client should have, with their policy and item: items follow the selection
     * (groups do not apply to them), other files their groups; server-only files are skipped.
     */
    private static List<Desired> desiredFiles(PackManifest manifest, Set<String> enabledItems, Map<String, Boolean> groups) {
        Map<String, PackManifest.PackItem> itemByPath = new HashMap<>();
        for (PackManifest.PackItem item : manifest.projects.items) itemByPath.put(item.path, item);
        Map<String, List<String>> membership = new HashMap<>();
        for (PackManifest.OptionalGroup group : manifest.projects.optionalGroups) {
            for (String path : group.files) {
                List<String> ids = membership.get(path);
                if (ids == null) membership.put(path, ids = new ArrayList<>());
                ids.add(group.id);
            }
        }
        List<Desired> desired = new ArrayList<>();
        for (PackManifest.PackFile file : manifest.files) {
            if ("unsupported".equals(file.clientSupport())) continue;
            PackManifest.PackItem item = itemByPath.get(file.path);
            if (item != null) {
                if (enabledItems.contains(item.id)) desired.add(new Desired(file, manifest.policyFor(file.path), item.id));
                continue;
            }
            List<String> ids = membership.get(file.path);
            boolean wanted = ids == null;
            if (ids != null) for (String id : ids) wanted |= Boolean.TRUE.equals(groups.get(id));
            if (wanted) desired.add(new Desired(file, manifest.policyFor(file.path), null));
        }
        return desired;
    }

    /** The manifest item a record belongs to: its tag, or for older records, the item at its path. */
    private static String recordItem(PackManifest.Projects ext, String path, PackState.InstalledFile record) {
        PackManifest.PackItem item = record.item != null ? ext.item(record.item) : ext.itemForPath(path);
        return item == null ? null : item.id;
    }

    /** Looks at the recorded files manual changes can concern: item files and everything under mods/. */
    private static Map<String, Seen> observeRecords(FileProbe probe, PackManifest.Projects ext, PackState prev) {
        Map<String, Seen> disk = new LinkedHashMap<>();
        for (Map.Entry<String, PackState.InstalledFile> entry : prev.files.entrySet()) {
            String path = entry.getKey();
            if (FileCategory.of(path) == FileCategory.MOD || recordItem(ext, path, entry.getValue()) != null) {
                disk.put(path, observe(probe, path));
            }
        }
        return disk;
    }

    private static Seen observe(FileProbe probe, String path) {
        try {
            ContentPaths.checkRelativePath(path);
        } catch (ContentException e) {
            return Seen.UNKNOWN;
        }
        Stat stat = probe.stat(path);
        if (stat.kind == Kind.FILE) return Seen.PRESENT;
        if (stat.kind != Kind.MISSING) return Seen.UNKNOWN;
        Stat disabled = probe.stat(path + DISABLED_SUFFIX);
        if (disabled.kind == Kind.FILE) return Seen.DISABLED;
        return disabled.kind == Kind.MISSING ? Seen.MISSING : Seen.UNKNOWN;
    }

    /** The mods folder looks wiped rather than edited: gone, or most pack mods missing at once. */
    private static boolean looksWiped(FileProbe probe, PackState prev, Map<String, Seen> disk) {
        int mods = 0;
        int missing = 0;
        for (Map.Entry<String, PackState.InstalledFile> entry : prev.files.entrySet()) {
            if (FileCategory.of(entry.getKey()) != FileCategory.MOD || entry.getValue().disabled) continue;
            mods++;
            if (disk.get(entry.getKey()) == Seen.MISSING) missing++;
        }
        if (missing == 0) return false;
        return !probe.isDirectory("mods") || (missing >= RESET_MIN_MISSING && missing * 2 > mods);
    }

    /**
     * Item choices the disk shows: files the last sync installed that the player deleted or
     * disabled switch their item off, a kept disabled copy renamed back switches it on. Items the
     * selection names are the player's explicit choice and are skipped.
     */
    private static Adoption adopt(PackManifest.Projects ext, PackState prev, PackSelection selection,
                                  Map<String, Seen> disk, boolean reset) {
        Adoption adoption = new Adoption();
        for (PackManifest.PackItem item : ext.items) {
            if (item.library) continue;
            if (selection != null && selection.items.containsKey(item.id)) continue;
            Map.Entry<String, PackState.InstalledFile> record = ItemResolver.installedRecord(prev, item);
            if (record == null) continue;
            Seen seen = disk.get(record.getKey());
            if (seen == null) seen = Seen.UNKNOWN;
            boolean disabled = record.getValue().disabled;
            if (!disabled && seen == Seen.DISABLED) adopt(adoption, item.id, false, PackState.MANUAL_DISABLED);
            else if (!disabled && seen == Seen.MISSING && !reset) adopt(adoption, item.id, false, PackState.MANUAL_REMOVED);
            else if (disabled && seen == Seen.PRESENT) adopt(adoption, item.id, true, PackState.MANUAL_ENABLED);
        }
        return adoption;
    }

    private static void adopt(Adoption adoption, String id, boolean selected, String change) {
        adoption.choices.put(id, selected);
        adoption.changes.add(new Adopted(id, change));
    }

    private enum DecisionType { UNCHANGED, DOWNLOAD, RESTORE, KEEP, ISSUE }

    private static final class Decision {
        DecisionType type;
        PackState.InstalledFile record;
        Action action;
        String stalePath;
        long staleSize;
        KeepReason reason;
        IssueKind issue;
        String detail;

        static Decision of(DecisionType type, PackState.InstalledFile record) {
            Decision decision = new Decision();
            decision.type = type;
            decision.record = record;
            return decision;
        }

        static Decision download(Action action, PackState.InstalledFile record) {
            Decision decision = of(DecisionType.DOWNLOAD, record);
            decision.action = action;
            return decision;
        }

        static Decision keep(KeepReason reason, PackState.InstalledFile record) {
            Decision decision = of(DecisionType.KEEP, record);
            decision.reason = reason;
            return decision;
        }

        static Decision issue(IssueKind kind, String detail) {
            Decision decision = of(DecisionType.ISSUE, null);
            decision.issue = kind;
            decision.detail = detail;
            return decision;
        }
    }

    private static Decision inspectDesired(FileProbe probe, PackManifest.PackFile file, String policy,
                                           PackState.InstalledFile previous, String item, boolean repair, boolean quickCheck) {
        String expected = file.sha1();
        PackState.InstalledFile record = new PackState.InstalledFile(expected, policy, item);
        Stat stat = probe.stat(file.path);
        if (stat.kind == Kind.UNREADABLE) return Decision.issue(IssueKind.UNREADABLE, stat.detail);
        if (stat.kind == Kind.MISSING) {
            String disabledPath = file.path + DISABLED_SUFFIX;
            Stat disabled = probe.stat(disabledPath);
            if (disabled.kind != Kind.FILE) return Decision.download(Action.ADD, record);
            if (item == null) return Decision.keep(KeepReason.USER_DISABLED, previous == null ? null : previous.copy());
            // An enabled item whose file sits disabled (selected again, or required) comes back on.
            String current;
            try {
                current = probe.sha1(disabledPath);
            } catch (IOException e) {
                return Decision.issue(IssueKind.UNREADABLE, String.valueOf(e));
            }
            if (expected.equals(current)) return Decision.of(DecisionType.RESTORE, record);
            Decision download = Decision.download(Action.ADD, record);
            if (previous != null && previous.sha1 != null && previous.sha1.equalsIgnoreCase(current)) {
                download.stalePath = disabledPath;
                download.staleSize = disabled.size;
            }
            return download;
        }
        if (stat.kind == Kind.OTHER) return Decision.issue(IssueKind.NOT_A_FILE, file.path + " is not a file");

        if (quickCheck && !repair && stat.size == file.fileSize && previous != null && !previous.modified
                && previous.sha1 != null && previous.sha1.equalsIgnoreCase(expected)) {
            return Decision.of(DecisionType.UNCHANGED, record);
        }
        String current;
        try {
            current = probe.sha1(file.path);
        } catch (IOException e) {
            return Decision.issue(IssueKind.UNREADABLE, String.valueOf(e));
        }
        if (expected.equals(current)) return Decision.of(DecisionType.UNCHANGED, record);
        // A jar the player replaced is theirs: overwrite acts like preserveUserChanges there.
        String effective;
        if (repair) effective = PackManifest.POLICY_OVERWRITE;
        else if (PackManifest.POLICY_OVERWRITE.equals(policy) && FileCategory.of(file.path) == FileCategory.MOD) effective = PackManifest.POLICY_PRESERVE;
        else effective = policy;
        if (PackManifest.POLICY_OVERWRITE.equals(effective)) return Decision.download(Action.UPDATE, record);
        if (PackManifest.POLICY_IF_MISSING.equals(effective)) {
            return Decision.keep(KeepReason.EXISTING_FILE, previous == null ? null : previous.copy());
        }
        // preserveUserChanges: only while it is still the copy we installed.
        if (previous != null && previous.sha1 != null && previous.sha1.equalsIgnoreCase(current)) {
            return Decision.download(Action.UPDATE, record);
        }
        PackState.InstalledFile modified = previous == null ? null : previous.copy();
        if (modified != null) modified.modified = true;
        return Decision.keep(KeepReason.USER_MODIFIED, modified);
    }

    private enum RemovalType { DELETE, KEEP, GONE, ISSUE }

    private static final class Removal {
        RemovalType type;
        String path;
        long size;
        IssueKind issue;
        String detail;

        static Removal of(RemovalType type) {
            Removal removal = new Removal();
            removal.type = type;
            return removal;
        }

        static Removal delete(String path, long size) {
            Removal removal = of(RemovalType.DELETE);
            removal.path = path;
            removal.size = size;
            return removal;
        }

        static Removal issue(String detail) {
            Removal removal = of(RemovalType.ISSUE);
            removal.issue = IssueKind.UNREADABLE;
            removal.detail = detail;
            return removal;
        }
    }

    private static Removal inspectRemoved(FileProbe probe, String path, PackState.InstalledFile record) {
        // The state file is local and could be edited: never touch unsafe paths.
        try {
            ContentPaths.checkRelativePath(path);
        } catch (ContentException e) {
            return Removal.of(RemovalType.GONE);
        }
        String installed = record.sha1 == null ? "" : record.sha1.toLowerCase(Locale.ROOT);
        Stat target = probe.stat(path);
        if (target.kind == Kind.FILE) {
            try {
                return installed.equals(probe.sha1(path)) ? Removal.delete(path, target.size) : Removal.of(RemovalType.KEEP);
            } catch (IOException e) {
                return Removal.issue(String.valueOf(e));
            }
        }
        String disabledPath = path + DISABLED_SUFFIX;
        Stat disabled = probe.stat(disabledPath);
        if (disabled.kind == Kind.FILE) {
            try {
                return installed.equals(probe.sha1(disabledPath)) ? Removal.delete(disabledPath, disabled.size) : Removal.of(RemovalType.GONE);
            } catch (IOException e) {
                return Removal.issue(String.valueOf(e));
            }
        }
        return Removal.of(RemovalType.GONE);
    }

    /** A {@link FileProbe} over a real directory. */
    public static FileProbe directoryProbe(final File root) {
        return new FileProbe() {
            @Override
            public Stat stat(String relativePath) {
                File file = new File(root, relativePath);
                try {
                    if (file.isFile()) return Stat.file(file.length());
                    if (file.exists()) return Stat.other();
                    return Stat.missing();
                } catch (SecurityException e) {
                    return Stat.unreadable(String.valueOf(e));
                }
            }

            @Override
            public boolean isDirectory(String relativePath) {
                return new File(root, relativePath).isDirectory();
            }

            @Override
            public String sha1(String relativePath) throws IOException {
                return Hashes.fileHex(new File(root, relativePath), "SHA-1");
            }
        };
    }
}
