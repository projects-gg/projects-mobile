package gg.projects.mobile.social;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * FIX2: what the running game is doing, read from its log in the game's process (the desktop's
 * {@code pl_social::game} and its tests): the server it joins, a singleplayer world, the menus; only
 * public servers are named; the launch's own join names the first connection; chat never reads as one.
 */
public class GameActivityTest {
    private static GameActivity.Status playing(String host) {
        return GameActivity.Status.server(host);
    }

    @Test
    public void readsTheLinesThatMatter() {
        GameActivity.Signal s = GameActivity.signal("Connecting to play.projects.gg, 25565");
        assertEquals("play.projects.gg", s.host);
        assertEquals(25565, s.port);
        assertEquals("mc.hypixel.net", GameActivity.signal("  Connecting to mc.hypixel.net, 25565 ").host);
        assertEquals(GameActivity.Signal.Kind.SINGLEPLAYER, GameActivity.signal("Starting integrated minecraft server version 1.21.1").kind);
        assertEquals(GameActivity.Signal.Kind.MENU, GameActivity.signal("Stopping server").kind);
        assertEquals(GameActivity.Signal.Kind.MENU, GameActivity.signal("Couldn't connect to server").kind);
        for (String other : new String[]{"Connecting to", "Connecting to play.projects.gg", "Connecting to play.projects.gg, port",
                "Connecting to , 25565", "Stopping server now", "Loaded 7 advancements",
                "[CHAT] Connecting to play.projects.gg, 25565 is fun"}) {
            assertNull(other, GameActivity.signal(other));
        }
        assertTrue(GameActivity.maySignal("Connecting to a, 1"));
        assertFalse(GameActivity.maySignal("Setting user: Ayse"));
    }

    @Test
    public void takesTheMessageOutOfTheLogsLines() {
        assertEquals("Connecting to play.projects.gg, 25565",
                GameActivity.messageOf("[21:30:01] [Render thread/INFO]: Connecting to play.projects.gg, 25565"));
        // Fabric's pattern names the logger in parentheses, Forge's in brackets.
        assertEquals("Connecting to mc.example.net, 25565",
                GameActivity.messageOf("[21:30:01] [Render thread/INFO] (Minecraft) Connecting to mc.example.net, 25565"));
        assertEquals("Connecting to mc.example.net, 25565",
                GameActivity.messageOf("[21:30:01] [Render thread/INFO] [minecraft/ConnectScreen]: Connecting to mc.example.net, 25565"));
        // Chat keeps its own marks: a message that only looks like the signal never is one.
        GameActivity game = GameActivity.started(null);
        assertNull(game.observe("[21:30:02] [Render thread/INFO]: [System] [CHAT] Connecting to mc.evil.net, 25565"));
        assertNull(game.observe("[21:30:02] [Render thread/INFO] (Minecraft) [CHAT] <Ayse> Connecting to mc.evil.net, 25565"));
        assertEquals(GameActivity.Status.PLAYING, game.status());
    }

    @Test
    public void labelsOnlyPublicServers() {
        assertEquals("play.projects.gg", GameActivity.serverLabel("Play.Projects.GG.", 25565));
        assertEquals("mc.example.net:25570", GameActivity.serverLabel("mc.example.net", 25570));
        assertEquals("203.0.113.7", GameActivity.serverLabel("203.0.113.7", 25565));
        for (String local : new String[]{"localhost", "127.0.0.1", "192.168.1.20", "10.0.0.5", "172.20.1.1", "169.254.3.3",
                "0.0.0.0", "[::1]", "fe80::1", "2001:db8::1", "bad_host!", "-bad.example", ""}) {
            assertNull(local, GameActivity.serverLabel(local, 25565));
        }
        assertNull(GameActivity.serverLabel("mc.example.net", 0));
        // 172.32 is not private (only 172.16 to 172.31 are).
        assertEquals("172.32.0.1", GameActivity.serverLabel("172.32.0.1", 25565));
    }

    @Test
    public void followsASession() {
        GameActivity game = GameActivity.started(null);
        assertEquals(GameActivity.Status.PLAYING, game.status());
        assertNull(game.observe("Loaded 7 advancements"));
        assertEquals(GameActivity.Status.SINGLEPLAYER, game.observe("Starting integrated minecraft server version 1.21.1"));
        // The same world again is not a change.
        assertNull(game.observe("Starting integrated minecraft server version 1.21.1"));
        assertEquals(GameActivity.Status.PLAYING, game.observe("Stopping server"));
        assertEquals(playing("mc.example.net"), game.observe("Connecting to mc.example.net, 25565"));
        assertNull(game.observe("Connecting to mc.example.net, 25565"));
        assertEquals(GameActivity.Status.PLAYING, game.observe("Connecting to 192.168.1.4, 51234"));
        assertNull("already in the menus", game.observe("Couldn't connect to server"));
    }

    @Test
    public void theLaunchsOwnJoinNamesTheFirstConnection() {
        // Older versions log the SRV target; the launch knows the address the player knows.
        GameActivity game = GameActivity.started("play.projects.gg");
        assertEquals(playing("play.projects.gg"), game.observe("Connecting to node7.hosting.example, 25577"));
        assertEquals(playing("mc.example.net"), game.observe("Connecting to mc.example.net, 25565"));
        // A singleplayer world first: the launch's join is forgotten.
        GameActivity world = GameActivity.started("play.projects.gg");
        world.observe("Starting integrated minecraft server version 1.21.1");
        assertEquals(playing("mc.example.net"), world.observe("Connecting to mc.example.net, 25565"));
    }

    @Test
    public void theLogComesInPiecesAndLinesComeOutWhole() {
        final List<String> lines = new ArrayList<>();
        GameActivity.Lines splitter = new GameActivity.Lines();
        splitter.feed("[21:30:01] [Render thread/INFO]: Connec", lines::add);
        assertTrue(lines.isEmpty());
        splitter.feed("ting to play.projects.gg, 25565\n[21:30:02] [Render thread/INFO]: Loaded\n[21:3", lines::add);
        assertEquals(2, lines.size());
        assertEquals("[21:30:01] [Render thread/INFO]: Connecting to play.projects.gg, 25565", lines.get(0));
        splitter.feed("0:03] x\n", lines::add);
        assertEquals("[21:30:03] x", lines.get(2));
        // A line without an end never grows beyond a few kilobytes.
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 10_000; i++) huge.append('x');
        splitter.feed(huge.toString(), lines::add);
        splitter.feed("\n", lines::add);
        assertTrue(lines.get(3).length() <= 4096);
        splitter.feed(null, lines::add);
        assertEquals(4, lines.size());
    }
}
