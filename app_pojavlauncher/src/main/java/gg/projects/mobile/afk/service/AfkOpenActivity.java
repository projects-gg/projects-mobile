package gg.projects.mobile.afk.service;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.Nullable;

import gg.projects.mobile.afk.api.PhoneAfk;
import gg.projects.mobile.social.SocialRoutes;

/**
 * PHONE-AFK: where a tap on an AFK notification goes (not exported: only the app's own notifications start it). It
 * leaves the AFK screen to open ({@link PhoneAfk#takeOpenRequest()}) and brings the launcher up as the friends'
 * notifications do; while the game is on screen nothing is taken away from it.
 */
public final class AfkOpenActivity extends Activity {
    private static final String EXTRA_ACCOUNT = "gg.projects.mobile.afk.ACCOUNT";

    /** A tap that opens the AFK list ({@code accountId} null) or one account. */
    public static PendingIntent open(Context context, @Nullable String accountId) {
        Intent intent = new Intent(context, AfkOpenActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (accountId != null) intent.putExtra(EXTRA_ACCOUNT, accountId);
        int code = ("afk|" + accountId).hashCode();
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getActivity(context, code, intent, flags);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        PhoneAfk.requestOpen(intent == null ? null : intent.getStringExtra(EXTRA_ACCOUNT));
        SocialRoutes.bringUp(this);
        finish();
        overridePendingTransition(0, 0);
    }
}
