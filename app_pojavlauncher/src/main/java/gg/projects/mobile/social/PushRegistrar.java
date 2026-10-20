package gg.projects.mobile.social;

import android.content.Context;
import android.util.Log;

import androidx.annotation.MainThread;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;

import net.kdt.pojavlaunch.BuildConfig;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * This phone's registration for pushes (soc6.md "Telefon kaydı"): {@code PUT /v1/push/device} after
 * sign-in, when FCM gives a new token, when the player's switches change and otherwise at most once
 * a day; {@code DELETE} when nothing is wanted any more (switches off, notifications not allowed,
 * Friends off). Only while the service announces {@code push} and this build carries Firebase. The
 * FCM token is asked for only then: a player who never signs in never gets one (auto-init is off in
 * the manifest).
 */
public final class PushRegistrar {
    private PushRegistrar() {}

    private static final String TAG = "ProjectsSocial";

    /** Firebase is set up in this process (the config was in the build). */
    static boolean firebaseReady(Context context) {
        if (!BuildConfig.PROJECTS_FIREBASE) return false;
        try {
            if (FirebaseApp.getApps(context).isEmpty()) FirebaseApp.initializeApp(context);
            return !FirebaseApp.getApps(context).isEmpty();
        } catch (RuntimeException e) {
            Log.w(TAG, "Firebase not available", e);
            return false;
        }
    }

    @MainThread
    static void sync(final Context context, final SocialService service, final Runnable done) {
        final String user = service.signedInId();
        if (user != null && service.enabled() && !service.state().loaded()) {
            // What the service announces (features: push) comes with the list: nothing is changed before it,
            // or every cold start would end the registration and make it again.
            done.run();
            return;
        }
        final String session = service.token();
        final List<String> kinds = service.wantedKinds();
        final SocialPrefs prefs = service.prefs();
        boolean push = service.gate().push && service.enabled();
        final String debugToken = service.debugPushToken();
        if (user == null || !push || kinds.isEmpty() || (debugToken == null && !firebaseReady(context))) {
            String registered = prefs.pushRegistered();
            if (user != null && registered.startsWith(user + "|")) {
                // Nothing wanted any more: the service stops sending to this phone.
                service.io().execute(() -> {
                    service.api().bind(session);
                    try {
                        service.api().deletePushDevice();
                        service.main().post(() -> {
                            prefs.setPushRegistered("");
                            done.run();
                        });
                    } catch (SocialProblem problem) {
                        Log.i(TAG, "push registration not removed: " + problem.kind);
                        service.main().post(done);
                    } finally {
                        service.api().bind(null);
                    }
                });
            } else {
                done.run();
            }
            return;
        }
        if (debugToken != null) {
            register(context, service, session, user, kinds, debugToken, done);
            return;
        }
        FirebaseMessaging messaging;
        try {
            messaging = FirebaseMessaging.getInstance();
        } catch (RuntimeException e) {
            Log.w(TAG, "FCM not available", e);
            done.run();
            return;
        }
        messaging.getToken().addOnCompleteListener(task -> {
            if (!task.isSuccessful() || task.getResult() == null) {
                // No Google Play services, or no network: tried again next time the app comes up.
                Log.i(TAG, "no FCM token: " + (task.getException() == null ? "empty" : task.getException().getClass().getSimpleName()));
                done.run();
                return;
            }
            register(context, service, session, user, kinds, task.getResult(), done);
        });
    }

    /** {@code PUT /v1/push/device} unless the same registration was made today. */
    private static void register(final Context context, final SocialService service, final String session, final String user,
                                 final List<String> kinds, final String token, final Runnable done) {
        final SocialPrefs prefs = service.prefs();
        StringBuilder joined = new StringBuilder();
        for (String kind : kinds) joined.append(joined.length() == 0 ? "" : ",").append(kind);
        final String record = user + "|" + sha256(token) + "|" + joined + "|" + (SocialService.nowSeconds() / 86400);
        if (record.equals(prefs.pushRegistered()) || !user.equals(service.signedInId())) {
            done.run();
            return;
        }
        service.io().execute(() -> {
            service.api().bind(session);
            try {
                service.api().putPushDevice(token, context.getPackageName(), kinds);
                service.main().post(() -> {
                    if (user.equals(service.signedInId())) prefs.setPushRegistered(record);
                    Log.i(TAG, "push registered: " + kinds);
                    done.run();
                });
            } catch (SocialProblem problem) {
                Log.i(TAG, "push not registered: " + problem.kind);
                service.main().post(done);
            } finally {
                service.api().bind(null);
            }
        });
    }

    /** Signed out or the account deleted: this phone's FCM token is thrown away. */
    static void forget(Context context) {
        if (!firebaseReady(context)) return;
        try {
            FirebaseMessaging messaging = FirebaseMessaging.getInstance();
            messaging.setAutoInitEnabled(false);
            messaging.deleteToken();
        } catch (RuntimeException e) {
            Log.w(TAG, "FCM token not deleted", e);
        }
    }

    static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 8; i++) out.append(String.format("%02x", digest[i]));
            return out.toString();
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }
}
