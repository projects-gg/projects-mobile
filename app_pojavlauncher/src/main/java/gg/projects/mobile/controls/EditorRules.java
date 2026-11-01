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

    /**
     * APK-REL (0.4.9's emulator run): where the editor's bar ("Menü", "Kaydet") starts from the left. At the top
     * centre while no key is there; else at the free place nearest to the centre. The default layout's two drawers open
     * their keys up to 410 dp from the left edge, so on a phone held sideways (narrower than about 980 dp, nearly every
     * phone) the centred bar sat under "MİKROFON" and the player saw "enü". {@code lefts} and {@code rights} are the
     * edges of the keys in the bar's band, {@code gap} the room kept beside a key, {@code margin} the room kept from the
     * screen's edges. With no free place the bar stays centred: the keys are drawn over it and stay reachable.
     */
    public static int barLeft(int width, int barWidth, int[] lefts, int[] rights, int gap, int margin) {
        int centred = (width - barWidth) / 2;
        int min = margin;
        int max = width - margin - barWidth;
        if (max < min || lefts.length != rights.length) return centred;
        int best = centred;
        long bestDistance = Long.MAX_VALUE;
        // The places worth trying: the centre, and just after or just before each key.
        int[] tries = new int[1 + 2 * lefts.length];
        tries[0] = centred;
        for (int i = 0; i < lefts.length; i++) {
            tries[1 + 2 * i] = rights[i] + gap;
            tries[2 + 2 * i] = lefts[i] - gap - barWidth;
        }
        for (int at : tries) {
            int x = Math.max(min, Math.min(max, at));
            if (!clearOfKeys(x, barWidth, lefts, rights, gap)) continue;
            long distance = Math.abs((long) x - centred);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = x;
            }
        }
        return best;
    }

    /**
     * APK-REL (0.4.9's emulator run): whether the launcher screen that comes up {@code now} shows the save's line
     * ("Tuşlar kaydedildi." with "Geri al") the editor left behind. {@code shownAt} is when it first showed (0: not yet).
     * A screen relaunched within {@code againMs} of it (the turn back from the sideways editor relaunches the launcher)
     * shows it again; a later return does not.
     */
    public static boolean showSavedLine(long shownAt, long now, long againMs) {
        return shownAt == 0 || now - shownAt <= againMs;
    }

    private static boolean clearOfKeys(int x, int barWidth, int[] lefts, int[] rights, int gap) {
        for (int i = 0; i < lefts.length; i++) {
            if (x < rights[i] + gap && x + barWidth > lefts[i] - gap) return false;
        }
        return true;
    }
}
