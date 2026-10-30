package gg.projects.mobile.update;

import java.util.Collections;
import java.util.List;

/**
 * APK-UPD: where the Android release channel is and which keys may sign it (docs/content-format.md,
 * "Android release channel"). The channel lives on the content mirrors ({@code <mirror>android/<channel>.json}
 * and its {@code .sig}); it is signed with the launcher's updater key, the one in the launcher's
 * {@code tauri.conf.json} ({@code plugins.updater.pubkey}, key id 260676434E1A5359). A new key comes as the
 * launcher's does: a build that trusts the old and the new key first, the new key's signatures later.
 */
public final class UpdateConfig {
    private UpdateConfig() {}

    /** The channel this app reads. */
    public static final String CHANNEL = "stable";

    /** The launcher's updater public key (minisign's key line). */
    public static final List<String> TRUSTED_KEYS = Collections.singletonList("RWRZUxpOQ3YGJrAPxah2aKj66geKiruOXpziNcYfp8i/Fr1Czb+XJa2N");

    /** The channel file's path under a mirror base. */
    public static String channelPath(String channel) {
        return "android/" + channel + ".json";
    }

    /** The name the downloaded APK takes in the app's folder. */
    public static String apkName(UpdateManifest manifest) {
        return "projects-launcher-" + manifest.versionCode + ".apk";
    }
}
