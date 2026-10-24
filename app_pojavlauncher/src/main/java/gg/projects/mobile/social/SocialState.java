package gg.projects.mobile.social;

import androidx.annotation.Nullable;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import gg.projects.mobile.social.SocialModel.Badge;
import gg.projects.mobile.social.SocialModel.Call;
import gg.projects.mobile.social.SocialModel.ChatState;
import gg.projects.mobile.social.SocialModel.Crew;
import gg.projects.mobile.social.SocialModel.Friend;
import gg.projects.mobile.social.SocialModel.FriendsData;
import gg.projects.mobile.social.SocialModel.Invite;
import gg.projects.mobile.social.SocialModel.JoinRequest;
import gg.projects.mobile.social.SocialModel.Limits;
import gg.projects.mobile.social.SocialModel.Me;
import gg.projects.mobile.social.SocialModel.Member;
import gg.projects.mobile.social.SocialModel.ModNotice;
import gg.projects.mobile.social.SocialModel.MyRequest;
import gg.projects.mobile.social.SocialModel.Plan;
import gg.projects.mobile.social.SocialModel.Presence;
import gg.projects.mobile.social.SocialModel.Profile;
import gg.projects.mobile.social.SocialModel.Request;
import gg.projects.mobile.social.SocialModel.Team;
import gg.projects.mobile.social.SocialModel.TeamInvite;
import gg.projects.mobile.social.SocialModel.TeamNotifications;

/**
 * What the app knows about the player's friends and crews: the friend list from the service, made
 * live by the presence socket's messages (services/social README "Presence WebSocket"). Pure Java
 * and JVM-tested; every change returns the {@link Notice}s the app may announce. Times are Unix
 * seconds by the phone's clock; crew calls follow the hub's clock through the offset {@code hello}
 * and {@code call} carry.
 *
 * <p>M20 (SOC7): a player may be in several crews ({@code crews}, the primary first; a service
 * without {@code multi} has one, its {@code team}), calls name the crews they go to and carry their
 * server, and the socket brings the chat state, moderators' notes and the end of a restricted
 * account's session. Chat messages themselves are {@link ChatBook}'s.
 *
 * <p>M21 (TEAM5): the player's own join requests ({@code teamRequests}) and, for those who may answer,
 * each crew's waiting ones ({@code crews[].requests}, live through {@code team_requests}); the crew's
 * invite link and join setting ride on the crew card; plans come only from the socket ({@code
 * hello.plans}, {@code plan}, {@code plans}, {@code plan_removed}) and are over by the hub's clock.
 *
 * <p>MOB1 (TEAM6): the crew tag each friend, crewmate and the player wears ({@code badge} of their
 * profiles, live through {@code badge} and {@code team} messages), hidden crews, and who just joined a
 * crew of the player's (the crew chat's "Hoş geldin" line, kept in memory like the desktop's).
 */
public final class SocialState {
    @Nullable
    private Me mMe;
    private final Map<String, Friend> mFriends = new LinkedHashMap<>();
    private final List<Request> mIncoming = new ArrayList<>();
    private final List<Request> mOutgoing = new ArrayList<>();
    private final List<Profile> mBlocked = new ArrayList<>();
    private Limits mLimits = new Limits(0, 0, 0);
    /** The player's crews in their order; the first is the primary crew. */
    private final List<Crew> mCrews = new ArrayList<>();
    private int mCrewLimit;
    private final List<TeamInvite> mTeamInvites = new ArrayList<>();
    private Set<String> mTeamFeatures = new HashSet<>();
    private TeamNotifications mTeamNotifications = TeamNotifications.DEFAULT;
    @Nullable
    private Set<String> mFeatures;
    private boolean mLoaded;

    /** Online friends and teammates by id (absent: offline). Filled only while {@link #mLive}. */
    private final Map<String, Presence> mPresence = new HashMap<>();
    private final Map<String, Invite> mInvites = new LinkedHashMap<>();
    private final Map<String, Call> mCalls = new LinkedHashMap<>();
    private final Set<String> mDismissedInvites = new HashSet<>();
    /** The hub's clock minus the phone's (seconds). */
    private long mClockOffset;
    private boolean mLive;
    /** The capabilities the hub took from the socket's query ({@code hello.caps}). */
    private Set<String> mCaps = new HashSet<>();
    private ChatState mChat = ChatState.UNKNOWN;
    /** {@code bye} with {@code restricted}: until when (Unix seconds; 0: for good); null when not restricted. */
    @Nullable
    private Long mRestrictedUntil;
    /** TEAM5: the player's own waiting join requests, newest first. */
    private final List<MyRequest> mMyRequests = new ArrayList<>();
    /** TEAM5: the plans of the player's crews the player sees, by id (from the socket only). */
    private final Map<String, Plan> mPlans = new LinkedHashMap<>();
    /** TEAM5: a {@code hello} with plans came (the socket took {@code plans}): {@link #mPlans} is the hub's view. */
    private boolean mPlansKnown;
    /** TEAM6: who joined the player's crews while the app ran (the crew chat's local "Hoş geldin" lines), oldest first. */
    private final List<CrewJoin> mJoins = new ArrayList<>();

    /** TEAM6: someone (or the player) came into one of the player's crews, at {@code at} (Unix seconds, the phone's clock). */
    public static final class CrewJoin {
        /** The "Selam ver" button goes this long after the join (the desktop's 10 minutes). */
        public static final long GREET_SECONDS = 600;
        /** A join line stays this long in memory. */
        public static final long KEEP_SECONDS = 86_400;

        public final String crewId;
        public final String userId;
        public final long at;

        public CrewJoin(String crewId, String userId, long at) {
            this.crewId = crewId;
            this.userId = userId;
            this.at = at;
        }

        /** "Selam ver" still offered at {@code nowSeconds}. */
        public boolean greetable(long nowSeconds) {
            return nowSeconds < at + GREET_SECONDS;
        }
    }

    /** Something the app may tell the player about. */
    public static final class Notice {
        public enum Kind {
            REQUEST, INVITE, CALL, CALL_ENDED, FRIEND_ADDED, TEAM_INVITE, TEAM_GONE,
            /** M20: a moderator's note came live ({@link #modNotice}). */
            MOD_NOTICE,
            /** M20: the account was suspended or banned ({@link #until}; 0 for good). */
            RESTRICTED,
            /** TEAM5: {@link #who} asked to join {@link #crewId}, a crew the player may answer for. */
            JOIN_REQUEST,
            /** TEAM5: the player's join request to {@link #crewId} was accepted (its crew came). */
            REQUEST_ACCEPTED,
            /** TEAM5: {@link #who} made {@link #plan} in one of the player's crews (not quiet). */
            PLAN,
            /** TEAM5: a plan was cancelled ({@link #planId}). */
            PLAN_REMOVED,
            /** TEAM6: the leader hid {@link #teamName}, so the player's tag of it came off. */
            TAG_OFF
        }

        public final Kind kind;
        @Nullable
        public final Profile who;
        @Nullable
        public final Invite invite;
        @Nullable
        public final Call call;
        @Nullable
        public final TeamInvite teamInvite;
        /** TEAM_GONE: {@code left}, {@code kicked}, {@code disbanded} or null. */
        @Nullable
        public final String reason;
        /** TEAM_GONE: the crew's name. */
        @Nullable
        public final String teamName;
        /** TEAM_GONE: the player did it on another device (nothing to tell). */
        public final boolean byMe;
        /** CALL and CALL_ENDED: the call's id. */
        @Nullable
        public final String callId;
        /** TEAM_GONE: Projects' moderators did it. */
        public final boolean byStaff;
        /** CALL: an open call moved to another server (not a new one). */
        public final boolean moved;
        @Nullable
        public final ModNotice modNotice;
        /** RESTRICTED: until when (Unix seconds; 0 for good). */
        public final long until;
        /** TEAM5: the crew of JOIN_REQUEST, REQUEST_ACCEPTED, PLAN and PLAN_REMOVED. */
        @Nullable
        public final String crewId;
        /** TEAM5: PLAN's plan. */
        @Nullable
        public final Plan plan;
        /** TEAM5: PLAN's and PLAN_REMOVED's plan id. */
        @Nullable
        public final String planId;

        Notice(Kind kind, @Nullable Profile who, @Nullable Invite invite, @Nullable Call call,
               @Nullable TeamInvite teamInvite, @Nullable String reason, @Nullable String teamName, boolean byMe) {
            this(kind, who, invite, call, teamInvite, reason, teamName, byMe, call == null ? null : call.id, false, false, null, 0);
        }

        private Notice(Kind kind, @Nullable Profile who, @Nullable Invite invite, @Nullable Call call,
                       @Nullable TeamInvite teamInvite, @Nullable String reason, @Nullable String teamName, boolean byMe,
                       @Nullable String callId, boolean byStaff, boolean moved, @Nullable ModNotice modNotice, long until) {
            this(kind, who, invite, call, teamInvite, reason, teamName, byMe, callId, byStaff, moved, modNotice, until, null, null, null);
        }

        private Notice(Kind kind, @Nullable Profile who, @Nullable Invite invite, @Nullable Call call,
                       @Nullable TeamInvite teamInvite, @Nullable String reason, @Nullable String teamName, boolean byMe,
                       @Nullable String callId, boolean byStaff, boolean moved, @Nullable ModNotice modNotice, long until,
                       @Nullable String crewId, @Nullable Plan plan, @Nullable String planId) {
            this.kind = kind;
            this.who = who;
            this.invite = invite;
            this.call = call;
            this.teamInvite = teamInvite;
            this.reason = reason;
            this.teamName = teamName;
            this.byMe = byMe;
            this.callId = callId;
            this.byStaff = byStaff;
            this.moved = moved;
            this.modNotice = modNotice;
            this.until = until;
            this.crewId = crewId;
            this.plan = plan;
            this.planId = planId;
        }

        static Notice joinRequest(Profile who, String crewId, String teamName) {
            return new Notice(Kind.JOIN_REQUEST, who, null, null, null, null, teamName, false, null, false, false, null, 0,
                    crewId, null, null);
        }

        static Notice requestAccepted(String crewId, String teamName) {
            return new Notice(Kind.REQUEST_ACCEPTED, null, null, null, null, null, teamName, false, null, false, false, null, 0,
                    crewId, null, null);
        }

        static Notice plan(@Nullable Profile maker, Plan plan, @Nullable String teamName) {
            return new Notice(Kind.PLAN, maker, null, null, null, null, teamName, false, null, false, false, null, 0,
                    plan.crew, plan, plan.id);
        }

        static Notice planRemoved(String planId, @Nullable String crewId, boolean byMe) {
            return new Notice(Kind.PLAN_REMOVED, null, null, null, null, null, null, byMe, null, false, false, null, 0,
                    crewId, null, planId);
        }

        static Notice of(Kind kind, @Nullable Profile who) {
            return new Notice(kind, who, null, null, null, null, null, false);
        }

        static Notice callEnded(String callId, @Nullable String reason) {
            return new Notice(Kind.CALL_ENDED, null, null, null, null, reason, null, false, callId, false, false, null, 0);
        }

        static Notice call(@Nullable Profile caller, Call call, boolean moved) {
            return new Notice(Kind.CALL, caller, null, call, null, null, null, false, call.id, false, moved, null, 0);
        }

        static Notice teamGone(@Nullable String reason, String name, boolean byMe, boolean byStaff) {
            return new Notice(Kind.TEAM_GONE, null, null, null, null, reason, name, byMe, null, byStaff, false, null, 0);
        }

        static Notice modNotice(ModNotice notice) {
            return new Notice(Kind.MOD_NOTICE, null, null, null, null, notice.kind, null, false, null, true, false, notice, 0);
        }

        static Notice tagOff(String crewId, String teamName) {
            return new Notice(Kind.TAG_OFF, null, null, null, null, null, teamName, false, null, false, false, null, 0,
                    crewId, null, null);
        }

        static Notice restricted(long until) {
            return new Notice(Kind.RESTRICTED, null, null, null, null, "restricted", null, false, null, true, false, null, until);
        }
    }

    // ---- reading ----

    @Nullable
    public Me me() {
        return mMe;
    }

    @Nullable
    public String myId() {
        return mMe == null ? null : mMe.profile.id;
    }

    public boolean loaded() {
        return mLoaded;
    }

    public boolean live() {
        return mLive;
    }

    public List<Friend> friends() {
        return new ArrayList<>(mFriends.values());
    }

    @Nullable
    public Friend friend(String id) {
        return mFriends.get(id);
    }

    public boolean isFriend(String id) {
        return mFriends.containsKey(id);
    }

    public List<Request> incoming() {
        return Collections.unmodifiableList(new ArrayList<>(mIncoming));
    }

    public List<Request> outgoing() {
        return Collections.unmodifiableList(new ArrayList<>(mOutgoing));
    }

    public List<Profile> blocked() {
        return Collections.unmodifiableList(new ArrayList<>(mBlocked));
    }

    public Limits limits() {
        return mLimits;
    }

    /** The primary crew (the one apps that know one crew show), or null. */
    @Nullable
    public Team team() {
        return mCrews.isEmpty() ? null : mCrews.get(0).team;
    }

    /** Friends the player invited to the primary crew who have not answered. */
    public Set<String> teamInvited() {
        return mCrews.isEmpty() ? Collections.<String>emptySet() : mCrews.get(0).invited;
    }

    /** TEAM4: every crew of the player in their order (the primary first). */
    public List<Crew> crews() {
        return Collections.unmodifiableList(new ArrayList<>(mCrews));
    }

    @Nullable
    public Crew crew(@Nullable String id) {
        if (id == null) return null;
        for (Crew c : mCrews) if (c.id().equals(id)) return c;
        return null;
    }

    public List<String> crewIds() {
        List<String> out = new ArrayList<>();
        for (Crew c : mCrews) out.add(c.id());
        return out;
    }

    /** {@code crewLimit} of the service (0: it did not say; then one crew). */
    public int crewLimit() {
        return mCrewLimit;
    }

    /** The player is in as many crews as the service allows: founding and joining wait. */
    public boolean atCrewLimit() {
        int limit = mCrewLimit > 0 ? mCrewLimit : 1;
        return mCrews.size() >= limit;
    }

    public List<TeamInvite> teamInvites() {
        return Collections.unmodifiableList(new ArrayList<>(mTeamInvites));
    }

    public boolean hasTeamFeature(String feature) {
        return mTeamFeatures.contains(feature);
    }

    public Set<String> teamFeatures() {
        return Collections.unmodifiableSet(mTeamFeatures);
    }

    public TeamNotifications teamNotifications() {
        return mTeamNotifications;
    }

    /** {@code features} of the service; null for a service from before pushes. */
    @Nullable
    public Set<String> features() {
        return mFeatures == null ? null : Collections.unmodifiableSet(mFeatures);
    }

    /** The service has crews (it announces their limit): the first crew release or later. */
    public boolean serviceHasTeams() {
        return mLimits.teamMembers > 0;
    }

    /** The capabilities the hub took ({@code hello.caps}); empty before the first {@code hello}. */
    public Set<String> caps() {
        return Collections.unmodifiableSet(mCaps);
    }

    public ChatState chat() {
        return mChat;
    }

    /** The account was suspended ({@code until} &gt; 0) or banned (0); null when not. */
    @Nullable
    public Long restrictedUntil() {
        return mRestrictedUntil;
    }

    /** Presence of a friend or teammate; offline when unknown. */
    public Presence presence(String id) {
        Presence p = mPresence.get(id);
        if (p != null) return p;
        Friend f = mFriends.get(id);
        return Presence.offline(id, f == null ? null : f.lastSeen);
    }

    public long hubNow(long localNowSeconds) {
        return localNowSeconds + mClockOffset;
    }

    /** "Birlikte oyna" invites that still run, newest first (the ones the player closed left out). */
    public List<Invite> invites(long nowSeconds) {
        List<Invite> out = new ArrayList<>();
        for (Invite invite : mInvites.values()) {
            if (!invite.expired(nowSeconds) && mFriends.containsKey(invite.fromId) && !mDismissedInvites.contains(invite.id)) {
                out.add(invite);
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(b.at, a.at));
        return out;
    }

    @Nullable
    public Invite invite(String id) {
        return mInvites.get(id);
    }

    /** Crew calls still open by the hub's clock, oldest first. */
    public List<Call> calls(long nowSeconds) {
        long hub = hubNow(nowSeconds);
        List<Call> out = new ArrayList<>();
        for (Call call : mCalls.values()) if (call.until > hub) out.add(call);
        Collections.sort(out, (a, b) -> Long.compare(a.at, b.at));
        return out;
    }

    /** Open calls that name {@code crewId} (from an older service: every call names the primary crew). */
    public List<Call> calls(long nowSeconds, String crewId) {
        List<Call> out = new ArrayList<>();
        for (Call call : calls(nowSeconds)) if (callCrewIds(call).contains(crewId)) out.add(call);
        return out;
    }

    @Nullable
    public Call call(String id) {
        return mCalls.get(id);
    }

    /** The player's crews a call names, in the player's order (an older service's call: the primary crew). */
    public List<String> callCrewIds(Call call) {
        List<String> out = new ArrayList<>();
        if (call.crews.isEmpty()) {
            if (!mCrews.isEmpty()) out.add(mCrews.get(0).id());
            return out;
        }
        for (Crew c : mCrews) if (call.crews.contains(c.id())) out.add(c.id());
        return out;
    }

    /** The crews of {@link #callCrewIds}. */
    public List<Crew> callCrews(Call call) {
        List<Crew> out = new ArrayList<>();
        for (String id : callCrewIds(call)) {
            Crew c = crew(id);
            if (c != null) out.add(c);
        }
        return out;
    }

    /**
     * A call reaches the player loudly: crew calls on, not every crew muted (the all-crews mute) and
     * at least one crew the call names not muted on its own (TEAM4's per-crew mute).
     */
    public boolean callAudible(Call call, long nowSeconds) {
        long hub = hubNow(nowSeconds);
        if (!mTeamNotifications.callsAudible(hub)) return false;
        List<Crew> crews = callCrews(call);
        if (crews.isEmpty()) return true;
        for (Crew c : crews) if (!c.mute.mutedAt(hub)) return true;
        return false;
    }

    /** The call the player started, while it is open. */
    @Nullable
    public Call ownCall(long nowSeconds) {
        String me = myId();
        if (me == null) return null;
        for (Call call : calls(nowSeconds)) if (call.from.equals(me)) return call;
        return null;
    }

    /** A crewmate's open call the player has not answered yet (the one the home screen offers). */
    @Nullable
    public Call unansweredCall(long nowSeconds) {
        String me = myId();
        if (me == null) return null;
        for (Call call : calls(nowSeconds)) {
            if (!call.from.equals(me) && !call.answers.containsKey(me)) return call;
        }
        return null;
    }

    /** Seconds left in {@code call} by the hub's clock. */
    public long secondsLeft(Call call, long nowSeconds) {
        return Math.max(0, call.until - hubNow(nowSeconds));
    }

    /** A crewmate's or friend's profile by id (the crews' member lists know teammates who are not friends). */
    @Nullable
    public Profile profile(String id) {
        Friend f = mFriends.get(id);
        if (f != null) return f.profile;
        for (Crew c : mCrews) {
            Member m = c.team.member(id);
            if (m != null) return m.profile;
        }
        if (mMe != null && mMe.profile.id.equals(id)) return mMe.profile;
        return null;
    }

    /** The player's crews {@code userId} is in too (in the player's order). */
    public List<Crew> crewsOf(String userId) {
        List<Crew> out = new ArrayList<>();
        for (Crew c : mCrews) if (c.team.member(userId) != null) out.add(c);
        return out;
    }

    /** TEAM6: who joined {@code crewId} while the app ran (the last day), oldest first. */
    public List<CrewJoin> joins(String crewId, long nowSeconds) {
        List<CrewJoin> out = new ArrayList<>();
        for (CrewJoin j : mJoins) if (j.crewId.equals(crewId) && nowSeconds - j.at < CrewJoin.KEEP_SECONDS) out.add(j);
        return out;
    }

    /** TEAM6: someone came into a crew (also the player, from an answer of their own): the chat's join line. */
    public void noteJoin(String crewId, String userId, long nowSeconds) {
        for (CrewJoin j : mJoins) if (j.crewId.equals(crewId) && j.userId.equals(userId) && nowSeconds - j.at < 60) return;
        mJoins.add(new CrewJoin(crewId, userId, nowSeconds));
        while (mJoins.size() > 60) mJoins.remove(0);
    }

    /** TEAM6: the tag a player wears as the player's own lists know it (a friend's, a crewmate's, their own); null: none. */
    @Nullable
    public Badge badgeOf(String userId) {
        Profile p = profile(userId);
        return p == null ? null : p.badge;
    }

    // ---- joining a crew (TEAM5) ----

    /** The player's own join requests that still wait at {@code nowSeconds}, newest first. */
    public List<MyRequest> myRequests(long nowSeconds) {
        List<MyRequest> out = new ArrayList<>();
        for (MyRequest r : mMyRequests) if (r.waiting(nowSeconds) && crew(r.team.id) == null) out.add(r);
        return out;
    }

    /** The player's waiting join request to {@code teamId}, or null. */
    @Nullable
    public MyRequest myRequest(String teamId, long nowSeconds) {
        for (MyRequest r : myRequests(nowSeconds)) if (r.team.id.equals(teamId)) return r;
        return null;
    }

    /** The player may answer {@code crewId}'s join requests and manage its invite link ({@code permissions.admit}). */
    public boolean mayAdmit(@Nullable String crewId) {
        Crew c = crew(crewId);
        String me = myId();
        return c != null && me != null && c.team.allows(me, c.team.permissions.admit);
    }

    /** The waiting join requests of {@code crewId} the player may answer, oldest first. */
    public List<JoinRequest> requests(@Nullable String crewId, long nowSeconds) {
        List<JoinRequest> out = new ArrayList<>();
        Crew c = crew(crewId);
        if (c == null || !mayAdmit(crewId)) return out;
        for (JoinRequest r : c.requests) if (r.waiting(nowSeconds) && c.team.member(r.from.id) == null) out.add(r);
        return out;
    }

    /**
     * The newest join request of any crew the player may answer for and has not muted (the home line
     * names it), or null. Like the desktop's friends button, a muted crew's requests wait on its page.
     */
    @Nullable
    public JoinRequest newestRequest(long nowSeconds) {
        JoinRequest newest = null;
        for (Crew c : mCrews) {
            if (crewMuted(c, nowSeconds)) continue;
            for (JoinRequest r : requests(c.id(), nowSeconds)) if (newest == null || r.sentAt > newest.sentAt) newest = r;
        }
        return newest;
    }

    /** The crew a join request (of {@link #newestRequest}) waits in, or null. */
    @Nullable
    public Crew crewOfRequest(JoinRequest request, long nowSeconds) {
        for (Crew c : mCrews) {
            for (JoinRequest r : requests(c.id(), nowSeconds)) {
                if (r.from.id.equals(request.from.id) && r.sentAt == request.sentAt) return c;
            }
        }
        return null;
    }

    /** Join requests waiting for the player's answer, every crew the player has not muted together. */
    public int requestCount(long nowSeconds) {
        int n = 0;
        for (Crew c : mCrews) if (!crewMuted(c, nowSeconds)) n += requests(c.id(), nowSeconds).size();
        return n;
    }

    /** The player muted this crew, or every crew, at the moment (the hub's clock). */
    private boolean crewMuted(Crew c, long nowSeconds) {
        long hub = hubNow(nowSeconds);
        return mTeamNotifications.mutedAt(hub) || c.mute.mutedAt(hub);
    }

    // ---- plans (TEAM5) ----

    /** A {@code hello} with plans came: the plans are the hub's (reminders may follow them). */
    public boolean plansKnown() {
        return mPlansKnown;
    }

    @Nullable
    public Plan plan(@Nullable String id) {
        return id == null ? null : mPlans.get(id);
    }

    /** Every plan of the player's crews that is not over by the hub's clock, soonest first. */
    public List<Plan> plans(long nowSeconds) {
        long hub = hubNow(nowSeconds);
        List<Plan> out = new ArrayList<>();
        for (Plan p : mPlans.values()) if (p.live(hub) && crew(p.crew) != null) out.add(p);
        Collections.sort(out, (a, b) -> a.at != b.at ? Long.compare(a.at, b.at) : a.id.compareTo(b.id));
        return out;
    }

    /** One crew's plans that are not over, soonest first. */
    public List<Plan> plans(long nowSeconds, String crewId) {
        List<Plan> out = new ArrayList<>();
        for (Plan p : plans(nowSeconds)) if (p.crew.equals(crewId)) out.add(p);
        return out;
    }

    /** The crew's plans still ahead (the service allows 3). */
    public int upcomingPlans(long nowSeconds, String crewId) {
        long hub = hubNow(nowSeconds);
        int n = 0;
        for (Plan p : plans(nowSeconds, crewId)) if (p.upcoming(hub)) n++;
        return n;
    }

    /** The player may make plans in {@code crewId}: the right to call the crew covers plans. */
    public boolean mayPlan(@Nullable String crewId) {
        Crew c = crew(crewId);
        String me = myId();
        return c != null && me != null && c.team.allows(me, c.team.permissions.call);
    }

    /** Its maker, or the crew's leader or a moderator, may cancel a plan ("Planı sil"). */
    public boolean mayCancel(Plan plan) {
        String me = myId();
        if (me == null) return false;
        if (plan.by.equals(me)) return true;
        Crew c = crew(plan.crew);
        Member m = c == null ? null : c.team.member(me);
        return m != null && (m.founder || m.moderator);
    }

    /** FIX2 ({@code planNotes}): the plan's maker writes, changes or clears its note while it shows. */
    public boolean mayWriteNote(Plan plan, long nowSeconds) {
        String me = myId();
        return me != null && plan.by.equals(me) && plan.live(hubNow(nowSeconds)) && crew(plan.crew) != null;
    }

    /** FIX2: the crew's leader and moderators may only clear someone else's note. */
    public boolean mayClearNote(Plan plan, long nowSeconds) {
        String me = myId();
        if (me == null || plan.note == null || plan.by.equals(me) || !plan.live(hubNow(nowSeconds))) return false;
        Crew c = crew(plan.crew);
        Member m = c == null ? null : c.team.member(me);
        return m != null && (m.founder || m.moderator);
    }

    /** The player's answer to a plan (null: none yet). */
    @Nullable
    public String myPlanAnswer(String planId) {
        Plan p = mPlans.get(planId);
        String me = myId();
        return p == null || me == null ? null : p.answers.get(me);
    }

    /**
     * The soonest plan the player said "Geliyorum" or "Belki" to that runs now or starts within
     * {@code windowSeconds} (the home line), or null.
     */
    @Nullable
    public Plan soonPlan(long nowSeconds, long windowSeconds) {
        return soonPlan(nowSeconds, windowSeconds, Plan.AFTER_SECONDS);
    }

    /** The same, only while the plan started at most {@code afterSeconds} ago. */
    @Nullable
    public Plan soonPlan(long nowSeconds, long windowSeconds, long afterSeconds) {
        long hub = hubNow(nowSeconds);
        String me = myId();
        if (me == null) return null;
        for (Plan p : plans(nowSeconds)) {
            String answer = p.answers.get(me);
            if (!Plan.YES.equals(answer) && !Plan.MAYBE.equals(answer)) continue;
            if (hub >= p.at + afterSeconds) continue;
            if (p.underWay(hub) || p.at - hub <= windowSeconds) return p;
        }
        return null;
    }

    // ---- changes from the service's answers ----

    public void setMe(@Nullable Me me) {
        mMe = me;
    }

    /** A new {@code GET /v1/friends}: it replaces the lists; presence keeps what the socket said. */
    public void applyFriends(FriendsData data) {
        mFriends.clear();
        for (Friend f : data.friends) mFriends.put(f.profile.id, f);
        mIncoming.clear();
        mIncoming.addAll(data.incoming);
        mOutgoing.clear();
        mOutgoing.addAll(data.outgoing);
        mBlocked.clear();
        mBlocked.addAll(data.blocked);
        mLimits = data.limits;
        mCrews.clear();
        if (data.crews != null) {
            mCrews.addAll(data.crews);
        } else if (data.team != null) {
            mCrews.add(new Crew(data.team, data.teamInvited, TeamNotifications.DEFAULT));
        }
        mCrewLimit = data.crewLimit;
        mTeamInvites.clear();
        mTeamInvites.addAll(data.teamInvites);
        mTeamFeatures = new HashSet<>(data.teamFeatures);
        mTeamNotifications = data.teamNotifications;
        mFeatures = data.features == null ? null : new HashSet<>(data.features);
        mMyRequests.clear();
        mMyRequests.addAll(data.teamRequests);
        mLoaded = true;
        mRestrictedUntil = null;
        prunePresence();
        pruneCalls();
        prunePlans();
    }

    /**
     * A crew answer of a service without several crews ({@code {team, invited}}): the player's one crew
     * (null: none any more).
     */
    public void setTeam(@Nullable Team team, @Nullable Set<String> invited) {
        if (team == null) {
            mCrews.clear();
            mCalls.clear();
            mPlans.clear();
        } else {
            Crew before = crew(team.id);
            Set<String> keep = invited != null ? invited : before != null ? before.invited : Collections.<String>emptySet();
            mCrews.clear();
            Crew next = new Crew(team, keep, before != null ? before.mute : TeamNotifications.DEFAULT,
                    before != null ? before.requests : Collections.<JoinRequest>emptyList());
            mCrews.add(keepRequestsIfAllowed(next));
            // An answered invite to the crew the player is in now is gone (and a join request to it).
            removeTeamInvite(team.id);
            removeMyRequest(team.id);
            pruneCalls();
            prunePlans();
        }
        prunePresence();
    }

    /**
     * TEAM4: an answer about one crew ({@code {team, invited}} of a join, an invite's acceptance, a
     * change): it takes that crew's place, or comes last when it is new.
     */
    public void upsertCrew(Team team, @Nullable Set<String> invited) {
        for (int i = 0; i < mCrews.size(); i++) {
            Crew c = mCrews.get(i);
            if (c.id().equals(team.id)) {
                Crew next = c.withTeam(team);
                if (invited != null) next = next.withInvited(invited);
                mCrews.set(i, keepRequestsIfAllowed(next));
                removeTeamInvite(team.id);
                prunePresence();
                return;
            }
        }
        mCrews.add(new Crew(team, invited == null ? Collections.<String>emptySet() : invited, TeamNotifications.DEFAULT));
        removeTeamInvite(team.id);
        removeMyRequest(team.id);
        prunePresence();
    }

    /** TEAM5: a crew card that shows the player's role lost {@code admit} drops the crew's join requests (README). */
    private Crew keepRequestsIfAllowed(Crew crew) {
        String me = myId();
        if (crew.requests.isEmpty() || (me != null && crew.team.allows(me, crew.team.permissions.admit))) return crew;
        return crew.withRequests(Collections.<JoinRequest>emptyList());
    }

    /** TEAM4: the player is no longer in {@code crewId} (left, removed, or it ended). */
    public void removeCrew(String crewId) {
        for (int i = mCrews.size() - 1; i >= 0; i--) if (mCrews.get(i).id().equals(crewId)) mCrews.remove(i);
        pruneCalls();
        prunePlans();
        prunePresence();
    }

    // ---- TEAM5: join requests, the invite link, plans ----

    /** The player asked to join (a {@code 202 {request}} answer): it waits in their list, newest first. */
    public void addMyRequest(MyRequest request) {
        removeMyRequest(request.team.id);
        mMyRequests.add(0, request);
    }

    /** The player withdrew the request (or its crew came, or it went). */
    public void removeMyRequest(String teamId) {
        for (int i = mMyRequests.size() - 1; i >= 0; i--) if (mMyRequests.get(i).team.id.equals(teamId)) mMyRequests.remove(i);
    }

    /** A crew's waiting join requests as the service says them now ({@code team_requests}). */
    public void setCrewRequests(String crewId, List<JoinRequest> requests) {
        for (int i = 0; i < mCrews.size(); i++) {
            if (mCrews.get(i).id().equals(crewId)) mCrews.set(i, mCrews.get(i).withRequests(requests));
        }
    }

    /** One request answered here (accepted, declined, or its sender blocked): it goes at once. */
    public void removeCrewRequest(String crewId, String userId) {
        Crew c = crew(crewId);
        if (c == null) return;
        List<JoinRequest> left = new ArrayList<>();
        for (JoinRequest r : c.requests) if (!r.from.id.equals(userId)) left.add(r);
        setCrewRequests(crewId, left);
    }

    /** The crew's invite link as its own route answered ({@code POST}/{@code DELETE /v1/teams/:id/link}). */
    public void setCrewLink(String crewId, @Nullable String link) {
        for (int i = 0; i < mCrews.size(); i++) {
            Crew c = mCrews.get(i);
            if (c.id().equals(crewId)) mCrews.set(i, c.withTeam(c.team.withLink(link)));
        }
    }

    /** A plan as the service stored it (the {@code ack} of the player's own new plan). */
    public void putPlan(Plan plan) {
        mPlans.put(plan.id, plan);
    }

    public void removePlan(String planId) {
        mPlans.remove(planId);
    }

    /** The player's own answer, shown at once (the socket confirms it). */
    public void answerPlanLocally(String planId, String answer) {
        Plan p = mPlans.get(planId);
        String me = myId();
        if (p != null && me != null) mPlans.put(planId, p.withAnswer(me, answer));
    }

    /** An answer that did not go: the one before it comes back (null: none). */
    public void restorePlanAnswer(String planId, @Nullable String previous) {
        Plan p = mPlans.get(planId);
        String me = myId();
        if (p != null && me != null) mPlans.put(planId, p.withAnswer(me, previous));
    }

    /** TEAM4: the crews in a new order (unknown ids are skipped, crews it leaves out keep their place after it). */
    public void setCrewOrder(List<String> order) {
        List<Crew> next = new ArrayList<>();
        for (String id : order) {
            Crew c = crew(id);
            if (c != null && !next.contains(c)) next.add(c);
        }
        for (Crew c : mCrews) if (!next.contains(c)) next.add(c);
        mCrews.clear();
        mCrews.addAll(next);
    }

    /** TEAM4: the player's mute of one crew. */
    public void setCrewMute(String crewId, TeamNotifications mute) {
        for (int i = 0; i < mCrews.size(); i++) {
            if (mCrews.get(i).id().equals(crewId)) mCrews.set(i, mCrews.get(i).withMute(mute));
        }
    }

    public void setTeamNotifications(TeamNotifications notifications) {
        mTeamNotifications = notifications;
    }

    public void addFriend(Friend friend) {
        mFriends.put(friend.profile.id, friend);
        removeRequest(mIncoming, friend.profile.id);
        removeRequest(mOutgoing, friend.profile.id);
    }

    public void removeFriend(String id) {
        mFriends.remove(id);
        removeRequest(mIncoming, id);
        removeRequest(mOutgoing, id);
        dropInvitesFrom(id);
        if (!isTeammate(id)) mPresence.remove(id);
    }

    public void removeIncoming(String id) {
        removeRequest(mIncoming, id);
    }

    public void removeOutgoing(String id) {
        removeRequest(mOutgoing, id);
    }

    public void addOutgoing(Request request) {
        removeRequest(mOutgoing, request.profile.id);
        mOutgoing.add(0, request);
    }

    public void block(Profile profile) {
        removeFriend(profile.id);
        for (Profile p : mBlocked) if (p.id.equals(profile.id)) return;
        mBlocked.add(profile);
    }

    public void unblock(String id) {
        for (int i = mBlocked.size() - 1; i >= 0; i--) if (mBlocked.get(i).id.equals(id)) mBlocked.remove(i);
    }

    public void removeTeamInvite(String teamId) {
        for (int i = mTeamInvites.size() - 1; i >= 0; i--) if (mTeamInvites.get(i).team.id.equals(teamId)) mTeamInvites.remove(i);
    }

    /** The player invited a friend to the primary crew. */
    public void addTeamInvited(String friendId) {
        if (!mCrews.isEmpty()) mCrews.set(0, mCrews.get(0).withInvitedAdded(friendId));
    }

    /** TEAM4: the player invited a friend to {@code crewId}. */
    public void addCrewInvited(String crewId, String friendId) {
        for (int i = 0; i < mCrews.size(); i++) {
            if (mCrews.get(i).id().equals(crewId)) mCrews.set(i, mCrews.get(i).withInvitedAdded(friendId));
        }
    }

    /** The player closed an invite ("Kapat"): it stays away until it expires. */
    public void dismissInvite(String id) {
        mDismissedInvites.add(id);
    }

    /** The player's own answer, shown at once (the socket confirms it). */
    public void answerLocally(String callId, String answer) {
        Call call = mCalls.get(callId);
        String me = myId();
        if (call != null && me != null) mCalls.put(callId, call.withAnswer(me, answer));
    }

    /** The player's answer to a call (null: none yet). */
    @Nullable
    public String myAnswer(String callId) {
        Call call = mCalls.get(callId);
        String me = myId();
        return call == null || me == null ? null : call.answers.get(me);
    }

    /** An answer that was not sent: the one before it comes back. */
    public void restoreAnswer(String callId, @Nullable String previous) {
        Call call = mCalls.get(callId);
        String me = myId();
        if (call == null || me == null) return;
        mCalls.put(callId, previous == null ? call.withoutAnswer(me) : call.withAnswer(me, previous));
    }

    /** The notices the player has seen go (the service is told separately). */
    public void noticesSeen(Set<String> ids) {
        if (mMe != null) mMe = mMe.withoutNotices(ids);
    }

    /** The socket closed: presence is unknown until the next {@code hello}; invites and calls stay. */
    public void setOffline() {
        mLive = false;
        mPresence.clear();
    }

    /** Signed out or the account went: nothing stays. */
    public void clear() {
        mMe = null;
        mFriends.clear();
        mIncoming.clear();
        mOutgoing.clear();
        mBlocked.clear();
        mLimits = new Limits(0, 0, 0);
        mCrews.clear();
        mCrewLimit = 0;
        mTeamInvites.clear();
        mTeamFeatures = new HashSet<>();
        mTeamNotifications = TeamNotifications.DEFAULT;
        mFeatures = null;
        mLoaded = false;
        mPresence.clear();
        mInvites.clear();
        mCalls.clear();
        mDismissedInvites.clear();
        mLive = false;
        mClockOffset = 0;
        mCaps = new HashSet<>();
        mChat = ChatState.UNKNOWN;
        mRestrictedUntil = null;
        mMyRequests.clear();
        mPlans.clear();
        mPlansKnown = false;
        mJoins.clear();
    }

    // ---- changes from the presence socket ----

    /**
     * Applies one message from the presence socket and returns what the app may announce. Unknown
     * messages change nothing (a newer service may send more).
     */
    public List<Notice> apply(JsonObject message, long nowSeconds) {
        List<Notice> notices = new ArrayList<>();
        String type = Json.str(message, "t", "");
        switch (type) {
            case "hello":
                onHello(message, nowSeconds);
                break;
            case "presence": {
                Presence p = Presence.read(Json.obj(message, "presence"));
                if (p != null) setPresence(p);
                break;
            }
            case "friend_added": {
                Profile profile = Profile.read(Json.obj(message, "friend"));
                if (profile != null) {
                    boolean known = mFriends.containsKey(profile.id);
                    addFriend(new Friend(profile, null, null));
                    Presence p = Presence.read(Json.obj(message, "presence"));
                    if (p != null && p.id.equals(profile.id)) setPresence(p);
                    if (!known) notices.add(Notice.of(Notice.Kind.FRIEND_ADDED, profile));
                }
                break;
            }
            case "friend_removed": {
                String id = Json.str(message, "id");
                if (id != null) removeFriend(id);
                break;
            }
            case "request": {
                Profile from = Profile.read(Json.obj(message, "from"));
                if (from != null && !mFriends.containsKey(from.id)) {
                    boolean known = false;
                    for (Request r : mIncoming) if (r.profile.id.equals(from.id)) known = true;
                    removeRequest(mIncoming, from.id);
                    mIncoming.add(0, new Request(from, nowSeconds));
                    if (!known) notices.add(Notice.of(Notice.Kind.REQUEST, from));
                }
                break;
            }
            case "request_cancelled": {
                String id = Json.str(message, "id");
                if (id != null) removeRequest(mIncoming, id);
                break;
            }
            case "invite": {
                JsonObject wire = Json.obj(message, "invite");
                Invite invite = Invite.read(wire);
                // Only friends invite (the service's rule); the list shows those of known friends.
                if (invite != null && !invite.expired(nowSeconds)) {
                    boolean known = mInvites.containsKey(invite.id);
                    mInvites.put(invite.id, invite);
                    if (!known && !Json.bool(wire, "quiet", false)) {
                        notices.add(new Notice(Notice.Kind.INVITE, profile(invite.fromId), invite, null, null, null, null, false));
                    }
                }
                break;
            }
            case "invites":
                // Queued while on do-not-disturb: listed quietly.
                for (JsonObject wire : Json.objects(message, "invites")) {
                    Invite invite = Invite.read(wire);
                    if (invite != null && !invite.expired(nowSeconds)) mInvites.put(invite.id, invite);
                }
                break;
            case "status": {
                String status = Json.str(message, "status");
                if (mMe != null && SocialModel.Status.isKnown(status)) mMe = mMe.withStatus(status);
                break;
            }
            case "team":
                onTeam(message, notices, nowSeconds);
                break;
            case "team_invite": {
                TeamInvite invite = TeamInvite.read(Json.obj(message, "invite"));
                if (invite != null) {
                    boolean known = false;
                    for (TeamInvite t : mTeamInvites) if (t.team.id.equals(invite.team.id)) known = true;
                    removeTeamInvite(invite.team.id);
                    mTeamInvites.add(0, invite);
                    if (!known) notices.add(new Notice(Notice.Kind.TEAM_INVITE, invite.from, null, null, invite, null, null, false));
                }
                break;
            }
            case "team_notifications":
                mTeamNotifications = TeamNotifications.read(Json.obj(message, "notifications"));
                break;
            case "crew_notifications": {
                String crew = Json.str(message, "crew");
                if (crew != null) setCrewMute(crew, TeamNotifications.read(Json.obj(message, "notifications")));
                break;
            }
            case "crew_order":
                setCrewOrder(Json.strings(message, "order"));
                break;
            case "call":
                onCall(message, nowSeconds, notices);
                break;
            case "call_answer": {
                String id = Json.str(message, "call");
                String by = Json.str(message, "by");
                String answer = Json.str(message, "answer");
                Call call = id == null ? null : mCalls.get(id);
                if (call != null && by != null && Call.isAnswer(answer)) mCalls.put(id, call.withAnswer(by, answer));
                break;
            }
            case "call_end": {
                String id = Json.str(message, "call");
                if (id != null) {
                    mCalls.remove(id);
                    // Told even when the call was not kept: a notification of it may still be up.
                    notices.add(Notice.callEnded(id, Json.str(message, "reason")));
                }
                break;
            }
            case "chat_state":
                mChat = ChatState.read(Json.obj(message, "chat"));
                break;
            case "notice": {
                ModNotice notice = ModNotice.read(Json.obj(message, "notice"));
                if (notice != null && mMe != null) {
                    boolean known = false;
                    for (ModNotice n : mMe.notices) if (n.id.equals(notice.id)) known = true;
                    mMe = mMe.withNotice(notice);
                    if (!known) notices.add(Notice.modNotice(notice));
                }
                break;
            }
            case "bye":
                if ("restricted".equals(Json.str(message, "reason"))) {
                    Long until = Json.numOrNull(message, "until");
                    mRestrictedUntil = until == null ? 0L : until;
                    notices.add(Notice.restricted(mRestrictedUntil));
                }
                break;
            case "team_requests":
                onTeamRequests(message, nowSeconds, notices);
                break;
            case "badge": {
                String id = Json.str(message, "id");
                if (SocialModel.isUserId(id)) applyBadge(id, Badge.read(Json.obj(message, "badge")), notices);
                break;
            }
            case "plan": {
                // Kept even before the list names its crew (the socket may speak first); read through plans().
                Plan plan = Plan.read(Json.obj(message, "plan"));
                if (plan == null) break;
                Plan before = mPlans.put(plan.id, plan);
                // Announced only when it is new to the player and not quiet (README "Crew plans").
                Crew c = crew(plan.crew);
                if (before == null && c != null && !Json.bool(message, "quiet", false) && !plan.by.equals(myId())
                        && plan.live(hubNow(nowSeconds))) {
                    notices.add(Notice.plan(profile(plan.by), plan, c.team.name));
                }
                break;
            }
            case "plans": {
                // Everything the player sees of one crew's plans now (also an empty list): replace them.
                String crewId = Json.str(message, "crew");
                if (!SocialModel.isCrewId(crewId)) break;
                for (String id : new ArrayList<>(mPlans.keySet())) {
                    Plan p = mPlans.get(id);
                    if (p != null && p.crew.equals(crewId)) mPlans.remove(id);
                }
                for (JsonObject wire : Json.objects(message, "plans")) {
                    Plan plan = Plan.read(wire);
                    if (plan != null && plan.crew.equals(crewId)) mPlans.put(plan.id, plan);
                }
                break;
            }
            case "plan_removed": {
                String id = Json.str(message, "plan");
                if (id == null) break;
                Plan gone = mPlans.remove(id);
                String by = Json.str(message, "by");
                notices.add(Notice.planRemoved(id, gone != null ? gone.crew : Json.str(message, "crew"), by != null && by.equals(myId())));
                break;
            }
            default:
                break;
        }
        return notices;
    }

    /**
     * {@code team_requests}: a crew's waiting join requests changed, or the player gained the right to
     * answer them; {@code added} is the player whose request is new.
     */
    private void onTeamRequests(JsonObject message, long nowSeconds, List<Notice> notices) {
        String crewId = Json.str(message, "crew");
        Crew c = crew(crewId);
        if (c == null) return;
        Set<String> before = new HashSet<>();
        for (JoinRequest r : c.requests) before.add(r.from.id);
        List<JoinRequest> requests = SocialModel.joinRequests(message, "requests");
        setCrewRequests(c.id(), requests);
        String added = Json.str(message, "added");
        if (added == null || before.contains(added)) return;
        for (JoinRequest r : requests) {
            if (r.from.id.equals(added) && r.waiting(nowSeconds)) notices.add(Notice.joinRequest(r.from, c.id(), c.team.name));
        }
    }

    private void onHello(JsonObject message, long nowSeconds) {
        mLive = true;
        if (Json.has(message, "now")) mClockOffset = Json.num(message, "now", nowSeconds) - nowSeconds;
        String status = Json.str(message, "status");
        if (mMe != null && SocialModel.Status.isKnown(status)) mMe = mMe.withStatus(status);
        mCaps = new HashSet<>(Json.strings(message, "caps"));
        mChat = ChatState.read(Json.obj(message, "chat"));
        mPresence.clear();
        for (JsonObject p : Json.objects(message, "friends")) {
            Presence presence = Presence.read(p);
            if (presence != null) setPresence(presence);
        }
        for (JsonObject p : Json.objects(message, "team")) {
            Presence presence = Presence.read(p);
            if (presence != null) setPresence(presence);
        }
        // Invites that waited: shown quietly, in place of what was kept.
        mInvites.clear();
        for (JsonObject wire : Json.objects(message, "invites")) {
            Invite invite = Invite.read(wire);
            if (invite != null && !invite.expired(nowSeconds)) mInvites.put(invite.id, invite);
        }
        // Every open call in view, the player's own included: any other kept one is over.
        mCalls.clear();
        for (JsonObject wire : Json.objects(message, "calls")) {
            Call call = Call.read(wire);
            if (call != null) mCalls.put(call.id, call);
        }
        // TEAM5: every plan of the player's crews they see, upcoming or under way: keep exactly these.
        mPlans.clear();
        mPlansKnown = Json.has(message, "plans");
        for (JsonObject wire : Json.objects(message, "plans")) {
            Plan plan = Plan.read(wire);
            if (plan != null) mPlans.put(plan.id, plan);
        }
    }

    private void onTeam(JsonObject message, List<Notice> notices, long nowSeconds) {
        JsonObject wire = Json.obj(message, "team");
        String by = Json.str(message, "by");
        boolean byMe = by != null && by.equals(myId());
        boolean byStaff = "staff".equals(by);
        String crewId = Json.str(message, "crew");
        if (wire == null) {
            // No longer in that crew (a service from before several crews names none: its one crew).
            String id = crewId != null ? crewId : mCrews.isEmpty() ? null : mCrews.get(0).id();
            Crew before = crew(id);
            if (before == null) return;
            removeCrew(before.id());
            // TEAM6: its tag goes with the membership (quietly: the crew's own notice says enough), from the
            // player's entries in their other crews' lists too (profile() reads those before mMe).
            if (mMe != null && mMe.profile.badge != null && mMe.profile.badge.crew.equals(before.id())) {
                applyBadge(mMe.profile.id, null, new ArrayList<Notice>());
            }
            notices.add(Notice.teamGone(Json.str(message, "reason"), before.team.name, byMe, byStaff));
            return;
        }
        Team team = Team.read(wire);
        if (team == null) return;
        if (crewId == null && crew(team.id) == null) {
            // An older service: its one crew is this one now.
            setTeam(team, null);
            return;
        }
        // TEAM5: a crew the player asked to join that comes now was taken in (`by`: who accepted).
        boolean accepted = crew(team.id) == null && !byMe && myRequestEver(team.id);
        Crew before = crew(team.id);
        boolean wasHidden = before != null && before.team.hidden;
        upsertCrew(team, null);
        if (accepted) notices.add(Notice.requestAccepted(team.id, team.name));
        // TEAM6: someone came in (the crew chat's "Hoş geldin" line; the player's own too). The service names them
        // in a list (`joined: [id]`, when a join request is accepted), as the desktop's pl-social reads it.
        for (String joined : Json.strings(message, "joined")) {
            if (SocialModel.isUserId(joined) && team.member(joined) != null) noteJoin(team.id, joined, nowSeconds);
        }
        // TEAM6: the leader hid the crew whose tag the player wore: it came off (the desktop's toast).
        String me = myId();
        Member leader = team.founder();
        boolean leaderIsMe = leader != null && me != null && leader.profile.id.equals(me);
        if (team.hidden && !wasHidden && mMe != null && mMe.profile.badge != null && mMe.profile.badge.crew.equals(team.id)) {
            // Everywhere the player shows (their other crews' lists too); the notice is this one.
            applyBadge(mMe.profile.id, null, new ArrayList<Notice>());
            if (!byMe && !leaderIsMe) notices.add(Notice.tagOff(team.id, team.name));
        }
        // The player's own entry carries their tag as it is now (another device of theirs may have changed it).
        Member mine = me == null ? null : team.member(me);
        if (mine != null && mMe != null && mine.profile.badge != null && !sameBadge(mMe.profile.badge, mine.profile.badge)) {
            mMe = mMe.withBadge(mine.profile.badge);
        }
        // Invites whose sender lost the right are withdrawn by the service; the next list says so.
    }

    private static boolean sameBadge(@Nullable Badge a, @Nullable Badge b) {
        return a == null ? b == null : b != null && a.key().equals(b.key());
    }

    /**
     * TEAM6 ({@code badge}): the tag a friend, a crewmate or the player (from another device) wears
     * changed: it takes the old one's place wherever they show. The player's own tag coming off
     * because the leader hid its crew is told once.
     */
    private void applyBadge(String userId, @Nullable Badge badge, List<Notice> notices) {
        Friend f = mFriends.get(userId);
        if (f != null) mFriends.put(userId, new Friend(f.profile.withBadge(badge), f.since, f.lastSeen));
        for (int i = 0; i < mCrews.size(); i++) {
            Crew c = mCrews.get(i);
            Team next = c.team.withMemberBadge(userId, badge);
            if (next != c.team) mCrews.set(i, c.withTeam(next));
        }
        if (mMe != null && mMe.profile.id.equals(userId)) {
            Badge before = mMe.profile.badge;
            mMe = mMe.withBadge(badge);
            if (badge == null && before != null) {
                Crew c = crew(before.crew);
                Member leader = c == null ? null : c.team.founder();
                if (c != null && c.team.hidden && (leader == null || !leader.profile.id.equals(userId))) {
                    notices.add(Notice.tagOff(c.id(), c.team.name));
                }
            }
        }
    }

    /** TEAM6: the player's own tag as an answer of theirs says it (wearing, taking off). */
    public void setMyBadge(@Nullable Badge badge) {
        if (mMe == null) return;
        String me = mMe.profile.id;
        applyBadge(me, badge, new ArrayList<Notice>());
    }

    private boolean myRequestEver(String teamId) {
        for (MyRequest r : mMyRequests) if (r.team.id.equals(teamId)) return true;
        return false;
    }

    private void onCall(JsonObject message, long nowSeconds, List<Notice> notices) {
        Call call = Call.read(Json.obj(message, "call"));
        if (call == null) return;
        if (Json.has(message, "now")) mClockOffset = Json.num(message, "now", nowSeconds) - nowSeconds;
        Call before = mCalls.put(call.id, call);
        boolean quiet = Json.bool(message, "quiet", false);
        boolean mine = call.from.equals(myId());
        // Announced when it is new, just became "join", or (with callServer) its server changed; never when quiet (README).
        boolean becameJoin = before != null && !Call.JOIN.equals(before.kind) && Call.JOIN.equals(call.kind);
        boolean moved = before != null && call.serverWhere != null
                && (!eq(before.serverWhere, call.serverWhere) || !eq(before.serverHost, call.serverHost));
        boolean announce = !quiet && !mine && (before == null || becameJoin || moved);
        if (announce && call.until > hubNow(nowSeconds)) {
            notices.add(Notice.call(profile(call.from), call, before != null));
        }
    }

    private void setPresence(Presence p) {
        if (mMe != null && p.id.equals(mMe.profile.id)) return;
        if (!p.online()) {
            mPresence.remove(p.id);
            Friend f = mFriends.get(p.id);
            if (f != null && p.lastSeen != null) mFriends.put(p.id, new Friend(f.profile, f.since, p.lastSeen));
        } else {
            mPresence.put(p.id, p);
        }
        // A new picture choice shows at once.
        if (p.avatar != null) updateAvatar(p.id, p.avatar, p.avatarHash);
    }

    private void updateAvatar(String id, String avatar, @Nullable String hash) {
        Friend f = mFriends.get(id);
        if (f != null && (!avatar.equals(f.profile.avatar) || !eq(hash, f.profile.avatarHash))) {
            mFriends.put(id, new Friend(f.profile.withAvatar(avatar, hash), f.since, f.lastSeen));
        }
    }

    private static boolean eq(@Nullable String a, @Nullable String b) {
        return a == null ? b == null : a.equals(b);
    }

    private boolean isTeammate(String id) {
        for (Crew c : mCrews) if (c.team.member(id) != null) return true;
        return false;
    }

    private void prunePresence() {
        for (String id : new ArrayList<>(mPresence.keySet())) {
            if (!mFriends.containsKey(id) && !isTeammate(id)) mPresence.remove(id);
        }
    }

    /** Calls whose crews the player is no longer in go (the service also ends them for the player). */
    private void pruneCalls() {
        if (mCrews.isEmpty()) {
            mCalls.clear();
            return;
        }
        for (String id : new ArrayList<>(mCalls.keySet())) {
            Call call = mCalls.get(id);
            if (call == null || call.crews.isEmpty()) continue;
            List<String> left = new ArrayList<>();
            for (String crew : call.crews) if (crew(crew) != null) left.add(crew);
            if (left.isEmpty()) mCalls.remove(id);
            else if (left.size() != call.crews.size()) mCalls.put(id, call.withCrews(left));
        }
    }

    /** Plans of crews the player is no longer in go (the hub drops them for the player too). */
    private void prunePlans() {
        for (String id : new ArrayList<>(mPlans.keySet())) {
            Plan p = mPlans.get(id);
            if (p == null || crew(p.crew) == null) mPlans.remove(id);
        }
    }

    private void dropInvitesFrom(String id) {
        for (String key : new ArrayList<>(mInvites.keySet())) {
            Invite invite = mInvites.get(key);
            if (invite != null && invite.fromId.equals(id)) mInvites.remove(key);
        }
    }

    private static void removeRequest(List<Request> list, String id) {
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).profile.id.equals(id)) list.remove(i);
    }

    // ---- views ----

    /** The friends list's sections, in the desktop's order. */
    public static final class Sections {
        public final List<Friend> playing = new ArrayList<>();
        public final List<Friend> online = new ArrayList<>();
        public final List<Friend> offline = new ArrayList<>();
    }

    /**
     * Friends by where they are: in game (favourites first, then projects.gg, then by name), in the
     * launcher (favourites, name), offline (favourites, last seen, name).
     */
    public Sections sections(final Set<String> favourites) {
        Sections s = new Sections();
        for (Friend f : mFriends.values()) {
            Presence p = presence(f.profile.id);
            if (p.playing()) s.playing.add(f);
            else if (p.online()) s.online.add(f);
            else s.offline.add(f);
        }
        final Comparator<Friend> byName = (a, b) -> a.profile.name().toLowerCase(Locale.ROOT)
                .compareTo(b.profile.name().toLowerCase(Locale.ROOT));
        Collections.sort(s.playing, (a, b) -> {
            int fav = Boolean.compare(favourites.contains(b.profile.id), favourites.contains(a.profile.id));
            if (fav != 0) return fav;
            int projects = Boolean.compare(presence(b.profile.id).onProjects(), presence(a.profile.id).onProjects());
            return projects != 0 ? projects : byName.compare(a, b);
        });
        Collections.sort(s.online, (a, b) -> {
            int fav = Boolean.compare(favourites.contains(b.profile.id), favourites.contains(a.profile.id));
            return fav != 0 ? fav : byName.compare(a, b);
        });
        Collections.sort(s.offline, (a, b) -> {
            int fav = Boolean.compare(favourites.contains(b.profile.id), favourites.contains(a.profile.id));
            if (fav != 0) return fav;
            int seen = Integer.compare(seenRank(a.lastSeen), seenRank(b.lastSeen));
            return seen != 0 ? seen : byName.compare(a, b);
        });
        return s;
    }

    static int seenRank(@Nullable String lastSeen) {
        if (lastSeen == null) return 4;
        switch (lastSeen) {
            case "today": return 0;
            case "yesterday": return 1;
            case "week": return 2;
            case "long": return 3;
            default: return 4;
        }
    }

    /** Where a crew member is, for the crew page's groups. */
    public enum Place { PROJECTS, PLAYING, LAUNCHER, OFFLINE }

    public Place place(String id) {
        if (mMe != null && mMe.profile.id.equals(id)) {
            // The player looks at the app: in the launcher (invisible ones look offline to others).
            return SocialModel.Status.INVISIBLE.equals(mMe.status) ? Place.OFFLINE : Place.LAUNCHER;
        }
        Presence p = presence(id);
        if (p.onProjects()) return Place.PROJECTS;
        if (p.playing()) return Place.PLAYING;
        if (p.online()) return Place.LAUNCHER;
        return Place.OFFLINE;
    }

    /** The primary crew's members by place (leader, moderators, then by name within each). */
    public Map<Place, List<Member>> roster() {
        return roster(mCrews.isEmpty() ? null : mCrews.get(0).id());
    }

    /** A crew's members by place (leader, moderators, then by name within each). */
    public Map<Place, List<Member>> roster(@Nullable String crewId) {
        Map<Place, List<Member>> out = new LinkedHashMap<>();
        for (Place place : Place.values()) out.put(place, new ArrayList<Member>());
        Crew crew = crew(crewId);
        if (crew == null) return out;
        for (Member m : crew.team.members) out.get(place(m.profile.id)).add(m);
        for (List<Member> list : out.values()) {
            Collections.sort(list, (a, b) -> {
                int founder = Boolean.compare(b.founder, a.founder);
                if (founder != 0) return founder;
                int mod = Boolean.compare(b.moderator, a.moderator);
                if (mod != 0) return mod;
                return a.profile.name().toLowerCase(Locale.ROOT).compareTo(b.profile.name().toLowerCase(Locale.ROOT));
            });
        }
        return out;
    }

    /** Crewmates of every crew (each once; the player left out) on projects.gg. */
    public List<Member> crewOnProjects() {
        List<Member> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Crew c : mCrews) {
            for (Member m : c.team.members) {
                if (m.profile.id.equals(myId()) || !seen.add(m.profile.id)) continue;
                if (presence(m.profile.id).onProjects()) out.add(m);
            }
        }
        return out;
    }

    /** One crew's members (the player left out) on projects.gg. */
    public List<Member> crewOnProjects(String crewId) {
        List<Member> out = new ArrayList<>();
        Crew crew = crew(crewId);
        if (crew == null) return out;
        for (Member m : crew.team.members) {
            if (!m.profile.id.equals(myId()) && presence(m.profile.id).onProjects()) out.add(m);
        }
        return out;
    }

    /** The crew with the most members on projects.gg (the home line's tap opens it), or null. */
    @Nullable
    public Crew busiestCrewOnProjects() {
        Crew best = null;
        int most = 0;
        for (Crew c : mCrews) {
            int n = crewOnProjects(c.id()).size();
            if (n > most) {
                most = n;
                best = c;
            }
        }
        return best;
    }

    /** Crewmates of a crew (the player left out) online: in the launcher or in game. */
    public int onlineInCrew(String crewId) {
        Crew crew = crew(crewId);
        if (crew == null) return 0;
        int n = 0;
        for (Member m : crew.team.members) {
            if (!m.profile.id.equals(myId()) && presence(m.profile.id).online()) n++;
        }
        return n;
    }

    /** Friends on projects.gg, favourites first. */
    public List<Friend> friendsOnProjects(Set<String> favourites) {
        List<Friend> out = new ArrayList<>();
        for (Friend f : sections(favourites).playing) if (presence(f.profile.id).onProjects()) out.add(f);
        return out;
    }

    public int onlineCount() {
        int n = 0;
        for (Friend f : mFriends.values()) if (presence(f.profile.id).online()) n++;
        return n;
    }
}
