package gg.projects.mobile.voice;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Opus as voice chat sends it (design §6.2; the desktop's {@code sdp.ts}): mono, a capped bit rate, silence suppression
 * (DTX) and in-band error correction. The sender reads these from the other side's description, so the engine writes them
 * into every description it receives (the SFU's answer and its later offers) before applying it.
 */
public final class VoiceSdp {
    private VoiceSdp() {}

    private static final Pattern RTPMAP = Pattern.compile("^a=rtpmap:(\\d+) opus/48000", Pattern.CASE_INSENSITIVE);

    /** {@code sdp} with every Opus format's parameters set (other parameters and lines kept). */
    public static String tuneOpus(String sdp, int bitrate, boolean dtx, boolean fec) {
        String newline = sdp.contains("\r\n") ? "\r\n" : "\n";
        List<String> out = new ArrayList<>();
        List<String> section = new ArrayList<>();
        for (String line : sdp.split(newline, -1)) {
            if (line.startsWith("m=")) {
                out.addAll(tuneSection(section, bitrate, dtx, fec));
                section = new ArrayList<>();
            }
            section.add(line);
        }
        out.addAll(tuneSection(section, bitrate, dtx, fec));
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < out.size(); i++) {
            if (i > 0) text.append(newline);
            text.append(out.get(i));
        }
        return text.toString();
    }

    private static List<String> tuneSection(List<String> lines, int bitrate, boolean dtx, boolean fec) {
        List<String> payloads = new ArrayList<>();
        for (String line : lines) {
            Matcher m = RTPMAP.matcher(line);
            if (m.find()) payloads.add(m.group(1));
        }
        Map<String, String> wanted = new LinkedHashMap<>();
        wanted.put("stereo", "0");
        wanted.put("sprop-stereo", "0");
        wanted.put("maxaveragebitrate", String.valueOf(bitrate));
        wanted.put("usedtx", dtx ? "1" : "0");
        wanted.put("useinbandfec", fec ? "1" : "0");
        for (String payload : payloads) {
            String prefix = "a=fmtp:" + payload + " ";
            int at = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).startsWith(prefix)) {
                    at = i;
                    break;
                }
            }
            Map<String, String> params = new LinkedHashMap<>();
            if (at >= 0) {
                for (String pair : lines.get(at).substring(prefix.length()).split(";")) {
                    String[] kv = pair.trim().split("=", 2);
                    if (!kv[0].isEmpty()) params.put(kv[0], kv.length > 1 ? kv[1] : "");
                }
            }
            params.putAll(wanted);
            StringBuilder fmtp = new StringBuilder(prefix);
            boolean first = true;
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (!first) fmtp.append(';');
                first = false;
                fmtp.append(e.getKey()).append('=').append(e.getValue());
            }
            if (at >= 0) {
                lines.set(at, fmtp.toString());
            } else {
                int rtpmap = -1;
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).startsWith("a=rtpmap:" + payload + " ")) {
                        rtpmap = i;
                        break;
                    }
                }
                lines.add(rtpmap + 1, fmtp.toString());
            }
        }
        return lines;
    }
}
