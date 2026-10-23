package gg.projects.mobile.social.ui;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.TouchDelegate;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

import net.kdt.pojavlaunch.R;

import gg.projects.mobile.mods.Motion;
import gg.projects.mobile.social.CrewLook;
import gg.projects.mobile.social.SocialModel.Badge;

/**
 * TEAM6: the crew tag before a name ("[GK] Ayşe"), the crew tag chip the crew card already shows (the
 * crew colour at 24 % under bone-50 Tektur, a 4 dp corner): sm 16 dp (chat names, lists inside sheets),
 * md 18 dp (rows), lg 20 dp (a card's or a conversation's title). It never shortens; the name beside
 * it does. In a row it is part of the row (the row's own label says "Ayşe, etiket GK"); standing alone
 * (a card's title, the player's own card, a direct conversation's title) it is a button with a 48 dp
 * target that opens the tag's card. A tag that comes or goes live fades and grows from 96 % (160 ms in,
 * 110 ms out); nothing moves otherwise, and nothing at all with animations off.
 */
public final class CrewTagChip extends AppCompatTextView {
    public enum Size {
        SM(16, 10f), MD(18, 11f), LG(20, 12f);

        final int heightDp;
        final float textSp;

        Size(int heightDp, float textSp) {
            this.heightDp = heightDp;
            this.textSp = textSp;
        }
    }

    @Nullable
    private String mKey;
    private Size mSize = Size.MD;
    /** Whose tag a row's chip showed last ({@link #setBadgeOf}). */
    @Nullable
    private String mOwner;

    public CrewTagChip(Context context) {
        this(context, null);
    }

    public CrewTagChip(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setGravity(Gravity.CENTER);
        setIncludeFontPadding(false);
        setMaxLines(1);
        setLetterSpacing(0.06f);
        setTextColor(ContextCompat.getColor(context, R.color.projects_bone_50));
        try {
            setTypeface(ResourcesCompat.getFont(context, R.font.tektur_semibold));
        } catch (RuntimeException ignored) {
            // The theme's font.
        }
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setVisibility(GONE);
        applySize(Size.MD);
    }

    private void applySize(Size size) {
        mSize = size;
        float dp = getResources().getDisplayMetrics().density;
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size.textSp);
        setMinHeight(Math.round(size.heightDp * dp));
        setHeight(Math.round(size.heightDp * dp));
        int pad = Math.round((size == Size.SM ? 4 : 6) * dp);
        setPadding(pad, 0, pad, 0);
    }

    /**
     * Shows {@code badge}'s tag (null: gone). {@code animate}: a live change (the tag came, went or
     * changed while the row was on screen) gets the short fade; a first bind just appears.
     */
    public void setBadge(@Nullable Badge badge, Size size, boolean animate) {
        if (size != mSize) applySize(size);
        String key = badge == null ? null : badge.tag + "|" + badge.color;
        boolean changed = mKey == null ? key != null : !mKey.equals(key);
        mKey = key;
        animate().cancel();
        if (badge == null) {
            if (animate && changed && getVisibility() == VISIBLE && Motion.enabled(getContext())) {
                animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setDuration(110).setInterpolator(Motion.EASE_OUT)
                        .withEndAction(() -> {
                            if (mKey == null) setVisibility(GONE);
                            setAlpha(1f);
                            setScaleX(1f);
                            setScaleY(1f);
                        }).start();
            } else {
                setVisibility(GONE);
                setAlpha(1f);
                setScaleX(1f);
                setScaleY(1f);
            }
            return;
        }
        setText(badge.tag);
        GradientDrawable chip = new GradientDrawable();
        chip.setCornerRadius(4 * getResources().getDisplayMetrics().density);
        chip.setColor(CrewLook.tagTint(badge.color));
        if (isClickable()) {
            setBackground(new RippleDrawable(ColorStateList.valueOf(ContextCompat.getColor(getContext(), R.color.projects_ripple_strong)),
                    chip, null));
        } else {
            setBackground(chip);
        }
        setVisibility(VISIBLE);
        if (animate && changed && Motion.enabled(getContext())) {
            setAlpha(0f);
            setScaleX(0.96f);
            setScaleY(0.96f);
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(Motion.EASE_OUT).start();
        } else {
            setAlpha(1f);
            setScaleX(1f);
            setScaleY(1f);
        }
    }

    /**
     * A row's chip ({@code owner}: whose tag): it fades only when the same person's tag changes, never
     * when a recycled row shows someone else. Part of the row (see {@link #labelName}).
     */
    public void setBadgeOf(@Nullable String owner, @Nullable Badge badge, Size size) {
        boolean same = owner != null && owner.equals(mOwner);
        mOwner = owner;
        setBadge(badge, size, same);
    }

    /** A row's name as TalkBack reads it with the chip beside it: "Ayşe, etiket GK". */
    public static void labelName(android.widget.TextView name, @Nullable Badge badge) {
        name.setContentDescription(badge == null ? null
                : name.getContext().getString(R.string.projects_badge_row_label, name.getText(), badge.tag));
    }

    /**
     * Standing alone: a button that opens the tag's card ({@code open}), named for TalkBack ("Gece
     * Kartalları ekibinin etiketi"), its target widened to 48 dp inside its parent.
     */
    public void makeButton(@Nullable Badge badge, final View.OnClickListener open) {
        setClickable(true);
        setFocusable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setContentDescription(badge == null ? null : getContext().getString(R.string.projects_badge_chip_label, badge.name));
        setOnClickListener(v -> {
            gg.projects.mobile.ui.Haptics.press(v);
            open.onClick(v);
        });
        gg.projects.mobile.ui.Press.attach(this, gg.projects.mobile.ui.Press.Style.BUTTON);
        post(this::widenTarget);
    }

    /** The chip's target grows to 48 dp inside its parent (TouchDelegate), so a 20 dp chip is easy to press. */
    private void widenTarget() {
        final View parent = (View) getParent();
        if (parent == null || !isAttachedToWindow()) return;
        Rect area = new Rect();
        getHitRect(area);
        int min = Math.round(48 * getResources().getDisplayMetrics().density);
        int growY = Math.max(0, (min - area.height()) / 2);
        int growX = Math.max(0, (min - area.width()) / 2);
        area.top -= growY;
        area.bottom += growY;
        area.left -= growX;
        area.right += growX;
        parent.setTouchDelegate(new TouchDelegate(area, this));
    }

    /** A chip made in code for a row or a title ({@code margin} after it, before the name). */
    public static CrewTagChip make(Context context, Size size) {
        CrewTagChip chip = new CrewTagChip(context);
        chip.applySize(size);
        ViewGroup.MarginLayoutParams lp = new ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(Math.round(6 * context.getResources().getDisplayMetrics().density));
        chip.setLayoutParams(lp);
        return chip;
    }
}
