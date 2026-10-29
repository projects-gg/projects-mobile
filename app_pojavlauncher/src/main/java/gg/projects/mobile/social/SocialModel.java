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
 * "Joining a crew", "Crew calls", "Crew plans", "Crew notifications", "Push notifications"). Every
 * reader is lenient: unknown fields are ignored and a missing one takes its default, so older and
 * newer services both read.
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
        /**
         * TEAM6: the crew tag this player wears, as friends and crewmates see it (null: none, or a
         * profile that does not reach the player as a friend or crewmate, or a service before tags).
         */
        @Nullable
        public final Badge badge;

        public Profile(String id, String username, @Nullable String displayName, String avatar, @Nullable String avatarHash) {
            this(id, username, displayName, avatar, avatarHash, null);
        }

        public Profile(String id, String username, @Nullable String displayName, String avatar, @Nullable String avatarHash,
                       @Nullable Badge badge) {
            this.id = id;
            this.username = username;
            this.displayName = displayName;
            this.avatar = avatar;
            this.avatarHash = avatarHash;
            this.badge = badge;
        }

        @Nullable
        public static Profile read(@Nullable JsonObject o) {
            String id = Json.str(o, "id");
            if (!isUserId(id)) return null;
            String username = Json.str(o, "username", "");
            String display = Json.str(o, "displayName");
            if (display != null && display.trim().isEmpty()) display = null;
            return new Profile(id, username, display, Json.str(o, "avatar", "discord"), Json.str(o, "avatarHash"),
                    Badge.read(Json.obj(o, "badge")));
        }

        /** The same player with another tag (TEAM6's {@code badge} message; null takes it off). */
        public Profile withBadge(@Nullable Badge next) {
            return new Profile(id, username, displayName, avatar, avatarHash, next);
        }

        /** The same player with another picture (a presence that carries one). */
        public Profile withAvatar(String nextAvatar, @Nullable String nextHash) {
            return new Profile(id, username, displayName, nextAvatar, nextHash, badge);
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

    /**
     * TEAM6 ({@code badge} of a profile): the card of the crew tag a player wears, made from the
     * crew's own row at every read (README "Crew tags"): never its members, leader, code or link.
     */
    public static final class Badge {
        public final String crew;
        public final String tag;
        public final String color;
        public final String frame;
        public final String emblem;
        public final String name;
        /** "" when it has none. */
        public final String motto;
        /** {@code YYYY-MM-DD}, or null. */
        @Nullable
        public final String createdOn;
        public final int members;
        /** The crew takes join requests from its tag now (README "Join requests from a tag"). */
        public final boolean requests;

        public Badge(String crew, String tag, String color, String frame, String emblem, String name, String motto,
                     @Nullable String createdOn, int members, boolean requests) {
            this.crew = crew;
            this.tag = tag;
            this.color = color;
            this.frame = frame;
            this.emblem = emblem;
            this.name = name;
            this.motto = motto;
            this.createdOn = createdOn;
            this.members = members;
            this.requests = requests;
        }

        /** The card, or null (none worn, or one the app cannot show: no crew id, tag or name). */
        @Nullable
        public static Badge read(@Nullable JsonObject o) {
            if (o == null) return null;
            String crew = Json.str(o, "crew");
            String tag = Json.str(o, "tag");
            String name = Json.str(o, "name");
            if (!isCrewId(crew) || tag == null || tag.trim().isEmpty() || name == null || name.trim().isEmpty()) return null;
            String motto = Json.str(o, "motto", "").trim();
            return new Badge(crew, tag.trim(), CrewLook.color(Json.str(o, "color")), CrewLook.frame(Json.str(o, "frame")),
                    Json.str(o, "emblem", "shield"), name, motto, Json.str(o, "createdOn"),
                    (int) Math.max(0, Json.num(o, "members", 0)), Json.bool(o, "requests", false));
        }

        /** What a change of the card means on screen (its look or words). */
        public String key() {
            return crew + "|" + tag + "|" + color + "|" + frame + "|" + emblem + "|" + name + "|" + motto + "|" + members + "|" + requests;
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
        /** CHAT2: "Görüldü bilgisi" (on unless the player turned it off; works both ways). */
        public final boolean shareRead;
        /** CHAT2: "Yazıyor bilgisi". */
        public final boolean shareTyping;
        /** CHAT2: the service has the two switches ({@code privacy} carries them). */
        public final boolean chatPrivacy;

        public Me(Profile profile, String friendCode, String status, boolean shareServer, boolean shareLastSeen,
                  @Nullable String createdOn) {
            this(profile, friendCode, status, shareServer, shareLastSeen, createdOn, null,
                    Collections.<ModNotice>emptyList(), false);
        }

        public Me(Profile profile, String friendCode, String status, boolean shareServer, boolean shareLastSeen,
                  @Nullable String createdOn, @Nullable Limited limited, List<ModNotice> notices, boolean moderation) {
            this(profile, friendCode, status, shareServer, shareLastSeen, createdOn, limited, notices, moderation, true, true, false);
        }

        public Me(Profile profile, String friendCode, String status, boolean shareServer, boolean shareLastSeen,
                  @Nullable String createdOn, @Nullable Limited limited, List<ModNotice> notices, boolean moderation,
                  boolean shareRead, boolean shareTyping, boolean chatPrivacy) {
            this.profile = profile;
            this.friendCode = friendCode;
            this.status = status;
            this.shareServer = shareServer;
            this.shareLastSeen = shareLastSeen;
            this.createdOn = createdOn;
            this.limited = limited;
            this.notices = Collections.unmodifiableList(new ArrayList<>(notices));
            this.moderation = moderation;
            this.shareRead = shareRead;
            this.shareTyping = shareTyping;
            this.chatPrivacy = chatPrivacy;
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
            boolean chatPrivacy = privacy != null && (privacy.has("shareRead") || privacy.has("shareTyping"));
            return new Me(profile, Json.str(o, "friendCode", ""), status,
                    Json.bool(privacy, "shareServer", false), Json.bool(privacy, "shareLastSeen", true),
                    Json.str(o, "createdOn"), Limited.read(Json.obj(o, "limited")), notices, moderation,
                    Json.bool(privacy, "shareRead", true), Json.bool(privacy, "shareTyping", true), chatPrivacy);
        }

        public Me withStatus(String newStatus) {
            return new Me(profile, friendCode, newStatus, shareServer, shareLastSeen, createdOn, limited, notices, moderation,
                    shareRead, shareTyping, chatPrivacy);
        }

        /** The same account without the notices the player has seen (they go from the service too). */
        public Me withoutNotices(Set<String> seen) {
            List<ModNotice> left = new ArrayList<>();
            for (ModNotice n : notices) if (!seen.contains(n.id)) left.add(n);
            return new Me(profile, friendCode, status, shareServer, shareLastSeen, createdOn, limited, left, moderation,
                    shareRead, shareTyping, chatPrivacy);
        }

        /** With a notice that came live ({@code {"t":"notice"}}), unless it is there already. */
        public Me withNotice(ModNotice notice) {
            for (ModNotice n : notices) if (n.id.equals(notice.id)) return this;
            List<ModNotice> next = new ArrayList<>(notices);
            next.add(notice);
            return new Me(profile, friendCode, status, shareServer, shareLastSeen, createdOn, limited, next, true,
                    shareRead, shareTyping, chatPrivacy);
        }

        /** TEAM6: the same account wearing another tag (null: none). */
        public Me withBadge(@Nullable Badge badge) {
            return new Me(profile.withBadge(badge), friendCode, status, shareServer, shareLastSeen, createdOn, limited, notices,
                    moderation, shareRead, shareTyping, chatPrivacy);
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
        /** TEAM5: making or renewing a crew's invite link (listed with {@code link} in {@code teamFeatures}). */
        public static final String CREW_LINK = "crew_link";
        /** TEAM5: making crew plans; answering them is fine (listed with {@code plans}). */
        public static final String CREW_PLANS = "crew_plans";
        /** CHAT2: links in chat messages (listed while chat and Studio allow links). */
        public static final String CHAT_LINKS = "chat_links";
        /** CHAT2: fewer stickers a day ({@code perDay}). */
        public static final String CHAT_STICKERS = "chat_stickers";
        /** CHAT2: fewer reaction changes a day ({@code perDay}). */
        public static final String CHAT_REACTIONS = "chat_reactions";

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
        /** SOC9: the Minecraft version they play ({@code game.ver}, {@link GameVersion}); null when not playing or not said. */
        @Nullable
        public final String ver;

        public Presence(String id, String state, boolean dnd, @Nullable String where, @Nullable String host,
                        @Nullable String lastSeen) {
            this(id, state, dnd, where, host, lastSeen, null, null);
        }

        public Presence(String id, String state, boolean dnd, @Nullable String where, @Nullable String host,
                        @Nullable String lastSeen, @Nullable String avatar, @Nullable String avatarHash) {
            this(id, state, dnd, where, host, lastSeen, avatar, avatarHash, null);
        }

        public Presence(String id, String state, boolean dnd, @Nullable String where, @Nullable String host,
                        @Nullable String lastSeen, @Nullable String avatar, @Nullable String avatarHash, @Nullable String ver) {
            this.id = id;
            this.state = state;
            this.dnd = dnd;
            this.where = where;
            this.host = host;
            this.lastSeen = lastSeen;
            this.avatar = avatar;
            this.avatarHash = avatarHash;
            this.ver = PLAYING.equals(state) ? GameVersion.wire(ver) : null;
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
                    Json.str(o, "avatar"), Json.str(o, "avatarHash"), playing ? Json.str(game, "ver") : null);
        }

        /** SOC9: a playing line with the version after it ("Projects'te · 1.21.4"), as it is: no "sürüm", no translation. */
        public String withVersion(String place) {
            return ver == null ? place : place + " · " + ver;
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
            return new Presence(id, state, dnd, where, host, before.lastSeen, avatar, avatarHash, ver);
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
        /** Calling the crew, and (TEAM5) making plans. */
        public final String call;
        public final String kick;
        public final String edit;
        /** TEAM5 ({@code requests}): answering join requests and the invite link ({@code moderators} or {@code leader}). */
        public final String admit;

        public Permissions(String invite, String call, String kick, String edit) {
            this(invite, call, kick, edit, MODERATORS);
        }

        public Permissions(String invite, String call, String kick, String edit, String admit) {
            this.invite = invite;
            this.call = call;
            this.kick = kick;
            this.edit = edit;
            this.admit = admit;
        }

        /** The service's defaults (and what a service without roles behaves like). */
        public static final Permissions DEFAULTS = new Permissions(MEMBERS, MEMBERS, MODERATORS, LEADER, MODERATORS);

        public static Permissions read(@Nullable JsonObject o) {
            if (o == null) return DEFAULTS;
            // admit is never "members" (the service takes moderators or leader): a member's row never answers requests.
            String admit = level(Json.str(o, "admit"), MODERATORS);
            return new Permissions(level(Json.str(o, "invite"), MEMBERS), level(Json.str(o, "call"), MEMBERS),
                    level(Json.str(o, "kick"), MODERATORS), level(Json.str(o, "edit"), LEADER),
                    MEMBERS.equals(admit) ? MODERATORS : admit);
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
            // TEAM6: the member's tag rides beside the profile.
            if (profile.badge == null && inner != null) profile = profile.withBadge(Badge.read(Json.obj(o, "badge")));
            boolean founder = "founder".equals(Json.str(o, "role"));
            return new Member(profile, founder, !founder && Json.bool(o, "moderator", false), Json.num(o, "joinedAt", 0));
        }

        /** The same member wearing another tag. */
        public Member withBadge(@Nullable Badge badge) {
            return new Member(profile.withBadge(badge), founder, moderator, joinedAt);
        }
    }

    /** A crew's look and name (what invites and lookups show). */
    public static class TeamSummary {
        /** TEAM5 {@code join}: the code and the invite link let anyone in at once (the default, and every older crew). */
        public static final String JOIN_OPEN = "open";
        /** TEAM5 {@code join}: newcomers with the code or the link ask; the leader (or moderators) take them in. */
        public static final String JOIN_APPROVAL = "approval";

        public final String id;
        public final String name;
        public final String tag;
        public final String emblem;
        public final String color;
        public final String frame;
        @Nullable
        public final String motto;
        public final int memberCount;
        /** {@link #JOIN_OPEN} or {@link #JOIN_APPROVAL} (an unknown value reads as approval: the careful side). */
        public final String join;

        public TeamSummary(String id, String name, String tag, String emblem, String color, String frame,
                           @Nullable String motto, int memberCount) {
            this(id, name, tag, emblem, color, frame, motto, memberCount, JOIN_OPEN);
        }

        public TeamSummary(String id, String name, String tag, String emblem, String color, String frame,
                           @Nullable String motto, int memberCount, String join) {
            this.id = id;
            this.name = name;
            this.tag = tag;
            this.emblem = emblem;
            this.color = color;
            this.frame = frame;
            this.motto = motto;
            this.memberCount = memberCount;
            this.join = join;
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
                    CrewLook.color(Json.str(o, "color")), CrewLook.frame(Json.str(o, "frame")), motto, members,
                    joinOf(Json.str(o, "join")));
        }

        static String joinOf(@Nullable String join) {
            if (join == null || JOIN_OPEN.equals(join)) return JOIN_OPEN;
            return JOIN_APPROVAL;
        }

        /** Newcomers with the code or the link ask first (TEAM5). */
        public boolean approval() {
            return JOIN_APPROVAL.equals(join);
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
        /** TEAM5: the crew's invite link as {@code XXXX-XXXX-XXXX}, or null (none, or a service from before links). */
        @Nullable
        public final String link;
        /** TEAM6: its leader hid it: its tag shows nowhere and cannot be worn ({@code false} from older services). */
        public final boolean hidden;
        /** TEAM6 "Etiketten istek": friends of its wearers may ask to join from the tag's card (on by default). */
        public final boolean badgeRequests;

        public Team(TeamSummary summary, String code, @Nullable String createdOn, List<Member> members, Permissions permissions) {
            this(summary, code, createdOn, members, permissions, null);
        }

        public Team(TeamSummary summary, String code, @Nullable String createdOn, List<Member> members, Permissions permissions,
                    @Nullable Long textLockedUntil) {
            this(summary, code, createdOn, members, permissions, textLockedUntil, null);
        }

        public Team(TeamSummary summary, String code, @Nullable String createdOn, List<Member> members, Permissions permissions,
                    @Nullable Long textLockedUntil, @Nullable String link) {
            this(summary, code, createdOn, members, permissions, textLockedUntil, link, false, true);
        }

        public Team(TeamSummary summary, String code, @Nullable String createdOn, List<Member> members, Permissions permissions,
                    @Nullable Long textLockedUntil, @Nullable String link, boolean hidden, boolean badgeRequests) {
            super(summary.id, summary.name, summary.tag, summary.emblem, summary.color, summary.frame, summary.motto,
                    members.size(), summary.join);
            this.code = code;
            this.createdOn = createdOn;
            this.members = Collections.unmodifiableList(members);
            this.permissions = permissions;
            this.textLockedUntil = textLockedUntil;
            this.link = link;
            this.hidden = hidden;
            this.badgeRequests = badgeRequests;
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
                    Permissions.read(Json.obj(o, "permissions")), Json.numOrNull(o, "textLockedUntil"),
                    SocialCodes.normalizeInviteLink(Json.str(o, "link")), Json.bool(o, "hidden", false),
                    Json.bool(o, "badgeRequests", true));
        }

        /** The same crew with another invite link (null: switched off), as an answer of its own route says. */
        public Team withLink(@Nullable String newLink) {
            return new Team(this, code, createdOn, new ArrayList<>(members), permissions, textLockedUntil, newLink, hidden,
                    badgeRequests);
        }

        /** TEAM6: the same crew with one member's tag changed (a {@code badge} message). */
        public Team withMemberBadge(String userId, @Nullable Badge badge) {
            List<Member> next = new ArrayList<>();
            boolean changed = false;
            for (Member m : members) {
                if (m.profile.id.equals(userId)) {
                    next.add(m.withBadge(badge));
                    changed = true;
                } else {
                    next.add(m);
                }
            }
            return changed ? new Team(this, code, createdOn, next, permissions, textLockedUntil, link, hidden, badgeRequests) : this;
        }

        /** TEAM6: members wearing this crew's own tag now. */
        public int wearers() {
            int n = 0;
            for (Member m : members) if (m.profile.badge != null && m.profile.badge.crew.equals(id)) n++;
            return n;
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

    /** How long a join request waits before it goes (README "Joining a crew"); nothing is sent then. */
    public static final long JOIN_REQUEST_TTL_SECONDS = 7 * 86_400L;

    /**
     * TEAM5: someone asked to join a crew the player may answer for ({@code crews[].requests},
     * {@code team_requests}): who and when.
     */
    public static final class JoinRequest {
        public final Profile from;
        public final long sentAt;
        /** TEAM6: the member whose tag's card it came from ("Ayşe aracılığıyla"), while still in the crew; else null. */
        @Nullable
        public final String via;

        public JoinRequest(Profile from, long sentAt) {
            this(from, sentAt, null);
        }

        public JoinRequest(Profile from, long sentAt, @Nullable String via) {
            this.from = from;
            this.sentAt = sentAt;
            this.via = isUserId(via) ? via : null;
        }

        @Nullable
        public static JoinRequest read(@Nullable JsonObject o) {
            JsonObject inner = Json.obj(o, "from");
            Profile from = Profile.read(inner != null ? inner : o);
            if (from == null) return null;
            // A requester's tag never shows (strangers see none): the service sends none, and none is kept.
            return new JoinRequest(from.withBadge(null), Json.num(o, "sentAt", 0), Json.str(o, "via"));
        }

        /** Still waiting at {@code nowSeconds} (launchers drop it 7 days after it was sent). */
        public boolean waiting(long nowSeconds) {
            return sentAt <= 0 || nowSeconds < sentAt + JOIN_REQUEST_TTL_SECONDS;
        }
    }

    /** TEAM5: the player's own waiting join request to a crew that approves newcomers ({@code teamRequests}). */
    public static final class MyRequest {
        public final TeamSummary team;
        public final long sentAt;

        public MyRequest(TeamSummary team, long sentAt) {
            this.team = team;
            this.sentAt = sentAt;
        }

        @Nullable
        public static MyRequest read(@Nullable JsonObject o) {
            TeamSummary team = TeamSummary.read(Json.obj(o, "team"));
            if (team == null) return null;
            return new MyRequest(team, Json.num(o, "sentAt", 0));
        }

        public boolean waiting(long nowSeconds) {
            return sentAt <= 0 || nowSeconds < sentAt + JOIN_REQUEST_TTL_SECONDS;
        }
    }

    /**
     * TEAM5 ("Planlar", README "Crew plans"): a time to meet on Projects and who is coming. {@code at}
     * is a whole quarter of an hour; the plan is over at {@code until} ({@code at} + 2 hours) by the
     * hub's clock, when nothing is sent. FIX2 ({@code planNotes}): an optional short note, its only
     * text ({@link PlanNote}).
     */
    public static final class Plan {
        public static final String YES = "yes";
        public static final String MAYBE = "maybe";
        public static final String NO = "no";
        /** {@code at} is a multiple of this (15 minutes). */
        public static final long STEP_SECONDS = 900;
        /** At most this far ahead. */
        public static final long AHEAD_SECONDS = 7 * 86_400L;
        /** A plan is over this long after its time. */
        public static final long AFTER_SECONDS = 2 * 3_600L;
        /** Upcoming plans per crew. */
        public static final int PER_CREW = 3;

        public final String id;
        public final String crew;
        public final String by;
        public final long at;
        public final long until;
        public final long createdAt;
        /** Answers by user id: {@code yes}, {@code maybe} or {@code no} (only those the player may see). */
        public final Map<String, String> answers;
        /** FIX2: the plan's note, cleaned; null when it has none (and from services before {@code planNotes}). */
        @Nullable
        public final String note;

        public Plan(String id, String crew, String by, long at, long until, long createdAt, Map<String, String> answers) {
            this(id, crew, by, at, until, createdAt, answers, null);
        }

        public Plan(String id, String crew, String by, long at, long until, long createdAt, Map<String, String> answers,
                    @Nullable String note) {
            this.id = id;
            this.crew = crew;
            this.by = by;
            this.at = at;
            this.until = until;
            this.createdAt = createdAt;
            this.answers = Collections.unmodifiableMap(new LinkedHashMap<>(answers));
            this.note = PlanNote.of(note);
        }

        @Nullable
        public static Plan read(@Nullable JsonObject o) {
            String id = Json.str(o, "id");
            String crew = Json.str(o, "crew");
            String by = Json.str(o, "by");
            long at = Json.num(o, "at", 0);
            if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}") || !isCrewId(crew) || !isUserId(by) || at <= 0) return null;
            Map<String, String> answers = new LinkedHashMap<>();
            JsonObject a = Json.obj(o, "answers");
            if (a != null) {
                for (Map.Entry<String, JsonElement> e : a.entrySet()) {
                    String value = e.getValue().isJsonPrimitive() ? e.getValue().getAsString() : null;
                    if (isUserId(e.getKey()) && isAnswer(value)) answers.put(e.getKey(), value);
                }
            }
            long until = Json.num(o, "until", 0);
            if (until <= at) until = at + AFTER_SECONDS;
            JsonElement note = o.get("note");
            String text = note != null && note.isJsonPrimitive() && note.getAsJsonPrimitive().isString() ? note.getAsString() : null;
            return new Plan(id, crew, by, at, until, Json.num(o, "createdAt", 0), answers, text);
        }

        public static boolean isAnswer(@Nullable String value) {
            return YES.equals(value) || MAYBE.equals(value) || NO.equals(value);
        }

        public Plan withAnswer(String userId, @Nullable String answer) {
            Map<String, String> next = new LinkedHashMap<>(answers);
            if (answer == null) next.remove(userId);
            else if (isAnswer(answer)) next.put(userId, answer);
            return new Plan(id, crew, by, at, until, createdAt, next, note);
        }

        /** The same plan with another note (null or "": none). */
        public Plan withNote(@Nullable String next) {
            return new Plan(id, crew, by, at, until, createdAt, answers, next);
        }

        /** The players who answered {@code answer}, in the order the service listed them. */
        public List<String> answered(String answer) {
            List<String> out = new ArrayList<>();
            for (Map.Entry<String, String> e : answers.entrySet()) if (answer.equals(e.getValue())) out.add(e.getKey());
            return out;
        }

        public int count(String answer) {
            int n = 0;
            for (String value : answers.values()) if (answer.equals(value)) n++;
            return n;
        }

        /** Not over at {@code hubNowSeconds}. */
        public boolean live(long hubNowSeconds) {
            return hubNowSeconds < until;
        }

        /** Its time has come and it is not over: they are meeting now. */
        public boolean underWay(long hubNowSeconds) {
            return hubNowSeconds >= at && hubNowSeconds < until;
        }

        /** Still ahead (counts towards the crew's 3 upcoming plans). */
        public boolean upcoming(long hubNowSeconds) {
            return at > hubNowSeconds;
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
        /** TEAM5: the crew's waiting join requests, oldest first, while the player may answer them (else empty). */
        public final List<JoinRequest> requests;

        public Crew(Team team, Set<String> invited, TeamNotifications mute) {
            this(team, invited, mute, Collections.<JoinRequest>emptyList());
        }

        public Crew(Team team, Set<String> invited, TeamNotifications mute, List<JoinRequest> requests) {
            this.team = team;
            this.invited = Collections.unmodifiableSet(new HashSet<>(invited));
            this.mute = mute;
            this.requests = Collections.unmodifiableList(new ArrayList<>(requests));
        }

        @Nullable
        public static Crew read(@Nullable JsonObject o) {
            Team team = Team.read(Json.obj(o, "team"));
            if (team == null) return null;
            return new Crew(team, ids(o, "invited"), TeamNotifications.read(Json.obj(o, "notifications")), joinRequests(o, "requests"));
        }

        public String id() {
            return team.id;
        }

        public Crew withTeam(Team next) {
            return new Crew(next, invited, mute, requests);
        }

        public Crew withInvited(Set<String> next) {
            return new Crew(team, next, mute, requests);
        }

        public Crew withInvitedAdded(String friendId) {
            Set<String> next = new HashSet<>(invited);
            next.add(friendId);
            return new Crew(team, next, mute, requests);
        }

        public Crew withMute(TeamNotifications next) {
            return new Crew(team, invited, next, requests);
        }

        public Crew withRequests(List<JoinRequest> next) {
            return new Crew(team, invited, mute, next);
        }
    }

    /** Join requests of an array of {@code {from, sentAt}}, each player once (oldest first, as the service sends them). */
    static List<JoinRequest> joinRequests(@Nullable JsonObject o, String key) {
        List<JoinRequest> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonObject r : Json.objects(o, key)) {
            JoinRequest request = JoinRequest.read(r);
            if (request != null && seen.add(request.from.id)) out.add(request);
        }
        return out;
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
        /** CHAT2's extras by name ({@code hello.chat.extras}; links need no capability). */
        public static final String REPLIES = "replies";
        public static final String STICKERS = "stickers";
        public static final String REACTIONS = "reactions";
        public static final String TYPING = "typing";
        public static final String READS = "reads";
        public static final String LINKS = "links";

        public final boolean on;
        public final boolean mailbox;
        /** Unix seconds when the mailbox opens again; null while not known. */
        @Nullable
        public final Long mailboxBack;
        public final int perDay;
        /** Unix seconds until which the player may not send; null when they may. */
        @Nullable
        public final Long mutedUntil;
        /** CHAT2: the extras Studio has on now; null from a service without them (chat is CHAT1 then). */
        @Nullable
        public final Set<String> extras;
        /** CHAT2: the sticker sets the service takes ({@code ["pl1"]}). */
        public final List<String> stickerSets;
        /** CHAT2: the link rules and Studio's lists while links are on ({@code {rules, trusted, blocked, invites}}), else null. */
        @Nullable
        public final JsonObject links;

        public ChatState(boolean on, boolean mailbox, @Nullable Long mailboxBack, int perDay, @Nullable Long mutedUntil) {
            this(on, mailbox, mailboxBack, perDay, mutedUntil, null, Collections.<String>emptyList(), null);
        }

        public ChatState(boolean on, boolean mailbox, @Nullable Long mailboxBack, int perDay, @Nullable Long mutedUntil,
                         @Nullable Set<String> extras, List<String> stickerSets, @Nullable JsonObject links) {
            this.on = on;
            this.mailbox = mailbox;
            this.mailboxBack = mailboxBack;
            this.perDay = perDay;
            this.mutedUntil = mutedUntil;
            this.extras = extras == null ? null : Collections.unmodifiableSet(new HashSet<>(extras));
            this.stickerSets = Collections.unmodifiableList(new ArrayList<>(stickerSets));
            this.links = links;
        }

        /** Before the first {@code hello}: assumed on (the composer shows; the service answers anyway). */
        public static final ChatState UNKNOWN = new ChatState(true, true, null, 0, null);

        public static ChatState read(@Nullable JsonObject o) {
            if (o == null) return UNKNOWN;
            Set<String> extras = null;
            JsonObject e = Json.obj(o, "extras");
            if (e != null) {
                extras = new HashSet<>();
                for (Map.Entry<String, JsonElement> entry : e.entrySet()) {
                    JsonElement value = entry.getValue();
                    if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean()) {
                        extras.add(entry.getKey());
                    }
                }
            }
            return new ChatState(Json.bool(o, "on", true), Json.bool(o, "mailbox", true), Json.numOrNull(o, "mailboxBack"),
                    (int) Json.num(o, "perDay", 0), Json.numOrNull(o, "mutedUntil"), extras, Json.strings(o, "stickers"),
                    Json.obj(o, "links"));
        }

        public boolean mutedAt(long hubNowSeconds) {
            return mutedUntil != null && hubNowSeconds < mutedUntil;
        }

        /** The mailbox is off (messages reach only those online) at {@code hubNowSeconds}. */
        public boolean liveOnlyAt(long hubNowSeconds) {
            return on && !mailbox && (mailboxBack == null || hubNowSeconds < mailboxBack);
        }

        public ChatState withMutedUntil(@Nullable Long until) {
            return new ChatState(on, mailbox, mailboxBack, perDay, until, extras, stickerSets, links);
        }

        /**
         * CHAT2: whether an extra may be offered now: Studio has it on and (but for links, which are
         * text) the socket's {@code caps} took it. An older service has none.
         */
        public boolean offers(String extra, Set<String> caps) {
            if (!on || extras == null || !extras.contains(extra)) return false;
            return LINKS.equals(extra) || caps.contains(extra);
        }

        /** CHAT2: the service speaks the extras at all (it sends {@code extras}, maybe all off). */
        public boolean hasExtras() {
            return extras != null;
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
        /** TEAM5: the player's own waiting join requests, newest first (empty from services before them). */
        public final List<MyRequest> teamRequests;

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
            this(friends, incoming, outgoing, blocked, limits, team, teamInvited, teamInvites, teamFeatures, teamNotifications,
                    features, crews, crewLimit, Collections.<MyRequest>emptyList());
        }

        public FriendsData(List<Friend> friends, List<Request> incoming, List<Request> outgoing, List<Profile> blocked,
                           Limits limits, @Nullable Team team, Set<String> teamInvited, List<TeamInvite> teamInvites,
                           Set<String> teamFeatures, TeamNotifications teamNotifications, @Nullable Set<String> features,
                           @Nullable List<Crew> crews, int crewLimit, List<MyRequest> teamRequests) {
            this.teamRequests = Collections.unmodifiableList(new ArrayList<>(teamRequests));
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
            List<MyRequest> mine = new ArrayList<>();
            Set<String> asked = new HashSet<>();
            for (JsonObject r : Json.objects(o, "teamRequests")) {
                MyRequest request = MyRequest.read(r);
                if (request != null && asked.add(request.team.id)) mine.add(request);
            }
            return new FriendsData(friends, incoming, outgoing, blocked, Limits.read(Json.obj(o, "limits")),
                    Team.read(Json.obj(o, "team")), invited, teamInvites, new HashSet<>(Json.strings(o, "teamFeatures")),
                    TeamNotifications.read(Json.obj(o, "teamNotifications")), features, crews,
                    (int) Math.max(0, Json.num(o, "crewLimit", 0)), mine);
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
