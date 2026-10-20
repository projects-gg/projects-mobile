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

import gg.projects.mobile.social.SocialModel.Call;
import gg.projects.mobile.social.SocialModel.Friend;
import gg.projects.mobile.social.SocialModel.FriendsData;
import gg.projects.mobile.social.SocialModel.Invite;
import gg.projects.mobile.social.SocialModel.Limits;
import gg.projects.mobile.social.SocialModel.Me;
import gg.projects.mobile.social.SocialModel.Member;
import gg.projects.mobile.social.SocialModel.Presence;
import gg.projects.mobile.social.SocialModel.Profile;
import gg.projects.mobile.social.SocialModel.Request;
import gg.projects.mobile.social.SocialModel.Team;
import gg.projects.mobile.social.SocialModel.TeamInvite;
import gg.projects.mobile.social.SocialModel.TeamNotifications;

/**
 * What the app knows about the player's friends and crew: the friend list from the service, made
 * live by the presence socket's messages (services/social README "Presence WebSocket"). Pure Java
 * and JVM-tested; every change returns the {@link Notice}s the app may announce. Times are Unix
 * seconds by the phone's clock; crew calls follow the hub's clock through the offset {@code hello}
 * and {@code call} carry.
 */
public final class SocialState {
    @Nullable
    private Me mMe;
    private final Map<String, Friend> mFriends = new LinkedHashMap<>();
    private final List<Request> mIncoming = new ArrayList<>();
    private final List<Request> mOutgoing = new ArrayList<>();
    private final List<Profile> mBlocked = new ArrayList<>();
    private Limits mLimits = new Limits(0, 0, 0);
    @Nullable
    private Team mTeam;
    private final Set<String> mTeamInvited = new HashSet<>();
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

    /** Something the app may tell the player about. */
    public static final class Notice {
        public enum Kind { REQUEST, INVITE, CALL, CALL_ENDED, FRIEND_ADDED, TEAM_INVITE, TEAM_GONE }

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

        Notice(Kind kind, @Nullable Profile who, @Nullable Invite invite, @Nullable Call call,
               @Nullable TeamInvite teamInvite, @Nullable String reason, @Nullable String teamName, boolean byMe) {
            this.kind = kind;
            this.who = who;
            this.invite = invite;
            this.call = call;
            this.teamInvite = teamInvite;
            this.reason = reason;
            this.teamName = teamName;
            this.byMe = byMe;
            this.callId = call == null ? null : call.id;
        }

        private Notice(String endedCallId, @Nullable String reason) {
            this.kind = Kind.CALL_ENDED;
            this.who = null;
            this.invite = null;
            this.call = null;
            this.teamInvite = null;
            this.reason = reason;
            this.teamName = null;
            this.byMe = false;
            this.callId = endedCallId;
        }

        static Notice of(Kind kind, @Nullable Profile who) {
            return new Notice(kind, who, null, null, null, null, null, false);
        }

        static Notice callEnded(String callId, @Nullable String reason) {
            return new Notice(callId, reason);
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

    @Nullable
    public Team team() {
        return mTeam;
    }

    public Set<String> teamInvited() {
        return Collections.unmodifiableSet(mTeamInvited);
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

    @Nullable
    public Call call(String id) {
        return mCalls.get(id);
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

    /** A crewmate's or friend's profile by id (the crew's member list knows teammates who are not friends). */
    @Nullable
    public Profile profile(String id) {
        Friend f = mFriends.get(id);
        if (f != null) return f.profile;
        if (mTeam != null) {
            Member m = mTeam.member(id);
            if (m != null) return m.profile;
        }
        if (mMe != null && mMe.profile.id.equals(id)) return mMe.profile;
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
        mTeam = data.team;
        mTeamInvited.clear();
        mTeamInvited.addAll(data.teamInvited);
        mTeamInvites.clear();
        mTeamInvites.addAll(data.teamInvites);
        mTeamFeatures = new HashSet<>(data.teamFeatures);
        mTeamNotifications = data.teamNotifications;
        mFeatures = data.features == null ? null : new HashSet<>(data.features);
        mLoaded = true;
        prunePresence();
        if (mTeam == null) mCalls.clear();
    }

    public void setTeam(@Nullable Team team, @Nullable Set<String> invited) {
        mTeam = team;
        if (invited != null) {
            mTeamInvited.clear();
            mTeamInvited.addAll(invited);
        }
        if (team == null) {
            mCalls.clear();
            mTeamInvited.clear();
        } else {
            // An answered invite to the crew the player is in now is gone.
            removeTeamInvite(team.id);
        }
        prunePresence();
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

    public void addTeamInvited(String friendId) {
        mTeamInvited.add(friendId);
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
        mTeam = null;
        mTeamInvited.clear();
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
                onTeam(message, notices);
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
            default:
                break;
        }
        return notices;
    }

    private void onHello(JsonObject message, long nowSeconds) {
        mLive = true;
        if (Json.has(message, "now")) mClockOffset = Json.num(message, "now", nowSeconds) - nowSeconds;
        String status = Json.str(message, "status");
        if (mMe != null && SocialModel.Status.isKnown(status)) mMe = mMe.withStatus(status);
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
    }

    private void onTeam(JsonObject message, List<Notice> notices) {
        JsonObject wire = Json.obj(message, "team");
        String by = Json.str(message, "by");
        boolean byMe = by != null && by.equals(myId());
        if (wire == null) {
            Team before = mTeam;
            mTeam = null;
            mCalls.clear();
            mTeamInvited.clear();
            prunePresence();
            if (before != null) {
                notices.add(new Notice(Notice.Kind.TEAM_GONE, null, null, null, null, Json.str(message, "reason"),
                        before.name, byMe));
            }
            return;
        }
        Team team = Team.read(wire);
        if (team == null) return;
        mTeam = team;
        removeTeamInvite(team.id);
        // Invites whose sender lost the right are withdrawn by the service; the next list says so.
        prunePresence();
    }

    private void onCall(JsonObject message, long nowSeconds, List<Notice> notices) {
        Call call = Call.read(Json.obj(message, "call"));
        if (call == null) return;
        if (Json.has(message, "now")) mClockOffset = Json.num(message, "now", nowSeconds) - nowSeconds;
        Call before = mCalls.put(call.id, call);
        boolean quiet = Json.bool(message, "quiet", false);
        boolean mine = call.from.equals(myId());
        // Announced only when it is new or just became "join", and not quiet (README).
        boolean announce = !quiet && !mine && (before == null || (!before.joinable() && call.joinable()));
        if (announce && call.until > hubNow(nowSeconds)) {
            notices.add(new Notice(Notice.Kind.CALL, profile(call.from), null, call, null, null, null, false));
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
            Profile p = f.profile;
            mFriends.put(id, new Friend(new Profile(p.id, p.username, p.displayName, avatar, hash), f.since, f.lastSeen));
        }
    }

    private static boolean eq(@Nullable String a, @Nullable String b) {
        return a == null ? b == null : a.equals(b);
    }

    private boolean isTeammate(String id) {
        return mTeam != null && mTeam.member(id) != null;
    }

    private void prunePresence() {
        for (String id : new ArrayList<>(mPresence.keySet())) {
            if (!mFriends.containsKey(id) && !isTeammate(id)) mPresence.remove(id);
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

    /** The crew's members by place (leader, moderators, then by name within each). */
    public Map<Place, List<Member>> roster() {
        Map<Place, List<Member>> out = new LinkedHashMap<>();
        for (Place place : Place.values()) out.put(place, new ArrayList<Member>());
        if (mTeam == null) return out;
        for (Member m : mTeam.members) out.get(place(m.profile.id)).add(m);
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

    /** Crewmates (the player left out) on projects.gg. */
    public List<Member> crewOnProjects() {
        List<Member> out = new ArrayList<>();
        if (mTeam == null) return out;
        for (Member m : mTeam.members) {
            if (!m.profile.id.equals(myId()) && presence(m.profile.id).onProjects()) out.add(m);
        }
        return out;
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
