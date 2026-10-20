package gg.projects.mobile.social;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** M19: the friends service's FCM data messages (soc6.md) and what the app does with each. */
public class PushMessageTest {
    private static final String ME = "100000000000000001";
    private static final String AYSE = "100000000000000002";
    private static final Set<String> ALL = new HashSet<>(Arrays.asList("request", "invite", "call"));
    private static final Set<String> NONE_ENDED = Collections.emptySet();

    private static Map<String, String> data(String... pairs) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
        return map;
    }

    private static Map<String, String> request() {
        return data("v", "1", "type", "request", "sender", AYSE, "name", "Ayşe", "at", "1792521000", "to", ME);
    }

    private static Map<String, String> invite() {
        return data("v", "1", "type", "invite", "invite", "57", "sender", AYSE, "name", "Ayşe", "at", "1792521000",
                "expires", "1792524600", "where", "projects", "host", "projects.gg", "to", ME);
    }

    private static Map<String, String> call() {
        return data("v", "1", "type", "call", "call", "3f9c0a1b2c3d4e5f", "caller", AYSE, "name", "Ayşe", "kind", "ready",
                "at", "1792521000", "until", "1792522200", "to", ME);
    }

    @Test
    public void readsTheFourMessages() {
        PushMessage r = PushMessage.parse(request());
        assertNotNull(r);
        assertEquals("request:" + AYSE, r.tag());
        assertEquals("Ayşe", r.name);
        PushMessage i = PushMessage.parse(invite());
        assertNotNull(i);
        assertEquals("57", i.id);
        assertEquals(1792524600L, i.until);
        assertEquals("projects", i.where);
        assertEquals("invite:" + AYSE, i.tag());
        PushMessage c = PushMessage.parse(call());
        assertNotNull(c);
        assertEquals("call:3f9c0a1b2c3d4e5f", c.tag());
        assertEquals("ready", c.kind);
        PushMessage e = PushMessage.parse(data("v", "1", "type", "call_end", "call", "3f9c0a1b2c3d4e5f", "caller", AYSE,
                "reason", "ended", "to", ME));
        assertNotNull(e);
        assertEquals("call:3f9c0a1b2c3d4e5f", e.tag());
    }

    @Test
    public void otherVersionsTypesAndBrokenMessagesAreIgnored() {
        Map<String, String> v2 = request();
        v2.put("v", "2");
        assertNull(PushMessage.parse(v2));
        Map<String, String> unknown = request();
        unknown.put("type", "chat");
        assertNull(PushMessage.parse(unknown));
        Map<String, String> noTo = request();
        noTo.remove("to");
        assertNull(PushMessage.parse(noTo));
        Map<String, String> badSender = invite();
        badSender.put("sender", "ayse");
        assertNull(PushMessage.parse(badSender));
        Map<String, String> noUntil = call();
        noUntil.remove("until");
        assertNull(PushMessage.parse(noUntil));
        assertNull(PushMessage.parse(null));
    }

    @Test
    public void aMessageForAnotherAccountIsDropped() {
        PushMessage r = PushMessage.parse(request());
        assertEquals(PushMessage.Decision.Action.DROP, r.decide("100000000000000009", 1792521001, NONE_ENDED, ALL, true).action);
        assertEquals("signed out", PushMessage.Decision.Action.DROP, r.decide(null, 1792521001, NONE_ENDED, ALL, true).action);
        assertEquals(PushMessage.Decision.Action.SHOW, r.decide(ME, 1792521001, NONE_ENDED, ALL, true).action);
    }

    @Test
    public void invitesRunOutAtTheirHour() {
        PushMessage i = PushMessage.parse(invite());
        PushMessage.Decision shown = i.decide(ME, 1792521000 + 600, NONE_ENDED, ALL, true);
        assertEquals(PushMessage.Decision.Action.SHOW, shown.action);
        assertEquals((1792524600L - 1792521600L) * 1000L, shown.timeoutMs);
        assertEquals(PushMessage.Decision.Action.DROP, i.decide(ME, 1792524600, NONE_ENDED, ALL, true).action);
        assertEquals("the player switched invites off", PushMessage.Decision.Action.DROP,
                i.decide(ME, 1792521100, NONE_ENDED, new HashSet<>(Collections.singletonList("call")), true).action);
    }

    @Test
    public void callsRespectTheEndTheMuteAndTheirTime() {
        PushMessage c = PushMessage.parse(call());
        PushMessage.Decision shown = c.decide(ME, 1792521100, NONE_ENDED, ALL, true);
        assertEquals(PushMessage.Decision.Action.SHOW, shown.action);
        assertEquals(1100 * 1000L, shown.timeoutMs);
        assertEquals("its end came first (FCM keeps no order)", PushMessage.Decision.Action.DROP,
                c.decide(ME, 1792521100, Collections.singleton("3f9c0a1b2c3d4e5f"), ALL, true).action);
        assertEquals("muted", PushMessage.Decision.Action.DROP, c.decide(ME, 1792521100, NONE_ENDED, ALL, false).action);
        assertEquals("over", PushMessage.Decision.Action.DROP, c.decide(ME, 1792522200, NONE_ENDED, ALL, true).action);
        PushMessage end = PushMessage.parse(data("v", "1", "type", "call_end", "call", "3f9c0a1b2c3d4e5f", "to", ME));
        assertEquals(PushMessage.Decision.Action.CANCEL, end.decide(ME, 1792521100, NONE_ENDED, ALL, true).action);
    }

    @Test
    public void namesArePlainBoundedText() {
        assertEquals("Ayşe", PushMessage.clean(" Ay​şe\n "));
        assertEquals(40, PushMessage.clean(new String(new char[80]).replace('\0', 'a')).length());
        assertEquals("", PushMessage.clean(null));
    }
}
