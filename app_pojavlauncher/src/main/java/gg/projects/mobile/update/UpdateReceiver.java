package gg.projects.mobile.update;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/**
 * APK-UPD: Android's answers about the app's own update. The install session's status ({@link ApkInstaller},
 * an explicit intent only Android fills in: the receiver is not exported) and the moment the app was
 * replaced (MY_PACKAGE_REPLACED, Android's broadcast to this package alone).
 */
public final class UpdateReceiver extends BroadcastReceiver {
    @Override
    @SuppressWarnings("deprecation")
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (ApkInstaller.ACTION_STATUS.equals(action)) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            Intent confirm = status == PackageInstaller.STATUS_PENDING_USER_ACTION
                    ? (Intent) intent.getParcelableExtra(Intent.EXTRA_INTENT) : null;
            AppUpdates.onInstallStatus(context, status, confirm, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
        } else if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            AppUpdates.onPackageReplaced(context);
        }
    }
}
