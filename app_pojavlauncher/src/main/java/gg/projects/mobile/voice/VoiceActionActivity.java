package gg.projects.mobile.voice;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import gg.projects.mobile.language.LanguageManager;
import gg.projects.mobile.social.SocialService;

/**
 * "Katıl" on a ring's notification: an invisible screen (not exported: only the app's own notifications start it), so
 * the stay starts from the app in front, which Android requires before an app may use the microphone. It asks for the
 * microphone first when the app may not use it (a "no" joins to listen), answers the ring and goes; the player stays
 * where they were (the game too), the stay's notification carries on.
 */
public final class VoiceActionActivity extends Activity {
    private static final String EXTRA_ROOM = "gg.projects.mobile.voice.ROOM";
    private static final String EXTRA_MIC = "gg.projects.mobile.voice.MIC_ONLY";
    /** What runs once the microphone was asked for ({@link #askMicThen}); this process only. */
    @Nullable private static Runnable sThen;
    private static final int ASK_MIC = 0x5A01;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    @Nullable private String mRoom;
    private boolean mDone;
    private boolean mMicOnly;

    public static PendingIntent join(Context context, String room) {
        Intent intent = new Intent(context, VoiceActionActivity.class)
                .putExtra(EXTRA_ROOM, room)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getActivity(context, ("join|" + room).hashCode(), intent, flags);
    }

    /**
     * Asks for the microphone over the current screen, then runs {@code then} whatever the answer (a "no" joins to
     * listen; the stay says "Mikrofona izin verilmedi." with "Ayarları aç").
     */
    public static void askMicThen(Context context, Runnable then) {
        sThen = then;
        context.startActivity(new Intent(context, VoiceActionActivity.class)
                .putExtra(EXTRA_MIC, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_MIC, false)) {
            mMicOnly = true;
            if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, ASK_MIC);
            } else {
                runThen();
            }
            return;
        }
        mRoom = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_ROOM);
        if (mRoom == null || !VoiceWire.isRoomId(mRoom)) {
            finishNow();
            return;
        }
        VoiceNotify.cancelRing(this, mRoom);
        // Never longer than this on screen, whatever happens.
        mMain.postDelayed(this::finishNow, 15_000);
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, ASK_MIC);
            return;
        }
        answer();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != ASK_MIC) return;
        if (mMicOnly) runThen();
        else answer();
    }

    /** The stay starts from here, while the app is in front (Android lets the microphone start only then). */
    private void runThen() {
        Runnable then = sThen;
        sThen = null;
        if (then != null) then.run();
        // A join that asks the service first starts its stay a moment later: stay up for it a little.
        mMain.postDelayed(this::finishNow, 1_500);
    }

    private void answer() {
        if (mDone || mRoom == null) return;
        // The friends feature in this process (it reads the session itself when the app was not running).
        SocialService.get(this);
        VoiceHub hub = VoiceHub.instance();
        if (hub == null) {
            finishNow();
            return;
        }
        hub.answer(mRoom, answer -> {
            if (!answer.ok()) {
                String text = VoiceNotify.refusalText(LanguageManager.wrap(this), answer, null);
                if (!text.isEmpty()) Toast.makeText(getApplicationContext(), text, Toast.LENGTH_LONG).show();
            }
            finishNow();
        });
    }

    private void finishNow() {
        if (mDone) return;
        mDone = true;
        mMain.removeCallbacksAndMessages(null);
        finish();
        overridePendingTransition(0, 0);
    }
}
