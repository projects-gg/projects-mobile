package gg.projects.mobile.cloud.items;

import android.util.Log;

import java.io.File;

/**
 * {@code pl-items} on the phone: the launcher's own item-icon drawing (crates/pl-items, ITEMART), built for Android as
 * {@code libplitems.so} (src/main/rust/plitems; NOTICE). One call draws a client jar's atlas and index into a folder:
 * the same drawing and index format the launcher and the site use. When the library is missing (an ABI it was not built
 * for), items keep their stand-ins.
 */
public final class PlItems {
    private static final String TAG = "ProjectsItemArt";
    /** pl-items' ATLAS_REVISION of the built library: a new drawing invalidates the cached atlases. */
    public static final int REVISION = 6;
    private static final boolean LOADED = load();

    private PlItems() {}

    private static boolean load() {
        try {
            System.loadLibrary("plitems");
            return true;
        } catch (Throwable e) {
            Log.i(TAG, "pl-items is not in this build: stand-ins only");
            return false;
        }
    }

    public static boolean available() {
        return LOADED;
    }

    /** The size 3D icons are drawn at for items {@code itemPx} wide ({@link ItemIndex#drawingSize}). */
    public static int blockSize(int itemPx) {
        return ItemIndex.drawingSize(itemPx);
    }

    /** Draws {@code jar}'s atlas into {@code out} ({@code atlas.png}, {@code index.json}); false when it could not. */
    static boolean build(File jar, File out, String version, int blockSize) {
        if (!LOADED) return false;
        //noinspection ResultOfMethodCallIgnored
        out.mkdirs();
        try {
            return nativeBuild(jar.getAbsolutePath(), out.getAbsolutePath(), version, blockSize);
        } catch (Throwable e) {
            Log.w(TAG, "pl-items failed", e);
            return false;
        }
    }

    private static native boolean nativeBuild(String jar, String outDir, String version, int blockSize);
}
