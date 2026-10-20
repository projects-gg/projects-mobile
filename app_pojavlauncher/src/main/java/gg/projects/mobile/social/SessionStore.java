package gg.projects.mobile.social;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import gg.projects.mobile.content.AtomicFiles;

/**
 * The friends session on this phone: the session token, the account it belongs to and a sign-in
 * that waits for the browser. Kept in the app's no-backup folder ({@code getNoBackupFilesDir}), so
 * Android's backup never carries a token to another phone, and readable by this app only. The
 * token goes nowhere else: no log, no URL, no preferences file.
 */
public final class SessionStore {
    private static final String TAG = "ProjectsSocial";

    private final File mFile;

    public SessionStore(Context context, String name) {
        File dir = new File(context.getNoBackupFilesDir(), "projects-social");
        mFile = new File(dir, name);
    }

    public static final class Saved {
        @Nullable
        public final String token;
        @Nullable
        public final String userId;
        @Nullable
        public final JsonObject me;
        @Nullable
        public final SignInFlow.Attempt attempt;

        Saved(@Nullable String token, @Nullable String userId, @Nullable JsonObject me, @Nullable SignInFlow.Attempt attempt) {
            this.token = token;
            this.userId = userId;
            this.me = me;
            this.attempt = attempt;
        }
    }

    public synchronized Saved load() {
        if (!mFile.isFile()) return new Saved(null, null, null, null);
        try {
            JsonObject o = Json.parseObject(new String(AtomicFiles.read(mFile, 256 * 1024), StandardCharsets.UTF_8));
            String token = Json.str(o, "token");
            if (token != null && !token.startsWith("pls_")) token = null;
            JsonObject attemptJson = Json.obj(o, "attempt");
            SignInFlow.Attempt attempt = null;
            if (attemptJson != null) {
                String verifier = Json.str(attemptJson, "verifier");
                String challenge = Json.str(attemptJson, "challenge");
                if (verifier != null && challenge != null) {
                    attempt = new SignInFlow.Attempt(verifier, challenge, Json.num(attemptJson, "startedAt", 0));
                }
            }
            return new Saved(token, Json.str(o, "userId"), Json.obj(o, "me"), attempt);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "session file unreadable; signed out");
            return new Saved(null, null, null, null);
        }
    }

    public synchronized void save(@Nullable String token, @Nullable String userId, @Nullable JsonObject me,
                                  @Nullable SignInFlow.Attempt attempt) {
        JsonObject o = new JsonObject();
        if (token != null) o.addProperty("token", token);
        if (userId != null) o.addProperty("userId", userId);
        if (me != null) o.add("me", me);
        if (attempt != null) {
            JsonObject a = new JsonObject();
            a.addProperty("verifier", attempt.verifier);
            a.addProperty("challenge", attempt.challenge);
            a.addProperty("startedAt", attempt.startedAt);
            o.add("attempt", a);
        }
        try {
            if (o.size() == 0) {
                //noinspection ResultOfMethodCallIgnored
                mFile.delete();
                return;
            }
            AtomicFiles.write(mFile, o.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "session file not written", e);
        }
    }
}
