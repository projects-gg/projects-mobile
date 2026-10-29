package gg.projects.mobile.cloud.ui;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.R;

import gg.projects.mobile.cloud.CloudModel;
import gg.projects.mobile.cloud.CloudProblem;
import gg.projects.mobile.cloud.CloudService;
import gg.projects.mobile.cloud.PlusConfig;

/**
 * One AFK+ settings question on its own screen (SETUP-UX, 28 Oct 2026: the site opens it under its row; the phone, a
 * screen of its own): its lede, its editor and its folded "Gelişmiş", saved as every AFK+ settings screen is. "Sınırlara
 * git" opens the question of a limit with that field in view (its "Gelişmiş" open when the field is there).
 */
public final class PlusQuestionFragment extends PlusScreen implements PlusEditors.Host {
    public static final String TAG = "ProjectsPlusQuestionFragment";
    static final String ARG_QUESTION = "gg.projects.mobile.cloud.PLUS_QUESTION";
    static final String ARG_FOCUS = "gg.projects.mobile.cloud.PLUS_FOCUS";

    private PlusEditors.Drawn mDrawn = new PlusEditors.Drawn();
    private boolean mFocused;

    static Bundle args(String account, String question, @Nullable String focus) {
        Bundle args = CloudScreen.args(account);
        args.putString(ARG_QUESTION, question);
        if (focus != null) args.putString(ARG_FOCUS, focus);
        return args;
    }

    /** "Sınırlara git": the question that holds {@code field}, scrolled to it. */
    static void openLimit(CloudScreen from, String account, String field) {
        from.open(PlusQuestionFragment.class, args(account, PlusEditors.questionOf(field), field));
    }

    private String question() {
        Bundle args = getArguments();
        String q = args == null ? null : args.getString(ARG_QUESTION);
        return q == null ? PlusEditors.MONEY : q;
    }

    @Nullable
    private String focus() {
        Bundle args = getArguments();
        return args == null ? null : args.getString(ARG_FOCUS);
    }

    @Override
    protected int title() {
        return PlusEditors.title(question());
    }

    @Override
    protected void draw(LinearLayout content) {
        Context c = ctx();
        if (mEdit == null) return;
        mDrawn = new PlusEditors.Drawn();
        String lede = PlusEditors.lede(c, question());
        if (lede != null) content.addView(CloudUi.note(c, lede));
        String focus = focus();
        boolean open = focus != null && PlusEditors.advancedField(focus);
        PlusEditors.draw(question(), content, this, mDrawn, open, false);
        if (focus != null && !mFocused) {
            mFocused = true;
            final View target = mDrawn.fields.get(focus);
            if (target != null && mScroll != null) {
                // Once laid out: the field near the top, a jump while motion is reduced (design.md: no motion then).
                target.post(() -> {
                    if (mScroll == null) return;
                    int y = Math.max(0, target.getTop() - CloudUi.dp(c, 24));
                    View parent = (View) target.getParent();
                    while (parent != null && parent != mContent) {
                        y += parent.getTop();
                        parent = parent.getParent() instanceof View ? (View) parent.getParent() : null;
                    }
                    if (!gg.projects.mobile.mods.Motion.enabled(c)) mScroll.scrollTo(0, y);
                    else mScroll.smoothScrollTo(0, y);
                    light(target);
                });
            }
        }
    }

    /** Lights the field it was opened for once: a bone ring that fades out (opacity only; with motion off it just goes). */
    private void light(final View target) {
        if (target.getWidth() == 0 || target.getHeight() == 0) return;
        Context c = target.getContext();
        final android.graphics.drawable.GradientDrawable ring = new android.graphics.drawable.GradientDrawable();
        ring.setColor(0x14F3EBDD);
        ring.setStroke(CloudUi.dp(c, 2), CloudUi.color(c, R.color.projects_bone_100));
        ring.setCornerRadius(CloudUi.dp(c, 8));
        ring.setBounds(0, 0, target.getWidth(), target.getHeight());
        target.getOverlay().add(ring);
        if (gg.projects.mobile.mods.Motion.enabled(c)) {
            android.animation.ObjectAnimator fade = android.animation.ObjectAnimator.ofInt(ring, "alpha", 255, 0);
            fade.setStartDelay(900);
            fade.setDuration(700);
            fade.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    target.getOverlay().remove(ring);
                }
            });
            fade.start();
        } else {
            target.postDelayed(() -> target.getOverlay().remove(ring), 1600);
        }
        target.announceForAccessibility(c.getString(R.string.projects_limit_here));
    }

    // ---- PlusEditors.Host ----

    @Override
    public Context ctx() {
        return super.ctx();
    }

    @Override
    public Activity activity() {
        return requireActivity();
    }

    @Override
    public PlusConfig edit() {
        return mEdit == null ? new PlusConfig(new com.google.gson.JsonObject()) : mEdit;
    }

    @Nullable
    @Override
    public PlusConfig original() {
        return mOriginal;
    }

    @Nullable
    @Override
    public CloudModel.PlusDoc doc() {
        return mDoc;
    }

    @Override
    public CloudService service() {
        return mService;
    }

    @Nullable
    @Override
    public String accountId() {
        return super.accountId();
    }

    @Nullable
    @Override
    public String problemAt(String field) {
        return super.problemAt(field);
    }

    @Override
    public void changed() {
        super.changed();
    }

    @Override
    public void rebuild() {
        redraw();
    }

    @Override
    public void toast(CharSequence text) {
        super.toast(text);
    }

    @Override
    public void problem(CloudProblem problem, @Nullable View from) {
        super.problem(problem, from);
    }

    @Override
    public void openOrders() {
        String id = super.accountId();
        if (id != null) openFor(PlusOrdersFragment.class, id);
    }

    @Override
    public boolean alive() {
        return super.alive();
    }

    @Override
    public boolean notNow() {
        return false;
    }

    @Override
    public void setNotNow(boolean on) {
        // The setup's choice only.
    }
}
