package gg.projects.mobile.social;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

/**
 * Firebase Cloud Messaging's way into the app (M19): the friends service's data messages become
 * notifications in the app's language ({@link SocialPush}); a new token is registered again.
 * Runs in the launcher's process, never the game's.
 */
public final class ProjectsMessagingService extends FirebaseMessagingService {
    @Override
    public void onMessageReceived(@NonNull RemoteMessage message) {
        SocialPush.handle(this, message.getData());
    }

    @Override
    public void onNewToken(@NonNull String token) {
        // The token itself is asked for again where it is used; this only says it changed.
        new Handler(Looper.getMainLooper()).post(() -> SocialService.get(getApplicationContext()).onNewPushToken());
    }
}
