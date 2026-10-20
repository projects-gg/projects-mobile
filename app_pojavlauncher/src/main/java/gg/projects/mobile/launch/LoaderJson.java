package gg.projects.mobile.launch;

import androidx.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import gg.projects.mobile.content.AtomicFiles;

/**
 * M18: the version JSON of a Fabric or Quilt version ({@code versions/<id>/<id>.json}), which a start
 * cannot do without: a player's start failed with "Unable to read Version JSON for version
 * fabric-loader-0.19.5-26.1.2" (an install that stopped after the profile had moved to the loader's
 * version, before the loader was written; an interrupted write leaves a broken file the old check took
 * as installed). What makes one usable and which loader an id names. Plain Java, JVM-tested.
 */
public final class LoaderJson {
    private LoaderJson() {}

    /** A version JSON is a few kilobytes; more than this is not one. */
    private static final long MAX_BYTES = 4L << 20;

    /** What a Fabric or Quilt version id names. */
    public static final class Id {
        public final boolean quilt;
        public final String loader;
        public final String minecraft;

        Id(boolean quilt, String loader, String minecraft) {
            this.quilt = quilt;
            this.loader = loader;
            this.minecraft = minecraft;
        }
    }

    /**
     * {@code fabric-loader-0.19.5-26.1.2} and {@code quilt-loader-0.26.4-1.21.1}: the loader's version
     * (it has no dash) and the Minecraft version (which may have them, "26.4-snapshot-4"); null for any
     * other id.
     */
    @Nullable
    public static Id parse(@Nullable String versionId) {
        if (versionId == null) return null;
        boolean quilt;
        String rest;
        if (versionId.startsWith("fabric-loader-")) {
            quilt = false;
            rest = versionId.substring("fabric-loader-".length());
        } else if (versionId.startsWith("quilt-loader-")) {
            quilt = true;
            rest = versionId.substring("quilt-loader-".length());
        } else {
            return null;
        }
        int dash = rest.indexOf('-');
        if (dash <= 0 || dash == rest.length() - 1) return null;
        String loader = rest.substring(0, dash);
        String minecraft = rest.substring(dash + 1);
        if (!loader.matches("[0-9A-Za-z.+_]+") || !minecraft.matches("[0-9A-Za-z.+_-]+")) return null;
        return new Id(quilt, loader, minecraft);
    }

    /**
     * True when {@code json} is a version JSON for {@code versionId} a start can use: an object with that
     * id, the version it builds on or its own main class, and its libraries.
     */
    public static boolean usable(@Nullable String json, String versionId) {
        if (json == null || json.trim().isEmpty()) return false;
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) return false;
            JsonObject object = root.getAsJsonObject();
            if (!versionId.equals(string(object, "id"))) return false;
            if (string(object, "inheritsFrom") == null && string(object, "mainClass") == null) return false;
            JsonElement libraries = object.get("libraries");
            return libraries != null && libraries.isJsonArray();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** {@link #usable(String, String)} for the file a start reads; false when it is missing or unreadable. */
    public static boolean usable(File file, String versionId) {
        if (!file.isFile()) return false;
        try {
            return usable(new String(AtomicFiles.read(file, MAX_BYTES), StandardCharsets.UTF_8), versionId);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    @Nullable
    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive()) return null;
        String text = value.getAsString();
        return text.trim().isEmpty() ? null : text;
    }
}
