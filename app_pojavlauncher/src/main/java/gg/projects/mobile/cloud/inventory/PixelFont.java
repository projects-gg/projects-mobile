package gg.projects.mobile.cloud.inventory;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The game's letters as whole pixels (Projects Pixel's bitmaps, the launcher's pixel-font.ts): how far a text moves the
 * pen, where it wraps and the pixels it lights. Every word of the game in the inventory (titles, labels, counts, the
 * tooltip) is drawn from these, so the phone measures and draws them as the launcher does.
 */
public final class PixelFont {
    private PixelFont() {}

    /** A line's box in GUI pixels: the game's tooltip pitch. Its top is the glyph cell's top. */
    public static final int LINE_BOX = 10;
    /** How far a character the bitmaps lack moves the pen (the fallback font draws it). */
    public static final int MISSING_ADVANCE = 6;

    private static Map<Integer, int[]> sGlyphs;

    static synchronized Map<Integer, int[]> glyphs() {
        if (sGlyphs != null) return sGlyphs;
        Map<Integer, int[]> map = new HashMap<>();
        for (String entry : PixelGlyphs.DATA.split(";")) {
            int colon = entry.indexOf(':');
            if (colon <= 0) continue;
            int cp = Integer.parseInt(entry.substring(0, colon), 16);
            String[] parts = entry.substring(colon + 1).split(",");
            int[] values = new int[parts.length];
            for (int i = 0; i < parts.length; i++) values[i] = Integer.parseInt(parts[i]);
            map.put(cp, values);
        }
        sGlyphs = map;
        return map;
    }

    /** [advance, top row, rows from the top as bit masks...], or null for a character the bitmaps lack. */
    @Nullable
    public static int[] glyph(int codePoint) {
        return glyphs().get(codePoint);
    }

    public static int advance(int codePoint) {
        int[] g = glyph(codePoint);
        return g == null ? MISSING_ADVANCE : g[0];
    }

    /** How far a text moves the pen, in GUI pixels. */
    public static int advance(String text) {
        int width = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            width += advance(cp);
        }
        return width;
    }

    /** The lit width of a text: its advance without the gap after the last character. */
    public static int width(String text) {
        return Math.max(0, advance(text) - 1);
    }

    public static boolean canDraw(String text) {
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (glyph(cp) == null) return false;
        }
        return true;
    }

    /** The text's first characters that fit {@code width} GUI pixels (whole characters, nothing added). */
    public static String clip(String text, int width) {
        int pen = 0;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int step = advance(cp);
            if (pen + step - 1 > width) break;
            out.appendCodePoint(cp);
            pen += step;
            i += Character.charCount(cp);
        }
        return out.toString();
    }

    /** A line broken at spaces into lines of at most {@code width} GUI pixels; a longer word breaks between characters. */
    public static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        if (width <= 0 || advance(text) <= width) {
            lines.add(text);
            return lines;
        }
        int space = advance(' ');
        StringBuilder line = new StringBuilder();
        int pen = 0;
        for (String word : text.split(" ", -1)) {
            int adv = advance(word);
            if (line.length() > 0 && pen + space + adv <= width) {
                line.append(' ').append(word);
                pen += space + adv;
                continue;
            }
            if (line.length() > 0) lines.add(line.toString());
            line.setLength(0);
            pen = 0;
            if (adv <= width) {
                line.append(word);
                pen = adv;
                continue;
            }
            for (int i = 0; i < word.length(); ) {
                int cp = word.codePointAt(i);
                i += Character.charCount(cp);
                int step = advance(cp);
                if (line.length() > 0 && pen + step > width) {
                    lines.add(line.toString());
                    line.setLength(0);
                    pen = 0;
                }
                line.appendCodePoint(cp);
                pen += step;
            }
        }
        if (line.length() > 0) lines.add(line.toString());
        return lines;
    }

    /** One lit run of a glyph row: x from the pen's start, y down from the glyph cell's top, its width. */
    public interface Runs {
        void run(int x, int y, int w);
    }

    /**
     * The text's pixels as runs of lit pixels per row (the baseline is under row 6; accents of capitals reach above 0,
     * tails below 7); characters the bitmaps lack are skipped here (the caller draws them with the fallback font).
     */
    public static void runs(String text, Runs out) {
        int pen = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            int[] g = glyph(cp);
            if (g == null) {
                pen += MISSING_ADVANCE;
                continue;
            }
            int top = g[1];
            for (int r = 2; r < g.length; r++) {
                int mask = g[r];
                int y = 6 - top + (r - 2);
                int x = 0;
                while ((mask >> x) != 0) {
                    if (((mask >> x) & 1) == 0) {
                        x++;
                        continue;
                    }
                    int w = 1;
                    while (((mask >> (x + w)) & 1) != 0) w++;
                    out.run(pen + x, y, w);
                    x += w;
                }
            }
            pen += g[0];
        }
    }
}
