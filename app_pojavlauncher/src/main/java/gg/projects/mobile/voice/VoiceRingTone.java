package gg.projects.mobile.voice;

import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

import androidx.annotation.Nullable;

/**
 * The ring while a screen of the app shows it (its notification rings by itself otherwise): our own chime, looped, as
 * the phone's ringer allows it (silent: nothing; vibrate: a soft pattern; do not disturb: nothing).
 */
final class VoiceRingTone {
    private final Context mApp;
    @Nullable private MediaPlayer mPlayer;
    private boolean mVibrating;

    VoiceRingTone(Context context) {
        mApp = context.getApplicationContext();
    }

    void set(boolean on) {
        if (on) start();
        else stop();
    }

    private void start() {
        if (mPlayer != null || mVibrating) return;
        AudioManager audio = (AudioManager) mApp.getSystemService(Context.AUDIO_SERVICE);
        NotificationManager notifications = (NotificationManager) mApp.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 23 && notifications != null
                && notifications.getCurrentInterruptionFilter() > NotificationManager.INTERRUPTION_FILTER_ALL) {
            return;
        }
        int mode = audio == null ? AudioManager.RINGER_MODE_NORMAL : audio.getRingerMode();
        if (mode == AudioManager.RINGER_MODE_SILENT) return;
        if (mode == AudioManager.RINGER_MODE_VIBRATE) {
            vibrate(true);
            return;
        }
        try {
            MediaPlayer player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            player.setDataSource(mApp, VoiceNotify.ringSound(mApp));
            player.setLooping(true);
            player.prepare();
            player.start();
            mPlayer = player;
        } catch (Exception e) {
            // A ring is a nicety: the card says it.
            mPlayer = null;
        }
    }

    private void stop() {
        if (mPlayer != null) {
            try {
                mPlayer.stop();
            } catch (IllegalStateException ignored) {
                // Not started.
            }
            mPlayer.release();
            mPlayer = null;
        }
        if (mVibrating) vibrate(false);
    }

    private void vibrate(boolean on) {
        Vibrator vibrator = (Vibrator) mApp.getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null) return;
        mVibrating = on;
        if (!on) {
            vibrator.cancel();
            return;
        }
        long[] pattern = {0, 300, 700, 300, 1300};
        if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
        else vibrator.vibrate(pattern, 0);
    }
}
