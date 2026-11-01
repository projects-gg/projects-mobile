package gg.projects.mobile.afk.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import gg.projects.mobile.afk.api.AfkLine;

/**
 * A console line's looks (CONSOLE-COLOR), checked again here since the view is the last guard: the engine's style runs
 * clipped to the text, in order and without overlaps, each server colour made readable on the console's ground
 * ({@link ConsoleColors}); §k stays plain, unmoving text. The Android side turns the runs into spans. Plain Java,
 * JVM-tested.
 */
public final class AfkLineStyle {
    private AfkLineStyle() {}

    /** At most this many runs are drawn per line (the desktop's {@code MAX_SPANS}); the rest of the line stays plain. */
    public static final int MAX_RUNS = 64;

    /** One styled stretch {@code [start, end)}: a readable colour ({@link AfkLine#NO_COLOR}: the kind's own) and flags. */
    public static final class Run {
        public final int start;
        public final int end;
        public final int color;
        public final boolean bold;
        public final boolean italic;
        public final boolean underlined;
        public final boolean strikethrough;

        Run(int start, int end, int color, int style) {
            this.start = start;
            this.end = end;
            this.color = color;
            this.bold = (style & AfkLine.BOLD) != 0;
            this.italic = (style & AfkLine.ITALIC) != 0;
            this.underlined = (style & AfkLine.UNDERLINED) != 0;
            this.strikethrough = (style & AfkLine.STRIKETHROUGH) != 0;
        }

        @Override
        public String toString() {
            return start + "-" + end + (color == AfkLine.NO_COLOR ? "" : String.format("#%06x", color))
                    + (bold ? "b" : "") + (italic ? "i" : "") + (underlined ? "u" : "") + (strikethrough ? "s" : "");
        }
    }

    /** The runs worth drawing (a run with neither a colour nor a flag is left out: the line's own look covers it). */
    public static List<Run> runs(AfkLine line) {
        if (line.spans.isEmpty() || line.text.isEmpty()) return Collections.emptyList();
        int length = line.text.length();
        List<Run> out = new ArrayList<>();
        int at = 0;
        for (AfkLine.Span span : line.spans) {
            if (out.size() >= MAX_RUNS) break;
            if (span == null) continue;
            int start = Math.max(at, Math.max(0, span.start));
            int end = Math.min(length, span.end);
            if (end <= start) continue;
            // Never split a surrogate pair (an emoji half in one colour, half in another).
            if (start > 0 && start < length && Character.isLowSurrogate(line.text.charAt(start))
                    && Character.isHighSurrogate(line.text.charAt(start - 1))) start++;
            if (end < length && end > 0 && Character.isLowSurrogate(line.text.charAt(end))
                    && Character.isHighSurrogate(line.text.charAt(end - 1))) end++;
            if (end <= start) continue;
            int style = span.style & (AfkLine.BOLD | AfkLine.ITALIC | AfkLine.UNDERLINED | AfkLine.STRIKETHROUGH);
            int color = span.color == AfkLine.NO_COLOR || span.color < 0 ? AfkLine.NO_COLOR : ConsoleColors.readable(span.color);
            at = end;
            if (color == AfkLine.NO_COLOR && style == 0) continue;
            out.add(new Run(start, end, color, style));
        }
        return out;
    }
}
