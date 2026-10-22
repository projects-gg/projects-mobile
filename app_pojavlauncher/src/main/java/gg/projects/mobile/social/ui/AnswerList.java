package gg.projects.mobile.social.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

import net.kdt.pojavlaunch.R;

import java.util.List;
import java.util.Map;

import gg.projects.mobile.mods.Motion;
import gg.projects.mobile.social.AnswerPeople;
import gg.projects.mobile.social.SocialModel.Profile;
import gg.projects.mobile.social.SocialService;
import gg.projects.mobile.social.SocialState;
import gg.projects.mobile.ui.Haptics;
import gg.projects.mobile.ui.Press;

/**
 * FIX2 (the owner's wish: "kimlerin geliyor gelemiyor listesini görebilelim"): who answered a plan or a
 * crew call, on its card. Folded it is the card's short tally with a chevron; a tap opens it in place:
 * "Kimler geliyor", then each answer's people with its count (the player first, then by name), the first
 * four of a group and "N kişi daha". Every person holds the same menu as anywhere else. The list grows
 * and folds with the card (interruptible, nothing moves with animations off); TalkBack hears it as
 * expanded or collapsed and can open or fold it with its actions.
 */
final class AnswerList {
    private AnswerList() {}

    /** What the card's screen keeps about its lists (they stay open across new answers). */
    interface Host {
        boolean isOpen(String key);

        void setOpen(String key, boolean open);

        /** A group shows everyone ("N kişi daha" was tapped). */
        boolean showsAll(String key, String answer);

        void setShowsAll(String key, String answer, boolean all);

        /** A person's menu ({@code anchor}: their row). */
        void person(String userId, View anchor);
    }

    /**
     * Binds the answers block of a card ({@code view_projects_answers}). {@code key}: the plan's or the
     * call's id; {@code labels}: each answer's group name, in {@code order}; {@code summary}: the folded
     * line (the tally).
     */
    static void bind(final View card, final String key, Map<String, String> answers, String[] order, int[] labels, String summary,
                     final Host host) {
        final View block = card.findViewById(R.id.projects_answers);
        final Context context = card.getContext();
        if (answers.isEmpty()) {
            block.setVisibility(View.GONE);
            return;
        }
        block.setVisibility(View.VISIBLE);
        final SocialState state = SocialService.get(context).state();
        final List<AnswerPeople.Group> groups = AnswerPeople.of(answers, order, state.myId(), id -> {
            Profile p = state.profile(id);
            return p == null ? null : p.name();
        });
        final View toggle = block.findViewById(R.id.projects_answers_toggle);
        final TextView line = block.findViewById(R.id.projects_answers_summary);
        final ImageView chevron = block.findViewById(R.id.projects_answers_chevron);
        final LinearLayout list = block.findViewById(R.id.projects_answers_list);
        final boolean open = host.isOpen(key);
        final int total = AnswerPeople.total(groups);
        Object running = list.getTag(R.id.projects_answers_anim);
        if (running instanceof Animator) ((Animator) running).end();
        showSummary(context, line, open, summary);
        chevron.setRotation(open ? 180f : 0f);
        fill(list, key, groups, order, labels, host);
        list.setVisibility(open ? View.VISIBLE : View.GONE);
        ViewGroup.LayoutParams lp = list.getLayoutParams();
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        list.setLayoutParams(lp);
        describe(toggle, context, open, total, () -> flip(block, key, summary, host));
        Press.attach(toggle, Press.Style.ROW);
        toggle.setOnClickListener(v -> flip(block, key, summary, host));
    }

    private static void flip(View block, String key, String summary, Host host) {
        Context context = block.getContext();
        boolean open = !host.isOpen(key);
        host.setOpen(key, open);
        View toggle = block.findViewById(R.id.projects_answers_toggle);
        TextView line = block.findViewById(R.id.projects_answers_summary);
        ImageView chevron = block.findViewById(R.id.projects_answers_chevron);
        LinearLayout list = block.findViewById(R.id.projects_answers_list);
        Haptics.toggle(toggle, open);
        showSummary(context, line, open, summary);
        boolean motion = Motion.enabled(context) && list.isAttachedToWindow();
        chevron.animate().cancel();
        if (motion) {
            chevron.animate().rotation(open ? 180f : 0f).setDuration(open ? 260 : 200)
                    .setInterpolator(open ? Motion.EASE_OUT : Motion.EASE_IN_OUT).start();
        } else {
            chevron.setRotation(open ? 180f : 0f);
        }
        animateHeight(list, open, motion);
        int count = 0;
        for (int i = 0; i < list.getChildCount(); i++) if (list.getChildAt(i).getTag() instanceof String) count++;
        describe(toggle, context, open, count, () -> flip(block, key, summary, host));
        toggle.announceForAccessibility(context.getString(open ? R.string.projects_answers_expanded : R.string.projects_answers_collapsed));
    }

    private static void showSummary(Context context, TextView line, boolean open, String summary) {
        line.setText(open ? context.getString(R.string.projects_answers_toggle) : summary);
        line.setTextColor(ContextCompat.getColor(context, open ? R.color.projects_bone_100 : R.color.projects_ash_300));
        try {
            line.setTypeface(ResourcesCompat.getFont(context, open ? R.font.figtree_semibold : R.font.figtree_regular));
        } catch (RuntimeException ignored) {
            // The theme's font.
        }
    }

    /** TalkBack: "Kimler geliyor, 5 yanıt", expanded or collapsed, with the action that opens or folds it. */
    private static void describe(View toggle, Context context, boolean open, int total, final Runnable flip) {
        toggle.setContentDescription(context.getString(R.string.projects_answers_toggle) + ", "
                + context.getResources().getQuantityString(R.plurals.projects_answers_count, total, total));
        ViewCompat.setStateDescription(toggle, context.getString(open ? R.string.projects_answers_expanded : R.string.projects_answers_collapsed));
        ViewCompat.replaceAccessibilityAction(toggle, open ? AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_COLLAPSE
                        : AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_EXPAND,
                context.getString(open ? R.string.projects_answers_collapse : R.string.projects_answers_expand), (v, args) -> {
                    flip.run();
                    return true;
                });
        ViewCompat.removeAccessibilityAction(toggle, (open ? AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_EXPAND
                : AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_COLLAPSE).getId());
    }

    /** The list grows or folds with the card (its height animates; the rows below follow each frame). */
    private static void animateHeight(final LinearLayout list, final boolean open, boolean motion) {
        Object running = list.getTag(R.id.projects_answers_anim);
        if (running instanceof Animator) ((Animator) running).cancel();
        final ViewGroup.LayoutParams lp = list.getLayoutParams();
        if (!motion) {
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            list.setLayoutParams(lp);
            list.setVisibility(open ? View.VISIBLE : View.GONE);
            list.setAlpha(1f);
            return;
        }
        int from = list.getVisibility() == View.VISIBLE ? list.getHeight() : 0;
        View parent = (View) list.getParent();
        int width = parent == null ? list.getWidth() : parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight();
        list.measure(View.MeasureSpec.makeMeasureSpec(Math.max(0, width), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int to = open ? list.getMeasuredHeight() : 0;
        list.setVisibility(View.VISIBLE);
        lp.height = from;
        list.setLayoutParams(lp);
        final float startAlpha = open && from == 0 ? 0f : list.getAlpha();
        ValueAnimator grow = ValueAnimator.ofInt(from, to);
        grow.setDuration(open ? Motion.ENTER_MS : Motion.EXIT_MS + 30);
        grow.setInterpolator(open ? Motion.EASE_OUT : Motion.EASE_IN_OUT);
        grow.addUpdateListener(a -> {
            lp.height = (int) a.getAnimatedValue();
            list.setLayoutParams(lp);
            float f = a.getAnimatedFraction();
            list.setAlpha(open ? startAlpha + (1f - startAlpha) * Math.min(1f, f * 1.4f) : startAlpha * (1f - f));
        });
        grow.addListener(new AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                list.setTag(R.id.projects_answers_anim, null);
                if (mCancelled) return;
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                list.setLayoutParams(lp);
                list.setAlpha(1f);
                if (!open) list.setVisibility(View.GONE);
            }
        });
        list.setTag(R.id.projects_answers_anim, grow);
        grow.start();
    }

    /** The groups' rows (rebuilt with each bind: a crew holds at most 30). */
    private static void fill(final LinearLayout list, final String key, List<AnswerPeople.Group> groups, String[] order, int[] labels,
                             final Host host) {
        list.removeAllViews();
        final Context context = list.getContext();
        SocialState state = SocialService.get(context).state();
        String me = state.myId();
        float dp = context.getResources().getDisplayMetrics().density;
        for (final AnswerPeople.Group group : groups) {
            int label = 0;
            for (int i = 0; i < order.length; i++) if (order[i].equals(group.answer)) label = labels[i];
            TextView heading = new TextView(context);
            heading.setText(context.getString(R.string.projects_answers_group, context.getString(label), group.count()));
            heading.setTextSize(12);
            heading.setTextColor(ContextCompat.getColor(context, R.color.projects_ash_300));
            heading.setPadding(Math.round(4 * dp), Math.round(10 * dp), 0, Math.round(2 * dp));
            try {
                heading.setTypeface(ResourcesCompat.getFont(context, R.font.figtree_semibold));
            } catch (RuntimeException ignored) {
                // The theme's font.
            }
            ViewCompat.setAccessibilityHeading(heading, true);
            list.addView(heading);
            final boolean all = host.showsAll(key, group.answer);
            for (final String id : group.shown(all)) {
                Profile p = state.profile(id);
                String name = id.equals(me) ? context.getString(R.string.projects_crew_you)
                        : p == null ? context.getString(R.string.projects_crew_someone) : p.name();
                list.addView(person(context, id, p, name, id.equals(me), host));
            }
            if (group.folds()) {
                TextView more = new TextView(context);
                more.setText(all ? context.getString(R.string.projects_answers_less)
                        : context.getResources().getQuantityString(R.plurals.projects_answers_more, group.hidden(), group.hidden()));
                more.setTextSize(13);
                more.setTextColor(ContextCompat.getColor(context, R.color.projects_gold_300));
                more.setGravity(Gravity.CENTER_VERTICAL);
                more.setMinHeight(Math.round(40 * dp));
                more.setPadding(Math.round(4 * dp), 0, Math.round(8 * dp), 0);
                more.setBackgroundResource(R.drawable.projects_row_ripple);
                try {
                    more.setTypeface(ResourcesCompat.getFont(context, R.font.figtree_semibold));
                } catch (RuntimeException ignored) {
                    // The theme's font.
                }
                Press.attach(more, Press.Style.ROW);
                more.setOnClickListener(v -> {
                    host.setShowsAll(key, group.answer, !all);
                    // The group grows or folds in place: the list's new height animates from the old one.
                    int before = list.getHeight();
                    fill(list, key, AnswerPeople.of(answersOf(groups), order, state.myId(), uid -> {
                        Profile q = state.profile(uid);
                        return q == null ? null : q.name();
                    }), order, labels, host);
                    regrow(list, before);
                });
                list.addView(more);
            }
        }
    }

    /** The answers again from the groups (the list is rebuilt from what it showed). */
    private static Map<String, String> answersOf(List<AnswerPeople.Group> groups) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (AnswerPeople.Group g : groups) for (String id : g.ids) out.put(id, g.answer);
        return out;
    }

    /** After "N kişi daha" or "Daha az göster": the list's height moves from {@code before} to its new one. */
    private static void regrow(final LinearLayout list, int before) {
        if (!Motion.enabled(list.getContext()) || !list.isAttachedToWindow()) return;
        View parent = (View) list.getParent();
        int width = parent == null ? list.getWidth() : parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight();
        list.measure(View.MeasureSpec.makeMeasureSpec(Math.max(0, width), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int after = list.getMeasuredHeight();
        final ViewGroup.LayoutParams lp = list.getLayoutParams();
        lp.height = before;
        list.setLayoutParams(lp);
        ValueAnimator grow = ValueAnimator.ofInt(before, after);
        grow.setDuration(Motion.ENTER_MS);
        grow.setInterpolator(Motion.EASE_OUT);
        grow.addUpdateListener(a -> {
            lp.height = (int) a.getAnimatedValue();
            list.setLayoutParams(lp);
        });
        grow.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                list.setLayoutParams(lp);
                list.setTag(R.id.projects_answers_anim, null);
            }
        });
        list.setTag(R.id.projects_answers_anim, grow);
        grow.start();
    }

    private static View person(Context context, final String id, Profile profile, String name, boolean me, final Host host) {
        float dp = context.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(context);
        row.setTag(id);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Math.round(44 * dp));
        row.setPaddingRelative(Math.round(4 * dp), Math.round(2 * dp), Math.round(8 * dp), Math.round(2 * dp));
        AvatarView avatar = new AvatarView(context);
        avatar.setProfile(profile);
        avatar.setSurfaceColor(ContextCompat.getColor(context, R.color.projects_obsidian_900));
        avatar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (!me) avatar.setDot(SocialUi.dot(SocialService.get(context).state().presence(id)));
        row.addView(avatar, new LinearLayout.LayoutParams(Math.round(28 * dp), Math.round(28 * dp)));
        TextView text = new TextView(context);
        text.setText(name);
        text.setTextSize(14);
        text.setMaxLines(1);
        text.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.setTextColor(ContextCompat.getColor(context, R.color.projects_bone_100));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginStart(Math.round(10 * dp));
        row.addView(text, lp);
        if (!me) {
            // The same menu as their row anywhere: a tap or a hold (a row here does nothing else).
            row.setBackgroundResource(R.drawable.projects_row_ripple);
            row.setClickable(true);
            row.setFocusable(true);
            Press.attach(row, Press.Style.ROW);
            row.setOnClickListener(v -> host.person(id, v));
            SocialMenuSheet.attach(row, name, v -> {
                host.person(id, v);
                return true;
            });
        } else {
            row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            row.setContentDescription(name);
        }
        return row;
    }
}
