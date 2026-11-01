package gg.projects.mobile.afk.net;

import org.geysermc.mcprotocollib.network.codec.PacketDefinition;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.network.packet.PacketRegistry;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftCodec;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftPacket;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftPacketRegistry;
import org.geysermc.mcprotocollib.protocol.codec.PacketCodec;
import org.geysermc.mcprotocollib.protocol.codec.PacketFactory;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundDisconnectPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundKeepAlivePacket;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundPingPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundResourcePackPushPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundTransferPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundCustomPayloadPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundCodeOfConductPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundFinishConfigurationPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundRegistryDataPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundSelectKnownPacks;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundShowDialogConfigurationPacket;
import org.geysermc.mcprotocollib.protocol.packet.cookie.clientbound.ClientboundCookieRequestPacket;
import org.geysermc.mcprotocollib.protocol.packet.cookie.serverbound.ServerboundCookieResponsePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundDisguisedChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundRespawnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundShowDialogGamePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundStartConfigurationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerCombatKillPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundOpenScreenPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundChunkBatchFinishedPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.clientbound.ClientboundCustomQueryPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.clientbound.ClientboundHelloPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.clientbound.ClientboundLoginCompressionPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.clientbound.ClientboundLoginDisconnectPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.clientbound.ClientboundLoginFinishedPacket;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.netty.buffer.ByteBuf;

/**
 * The phone client's packet codec: MCProtocolLib's ids for its Minecraft version (taken from its own codec, so an
 * update keeps them right), with our readers ({@link AfkPackets}) for what AFK uses and a skip for everything else.
 * Serverbound packets stay MCProtocolLib's, except the two that would load Adventure's Key.
 */
final class AfkCodec {
    private AfkCodec() {}

    /** Ours, keyed by MCProtocolLib's clientbound class. */
    private static final Map<Class<?>, Reader> READERS = new HashMap<>();
    private static final Map<Class<?>, Class<? extends MinecraftPacket>> SERVERBOUND = new HashMap<>();

    interface Reader {
        AfkPackets.In read(ByteBuf in, ProtocolState state);
    }

    static {
        READERS.put(ClientboundLoginDisconnectPacket.class, (in, s) -> new AfkPackets.LoginDisconnect(in));
        READERS.put(ClientboundHelloPacket.class, (in, s) -> new AfkPackets.Hello(in));
        READERS.put(ClientboundLoginFinishedPacket.class, (in, s) -> {
            in.skipBytes(in.readableBytes());
            return new AfkPackets.Terminal(AfkPackets.Terminal.Kind.LOGIN_FINISHED);
        });
        READERS.put(ClientboundLoginCompressionPacket.class, (in, s) -> new AfkPackets.LoginCompression(in));
        READERS.put(ClientboundCustomQueryPacket.class, (in, s) -> new AfkPackets.CustomQuery(in));
        READERS.put(ClientboundCookieRequestPacket.class, (in, s) -> new AfkPackets.CookieRequest(in));
        READERS.put(ClientboundDisconnectPacket.class, (in, s) -> new AfkPackets.Disconnect(in));
        READERS.put(ClientboundKeepAlivePacket.class, (in, s) -> new AfkPackets.KeepAlive(in));
        READERS.put(ClientboundPingPacket.class, (in, s) -> new AfkPackets.Ping(in));
        READERS.put(ClientboundResourcePackPushPacket.class, (in, s) -> new AfkPackets.ResourcePackPush(in));
        READERS.put(ClientboundTransferPacket.class, (in, s) -> new AfkPackets.Transfer(in));
        READERS.put(ClientboundFinishConfigurationPacket.class, (in, s) ->
                new AfkPackets.Terminal(AfkPackets.Terminal.Kind.FINISH_CONFIGURATION));
        READERS.put(ClientboundRegistryDataPacket.class, (in, s) -> new AfkPackets.RegistryData(in));
        READERS.put(ClientboundSelectKnownPacks.class, (in, s) -> new AfkPackets.SelectKnownPacks(in));
        READERS.put(ClientboundShowDialogConfigurationPacket.class, (in, s) -> new AfkPackets.ShowDialog(in, false));
        READERS.put(ClientboundShowDialogGamePacket.class, (in, s) -> new AfkPackets.ShowDialog(in, true));
        READERS.put(ClientboundCodeOfConductPacket.class, (in, s) -> new AfkPackets.CodeOfConduct(in));
        READERS.put(ClientboundLoginPacket.class, (in, s) -> new AfkPackets.Login(in));
        READERS.put(ClientboundRespawnPacket.class, (in, s) -> new AfkPackets.Respawn(in));
        READERS.put(ClientboundPlayerChatPacket.class, (in, s) -> new AfkPackets.PlayerChat(in));
        READERS.put(ClientboundDisguisedChatPacket.class, (in, s) -> new AfkPackets.DisguisedChat(in));
        READERS.put(ClientboundSystemChatPacket.class, (in, s) -> new AfkPackets.SystemChat(in));
        READERS.put(ClientboundStartConfigurationPacket.class, (in, s) ->
                new AfkPackets.Terminal(AfkPackets.Terminal.Kind.START_CONFIGURATION));
        READERS.put(ClientboundPlayerCombatKillPacket.class, (in, s) -> new AfkPackets.CombatKill(in));
        READERS.put(ClientboundPlayerPositionPacket.class, (in, s) -> new AfkPackets.PlayerPosition(in));
        READERS.put(ClientboundChunkBatchFinishedPacket.class, (in, s) -> new AfkPackets.ChunkBatchFinished(in));
        READERS.put(ClientboundOpenScreenPacket.class, (in, s) -> new AfkPackets.OpenScreen(in));
        SERVERBOUND.put(ServerboundCookieResponsePacket.class, AfkPackets.CookieResponse.class);
        SERVERBOUND.put(ServerboundCustomPayloadPacket.class, AfkPackets.Brand.class);
    }

    /** The MCProtocolLib clientbound classes we read (the tests check each is in its codec). */
    static List<Class<?>> readClasses() {
        return new ArrayList<>(READERS.keySet());
    }

    static final PacketCodec CODEC = build();

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static PacketCodec build() {
        PacketCodec mc = MinecraftCodec.CODEC;
        PacketCodec.Builder builder = PacketCodec.builder()
                .protocolVersion(mc.getProtocolVersion())
                .minecraftVersion(mc.getMinecraftVersion());
        for (final ProtocolState state : ProtocolState.values()) {
            PacketRegistry source = mc.getCodec(state);
            if (source == null) continue;
            MinecraftPacketRegistry ours = MinecraftPacketRegistry.builder();
            List<PacketDefinition<? extends Packet>> in = sorted(source.getAllClientboundPackets());
            for (int i = 0; i < in.size(); i++) {
                PacketDefinition<? extends Packet> def = in.get(i);
                if (def.getId() != i) throw new IllegalStateException("clientbound ids not contiguous in " + state);
                final Reader reader = READERS.get(def.getPacketClass());
                if (reader != null) {
                    ours.registerClientboundPacket((Class) AfkPackets.In.class, (PacketFactory) buf -> reader.read(buf, state));
                } else {
                    final AfkPackets.Skip skip = new AfkPackets.Skip(i);
                    ours.registerClientboundPacket((Class) AfkPackets.Skip.class, (PacketFactory) buf -> {
                        buf.skipBytes(buf.readableBytes());
                        return skip;
                    });
                }
            }
            List<PacketDefinition<? extends Packet>> out = sorted(source.getAllServerboundPackets());
            for (int i = 0; i < out.size(); i++) {
                PacketDefinition<? extends Packet> def = out.get(i);
                if (def.getId() != i) throw new IllegalStateException("serverbound ids not contiguous in " + state);
                Class<?> mine = SERVERBOUND.get(def.getPacketClass());
                ours.registerServerboundPacket((Class) (mine != null ? mine : def.getPacketClass()), (PacketFactory) buf -> {
                    throw new UnsupportedOperationException("serverbound");
                });
            }
            builder.state(state, ours);
        }
        return builder.build();
    }

    private static List<PacketDefinition<? extends Packet>> sorted(java.util.Collection<PacketDefinition<? extends Packet>> all) {
        List<PacketDefinition<? extends Packet>> list = new ArrayList<>(all);
        Collections.sort(list, new Comparator<PacketDefinition<? extends Packet>>() {
            @Override
            public int compare(PacketDefinition<? extends Packet> a, PacketDefinition<? extends Packet> b) {
                return Integer.compare(a.getId(), b.getId());
            }
        });
        return list;
    }
}
