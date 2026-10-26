package gg.projects.mobile.cloud.console;

import androidx.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import gg.projects.mobile.cloud.inventory.InvText;
import gg.projects.mobile.social.Json;

/**
 * The status strip above the console ({@code bars}, services/cloud-afk README "Console kinds" → The status strip): the
 * action bar's text, the title and the boss bars the server keeps showing. They never reach the log; the strip shows the
 * latest state and hides a text when its time is up, unless a newer frame came.
 */
public final class Bars {
    public static final int MAX_BOSS = 4;

    public static final class Boss {
        public final String id;
        public final String text;
        /** 0–1. */
        public final float progress;
        /** pink, blue, red, green, yellow, purple or white. */
        public final String color;

        Boss(String id, String text, float progress, String color) {
            this.id = id;
            this.text = text;
            this.progress = progress;
            this.color = color;
        }
    }

    @Nullable
    public final String actionbar;
    public final long actionbarUntil;
    @Nullable
    public final String title;
    @Nullable
    public final String subtitle;
    public final long titleUntil;
    public final List<Boss> boss;

    public Bars(@Nullable String actionbar, long actionbarUntil, @Nullable String title, @Nullable String subtitle, long titleUntil,
                List<Boss> boss) {
        this.actionbar = actionbar;
        this.actionbarUntil = actionbarUntil;
        this.title = title;
        this.subtitle = subtitle;
        this.titleUntil = titleUntil;
        this.boss = Collections.unmodifiableList(boss);
    }

    public static Bars none() {
        return new Bars(null, 0, null, null, 0, Collections.<Boss>emptyList());
    }

    private static String clean(@Nullable String text) {
        String t = InvText.cleanText(text);
        return t.length() > 120 ? t.substring(0, 119) + "…" : t;
    }

    public static Bars read(@Nullable JsonObject o) {
        String actionbar = null;
        long actionbarUntil = 0;
        JsonObject ab = Json.obj(o, "actionbar");
        if (ab != null) {
            String text = clean(Json.str(ab, "text", ""));
            if (!text.isEmpty()) {
                actionbar = text;
                actionbarUntil = Json.num(ab, "untilMs", 0);
            }
        }
        String title = null;
        String subtitle = null;
        long titleUntil = 0;
        JsonObject t = Json.obj(o, "title");
        if (t != null) {
            String text = clean(Json.str(t, "text", ""));
            String sub = clean(Json.str(t, "subtitle", ""));
            if (!text.isEmpty() || !sub.isEmpty()) {
                title = text.isEmpty() ? null : text;
                subtitle = sub.isEmpty() ? null : sub;
                titleUntil = Json.num(t, "untilMs", 0);
            }
        }
        List<Boss> boss = new ArrayList<>();
        JsonElement bossEl = o == null ? null : o.get("boss");
        if (bossEl != null && bossEl.isJsonArray()) {
            for (JsonElement e : bossEl.getAsJsonArray()) {
                if (!e.isJsonObject()) continue;
                JsonObject b = e.getAsJsonObject();
                String text = clean(Json.str(b, "text", ""));
                double progress = b.has("progress") && b.get("progress").isJsonPrimitive() ? b.get("progress").getAsDouble() : 0;
                if (Double.isNaN(progress)) progress = 0;
                boss.add(new Boss(Json.str(b, "id", String.valueOf(boss.size())), text, (float) Math.max(0, Math.min(1, progress)),
                        Json.str(b, "color", "white")));
                if (boss.size() >= MAX_BOSS) break;
            }
        }
        return new Bars(actionbar, actionbarUntil, title, subtitle, titleUntil, boss);
    }

    /** The action bar's text while it shows ({@code nowMs} in Unix milliseconds). */
    @Nullable
    public String actionbarAt(long nowMs) {
        return actionbar != null && (actionbarUntil == 0 || nowMs < actionbarUntil) ? actionbar : null;
    }

    public boolean titleShows(long nowMs) {
        return (title != null || subtitle != null) && (titleUntil == 0 || nowMs < titleUntil);
    }

    /** Whether anything shows at {@code nowMs}. */
    public boolean empty(long nowMs) {
        return actionbarAt(nowMs) == null && !titleShows(nowMs) && boss.isEmpty();
    }

    /** When the strip next changes by itself (a text's time ends), or 0. */
    public long nextChange(long nowMs) {
        long next = 0;
        if (actionbarAt(nowMs) != null && actionbarUntil > nowMs) next = actionbarUntil;
        if (titleShows(nowMs) && titleUntil > nowMs && (next == 0 || titleUntil < next)) next = titleUntil;
        return next;
    }
}
