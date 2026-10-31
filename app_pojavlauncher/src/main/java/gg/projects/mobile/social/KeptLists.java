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
 * SOC-DISK on the phone (M048; scale-soc.md, the phone's spec 6): the friend list's answer ({@link #FROM}) kept on this
 * phone exactly as the service sent it, with the profile ({@code me}) and the relations version ({@code rel}) in it, so
 * a start the same day shows the lists at once and reads them again only when the presence {@code hello} names another
 * version ({@link RelVersion}). One file per account in the app's no-backup folder ({@code projects-social/lists/
 * <account>.json}, never in a backup): {@code {"v":1,"day":<Turkey day>,"from":<the request>,"answer":{…}}}, with no
 * token and nothing of the session. It goes whenever the copy stops matching its version (a socket event, the player's
 * own change, a push) and with the session (a sign-out, its end, a restriction, "Verilerimi sil"); a write decided
 * before such a moment never lands after it ({@link #generation}). Pure Java, with tests.
 */
public final class KeptLists {
    /** The request whose answer is kept: a copy of any other request is no copy. */
    public static final String FROM = "v1/friends?crews=lite&rel=1";
    static final int FORMAT = 1;
    /** The launcher's limit too: a larger answer is not kept, and a larger file is no copy. */
    static final long MAX_FILE = 2L * 1024 * 1024;
    private static final Pattern REL = Pattern.compile("[0-9a-f]{16}");

    private final File mDir;
    private final Object mLock = new Object();
    /** Raised by every {@link #clear}: a {@link #save} decided before it changes nothing. */
    private long mGeneration;
    /** False while the folder is known to be gone (a clear then touches no file); unknown at start. */
    private boolean mMaybeKept = true;

    /** {@code dir}: the folder of the copies ({@code projects-social/lists}, {@code lists-debug} in debug builds). */
    public KeptLists(File dir) {
        mDir = dir;
    }

    /** A relations version as the service writes it: 16 lowercase hex digits (anything else is never sent back). */
    public static boolean isRel(@Nullable String rel) {
        return rel != null && REL.matcher(rel).matches();
    }

    /** The copy a start may show. */
    public static final class Copy {
        public final String rel;
        public final JsonObject me;
        /** The whole answer as it was kept (a fresh one at every load). */
        public final JsonObject answer;

        Copy(String rel, JsonObject me, JsonObject answer) {
            this.rel = rel;
            this.me = me;
            this.answer = answer;
        }
    }

    /** Taken when the answer of a read is to be kept: a {@link #clear} after it drops that {@link #save}. */
    public long generation() {
        synchronized (mLock) {
            return mGeneration;
        }
    }

    /**
     * The copy kept for {@code account} when it can still be the service's {@code today}: this format, this account (the
     * file's and the answer's {@code me}), the same Turkey day (the version holds the day), the same request and a
     * version. Anything else is no copy: no file, unreadable, broken or half written, another shape, over 2 MB.
     */
    @Nullable
    public Copy load(@Nullable String account, long today) {
        File file = file(account);
        if (file == null) return null;
        synchronized (mLock) {
            if (!file.isFile()) return null;
            try {
                JsonObject o = Json.parseObject(new String(AtomicFiles.read(file, MAX_FILE), StandardCharsets.UTF_8));
                if (o == null || !isNumber(o.get("v"), FORMAT) || !isNumber(o.get("day"), today) || !FROM.equals(Json.str(o, "from"))) {
                    return null;
                }
                JsonObject answer = Json.obj(o, "answer");
                JsonObject me = Json.obj(answer, "me");
                String rel = Json.str(answer, "rel");
                if (me == null || !isRel(rel) || !account.equals(Json.str(me, "id"))) return null;
                return new Copy(rel, me, answer);
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }
    }

    /**
     * Keeps {@code answer} (the list's answer as it came, with its {@code me} and {@code rel}) for {@code account}, read on
     * {@code day}; replaces the account's earlier copy. Nothing is kept, and that earlier copy goes, when the answer is
     * not one a start could use or is over 2 MB; nothing at all happens when a {@link #clear} came after
     * {@code generation} was taken. False when nothing was kept.
     */
    public boolean save(long generation, @Nullable String account, long day, JsonObject answer) {
        File file = file(account);
        if (file == null) return false;
        JsonObject me = Json.obj(answer, "me");
        boolean usable = me != null && account.equals(Json.str(me, "id")) && isRel(Json.str(answer, "rel"));
        byte[] bytes = null;
        if (usable) {
            JsonObject o = new JsonObject();
            o.addProperty("v", FORMAT);
            o.addProperty("day", day);
            o.addProperty("from", FROM);
            o.add("answer", answer);
            bytes = o.toString().getBytes(StandardCharsets.UTF_8);
        }
        synchronized (mLock) {
            if (generation != mGeneration) return false;
            if (bytes == null || bytes.length > MAX_FILE) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                return false;
            }
            mMaybeKept = true;
            try {
                AtomicFiles.write(file, bytes);
                return true;
            } catch (IOException | RuntimeException e) {
                // Not kept: the next start reads the lists as before.
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                return false;
            }
        }
    }

    /** Every account's copy goes (and any half-written file with it); a {@link #save} decided before now is dropped. */
    public void clear() {
        synchronized (mLock) {
            mGeneration++;
            if (!mMaybeKept) return;
            File[] files = mDir.listFiles();
            if (files != null) {
                //noinspection ResultOfMethodCallIgnored
                for (File f : files) f.delete();
            }
            //noinspection ResultOfMethodCallIgnored
            mDir.delete();
            mMaybeKept = mDir.exists();
        }
    }

    /**
     * A start's lists from its {@code copy}, each crew in full from the crews' copies on this phone ({@link CrewCache}),
     * when every crew's copy is today's at the list's {@code rev}; null when one would have to be read (the start then
     * reads the lists as before). Nothing is fetched and the copy is not changed.
     */
    @Nullable
    public static JsonObject listsWithCrews(Copy copy, CrewCache crews, long today) {
        JsonObject lists = copy.answer.deepCopy();
        final boolean[] unread = {false};
        try {
            CrewDetails.resolve(lists, crews, (crewId, etag) -> {
                unread[0] = true;
                throw SocialProblem.unavailable("not kept");
            }, today);
        } catch (SocialProblem problem) {
            return null;
        }
        return unread[0] ? null : lists;
    }

    @Nullable
    private File file(@Nullable String account) {
        return SocialModel.isUserId(account) ? new File(mDir, account + ".json") : null;
    }

    private static boolean isNumber(@Nullable JsonElement e, long value) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return false;
        try {
            return e.getAsDouble() == value;
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
