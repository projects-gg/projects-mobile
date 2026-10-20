package gg.projects.mobile.launch;

import android.util.Log;

import androidx.annotation.WorkerThread;

import net.kdt.pojavlaunch.modloaders.FabriclikeUtils;
import net.kdt.pojavlaunch.utils.DownloadUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import gg.projects.mobile.content.AtomicFiles;

/**
 * M18: puts a Fabric or Quilt version JSON in place from the loader's own server ({@link LoaderJson}),
 * written to a temporary file and renamed, so an interrupted write never leaves half a file behind.
 * Used by the Projects join for its loader and by the start's file check for any profile whose loader
 * version lost its JSON, so such a start heals instead of failing.
 */
public final class LoaderRepair {
    private LoaderRepair() {}

    private static final String TAG = "ProjectsLoader";

    /** Whether {@code versionId} is a version this class can fetch (Fabric or Quilt). */
    public static boolean canFetch(String versionId) {
        return LoaderJson.parse(versionId) != null;
    }

    /** Fetches {@code versionId}'s JSON into {@code target}; throws when the loader's server has none. */
    @WorkerThread
    public static void fetch(String versionId, File target) throws IOException {
        LoaderJson.Id id = LoaderJson.parse(versionId);
        if (id == null) throw new IOException("not a Fabric or Quilt version: " + versionId);
        FabriclikeUtils utils = id.quilt ? FabriclikeUtils.QUILT_UTILS : FabriclikeUtils.FABRIC_UTILS;
        String json = DownloadUtils.downloadString(utils.createJsonDownloadUrl(id.minecraft, id.loader));
        if (!LoaderJson.usable(json, versionId)) {
            throw new IOException("the " + utils.getName() + " server sent no usable version JSON for " + versionId);
        }
        AtomicFiles.write(target, json.getBytes(StandardCharsets.UTF_8));
        Log.i(TAG, "version JSON of " + versionId + " put in place");
    }

    /**
     * Makes sure {@code versionId}'s JSON in {@code versionsDir} is usable, fetching it when it is
     * missing or broken. Returns false (and logs why) when it could not be fetched; other versions are
     * left alone and count as fine.
     */
    @WorkerThread
    public static boolean ensure(File versionsDir, String versionId) {
        if (!canFetch(versionId)) return true;
        File target = new File(versionsDir, versionId + File.separator + versionId + ".json");
        if (LoaderJson.usable(target, versionId)) return true;
        Log.w(TAG, "version JSON of " + versionId + (target.isFile() ? " is broken" : " is missing") + "; fetching it again");
        try {
            fetch(versionId, target);
            return true;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "version JSON of " + versionId + " not fetched", e);
            return false;
        }
    }
}
