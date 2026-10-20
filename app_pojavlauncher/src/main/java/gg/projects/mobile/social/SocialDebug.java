package gg.projects.mobile.social;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import com.google.gson.JsonObject;

import net.kdt.pojavlaunch.BuildConfig;

import java.io.File;
import java.nio.charset.StandardCharsets;

import gg.projects.mobile.content.AtomicFiles;

/**
 * Debug builds only (M19; like the desktop's {@code PROJECTS_SOCIAL_URL} and
 * {@code PROJECTS_SOCIAL_TEAMS}): the {@code social} part of {@code projects-debug.json} in the app's
 * external files folder points the app at a local friends service and may turn Friends and crews on
 * without the signed config:
 * <pre>{"social": {"url": "http://10.0.2.2:8897/", "friends": true, "teams": true, "browser": "log"}}</pre>
 * {@code browser: "log"} writes the sign-in page's address to logcat instead of opening a browser
 * (the emulator's browser must not run; a script plays the browser's part). Release builds never
 * read the file: the service is {@code https://social.projects.gg/} and the signed config decides.
 */
public final class SocialDebug {
    private static final String TAG = "ProjectsSocial";
    public static final String PRODUCTION_URL = "https://social.projects.gg/";

    public final String url;
    public final boolean forceFriends;
    public final boolean forceTeams;
    public final boolean logBrowser;
    public final boolean override;
    /**
     * {@code pushToken}: a made-up FCM token registered in place of Firebase's (an emulator without a
     * working FCM, against a local service whose fake Google records the pushes); null otherwise.
     */
    @Nullable
    public final String pushToken;

    private SocialDebug(String url, boolean forceFriends, boolean forceTeams, boolean logBrowser, boolean override,
                        @Nullable String pushToken) {
        this.url = url;
        this.forceFriends = forceFriends;
        this.forceTeams = forceTeams;
        this.logBrowser = logBrowser;
        this.override = override;
        this.pushToken = pushToken;
    }

    public static SocialDebug read(Context context) {
        SocialDebug production = new SocialDebug(PRODUCTION_URL, false, false, false, false, null);
        if (!BuildConfig.DEBUG) return production;
        File dir = context.getExternalFilesDir(null);
        File file = dir == null ? null : new File(dir, "projects-debug.json");
        if (file == null || !file.isFile()) return production;
        try {
            JsonObject social = Json.obj(Json.parseObject(new String(AtomicFiles.read(file, 64 * 1024), StandardCharsets.UTF_8)), "social");
            if (social == null) return production;
            String url = normalizeUrl(Json.str(social, "url"));
            String pushToken = Json.str(social, "pushToken");
            if (pushToken != null && !pushToken.matches("[A-Za-z0-9_:-]{32,1024}")) pushToken = null;
            SocialDebug debug = new SocialDebug(url == null ? PRODUCTION_URL : url, Json.bool(social, "friends", false),
                    Json.bool(social, "teams", false), "log".equals(Json.str(social, "browser")), true, pushToken);
            Log.w(TAG, "debug friends override in use: " + debug.url + " friends=" + debug.forceFriends
                    + " teams=" + debug.forceTeams + " browser=" + (debug.logBrowser ? "log" : "open"));
            return debug;
        } catch (Exception e) {
            Log.w(TAG, "ignoring a broken projects-debug.json", e);
            return production;
        }
    }

    /** An http(s) base address ending in a slash, or null. */
    @Nullable
    static String normalizeUrl(@Nullable String url) {
        if (url == null) return null;
        String u = url.trim();
        if (!u.startsWith("https://") && !u.startsWith("http://")) return null;
        return u.endsWith("/") ? u : u + "/";
    }
}
