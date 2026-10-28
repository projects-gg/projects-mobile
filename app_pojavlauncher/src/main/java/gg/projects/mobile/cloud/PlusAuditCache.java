package gg.projects.mobile.cloud;

import androidx.annotation.Nullable;

import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;
import java.util.regex.Pattern;

import gg.projects.mobile.social.Json;

/**
 * The audit log entries this device saw ("Akış", services/cloud-afk README "AFK+ player API" → Audit log: "each device
 * keeps what it saw, 7 days"), so the screen still shows them while the account's machine is away. One file of JSON
 * lines per account ({@code <acct>.jsonl}), written whole through a temporary file; entries older than 7 days go, and
 * the file never holds more than {@link #MAX_ENTRIES} entries or {@link #MAX_BYTES} bytes (the oldest go first). Pure
 * Java; call it off the main thread.
 */
public final class PlusAuditCache {
    public static final long KEEP_MS = 7L * 86_400_000L;
    public static final int MAX_ENTRIES = 3000;
    public static final int MAX_BYTES = 1_500_000;
    private static final Pattern ACCOUNT = Pattern.compile("^a_[0-9A-HJKMNP-TV-Z]{16}$");

    private final File mDir;

    public PlusAuditCache(File dir) {
        mDir = dir;
    }

    @Nullable
    private File file(String acct) {
        if (acct == null || !ACCOUNT.matcher(acct).matches()) return null;
        return new File(mDir, acct + ".jsonl");
    }

    /** What the device kept of {@code acct}'s log, newest first, without entries older than 7 days. */
    public synchronized List<PlusModel.AuditEntry> read(String acct, long nowMs) {
        File f = file(acct);
        if (f == null || !f.isFile()) return Collections.emptyList();
        TreeMap<Long, PlusModel.AuditEntry> bySeq = new TreeMap<>(Collections.reverseOrder());
        try {
            for (String line : readAll(f).split("\n")) {
                PlusModel.AuditEntry e = PlusModel.AuditEntry.read(Json.parseObject(line));
                if (e != null && e.t >= nowMs - KEEP_MS) bySeq.put(e.s, e);
            }
        } catch (IOException e) {
            return Collections.emptyList();
        }
        return new ArrayList<>(bySeq.values());
    }

    /** Adds {@code entries} (a page or a live batch) to what the device kept; an entry seen twice is kept once. */
    public synchronized List<PlusModel.AuditEntry> merge(String acct, Collection<PlusModel.AuditEntry> entries, long nowMs) {
        File f = file(acct);
        if (f == null) return Collections.emptyList();
        TreeMap<Long, PlusModel.AuditEntry> bySeq = new TreeMap<>(Collections.reverseOrder());
        for (PlusModel.AuditEntry e : read(acct, nowMs)) bySeq.put(e.s, e);
        boolean changed = false;
        for (PlusModel.AuditEntry e : entries) {
            if (e.t < nowMs - KEEP_MS) continue;
            PlusModel.AuditEntry before = bySeq.put(e.s, e);
            if (before == null || !before.json.equals(e.json)) changed = true;
        }
        List<PlusModel.AuditEntry> kept = new ArrayList<>(bySeq.values());
        if (kept.size() > MAX_ENTRIES) {
            kept = new ArrayList<>(kept.subList(0, MAX_ENTRIES));
            changed = true;
        }
        if (!changed && f.isFile()) return kept;
        // Oldest last; past the byte cap the oldest go.
        StringBuilder out = new StringBuilder();
        int n = 0;
        for (PlusModel.AuditEntry e : kept) {
            String line = compact(e.json);
            if (out.length() + line.length() + 1 > MAX_BYTES) break;
            out.append(line).append('\n');
            n++;
        }
        if (n < kept.size()) kept = new ArrayList<>(kept.subList(0, n));
        write(f, out.toString());
        return kept;
    }

    /** Forgets an account's log (signed out, the account is gone, or it is not AFK+ any more). */
    public synchronized void drop(String acct) {
        File f = file(acct);
        if (f != null && f.exists() && !f.delete()) f.deleteOnExit();
    }

    /** Forgets every account's log but those in {@code keep}. */
    public synchronized void keepOnly(Collection<String> keep) {
        File[] files = mDir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = f.getName();
            if (!name.endsWith(".jsonl") && !name.endsWith(".tmp")) continue;
            String acct = name.substring(0, name.indexOf('.'));
            if (!keep.contains(acct) && !f.delete()) f.deleteOnExit();
        }
    }

    /** The file's text (no java.nio.file below Android 8). */
    private static String readAll(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String compact(JsonObject o) {
        return o.toString().replace('\n', ' ');
    }

    private void write(File f, String text) {
        if (!mDir.isDirectory() && !mDir.mkdirs()) return;
        File tmp = new File(mDir, f.getName() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            if (!tmp.delete()) tmp.deleteOnExit();
            return;
        }
        // On Android a rename replaces the old file at once (killed in between, the old log stays); where it cannot
        // (Windows, the JVM tests) the old one goes first.
        if (tmp.renameTo(f)) return;
        if (f.exists() && !f.delete()) {
            if (!tmp.delete()) tmp.deleteOnExit();
            return;
        }
        if (!tmp.renameTo(f) && !tmp.delete()) tmp.deleteOnExit();
    }
}
