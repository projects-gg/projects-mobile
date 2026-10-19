package gg.projects.mobile.join;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import gg.projects.mobile.content.PackChoice;

/** What a join (and since M16 OYNA on the Projects profile) does; never vanilla by accident. */
public class JoinRouteTest {
    @Test
    public void aPublishedPackIsInstalledEvenOnAProfileThatStartedAsVanilla() {
        // M14's finding: the profile showed "Minecraft 26.3" before its first join and OYNA started it
        // without the pack. Its folder has no pack state, so the pack is a new install.
        assertEquals(JoinRoute.Kind.PACK, JoinRoute.of(true, PackChoice.Kind.NEW_INSTALL, false));
        assertEquals(JoinRoute.Kind.PACK, JoinRoute.of(true, PackChoice.Kind.MEMBER, true));
        assertEquals(JoinRoute.Kind.PACK, JoinRoute.of(true, PackChoice.Kind.IN_PLACE, true));
    }

    @Test
    public void vanillaOnlyWhenTheContentPublishesNoPack() {
        assertEquals(JoinRoute.Kind.VANILLA, JoinRoute.of(true, PackChoice.Kind.NONE, false));
        assertEquals(JoinRoute.Kind.VANILLA, JoinRoute.of(true, PackChoice.Kind.NONE, true));
    }

    @Test
    public void withoutContentNothingNewStartsAndTheInstalledSetStartsAsItIs() {
        // A phone that never verified content (a first start offline): no vanilla, the join says offline.
        assertEquals(JoinRoute.Kind.NEEDS_CONTENT, JoinRoute.of(false, PackChoice.Kind.NONE, false));
        // A pack installed before: it starts as it is (nothing to sync with).
        assertEquals(JoinRoute.Kind.AS_IS, JoinRoute.of(false, PackChoice.Kind.NONE, true));
    }

    @Test
    public void aDroppedVersionStartsAsItIs() {
        assertEquals(JoinRoute.Kind.AS_IS, JoinRoute.of(true, PackChoice.Kind.DROPPED, true));
    }
}
