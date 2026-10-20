package gg.projects.mobile.social;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/** M19: what the app shows from the signed config, what the service announces and the build. */
public class SocialGateTest {
    private static final Set<String> ALL = new HashSet<>(Arrays.asList("look", "calls", "roles", "mute"));
    private static final Set<String> PUSH = new HashSet<>(Collections.singletonList("push"));
    private static final Set<String> NONE = Collections.emptySet();

    @Test
    public void nothingWithoutTheConfigsFriends() {
        SocialGate gate = SocialGate.of(false, true, 30, ALL, PUSH, true);
        assertFalse(gate.friends);
        assertFalse(gate.teams);
        assertFalse(gate.push);
    }

    @Test
    public void theDeployedServiceGetsFriendsAndPlainCrews() {
        // SOC4 (0001-0004): team limits, no teamFeatures, no features.
        SocialGate gate = SocialGate.of(true, true, 30, NONE, null, true);
        assertTrue(gate.friends);
        assertTrue(gate.teams);
        assertFalse(gate.look);
        assertFalse(gate.calls);
        assertFalse(gate.roles);
        assertFalse(gate.mute);
        assertFalse("no features field: no pushes", gate.push);
    }

    @Test
    public void aServiceWithoutTeamRoutesHidesCrews() {
        SocialGate gate = SocialGate.of(true, true, 0, ALL, PUSH, true);
        assertTrue(gate.friends);
        assertFalse(gate.teams);
        assertFalse(gate.calls);
        assertTrue(gate.push);
    }

    @Test
    public void crewsNeedTheirOwnFlag() {
        SocialGate gate = SocialGate.of(true, false, 30, ALL, PUSH, true);
        assertFalse(gate.teams);
        assertFalse(gate.mute);
    }

    @Test
    public void theNewestServiceTurnsEverythingOn() {
        SocialGate gate = SocialGate.of(true, true, 30, ALL, PUSH, true);
        assertTrue(gate.look && gate.calls && gate.roles && gate.mute && gate.push);
    }

    @Test
    public void pushesNeedFirebaseInTheBuildAndTheServicesWord() {
        assertFalse("a build without the config file", SocialGate.of(true, true, 30, ALL, PUSH, false).push);
        assertFalse("a service whose FCM key does not work", SocialGate.of(true, true, 30, ALL, NONE, true).push);
    }

    @Test
    public void problemsAreReadFromTheEnvelope() {
        SocialProblem rate = SocialProblem.of(429, "{\"error\":{\"code\":\"rate_limited\",\"message\":\"x\",\"retryAfter\":3600}}", null, false);
        assertEquals(SocialProblem.Kind.RATE_LIMITED, rate.kind);
        assertEquals(3600, rate.retryAfter);
        assertEquals("a crew's ten text changes", SocialProblem.Kind.CREW_TEXT_TODAY,
                SocialProblem.of(429, "{\"error\":{\"code\":\"rate_limited\"}}", "120", true).kind);
        assertEquals(SocialProblem.Kind.SIGNED_OUT, SocialProblem.of(401, "{\"error\":{\"code\":\"unauthorized\"}}", null, false).kind);
        assertEquals(SocialProblem.Kind.NO_ONE_WITH_CODE, SocialProblem.of(404, "{\"error\":{\"code\":\"code_not_found\"}}", null, false).kind);
        assertEquals(SocialProblem.Kind.HAND_OVER_FIRST, SocialProblem.of(409, "{\"error\":{\"code\":\"founder_must_transfer\"}}", null, false).kind);
        SocialProblem platform = SocialProblem.of(503, "<html>Cloudflare</html>", "60", false);
        assertEquals("a platform page counts as the service being away", SocialProblem.Kind.UNAVAILABLE, platform.kind);
        assertEquals(60, platform.retryAfter);
        assertNull(platform.code);
        assertEquals(SocialProblem.Kind.FAILED, SocialProblem.of(418, "{\"error\":{\"code\":\"teapot\"}}", null, false).kind);
    }

    @Test
    public void backoffGrowsAndSpreadsTheFirstReconnect() {
        Backoff backoff = new Backoff(new Random(7));
        long first = backoff.nextDelayMs();
        assertTrue(first >= Backoff.MIN_MS / 2 && first <= Backoff.MIN_MS);
        long last = 0;
        for (int i = 0; i < 20; i++) last = backoff.nextDelayMs();
        assertTrue(last <= Backoff.MAX_MS && last >= Backoff.MAX_MS / 2);
        backoff.healthy();
        assertTrue("after a healthy connection: 0-5 s", backoff.nextDelayMs() <= Backoff.FIRST_SPREAD_MS);
        assertTrue("then it starts small again", backoff.nextDelayMs() <= Backoff.MIN_MS);
        assertTrue("4008: at least a minute", backoff.atLeastMs(60) >= 60_000);
    }
}
