package gg.projects.mobile.cloud;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import gg.projects.mobile.cloud.CloudModel.CommandView;
import gg.projects.mobile.cloud.CloudModel.ConfigView;
import gg.projects.mobile.cloud.CloudModel.Limits;
import gg.projects.mobile.cloud.CloudModel.PayloadView;
import gg.projects.mobile.cloud.CloudModel.RuleView;

/**
 * An account's commands and auto-replies as the phone's settings screen holds them (services/cloud-afk README
 * "Account config"; the launcher's {@code config.rs}), checked against the contract's rules and turned into the
 * change the service takes: secret rows typed on the phone are sealed here, stored seals are kept ({@code keep}),
 * a new server needs them typed again. The phone keeps no copy of a secret: "Göster" asks the machine.
 */
public final class CloudEdit {
    private CloudEdit() {}

    static final int HARD_MAX_ROWS = 50;
    static final int MAX_DAILY_TIMES = 6;
    static final int MAX_DELAY_SECS = 60;
    static final int MAX_SECS = 86_400;
    static final int ANTI_AFK_MIN = 10;
    static final int ANTI_AFK_MAX = 3_600;
    static final int MAX_PATTERN_CHARS = 256;

    /** A row's words in the editor. */
    public static final class Words {
        public enum Kind { PLAIN, SECRET, SEALED }

        public final Kind kind;
        /** PLAIN and SECRET: the words; SEALED: empty. */
        public final String text;
        /** SEALED: the preview; SECRET: the preview it replaces, if it was revealed from a stored seal. */
        @Nullable
        public final String preview;
        /** SECRET: the words came from "Göster" of the stored seal (unchanged, the seal is kept). */
        public final boolean revealed;

        private Words(Kind kind, String text, @Nullable String preview, boolean revealed) {
            this.kind = kind;
            this.text = text;
            this.preview = preview;
            this.revealed = revealed;
        }

        public static Words plain(String text) {
            return new Words(Kind.PLAIN, text, null, false);
        }

        public static Words secret(String text) {
            return new Words(Kind.SECRET, text, null, false);
        }

        public static Words sealed(@Nullable String preview) {
            return new Words(Kind.SEALED, "", preview == null ? "******" : preview, false);
        }

        /** "Göster" of a stored seal: its words, kept as they are stored unless they change. */
        public static Words revealed(String text, @Nullable String preview) {
            return new Words(Kind.SECRET, text, preview, true);
        }

        public boolean secretKind() {
            return kind != Kind.PLAIN;
        }

        /** The same row, its words changed by the player (a revealed seal no longer counts as kept). */
        public Words typed(String next) {
            return kind == Kind.PLAIN ? plain(next) : secret(next);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Words)) return false;
            Words w = (Words) o;
            return kind == w.kind && text.equals(w.text) && revealed == w.revealed
                    && (preview == null ? w.preview == null : preview.equals(w.preview));
        }

        @Override
        public int hashCode() {
            return kind.hashCode() * 31 + text.hashCode();
        }
    }

    public static final class Command {
        public String id;
        public boolean enabled;
        public String on;
        public int delay;
        public int everyMin;
        public int everyMax;
        public List<String> at;
        public Words words;
        /** CAFK-MOD: moderation paused it ("Durduruldu"). */
        public boolean paused;

        public Command(String id, boolean enabled, String on, int delay, int everyMin, int everyMax, List<String> at, Words words) {
            this.id = id;
            this.enabled = enabled;
            this.on = on;
            this.delay = delay;
            this.everyMin = everyMin;
            this.everyMax = everyMax;
            this.at = new ArrayList<>(at);
            this.words = words;
        }

        public Command copy() {
            Command c = new Command(id, enabled, on, delay, everyMin, everyMax, at, words);
            c.paused = paused;
            return c;
        }

        boolean same(Command o) {
            return id.equals(o.id) && enabled == o.enabled && on.equals(o.on) && delay == o.delay && everyMin == o.everyMin
                    && everyMax == o.everyMax && at.equals(o.at) && words.equals(o.words);
        }
    }

    public static final class Rule {
        public String id;
        public boolean enabled;
        public String pattern;
        public boolean regex;
        public String from;
        public boolean ownerOnly;
        public int cooldown;
        public Words reply;
        public boolean paused;

        public Rule(String id, boolean enabled, String pattern, boolean regex, String from, boolean ownerOnly, int cooldown, Words reply) {
            this.id = id;
            this.enabled = enabled;
            this.pattern = pattern;
            this.regex = regex;
            this.from = from;
            this.ownerOnly = ownerOnly;
            this.cooldown = cooldown;
            this.reply = reply;
        }

        public Rule copy() {
            Rule r = new Rule(id, enabled, pattern, regex, from, ownerOnly, cooldown, reply);
            r.paused = paused;
            return r;
        }

        boolean same(Rule o) {
            return id.equals(o.id) && enabled == o.enabled && pattern.equals(o.pattern) && regex == o.regex && from.equals(o.from)
                    && ownerOnly == o.ownerOnly && cooldown == o.cooldown && reply.equals(o.reply);
        }
    }

    public static final class Config {
        public String owner;
        public boolean antiAfk;
        public int delayMin;
        public int delayMax;
        public final List<Command> commands = new ArrayList<>();
        public final List<Rule> rules = new ArrayList<>();
        /**
         * CAFK-FEAT: the player's inventory choice; null when never chosen (a save then leaves it out, and the service
         * keeps whatever it has: a field this app sends only when it knows it).
         */
        @Nullable
        public Boolean inventory;
        /**
         * Movement: the player's choice; null when not stored and not touched (a save then leaves it out: the service
         * keeps what it has, and an older one never sees the field).
         */
        @Nullable
        public Boolean movement;

        public Config(String owner, boolean antiAfk, int delayMin, int delayMax) {
            this.owner = owner;
            this.antiAfk = antiAfk;
            this.delayMin = delayMin;
            this.delayMax = delayMax;
        }

        public Config copy() {
            Config c = new Config(owner, antiAfk, delayMin, delayMax);
            for (Command command : commands) c.commands.add(command.copy());
            for (Rule rule : rules) c.rules.add(rule.copy());
            c.inventory = inventory;
            c.movement = movement;
            return c;
        }

        /** Whether only the inventory choice differs from {@code other} (a change that applies at the next start, no restart). */
        public boolean onlyInventoryDiffers(Config other) {
            Config same = copy();
            same.inventory = other.inventory;
            return same.same(other) && !same(other);
        }

        /** The same settings as {@code other} (the save bar shows while they differ). */
        public boolean same(Config other) {
            if (inventory == null ? other.inventory != null : !inventory.equals(other.inventory)) return false;
            if (movement == null ? other.movement != null : !movement.equals(other.movement)) return false;
            if (!owner.equals(other.owner) || antiAfk != other.antiAfk || delayMin != other.delayMin || delayMax != other.delayMax) return false;
            if (commands.size() != other.commands.size() || rules.size() != other.rules.size()) return false;
            for (int i = 0; i < commands.size(); i++) if (!commands.get(i).same(other.commands.get(i))) return false;
            for (int i = 0; i < rules.size(); i++) if (!rules.get(i).same(other.rules.get(i))) return false;
            return true;
        }

        public Set<String> ids() {
            Set<String> ids = new HashSet<>();
            for (Command c : commands) ids.add(c.id);
            for (Rule r : rules) ids.add(r.id);
            return ids;
        }
    }

    /** The editor's form of the account view: a secret row shows only its preview until "Göster". */
    public static Config from(ConfigView view) {
        Config config = new Config(view.owner, view.antiAfk, view.delayMin, view.delayMax);
        config.inventory = view.inventory;
        config.movement = view.movement;
        for (CommandView row : view.commands) {
            Command c = new Command(row.id, row.enabled, row.on, row.delay, row.everyMin, row.everyMax, row.at, words(row.payload));
            c.paused = row.paused;
            config.commands.add(c);
        }
        for (RuleView rule : view.rules) {
            Rule r = new Rule(rule.id, rule.enabled, rule.match, rule.regex, rule.from, rule.ownerOnly, rule.cooldown, words(rule.reply));
            r.paused = rule.paused;
            config.rules.add(r);
        }
        return config;
    }

    private static Words words(PayloadView payload) {
        return payload.secret ? Words.sealed(payload.preview) : Words.plain(payload.text == null ? "" : payload.text);
    }

    /**
     * Where a problem is and what it is: {@code place} is {@code general}, {@code commands} or {@code rules}; {@code index}
     * the row (-1: none); {@code field} what of it; {@code problem} a key the screen words.
     */
    public static final class Issue {
        public final String place;
        public final int index;
        public final String field;
        public final String problem;

        public Issue(String place, int index, String field, String problem) {
            this.place = place;
            this.index = index;
            this.field = field;
            this.problem = problem;
        }

        static Issue general(String field, String problem) {
            return new Issue("general", -1, field, problem);
        }

        static Issue row(String place, int index, String field, String problem) {
            return new Issue(place, index, field, problem);
        }

        /** The issue of a refusal's {@code field} path ({@code commands[2].every.min}, {@code rules[0].reply}). */
        public static Issue fromPath(@Nullable String path, String problem) {
            if (path == null || path.isEmpty()) return general("config", problem);
            int dot = path.indexOf('.');
            String head = dot < 0 ? path : path.substring(0, dot);
            String rest = dot < 0 ? "" : path.substring(dot + 1);
            Matcher m = Pattern.compile("^(commands|rules)\\[(\\d+)]$").matcher(head);
            if (m.matches()) {
                String place = m.group(1);
                int index = Integer.parseInt(m.group(2));
                String field = rest.split("[.\\[]", 2)[0];
                if (place.equals("commands")) {
                    if (field.isEmpty() || field.equals("secret") || field.equals("sealed") || field.equals("preview") || field.equals("keep")) field = "text";
                } else if (field.isEmpty()) {
                    field = "reply";
                }
                return row(place, index, field, problem);
            }
            if (head.equals("commands") || head.equals("rules")) return new Issue(head, -1, "count", problem);
            return general(head.split("[.\\[]", 2)[0], problem);
        }

        @Override
        public String toString() {
            return place + (index >= 0 ? "[" + index + "]" : "") + "." + field + ":" + problem;
        }
    }

    static boolean isRowId(@Nullable String id) {
        if (id == null || id.isEmpty() || id.length() > 16 || CloudSeal.CONSOLE_ROW.equals(id)) return false;
        char first = id.charAt(0);
        if (first < 'a' || first > 'z') return false;
        for (int i = 1; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))) return false;
        }
        return true;
    }

    public static boolean isClock(@Nullable String time) {
        return time != null && time.matches("^([01]\\d|2[0-3]):[0-5]\\d$");
    }

    /** The in-game name rule (3-16 of A-Z, a-z, 0-9 and _). */
    public static boolean isGameName(@Nullable String name) {
        return name != null && name.matches("^[A-Za-z0-9_]{3,16}$");
    }

    /** Returns whether the words passed the text rule (the placeholder checks come only after it). */
    private static boolean checkWords(Words words, String place, int index, String field, List<Issue> issues) {
        if (words.kind == Words.Kind.SEALED) return false;
        CloudText.Problem problem = CloudText.check(words.text);
        if (problem != null) issues.add(Issue.row(place, index, field, problem.code));
        return problem == null;
    }

    /** CAFK-FEAT: what the save checks of the account's switches need ({@link #NONE}: placeholders off). */
    public static final class Checks {
        public static final Checks NONE = new Checks(false, CloudModel.DEFAULT_PLACEHOLDER_COMMANDS);
        public final boolean placeholders;
        public final List<String> commands;

        public Checks(boolean placeholders, List<String> commands) {
            this.placeholders = placeholders;
            this.commands = commands;
        }
    }

    /** Everything the service would refuse, found before anything is sent (Studio's numbers from {@code /offer}). */
    public static List<Issue> validate(Config config, Limits limits) {
        return validate(config, limits, Checks.NONE);
    }

    /**
     * {@link #validate(Config, Limits)} with the placeholder checks while the account's {@code placeholders} switch is on
     * (with it off, braces are plain text as before): every row, a stored one included, the first failure per row.
     */
    public static List<Issue> validate(Config config, Limits limits, Checks checks) {
        List<Issue> issues = new ArrayList<>();
        if (!config.owner.isEmpty() && !isGameName(config.owner)) issues.add(Issue.general("owner", "format"));
        if (config.delayMin < ANTI_AFK_MIN || config.delayMin > ANTI_AFK_MAX || config.delayMax < ANTI_AFK_MIN
                || config.delayMax > ANTI_AFK_MAX || config.delayMin > config.delayMax) {
            issues.add(Issue.general("antiAfk", "range"));
        }
        if (config.commands.size() > Math.min(limits.maxCommandRows, HARD_MAX_ROWS)) issues.add(new Issue("commands", -1, "count", "limit"));
        if (config.rules.size() > Math.min(limits.maxRules, HARD_MAX_ROWS)) issues.add(new Issue("rules", -1, "count", "limit"));
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < config.commands.size(); i++) {
            Command row = config.commands.get(i);
            checkId(row.id, "commands", i, ids, issues);
            switch (row.on) {
                case "join":
                case "start":
                case "reconnect":
                    if (row.delay < 0 || row.delay > MAX_DELAY_SECS) issues.add(Issue.row("commands", i, "delay", "range"));
                    break;
                case "interval":
                    if (row.everyMin > row.everyMax) issues.add(Issue.row("commands", i, "every", "order"));
                    else if (row.everyMin < limits.minIntervalSec || row.everyMax > MAX_SECS) issues.add(Issue.row("commands", i, "every", "range"));
                    break;
                case "daily": {
                    Set<String> distinct = new HashSet<>(row.at);
                    if (row.at.isEmpty()) issues.add(Issue.row("commands", i, "at", "empty"));
                    else if (row.at.size() > MAX_DAILY_TIMES) issues.add(Issue.row("commands", i, "at", "many"));
                    else {
                        boolean clocks = true;
                        for (String t : row.at) if (!isClock(t)) clocks = false;
                        if (!clocks) issues.add(Issue.row("commands", i, "at", "format"));
                        else if (distinct.size() != row.at.size()) issues.add(Issue.row("commands", i, "at", "duplicate"));
                    }
                    break;
                }
                default:
                    issues.add(Issue.row("commands", i, "on", "format"));
                    break;
            }
            if (checkWords(row.words, "commands", i, "text", issues) && checks.placeholders && row.words.kind == Words.Kind.PLAIN) {
                CloudPlaceholders.Problem p = CloudPlaceholders.checkCommand(row.words.text);
                if (p != null) issues.add(Issue.row("commands", i, "text", p.reason));
            }
        }
        for (int i = 0; i < config.rules.size(); i++) {
            Rule rule = config.rules.get(i);
            checkId(rule.id, "rules", i, ids, issues);
            String problem = checkPattern(rule.pattern, rule.regex);
            if (problem != null) issues.add(Issue.row("rules", i, "match", problem));
            if (!rule.from.equals("any") && !rule.from.equals("player") && !rule.from.equals("private") && !rule.from.equals("server")) {
                issues.add(Issue.row("rules", i, "from", "format"));
            }
            if (rule.ownerOnly) {
                if (config.owner.isEmpty()) issues.add(Issue.row("rules", i, "ownerOnly", "owner"));
                else if (rule.from.equals("server")) issues.add(Issue.row("rules", i, "ownerOnly", "server"));
            }
            if (rule.cooldown < limits.minRuleCooldownSec || rule.cooldown > MAX_SECS) issues.add(Issue.row("rules", i, "cooldown", "range"));
            if (checkWords(rule.reply, "rules", i, "reply", issues) && checks.placeholders && rule.reply.kind == Words.Kind.PLAIN) {
                Issue p = placeholderIssue(rule, i, checks);
                if (p != null) issues.add(p);
            }
        }
        return issues;
    }

    /** A rule's plain reply against the placeholder rules: its issue ({@code placeholder_part/3} names the part), or null. */
    @Nullable
    public static Issue placeholderIssue(Rule rule, int index, Checks checks) {
        if (!checks.placeholders || rule.reply.kind != Words.Kind.PLAIN) return null;
        CloudPlaceholders.Problem p = CloudPlaceholders.checkReply(rule.reply.text,
                CloudPlaceholders.captureCount(rule.pattern.trim(), rule.regex), rule.from, checks.commands);
        if (p == null) return null;
        return Issue.row("rules", index, "reply", p.part > 0 ? p.reason + "/" + p.part : p.reason);
    }

    private static void checkId(String id, String place, int index, Set<String> ids, List<Issue> issues) {
        if (!isRowId(id)) issues.add(Issue.row(place, index, "id", "format"));
        else if (!ids.add(id)) issues.add(Issue.row(place, index, "id", "duplicate"));
    }

    /**
     * An auto-reply pattern (the site's {@code checkPattern}): plain text matched case-insensitively; a regular
     * expression one .NET and the machine's checker read the same way (no lookarounds, no backreferences, no
     * repetition inside a repetition, counts up to 1000). This catches the usual mistakes; the machine's full check
     * runs on save ({@code invalid_pattern}). Null when it may be saved.
     */
    @Nullable
    public static String checkPattern(String pattern, boolean regex) {
        String value = pattern.trim();
        if (value.isEmpty()) return "empty";
        if (value.codePointCount(0, value.length()) > MAX_PATTERN_CHARS) return "tooLong";
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c <= 0x1F || (c >= 0x7F && c <= 0x9F)) return "invalid";
        }
        if (!regex) return null;
        if (usesUnsupportedSyntax(value)) return "invalid";
        String javaSource = value.replaceAll("\\(\\?[ims]+\\)", "").replaceAll("\\(\\?[ims-]+:", "(?:");
        try {
            Pattern.compile(javaSource);
        } catch (PatternSyntaxException e) {
            return "invalid";
        }
        Matcher counts = Pattern.compile("\\{(\\d+)(?:,(\\d*))?}").matcher(value);
        while (counts.find()) {
            if (exceeds(counts.group(1)) || (counts.group(2) != null && !counts.group(2).isEmpty() && exceeds(counts.group(2)))) return "tooComplex";
        }
        if (hasNestedRepetition(value)) return "tooComplex";
        return null;
    }

    private static boolean exceeds(String digits) {
        return digits.length() > 4 || Integer.parseInt(digits) > 1000;
    }

    private static boolean usesUnsupportedSyntax(String pattern) {
        if (pattern.contains("[[:")) return true;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            char next = i + 1 < pattern.length() ? pattern.charAt(i + 1) : 0;
            if (c == '\\') {
                char after = i + 2 < pattern.length() ? pattern.charAt(i + 2) : 0;
                if ((next >= '1' && next <= '9') || next == 'U' || next == '<' || next == '>' || next == '_') return true;
                if (next == 'k' && after == '<') return true;
                if ((next == 'p' || next == 'P') && after != '{') return true;
                if (next == 'x' && after == '{') return true;
                i++;
                continue;
            }
            if (c == '(' && next == '?') {
                String rest = pattern.substring(i + 2);
                if (rest.matches("^(<?[=!]|P<)[\\s\\S]*")) return true;
                Matcher flags = Pattern.compile("^([A-Za-z-]+)[:)]").matcher(rest);
                if (flags.find() && flags.group(1).matches(".*[^ims-].*")) return true;
            }
        }
        return false;
    }

    /** A group that holds a repetition and is repeated itself ({@code (a+)+}, {@code (\w+\s?)*}). */
    private static boolean hasNestedRepetition(String pattern) {
        List<Boolean> stack = new ArrayList<>();
        boolean inClass = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\\') {
                char next = i + 1 < pattern.length() ? pattern.charAt(i + 1) : 0;
                if ((next == 'p' || next == 'P') && i + 2 < pattern.length() && pattern.charAt(i + 2) == '{') {
                    int end = pattern.indexOf('}', i + 3);
                    i = end < 0 ? pattern.length() : end;
                } else {
                    i++;
                }
                continue;
            }
            if (inClass) {
                if (c == ']') inClass = false;
                continue;
            }
            if (c == '[') {
                inClass = true;
                continue;
            }
            if (c == '(') {
                stack.add(false);
                continue;
            }
            if (c == ')') {
                boolean inner = !stack.isEmpty() && stack.remove(stack.size() - 1);
                if (inner && quantifier(pattern, i + 1) > 0) return true;
                if (inner && !stack.isEmpty()) stack.set(stack.size() - 1, true);
                continue;
            }
            int q = quantifier(pattern, i);
            if (q != 0) {
                if (q > 0 && !stack.isEmpty()) stack.set(stack.size() - 1, true);
                i += Math.abs(q) - 1;
            }
        }
        return false;
    }

    /** The quantifier at {@code at}: its length, negative when it never repeats; 0 when there is none. */
    private static int quantifier(String pattern, int at) {
        if (at >= pattern.length()) return 0;
        char c = pattern.charAt(at);
        if (c == '*' || c == '+') return 1;
        if (c != '{') return 0;
        Matcher m = Pattern.compile("^\\{(\\d+)(?:(,)(\\d*))?}").matcher(pattern.substring(at));
        if (!m.find()) return 0;
        boolean many = m.group(2) == null ? parse(m.group(1)) > 1 : m.group(3).isEmpty() || parse(m.group(3)) > 1;
        return many ? m.group(0).length() : -m.group(0).length();
    }

    private static long parse(String digits) {
        return digits.length() > 9 ? Long.MAX_VALUE : Long.parseLong(digits);
    }

    /** Seals a secret row's words for its row id (the real one is {@link CloudSeal}; tests replace it). */
    public interface Sealer {
        String seal(String text, String rowId) throws CloudSeal.SealException;
    }

    /** The change to send, or the issues found. */
    public static final class Built {
        @Nullable
        public final JsonObject config;
        public final List<Issue> issues;

        Built(@Nullable JsonObject config, List<Issue> issues) {
            this.config = config;
            this.issues = issues;
        }
    }

    /**
     * The config for {@code PATCH}: plain rows as they are (a password command is sealed anyway), secret rows typed on
     * the phone sealed with {@code sealer}, stored seals kept ({@code keep}); with a new server ({@code serverChanged})
     * every secret must be typed again ({@code reseal}). {@code fps}: the stored seals' fingerprints (a revealed row's
     * words unchanged keep their seal only while the stored seal is still there).
     */
    public static Built build(Config config, Limits limits, boolean serverChanged, Sealer sealer, Map<String, String> fps)
            throws CloudSeal.SealException {
        return build(config, limits, Checks.NONE, serverChanged, sealer, fps);
    }

    /** {@link #build(Config, Limits, boolean, Sealer, Map)} with the account's switches' checks. */
    public static Built build(Config config, Limits limits, Checks checks, boolean serverChanged, Sealer sealer, Map<String, String> fps)
            throws CloudSeal.SealException {
        List<Issue> issues = validate(config, limits, checks);
        if (serverChanged) {
            for (int i = 0; i < config.commands.size(); i++) {
                // A revealed row's words are known here: they are sealed again for the new server by themselves.
                if (config.commands.get(i).words.kind == Words.Kind.SEALED) issues.add(Issue.row("commands", i, "text", "reseal"));
            }
            for (int i = 0; i < config.rules.size(); i++) {
                if (config.rules.get(i).reply.kind == Words.Kind.SEALED) issues.add(Issue.row("rules", i, "reply", "reseal"));
            }
        }
        if (!issues.isEmpty()) return new Built(null, issues);
        JsonObject out = new JsonObject();
        out.addProperty("v", 1);
        out.addProperty("owner", config.owner);
        JsonObject anti = new JsonObject();
        anti.addProperty("enabled", config.antiAfk);
        anti.addProperty("delayMin", config.delayMin);
        anti.addProperty("delayMax", config.delayMax);
        out.add("antiAfk", anti);
        JsonArray commands = new JsonArray();
        for (Command row : config.commands) {
            JsonObject c = new JsonObject();
            c.addProperty("id", row.id);
            c.addProperty("enabled", row.enabled);
            c.addProperty("on", row.on);
            switch (row.on) {
                case "interval": {
                    JsonObject every = new JsonObject();
                    every.addProperty("min", row.everyMin);
                    every.addProperty("max", row.everyMax);
                    c.add("every", every);
                    break;
                }
                case "daily": {
                    List<String> times = new ArrayList<>(row.at);
                    Collections.sort(times);
                    JsonArray at = new JsonArray();
                    for (String t : times) at.add(t);
                    c.add("at", at);
                    break;
                }
                default:
                    c.addProperty("delay", row.delay);
                    break;
            }
            payload(c, row.id, row.words, serverChanged, sealer, fps);
            commands.add(c);
        }
        out.add("commands", commands);
        JsonArray rules = new JsonArray();
        for (Rule rule : config.rules) {
            JsonObject r = new JsonObject();
            r.addProperty("id", rule.id);
            r.addProperty("enabled", rule.enabled);
            r.addProperty("match", rule.pattern.trim());
            r.addProperty("regex", rule.regex);
            r.addProperty("from", rule.from);
            r.addProperty("ownerOnly", rule.ownerOnly);
            r.addProperty("cooldown", rule.cooldown);
            JsonObject reply = new JsonObject();
            payload(reply, rule.id, rule.reply, serverChanged, sealer, fps);
            r.add("reply", reply);
            rules.add(r);
        }
        out.add("rules", rules);
        // A field this phase added goes only when the player chose it: left out, the service keeps what it has.
        if (config.inventory != null) {
            JsonObject inventory = new JsonObject();
            inventory.addProperty("enabled", config.inventory);
            out.add("inventory", inventory);
        }
        // Movement likewise: known only from a service that has it (stored, or the switch its features offer).
        if (config.movement != null) {
            JsonObject movement = new JsonObject();
            movement.addProperty("enabled", config.movement);
            out.add("movement", movement);
        }
        return new Built(out, issues);
    }

    private static void payload(JsonObject into, String id, Words words, boolean serverChanged, Sealer sealer, Map<String, String> fps)
            throws CloudSeal.SealException {
        if (words.kind == Words.Kind.PLAIN && !CloudText.looksSensitive(words.text)) {
            into.addProperty("secret", false);
            into.addProperty("text", words.text);
            return;
        }
        if (words.kind == Words.Kind.SEALED || (words.revealed && !serverChanged && fps.containsKey(id))) {
            into.addProperty("secret", true);
            into.addProperty("keep", true);
            return;
        }
        into.addProperty("secret", true);
        into.addProperty("sealed", sealer.seal(words.text, id));
        into.addProperty("preview", CloudText.preview(words.text));
    }

    /**
     * A refusal of a {@code PATCH} about the change's contents, as issues the settings screen shows where they are;
     * null for any other problem. A content refusal (moderation) is {@code content/<category>}.
     */
    @Nullable
    public static List<Issue> fromRefusal(CloudProblem problem) {
        String code = problem.code;
        if (code == null) return null;
        String field = problem.field();
        String reason = problem.reason();
        List<Issue> out = new ArrayList<>();
        switch (code) {
            case "invalid_name":
                out.add(Issue.general("name", "format"));
                break;
            case "duplicate_account":
                out.add(Issue.general("name", "duplicate"));
                break;
            case "name_protected":
                out.add(Issue.general("server".equals(field) ? "server" : "name", "protected"));
                break;
            case "invalid_server":
                if ("port".equals(reason)) out.add(Issue.general("port", "port"));
                else out.add(Issue.general("server", "private".equals(reason) || "self".equals(reason) ? "private" : "format"));
                break;
            case "server_blocked":
                out.add(Issue.general("server", "blocked"));
                break;
            case "server_full":
                out.add(Issue.general("server", "full"));
                break;
            case "server_change_limit":
                out.add(Issue.general("server", "changeLimit"));
                break;
            case "invalid_version":
                out.add(Issue.general("version", "version"));
                break;
            case "invalid_config":
                out.add(Issue.fromPath(field, reason == null ? "range" : reason));
                break;
            case "invalid_command":
                if ("content".equals(reason)) {
                    String category = problem.string("category");
                    out.add(Issue.fromPath(field, "content/" + (category == null ? "other" : category)));
                } else if ("placeholder_part".equals(reason)) {
                    out.add(Issue.fromPath(field, "placeholder_part/" + problem.number("part", 0)));
                } else {
                    out.add(Issue.fromPath(field, reason == null ? "range" : reason));
                }
                break;
            case "invalid_pattern": {
                String p = problem.problem();
                out.add(Issue.fromPath(field, p == null ? "invalid" : p));
                break;
            }
            case "limit_rows":
                out.add(Issue.fromPath(field, "limit"));
                break;
            case "limit_movement": {
                // No movement slot: for the player ("player/<max>") or on the machine ("host").
                String scope = problem.string("scope");
                out.add(Issue.general("movement", "player".equals(scope) ? "player/" + problem.number("max", 1) : "host"));
                break;
            }
            default:
                return null;
        }
        return out;
    }

    /** The change of name, server, version and language for {@code PATCH} (only what differs from {@code account}). */
    public static JsonObject patch(CloudModel.Account account, @Nullable String name, @Nullable CloudServer server, @Nullable String version,
                                   @Nullable String lang, @Nullable JsonObject config) {
        JsonObject patch = new JsonObject();
        if (name != null && !name.equals(account.name)) patch.addProperty("name", name);
        if (server != null && !server.equals(account.server)) {
            JsonObject s = new JsonObject();
            s.addProperty("host", server.host);
            s.addProperty("port", server.port);
            patch.add("server", s);
        }
        if (version != null && !version.equals(account.version)) patch.addProperty("version", version);
        if (lang != null && !lang.equals(account.lang)) patch.addProperty("lang", lang);
        if (config != null) patch.add("config", config);
        return patch;
    }
}
