package gg.projects.mobile.update;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * APK-UPD: hands the verified APK to Android's installer in a session. On Android 12 and later the session
 * asks for no prompt: Android installs without one when it allows an app to update itself (it decides; the
 * app holds REQUEST_INSTALL_PACKAGES and, from Android 13, UPDATE_PACKAGES_WITHOUT_USER_ACTION), otherwise
 * it answers STATUS_PENDING_USER_ACTION and its own confirmation opens ({@link UpdateReceiver}). The first
 * update of a sideloaded install takes the player's tap there, with Android's "unknown apps" switch if it
 * is off. The result comes to {@link UpdateReceiver}.
 */
public final class ApkInstaller {
    private ApkInstaller() {}

    public static final String ACTION_STATUS = "gg.projects.mobile.update.INSTALL_STATUS";

    /** Writes {@code apk} into a new session and commits it; the session is abandoned on any failure. */
    @SuppressLint("NewApi")
    public static void install(Context context, File apk) throws UpdateException {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        params.setSize(apk.length());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) params.setInstallReason(PackageManager.INSTALL_REASON_USER);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int sessionId = -1;
        PackageInstaller.Session session = null;
        try {
            sessionId = installer.createSession(params);
            session = installer.openSession(sessionId);
            try (InputStream in = new FileInputStream(apk); OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                session.fsync(out);
            }
            Intent intent = new Intent(ACTION_STATUS).setPackage(context.getPackageName()).setClass(context, UpdateReceiver.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            // Android fills in the status: the intent must stay mutable (Android 12+ asks for it to be said).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent pending = PendingIntent.getBroadcast(context, sessionId, intent, flags);
            session.commit(pending.getIntentSender());
            session.close();
            session = null;
        } catch (IOException | RuntimeException e) {
            if (session != null) session.abandon();
            else if (sessionId >= 0) abandon(installer, sessionId);
            String text = String.valueOf(e);
            UpdateException.Kind kind = text.contains("ENOSPC") || text.contains("No space left")
                    ? UpdateException.Kind.SPACE : UpdateException.Kind.INSTALL_FAILED;
            throw new UpdateException(kind, "the install session failed: " + text, e);
        }
    }

    private static void abandon(PackageInstaller installer, int sessionId) {
        try {
            installer.abandonSession(sessionId);
        } catch (RuntimeException ignored) {
        }
    }
}
