package gg.projects.mobile.social;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import gg.projects.mobile.social.SocialModel.Call;
import gg.projects.mobile.social.SocialModel.Friend;
import gg.projects.mobile.social.SocialModel.Invite;
import gg.projects.mobile.social.SocialModel.Member;
import gg.projects.mobile.social.SocialModel.Profile;

/**
 * The main menu's "Arkadaşlar" row: one line, the most useful thing first (the desktop's Home
 * priority, TEAM3-L, with invites added: on the phone the row is where an invite waits). A crewmate's
 * call the player has not answered, then a "Birlikte oyna" invite, then crewmates on Projects (two or
 * more), then friends on Projects, then how many friends are online. Pure Java.
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
        CREW_ON_PROJECTS,
        FRIENDS_ON_PROJECTS,
        ONLINE,
        NOBODY_ONLINE,
        NO_FRIENDS
    }

    public final Kind kind;
    @Nullable
    public final Call call;
    @Nullable
    public final Invite invite;
    /** The faces the row shows (at most three). */
    public final List<Profile> faces;
    /** People the line counts (crewmates or friends on Projects, friends online). */
    public final int count;
    /** Something waits for an answer elsewhere (a friend request, a crew invite): a small gold dot. */
    public final boolean pending;

    private HomeLine(Kind kind, @Nullable Call call, @Nullable Invite invite, List<Profile> faces, int count, boolean pending) {
        this.kind = kind;
        this.call = call;
        this.invite = invite;
        this.faces = Collections.unmodifiableList(faces);
        this.count = count;
        this.pending = pending;
    }

    public static HomeLine of(boolean signedIn, boolean unavailable, SocialState state, Set<String> favourites,
                              boolean teams, long nowSeconds) {
        List<Profile> none = new ArrayList<>();
        if (!signedIn) return new HomeLine(Kind.SIGNED_OUT, null, null, none, 0, false);
        boolean pending = !state.incoming().isEmpty() || (teams && !state.teamInvites().isEmpty());
        if (unavailable && !state.live()) return new HomeLine(Kind.UNAVAILABLE, null, null, none, 0, pending);
        if (!state.loaded()) return new HomeLine(Kind.LOADING, null, null, none, 0, pending);
        if (teams) {
            Call call = state.unansweredCall(nowSeconds);
            if (call != null) {
                Profile caller = state.profile(call.from);
                List<Profile> faces = new ArrayList<>();
                if (caller != null) faces.add(caller);
                return new HomeLine(Kind.CALL, call, null, faces, 1, pending);
            }
        }
        List<Invite> invites = state.invites(nowSeconds);
        if (!invites.isEmpty()) {
            Invite invite = invites.get(0);
            List<Profile> faces = new ArrayList<>();
            Profile from = state.profile(invite.fromId);
            if (from != null) faces.add(from);
            return new HomeLine(Kind.INVITE, null, invite, faces, invites.size(), pending);
        }
        if (teams) {
            List<Member> crew = state.crewOnProjects();
            if (crew.size() >= 2) {
                List<Profile> faces = new ArrayList<>();
                for (Member m : crew) if (faces.size() < 3) faces.add(m.profile);
                return new HomeLine(Kind.CREW_ON_PROJECTS, null, null, faces, crew.size(), pending);
            }
        }
        List<Friend> onProjects = state.friendsOnProjects(favourites);
        if (!onProjects.isEmpty()) {
            List<Profile> faces = new ArrayList<>();
            for (Friend f : onProjects) if (faces.size() < 3) faces.add(f.profile);
            return new HomeLine(Kind.FRIENDS_ON_PROJECTS, null, null, faces, onProjects.size(), pending);
        }
        if (state.friends().isEmpty()) return new HomeLine(Kind.NO_FRIENDS, null, null, none, 0, pending);
        int online = state.onlineCount();
        if (online == 0) return new HomeLine(Kind.NOBODY_ONLINE, null, null, none, 0, pending);
        List<Profile> faces = new ArrayList<>();
        SocialState.Sections sections = state.sections(favourites);
        for (Friend f : sections.playing) if (faces.size() < 3) faces.add(f.profile);
        for (Friend f : sections.online) if (faces.size() < 3) faces.add(f.profile);
        return new HomeLine(Kind.ONLINE, null, null, faces, online, pending);
    }
}
