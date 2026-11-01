package gg.projects.mobile.social;

import androidx.annotation.Nullable;

import java.util.Collection;

/**
 * BADGES: the badge catalogue the app draws with, and when it is read again ({@code GET /v1/marks}). It starts with the
 * built-in one ({@link MarkCatalog#BUILTIN}), takes the copy kept on the phone ({@link MarkStore}) once that is read,
 * and reads the service's:
 * <ul>
 * <li>when the service names a revision other than the one held (the list's {@code marksRev}, a {@code markdefs}
 * frame);</li>
 * <li>when a card names a badge the catalogue does not know (it was made with a newer one), at most every
 * {@link #UNKNOWN_GAP_MS} and only from a service that named a revision: one from before badges has no catalogue.</li>
 * </ul>
 * One read at a time; a trigger while one is on its way reads once more after it, if it still holds. A {@code 304} or a
 * failure keeps what is held, and nothing reads again until the next trigger (a list read, a frame), so a service whose
 * answers disagree is never read in a loop. Main thread; pure Java, with tests.
 */
public final class MarkBook {
    /** A card naming an unknown badge reads the catalogue at most this often (the desktop: 10 minutes, it keeps no copy). */
    public static final long UNKNOWN_GAP_MS = 30L * 60 * 1000;

    private MarkCatalog mCatalog = MarkCatalog.BUILTIN;
    @Nullable
    private String mEtag;
    /** The kept copy is read (or there was none): reads wait for it, so a start never reads what it keeps. */
    private boolean mLoaded;
    private boolean mLoadAsked;
    /** The revision the service named last; -1 before it named one. */
    private long mNamed = -1;
    private boolean mReading;
    /** A trigger came: the next read decision is taken (at load, or when the read on its way ends). */
    private boolean mPending;
    /** An unknown badge passed the 30-minute rule: the next read goes even for the revision held. */
    private boolean mForce;
    /** When an unknown badge last made a read (elapsed milliseconds); none yet when {@link #mUnknownOnce} is false. */
    private long mUnknownAt;
    private boolean mUnknownOnce;

    /** The catalogue to draw with now. */
    public MarkCatalog catalog() {
        return mCatalog;
    }

    /** True the first time only: the caller reads the kept copy (off the main thread) and hands it to {@link #loaded}. */
    public boolean takeLoad() {
        if (mLoadAsked) return false;
        mLoadAsked = true;
        return true;
    }

    /**
     * The copy kept on the phone (null: none or unreadable). It draws until a read brings the service's. True when a read
     * should start now (a revision named meanwhile is not the kept one).
     */
    public boolean loaded(@Nullable MarkStore.Kept kept) {
        if (mLoaded) return false;
        mLoaded = true;
        mLoadAsked = true;
        if (kept != null) {
            mCatalog = kept.catalog;
            mEtag = kept.etag;
        }
        return decide();
    }

    /** The service named its catalogue's revision ({@code marksRev}, {@code markdefs}). True when a read should start now. */
    public boolean named(long rev) {
        if (rev < 0) return false;
        mNamed = rev;
        if (rev == mCatalog.rev && !mForce) return false;
        mPending = true;
        return decide();
    }

    /**
     * Cards named these badges ({@code nowMs}: elapsed time). True when one is not in the catalogue, the service named a
     * revision, the 30-minute rule allows it and a read should start now. Not while the kept copy is still read or a read
     * is on its way: either may know it, and the next card that still names it decides.
     */
    public boolean saw(Collection<String> keys, long nowMs) {
        if (mNamed < 0 || !mLoaded || mReading || keys.isEmpty()) return false;
        boolean unknown = false;
        for (String key : keys) {
            if (mCatalog.get(key) == null) {
                unknown = true;
                break;
            }
        }
        if (!unknown) return false;
        if (mUnknownOnce && nowMs - mUnknownAt < UNKNOWN_GAP_MS) return false;
        mUnknownOnce = true;
        mUnknownAt = nowMs;
        mForce = true;
        mPending = true;
        return decide();
    }

    /**
     * The one later try after a failed read (the caller waits a minute, once): true when the revision the service named is
     * still not the one held and a read should start now.
     */
    public boolean retry() {
        return named(mNamed);
    }

    /** A read starts: the ETag it sends ({@code If-None-Match}; null: none held). */
    @Nullable
    public String begin() {
        mReading = true;
        mPending = false;
        mForce = false;
        return mEtag;
    }

    /** Whether a read is on its way. */
    public boolean reading() {
        return mReading;
    }

    /**
     * A read ended: {@code read} the service's catalogue (null: not modified, or the read failed; the one held stays).
     * True when another read should start now (a trigger came meanwhile and still holds).
     */
    public boolean ended(@Nullable MarkStore.Kept read) {
        mReading = false;
        if (read != null) {
            mCatalog = read.catalog;
            mEtag = read.etag;
        }
        return decide();
    }

    /** A read goes when the kept copy is in, none is on its way, and a trigger still holds. */
    private boolean decide() {
        if (!mLoaded || mReading || !mPending) return false;
        if (mForce || (mNamed >= 0 && mNamed != mCatalog.rev)) return true;
        mPending = false;
        return false;
    }
}
