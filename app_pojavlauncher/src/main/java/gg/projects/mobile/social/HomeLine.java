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
 * call the player has not answered, then a "Birlikte oyna" invite, then (M21) a crew plan the player
 * is coming to that starts within 15 minutes or began at most 30 minutes ago, then (M20) an unread
 * direct message, then
 * crewmates on Projects (two or more, of every crew together, each once), then friends on Projects,
 * then a friend request, (M21) a join request to answer or a crew invite that waits, then how many
 * friends are online. Pure Java.
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
        /** M21: a plan the player is coming to runs now or starts soon ({@link #plan}). */
        PLAN,
        CREW_ON_PROJECTS,
        FRIENDS_ON_PROJECTS,
        /** Someone asked to be the player's friend (the first of them). */
        REQUEST,
        /** M21: someone asked to join a crew the player may answer for ({@link #joinRequest}, the newest). */
        JOIN_REQUEST,
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
    /** M21: {@link Kind#PLAN}'s plan. */
    @Nullable
    public final SocialModel.Plan plan;
    /** M21: {@link Kind#JOIN_REQUEST}'s request (its crew is {@link #crew}). */
    @Nullable
    public final SocialModel.JoinRequest joinRequest;

    /** A plan shows from this long before its time (after its reminder)... */
    public static final long PLAN_WINDOW_SECONDS = 15 * 60;
    /** ...until this long after it started (the row is the way to the friends list too; the crew page keeps it). */
    public static final long PLAN_AFTER_SECONDS = 30 * 60;

    private HomeLine(Kind kind, @Nullable Call call, @Nullable Invite invite, @Nullable SocialModel.TeamInvite teamInvite,
                     List<Profile> faces, int count, boolean pending, @Nullable Crew crew, @Nullable Unread unread,
                     boolean severalCrews) {
        this(kind, call, invite, teamInvite, faces, count, pending, crew, unread, severalCrews, null, null);
    }

    private HomeLine(Kind kind, @Nullable Call call, @Nullable Invite invite, @Nullable SocialModel.TeamInvite teamInvite,
                     List<Profile> faces, int count, boolean pending, @Nullable Crew crew, @Nullable Unread unread,
                     boolean severalCrews, @Nullable SocialModel.Plan plan, @Nullable SocialModel.JoinRequest joinRequest) {
        this.plan = plan;
        this.joinRequest = joinRequest;
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
        return of(signedIn, unavailable, false, state, favourites, teams, nowSeconds, unread);
    }

    /**
     * {@code connecting}: the socket is on its way and has not said hello (M048): who is online is not known yet, so the
     * row says "Bağlanıyor" instead of a count or "nobody" (the lists, kept on the phone since 0.4.8, show before it), and
     * what does not depend on it (a call, an invite, a plan, a message, a request) still shows.
     */
    public static HomeLine of(boolean signedIn, boolean unavailable, boolean connecting, SocialState state, Set<String> favourites,
                              boolean teams, long nowSeconds, @Nullable Unread unread) {
        boolean presence = !connecting || state.live();
        List<Profile> none = new ArrayList<>();
        boolean several = teams && state.crews().size() > 1;
        if (!signedIn) return simple(Kind.SIGNED_OUT, none, 0, false, false);
        boolean pending = !state.incoming().isEmpty() || (teams && !state.teamInvites().isEmpty())
                || (teams && state.requestCount(nowSeconds) > 0);
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
        if (teams) {
            SocialModel.Plan plan = state.soonPlan(nowSeconds, PLAN_WINDOW_SECONDS, PLAN_AFTER_SECONDS);
            if (plan != null) {
                // Who is coming (the player left out), at most three faces.
                List<Profile> faces = new ArrayList<>();
                int coming = 0;
                for (java.util.Map.Entry<String, String> e : plan.answers.entrySet()) {
                    if (!SocialModel.Plan.YES.equals(e.getValue()) || e.getKey().equals(state.myId())) continue;
                    coming++;
                    Profile p = state.profile(e.getKey());
                    if (p != null && faces.size() < 3) faces.add(p);
                }
                return new HomeLine(Kind.PLAN, null, null, null, faces, coming, pending, state.crew(plan.crew), null, several,
                        plan, null);
            }
        }
        if (unread != null && state.isFriend(unread.from)) {
            List<Profile> faces = new ArrayList<>();
            Profile from = state.profile(unread.from);
            if (from != null) faces.add(from);
            return new HomeLine(Kind.MESSAGE, null, null, null, faces, unread.count, pending, null, unread, several);
        }
        if (teams && presence) {
            List<Member> crew = state.crewOnProjects();
            if (crew.size() >= 2) {
                List<Profile> faces = new ArrayList<>();
                for (Member m : crew) if (faces.size() < 3) faces.add(m.profile);
                return new HomeLine(Kind.CREW_ON_PROJECTS, null, null, null, faces, crew.size(), pending,
                        state.busiestCrewOnProjects(), null, several);
            }
        }
        List<Friend> onProjects = presence ? state.friendsOnProjects(favourites) : new ArrayList<Friend>();
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
        if (teams) {
            SocialModel.JoinRequest request = state.newestRequest(nowSeconds);
            if (request != null) {
                List<Profile> faces = new ArrayList<>();
                faces.add(request.from);
                return new HomeLine(Kind.JOIN_REQUEST, null, null, null, faces, state.requestCount(nowSeconds), pending,
                        state.crewOfRequest(request, nowSeconds), null, several, null, request);
            }
        }
        if (teams && !state.teamInvites().isEmpty()) {
            SocialModel.TeamInvite teamInvite = state.teamInvites().get(0);
            List<Profile> faces = new ArrayList<>();
            faces.add(teamInvite.from);
            return new HomeLine(Kind.CREW_INVITE, null, null, teamInvite, faces, state.teamInvites().size(), pending, null,
                    null, several);
        }
        if (state.friends().isEmpty()) return simple(Kind.NO_FRIENDS, none, 0, pending, several);
        if (!presence) return simple(Kind.LOADING, none, 0, pending, several);
        int online = state.onlineCount();
        if (online == 0) return simple(Kind.NOBODY_ONLINE, none, 0, pending, several);
        List<Profile> faces = new ArrayList<>();
        SocialState.Sections sections = state.sections(favourites);
        for (Friend f : sections.playing) if (faces.size() < 3) faces.add(f.profile);
        for (Friend f : sections.online) if (faces.size() < 3) faces.add(f.profile);
        return simple(Kind.ONLINE, faces, online, pending, several);
    }
}
