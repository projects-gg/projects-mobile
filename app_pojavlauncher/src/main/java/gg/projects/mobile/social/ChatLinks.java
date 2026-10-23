package gg.projects.mobile.social;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Links in chat messages (CHAT2; services/social src/chatlinks.ts, README "Chat", "How links are
 * checked"; the launcher's chat/text.ts): the plain https links a message may carry, by the shared
 * rule table (the asset social/chat-links.json, the service's src/chat-links.json copied as it is)
 * and Studio's lists. The composer says why a link may not go before anything is sent, and a
 * conversation draws a link as one only while it passes the rules as they are now; the service
 * decides either way. Its cases (test/chat-link-vectors.json) pass here too (ChatLinksTest). Pure Java.
 *
 * <p>Without the table (no source set, or a file that does not read) every link counts as switched
 * off ({@link Rule#OFF}): refusing is the safe side, and callers never see an exception.
 */
public final class ChatLinks {
    private ChatLinks() {}

    /** Why a link may not go: the {@code rule} of {@code chat_rejected} with {@code link}. */
    public enum Rule {
        OFF, FORM, COUNT, HTTP, CREDENTIALS, PORT, IP, LONG, SHORTENER, GRABBER, ADULT, LOOKALIKE, SCAM, FILE,
        INVITE, BLOCKED;

        /** The service's name for it: "off", "form", ... */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The rule the service names; null for none, or one this version does not know. */
        @Nullable
        public static Rule of(@Nullable String wire) {
            if (wire == null) return null;
            for (Rule rule : values()) {
                if (rule.wire().equals(wire)) return rule;
            }
            return null;
        }
    }

    /**
     * Studio's lists as {@code hello.chat.links} and {@code chat_state} bring them while links are on:
     * the version of the rule table the service applies, hosts whose links open without the warning,
     * hosts and pages no link may lead to, and whether Discord invites may be linked.
     */
    public static final class Lists {
        public final int rules;
        public final List<String> trusted;
        public final List<String> blocked;
        public final boolean invites;

        public Lists(int rules, List<String> trusted, List<String> blocked, boolean invites) {
            this.rules = rules;
            this.trusted = Collections.unmodifiableList(new ArrayList<>(trusted));
            this.blocked = Collections.unmodifiableList(new ArrayList<>(blocked));
            this.invites = invites;
        }

        /**
         * {@code hello.chat.links}: a missing field reads as the launcher reads it (version 0, empty
         * lists, invites allowed); null when the object is absent or a field has another type.
         */
        @Nullable
        public static Lists read(@Nullable JsonObject links) {
            if (links == null) return null;
            try {
                JsonElement rules = given(links, "rules");
                JsonElement invites = given(links, "invites");
                if (rules != null && !(rules.isJsonPrimitive() && rules.getAsJsonPrimitive().isNumber())) return null;
                if (invites != null && !(invites.isJsonPrimitive() && invites.getAsJsonPrimitive().isBoolean())) {
                    return null;
                }
                List<String> trusted = entries(given(links, "trusted"));
                List<String> blocked = entries(given(links, "blocked"));
                if (trusted == null || blocked == null) return null;
                return new Lists(rules == null ? 0 : rules.getAsInt(), trusted, blocked,
                        invites == null || invites.getAsBoolean());
            } catch (RuntimeException e) {
                return null;
            }
        }

        /** What the service starts with while Studio has never set its lists (the table's), invites allowed. */
        public static Lists defaults() {
            Table table = table();
            if (table == null) return new Lists(0, Collections.<String>emptyList(), Collections.<String>emptyList(), true);
            return new Lists(table.version, table.studioTrusted, table.studioBlocked, true);
        }

        @Nullable
        private static JsonElement given(JsonObject o, String key) {
            JsonElement e = o.get(key);
            return e == null || e.isJsonNull() ? null : e;
        }

        /** The strings of a list (none when it is missing); null when it is no list of strings. */
        @Nullable
        private static List<String> entries(@Nullable JsonElement e) {
            if (e == null) return Collections.emptyList();
            if (!e.isJsonArray()) return null;
            List<String> out = new ArrayList<>();
            for (JsonElement item : e.getAsJsonArray()) {
                if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) return null;
                out.add(item.getAsString());
            }
            return out;
        }
    }

    /** A plain link found in a text: where it is, as written, and its parts. */
    public static final class Found {
        /** UTF-16 offsets in the text. */
        public final int start, end;
        /** As written (trailing punctuation and a Turkish case ending left out). */
        public final String url;
        /** Lower case: "https", "http". */
        public final String scheme;
        /** What lies between "//" and the first "/", "?" or "#", as written. */
        public final String authority;
        /** The path, query and fragment, as written. */
        public final String rest;

        Found(int start, int end, String url, String scheme, String authority, String rest) {
            this.start = start;
            this.end = end;
            this.url = url;
            this.scheme = scheme;
            this.authority = authority;
            this.rest = rest;
        }
    }

    /** A link of a message as the conversation draws it now. */
    public static final class MessageLink {
        public final Found link;
        public final String host;
        /** It may be pressed: it passes the rules and Studio's lists as they are now. */
        public final boolean ok;
        /** A trusted site: it opens without the warning. */
        public final boolean trusted;
        /** Why not, when it may not be pressed. */
        @Nullable public final Rule rule;

        MessageLink(Found link, String host, boolean ok, boolean trusted, @Nullable Rule rule) {
            this.link = link;
            this.host = host;
            this.ok = ok;
            this.trusted = trusted;
            this.rule = rule;
        }
    }

    // ---- the rule table ----

    /** Where the rule table is read from: the app gives its asset social/chat-links.json. */
    public interface Source {
        InputStream open() throws IOException;
    }

    private static Source sSource;
    private static Table sTable;
    private static boolean sUnreadable;

    /** Where the table is read from; it is read on next use (again after a new source). */
    public static synchronized void setSource(@Nullable Source source) {
        sSource = source;
        sTable = null;
        sUnreadable = false;
    }

    /** The version of the rule table the app carries (the service names its own in {@link Lists#rules}); 0 without it. */
    public static int rulesVersion() {
        Table table = table();
        return table == null ? 0 : table.version;
    }

    /** The table, read once; null without a source, or when it does not read (until the next source). */
    @Nullable
    private static synchronized Table table() {
        if (sTable != null || sUnreadable || sSource == null) return sTable;
        try (InputStream in = sSource.open()) {
            sTable = new Table(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
        } catch (IOException | RuntimeException e) {
            sUnreadable = true;
        }
        return sTable;
    }

    // ---- finding links ----

    private static final char LONG_S = (char) 0x017F;
    private static final String URL_SIGNS = "-._~:/?#[]@!$&'()*+,;=%";

    /**
     * The plain links of a (cleaned) text, in order: "http://" or "https://" in any case, not right
     * after a letter or a digit, and the URL characters after it, less what only ends a sentence.
     */
    public static List<Found> find(String text) {
        List<Found> out = new ArrayList<>();
        int n = text.length();
        int i = 0;
        while (i < n) {
            int scheme = schemeAt(text, i);
            if (scheme == 0 || (i > 0 && letterOrNumber(text.codePointBefore(i)))) {
                i++;
                continue;
            }
            int end = i + scheme;
            while (end < n) {
                int cp = text.codePointAt(end);
                if (!urlChar(cp)) break;
                end += Character.charCount(cp);
            }
            String url = trimmed(text.substring(i, end));
            int after = url.indexOf("://") + 3;
            String tail = url.substring(after);
            int cut = -1;
            for (int k = 0; k < tail.length() && cut < 0; k++) {
                char c = tail.charAt(k);
                if (c == '/' || c == '?' || c == '#') cut = k;
            }
            out.add(new Found(i, i + url.length(), url, url.substring(0, after - 3).toLowerCase(Locale.ROOT),
                    cut < 0 ? tail : tail.substring(0, cut), cut < 0 ? "" : tail.substring(cut)));
            i = end;
        }
        return out;
    }

    /**
     * The length of "https://" or "http://" at {@code at}, else 0. Any case, as JavaScript's
     * case-insensitive Unicode match reads it: the long s is an s too.
     */
    private static int schemeAt(String text, int at) {
        if (at + 7 > text.length()) return 0;
        if (lower(text.charAt(at)) != 'h' || lower(text.charAt(at + 1)) != 't' || lower(text.charAt(at + 2)) != 't'
                || lower(text.charAt(at + 3)) != 'p') {
            return 0;
        }
        char s = text.charAt(at + 4);
        if ((s == 's' || s == 'S' || s == LONG_S) && text.startsWith("://", at + 5)) return 8;
        return text.startsWith("://", at + 4) ? 7 : 0;
    }

    private static char lower(char c) {
        return c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c;
    }

    /** URL characters: ASCII letters, digits and signs, and letters, marks and digits of any script. */
    private static boolean urlChar(int cp) {
        if (cp < 0x80) {
            return (cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z') || (cp >= '0' && cp <= '9')
                    || URL_SIGNS.indexOf(cp) >= 0;
        }
        return letterOrNumber(cp) || ChatText.mark(cp);
    }

    private static boolean letter(int cp) {
        int type = Character.getType(cp);
        return type == Character.UPPERCASE_LETTER || type == Character.LOWERCASE_LETTER
                || type == Character.TITLECASE_LETTER || type == Character.MODIFIER_LETTER
                || type == Character.OTHER_LETTER;
    }

    private static boolean letterOrNumber(int cp) {
        int type = Character.getType(cp);
        return letter(cp) || type == Character.DECIMAL_DIGIT_NUMBER || type == Character.LETTER_NUMBER
                || type == Character.OTHER_NUMBER;
    }

    /**
     * A link as written, without what only follows it in a sentence, one at a time from the end:
     * . , ; : ! ? ' *, a ")" or "]" without its opening one, an apostrophe and letters only (a
     * Turkish case ending: "x'i izle").
     */
    private static String trimmed(String raw) {
        String url = raw;
        while (true) {
            int scheme = url.indexOf("://") + 3;
            if (url.length() <= scheme) return url;
            char last = url.charAt(url.length() - 1);
            int ending = caseEnding(url);
            if (ending > scheme) url = url.substring(0, ending);
            else if (".,;:!?'*".indexOf(last) >= 0) url = url.substring(0, url.length() - 1);
            else if (last == ')' && count(url, '(') < count(url, ')')) url = url.substring(0, url.length() - 1);
            else if (last == ']' && count(url, '[') < count(url, ']')) url = url.substring(0, url.length() - 1);
            else return url;
        }
    }

    /** Where an apostrophe that only letters follow ends the text, or -1 (only the last one can). */
    private static int caseEnding(String url) {
        int quote = url.lastIndexOf('\'');
        if (quote < 0 || quote == url.length() - 1) return -1;
        for (int i = quote + 1; i < url.length(); ) {
            int cp = url.codePointAt(i);
            if (!letter(cp)) return -1;
            i += Character.charCount(cp);
        }
        return quote;
    }

    private static int count(String text, char c) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == c) n++;
        }
        return n;
    }

    /** {@code text} with its plain links blanked out (the other checks read the rest). */
    static String withoutLinks(String text, List<Found> links) {
        StringBuilder out = new StringBuilder(text.length());
        int at = 0;
        for (Found link : links) {
            out.append(text, at, link.start);
            for (int i = link.start; i < link.end; i++) out.append(' ');
            at = link.end;
        }
        return out.append(text, at, text.length()).toString();
    }

    /** The host of a link that passed the first checks: lower case, without a trailing dot. */
    public static String hostOf(Found link) {
        String host = link.authority.toLowerCase(Locale.ROOT);
        return host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
    }

    /** A trusted site (its subdomains too): its links open without the warning. */
    public static boolean trusted(String host, Lists lists) {
        return anyUnder(host, lists.trusted);
    }

    /** {@code host} is {@code domain} or one of its subdomains. */
    private static boolean under(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    private static boolean anyUnder(String host, List<String> domains) {
        for (String domain : domains) {
            if (under(host, domain)) return true;
        }
        return false;
    }

    /**
     * Whether a list entry names a link: a host (with its subdomains), or a host and the start of its
     * path in whole segments, without regard to case ("youtube.com/@kanal" names "/@kanal" and
     * "/@kanal/videos", not "/@kanallar").
     */
    static boolean entryNames(String entry, String host, String rest) {
        int slash = entry.indexOf('/');
        if (slash < 0) return under(host, entry);
        String prefix = entry.substring(slash).toLowerCase(Locale.ROOT);
        String path = rest.toLowerCase(Locale.ROOT);
        if (!under(host, entry.substring(0, slash)) || !path.startsWith(prefix)) return false;
        if (prefix.endsWith("/") || path.length() == prefix.length()) return true;
        char next = path.charAt(prefix.length());
        return next == '/' || next == '?' || next == '#';
    }

    // ---- the rules ----

    private static final Pattern LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
    private static final Pattern TLD = Pattern.compile("[a-z]{2,24}");
    private static final Pattern PHONE_IN_LINK = Pattern.compile("(?:^|[^0-9])\\+?[0-9]{10,15}(?![0-9])");

    /** Why one plain link may not go ("phone": it carries a phone number), or null when it may. */
    @Nullable
    private static String linkProblem(Found link, Lists lists, Table table) {
        if (!link.scheme.equals("https")) return "http";
        String authority = link.authority;
        if (authority.indexOf('@') >= 0) return "credentials";
        // An address with or without a port is an address first.
        int colon = authority.lastIndexOf(':');
        String named = colon < 0 ? authority : authority.substring(0, colon);
        if (authority.startsWith("[") || digitsAndDots(named)) return "ip";
        if (colon >= 0) return "port";
        String host = hostOf(link);
        String[] labels = host.split("\\.", -1);
        boolean punycode = false;
        for (String label : labels) punycode |= label.startsWith("xn--");
        if (!printableAscii(host) || punycode) return "lookalike";
        boolean hostName = host.length() <= 253 && labels.length >= 2 && TLD.matcher(labels[labels.length - 1]).matches();
        for (String label : labels) hostName &= LABEL.matcher(label).matches();
        if (!hostName) return "form";
        if (link.url.codePointCount(0, link.url.length()) > table.maxLength) return "long";
        if (anyUnder(host, table.shorteners)) return "shortener";
        if (anyUnder(host, table.grabbers)) return "grabber";
        if (table.adult.contains(labels[labels.length - 1])) return "adult";
        if (!trusted(host, lists) && table.lookalike(host)) return "lookalike";
        if (anyUnder(host, table.messengers) && PHONE_IN_LINK.matcher(decoded(link.rest)).find()) return "phone";
        if (table.scammy(host + " " + decoded(link.rest))) return "scam";
        String path = decoded(beforeQuery(link.rest)).toLowerCase(Locale.ROOT);
        for (String segment : path.split("/", -1)) {
            int dot = segment.lastIndexOf('.');
            if (dot >= 0 && table.files.contains(segment.substring(dot + 1))) return "file";
        }
        if (!lists.invites && table.discordInvite(host, link.rest)) return "invite";
        for (String entry : lists.blocked) {
            if (entryNames(entry, host, link.rest)) return "blocked";
        }
        return null;
    }

    private static boolean digitsAndDots(String text) {
        if (text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '.' && (c < '0' || c > '9')) return false;
        }
        return true;
    }

    private static boolean printableAscii(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 || c > 0x7E) return false;
        }
        return true;
    }

    private static String beforeQuery(String rest) {
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '?' || c == '#') return rest.substring(0, i);
        }
        return rest;
    }

    /**
     * Percent escapes read as JavaScript's decodeURIComponent reads them (every escape, UTF-8, "+"
     * stays itself), or the text as it is when one does not read: no URLDecoder, which reads "+" as
     * a space and gives up differently.
     */
    static String decoded(String text) {
        if (text.indexOf('%') < 0) return text;
        StringBuilder out = new StringBuilder(text.length());
        int n = text.length();
        for (int k = 0; k < n; k++) {
            char c = text.charAt(k);
            if (c != '%') {
                out.append(c);
                continue;
            }
            int b = octet(text, k);
            if (b < 0) return text;
            k += 2;
            if (b < 0x80) {
                out.append((char) b);
                continue;
            }
            int size = b >= 0xF8 ? 5 : b >= 0xF0 ? 4 : b >= 0xE0 ? 3 : b >= 0xC0 ? 2 : 1;
            if (size == 1 || size > 4) return text;
            int cp = b & (0xFF >> (size + 1));
            for (int j = 1; j < size; j++) {
                k++;
                int next = k < n && text.charAt(k) == '%' ? octet(text, k) : -1;
                if (next < 0 || (next & 0xC0) != 0x80) return text;
                cp = (cp << 6) | (next & 0x3F);
                k += 2;
            }
            int least = size == 2 ? 0x80 : size == 3 ? 0x800 : 0x10000;
            if (cp < least || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) return text;
            out.appendCodePoint(cp);
        }
        return out.toString();
    }

    /** The byte of the escape "%XX" at {@code at}, or -1. */
    private static int octet(String text, int at) {
        if (at + 3 > text.length()) return -1;
        int high = hex(text.charAt(at + 1));
        int low = hex(text.charAt(at + 2));
        return high < 0 || low < 0 ? -1 : high * 16 + low;
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    private static final char DOTTED_CAPITAL_I = (char) 0x0130;
    private static final char DOTLESS_SMALL_I = (char) 0x0131;

    /**
     * The scam form: lower case the Turkish way, marks dropped, the dotless i read as i, leet digits
     * and signs as letters (0 o, 1 i, 3 e, 4 a, 5 s, 7 t, @ a, $ s, ! i), anything else a space,
     * words apart with one space before and after each.
     */
    static String scamForm(String text) {
        String lower = Normalizer.normalize(text.replace(DOTTED_CAPITAL_I, 'i').replace('I', DOTLESS_SMALL_I)
                .toLowerCase(Locale.ROOT), Normalizer.Form.NFKD);
        StringBuilder out = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); ) {
            int cp = lower.codePointAt(i);
            i += Character.charCount(cp);
            if (ChatText.mark(cp)) continue;
            int mapped = cp == DOTLESS_SMALL_I ? 'i' : leet(cp);
            out.append(mapped >= 'a' && mapped <= 'z' ? (char) mapped : ' ');
        }
        return " " + SPACES.matcher(out).replaceAll(" ").trim() + " ";
    }

    private static final Pattern SPACES = Pattern.compile(" +");

    private static int leet(int cp) {
        switch (cp) {
            case '0': return 'o';
            case '1': return 'i';
            case '3': return 'e';
            case '4': return 'a';
            case '5': return 's';
            case '7': return 't';
            case '@': return 'a';
            case '$': return 's';
            case '!': return 'i';
            default: return cp;
        }
    }

    /** Edit distance with swaps of two neighbours (optimal string alignment). */
    static int typoDistance(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) d[i][0] = i;
        for (int j = 0; j <= b.length(); j++) d[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                int best = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    best = Math.min(best, d[i - 2][j - 2] + 1);
                }
                d[i][j] = best;
            }
        }
        return d[a.length()][b.length()];
    }

    /** A name as the eye reads it (the table's confusables); the name itself without the table. */
    static String skeleton(String name) {
        Table table = table();
        return table == null ? name : table.skeleton(name);
    }

    /** Whether a (valid, lower-case) host borrows a brand's name without being the brand's. */
    static boolean lookalike(String host) {
        Table table = table();
        return table != null && table.lookalike(host);
    }

    // ---- a message ----

    /** What the rules say about a text's plain links: a phone number, a {@code rule}, or neither and the {@code rest}. */
    static final class Check {
        final boolean phone;
        @Nullable final Rule rule;
        /** The text with its links blanked out, for the text checks (when the links may go). */
        final String rest;

        Check(boolean phone, @Nullable Rule rule, String rest) {
            this.phone = phone;
            this.rule = rule;
            this.rest = rest;
        }
    }

    /**
     * A cleaned text's plain links against the rules: at most three, each allowed, no scam phrase
     * beside them; null without the table (links then count as off).
     */
    @Nullable
    static Check check(String text, Lists lists) {
        Table table = table();
        if (table == null) return null;
        List<Found> found = find(text);
        if (found.size() > table.maxLinks) return new Check(false, Rule.COUNT, text);
        for (Found link : found) {
            String problem = linkProblem(link, lists, table);
            if ("phone".equals(problem)) return new Check(true, null, text);
            if (problem != null) return new Check(false, Rule.of(problem), text);
        }
        String rest = withoutLinks(text, found);
        if (!found.isEmpty() && table.scammy(rest)) return new Check(false, Rule.SCAM, text);
        return new Check(false, null, rest);
    }

    /**
     * The links of a message's text against the rules as they are now (a site Studio blocks later
     * turns back into plain text in older messages too); {@code lists} null while links are off:
     * then none may be pressed ({@link Rule#OFF}), as without the table.
     */
    public static List<MessageLink> messageLinks(String text, @Nullable Lists lists) {
        Table table = lists == null ? null : table();
        List<Found> found = find(text);
        List<MessageLink> out = new ArrayList<>(found.size());
        for (int i = 0; i < found.size(); i++) {
            Found link = found.get(i);
            String host = hostOf(link);
            String problem = table == null ? "off" : i >= table.maxLinks ? "count" : linkProblem(link, lists, table);
            // A link with a phone number is no link a conversation draws.
            Rule rule = problem == null ? null : problem.equals("phone") ? Rule.FORM : Rule.of(problem);
            boolean ok = rule == null;
            out.add(new MessageLink(link, host, ok, ok && trusted(host, lists), rule));
        }
        return out;
    }

    /** The rule table (chat-links.json) as the checks use it; it throws while built from a table that does not read. */
    private static final class Table {
        final int version;
        final int maxLinks;
        final int maxLength;
        final List<String> shorteners;
        final List<String> grabbers;
        final List<String> messengers;
        final Set<String> files;
        final Set<String> adult;
        final List<String[]> confusables = new ArrayList<>();
        final Set<String> secondLevel;
        /** Every brand's names and own domains (the latter also as labels); names read through the confusables. */
        final List<String> keys = new ArrayList<>();
        final List<String> official = new ArrayList<>();
        final List<String[]> officialLabels = new ArrayList<>();
        final Set<String> lureWords = new HashSet<>();
        final int lureMax;
        /** The scam phrases, a space before and after (whole words). */
        final List<String> scams = new ArrayList<>();
        final List<String> inviteHosts;
        final List<String> invitePaths;
        final List<String> studioTrusted;
        final List<String> studioBlocked;

        Table(JsonObject o) {
            version = positive(o, "version");
            JsonObject limits = object(o, "limits");
            maxLinks = positive(limits, "links");
            maxLength = positive(limits, "length");
            shorteners = words(o, "shorteners");
            grabbers = words(o, "grabbers");
            messengers = words(object(o, "messengers"), "hosts");
            files = new HashSet<>(words(object(o, "files"), "extensions"));
            adult = new HashSet<>(words(object(o, "adult"), "tlds"));
            JsonObject alike = object(o, "lookalike");
            for (JsonElement pair : array(alike, "confusables")) {
                JsonArray both = pair.getAsJsonArray();
                if (both.size() != 2) throw new IllegalStateException("confusables");
                confusables.add(new String[]{word(both.get(0)), word(both.get(1))});
            }
            secondLevel = new HashSet<>(words(alike, "secondLevel"));
            for (JsonElement brand : array(alike, "brands")) {
                for (String key : words(brand.getAsJsonObject(), "keys")) keys.add(skeleton(key));
                for (String domain : words(brand.getAsJsonObject(), "official")) {
                    official.add(domain);
                    officialLabels.add(domain.split("\\.", -1));
                }
            }
            int longest = 0;
            for (String lure : words(alike, "lures")) {
                String read = skeleton(lure);
                lureWords.add(read);
                longest = Math.max(longest, read.length());
            }
            lureMax = longest;
            for (String phrase : words(object(o, "scams"), "phrases")) scams.add(" " + phrase + " ");
            JsonObject invites = object(o, "invites");
            inviteHosts = words(invites, "hosts");
            invitePaths = words(invites, "paths");
            JsonObject studio = object(o, "studio");
            studioTrusted = words(studio, "trusted");
            studioBlocked = words(studio, "blocked");
        }

        String skeleton(String name) {
            String out = name.replace("-", "");
            for (String[] pair : confusables) out = out.replace(pair[0], pair[1]);
            return out;
        }

        /** Whether {@code text} is made of lure words only (or is empty). */
        boolean lures(String text) {
            boolean[] can = new boolean[text.length() + 1];
            can[0] = true;
            for (int end = 1; end <= text.length(); end++) {
                for (int start = Math.max(0, end - lureMax); start < end && !can[end]; start++) {
                    can[end] = can[start] && lureWords.contains(text.substring(start, end));
                }
            }
            return can[text.length()];
        }

        /** The label before the public suffix ("example" of "www.example.com.tr"). */
        String mainLabel(String[] labels) {
            int n = labels.length;
            String second = n >= 2 ? labels[n - 2] : "";
            return n >= 3 && labels[n - 1].length() == 2 && secondLevel.contains(second) ? labels[n - 3] : second;
        }

        boolean lookalike(String host) {
            if (anyUnder(host, official)) return false;
            String[] labels = host.split("\\.", -1);
            // An official domain as whole labels inside the host, more labels after it.
            for (String[] parts : officialLabels) {
                for (int i = 0; i + parts.length < labels.length; i++) {
                    boolean inside = true;
                    for (int k = 0; k < parts.length && inside; k++) inside = labels[i + k].equals(parts[k]);
                    if (inside) return true;
                }
            }
            String name = skeleton(mainLabel(labels));
            for (String key : keys) {
                if (name.equals(key)) return true;
                if (key.length() >= 6 && Math.abs(name.length() - key.length()) <= 1 && typoDistance(name, key) == 1) {
                    return true;
                }
                if (key.length() >= 5) {
                    for (int at = name.indexOf(key); at >= 0; at = name.indexOf(key, at + 1)) {
                        String before = name.substring(0, at);
                        String after = name.substring(at + key.length());
                        if (before.length() + after.length() > 0 && lures(before) && lures(after)) return true;
                    }
                }
            }
            return false;
        }

        boolean scammy(String text) {
            String form = scamForm(text);
            for (String phrase : scams) {
                if (form.contains(phrase)) return true;
            }
            return false;
        }

        boolean discordInvite(String host, String rest) {
            if (anyUnder(host, inviteHosts)) return true;
            for (String entry : invitePaths) {
                if (entryNames(entry, host, rest)) return true;
            }
            return false;
        }

        private static JsonObject object(JsonObject o, String key) {
            JsonElement e = o.get(key);
            if (e == null || !e.isJsonObject()) throw new IllegalStateException(key);
            return e.getAsJsonObject();
        }

        private static JsonArray array(JsonObject o, String key) {
            JsonElement e = o.get(key);
            if (e == null || !e.isJsonArray()) throw new IllegalStateException(key);
            return e.getAsJsonArray();
        }

        private static int positive(JsonObject o, String key) {
            JsonElement e = o.get(key);
            if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber() || e.getAsInt() <= 0) {
                throw new IllegalStateException(key);
            }
            return e.getAsInt();
        }

        private static List<String> words(JsonObject o, String key) {
            List<String> out = new ArrayList<>();
            for (JsonElement item : array(o, key)) out.add(word(item));
            return Collections.unmodifiableList(out);
        }

        private static String word(JsonElement e) {
            if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw new IllegalStateException("word");
            return e.getAsString();
        }
    }
}
