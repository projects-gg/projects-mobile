package gg.projects.mobile.social;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * MOB-PARITY "Ekip bul" (CREW-DISCOVER; the service's README "Crew discovery", the desktop's
 * {@code features/social/discover/model.ts}): the listing as the service sends it, searching and filtering it on the
 * phone, when the kept copy is asked for again, a card's one action, and the leader's "Keşif" block (its status, the
 * text as the service counts it, what may be sent). Pure Java.
 */
public final class DiscoverModel {
    private DiscoverModel() {}

    /** The service's tags in its order; a name the phone does not know is dropped. */
    public static final List<String> TAGS = Collections.unmodifiableList(Arrays.asList(
            "survival", "skyblock", "smp", "hg", "pvp", "build", "events", "chat", "voice", "newcomers"));
    public static final int TEXT_MIN = 10;
    public static final int TEXT_MAX = 160;
    public static final int MAX_TAGS = 3;
    /** A kept listing is asked again (with its ETag: a quick "unchanged") once it is this old. */
    public static final long REVALIDATE_MS = 15 * 60_000L;
    /** CREW-CAP: a crew's cap when the listing does not say it (a service before crew caps held 30). */
    public static final int FULL = 30;

    /** One listed crew. */
    public static final class Crew {
        public final String id, name, tag, emblem, color, frame, motto, text;
        public final List<String> tags;
        /** {@code open} or {@code approval}. */
        public final String join;
        public final int members;
        /** "Bu hafta aktif". */
        public final boolean active;
        /** CREW-CAP: the crew's cap at the listing's wave (at most a day old); 0 when the listing does not say it. */
        public final int maxMembers;

        Crew(String id, String name, String tag, String emblem, String color, String frame, String motto, String text,
             List<String> tags, String join, int members, boolean active) {
            this(id, name, tag, emblem, color, frame, motto, text, tags, join, members, active, 0);
        }

        Crew(String id, String name, String tag, String emblem, String color, String frame, String motto, String text,
             List<String> tags, String join, int members, boolean active, int maxMembers) {
            this.id = id;
            this.name = name;
            this.tag = tag;
            this.emblem = emblem;
            this.color = color;
            this.frame = frame;
            this.motto = motto;
            this.text = text;
            this.tags = Collections.unmodifiableList(tags);
            this.join = join;
            this.members = members;
            this.active = active;
            this.maxMembers = Math.max(0, maxMembers);
        }

        /** The cap a join meets: the listing's, else the old 30 (the launcher's {@code CAP_BEFORE}). */
        public int cap() {
            return maxMembers > 0 ? maxMembers : FULL;
        }

        public boolean open() {
            return "open".equals(join);
        }

        /** The crew's look and name as the app's crew cards take them (report, crew mark). */
        public SocialModel.TeamSummary summary() {
            return new SocialModel.TeamSummary(id, name, tag, emblem, color, frame, motto.isEmpty() ? null : motto, members,
                    open() ? SocialModel.TeamSummary.JOIN_OPEN : SocialModel.TeamSummary.JOIN_APPROVAL, maxMembers);
        }

        /** What a card shows: a change binds it again. */
        public String key() {
            return id + "|" + name + "|" + tag + "|" + emblem + color + frame + "|" + motto + "|" + text + "|" + tags + "|" + join
                    + "|" + members + "|" + active + "|" + maxMembers;
        }
    }

    /** The listing in the day's order. */
    public static final class Listing {
        public final long ver;
        /** Unix seconds: when the wave built it, and the next wave (the copy is good until then). */
        public final long at, next;
        public final List<Crew> crews;

        Listing(long ver, long at, long next, List<Crew> crews) {
            this.ver = ver;
            this.at = at;
            this.next = next;
            this.crews = Collections.unmodifiableList(crews);
        }

        /** Without one crew (a join answered that it left the listing). */
        public Listing without(String crewId) {
            List<Crew> left = new ArrayList<>();
            for (Crew c : crews) if (!c.id.equals(crewId)) left.add(c);
            return new Listing(ver, at, next, left);
        }
    }

    /** Format 1; null for anything else (an unknown format shows as a failure, never half). */
    @Nullable
    public static Listing readListing(@Nullable JsonObject o) {
        if (o == null || Json.num(o, "v", 0) != 1) return null;
        List<Crew> crews = new ArrayList<>();
        for (JsonObject c : Json.objects(o, "crews")) {
            String id = Json.str(c, "id");
            String name = Json.str(c, "name");
            if (id == null || name == null || !id.matches("[A-Za-z0-9_-]{1,64}")) continue;
            List<String> tags = new ArrayList<>();
            for (String tag : Json.strings(c, "tags")) if (TAGS.contains(tag) && !tags.contains(tag)) tags.add(tag);
            String join = "open".equals(Json.str(c, "join")) ? "open" : "approval";
            crews.add(new Crew(id, name, Json.str(c, "tag", ""), Json.str(c, "emblem", "shield"), CrewLook.color(Json.str(c, "color")),
                    CrewLook.frame(Json.str(c, "frame")), Json.str(c, "motto", "").trim(), Json.str(c, "text", ""), tags, join,
                    (int) Math.max(0, Json.num(c, "members", 0)), Json.bool(c, "active", false), SocialModel.maxMembersOf(c)));
        }
        return new Listing(Json.num(o, "ver", 0), Json.num(o, "at", 0), Json.num(o, "next", 0), crews);
    }

    // ---- searching and filtering on the phone ----

    public enum Size { ANY, SMALL, MID, BIG }

    /** Small 2-9, mid 10-19, big 20 and more. */
    public static Size sizeOf(int members) {
        return members >= 20 ? Size.BIG : members >= 10 ? Size.MID : Size.SMALL;
    }

    public static final class Filter {
        public final String query;
        public final List<String> tags;
        /** "Hemen katılınabilenler". */
        public final boolean open;
        /** "Bu hafta aktif olanlar". */
        public final boolean active;
        public final Size size;

        public static final Filter NONE = new Filter("", Collections.<String>emptyList(), false, false, Size.ANY);

        public Filter(String query, List<String> tags, boolean open, boolean active, Size size) {
            this.query = query == null ? "" : query;
            this.tags = Collections.unmodifiableList(new ArrayList<>(tags));
            this.open = open;
            this.active = active;
            this.size = size;
        }

        public Filter withQuery(String q) {
            return new Filter(q, tags, open, active, size);
        }

        public Filter withTag(String tag, boolean on) {
            List<String> next = new ArrayList<>(tags);
            next.remove(tag);
            if (on) next.add(tag);
            return new Filter(query, next, open, active, size);
        }

        public Filter withOpen(boolean on) {
            return new Filter(query, tags, on, active, size);
        }

        public Filter withActive(boolean on) {
            return new Filter(query, tags, open, on, size);
        }

        public Filter withSize(Size s) {
            return new Filter(query, tags, open, active, s);
        }

        /** How many of the menu's filters are on ("Filtreler"'s count; tags show on their own). */
        public int menuCount() {
            return (open ? 1 : 0) + (active ? 1 : 0) + (size != Size.ANY ? 1 : 0);
        }

        public boolean filtering() {
            return !query.trim().isEmpty() || !tags.isEmpty() || menuCount() > 0;
        }

        public String key() {
            return query + "|" + tags + "|" + open + active + size;
        }
    }

    public interface TagWords {
        String words(String tag);
    }

    /** The crews the filter keeps, in the listing's own order (the day's, the same for everyone). */
    public static List<Crew> filter(List<Crew> crews, Filter f, TagWords words) {
        String q = fold(f.query.trim());
        List<Crew> out = new ArrayList<>();
        for (Crew c : crews) {
            if (!q.isEmpty() && !matches(c, q, words)) continue;
            if (!c.tags.containsAll(f.tags)) continue;
            if (f.open && !c.open()) continue;
            if (f.active && !c.active) continue;
            if (f.size != Size.ANY && sizeOf(c.members) != f.size) continue;
            out.add(c);
        }
        return out;
    }

    private static boolean matches(Crew c, String q, TagWords words) {
        if (fold(c.name).contains(q) || fold(c.tag).contains(q) || fold(c.text).contains(q)) return true;
        for (String tag : c.tags) if (fold(words.words(tag)).contains(q)) return true;
        return false;
    }

    /** Case and marks folded the Turkish way (İ/ı and I/i alike, "ş" finds "s"), as the desktop's search. */
    static String fold(String text) {
        String lower = text.replace('İ', 'i').replace('I', 'i').replace('ı', 'i').toLowerCase(Locale.ROOT);
        String plain = Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return plain.replace('ı', 'i');
    }

    // ---- the kept copy ----

    public enum Freshness { FETCH, REVALIDATE, KEEP }

    /** {@code FETCH} without a copy or once its next wave passed; {@code REVALIDATE} after a quarter of an hour. */
    public static Freshness freshness(@Nullable Listing kept, long fetchedAtMs, long nowMs) {
        if (kept == null) return Freshness.FETCH;
        if (nowMs >= kept.next * 1000L) return Freshness.FETCH;
        return nowMs - fetchedAtMs >= REVALIDATE_MS ? Freshness.REVALIDATE : Freshness.KEEP;
    }

    // ---- a card's one action ----

    public enum Slot { JOIN, ASK, MEMBER, WAITING, FULL, LIMIT }

    public static Slot slot(Crew crew, boolean member, boolean waiting, boolean canAdd) {
        if (member) return Slot.MEMBER;
        if (waiting) return Slot.WAITING;
        if (crew.members >= crew.cap()) return Slot.FULL;
        if (!canAdd) return Slot.LIMIT;
        return crew.open() ? Slot.JOIN : Slot.ASK;
    }

    // ---- the leader's block ----

    public static final class Text {
        public final String text;
        public final List<String> tags;

        Text(String text, List<String> tags) {
            this.text = text;
            this.tags = Collections.unmodifiableList(tags);
        }

        @Nullable
        static Text read(@Nullable JsonObject o) {
            if (o == null) return null;
            List<String> tags = new ArrayList<>();
            for (String tag : Json.strings(o, "tags")) if (TAGS.contains(tag) && !tags.contains(tag)) tags.add(tag);
            return new Text(Json.str(o, "text", ""), tags);
        }
    }

    public static final class Check {
        public final int need, have;

        Check(int need, int have) {
            this.need = need;
            this.have = have;
        }

        static Check read(@Nullable JsonObject o) {
            return new Check((int) Json.num(o, "need", 0), (int) Json.num(o, "have", 0));
        }

        public boolean holds() {
            return have >= need;
        }
    }

    public static final class Status {
        /** {@code off}, {@code waiting}, {@code listed}, {@code refused}, {@code closed}, {@code held}. */
        public final String state;
        @Nullable
        public final Text listed, draft;
        public final boolean changeWaits;
        public final long waveAt;
        @Nullable
        public final Long retryAt;
        public final Check members, crewDays, leaderDays;
        public final boolean visible;
        public final int editsLeft;
        public final long editsReset;

        Status(String state, @Nullable Text listed, @Nullable Text draft, boolean changeWaits, long waveAt, @Nullable Long retryAt,
               Check members, Check crewDays, Check leaderDays, boolean visible, int editsLeft, long editsReset) {
            this.state = state;
            this.listed = listed;
            this.draft = draft;
            this.changeWaits = changeWaits;
            this.waveAt = waveAt;
            this.retryAt = retryAt;
            this.members = members;
            this.crewDays = crewDays;
            this.leaderDays = leaderDays;
            this.visible = visible;
            this.editsLeft = editsLeft;
            this.editsReset = editsReset;
        }

        public boolean on() {
            return "waiting".equals(state) || "listed".equals(state) || "held".equals(state);
        }

        public boolean checksHold() {
            return visible && members.holds() && crewDays.holds() && leaderDays.holds();
        }
    }

    /** {@code {discover: status}} (or the status itself); null when unreadable. */
    @Nullable
    public static Status readStatus(@Nullable JsonObject answer) {
        JsonObject o = Json.obj(answer, "discover");
        if (o == null) o = answer;
        String state = Json.str(o, "state");
        if (state == null) return null;
        JsonObject checks = Json.obj(o, "checks");
        return new Status(state, Text.read(Json.obj(o, "listed")), Text.read(Json.obj(o, "draft")), Json.bool(o, "changeWaits", false),
                Json.num(o, "waveAt", 0), Json.numOrNull(o, "retryAt"), Check.read(Json.obj(checks, "members")),
                Check.read(Json.obj(checks, "crewDays")), Check.read(Json.obj(checks, "leaderDays")), Json.bool(checks, "visible", true),
                (int) Json.num(o, "editsLeft", 0), Json.num(o, "editsReset", 0));
    }

    /** The leader's text as the service keeps it: one paragraph, spaces collapsed, trimmed. */
    public static String cleanText(@Nullable String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    /** Its length as the service counts it (characters, not UTF-16 units). */
    public static int textLength(@Nullable String text) {
        String clean = cleanText(text);
        return clean.codePointCount(0, clean.length());
    }

    /** 10-160 characters with at least 5 letters. */
    public static boolean textReady(@Nullable String text) {
        String clean = cleanText(text);
        int length = clean.codePointCount(0, clean.length());
        if (length < TEXT_MIN || length > TEXT_MAX) return false;
        int letters = 0;
        for (int i = 0; i < clean.length(); ) {
            int cp = clean.codePointAt(i);
            if (Character.isLetter(cp)) letters++;
            i += Character.charCount(cp);
        }
        return letters >= 5;
    }

    /** "Keşfe gönder" may go: a ready text, at most 3 tags, edits left, and something not already there. */
    public static boolean maySend(Status status, String text, List<String> tags) {
        if (!textReady(text) || tags.size() > MAX_TAGS || status.editsLeft <= 0) return false;
        Text now = status.changeWaits || "waiting".equals(status.state) ? status.draft : status.listed;
        if (now == null) return true;
        return !now.text.equals(cleanText(text)) || now.tags.size() != tags.size() || !now.tags.containsAll(tags);
    }

    /** {@code PUT}'s body. */
    public static JsonObject body(String text, List<String> tags) {
        JsonObject o = new JsonObject();
        o.addProperty("text", cleanText(text));
        JsonArray list = new JsonArray();
        for (String tag : TAGS) if (tags.contains(tag)) list.add(tag);
        o.add("tags", list);
        return o;
    }

    public enum Day { TODAY, TOMORROW, LATER }

    /** The wave's day on the phone's clock (Turkey's for a Turkish phone). */
    public static Day waveDay(long waveAtSeconds, long nowMs, TimeZone zone) {
        long at = waveAtSeconds * 1000L;
        long days = localDay(at, zone) - localDay(nowMs, zone);
        return days <= 0 ? Day.TODAY : days == 1 ? Day.TOMORROW : Day.LATER;
    }

    private static long localDay(long ms, TimeZone zone) {
        long local = ms + zone.getOffset(ms);
        return local >= 0 ? local / 86_400_000L : (local - 86_399_999L) / 86_400_000L;
    }

    /** Whole days until {@code untilSeconds}, at least one ("1 gün sonra"). */
    public static int daysUntil(long untilSeconds, long nowMs) {
        long ms = untilSeconds * 1000L - nowMs;
        return (int) Math.max(1, (ms + 86_399_999L) / 86_400_000L);
    }
}
