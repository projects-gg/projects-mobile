package gg.projects.mobile.social.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import net.kdt.pojavlaunch.R;

import gg.projects.mobile.ui.ChamferDrawable;

/**
 * MOB-POLISH: the friends screens' chips in the desktop's two-shape rule (design.md §1): an action (a chip, a tab, a
 * quick reply, a reaction) is chamfered, its top-left and bottom-right corners cut, never a pill; containers keep their
 * 10 dp radius, and message bubbles, the field, faces and crew tags keep their own shapes. Quiet by default: a faint
 * fill and hairline, as the desktop's quick replies. Gold only where it carries meaning: the player's own reaction, or
 * the one action a place offers now ("Selam ver"). 0.4.5: what is chosen among several (a tab, a day) sits on the quiet
 * bone plate, the one selected look of the launcher, the site and Studio (it was a faint gold plate with a gold line).
 */
final class SocialChips {
    private SocialChips() {}

    /** Discord's Blurple, outside the palette: only on Discord-branded actions, with Discord's own symbol. */
    static final int BLURPLE = 0xFF5865F2;
    /** The symbol's width to its height (its drawing is 127.14 × 96.36). */
    static final float DISCORD_RATIO = 127.14f / 96.36f;

    private static final int QUIET_FILL = 0x0FFFFFFF;
    private static final int QUIET_LINE = 0x14FFFFFF;
    private static final int QUIET_PRESSED_FILL = 0x1AFFFFFF;
    private static final int QUIET_PRESSED_LINE = 0x29FFFFFF;
    /** The quiet bone plate of a chosen option (ChamferDrawable.toggle's chosen look). */
    private static final int CHOSEN_FILL = 0x1FF3EBDD;
    private static final int CHOSEN_LINE = 0x80F3EBDD;
    private static final int CHOSEN_PRESSED_FILL = 0x2EF3EBDD;
    private static final int GOLD_FILL = 0x1FE89A2C;
    private static final int GOLD_PRESSED_FILL = 0x33E89A2C;

    private static float dp(Context context) {
        return context.getResources().getDisplayMetrics().density;
    }

    /** A quiet action chip ("Geliyorum", "Discord'dan mesajlaş", "Sınırlı hesap"); {@code cutDp} by its height. */
    static Drawable quiet(Context context, float cutDp) {
        float d = dp(context);
        float cut = cutDp * d;
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled}, new ChamferDrawable(0x08FFFFFF, 0x0FFFFFFF, d, cut, null));
        states.addState(new int[]{android.R.attr.state_pressed}, new ChamferDrawable(QUIET_PRESSED_FILL, QUIET_PRESSED_LINE, d, cut, null));
        states.addState(new int[]{android.R.attr.state_focused}, focusRing(context, QUIET_FILL, cut));
        states.addState(new int[]{}, new ChamferDrawable(QUIET_FILL, QUIET_LINE, d, cut, null));
        return states;
    }

    /**
     * A chip among several of which one is chosen (the picker's and the reactions' tabs, the plan's days): quiet, the
     * chosen one ({@code setSelected(true)}) on the quiet bone plate; its text goes bone-100 with it (ash-300 otherwise).
     */
    static Drawable choice(Context context, float cutDp) {
        float d = dp(context);
        float cut = cutDp * d;
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_selected, android.R.attr.state_pressed},
                new ChamferDrawable(CHOSEN_PRESSED_FILL, CHOSEN_LINE, d, cut, null));
        states.addState(new int[]{android.R.attr.state_selected, android.R.attr.state_focused}, focusRing(context, CHOSEN_FILL, cut));
        states.addState(new int[]{android.R.attr.state_selected}, new ChamferDrawable(CHOSEN_FILL, CHOSEN_LINE, d, cut, null));
        states.addState(new int[]{android.R.attr.state_pressed}, new ChamferDrawable(QUIET_PRESSED_FILL, QUIET_PRESSED_LINE, d, cut, null));
        states.addState(new int[]{android.R.attr.state_focused}, focusRing(context, QUIET_FILL, cut));
        states.addState(new int[]{}, new ChamferDrawable(QUIET_FILL, QUIET_LINE, d, cut, null));
        return states;
    }

    /** The one gold action a place offers now ("Selam ver", the desktop's line action): a faint gold plate, no line. */
    static Drawable gold(Context context, float cutDp) {
        float cut = cutDp * dp(context);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, new ChamferDrawable(GOLD_PRESSED_FILL, cut, null));
        states.addState(new int[]{android.R.attr.state_focused}, focusRing(context, GOLD_FILL, cut));
        states.addState(new int[]{}, new ChamferDrawable(GOLD_FILL, cut, null));
        return states;
    }

    /** A reaction under a message (28 dp): the player's own on the "mine" colour with a gold edge (the desktop's). */
    static Drawable reaction(Context context, boolean mine) {
        float d = dp(context);
        int fill = ContextCompat.getColor(context, mine ? R.color.projects_chat_mine : R.color.projects_obsidian_800);
        int line = ContextCompat.getColor(context, mine ? R.color.projects_gold_700 : R.color.projects_obsidian_700);
        return new ChamferDrawable(fill, line, Math.max(1f, Math.round(d)), 5 * d, null);
    }

    /** Discord's own symbol in Blurple, {@code heightDp} tall at its own proportions. */
    @Nullable
    static Drawable discordMark(Context context, float heightDp) {
        Drawable mark = ContextCompat.getDrawable(context, R.drawable.projects_ic_discord_glyph);
        if (mark == null) return null;
        mark = mark.mutate();
        mark.setTint(BLURPLE);
        float d = dp(context);
        mark.setBounds(0, 0, Math.round(heightDp * DISCORD_RATIO * d), Math.round(heightDp * d));
        return mark;
    }

    /** The keyboard's focus: the same plate with a 2 dp gold-300 edge (as the app's chamfered buttons). */
    private static Drawable focusRing(Context context, int fill, float cut) {
        return new ChamferDrawable(fill, ContextCompat.getColor(context, R.color.projects_gold_300), 2 * dp(context), cut, null);
    }
}
