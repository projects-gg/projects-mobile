package gg.projects.mobile.launch;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;

import org.junit.Test;

import java.util.Arrays;

public class QuickPlayTest {
    private static final String[] BASE = {"--username", "Steve", "--version", "1.21.1"};

    @Test
    public void addresses() {
        assertTrue(QuickPlay.isValidAddress("play.projects.gg"));
        assertTrue(QuickPlay.isValidAddress("play.projects.gg:25565"));
        assertTrue(QuickPlay.isValidAddress("127.0.0.1:1"));
        assertTrue(QuickPlay.isValidAddress("localhost"));
        assertFalse(QuickPlay.isValidAddress(""));
        assertFalse(QuickPlay.isValidAddress(null));
        assertFalse(QuickPlay.isValidAddress("play.projects.gg:0"));
        assertFalse(QuickPlay.isValidAddress("play.projects.gg:65536"));
        assertFalse(QuickPlay.isValidAddress("play.projects.gg --demo"));
        assertFalse(QuickPlay.isValidAddress("-play.projects.gg"));
        assertFalse(QuickPlay.isValidAddress("play..projects.gg"));
    }

    @Test
    public void quickPlayForModernVersions() {
        String[] args = QuickPlay.withServer(BASE, QuickPlay.PROJECTS_SERVER, true);
        assertEquals(Arrays.asList("--username", "Steve", "--version", "1.21.1", "--quickPlayMultiplayer", "play.projects.gg"),
                Arrays.asList(args));
    }

    @Test
    public void serverAndPortForOldVersions() {
        assertEquals(Arrays.asList("--username", "Steve", "--version", "1.21.1", "--server", "play.projects.gg", "--port", "25565"),
                Arrays.asList(QuickPlay.withServer(BASE, QuickPlay.PROJECTS_SERVER, false)));
        assertEquals(Arrays.asList("--server", "example.org", "--port", "25570"),
                Arrays.asList(QuickPlay.withServer(new String[0], "example.org:25570", false)));
    }

    @Test
    public void existingJoinArgumentsAndBadAddressesAreLeftAlone() {
        String[] joined = {"--quickPlayMultiplayer", "other.example:25565"};
        assertSame(joined, QuickPlay.withServer(joined, QuickPlay.PROJECTS_SERVER, true));
        String[] singleplayer = {"--quickPlaySingleplayer", "World"};
        assertSame(singleplayer, QuickPlay.withServer(singleplayer, QuickPlay.PROJECTS_SERVER, true));
        assertArrayEquals(BASE, QuickPlay.withServer(BASE, "bad address", true));
    }

    @Test
    public void detectsQuickPlayInVersionArguments() {
        // Shape of the 1.20+ version JSON "arguments.game" as upstream parses it (Gson, Object[]).
        String json = "[\"--username\", \"${auth_player_name}\", {\"rules\": [{\"action\": \"allow\", \"features\": "
                + "{\"is_quick_play_multiplayer\": true}}], \"value\": [\"--quickPlayMultiplayer\", \"${quickPlayMultiplayer}\"]}]";
        Object[] modern = new Gson().fromJson(json, Object[].class);
        assertTrue(QuickPlay.supportsQuickPlay(modern));
        Object[] legacy = new Gson().fromJson("[\"--username\", \"${auth_player_name}\", \"--version\", \"${version_name}\"]", Object[].class);
        assertFalse(QuickPlay.supportsQuickPlay(legacy));
        assertFalse(QuickPlay.supportsQuickPlay(null));
    }
}
