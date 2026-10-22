package gg.projects.mobile.social;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;

/**
 * FIX2 (item 8): what the game's own socket announces while the game runs (the pushes it stands in for),
 * and when "Bağlan" would only join the server the game is already on.
 */
public class InGameRulesTest {
    private static SocialModel.Call call(String where, String host) {
        return new SocialModel.Call("3f9c0a1b2c3d4e5f", "111111111111111111", "join", 0, 100,
                new HashMap<String, String>(), Collections.<String>emptyList(), where, host);
    }

    @Test
    public void requestsAndInvitesFollowTheSwitchesAndDoNotDisturb() {
        assertTrue(InGameRules.announceRequest(false, true));
        assertFalse("do not disturb", InGameRules.announceRequest(true, true));
        assertFalse("switched off", InGameRules.announceRequest(false, false));
        assertTrue(InGameRules.announceInvite(false, true, false));
        assertFalse("expired", InGameRules.announceInvite(false, true, true));
        assertFalse("do not disturb", InGameRules.announceInvite(true, true, false));
        assertFalse("switched off", InGameRules.announceInvite(false, false, false));
    }

    @Test
    public void aCallIsAnnouncedOnceAndAgainWhenItMoves() {
        String projects = InGameRules.callWhere(call("projects", "play.projects.gg"));
        String other = InGameRules.callWhere(call("other", "MC.example.net"));
        assertEquals("other|mc.example.net", other);
        assertTrue(InGameRules.announceCall(null, projects, false, false, false, true, false, 60));
        assertFalse("seen", InGameRules.announceCall(projects, projects, false, false, false, true, false, 60));
        assertTrue("moved", InGameRules.announceCall(projects, other, false, false, false, true, false, 60));
        assertFalse("quiet", InGameRules.announceCall(null, projects, true, false, false, true, false, 60));
        assertFalse("own", InGameRules.announceCall(null, projects, false, true, false, true, false, 60));
        assertFalse("do not disturb", InGameRules.announceCall(null, projects, false, false, true, true, false, 60));
        assertFalse("switched off", InGameRules.announceCall(null, projects, false, false, false, false, false, 60));
        assertFalse("ended", InGameRules.announceCall(null, projects, false, false, false, true, true, 60));
        assertFalse("over", InGameRules.announceCall(null, projects, false, false, false, true, false, 0));
    }

    @Test
    public void knowsWhenTheGameIsAlreadyThere() {
        assertTrue(InGameRules.isOn(GameActivity.Status.server("play.projects.gg"), true, null));
        assertTrue(InGameRules.isOn(GameActivity.Status.server("projects.gg"), true, null));
        assertFalse(InGameRules.isOn(GameActivity.Status.server("projects.gg.evil.net"), true, null));
        assertFalse(InGameRules.isOn(GameActivity.Status.server("mc.example.net"), true, null));
        assertTrue(InGameRules.isOn(GameActivity.Status.server("mc.example.net"), false, "MC.example.net:25565"));
        assertTrue(InGameRules.isOn(GameActivity.Status.server("mc.example.net:25570"), false, "mc.example.net:25570"));
        assertFalse(InGameRules.isOn(GameActivity.Status.server("mc.example.net:25570"), false, "mc.example.net"));
        assertFalse(InGameRules.isOn(GameActivity.Status.PLAYING, true, null));
        assertFalse(InGameRules.isOn(GameActivity.Status.SINGLEPLAYER, false, "mc.example.net"));
        assertFalse(InGameRules.isOn(null, true, null));
    }

    @Test
    public void theStartsOwnJoinIsNamedOnlyWhenPublic() {
        assertEquals("play.projects.gg", GameActivity.publicLabel("play.projects.gg"));
        assertEquals("mc.example.net:25570", GameActivity.publicLabel("mc.example.net:25570"));
        assertEquals("mc.example.net", GameActivity.publicLabel("MC.example.net:25565"));
        assertEquals("10.0.2.2.nip.io", GameActivity.publicLabel("10.0.2.2.nip.io"));
        for (String local : new String[]{"10.0.2.2:25565", "192.168.1.4", "localhost", "[::1]:25565", "mc.example.net:port", ""}) {
            assertNull(local, GameActivity.publicLabel(local));
        }
        assertNull(GameActivity.publicLabel(null));
        // A start that joins a server on the local network says only "Oyunda".
        GameActivity lan = GameActivity.started("10.0.2.2:25565");
        assertNull(lan.observe("[21:30:01] [Render thread/INFO]: Connecting to 10.0.2.2, 25565"));
        assertEquals(GameActivity.Status.PLAYING, lan.status());
        assertTrue(GameActivity.mayMatter("[21:30:01] [Render thread/INFO]: Connecting to a, 1"));
        assertTrue(GameActivity.mayMatter("[21:30:01] [Server thread/INFO]: Stopping server"));
        assertFalse(GameActivity.mayMatter("[21:30:01] [Render thread/INFO]: Loaded 7 advancements"));
    }
}
