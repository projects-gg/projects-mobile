package gg.projects.mobile.social;

import androidx.annotation.Nullable;

/**
 * Chat conversation ids (services/social README "Chat"): a crew's chat is {@code c:<team id>}, a
 * direct conversation {@code d:<id>:<id>} with the two user ids in ascending numeric order (a
 * shorter id is the smaller; ids of the same length compare as text). Pure Java.
 */
public final class ChatConv {
    private ChatConv() {}

    public static String crew(String teamId) {
        return "c:" + teamId;
    }

    /** The direct conversation of two players. */
    public static String direct(String a, String b) {
        return smaller(a, b) ? "d:" + a + ":" + b : "d:" + b + ":" + a;
    }

    static boolean smaller(String a, String b) {
        if (a.length() != b.length()) return a.length() < b.length();
        return a.compareTo(b) <= 0;
    }

    public static boolean isCrew(@Nullable String conv) {
        return conv != null && conv.startsWith("c:") && SocialModel.isCrewId(conv.substring(2));
    }

    public static boolean isDirect(@Nullable String conv) {
        if (conv == null || !conv.startsWith("d:")) return false;
        String[] parts = conv.substring(2).split(":", -1);
        return parts.length == 2 && SocialModel.isUserId(parts[0]) && SocialModel.isUserId(parts[1])
                && !parts[0].equals(parts[1]) && smaller(parts[0], parts[1]);
    }

    public static boolean valid(@Nullable String conv) {
        return isCrew(conv) || isDirect(conv);
    }

    /** The crew of {@code c:<team id>}, or null. */
    @Nullable
    public static String crewId(@Nullable String conv) {
        return isCrew(conv) ? conv.substring(2) : null;
    }

    /** The other player of a direct conversation of {@code me}, or null. */
    @Nullable
    public static String other(@Nullable String conv, @Nullable String me) {
        if (!isDirect(conv) || me == null) return null;
        String[] parts = conv.substring(2).split(":");
        if (parts[0].equals(me)) return parts[1];
        if (parts[1].equals(me)) return parts[0];
        return null;
    }
}
