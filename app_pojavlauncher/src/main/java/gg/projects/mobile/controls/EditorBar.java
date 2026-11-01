package gg.projects.mobile.controls;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;

import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

import net.kdt.pojavlaunch.R;

import gg.projects.mobile.mods.Motion;
import gg.projects.mobile.ui.ChamferDrawable;
import gg.projects.mobile.ui.Press;

/**
 * CTRL-SAVE (1 Nov 2026): the controls editor's two buttons at the top centre, in the launcher's editor and in the
 * game's: "Menü" opens the editor's menu (upstream's faint cog at a third of its opacity, which players did not
 * find) and "Kaydet" writes the layout the game reads. "Kaydet" is quiet and cannot be pressed while nothing changed,
 * and turns gold, the editor's one loud thing, once something did. Chamfered actions in the keys' own colours
 * (obsidian, a bone hairline), so they read over snow and in a cave; 40 dp tall inside 48 dp targets. Just under the
 * keys (a key placed at the top centre stays reachable), under a key's settings panel and its action row. It fades in
 * and out with the editor (opacity only, interruptible; at once with animations off).
 */
@SuppressLint("ViewConstructor")
public final class EditorBar extends LinearLayout {
    private static final long ENTER_MS = 160;
    private static final long EXIT_MS = 110;
    /** obsidian-900 at 88 %: the keys' own dark, a little denser so a label reads over the world. */
    private static final int FILL = 0xE0121016;
    private static final int FILL_PRESSED = 0xE6272229;
    /** bone-100 at 30 % (the keys' ring), at 16 % around a "Kaydet" that cannot be pressed yet. */
    private static final int RING = 0x4DF3EBDD;
    private static final int RING_QUIET = 0x29F3EBDD;
    private static final int RING_PRESSED = 0x80F3EBDD;

    private final Button mMenu;
    private final Button mSave;

    public EditorBar(Context context, Runnable onMenu, Runnable onSave) {
        super(context);
        float dp = context.getResources().getDisplayMetrics().density;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setClipChildren(false);
        setClipToPadding(false);
        // Just under the keys (upstream lifts every key to a translation Z of 10, the resize handle to 10.5), without a
        // shadow: a key a player put at the top centre, and its handle, stay reachable over the bar.
        setOutlineProvider(null);
        setTranslationZ(9.5f);

        mMenu = button(context, R.string.projects_ctrl_menu, quiet(context, dp), ContextCompat.getColor(context, R.color.projects_bone_100),
                R.font.figtree_semibold);
        mMenu.setOnClickListener(v -> onMenu.run());
        addView(mMenu, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mSave = button(context, R.string.projects_ctrl_save, save(context, dp), 0, R.font.tektur_semibold);
        mSave.setTextColor(new ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{ContextCompat.getColor(context, R.color.projects_ash_400), ContextCompat.getColor(context, R.color.projects_on_gold)}));
        mSave.setOnClickListener(v -> onSave.run());
        LayoutParams saveParams = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        saveParams.setMarginStart(Math.round(8 * dp));
        addView(mSave, saveParams);
        setCanSave(false);
        setVisibility(GONE);
    }

    /** "Kaydet" can be pressed (gold) while the layout holds a change it has not written. */
    public void setCanSave(boolean canSave) {
        mSave.setEnabled(canSave);
    }

    /** Shows the bar (the editor opened). */
    public void appear() {
        animate().cancel();
        if (!Motion.enabled(getContext())) {
            setAlpha(1f);
            setVisibility(VISIBLE);
            return;
        }
        if (getVisibility() != VISIBLE) {
            setAlpha(0f);
            setVisibility(VISIBLE);
        }
        animate().alpha(1f).setDuration(ENTER_MS).setInterpolator(Motion.EASE_OUT).withLayer();
    }

    /** Hides the bar (the editor closed), faster than it came. */
    public void disappear() {
        animate().cancel();
        if (getVisibility() != VISIBLE) return;
        if (!Motion.enabled(getContext())) {
            setVisibility(GONE);
            return;
        }
        animate().alpha(0f).setDuration(EXIT_MS).setInterpolator(Motion.EASE_OUT).withLayer()
                .withEndAction(() -> setVisibility(GONE));
    }

    private static Button button(Context context, int text, Drawable background, int textColor, int font) {
        float dp = context.getResources().getDisplayMetrics().density;
        Button button = new Button(context, null, android.R.attr.borderlessButtonStyle);
        button.setText(text);
        button.setAllCaps(false);
        button.setBackground(background);
        try {
            button.setTypeface(ResourcesCompat.getFont(context, font));
        } catch (RuntimeException ignored) {
            // The platform font is fine.
        }
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        if (textColor != 0) button.setTextColor(textColor);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(Math.round(48 * dp));
        button.setMinimumHeight(Math.round(48 * dp));
        button.setMinWidth(Math.round(72 * dp));
        button.setMinimumWidth(Math.round(72 * dp));
        button.setPadding(Math.round(16 * dp), Math.round(4 * dp), Math.round(16 * dp), Math.round(4 * dp));
        button.setIncludeFontPadding(false);
        Press.attach(button, Press.Style.BUTTON);
        return button;
    }

    /** "Menü": the keys' dark with their bone hairline; lighter while pressed. */
    private static Drawable quiet(Context context, float dp) {
        float cut = 9 * dp;
        float stroke = Math.max(1f, dp);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, new ChamferDrawable(FILL_PRESSED, RING_PRESSED, stroke, cut, null));
        states.addState(new int[]{android.R.attr.state_focused},
                new ChamferDrawable(FILL, ContextCompat.getColor(context, R.color.projects_gold_300), 2 * stroke, cut, null));
        states.addState(new int[]{}, new ChamferDrawable(FILL, RING, stroke, cut, null));
        return inset(states, dp);
    }

    /** "Kaydet": gold (pressed gold-600); while it cannot be pressed, the quiet look with a fainter ring. */
    private static Drawable save(Context context, float dp) {
        float cut = 9 * dp;
        float stroke = Math.max(1f, dp);
        int gold = ContextCompat.getColor(context, R.color.projects_gold_500);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled}, new ChamferDrawable(FILL, RING_QUIET, stroke, cut, null));
        states.addState(new int[]{android.R.attr.state_pressed},
                new ChamferDrawable(ContextCompat.getColor(context, R.color.projects_gold_600), cut, null));
        states.addState(new int[]{android.R.attr.state_focused},
                new ChamferDrawable(gold, ContextCompat.getColor(context, R.color.projects_gold_300), 2 * stroke, cut, null));
        states.addState(new int[]{}, new ChamferDrawable(gold, cut, null));
        return inset(states, dp);
    }

    /** 40 dp drawn inside the 48 dp target. */
    private static Drawable inset(Drawable drawable, float dp) {
        int v = Math.round(4 * dp);
        return new InsetDrawable(drawable, 0, v, 0, v);
    }
}
