package gg.projects.mobile.cloud.items;

import android.content.Context;

import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.Tools;

import java.io.File;
import java.util.regex.Pattern;

/**
 * The Minecraft client jars installed on this phone ({@code .minecraft/versions/<id>/<id>.jar}): the item pictures come
 * from them (the player's own game files; never downloaded for this and never shipped).
 */
public final class GameJars {
    private GameJars() {}

    /** A release or snapshot's own folder (not a loader's profile, which inherits its jar). */
    private static final Pattern GAME_VERSION = Pattern.compile("^(\\d+\\.\\d+(\\.\\d+)?(-(pre|rc)\\d+)?|\\d\\dw\\d\\d[a-z])$");

    /**
     * The jar of {@code version} when it is installed; else the newest installed game jar; null when there is none (the
     * stand-ins show).
     */
    @Nullable
    public static File find(Context context, @Nullable String version) {
        if (Tools.DIR_HOME_VERSION == null) return null;
        File versions = new File(Tools.DIR_HOME_VERSION);
        if (version != null && GAME_VERSION.matcher(version).matches()) {
            File exact = new File(versions, version + File.separator + version + ".jar");
            if (exact.isFile()) return exact;
        }
        File[] dirs = versions.listFiles(File::isDirectory);
        File newest = null;
        if (dirs == null) return null;
        for (File dir : dirs) {
            if (!GAME_VERSION.matcher(dir.getName()).matches()) continue;
            File jar = new File(dir, dir.getName() + ".jar");
            if (jar.isFile() && jar.length() > 1_000_000 && (newest == null || jar.lastModified() > newest.lastModified())) newest = jar;
        }
        return newest;
    }

    /** "26.1.2" from {@code .../versions/26.1.2/26.1.2.jar}. */
    public static String versionOf(File jar) {
        String name = jar.getName();
        return name.endsWith(".jar") ? name.substring(0, name.length() - 4) : name;
    }
}
