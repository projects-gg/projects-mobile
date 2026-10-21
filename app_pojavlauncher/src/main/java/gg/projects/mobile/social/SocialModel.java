package gg.projects.mobile.social;

import androidx.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The friends service's data as the app keeps it (services/social README: "HTTP API", "Teams",
 * "Crew calls", "Crew notifications", "Push notifications"). Every reader is lenient: unknown fields
 * are ignored and a missing one takes its default, so older and newer services both read.
 */
public final class SocialModel {
    private SocialModel() {}

    private static final Pattern USER_ID = Pattern.compile("\\d{17,20}");
    private static final Pattern TEXTURE = Pattern.compile("minecraft:([0-9a-f]{40,64})");
    private static final Pattern PRESET = Pattern.compile("preset:([a-z0-9-]{1,32})");

    public static boolean isUserId(@Nullable String id) {
        return id != null && USER_ID.matcher(id).matches();
    }

    /** What other players see about someone. */
    public static final class Profile {
        public final String id;
        public final String username;
        @Nullable
        public final String displayName;
        /** {@code discord}, {@code preset:<slug>} or {@code minecraft:<texture hash>}. */
        public final String avatar;
        /** The Discord avatar hash, only while {@code avatar} is {@code discord} (null: Discord's default). */
        @Nullable
        public final String avatarHash;

        public Profile(String id, String username, @Nullable String displayName, String avatar, @Nullable String avatarHash) {
            this.id = id;
            this.username = username;
            this.displayName = displayName;
            this.avatar = avatar;
            this.avatarHash = avatarHash;
        }

        @Nullable
        public static Profile read(@Nullable JsonObject o) {
            String id = Json.str(o, "id");
            if (!isUserId(id)) return null;
            String username = Json.str(o, "username", "");
            String display = Json.str(o, "displayName");
            if (display != null && display.trim().isEmpty()) display = null;
            return new Profile(id, username, display, Json.str(o, "avatar", "discord"), Json.str(o, "avatarHash"));
        }

        /** The display name, else the username. */
        public String name() {
            if (displayName != null) return displayName;
            return username.isEmpty() ? "?" : username;
        }

        /** The preset slug of {@code preset:<slug>}, else null. */
        @Nullable
        public String presetSlug() {
            java.util.regex.Matcher m = PRESET.matcher(avatar);
            return m.matches() ? m.group(1) : null;
        }

        /** The skin texture hash of {@code minecraft:<hash>}, else null. */
        @Nullable
        public String textureHash() {
            java.util.regex.Matcher m = TEXTURE.matcher(avatar);
            return m.matches() ? m.group(1) : null;
        }

        /** Discord's CDN address of the picture while {@code avatar} is {@code discord} (the desktop's rule). */
        @Nullable
        public String discordAvatarUrl(int size) {
            if (presetSlug() != null || textureHash() != null) return null;
            int s = 16;
            while (s < Math.min(1024, Math.max(16, size))) s <<= 1;
            if (avatarHash != null && avatarHash.matches("(a_)?[0-9a-f]{32}")) {
                // Animated pictures are asked for as a still picture: the app draws one frame anyway.
                return "https://cdn.discordapp.com/avatars/" + id + "/" + avatarHash + ".png?size=" + s;
            }
            long index;
            try {
                index = (Long.parseLong(id) >>> 22) % 6;
            } catch (NumberFormatException e) {
                index = 0;
            }
            return "https://cdn.discordapp.com/embed/avatars/" + index + ".png";
        }

        /** The Discord profile ("Discord'dan mesaj at"). */
        public String discordProfileUrl() {
            return "https://discord.com/users/" + id;
        }

        /** What a face shows; a change means the picture must be drawn again. */
        public String faceKey() {
            return avatar + "|" + (avatarHash == null ? "" : avatarHash);
        }
    }

    /** The player's own account ({@code me}). */
    public static final class Me {
        public final Profile profile;
        public final String friendCode;
        /** {@code online}, {@code dnd} or {@code invisible}. */
        public final String status;
        public final boolean shareServer;
        public final boolean shareLastSeen;
        @Nullable
        public final String createdOn;
        /** SPAM1: a new Discord account's limits, or null (also from services before SPAM1). */
        @Nullable
        public final Limited limited;
        /** MOD1: moderators' one-time notes not shown yet, oldest first. */
        public final List<ModNotice> notices;
        /** The service has reports and moderation (MOD1): its {@code me} carries {@code notices}. */
        public final boolean moderation;

        public Me(Profile profile, String friendCode, String status, boolean shareServer, boolean shareLastSeen,
                  @Nullable String createdOn) {
            this(profile, friendCode, status, shareServer, shareLastSeen, createdOn, null,
                    Collections.<ModNotice>emptyList(), false);
        }

        public Me(Profile profile, String friendCode, String status, boolean shareServer, boolean shareLastSeen,
                  @Nullable String createdOn, @Nullable Limited limited, List<ModNotice> notices, boolean moderation) {
            this.profile = profile;
            this.friendCode = friendCode;
            this.status = status;
            this.shareServer = shareServer;
            this.shareLastSeen = shareLastSeen;
            this.createdOn = createdOn;
            this.limited = limited;
            this.notices = Collections.unmodifiableList(new ArrayList<>(notices));
            this.moderation = moderation;
        }

        @Nullable
        public static Me read(@Nullable JsonObject o) {
            Profile profile = Profile.read(o);
            if (profile == null) return null;
            JsonObject privacy = Json.obj(o, "privacy");
            String status = Json.str(o, "status", Status.ONLINE);
            if (!Status.isKnown(status)) status = Status.ONLINE;
            List<ModNotice> notices = new ArrayList<>();
            for (JsonObject n : Json.objects(o, "notices")) {
                ModNotice notice = ModNotice.read(n);
                if (notice != null) notices.add(notice);
            }
            boolean moderation = o != null && (o.has("notices") || o.has("limited"));
            return new Me(profile, Json.str(o, "friendCode", ""), status,
                    Json.bool(privacy, "shareServer", false), Json.bool(privacy, "shareLastSeen", true),
                    Json.str(o, "createdOn"), Limited.read(Json.obj(o, "limited")), notices, moderation);
        }

        public Me withStatus(String newStatus) {
            return new Me(profile, friendCode, newStatus, shareServer, shareLastSeen, createdOn, limited, notices, moderation);
        }

        /** The same account without the notices the player has seen (they go from the service too). */
        public Me withoutNotices(Set<String> seen) {
            List<ModNotice> left = new ArrayList<>();
            for (ModNotice n : notices) if (!seen.contains(n.id)) left.add(n);
            return new Me(profile, friendCode, status, shareServer, shareLastSeen, createdOn, limited, left, moderation);
        }

        /** With a notice that came live ({@code {"t":"notice"}}), unless it is there already. */
        public Me withNotice(ModNotice notice) {
            for (ModNotice n : notices) if (n.id.equals(notice.id)) return this;
            List<ModNotice> next = new ArrayList<>(notices);
            next.add(notice);
            return new Me(profile, friendCode, status, shareServer, shareLastSeen, createdOn, limited, next, true);
        }
    }

    /**
     * SPAM1 ({@code me.limited}): what a Discord account younger than the service's threshold may not
     * do yet, until {@code until} (Unix seconds). Keys the app does not know are kept and ignored.
     */
    public static final class Limited {
        public static final String CREW_FOUND = "crew_found";
        public static final String CREW_INVITES = "crew_invites";
        public static final String CODE_LOOKUPS = "code_lookups";
        public static final String SETTINGS_BACKUP = "settings_backup";
        public static final String CHAT_MESSAGES = "chat_messages";

        public final long until;
        /** key → the daily cap ({@code perDay}; 0 when the item has none). */
        public final Map<String, Integer> items;

        public Limited(long until, Map<String, Integer> items) {
            this.until = until;
            this.items = Collections.unmodifiableMap(new LinkedHashMap<>(items));
        }

        @Nullable
        public static Limited read(@Nullable JsonObject o) {
            if (o == null) return null;
            long until = Json.num(o, "until", 0);
            if (until <= 0) return null;
            Map<String, Integer> items = new LinkedHashMap<>();
            for (JsonObject item : Json.objects(o, "items")) {
                String key = Json.str(item, "key");
                if (key != null && key.matches("[a-z_]{1,32}")) items.put(key, (int) Math.max(0, Json.num(item, "perDay", 0)));
            }
            return new Limited(until, items);
        }

        /** Still limited at {@code nowSeconds} (the limits lift by themselves). */
        public boolean active(long nowSeconds) {
            return nowSeconds < until;
        }

        public boolean has(String key) {
            return items.containsKey(key);
        }

        public int perDay(String key) {
            Integer value = items.get(key);
            return value == null ? 0 : value;
        }

        /** Whole days until {@code until} ("3 gün sonra açılır"): at least 1 while it is ahead, 0 after. */
        public static int daysLeft(long until, long nowSeconds) {
            long left = until - nowSeconds;
            if (left <= 0) return 0;
            return (int) Math.min(366, Math.max(1, (left + 86_399) / 86_400));
        }
    }

    /** MOD1 ({@code me.notices}, {@code {"t":"notice"}}): a moderator's one-time note to the player. */
    public static final class ModNotice {
        public static final String WARNING = "warning";
        public static final String PROFILE_RESET = "profile_reset";

        public final String id;
        /** {@code warning} or {@code profile_reset} (others are kept, shown as a warning). */
        public final String kind;
        public final long at;
        @Nullable
        public final String reason;

        public ModNotice(String id, String kind, long at, @Nullable String reason) {
            this.id = id;
            this.kind = kind;
            this.at = at;
            this.reason = reason;
        }

        @Nullable
        public static ModNotice read(@Nullable JsonObject o) {
            String id = Json.str(o, "id");
            if (id == null) {
                Long number = Json.numOrNull(o, "id");
                if (number != null) id = String.valueOf(number);
            }
            if (id == null || !id.matches("[A-Za-z0-9_:-]{1,64}")) return null;
            String kind = Json.str(o, "kind", WARNING);
            return new ModNotice(id, kind, Json.num(o, "at", 0), Json.str(o, "reason"));
        }
    }

    /** The player's own mode. */
    public static final class Status {
        private Status() {}

        public static final String ONLINE = "online";
        public static final String DND = "dnd";
        public static final String INVISIBLE = "invisible";

        public static boolean isKnown(@Nullable String status) {
            return ONLINE.equals(status) || DND.equals(status) || INVISIBLE.equals(status);
        }
    }

    /** Where someone is: {@code offline}, {@code launcher} or {@code playing}. */
    public static final class Presence {
        public static final String OFFLINE = "offline";
        public static final String LAUNCHER = "launcher";
        public static final String PLAYING = "playing";

        public final String id;
        public final String state;
        public final boolean dnd;
        /** {@code projects}, {@code other}, {@code singleplayer} or null (in game, place unknown). */
        @Nullable
        public final String where;
        @Nullable
        public final String host;
        @Nullable
        public final String lastSeen;
        /** The picture choice the presence carries (a change shows at once), or null. */
        @Nullable
        public final String avatar;
        @Nullable
        public final String avatarHash;

        public Presence(String id, String state, boolean dnd, @Nullable String where, @Nullable String host,
                        @Nullable String lastSeen) {
            this(id, state, dnd, where, host, lastSeen, null, null);
        }

        public Presence(String id, String state, boolean dnd, @Nullable String where, @Nullable String host,
                        @Nullable String lastSeen, @Nullable String avatar, @Nullable String avatarHash) {
            this.id = id;
            this.state = state;
            this.dnd = dnd;
            this.where = where;
            this.host = host;
            this.lastSeen = lastSeen;
            this.avatar = avatar;
            this.avatarHash = avatarHash;
        }

        public static Presence offline(String id, @Nullable String lastSeen) {
            return new Presence(id, OFFLINE, false, null, null, lastSeen);
        }

        @Nullable
        public static Presence read(@Nullable JsonObject o) {
            String id = Json.str(o, "id");
            if (!isUserId(id)) return null;
            String state = Json.str(o, "state", OFFLINE);
            if (!LAUNCHER.equals(state) && !PLAYING.equals(state)) state = OFFLINE;
            JsonObject game = Json.obj(o, "game");
            String where = Invite.normalizeWhere(Json.str(game, "where"));
            boolean playing = PLAYING.equals(state);
            return new Presence(id, state, "dnd".equals(Json.str(o, "status")), playing ? where : null,
                    playing ? Json.str(game, "host") : null, Json.str(o, "lastSeen"),
                    Json.str(o, "avatar"), Json.str(o, "avatarHash"));
        }

        public boolean online() {
            return !OFFLINE.equals(state);
        }

        public boolean playing() {
            return PLAYING.equals(state);
        }

        /** On projects.gg: the app may offer "Katıl". */
        public boolean onProjects() {
            return playing() && "projects".equals(where);
        }

        /** The same presence keeping {@code lastSeen} from {@code before} when this one has none. */
        public Presence keepingLastSeen(@Nullable Presence before) {
            if (lastSeen != null || before == null || before.lastSeen == null) return this;
            return new Presence(id, state, dnd, where, host, before.lastSeen, avatar, avatarHash);
        }
    }

    /** A friend with the moment the friendship started. */
    public static final class Friend {
        public final Profile profile;
        @Nullable
        public final String since;
        @Nullable
        public final String lastSeen;

        public Friend(Profile profile, @Nullable String since, @Nullable String lastSeen) {
            this.profile = profile;
            this.since = since;
            this.lastSeen = lastSeen;
        }

        @Nullable
        public static Friend read(@Nullable JsonObject o) {
            Profile profile = Profile.read(o);
            if (profile == null) return null;
            String since = Json.str(o, "since");
            if (since == null && Json.has(o, "since")) since = String.valueOf(Json.num(o, "since", 0));
            return new Friend(profile, since, Json.str(o, "lastSeen"));
        }
    }

    /** A friend request (incoming or outgoing). */
    public static final class Request {
        public final Profile profile;
        public final long sentAt;

        public Request(Profile profile, long sentAt) {
            this.profile = profile;
            this.sentAt = sentAt;
        }

        @Nullable
        public static Request read(@Nullable JsonObject o) {
            Profile profile = Profile.read(o);
            if (profile == null) return null;
            return new Request(profile, Json.num(o, "sentAt", 0));
        }
    }

    /**
     * A "Birlikte oyna" invite: who sent it and the sender's server, never text. On the socket it is
     * {@code {id, from: <user id>, at, server: {where, host?} | null, quiet}}; it is delivered for an hour.
     */
    public static final class Invite {
        /** How long the hub delivers an invite. */
        public static final long LIFETIME_SECONDS = 3600;

        public final String id;
        public final String fromId;
        public final long at;
        public final long expires;
        /** {@code projects}, {@code other}, {@code singleplayer}, or null when the sender's server is not shared. */
        @Nullable
        public final String where;
        @Nullable
        public final String host;

        public Invite(String id, String fromId, long at, long expires, @Nullable String where, @Nullable String host) {
            this.id = id;
            this.fromId = fromId;
            this.at = at;
            this.expires = expires;
            this.where = where;
            this.host = host;
        }

        /** The socket's invite ({@code hello.invites}, {@code invite}, {@code invites}). */
        @Nullable
        public static Invite read(@Nullable JsonObject o) {
            String from = Json.str(o, "from");
            if (!isUserId(from)) return null;
            String id = Json.str(o, "id");
            if (id == null && Json.has(o, "id")) id = String.valueOf(Json.num(o, "id", 0));
            long at = Json.num(o, "at", 0);
            if (id == null || id.isEmpty()) id = from + "@" + at;
            JsonObject server = Json.obj(o, "server");
            String where = normalizeWhere(Json.str(server, "where"));
            String host = server == null ? null : Json.str(server, "host");
            return new Invite(id, from, at, at > 0 ? at + LIFETIME_SECONDS : 0, where, host);
        }

        @Nullable
        static String normalizeWhere(@Nullable String where) {
            if (where == null) return null;
            if (where.equals("projects") || where.equals("singleplayer")) return where;
            return "other";
        }

        public boolean onProjects() {
            return "projects".equals(where);
        }

        public boolean expired(long nowSeconds) {
            return expires > 0 && nowSeconds >= expires;
        }
    }

    public static boolean isProjectsHost(@Nullable String host) {
        if (host == null) return false;
        String h = host.trim().toLowerCase(Locale.ROOT);
        while (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        return h.equals("projects.gg") || h.endsWith(".projects.gg");
    }

    /** Who may do what in a crew: the lowest role that has each right. */
    public static final class Permissions {
        public static final String MEMBERS = "members";
        public static final String MODERATORS = "moderators";
        public static final String LEADER = "leader";

        public final String invite;
        public final String call;
        public final String kick;
        public final String edit;

        public Permissions(String invite, String call, String kick, String edit) {
            this.invite = invite;
            this.call = call;
            this.kick = kick;
            this.edit = edit;
        }

        /** The service's defaults (and what a service without roles behaves like). */
        public static final Permissions DEFAULTS = new Permissions(MEMBERS, MEMBERS, MODERATORS, LEADER);

        public static Permissions read(@Nullable JsonObject o) {
            if (o == null) return DEFAULTS;
            return new Permissions(level(Json.str(o, "invite"), MEMBERS), level(Json.str(o, "call"), MEMBERS),
                    level(Json.str(o, "kick"), MODERATORS), level(Json.str(o, "edit"), LEADER));
        }

        /** An unknown level counts as {@link #LEADER}: the narrowest reading (the desktop's rule). */
        private static String level(@Nullable String value, String missing) {
            if (value == null) return missing;
            return MEMBERS.equals(value) || MODERATORS.equals(value) ? value : LEADER;
        }

        /** Whether a member of the given role has a right whose level is {@code level}. */
        public static boolean allows(String level, boolean founder, boolean moderator) {
            if (founder) return true;
            if (moderator) return MEMBERS.equals(level) || MODERATORS.equals(level);
            return MEMBERS.equals(level);
        }
    }

    /** A crew member. */
    public static final class Member {
        public final Profile profile;
        public final boolean founder;
        public final boolean moderator;
        public final long joinedAt;

        public Member(Profile profile, boolean founder, boolean moderator, long joinedAt) {
            this.profile = profile;
            this.founder = founder;
            this.moderator = moderator;
            this.joinedAt = joinedAt;
        }

        @Nullable
        public static Member read(@Nullable JsonObject o) {
            JsonObject inner = Json.obj(o, "profile");
            Profile profile = Profile.read(inner != null ? inner : o);
            if (profile == null) return null;
            boolean founder = "founder".equals(Json.str(o, "role"));
            return new Member(profile, founder, !founder && Json.bool(o, "moderator", false), Json.num(o, "joinedAt", 0));
        }
    }

    /** A crew's look and name (what invites and lookups show). */
    public static class TeamSummary {
        public final String id;
        public final String name;
        public final String tag;
        public final String emblem;
        public final String color;
        public final String frame;
        @Nullable
        public final String motto;
        public final int memberCount;

        public TeamSummary(String id, String name, String tag, String emblem, String color, String frame,
                           @Nullable String motto, int memberCount) {
            this.id = id;
            this.name = name;
            this.tag = tag;
            this.emblem = emblem;
            this.color = color;
            this.frame = frame;
            this.motto = motto;
            this.memberCount = memberCount;
        }

        @Nullable
        public static TeamSummary read(@Nullable JsonObject o) {
            String id = Json.str(o, "id");
            String name = Json.str(o, "name");
            if (id == null || name == null) return null;
            String motto = Json.str(o, "motto");
            if (motto != null && motto.trim().isEmpty()) motto = null;
            int members = Json.has(o, "members") && o.get("members").isJsonArray()
                    ? o.getAsJsonArray("members").size() : (int) Json.num(o, "members", 0);
            return new TeamSummary(id, name, Json.str(o, "tag", ""), Json.str(o, "emblem", "shield"),
                    CrewLook.color(Json.str(o, "color")), CrewLook.frame(Json.str(o, "frame")), motto, members);
        }
    }

    /** The player's crew (members see roles and permissions). */
    public static final class Team extends TeamSummary {
        public final String code;
        @Nullable
        public final String createdOn;
        public final List<Member> members;
        public final Permissions permissions;
        /**
         * MOD1: Projects' moderators reset the crew's name, tag and motto; they stay locked until then
         * (Unix seconds). Null when not locked (and from services before MOD1).
         */
        @Nullable
        public final Long textLockedUntil;

        public Team(TeamSummary summary, String code, @Nullable String createdOn, List<Member> members, Permissions permissions) {
            this(summary, code, createdOn, members, permissions, null);
        }

        public Team(TeamSummary summary, String code, @Nullable String createdOn, List<Member> members, Permissions permissions,
                    @Nullable Long textLockedUntil) {
            super(summary.id, summary.name, summary.tag, summary.emblem, summary.color, summary.frame, summary.motto,
                    members.size());
            this.code = code;
            this.createdOn = createdOn;
            this.members = Collections.unmodifiableList(members);
            this.permissions = permissions;
            this.textLockedUntil = textLockedUntil;
        }

        @Nullable
        public static Team read(@Nullable JsonObject o) {
            TeamSummary summary = TeamSummary.read(o);
            if (summary == null) return null;
            List<Member> members = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (JsonObject m : Json.objects(o, "members")) {
                Member member = Member.read(m);
                if (member != null && seen.add(member.profile.id)) members.add(member);
            }
            return new Team(summary, Json.str(o, "code", ""), Json.str(o, "createdOn"), members,
                    Permissions.read(Json.obj(o, "permissions")), Json.numOrNull(o, "textLockedUntil"));
        }

        /** The moderators' lock on the crew's texts is on at {@code nowSeconds}. */
        public boolean textLocked(long nowSeconds) {
            return textLockedUntil != null && nowSeconds < textLockedUntil;
        }

        @Nullable
        public Member member(String userId) {
            for (Member m : members) if (m.profile.id.equals(userId)) return m;
            return null;
        }

        @Nullable
        public Member founder() {
            for (Member m : members) if (m.founder) return m;
            return null;
        }

        /** Whether {@code userId} may do what needs {@code level} in this crew. */
        public boolean allows(String userId, String level) {
            Member m = member(userId);
            return m != null && Permissions.allows(level, m.founder, m.moderator);
        }
    }

    /** An invite to a crew from a friend. */
    public static final class TeamInvite {
        public final TeamSummary team;
        public final Profile from;
        public final long sentAt;

        public TeamInvite(TeamSummary team, Profile from, long sentAt) {
            this.team = team;
            this.from = from;
            this.sentAt = sentAt;
        }

        @Nullable
        public static TeamInvite read(@Nullable JsonObject o) {
            TeamSummary team = TeamSummary.read(Json.obj(o, "team"));
            Profile from = Profile.read(Json.obj(o, "from"));
            if (team == null || from == null) return null;
            return new TeamInvite(team, from, Json.num(o, "sentAt", 0));
        }
    }

    /** The player's own crew notification settings ({@code teamNotifications}, with {@code mute}). */
    public static final class TeamNotifications {
        public final boolean calls;
        public final boolean muted;
        /** Unix seconds; null with {@code muted}: until the player unmutes. */
        @Nullable
        public final Long muteUntil;

        public TeamNotifications(boolean calls, boolean muted, @Nullable Long muteUntil) {
            this.calls = calls;
            this.muted = muted;
            this.muteUntil = muteUntil;
        }

        public static final TeamNotifications DEFAULT = new TeamNotifications(true, false, null);

        public static TeamNotifications read(@Nullable JsonObject o) {
            if (o == null) return DEFAULT;
            boolean muted = Json.bool(o, "muted", false);
            return new TeamNotifications(Json.bool(o, "calls", true), muted, muted ? Json.numOrNull(o, "muteUntil") : null);
        }

        /** Muted at {@code nowSeconds}: a timed mute ends by itself (nothing is sent then). */
        public boolean mutedAt(long nowSeconds) {
            if (!muted) return false;
            return muteUntil == null || nowSeconds < muteUntil;
        }

        /** Crew calls reach the player loudly (a notification): calls on and not muted. */
        public boolean callsAudible(long nowSeconds) {
            return calls && !mutedAt(nowSeconds);
        }
    }

    /** A crew id as the API sends it (16 hex characters today; letters, digits, _ and - at most). */
    public static boolean isCrewId(@Nullable String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{1,64}");
    }

    /**
     * One of the player's crews (TEAM4, {@code crews[]} of {@code GET /v1/friends} with {@code multi}):
     * the crew as members see it, the friends the player invited to it who have not answered, and the
     * player's own mute of it. A service without {@code multi} has one: its {@code team}.
     */
    public static final class Crew {
        public final Team team;
        public final Set<String> invited;
        /** The player's mute of this crew ({@code calls} is always on here: it is the all-crews switch). */
        public final TeamNotifications mute;

        public Crew(Team team, Set<String> invited, TeamNotifications mute) {
            this.team = team;
            this.invited = Collections.unmodifiableSet(new HashSet<>(invited));
            this.mute = mute;
        }

        @Nullable
        public static Crew read(@Nullable JsonObject o) {
            Team team = Team.read(Json.obj(o, "team"));
            if (team == null) return null;
            return new Crew(team, ids(o, "invited"), TeamNotifications.read(Json.obj(o, "notifications")));
        }

        public String id() {
            return team.id;
        }

        public Crew withTeam(Team next) {
            return new Crew(next, invited, mute);
        }

        public Crew withInvited(Set<String> next) {
            return new Crew(team, next, mute);
        }

        public Crew withInvitedAdded(String friendId) {
            Set<String> next = new HashSet<>(invited);
            next.add(friendId);
            return new Crew(team, next, mute);
        }

        public Crew withMute(TeamNotifications next) {
            return new Crew(team, invited, next);
        }
    }

    /** User ids of an array of strings or of {@code {id}} objects. */
    static Set<String> ids(@Nullable JsonObject o, String key) {
        Set<String> out = new HashSet<>();
        for (JsonElement e : Json.arr(o, key)) {
            if (e.isJsonPrimitive() && isUserId(e.getAsString())) out.add(e.getAsString());
            else if (e.isJsonObject()) {
                String id = Json.str(e.getAsJsonObject(), "id");
                if (isUserId(id)) out.add(id);
            }
        }
        return out;
    }

    /**
     * CHAT1 ({@code hello.chat}, {@code chat_state}): whether the service takes chat messages now, keeps
     * them for devices that were away, the player's daily limit and a moderator's mute.
     */
    public static final class ChatState {
        public final boolean on;
        public final boolean mailbox;
        /** Unix seconds when the mailbox opens again; null while not known. */
        @Nullable
        public final Long mailboxBack;
        public final int perDay;
        /** Unix seconds until which the player may not send; null when they may. */
        @Nullable
        public final Long mutedUntil;

        public ChatState(boolean on, boolean mailbox, @Nullable Long mailboxBack, int perDay, @Nullable Long mutedUntil) {
            this.on = on;
            this.mailbox = mailbox;
            this.mailboxBack = mailboxBack;
            this.perDay = perDay;
            this.mutedUntil = mutedUntil;
        }

        /** Before the first {@code hello}: assumed on (the composer shows; the service answers anyway). */
        public static final ChatState UNKNOWN = new ChatState(true, true, null, 0, null);

        public static ChatState read(@Nullable JsonObject o) {
            if (o == null) return UNKNOWN;
            return new ChatState(Json.bool(o, "on", true), Json.bool(o, "mailbox", true), Json.numOrNull(o, "mailboxBack"),
                    (int) Json.num(o, "perDay", 0), Json.numOrNull(o, "mutedUntil"));
        }

        public boolean mutedAt(long hubNowSeconds) {
            return mutedUntil != null && hubNowSeconds < mutedUntil;
        }

        /** The mailbox is off (messages reach only those online) at {@code hubNowSeconds}. */
        public boolean liveOnlyAt(long hubNowSeconds) {
            return on && !mailbox && (mailboxBack == null || hubNowSeconds < mailboxBack);
        }

        public ChatState withMutedUntil(@Nullable Long until) {
            return new ChatState(on, mailbox, mailboxBack, perDay, until);
        }
    }

    /** An open crew call ("Ekibi çağır"). */
    public static final class Call {
        public static final String JOIN = "join";
        public static final String READY = "ready";
        /** Where a call is: on projects.gg, on another server, or (an older service) "Hazır mısın?". */
        public enum Place { PROJECTS, OTHER, READY }

        public final String id;
        public final String from;
        public final String kind;
        public final long at;
        /** Ends at this moment by the hub's clock (Unix seconds). */
        public final long until;
        /** Answers by user id: {@code yes}, {@code soon} or {@code no}. */
        public final Map<String, String> answers;
        /**
         * SOC7 ({@code crews} capability): the player's crews the call names, in the player's order
         * (for the caller: all); empty from older services (the primary crew).
         */
        public final List<String> crews;
        /** SOC7 ({@code callServer}): {@code projects} or {@code other}; null from older services. */
        @Nullable
        public final String serverWhere;
        /** The server's address as the caller's launcher reported it ({@code play.projects.gg}, another host). */
        @Nullable
        public final String serverHost;

        public Call(String id, String from, String kind, long at, long until, Map<String, String> answers) {
            this(id, from, kind, at, until, answers, Collections.<String>emptyList(), null, null);
        }

        public Call(String id, String from, String kind, long at, long until, Map<String, String> answers,
                    List<String> crews, @Nullable String serverWhere, @Nullable String serverHost) {
            this.id = id;
            this.from = from;
            this.kind = kind;
            this.at = at;
            this.until = until;
            this.answers = Collections.unmodifiableMap(answers);
            this.crews = Collections.unmodifiableList(new ArrayList<>(crews));
            this.serverWhere = serverWhere;
            this.serverHost = serverHost;
        }

        @Nullable
        public static Call read(@Nullable JsonObject o) {
            String id = Json.str(o, "id");
            String from = Json.str(o, "from");
            if (id == null || id.isEmpty() || !isUserId(from)) return null;
            String kind = JOIN.equals(Json.str(o, "kind")) ? JOIN : READY;
            Map<String, String> answers = new LinkedHashMap<>();
            JsonObject a = Json.obj(o, "answers");
            if (a != null) {
                for (Map.Entry<String, JsonElement> e : a.entrySet()) {
                    String value = e.getValue().isJsonPrimitive() ? e.getValue().getAsString() : null;
                    if (isUserId(e.getKey()) && isAnswer(value)) answers.put(e.getKey(), value);
                }
            }
            List<String> crews = new ArrayList<>();
            for (String crew : Json.strings(o, "crews")) if (isCrewId(crew) && !crews.contains(crew)) crews.add(crew);
            JsonObject server = Json.obj(o, "server");
            String where = null;
            String host = null;
            if (server != null) {
                String w = Json.str(server, "where");
                host = cleanHost(Json.str(server, "host"));
                where = "projects".equals(w) || isProjectsHost(host) ? "projects" : "other";
            }
            return new Call(id, from, kind, Json.num(o, "at", 0), Json.num(o, "until", 0), answers, crews, where, host);
        }

        /** A host name as the service passes it on, or null when it does not look like one. */
        @Nullable
        static String cleanHost(@Nullable String host) {
            if (host == null) return null;
            String h = host.trim();
            return h.matches("[A-Za-z0-9.:\\[\\]-]{1,260}") ? h : null;
        }

        public static boolean isAnswer(@Nullable String value) {
            return "yes".equals(value) || "soon".equals(value) || "no".equals(value);
        }

        public Call withAnswer(String userId, String answer) {
            Map<String, String> next = new LinkedHashMap<>(answers);
            if (isAnswer(answer)) next.put(userId, answer);
            return new Call(id, from, kind, at, until, next, crews, serverWhere, serverHost);
        }

        public Call withoutAnswer(String userId) {
            Map<String, String> next = new LinkedHashMap<>(answers);
            next.remove(userId);
            return new Call(id, from, kind, at, until, next, crews, serverWhere, serverHost);
        }

        /** The same call naming only {@code crews} (a crew of the player's ended or the player left it). */
        public Call withCrews(List<String> crews) {
            return new Call(id, from, kind, at, until, answers, crews, serverWhere, serverHost);
        }

        /**
         * Where it is: with {@code callServer} the call's server; from an older service {@code join}
         * means projects.gg and {@code ready} "Hazır mısın?" (another server, or the launcher).
         */
        public Place place() {
            if (serverWhere != null) return "projects".equals(serverWhere) ? Place.PROJECTS : Place.OTHER;
            return JOIN.equals(kind) ? Place.PROJECTS : Place.READY;
        }

        /** "Geliyorum" takes the player to projects.gg. */
        public boolean onProjects() {
            return place() == Place.PROJECTS;
        }

        /** The other server's address ("Geliyorum" shows it), or null. */
        @Nullable
        public String otherHost() {
            return place() == Place.OTHER && serverHost != null && !serverHost.isEmpty() ? serverHost : null;
        }

        /** Kept for M19's callers: a call the player can join on projects.gg. */
        public boolean joinable() {
            return onProjects();
        }

        public int count(String answer) {
            int n = 0;
            for (String value : answers.values()) if (answer.equals(value)) n++;
            return n;
        }
    }

    /** {@code limits} of {@code GET /v1/friends}; zero means the service did not say. */
    public static final class Limits {
        public final int friends;
        public final int teamMembers;
        public final int teamModerators;

        public Limits(int friends, int teamMembers, int teamModerators) {
            this.friends = friends;
            this.teamMembers = teamMembers;
            this.teamModerators = teamModerators;
        }

        public static Limits read(@Nullable JsonObject o) {
            return new Limits((int) Json.num(o, "friends", 0), (int) Json.num(o, "teamMembers", 0),
                    (int) Json.num(o, "teamModerators", 0));
        }
    }

    /** {@code GET /v1/friends}: everything the friends screen shows, read at once. */
    public static final class FriendsData {
        public final List<Friend> friends;
        public final List<Request> incoming;
        public final List<Request> outgoing;
        public final List<Profile> blocked;
        public final Limits limits;
        @Nullable
        public final Team team;
        /** Friend ids the player invited to the crew who have not answered. */
        public final Set<String> teamInvited;
        public final List<TeamInvite> teamInvites;
        public final Set<String> teamFeatures;
        public final TeamNotifications teamNotifications;
        /** {@code features} (null: a service from before pushes). */
        @Nullable
        public final Set<String> features;
        /** TEAM4: every crew of the player in their order, the primary first; null from services before it. */
        @Nullable
        public final List<Crew> crews;
        /** TEAM4: how many crews a player may be in (0: the service did not say). */
        public final int crewLimit;

        public FriendsData(List<Friend> friends, List<Request> incoming, List<Request> outgoing, List<Profile> blocked,
                           Limits limits, @Nullable Team team, Set<String> teamInvited, List<TeamInvite> teamInvites,
                           Set<String> teamFeatures, TeamNotifications teamNotifications, @Nullable Set<String> features) {
            this(friends, incoming, outgoing, blocked, limits, team, teamInvited, teamInvites, teamFeatures, teamNotifications,
                    features, null, 0);
        }

        public FriendsData(List<Friend> friends, List<Request> incoming, List<Request> outgoing, List<Profile> blocked,
                           Limits limits, @Nullable Team team, Set<String> teamInvited, List<TeamInvite> teamInvites,
                           Set<String> teamFeatures, TeamNotifications teamNotifications, @Nullable Set<String> features,
                           @Nullable List<Crew> crews, int crewLimit) {
            this.friends = friends;
            this.incoming = incoming;
            this.outgoing = outgoing;
            this.blocked = blocked;
            this.limits = limits;
            this.team = team;
            this.teamInvited = teamInvited;
            this.teamInvites = teamInvites;
            this.teamFeatures = teamFeatures;
            this.teamNotifications = teamNotifications;
            this.features = features;
            this.crews = crews;
            this.crewLimit = crewLimit;
        }

        public static FriendsData read(@Nullable JsonObject o) {
            List<Friend> friends = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (JsonObject f : Json.objects(o, "friends")) {
                Friend friend = Friend.read(f);
                if (friend != null && seen.add(friend.profile.id)) friends.add(friend);
            }
            List<Request> incoming = requests(o, "incoming");
            List<Request> outgoing = requests(o, "outgoing");
            List<Profile> blocked = new ArrayList<>();
            for (JsonObject b : Json.objects(o, "blocked")) {
                Profile p = Profile.read(b);
                if (p != null) blocked.add(p);
            }
            Set<String> invited = new HashSet<>();
            for (JsonElement e : Json.arr(o, "teamInvited")) {
                if (e.isJsonPrimitive() && isUserId(e.getAsString())) invited.add(e.getAsString());
                else if (e.isJsonObject()) {
                    String id = Json.str(e.getAsJsonObject(), "id");
                    if (isUserId(id)) invited.add(id);
                }
            }
            // GET /v1/teams and the team routes answer {team, invited}: the same ids.
            for (JsonElement e : Json.arr(o, "invited")) {
                if (e.isJsonPrimitive() && isUserId(e.getAsString())) invited.add(e.getAsString());
            }
            List<TeamInvite> teamInvites = new ArrayList<>();
            for (JsonObject t : Json.objects(o, "teamInvites")) {
                TeamInvite invite = TeamInvite.read(t);
                if (invite != null) teamInvites.add(invite);
            }
            Set<String> features = Json.has(o, "features") ? new HashSet<>(Json.strings(o, "features")) : null;
            List<Crew> crews = null;
            if (o != null && o.has("crews") && o.get("crews").isJsonArray()) {
                crews = new ArrayList<>();
                Set<String> crewIds = new HashSet<>();
                for (JsonObject c : Json.objects(o, "crews")) {
                    Crew crew = Crew.read(c);
                    if (crew != null && crewIds.add(crew.id())) crews.add(crew);
                }
            }
            return new FriendsData(friends, incoming, outgoing, blocked, Limits.read(Json.obj(o, "limits")),
                    Team.read(Json.obj(o, "team")), invited, teamInvites, new HashSet<>(Json.strings(o, "teamFeatures")),
                    TeamNotifications.read(Json.obj(o, "teamNotifications")), features, crews,
                    (int) Math.max(0, Json.num(o, "crewLimit", 0)));
        }

        private static List<Request> requests(@Nullable JsonObject o, String key) {
            List<Request> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (JsonObject r : Json.objects(o, key)) {
                Request request = Request.read(r);
                if (request != null && seen.add(request.profile.id)) out.add(request);
            }
            return out;
        }
    }
}
