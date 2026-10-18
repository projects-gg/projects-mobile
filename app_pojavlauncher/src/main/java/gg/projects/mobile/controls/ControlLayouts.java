package gg.projects.mobile.controls;

import android.content.Context;
import android.util.Log;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.Tools;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import gg.projects.mobile.content.AtomicFiles;
import gg.projects.mobile.language.LanguageManager;

/**
 * Keeps the default control layout ({@code controlmap/default.json}, and upstream's
 * {@code new_default.json} beside an edited one) in the app's language ({@link ControlLabels}):
 * a default nobody edited is replaced by the one of the current language, an edited one keeps its
 * buttons and only its untouched default labels are translated. Runs at start and when the
 * language changes; the game reads the layout when it starts.
 */
public final class ControlLayouts {
    private ControlLayouts() {}

    private static final String TAG = "ControlLayouts";
    private static final long MAX_LAYOUT_BYTES = 4L << 20;

    /** Brings the layouts in line with the app's language; returns the default layout asset of that language. */
    public static synchronized String sync(Context context) {
        boolean turkish = LanguageManager.isTurkish(context);
        String asset = ControlLabels.assetFor(turkish);
        try {
            byte[] turkishLayout = readAsset(context, ControlLabels.TURKISH_ASSET);
            byte[] englishLayout = readAsset(context, ControlLabels.ENGLISH_ASSET);
            byte[] wanted = turkish ? turkishLayout : englishLayout;
            follow(new File(Tools.CTRLDEF_FILE), wanted, turkish, turkishLayout, englishLayout);
            follow(new File(Tools.CTRLMAP_PATH, "new_default.json"), wanted, turkish, turkishLayout, englishLayout);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "control layouts left as they are", e);
        }
        return asset;
    }

    /** {@link #sync} off the main thread (after the player picks another language). */
    public static void syncInBackground(final Context context) {
        PojavApplication.sExecutorService.execute(() -> sync(context));
    }

    private static void follow(File installed, byte[] wanted, boolean turkish, byte[] turkishLayout, byte[] englishLayout)
            throws IOException {
        if (!installed.isFile()) return;
        byte[] current = AtomicFiles.read(installed, MAX_LAYOUT_BYTES);
        if (ControlLabels.isUntouchedDefault(current, turkishLayout, englishLayout)) {
            if (ControlLabels.sameLayout(current, wanted)) return;
            AtomicFiles.write(installed, wanted);
            Log.i(TAG, installed.getName() + ": the untouched default now has the " + (turkish ? "Turkish" : "English") + " labels");
            return;
        }
        String translated = ControlLabels.translate(new String(current, StandardCharsets.UTF_8), turkish);
        if (translated == null) return;
        AtomicFiles.write(installed, translated.getBytes(StandardCharsets.UTF_8));
        Log.i(TAG, installed.getName() + ": default labels in an edited layout translated");
    }

    private static byte[] readAsset(Context context, String name) throws IOException {
        try (InputStream in = context.getAssets().open(name)) {
            return AtomicFiles.readBounded(in, MAX_LAYOUT_BYTES);
        }
    }
}
