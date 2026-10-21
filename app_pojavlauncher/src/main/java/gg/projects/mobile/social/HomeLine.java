package gg.projects.mobile.social;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import gg.projects.mobile.social.SocialModel.Call;
import gg.projects.mobile.social.SocialModel.Crew;
import gg.projects.mobile.social.SocialModel.Friend;
import gg.projects.mobile.social.SocialModel.Invite;
import gg.projects.mobile.social.SocialModel.Member;
import gg.projects.mobile.social.SocialModel.Profile;

/**
 * The main menu's "Arkadaşlar" row: one line, the most useful thing first (the desktop's Home
 * priority, TEAM3-L, with invites added: on the phone the row is where an invite waits). A crewmate's
 * call the player has not answered, then a "Birlikte oyna" invite, then (M20) an unread direct
 * message, then crewmates on Projects (two or more, of every crew together, each once), then friends
 * on Projects, then a friend request or a crew invite that waits, then how many friends are online.
 * Pure Java.
 */
public final class HomeLine {
    public enum Kind {
        /** Friends are on but nobody is signed in: "Discord ile bağlan". */
        SIGNED_OUT,
        /** Signed in, the list is not there yet. */
        LOADING,
        /** The service does not answer. */
        UNAVAILABLE,
        CALL,
        INVITE,
        /** M20: a friend wrote to the player ({@link #unread}). */
        MESSAGE,
        CREW_ON_PROJECTS,
        FRIENDS_ON_PROJECTS,
        /** Someone asked to be the player's friend (the first of them). */
        REQUEST,
        /** A crew invite waits. */
        CREW_INVITE,
        ONLINE,
        NOBODY_ONLINE,
        NO_FRIENDS
    }

    /** M20: the newest direct conversation with unread messages, and how many are unread in all. */
    public static final class Unread {
        public final String conv;
        public final String from;
        /** Unread in that conversation. */
        public final int count;
        /** Direct conversations with unread messages. */
        public final int conversations;

        public Unread(String conv, String from, int count, int conversations) {
            this.conv = conv;
            this.from = from;
            this.count = count;
            this.conversations = conversations;
        }
    }

    public final Kind kind;
    @Nullable
    public final Call call;
    @Nullable
    public final Invite invite;
    /** The crew invite of {@link Kind#CREW_INVITE}. */
    @Nullable
    public final SocialModel.TeamInvite teamInvite;
    /** The faces the row shows (at most three). */
    public final List<Profile> faces;
    /** People the line counts (crewmates or friends on Projects, friends online). */
    public final int count;
    /** Something waits for an answer elsewhere (a friend request, a crew invite): a small gold dot. */
    public final boolean pending;
    /** M20: the crew a tap opens (the call's first crew, the crew with the most players on Projects). */
    @Nullable
    public final Crew crew;
    /** M20: {@link Kind#MESSAGE}'s conversation. */
    @Nullable
    public final Unread unread;
    /** M20: the player is in more than one crew (the line counts all of them). */
    public final boolean severalCrews;

    private HomeLine(Kind kind, @Nullable Call call, @Nullable Invite invite, @Nullable SocialModel.TeamInvite teamInvite,
                     List<Profile> faces, int count, boolean pending, @Nullable Crew crew, @Nullable Unread unread,
                     boolean severalCrews) {
        this.kind = kind;
        this.call = call;
        this.invite = invite;
        this.teamInvite = teamInvite;
        this.faces = Collections.unmodifiableList(faces);
        this.count = count;
        this.pending = pending;
        this.crew = crew;
        this.unread = unread;
        this.severalCrews = severalCrews;
    }

    private static HomeLine simple(Kind kind, List<Profile> faces, int count, boolean pending, boolean several) {
        return new HomeLine(kind, null, null, null, faces, count, pending, null, null, several);
    }

    public static HomeLine of(boolean signedIn, boolean unavailable, SocialState state, Set<String> favourites,
                              boolean teams, long nowSeconds) {
        return of(signedIn, unavailable, state, favourites, teams, nowSeconds, null);
    }

    public static HomeLine of(boolean signedIn, boolean unavailable, SocialState state, Set<String> favourites,
                              boolean teams, long nowSeconds, @Nullable Unread unread) {
        List<Profile> none = new ArrayList<>();
        boolean several = teams && state.crews().size() > 1;
        if (!signedIn) return simple(Kind.SIGNED_OUT, none, 0, false, false);
        boolean pending = !state.incoming().isEmpty() || (teams && !state.teamInvites().isEmpty());
        if (unavailable && !state.live()) return simple(Kind.UNAVAILABLE, none, 0, pending, several);
        if (!state.loaded()) return simple(Kind.LOADING, none, 0, pending, several);
        if (teams) {
            Call call = state.unansweredCall(nowSeconds);
            if (call != null) {
                Profile caller = state.profile(call.from);
                List<Profile> faces = new ArrayList<>();
                if (caller != null) faces.add(caller);
                List<Crew> crews = state.callCrews(call);
                return new HomeLine(Kind.CALL, call, null, null, faces, 1, pending, crews.isEmpty() ? null : crews.get(0), null,
                        several);
            }
        }
        List<Invite> invites = state.invites(nowSeconds);
        if (!invites.isEmpty()) {
            Invite invite = invites.get(0);
            List<Profile> faces = new ArrayList<>();
            Profile from = state.profile(invite.fromId);
            if (from != null) faces.add(from);
            return new HomeLine(Kind.INVITE, null, invite, null, faces, invites.size(), pending, null, null, several);
        }
        if (unread != null && state.isFriend(unread.from)) {
            List<Profile> faces = new ArrayList<>();
            Profile from = state.profile(unread.from);
            if (from != null) faces.add(from);
            return new HomeLine(Kind.MESSAGE, null, null, null, faces, unread.count, pending, null, unread, several);
        }
        if (teams) {
            List<Member> crew = state.crewOnProjects();
            if (crew.size() >= 2) {
                List<Profile> faces = new ArrayList<>();
                for (Member m : crew) if (faces.size() < 3) faces.add(m.profile);
                return new HomeLine(Kind.CREW_ON_PROJECTS, null, null, null, faces, crew.size(), pending,
                        state.busiestCrewOnProjects(), null, several);
            }
        }
        List<Friend> onProjects = state.friendsOnProjects(favourites);
        if (!onProjects.isEmpty()) {
            List<Profile> faces = new ArrayList<>();
            for (Friend f : onProjects) if (faces.size() < 3) faces.add(f.profile);
            return simple(Kind.FRIENDS_ON_PROJECTS, faces, onProjects.size(), pending, several);
        }
        // Something that waits for the player's answer says so before how many are online.
        if (!state.incoming().isEmpty()) {
            List<Profile> faces = new ArrayList<>();
            faces.add(state.incoming().get(0).profile);
            return simple(Kind.REQUEST, faces, state.incoming().size(), pending, several);
        }
        if (teams && !state.teamInvites().isEmpty()) {
            SocialModel.TeamInvite teamInvite = state.teamInvites().get(0);
            List<Profile> faces = new ArrayList<>();
            faces.add(teamInvite.from);
            return new HomeLine(Kind.CREW_INVITE, null, null, teamInvite, faces, state.teamInvites().size(), pending, null,
                    null, several);
        }
        if (state.friends().isEmpty()) return simple(Kind.NO_FRIENDS, none, 0, pending, several);
        int online = state.onlineCount();
        if (online == 0) return simple(Kind.NOBODY_ONLINE, none, 0, pending, several);
        List<Profile> faces = new ArrayList<>();
        SocialState.Sections sections = state.sections(favourites);
        for (Friend f : sections.playing) if (faces.size() < 3) faces.add(f.profile);
        for (Friend f : sections.online) if (faces.size() < 3) faces.add(f.profile);
        return simple(Kind.ONLINE, faces, online, pending, several);
    }
}
