package net.kdt.pojavlaunch.services;

import android.content.Context;
import android.util.Log;

import net.kdt.pojavlaunch.progresskeeper.TaskCountListener;

public class ProgressServiceKeeper implements TaskCountListener {
    private final Context context;
    public ProgressServiceKeeper(Context ctx) {
        this.context = ctx;
    }

    @Override
    public void onUpdateTaskCount(int taskCount) {
        if (taskCount <= 0) return;
        try {
            ProgressService.startService(context);
        } catch (RuntimeException e) {
            // Projects fork (M18): Android 12+ refuses a foreground service an app in the background starts
            // anew (the running one is kept a moment after its tasks end, so a next step rarely needs this).
            // The task goes on without it rather than crashing the app.
            Log.w("ProgressServiceKeeper", "progress service not started", e);
        }
    }
}
