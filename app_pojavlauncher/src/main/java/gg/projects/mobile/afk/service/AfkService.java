package gg.projects.mobile.afk.service;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import gg.projects.mobile.afk.api.AfkLine;
import gg.projects.mobile.afk.api.AfkSendResult;
import gg.projects.mobile.afk.api.AfkState;
import gg.projects.mobile.afk.api.AfkStatus;

/**
 * PHONE-AFK (WIP, API step): the {@code :afk} process's foreground service. This first version only answers the link
 * (statuses, stop); the engine, notifications, network and power follow (docs/tasks/afk-m.md, steps 3–5).
 */
public final class AfkService extends Service {
    public static final String ACTION_START = "gg.projects.mobile.afk.START";
    public static final String ACTION_STOP = "gg.projects.mobile.afk.STOP";
    public static final String ACTION_STOP_ALL = "gg.projects.mobile.afk.STOP_ALL";
    public static final String EXTRA_ACCOUNT = "account";
    public static final String EXTRA_ERASE = "erase";

    public static Intent startIntent(Context context, String accountId) {
        return new Intent(context, AfkService.class).setAction(ACTION_START).putExtra(EXTRA_ACCOUNT, accountId);
    }

    public static Intent stopIntent(Context context, String accountId) {
        return new Intent(context, AfkService.class).setAction(ACTION_STOP).putExtra(EXTRA_ACCOUNT, accountId);
    }

    public static Intent stopAllIntent(Context context, boolean erase) {
        return new Intent(context, AfkService.class).setAction(ACTION_STOP_ALL).putExtra(EXTRA_ERASE, erase);
    }

    private final Map<String, AfkStatus> statuses = new LinkedHashMap<>();
    private AfkHub hub;

    @Override
    public void onCreate() {
        super.onCreate();
        hub = new AfkHub(new AfkHub.Engine() {
            @Override
            public List<AfkStatus> statuses() {
                return new ArrayList<>(statuses.values());
            }

            @Override
            public List<AfkLine> backlog(String accountId) {
                return Collections.emptyList();
            }

            @Override
            public List<String> runningIds() {
                return new ArrayList<>(statuses.keySet());
            }

            @Override
            public void send(String accountId, String text, AfkHub.Done<AfkSendResult> done) {
                done.done(AfkSendResult.NOT_IN_GAME);
            }

            @Override
            public void stop(String accountId) {
                statuses.remove(accountId);
                hub.statuses(statuses());
                if (statuses.isEmpty()) stopSelf();
            }

            @Override
            public void stopAll(boolean erase) {
                statuses.clear();
                hub.statuses(statuses());
                stopSelf();
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        AfkNotifications.startForeground(this);
        String action = intent == null ? null : intent.getAction();
        String id = intent == null ? null : intent.getStringExtra(EXTRA_ACCOUNT);
        if (ACTION_START.equals(action) && id != null) {
            long now = System.currentTimeMillis();
            AfkStatus s = new AfkStatus(id, "", AfkState.CONNECTING, now, 0, 0, 0, null, null);
            statuses.put(id, s);
            hub.status(s);
        } else if (ACTION_STOP.equals(action) && id != null) {
            statuses.remove(id);
            hub.statuses(new ArrayList<>(statuses.values()));
        } else {
            statuses.clear();
        }
        if (statuses.isEmpty()) stopSelf();
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return hub.messenger.getBinder();
    }
}
