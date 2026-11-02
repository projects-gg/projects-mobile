package gg.projects.mobile.controls;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;

import androidx.core.content.ContextCompat;

import net.kdt.pojavlaunch.R;

import gg.projects.mobile.ui.ChamferDrawable;
import gg.projects.mobile.ui.Haptics;

/**
 * MOB-NEXT (TASTE-2's P1): the controls editor's switch between the game's two screens, at the bottom centre where the
 * default layout leaves room (Minecraft's hotbar): "Oyunda" shows the keys the game shows in play, "Menülerde" those it
 * shows in its menus ({@link EditorRules#shownIn}), so the default layout's twins (DURAKLAT and GERİ, ZIPLA and SHIFT,
 * ...) are never drawn over each other. A segmented control in {@link EditorBar}'s dress (obsidian, a bone hairline,
 * chamfered, 40 dp drawn in 48 dp targets); the chosen screen is the quiet plate with bone words (design.md §6, never
 * gold: the editor's gold is "Kaydet"). It comes and goes with the editor, just under the keys as the top bar.
 */
@SuppressLint("ViewConstructor")
public final class EditorScenes extends LinearLayout {
    /** The chosen screen: obsidian under a white 16 % plate, and a bone ring at 50 %. */
    private static final int FILL_CHOSEN = 0xE638363B;
    private static final int RING_CHOSEN = 0x80F3EBDD;

    public interface Picked {
        void scene(EditorRules.Scene scene);
    }

    private final Button mGame;
    private final Button mMenus;
    private final int mMinWidth;
    private EditorRules.Scene mScene = EditorRules.Scene.GAME;

    public EditorScenes(Context context, Picked picked) {
        super(context);
        float dp = context.getResources().getDisplayMetrics().density;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setClipChildren(false);
        setClipToPadding(false);
        setOutlineProvider(null);
        setTranslationZ(9.5f);
        mMinWidth = Math.round(72 * dp);

        mGame = EditorBar.button(context, R.string.projects_ctrl_scene_game, segment(context, dp), 0, R.font.figtree_semibold);
        mGame.setOnClickListener(v -> pick(picked, EditorRules.Scene.GAME));
        addView(mGame, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mMenus = EditorBar.button(context, R.string.projects_ctrl_scene_menus, segment(context, dp), 0, R.font.figtree_semibold);
        mMenus.setOnClickListener(v -> pick(picked, EditorRules.Scene.MENU));
        LayoutParams menusParams = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        menusParams.setMarginStart(Math.round(6 * dp));
        addView(mMenus, menusParams);

        setScene(EditorRules.Scene.GAME);
        setVisibility(GONE);
    }

    private void pick(Picked picked, EditorRules.Scene scene) {
        if (scene == mScene) return;
        Haptics.tick(scene == EditorRules.Scene.GAME ? mGame : mMenus);
        picked.scene(scene);
    }

    /** Marks {@code scene} as the one shown (the editor calls it for every switch, and on opening). */
    public void setScene(EditorRules.Scene scene) {
        mScene = scene;
        mark(mGame, scene == EditorRules.Scene.GAME);
        mark(mMenus, scene == EditorRules.Scene.MENU);
    }

    private static void mark(Button button, boolean on) {
        Context context = button.getContext();
        button.setActivated(on);
        button.setTextColor(ContextCompat.getColor(context, on ? R.color.projects_bone_50 : R.color.projects_ash_300));
        if (Build.VERSION.SDK_INT >= 30) button.setStateDescription(on ? context.getString(R.string.projects_cloud_selected) : null);
    }

    public void appear() {
        EditorBar.fadeIn(this);
    }

    public void disappear() {
        EditorBar.fadeOut(this);
    }

    /** Two equal segments, as wide as the longer word needs (it grows with the font size). */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = Math.max(mMinWidth, Math.max(wordWidth(mGame), wordWidth(mMenus)));
        for (Button button : new Button[]{mGame, mMenus}) {
            if (button.getMinWidth() != width) {
                button.setMinWidth(width);
                button.setMinimumWidth(width);
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    private static int wordWidth(Button button) {
        return (int) Math.ceil(button.getPaint().measureText(button.getText().toString()))
                + button.getPaddingLeft() + button.getPaddingRight();
    }

    /** A segment: the keys' dark with a faint ring; the chosen one the quiet plate; lighter while pressed. */
    private static Drawable segment(Context context, float dp) {
        float cut = 9 * dp;
        float stroke = Math.max(1f, dp);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused},
                new ChamferDrawable(EditorBar.FILL, ContextCompat.getColor(context, R.color.projects_gold_300), 2 * stroke, cut, null));
        states.addState(new int[]{android.R.attr.state_activated}, new ChamferDrawable(FILL_CHOSEN, RING_CHOSEN, stroke, cut, null));
        states.addState(new int[]{android.R.attr.state_pressed},
                new ChamferDrawable(EditorBar.FILL_PRESSED, EditorBar.RING_PRESSED, stroke, cut, null));
        states.addState(new int[]{}, new ChamferDrawable(EditorBar.FILL, EditorBar.RING_QUIET, stroke, cut, null));
        return EditorBar.inset(states, dp);
    }
}
