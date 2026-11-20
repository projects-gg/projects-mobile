package gg.projects.mobile.cloud.auto;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import gg.projects.mobile.cloud.CloudText;
import gg.projects.mobile.social.Json;

/**
 * The account's own settings of auto-fishing and auto-mining ({@code config.fishing}, {@code config.mining};
 * services/cloud-afk README "Auto settings", {@code feat/settings-v1.json}): their parts with the contract's defaults,
 * read leniently from the account view, checked before a save as the service checks them, and written whole (a
 * {@code PATCH} that carries a mode carries all of it, and the service keeps a mode the {@code PATCH} leaves out).
 * Pure, so the tests hold it to the contract.
 */
public final class AutoSettings {
    private AutoSettings() {}

    public static final String FISHING = "fishing";
    public static final String MINING = "mining";
    /** The durability left at which a rod or a pickaxe is put away: 1 to 100, 5 by default. */
    public static final int KEEP_MIN = 1;
    public static final int KEEP_MAX = 100;
    public static final int KEEP_DEFAULT = 5;
    /** Mining's idle moves come once in a random time within these seconds: 20 <= min <= max <= 3600, 60 to 180 by default. */
    public static final int EVERY_MIN = 20;
    public static final int EVERY_MAX = 3600;
    public static final int EVERY_DEFAULT_MIN = 60;
    public static final int EVERY_DEFAULT_MAX = 180;
    /** What happens when the inventory has no room: wait, keep on (what does not fit falls) or send a command, then wait. */
    public static final String FULL_WAIT = "wait";
    public static final String FULL_KEEP = "keep";
    public static final String FULL_COMMAND = "command";

    /** {@code fixed.miningBlocks}: the only blocks mining breaks (stone kinds and ores), in the contract's order. */
    public static final List<String> MINING_BLOCKS = Collections.unmodifiableList(Arrays.asList(
            "minecraft:cobblestone",
            "minecraft:stone",
            "minecraft:cobbled_deepslate",
            "minecraft:deepslate",
            "minecraft:andesite",
            "minecraft:diorite",
            "minecraft:granite",
            "minecraft:tuff",
            "minecraft:basalt",
            "minecraft:smooth_basalt",
            "minecraft:blackstone",
            "minecraft:netherrack",
            "minecraft:end_stone",
            "minecraft:obsidian",
            "minecraft:coal_ore",
            "minecraft:deepslate_coal_ore",
            "minecraft:copper_ore",
            "minecraft:deepslate_copper_ore",
            "minecraft:iron_ore",
            "minecraft:deepslate_iron_ore",
            "minecraft:gold_ore",
            "minecraft:deepslate_gold_ore",
            "minecraft:redstone_ore",
            "minecraft:deepslate_redstone_ore",
            "minecraft:lapis_ore",
            "minecraft:deepslate_lapis_ore",
            "minecraft:diamond_ore",
            "minecraft:deepslate_diamond_ore",
            "minecraft:emerald_ore",
            "minecraft:deepslate_emerald_ore",
            "minecraft:nether_gold_ore",
            "minecraft:nether_quartz_ore",
            "minecraft:ancient_debris"));

    /**
     * A problem of a mode's settings: {@code field} is {@code <mode>.keep}, {@code <mode>.command}, {@code mining.blocks} or
     * {@code mining.every}; {@code problem} the service's reason ({@code range}, or the text rule's for a command).
     */
    public static final class Problem {
        public final String field;
        public final String problem;

        Problem(String field, String problem) {
            this.field = field;
            this.problem = problem;
        }

        @Override
        public String toString() {
            return field + ":" + problem;
        }
    }

    /** A rod's or a pickaxe's rule ({@code rod}, {@code tool}): {@code change} is the contract's {@code switch}. */
    public static final class Tool {
        /** Take another rod or pickaxe from the inventory (the hotbar first) when the one in hand is at {@link #keep}. */
        public boolean change;
        public int keep;

        public Tool(boolean change, int keep) {
            this.change = change;
            this.keep = keep;
        }

        static Tool read(@Nullable JsonObject o) {
            if (o == null) return new Tool(true, KEEP_DEFAULT);
            long keep = Json.num(o, "keep", KEEP_DEFAULT);
            return new Tool(Json.bool(o, "switch", true), (int) Math.max(KEEP_MIN, Math.min(KEEP_MAX, keep)));
        }

        JsonObject json() {
            JsonObject o = new JsonObject();
            o.addProperty("switch", change);
            o.addProperty("keep", keep);
            return o;
        }

        Tool copy() {
            return new Tool(change, keep);
        }

        boolean same(Tool o) {
            return change == o.change && keep == o.keep;
        }
    }

    /** {@code full}: {@link #what} is the contract's {@code do}; {@link #command} goes only with {@code command}. */
    public static final class Full {
        public String what;
        public String command;
        /**
         * The view's mark: moderation paused the command (the mode waits on a full inventory meanwhile). Read only: the
         * service accepts it written back and never stores it; a changed command or choice is checked again.
         */
        public final boolean paused;

        public Full(String what, String command) {
            this(what, command, false);
        }

        Full(String what, String command, boolean paused) {
            this.what = what;
            this.command = command;
            this.paused = paused;
        }

        static Full read(@Nullable JsonObject o) {
            String what = Json.str(o, "do", FULL_WAIT);
            if (!FULL_KEEP.equals(what) && !FULL_COMMAND.equals(what)) what = FULL_WAIT;
            // Moderation's mark is an object ({case, category}), as a paused row's; a plain true reads the same.
            boolean paused = Json.obj(o, "paused") != null || Json.bool(o, "paused", false);
            return new Full(what, Json.str(o, "command", ""), paused);
        }

        JsonObject json() {
            JsonObject o = new JsonObject();
            o.addProperty("do", what);
            // The service refuses a command beside "wait" and "keep" (it would never be sent).
            if (FULL_COMMAND.equals(what)) o.addProperty("command", command);
            return o;
        }

        Full copy() {
            return new Full(what, command, paused);
        }

        boolean same(Full o) {
            return what.equals(o.what) && (!FULL_COMMAND.equals(what) || command.equals(o.command));
        }
    }

    /** Mining's "Arada hareket etsin": the moves between blocks and how often, in seconds. */
    public static final class Idle {
        public boolean look;
        public boolean sneak;
        public boolean jump;
        public boolean step;
        public int min;
        public int max;

        public Idle(boolean look, boolean sneak, boolean jump, boolean step, int min, int max) {
            this.look = look;
            this.sneak = sneak;
            this.jump = jump;
            this.step = step;
            this.min = min;
            this.max = max;
        }

        static Idle read(@Nullable JsonObject o) {
            JsonObject every = Json.obj(o, "every");
            return new Idle(Json.bool(o, "look", false), Json.bool(o, "sneak", false), Json.bool(o, "jump", false),
                    Json.bool(o, "step", false), (int) Json.num(every, "min", EVERY_DEFAULT_MIN), (int) Json.num(every, "max", EVERY_DEFAULT_MAX));
        }

        /** Any move is on. */
        public boolean any() {
            return look || sneak || jump || step;
        }

        JsonObject json() {
            JsonObject o = new JsonObject();
            o.addProperty("look", look);
            o.addProperty("sneak", sneak);
            o.addProperty("jump", jump);
            o.addProperty("step", step);
            JsonObject every = new JsonObject();
            every.addProperty("min", min);
            every.addProperty("max", max);
            o.add("every", every);
            return o;
        }

        Idle copy() {
            return new Idle(look, sneak, jump, step, min, max);
        }

        boolean same(Idle o) {
            return look == o.look && sneak == o.sneak && jump == o.jump && step == o.step && min == o.min && max == o.max;
        }
    }

    /** {@code config.fishing}. */
    public static final class Fishing {
        public boolean enabled;
        public final Tool rod;
        public final Full full;
        /** A small turn between casts (keeps a server's AFK check quiet). */
        public boolean look;

        public Fishing(boolean enabled, Tool rod, Full full, boolean look) {
            this.enabled = enabled;
            this.rod = rod;
            this.full = full;
            this.look = look;
        }

        /** What an account that never stored it has: off, and every part at its default. */
        public static Fishing defaults() {
            return new Fishing(false, new Tool(true, KEEP_DEFAULT), new Full(FULL_WAIT, ""), false);
        }

        /** The stored setting from the account view; null when none is stored (then it is off). */
        @Nullable
        public static Fishing read(@Nullable JsonObject o) {
            if (o == null) return null;
            return new Fishing(Json.bool(o, "enabled", false), Tool.read(Json.obj(o, "rod")), Full.read(Json.obj(o, "full")),
                    Json.bool(o, "look", false));
        }

        /** The whole object, every part (the service fills nothing in from what it stored before). */
        public JsonObject json() {
            JsonObject o = new JsonObject();
            o.addProperty("enabled", enabled);
            o.add("rod", rod.json());
            o.add("full", full.json());
            o.addProperty("look", look);
            return o;
        }

        public Fishing copy() {
            return new Fishing(enabled, rod.copy(), full.copy(), look);
        }

        public boolean same(@Nullable Fishing o) {
            return o != null && enabled == o.enabled && rod.same(o.rod) && full.same(o.full) && look == o.look;
        }

        /** Everything but {@code enabled} is the same as {@code o}'s (a change the running client takes without a restart). */
        public boolean samePartsAs(@Nullable Fishing o) {
            return o != null && rod.same(o.rod) && full.same(o.full) && look == o.look;
        }

        /** What the service would refuse, found before anything is sent. */
        public List<Problem> problems() {
            List<Problem> out = new ArrayList<>();
            checkTool(FISHING, rod, out);
            checkFull(FISHING, full, out);
            return out;
        }
    }

    /** {@code config.mining}. */
    public static final class Mining {
        public boolean enabled;
        /** Null: every block of {@link #MINING_BLOCKS}; else the player's own choice of them. */
        @Nullable
        public List<String> blocks;
        public final Tool tool;
        public final Full full;
        public final Idle idle;

        public Mining(boolean enabled, @Nullable List<String> blocks, Tool tool, Full full, Idle idle) {
            this.enabled = enabled;
            this.blocks = blocks;
            this.tool = tool;
            this.full = full;
            this.idle = idle;
        }

        public static Mining defaults() {
            return new Mining(false, null, new Tool(true, KEEP_DEFAULT), new Full(FULL_WAIT, ""),
                    new Idle(false, false, false, false, EVERY_DEFAULT_MIN, EVERY_DEFAULT_MAX));
        }

        @Nullable
        public static Mining read(@Nullable JsonObject o) {
            if (o == null) return null;
            return new Mining(Json.bool(o, "enabled", false), readBlocks(o.get("blocks")), Tool.read(Json.obj(o, "tool")),
                    Full.read(Json.obj(o, "full")), Idle.read(Json.obj(o, "idle")));
        }

        public JsonObject json() {
            JsonObject o = new JsonObject();
            o.addProperty("enabled", enabled);
            // Every part, as the service stores it: null is all of the list.
            List<String> chosen = ordered(blocks);
            if (chosen != null) {
                JsonArray a = new JsonArray();
                for (String id : chosen) a.add(id);
                o.add("blocks", a);
            } else {
                o.add("blocks", com.google.gson.JsonNull.INSTANCE);
            }
            o.add("tool", tool.json());
            o.add("full", full.json());
            o.add("idle", idle.json());
            return o;
        }

        public Mining copy() {
            return new Mining(enabled, blocks == null ? null : new ArrayList<>(blocks), tool.copy(), full.copy(), idle.copy());
        }

        public boolean same(@Nullable Mining o) {
            return o != null && enabled == o.enabled && samePartsAs(o);
        }

        public boolean samePartsAs(@Nullable Mining o) {
            if (o == null) return false;
            List<String> a = ordered(blocks);
            List<String> b = ordered(o.blocks);
            boolean sameBlocks = a == null ? b == null : a.equals(b);
            return sameBlocks && tool.same(o.tool) && full.same(o.full) && idle.same(o.idle);
        }

        /** Whether {@code id} is broken: every block when no choice is made. */
        public boolean breaks(String id) {
            return blocks == null ? MINING_BLOCKS.contains(id) : blocks.contains(id);
        }

        /** How many of the list's blocks it breaks. */
        public int count() {
            List<String> chosen = ordered(blocks);
            return chosen == null ? MINING_BLOCKS.size() : chosen.size();
        }

        public List<Problem> problems() {
            List<Problem> out = new ArrayList<>();
            List<String> chosen = ordered(blocks);
            // The service's reasons: an empty choice is out of range, as an interval the wrong way round.
            if (chosen != null && chosen.isEmpty()) out.add(new Problem("mining.blocks", "range"));
            checkTool(MINING, tool, out);
            checkFull(MINING, full, out);
            if (idle.min < EVERY_MIN || idle.max > EVERY_MAX || idle.min > idle.max) out.add(new Problem("mining.every", "range"));
            return out;
        }
    }

    private static void checkTool(String mode, Tool tool, List<Problem> out) {
        if (tool.keep < KEEP_MIN || tool.keep > KEEP_MAX) out.add(new Problem(mode + ".keep", "range"));
    }

    private static void checkFull(String mode, Full full, List<Problem> out) {
        if (!FULL_COMMAND.equals(full.what)) return;
        // The contract's text rules, and never a secret (a password cannot be sealed here: nothing to type it into).
        CloudText.Problem text = CloudText.check(full.command);
        if (text != null) out.add(new Problem(mode + ".command", text.code));
        else if (CloudText.looksSensitive(full.command)) out.add(new Problem(mode + ".command", "secret"));
    }

    /**
     * The stored choice: null or absent is every block; a list keeps its known blocks (the service stores only those) and
     * an empty one stays empty (refused at a save).
     */
    @Nullable
    static List<String> readBlocks(@Nullable JsonElement e) {
        if (e == null || e.isJsonNull() || !e.isJsonArray()) return null;
        List<String> out = new ArrayList<>();
        for (JsonElement item : e.getAsJsonArray()) {
            if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()) {
                String id = item.getAsString();
                if (MINING_BLOCKS.contains(id) && !out.contains(id)) out.add(id);
            }
        }
        return out;
    }

    /**
     * A choice in the contract's order, without repeats or unknown ids (the service takes only that form); null for
     * every block. A choice of every block stays a list: the player picked them one by one, and a block the list gains
     * later is not theirs.
     */
    @Nullable
    public static List<String> ordered(@Nullable List<String> blocks) {
        if (blocks == null) return null;
        List<String> out = new ArrayList<>();
        for (String id : MINING_BLOCKS) if (blocks.contains(id)) out.add(id);
        return out;
    }
}
