package gg.projects.mobile.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQOL-M (0.5.4, P1): org.webrtc's PeerConnection.getTransceivers(), getSenders() and getReceivers() dispose every
 * wrapper they handed out before, the microphone's transceiver and sender from addTransceiver too; 0.5.3 asked for the
 * transceivers after setLocalDescription to find the mid and the voice process died on every join with the microphone
 * ("RtpSender has been disposed"). The engine keeps its own transceiver and never asks for those lists again: this
 * guards the sources (libwebrtc does not run on the JVM).
 */
public class VoiceEngineGuardTest {
    private static final File VOICE = new File("src/main/java/gg/projects/mobile/voice");
    private static final Pattern LISTS = Pattern.compile("\\.\\s*(getTransceivers|getSenders|getReceivers)\\s*\\(");

    @Test
    public void theVoiceCodeNeverAsksAConnectionForItsTransceiversSendersOrReceivers() throws Exception {
        List<String> found = new ArrayList<>();
        scan(VOICE, found);
        assertEquals("calls that dispose the engine's own sender", new ArrayList<String>(), found);
    }

    @Test
    public void theEngineReadsTheMidFromItsOwnTransceiver() throws Exception {
        String code = withoutComments(new String(Files.readAllBytes(new File(VOICE, "VoiceEngine.java").toPath()), StandardCharsets.UTF_8));
        assertTrue(code.contains("transceiver = t;"));
        assertTrue(code.contains("transceiver.getMid()"));
    }

    private static void scan(File dir, List<String> found) throws Exception {
        File[] files = dir.listFiles();
        assertTrue("no sources at " + dir.getAbsolutePath(), files != null && files.length > 0);
        for (File file : files) {
            if (file.isDirectory()) {
                scan(file, found);
                continue;
            }
            if (!file.getName().endsWith(".java")) continue;
            String code = withoutComments(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            Matcher m = LISTS.matcher(code);
            while (m.find()) found.add(file.getName() + ": " + m.group(1));
        }
    }

    /** Java source without its comments (string and character literals kept). */
    static String withoutComments(String code) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < code.length()) {
            char c = code.charAt(i);
            if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < code.length() && code.charAt(j) != c) j += code.charAt(j) == '\\' ? 2 : 1;
                out.append(code, i, Math.min(code.length(), j + 1));
                i = j + 1;
            } else if (c == '/' && i + 1 < code.length() && code.charAt(i + 1) == '/') {
                while (i < code.length() && code.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < code.length() && code.charAt(i + 1) == '*') {
                int end = code.indexOf("*/", i + 2);
                i = end < 0 ? code.length() : end + 2;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
