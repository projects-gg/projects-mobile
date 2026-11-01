package gg.projects.mobile.afk.store;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import gg.projects.mobile.afk.api.AfkAccount;
import gg.projects.mobile.afk.api.AfkLoginMode;
import gg.projects.mobile.content.AtomicFiles;

/**
 * PHONE-AFK: the phone's AFK accounts, {@code no_backup/afk/accounts.json} (never in Android's backups; the login
 * commands' key would not come along anyway). Only the app's own process writes it (atomically, so the {@code :afk}
 * process always reads a whole file); the login commands are sealed ({@link AfkSealer}) and never stored in clear.
 * Pure Java: the JVM tests read and write it in a temporary folder.
 */
public final class AfkStore {
    public static final String FILE = "accounts.json";
    static final int VERSION = 1;
    private static final long MAX_BYTES = 256 * 1024;

    /** An account and its sealed login commands ("" when it has none). */
    public static final class Entry {
        public final AfkAccount account;
        public final String sealed;

        public Entry(AfkAccount account, String sealed) {
            this.account = account;
            this.sealed = sealed == null ? "" : sealed;
        }
    }

    private final File file;
    private List<Entry> cache;
    private long cacheModified = -1;
    private long cacheLength = -1;

    public AfkStore(File afkDir) {
        this.file = new File(afkDir, FILE);
    }

    public File file() {
        return file;
    }

    /** The accounts in the order they were added (empty when there is no file or it cannot be read). */
    public synchronized List<Entry> entries() {
        long modified = file.lastModified();
        long length = file.length();
        if (cache != null && modified == cacheModified && length == cacheLength) return cache;
        List<Entry> read;
        try {
            read = decode(AtomicFiles.read(file, MAX_BYTES));
        } catch (FileNotFoundException e) {
            read = Collections.emptyList();
        } catch (IOException | RuntimeException e) {
            // A broken file never takes the app down; the next save writes a whole one.
            read = Collections.emptyList();
        }
        cache = read;
        cacheModified = modified;
        cacheLength = length;
        return read;
    }

    public synchronized Entry find(String id) {
        if (id == null) return null;
        for (Entry e : entries()) {
            if (e.account.id.equals(id)) return e;
        }
        return null;
    }

    public synchronized void write(List<Entry> entries) throws IOException {
        AtomicFiles.write(file, encode(entries).getBytes(StandardCharsets.UTF_8));
        cache = Collections.unmodifiableList(new ArrayList<>(entries));
        cacheModified = file.lastModified();
        cacheLength = file.length();
    }

    public synchronized void delete() {
        //noinspection ResultOfMethodCallIgnored
        file.delete();
        cache = null;
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    /** A new account id: 16 random hex digits (never shown). */
    public static String newId() {
        byte[] b = new byte[8];
        RANDOM.nextBytes(b);
        StringBuilder s = new StringBuilder(16);
        for (byte x : b) s.append(String.format(Locale.ROOT, "%02x", x & 0xff));
        return s.toString();
    }

    static String encode(List<Entry> entries) {
        JsonObject root = new JsonObject();
        root.addProperty("v", VERSION);
        JsonArray list = new JsonArray();
        for (Entry e : entries) {
            AfkAccount a = e.account;
            JsonObject o = new JsonObject();
            o.addProperty("id", a.id);
            o.addProperty("name", a.name);
            o.addProperty("host", a.host);
            o.addProperty("port", a.port);
            o.addProperty("login", e.sealed);
            o.addProperty("loginCount", a.loginCommandCount);
            o.addProperty("mode", a.loginMode.key());
            o.addProperty("sneak", a.sneak);
            o.addProperty("mobileData", a.mobileData);
            o.addProperty("createdAt", a.createdAt);
            list.add(o);
        }
        root.add("accounts", list);
        return root.toString();
    }

    static List<Entry> decode(byte[] bytes) {
        JsonElement root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
        if (!root.isJsonObject()) return Collections.emptyList();
        JsonElement accounts = root.getAsJsonObject().get("accounts");
        if (accounts == null || !accounts.isJsonArray()) return Collections.emptyList();
        List<Entry> out = new ArrayList<>();
        for (JsonElement value : accounts.getAsJsonArray()) {
            if (!value.isJsonObject()) continue;
            JsonObject o = value.getAsJsonObject();
            String id = string(o, "id");
            String name = string(o, "name");
            String host = string(o, "host");
            if (id.isEmpty() || !AfkDrafts.validName(name) || host.isEmpty()) continue;
            int port = (int) number(o, "port", 0);
            if (port < 0 || port > 65535) port = 0;
            AfkAccount account = new AfkAccount(id, name, host, port, (int) Math.max(0, number(o, "loginCount", 0)),
                    AfkLoginMode.ofKey(string(o, "mode")), bool(o, "sneak", true), bool(o, "mobileData", false),
                    number(o, "createdAt", 0));
            out.add(new Entry(account, string(o, "login")));
        }
        return Collections.unmodifiableList(out);
    }

    static String string(JsonObject o, String key) {
        JsonElement v = o.get(key);
        return v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isString() ? v.getAsString() : "";
    }

    static long number(JsonObject o, String key, long fallback) {
        JsonElement v = o.get(key);
        if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) return fallback;
        try {
            return v.getAsLong();
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static boolean bool(JsonObject o, String key, boolean fallback) {
        JsonElement v = o.get(key);
        return v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean() ? v.getAsBoolean() : fallback;
    }
}
