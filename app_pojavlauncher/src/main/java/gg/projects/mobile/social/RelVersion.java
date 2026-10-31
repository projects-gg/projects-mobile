package gg.projects.mobile.social;

import androidx.annotation.Nullable;

import java.util.List;

/**
 * SCALE-SOC on the phone (M048; scale-soc.md, the phone's spec 2-5 and 7): the relations version ({@code rel}) of the
 * profile and the lists the phone shows. The list's answer brings it ({@link KeptLists#FROM}); the phone names it when
 * it connects ({@code /v1/presence?…&rel=}) and the {@code hello} says the service's as it is now: the same, nothing is
 * read; another, the lists are read once. It is held only while the copy is exactly what its read brought: a socket
 * event that changes the lists or the profile, a change of the player's own, a push and the session's end forget it,
 * and a read already on its way then keeps none. Main thread only; pure, with tests.
 */
final class RelVersion {
    /** What a {@code hello} means for the lists. */
    enum Hello {
        /** The service's version is the one held and the lists are loaded: nothing is read. */
        SAME,
        /** Another version (the one held is dropped: the copy is older than the service's): read by the usual rule. */
        CHANGED,
        /** No version in it (the connect named none, or an older service): read by the usual rule, the one held kept. */
        UNSAID
    }

    @Nullable
    private String mRel;
    private long mEpoch;

    /** The version held, or null. */
    @Nullable
    String held() {
        return mRel;
    }

    /** A read starts: the moment it must still find to keep the version it brings ({@link #keep}). */
    long readStarts() {
        return mEpoch;
    }

    /**
     * A read's answer (or a start's kept copy) came: its version is held unless the copy changed since the read started
     * or the version is not one ({@code null}: an older service, or an answer whose copy is not kept). True when held.
     */
    boolean keep(@Nullable String rel, long since) {
        if (since == mEpoch && KeptLists.isRel(rel)) {
            mRel = rel;
            return true;
        }
        mRel = null;
        return false;
    }

    /** The copy changed here (spec 5): no version describes it, and a read on its way keeps none. */
    void forget() {
        mEpoch++;
        mRel = null;
    }

    /** A {@code hello} came with {@code rel} (null: none) while the lists are {@code loaded} or not (spec 4). */
    Hello hello(@Nullable String rel, boolean loaded) {
        if (!KeptLists.isRel(rel)) return Hello.UNSAID;
        if (loaded && rel.equals(mRel)) return Hello.SAME;
        mRel = null;
        return Hello.CHANGED;
    }

    /** Spec 7: an unforced read waits for the hello while a version is held for loaded lists (each connection's decides). */
    boolean refreshSkips(boolean force, boolean loaded) {
        return !force && loaded && mRel != null;
    }

    /** The presence socket's path: what the app understands ({@code ?caps=}) and the version held ({@code &rel=}). */
    static String presencePath(List<String> caps, @Nullable String rel) {
        StringBuilder path = new StringBuilder("v1/presence");
        char next = '?';
        if (!caps.isEmpty()) {
            path.append("?caps=");
            for (int i = 0; i < caps.size(); i++) path.append(i == 0 ? "" : ",").append(caps.get(i));
            next = '&';
        }
        if (KeptLists.isRel(rel)) path.append(next).append("rel=").append(rel);
        return path.toString();
    }

    /**
     * The socket frames that change what the list's answer shows, the profile included (spec 5; the launcher's
     * {@code changes_relations}): requests, friends, blocks' and crews' changes, invites to crews and join requests, the
     * player's crew order, mutes and notification settings, categories, tags and marks, and the player's own status,
     * profile and moderators' notices from another device. Presence, game invites, calls, plans and chat are not.
     */
    static boolean changesRelations(@Nullable String type) {
        if (type == null) return false;
        switch (type) {
            case "friend_added":
            case "friend_removed":
            case "request":
            case "request_cancelled":
            case "team_invite":
            case "team":
            case "team_requests":
            case "crew_order":
            case "crew_notifications":
            case "team_notifications":
            case "groups":
            case "badge":
            case "mark":
            case "status":
            case "profile":
            case "notice":
                return true;
            default:
                return false;
        }
    }
}
