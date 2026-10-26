package gg.projects.mobile.cloud.movement;

import android.content.Context;

import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.R;

import gg.projects.mobile.cloud.CloudModel;

/**
 * Movement of a cloud account (services/cloud-afk README "Movement"): the player walks it by hand a few steps at a time,
 * to coordinates at most 256 blocks away, or next to their own main account or another of their accounts, which it can
 * also follow for a while. This class says when the panel shows and words the contract's states, refusals and ends
 * (its "Texts in the apps" table).
 */
public final class Movement {
    private Movement() {}

    /** Whether the account shows "Hareket": the service and its machine have movement, and it is on for this account. */
    public static boolean shows(CloudModel.Account account) {
        return account.host != null && account.features.movementPanel();
    }

    /** Why it cannot move now ({@code movst} off, or {@code movement_off}'s reason). */
    public static String off(Context c, @Nullable String reason) {
        if (reason == null) return c.getString(R.string.projects_feat_move_service_off);
        switch (reason) {
            case "not_running": return c.getString(R.string.projects_feat_move_not_running);
            case "setting": return c.getString(R.string.projects_feat_move_off);
            case "restart": return c.getString(R.string.projects_feat_move_restart);
            case "slots": return c.getString(R.string.projects_feat_move_no_slot);
            case "version": return c.getString(R.string.projects_feat_move_version);
            default: return c.getString(R.string.projects_feat_move_service_off);
        }
    }

    /** Why a switch cannot be turned on now ({@code features.movement.slot}, or {@code limit_movement}'s scope). */
    public static String slot(Context c, String scope, long max) {
        if (!"player".equals(scope)) return c.getString(R.string.projects_feat_move_no_slot);
        return max > 1 ? c.getString(R.string.projects_feat_move_max_accounts, (int) max) : c.getString(R.string.projects_feat_move_one_account);
    }

    /**
     * A refused operation in one line; null when there is nothing to say (the next frames redraw). {@code name}: the
     * account an approach or a follow went to, {@code main} the player's main name.
     */
    @Nullable
    public static String refusal(Context c, MoveFrames.Error error, @Nullable String name, @Nullable String main) {
        if (error.redrawOnly()) return null;
        switch (error.code) {
            case "movement_off": return off(c, error.reason);
            case "not_running": return c.getString(R.string.projects_feat_move_not_running);
            case "busy_trade": return c.getString(R.string.projects_feat_move_trade);
            case "too_far": return c.getString(R.string.projects_feat_move_far);
            case "not_allowed": return c.getString(R.string.projects_feat_move_not_allowed);
            case "not_visible": return notNear(c, name, main);
            case "blocked": return c.getString(R.string.projects_feat_move_blocked);
            case "loop": return c.getString(R.string.projects_feat_move_loop);
            case "rate_limited": return c.getString(R.string.projects_feat_move_slow);
            default: return c.getString(R.string.projects_feat_inv_unreachable);
        }
    }

    /** How the last walk or follow ended by itself ({@code movst} {@code ended}); null for none. */
    @Nullable
    public static String ended(Context c, @Nullable String ended, @Nullable String name, @Nullable String main) {
        if (ended == null) return null;
        switch (ended) {
            case "arrived": return c.getString(R.string.projects_feat_move_arrived);
            case "blocked": return c.getString(R.string.projects_feat_move_blocked);
            case "no_path": return c.getString(R.string.projects_feat_move_no_path);
            case "timeout": return c.getString(R.string.projects_feat_move_timeout);
            case "limit": return c.getString(R.string.projects_feat_move_limit);
            case "out_of_sight": return notNear(c, name, main);
            case "reset": return c.getString(R.string.projects_feat_move_reset);
            case "service": return c.getString(R.string.projects_feat_move_service_off);
            case "trade": return c.getString(R.string.projects_feat_move_trade_started);
            default: return null;
        }
    }

    private static String notNear(Context c, @Nullable String name, @Nullable String main) {
        return name == null || name.equalsIgnoreCase(main == null ? "" : main) ? c.getString(R.string.projects_feat_move_not_near)
                : c.getString(R.string.projects_feat_move_not_near_named, name);
    }

    /** Where it looks: north, south, east, west in the app's language. */
    public static String facing(Context c, String facing) {
        switch (facing) {
            case "north": return c.getString(R.string.projects_feat_move_north);
            case "east": return c.getString(R.string.projects_feat_move_east);
            case "west": return c.getString(R.string.projects_feat_move_west);
            default: return c.getString(R.string.projects_feat_move_south);
        }
    }

    /** The dimension's name: the game's three by name, a server's own by its id's last part. */
    public static String dimension(Context c, String dim) {
        switch (dim) {
            case "minecraft:overworld": return c.getString(R.string.projects_feat_move_overworld);
            case "minecraft:the_nether": return c.getString(R.string.projects_feat_move_nether);
            case "minecraft:the_end": return c.getString(R.string.projects_feat_move_end);
            default: {
                int colon = dim.indexOf(':');
                return colon >= 0 ? dim.substring(colon + 1) : dim;
            }
        }
    }
}
