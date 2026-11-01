package gg.projects.mobile.afk.link;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import java.util.List;

import gg.projects.mobile.afk.api.AfkAlert;
import gg.projects.mobile.afk.api.AfkLine;
import gg.projects.mobile.afk.api.AfkSendResult;
import gg.projects.mobile.afk.api.AfkStatus;
import gg.projects.mobile.afk.service.AfkService;

/**
 * PHONE-AFK: the app's side of the link to the {@code :afk} service. Bound without {@code BIND_AUTO_CREATE}: it never
 * starts the service (only the player's tap does) and it connects by itself whenever the service runs. {@code HELLO}
 * asks for the statuses only, or with {@code full} also the console lines and alerts (a screen watches). Main thread.
 */
@MainThread
public final class AfkClient implements ServiceConnection {
    private static final String TAG = "ProjectsAfk";

    public interface Sink {
        void connected(boolean connected);

        void statuses(List<AfkStatus> all);

        void status(AfkStatus status);

        void backlog(String accountId, List<AfkLine> lines);

        void line(AfkLine line);

        void alert(AfkAlert alert);

        void sendResult(int request, AfkSendResult result);
    }

    private final Context app;
    private final Sink sink;
    private final Messenger incoming;
    @Nullable
    private Messenger service;
    private boolean bound;
    private boolean full;

    public AfkClient(Context context, Sink sink) {
        this.app = context.getApplicationContext();
        this.sink = sink;
        this.incoming = new Messenger(new Handler(Looper.getMainLooper(), this::handle));
    }

    public void open() {
        if (bound) return;
        try {
            bound = app.bindService(new Intent(app, AfkService.class), this, 0);
        } catch (RuntimeException e) {
            Log.w(TAG, "AFK link not bound", e);
            bound = false;
        }
    }

    public void close() {
        if (!bound) return;
        send(AfkIpc.BYE, 0, null);
        try {
            app.unbindService(this);
        } catch (RuntimeException ignored) {
            // Already gone.
        }
        bound = false;
        service = null;
    }

    public boolean connected() {
        return service != null;
    }

    /** Lines and alerts too ({@code true}: a screen watches) or the statuses only. */
    public void setFull(boolean full) {
        if (this.full == full) return;
        this.full = full;
        if (service != null) hello();
    }

    public boolean send(int what, int arg1, @Nullable Bundle data) {
        Messenger target = service;
        if (target == null) return false;
        Message m = Message.obtain(null, what, arg1, 0);
        m.replyTo = incoming;
        if (data != null) m.setData(data);
        try {
            target.send(m);
            return true;
        } catch (RemoteException e) {
            service = null;
            return false;
        }
    }

    private void hello() {
        send(AfkIpc.HELLO, full ? 1 : 0, null);
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        service = new Messenger(binder);
        hello();
        sink.connected(true);
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        // The process died; the binding stays and connects again when the service starts.
        service = null;
        sink.connected(false);
    }

    @Override
    public void onBindingDied(ComponentName name) {
        service = null;
        bound = false;
        try {
            app.unbindService(this);
        } catch (RuntimeException ignored) {
            // Already gone.
        }
        sink.connected(false);
        open();
    }

    private boolean handle(Message m) {
        Bundle data = m.getData();
        String json = data == null ? null : data.getString(AfkIpc.KEY_JSON);
        switch (m.what) {
            case AfkIpc.STATUSES:
                sink.statuses(AfkIpc.statuses(json));
                return true;
            case AfkIpc.STATUS:
                if (json != null) sink.status(AfkIpc.status(AfkIpc.object(json)));
                return true;
            case AfkIpc.BACKLOG:
                if (data != null) sink.backlog(data.getString(AfkIpc.KEY_ACCOUNT, ""), AfkIpc.lines(json));
                return true;
            case AfkIpc.LINE:
                if (json != null) sink.line(AfkIpc.line(AfkIpc.object(json)));
                return true;
            case AfkIpc.ALERT:
                AfkAlert alert = json == null ? null : AfkIpc.alert(AfkIpc.object(json));
                if (alert != null) sink.alert(alert);
                return true;
            case AfkIpc.SEND_RESULT:
                sink.sendResult(m.arg1, AfkSendResult.ofKey(data == null ? null : data.getString(AfkIpc.KEY_TEXT)));
                return true;
            default:
                return false;
        }
    }
}
