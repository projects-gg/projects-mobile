package gg.projects.mobile.content;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M053: the Minecraft versions the mods screen offers, the desktop's {@code instance_versions} for the official pack:
 * the family's published members (snapshots only when recommended or current), the current version and every parked
 * set, newest first. "Önerilen" goes to the config's {@code recommendedMinecraftVersion} when the family publishes it,
 * else to the family's default member. A version can be picked when it is published and not the current one.
 */
public final class PackVersions {
    private PackVersions() {}

    public static final class Option {
        public final String minecraft;
        /** The family member published for it, or null. */
        @Nullable public final String packId;
        public final boolean current;
        public final boolean recommended;
        /** Published and not current: the player can switch to it. */
        public final boolean available;
        /** A parked set waits for it (its mods and settings come back). */
        public final boolean hasSet;

        Option(String minecraft, @Nullable String packId, boolean current, boolean recommended, boolean available, boolean hasSet) {
            this.minecraft = minecraft;
            this.packId = packId;
            this.current = current;
            this.recommended = recommended;
            this.available = available;
            this.hasSet = hasSet;
        }
    }

    /** The family the installed state follows, or null without one. */
    @Nullable
    public static String family(RootIndex index, @Nullable PackState state) {
        if (state == null) return null;
        RootIndex.PackRef recorded = state.packId == null || state.packId.isEmpty() ? null : index.pack(state.packId);
        if (recorded != null) return recorded.familyId();
        if (state.family != null && !state.family.isEmpty()) return state.family;
        return state.packId == null || state.packId.isEmpty() ? null : state.packId;
    }

    /** The installed set's version, or null. */
    @Nullable
    public static String current(RootIndex index, @Nullable PackState state) {
        if (state == null) return null;
        if (state.minecraft != null && !state.minecraft.isEmpty()) return state.minecraft;
        RootIndex.PackRef recorded = state.packId == null || state.packId.isEmpty() ? null : index.pack(state.packId);
        return recorded == null ? null : recorded.minecraft();
    }

    public static List<Option> options(@Nullable RootIndex index, @Nullable PackState state, @Nullable String recommendedMinecraft,
                                       List<VersionSets.Parked> parked) {
        List<Option> out = new ArrayList<>();
        if (index == null || state == null) return out;
        String family = family(index, state);
        String current = current(index, state);
        if (family == null || current == null) return out;
        List<RootIndex.PackRef> members = index.familyMembers(family);
        String recommended = null;
        if (recommendedMinecraft != null && index.familyMember(family, recommendedMinecraft) != null) {
            recommended = recommendedMinecraft;
        } else {
            RootIndex.PackRef fallback = index.familyDefault(family);
            if (fallback != null) recommended = fallback.minecraft();
        }
        Map<String, String> published = new LinkedHashMap<>();
        for (RootIndex.PackRef member : members) {
            String minecraft = member.minecraft();
            if (minecraft == null || minecraft.isEmpty() || published.containsKey(minecraft)) continue;
            if (!isRelease(minecraft) && !minecraft.equals(recommended) && !minecraft.equals(current)) continue;
            published.put(minecraft, member.id);
        }
        Map<String, Boolean> versions = new LinkedHashMap<>();
        for (String minecraft : published.keySet()) versions.put(minecraft, true);
        versions.put(current, true);
        for (VersionSets.Parked set : parked) versions.put(set.minecraft, true);
        List<String> ordered = new ArrayList<>(versions.keySet());
        Collections.sort(ordered, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return MinecraftVersions.compare(b, a);
            }
        });
        for (String minecraft : ordered) {
            boolean isCurrent = minecraft.equals(current);
            boolean hasSet = false;
            for (VersionSets.Parked set : parked) hasSet |= set.minecraft.equals(minecraft);
            String packId = published.get(minecraft);
            out.add(new Option(minecraft, packId, isCurrent, minecraft.equals(recommended), !isCurrent && packId != null,
                    hasSet && !isCurrent));
        }
        return out;
    }

    /** A release ("1.21.1", "26.1"), not a snapshot or a pre-release. */
    static boolean isRelease(String minecraft) {
        return minecraft.matches("\\d+\\.\\d+(\\.\\d+)?");
    }
}
