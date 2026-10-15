package gg.projects.mobile.servers;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Just enough uncompressed NBT to round-trip {@code servers.dat} without losing fields the
 * game (or another launcher) wrote. Values: Byte, Short, Integer, Long, Float, Double, byte[],
 * String, {@link ListTag}, {@link Compound}, int[], long[].
 */
public final class Nbt {
    private Nbt() {}

    public static final byte END = 0, BYTE = 1, SHORT = 2, INT = 3, LONG = 4, FLOAT = 5, DOUBLE = 6,
            BYTE_ARRAY = 7, STRING = 8, LIST = 9, COMPOUND = 10, INT_ARRAY = 11, LONG_ARRAY = 12;

    private static final int MAX_DEPTH = 512;
    private static final int MAX_ARRAY = 16 * 1024 * 1024;

    public static final class Tag {
        public final byte type;
        public final Object value;

        public Tag(byte type, Object value) {
            this.type = type;
            this.value = value;
        }
    }

    public static final class ListTag {
        public byte elementType;
        public final List<Object> values = new ArrayList<>();

        public ListTag(byte elementType) {
            this.elementType = elementType;
        }
    }

    /** Insertion-ordered named tags. */
    public static final class Compound {
        public final Map<String, Tag> tags = new LinkedHashMap<>();

        public Compound putString(String name, String value) {
            tags.put(name, new Tag(STRING, value));
            return this;
        }

        public String getString(String name) {
            Tag tag = tags.get(name);
            return tag != null && tag.type == STRING ? (String) tag.value : null;
        }
    }

    public static final class Root {
        public final String name;
        public final Compound compound;

        public Root(String name, Compound compound) {
            this.name = name;
            this.compound = compound;
        }
    }

    public static Root readRoot(DataInput in) throws IOException {
        byte type = in.readByte();
        if (type != COMPOUND) throw new IOException("NBT root is not a compound (type " + type + ")");
        String name = in.readUTF();
        return new Root(name, (Compound) readPayload(in, COMPOUND, 0));
    }

    public static void writeRoot(DataOutput out, Root root) throws IOException {
        out.writeByte(COMPOUND);
        out.writeUTF(root.name);
        writePayload(out, COMPOUND, root.compound, 0);
    }

    private static int readLength(DataInput in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_ARRAY) throw new IOException("bad NBT length " + length);
        return length;
    }

    private static Object readPayload(DataInput in, byte type, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("NBT nested too deeply");
        switch (type) {
            case BYTE: return in.readByte();
            case SHORT: return in.readShort();
            case INT: return in.readInt();
            case LONG: return in.readLong();
            case FLOAT: return in.readFloat();
            case DOUBLE: return in.readDouble();
            case BYTE_ARRAY: {
                byte[] bytes = new byte[readLength(in)];
                in.readFully(bytes);
                return bytes;
            }
            case STRING: return in.readUTF();
            case LIST: {
                byte elementType = in.readByte();
                int length = readLength(in);
                ListTag list = new ListTag(elementType);
                if (length > 0 && (elementType <= END || elementType > LONG_ARRAY)) {
                    throw new IOException("bad NBT list element type " + elementType);
                }
                for (int i = 0; i < length; i++) list.values.add(readPayload(in, elementType, depth + 1));
                return list;
            }
            case COMPOUND: {
                Compound compound = new Compound();
                while (true) {
                    byte tagType = in.readByte();
                    if (tagType == END) return compound;
                    if (tagType < BYTE || tagType > LONG_ARRAY) throw new IOException("bad NBT tag type " + tagType);
                    String name = in.readUTF();
                    compound.tags.put(name, new Tag(tagType, readPayload(in, tagType, depth + 1)));
                }
            }
            case INT_ARRAY: {
                int[] values = new int[readLength(in)];
                for (int i = 0; i < values.length; i++) values[i] = in.readInt();
                return values;
            }
            case LONG_ARRAY: {
                long[] values = new long[readLength(in)];
                for (int i = 0; i < values.length; i++) values[i] = in.readLong();
                return values;
            }
            default:
                throw new IOException("bad NBT tag type " + type);
        }
    }

    private static void writePayload(DataOutput out, byte type, Object value, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("NBT nested too deeply");
        switch (type) {
            case BYTE: out.writeByte((Byte) value); break;
            case SHORT: out.writeShort((Short) value); break;
            case INT: out.writeInt((Integer) value); break;
            case LONG: out.writeLong((Long) value); break;
            case FLOAT: out.writeFloat((Float) value); break;
            case DOUBLE: out.writeDouble((Double) value); break;
            case BYTE_ARRAY: {
                byte[] bytes = (byte[]) value;
                out.writeInt(bytes.length);
                out.write(bytes);
                break;
            }
            case STRING: out.writeUTF((String) value); break;
            case LIST: {
                ListTag list = (ListTag) value;
                out.writeByte(list.elementType);
                out.writeInt(list.values.size());
                for (Object element : list.values) writePayload(out, list.elementType, element, depth + 1);
                break;
            }
            case COMPOUND: {
                for (Map.Entry<String, Tag> entry : ((Compound) value).tags.entrySet()) {
                    out.writeByte(entry.getValue().type);
                    out.writeUTF(entry.getKey());
                    writePayload(out, entry.getValue().type, entry.getValue().value, depth + 1);
                }
                out.writeByte(END);
                break;
            }
            case INT_ARRAY: {
                int[] values = (int[]) value;
                out.writeInt(values.length);
                for (int v : values) out.writeInt(v);
                break;
            }
            case LONG_ARRAY: {
                long[] values = (long[]) value;
                out.writeInt(values.length);
                for (long v : values) out.writeLong(v);
                break;
            }
            default:
                throw new IOException("bad NBT tag type " + type);
        }
    }
}
