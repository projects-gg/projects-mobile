package gg.projects.mobile.cloud;

import android.content.Context;
import android.util.Log;

import com.google.gson.JsonObject;

import net.kdt.pojavlaunch.BuildConfig;

import java.io.File;
import java.nio.charset.StandardCharsets;

import gg.projects.mobile.content.AtomicFiles;
import gg.projects.mobile.social.Json;

/**
 * Debug builds only (the desktop's {@code PROJECTS_CLOUD_AFK_URL}): the {@code cloud} part of
 * {@code projects-debug.json} in the app's external files folder points the app at a local 7/24 Bulut service:
 * <pre>{"cloud": {"url": "http://10.0.2.2:8790/api/v1/", "browser": "log"}}</pre>
 * {@code browser: "log"} (or the friends part's) writes a site page's address to logcat instead of opening a browser
 * (the emulator's browser must not run). Release builds never read the file: the service is
 * {@code https://afk.projects.gg/api/v1/}.
 */
public final class CloudDebug {
    private static final String TAG = "ProjectsCloud";

    public final String url;
    public final boolean override;
    public final boolean logBrowser;
    /** FEAT-M: the movement panel against its stand-in machine ({@code cloud.movementMock}), until the service has movement. */
    public final boolean movementMock;

    private CloudDebug(String url, boolean override, boolean logBrowser) {
        this(url, override, logBrowser, false);
    }

    private CloudDebug(String url, boolean override, boolean logBrowser, boolean movementMock) {
        this.url = url;
        this.override = override;
        this.logBrowser = logBrowser;
        this.movementMock = movementMock;
    }

    public static CloudDebug read(Context context) {
        CloudDebug production = new CloudDebug(CloudApi.PRODUCTION, false, false);
        if (!BuildConfig.DEBUG) return production;
        File dir = context.getExternalFilesDir(null);
        File file = dir == null ? null : new File(dir, "projects-debug.json");
        if (file == null || !file.isFile()) return production;
        try {
            JsonObject all = Json.parseObject(new String(AtomicFiles.read(file, 64 * 1024), StandardCharsets.UTF_8));
            JsonObject cloud = Json.obj(all, "cloud");
            boolean log = "log".equals(Json.str(cloud, "browser")) || "log".equals(Json.str(Json.obj(all, "social"), "browser"));
            String url = cloud == null ? null : Json.str(cloud, "url");
            boolean movement = Json.bool(cloud, "movementMock", false);
            if (url == null) return new CloudDebug(CloudApi.PRODUCTION, false, log, movement);
            url = url.trim();
            if (!url.startsWith("https://") && !url.startsWith("http://")) return production;
            if (!url.endsWith("/")) url = url + "/";
            if (!url.endsWith("api/v1/")) url = url + "api/v1/";
            Log.w(TAG, "debug cloud override in use: " + url + (log ? " browser=log" : ""));
            return new CloudDebug(url, true, log, movement);
        } catch (Exception e) {
            Log.w(TAG, "ignoring a broken projects-debug.json", e);
            return production;
        }
    }
}
