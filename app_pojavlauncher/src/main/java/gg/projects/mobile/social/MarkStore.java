package gg.projects.mobile.social;

import androidx.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import gg.projects.mobile.content.AtomicFiles;

/**
 * BADGES: the last badge catalogue the app read ({@code GET /v1/marks}), kept with its ETag so a cold start draws every
 * badge right before the network answers, and the next read can be a {@code 304}. One small file in the app's no-backup
 * folder ({@code projects-social/marks.json}, {@code marks-debug.json} in debug builds that point elsewhere):
 * {@code {"v":1,"etag":"\"3\"","catalog":{rev, marks}}}. The catalogue is public (the forum and the sites read it too):
 * nothing of an account is in it, so it stays across sign-outs. Pure Java, with tests.
 */
public final class MarkStore {
    static final int FORMAT = 1;
    /** A catalogue is about a kilobyte; anything much larger is not one. */
    static final long MAX_FILE = 64 * 1024;
    /** What goes back as {@code If-None-Match}: printable ASCII, short (the service's is {@code "<rev>"}). */
    private static final Pattern ETAG = Pattern.compile("[\\x21-\\x7e]{1,64}");

    private final File mFile;
    private final Object mLock = new Object();

    public MarkStore(File file) {
        mFile = file;
    }

    /** A kept catalogue and the ETag it came with (null when it had none). */
    public static final class Kept {
        public final MarkCatalog catalog;
        @Nullable
        public final String etag;

        Kept(MarkCatalog catalog, @Nullable String etag) {
            this.catalog = catalog;
            this.etag = etag;
        }
    }

    /** An ETag that may go back to the service; anything else is not kept. */
    @Nullable
    public static String etag(@Nullable String value) {
        return value != null && ETAG.matcher(value).matches() ? value : null;
    }

    /** The kept catalogue; null when there is none or it cannot be read (the built-in one draws then). */
    @Nullable
    public Kept load() {
        synchronized (mLock) {
            if (!mFile.isFile()) return null;
            try {
                JsonObject o = Json.parseObject(new String(AtomicFiles.read(mFile, MAX_FILE), StandardCharsets.UTF_8));
                JsonElement v = o == null ? null : o.get("v");
                if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber() || v.getAsInt() != FORMAT) return null;
                MarkCatalog catalog = MarkCatalog.read(Json.obj(o, "catalog"));
                return catalog == null ? null : new Kept(catalog, etag(Json.str(o, "etag")));
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }
    }

    /** Keeps {@code catalog} with its ETag in place of the one kept before. False when it could not be written. */
    public boolean save(MarkCatalog catalog, @Nullable String etag) {
        JsonObject o = new JsonObject();
        o.addProperty("v", FORMAT);
        String kept = etag(etag);
        if (kept != null) o.addProperty("etag", kept);
        o.add("catalog", catalog.toJson());
        byte[] bytes = o.toString().getBytes(StandardCharsets.UTF_8);
        synchronized (mLock) {
            try {
                AtomicFiles.write(mFile, bytes);
                return true;
            } catch (IOException | RuntimeException e) {
                // Not kept: the next start draws the built-in catalogue until the list says which one is current.
                return false;
            }
        }
    }
}
