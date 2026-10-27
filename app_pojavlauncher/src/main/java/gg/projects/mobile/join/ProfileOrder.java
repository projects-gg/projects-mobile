package gg.projects.mobile.join;

import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MOB2: the order of the profile list, and so the profile chosen when the chosen one is deleted (the
 * first, never a HashMap's first entry, which was practically random): the Projects profile, then
 * Amethyst's default profile, then the player's own by name (as the language sorts it, "Çiftlik" after
 * "Cadı"), unnamed ones last; equal names by key, so the order never changes on its own. Plain Java,
 * JVM-tested.
 */
public final class ProfileOrder {
    private ProfileOrder() {}

    /** The keys of {@code profiles} in the list's order. */
    public static List<String> keys(Map<String, MinecraftProfile> profiles, Locale locale) {
        List<String> keys = new ArrayList<>(profiles.keySet());
        final Collator collator = Collator.getInstance(locale);
        collator.setStrength(Collator.SECONDARY);
        final Map<String, MinecraftProfile> all = profiles;
        Collections.sort(keys, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                MinecraftProfile pa = all.get(a);
                MinecraftProfile pb = all.get(b);
                String na = pa == null ? null : pa.name;
                String nb = pb == null ? null : pb.name;
                int byRank = Integer.compare(rank(a, na), rank(b, nb));
                if (byRank != 0) return byRank;
                if (rank(a, na) == RANK_OWN) {
                    int byName = collator.compare(na.trim(), nb.trim());
                    if (byName != 0) return byName;
                }
                return a.compareTo(b);
            }
        });
        return keys;
    }

    /** The first profile of the list, or null when there is none. */
    @Nullable
    public static String first(Map<String, MinecraftProfile> profiles, Locale locale) {
        if (profiles.isEmpty()) return null;
        return keys(profiles, locale).get(0);
    }

    private static final int RANK_PROJECTS = 0;
    private static final int RANK_DEFAULT = 1;
    private static final int RANK_OWN = 2;
    private static final int RANK_UNNAMED = 3;

    private static int rank(String key, @Nullable String name) {
        if (ProjectsProfile.KEY.equals(key)) return RANK_PROJECTS;
        if (ProfileNames.isDefault(name)) return RANK_DEFAULT;
        if (ProfileNames.isUnnamed(name)) return RANK_UNNAMED;
        return RANK_OWN;
    }
}
