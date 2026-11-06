package gg.projects.mobile.afk.ui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import gg.projects.mobile.afk.api.AfkDraft;
import gg.projects.mobile.afk.api.AfkLimits;
import gg.projects.mobile.afk.core.AfkLineFilter;

/**
 * The add and edit screen's own rules, beside the engine's ({@code PhoneAfk.check}): the desktop's empty address
 * ("Boş bırakırsan play.projects.gg kullanılır."), the commands as the player keeps them, what changed, how a secret
 * line shows (the command word, the rest masked, the desktop's one look), and which of the phone's own names may fill
 * the name field. Plain Java, JVM-tested.
 */
public final class AfkFormRules {
    private AfkFormRules() {}

    /** The address an empty field stands for (the desktop's {@code play.projects.gg}). */
    public static final String DEFAULT_ADDRESS = "play.projects.gg";
    /** One masked character (the desktop's bullet). */
    public static final char MASK = '•';

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{" + AfkLimits.NAME_MIN + "," + AfkLimits.NAME_MAX + "}");

    /** The address to save: as typed (trimmed), or the default when left empty. */
    public static String address(String typed) {
        String t = typed == null ? "" : typed.trim();
        return t.isEmpty() ? DEFAULT_ADDRESS : t;
    }

    /** The login commands to save: trimmed, blank lines dropped (the engine drops them too). */
    public static List<String> commands(List<String> lines) {
        List<String> out = new ArrayList<>();
        if (lines == null) return out;
        for (String l : lines) {
            if (l == null) continue;
            String t = l.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** Whether the draft differs from the one the screen started with (what the save bar is about). */
    public static boolean changed(AfkDraft start, AfkDraft now) {
        if (start == null || now == null) return start != now;
        return !start.name.trim().equals(now.name.trim())
                || !address(start.address).equalsIgnoreCase(address(now.address))
                || !commands(start.loginCommands).equals(commands(now.loginCommands))
                || start.loginMode != now.loginMode
                || start.sneak != now.sneak
                || start.mobileData != now.mobileData;
    }

    /** A copy of the draft (the screen edits one copy and compares it with the start). */
    public static AfkDraft copy(AfkDraft d) {
        AfkDraft c = new AfkDraft();
        c.name = d.name;
        c.address = d.address;
        c.loginCommands = new ArrayList<>(d.loginCommands);
        c.loginMode = d.loginMode;
        c.sneak = d.sneak;
        c.mobileData = d.mobileData;
        c.loginCommandsUnreadable = d.loginCommandsUnreadable;
        return c;
    }

    /** The line holds a secret (a login, register or password command: its words after the first are masked). */
    public static boolean secret(String line) {
        return line != null && AfkLineFilter.isSensitiveCommand(line.trim());
    }

    /**
     * How a line shows while hidden: a secret line keeps its command word and the space after it, every other
     * character is a bullet (as many as typed, so the cursor keeps its place); other lines show as they are.
     */
    public static String masked(String line) {
        if (!secret(line)) return line;
        StringBuilder out = new StringBuilder(line.length());
        for (int i = 0; i < line.length(); i++) out.append(shown(line, i));
        return out.toString();
    }

    /**
     * The character a hidden line shows at {@code i}, read from the line as it is now (an EditText's text changes under
     * its transformation, so the mask is worked out per character, never from a copy): the command word and white
     * space as typed, the rest of a secret line as {@link #MASK}. Costs the first word's length, not the line's.
     */
    public static char shown(CharSequence line, int i) {
        char c = line.charAt(i);
        if (c == ' ' || c == '\t') return c;
        int start = 0;
        int length = line.length();
        while (start < length && (line.charAt(start) == ' ' || line.charAt(start) == '\t')) start++;
        int end = start;
        while (end < length && line.charAt(end) != ' ' && line.charAt(end) != '\t') end++;
        // Within the command word, or a lone command with nothing after it: as typed.
        if (i < end || end >= length) return c;
        return AfkLineFilter.isSensitiveCommand(line.subSequence(start, end).toString()) ? MASK : c;
    }

    /**
     * The phone's own username accounts that may fill the name field: valid Minecraft names, not yet AFK accounts
     * (ignoring case), each once, in the order given.
     */
    public static List<String> phoneNames(List<String> local, List<String> afkNames) {
        Set<String> taken = new HashSet<>();
        if (afkNames != null) for (String n : afkNames) taken.add(n.toLowerCase(Locale.ROOT));
        List<String> out = new ArrayList<>();
        if (local == null) return out;
        for (String n : local) {
            if (n == null || !NAME.matcher(n).matches()) continue;
            if (!taken.add(n.toLowerCase(Locale.ROOT))) continue;
            out.add(n);
        }
        return out;
    }
}
