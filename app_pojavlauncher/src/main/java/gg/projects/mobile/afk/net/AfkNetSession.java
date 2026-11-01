package gg.projects.mobile.afk.net;

import net.kyori.adventure.text.Component;

import org.geysermc.mcprotocollib.network.BuiltinFlags;
import org.geysermc.mcprotocollib.network.compression.CompressionConfig;
import org.geysermc.mcprotocollib.network.netty.MinecraftChannelInitializer;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.network.session.ClientNetworkSession;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.util.concurrent.TimeoutException;

import gg.projects.mobile.afk.core.AfkDrop;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.ConnectTimeoutException;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.ReadTimeoutException;

/**
 * MCProtocolLib's client session for the phone (AFK-M0 §4.3): an address resolved beforehand (no NettyHelper and its
 * JNDI DNS, which Android lacks; no proxy, no HAProxy header), NIO on the wire's own single event loop, byte-array
 * zlib, our skipped packets dropped before any listener, and every ending told as an {@link AfkDrop} instead of an
 * Adventure text. Skip packets never reach listeners.
 */
final class AfkNetSession extends ClientNetworkSession {
    /** The text MCProtocolLib's events carry; the reason travels as {@link #drop}. */
    static final Component NO_TEXT = Component.empty();

    private final EventLoopGroup group;
    /** Why this connection ends, set before the session disconnects (the first reason wins). */
    private volatile AfkDrop drop;

    AfkNetSession(InetSocketAddress address, MinecraftProtocol protocol, EventLoopGroup group) {
        super(address, protocol, Runnable::run, null, null);
        this.group = group;
    }

    AfkDrop drop() {
        return drop;
    }

    /** Ends the connection for {@code why} (kept only if no reason was set before). */
    void end(AfkDrop why, Throwable cause) {
        if (drop == null) drop = why;
        disconnect(NO_TEXT, cause);
    }

    @Override
    public void connect(boolean wait) {
        if (this.disconnected) throw new IllegalStateException("Session has already been disconnected.");
        Bootstrap bootstrap = new Bootstrap()
                .channelFactory(getChannelFactory())
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, getFlag(BuiltinFlags.CLIENT_CONNECT_TIMEOUT, 15) * 1000)
                .group(getEventLoopGroup())
                .remoteAddress(this.remoteAddress)
                .handler(getChannelHandler());
        setOptions(bootstrap);
        bootstrap.connect().addListener(future -> {
            if (!future.isSuccess()) end(AfkDrop.of(AfkDrop.Kind.UNREACHABLE), future.cause());
        });
    }

    @Override
    protected EventLoopGroup getEventLoopGroup() {
        return group;
    }

    @Override
    protected ChannelFactory<? extends Channel> getChannelFactory() {
        return NioSocketChannel::new;
    }

    @Override
    public void setCompression(CompressionConfig config) {
        if (config != null && !(config.compression() instanceof AfkCompression)) {
            config.compression().close();
            config = new CompressionConfig(config.threshold(), new AfkCompression(), config.validateDecompression());
        }
        super.setCompression(config);
    }

    @Override
    protected ChannelHandler getChannelHandler() {
        final AfkNetSession self = this;
        return new MinecraftChannelInitializer<AfkNetSession>(channel -> {
            self.getPacketProtocol().newClientSession(self);
            return self;
        }, true);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        ProtocolState state = getPacketProtocol().getInboundState();
        end(AfkDrop.of(state == ProtocolState.GAME ? AfkDrop.Kind.CONNECTION_LOST : AfkDrop.Kind.DISCONNECTED), null);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (!ctx.channel().isOpen()) return;
        end(classify(cause), cause);
    }

    @Override
    protected Component getGenericDisconnectMessage(Throwable cause) {
        return NO_TEXT;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Packet packet) {
        if (packet instanceof AfkPackets.Skip) return;
        super.channelRead0(ctx, packet);
    }

    static AfkDrop classify(Throwable cause) {
        Throwable t = cause;
        for (int i = 0; i < 5 && t != null; i++) {
            if (t instanceof ReadTimeoutException || t instanceof TimeoutException) {
                return new AfkDrop(AfkDrop.Kind.CONNECTION_LOST, null, "Timed out", null);
            }
            if (t instanceof ConnectTimeoutException || t instanceof ConnectException || t instanceof NoRouteToHostException) {
                return AfkDrop.of(AfkDrop.Kind.UNREACHABLE);
            }
            if (t instanceof SocketException || t instanceof IOException) return AfkDrop.of(AfkDrop.Kind.CONNECTION_LOST);
            t = t.getCause();
        }
        return AfkDrop.of(AfkDrop.Kind.ERROR);
    }
}
