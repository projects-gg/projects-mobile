package gg.projects.mobile.social;

import androidx.annotation.Nullable;

/**
 * FIX2 (README "Crew plans", {@code planNotes}): a plan's short note ("Bedwars, sesli gelin"), its
 * only text. Cleaned the way the service cleans it (chat's cleaning on one line: invisible
 * characters dropped, white space collapsed, trimmed) and checked for what it turns away (a link, a
 * phone number, more than 80 characters), so the sheet can say why before anything is sent. The
 * service also checks its word list; a difference only means it answers {@code invalid_plan_note}.
 * Line breaks become spaces here: the field is one line, and a pasted text keeps its words. Pure Java.
 */
public final class PlanNote {
    private PlanNote() {}

    /** At most this many characters (Unicode code points, as the service counts them). */
    public static final int MAX = 80;
    /** The counter shows from here on. */
    public static final int COUNTER_FROM = 60;

    public enum Problem { NONE, TOO_LONG, LINK, PHONE }

    /** The note as the service keeps it; "" is none. */
    public static String clean(@Nullable String value) {
        if (value == null) return "";
        String oneLine = value.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ')
                .replace((char) 0x0085, ' ').replace((char) 0x2028, ' ').replace((char) 0x2029, ' ');
        return ChatText.clean(oneLine).replace('\n', ' ');
    }

    /** What is wrong with a note the player is about to send ({@link Problem#NONE} also for an empty one: no note). */
    public static Problem check(@Nullable String value) {
        String text = clean(value);
        if (text.isEmpty()) return Problem.NONE;
        if (ChatText.length(text) > MAX) return Problem.TOO_LONG;
        if (ChatText.looksLikeLink(text)) return Problem.LINK;
        if (ChatText.looksLikePhone(text)) return Problem.PHONE;
        return Problem.NONE;
    }

    /** The note a plan carries, or null when it has none (a missing, empty or blank one). */
    @Nullable
    public static String of(@Nullable String wire) {
        if (wire == null) return null;
        String text = clean(wire);
        return text.isEmpty() ? null : text;
    }

    /** The reason of an {@code invalid_plan_note} or a {@code chat_rejected} refusal, as {@link Problem}. */
    public static Problem ofReason(@Nullable String reason) {
        if ("link".equals(reason)) return Problem.LINK;
        if ("phone".equals(reason)) return Problem.PHONE;
        return Problem.NONE;
    }
}
