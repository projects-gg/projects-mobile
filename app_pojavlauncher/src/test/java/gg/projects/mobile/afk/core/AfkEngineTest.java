package gg.projects.mobile.afk.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import gg.projects.mobile.afk.api.AfkAlert;
import gg.projects.mobile.afk.api.AfkLine;
import gg.projects.mobile.afk.api.AfkLoginMode;
import gg.projects.mobile.afk.api.AfkReason;
import gg.projects.mobile.afk.api.AfkSendResult;
import gg.projects.mobile.afk.api.AfkStartResult;
import gg.projects.mobile.afk.api.AfkState;
import gg.projects.mobile.afk.api.AfkStatus;

/** PHONE-AFK's engine against a stand-in server: joins, login commands, drops, refusals, network, limits, alerts. */
public class AfkEngineTest {
    private FakeHost host;
    private FakeWire wire;
    private AfkEngine engine;

    /** Spread 0.5: delays exactly as the schedule says. */
    private static final class Middle extends Random {
        @Override
        public double nextDouble() {
            return 0.5;
        }
    }

    private static AfkDrop kick(String message) {
        return new AfkDrop(AfkDrop.Kind.KICKED, message, message, null);
    }

    @Before
    public void setUp() {
        host = new FakeHost();
        wire = new FakeWire();
        engine = new AfkEngine(wire, host, new Middle());
        host.account("a", "SleepyFox_42", "play.example.com", Arrays.asList("/login hunter22", "/is home"),
                AfkLoginMode.EVERY_JOIN, false);
        host.account("b", "Night_Owl", "play.example.com", Collections.<String>emptyList(), AfkLoginMode.FIRST_JOIN, false);
        host.account("c", "Ember_77", "mc.other.org", Collections.<String>emptyList(), AfkLoginMode.EVERY_JOIN, true);
        host.account("d", "Frost_Bite", "mc.fourth.org", Collections.<String>emptyList(), AfkLoginMode.EVERY_JOIN, true);
    }

    @Test
    public void joinsAndSendsItsLoginCommandsTwoSecondsApartMasked() {
        assertEquals(AfkStartResult.STARTED, engine.start("a"));
        assertEquals(AfkStartResult.ALREADY_RUNNING, engine.start("a"));
        assertEquals(AfkState.CONNECTING, host.lastStatus("a").state);
        host.advance(0);
        FakeWire.Conn c = wire.last();
        assertEquals("play.example.com", c.target.host);
        assertEquals("SleepyFox_42", c.target.name);
        assertEquals("en_us", c.target.locale);
        assertFalse(c.target.transfer);
        c.events.loggingIn();
        assertEquals(AfkState.LOGGING_IN, host.lastStatus("a").state);
        c.events.configuring();
        assertEquals(AfkState.CONFIGURING, host.lastStatus("a").state);
        c.events.joined();
        AfkStatus inGame = host.lastStatus("a");
        assertEquals(AfkState.IN_GAME, inGame.state);
        assertEquals(host.now, inGame.inGameSince);
        host.advance(1_999);
        assertTrue(c.chats.isEmpty());
        host.advance(1);
        assertEquals(Collections.singletonList("/login hunter22"), c.chats);
        host.advance(2_000);
        assertEquals(Arrays.asList("/login hunter22", "/is home"), c.chats);
        // The server echoes the password: masked before it is kept.
        c.events.line(AfkLine.Kind.SYSTEM, AfkText.plain("Komut: /login hunter22"), null);
        List<String> texts = host.texts("a");
        assertTrue(texts.toString(), texts.contains("SYSTEM: Komut: /login ******"));
        assertTrue(texts.contains("NOTICE: login commands sent"));
        for (String t : texts) assertFalse(t, t.contains("hunter22"));
    }

    @Test
    public void everyJoinRepeatsTheCommandsAfterAProxySwitchFirstJoinDoesNot() {
        host.accounts.put("b", new AfkHost.Loaded(host.load("b").account, Collections.singletonList("/login pw1234")));
        engine.start("a");
        engine.start("b");
        host.advance(10_000);
        FakeWire.Conn a = wire.opened.get(0);
        FakeWire.Conn b = wire.opened.get(1);
        a.join();
        b.join();
        host.advance(5_000);
        assertEquals(2, a.chats.size());
        assertEquals(1, b.chats.size());
        // The proxy switches both to another server: "every join" sends again, "first join" does not.
        a.events.configuring();
        a.events.joined();
        b.events.configuring();
        b.events.joined();
        host.advance(5_000);
        assertEquals(4, a.chats.size());
        assertEquals(1, b.chats.size());
        assertEquals(AfkState.IN_GAME, host.lastStatus("a").state);
    }

    @Test
    public void accountsOnOneServerConnectFourAndAHalfSecondsApart() {
        engine.start("a");
        engine.start("b");
        engine.start("c");
        host.advance(0);
        assertEquals(2, wire.opened.size()); // a and c (another server) at once
        host.advance(4_499);
        assertEquals(2, wire.opened.size());
        host.advance(1);
        assertEquals(3, wire.opened.size());
        assertEquals("Night_Owl", wire.last().target.name);
    }

    @Test
    public void atMostThreeRun() {
        assertEquals(AfkStartResult.STARTED, engine.start("a"));
        assertEquals(AfkStartResult.STARTED, engine.start("b"));
        assertEquals(AfkStartResult.STARTED, engine.start("c"));
        assertEquals(AfkStartResult.LIMIT_REACHED, engine.start("d"));
        assertEquals(AfkStartResult.NO_ACCOUNT, new AfkEngine(wire, host, new Middle()).start("nope"));
        assertEquals(3, engine.runningCount());
    }

    @Test
    public void dropsBackOffAndReconnect() {
        engine.start("c");
        host.advance(0);
        FakeWire.Conn first = wire.last();
        first.join();
        first.events.ended(kick("Server is restarting"));
        AfkStatus waiting = host.lastStatus("c");
        assertEquals(AfkState.WAITING, waiting.state);
        assertEquals(AfkReason.KICKED, waiting.reason);
        assertEquals("Server is restarting", waiting.serverMessage);
        assertEquals(1, waiting.attempt);
        assertEquals(host.now + 5_000, waiting.nextAttemptAt);
        host.advance(4_999);
        assertEquals(1, wire.opened.size());
        host.advance(1);
        assertEquals(2, wire.opened.size());
        // Failing again waits longer: 10 s, then 20 s.
        wire.last().events.ended(AfkDrop.of(AfkDrop.Kind.UNREACHABLE));
        assertEquals(host.now + 10_000, host.lastStatus("c").nextAttemptAt);
        host.advance(10_000);
        wire.last().events.ended(AfkDrop.of(AfkDrop.Kind.UNREACHABLE));
        assertEquals(host.now + 20_000, host.lastStatus("c").nextAttemptAt);
        assertEquals(3, host.lastStatus("c").attempt);
    }

    @Test
    public void twoMinutesInGameStartTheCountOver() {
        engine.start("c");
        host.advance(0);
        wire.last().events.ended(AfkDrop.of(AfkDrop.Kind.UNREACHABLE));
        host.advance(5_000);
        wire.last().events.ended(AfkDrop.of(AfkDrop.Kind.UNREACHABLE));
        assertEquals(2, host.lastStatus("c").attempt);
        host.advance(10_000);
        wire.last().join();
        host.advance(120_000);
        wire.last().events.ended(AfkDrop.of(AfkDrop.Kind.CONNECTION_LOST));
        assertEquals(1, host.lastStatus("c").attempt);
        assertEquals(host.now + 5_000, host.lastStatus("c").nextAttemptAt);
    }

    @Test
    public void aBanStopsAtOnceAndSaysWhy() {
        engine.start("c");
        host.advance(0);
        wire.last().join();
        wire.last().events.ended(kick("You are banned from this server!"));
        AfkStatus stopped = host.lastStatus("c");
        assertEquals(AfkState.STOPPED, stopped.state);
        assertEquals(AfkReason.BANNED, stopped.reason);
        assertEquals(0, engine.runningCount());
        assertEquals(AfkReason.BANNED, engine.stoppedStatuses().get("c").reason);
        AfkAlert alert = host.alerts.get(host.alerts.size() - 1);
        assertEquals(AfkAlert.Kind.STOPPED, alert.kind);
        assertEquals(AfkReason.BANNED, alert.reason);
        assertEquals(0, host.pendingTimers());
        // Starting it again clears the stop.
        assertEquals(AfkStartResult.STARTED, engine.start("c"));
        assertNull(engine.stoppedStatuses().get("c"));
    }

    @Test
    public void alreadyConnectedStopsOnTheFifthInARow() {
        engine.start("c");
        for (int i = 1; i <= 4; i++) {
            host.advance(300_000);
            wire.last().events.loggingIn();
            wire.last().events.ended(new AfkDrop(AfkDrop.Kind.LOGIN_REJECTED, "You are already connected to this proxy!",
                    "You are already connected to this proxy!", null));
            assertEquals("kick " + i, AfkState.WAITING, host.lastStatus("c").state);
        }
        host.advance(300_000);
        wire.last().events.ended(new AfkDrop(AfkDrop.Kind.LOGIN_REJECTED, "You are already connected to this proxy!",
                "You are already connected to this proxy!", null));
        assertEquals(AfkReason.ALREADY_CONNECTED, host.lastStatus("c").reason);
        assertEquals(AfkState.STOPPED, host.lastStatus("c").state);
    }

    @Test
    public void premiumAndVersionRefusalsStop() {
        engine.start("c");
        host.advance(0);
        wire.last().events.ended(AfkDrop.of(AfkDrop.Kind.PREMIUM_ONLY));
        assertEquals(AfkReason.PREMIUM_ONLY, host.lastStatus("c").reason);
        engine.start("d");
        host.advance(0);
        wire.last().events.ended(AfkDrop.of(AfkDrop.Kind.UNSUPPORTED_VERSION));
        assertEquals(AfkReason.UNSUPPORTED_VERSION, host.lastStatus("d").reason);
        assertEquals(0, engine.runningCount());
    }

    @Test
    public void noNetworkWaitsAndGoesOnAtOnceWhenOneComesBack() {
        engine.network(AfkEngine.Net.WIFI);
        engine.start("a");
        host.advance(0);
        FakeWire.Conn c = wire.last();
        c.join();
        engine.network(AfkEngine.Net.NONE);
        assertTrue(c.closed);
        assertEquals(AfkState.NO_NETWORK, host.lastStatus("a").state);
        assertEquals(AfkReason.NO_NETWORK, host.lastStatus("a").reason);
        // Mobile data only: account "a" waits for Wi-Fi; "c" may use mobile data.
        engine.network(AfkEngine.Net.MOBILE);
        assertEquals(AfkReason.WAITING_WIFI, host.lastStatus("a").reason);
        engine.start("c");
        host.advance(0);
        assertEquals("mc.other.org", wire.last().target.host);
        engine.network(AfkEngine.Net.WIFI);
        // Its turn at its server: the last attempt there was this same moment, so 4.5 s later.
        host.advance(4_499);
        assertEquals("mc.other.org", wire.last().target.host);
        host.advance(1);
        assertEquals("play.example.com", wire.last().target.host);
        assertEquals(AfkState.CONNECTING, host.lastStatus("a").state);
    }

    @Test
    public void outOfTheGameForThreeMinutesAlertsAndJoiningClearsIt() {
        engine.start("c");
        host.advance(0);
        wire.last().join();
        wire.last().events.ended(AfkDrop.of(AfkDrop.Kind.CONNECTION_LOST));
        for (int i = 0; i < 6; i++) {
            host.advance(30_000);
            if (wire.opened.size() > i + 1) wire.last().events.ended(AfkDrop.of(AfkDrop.Kind.UNREACHABLE));
        }
        host.advance(60_000);
        boolean dropped = false;
        for (AfkAlert a : host.alerts) dropped |= a.kind == AfkAlert.Kind.DROPPED;
        assertTrue(dropped);
        host.advance(300_000);
        wire.last().join();
        assertTrue(host.cleared.contains("c:DROPPED"));
    }

    @Test
    public void sendsWithinTheRateLimitOnlyInGame() {
        engine.start("a");
        assertEquals(AfkSendResult.NOT_IN_GAME, engine.send("a", "hi"));
        assertEquals(AfkSendResult.NOT_RUNNING, engine.send("b", "hi"));
        host.advance(0);
        FakeWire.Conn c = wire.last();
        c.join();
        assertEquals(AfkSendResult.EMPTY, engine.send("a", "  "));
        assertEquals(AfkSendResult.SENT, engine.send("a", "/msg Ember_77 selam"));
        assertEquals(AfkSendResult.SENT, engine.send("a", "/l secret99"));
        assertEquals(AfkSendResult.SENT, engine.send("a", "hi"));
        assertEquals(AfkSendResult.TOO_FAST, engine.send("a", "again"));
        host.advance(3_000);
        assertEquals(AfkSendResult.SENT, engine.send("a", "again"));
        assertTrue(c.chats.contains("/l secret99"));
        List<String> texts = host.texts("a");
        assertTrue(texts.toString(), texts.contains("OWN: /l ******"));
        c.events.line(AfkLine.Kind.SYSTEM, AfkText.plain("Giriş: /l secret99"), null);
        assertTrue(host.texts("a").contains("SYSTEM: Giriş: /l ******"));
    }

    @Test
    public void mentionsAndPrivateMessagesAlertOwnLinesDoNot() {
        engine.start("a");
        host.advance(0);
        FakeWire.Conn c = wire.last();
        c.join();
        c.events.line(AfkLine.Kind.CHAT, AfkText.plain("<Ember_77> sleepyfox_42 neredesin?"), "Ember_77");
        c.events.line(AfkLine.Kind.SYSTEM, AfkText.plain("SleepyFox_42 joined the game"), null);
        c.events.line(AfkLine.Kind.CHAT, AfkText.plain("<SleepyFox_42> SleepyFox_42 burada"), "SleepyFox_42");
        c.events.line(AfkLine.Kind.SYSTEM, AfkText.plain("[Ember_77 -> ben] AFK misin?"), null);
        c.events.line(AfkLine.Kind.PRIVATE, AfkText.plain("Night_Owl whispers to you: hey"), "Night_Owl");
        int mentions = 0;
        int privates = 0;
        for (AfkAlert a : host.alerts) {
            if (a.kind == AfkAlert.Kind.MENTION) mentions++;
            if (a.kind == AfkAlert.Kind.PRIVATE_MESSAGE) privates++;
        }
        assertEquals(1, mentions);
        assertEquals(2, privates);
        AfkLine pm = null;
        for (AfkLine l : host.lines) {
            if (l.text.startsWith("[Ember_77 -> ben]")) pm = l;
        }
        assertEquals(AfkLine.Kind.PRIVATE, pm.kind);
        assertEquals("Ember_77", pm.sender);
    }

    @Test
    public void windowsAlertAndTransfersReconnectThere() {
        engine.start("c");
        host.advance(0);
        FakeWire.Conn c = wire.last();
        c.join();
        c.events.window("Bot kontrolü");
        assertEquals(AfkAlert.Kind.SERVER_WINDOW, host.alerts.get(host.alerts.size() - 1).kind);
        assertEquals("Bot kontrolü", host.alerts.get(host.alerts.size() - 1).text);
        c.events.transfer("Lobby.Example.ORG", 25570);
        host.advance(0);
        FakeWire.Conn moved = wire.last();
        assertEquals("lobby.example.org", moved.target.host);
        assertEquals(25570, moved.target.port);
        assertTrue(moved.target.transfer);
        // A drop there goes back to the account's own server.
        moved.join();
        moved.events.ended(AfkDrop.of(AfkDrop.Kind.CONNECTION_LOST));
        host.advance(10_000);
        assertEquals("mc.other.org", wire.last().target.host);
        assertFalse(wire.last().target.transfer);
    }

    @Test
    public void thePlayersStopIsQuietAndLateEventsAreIgnored() {
        engine.start("a");
        host.advance(0);
        FakeWire.Conn c = wire.last();
        c.join();
        int alerts = host.alerts.size();
        engine.stop("a");
        assertTrue(c.closed);
        assertEquals(AfkState.STOPPED, host.lastStatus("a").state);
        assertNull(host.lastStatus("a").reason);
        c.events.ended(AfkDrop.of(AfkDrop.Kind.CONNECTION_LOST));
        c.events.line(AfkLine.Kind.CHAT, AfkText.plain("late"), null);
        assertEquals(alerts, host.alerts.size());
        assertEquals(0, host.pendingTimers());
        assertTrue(host.texts("a").contains("NOTICE: stopped by player"));
    }

    @Test
    public void thePhoneStopsEveryAccountWithItsReason() {
        engine.start("a");
        engine.start("c");
        host.advance(5_000);
        engine.stopAll(AfkReason.BATTERY_LOW);
        assertEquals(0, engine.runningCount());
        assertEquals(AfkReason.BATTERY_LOW, engine.stoppedStatuses().get("a").reason);
        assertEquals(AfkReason.BATTERY_LOW, engine.stoppedStatuses().get("c").reason);
        // One phone alert comes from the service, not one per account.
        for (AfkAlert a : host.alerts) assertFalse(a.kind == AfkAlert.Kind.STOPPED);
    }

    @Test
    public void aRemovedAccountStopsAtItsNextConnect() {
        engine.start("c");
        host.advance(0);
        wire.last().events.ended(AfkDrop.of(AfkDrop.Kind.UNREACHABLE));
        host.accounts.remove("c");
        host.advance(10_000);
        assertEquals(0, engine.runningCount());
        assertEquals(1, wire.opened.size());
    }

    @Test
    public void sneaksNowAndThenWhileInGame() {
        engine.start("c");
        host.advance(0);
        FakeWire.Conn c = wire.last();
        c.join();
        host.advance(75_000);
        assertEquals(Collections.singletonList(true), c.sneaks);
        host.advance(800);
        assertEquals(Arrays.asList(true, false), c.sneaks);
        host.advance(74_200);
        assertEquals(Arrays.asList(true, false, true), c.sneaks);
        // Dropped: no more sneaking on a closed connection.
        c.events.ended(AfkDrop.of(AfkDrop.Kind.CONNECTION_LOST));
        host.advance(800);
        assertEquals(Arrays.asList(true, false, true), c.sneaks);
    }

    @Test
    public void theConsoleKeepsThreeHundredLinesInOrder() {
        engine.start("c");
        host.advance(0);
        FakeWire.Conn c = wire.last();
        c.join();
        for (int i = 0; i < 350; i++) c.events.line(AfkLine.Kind.SYSTEM, AfkText.plain("line " + i), null);
        List<AfkLine> backlog = engine.backlog("c");
        assertEquals(300, backlog.size());
        assertEquals("line 349", backlog.get(299).text);
        for (int i = 1; i < backlog.size(); i++) assertEquals(backlog.get(i - 1).seq + 1, backlog.get(i).seq);
    }
}
