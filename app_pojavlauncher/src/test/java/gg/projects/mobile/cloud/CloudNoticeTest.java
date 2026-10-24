package gg.projects.mobile.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** AFK notices from the friends service's socket and from pushes (the README's examples), and what the phone does with them. */
public class CloudNoticeTest {
    private static final String SOCKET = "{\"t\":\"afk_notice\",\"notice\":{\"id\":\"01JBXK4V7Q2M9R3T5W8Y0ZA1BC\",\"event\":\"approval\","
            + "\"kind\":\"afk_deal\",\"priority\":\"high\",\"at\":1792521000,\"tag\":\"approval:01JBXK3D2E\",\"data\":{\"acct\":\"a_7f3k2m\","
            + "\"name\":\"Ahmet_AFK\",\"approval\":\"01JBXK3D2E\",\"side\":\"buy\",\"item\":\"minecraft:elytra\",\"qty\":\"1\",\"price\":\"37500\","
            + "\"currency\":\"smp\",\"expires\":\"1792521600\"}}}";

    private static Map<String, String> push(String... pairs) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) m.put(pairs[i], pairs[i + 1]);
        return m;
    }

    @Test
    public void theSocketsApprovalNotice() {
        CloudNotice n = CloudNotice.fromSocket(Vectors.json(SOCKET));
        assertNotNull(n);
        assertEquals("approval", n.event);
        assertEquals(CloudNotice.KIND_DEAL, n.kind);
        assertTrue(n.high);
        assertEquals("01JBXK3D2E", n.approvalId());
        assertEquals("Ahmet_AFK", n.name());
        assertEquals("a_7f3k2m", n.account());
        assertEquals(37500, n.number("price", 0));
        assertEquals(CloudNotice.Action.SHOW, n.decide("123", 1792521100).action);
        assertEquals(CloudNotice.Action.DROP, n.decide("123", 1792521600).action);
    }

    @Test
    public void aPushForAnotherAccountOrOfAnotherShapeGoes() {
        Map<String, String> data = push("v", "1", "type", "afk", "event", "approval", "kind", "afk_deal", "id", "01J", "at", "1792521000",
                "tag", "approval:01JBXK3D2E", "to", "111", "acct", "a_1", "name", "Ahmet_AFK", "approval", "01JBXK3D2E", "expires", "1792521600");
        CloudNotice n = CloudNotice.fromPush(data);
        assertNotNull(n);
        assertEquals("111", n.to);
        assertFalse(n.data.containsKey("to"));
        assertFalse(n.data.containsKey("type"));
        assertEquals(CloudNotice.Action.DROP, n.decide("222", 1792521100).action);
        assertEquals(CloudNotice.Action.DROP, n.decide(null, 1792521100).action);
        assertEquals(CloudNotice.Action.SHOW, n.decide("111", 1792521100).action);
        assertNull(CloudNotice.fromPush(push("v", "2", "type", "afk", "event", "found", "kind", "afk_deal", "id", "x")));
        assertNull(CloudNotice.fromPush(push("v", "1", "type", "message", "id", "x")));
        assertNull(CloudNotice.fromPush(push("v", "1", "type", "afk", "event", "found", "kind", "afk_other", "id", "x")));
    }

    @Test
    public void anApprovalsEndTakesItsNotificationAway() {
        CloudNotice closed = CloudNotice.fromPush(push("v", "1", "type", "afk", "event", "approval_closed", "kind", "afk_deal", "id", "02J",
                "tag", "approval:01JBXK3D2E", "to", "111", "acct", "a_1", "approval", "01JBXK3D2E", "state", "approved"));
        assertEquals(CloudNotice.Action.CANCEL, closed.decide("111", 0).action);
        assertEquals("01JBXK3D2E", closed.approvalId());
    }

    @Test
    public void theNightsSummaryCountsInOrder() {
        CloudNotice night = CloudNotice.fromPush(push("v", "1", "type", "afk", "event", "night", "kind", "afk_info", "id", "night:1792540800",
                "at", "1792540800", "to", "111", "counts", "deal_done=2,found=1,bad,x=0"));
        assertNotNull(night);
        Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put("deal_done", 2);
        expected.put("found", 1);
        assertEquals(expected, night.counts());
        assertNull(night.account());
        assertEquals(CloudNotice.Action.SHOW, night.decide("111", 0).action);
    }

    @Test
    public void anEventFromANewerServiceIsDropped() {
        CloudNotice later = CloudNotice.fromPush(push("v", "1", "type", "afk", "event", "teleported", "kind", "afk_info", "id", "x", "to", "1"));
        assertEquals(CloudNotice.Action.DROP, later.decide("1", 0).action);
        assertTrue(CloudNotice.showable("message"));
        assertTrue(CloudNotice.showable("weekly"));
        assertTrue(CloudNotice.showable("moderation"));
    }
}
