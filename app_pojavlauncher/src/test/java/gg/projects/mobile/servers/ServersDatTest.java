package gg.projects.mobile.servers;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

public class ServersDatTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final ServersDat.Entry PROJECTS = new ServersDat.Entry("Projects", "play.projects.gg");

    @Test
    public void createsTheListWhenMissing() throws Exception {
        File game = tmp.newFolder();
        assertTrue(ServersDat.addServers(game, Collections.singletonList(PROJECTS)));
        assertEquals(Collections.singletonList("play.projects.gg"), ServersDat.addresses(game));
        // Valid uncompressed NBT: root compound "" with a list of compounds named "servers".
        byte[] bytes = Files.readAllBytes(new File(game, "servers.dat").toPath());
        assertEquals(Nbt.COMPOUND, bytes[0]);
        assertFalse(ServersDat.addServers(game, Collections.singletonList(new ServersDat.Entry("x", "PLAY.Projects.GG"))));
    }

    /** A list written the way the game writes it, with fields we do not model. */
    private static byte[] gameWrittenList() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(Nbt.COMPOUND);
        out.writeUTF("");
        out.writeByte(Nbt.LIST);
        out.writeUTF("servers");
        out.writeByte(Nbt.COMPOUND);
        out.writeInt(2);
        // server 1
        out.writeByte(Nbt.STRING); out.writeUTF("ip"); out.writeUTF("mc.hypixel.net");
        out.writeByte(Nbt.STRING); out.writeUTF("name"); out.writeUTF("Hypixel");
        out.writeByte(Nbt.STRING); out.writeUTF("icon"); out.writeUTF("iVBORw0KGgo=");
        out.writeByte(Nbt.BYTE); out.writeUTF("acceptTextures"); out.writeByte(1);
        out.writeByte(Nbt.END);
        // server 2
        out.writeByte(Nbt.STRING); out.writeUTF("ip"); out.writeUTF("localhost:25566");
        out.writeByte(Nbt.STRING); out.writeUTF("name"); out.writeUTF("Yerel sunucu ğüşiöç");
        out.writeByte(Nbt.INT_ARRAY); out.writeUTF("ints"); out.writeInt(2); out.writeInt(7); out.writeInt(-1);
        out.writeByte(Nbt.LONG_ARRAY); out.writeUTF("longs"); out.writeInt(1); out.writeLong(Long.MAX_VALUE);
        out.writeByte(Nbt.END);
        // unrelated root field
        out.writeByte(Nbt.DOUBLE); out.writeUTF("version"); out.writeDouble(1.5);
        out.writeByte(Nbt.END);
        out.flush();
        return bytes.toByteArray();
    }

    @Test
    public void addsOnTopAndPreservesEverythingElse() throws Exception {
        File game = tmp.newFolder();
        byte[] original = gameWrittenList();
        try (FileOutputStream out = new FileOutputStream(new File(game, "servers.dat"))) {
            out.write(original);
        }
        assertTrue(ServersDat.addServers(game, Arrays.asList(PROJECTS, new ServersDat.Entry("Hypixel again", "MC.HYPIXEL.NET"))));
        assertEquals(Arrays.asList("play.projects.gg", "mc.hypixel.net", "localhost:25566"), ServersDat.addresses(game));

        Nbt.Root root = ServersDat.read(Files.readAllBytes(new File(game, "servers.dat").toPath()));
        Nbt.ListTag servers = (Nbt.ListTag) root.compound.tags.get("servers").value;
        Nbt.Compound hypixel = (Nbt.Compound) servers.values.get(1);
        assertEquals("iVBORw0KGgo=", hypixel.getString("icon"));
        assertEquals((byte) 1, hypixel.tags.get("acceptTextures").value);
        Nbt.Compound local = (Nbt.Compound) servers.values.get(2);
        assertEquals("Yerel sunucu ğüşiöç", local.getString("name"));
        assertArrayEquals(new int[]{7, -1}, (int[]) local.tags.get("ints").value);
        assertEquals(1.5, (Double) root.compound.tags.get("version").value, 0.0);

        // Removing our entry again would give back the original bytes: the round trip is lossless.
        servers.values.remove(0);
        assertArrayEquals(original, ServersDat.write(root));
    }

    @Test
    public void unreadableListIsNeverReplaced() throws Exception {
        File game = tmp.newFolder();
        File file = new File(game, "servers.dat");
        byte[] garbage = {Nbt.COMPOUND, 0, 0, Nbt.LIST, 0, 7, 's', 'e', 'r', 'v', 'e', 'r', 's', Nbt.COMPOUND, 0, 0, 0, 5};
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(garbage);
        }
        try {
            ServersDat.addServers(game, Collections.singletonList(PROJECTS));
            fail("accepted a truncated servers.dat");
        } catch (IOException expected) {
            // expected
        }
        assertArrayEquals(garbage, Files.readAllBytes(file.toPath()));

        byte[] gzip = {0x1F, (byte) 0x8B, 8, 0};
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(gzip);
        }
        try {
            ServersDat.addServers(game, Collections.singletonList(PROJECTS));
            fail("accepted a compressed servers.dat");
        } catch (IOException expected) {
            // expected
        }
        assertArrayEquals(gzip, Files.readAllBytes(file.toPath()));
    }
}
