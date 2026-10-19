package gg.projects.mobile.join;

import gg.projects.mobile.content.PackChoice;

/**
 * What a join of the Projects profile does ("Projects'e katıl", and since M16 OYNA on that profile
 * too), from what is known: whether verified content exists, which pack the profile follows
 * ({@link PackChoice}) and whether a pack is installed in its folder. A player never lands in
 * vanilla by accident: vanilla only when the verified content publishes no pack (the owner's
 * Decision 4), never because the content could not be read. Plain Java, JVM-tested.
 */
public final class JoinRoute {
    private JoinRoute() {}

    public enum Kind {
        /** Install or update the followed pack, then start. */
        PACK,
        /** Start the installed set as it is (the family dropped its version, or no content to sync with). */
        AS_IS,
        /** The content publishes no pack: the profile is vanilla on the newest release. */
        VANILLA,
        /** Nothing installed and no verified content to install from: the join fails as offline. */
        NEEDS_CONTENT,
    }

    /**
     * @param hasContent   a verified index exists (fresh or cached)
     * @param choice       the pack the profile follows by that index
     * @param packInstalled the profile's folder has a pack state (a pack was installed there)
     */
    public static Kind of(boolean hasContent, PackChoice.Kind choice, boolean packInstalled) {
        switch (choice) {
            case NONE:
                if (hasContent) return Kind.VANILLA;
                return packInstalled ? Kind.AS_IS : Kind.NEEDS_CONTENT;
            case DROPPED:
                return Kind.AS_IS;
            default:
                return Kind.PACK;
        }
    }
}
