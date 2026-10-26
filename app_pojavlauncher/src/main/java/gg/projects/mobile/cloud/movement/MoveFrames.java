package gg.projects.mobile.cloud.movement;

import androidx.annotation.Nullable;

import com.google.gson.JsonObject;

import gg.projects.mobile.social.Json;

/**
 * The movement stream's frames (services/cloud-afk README "Movement on the live socket"): {@code movst} (whether the
 * account can move now and what it is doing), {@code pos} (where it stands) and {@code movres}'s error. Pure, so the
 * tests read the service's own frames.
 */
public final class MoveFrames {
    private MoveFrames() {}

    /** {@code movst}. */
    public static final class State {
        /** {@code state} {@code ok}: the account can move now. */
        public final boolean ok;
        /**
         * With off: {@code not_running}, {@code setting}, {@code restart}, {@code slots}, {@code version} or
         * {@code service}.
         */
        @Nullable
        public final String reason;
        /** With ok: {@code idle}, {@code step}, {@code goto}, {@code approach} or {@code follow}. */
        public final String doing;
        /** goto: the target block. */
        @Nullable
        public final int[] to;
        /** approach, follow: the account it walks to. */
        @Nullable
        public final String name;
        /** idle: how the last activity ended by itself (a stop leaves none). */
        @Nullable
        public final String ended;
        public final boolean sneak;
        /** An AFK+ deal is open: everything but stop is refused. */
        public final boolean tradeLock;

        public State(boolean ok, @Nullable String reason, String doing, @Nullable int[] to, @Nullable String name, @Nullable String ended,
                     boolean sneak, boolean tradeLock) {
            this.ok = ok;
            this.reason = reason;
            this.doing = doing;
            this.to = to;
            this.name = name;
            this.ended = ended;
            this.sneak = sneak;
            this.tradeLock = tradeLock;
        }

        public static State read(@Nullable JsonObject o) {
            boolean ok = "ok".equals(Json.str(o, "state", "off"));
            JsonObject to = Json.obj(o, "to");
            int[] target = to == null ? null
                    : new int[]{(int) Json.num(to, "x", 0), (int) Json.num(to, "y", 0), (int) Json.num(to, "z", 0)};
            String doing = Json.str(o, "doing", "idle");
            switch (doing) {
                case "step":
                case "goto":
                case "approach":
                case "follow":
                    break;
                default:
                    doing = "idle";
            }
            String reason = ok ? null : Json.str(o, "reason", "service");
            return new State(ok, reason, doing, "goto".equals(doing) ? target : null, Json.str(o, "name"),
                    "idle".equals(doing) ? Json.str(o, "ended") : null, Json.bool(o, "sneak", false), "trade".equals(Json.str(o, "lock")));
        }

        /** It walks somewhere or follows someone: a stop has something to stop. */
        public boolean going() {
            return "goto".equals(doing) || "approach".equals(doing) || "follow".equals(doing);
        }
    }

    /** {@code pos}: the block it stands in, its dimension and where it looks. */
    public static final class Pos {
        public final int x;
        public final int y;
        public final int z;
        /** {@code minecraft:overworld}, {@code minecraft:the_nether}, {@code minecraft:the_end}, or a server's own. */
        public final String dim;
        /** {@code north}, {@code south}, {@code east} or {@code west}. */
        public final String facing;

        public Pos(int x, int y, int z, String dim, String facing) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.dim = dim;
            this.facing = facing;
        }

        public static Pos read(@Nullable JsonObject o) {
            // Block coordinates: whole numbers already (the machine rounds down).
            return new Pos((int) Json.num(o, "x", 0), (int) Json.num(o, "y", 0), (int) Json.num(o, "z", 0),
                    Json.str(o, "dim", "minecraft:overworld"), Json.str(o, "facing", "south"));
        }
    }

    /** {@code movres}'s error: {@code code}, its {@code reason} and {@code retryAfter} (seconds). */
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

        /** Nothing to say: the next frames show the truth ({@code stale}, {@code invalid}). */
        public boolean redrawOnly() {
            return "stale".equals(code) || "invalid".equals(code);
        }
    }
}
