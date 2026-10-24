package gg.projects.mobile.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Server addresses as the service takes them (the launcher's server.rs cases, the browser's host parser). */
public class CloudServerTest {
    private static String host(String input) {
        CloudServer.Host h = CloudServer.normalizeHost(input);
        return h.value != null ? h.value : "!" + h.problem;
    }

    @Test
    public void normalizesHosts() {
        assertEquals("play.projects.gg", host(" Play.Projects.GG. "));
        assertEquals("xn--rnek-sunucu-qfb.com", host("Örnek-Sunucu.com"));
        assertEquals("93.184.216.34", host("93.184.216.34"));
        assertEquals("mc_1.example.org", host("mc_1.example.org"));
    }

    @Test
    public void refusesWhatTheServiceRefuses() {
        assertEquals("!EMPTY", host(""));
        assertEquals("!FORMAT", host("play.projects.gg:25565"));
        assertEquals("!FORMAT", host("http://play.projects.gg"));
        assertEquals("!FORMAT", host("[::1]"));
        assertEquals("!DOT", host("sunucum"));
        // The browser's IPv4 parser fills short forms in (the site sends the same).
        assertEquals("1.2.0.3", host("1.2.3"));
        for (String p : new String[]{"localhost", "oyun.local", "x.lan", "127.0.0.1", "10.1.2.3", "192.168.1.20", "172.20.0.5",
                "100.64.0.1", "169.254.1.1", "224.0.0.1", "0.0.0.0"}) {
            assertEquals(p, "!PRIVATE", host(p));
        }
        assertEquals("!PRIVATE", host("0x7f.1"));
        assertEquals("172.32.0.1", host("172.32.0.1"));
        assertEquals("!FORMAT", host("256.1.1.1"));
        assertEquals("!FORMAT", host("a..b.com"));
        assertEquals("!FORMAT", host("-x.com"));
    }

    @Test
    public void ports() {
        assertNull(CloudServer.checkPort(0));
        assertNull(CloudServer.checkPort(25565));
        assertEquals(CloudServer.Problem.PORT, CloudServer.checkPort(22));
        assertEquals(CloudServer.Problem.PORT, CloudServer.checkPort(3306));
        assertEquals(CloudServer.Problem.PORT, CloudServer.checkPort(70000));
    }

    @Test
    public void theSameServer() {
        CloudServer a = new CloudServer("play.projects.gg", 0);
        assertTrue(CloudServer.sameServer(a, new CloudServer("Play.Projects.gg", 25565)));
        assertFalse(CloudServer.sameServer(a, new CloudServer("play.projects.gg", 25566)));
        assertFalse(CloudServer.sameServer(a, new CloudServer("mc.projects.gg", 0)));
        assertEquals("play.projects.gg", a.label());
        assertEquals("mc.x.com:25570", new CloudServer("mc.x.com", 25570).label());
        assertEquals("play.projects.gg:0", a.aadPart());
    }
}
