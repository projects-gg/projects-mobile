package gg.projects.mobile.controls;

import java.io.File;
import java.io.IOException;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * CTRL-SAVE (1 Nov 2026): the controls editor's decisions, apart from its views (the tests run them on the JVM).
 *
 * <p>Upstream counted only an added button as a change: moving, resizing or restyling a key left the layout "not
 * modified", so leaving the editor asked only "Çıkılsın mı?" (yes / no, no save) and everything went back to the
 * saved layout. Every real edit now counts, and leaving with one asks once whether to save.
 */
public final class EditorRules {
    private EditorRules() {}

    /**
     * MOB-NEXT (TASTE-2's P1): the game's two screens, whose keys the editor shows one at a time. The game shows a key in
     * play ("Oyunda"), in its menus ("Menülerde") or in both, as the key's two boxes say, so the default layout puts
     * twins in one place (DURAKLAT and GERİ, SOHBET and KLAVYE, ZIPLA and SHIFT, SALDIR and SAĞ TIK); the editor showed
     * every key at once and drew the twins over each other. Only what is shown changes: the layout and its file do not.
     */
    public enum Scene { GAME, MENU }

    /**
     * Whether the editor shows a key while it shows {@code scene}: a key the game shows on that screen; a key the game
     * never hides (upstream's hide and mouse keys, {@code hideable} false) on both, as in play; and a key shown on
     * neither screen (the default layout's welcome text, an editor-only note) on both, as upstream's editor did.
     */
    public static boolean shownIn(Scene scene, boolean inGame, boolean inMenus, boolean hideable) {
        if (!hideable || (!inGame && !inMenus)) return true;
        return scene == Scene.GAME ? inGame : inMenus;
    }

    /**
     * The screen the editor opens on: the launcher's editor on play's keys; the game's editor on the keys the game
     * showed when it opened (its menus' keys when a menu was open: the inventory's SHIFT, the pause menu's GERİ).
     */
    public static Scene firstScene(boolean inGame, boolean grabbing) {
        return inGame && !grabbing ? Scene.MENU : Scene.GAME;
    }

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
        int left = freeLeft(width, barWidth, centred, lefts, rights, gap, margin);
        return left >= 0 ? left : centred;
    }

    /**
     * MOB-NEXT (the lead's decision, 2 Nov): the left edge nearest {@code preferred} where something {@code barWidth}
     * wide keeps {@code gap} from every box in its band ({@code lefts}, {@code rights}) and {@code margin} from the
     * screen's edges; -1 when there is none. {@link #barLeft} is this from the centre, centred when nothing is free; an
     * open key panel counts as a box (a bar with no room beside it fades until the panel closes).
     */
    public static int freeLeft(int width, int barWidth, int preferred, int[] lefts, int[] rights, int gap, int margin) {
        int min = margin;
        int max = width - margin - barWidth;
        if (max < min || lefts.length != rights.length) return -1;
        int best = -1;
        long bestDistance = Long.MAX_VALUE;
        // The places worth trying: the preferred one, and just after or just before each box.
        int[] tries = new int[1 + 2 * lefts.length];
        tries[0] = preferred;
        for (int i = 0; i < lefts.length; i++) {
            tries[1 + 2 * i] = rights[i] + gap;
            tries[2 + 2 * i] = lefts[i] - gap - barWidth;
        }
        for (int at : tries) {
            int x = Math.max(min, Math.min(max, at));
            if (!clearOfKeys(x, barWidth, lefts, rights, gap)) continue;
            long distance = Math.abs((long) x - preferred);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = x;
            }
        }
        return best;
    }

    /**
     * MOB-NEXT (the lead's decision, 2 Nov): where a selected key's action row ("Sil", "Kopyala", "Tuş ekle") docks
     * while the key's panel is closed (while it is open the row is in the panel's header): never over a box (the shown
     * keys, an open drawer's included, the editor's bars, the key's handle), at the free place nearest the key, under it
     * rather than above it at a like distance; {@code margin} from the screen's edges, {@code gap} beside every box.
     * Boxes are left, top, right, bottom. Returns the row's left and top, or null when no place is free.
     */
    public static float[] rowSpot(float[] key, float rowWidth, float rowHeight, float[][] boxes, float width, float height,
                                  float gap, float margin) {
        float step = Math.max(1f, rowHeight / 2f);
        float preferred = (key[0] + key[2]) / 2f - rowWidth / 2f;
        float[] best = null;
        double bestDistance = Double.MAX_VALUE;
        int[] lefts = new int[boxes.length];
        int[] rights = new int[boxes.length];
        for (int above = 0; above < 2; above++) {
            // Above the key counts a row's height farther: under it first.
            double bias = above == 1 ? rowHeight : 0;
            for (int n = 0; ; n++) {
                float top = above == 0 ? key[3] + gap + n * step : key[1] - gap - rowHeight - n * step;
                if (top < margin || top + rowHeight > height - margin) break;
                float dy = gap + n * step;
                if (dy + bias >= bestDistance) break;
                int count = 0;
                for (float[] box : boxes) {
                    if (box[1] < top + rowHeight + gap && top - gap < box[3]) {
                        lefts[count] = Math.round(box[0]);
                        rights[count] = Math.round(box[2]);
                        count++;
                    }
                }
                int left = freeLeft(Math.round(width), Math.round(rowWidth), Math.round(preferred),
                        java.util.Arrays.copyOf(lefts, count), java.util.Arrays.copyOf(rights, count),
                        Math.round(gap), Math.round(margin));
                if (left < 0) continue;
                double distance = Math.hypot(left - preferred, dy) + bias;
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = new float[]{left, top};
                }
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

    /**
     * MOB-NEXT: whether a key shown on neither of the game's screens (the welcome text) steps aside in the editor: while
     * a key it shows lies over it (an open drawer's: the default layout's DİĞER panel reaches the centred text on phones
     * held sideways; or a key made larger: the joystick at 110 % on a 16:9 phone). It comes back when they go.
     * Rectangles are left, top, right, bottom.
     */
    public static boolean noteGivesWay(float[] note, float[][] keys) {
        for (float[] key : keys) {
            if (key[0] < note[2] && note[0] < key[2] && key[1] < note[3] && note[1] < key[3]) return true;
        }
        return false;
    }

    /**
     * MOB-NEXT (TASTE-2's second look): a key's size as the key settings panel shows it, a whole number; upstream printed
     * the float ("Boyut 121.00001 x 110.00001"). The field only shows it: the layout keeps its value until the player
     * types one.
     */
    public static String sizeText(float size) {
        return String.valueOf(Math.round(size));
    }

    /** MOB-NEXT (TASTE-2's second look): a slider's value the language's way: "%10" in Turkish, "10%" in English. */
    public static String percent(Locale locale, int value) {
        return NumberFormat.getPercentInstance(locale).format(value / 100d);
    }

    private static boolean clearOfKeys(int x, int barWidth, int[] lefts, int[] rights, int gap) {
        for (int i = 0; i < lefts.length; i++) {
            if (x < rights[i] + gap && x + barWidth > lefts[i] - gap) return false;
        }
        return true;
    }
}
