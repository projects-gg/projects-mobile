package gg.projects.mobile.afk.net;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The DNS messages of a Minecraft server's SRV lookup ({@code _minecraft._tcp.<host>}): the query and the answer's
 * SRV records (name compression handled). MCProtocolLib's own lookup needs JNDI, which Android lacks. Pure Java.
 */
public final class AfkDns {
    private AfkDns() {}

    public static final int TYPE_SRV = 33;
    public static final int CLASS_IN = 1;

    public static final class Srv {
        public final int priority;
        public final int weight;
        public final int port;
        public final String target;

        public Srv(int priority, int weight, int port, String target) {
            this.priority = priority;
            this.weight = weight;
            this.port = port;
            this.target = target;
        }

        @Override
        public String toString() {
            return priority + " " + weight + " " + port + " " + target;
        }
    }

    public static String srvName(String host) {
        return "_minecraft._tcp." + host;
    }

    /** A recursive query for {@code name} of {@code type}. */
    public static byte[] query(int id, String name, int type) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64);
        out.write((id >> 8) & 0xff);
        out.write(id & 0xff);
        out.write(0x01); // RD
        out.write(0x00);
        out.write(0);
        out.write(1); // QDCOUNT
        for (int i = 0; i < 6; i++) out.write(0);
        for (String label : name.split("\\.")) {
            if (label.isEmpty()) continue;
            byte[] b = label.getBytes(StandardCharsets.US_ASCII);
            out.write(Math.min(63, b.length));
            out.write(b, 0, Math.min(63, b.length));
        }
        out.write(0);
        out.write((type >> 8) & 0xff);
        out.write(type & 0xff);
        out.write(0);
        out.write(CLASS_IN);
        return out.toByteArray();
    }

    /**
     * The SRV records of an answer ({@code expectId} &lt; 0: any id). Empty when there are none or the answer is broken.
     */
    public static List<Srv> parseSrv(byte[] msg, int expectId) {
        List<Srv> out = new ArrayList<>();
        try {
            if (msg == null || msg.length < 12) return out;
            int id = u16(msg, 0);
            if (expectId >= 0 && id != expectId) return out;
            int flags = u16(msg, 2);
            if ((flags & 0x8000) == 0 || (flags & 0x000f) != 0) return out; // not an answer, or an error code
            int qd = u16(msg, 4);
            int an = u16(msg, 6);
            int p = 12;
            for (int i = 0; i < qd; i++) {
                p = skipName(msg, p) + 4;
            }
            for (int i = 0; i < an; i++) {
                p = skipName(msg, p);
                int type = u16(msg, p);
                int len = u16(msg, p + 8);
                int data = p + 10;
                if (data + len > msg.length) break;
                if (type == TYPE_SRV && len >= 7) {
                    out.add(new Srv(u16(msg, data), u16(msg, data + 2), u16(msg, data + 4), name(msg, data + 6)));
                }
                p = data + len;
            }
        } catch (RuntimeException e) {
            return Collections.emptyList();
        }
        return out;
    }

    /** The record to use: the lowest priority, then the highest weight (null when none). */
    public static Srv pick(List<Srv> records) {
        Srv best = null;
        for (Srv r : records) {
            if (r.target.isEmpty() || r.target.equals(".")) continue;
            if (best == null || r.priority < best.priority || (r.priority == best.priority && r.weight > best.weight)) best = r;
        }
        return best;
    }

    private static int u16(byte[] b, int at) {
        if (at + 1 >= b.length) throw new IndexOutOfBoundsException();
        return ((b[at] & 0xff) << 8) | (b[at + 1] & 0xff);
    }

    private static int skipName(byte[] b, int p) {
        int guard = 0;
        while (true) {
            if (p >= b.length || ++guard > 128) throw new IndexOutOfBoundsException();
            int len = b[p] & 0xff;
            if (len == 0) return p + 1;
            if ((len & 0xc0) == 0xc0) return p + 2;
            p += 1 + len;
        }
    }

    private static String name(byte[] b, int p) {
        StringBuilder out = new StringBuilder();
        int guard = 0;
        while (true) {
            if (p >= b.length || ++guard > 128) throw new IndexOutOfBoundsException();
            int len = b[p] & 0xff;
            if (len == 0) break;
            if ((len & 0xc0) == 0xc0) {
                p = ((len & 0x3f) << 8) | (b[p + 1] & 0xff);
                continue;
            }
            if (out.length() > 0) out.append('.');
            out.append(new String(b, p + 1, len, StandardCharsets.US_ASCII));
            p += 1 + len;
        }
        return out.toString().toLowerCase(java.util.Locale.ROOT);
    }
}
