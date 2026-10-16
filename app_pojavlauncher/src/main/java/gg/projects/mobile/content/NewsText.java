package gg.projects.mobile.content;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/** Short text taken from news Markdown for the patch notes box (the desktop's {@code lib/news.ts}). */
public final class NewsText {
    private NewsText() {}

    private static final Pattern BULLET = Pattern.compile("^([-*+]|[0-9]+[.)])\\s+");
    private static final Pattern LINK = Pattern.compile("!?\\[([^\\]]*)\\]\\([^)]*\\)");
    private static final Pattern EMPHASIS = Pattern.compile("[*_`]");

    /** The first {@code max} bullet points of a Markdown text, as plain text, each once. */
    public static List<String> highlights(String markdown, int max) {
        List<String> out = new ArrayList<>();
        if (markdown == null) return out;
        for (String raw : markdown.split("\r?\n")) {
            if (out.size() >= max) break;
            String line = ContentText.trim(raw);
            if (!BULLET.matcher(line).find()) continue;
            String text = BULLET.matcher(line).replaceFirst("");
            text = LINK.matcher(text).replaceAll("$1");
            text = ContentText.trim(EMPHASIS.matcher(text).replaceAll(""));
            if (!text.isEmpty()) out.add(text);
        }
        return new ArrayList<>(new LinkedHashSet<>(out));
    }

    /**
     * Lines of the patch notes box for a news item (under its title, so one line fewer than a pack
     * changelog): its bullet points, else its summary.
     */
    public static List<String> newsHighlights(String body, String summary, int max) {
        List<String> bullets = highlights(body, max);
        if (!bullets.isEmpty()) return bullets;
        List<String> out = new ArrayList<>();
        if (summary != null && !ContentText.isBlank(summary)) out.add(ContentText.trim(summary));
        return out;
    }
}
