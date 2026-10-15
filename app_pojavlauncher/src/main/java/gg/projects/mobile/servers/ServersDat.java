package gg.projects.mobile.servers;

import gg.projects.mobile.content.AtomicFiles;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** The multiplayer server list of a game directory ({@code servers.dat}, uncompressed NBT). */
public final class ServersDat {
    private ServersDat() {}

    public static final String FILE = "servers.dat";

    public static final class Entry {
        public final String name;
        public final String address;

        public Entry(String name, String address) {
            this.name = name;
            this.address = address;
        }
    }

    /**
     * Adds servers to the top of {@code servers.dat}, skipping addresses already listed
     * (case-insensitive). A list that cannot be read is never replaced: the call fails instead.
     *
     * @return true when the file changed
     */
    public static boolean addServers(File gameDir, List<Entry> servers) throws IOException {
        File file = new File(gameDir, FILE);
        Nbt.Root root;
        if (file.isFile() && file.length() > 0) {
            root = read(AtomicFiles.read(file, 16L << 20));
        } else {
            root = new Nbt.Root("", new Nbt.Compound());
        }
        Nbt.ListTag list = serverList(root);

        boolean changed = false;
        for (int i = servers.size() - 1; i >= 0; i--) {
            Entry entry = servers.get(i);
            if (contains(list, entry.address)) continue;
            Nbt.Compound server = new Nbt.Compound().putString("name", entry.name).putString("ip", entry.address);
            list.values.add(0, server);
            changed = true;
        }
        if (changed) AtomicFiles.write(file, write(root));
        return changed;
    }

    /** Addresses in the list, top to bottom (for tests and diagnostics). */
    public static List<String> addresses(File gameDir) throws IOException {
        List<String> out = new ArrayList<>();
        File file = new File(gameDir, FILE);
        if (!file.isFile()) return out;
        Nbt.ListTag list = serverList(read(AtomicFiles.read(file, 16L << 20)));
        for (Object value : list.values) {
            String ip = ((Nbt.Compound) value).getString("ip");
            if (ip != null) out.add(ip);
        }
        return out;
    }

    static Nbt.Root read(byte[] bytes) throws IOException {
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x8B) {
            throw new IOException("compressed servers.dat is not supported");
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            return Nbt.readRoot(in);
        } catch (EOFException e) {
            throw new IOException("truncated servers.dat", e);
        } catch (ClassCastException e) {
            throw new IOException("malformed servers.dat", e);
        }
    }

    static byte[] write(Nbt.Root root) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            Nbt.writeRoot(out, root);
        }
        return bytes.toByteArray();
    }

    private static Nbt.ListTag serverList(Nbt.Root root) throws IOException {
        Nbt.Tag tag = root.compound.tags.get("servers");
        if (tag == null) {
            Nbt.ListTag list = new Nbt.ListTag(Nbt.COMPOUND);
            root.compound.tags.put("servers", new Nbt.Tag(Nbt.LIST, list));
            return list;
        }
        if (tag.type != Nbt.LIST) throw new IOException("servers is not a list");
        Nbt.ListTag list = (Nbt.ListTag) tag.value;
        if (list.values.isEmpty()) list.elementType = Nbt.COMPOUND;
        if (list.elementType != Nbt.COMPOUND) throw new IOException("servers is not a list of compounds");
        return list;
    }

    private static boolean contains(Nbt.ListTag list, String address) {
        for (Object value : list.values) {
            String ip = ((Nbt.Compound) value).getString("ip");
            if (ip != null && ip.trim().equalsIgnoreCase(address)) return true;
        }
        return false;
    }
}
