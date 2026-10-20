package gg.projects.mobile.social;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.security.SecureRandom;

/** M19: the phone's half of the Discord sign-in (one-time code + verifier, README "Sign-in"). */
public class SignInFlowTest {
    private static final String CODE = "Qm9fT2sXyZ-0123456789_abcdefghijklmnopqrstu";

    @Test
    public void challengeIsRfc7636S256() {
        // RFC 7636 appendix B.
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                SignInFlow.challengeOf("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
        assertEquals("", SignInFlow.base64Url(new byte[0]));
        assertEquals("AQ", SignInFlow.base64Url(new byte[]{1}));
        assertEquals("AQI", SignInFlow.base64Url(new byte[]{1, 2}));
        assertEquals("_-8", SignInFlow.base64Url(new byte[]{(byte) 0xFF, (byte) 0xEF}));
    }

    @Test
    public void aNewAttemptHas43CharactersOfVerifier() {
        SignInFlow flow = new SignInFlow();
        SignInFlow.Attempt attempt = flow.begin(new SecureRandom(), 1000);
        assertEquals(43, attempt.verifier.length());
        assertEquals(SignInFlow.challengeOf(attempt.verifier), attempt.challenge);
        assertEquals(SignInFlow.Phase.WAITING, flow.phase());
        assertTrue(flow.waiting(1001));
        assertEquals("https://social.projects.gg/auth/discord/start?challenge=" + attempt.challenge,
                SignInFlow.startUrl("https://social.projects.gg/", attempt.challenge));
    }

    @Test
    public void aLinkNobodyHereStartedIsIgnored() {
        SignInFlow flow = new SignInFlow();
        assertNull(flow.onDeepLink(CODE, 1000));
        flow.starting();
        assertNull("the browser has not opened yet", flow.onDeepLink(CODE, 1000));
    }

    @Test
    public void oneClaimPerAttempt() {
        SignInFlow flow = new SignInFlow();
        SignInFlow.Attempt attempt = flow.begin(new SecureRandom(), 1000);
        assertSame(attempt, flow.onDeepLink(CODE, 1100));
        assertEquals(SignInFlow.Phase.CLAIMING, flow.phase());
        assertNull("the attempt is used up", flow.onDeepLink(CODE, 1101));
        flow.reset();
        assertEquals(SignInFlow.Phase.IDLE, flow.phase());
        assertNull(flow.onDeepLink(CODE, 1102));
    }

    @Test
    public void anExpiredAttemptIsIgnored() {
        SignInFlow flow = new SignInFlow();
        flow.begin(new SecureRandom(), 1000);
        assertNull(flow.onDeepLink(CODE, 1000 + SignInFlow.ATTEMPT_SECONDS));
        assertEquals(SignInFlow.Phase.IDLE, flow.phase());
        assertFalse(flow.waiting(1000 + SignInFlow.ATTEMPT_SECONDS));
    }

    @Test
    public void aMissingCodeLeavesTheAttemptWaiting() {
        SignInFlow flow = new SignInFlow();
        flow.begin(new SecureRandom(), 1000);
        assertNull(flow.onDeepLink(null, 1001));
        assertTrue(flow.waiting(1002));
    }

    @Test
    public void aKeptAttemptComesBackOnlyWhenItIsWhole() {
        SignInFlow flow = new SignInFlow();
        String verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        flow.restore(new SignInFlow.Attempt(verifier, "tampered", 1000), 1001);
        assertEquals(SignInFlow.Phase.IDLE, flow.phase());
        flow.restore(new SignInFlow.Attempt(verifier, SignInFlow.challengeOf(verifier), 1000), 1000 + SignInFlow.ATTEMPT_SECONDS + 1);
        assertEquals("expired", SignInFlow.Phase.IDLE, flow.phase());
        flow.restore(new SignInFlow.Attempt(verifier, SignInFlow.challengeOf(verifier), 1000), 1300);
        assertEquals(SignInFlow.Phase.WAITING, flow.phase());
        assertNotNull(flow.onDeepLink(CODE, 1301));
    }
}
