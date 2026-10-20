package gg.projects.mobile.join;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** M18: OYNA plays Projects when the player most likely means it (the 0.1.7 tester's report). */
public class PlayChoiceTest {
    @Test
    public void aNewInstallPlaysProjects() {
        // Upstream's default profile was the only one, so it was the selection: Play started vanilla.
        assertTrue(PlayChoice.selectProjectsAtFirstStart(false, true, "(Default)"));
        assertTrue(PlayChoice.selectProjectsAtFirstStart(false, true, "Default"));
        assertTrue(PlayChoice.selectProjectsAtFirstStart(false, true, "New"));
        assertTrue(PlayChoice.selectProjectsAtFirstStart(false, true, null));
        assertTrue(PlayChoice.selectProjectsAtFirstStart(false, true, " "));
        assertTrue("no profile selected yet", PlayChoice.selectProjectsAtFirstStart(false, false, null));
    }

    @Test
    public void aChoiceOfThePlayersOwnStays() {
        assertFalse(PlayChoice.selectProjectsAtFirstStart(false, true, "Fabric"));
        assertFalse(PlayChoice.selectProjectsAtFirstStart(false, true, "Vanilla 1.8.9"));
        // An install that joined before keeps whatever is selected, the default profile too.
        assertFalse(PlayChoice.selectProjectsAtFirstStart(true, true, "(Default)"));
        assertFalse(PlayChoice.selectProjectsAtFirstStart(true, false, null));
    }

    @Test
    public void aLoaderProfileWithoutModsOffersProjects() {
        // The tester's case: upstream's "Fabric" profile on the pack's version, an empty mods folder.
        assertTrue(PlayChoice.offerProjects("fabric-loader-0.19.5-26.1.2", false, true, false));
        assertTrue(PlayChoice.offerProjects("quilt-loader-0.26.4-1.21.1", false, true, false));
        assertTrue(PlayChoice.offerProjects("1.20.1-forge-47.2.0", false, true, false));
        assertTrue(PlayChoice.offerProjects("neoforge-21.1.77", false, true, false));
    }

    @Test
    public void otherProfilesPlayAtOnce() {
        assertFalse("mods of the player's own", PlayChoice.offerProjects("fabric-loader-0.19.5-26.1.2", true, true, false));
        assertFalse("already answered for this profile", PlayChoice.offerProjects("fabric-loader-0.19.5-26.1.2", false, true, true));
        assertFalse("no Projects pack to offer", PlayChoice.offerProjects("fabric-loader-0.19.5-26.1.2", false, false, false));
        assertFalse("vanilla has no mods to miss", PlayChoice.offerProjects("26.1.2", false, true, false));
        assertFalse(PlayChoice.offerProjects("latest-release", false, true, false));
        assertFalse(PlayChoice.offerProjects("1.20.1-OptiFine_HD_U_I6", false, true, false));
        assertFalse(PlayChoice.offerProjects(null, false, true, false));
    }

    @Test
    public void modFilesAreJars() {
        assertTrue(PlayChoice.isModFile("sodium-fabric-0.8.12+mc26.1.2.jar"));
        assertTrue(PlayChoice.isModFile("WI-Zoom.JAR"));
        assertFalse(PlayChoice.isModFile("sodium-fabric-0.8.12+mc26.1.2.jar.disabled"));
        assertFalse(PlayChoice.isModFile(".index"));
        assertFalse(PlayChoice.isModFile(null));
        assertTrue(PlayChoice.hasModFiles(new String[]{".index", "fabric-api-0.141.3+26.1.2.jar"}));
        assertFalse("only switched-off mods", PlayChoice.hasModFiles(new String[]{"iris.jar.disabled", ".connector"}));
        assertFalse(PlayChoice.hasModFiles(new String[0]));
        assertFalse("no mods folder", PlayChoice.hasModFiles(null));
    }
}
