package gg.projects.mobile.social;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TEAM6 crew tags (team6.md §3–§5, §7; the desktop's badges.ts), pure rules of the screens: what a
 * tag's card offers whoever opens it (its bottom row and its menu), when "Katılma isteği gönder" shows
 * (never a greyed button: it is there or it is not), what wearing a tag does and says, how a refusal
 * reads and how long this phone remembers it (the Turkey day), the crews whose tag the player may wear,
 * and the "Hoş geldin" lines' "Selam ver". The screens word and draw them.
 */
public final class BadgeRules {
    private BadgeRules() {}

    /** Join requests a player may have waiting (TEAM5's three). */
    public static final int MAX_OWN_REQUESTS = 3;

    /** The card's bottom row. */
    public enum Row { OWN, MEMBER, INVITED, WAITING, ASK, NONE }

    /** What a refusal of a request from a tag was (the service's codes, read in the desktop's words). */
    public enum Refusal { CLOSED, TODAY, WAITING_FULL, FULL, BLOCKED, OWN_DAILY, OWN_WAITING, OWN_CREWS, MEMBER }

    /** One of the player's crews and its tag: may be put on, is worn, or may not be worn now. */
    public enum Wear { WEAR, WORN, HIDDEN, LOCKED }

    /** A change of the worn tag: put on, switched from another crew's, taken off. */
    public enum Outcome { WORE, SWITCHED, OFF }

    /** What the card needs to know about the player. */
    public static final class CardInput {
        /** The crew of the tag and whether it takes requests from its tag now. */
        public String crew = "";
        public boolean requests;
        /** The tag on the card is the player's own. */
        public boolean own;
        /** Its wearer is on the player's friend list (requests come only from the wearer's friends). */
        public boolean friend;
        public List<String> crews = Collections.emptyList();
        public int crewLimit;
        public boolean multi;
        /** Crews that invited the player. */
        public List<String> invites = Collections.emptyList();
        /** Crews the player's join requests still wait at. */
        public List<String> waiting = Collections.emptyList();
        /** The service takes requests from tags ({@code badgeRequests}). */
        public boolean requestsOn;
        /** This phone was refused a request to this crew today (Turkey time). */
        public boolean refusedToday;
    }

    /** The player may be in one more crew (TEAM4: several when the service has them). */
    public static boolean canAddCrew(int crews, int limit, boolean multi) {
        return multi ? crews < Math.max(1, limit) : crews == 0;
    }

    /** Whether "Katılma isteği gönder" shows (team6.md §4.6, "Düğme ne zaman görünür"). */
    public static boolean canAsk(CardInput in) {
        return in.requestsOn && in.requests && in.friend && !in.own && !in.crews.contains(in.crew)
                && !in.invites.contains(in.crew) && !in.waiting.contains(in.crew)
                && canAddCrew(in.crews.size(), in.crewLimit, in.multi) && in.waiting.size() < MAX_OWN_REQUESTS
                && !in.refusedToday;
    }

    /**
     * The card's bottom row: the wearer is the player ("Etiketi çıkar", "Ekibi aç"), the player is in the
     * crew ("Sen de bu ekiptesin", "Ekibi aç"), invited to it ("Kabul et"), waiting for its answer
     * ("Geri çek"), may ask ("Katılma isteği gönder"), or nothing at all.
     */
    public static Row cardRow(CardInput in) {
        if (in.own) return Row.OWN;
        if (in.crews.contains(in.crew)) return Row.MEMBER;
        if (in.invites.contains(in.crew)) return Row.INVITED;
        if (in.waiting.contains(in.crew)) return Row.WAITING;
        return canAsk(in) ? Row.ASK : Row.NONE;
    }

    /** The card's "⋯": the crew's whole menu for its members, "Ekibi rapor et" for everyone else. */
    public static boolean menuIsCrews(Row row) {
        return row == Row.OWN || row == Row.MEMBER;
    }

    /** A refusal of "Katılma isteği gönder" by the service's code; null when the code is not a refusal of it. */
    @Nullable
    public static Refusal refusalOf(@Nullable String code) {
        if (code == null) return null;
        switch (code) {
            case "badge_request_closed": return Refusal.CLOSED;
            case "limit_badge_requests": return Refusal.TODAY;
            case "limit_team_requests": return Refusal.WAITING_FULL;
            case "limit_team_members": return Refusal.FULL;
            case "blocked_member": return Refusal.BLOCKED;
            case "rate_limited": return Refusal.OWN_DAILY;
            case "limit_outgoing_team_requests": return Refusal.OWN_WAITING;
            case "limit_crews": return Refusal.OWN_CREWS;
            case "already_member": return Refusal.MEMBER;
            default: return null;
        }
    }

    /** The end of today in Turkey (UTC+3 all year), in Unix seconds: a refusal lasts until then. */
    public static long turkeyDayEnd(long nowMs) {
        long day = 86_400_000L;
        long offset = 3 * 3_600_000L;
        return (((nowMs + offset) / day + 1) * day) / 1000 - offset / 1000;
    }

    /** Refused crews and until when (Unix seconds), as one string for the phone's preferences. */
    public static Map<String, Long> readMemory(@Nullable String stored) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (stored == null || stored.isEmpty()) return out;
        for (String entry : stored.split(",")) {
            int at = entry.indexOf('=');
            if (at <= 0) continue;
            try {
                String crew = entry.substring(0, at);
                if (SocialModel.isCrewId(crew)) out.put(crew, Long.parseLong(entry.substring(at + 1)));
            } catch (NumberFormatException ignored) {
                // A broken entry is forgotten.
            }
        }
        return out;
    }

    public static String writeMemory(Map<String, Long> memory) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Long> e : memory.entrySet()) {
            if (out.length() > 0) out.append(',');
            out.append(e.getKey()).append('=').append(e.getValue());
        }
        return out.toString();
    }

    public static boolean refusedNow(Map<String, Long> memory, String crew, long nowMs) {
        Long until = memory.get(crew);
        return until != null && until * 1000 > nowMs;
    }

    /** The memory with one more refused crew (those whose time passed are forgotten; at most 50). */
    public static Map<String, Long> withRefusal(Map<String, Long> memory, String crew, long until, long nowMs) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : memory.entrySet()) {
            if (e.getValue() * 1000 > nowMs && !e.getKey().equals(crew)) out.put(e.getKey(), e.getValue());
        }
        out.put(crew, until);
        while (out.size() > 50) out.remove(out.keySet().iterator().next());
        return out;
    }

    /** Whether {@code team}'s tag may be put on, is worn, or why not ({@code worn}: the crew of the worn tag). */
    public static Wear wearState(SocialModel.Team team, @Nullable String worn, long nowSeconds) {
        if (team.hidden) return Wear.HIDDEN;
        if (team.textLocked(nowSeconds)) return Wear.LOCKED;
        return team.id.equals(worn) ? Wear.WORN : Wear.WEAR;
    }

    @Nullable
    public static Outcome wearOutcome(@Nullable String before, @Nullable String after) {
        if (before == null ? after == null : before.equals(after)) return null;
        if (after == null) return Outcome.OFF;
        return before == null ? Outcome.WORE : Outcome.SWITCHED;
    }

    /** The crews whose tag the player may pick now, in their order (hidden and locked ones left out). */
    public static List<SocialModel.Crew> wearable(List<SocialModel.Crew> crews, long nowSeconds) {
        List<SocialModel.Crew> out = new ArrayList<>();
        for (SocialModel.Crew c : crews) {
            Wear w = wearState(c.team, null, nowSeconds);
            if (w != Wear.HIDDEN && w != Wear.LOCKED) out.add(c);
        }
        return out;
    }

    /** "Selam ver" beside a join line: not on the player's own, until its ten minutes end. */
    public static boolean canGreet(SocialState.CrewJoin join, @Nullable String me, long nowSeconds) {
        return me != null && !join.userId.equals(me) && join.greetable(nowSeconds);
    }

    /** When the next "Selam ver" of these goes by itself (Unix seconds), 0 when none waits (one timer). */
    public static long nextGreetEnd(List<SocialState.CrewJoin> joins, @Nullable String me, long nowSeconds) {
        long next = 0;
        for (SocialState.CrewJoin j : joins) {
            if (!canGreet(j, me, nowSeconds)) continue;
            long end = j.at + SocialState.CrewJoin.GREET_SECONDS;
            if (next == 0 || end < next) next = end;
        }
        return next;
    }

    /** The tag shows only while the service offers tags (an older one, or Studio's switch off, shows none). */
    @Nullable
    public static SocialModel.Badge shown(SocialGate gate, @Nullable SocialModel.Badge badge) {
        return gate.badges ? badge : null;
    }
}
