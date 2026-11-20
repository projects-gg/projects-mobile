package gg.projects.mobile.cloud.auto;

import androidx.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import gg.projects.mobile.social.Json;

/**
 * The auto stream's frames (services/cloud-afk README "Auto on the live socket"): {@code autost} (what each mode does,
 * its rod or pickaxe and today's numbers) and {@code autores}'s error. Read leniently: an unknown state reads as
 * {@code off}, an unknown catch as junk, a missing number as nothing yet. Pure, so the tests read the service's frames.
 */
public final class AutoFrames {
    private AutoFrames() {}

    public static final String OFF = "off";
    public static final String ON = "on";
    public static final String PAUSED = "paused";
    public static final String WAITING = "waiting";
    /** The most items {@code today.items} names, and the most catches {@code today.last} keeps. */
    public static final int ITEMS_MAX = 12;
    public static final int LAST_MAX = 8;

    /** What a mode does now ({@code AutoState}). */
    public static class State {
        /** {@code off}, {@code on}, {@code paused} or {@code waiting}. */
        public final String state;
        /** Why it is off, paused or waiting (null with on). */
        @Nullable
        public final String reason;
        /** Unix seconds: when this state began (0: not said). */
        public final long since;

        State(@Nullable JsonObject o) {
            String s = Json.str(o, "state", OFF);
            state = ON.equals(s) || PAUSED.equals(s) || WAITING.equals(s) ? s : OFF;
            reason = ON.equals(state) ? null : Json.str(o, "reason");
            since = Math.max(0, Json.num(o, "since", 0));
        }

        public boolean on() {
            return ON.equals(state);
        }

        public boolean off() {
            return OFF.equals(state);
        }

        public boolean paused() {
            return PAUSED.equals(state);
        }

        public boolean waiting() {
            return WAITING.equals(state);
        }

        /** Paused by the player or by a move: only the player's "Devam Et" goes on (a menu or a deal ends by itself). */
        public boolean waitsForPlayer() {
            return paused() && ("player".equals(reason) || "moved".equals(reason));
        }

        /** The mode runs or holds its spot: "Duraklat" makes sense (on or waiting; a menu or a deal pauses on its own). */
        public boolean pausable() {
            return on() || waiting() || (paused() && !waitsForPlayer());
        }
    }

    /** A rod or a pickaxe: its namespaced id, the durability left and the most it has. */
    public static final class Tool {
        public final String item;
        public final int dur;
        public final int max;

        public Tool(String item, int dur, int max) {
            this.item = item;
            this.dur = dur;
            this.max = max;
        }

        @Nullable
        static Tool read(@Nullable JsonObject o) {
            String item = Json.str(o, "item");
            if (item == null || item.isEmpty()) return null;
            int max = (int) Math.max(0, Json.num(o, "max", 0));
            int dur = (int) Math.max(0, Json.num(o, "dur", 0));
            return new Tool(item, max > 0 ? Math.min(dur, max) : dur, max);
        }

        /** How much is left, 0 to 1 (1 for a tool without wear). */
        public float left() {
            return max <= 0 ? 1f : Math.max(0f, Math.min(1f, dur / (float) max));
        }
    }

    /** An item and how many of it today. */
    public static final class Count {
        public final String id;
        public final int n;

        public Count(String id, int n) {
            this.id = id;
            this.n = n;
        }

        static List<Count> list(@Nullable JsonObject o, String key) {
            List<Count> out = new ArrayList<>();
            for (JsonObject c : Json.objects(o, key)) {
                String id = Json.str(c, "id");
                if (id == null || id.isEmpty()) continue;
                out.add(new Count(id, (int) Math.max(0, Json.num(c, "n", 0))));
                if (out.size() == ITEMS_MAX) break;
            }
            return Collections.unmodifiableList(out);
        }
    }

    /** One catch of today's last ones: the item, the game's loot category, whether it is enchanted, when (Unix seconds). */
    public static final class Catch {
        public final String id;
        /** {@code fish}, {@code treasure} or {@code junk}. */
        public final String cat;
        public final boolean ench;
        public final long at;

        public Catch(String id, String cat, boolean ench, long at) {
            this.id = id;
            this.cat = cat;
            this.ench = ench;
            this.at = at;
        }

        public boolean treasure() {
            return "treasure".equals(cat);
        }

        static List<Catch> list(@Nullable JsonObject o) {
            List<Catch> out = new ArrayList<>();
            for (JsonObject c : Json.objects(o, "last")) {
                String id = Json.str(c, "id");
                if (id == null || id.isEmpty()) continue;
                String cat = Json.str(c, "cat", "junk");
                if (!"fish".equals(cat) && !"treasure".equals(cat)) cat = "junk";
                out.add(new Catch(id, cat, Json.bool(c, "ench", false), Math.max(0, Json.num(c, "at", 0))));
                if (out.size() == LAST_MAX) break;
            }
            return Collections.unmodifiableList(out);
        }
    }

    /** Fishing's {@code FishState}: since 00:00 Istanbul time, kept by the machine across restarts. */
    public static final class Fish extends State {
        /** With on: {@code cast}, {@code wait} or {@code reel}; else null. */
        @Nullable
        public final String doing;
        @Nullable
        public final Tool rod;
        public final int catches;
        public final int fish;
        public final int treasure;
        public final int junk;
        /** Catches per hour of fishing time today; null before there is one to say. */
        @Nullable
        public final Double perHour;
        public final List<Count> items;
        /** Newest first. */
        public final List<Catch> last;

        Fish(JsonObject o) {
            super(o);
            String d = Json.str(o, "doing");
            doing = on() && ("cast".equals(d) || "wait".equals(d) || "reel".equals(d)) ? d : null;
            rod = Tool.read(Json.obj(o, "rod"));
            JsonObject today = Json.obj(o, "today");
            catches = count(today, "catches");
            fish = count(today, "fish");
            treasure = count(today, "treasure");
            junk = count(today, "junk");
            perHour = rate(today, "perHour");
            items = Count.list(today, "items");
            last = Catch.list(today);
        }

        @Nullable
        public static Fish read(@Nullable JsonObject o) {
            return o == null ? null : new Fish(o);
        }
    }

    /** The block mining looks at. */
    public static final class Target {
        public final String id;
        public final int x;
        public final int y;
        public final int z;

        Target(String id, int x, int y, int z) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Nullable
        static Target read(@Nullable JsonObject o) {
            String id = Json.str(o, "id");
            if (id == null || id.isEmpty()) return null;
            return new Target(id, (int) Json.num(o, "x", 0), (int) Json.num(o, "y", 0), (int) Json.num(o, "z", 0));
        }
    }

    /** Mining's {@code MineState}. */
    public static final class Mine extends State {
        @Nullable
        public final Target target;
        @Nullable
        public final Tool tool;
        public final int blocks;
        /** Blocks a minute; null before there is one to say. */
        @Nullable
        public final Double perMinute;
        /** Blocks broken by kind, most first. */
        public final List<Count> items;

        Mine(JsonObject o) {
            super(o);
            target = Target.read(Json.obj(o, "target"));
            tool = Tool.read(Json.obj(o, "tool"));
            JsonObject today = Json.obj(o, "today");
            blocks = count(today, "blocks");
            perMinute = rate(today, "perMinute");
            items = Count.list(today, "items");
        }

        @Nullable
        public static Mine read(@Nullable JsonObject o) {
            return o == null ? null : new Mine(o);
        }
    }

    /** One {@code autost}: the modes it names (the first after a watch names every mode the account has; later ones what changed). */
    public static final class Update {
        @Nullable
        public final Fish fishing;
        @Nullable
        public final Mine mining;

        public Update(@Nullable Fish fishing, @Nullable Mine mining) {
            this.fishing = fishing;
            this.mining = mining;
        }

        public static Update read(@Nullable JsonObject message) {
            return new Update(Fish.read(Json.obj(message, "fishing")), Mine.read(Json.obj(message, "mining")));
        }
    }

    /** {@code autores}'s error: {@code code}, its {@code reason} and {@code retryAfter} (seconds). */
    public static final class Error {
        public final String code;
        @Nullable
        public final String reason;
        public final long retryAfter;

        public Error(String code, @Nullable String reason, long retryAfter) {
            this.code = code;
            this.reason = reason;
            this.retryAfter = Math.max(0, retryAfter);
        }

        public static Error read(@Nullable JsonObject o) {
            return new Error(Json.str(o, "code", "invalid"), Json.str(o, "reason"), Json.num(o, "retryAfter", 0));
        }

        public static Error of(String code) {
            return new Error(code, null, 0);
        }

        /** Nothing to say: the next frames show the truth. */
        public boolean redrawOnly() {
            return "invalid".equals(code);
        }
    }

    private static int count(@Nullable JsonObject o, String key) {
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, Json.num(o, key, 0)));
    }

    @Nullable
    private static Double rate(@Nullable JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return null;
        double d = e.getAsDouble();
        return Double.isNaN(d) || Double.isInfinite(d) || d < 0 ? null : d;
    }
}
