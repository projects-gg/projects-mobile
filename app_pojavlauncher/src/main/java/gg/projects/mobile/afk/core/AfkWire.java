package gg.projects.mobile.afk.core;

import gg.projects.mobile.afk.api.AfkLine;

/**
 * The network side the engine drives (the {@code net} package implements it with MCProtocolLib; the JVM tests with
 * a stand-in). {@link #open} and every {@link Conn} call happen on the engine thread, and every {@link Events} call
 * comes on it.
 */
public interface AfkWire {
    /** Opens a connection to {@code target}; its events come until it {@link Events#ended} or is closed. */
    Conn open(Target target, Events events);

    /** Where and as whom. */
    final class Target {
        /** The host as the player wrote it (lower case, ASCII). */
        public final String host;
        /** The written port, or 0: the server's SRV record, else 25565. */
        public final int port;
        public final String name;
        /** A server's transfer brought the account here (the handshake says so). */
        public final boolean transfer;
        /** The client's language for the server ({@code tr_tr}, {@code en_us}). */
        public final String locale;

        public Target(String host, int port, String name, boolean transfer, String locale) {
            this.host = host;
            this.port = port;
            this.name = name;
            this.transfer = transfer;
            this.locale = locale;
        }

        /** {@code host} or {@code host:port} as the console shows it. */
        public String address() {
            String h = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
            return port == 0 ? h : h + ":" + port;
        }
    }

    interface Conn {
        /** A chat line, or a command when it starts with {@code /}. */
        void chat(String text);

        /** Holds (true) or lets go (false) of sneak. */
        void sneak(boolean down);

        /** Ends it quietly: no {@link Events#ended} follows. */
        void close();
    }

    interface Events {
        /** Connected; the login started. */
        void loggingIn();

        /** The login finished, or a proxy started a server switch: the server sends its settings. */
        void configuring();

        /** In game (each time: also after a proxy's server switch). */
        void joined();

        /**
         * A line the server sent. {@code sender}: the speaking player's name when the packet says it (player chat),
         * else null. {@code text} is not masked yet.
         */
        void line(AfkLine.Kind kind, AfkText text, String sender);

        /** The account died; the wire asked to respawn. */
        void died();

        /** The server opened a window, a dialog or its code of conduct ({@code title}: may be empty). */
        void window(String title);

        /** The server moved the account to another server (the wire closed this connection). */
        void transfer(String host, int port);

        /** The connection is over. */
        void ended(AfkDrop drop);
    }
}
