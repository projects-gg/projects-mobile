package net.kdt.pojavlaunch.lifecycle;

import static net.kdt.pojavlaunch.MainActivity.INTENT_MINECRAFT_VERSION;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import net.kdt.pojavlaunch.MainActivity;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.lifecycle.ContextExecutor;
import net.kdt.pojavlaunch.lifecycle.ContextExecutorTask;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.tasks.AsyncMinecraftDownloader;
import net.kdt.pojavlaunch.utils.NotificationUtils;

public class ContextAwareDoneListener implements AsyncMinecraftDownloader.DoneListener, ContextExecutorTask {
    private final String mErrorString;
    private final String mNormalizedVersionid;
    // Projects fork (M17): the Projects profile's start never opens the game while the player is away
    // from the home screen; its outcome goes to the home screen (gg.projects.mobile.join.JoinOutcome).
    private final Context mAppContext;
    private final boolean mProjects;

    public ContextAwareDoneListener(Context baseContext, String versionId) {
        this.mErrorString = baseContext.getString(R.string.mc_download_failed);
        this.mNormalizedVersionid = versionId;
        this.mAppContext = baseContext.getApplicationContext();
        this.mProjects = gg.projects.mobile.join.ProjectsProfile.KEY.equals(
                LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, ""));
    }

    private Intent createGameStartIntent(Context context) {
        Intent mainIntent = new Intent(context, MainActivity.class);
        mainIntent.putExtra(INTENT_MINECRAFT_VERSION, mNormalizedVersionid);
        mainIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return mainIntent;
    }

    @Override
    public void onDownloadDone() {
        ProgressKeeper.waitUntilDone(()->ContextExecutor.execute(this));
    }

    @Override
    public void onDownloadFailed(Throwable throwable) {
        if (mProjects) {
            // Projects fork (M17): said in the app's words on the home screen, now or when the player is
            // back; "check your internet" only for a connection problem (not a full disk).
            gg.projects.mobile.join.JoinOutcome.failed(mAppContext, gg.projects.mobile.join.FailureKind.isNetwork(throwable, false),
                    true, gg.projects.mobile.join.ProjectsJoin.details(throwable));
            return;
        }
        Tools.showErrorRemote(mErrorString, throwable);
    }

    @Override
    public void executeWithActivity(Activity activity) {
        if (mProjects && !gg.projects.mobile.join.JoinOutcome.homeVisible()) {
            // Projects fork (M17): the player left the home screen (Settings, the mod screen...): the
            // game does not open over it; "Projects hazır" says Play is ready.
            gg.projects.mobile.join.JoinOutcome.filesReadyAway(activity);
            return;
        }
        try {
            Intent gameStartIntent = createGameStartIntent(activity);
            activity.startActivity(gameStartIntent);
            activity.finish();
            android.os.Process.killProcess(android.os.Process.myPid()); //You should kill yourself, NOW!
        } catch (Throwable e) {
            Tools.showError(activity.getBaseContext(), e);
        }
    }

    @Override
    public void executeWithApplication(Context context) {
        if (mProjects) {
            // Projects fork (M17): the app is in the background: "Projects hazır" opens the app with Play
            // ready instead of starting the game from the notification.
            gg.projects.mobile.join.JoinOutcome.filesReadyAway(context);
            return;
        }
        Intent gameStartIntent = createGameStartIntent(context);
        // Since the game is a separate process anyway, it does not matter if it gets invoked
        // from somewhere other than the launcher activity.
        // The only problem may arise if the launcher starts doing something when the user starts the notification.
        // So, the notification is automatically removed once there are tasks ongoing in the ProgressKeeper
        NotificationUtils.sendBasicNotification(context,
                R.string.notif_download_finished,
                R.string.notif_download_finished_desc,
                gameStartIntent,
                NotificationUtils.PENDINGINTENT_CODE_GAME_START,
                NotificationUtils.NOTIFICATION_ID_GAME_START
        );
        // You should keep yourself safe, NOW!
        // otherwise android does weird things...
    }
}
