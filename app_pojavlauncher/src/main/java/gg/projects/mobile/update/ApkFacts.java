package gg.projects.mobile.update;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

/**
 * APK-UPD: what Android reads from the downloaded APK and from the installed app: the package name, the
 * version code and the signing certificates (the APK contents' signers, so a certificate the app only rotated
 * away from never counts).
 */
public final class ApkFacts {
    public final String packageName;
    public final long versionCode;
    public final Set<String> signers;

    private ApkFacts(String packageName, long versionCode, Set<String> signers) {
        this.packageName = packageName;
        this.versionCode = versionCode;
        this.signers = signers;
    }

    /** The downloaded file's facts; null when Android cannot read it as an APK. */
    public static ApkFacts archive(Context context, File apk) {
        PackageManager pm = context.getPackageManager();
        PackageInfo info = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags());
        return info == null ? null : of(info);
    }

    /** This app as installed. */
    public static ApkFacts installed(Context context) {
        try {
            return of(context.getPackageManager().getPackageInfo(context.getPackageName(), flags()));
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalStateException("the app cannot find itself", e);
        }
    }

    @SuppressWarnings("deprecation")
    private static int flags() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
    }

    @SuppressLint("NewApi")
    @SuppressWarnings("deprecation")
    private static ApkFacts of(PackageInfo info) {
        Set<String> signers = new HashSet<>();
        Signature[] signatures = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            SigningInfo signing = info.signingInfo;
            if (signing != null) signatures = signing.getApkContentsSigners();
        } else {
            signatures = info.signatures;
        }
        if (signatures != null) for (Signature signature : signatures) signers.add(Signers.sha256(signature.toByteArray()));
        long versionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? info.getLongVersionCode() : info.versionCode;
        return new ApkFacts(info.packageName, versionCode, signers);
    }

    /** This app's version code as Android has it installed (a test build's own one included). */
    @SuppressLint("NewApi")
    @SuppressWarnings("deprecation")
    public static long installedVersionCode(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? info.getLongVersionCode() : info.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalStateException("the app cannot find itself", e);
        }
    }
}
