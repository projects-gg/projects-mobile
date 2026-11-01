package gg.projects.mobile.afk.core;

import java.util.ArrayList;
import java.util.List;

/** A wire that records what the engine asks and lets the test play the server. */
final class FakeWire implements AfkWire {
    final List<Conn> opened = new ArrayList<>();

    final class Conn implements AfkWire.Conn {
        final Target target;
        final Events events;
        final List<String> chats = new ArrayList<>();
        final List<Boolean> sneaks = new ArrayList<>();
        boolean closed;

        Conn(Target target, Events events) {
            this.target = target;
            this.events = events;
        }

        @Override
        public void chat(String text) {
            chats.add(text);
        }

        @Override
        public void sneak(boolean down) {
            sneaks.add(down);
        }

        @Override
        public void close() {
            closed = true;
        }

        /** The server lets the account in. */
        void join() {
            events.loggingIn();
            events.configuring();
            events.joined();
        }
    }

    @Override
    public AfkWire.Conn open(Target target, Events events) {
        Conn c = new Conn(target, events);
        opened.add(c);
        return c;
    }

    Conn last() {
        return opened.get(opened.size() - 1);
    }
}
