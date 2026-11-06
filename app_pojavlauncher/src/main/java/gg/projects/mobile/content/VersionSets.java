package gg.projects.mobile.content;

import androidx.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * M053: one set of mods and settings per Minecraft version in the Projects game folder, the desktop's
 * {@code pl_content::sets} (content-format.md "Per-version sets"): switching versions parks the active {@code mods/} and
 * {@code config/} in {@code .projects/versions/<minecraft>/} with their pack state, then brings the target's parked set
 * back, or on a first visit copies {@code config/} and starts an empty {@code mods/} (the next sync installs the
 * target's mods with the choices carried over). Nothing is deleted: switching back returns the earlier set exactly,
 * which is the switch's "Geri al". A journal ({@code .projects/version-switch.json}) lets a switch the phone
 * interrupted finish on the next one ({@link #resume}). Plain {@code java.io}; call it off the main thread.
 *
 * <p>Differences from the desktop: no ready-made mod sets and no prepared sets (the phone has neither); on a first visit
 * the active state names the target member's pack id (the desktop leaves it empty until the sync), so every phone
 * screen that reads the state follows the new member at once.
 */
public final class VersionSets {
    public static final String SETS_DIR = "versions";
    public static final String SET_FILE = "set.json";
    public static final String JOURNAL = "version-switch.json";
    static final String[] PARKED_DIRS = {"mods", "config"};
    static final String[] COPIED_DIRS = {"config"};
    private static final String COPY_SUFFIX = ".switch-copy";
    private static final long MAX_JSON = 16L << 20;

    private final File gameDir;

    public VersionSets(File gameDir) {
        this.gameDir = gameDir;
    }

    private File launcherDir() {
        return new File(gameDir, ContentPaths.LAUNCHER_DIR);
    }

    File setDir(String minecraft) {
        return new File(new File(launcherDir(), SETS_DIR), folderName(minecraft));
    }

    private File journalFile() {
        return new File(launcherDir(), JOURNAL);
    }

    /** One parked set: its Minecraft version, the pack it followed and its size when parked. */
    public static final class Parked {
        public final String minecraft;
        public final String packId;
        public final long sizeBytes;

        Parked(String minecraft, String packId, long sizeBytes) {
            this.minecraft = minecraft;
            this.packId = packId;
            this.sizeBytes = sizeBytes;
        }
    }

    /** The parked sets (each folder with a {@code set.json}). */
    public List<Parked> parked() {
        List<Parked> out = new ArrayList<>();
        File[] dirs = new File(launcherDir(), SETS_DIR).listFiles();
        if (dirs == null) return out;
        for (File dir : dirs) {
            JsonObject info = readJson(new File(dir, SET_FILE));
            if (info == null) continue;
            String minecraft = string(info, "minecraft");
            if (minecraft.isEmpty()) continue;
            out.add(new Parked(minecraft, string(info, "packId"), number(info, "sizeBytes")));
        }
        return out;
    }

    @Nullable
    public Parked parked(String minecraft) {
        JsonObject info = readJson(new File(setDir(minecraft), SET_FILE));
        if (info == null) return null;
        return new Parked(minecraft, string(info, "packId"), number(info, "sizeBytes"));
    }

    /** A switch the phone did not finish, or null. */
    @Nullable
    public Journal pending() {
        JsonObject o = readJson(journalFile());
        if (o == null) {
            if (journalFile().isFile()) setAsideJournal();
            return null;
        }
        try {
            return Journal.from(o);
        } catch (RuntimeException | ContentException e) {
            setAsideJournal();
            return null;
        }
    }

    /** Finishes an interrupted switch (a no-op without one). Returns it, or null. */
    @Nullable
    public Journal resume() throws IOException {
        Journal journal = pending();
        if (journal == null) return null;
        run(journal);
        return journal;
    }

    /**
     * Switches the active set from {@code from} to {@code to}.
     *
     * @param toPackId the pack id of the family member for {@code to} (written into a first visit's state)
     */
    public Journal switchTo(String from, String to, String toPackId, long nowMs) throws IOException {
        if (pending() != null) resume();
        from = from == null ? "" : from.trim();
        to = to == null ? "" : to.trim();
        if (from.isEmpty() || to.isEmpty()) throw new IOException("a version switch needs both versions");
        if (from.equals(to) || folderName(from).equals(folderName(to))) throw new IOException(to + " is already active");

        Parked target = parked(to);
        boolean returning = target != null;
        PackState returningState = returning ? loadState(new File(setDir(to), PackState.FILE)) : null;
        PackState active = PackState.load(gameDir);
        PackState[] split = split(active, from, to, returning, returningState, toPackId);

        // A leftover parked folder of the version being left would collide: it is set aside, never deleted.
        File parkedDir = setDir(from);
        for (String dir : PARKED_DIRS) {
            File leftover = new File(parkedDir, dir);
            if (leftover.exists()) moveAside(leftover, parkedDir, dir, nowMs);
        }
        String fromPack = active != null && !active.packId.isEmpty() ? active.packId
                : string(readJson(new File(launcherDir(), SET_FILE)), "packId");
        Journal journal = new Journal(from, to, Step.PARK, !returning, split[0], split[1], fromPack,
                returning ? target.packId : toPackId, nowMs);
        saveJournal(journal);
        run(journal);
        return journal;
    }

    /** The pack states after a switch: {parked, active} (either may be null). */
    static PackState[] split(@Nullable PackState active, String from, String to, boolean returning,
                             @Nullable PackState returningState, String toPackId) {
        if (active == null) {
            if (returningState == null) return new PackState[]{null, null};
            PackState back = returningState.copy();
            back.minecraft = to;
            return new PackState[]{null, back};
        }
        if (returningState == null && returning) {
            // Returning to a set whose state is missing still carries the choices (no folder records).
            returningState = new PackState();
            returningState.family = active.family;
            returningState.groups.putAll(active.groups);
            returningState.items.putAll(active.items);
            returningState.seenItems.addAll(active.seenItems);
        }
        PackState.VersionSwitch result = active.switchVersion(from, to, returningState);
        if (returningState == null && result.active.packId.isEmpty() && toPackId != null) {
            result.active.packId = toPackId;
        }
        return new PackState[]{result.parked, result.active};
    }

    private void run(Journal journal) throws IOException {
        while (true) {
            switch (journal.step) {
                case PARK:
                    park(journal);
                    journal.step = Step.RESTORE;
                    break;
                case RESTORE:
                    restore(journal);
                    journal.step = Step.STATES;
                    break;
                case STATES:
                    writeStates(journal);
                    journal.step = Step.DONE;
                    break;
                case DONE:
                default:
                    //noinspection ResultOfMethodCallIgnored
                    journalFile().delete();
                    return;
            }
            saveJournal(journal);
        }
    }

    /** Moves the active folders into the leaving set's folder. Repeatable. */
    private void park(Journal journal) throws IOException {
        File parked = setDir(journal.from);
        mkdirs(parked);
        for (String dir : PARKED_DIRS) {
            File active = new File(gameDir, dir);
            File destination = new File(parked, dir);
            if (active.exists() && !destination.exists()) rename(active, destination);
        }
    }

    /** Brings the target's folders in, or copies {@code config/} and starts {@code mods/} on a first visit. Repeatable. */
    private void restore(Journal journal) throws IOException {
        File target = setDir(journal.to);
        File parked = setDir(journal.from);
        for (String dir : PARKED_DIRS) {
            File active = new File(gameDir, dir);
            boolean copied = journal.firstVisit && contains(COPIED_DIRS, dir);
            File incoming = new File(target, dir);
            if (!copied && incoming.exists()) {
                if (active.exists()) moveAside(active, parked, dir, System.currentTimeMillis());
                rename(incoming, active);
            } else if (copied) {
                File source = new File(parked, dir);
                if (!active.exists() && source.exists()) {
                    File staging = new File(gameDir, dir + COPY_SUFFIX);
                    if (staging.exists()) deleteTree(staging);
                    copyTree(source, staging);
                    rename(staging, active);
                }
            } else if ("mods".equals(dir) && !active.exists()) {
                mkdirs(active);
            }
        }
    }

    /** Writes both pack states and the sets' metadata, then removes the target's parked leftovers. Repeatable. */
    private void writeStates(Journal journal) throws IOException {
        File parked = setDir(journal.from);
        mkdirs(parked);
        File parkedState = new File(parked, PackState.FILE);
        if (journal.parked != null) AtomicFiles.write(parkedState, journal.parked.toJson().getBytes(StandardCharsets.UTF_8));
        else //noinspection ResultOfMethodCallIgnored
            parkedState.delete();
        long size = 0;
        for (String dir : PARKED_DIRS) size += treeSize(new File(parked, dir));
        JsonObject parkedInfo = new JsonObject();
        parkedInfo.addProperty("minecraft", journal.from);
        if (journal.fromPack != null && !journal.fromPack.isEmpty()) parkedInfo.addProperty("packId", journal.fromPack);
        parkedInfo.addProperty("parkedAt", timestamp(journal.startedAt));
        parkedInfo.addProperty("sizeBytes", size);
        AtomicFiles.write(new File(parked, SET_FILE), parkedInfo.toString().getBytes(StandardCharsets.UTF_8));

        if (journal.active != null) journal.active.save(gameDir);
        else //noinspection ResultOfMethodCallIgnored
            PackState.path(gameDir).delete();
        JsonObject activeInfo = new JsonObject();
        activeInfo.addProperty("minecraft", journal.to);
        if (journal.toPack != null && !journal.toPack.isEmpty()) activeInfo.addProperty("packId", journal.toPack);
        activeInfo.addProperty("sizeBytes", 0);
        AtomicFiles.write(new File(launcherDir(), SET_FILE), activeInfo.toString().getBytes(StandardCharsets.UTF_8));

        File target = setDir(journal.to);
        //noinspection ResultOfMethodCallIgnored
        new File(target, PackState.FILE).delete();
        //noinspection ResultOfMethodCallIgnored
        new File(target, SET_FILE).delete();
        // Only an empty folder goes; anything left in it stays visible.
        //noinspection ResultOfMethodCallIgnored
        target.delete();
    }

    private void moveAside(File path, File into, String name, long nowMs) throws IOException {
        long seconds = nowMs / 1000;
        File aside = new File(into, name + ".aside-" + seconds);
        int counter = 1;
        while (aside.exists()) {
            counter++;
            aside = new File(into, name + ".aside-" + seconds + "-" + counter);
        }
        mkdirs(into);
        rename(path, aside);
    }

    private void saveJournal(Journal journal) throws IOException {
        AtomicFiles.write(journalFile(), journal.toJson().toString().getBytes(StandardCharsets.UTF_8));
    }

    private void setAsideJournal() {
        File file = journalFile();
        //noinspection ResultOfMethodCallIgnored
        file.renameTo(new File(launcherDir(), JOURNAL + ".unreadable-" + System.currentTimeMillis()));
    }

    // ---- the journal ----

    public enum Step { PARK, RESTORE, STATES, DONE }

    /** What a switch needs to finish after an interruption. */
    public static final class Journal {
        public final String from;
        public final String to;
        public Step step;
        public final boolean firstVisit;
        @Nullable final PackState parked;
        @Nullable final PackState active;
        @Nullable public final String fromPack;
        @Nullable public final String toPack;
        final long startedAt;

        Journal(String from, String to, Step step, boolean firstVisit, @Nullable PackState parked, @Nullable PackState active,
                @Nullable String fromPack, @Nullable String toPack, long startedAt) {
            this.from = from;
            this.to = to;
            this.step = step;
            this.firstVisit = firstVisit;
            this.parked = parked;
            this.active = active;
            this.fromPack = fromPack;
            this.toPack = toPack;
            this.startedAt = startedAt;
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("from", from);
            o.addProperty("to", to);
            o.addProperty("step", step.name().toLowerCase(Locale.ROOT));
            o.addProperty("firstVisit", firstVisit);
            if (parked != null) o.add("parkedState", JsonParser.parseString(parked.toJson()));
            if (active != null) o.add("activeState", JsonParser.parseString(active.toJson()));
            if (fromPack != null) o.addProperty("fromPack", fromPack);
            if (toPack != null) o.addProperty("toPack", toPack);
            o.addProperty("startedAt", startedAt);
            return o;
        }

        static Journal from(JsonObject o) throws ContentException {
            String from = string(o, "from");
            String to = string(o, "to");
            if (from.isEmpty() || to.isEmpty()) throw new IllegalArgumentException("journal without versions");
            Step step = Step.valueOf(string(o, "step").toUpperCase(Locale.ROOT));
            JsonElement p = o.get("parkedState");
            JsonElement a = o.get("activeState");
            PackState parked = p != null && p.isJsonObject() ? PackState.parse(p.toString().getBytes(StandardCharsets.UTF_8)) : null;
            PackState active = a != null && a.isJsonObject() ? PackState.parse(a.toString().getBytes(StandardCharsets.UTF_8)) : null;
            JsonElement first = o.get("firstVisit");
            return new Journal(from, to, step, first != null && first.isJsonPrimitive() && first.getAsBoolean(), parked, active,
                    o.has("fromPack") ? string(o, "fromPack") : null, o.has("toPack") ? string(o, "toPack") : null,
                    number(o, "startedAt"));
        }
    }

    // ---- helpers ----

    /**
     * {@code text} as one safe folder name (the desktop's {@code folder_name}): characters outside {@code [A-Za-z0-9._-]}
     * become {@code _}; a name the path rules refuse (empty, dots only, trailing dot) is hex with a {@code _} prefix.
     */
    public static String folderName(String text) {
        String trimmed = text == null ? "" : text.trim();
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            name.append(ok ? c : '_');
        }
        String out = name.toString();
        if (out.isEmpty() || out.matches("\\.+") || out.endsWith(".")) {
            StringBuilder hex = new StringBuilder("_");
            for (byte b : (text == null ? "" : text).getBytes(StandardCharsets.UTF_8)) hex.append(String.format(Locale.ROOT, "%02x", b));
            return hex.toString();
        }
        return out;
    }

    @Nullable
    private static PackState loadState(File file) {
        if (!file.isFile()) return null;
        try {
            return PackState.parse(AtomicFiles.read(file, MAX_JSON));
        } catch (IOException | ContentException e) {
            return null;
        }
    }

    @Nullable
    private static JsonObject readJson(File file) {
        if (!file.isFile()) return null;
        try {
            JsonElement root = JsonParser.parseString(new String(AtomicFiles.read(file, MAX_JSON), StandardCharsets.UTF_8));
            return root.isJsonObject() ? root.getAsJsonObject() : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String string(@Nullable JsonObject o, String key) {
        if (o == null) return "";
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    private static long number(JsonObject o, String key) {
        JsonElement e = o.get(key);
        try {
            return e != null && e.isJsonPrimitive() ? e.getAsLong() : 0;
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static boolean contains(String[] list, String value) {
        for (String s : list) if (s.equals(value)) return true;
        return false;
    }

    private static String timestamp(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }

    private static void mkdirs(File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
    }

    private static void rename(File from, File to) throws IOException {
        File parent = to.getParentFile();
        if (parent != null) mkdirs(parent);
        if (!from.renameTo(to)) throw new IOException("cannot move " + from + " to " + to);
    }

    static long treeSize(File file) {
        if (file.isFile()) return file.length();
        long size = 0;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) size += treeSize(child);
        return size;
    }

    private static void copyTree(File from, File to) throws IOException {
        if (from.isDirectory()) {
            mkdirs(to);
            File[] children = from.listFiles();
            if (children != null) for (File child : children) copyTree(child, new File(to, child.getName()));
            return;
        }
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        }
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
