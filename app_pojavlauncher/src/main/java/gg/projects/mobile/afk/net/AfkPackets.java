package gg.projects.mobile.afk.net;

import org.geysermc.mcprotocollib.protocol.codec.MinecraftPacket;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftTypes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.netty.buffer.ByteBuf;

/**
 * The packets the phone's client reads, in place of MCProtocolLib's (AFK-M0 §3, docs/tasks/afk-m.md): only what AFK
 * needs is decoded, with our own NBT reader and no Adventure, GameProfile or Key, so nothing Android 8–13 lacks is
 * loaded; everything else is {@link Skip}ped without decoding (far less work than MCProtocolLib's full decoding of
 * every entity move). The two serverbound packets that would take an Adventure {@code Key} are ours too
 * ({@link CookieResponse}, {@link Brand}). Every handler runs on the connection's event loop.
 */
final class AfkPackets {
    private AfkPackets() {}

    /** A clientbound packet we read; never sent by the client. */
    abstract static class In implements MinecraftPacket {
        @Override
        public final void serialize(ByteBuf buf) {
            throw new UnsupportedOperationException("clientbound");
        }
    }

    /** A packet AFK has no use for: its bytes are dropped. One instance per state and id. */
    static final class Skip extends In {
        final int id;

        Skip(int id) {
            this.id = id;
        }
    }

    /** The end of a state (login finished, configuration finished, configuration started): read no further. */
    static final class Terminal extends In {
        enum Kind { LOGIN_FINISHED, FINISH_CONFIGURATION, START_CONFIGURATION }

        final Kind kind;

        Terminal(Kind kind) {
            this.kind = kind;
        }

        @Override
        public boolean isTerminal() {
            return true;
        }
    }

    static final class LoginDisconnect extends In {
        final String json;

        LoginDisconnect(ByteBuf in) {
            json = MinecraftTypes.readString(in, 262144);
        }
    }

    static final class Hello extends In {
        final byte[] publicKey;
        final byte[] challenge;
        final boolean shouldAuthenticate;

        Hello(ByteBuf in) {
            MinecraftTypes.readString(in, 20);
            publicKey = MinecraftTypes.readByteArray(in);
            challenge = MinecraftTypes.readByteArray(in);
            shouldAuthenticate = in.readBoolean();
        }
    }

    static final class LoginCompression extends In {
        final int threshold;

        LoginCompression(ByteBuf in) {
            threshold = MinecraftTypes.readVarInt(in);
        }
    }

    static final class CustomQuery extends In {
        final int messageId;

        CustomQuery(ByteBuf in) {
            messageId = MinecraftTypes.readVarInt(in);
            in.skipBytes(in.readableBytes());
        }
    }

    static final class CookieRequest extends In {
        final String key;

        CookieRequest(ByteBuf in) {
            key = MinecraftTypes.readString(in, 32767);
        }
    }

    /** A kick in the configuration or in game (a text component). */
    static final class Disconnect extends In {
        final Object reason;

        Disconnect(ByteBuf in) {
            reason = AfkNbt.read(in);
        }
    }

    static final class KeepAlive extends In {
        final long id;

        KeepAlive(ByteBuf in) {
            id = in.readLong();
        }
    }

    static final class Ping extends In {
        final int id;

        Ping(ByteBuf in) {
            id = in.readInt();
        }
    }

    /** Only {@code minecraft:chat_type}'s entries are kept (the chat decorations by registry index). */
    static final class RegistryData extends In {
        final String registry;
        final List<String> ids;
        final List<Object> data;

        RegistryData(ByteBuf in) {
            registry = MinecraftTypes.readString(in, 32767);
            if (!"minecraft:chat_type".equals(registry)) {
                in.skipBytes(in.readableBytes());
                ids = Collections.emptyList();
                data = Collections.emptyList();
                return;
            }
            int n = MinecraftTypes.readVarInt(in);
            ids = new ArrayList<>(n);
            data = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                ids.add(MinecraftTypes.readString(in, 32767));
                data.add(in.readBoolean() ? AfkNbt.read(in) : null);
            }
        }
    }

    static final class ResourcePackPush extends In {
        final UUID id;

        ResourcePackPush(ByteBuf in) {
            id = MinecraftTypes.readUUID(in);
            in.skipBytes(in.readableBytes());
        }
    }

    static final class Transfer extends In {
        final String host;
        final int port;

        Transfer(ByteBuf in) {
            host = MinecraftTypes.readString(in, 32767);
            port = MinecraftTypes.readVarInt(in);
        }
    }

    static final class SelectKnownPacks extends In {
        SelectKnownPacks(ByteBuf in) {
            in.skipBytes(in.readableBytes());
        }
    }

    /** A dialog (configuration: a compound; in game: a registry holder, inline when its id is 0). */
    static final class ShowDialog extends In {
        final Object dialog;

        ShowDialog(ByteBuf in, boolean holder) {
            if (holder && MinecraftTypes.readVarInt(in) != 0) {
                dialog = null;
            } else {
                dialog = AfkNbt.read(in);
            }
            in.skipBytes(in.readableBytes());
        }
    }

    static final class CodeOfConduct extends In {
        final String text;

        CodeOfConduct(ByteBuf in) {
            text = MinecraftTypes.readString(in);
        }
    }

    /** JoinGame: only the account's entity id. */
    static final class Login extends In {
        final int entityId;

        Login(ByteBuf in) {
            entityId = in.readInt();
            in.skipBytes(in.readableBytes());
        }
    }

    static final class Respawn extends In {
        Respawn(ByteBuf in) {
            in.skipBytes(in.readableBytes());
        }
    }

    /** A chat type: inline decoration or a registry index ({@code registryId} ≥ 0). */
    static final class ChatTypeRef {
        final int registryId;
        final String translationKey;
        final List<String> parameters;

        ChatTypeRef(int registryId, String translationKey, List<String> parameters) {
            this.registryId = registryId;
            this.translationKey = translationKey;
            this.parameters = parameters;
        }

        static ChatTypeRef read(ByteBuf in) {
            int id = MinecraftTypes.readVarInt(in);
            if (id != 0) return new ChatTypeRef(id - 1, null, null);
            // Inline: the chat decoration, then the narration's (skipped).
            String key = MinecraftTypes.readString(in);
            int n = MinecraftTypes.readVarInt(in);
            List<String> params = new ArrayList<>(n);
            for (int i = 0; i < n; i++) params.add(parameterName(MinecraftTypes.readVarInt(in)));
            AfkNbt.skip(in);
            MinecraftTypes.readString(in);
            int m = MinecraftTypes.readVarInt(in);
            for (int i = 0; i < m; i++) MinecraftTypes.readVarInt(in);
            AfkNbt.skip(in);
            return new ChatTypeRef(-1, key, params);
        }

        /** Vanilla's ChatTypeDecoration.Parameter by id. */
        static String parameterName(int id) {
            switch (id) {
                case 0: return "sender";
                case 1: return "target";
                default: return "content";
            }
        }
    }

    static final class PlayerChat extends In {
        final UUID sender;
        final boolean signed;
        final String content;
        final Object unsignedContent;
        final boolean filtered;
        final ChatTypeRef chatType;
        final Object name;
        final Object target;

        PlayerChat(ByteBuf in) {
            MinecraftTypes.readVarInt(in);
            sender = MinecraftTypes.readUUID(in);
            MinecraftTypes.readVarInt(in);
            signed = in.readBoolean();
            if (signed) in.skipBytes(256);
            content = MinecraftTypes.readString(in, 256);
            in.readLong();
            in.readLong();
            int seen = Math.min(MinecraftTypes.readVarInt(in), 20);
            for (int i = 0; i < seen; i++) {
                if (MinecraftTypes.readVarInt(in) == 0) in.skipBytes(256);
            }
            unsignedContent = in.readBoolean() ? AfkNbt.read(in) : null;
            int filter = MinecraftTypes.readVarInt(in);
            if (filter == 2) {
                int longs = MinecraftTypes.readVarInt(in);
                in.skipBytes(8 * Math.max(0, longs));
            }
            filtered = filter == 1;
            chatType = ChatTypeRef.read(in);
            name = AfkNbt.read(in);
            target = in.readBoolean() ? AfkNbt.read(in) : null;
        }
    }

    static final class DisguisedChat extends In {
        final Object message;
        final ChatTypeRef chatType;
        final Object name;
        final Object target;

        DisguisedChat(ByteBuf in) {
            message = AfkNbt.read(in);
            chatType = ChatTypeRef.read(in);
            name = AfkNbt.read(in);
            target = in.readBoolean() ? AfkNbt.read(in) : null;
        }
    }

    static final class SystemChat extends In {
        final Object content;
        final boolean overlay;

        SystemChat(ByteBuf in) {
            content = AfkNbt.read(in);
            overlay = in.readBoolean();
        }
    }

    static final class CombatKill extends In {
        final int playerId;

        CombatKill(ByteBuf in) {
            playerId = MinecraftTypes.readVarInt(in);
            in.skipBytes(in.readableBytes());
        }
    }

    /** MOB-NEXT: the player's health; a client back in a game while dead hears only this, never a new death. */
    static final class SetHealth extends In {
        final float health;

        SetHealth(ByteBuf in) {
            health = in.readFloat();
            in.skipBytes(in.readableBytes());
        }
    }

    static final class PlayerPosition extends In {
        final int teleportId;
        final double x, y, z;
        final float yRot, xRot;
        final int relatives;

        PlayerPosition(ByteBuf in) {
            teleportId = MinecraftTypes.readVarInt(in);
            x = in.readDouble();
            y = in.readDouble();
            z = in.readDouble();
            in.skipBytes(24);
            yRot = in.readFloat();
            xRot = in.readFloat();
            relatives = in.readInt();
        }
    }

    static final class ChunkBatchFinished extends In {
        ChunkBatchFinished(ByteBuf in) {
            MinecraftTypes.readVarInt(in);
        }
    }

    static final class OpenScreen extends In {
        final Object title;

        OpenScreen(ByteBuf in) {
            MinecraftTypes.readVarInt(in);
            MinecraftTypes.readVarInt(in);
            title = AfkNbt.read(in);
        }
    }

    // ---- serverbound replacements ----

    /** ServerboundCookieResponsePacket without Adventure's Key: "no cookie". */
    static final class CookieResponse implements MinecraftPacket {
        final String key;

        CookieResponse(String key) {
            this.key = key;
        }

        @Override
        public void serialize(ByteBuf out) {
            MinecraftTypes.writeString(out, key);
            out.writeBoolean(false);
        }
    }

    /** ServerboundCustomPayloadPacket for {@code minecraft:brand} only (vanilla's client says "vanilla"). */
    static final class Brand implements MinecraftPacket {
        @Override
        public void serialize(ByteBuf out) {
            MinecraftTypes.writeString(out, "minecraft:brand");
            MinecraftTypes.writeString(out, "vanilla");
        }
    }

    /** A chat type's translation key and parameters from its registry entry ({@code chat: {...}}), or null. */
    @SuppressWarnings("unchecked")
    static ChatTypeRef decorationOf(Object entry) {
        if (!(entry instanceof Map)) return null;
        Object chat = ((Map<String, Object>) entry).get("chat");
        if (!(chat instanceof Map)) return null;
        Map<String, Object> c = (Map<String, Object>) chat;
        Object key = c.get("translation_key");
        if (!(key instanceof String)) return null;
        List<String> params = new ArrayList<>();
        Object p = c.get("parameters");
        if (p instanceof List) {
            for (Object o : (List<Object>) p) {
                if (o instanceof String) params.add(((String) o).replace("minecraft:", ""));
            }
        } else if (p instanceof String) {
            params.add(((String) p).replace("minecraft:", ""));
        }
        return new ChatTypeRef(-1, (String) key, params);
    }
}
