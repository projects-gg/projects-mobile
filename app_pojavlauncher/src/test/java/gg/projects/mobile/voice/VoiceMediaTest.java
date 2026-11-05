package gg.projects.mobile.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/** The engine's pure parts: Opus tuning, receivers by mid, the quality mark, the sounds. */
public class VoiceMediaTest {
    @Test
    public void opusIsTunedInEveryOpusSection() {
        String sdp = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111 0\r\na=mid:0\r\na=rtpmap:111 opus/48000/2\r\n"
                + "a=fmtp:111 minptime=10;useinbandfec=0\r\na=rtpmap:0 PCMU/8000\r\n"
                + "m=audio 9 UDP/TLS/RTP/SAVPF 109\r\na=mid:1\r\na=rtpmap:109 OPUS/48000/2\r\n";
        String tuned = VoiceSdp.tuneOpus(sdp, 24_000, true, true);
        assertTrue(tuned.contains("a=fmtp:111 minptime=10;useinbandfec=1;stereo=0;sprop-stereo=0;maxaveragebitrate=24000;usedtx=1\r\n"));
        // A section without fmtp gets one right after its rtpmap.
        assertTrue(tuned.contains("a=rtpmap:109 OPUS/48000/2\r\na=fmtp:109 stereo=0;sprop-stereo=0;maxaveragebitrate=24000;"
                + "usedtx=1;useinbandfec=1\r\n"));
        // Other formats and lines stay.
        assertTrue(tuned.contains("a=rtpmap:0 PCMU/8000\r\nm=audio"));
        assertFalse(tuned.contains("a=fmtp:0"));
        assertTrue(tuned.endsWith("\r\n"));
        // Bare newlines stay bare.
        assertFalse(VoiceSdp.tuneOpus(sdp.replace("\r\n", "\n"), 16_000, false, false).contains("\r"));
    }

    @Test
    public void receiversBelongToWhoeverTheSfuNamesLast() {
        VoiceMids mids = new VoiceMids();
        Map<String, String> first = new TreeMap<>();
        first.put("1", "kaan");
        first.put("2", "ece");
        assertEquals(Arrays.asList("1", "2"), mids.assign(first));
        assertTrue(mids.current("1"));
        // Kaan came back: a new receiver, the old one is dead.
        assertEquals(Collections.singletonList("3"), mids.assign(Collections.singletonMap("3", "kaan")));
        assertFalse(mids.current("1"));
        assertTrue(mids.current("3"));
        // A mid given again belongs to its new person.
        assertEquals(Collections.singletonList("1"), mids.assign(Collections.singletonMap("1", "zeyno")));
        assertEquals("zeyno", mids.personOf("1"));
        assertTrue(mids.assign(Collections.singletonMap("1", "zeyno")).isEmpty());
    }

    private static Map<String, Object> stat(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    public void qualityIsWorseAtOnceAndBetterSlowly() {
        VoiceQuality.Raw before = new VoiceQuality.Raw();
        VoiceQuality.read(before, "outbound-rtp", stat("kind", "audio", "packetsSent", 100L));
        VoiceQuality.read(before, "inbound-rtp", stat("kind", "audio", "id", "r1", "packetsReceived", 100L, "packetsLost", 0L));
        VoiceQuality.Raw now = new VoiceQuality.Raw();
        VoiceQuality.read(now, "candidate-pair", stat("nominated", true, "state", "succeeded", "currentRoundTripTime", 0.05));
        VoiceQuality.read(now, "outbound-rtp", stat("kind", "audio", "packetsSent", 200L));
        VoiceQuality.read(now, "inbound-rtp", stat("kind", "audio", "id", "r1", "packetsReceived", 190L, "packetsLost", 10L,
                "jitter", 0.01));
        // 10 lost of 100 in the window: weak.
        assertEquals(1, VoiceQuality.grade(before, now));
        // Video stats never count.
        VoiceQuality.read(now, "inbound-rtp", stat("kind", "video", "id", "v", "packetsReceived", 1L, "packetsLost", 999L));
        assertEquals(1, VoiceQuality.grade(before, now));
        // Silence (too few packets) says nothing about loss.
        VoiceQuality.Raw quiet = new VoiceQuality.Raw();
        VoiceQuality.read(quiet, "inbound-rtp", stat("kind", "audio", "id", "r1", "packetsReceived", 101L, "packetsLost", 5L));
        assertEquals(0, VoiceQuality.grade(before, quiet));
        VoiceQuality.Tracker tracker = new VoiceQuality.Tracker();
        assertTrue(tracker.push(2));
        assertFalse(tracker.push(0));
        assertTrue(tracker.push(0));
        assertEquals(1, tracker.quality);
    }

    @Test
    public void theSoundsAreQuietAndTheirLengthsAsTheDesktops() {
        for (VoiceMachine.Sound sound : VoiceMachine.Sound.values()) {
            if (sound == VoiceMachine.Sound.STOP) continue;
            short[] samples = VoiceSounds.render(sound, 48_000);
            int peak = 0;
            for (short s : samples) peak = Math.max(peak, Math.abs(s));
            assertTrue(sound + " " + peak, peak > 1_000 && peak < 32767 / 3);
        }
        assertEquals(144_000, VoiceSounds.render(VoiceMachine.Sound.WAITING, 48_000).length);
        assertEquals(6_240, VoiceSounds.render(VoiceMachine.Sound.MIC_ON, 48_000).length);
        assertTrue(VoiceSounds.loops(VoiceMachine.Sound.WAITING));
        assertFalse(VoiceSounds.playsWhileDeaf(VoiceMachine.Sound.JOIN));
        assertTrue(VoiceSounds.playsWhileDeaf(VoiceMachine.Sound.MIC_OFF));
        short[] ring = VoiceSounds.ring(22_050);
        assertEquals(57_330, ring.length);
        int peak = 0;
        for (short s : ring) peak = Math.max(peak, Math.abs(s));
        assertTrue(String.valueOf(peak), peak > 4_000 && peak < 32_767);
    }
}
