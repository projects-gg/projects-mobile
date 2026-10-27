package gg.projects.mobile.join;

import androidx.annotation.Nullable;

import gg.projects.mobile.ui.VersionLabel;

/**
 * MOB2: a profile's name as the player reads it, the same in the profile list, the editor and the
 * undo bar: Amethyst's default profile ("(Default)", "Default") in the player's language
 * ("Varsayılan"), as {@code ProfileRows} shows it. The stored name stays what it was while the player
 * leaves it alone: the app recognises the default profile by it ({@code DefaultVersion}). Plain Java,
 * JVM-tested; the localized words come from the caller.
 */
public final class ProfileNames {
    private ProfileNames() {}

    /** Amethyst's default profile's name. */
    public static boolean isDefault(@Nullable String name) {
        return VersionLabel.isDefaultName(name);
    }

    /** No name of its own (upstream historically wrote "New" for one). */
    public static boolean isUnnamed(@Nullable String name) {
        return name == null || name.trim().isEmpty() || "New".equalsIgnoreCase(name.trim());
    }

    /** What the editor's name field shows: the default profile by its localized name, others as stored. */
    public static String editable(@Nullable String stored, String defaultName) {
        if (isDefault(stored)) return defaultName;
        return stored == null ? "" : stored;
    }

    /**
     * What the editor saves from {@code typed}: the localized default name the field was given, left as
     * it was, keeps the stored name; anything else the player typed is the new name.
     */
    public static String saved(@Nullable String stored, String typed, String defaultName) {
        if (isDefault(stored) && typed.trim().equals(defaultName.trim())) return stored;
        return typed;
    }

    /** The name in a sentence ("<name> silindi"): {@code unnamed} ("Profil") when it has none. */
    public static String shown(@Nullable String stored, String defaultName, String unnamed) {
        if (isDefault(stored)) return defaultName;
        if (isUnnamed(stored)) return unnamed;
        return stored.trim();
    }
}
