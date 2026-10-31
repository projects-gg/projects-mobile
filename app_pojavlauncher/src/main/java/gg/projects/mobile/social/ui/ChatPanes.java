package gg.projects.mobile.social.ui;

/**
 * TABLET-M (TASTE-2 r1): where a conversation shows follows the window alone, never the turns before it. A wide and tall
 * window ({@code projects_chat_two_pane}) always shows "Mesajlar" with the open conversation beside the list; a narrower
 * one shows a conversation on its own screen. What is being written goes along both ways (a short-lived handoff).
 */
final class ChatPanes {
    private ChatPanes() {}

    /** What a conversation shown on its own screen does once its window has room for two panes. */
    enum Move {
        /** It stays: the window is narrow, or the conversation already sits beside the list. */
        STAY,
        /** "Mesajlar" is the screen under it: it goes, and the list opens it beside itself. */
        BACK_TO_LIST,
        /** Another screen is under it: it goes, and "Mesajlar" opens with it beside the list. */
        LIST_INSTEAD
    }

    static Move onWindow(boolean twoPanes, boolean ownScreen, boolean listBelow) {
        if (!twoPanes || !ownScreen) return Move.STAY;
        return listBelow ? Move.BACK_TO_LIST : Move.LIST_INSTEAD;
    }

    /** A handoff counts this long after it was kept: a turn or a resize makes the screen again within a second or two. */
    static final long HANDOFF_MS = 10_000;

    /** True while a handoff kept at {@code keptAtMs} (elapsed-realtime clock) may still be taken at {@code nowMs}. */
    static boolean fresh(long keptAtMs, long nowMs) {
        return nowMs >= keptAtMs && nowMs - keptAtMs <= HANDOFF_MS;
    }
}
