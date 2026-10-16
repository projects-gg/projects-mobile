package gg.projects.mobile.ui;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;

import gg.projects.mobile.content.ContentPaths;

/**
 * A small, safe Markdown renderer for news bodies: headings, bullet lists, bold, italic, inline
 * code and links. No HTML is interpreted; only https links become clickable (which also covers the
 * Discord feed's rule); pictures show their alt text.
 */
public final class MarkdownLite {
    private MarkdownLite() {}

    public static CharSequence render(String markdown) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        if (markdown == null) return out;
        String[] lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        boolean pendingBreak = false;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) {
                pendingBreak = out.length() > 0;
                continue;
            }
            if (line.matches("^(-{3,}|\\*{3,}|_{3,})$")) continue;
            if (out.length() > 0) out.append(pendingBreak ? "\n\n" : "\n");
            pendingBreak = false;

            int heading = 0;
            while (heading < line.length() && heading < 6 && line.charAt(heading) == '#') heading++;
            if (heading > 0 && heading < line.length() && line.charAt(heading) == ' ') {
                int start = out.length();
                appendInline(out, line.substring(heading + 1).trim());
                out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new RelativeSizeSpan(heading <= 2 ? 1.2f : 1.08f), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                continue;
            }
            if (line.startsWith("> ")) line = line.substring(2);
            if (line.length() > 2 && (line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ "))) {
                out.append("•  ");
                appendInline(out, line.substring(2).trim());
                continue;
            }
            appendInline(out, line);
        }
        return out;
    }

    private static void appendInline(SpannableStringBuilder out, String text) {
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                out.append(text.charAt(i + 1));
                i += 2;
                continue;
            }
            if (text.startsWith("**", i) || text.startsWith("__", i)) {
                String marker = text.substring(i, i + 2);
                int end = text.indexOf(marker, i + 2);
                if (end > i + 2) {
                    int start = out.length();
                    appendInline(out, text.substring(i + 2, end));
                    out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 2;
                    continue;
                }
            }
            if (c == '*') {
                int end = text.indexOf('*', i + 1);
                if (end > i + 1) {
                    int start = out.length();
                    appendInline(out, text.substring(i + 1, end));
                    out.setSpan(new StyleSpan(Typeface.ITALIC), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1;
                    continue;
                }
            }
            if (c == '`') {
                int end = text.indexOf('`', i + 1);
                if (end > i + 1) {
                    int start = out.length();
                    out.append(text, i + 1, end);
                    out.setSpan(new TypefaceSpan("monospace"), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1;
                    continue;
                }
            }
            if (c == '!' && i + 1 < text.length() && text.charAt(i + 1) == '[') {
                // Pictures are never loaded (a Discord post could point anywhere): their text stays.
                int close = text.indexOf("](", i + 2);
                int end = close < 0 ? -1 : text.indexOf(')', close + 2);
                if (close > i + 1 && end > close) {
                    appendInline(out, text.substring(i + 2, close));
                    i = end + 1;
                    continue;
                }
            }
            if (c == '[') {
                int close = text.indexOf("](", i + 1);
                int end = close < 0 ? -1 : text.indexOf(')', close + 2);
                if (close > i && end > close) {
                    String label = text.substring(i + 1, close);
                    String url = text.substring(close + 2, end).trim();
                    int start = out.length();
                    appendInline(out, label);
                    if (ContentPaths.isHttpsUrl(url)) {
                        out.setSpan(new URLSpan(url), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    i = end + 1;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
    }
}
