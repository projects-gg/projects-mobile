package gg.projects.mobile.launch;

import android.content.Context;

import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import gg.projects.mobile.content.AtomicFiles;

/**
 * What the last game start was: written by the game process right before the game starts, read by
 * the launcher when the game ends with an error ({@link SodiumFallback}).
 */
public final class LaunchRecord {
    private static final String FILE = "projects-last-launch.json";
    private static final Gson GSON = new Gson();

    public String profileKey;
    public String gameDir;
    /** The renderer the game started with ({@code Tools.LOCAL_RENDERER}). */
    @Nullable public String renderer;
    /** The renderer the profile asks for ({@code pojavRendererName}; null for the default). */
    @Nullable public String profileRenderer;
    public String versionId;
    /** Jars of Sodium and its family in the profile's mods folder (file names). */
    public List<String> sodiumJars = new ArrayList<>();
    public long startedAt;

    LaunchRecord() {}

    LaunchRecord(String profileKey, String gameDir, @Nullable String renderer, String versionId, List<String> sodiumJars, long startedAt) {
        this.profileKey = profileKey;
        this.gameDir = gameDir;
        this.renderer = renderer;
        this.versionId = versionId;
        this.sodiumJars = sodiumJars;
        this.startedAt = startedAt;
    }

    private static File file(Context context) {
        return new File(context.getFilesDir(), FILE);
    }

    void write(Context context) throws IOException {
        AtomicFiles.write(file(context), GSON.toJson(this).getBytes(StandardCharsets.UTF_8));
    }

    /** The last start, or null when none was recorded (or the record cannot be read). */
    @Nullable
    static LaunchRecord read(Context context) {
        File file = file(context);
        if (!file.isFile()) return null;
        try {
            LaunchRecord record = GSON.fromJson(new String(AtomicFiles.read(file, 64 * 1024), StandardCharsets.UTF_8), LaunchRecord.class);
            if (record == null || record.gameDir == null) return null;
            if (record.sodiumJars == null) record.sodiumJars = new ArrayList<>();
            return record;
        } catch (IOException | JsonSyntaxException e) {
            return null;
        }
    }

    /** The record was looked at: a later exit must not be blamed on the same start. */
    static void consume(Context context) {
        //noinspection ResultOfMethodCallIgnored
        file(context).delete();
    }
}
