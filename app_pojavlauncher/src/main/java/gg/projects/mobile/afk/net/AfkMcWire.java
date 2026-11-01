package gg.projects.mobile.afk.net;

import com.google.gson.JsonParser;

import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.compression.CompressionConfig;
import org.geysermc.mcprotocollib.network.crypt.AESEncryption;
import org.geysermc.mcprotocollib.network.crypt.EncryptionConfig;
import org.geysermc.mcprotocollib.network.event.session.ConnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.data.game.ClientCommand;
import org.geysermc.mcprotocollib.protocol.data.game.ResourcePackStatus;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.HandPreference;
import org.geysermc.mcprotocollib.protocol.data.game.setting.ChatVisibility;
import org.geysermc.mcprotocollib.protocol.data.game.setting.ParticleStatus;
import org.geysermc.mcprotocollib.protocol.data.game.setting.SkinPart;
import org.geysermc.mcprotocollib.protocol.data.handshake.HandshakeIntent;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundClientInformationPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundKeepAlivePacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundPongPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundResourcePackPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.serverbound.ServerboundFinishConfigurationPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.serverbound.ServerboundSelectKnownPacks;
import org.geysermc.mcprotocollib.protocol.packet.handshake.serverbound.ClientIntentionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatAckPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundClientCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundConfigurationAcknowledgedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundPlayerLoadedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundChunkBatchReceivedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundPlayerInputPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.serverbound.ServerboundCustomQueryAnswerPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.serverbound.ServerboundHelloPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.serverbound.ServerboundKeyPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.serverbound.ServerboundLoginAcknowledgedPacket;

import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import gg.projects.mobile.afk.api.AfkLimits;
import gg.projects.mobile.afk.api.AfkLine;
import gg.projects.mobile.afk.core.AfkComponents;
import gg.projects.mobile.afk.core.AfkDrop;
import gg.projects.mobile.afk.core.AfkRestart;
import gg.projects.mobile.afk.core.AfkText;
import gg.projects.mobile.afk.core.AfkWire;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;

/**
 * The phone's Minecraft client (MCProtocolLib 26.3, offline names): {@link AfkWire} over {@link AfkNetSession} with
 * our codec ({@link AfkCodec}) and our own login, configuration and game flow instead of MCProtocolLib's default
 * listener (no Lombok trap, no GameProfile, no Adventure): handshake and login with the offline UUID, encryption when
 * a server asks without authentication (with authentication: premium only), compression, cookies and plugin queries
 * answered "none", known packs empty, resource packs accepted without downloading, keep-alives and pings, teleports
 * confirmed, respawn after death, chunk batches acknowledged, view distance {@link AfkLimits#VIEW_DISTANCE}. Packets
 * are handled on the wire's one event loop; the engine hears about them on its own thread ({@code engine}).
 */
public final class AfkMcWire implements AfkWire {
    static {
        // No epoll/kqueue/io_uring jars or natives are shipped: plain NIO.
        System.setProperty("Mcpl.disable-native-transport", "true");
    }

    /** Where the wire says what it sees (logcat on the phone); never login commands or chat. */
    public interface Log {
        void info(String message);

        void warn(String message, Throwable error);
    }

    private final Executor engine;
    private final AfkResolver resolver;
    private final boolean turkish;
    private final Log log;
    private final EventLoopGroup group = new NioEventLoopGroup(1, new DefaultThreadFactory("projects-afk-net", true));
    private final ExecutorService resolverThread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "projects-afk-dns");
        t.setDaemon(true);
        return t;
    });

    public AfkMcWire(Executor engine, AfkResolver resolver, boolean turkish, Log log) {
        this.engine = engine;
        this.resolver = resolver;
        this.turkish = turkish;
        this.log = log;
    }

    /** Ends the event loop and the resolver (the service is going). */
    public void shutdown() {
        group.shutdownGracefully(0, 500, TimeUnit.MILLISECONDS);
        resolverThread.shutdownNow();
    }

    @Override
    public Conn open(Target target, Events events) {
        Connection c = new Connection(target, events);
        resolverThread.execute(c::resolveAndConnect);
        return c;
    }

    /** Minecraft's offline UUID: version 3 of "OfflinePlayer:" + name. */
    public static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    private static final AfkPackets.ChatTypeRef DEFAULT_CHAT = new AfkPackets.ChatTypeRef(-1, "chat.type.text",
            Arrays.asList("sender", "content"));
    private static final Map<String, AfkPackets.ChatTypeRef> VANILLA_CHAT = new HashMap<>();

    static {
        VANILLA_CHAT.put("minecraft:chat", DEFAULT_CHAT);
        VANILLA_CHAT.put("minecraft:say_command", new AfkPackets.ChatTypeRef(-1, "chat.type.announcement",
                Arrays.asList("sender", "content")));
        VANILLA_CHAT.put("minecraft:msg_command_incoming", new AfkPackets.ChatTypeRef(-1,
                "commands.message.display.incoming", Arrays.asList("sender", "content")));
        VANILLA_CHAT.put("minecraft:msg_command_outgoing", new AfkPackets.ChatTypeRef(-1,
                "commands.message.display.outgoing", Arrays.asList("target", "content")));
        VANILLA_CHAT.put("minecraft:team_msg_command_incoming", new AfkPackets.ChatTypeRef(-1, "chat.type.team.text",
                Arrays.asList("target", "sender", "content")));
        VANILLA_CHAT.put("minecraft:team_msg_command_outgoing", new AfkPackets.ChatTypeRef(-1, "chat.type.team.sent",
                Arrays.asList("target", "sender", "content")));
        VANILLA_CHAT.put("minecraft:emote_command", new AfkPackets.ChatTypeRef(-1, "chat.type.emote",
                Arrays.asList("sender", "content")));
    }

    final class Connection extends SessionAdapter implements Conn {
        private final Target target;
        private final Events events;
        private volatile boolean closed;
        private volatile AfkNetSession session;
        private MinecraftProtocol protocol;
        private String handshakeHost;
        private int handshakePort;

        // Event loop only.
        private int entityId = Integer.MIN_VALUE;
        private boolean loadedSent;
        private double x;
        private double y;
        private double z;
        private float yRot;
        private float xRot;
        private int unacked;
        private final Map<Integer, AfkPackets.ChatTypeRef> chatTypes = new HashMap<>();
        private final Map<Integer, String> chatTypeIds = new HashMap<>();

        Connection(Target target, Events events) {
            this.target = target;
            this.events = events;
        }

        void resolveAndConnect() {
            if (closed) return;
            AfkResolver.Resolved resolved;
            try {
                resolved = resolver.resolve(target.host, target.port);
            } catch (UnknownHostException e) {
                post(() -> events.ended(AfkDrop.of(AfkDrop.Kind.ADDRESS_NOT_FOUND)));
                return;
            } catch (RuntimeException e) {
                log.warn("address lookup failed", e);
                post(() -> events.ended(AfkDrop.of(AfkDrop.Kind.ADDRESS_NOT_FOUND)));
                return;
            }
            if (closed) return;
            handshakeHost = resolved.host;
            handshakePort = resolved.port;
            protocol = new MinecraftProtocol(AfkCodec.CODEC);
            protocol.setUseDefaultListeners(false);
            AfkNetSession s = new AfkNetSession(new InetSocketAddress(resolved.address, resolved.port), protocol, group);
            s.addListener(this);
            session = s;
            if (closed) return;
            try {
                s.connect(false);
            } catch (RuntimeException e) {
                log.warn("connect failed", e);
                post(() -> events.ended(AfkDrop.of(AfkDrop.Kind.ERROR)));
            }
        }

        private void post(Runnable r) {
            if (closed) return;
            engine.execute(r);
        }

        // ---- the engine's side ----

        @Override
        public void chat(String text) {
            AfkNetSession s = session;
            if (s == null || closed || protocol.getOutboundState() != ProtocolState.GAME) return;
            if (text.startsWith("/")) {
                s.send(new ServerboundChatCommandPacket(text.substring(1)));
            } else {
                s.send(new ServerboundChatPacket(text, System.currentTimeMillis(), 0L, null, 0, new BitSet(20), 0));
            }
        }

        @Override
        public void sneak(boolean down) {
            AfkNetSession s = session;
            if (s == null || closed || protocol.getOutboundState() != ProtocolState.GAME) return;
            s.send(new ServerboundPlayerInputPacket(false, false, false, false, false, down, false));
        }

        @Override
        public void close() {
            closed = true;
            AfkNetSession s = session;
            if (s != null) s.end(AfkDrop.of(AfkDrop.Kind.DISCONNECTED), null);
        }

        // ---- MCProtocolLib's side (its event loop) ----

        @Override
        public void connected(ConnectedEvent event) {
            AfkNetSession s = session;
            if (s == null) return;
            s.switchInboundState(() -> protocol.setInboundState(ProtocolState.LOGIN));
            s.send(new ClientIntentionPacket(protocol.getCodec().getProtocolVersion(), handshakeHost, handshakePort,
                    target.transfer ? HandshakeIntent.TRANSFER : HandshakeIntent.LOGIN));
            s.switchOutboundState(() -> protocol.setOutboundState(ProtocolState.LOGIN));
            s.send(new ServerboundHelloPacket(target.name, offlineUuid(target.name)));
            post(events::loggingIn);
        }

        @Override
        public void disconnected(DisconnectedEvent event) {
            AfkNetSession s = session;
            AfkDrop drop = s == null ? null : s.drop();
            if (drop == null) drop = event.getCause() == null ? AfkDrop.of(AfkDrop.Kind.CONNECTION_LOST)
                    : AfkNetSession.classify(event.getCause());
            Throwable cause = event.getCause();
            if (drop.kind == AfkDrop.Kind.ERROR && cause != null) log.warn("AFK connection error", cause);
            final AfkDrop ended = drop;
            post(() -> events.ended(ended));
        }

        @Override
        public void packetReceived(Session raw, Packet packet) {
            AfkNetSession s = session;
            if (s == null || closed) return;
            ProtocolState state = protocol.getInboundState();
            if (state == ProtocolState.LOGIN) {
                login(s, packet);
            } else if (state == ProtocolState.CONFIGURATION) {
                configuration(s, packet);
            } else if (state == ProtocolState.GAME) {
                game(s, packet);
            }
        }

        private void login(AfkNetSession s, Packet packet) {
            if (packet instanceof AfkPackets.Terminal) {
                s.switchInboundState(() -> protocol.setInboundState(ProtocolState.CONFIGURATION));
                s.send(new ServerboundLoginAcknowledgedPacket());
                s.switchOutboundState(() -> protocol.setOutboundState(ProtocolState.CONFIGURATION));
                s.send(new AfkPackets.Brand());
                s.send(new ServerboundClientInformationPacket(target.locale, AfkLimits.VIEW_DISTANCE, ChatVisibility.FULL,
                        true, Arrays.asList(SkinPart.values()), HandPreference.RIGHT_HAND, false, true, ParticleStatus.MINIMAL));
                post(events::configuring);
            } else if (packet instanceof AfkPackets.LoginCompression) {
                int threshold = ((AfkPackets.LoginCompression) packet).threshold;
                if (threshold >= 0) s.setCompression(new CompressionConfig(threshold, new AfkCompression(), false));
            } else if (packet instanceof AfkPackets.Hello) {
                encryption(s, (AfkPackets.Hello) packet);
            } else if (packet instanceof AfkPackets.LoginDisconnect) {
                Object reason = null;
                try {
                    reason = AfkComponents.fromJson(JsonParser.parseString(((AfkPackets.LoginDisconnect) packet).json));
                } catch (RuntimeException ignored) {
                    reason = ((AfkPackets.LoginDisconnect) packet).json;
                }
                s.end(kick(AfkDrop.Kind.LOGIN_REJECTED, reason), null);
            } else if (packet instanceof AfkPackets.CustomQuery) {
                s.send(new ServerboundCustomQueryAnswerPacket(((AfkPackets.CustomQuery) packet).messageId, null));
            } else if (packet instanceof AfkPackets.CookieRequest) {
                s.send(new AfkPackets.CookieResponse(((AfkPackets.CookieRequest) packet).key));
            }
        }

        private void encryption(AfkNetSession s, AfkPackets.Hello hello) {
            if (hello.shouldAuthenticate) {
                // An online-mode server: only Microsoft accounts get in.
                s.end(AfkDrop.of(AfkDrop.Kind.PREMIUM_ONLY), null);
                return;
            }
            try {
                KeyGenerator gen = KeyGenerator.getInstance("AES");
                gen.init(128);
                final SecretKey key = gen.generateKey();
                PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(hello.publicKey));
                s.send(new ServerboundKeyPacket(publicKey, key, hello.challenge), () -> {
                    try {
                        s.setEncryption(new EncryptionConfig(new AESEncryption(key)));
                    } catch (Exception e) {
                        s.end(AfkDrop.of(AfkDrop.Kind.ERROR), e);
                    }
                });
            } catch (Exception e) {
                s.end(AfkDrop.of(AfkDrop.Kind.ERROR), e);
            }
        }

        private void configuration(AfkNetSession s, Packet packet) {
            if (packet instanceof AfkPackets.KeepAlive) {
                s.send(new ServerboundKeepAlivePacket(((AfkPackets.KeepAlive) packet).id));
            } else if (packet instanceof AfkPackets.Ping) {
                s.send(new ServerboundPongPacket(((AfkPackets.Ping) packet).id));
            } else if (packet instanceof AfkPackets.Terminal) {
                s.switchInboundState(() -> protocol.setInboundState(ProtocolState.GAME));
                s.send(ServerboundFinishConfigurationPacket.INSTANCE);
                s.switchOutboundState(() -> protocol.setOutboundState(ProtocolState.GAME));
            } else if (packet instanceof AfkPackets.SelectKnownPacks) {
                s.send(new ServerboundSelectKnownPacks(Collections.emptyList()));
            } else if (packet instanceof AfkPackets.RegistryData) {
                registry((AfkPackets.RegistryData) packet);
            } else if (common(s, packet)) {
                return;
            } else if (packet instanceof AfkPackets.CodeOfConduct) {
                // Never accepted for the player: the server waits, the player hears about it.
                post(() -> events.window(""));
            }
        }

        /** Packets of both the configuration and the game: true when handled. */
        private boolean common(AfkNetSession s, Packet packet) {
            if (packet instanceof AfkPackets.Disconnect) {
                s.end(kick(AfkDrop.Kind.KICKED, ((AfkPackets.Disconnect) packet).reason), null);
            } else if (packet instanceof AfkPackets.CookieRequest) {
                s.send(new AfkPackets.CookieResponse(((AfkPackets.CookieRequest) packet).key));
            } else if (packet instanceof AfkPackets.ResourcePackPush) {
                UUID id = ((AfkPackets.ResourcePackPush) packet).id;
                s.send(new ServerboundResourcePackPacket(id, ResourcePackStatus.ACCEPTED));
                s.send(new ServerboundResourcePackPacket(id, ResourcePackStatus.DOWNLOADED));
                s.send(new ServerboundResourcePackPacket(id, ResourcePackStatus.SUCCESSFULLY_LOADED));
            } else if (packet instanceof AfkPackets.Transfer) {
                final AfkPackets.Transfer t = (AfkPackets.Transfer) packet;
                post(() -> events.transfer(t.host, t.port));
                closed = true;
                s.end(AfkDrop.of(AfkDrop.Kind.DISCONNECTED), null);
            } else if (packet instanceof AfkPackets.ShowDialog) {
                final String title = dialogTitle(((AfkPackets.ShowDialog) packet).dialog);
                post(() -> events.window(title));
            } else {
                return false;
            }
            return true;
        }

        private void game(AfkNetSession s, Packet packet) {
            if (packet instanceof AfkPackets.KeepAlive) {
                s.send(new ServerboundKeepAlivePacket(((AfkPackets.KeepAlive) packet).id));
            } else if (packet instanceof AfkPackets.Ping) {
                s.send(new ServerboundPongPacket(((AfkPackets.Ping) packet).id));
            } else if (packet instanceof AfkPackets.PlayerPosition) {
                position(s, (AfkPackets.PlayerPosition) packet);
            } else if (packet instanceof AfkPackets.ChunkBatchFinished) {
                s.send(new ServerboundChunkBatchReceivedPacket(7.0f));
            } else if (packet instanceof AfkPackets.SystemChat) {
                AfkPackets.SystemChat chat = (AfkPackets.SystemChat) packet;
                if (chat.overlay) return;
                final AfkText text = AfkComponents.render(chat.content, turkish);
                post(() -> events.line(AfkLine.Kind.SYSTEM, text, null));
            } else if (packet instanceof AfkPackets.PlayerChat) {
                AfkPackets.PlayerChat chat = (AfkPackets.PlayerChat) packet;
                if (chat.signed && ++unacked >= 64) {
                    s.send(new ServerboundChatAckPacket(unacked));
                    unacked = 0;
                }
                if (chat.filtered) return;
                Object content = chat.unsignedContent != null ? chat.unsignedContent : chat.content;
                chatLine(chat.chatType, content, chat.name, chat.target);
            } else if (packet instanceof AfkPackets.DisguisedChat) {
                AfkPackets.DisguisedChat chat = (AfkPackets.DisguisedChat) packet;
                chatLine(chat.chatType, chat.message, chat.name, chat.target);
            } else if (packet instanceof AfkPackets.Login) {
                entityId = ((AfkPackets.Login) packet).entityId;
                loadedSent = false;
                post(events::joined);
            } else if (packet instanceof AfkPackets.Respawn) {
                loadedSent = false;
            } else if (packet instanceof AfkPackets.CombatKill) {
                if (((AfkPackets.CombatKill) packet).playerId != entityId) return;
                s.getChannel().eventLoop().schedule(() -> {
                    if (!closed) s.send(new ServerboundClientCommandPacket(ClientCommand.PERFORM_RESPAWN));
                }, 1, TimeUnit.SECONDS);
                post(events::died);
            } else if (packet instanceof AfkPackets.OpenScreen) {
                final String title = AfkComponents.render(((AfkPackets.OpenScreen) packet).title, turkish).text;
                post(() -> events.window(title));
            } else if (packet instanceof AfkPackets.Terminal) {
                // A proxy's server switch: back to the configuration.
                s.switchInboundState(() -> protocol.setInboundState(ProtocolState.CONFIGURATION));
                s.send(new ServerboundConfigurationAcknowledgedPacket());
                s.switchOutboundState(() -> protocol.setOutboundState(ProtocolState.CONFIGURATION));
                post(events::configuring);
            } else {
                common(s, packet);
            }
        }

        private void position(AfkNetSession s, AfkPackets.PlayerPosition p) {
            int rel = p.relatives;
            x = (rel & 1) != 0 ? x + p.x : p.x;
            y = (rel & 2) != 0 ? y + p.y : p.y;
            z = (rel & 4) != 0 ? z + p.z : p.z;
            yRot = (rel & 8) != 0 ? yRot + p.yRot : p.yRot;
            xRot = (rel & 16) != 0 ? xRot + p.xRot : p.xRot;
            s.send(new ServerboundAcceptTeleportationPacket(p.teleportId, x, y, z, yRot, xRot));
            s.send(new ServerboundMovePlayerPosRotPacket(true, false, x, y, z, yRot, xRot));
            if (!loadedSent) {
                loadedSent = true;
                s.send(ServerboundPlayerLoadedPacket.INSTANCE);
            }
        }

        private void registry(AfkPackets.RegistryData data) {
            if (!"minecraft:chat_type".equals(data.registry)) return;
            chatTypes.clear();
            chatTypeIds.clear();
            for (int i = 0; i < data.ids.size(); i++) {
                String id = data.ids.get(i);
                AfkPackets.ChatTypeRef ref = AfkPackets.decorationOf(data.data.get(i));
                if (ref == null) ref = VANILLA_CHAT.get(id);
                if (ref != null) chatTypes.put(i, ref);
                chatTypeIds.put(i, id);
            }
        }

        private void chatLine(AfkPackets.ChatTypeRef type, Object content, Object name, Object targetName) {
            AfkPackets.ChatTypeRef d = type.registryId >= 0 ? chatTypes.get(type.registryId) : type;
            String typeId = type.registryId >= 0 ? chatTypeIds.get(type.registryId) : null;
            if (d == null) d = DEFAULT_CHAT;
            boolean incoming = "minecraft:msg_command_incoming".equals(typeId)
                    || "commands.message.display.incoming".equals(d.translationKey);
            List<Object> args = new ArrayList<>();
            for (String p : d.parameters == null ? DEFAULT_CHAT.parameters : d.parameters) {
                if ("sender".equals(p)) args.add(name);
                else if ("target".equals(p)) args.add(targetName == null ? "" : targetName);
                else args.add(content);
            }
            Map<String, Object> component = new LinkedHashMap<>();
            component.put("translate", d.translationKey);
            component.put("with", args);
            final AfkText text = AfkComponents.render(component, turkish);
            final String sender = playerName(AfkComponents.render(name, false).text);
            final AfkLine.Kind kind = incoming ? AfkLine.Kind.PRIVATE : AfkLine.Kind.CHAT;
            post(() -> events.line(kind, text, sender));
        }

        /** A dialog's title (its {@code title} text), or "". */
        @SuppressWarnings("unchecked")
        private String dialogTitle(Object dialog) {
            if (!(dialog instanceof Map)) return "";
            Object title = ((Map<String, Object>) dialog).get("title");
            return title == null ? "" : AfkComponents.render(title, turkish).text;
        }

        /** A kick's drop: its words as shown and in English, its keys, and whether it is about the version. */
        private AfkDrop kick(AfkDrop.Kind kind, Object reason) {
            String shown = AfkComponents.render(reason, turkish).text;
            String english = AfkComponents.english(reason);
            List<String> keys = AfkComponents.keys(reason);
            if (versionRefusal(english, keys)) kind = AfkDrop.Kind.UNSUPPORTED_VERSION;
            return new AfkDrop(kind, shown, english, keys);
        }
    }

    /** The name a chat line's sender component ends with ("[VIP] SleepyFox_42" → "SleepyFox_42"), or null. */
    static String playerName(String shown) {
        if (shown == null) return null;
        String s = shown.trim();
        int end = s.length();
        while (end > 0 && !nameChar(s.charAt(end - 1))) end--;
        int start = end;
        while (start > 0 && nameChar(s.charAt(start - 1))) start--;
        int n = end - start;
        return n >= 3 && n <= 16 ? s.substring(start, end) : null;
    }

    private static boolean nameChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
    }

    private static final String[] VERSION_WORDS = {"OUTDATED CLIENT", "OUTDATED SERVER", "INCOMPATIBLE CLIENT",
            "UNSUPPORTED CLIENT", "UNSUPPORTED PROTOCOL", "UNSUPPORTED VERSION", "DESTEKLENMEYEN SURUM",
            "SURUMU DESTEKLENMIYOR", "ESKI SURUM"};

    static boolean versionRefusal(String english, List<String> keys) {
        for (String k : keys) {
            if (k.equals("multiplayer.disconnect.outdated_client") || k.equals("multiplayer.disconnect.outdated_server")
                    || k.equals("multiplayer.disconnect.incompatible")) {
                return true;
            }
        }
        if (english == null) return false;
        String text = AfkRestart.plainUpper(english);
        for (String w : VERSION_WORDS) {
            if (text.contains(w)) return true;
        }
        return false;
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
