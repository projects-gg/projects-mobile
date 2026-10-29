package gg.projects.mobile.social;

import androidx.annotation.Nullable;

import java.util.regex.Pattern;

/**
 * SOC9 (soc9.md §1): the Minecraft version a friend plays, as the friends service carries it ({@code ver} in the game's
 * {@code status}, {@code game.ver} in a presence): the vanilla version id ("1.21.4", "26.2"), never a loader's name, at
 * most 24 characters of {@code [0-9A-Za-z._+-]}. Friends see it as it is, after the place ("Projects'te 1.21.4"). Pure,
 * with tests.
 */
public final class GameVersion {
    private GameVersion() {}

    private static final Pattern WIRE = Pattern.compile("[0-9A-Za-z._+-]{1,24}");

    /** {@code id} when the service takes it as a version, else null (nothing is sent or shown). */
    @Nullable
    public static String wire(@Nullable String id) {
        return id != null && WIRE.matcher(id).matches() ? id : null;
    }

    /**
     * The vanilla version a launched version runs: a loader version's {@code inheritsFrom} ("fabric-loader-0.16.10-1.21.4"
     * → "1.21.4"), else the version itself; null when that is not a version the service takes.
     */
    @Nullable
    public static String vanilla(@Nullable String versionId, @Nullable String inheritsFrom) {
        if (inheritsFrom != null && !inheritsFrom.isEmpty()) return wire(inheritsFrom);
        return wire(versionId);
    }
}
