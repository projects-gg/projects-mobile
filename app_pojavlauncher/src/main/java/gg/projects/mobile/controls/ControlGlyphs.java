package gg.projects.mobile.controls;

import androidx.annotation.DrawableRes;

import net.kdt.pojavlaunch.R;

/** Drawable of each control glyph name ({@link ControlStyle#GLYPHS}). */
final class ControlGlyphs {
    private ControlGlyphs() {}

    /** The glyph's drawable, 0 for an unknown name. */
    @DrawableRes
    static int drawable(String name) {
        if (name == null) return 0;
        switch (name) {
            case "pause": return R.drawable.projects_ctl_pause;
            case "back": return R.drawable.projects_ctl_back;
            case "chat": return R.drawable.projects_ctl_chat;
            case "keyboard": return R.drawable.projects_ctl_keyboard;
            case "inventory": return R.drawable.projects_ctl_inventory;
            case "more": return R.drawable.projects_ctl_more;
            case "mods": return R.drawable.projects_ctl_mods;
            case "menu": return R.drawable.projects_ctl_menu;
            case "hide": return R.drawable.projects_ctl_hide;
            case "show": return R.drawable.projects_ctl_show;
            case "close": return R.drawable.projects_ctl_close;
            case "jump": return R.drawable.projects_ctl_jump;
            case "sneak": return R.drawable.projects_ctl_sneak;
            case "sprint": return R.drawable.projects_ctl_sprint;
            case "attack": return R.drawable.projects_ctl_attack;
            case "use": return R.drawable.projects_ctl_use;
            case "rightclick": return R.drawable.projects_ctl_rightclick;
            case "leftclick": return R.drawable.projects_ctl_leftclick;
            case "shift": return R.drawable.projects_ctl_shift;
            case "view": return R.drawable.projects_ctl_view;
            case "drop": return R.drawable.projects_ctl_drop;
            case "swap": return R.drawable.projects_ctl_swap;
            case "players": return R.drawable.projects_ctl_players;
            case "hud": return R.drawable.projects_ctl_hud;
            case "debug": return R.drawable.projects_ctl_debug;
            case "mouse": return R.drawable.projects_ctl_mouse;
            case "pick": return R.drawable.projects_ctl_pick;
            case "zoom": return R.drawable.projects_ctl_zoom;
            case "bright": return R.drawable.projects_ctl_bright;
            case "mic": return R.drawable.projects_ctl_mic;
            case "waypoint": return R.drawable.projects_ctl_waypoint;
            case "map": return R.drawable.projects_ctl_map;
            default: return 0;
        }
    }
}
