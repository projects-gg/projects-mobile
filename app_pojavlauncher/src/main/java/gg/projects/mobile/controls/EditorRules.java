package gg.projects.mobile.controls;

import java.io.File;
import java.io.IOException;

/**
 * CTRL-SAVE (1 Nov 2026): the controls editor's decisions, apart from its views (the tests run them on the JVM).
 *
 * <p>Upstream counted only an added button as a change: moving, resizing or restyling a key left the layout "not
 * modified", so leaving the editor asked only "Çıkılsın mı?" (yes / no, no save) and everything went back to the
 * saved layout. Every real edit now counts, and leaving with one asks once whether to save.
 */
public final class EditorRules {
    private EditorRules() {}

    /** What leaving the editor does. */
    public enum Leave {
        /** Nothing changed since the layout was read or saved: the editor closes at once, no question. */
        NOW,
        /** Something changed: "Değişiklikler kaydedilsin mi?" with "Kaydet ve çık" and "Kaydetmeden çık". */
        ASK
    }

    public static Leave leave(boolean modified) {
        return modified ? Leave.ASK : Leave.NOW;
    }

    /**
     * True once a finger on a key in the editor has travelled past {@code slop} (raw pixels) on either axis: from
     * then on the key follows it. Before, upstream moved the key from the first pixel, so a tap that trembled (to
     * open the key's settings) shifted it, snapped it to a neighbour and rewrote its place as a fraction of the
     * screen.
     */
    public static boolean dragged(float dxRaw, float dyRaw, float slop) {
        return Math.abs(dxRaw) > slop || Math.abs(dyRaw) > slop;
    }

    /**
     * True when "Yükle" put another file's layout in the editor than the one the game reads: that is a change to
     * save ("Kaydet" writes it into the layout in use), not the layout as saved.
     */
    public static boolean loadedAnother(String loaded, String active) {
        return !sameFile(loaded, active);
    }

    /** True when both paths name one file ("controlmap/./default.json" and "controlmap/default.json"). */
    public static boolean sameFile(String a, String b) {
        if (a == null || b == null) return a == null && b == null;
        return canonical(a).equals(canonical(b));
    }

    private static String canonical(String path) {
        File file = new File(path);
        try {
            return file.getCanonicalPath();
        } catch (IOException e) {
            return file.getAbsolutePath();
        }
    }
}
