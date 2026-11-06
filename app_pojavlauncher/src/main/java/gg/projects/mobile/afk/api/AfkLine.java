package gg.projects.mobile.afk.api;

import java.util.Collections;
import java.util.List;

/**
 * One console line of an account (CONSOLE-COLOR): plain text plus style runs in the game's colours and formats.
 * Secrets (the login commands' passwords) are already masked. Immutable. Stable; fields and kinds may be added.
 */
public final class AfkLine {
    /** Where the line came from. */
    public enum Kind {
        /** A player's public chat. */
        CHAT,
        /** A private message to this account (vanilla's or a plugin's {@code [Ahmet -> ben]} form). */
        PRIVATE,
        /** Anything else the server wrote (join messages, plugin lines). */
        SYSTEM,
        /** A line this account sent (the player's or a login command, masked). */
        OWN,
        /** The app's own line in the app's language ("Oyuna girdi", "Bağlantı koptu: …"). */
        NOTICE
    }

    /** Style bits of a {@link Span}. */
    public static final int BOLD = 1;
    public static final int ITALIC = 1 << 1;
    public static final int UNDERLINED = 1 << 2;
    public static final int STRIKETHROUGH = 1 << 3;
    /** §k: shown as plain, unmoving text (CONSOLE-COLOR), never scrambled. */
    public static final int OBFUSCATED = 1 << 4;

    /** No colour given: the console's own text colour. */
    public static final int NO_COLOR = -1;

    /**
     * Characters {@code [start, end)} of {@link #text} drawn in {@link #color} ({@code 0xRRGGBB} as the server sent
     * it, or {@link #NO_COLOR}) with {@link #style} bits. Spans are in order and do not overlap; text outside them
     * has no style. Readability on the console's dark background is the UI's: {@code ConsoleColors.readable}.
     */
    public static final class Span {
        public final int start;
        public final int end;
        public final int color;
        public final int style;

        public Span(int start, int end, int color, int style) {
            this.start = start;
            this.end = end;
            this.color = color;
            this.style = style;
        }
    }

    public final String accountId;
    /** Increases by one per line of the account while the service runs (a gap means lines were dropped). */
    public final long seq;
    public final long time;
    public final Kind kind;
    public final String text;
    public final List<Span> spans;
    /** The sending player's name for CHAT and PRIVATE when known, else null. */
    public final String sender;
    /** The account's name stands in the line (an alert went out for it unless it was the account's own line). */
    public final boolean mentioned;

    public AfkLine(String accountId, long seq, long time, Kind kind, String text, List<Span> spans, String sender,
                   boolean mentioned) {
        this.accountId = accountId;
        this.seq = seq;
        this.time = time;
        this.kind = kind == null ? Kind.SYSTEM : kind;
        this.text = text == null ? "" : text;
        this.spans = spans == null ? Collections.<Span>emptyList() : Collections.unmodifiableList(spans);
        this.sender = sender;
        this.mentioned = mentioned;
    }

    @Override
    public String toString() {
        return kind + ": " + text;
    }
}
