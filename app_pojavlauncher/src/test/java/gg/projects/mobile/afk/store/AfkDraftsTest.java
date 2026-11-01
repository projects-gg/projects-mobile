package gg.projects.mobile.afk.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import gg.projects.mobile.afk.api.AfkDraft;
import gg.projects.mobile.afk.api.AfkLimits;
import gg.projects.mobile.afk.api.AfkProblem;

/** The add/edit form's rules: names, addresses, local servers, login commands, the account limit. */
public class AfkDraftsTest {
    private static AfkDraft draft(String name, String address, String... commands) {
        AfkDraft d = new AfkDraft();
        d.name = name;
        d.address = address;
        d.loginCommands = Arrays.asList(commands);
        return d;
    }

    private static List<AfkProblem> check(AfkDraft d) {
        return AfkDrafts.check(d, Collections.singletonList("Night_Owl"), true, 0);
    }

    @Test
    public void namesFollowTheDesktopsRule() {
        assertTrue(check(draft(" SleepyFox_42 ", "play.example.com")).isEmpty());
        assertEquals(Collections.singletonList(AfkProblem.NAME_INVALID), check(draft("ab", "play.example.com")));
        assertEquals(Collections.singletonList(AfkProblem.NAME_INVALID), check(draft("Çok_Güzel", "play.example.com")));
        assertEquals(Collections.singletonList(AfkProblem.NAME_INVALID), check(draft("a234567890123456X", "play.example.com")));
        assertEquals(Collections.singletonList(AfkProblem.NAME_TAKEN), check(draft("night_owl", "play.example.com")));
    }

    @Test
    public void addressesAndPorts() {
        assertEquals(Collections.singletonList(AfkProblem.ADDRESS_MISSING), check(draft("Ember_77", "  ")));
        assertEquals(Collections.singletonList(AfkProblem.PORT_INVALID), check(draft("Ember_77", "play.example.com:70000")));
        assertEquals(Collections.singletonList(AfkProblem.PORT_INVALID), check(draft("Ember_77", "play.example.com:")));
        assertEquals(Collections.singletonList(AfkProblem.ADDRESS_INVALID), check(draft("Ember_77", "play example.com")));
        assertEquals(Collections.singletonList(AfkProblem.ADDRESS_INVALID), check(draft("Ember_77", "-bad-.com")));
        AfkDrafts.Clean c = AfkDrafts.normalize(draft("Ember_77", "Play.Example.COM.:25570"));
        assertEquals("play.example.com", c.host);
        assertEquals(25570, c.port);
        AfkDrafts.Clean idn = AfkDrafts.normalize(draft("Ember_77", "oyun.örnek.com"));
        assertEquals("oyun.xn--rnek-4qa.com", idn.host);
        assertEquals(0, idn.port);
        AfkDrafts.Clean v6 = AfkDrafts.normalize(draft("Ember_77", "[2001:DB8::1]:25566"));
        assertEquals("2001:db8::1", v6.host);
        assertEquals(25566, v6.port);
        assertTrue(check(draft("Ember_77", "2001:db8::1")).isEmpty());
    }

    @Test
    public void localServersAreNotSupportedYet() {
        for (String local : new String[]{"192.168.1.20", "10.0.0.5:25565", "127.0.0.1", "localhost", "[::1]", "fd00::5",
                "172.20.1.1", "mc.local", "169.254.3.4", "[::ffff:192.168.1.2]"}) {
            assertEquals(local, Collections.singletonList(AfkProblem.LOCAL_ADDRESS), check(draft("Ember_77", local)));
        }
        assertFalse(AfkDrafts.isLocal("172.32.0.1"));
        assertFalse(AfkDrafts.isLocal("play.projects.gg"));
        assertFalse(AfkDrafts.isLocal("8.8.8.8"));
    }

    @Test
    public void loginCommands() {
        AfkDraft d = draft("Ember_77", "play.example.com", " /login abc ", "", "  ", "/is\u0007 home");
        assertTrue(check(d).isEmpty());
        assertEquals(Arrays.asList("/login abc", "/is home"), AfkDrafts.normalize(d).loginCommands);
        String[] six = {"/a", "/b", "/c", "/d", "/e", "/f"};
        assertEquals(Collections.singletonList(AfkProblem.TOO_MANY_COMMANDS), check(draft("Ember_77", "a.com", six)));
        StringBuilder longer = new StringBuilder("/login ");
        while (longer.length() <= AfkLimits.MAX_LINE_CHARS) longer.append('x');
        assertEquals(Collections.singletonList(AfkProblem.COMMAND_TOO_LONG),
                check(draft("Ember_77", "a.com", longer.toString())));
    }

    @Test
    public void addingStopsAtTwentyAccounts() {
        AfkDraft d = draft("Ember_77", "play.example.com");
        assertEquals(Collections.singletonList(AfkProblem.TOO_MANY_ACCOUNTS),
                AfkDrafts.check(d, Collections.<String>emptyList(), true, AfkLimits.MAX_ACCOUNTS));
        assertTrue(AfkDrafts.check(d, Collections.<String>emptyList(), false, AfkLimits.MAX_ACCOUNTS).isEmpty());
    }
}
