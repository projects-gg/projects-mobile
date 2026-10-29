package gg.projects.mobile.social;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashSet;
import java.util.Set;

/**
 * SOC9 (TEAM4-B, soc9.md §3): the light friend list ({@code GET /v1/friends?crews=lite}) names each crew in short
 * ({@code {id, name, tag, emblem, color, frame, hidden, members: <count>, rev}}); the members, roles, permissions, code,
 * link and texts come from the crew's detail ({@code GET /v1/teams/:id}, {@code {team}}), kept on the phone
 * ({@link CrewCache}). {@link #resolve} puts the full crews back into the list's answer before it is read, so the rest
 * of the app sees the list as it always was. A copy is used as it is when its {@code rev} is the list's and it was read
 * today (Turkey's calendar day, the service's); otherwise the crew is read again with the copy's ETag
 * ({@code If-None-Match}: a 304 keeps the copy). A crew the detail no longer gives the player ({@code team_not_found})
 * leaves the list; one that cannot be read keeps its older copy (the list's look over it), or fails the read when there
 * is none. An older service sends full crews (its {@code members} is a list): nothing to do. A {@code team} frame is
 * always the newest copy ({@link #onTeamFrame}). Pure, with tests.
 */
public final class CrewDetails {
    private CrewDetails() {}

    /** The crew's detail as the service answered. */
    public static final class Detail {
        public enum Status { OK, NOT_MODIFIED, GONE }

        public final Status status;
        @Nullable
        public final String etag;
        @Nullable
        public final JsonObject team;

        public Detail(Status status, @Nullable String etag, @Nullable JsonObject team) {
            this.status = status;
            this.etag = etag;
            this.team = team;
        }
    }

    /** Reads one crew's detail ({@code etag}: the copy's, for {@code If-None-Match}; null: none). */
    public interface Fetcher {
        Detail fetch(String crewId, @Nullable String etag) throws SocialProblem;
    }

    /** The list's look of a crew, laid over an older copy when it could not be read again. */
    private static final String[] LOOK = {"name", "tag", "emblem", "color", "frame", "hidden"};

    /** Turkey's calendar day of {@code seconds} (Unix; UTC+3 all year): the day in the detail's ETag. */
    public static long turkeyDay(long seconds) {
        return Math.max(0L, seconds + 3 * 3600L) / 86_400L;
    }

    /** A crew's {@code team} in the light list: its {@code members} is a count, not the list. */
    static boolean isLite(@Nullable JsonObject team) {
        return team != null && team.has("members") && !team.get("members").isJsonArray();
    }

    /**
     * Puts each light crew's full {@code team} into {@code friends} (the list's JSON, changed in place), from the copy or
     * the detail, and keeps the cache to the crews listed. Returns how many details were read.
     */
    public static int resolve(@Nullable JsonObject friends, CrewCache cache, Fetcher fetcher, long today) throws SocialProblem {
        if (friends == null || !friends.has("crews") || !friends.get("crews").isJsonArray()) return 0;
        JsonArray crews = friends.getAsJsonArray("crews");
        JsonArray kept = new JsonArray();
        Set<String> listed = new HashSet<>();
        boolean light = false;
        int read = 0;
        for (JsonElement element : crews) {
            if (!element.isJsonObject()) continue;
            JsonObject crew = element.getAsJsonObject();
            JsonObject team = Json.obj(crew, "team");
            String id = Json.str(team, "id");
            if (!isLite(team) || id == null || !SocialModel.isCrewId(id)) {
                kept.add(crew);
                continue;
            }
            light = true;
            long rev = Json.num(team, "rev", -1);
            CrewCache.Entry copy = cache.load(id);
            if (copy != null && rev >= 0 && copy.rev == rev && copy.day == today) {
                crew.add("team", copy.team.deepCopy());
                kept.add(crew);
                listed.add(id);
                continue;
            }
            Detail detail;
            try {
                detail = fetcher.fetch(id, copy == null ? null : copy.etag);
                read++;
            } catch (SocialProblem problem) {
                // An ended or restricted session ends the read whatever the copy (the list's own answer says the same).
                if (copy == null || problem.kind == SocialProblem.Kind.SIGNED_OUT || problem.restricted()) throw problem;
                // Not read now: the older copy, with the list's look over it; read again next time.
                crew.add("team", withLook(copy.team.deepCopy(), team));
                kept.add(crew);
                listed.add(id);
                continue;
            }
            if (detail.status == Detail.Status.GONE) {
                cache.remove(id);
                continue;
            }
            JsonObject full;
            if (detail.status == Detail.Status.NOT_MODIFIED && copy != null) {
                full = copy.team;
                cache.store(id, detail.etag != null ? detail.etag : copy.etag, copy.rev, today, copy.team);
            } else if (detail.status == Detail.Status.OK && detail.team != null && id.equals(Json.str(detail.team, "id"))) {
                full = detail.team;
                cache.store(id, detail.etag, Json.num(full, "rev", rev), today, full);
            } else {
                throw SocialProblem.unavailable("crew detail");
            }
            crew.add("team", full.deepCopy());
            kept.add(crew);
            listed.add(id);
        }
        if (light) {
            friends.add("crews", kept);
            cache.keepOnly(listed);
        }
        return read;
    }

    /**
     * A {@code team} frame ({@code {"t":"team","team":<full>|null,"crew":<id>}}): the newest copy of that crew (kept
     * with no ETag, so the next read asks without one), or the crew gone from the cache when it is null.
     */
    public static void onTeamFrame(JsonObject message, CrewCache cache, long today) {
        JsonObject team = Json.obj(message, "team");
        if (team == null) {
            String crew = Json.str(message, "crew");
            if (crew != null) cache.remove(crew);
            return;
        }
        String id = Json.str(team, "id");
        if (id == null || isLite(team) || !Json.has(team, "rev")) return;
        cache.store(id, null, Json.num(team, "rev", -1), today, team);
    }

    private static JsonObject withLook(JsonObject full, JsonObject summary) {
        for (String key : LOOK) {
            if (summary.has(key)) full.add(key, summary.get(key).deepCopy());
        }
        return full;
    }
}
