package gg.projects.mobile.afk.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import gg.projects.mobile.afk.api.AfkLine;

/**
 * A line of the game's text as the console shows it: plain text and its style runs ({@link AfkLine.Span}: colour as
 * the server sent it, bold/italic/lines; §k as plain text). Immutable; {@link Builder} merges equal neighbours.
 */
public final class AfkText {
    public static final AfkText EMPTY = new AfkText("", Collections.<AfkLine.Span>emptyList());

    public final String text;
    public final List<AfkLine.Span> spans;

    AfkText(String text, List<AfkLine.Span> spans) {
        this.text = text;
        this.spans = spans;
    }

    public static AfkText plain(String text) {
        return new AfkText(text == null ? "" : text, Collections.<AfkLine.Span>emptyList());
    }

    public boolean isEmpty() {
        return text.isEmpty();
    }

    /** This text with {@code mapped} as its words (same length: masking keeps the runs; otherwise the runs go). */
    public AfkText withText(String mapped) {
        if (mapped.equals(text)) return this;
        if (mapped.length() == text.length()) return new AfkText(mapped, spans);
        return plain(mapped);
    }

    /** At most {@code max} characters, a longer text cut with "…" (runs cut with it). */
    public AfkText capped(int max) {
        if (text.length() <= max) return this;
        int cut = max - 1;
        if (cut > 0 && Character.isHighSurrogate(text.charAt(cut - 1))) cut--;
        List<AfkLine.Span> kept = new ArrayList<>();
        for (AfkLine.Span s : spans) {
            if (s.start >= cut) break;
            kept.add(new AfkLine.Span(s.start, Math.min(s.end, cut), s.color, s.style));
        }
        return new AfkText(text.substring(0, cut) + "…", Collections.unmodifiableList(kept));
    }

    @Override
    public String toString() {
        return text;
    }

    public static final class Builder {
        private final StringBuilder text = new StringBuilder();
        private final List<AfkLine.Span> spans = new ArrayList<>();

        public Builder append(String part, int color, int style) {
            if (part == null || part.isEmpty()) return this;
            int start = text.length();
            text.append(part);
            int end = text.length();
            if (color == AfkLine.NO_COLOR && style == 0) return this;
            if (!spans.isEmpty()) {
                AfkLine.Span last = spans.get(spans.size() - 1);
                if (last.end == start && last.color == color && last.style == style) {
                    spans.set(spans.size() - 1, new AfkLine.Span(last.start, end, color, style));
                    return this;
                }
            }
            spans.add(new AfkLine.Span(start, end, color, style));
            return this;
        }

        public int length() {
            return text.length();
        }

        public AfkText build() {
            // Line breaks become spaces: a console line is one line (same length, the runs stay).
            String s = text.toString().replace('\n', ' ').replace('\r', ' ');
            return new AfkText(s, Collections.unmodifiableList(new ArrayList<>(spans)));
        }
    }
}
