package gg.projects.mobile.cloud.auto;

import android.content.Context;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import net.kdt.pojavlaunch.R;

import java.text.NumberFormat;
import java.util.Locale;

import gg.projects.mobile.cloud.CloudModel;
import gg.projects.mobile.cloud.CloudWords;

/**
 * Auto-fishing and auto-mining of a cloud account in words (services/cloud-afk README "Auto-fishing and auto-mining"):
 * when a mode's row and screen show, and every state, reason, refusal and slot as the site says it
 * (docs/tasks/fishmine-web.md, "Metinler"). A state or reason a newer machine sends reads as its kind's general words.
 */
public final class Auto {
    private Auto() {}

    /** The public list of servers where the account cannot join or a mode does not run (the site's page). */
    public static final String LIMITED_PATH_TR = "/sinirli-sunucular";
    public static final String LIMITED_PATH_EN = "/en/limited-servers";
    /** "Baktığı Yön": the game's yaw of each compass point (0 south, 90 west, 180 north, -90 east). */
    public static final String[] DIRS = {"north", "east", "south", "west"};
    public static final int[] DIR_YAW = {180, -90, 0, 90};
    /** "Bakış": up, ahead (the block at eye height) and down (the block in front at the feet), the site's pitches. */
    public static final String[] TILTS = {"up", "ahead", "down"};
    public static final int[] TILT_PITCH = {-45, 0, 45};

    public static boolean fishing(String mode) {
        return AutoSettings.FISHING.equals(mode);
    }

    /** Whether the account shows the mode's row and screen: the service and its machine have it, and it is on here. */
    public static boolean shows(CloudModel.Account account, String mode) {
        return account.host != null && account.features.autoPanel(mode);
    }

    /** "Balık" / "Kazma": the row's and the screen's name. */
    @StringRes
    public static int tab(String mode) {
        return fishing(mode) ? R.string.projects_auto_fishing_tab : R.string.projects_auto_mining_tab;
    }

    /** "Otomatik Balık Tutma" / "Otomatik Kazma": the switch's name. */
    @StringRes
    public static int name(String mode) {
        return fishing(mode) ? R.string.projects_auto_fishing_name : R.string.projects_auto_mining_name;
    }

    /** What the switch does, in a sentence. */
    @StringRes
    public static int line(String mode) {
        return fishing(mode) ? R.string.projects_auto_fishing_line : R.string.projects_auto_mining_line;
    }

    /** What the mode does now, in one sentence (the site's {@code stateText}). */
    public static String state(Context c, String mode, AutoFrames.State s) {
        String reason = s.reason == null ? "" : s.reason;
        switch (s.state) {
            case AutoFrames.ON:
                if (!fishing(mode)) return c.getString(R.string.projects_auto_mining_on);
                String doing = s instanceof AutoFrames.Fish ? ((AutoFrames.Fish) s).doing : null;
                if ("cast".equals(doing)) return c.getString(R.string.projects_auto_fishing_cast);
                if ("wait".equals(doing)) return c.getString(R.string.projects_auto_fishing_wait);
                if ("reel".equals(doing)) return c.getString(R.string.projects_auto_fishing_reel);
                return c.getString(R.string.projects_auto_fishing_on);
            case AutoFrames.PAUSED:
                switch (reason) {
                    case "player": return c.getString(R.string.projects_auto_paused_player);
                    case "moved": return c.getString(R.string.projects_auto_paused_moved);
                    case "window": return c.getString(R.string.projects_auto_paused_window);
                    case "trade": return c.getString(R.string.projects_auto_paused_trade);
                    default: return c.getString(R.string.projects_auto_paused_other);
                }
            case AutoFrames.WAITING:
                switch (reason) {
                    case "full": return c.getString(R.string.projects_auto_waiting_full);
                    case "no_rod": return c.getString(R.string.projects_auto_waiting_no_rod);
                    case "rod_low": return c.getString(R.string.projects_auto_waiting_rod_low);
                    case "no_water": return c.getString(R.string.projects_auto_waiting_no_water);
                    case "no_tool": return c.getString(R.string.projects_auto_waiting_no_tool);
                    case "tool_low": return c.getString(R.string.projects_auto_waiting_tool_low);
                    case "no_target": return c.getString(R.string.projects_auto_waiting_no_target);
                    default: return c.getString(R.string.projects_auto_waiting_other);
                }
            default:
                return off(c, mode, s.reason);
        }
    }

    /** Why the mode does not run ({@code autost} off, or {@code auto_off}'s reason). */
    public static String off(Context c, String mode, @Nullable String reason) {
        boolean fish = fishing(mode);
        switch (reason == null ? "" : reason) {
            case "setting": return c.getString(fish ? R.string.projects_auto_fishing_off_setting : R.string.projects_auto_mining_off_setting);
            case "restart": return c.getString(fish ? R.string.projects_auto_fishing_off_restart : R.string.projects_auto_mining_off_restart);
            case "slots": return c.getString(fish ? R.string.projects_auto_fishing_off_slots : R.string.projects_auto_mining_off_slots);
            case "version": return c.getString(fish ? R.string.projects_auto_fishing_off_version : R.string.projects_auto_mining_off_version);
            case "limited": return c.getString(fish ? R.string.projects_auto_fishing_off_limited : R.string.projects_auto_mining_off_limited);
            case "not_running":
                return c.getString(fish ? R.string.projects_auto_fishing_off_not_running : R.string.projects_auto_mining_off_not_running);
            default: return c.getString(fish ? R.string.projects_auto_fishing_off_service : R.string.projects_auto_mining_off_service);
        }
    }

    /**
     * A state that waits on the player (resume a moved account, give a rod or a pickaxe, turn it to the water): its
     * sentence carries the warning's small mark (the site's {@code needsPlayer}).
     */
    public static boolean needsPlayer(AutoFrames.State s) {
        if (s.paused()) return "moved".equals(s.reason);
        if (s.waiting()) return !"full".equals(s.reason) && !"no_target".equals(s.reason);
        return false;
    }

    /** Why the switch cannot be turned on now ({@code features.<mode>.slot}): the player's, number-free; or the machine's. */
    public static String slot(Context c, String mode, String slot) {
        if ("player".equals(slot)) return c.getString(R.string.projects_auto_slot_player);
        return c.getString(fishing(mode) ? R.string.projects_auto_fishing_no_slot : R.string.projects_auto_mining_no_slot);
    }

    /** A refused save ({@code limit_fishing}/{@code limit_mining}): the player's own most, or the machine's. */
    public static String limit(Context c, String mode, String scope, long max) {
        boolean fish = fishing(mode);
        if (!"player".equals(scope)) return c.getString(fish ? R.string.projects_auto_fishing_no_slot : R.string.projects_auto_mining_no_slot);
        if (max > 1) return c.getString(fish ? R.string.projects_auto_fishing_max_accounts : R.string.projects_auto_mining_max_accounts, (int) max);
        return c.getString(fish ? R.string.projects_auto_fishing_one_account : R.string.projects_auto_mining_one_account);
    }

    /** Studio's public reason in the app's language (the other when one is empty), else the general sentence. */
    public static String limited(Context c, String mode, @Nullable CloudModel.Localized reason) {
        String words = reason == null ? null : reason.in(CloudWords.lang(c));
        return words == null || words.trim().isEmpty() ? off(c, mode, "limited") : words;
    }

    /** The limited servers page in the app's language. */
    public static String limitedPath(Context c) {
        return "en".equals(CloudWords.lang(c)) ? LIMITED_PATH_EN : LIMITED_PATH_TR;
    }

    /**
     * A refused operation in one line (the site's {@code errorLine}); null when there is nothing to say (the next
     * frames redraw). {@code aim}: the refused one was an aim ("Takas sürerken çevrilemez.").
     */
    @Nullable
    public static String refusal(Context c, String mode, AutoFrames.Error error, boolean aim) {
        switch (error.code) {
            case "rate_limited": return c.getString(R.string.projects_auto_error_slow);
            case "busy_trade": return c.getString(aim ? R.string.projects_auto_error_trade : R.string.projects_auto_paused_trade);
            case "not_running": return c.getString(R.string.projects_auto_error_not_running);
            case "auto_off": return off(c, mode, error.reason);
            case "host_offline":
            case "not_found":
                return c.getString(R.string.projects_feat_inv_unreachable);
            default:
                return null;
        }
    }

    /**
     * A settings issue of a mode in words, or null for one the general words cover: {@code fishing}/{@code mining} (off,
     * limited, no slot), {@code <mode>.keep}, {@code <mode>.command}, {@code mining.blocks}, {@code mining.every}.
     */
    @Nullable
    public static String issue(Context c, String field, String problem) {
        String mode = field.startsWith(AutoSettings.MINING) ? AutoSettings.MINING : AutoSettings.FISHING;
        String part = field.indexOf('.') < 0 ? "" : field.substring(field.indexOf('.') + 1);
        switch (part) {
            case "": {
                if ("off".equals(problem)) return off(c, mode, "service");
                if ("limited".equals(problem)) return off(c, mode, "limited");
                if (problem.startsWith("player/")) {
                    long max;
                    try {
                        max = Long.parseLong(problem.substring("player/".length()));
                    } catch (NumberFormatException e) {
                        max = 1;
                    }
                    return limit(c, mode, "player", max);
                }
                if ("host".equals(problem)) return limit(c, mode, "host", 0);
                return null;
            }
            case "keep":
                return c.getString(R.string.projects_auto_keep_range);
            case "command":
                if ("empty".equals(problem)) return c.getString(R.string.projects_auto_command_empty);
                if ("secret".equals(problem)) return c.getString(R.string.projects_auto_command_secret);
                return null;
            case "blocks":
                return c.getString(R.string.projects_auto_blocks_none);
            case "every":
                return c.getString(R.string.projects_auto_idle_range);
            default:
                return null;
        }
    }

    /** A rate in the app's language with at most one decimal ("32,5", "33"). */
    public static String rate(Context c, double value) {
        NumberFormat f = NumberFormat.getNumberInstance("en".equals(CloudWords.lang(c)) ? Locale.ENGLISH : new Locale("tr", "TR"));
        f.setMaximumFractionDigits(value < 10 ? 1 : 0);
        f.setMinimumFractionDigits(0);
        return f.format(value);
    }

    /** A count in the app's language ("1.250"). */
    public static String count(Context c, long value) {
        return NumberFormat.getIntegerInstance("en".equals(CloudWords.lang(c)) ? Locale.ENGLISH : new Locale("tr", "TR")).format(value);
    }

    /** The compass point's name ("Kuzey"), the movement panel's own words. */
    public static String dir(Context c, String dir) {
        switch (dir) {
            case "north": return c.getString(R.string.projects_feat_move_north);
            case "east": return c.getString(R.string.projects_feat_move_east);
            case "west": return c.getString(R.string.projects_feat_move_west);
            default: return c.getString(R.string.projects_feat_move_south);
        }
    }

    /** The look's name ("Yukarı", "Düz", "Aşağı"). */
    public static String tilt(Context c, String tilt) {
        switch (tilt) {
            case "up": return c.getString(R.string.projects_auto_tilt_up);
            case "down": return c.getString(R.string.projects_auto_tilt_down);
            default: return c.getString(R.string.projects_auto_tilt_ahead);
        }
    }
}
