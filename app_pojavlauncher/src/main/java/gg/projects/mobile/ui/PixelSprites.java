package gg.projects.mobile.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The desktop's content sprites (ICON1, ICON2; design.md §6): the thing a tile or an empty state
 * stands for is one of our own 16 × 16 pixel item sprites in the golds, never a stock glyph.
 * Buttons, menus and rows keep their Phosphor icons. The rows are the desktop's own, copied from
 * {@code @pl/ui}'s ContentIcon ({@code packages/ui/src/components/content-icon.tsx}, launcher
 * {@code f293167}): a tone key per pixel, "." for nothing. {@link PixelSpriteDrawable} draws them
 * at whole multiples of 16 pixels. Plain Java, JVM-tested.
 */
public final class PixelSprites {
    private PixelSprites() {}

    /** A sprite: its 16 rows and whether it casts the one-pixel gold-700 drop shadow. */
    public enum Sprite {
        /** A grass block (a world; on the phone, a profile without its own icon). */
        WORLD(false,
                "......lmll......",
                "....lhllllml....",
                "..llmllllhllll..",
                "lllhllmllllmhlll",
                "mmllmllhlllllldd",
                "mmmmlmllllhldddd",
                "ommmmmllmldddddk",
                "ooommmmmddddkdkk",
                "ooomommmdddkkdkk",
                "oosooomokdkkkkkk",
                "osooooookdkokkkk",
                "oooosoookkkkkkok",
                "..oooosokokkkk..",
                "....ooookkkk....",
                "......ookk......",
                "................"),
        /** Two smiling players, the second a step back and looking at the first: friends. */
        FRIENDS(true,
                "................",
                "................",
                "................",
                "........ooooooo.",
                "........ooooooo.",
                ".sssssss.ddddoo.",
                ".sssssss.dddddd.",
                ".sllllls.kmdkmd.",
                ".lllllll.dddddd.",
                ".lholohl.sdddsd.",
                ".lllllll.dsssdd.",
                ".lslllsl.ssssss.",
                ".llsssll........",
                ".mmmmmmm........",
                "................",
                "................"),
        /** A speech bubble with three dots: messages. */
        CHAT(true,
                "................",
                "................",
                "..lllllllllll...",
                ".lhmmmmmmmmmmd..",
                ".lmmmmmmmmmmmd..",
                ".lmmmmmmmmmmmd..",
                ".lmmommommommd..",
                ".lmmmmmmmmmmmd..",
                ".lmmmmmmmmmmmd..",
                ".lmmmmmmmmmmmd..",
                "..sssssssssss...",
                "..ss............",
                "..s.............",
                "................",
                "................",
                "................"),
        /** A cloud, a dome between two shoulders, lit from above: 7/24 Bulut. (AFKPLUS-M, from the desktop's ContentIcon.) */
        CLOUD(true,
                "................",
                "................",
                "................",
                "......hhh.......",
                ".....hllllh.....",
                "....hllllllh....",
                "..hhlllllllhhh..",
                ".hllllllllllllm.",
                ".hlllllllllllmm.",
                ".lllllllllmmmmd.",
                "..mmmmmmmmmmmd..",
                "................",
                "................",
                "................",
                "................",
                "................"),
        /** A robot's head: antenna, visor, two lit eyes (AFK). (AFKPLUS-M, from the desktop's ContentIcon.) */
        AFK(true,
                "................",
                "......hh........",
                "......mm........",
                "..llllllllll....",
                ".mmmmmmmmmmmm...",
                ".moooooooooom...",
                "smohhoooohhoms..",
                "smohhoooohhoms..",
                ".moooooooooom...",
                ".mmmmmmmmmmmm...",
                ".mmmmssssmmmm...",
                ".mmmmmmmmmmmm...",
                "..ssssssssss....",
                "................",
                "................",
                "................"),
        /** A scroll: a log or a console. (AFKPLUS-M, from the desktop's ContentIcon.) */
        LOGS(true,
                "................",
                "................",
                ".smmmmmmmmmms...",
                ".sddddddddddds..",
                "..llllllllll....",
                "..llssssssll....",
                "..llllllllll....",
                "..llssssslll....",
                "..llllllllll....",
                "..llssssssll....",
                "..llllllllll....",
                ".smmmmmmmmmms...",
                ".sddddddddddds..",
                "................",
                "................",
                "................"),
        /** A chest: items, trades. (AFKPLUS-M, from the desktop's ContentIcon.) */
        PACK(true,
                "................",
                "................",
                ".llllllllllll...",
                "lmmmmmmmmmmmmm..",
                "lmmmmmmmmmmmmm..",
                "lmmmmmhhmmmmmm..",
                "oooooolloooooo..",
                "mdddddmmdddddd..",
                "mddddddddddddd..",
                "mddddddddddddd..",
                "mddddddddddddd..",
                "mddddddddddddd..",
                "ssssssssssssss..",
                "................",
                "................",
                "................"),
        /** A jade check: a finished job, nothing left to answer. (AFKPLUS-M, from the desktop's ContentIcon.) */
        DONE(false,
                "................",
                "................",
                ".............J..",
                "............Jj..",
                "...........Jjq..",
                "..........Jjq...",
                ".........Jjq....",
                "..J.....Jjq.....",
                "..jJ...Jjq......",
                "..qjJ.Jjq.......",
                "...qjJjq........",
                "....qjq.........",
                ".....q..........",
                "................",
                "................",
                "................"),
        /** A warning sign with the exclamation mark cut out (flare): a failure. (AFKPLUS-M, from the desktop's ContentIcon.) */
        FAILED(false,
                "................",
                "................",
                "......FF........",
                "......FF........",
                ".....FffF.......",
                ".....f..f.......",
                "....ff..ff......",
                "....ff..ff......",
                "...fff..fff.....",
                "...ffffffff.....",
                "..fffff.ffff....",
                "..fffffffffff...",
                ".fffffffffffff..",
                ".eeeeeeeeeeee...",
                "................",
                "................");

        final boolean shadow;
        final String[] rows;
        private List<int[]> mRuns;
        private List<int[]> mShadowRuns;

        Sprite(boolean shadow, String... rows) {
            this.shadow = shadow;
            this.rows = rows;
        }

        public String[] rows() {
            return rows.clone();
        }

        public boolean hasShadow() {
            return shadow;
        }

        /** Runs of one tone in a row: {x, y, width, tone}. */
        public synchronized List<int[]> runs() {
            if (mRuns == null) mRuns = Collections.unmodifiableList(PixelSprites.runs(rows));
            return mRuns;
        }

        /** The drop shadow's runs (one pixel down and right of every drawn run): {x, y, width}. */
        public synchronized List<int[]> shadowRuns() {
            if (mShadowRuns == null) mShadowRuns = Collections.unmodifiableList(PixelSprites.shadowRuns(rows));
            return mShadowRuns;
        }
    }

    /** The tone keys, in the desktop's order. */
    public static final String TONES = "hlmdsokFfeJjq";

    /**
     * A tone's colour (ARGB): "h" to "o" the golds 200 to 700, "k" the deep dirt (gold-700 mixed
     * 62 % with obsidian-950 in OKLab, as the desktop's CSS mixes it), "F", "f", "e" flare 300,
     * flare 400 and ember 700, "J", "j", "q" light, base and dark jade. {@code quiet} is one gold
     * step darker (a mark at rest); flare and jade stay.
     */
    public static int color(char tone, boolean quiet) {
        if (quiet) tone = quieter(tone);
        switch (tone) {
            case 'h': return 0xFFFAE5B3;
            case 'l': return 0xFFF6D58E;
            case 'm': return 0xFFF0B849;
            case 'd': return 0xFFE89A2C;
            case 's': return 0xFFC9741A;
            case 'o': return 0xFF8E4A12;
            case 'k': return 0xFF573016;
            case 'F': return 0xFFF6836F;
            case 'f': return 0xFFF0624D;
            case 'e': return 0xFF7A2416;
            case 'J': return 0xFFADE3C0;
            case 'j': return 0xFF5FCB8E;
            case 'q': return 0xFF305742;
            default: throw new IllegalArgumentException("unknown tone " + tone);
        }
    }

    static char quieter(char tone) {
        switch (tone) {
            case 'h': return 'l';
            case 'l': return 'm';
            case 'm': return 'd';
            case 'd': return 's';
            case 's': return 'o';
            case 'o': return 'k';
            default: return tone;
        }
    }

    /** How many screen pixels a sprite pixel takes in a box: whole, at least one, never past it. */
    public static int scale(int boxPx) {
        return Math.max(1, boxPx / 16);
    }

    static List<int[]> runs(String[] rows) {
        List<int[]> out = new ArrayList<>();
        for (int y = 0; y < rows.length; y++) {
            String row = rows[y];
            int x = 0;
            while (x < row.length()) {
                char tone = row.charAt(x);
                if (tone == '.') {
                    x++;
                    continue;
                }
                int w = 1;
                while (x + w < row.length() && row.charAt(x + w) == tone) w++;
                out.add(new int[] {x, y, w, tone});
                x += w;
            }
        }
        return out;
    }

    static List<int[]> shadowRuns(String[] rows) {
        List<int[]> out = new ArrayList<>();
        for (int y = 0; y < rows.length; y++) {
            String row = rows[y];
            int x = 0;
            while (x < row.length()) {
                if (row.charAt(x) == '.') {
                    x++;
                    continue;
                }
                int w = 1;
                while (x + w < row.length() && row.charAt(x + w) != '.') w++;
                out.add(new int[] {x + 1, y + 1, w});
                x += w;
            }
        }
        return out;
    }
}
