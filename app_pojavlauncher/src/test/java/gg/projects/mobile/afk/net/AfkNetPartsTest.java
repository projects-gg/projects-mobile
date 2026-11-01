package gg.projects.mobile.afk.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.geysermc.mcprotocollib.network.packet.PacketRegistry;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftCodec;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftTypes;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundCustomPayloadPacket;
import org.geysermc.mcprotocollib.protocol.packet.cookie.serverbound.ServerboundCookieResponsePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundAddEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerChatPacket;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/** Our codec against MCProtocolLib's ids, our packet readers, the NBT reader and the SRV messages. */
public class AfkNetPartsTest {
    @Test
    public void theCodecKeepsMinecraftsIdsAndSwapsOnlyOurPackets() {
        assertEquals(MinecraftCodec.CODEC.getProtocolVersion(), AfkCodec.CODEC.getProtocolVersion());
        for (Class<?> c : AfkCodec.readClasses()) {
            boolean found = false;
            for (ProtocolState s : ProtocolState.values()) {
                PacketRegistry r = MinecraftCodec.CODEC.getCodec(s);
                try {
                    r.getClientboundId((Class) c);
                    found = true;
                } catch (IllegalArgumentException ignored) {
                    // Not in this state.
                }
            }
            assertTrue(c.getName(), found);
        }
        for (ProtocolState s : new ProtocolState[]{ProtocolState.CONFIGURATION, ProtocolState.GAME}) {
            assertEquals(MinecraftCodec.CODEC.getCodec(s).getServerboundId(ServerboundCustomPayloadPacket.class),
                    AfkCodec.CODEC.getCodec(s).getServerboundId(AfkPackets.Brand.class));
        }
        assertEquals(MinecraftCodec.CODEC.getCodec(ProtocolState.LOGIN).getServerboundId(ServerboundCookieResponsePacket.class),
                AfkCodec.CODEC.getCodec(ProtocolState.LOGIN).getServerboundId(AfkPackets.CookieResponse.class));
        // Anything else is skipped whole.
        int addEntity = MinecraftCodec.CODEC.getCodec(ProtocolState.GAME).getClientboundId(ClientboundAddEntityPacket.class);
        ByteBuf junk = Unpooled.wrappedBuffer(new byte[]{1, 2, 3, 4, 5});
        assertTrue(AfkCodec.CODEC.getCodec(ProtocolState.GAME).createClientboundPacket(addEntity, junk) instanceof AfkPackets.Skip);
        assertEquals(0, junk.readableBytes());
    }

    // ---- a small NBT writer for the tests ----

    private static final class Nbt {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Nbt b(int v) {
            out.write(v);
            return this;
        }

        Nbt str(String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            out.write(b.length >> 8);
            out.write(b.length & 0xff);
            out.write(b, 0, b.length);
            return this;
        }

        Nbt tag(int type, String name) {
            return b(type).str(name);
        }

        byte[] bytes() {
            return out.toByteArray();
        }
    }

    /** {@code {text:"…", color:"gold", bold:1b}} as network NBT. */
    private static byte[] goldText(String text) {
        return new Nbt().b(AfkNbt.COMPOUND)
                .tag(AfkNbt.STRING, "text").str(text)
                .tag(AfkNbt.STRING, "color").str("gold")
                .tag(AfkNbt.BYTE, "bold").b(1)
                .b(AfkNbt.END).bytes();
    }

    @Test
    @SuppressWarnings("unchecked")
    public void nbtReadsCompoundsListsAndStrings() {
        ByteBuf buf = Unpooled.wrappedBuffer(goldText("Selam"));
        Map<String, Object> m = (Map<String, Object>) AfkNbt.read(buf);
        assertEquals("Selam", m.get("text"));
        assertEquals((byte) 1, m.get("bold"));
        assertEquals(0, buf.readableBytes());
        byte[] list = new Nbt().b(AfkNbt.LIST).b(AfkNbt.STRING).b(0).b(0).b(0).b(2).str("a").str("ş").bytes();
        assertEquals(java.util.Arrays.asList("a", "ş"), AfkNbt.read(Unpooled.wrappedBuffer(list)));
        assertNull(AfkNbt.read(Unpooled.wrappedBuffer(new byte[]{0})));
    }

    @Test
    public void playerChatIsReadInVanillasOrder() {
        ByteBuf buf = Unpooled.buffer();
        MinecraftTypes.writeVarInt(buf, 3);
        MinecraftTypes.writeUUID(buf, UUID.randomUUID());
        MinecraftTypes.writeVarInt(buf, 0);
        buf.writeBoolean(false);
        MinecraftTypes.writeString(buf, "selam herkes");
        buf.writeLong(1L);
        buf.writeLong(2L);
        MinecraftTypes.writeVarInt(buf, 1);
        MinecraftTypes.writeVarInt(buf, 5);
        buf.writeBoolean(false);
        MinecraftTypes.writeVarInt(buf, 0);
        MinecraftTypes.writeVarInt(buf, 1);
        buf.writeBytes(goldText("Ember_77"));
        buf.writeBoolean(false);
        int id = MinecraftCodec.CODEC.getCodec(ProtocolState.GAME).getClientboundId(ClientboundPlayerChatPacket.class);
        AfkPackets.PlayerChat chat = (AfkPackets.PlayerChat) AfkCodec.CODEC.getCodec(ProtocolState.GAME).createClientboundPacket(id, buf);
        assertEquals("selam herkes", chat.content);
        assertEquals(0, chat.chatType.registryId);
        assertEquals("Ember_77", AfkMcWire.playerName(gg.projects.mobile.afk.core.AfkComponents.render(chat.name, false).text));
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void chatTypeDecorationsAndNames() {
        Map<String, Object> entry = new java.util.LinkedHashMap<>();
        Map<String, Object> chat = new java.util.LinkedHashMap<>();
        chat.put("translation_key", "commands.message.display.incoming");
        chat.put("parameters", java.util.Arrays.<Object>asList("sender", "content"));
        entry.put("chat", chat);
        AfkPackets.ChatTypeRef ref = AfkPackets.decorationOf(entry);
        assertEquals("commands.message.display.incoming", ref.translationKey);
        assertEquals(java.util.Arrays.asList("sender", "content"), ref.parameters);
        assertEquals("SleepyFox_42", AfkMcWire.playerName("[VIP] SleepyFox_42"));
        assertNull(AfkMcWire.playerName("[--] »"));
        assertEquals("Admin", AfkMcWire.playerName("[Admin]"));
        assertTrue(AfkMcWire.versionRefusal("Outdated client! Please use 1.21.4", java.util.Collections.<String>emptyList()));
        assertTrue(AfkMcWire.versionRefusal(null, java.util.Collections.singletonList("multiplayer.disconnect.incompatible")));
    }

    @Test
    public void srvQueriesAndAnswers() {
        byte[] q = AfkDns.query(0x1234, "_minecraft._tcp.play.example.com", AfkDns.TYPE_SRV);
        assertEquals(0x12, q[0] & 0xff);
        assertEquals(0x34, q[1] & 0xff);
        // An answer: header, the question, one SRV record whose name points back at the question (compression).
        ByteArrayOutputStream a = new ByteArrayOutputStream();
        a.write(0x12);
        a.write(0x34);
        a.write(0x81);
        a.write(0x80);
        a.write(0);
        a.write(1);
        a.write(0);
        a.write(2);
        for (int i = 0; i < 4; i++) a.write(0);
        a.write(q, 12, q.length - 12);
        for (int[] rec : new int[][]{{10, 5, 25570}, {5, 1, 25565}}) {
            a.write(0xc0);
            a.write(12);
            a.write(0);
            a.write(33);
            a.write(0);
            a.write(1);
            for (int i = 0; i < 4; i++) a.write(0);
            byte[] target = new Nbt().b(2).out.toByteArray();
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            data.write(rec[0] >> 8);
            data.write(rec[0] & 0xff);
            data.write(rec[1] >> 8);
            data.write(rec[1] & 0xff);
            data.write(rec[2] >> 8);
            data.write(rec[2] & 0xff);
            for (String label : new String[]{"mc", "example", "com"}) {
                data.write(label.length());
                data.write(label.getBytes(StandardCharsets.US_ASCII), 0, label.length());
            }
            data.write(0);
            byte[] d = data.toByteArray();
            a.write(d.length >> 8);
            a.write(d.length & 0xff);
            a.write(d, 0, d.length);
            assertEquals(1, target.length);
        }
        List<AfkDns.Srv> records = AfkDns.parseSrv(a.toByteArray(), 0x1234);
        assertEquals(2, records.size());
        AfkDns.Srv best = AfkDns.pick(records);
        assertEquals(25565, best.port);
        assertEquals("mc.example.com", best.target);
        assertTrue(AfkDns.parseSrv(a.toByteArray(), 0x9999).isEmpty());
        assertTrue(AfkDns.parseSrv(new byte[]{1, 2, 3}, -1).isEmpty());
        assertTrue(AfkResolver.isIpLiteral("192.168.1.1"));
        assertTrue(AfkResolver.isIpLiteral("2001:db8::1"));
        assertTrue(!AfkResolver.isIpLiteral("play.example.com"));
    }
}
