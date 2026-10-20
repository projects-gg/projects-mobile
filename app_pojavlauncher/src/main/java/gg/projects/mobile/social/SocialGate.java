package gg.projects.mobile.social;

import androidx.annotation.Nullable;

import java.util.Set;

/**
 * What of the friends feature the app shows, from three sources (the desktop's rules,
 * content-format.md "features.friends", "features.teams"; services/social README):
 * <ul>
 * <li>the signed launcher config turns Friends on ({@code features.friends}) and crews within it
 * ({@code features.teams}); a debug build may force both for local tests;</li>
 * <li>the service decides the rest by what it announces: crews need its crew limit (a service
 * without the team routes has none), each crew addition its {@code teamFeatures} entry
 * ({@code look}, {@code calls}, {@code roles}, {@code mute}) and phone notifications {@code push}
 * in {@code features} (a service from before pushes has no such field);</li>
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

    private SocialGate(boolean friends, boolean teams, boolean look, boolean calls, boolean roles, boolean mute, boolean push) {
        this.friends = friends;
        this.teams = teams;
        this.look = look;
        this.calls = calls;
        this.roles = roles;
        this.mute = mute;
        this.push = push;
    }

    public static final SocialGate OFF = new SocialGate(false, false, false, false, false, false, false);

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
        if (!configFriends) return OFF;
        boolean teams = configTeams && serviceTeamLimit > 0;
        boolean push = firebase && features != null && features.contains("push");
        return new SocialGate(true, teams,
                teams && teamFeatures.contains("look"),
                teams && teamFeatures.contains("calls"),
                teams && teamFeatures.contains("roles"),
                // Mute is the player's own setting: it comes with calls, which it silences.
                teams && teamFeatures.contains("mute"),
                push);
    }
}
