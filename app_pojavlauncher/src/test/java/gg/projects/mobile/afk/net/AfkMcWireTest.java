package gg.projects.mobile.afk.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.server.ServerAdapter;
import org.geysermc.mcprotocollib.network.event.server.SessionAddedEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.network.server.NetworkServer;
import org.geysermc.mcprotocollib.protocol.MinecraftConstants;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.data.game.ClientCommand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerSpawnInfo;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundClientInformationPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundCustomPayloadPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerCombatKillPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundSetHealthPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundClientCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundClientTickEndPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.serverbound.ServerboundHelloPacket;
import org.junit.After;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import gg.projects.mobile.afk.api.AfkLine;
import gg.projects.mobile.afk.core.AfkDrop;
import gg.projects.mobile.afk.core.AfkText;
import gg.projects.mobile.afk.core.AfkWire;

/**
 * The phone's client end to end on the JVM against MCProtocolLib's own server (same Minecraft version): handshake,
 * login with encryption but no authentication, compression, configuration with the full registries, the join, a
 * chat line in colour, commands and chat going out, and the server's kick coming back as a drop.
 */
public class AfkMcWireTest {
    private NetworkServer server;
    private final ExecutorService engine = Executors.newSingleThreadExecutor();
    private AfkMcWire wire;
    private final BlockingQueue<String> events = new LinkedBlockingQueue<>();
    private final BlockingQueue<AfkDrop> drops = new LinkedBlockingQueue<>();
    private final BlockingQueue<AfkText> lines = new LinkedBlockingQueue<>();
    private final BlockingQueue<Packet> received = new LinkedBlockingQueue<>();
    private volatile Session serverSession;

    private static final AfkMcWire.Log LOG = new AfkMcWire.Log() {
        @Override
        public void info(String message) {
        }

        @Override
        public void warn(String message, Throwable error) {
            error.printStackTrace();
        }
    };

    private final AfkWire.Events recorder = new AfkWire.Events() {
        @Override
        public void loggingIn() {
            events.add("loggingIn");
        }

        @Override
        public void configuring() {
            events.add("configuring");
        }

        @Override
        public void joined() {
            events.add("joined");
        }

        @Override
        public void line(AfkLine.Kind kind, AfkText text, String sender) {
            lines.add(text);
        }

        @Override
        public void died() {
            events.add("died");
        }

        @Override
        public void window(String title) {
            events.add("window " + title);
        }

        @Override
        public void transfer(String host, int port) {
            events.add("transfer " + host + ":" + port);
        }

        @Override
        public void ended(AfkDrop drop) {
            drops.add(drop);
        }
    };

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private int startServer(boolean authenticate) throws Exception {
        int port = freePort();
        server = new NetworkServer(new InetSocketAddress("127.0.0.1", port), MinecraftProtocol::new);
        server.setGlobalFlag(MinecraftConstants.ENCRYPT_CONNECTION, true);
        server.setGlobalFlag(MinecraftConstants.SHOULD_AUTHENTICATE, authenticate);
        server.setGlobalFlag(MinecraftConstants.SERVER_COMPRESSION_THRESHOLD, 64);
        server.setGlobalFlag(MinecraftConstants.SERVER_LOGIN_HANDLER_KEY, session -> {
            serverSession = session;
            session.send(new ClientboundLoginPacket(42, false, new Key[]{Key.key("minecraft:overworld")}, 20, 2, 2, false,
                    true, false, new PlayerSpawnInfo(0, Key.key("minecraft:overworld"), 0L, GameMode.SURVIVAL,
                    GameMode.SURVIVAL, false, false, null, 0, 63), false, false));
            session.send(new ClientboundSystemChatPacket(Component.text("Hoş geldin, ")
                    .append(Component.text("SleepyFox_42", NamedTextColor.GOLD)), false));
        });
        server.addListener(new ServerAdapter() {
            @Override
            public void sessionAdded(SessionAddedEvent event) {
                event.getSession().addListener(new SessionAdapter() {
                    @Override
                    public void packetReceived(Session session, Packet packet) {
                        received.add(packet);
                    }
                });
            }
        });
        server.bind(true);
        wire = new AfkMcWire(engine, AfkResolver.PLAIN, false, LOG);
        return port;
    }

    private <T> T take(BlockingQueue<T> queue) throws InterruptedException {
        T value = queue.poll(20, TimeUnit.SECONDS);
        assertNotNull("timed out", value);
        return value;
    }

    @SuppressWarnings("unchecked")
    private <T extends Packet> T receivedOf(Class<T> type) throws InterruptedException {
        long end = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < end) {
            Packet p = received.poll(1, TimeUnit.SECONDS);
            if (type.isInstance(p)) return (T) p;
        }
        throw new AssertionError("no " + type.getSimpleName());
    }

    @After
    public void tearDown() {
        if (wire != null) wire.shutdown();
        if (server != null) server.close(true);
        engine.shutdownNow();
    }

    @Test
    public void joinsChatsAndHearsTheKick() throws Exception {
        int port = startServer(false);
        AfkWire.Conn conn = wire.open(new AfkWire.Target("127.0.0.1", port, "SleepyFox_42", false, "tr_tr"), recorder);
        assertEquals("loggingIn", take(events));
        ServerboundHelloPacket hello = receivedOf(ServerboundHelloPacket.class);
        assertEquals("SleepyFox_42", hello.getUsername());
        assertEquals(AfkMcWire.offlineUuid("SleepyFox_42"), hello.getProfileId());
        assertEquals("configuring", take(events));
        ServerboundCustomPayloadPacket brand = receivedOf(ServerboundCustomPayloadPacket.class);
        assertEquals("minecraft:brand", brand.getChannel().asString());
        ServerboundClientInformationPacket info = receivedOf(ServerboundClientInformationPacket.class);
        assertEquals("tr_tr", info.getLocale());
        assertEquals(2, info.getRenderDistance());
        assertEquals("joined", take(events));
        AfkText welcome = take(lines);
        assertEquals("Hoş geldin, SleepyFox_42", welcome.text);
        assertEquals(1, welcome.spans.size());
        assertEquals(0xFFAA00, welcome.spans.get(0).color);

        engine.submit(() -> conn.chat("/list")).get();
        assertEquals("list", receivedOf(ServerboundChatCommandPacket.class).getCommand());
        engine.submit(() -> conn.chat("selam")).get();
        assertEquals("selam", receivedOf(ServerboundChatPacket.class).getMessage());

        serverSession.disconnect(Component.text("You are banned from this server!"));
        AfkDrop drop = take(drops);
        assertEquals(AfkDrop.Kind.KICKED, drop.kind);
        assertEquals("You are banned from this server!", drop.english);
    }

    /**
     * Two teleports in a row, as a Paper 26.3 server sends them to a new player (its join, then its own correction)
     * or after a respawn. The 26.3 server takes one position per client tick and kicks a second one before
     * ServerboundClientTickEnd ("Invalid move player packet received", receivedPositionThisTick): the client confirms
     * each teleport with its id and absolute position and ends the tick after each position.
     */
    @Test
    public void teleportsKeepOnePositionPerClientTick() throws Exception {
        int port = startServer(false);
        wire.open(new AfkWire.Target("127.0.0.1", port, "Golge_Avci_2010", false, "tr_tr"), recorder);
        assertEquals("loggingIn", take(events));
        assertEquals("configuring", take(events));
        assertEquals("joined", take(events));
        serverSession.send(new ClientboundPlayerPositionPacket(1, -7.5, -60.0, -3.5, 0, 0, 0, 90.0f, 0.0f));
        serverSession.send(new ClientboundPlayerPositionPacket(2, -7.5, -60.0, -3.5, 0, 0, 0, 0.0f, 0.0f,
                PositionElement.Y_ROT, PositionElement.X_ROT));
        List<Integer> accepted = new ArrayList<>();
        boolean positionThisTick = false;
        int ticks = 0;
        long end = System.currentTimeMillis() + 20_000;
        while (ticks < 2 && System.currentTimeMillis() < end) {
            Packet p = received.poll(1, TimeUnit.SECONDS);
            if (p instanceof ServerboundAcceptTeleportationPacket) {
                ServerboundAcceptTeleportationPacket a = (ServerboundAcceptTeleportationPacket) p;
                accepted.add(a.getId());
                assertEquals(-7.5, a.getX(), 0);
                assertEquals(-60.0, a.getY(), 0);
                assertEquals(-3.5, a.getZ(), 0);
                // The second teleport keeps the rotation (relative yaw and pitch): absolute 90 again, never 180.
                assertEquals(90.0f, a.getYRot(), 0);
            } else if (p instanceof ServerboundMovePlayerPosRotPacket) {
                assertTrue("a second position before the client tick ended: the 26.3 server kicks", !positionThisTick);
                positionThisTick = true;
                assertEquals(-7.5, ((ServerboundMovePlayerPosRotPacket) p).getX(), 0);
            } else if (p instanceof ServerboundClientTickEndPacket) {
                positionThisTick = false;
                ticks++;
            }
        }
        assertEquals(Arrays.asList(1, 2), accepted);
        assertEquals(2, ticks);
    }

    /**
     * MOB-NEXT: an account back in a game while dead (its connection ended while it lay dead: a drop, the phone's AFK
     * process restarted) hears no new death from the server, only a health of 0, and stayed dead (seen on the emulator,
     * 2 Nov: its commands refused with "chat.disabled.options"). It respawns as after a death, once per life; a death's
     * packet and its health of 0 together ask once.
     */
    @Test
    public void anAccountBackWhileDeadRespawnsOncePerLife() throws Exception {
        int port = startServer(false);
        wire.open(new AfkWire.Target("127.0.0.1", port, "Golge_Avci_2010", false, "tr_tr"), recorder);
        assertEquals("loggingIn", take(events));
        assertEquals("configuring", take(events));
        assertEquals("joined", take(events));
        serverSession.send(new ClientboundSetHealthPacket(0f, 20, 5f));
        serverSession.send(new ClientboundSetHealthPacket(0f, 20, 5f));
        assertEquals(ClientCommand.PERFORM_RESPAWN, receivedOf(ServerboundClientCommandPacket.class).getRequest());
        assertEquals("died", take(events));
        assertNull("asked once for this life", events.poll(1500, TimeUnit.MILLISECONDS));
        // Alive again, then a real death: its packet and its health ask once more, together once.
        serverSession.send(new ClientboundSetHealthPacket(20f, 20, 5f));
        serverSession.send(new ClientboundPlayerCombatKillPacket(42, Component.text("Golge_Avci_2010 was slain by Slime")));
        serverSession.send(new ClientboundSetHealthPacket(0f, 20, 5f));
        assertEquals(ClientCommand.PERFORM_RESPAWN, receivedOf(ServerboundClientCommandPacket.class).getRequest());
        assertEquals("died", take(events));
        assertNull(events.poll(1500, TimeUnit.MILLISECONDS));
        // Health while alive asks for nothing.
        serverSession.send(new ClientboundSetHealthPacket(20f, 20, 5f));
        serverSession.send(new ClientboundSetHealthPacket(12.5f, 18, 2f));
        assertNull(events.poll(1500, TimeUnit.MILLISECONDS));
    }

    @Test
    public void anOnlineModeServerIsPremiumOnly() throws Exception {
        int port = startServer(true);
        wire.open(new AfkWire.Target("127.0.0.1", port, "Night_Owl", false, "en_us"), recorder);
        assertEquals(AfkDrop.Kind.PREMIUM_ONLY, take(drops).kind);
    }

    @Test
    public void unknownAddressesAndClosedPortsAreTold() throws Exception {
        wire = new AfkMcWire(engine, (host, port) -> {
            if (host.endsWith(".invalid")) throw new UnknownHostException(host);
            return AfkResolver.PLAIN.resolve(host, port);
        }, false, LOG);
        wire.open(new AfkWire.Target("nowhere.invalid", 0, "Ember_77", false, "en_us"), recorder);
        assertEquals(AfkDrop.Kind.ADDRESS_NOT_FOUND, take(drops).kind);
        wire.open(new AfkWire.Target("127.0.0.1", freePort(), "Ember_77", false, "en_us"), recorder);
        assertEquals(AfkDrop.Kind.UNREACHABLE, take(drops).kind);
        assertTrue(events.isEmpty());
    }
}
