package gg.projects.mobile.accounts;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * M053 (SKIN1 on the phone): the friends service's Minecraft link and Projects skin as the phone reads them
 * ({@code GET /v1/mc}, a job, the {@code mc} socket frame; projects-studio services/social README "Minecraft link and
 * Projects skin"), and the panel's rules (the desktop's {@code features/projects-skin/rules.ts}): which part shows, what
 * a refusal or a job's result means. Plain Java, JVM-tested.
 */
public final class ProjectsSkinState {
    private ProjectsSkinState() {}

    /** A day: a link checked longer ago is checked again when the panel opens. */
    public static final long CHECK_EVERY_SECONDS = 24 * 60 * 60;

    public enum LinkState { LINKED, NONE, UNKNOWN }

    public static final class Link {
        public final LinkState state;
        @Nullable public final String name;
        public final long checkedAt;

        Link(LinkState state, @Nullable String name, long checkedAt) {
            this.state = state;
            this.name = name;
            this.checkedAt = checkedAt;
        }

        static Link read(@Nullable JsonObject o) {
            String s = str(o, "state");
            LinkState state = "linked".equals(s) ? LinkState.LINKED : "none".equals(s) ? LinkState.NONE : LinkState.UNKNOWN;
            return new Link(state, str(o, "name"), num(o, "checkedAt"));
        }
    }

    public static final class Skin {
        /** {@code png}, {@code premium} or {@code default}. */
        public final String kind;
        @Nullable public final String texture;
        public final boolean slim;
        @Nullable public final String premiumName;

        Skin(String kind, @Nullable String texture, boolean slim, @Nullable String premiumName) {
            this.kind = kind;
            this.texture = texture;
            this.slim = slim;
            this.premiumName = premiumName;
        }

        public boolean isDefault() {
            return "default".equals(kind);
        }

        @Nullable
        static Skin read(@Nullable JsonObject o) {
            if (o == null) return null;
            JsonObject premium = obj(o, "premium");
            return new Skin(str(o, "kind") == null ? "default" : str(o, "kind"), str(o, "texture"), "slim".equals(str(o, "variant")),
                    str(premium, "name"));
        }
    }

    public static final class Job {
        public final String id;
        /** {@code link}, {@code unlink}, {@code check} or {@code skin}. */
        public final String kind;
        /** {@code pending}, {@code working}, {@code queued}, {@code done}, {@code failed} or {@code cancelled}. */
        public final String state;
        @Nullable public final String name;
        @Nullable public final String resultCode;
        @Nullable public final String resultName;
        @Nullable public final Long resultUntil;

        Job(String id, String kind, String state, @Nullable String name, @Nullable String resultCode, @Nullable String resultName,
            @Nullable Long resultUntil) {
            this.id = id;
            this.kind = kind;
            this.state = state;
            this.name = name;
            this.resultCode = resultCode;
            this.resultName = resultName;
            this.resultUntil = resultUntil;
        }

        public boolean open() {
            return "pending".equals(state) || "working".equals(state) || "queued".equals(state);
        }

        public boolean queued() {
            return "queued".equals(state);
        }

        public boolean succeeded() {
            return "done".equals(state) && resultCode != null
                    && (resultCode.equals("linked") || resultCode.equals("unlinked") || resultCode.equals("applied"));
        }

        @Nullable
        public static Job read(@Nullable JsonObject o) {
            String id = str(o, "id");
            if (!validId(id)) return null;
            JsonObject result = obj(o, "result");
            Long until = result != null && result.has("until") && result.get("until").isJsonPrimitive() ? result.get("until").getAsLong() : null;
            return new Job(id, str(o, "kind") == null ? "" : str(o, "kind"), str(o, "state") == null ? "" : str(o, "state"),
                    str(o, "name"), str(result, "code"), str(result, "name"), until);
        }
    }

    /** The desktop's check of a job id before it is asked for ({@code j} and 22 base64url characters). */
    public static boolean validId(@Nullable String id) {
        return id != null && id.matches("j[A-Za-z0-9_-]{22}");
    }

    public static final class State {
        public final Set<String> features;
        public final boolean botHealthy;
        public final Link link;
        @Nullable public final Skin skin;
        public final List<Job> jobs;
        public final int skinsPerDay;
        public final int skinsLeft;
        public final long resetAt;
        /** A staff block of skin changes: {@link #blockedUntil} 0 for good. */
        public final boolean blocked;
        public final long blockedUntil;
        /** A new Discord account's wait. */
        public final long limitedUntil;

        State(Set<String> features, boolean botHealthy, Link link, @Nullable Skin skin, List<Job> jobs, int skinsPerDay, int skinsLeft,
              long resetAt, boolean blocked, long blockedUntil, long limitedUntil) {
            this.features = features;
            this.botHealthy = botHealthy;
            this.link = link;
            this.skin = skin;
            this.jobs = jobs;
            this.skinsPerDay = skinsPerDay;
            this.skinsLeft = skinsLeft;
            this.resetAt = resetAt;
            this.blocked = blocked;
            this.blockedUntil = blockedUntil;
            this.limitedUntil = limitedUntil;
        }

        public static State read(JsonObject o) {
            Set<String> features = new HashSet<>();
            JsonElement f = o.get("features");
            if (f != null && f.isJsonArray()) for (JsonElement e : f.getAsJsonArray()) if (e.isJsonPrimitive()) features.add(e.getAsString());
            JsonObject health = obj(o, "health");
            List<Job> jobs = new ArrayList<>();
            JsonElement j = o.get("jobs");
            if (j != null && j.isJsonArray()) {
                for (JsonElement e : j.getAsJsonArray()) {
                    Job job = e.isJsonObject() ? Job.read(e.getAsJsonObject()) : null;
                    if (job != null) jobs.add(job);
                }
            }
            JsonObject limits = obj(o, "limits");
            JsonObject blocked = obj(o, "blocked");
            JsonObject limited = obj(o, "limited");
            return new State(features, health == null || bool(health, "bot", true), Link.read(obj(o, "link")), Skin.read(obj(o, "skin")),
                    jobs, (int) num(limits, "skinsPerDay"), limits == null ? 1 : (int) num(limits, "skinsLeft"), num(limits, "resetAt"),
                    blocked != null, num(blocked, "until"), num(limited, "until"));
        }

        /** The {@code mc} frame: the link, the skin and the block as the service says now. */
        public State withFrame(JsonObject frame) {
            Link link = frame.has("link") ? Link.read(obj(frame, "link")) : this.link;
            Skin skin = frame.has("skin") ? Skin.read(obj(frame, "skin")) : this.skin;
            boolean hasBlocked = frame.has("blocked");
            JsonObject b = obj(frame, "blocked");
            List<Job> jobs = new ArrayList<>(this.jobs);
            Job job = Job.read(obj(frame, "job"));
            if (job != null) {
                for (int i = jobs.size() - 1; i >= 0; i--) if (jobs.get(i).id.equals(job.id)) jobs.remove(i);
            }
            return new State(features, botHealthy, link, skin, jobs, skinsPerDay, skinsLeft, resetAt,
                    hasBlocked ? b != null : blocked, hasBlocked ? num(b, "until") : blockedUntil, limitedUntil);
        }

        @Nullable
        public Job openJob(String kind) {
            for (Job j : jobs) if (j.open() && kind.equals(j.kind)) return j;
            return null;
        }

        public boolean skinsOn() {
            return features.contains("skins");
        }

        public boolean linkOn() {
            return features.contains("mcLink");
        }
    }

    // ---- the panel ----

    public enum LinkArea { CHECKING, CODE, LINKED, OTHER }

    public static LinkArea linkArea(State s, String account) {
        if (s.link.state == LinkState.UNKNOWN) return LinkArea.CHECKING;
        if (s.link.state == LinkState.NONE) return LinkArea.CODE;
        return s.link.name != null && s.link.name.equalsIgnoreCase(account) ? LinkArea.LINKED : LinkArea.OTHER;
    }

    /** The link is not known or was checked over a day ago: the panel asks once ({@code POST /v1/mc/link/check}). */
    public static boolean needsCheck(State s, long nowSeconds) {
        if (s.link.state == LinkState.UNKNOWN) return true;
        return s.link.state == LinkState.LINKED && s.link.checkedAt > 0 && nowSeconds - s.link.checkedAt > CHECK_EVERY_SECONDS;
    }

    /** Why a skin change cannot go now, or null ({@code limited}, {@code blocked}, no changes left, a change in progress). */
    public enum Hold { LIMITED, BLOCKED, QUOTA, PENDING }

    @Nullable
    public static Hold hold(State s, long nowSeconds) {
        if (s.blocked && (s.blockedUntil == 0 || s.blockedUntil > nowSeconds)) return Hold.BLOCKED;
        if (s.limitedUntil > nowSeconds) return Hold.LIMITED;
        if (s.openJob("skin") != null) return Hold.PENDING;
        if (s.skinsLeft <= 0) return Hold.QUOTA;
        return null;
    }

    /** What a refused route means (the desktop's view.rs mapping). */
    public enum Refusal {
        SESSION, OFF, LIMITED, BLOCKED, RESTRICTED, PENDING, NOT_LINKED, OTHER_ACCOUNT, ALREADY_LINKED, QUOTA, TRIES,
        INVALID_PIN, INVALID_SKIN, UNREACHABLE
    }

    public static Refusal refusal(int status, @Nullable String code, @Nullable String item, @Nullable String reason) {
        String c = code == null ? "" : code;
        if (status == 401) return Refusal.SESSION;
        if ((status == 503 && "off".equals(reason)) || (status == 404 && "not_found".equals(c))) return Refusal.OFF;
        switch (c) {
            case "account_limited": return Refusal.LIMITED;
            case "skin_blocked": return Refusal.BLOCKED;
            case "account_restricted": return Refusal.RESTRICTED;
            case "job_pending": return Refusal.PENDING;
            case "not_linked": return Refusal.NOT_LINKED;
            case "other_account": return Refusal.OTHER_ACCOUNT;
            case "already_linked": return Refusal.ALREADY_LINKED;
            case "invalid_pin": return Refusal.INVALID_PIN;
            case "invalid_skin":
            case "payload_too_large": return Refusal.INVALID_SKIN;
            case "rate_limited":
                if ("skin_changes".equals(item)) return Refusal.QUOTA;
                if ("link_tries".equals(item)) return Refusal.TRIES;
                return Refusal.UNREACHABLE;
            default:
                return status == 413 ? Refusal.INVALID_SKIN : Refusal.UNREACHABLE;
        }
    }

    /** Only the digits of what was typed or pasted, at most six. */
    public static String pinDigits(@Nullable CharSequence typed) {
        if (typed == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < typed.length() && out.length() < 6; i++) {
            char ch = typed.charAt(i);
            if (ch >= '0' && ch <= '9') out.append(ch);
        }
        return out.toString();
    }

    /** A player name Minecraft could have (3–16 of {@code [A-Za-z0-9_]}): only those are looked up at Mojang. */
    public static boolean premiumName(@Nullable String name) {
        return name != null && name.matches("[A-Za-z0-9_]{3,16}");
    }

    public static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    // ---- JSON ----

    @Nullable
    static String str(@Nullable JsonObject o, String key) {
        if (o == null) return null;
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    static long num(@Nullable JsonObject o, String key) {
        if (o == null) return 0;
        JsonElement e = o.get(key);
        try {
            return e != null && e.isJsonPrimitive() ? e.getAsLong() : 0;
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    static boolean bool(JsonObject o, String key, boolean fallback) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsBoolean() : fallback;
    }

    @Nullable
    static JsonObject obj(@Nullable JsonObject o, String key) {
        if (o == null) return null;
        JsonElement e = o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    static List<String> strings(@Nullable JsonArray a) {
        if (a == null) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (JsonElement e : a) if (e.isJsonPrimitive()) out.add(e.getAsString());
        return out;
    }
}
