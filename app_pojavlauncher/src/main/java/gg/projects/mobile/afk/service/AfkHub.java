package gg.projects.mobile.afk.service;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import gg.projects.mobile.afk.api.AfkAlert;
import gg.projects.mobile.afk.api.AfkLine;
import gg.projects.mobile.afk.api.AfkSendResult;
import gg.projects.mobile.afk.api.AfkStatus;
import gg.projects.mobile.afk.link.AfkIpc;

/**
 * PHONE-AFK: the service's side of the link (its main thread). Keeps the screens that said {@code HELLO}, answers
 * them and passes on what the engine says: statuses to every one, console lines and alerts only to those watching a
 * screen ({@code full}).
 */
final class AfkHub extends Handler {
    /** What the hub asks of the engine (answers may come later; {@code done} on the main thread). */
    interface Engine {
        List<AfkStatus> statuses();

        List<AfkLine> backlog(String accountId);

        List<String> runningIds();

        void send(String accountId, String text, Done<AfkSendResult> done);

        void stop(String accountId);

        void stopAll(boolean erase);

        void reconnect(String accountId);
    }

    interface Done<T> {
        void done(T value);
    }

    private final Engine engine;
    private final Map<Messenger, Boolean> clients = new LinkedHashMap<>();
    final Messenger messenger = new Messenger(this);

    AfkHub(Engine engine) {
        super(Looper.getMainLooper());
        this.engine = engine;
    }

    @Override
    public void handleMessage(Message m) {
        final Messenger from = m.replyTo;
        Bundle data = m.getData();
        switch (m.what) {
            case AfkIpc.HELLO:
                if (from == null) return;
                boolean full = m.arg1 == 1;
                clients.put(from, full);
                reply(from, AfkIpc.STATUSES, 0, json(AfkIpc.statuses(engine.statuses())));
                if (full) {
                    for (String id : engine.runningIds()) {
                        Bundle b = json(AfkIpc.lines(engine.backlog(id)));
                        b.putString(AfkIpc.KEY_ACCOUNT, id);
                        reply(from, AfkIpc.BACKLOG, 0, b);
                    }
                }
                return;
            case AfkIpc.BYE:
                if (from != null) clients.remove(from);
                return;
            case AfkIpc.SEND: {
                final int request = m.arg1;
                String account = data == null ? null : data.getString(AfkIpc.KEY_ACCOUNT);
                String text = data == null ? null : data.getString(AfkIpc.KEY_TEXT);
                if (account == null || text == null) {
                    answer(from, request, AfkSendResult.EMPTY);
                    return;
                }
                engine.send(account, text, result -> answer(from, request, result));
                return;
            }
            case AfkIpc.STOP:
                if (data != null && data.getString(AfkIpc.KEY_ACCOUNT) != null) engine.stop(data.getString(AfkIpc.KEY_ACCOUNT));
                return;
            case AfkIpc.STOP_ALL:
                engine.stopAll(m.arg1 == 1);
                return;
            case AfkIpc.RECONNECT:
                if (data != null && data.getString(AfkIpc.KEY_ACCOUNT) != null) {
                    engine.reconnect(data.getString(AfkIpc.KEY_ACCOUNT));
                }
                return;
            default:
                super.handleMessage(m);
        }
    }

    private void answer(Messenger to, int request, AfkSendResult result) {
        if (to == null) return;
        Bundle b = new Bundle();
        b.putString(AfkIpc.KEY_TEXT, result.key());
        reply(to, AfkIpc.SEND_RESULT, request, b);
    }

    void statuses(List<AfkStatus> all) {
        broadcast(AfkIpc.STATUSES, json(AfkIpc.statuses(all)), false);
    }

    void status(AfkStatus s) {
        broadcast(AfkIpc.STATUS, json(AfkIpc.status(s).toString()), false);
    }

    void line(AfkLine l) {
        broadcast(AfkIpc.LINE, json(AfkIpc.line(l).toString()), true);
    }

    void alert(AfkAlert a) {
        broadcast(AfkIpc.ALERT, json(AfkIpc.alert(a).toString()), true);
    }

    boolean watched() {
        return clients.containsValue(Boolean.TRUE);
    }

    private void broadcast(int what, Bundle data, boolean fullOnly) {
        if (clients.isEmpty()) return;
        for (Map.Entry<Messenger, Boolean> e : new ArrayList<>(clients.entrySet())) {
            if (fullOnly && !e.getValue()) continue;
            reply(e.getKey(), what, 0, data);
        }
    }

    private void reply(Messenger to, int what, int arg1, Bundle data) {
        Message out = Message.obtain(null, what, arg1, 0);
        out.setData(data);
        try {
            to.send(out);
        } catch (RemoteException e) {
            // That screen's process is gone.
            clients.remove(to);
        }
    }

    private static Bundle json(String json) {
        Bundle b = new Bundle();
        b.putString(AfkIpc.KEY_JSON, json);
        return b;
    }
}
