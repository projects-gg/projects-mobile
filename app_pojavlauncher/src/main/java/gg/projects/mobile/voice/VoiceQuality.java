package gg.projects.mobile.voice;

import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * The connection's quality (§6.5; the desktop's {@code quality.ts}): 0 good, 1 weak, 2 bad, from what WebRTC already
 * counts (round trip, packets lost both ways, jitter). Worse shows at once; better only after two windows in a row, so
 * the mark does not flicker. Windows with too few packets (silence: DTX sends little) say nothing about loss.
 */
public final class VoiceQuality {
    /** Packets a direction needs in a window before its loss counts. */
    static final int MIN_PACKETS = 50;
    static final double[] WEAK = {300, 0.04, 40};
    static final double[] BAD = {600, 0.12, 80};

    /** One {@code getStats}, its counters as they stand. */
    public static final class Raw {
        @Nullable public Double rttMs;
        public double upSent;
        public double upLost;
        @Nullable public Double upJitter;
        /** Every receiver's counters by stats id: {received, lost, jitter ms or -1}. */
        public final Map<String, double[]> down = new HashMap<>();
    }

    /** The counters this needs from one stats entry ({@code type} and its members, as WebRTC names them). */
    public static void read(Raw raw, String type, Map<String, Object> m) {
        Object kind = m.get("kind");
        if (kind != null && !"audio".equals(kind)) return;
        switch (type) {
            case "candidate-pair": {
                Double rtt = num(m.get("currentRoundTripTime"));
                if (Boolean.TRUE.equals(m.get("nominated")) && "succeeded".equals(String.valueOf(m.get("state"))) && rtt != null) {
                    raw.rttMs = rtt * 1000;
                }
                break;
            }
            case "outbound-rtp":
                raw.upSent += orZero(num(m.get("packetsSent")));
                break;
            case "remote-inbound-rtp": {
                raw.upLost += Math.max(0, orZero(num(m.get("packetsLost"))));
                Double jitter = num(m.get("jitter"));
                if (jitter != null) raw.upJitter = Math.max(raw.upJitter == null ? 0 : raw.upJitter, jitter * 1000);
                if (raw.rttMs == null) {
                    Double rtt = num(m.get("roundTripTime"));
                    if (rtt != null) raw.rttMs = rtt * 1000;
                }
                break;
            }
            case "inbound-rtp": {
                Double jitter = num(m.get("jitter"));
                raw.down.put(String.valueOf(m.get("id")), new double[] {orZero(num(m.get("packetsReceived"))),
                        Math.max(0, orZero(num(m.get("packetsLost")))), jitter == null ? -1 : jitter * 1000});
                break;
            }
            default:
                break;
        }
    }

    /** The grade of the stretch from {@code before} (null: since the start) to {@code now}. */
    public static int grade(@Nullable Raw before, Raw now) {
        Double loss = null;
        double upSent = now.upSent - (before == null ? 0 : before.upSent);
        double upLost = now.upLost - (before == null ? 0 : before.upLost);
        if (upSent >= MIN_PACKETS && upLost >= 0) loss = Math.min(1, upLost / upSent);
        double received = 0;
        double lost = 0;
        Double jitter = now.upJitter;
        for (Map.Entry<String, double[]> e : now.down.entrySet()) {
            double[] old = before == null ? null : before.down.get(e.getKey());
            double got = e.getValue()[0] - (old == null ? 0 : old[0]);
            double missed = e.getValue()[1] - (old == null ? 0 : old[1]);
            if (got < 0 || missed < 0) continue;
            received += got;
            lost += missed;
            if (got > 0 && e.getValue()[2] >= 0) jitter = Math.max(jitter == null ? 0 : jitter, e.getValue()[2]);
        }
        if (received + lost >= MIN_PACKETS) {
            double down = lost / (received + lost);
            loss = loss == null ? down : Math.max(loss, down);
        }
        double rtt = now.rttMs == null ? 0 : now.rttMs;
        double l = loss == null ? 0 : loss;
        double j = jitter == null ? 0 : jitter;
        if (rtt > BAD[0] || l > BAD[1] || j > BAD[2]) return 2;
        if (rtt > WEAK[0] || l > WEAK[1] || j > WEAK[2]) return 1;
        return 0;
    }

    /** The quality shown: worse at once, better after two windows that say so. */
    public static final class Tracker {
        public int quality;
        private int better;

        /** A window's grade; true when the shown quality changed. */
        public boolean push(int next) {
            if (next > quality) {
                quality = next;
                better = 0;
                return true;
            }
            if (next == quality) {
                better = 0;
                return false;
            }
            better++;
            if (better < 2) return false;
            better = 0;
            quality = Math.max(next, quality - 1);
            return true;
        }

        public void reset() {
            quality = 0;
            better = 0;
        }
    }

    @Nullable
    private static Double num(@Nullable Object value) {
        if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            return Double.isNaN(d) || Double.isInfinite(d) ? null : d;
        }
        return null;
    }

    private static double orZero(@Nullable Double value) {
        return value == null ? 0 : value;
    }
}
