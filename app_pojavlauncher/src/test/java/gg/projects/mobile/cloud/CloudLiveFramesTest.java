package gg.projects.mobile.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** The live console socket's frames (the README's messages) and close codes. */
public class CloudLiveFramesTest {
    private static final class Recorder implements CloudLiveFrames.Listener {
        final List<String> calls = new ArrayList<>();

        @Override
        public void hello(boolean hostOnline, List<String> accounts, long leaseSeconds, long now) {
            calls.add("hello " + hostOnline + " " + accounts + " " + leaseSeconds + " " + now);
        }

        @Override
        public void state(String acct, JsonObject live) {
            calls.add("st " + acct + " " + live.get("state").getAsString());
        }

        @Override
        public void console(String acct, List<CloudModel.ConsoleLine> lines, int skipped, boolean snapshot) {
            calls.add("con " + acct + " " + lines.size() + " " + skipped + " " + snapshot + (lines.isEmpty() ? "" : " " + lines.get(0).text + "/" + lines.get(0).k));
        }

        @Override
        public void host(boolean online) {
            calls.add("host " + online);
        }

        @Override
        public void error(String code, String acct) {
            calls.add("error " + code + " " + acct);
        }

        @Override
        public void ai(String acct, JsonObject ai) {
            calls.add("aist " + acct + " " + ai.get("state").getAsString());
        }

        @Override
        public void feed(String acct, List<CloudModel.FeedItem> items, boolean snapshot) {
            calls.add("aifeed " + acct + " " + items.size() + " " + snapshot);
        }

        @Override
        public void approval(String acct, CloudModel.Approval approval) {
            calls.add("aiapproval " + acct + " " + approval.state);
        }

        @Override
        public void bye(String reason) {
            calls.add("bye " + reason);
        }
    }

    @Test
    public void everyMessageOfTheContract() {
        Recorder r = new Recorder();
        String[] frames = {
                "{\"t\":\"hello\",\"v\":1,\"host\":{\"id\":\"kz1\",\"online\":true},\"accts\":[\"a_1\",\"a_2\"],\"leaseSeconds\":600,\"now\":1792846800}",
                "{\"t\":\"st\",\"acct\":\"a_1\",\"live\":{\"state\":\"running\",\"since\":1792846000}}",
                "{\"t\":\"con\",\"acct\":\"a_1\",\"lines\":[{\"at\":1792846801,\"text\":\"<Kaan> selam\",\"k\":\"n\"},{\"at\":1792846802,\"text\":\"x\",\"k\":\"e\"}],\"skipped\":3}",
                "{\"t\":\"con\",\"acct\":\"a_1\",\"lines\":[],\"snapshot\":true}",
                "{\"t\":\"host\",\"online\":false}",
                "{\"t\":\"error\",\"code\":\"host_offline\",\"acct\":\"a_1\"}",
                "{\"t\":\"aist\",\"acct\":\"a_1\",\"ai\":{\"state\":\"paused\",\"reason\":\"quota\"}}",
                "{\"t\":\"aifeed\",\"acct\":\"a_1\",\"items\":[{\"id\":\"01J\",\"at\":1,\"kind\":\"pm_in\",\"counterpart\":\"Kaan_07\",\"text\":\"selam\"}],\"snapshot\":true}",
                "{\"t\":\"aiapproval\",\"acct\":\"a_1\",\"approval\":{\"id\":\"01JBXK3D2E\",\"state\":\"approved\",\"deal\":{\"side\":\"buy\"}}}",
                "{\"t\":\"bye\",\"reason\":\"session\"}",
                "{\"t\":\"pong\"}",
        };
        for (String f : frames) assertTrue(f, CloudLiveFrames.dispatch(Vectors.json(f), r));
        assertEquals("[hello true [a_1, a_2] 600 1792846800, st a_1 running, con a_1 2 3 false <Kaan> selam/n, con a_1 0 0 true, "
                + "host false, error host_offline a_1, aist a_1 paused, aifeed a_1 1 true, aiapproval a_1 approved, bye session]", r.calls.toString());
        assertFalse(CloudLiveFrames.dispatch(Vectors.json("{\"t\":\"later\"}"), r));
        assertFalse(CloudLiveFrames.dispatch(Vectors.json("{\"t\":\"st\"}"), r));
        assertFalse(CloudLiveFrames.dispatch(null, r));
    }

    @Test
    public void closeCodes() {
        assertEquals(CloudLiveFrames.After.REPLACED, CloudLiveFrames.after(4000));
        assertEquals(CloudLiveFrames.After.SIGNED_OUT, CloudLiveFrames.after(4001));
        assertEquals(CloudLiveFrames.After.RECONNECT_NOW, CloudLiveFrames.after(4004));
        assertEquals(CloudLiveFrames.After.WAIT_A_MINUTE, CloudLiveFrames.after(4008));
        assertEquals(CloudLiveFrames.After.BACKOFF, CloudLiveFrames.after(1006));
        assertEquals("{\"t\":\"watch\",\"acct\":\"a_1\",\"ai\":true}", CloudLiveFrames.watch("a_1", true).toString());
        assertEquals("{\"t\":\"watch\",\"acct\":\"a_1\"}", CloudLiveFrames.watch("a_1", false).toString());
        assertEquals("{\"t\":\"ping\"}", CloudLiveFrames.PING);
    }
}
