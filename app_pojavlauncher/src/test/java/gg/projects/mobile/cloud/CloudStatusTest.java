package gg.projects.mobile.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The state line, the main action and the time left (the launcher's status.ts cases). */
public class CloudStatusTest {
    private static final long NOW = 1792846800L;

    private static CloudModel.Account account(String json) {
        return CloudModel.Account.read(Vectors.json("{\"id\":\"a_7KQ2MZ3P9TW4XB1C\",\"name\":\"A\",\"desired\":\"run\",\"expiresAt\":"
                + (NOW + 10 * 86_400) + ",\"graceUntil\":" + (NOW + 24 * 86_400) + (json.isEmpty() ? "" : "," + json) + "}"));
    }

    @Test
    public void theHoldWinsOverTheLiveState() {
        CloudStatus.Line line = CloudStatus.of(account("\"hold\":{\"kind\":\"suspended\"},\"live\":{\"state\":\"running\",\"since\":1}"),
                NOW * 1000, "tr");
        assertEquals(CloudStatus.State.SUSPENDED, line.state);
        assertEquals(CloudStatus.Tone.ERROR, line.tone);
        assertEquals(CloudStatus.State.OFF, CloudStatus.of(account("\"hold\":{\"kind\":\"paused\",\"mode\":\"off\"}"), NOW * 1000, "tr").state);
        assertEquals(CloudStatus.State.PAUSED, CloudStatus.of(account("\"hold\":{\"kind\":\"paused\",\"mode\":\"paused\"}"), NOW * 1000, "tr").state);
        CloudStatus.Line noHost = CloudStatus.of(account("\"hold\":{\"kind\":\"no_host\",\"since\":1}"), NOW * 1000, "tr");
        assertEquals(CloudStatus.State.NO_HOST, noHost.state);
        assertEquals(CloudStatus.Hint.NO_HOST, noHost.hint);
    }

    @Test
    public void stopsForGoodSayWhyAndWhatToFix() {
        CloudStatus.Line banned = CloudStatus.of(account("\"hold\":{\"kind\":\"cause\",\"cause\":\"banned\"}"), NOW * 1000, "tr");
        assertEquals(CloudStatus.State.STOPPED_BECAUSE, banned.state);
        assertEquals("banned", banned.cause);
        assertNull(banned.hint);
        CloudStatus.Line wrong = CloudStatus.of(account("\"hold\":{\"kind\":\"cause\",\"cause\":\"wrongPassword\"}"), NOW * 1000, "tr");
        assertEquals(CloudStatus.Hint.WRONG_PASSWORD, wrong.hint);
        CloudStatus.Line later = CloudStatus.of(account("\"hold\":{\"kind\":\"cause\",\"cause\":\"somethingNew\"}"), NOW * 1000, "tr");
        assertEquals("other", later.cause);
        // A row the machine named: counted as the settings list it.
        String config = "\"config\":{\"commands\":[{\"id\":\"c1\",\"on\":\"join\",\"secret\":false,\"text\":\"/a\"},"
                + "{\"id\":\"c2\",\"on\":\"join\",\"secret\":true,\"preview\":\"******\"}],\"rules\":[{\"id\":\"r1\",\"match\":\"x\","
                + "\"reply\":{\"secret\":false,\"text\":\"\\\\y\"}}]}";
        CloudStatus.Line unreadable = CloudStatus.of(account(config + ",\"hold\":{\"kind\":\"cause\",\"cause\":\"secretUnreadable\",\"row\":\"c2\"}"),
                NOW * 1000, "tr");
        assertEquals(CloudStatus.State.STOPPED_BECAUSE, unreadable.state);
        assertEquals(CloudStatus.Hint.UNREADABLE_COMMAND_ROW, unreadable.hint);
        assertEquals(2, unreadable.hintRow);
        CloudStatus.Line invalid = CloudStatus.of(account(config + ",\"hold\":{\"kind\":\"cause\",\"cause\":\"invalidCommand\",\"row\":\"r1\"}"),
                NOW * 1000, "tr");
        assertEquals(CloudStatus.State.INVALID_COMMAND, invalid.state);
        assertEquals(CloudStatus.Hint.INVALID_REPLY_ROW, invalid.hint);
        assertEquals(1, invalid.hintRow);
        CloudStatus.Line olderMachine = CloudStatus.of(account(config + ",\"hold\":{\"kind\":\"cause\",\"cause\":\"invalidCommand\"}"), NOW * 1000, "tr");
        assertEquals(CloudStatus.Hint.INVALID_COMMAND, olderMachine.hint);
        CloudStatus.Line blocked = CloudStatus.of(account("\"hold\":{\"kind\":\"server_blocked\",\"reason\":{\"tr\":\"AFK yasak\",\"en\":\"No AFK\"}}"),
                NOW * 1000, "en");
        assertEquals(CloudStatus.State.SERVER_BLOCKED_BECAUSE, blocked.state);
        assertEquals("No AFK", blocked.blockedReason);
    }

    @Test
    public void runningCountsItsUptimeAndSaysWhenItChanges() {
        CloudStatus.Line seconds = CloudStatus.of(account("\"live\":{\"state\":\"running\",\"since\":" + (NOW - 42) + "}"), NOW * 1000 + 300, "tr");
        assertEquals(CloudStatus.State.RUNNING, seconds.state);
        assertEquals(42, seconds.uptime);
        assertEquals(700, seconds.nextChangeMs);
        CloudStatus.Line minutes = CloudStatus.of(account("\"live\":{\"state\":\"running\",\"since\":" + (NOW - 3725) + "}"), NOW * 1000, "tr");
        assertEquals(3720, minutes.uptime);
        assertEquals(55_000, minutes.nextChangeMs);
        assertEquals(CloudStatus.State.RUNNING_NOW, CloudStatus.of(account("\"live\":{\"state\":\"running\"}"), NOW * 1000, "tr").state);
    }

    @Test
    public void waitingCountsDownToTheNextTry() {
        CloudStatus.Line waiting = CloudStatus.of(account("\"live\":{\"state\":\"waiting\",\"retryAt\":" + (NOW + 20) + "}"), NOW * 1000 + 400, "tr");
        assertEquals(CloudStatus.State.WAITING_IN, waiting.state);
        assertEquals(20, waiting.seconds);
        assertEquals(CloudStatus.State.CONNECTING,
                CloudStatus.of(account("\"live\":{\"state\":\"waiting\",\"retryAt\":" + NOW + "}"), NOW * 1000, "tr").state);
        assertEquals(CloudStatus.State.QUEUED_FULL,
                CloudStatus.of(account("\"live\":{\"state\":\"queued\",\"reason\":\"target_cap\"}"), NOW * 1000, "tr").state);
        assertEquals(CloudStatus.State.UNKNOWN, CloudStatus.of(account("\"live\":{\"state\":\"warping\"}"), NOW * 1000, "tr").state);
    }

    @Test
    public void theMainAction() {
        assertEquals(CloudStatus.Primary.STOP, CloudStatus.primary(account("\"live\":{\"state\":\"running\"}")));
        assertEquals(CloudStatus.Primary.START, CloudStatus.primary(account("\"desired\":\"stop\"")));
        assertEquals(CloudStatus.Primary.START, CloudStatus.primary(account("\"hold\":{\"kind\":\"cause\",\"cause\":\"banned\"}")));
        assertEquals(CloudStatus.Primary.RENEW, CloudStatus.primary(account("\"expired\":true")));
        assertEquals(CloudStatus.Primary.NONE, CloudStatus.primary(account("\"hold\":{\"kind\":\"restricted\"}")));
        assertTrue(CloudStatus.inGame(account("\"live\":{\"state\":\"running\"}")));
        assertFalse(CloudStatus.inGame(account("\"live\":{\"state\":\"connecting\"}")));
    }

    @Test
    public void timeLeft() {
        CloudStatus.TimeLeft days = CloudStatus.timeLeft(account(""), NOW * 1000);
        assertEquals(CloudStatus.TimeLeft.Unit.DAYS, days.unit);
        assertEquals(10, days.count);
        CloudStatus.TimeLeft hours = CloudStatus.timeLeft(CloudModel.Account.read(Vectors.json(
                "{\"id\":\"a_7KQ2MZ3P9TW4XB1C\",\"expiresAt\":" + (NOW + 5 * 3600 + 10) + "}")), NOW * 1000);
        assertEquals(CloudStatus.TimeLeft.Unit.HOURS, hours.unit);
        assertEquals(5, hours.count);
        CloudStatus.TimeLeft grace = CloudStatus.timeLeft(account("\"expired\":true"), NOW * 1000);
        assertEquals(CloudStatus.TimeLeft.Unit.GRACE_DAYS, grace.unit);
        assertEquals(24, grace.count);
    }

    @Test
    public void prices() {
        assertEquals("60 TL", CloudStatus.price("60.00", "tr"));
        assertEquals("59,90 TL", CloudStatus.price("59.90", "tr"));
        assertEquals("59.90 TL", CloudStatus.price("59.90", "en"));
        assertEquals("2000 TL", CloudStatus.price("2000.00", "tr"));
    }

    @Test
    public void aStopFixedInTheSettingsOffersThemFirst() {
        CloudStatus.Line wrong = CloudStatus.of(account("\"hold\":{\"kind\":\"cause\",\"cause\":\"wrongPassword\"}"), NOW * 1000, "tr");
        assertEquals(CloudStatus.Hint.WRONG_PASSWORD, wrong.hint);
        assertTrue(CloudStatus.fixInSettings(wrong.hint));
        assertTrue(CloudStatus.fixInSettings(CloudStatus.Hint.UNSUPPORTED_VERSION));
        assertTrue(CloudStatus.fixInSettings(CloudStatus.Hint.INVALID_COMMAND_ROW));
        // Fixed elsewhere (the server's whitelist, leaving the game) or by waiting: starting stays the main action.
        assertFalse(CloudStatus.fixInSettings(CloudStatus.Hint.NOT_WHITELISTED));
        assertFalse(CloudStatus.fixInSettings(CloudStatus.Hint.LOGGED_IN_ELSEWHERE));
        assertFalse(CloudStatus.fixInSettings(CloudStatus.Hint.NO_HOST));
        assertFalse(CloudStatus.fixInSettings(null));
    }
}
