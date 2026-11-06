package gg.projects.mobile.afk.debug;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import gg.projects.mobile.afk.api.AfkAccount;
import gg.projects.mobile.afk.api.AfkAlert;
import gg.projects.mobile.afk.api.AfkDraft;
import gg.projects.mobile.afk.api.AfkDraftException;
import gg.projects.mobile.afk.api.AfkLine;
import gg.projects.mobile.afk.api.AfkListener;
import gg.projects.mobile.afk.api.AfkStatus;
import gg.projects.mobile.afk.api.PhoneAfk;
import gg.projects.mobile.afk.store.AfkDrafts;

/**
 * Debug builds only (PHONE-AFK tests on an emulator, before and beside the screens): drives {@link PhoneAfk} from adb
 * and writes what it hears to logcat (tag ProjectsAfkDebug). Only the shell (which holds DUMP) may send it.
 * <pre>
 * adb shell am broadcast -n &lt;pkg&gt;/gg.projects.mobile.afk.debug.AfkDebugReceiver --es cmd add --es name SleepyFox_42 \
 *     --es address 10.0.2.2:25565 [--es login "/login x"]      (a local test server is allowed here)
 *     cmd start|stop --es name SleepyFox_42 ; cmd stopall ; cmd send --es name .. --es text .. ; cmd dump ; cmd watch
 * </pre>
 * The first command also turns the feature on for this debug install (the file PhoneAfk reads).
 */
public final class AfkDebugReceiver extends BroadcastReceiver {
    private static final String TAG = "ProjectsAfkDebug";
    private static boolean watching;

    @Override
    public void onReceive(Context context, Intent intent) {
        File ext = context.getExternalFilesDir(null);
        if (ext != null) {
            try {
                //noinspection ResultOfMethodCallIgnored
                new File(ext, "projects-afk-debug-on").createNewFile();
            } catch (java.io.IOException ignored) {
                // The switch stays as Studio says.
            }
        }
        AfkDrafts.allowLocalForTests = true;
        PhoneAfk afk = PhoneAfk.get(context);
        String cmd = intent.getStringExtra("cmd");
        String name = intent.getStringExtra("name");
        AfkAccount account = null;
        for (AfkAccount a : afk.accounts()) {
            if (a.name.equalsIgnoreCase(name == null ? "" : name)) account = a;
        }
        if (cmd == null) return;
        switch (cmd) {
            case "add": {
                AfkDraft d = new AfkDraft();
                d.name = name == null ? "" : name;
                d.address = intent.getStringExtra("address");
                String login = intent.getStringExtra("login");
                d.loginCommands = login == null ? new ArrayList<String>() : new ArrayList<>(Arrays.asList(login.split(";")));
                d.mobileData = intent.getBooleanExtra("mobile", false);
                try {
                    Log.i(TAG, "added " + afk.add(d));
                } catch (AfkDraftException e) {
                    Log.w(TAG, "not added: " + e.problems);
                }
                break;
            }
            case "start":
                if (account != null) Log.i(TAG, "start " + account.name + ": " + afk.start(account.id));
                break;
            case "stop":
                if (account != null) afk.stop(account.id);
                break;
            case "stopall":
                afk.stopAll();
                break;
            case "send":
                if (account != null) {
                    afk.send(account.id, intent.getStringExtra("text"), r -> Log.i(TAG, "send: " + r));
                }
                break;
            case "dump":
                for (AfkStatus s : afk.statuses()) Log.i(TAG, "status " + s + " since " + s.since + " attempt " + s.attempt);
                Log.i(TAG, "lastEnd " + afk.lastEnd());
                break;
            case "watch":
                watch(afk);
                break;
            default:
                Log.w(TAG, "unknown cmd " + cmd);
        }
    }

    private static void watch(PhoneAfk afk) {
        if (watching) return;
        watching = true;
        new Handler(Looper.getMainLooper()).post(() -> afk.watch(new AfkListener() {
            @Override
            public void onStatus(AfkStatus status) {
                Log.i(TAG, "status " + status);
            }

            @Override
            public void onStatuses(List<AfkStatus> all) {
                Log.i(TAG, "statuses " + all);
            }

            @Override
            public void onLine(AfkLine line) {
                Log.i(TAG, "line " + line.kind + " " + line.text);
            }

            @Override
            public void onAlert(AfkAlert alert) {
                Log.i(TAG, "alert " + alert.kind + " " + alert.text);
            }
        }));
    }
}
