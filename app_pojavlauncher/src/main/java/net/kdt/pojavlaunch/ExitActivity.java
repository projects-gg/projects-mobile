package net.kdt.pojavlaunch;

import static net.kdt.pojavlaunch.Tools.shareLog;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import gg.projects.mobile.launch.ProjectsRelaunch;
import gg.projects.mobile.launch.SodiumFallback;

@Keep
public class ExitActivity extends AppCompatActivity {
    /** Projects fork: the game process may still be writing its last log lines when this opens. */
    private static final long SETTLE_MS = 400;

    /** Projects fork: in the app's language on Android 12 and older too (13+ has the app locale). */
    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(net.kdt.pojavlaunch.utils.LocaleUtils.setLocale(newBase));
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Projects fork: Android may have ended the launcher process while the game ran. A new one has
        // not set the storage paths up yet, and the game log is read and shared from there.
        if (Tools.checkStorageRoot(this)) Tools.initStorageConstants(getApplicationContext());
        int code = -1;
        boolean isSignal = false;
        Bundle extras = getIntent().getExtras();
        if(extras != null) {
            code = extras.getInt("code",-1);
            isSignal = extras.getBoolean("isSignal", false);
        }
        // Projects fork: when Sodium kept the game from starting, it is switched off and the player
        // is told in one line, with "start again" (upstream offered to delete it before every start).
        // Otherwise a short, code-free message replaces upstream's exit code and log file name.
        final int exitCode = code;
        final boolean signal = isSignal;
        final Context app = getApplicationContext();
        new Handler(Looper.getMainLooper()).postDelayed(() -> PojavApplication.sExecutorService.execute(() -> {
            SodiumFallback.Result result;
            try {
                result = SodiumFallback.inspect(app, exitCode, signal);
            } catch (RuntimeException e) {
                Log.w("ExitActivity", "exit not inspected", e);
                result = null;
            }
            final SodiumFallback.Result finalResult = result;
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) show(finalResult);
            });
        }), SETTLE_MS);
    }

    private void show(@Nullable SodiumFallback.Result result) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setOnDismissListener(dialog -> ExitActivity.this.finish());
        if (result != null && result.outcome == SodiumFallback.Outcome.PROJECTS) {
            builder.setMessage(R.string.projects_sodium_crash_projects)
                    .setPositiveButton(R.string.projects_sodium_start_again, (d, w) -> ProjectsRelaunch.start(this))
                    .setNegativeButton(R.string.projects_close, null);
        } else if (result != null && result.outcome == SodiumFallback.Outcome.OWN_PROFILE && result.modsDir != null) {
            final java.io.File mods = result.modsDir;
            builder.setMessage(R.string.projects_sodium_crash_own)
                    .setPositiveButton(R.string.projects_sodium_disable_and_start, (d, w) -> {
                        final Context app = getApplicationContext();
                        PojavApplication.sExecutorService.execute(() -> {
                            SodiumFallback.disableOwn(mods);
                            ProjectsRelaunch.start(app);
                        });
                    })
                    .setNegativeButton(R.string.projects_close, null);
        } else {
            builder.setMessage(R.string.projects_game_closed)
                    .setPositiveButton(R.string.projects_close, null)
                    .setNeutralButton(R.string.projects_share_log, (dialog, which) -> shareLog(this));
        }
        builder.show();
    }

    public static void showExitMessage(Context ctx, int code, boolean isSignal) {
        Intent i = new Intent(ctx,ExitActivity.class);
        i.putExtra("code",code);
        i.putExtra("isSignal", isSignal);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }

}
