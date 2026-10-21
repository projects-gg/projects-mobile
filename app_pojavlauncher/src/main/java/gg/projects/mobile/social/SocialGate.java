package gg.projects.mobile.social;

import androidx.annotation.Nullable;

import java.util.Set;

/**
 * What of the friends feature the app shows, from three sources (the desktop's rules,
 * content-format.md "features.friends", "features.teams", "features.chat"; services/social README):
 * <ul>
 * <li>the signed launcher config turns Friends on ({@code features.friends}), crews within it
 * ({@code features.teams}) and chat ({@code features.chat}); a debug build may force them for local
 * tests;</li>
 * <li>the service decides the rest by what it announces: crews need its crew limit (a service
 * without the team routes has none), each crew addition its {@code teamFeatures} entry
 * ({@code look}, {@code calls}, {@code roles}, {@code mute}, {@code multi}, {@code callServer} and
 * TEAM5's {@code requests}, {@code link}, {@code plans}),
 * chat and phone notifications their {@code features} entries ({@code chat}, {@code push}; a
 * service from before pushes has no such field), reports and moderators' notes a {@code me} that
 * carries {@code notices} (MOD1);</li>
 * <li>phone notifications also need Firebase in this build (the config file was there when it
 * was built).</li>
 * </ul>
 * Older services refuse fields, routes and socket messages they do not know, so nothing is sent
 * that the service did not announce.
 */
public final class SocialGate {
    public final boolean friends;
    public final boolean teams;
    public final boolean look;
    public final boolean calls;
    public final boolean roles;
    public final boolean mute;
    public final boolean push;
    /** TEAM4: several crews per player ({@code crews}, {@code multi: true}, the order, a mute per crew). */
    public final boolean multi;
    /** CALL1: calls start only from a multiplayer server and carry it. */
    public final boolean callServer;
    /** CHAT1: crew chat and direct messages. */
    public final boolean chat;
    /** MOD1: "Rapor et" and moderators' notes. */
    public final boolean reports;
    /** TEAM5: the join setting, join requests and {@code permissions.admit}. */
    public final boolean requests;
    /** TEAM5: crew invite links and their page. */
    public final boolean link;
    /** TEAM5: crew plans (on the presence socket). */
    public final boolean plans;

    private SocialGate(boolean friends, boolean teams, boolean look, boolean calls, boolean roles, boolean mute, boolean push,
                       boolean multi, boolean callServer, boolean chat, boolean reports, boolean requests, boolean link,
                       boolean plans) {
        this.friends = friends;
        this.teams = teams;
        this.look = look;
        this.calls = calls;
        this.roles = roles;
        this.mute = mute;
        this.push = push;
        this.multi = multi;
        this.callServer = callServer;
        this.chat = chat;
        this.reports = reports;
        this.requests = requests;
        this.link = link;
        this.plans = plans;
    }

    public static final SocialGate OFF = new SocialGate(false, false, false, false, false, false, false, false, false, false, false,
            false, false, false);

    /**
     * @param configFriends {@code features.friends} of the signed config (or a debug override)
     * @param configTeams {@code features.teams} of the signed config (or a debug override)
     * @param serviceTeamLimit {@code limits.teamMembers} of the last friend list (0: none, or not read yet)
     * @param teamFeatures {@code teamFeatures} of the last friend list (empty: none)
     * @param features {@code features} of the last friend list (null: none)
     * @param firebase this build carries the Firebase config
     */
    public static SocialGate of(boolean configFriends, boolean configTeams, int serviceTeamLimit,
                                Set<String> teamFeatures, @Nullable Set<String> features, boolean firebase) {
        return of(configFriends, configTeams, false, serviceTeamLimit, teamFeatures, features, firebase, false);
    }

    /**
     * @param configChat {@code features.chat} of the signed config (or a debug override)
     * @param moderation the service's {@code me} carries {@code notices} (reports and moderation, MOD1)
     */
    public static SocialGate of(boolean configFriends, boolean configTeams, boolean configChat, int serviceTeamLimit,
                                Set<String> teamFeatures, @Nullable Set<String> features, boolean firebase, boolean moderation) {
        if (!configFriends) return OFF;
        boolean teams = configTeams && serviceTeamLimit > 0;
        boolean push = firebase && features != null && features.contains("push");
        boolean chat = configChat && features != null && features.contains("chat");
        return new SocialGate(true, teams,
                teams && teamFeatures.contains("look"),
                teams && teamFeatures.contains("calls"),
                teams && teamFeatures.contains("roles"),
                // Mute is the player's own setting: it comes with calls, which it silences.
                teams && teamFeatures.contains("mute"),
                push,
                teams && teamFeatures.contains("multi"),
                teams && teamFeatures.contains("callServer"),
                chat,
                moderation,
                teams && teamFeatures.contains("requests"),
                teams && teamFeatures.contains("link"),
                teams && teamFeatures.contains("plans"));
    }
}
