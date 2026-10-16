package gg.projects.mobile.content;

/**
 * Which pack the Projects profile follows (the mobile counterpart of the desktop's
 * official_pack_ref and followed_pack):
 *
 * <ol>
 * <li>A new install (no pack state yet) takes the pack {@code recommendedPack} names (a pack id),
 * else the default member of the first pack's family, else the first pack.</li>
 * <li>An installed set follows its family at the Minecraft version it was installed with: the
 * member published for that version (Studio may have split the family into new pack ids).</li>
 * <li>When no member has that version but the recorded pack is still published, the pack moved
 * to another Minecraft version in place: an ordinary update.</li>
 * <li>Otherwise the family no longer offers the version: nothing is synced and the installed set
 * launches as it is. The Minecraft version never changes without the player.</li>
 * </ol>
 */
public final class PackChoice {
    public enum Kind {
        /** Nothing is published: the profile stays vanilla. */
        NONE,
        /** First install of {@link #pack}. */
        NEW_INSTALL,
        /** The family member published for the installed set's Minecraft version. */
        MEMBER,
        /** The recorded pack, moved to another Minecraft version in place. */
        IN_PLACE,
        /** The family no longer offers the installed set's version; {@link #recommended} is its default. */
        DROPPED
    }

    public final Kind kind;
    /** The pack to sync (null for NONE and DROPPED). */
    public final RootIndex.PackRef pack;
    /** For DROPPED: the family's default member, when there is one. */
    public final RootIndex.PackRef recommended;
    /** For DROPPED: the Minecraft version the installed set has. */
    public final String installedMinecraft;

    private PackChoice(Kind kind, RootIndex.PackRef pack, RootIndex.PackRef recommended, String installedMinecraft) {
        this.kind = kind;
        this.pack = pack;
        this.recommended = recommended;
        this.installedMinecraft = installedMinecraft;
    }

    public boolean syncs() {
        return pack != null;
    }

    /**
     * @param index           the verified index, or null when no content was ever verified
     * @param recommendedPack the launcher config's {@code recommendedPack}, may be null
     * @param state           the pack state of the profile's game directory, null for a new install
     */
    public static PackChoice choose(RootIndex index, String recommendedPack, PackState state) {
        if (index == null || index.packs == null || index.packs.isEmpty()) return new PackChoice(Kind.NONE, null, null, null);
        if (state == null || state.packId == null || state.packId.isEmpty()) {
            return new PackChoice(Kind.NEW_INSTALL, index.newInstallPack(recommendedPack), null, null);
        }
        RootIndex.PackRef recorded = index.pack(state.packId);
        String family;
        if (recorded != null) family = recorded.familyId();
        else if (state.family != null && !state.family.isEmpty()) family = state.family;
        else family = state.packId;
        String minecraft = state.minecraft == null || state.minecraft.isEmpty() ? null : state.minecraft;
        if (minecraft != null) {
            RootIndex.PackRef member = index.familyMember(family, minecraft);
            if (member != null) return new PackChoice(Kind.MEMBER, member, null, null);
        }
        if (recorded != null) return new PackChoice(Kind.IN_PLACE, recorded, null, null);
        return new PackChoice(Kind.DROPPED, null, index.familyDefault(family), minecraft);
    }
}
