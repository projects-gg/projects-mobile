package gg.projects.mobile.social.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.os.SystemClock;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;

import com.google.gson.JsonObject;

import net.kdt.pojavlaunch.R;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import gg.projects.mobile.mods.Motion;
import gg.projects.mobile.social.MarkCatalog;
import gg.projects.mobile.social.SocialModel.Me;
import gg.projects.mobile.social.SocialProblem;
import gg.projects.mobile.social.SocialService;
import gg.projects.mobile.ui.Haptics;

/**
 * BADGES: "Rozetler" in the friends settings (the launcher's BadgesSection; docs: badges.md "BADGES-UI için", "Kendi
 * rozetleri"): every badge the player holds that this version draws, each a switch named for it with its drawing; one
 * turned off leaves their profile everywhere (the service keeps it, on brings it back) and its drawing shows at 45 %.
 * One line under them says so. Not there while the player holds none, or the service cannot hide them
 * ({@code me.marksHidden} missing).
 *
 * <p>A switch turns at the tap and its drawing fades with it (160 ms, at once with animations off); the whole hidden
 * list goes ({@link MarkCatalog#hiddenToSend}), one change at a time: a switch turned while one is on its way goes with
 * the next, so answers never come out of order. The answer that agrees with the switches redraws nothing (the page's key
 * stays, so no motion is cut); a refusal turns the switches back and says why.
 */
final class OwnMarks {
    /** A badge kept off the profile (design.md: 45 %). */
    private static final int OFF_ALPHA = 115;
    private static final long FADE_MS = 160;

    /** A change taken as lost when no answer came in this long (a session that ended answers nothing). */
    private static final long SENDING_MS = 30_000;

    /** Switches turned whose answer has not come: badge key to shown. */
    private final Map<String, Boolean> mPending = new HashMap<>();
    /** A change is on its way (one at a time, so a later answer never overtakes an earlier one). */
    private boolean mSending;
    private long mSentAt;
    /** A switch turned while one was on its way: the list goes again when it is answered. */
    private boolean mResend;
    /** What the switches on screen show (the catalogue's revision and each badge's state); "" without the section. */
    private String mOnScreen = "";
    /** Bumps when the switches must be drawn again: the page's key for this section. */
    private int mDrawing;

    /** The player's badges this version lists, in the catalogue's order (empty: no section). */
    private static List<MarkCatalog.Own> own(SocialService service, @Nullable Me me) {
        if (me == null || me.profile.marksHidden == null) return Collections.emptyList();
        return service.marks().own(me.profile.marks, me.profile.marksHidden);
    }

    /** What the switches would show now: the profile, a switch turned and not answered yet over it. */
    private String wanted(SocialService service, List<MarkCatalog.Own> own) {
        if (own.isEmpty()) return "";
        StringBuilder out = new StringBuilder().append(service.marks().rev).append(':');
        for (MarkCatalog.Own o : own) {
            Boolean turned = mPending.get(o.def.key);
            boolean shown = turned != null ? turned : o.shown;
            out.append(o.def.key).append(shown ? '+' : '-');
        }
        return out.toString();
    }

    /**
     * The page's key for this section: it changes only when the switches must be drawn again, not when an answer agrees
     * with what they show. Called once per redraw decision; a changed key is drawn.
     */
    String key(SocialService service, @Nullable Me me) {
        List<MarkCatalog.Own> own = own(service, me);
        MarkCatalog.settle(mPending, own);
        String wanted = wanted(service, own);
        if (!wanted.equals(mOnScreen)) {
            mOnScreen = wanted;
            mDrawing++;
        }
        return "marks" + mDrawing;
    }

    /** Whether the section is there (its heading is the page's: {@code projects_marks_title}). */
    boolean shows(SocialService service, @Nullable Me me) {
        return !own(service, me).isEmpty();
    }

    /** A switch per badge, then the note; {@code redraw} draws the page again (a refusal turned a switch back). */
    void addRows(LinearLayout list, final SocialService service, final Runnable redraw) {
        final Context context = list.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        boolean turkish = ProfileMarks.turkish(context);
        for (MarkCatalog.Own o : own(service, service.state().me())) {
            final String key = o.def.key;
            Boolean turned = mPending.get(key);
            boolean shown = turned != null ? turned : o.shown;
            final SwitchCompat row = new SwitchCompat(context);
            row.setText(o.def.name(turkish));
            row.setTextSize(16);
            row.setTextColor(ContextCompat.getColor(context, R.color.projects_bone_100));
            row.setMinHeight(Math.round(56 * density));
            row.setPadding(Math.round(8 * density), 0, Math.round(8 * density), 0);
            // The drawing before the name, decorative (TalkBack says the name and the switch's state).
            final MarkDrawable mark = MarkDrawable.of(o.def, 16, density);
            if (mark != null) {
                mark.setAlpha(shown ? 255 : OFF_ALPHA);
                row.setCompoundDrawablesRelative(mark, null, null, null);
                row.setCompoundDrawablePadding(Math.round(12 * density));
            }
            row.setChecked(shown);
            row.setOnCheckedChangeListener((button, on) -> {
                Haptics.toggle(button, on);
                if (mark != null) fade(row, mark, on);
                turn(context, service, key, on, redraw);
            });
            list.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        TextView note = new TextView(context);
        note.setText(R.string.projects_marks_note);
        note.setTextSize(14);
        note.setTextColor(ContextCompat.getColor(context, R.color.projects_ash_400));
        note.setPadding(Math.round(8 * density), Math.round(6 * density), Math.round(8 * density), Math.round(6 * density));
        list.addView(note);
    }

    /** A switch turned: what the switches show now goes as the whole hidden list, one change at a time. */
    private void turn(Context context, SocialService service, String key, boolean on, Runnable redraw) {
        mPending.put(key, on);
        // The switches show it already: the answer that agrees redraws nothing.
        mOnScreen = wanted(service, own(service, service.state().me()));
        if (mSending && SystemClock.elapsedRealtime() - mSentAt < SENDING_MS) {
            // The list goes again when the one on its way is answered: answers never come out of order.
            mResend = true;
            return;
        }
        send(context, service, redraw);
    }

    /** Sends the hidden list as the switches show it now (a turned switch over the profile). */
    private void send(final Context context, final SocialService service, final Runnable redraw) {
        Me me = service.state().me();
        List<String> hidden = MarkCatalog.hiddenToSend(own(service, me), mPending, me == null ? null : me.profile.marksHidden);
        mSending = true;
        mResend = false;
        mSentAt = SystemClock.elapsedRealtime();
        service.setMarksHidden(hidden, new SocialService.Result<JsonObject>() {
            @Override
            public void ok(JsonObject value) {
                // The profile in the answer is applied (and the page redrawn if it says otherwise) before this.
                mSending = false;
                if (mResend) send(context, service, redraw);
            }

            @Override
            public void failed(SocialProblem problem) {
                mSending = false;
                mResend = false;
                // Every switch not answered turns back to what the profile says.
                mPending.clear();
                redraw.run();
                SocialUi.toastProblem(context, problem, null);
            }
        });
    }

    /** The drawing goes to 45 % with its switch off, back with it on: 160 ms from where it is, at once without motion. */
    private static void fade(SwitchCompat row, final MarkDrawable mark, boolean on) {
        int to = on ? 255 : OFF_ALPHA;
        Object running = row.getTag();
        if (running instanceof ValueAnimator) ((ValueAnimator) running).cancel();
        if (!Motion.enabled(row.getContext()) || !row.isAttachedToWindow()) {
            mark.setAlpha(to);
            return;
        }
        ValueAnimator fade = ValueAnimator.ofInt(mark.getAlpha(), to);
        fade.setDuration(FADE_MS);
        fade.setInterpolator(Motion.EASE_OUT);
        fade.addUpdateListener(a -> mark.setAlpha((Integer) a.getAnimatedValue()));
        row.setTag(fade);
        fade.start();
    }
}
