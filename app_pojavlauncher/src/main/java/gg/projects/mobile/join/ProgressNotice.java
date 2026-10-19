package gg.projects.mobile.join;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.progresskeeper.ProgressListener;

import java.util.ArrayList;
import java.util.IllegalFormatException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M17: upstream's progress notification (its foreground service keeps an install running when the
 * player leaves the app) says what happens and how far it got, instead of "Projects Launcher
 * Önplan Hizmeti / 1 işlem sürüyor": while a Projects start runs, "Projects hazırlanıyor" over the
 * progress bar's own line ("Projects paketi indiriliyor (12,4 MB / 140,0 MB)", "Oyun dosyaları
 * denetleniyor… (x/y)") and a bar; any other work shows its line as the title. A tap opens the app.
 * It follows the same progress records as the app's progress bar and updates the notification at
 * most twice a second (Android drops faster updates anyway).
 */
public final class ProgressNotice {
    private static final String TAG = "ProjectsProgress";
    /** The records the app's progress bar shows, the most telling first. */
    private static final String[] RECORDS = {
            ProgressLayout.INSTALL_MODPACK, ProgressLayout.DOWNLOAD_MINECRAFT, ProgressLayout.UNPACK_RUNTIME,
            ProgressLayout.EXTRACT_COMPONENTS, ProgressLayout.EXTRACT_SINGLE_FILES,
            ProgressLayout.DOWNLOAD_VERSION_LIST, ProgressLayout.AUTHENTICATE_MICROSOFT,
    };
    private static final long MIN_GAP_MS = 500;

    private static final class Entry {
        int progress = -1;
        String line = "";
    }

    private final Context context;
    private final Runnable post;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final List<Object[]> listeners = new ArrayList<>();
    private long lastPost;
    private boolean posted;
    /** The service stopped: nothing posts its notification any more. */
    private boolean stopped;
    private final Runnable postNow = this::postNow;

    /** {@code post} shows the notification again (on the main thread) after {@link #fill}. */
    public ProgressNotice(Context context, Runnable post) {
        this.context = context.getApplicationContext();
        this.post = post;
    }

    /** Starts following the progress records. */
    @MainThread
    public void start() {
        for (final String record : RECORDS) {
            ProgressListener listener = new ProgressListener() {
                @Override
                public void onProgressStarted() {
                    changed(record, 0, null);
                }

                @Override
                public void onProgressUpdated(int progress, int resid, Object... va) {
                    changed(record, progress, line(resid, va));
                }

                @Override
                public void onProgressEnded() {
                    synchronized (entries) {
                        entries.remove(record);
                    }
                    schedule();
                }
            };
            listeners.add(new Object[]{record, listener});
            ProgressKeeper.addListener(record, listener);
        }
    }

    @MainThread
    public void stop() {
        stopped = true;
        for (Object[] pair : listeners) ProgressKeeper.removeListener((String) pair[0], (ProgressListener) pair[1]);
        listeners.clear();
        main.removeCallbacks(postNow);
    }

    private void changed(String record, int progress, String line) {
        synchronized (entries) {
            Entry entry = entries.get(record);
            if (entry == null) {
                entry = new Entry();
                entries.put(record, entry);
            }
            entry.progress = progress;
            if (line != null && !line.isEmpty()) entry.line = line;
        }
        schedule();
    }

    private void schedule() {
        main.post(() -> {
            if (posted || stopped) return;
            posted = true;
            long wait = Math.max(0, lastPost + MIN_GAP_MS - SystemClock.uptimeMillis());
            main.postDelayed(postNow, wait);
        });
    }

    private void postNow() {
        posted = false;
        if (stopped) return;
        lastPost = SystemClock.uptimeMillis();
        post.run();
    }

    /** The text a record shows, as the app's progress bar writes it. */
    private String line(int resid, Object[] va) {
        try {
            if (resid != -1) return context.getString(resid, va);
            if (va != null && va.length > 0 && va[0] instanceof String) return (String) va[0];
        } catch (IllegalFormatException | android.content.res.Resources.NotFoundException e) {
            Log.w(TAG, "progress text not shown", e);
        }
        return null;
    }

    /** Fills {@code builder} for {@code tasks} running tasks: the title, the line, the bar and the tap. */
    @MainThread
    public void fill(NotificationCompat.Builder builder, int tasks) {
        String line = null;
        int progress = -1;
        boolean game = false;
        synchronized (entries) {
            for (String record : RECORDS) {
                Entry entry = entries.get(record);
                if (entry == null) continue;
                if (line == null || line.isEmpty()) {
                    line = entry.line;
                    progress = entry.progress;
                }
                game |= record.equals(ProgressLayout.DOWNLOAD_MINECRAFT);
            }
        }
        boolean projects = ProjectsJoin.isJoining() || (game && projectsSelected());
        String title;
        String text;
        if (projects) {
            title = context.getString(R.string.projects_join_preparing);
            text = line != null && !line.isEmpty() ? line : null;
        } else if (line != null && !line.isEmpty()) {
            title = line;
            text = tasks > 1 ? context.getResources().getQuantityString(R.plurals.projects_tasks_in_progress, tasks, tasks) : null;
        } else {
            title = context.getString(R.string.app_name);
            text = context.getResources().getQuantityString(R.plurals.projects_tasks_in_progress, Math.max(1, tasks), Math.max(1, tasks));
        }
        builder.setContentTitle(title);
        builder.setContentText(text);
        builder.setColor(ContextCompat.getColor(context, R.color.projects_gold_500));
        if (progress >= 0 && progress <= 100) builder.setProgress(100, progress, false);
        else builder.setProgress(0, 0, true);
        Intent open = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (open != null) {
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            builder.setContentIntent(PendingIntent.getActivity(context, 0x4A170003, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0)));
        }
    }

    private static boolean projectsSelected() {
        try {
            return ProjectsProfile.KEY.equals(LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, ""));
        } catch (RuntimeException e) {
            return false;
        }
    }
}
