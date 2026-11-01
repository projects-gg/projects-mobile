package gg.projects.mobile.afk.store;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import gg.projects.mobile.afk.api.AfkReason;
import gg.projects.mobile.content.AtomicFiles;

/**
 * PHONE-AFK: {@code no_backup/afk/runtime.json}, written by the {@code :afk} process only. What the player wants
 * running ({@link #running}: started and not stopped by the player or for a reason) and why accounts stopped
 * ({@link #stops}). The app reads it while the service is not there: a stopped account still says why it stopped, and
 * accounts still "running" in a file whose process is gone mean the phone closed AFK ({@link AfkEnds}). A sticky
 * restart of the service starts {@link #running} again. Pure Java.
 */
public final class AfkRuntime {
    public static final String FILE = "runtime.json";
    private static final long MAX_BYTES = 64 * 1024;

    /** Why an account stopped (the player's stop leaves no entry). */
    public static final class Stop {
        public final AfkReason reason;
        /** The server's own words, masked, or null. */
        public final String message;
        public final long at;

        public Stop(AfkReason reason, String message, long at) {
            this.reason = reason;
            this.message = message;
            this.at = at;
        }
    }

    /** The {@code :afk} process that wrote it (0: none). */
    public int pid;
    /** When that process's service started (ms since the epoch). */
    public long startedAt;
    public long updatedAt;
    /** Account ids the player wants AFK, in the order they were started. */
    public final List<String> running = new ArrayList<>();
    /** Account names of {@link #running} (for the morning notification, without reading the accounts). */
    public final List<String> runningNames = new ArrayList<>();
    public final Map<String, Stop> stops = new LinkedHashMap<>();

    public static AfkRuntime read(File afkDir) {
        AfkRuntime runtime = new AfkRuntime();
        File file = new File(afkDir, FILE);
        if (!file.isFile()) return runtime;
        try {
            JsonElement root = JsonParser.parseString(new String(AtomicFiles.read(file, MAX_BYTES), StandardCharsets.UTF_8));
            if (!root.isJsonObject()) return runtime;
            JsonObject o = root.getAsJsonObject();
            runtime.pid = (int) AfkStore.number(o, "pid", 0);
            runtime.startedAt = AfkStore.number(o, "startedAt", 0);
            runtime.updatedAt = AfkStore.number(o, "updatedAt", 0);
            strings(o.get("running"), runtime.running);
            strings(o.get("runningNames"), runtime.runningNames);
            JsonElement stops = o.get("stops");
            if (stops != null && stops.isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : stops.getAsJsonObject().entrySet()) {
                    if (!e.getValue().isJsonObject()) continue;
                    JsonObject s = e.getValue().getAsJsonObject();
                    AfkReason reason = AfkReason.ofKey(AfkStore.string(s, "reason"));
                    if (reason == null) continue;
                    String message = AfkStore.string(s, "message");
                    runtime.stops.put(e.getKey(), new Stop(reason, message.isEmpty() ? null : message,
                            AfkStore.number(s, "at", 0)));
                }
            }
        } catch (IOException | RuntimeException e) {
            // A broken file reads as nothing running.
        }
        return runtime;
    }

    public void write(File afkDir) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("v", 1);
        o.addProperty("pid", pid);
        o.addProperty("startedAt", startedAt);
        o.addProperty("updatedAt", updatedAt);
        JsonArray run = new JsonArray();
        for (String id : running) run.add(id);
        o.add("running", run);
        JsonArray names = new JsonArray();
        for (String name : runningNames) names.add(name);
        o.add("runningNames", names);
        JsonObject stopsJson = new JsonObject();
        for (Map.Entry<String, Stop> e : stops.entrySet()) {
            JsonObject s = new JsonObject();
            s.addProperty("reason", e.getValue().reason.key());
            if (e.getValue().message != null) s.addProperty("message", e.getValue().message);
            s.addProperty("at", e.getValue().at);
            stopsJson.add(e.getKey(), s);
        }
        o.add("stops", stopsJson);
        AtomicFiles.write(new File(afkDir, FILE), o.toString().getBytes(StandardCharsets.UTF_8));
    }

    public List<String> runningIds() {
        return Collections.unmodifiableList(running);
    }

    private static void strings(JsonElement value, List<String> into) {
        if (value == null || !value.isJsonArray()) return;
        for (JsonElement e : value.getAsJsonArray()) {
            if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) into.add(e.getAsString());
        }
    }
}
