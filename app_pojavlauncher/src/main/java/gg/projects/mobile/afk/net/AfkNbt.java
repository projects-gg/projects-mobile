package gg.projects.mobile.afk.net;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.netty.buffer.ByteBuf;

/**
 * A small reader of the network's NBT (a tag type byte, then the tag without a name), enough for text components,
 * chat types and dialogs: compounds become {@code Map<String, Object>}, lists {@code List<Object>}, strings strings,
 * numbers boxed numbers, arrays arrays. Ours instead of MCProtocolLib's, so no Adventure and no NBT library runs on the
 * phone. Bounded like the game's (depth 512, 2 MiB).
 */
public final class AfkNbt {
    private AfkNbt() {}

    static final int END = 0, BYTE = 1, SHORT = 2, INT = 3, LONG = 4, FLOAT = 5, DOUBLE = 6, BYTE_ARRAY = 7,
            STRING = 8, LIST = 9, COMPOUND = 10, INT_ARRAY = 11, LONG_ARRAY = 12;
    private static final int MAX_DEPTH = 512;
    private static final long MAX_BYTES = 2L * 1024 * 1024;

    /** Thrown for a malformed or oversized tag. */
    public static final class NbtException extends RuntimeException {
        NbtException(String message) {
            super(message);
        }
    }

    /** One root tag, or null for an end tag. */
    public static Object read(ByteBuf buf) {
        int type = buf.readUnsignedByte();
        if (type == END) return null;
        return new Reader(buf).value(type, 0);
    }

    /** Skips one root tag. */
    public static void skip(ByteBuf buf) {
        read(buf);
    }

    private static final class Reader {
        private final ByteBuf buf;
        private long budget = MAX_BYTES;

        Reader(ByteBuf buf) {
            this.buf = buf;
        }

        private void spend(long n) {
            budget -= n;
            if (budget < 0) throw new NbtException("NBT too large");
        }

        Object value(int type, int depth) {
            if (depth > MAX_DEPTH) throw new NbtException("NBT too deep");
            switch (type) {
                case BYTE: spend(1); return buf.readByte();
                case SHORT: spend(2); return buf.readShort();
                case INT: spend(4); return buf.readInt();
                case LONG: spend(8); return buf.readLong();
                case FLOAT: spend(4); return buf.readFloat();
                case DOUBLE: spend(8); return buf.readDouble();
                case BYTE_ARRAY: {
                    int n = length(1);
                    byte[] b = new byte[n];
                    buf.readBytes(b);
                    return b;
                }
                case STRING:
                    return string();
                case LIST: {
                    int element = buf.readUnsignedByte();
                    int n = buf.readInt();
                    if (n < 0) throw new NbtException("negative list");
                    spend(5 + 4L * n);
                    if (element == END && n > 0) throw new NbtException("list of end tags");
                    List<Object> list = new ArrayList<>(Math.min(n, 1024));
                    for (int i = 0; i < n; i++) list.add(value(element, depth + 1));
                    return list;
                }
                case COMPOUND: {
                    Map<String, Object> map = new LinkedHashMap<>();
                    while (true) {
                        int t = buf.readUnsignedByte();
                        if (t == END) return map;
                        String name = string();
                        spend(36);
                        map.put(name, value(t, depth + 1));
                    }
                }
                case INT_ARRAY: {
                    int n = length(4);
                    int[] a = new int[n];
                    for (int i = 0; i < n; i++) a[i] = buf.readInt();
                    return a;
                }
                case LONG_ARRAY: {
                    int n = length(8);
                    long[] a = new long[n];
                    for (int i = 0; i < n; i++) a[i] = buf.readLong();
                    return a;
                }
                default:
                    throw new NbtException("unknown tag " + type);
            }
        }

        private int length(int width) {
            int n = buf.readInt();
            if (n < 0 || (long) n * width > buf.readableBytes()) throw new NbtException("bad array length");
            spend(4 + (long) n * width);
            return n;
        }

        /** Modified UTF-8 (Java's DataInput) with an unsigned short length. */
        private String string() {
            int n = buf.readUnsignedShort();
            spend(2 + n);
            byte[] b = new byte[n];
            buf.readBytes(b);
            return modifiedUtf8(b);
        }
    }

    static String modifiedUtf8(byte[] b) {
        // Plain ASCII and ordinary UTF-8 decode the same; only NUL (C0 80) and supplementary characters (surrogate
        // pairs as two 3-byte forms) differ, which a char-by-char decoder handles.
        StringBuilder out = new StringBuilder(b.length);
        int i = 0;
        while (i < b.length) {
            int c = b[i] & 0xff;
            if (c < 0x80) {
                out.append((char) c);
                i++;
            } else if ((c & 0xe0) == 0xc0 && i + 1 < b.length) {
                out.append((char) (((c & 0x1f) << 6) | (b[i + 1] & 0x3f)));
                i += 2;
            } else if ((c & 0xf0) == 0xe0 && i + 2 < b.length) {
                out.append((char) (((c & 0x0f) << 12) | ((b[i + 1] & 0x3f) << 6) | (b[i + 2] & 0x3f)));
                i += 3;
            } else {
                // Not modified UTF-8 (a four-byte form from a lenient server): read the rest as UTF-8.
                out.append(new String(b, i, b.length - i, StandardCharsets.UTF_8));
                break;
            }
        }
        return out.toString();
    }
}
