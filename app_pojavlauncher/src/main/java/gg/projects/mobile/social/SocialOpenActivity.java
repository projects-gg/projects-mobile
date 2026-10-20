package gg.projects.mobile.social;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.Nullable;

/**
 * Where a tap on a friends notification goes (not exported: only the app's own notifications start
 * it). The friends list, the crew page or the invite; while the game is on screen nothing is taken
 * away from it and the place waits for the launcher.
 */
public final class SocialOpenActivity extends Activity {
    private static final String EXTRA_TARGET = "gg.projects.mobile.social.TARGET";
    private static final String EXTRA_ARG = "gg.projects.mobile.social.ARG";

    static PendingIntent open(Context context, String target, @Nullable String arg) {
        Intent intent = new Intent(context, SocialOpenActivity.class)
                .putExtra(EXTRA_TARGET, target)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (arg != null) intent.putExtra(EXTRA_ARG, arg);
        int code = (target + "|" + arg).hashCode();
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getActivity(context, code, intent, flags);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        String target = intent == null ? null : intent.getStringExtra(EXTRA_TARGET);
        if (target != null) {
            SocialRoutes.set(target, intent.getStringExtra(EXTRA_ARG));
            SocialRoutes.bringUp(this);
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
