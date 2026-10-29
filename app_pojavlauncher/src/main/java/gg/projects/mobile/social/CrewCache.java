package gg.projects.mobile.social;

import androidx.annotation.Nullable;

import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import gg.projects.mobile.content.AtomicFiles;

/**
 * SOC9 (TEAM4-B, soc9.md §3): the crews' details on this phone, one file per crew in a folder per account, each with the
 * detail's {@code rev}, its ETag (opaque, sent back as it came) and the Turkey calendar day it was read on. The light
 * friend list names each crew's {@code rev}; {@link CrewDetails} reads a crew again when that differs or the copy is
 * from an earlier day, so members' names and pictures are at most a day old. In the app's no-backup folder (members'
 * ids and names never travel with a backup); every copy goes when the player signs out. One lock for every instance:
 * the list's read and the socket's frames run on different worker threads. Pure Java, with tests.
 */
public final class CrewCache {
    private static final long MAX_FILE = 256 * 1024;
    private static final Object LOCK = new Object();

    private final File mDir;

    /** {@code root}: the cache's folder for every account; {@code account}: the signed-in Discord id. */
    public CrewCache(File root, String account) {
        mDir = new File(root, SocialModel.isUserId(account) ? account : "unknown");
    }

    /** One crew's copy. */
    public static final class Entry {
        @Nullable
        public final String etag;
        public final long rev;
        public final long day;
        public final JsonObject team;

        Entry(@Nullable String etag, long rev, long day, JsonObject team) {
            this.etag = etag;
            this.rev = rev;
            this.day = day;
            this.team = team;
        }
    }

    @Nullable
    public Entry load(String crewId) {
        File file = file(crewId);
        if (file == null) return null;
        synchronized (LOCK) {
            if (!file.isFile()) return null;
            try {
                JsonObject o = Json.parseObject(new String(AtomicFiles.read(file, MAX_FILE), StandardCharsets.UTF_8));
                JsonObject team = Json.obj(o, "team");
                if (o == null || team == null || !crewId.equals(Json.str(team, "id"))) return null;
                return new Entry(Json.str(o, "etag"), Json.num(o, "rev", -1), Json.num(o, "day", -1), team);
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }
    }

    /** {@code etag}: the detail's own (null: the copy came from a {@code team} frame). */
    public void store(String crewId, @Nullable String etag, long rev, long day, JsonObject team) {
        File file = file(crewId);
        if (file == null) return;
        JsonObject o = new JsonObject();
        if (etag != null) o.addProperty("etag", etag);
        o.addProperty("rev", rev);
        o.addProperty("day", day);
        o.add("team", team);
        synchronized (LOCK) {
            try {
                if (!mDir.isDirectory() && !mDir.mkdirs()) return;
                AtomicFiles.write(file, o.toString().getBytes(StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException e) {
                // A copy that is not written is read again next time.
            }
        }
    }

    public void remove(String crewId) {
        File file = file(crewId);
        if (file == null) return;
        synchronized (LOCK) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    /** The crews the player is no longer in go (left or removed while the phone was away). */
    public void keepOnly(Set<String> crewIds) {
        synchronized (LOCK) {
            File[] files = mDir.listFiles();
            if (files == null) return;
            for (File f : files) {
                String name = f.getName();
                String id = name.endsWith(".json") ? name.substring(0, name.length() - 5) : null;
                //noinspection ResultOfMethodCallIgnored
                if (id == null || !crewIds.contains(id)) f.delete();
            }
        }
    }

    /** Signed out or the account deleted: every account's copies go. */
    public static void clear(File root) {
        synchronized (LOCK) {
            File[] accounts = root.listFiles();
            if (accounts == null) return;
            for (File account : accounts) {
                File[] files = account.listFiles();
                if (files != null) {
                    //noinspection ResultOfMethodCallIgnored
                    for (File f : files) f.delete();
                }
                //noinspection ResultOfMethodCallIgnored
                account.delete();
            }
        }
    }

    @Nullable
    private File file(String crewId) {
        return SocialModel.isCrewId(crewId) ? new File(mDir, crewId + ".json") : null;
    }
}
