package gg.projects.mobile.cloud.console;

import androidx.annotation.Nullable;

import gg.projects.mobile.cloud.CloudModel;

/**
 * What a console line is (services/cloud-afk README "Console kinds"): its filter chip, its colour, its name in a
 * downloaded file, and whether it mentions the account. A line without a kind (an older machine, kinds off, a line
 * logged before) shows by {@code k} as it always did and only under "Hepsi"; an unknown kind shows as the client's own.
 */
public final class ConsoleKinds {
    private ConsoleKinds() {}

    /** The filter chips, in their order: Hepsi, Sohbet, Özel, Sunucu, Komutlarım, Bağlantı. */
    public enum Filter { ALL, CHAT, PRIVATE, SERVER, MINE, CONNECTION }

    /** The line's place among the chips; null for a line without a kind (it shows only under "Hepsi"). */
    @Nullable
    public static Filter category(CloudModel.ConsoleLine line) {
        if (line.c == null) return null;
        // `s` (a command row's echo or output) is "Komutlarım" whatever its kind.
        if ("s".equals(line.k)) return Filter.MINE;
        switch (line.c) {
            case "chat": return Filter.CHAT;
            case "pm": return Filter.PRIVATE;
            case "sys":
            case "title":
                return Filter.SERVER;
            default:
                return Filter.CONNECTION;
        }
    }

    public static boolean shows(Filter filter, CloudModel.ConsoleLine line) {
        return filter == Filter.ALL || category(line) == filter;
    }

    /**
     * "Adın geçenler": a chat or private line whose text holds the account's name (ASCII letters compared without case,
     * as the contract says) and whose sender is someone else.
     */
    public static boolean mentions(CloudModel.ConsoleLine line, @Nullable String name) {
        if (name == null || name.isEmpty() || line.c == null) return false;
        if (!"chat".equals(line.c) && !"pm".equals(line.c)) return false;
        if (line.who != null && asciiLower(line.who).equals(asciiLower(name))) return false;
        return asciiLower(line.text).contains(asciiLower(name));
    }

    /** Lower case for A–Z only (so a Turkish "I" never turns into a dotless one and misses a name). */
    public static String asciiLower(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            out.append(ch >= 'A' && ch <= 'Z' ? (char) (ch + 32) : ch);
        }
        return out.toString();
    }

    /** The colours of a line (docs/tasks/cafk-feat.md §3.2): a name the screen maps to the app's tokens. */
    public enum Tone { CHAT, PRIVATE, SERVER, MINE, CLIENT, ERROR, PLAIN }

    public static Tone tone(CloudModel.ConsoleLine line) {
        if ("s".equals(line.k)) return Tone.MINE;
        if (line.c == null) return "e".equals(line.k) ? Tone.ERROR : Tone.PLAIN;
        switch (line.c) {
            case "chat": return Tone.CHAT;
            case "pm": return Tone.PRIVATE;
            case "sys":
            case "title":
                return Tone.SERVER;
            case "err": return Tone.ERROR;
            default: return Tone.CLIENT;
        }
    }

    /** The kind's name in a downloaded file: Sohbet, Özel, Sunucu, Başlık, Komut, Bağlantı, Hata, Konsol. */
    public enum FileKind { CHAT, PRIVATE, SERVER, TITLE, COMMAND, CONNECTION, ERROR, CONSOLE }

    public static FileKind fileKind(CloudModel.ConsoleLine line) {
        if ("s".equals(line.k)) return FileKind.COMMAND;
        if (line.c == null) return FileKind.CONSOLE;
        switch (line.c) {
            case "chat": return FileKind.CHAT;
            case "pm": return FileKind.PRIVATE;
            case "sys": return FileKind.SERVER;
            case "title": return FileKind.TITLE;
            case "err": return FileKind.ERROR;
            default: return FileKind.CONNECTION;
        }
    }

    /** Whether a typed search finds the line (letters compared without case, Turkish letters folded alike). */
    public static boolean found(CloudModel.ConsoleLine line, String query) {
        String q = fold(query.trim());
        return q.isEmpty() || fold(line.text).contains(q);
    }

    /** A search key: lower case, with Turkish İ, I and ı read as i (a search typed either way finds the line). */
    static String fold(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case 'İ':
                case 'I':
                case 'ı':
                    out.append('i');
                    break;
                default:
                    out.append(Character.toLowerCase(ch));
                    break;
            }
        }
        return out.toString();
    }
}
