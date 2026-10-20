package gg.projects.mobile.content;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

/** M19: {@code features.friends} and {@code features.teams} of the signed config (content-format.md). */
public class FriendsFeatureTest {
    private static LauncherConfig parse(String json) throws ContentException {
        return LauncherConfig.parse(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void offUnlessTheConfigSaysTrue() throws ContentException {
        LauncherConfig none = parse("{\"schema\":1}");
        assertFalse(none.friendsFeature);
        assertFalse(none.teamsFeature);
        LauncherConfig both = parse("{\"schema\":1,\"features\":{\"friends\":true,\"teams\":true}}");
        assertTrue(both.friendsFeature);
        assertTrue(both.teamsFeature);
    }

    @Test
    public void crewsOnlyWithinFriends() throws ContentException {
        LauncherConfig teamsAlone = parse("{\"schema\":1,\"features\":{\"teams\":true}}");
        assertFalse(teamsAlone.friendsFeature);
        assertFalse(teamsAlone.teamsFeature);
    }

    @Test
    public void aBrokenValueIsOffAndTheRestOfTheConfigReads() throws ContentException {
        LauncherConfig broken = parse("{\"schema\":1,\"features\":{\"friends\":\"yes\",\"teams\":1},\"recommendedPack\":\"projects\"}");
        assertFalse(broken.friendsFeature);
        assertFalse(broken.teamsFeature);
        assertTrue("projects".equals(broken.recommendedPack));
        LauncherConfig notAnObject = parse("{\"schema\":1,\"features\":[true]}");
        assertFalse(notAnObject.friendsFeature);
    }
}
