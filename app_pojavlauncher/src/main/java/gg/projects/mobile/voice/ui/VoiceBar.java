package gg.projects.mobile.voice.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.fragment.app.FragmentActivity;

import net.kdt.pojavlaunch.R;

import java.util.List;

import gg.projects.mobile.mods.Motion;
import gg.projects.mobile.ui.ChamferDrawable;
import gg.projects.mobile.ui.Haptics;
import gg.projects.mobile.ui.Press;
import gg.projects.mobile.voice.VoiceAnswer;
import gg.projects.mobile.voice.VoiceHub;
import gg.projects.mobile.voice.VoiceLink;
import gg.projects.mobile.voice.VoiceRings;
import gg.projects.mobile.voice.VoiceState;

/**
 * Voice chat's bar at the bottom of the launcher's window (the desktop's title bar pill, where a phone's thumb is): while
 * the player is in a room, up to three faces with their speaking rings, the room's name and its state ("Aranıyor…",
 * "Yeniden bağlanıyor…", the call's time, "3 kişi"), the microphone and "Ayrıl"; a tap opens the room. While a call
 * rings and the app is in front, the same place holds the ring: the caller's face, "Kaan_07 arıyor", Reddet and Katıl.
 *
 * <p>It takes its own height in the window's column (the screen above makes room once, never covered: a chat's field
 * stays reachable). It comes in on transform and opacity (220 ms) and goes faster (160 ms); with animations off it just
 * appears. Nothing runs while it is hidden; the call's clock ticks once a second only while it shows a two-person call.
 */
public final class VoiceBar extends FrameLayout implements VoiceLink.Listener, VoiceHub.Listener {
    private enum Mode { NONE, RING, STAY }

    private final LinearLayout mRow;
    private final LinearLayout mFaces;
    private final TextView mTitle;
    private final TextView mLine;
    private final ImageButton mMic;
    private final ImageButton mLeave;
    private final TextView mDecline;
    private final TextView mJoin;
    private final float mDp;
    private Mode mMode = Mode.NONE;
    @Nullable private VoiceState mState;
    @Nullable private String mRingRoom;
    private boolean mAttached;
    private final Runnable mClock = new Runnable() {
        @Override
        public void run() {
            if (!mAttached || mMode != Mode.STAY || mState == null) return;
            mLine.setText(VoiceUi.roomLine(getContext(), mState, System.currentTimeMillis() / 1000));
            if (ticking()) postDelayed(this, 1_000);
        }
    };

    public VoiceBar(Context context) {
        this(context, null);
    }

    public VoiceBar(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mDp = context.getResources().getDisplayMetrics().density;
        setVisibility(GONE);
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(ContextCompat.getColor(context, R.color.projects_obsidian_900));
        setBackground(surface);
        // The hairline over it, as the top bar's under it.
        View line = new View(context);
        line.setBackgroundColor(ContextCompat.getColor(context, R.color.projects_hairline));
        addView(line, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(mDp))));

        mRow = new LinearLayout(context);
        mRow.setOrientation(LinearLayout.HORIZONTAL);
        mRow.setGravity(Gravity.CENTER_VERTICAL);
        mRow.setPaddingRelative(dp(12), 0, dp(6), 0);
        mRow.setBackgroundResource(R.drawable.projects_row_ripple);
        mRow.setOnClickListener(v -> openRoom());
        addView(mRow, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));

        mFaces = new LinearLayout(context);
        mFaces.setOrientation(LinearLayout.HORIZONTAL);
        mFaces.setGravity(Gravity.CENTER_VERTICAL);
        mRow.addView(mFaces, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout words = new LinearLayout(context);
        words.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wordsLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        wordsLp.setMarginStart(dp(10));
        mRow.addView(words, wordsLp);
        mTitle = text(context, 15, R.color.projects_bone_100, R.font.figtree_semibold);
        mLine = text(context, 13, R.color.projects_ash_400, R.font.figtree_regular);
        words.addView(mTitle);
        words.addView(mLine);

        mMic = iconButton(context);
        mMic.setOnClickListener(v -> {
            Haptics.toggle(v, true);
            VoiceLink.get(getContext()).toggleMic();
        });
        mRow.addView(mMic, new LinearLayout.LayoutParams(dp(48), dp(48)));
        mLeave = iconButton(context);
        mLeave.setImageResource(R.drawable.projects_ic_voice_leave);
        mLeave.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.projects_flare_400)));
        mLeave.setContentDescription(context.getString(R.string.projects_voice_leave));
        mLeave.setOnClickListener(v -> {
            Haptics.confirm(v);
            VoiceLink.get(getContext()).leave();
        });
        mRow.addView(mLeave, new LinearLayout.LayoutParams(dp(48), dp(48)));

        mDecline = chip(context, R.string.projects_voice_decline, false);
        mDecline.setOnClickListener(v -> {
            VoiceHub hub = VoiceHub.instance();
            if (hub != null && mRingRoom != null) hub.decline(mRingRoom);
        });
        mJoin = chip(context, R.string.projects_voice_join, true);
        mJoin.setOnClickListener(v -> {
            if (mRingRoom != null && getContext() instanceof FragmentActivity) {
                Haptics.confirm(v);
                VoiceUi.answer((FragmentActivity) getContext(), mRingRoom);
            }
        });
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        chipLp.setMarginStart(dp(8));
        mRow.addView(mDecline, chipLp);
        LinearLayout.LayoutParams joinLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        joinLp.setMarginStart(dp(8));
        joinLp.setMarginEnd(dp(6));
        mRow.addView(mJoin, joinLp);
    }

    // ---- life ----

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mAttached = true;
        VoiceLink.get(getContext()).attach(this);
        VoiceHub hub = VoiceUi.hub(getContext());
        if (hub != null) hub.addListener(this);
        update(false);
    }

    @Override
    protected void onDetachedFromWindow() {
        mAttached = false;
        removeCallbacks(mClock);
        VoiceLink link = VoiceLink.get(getContext());
        link.setMeters(false);
        link.detach(this);
        VoiceHub hub = VoiceHub.instance();
        if (hub != null) hub.removeListener(this);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        // The app went behind another (or came back): the speaking rings follow.
        if (mAttached) VoiceLink.get(getContext()).setMeters(mMode == Mode.STAY && visibility == VISIBLE);
    }

    @Override
    public void onVoiceState(@Nullable VoiceState state) {
        mState = state;
        update(true);
    }

    @Override
    public void onVoiceNotice(VoiceLink.Notice notice) {
        if (getContext() instanceof FragmentActivity) VoiceUi.notice((FragmentActivity) getContext(), notice);
    }

    @Override
    public void onVoiceRefused(VoiceAnswer answer) {
        VoiceUi.refused(getContext(), answer, null);
    }

    @Override
    public void onVoiceChanged() {
        update(true);
    }

    // ---- what it shows ----

    private void update(boolean animate) {
        if (!mAttached) return;
        VoiceHub hub = VoiceHub.instance();
        List<VoiceRings.Ring> rings = hub == null ? java.util.Collections.<VoiceRings.Ring>emptyList() : hub.rings().rings();
        Mode mode = !rings.isEmpty() ? Mode.RING : mState != null ? Mode.STAY : Mode.NONE;
        if (mode == Mode.RING) showRing(hub, rings.get(0));
        else if (mode == Mode.STAY) showStay(mState);
        if (mode != mMode) {
            Mode before = mMode;
            mMode = mode;
            reveal(mode != Mode.NONE, mode == Mode.NONE || (animate && before == Mode.NONE));
        }
        // Speaking rings are measured only while a room shows on screen.
        VoiceLink.get(getContext()).setMeters(mode == Mode.STAY && getWindowVisibility() == VISIBLE);
        removeCallbacks(mClock);
        if (ticking()) postDelayed(mClock, 1_000);
    }

    private boolean ticking() {
        return mMode == Mode.STAY && mState != null && mState.oneToOne() && mState.togetherSince != null && mState.live();
    }

    private void showRing(VoiceHub hub, VoiceRings.Ring ring) {
        mRingRoom = ring.room;
        Context c = getContext();
        mFaces.removeAllViews();
        VoiceFace face = new VoiceFace(c, 44);
        face.avatar().setProfile(VoiceFace.profileOf(c, ring.from, null));
        mFaces.addView(face, new LinearLayout.LayoutParams(dp(44), dp(44)));
        mTitle.setText(c.getString(R.string.projects_voice_ring_calling, hub.nameOr(ring.from)));
        mLine.setText(ring.with.isEmpty() ? c.getString(R.string.projects_voice_call_kind)
                : c.getString(R.string.projects_voice_ring_others, names(hub, ring.with)));
        mMic.setVisibility(GONE);
        mLeave.setVisibility(GONE);
        mDecline.setVisibility(VISIBLE);
        mJoin.setVisibility(VISIBLE);
        mRow.setClickable(false);
        setContentDescription(mTitle.getText());
    }

    private void showStay(VoiceState state) {
        mRingRoom = null;
        Context c = getContext();
        mFaces.removeAllViews();
        int shown = 0;
        // The friend being called stands in for the room until they come.
        if (state.calling != null && state.people.isEmpty()) {
            VoiceFace face = new VoiceFace(c, 36);
            face.avatar().setProfile(VoiceFace.profileOf(c, state.calling, null));
            face.setAlpha(0.55f);
            mFaces.addView(face, faceLp(0));
            shown = 1;
        }
        for (VoiceState.Person p : state.people) {
            if (shown == 3) break;
            VoiceFace face = new VoiceFace(c, 36);
            face.setPerson(c, p.id, p);
            face.setSpeaking(p.speaking);
            mFaces.addView(face, faceLp(shown));
            shown++;
        }
        if (shown == 0) {
            VoiceFace face = new VoiceFace(c, 36);
            face.avatar().setProfile(VoiceFace.profileOf(c, state.me.id, null));
            face.setSpeaking(state.me.talking);
            mFaces.addView(face, faceLp(0));
        }
        mTitle.setText(VoiceUi.roomTitle(c, state));
        mLine.setText(VoiceUi.roomLine(c, state, System.currentTimeMillis() / 1000));
        mLine.setTextColor(ContextCompat.getColor(c, state.reconnecting() ? R.color.projects_gold_300 : R.color.projects_ash_400));
        boolean off = state.me.mute || state.me.deaf || state.me.mutedByModerator || state.mic != null;
        mMic.setImageResource(off ? R.drawable.projects_ic_voice_mic_slash : R.drawable.projects_ic_voice_mic);
        mMic.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(c, off ? R.color.projects_flare_400 : R.color.projects_bone_100)));
        mMic.setContentDescription(c.getString(off ? R.string.projects_voice_unmute_mic : R.string.projects_voice_mute_mic));
        mMic.setEnabled(!state.me.mutedByModerator);
        mMic.setVisibility(VISIBLE);
        mLeave.setVisibility(VISIBLE);
        mLeave.setContentDescription(c.getString(state.calling != null && state.people.isEmpty()
                ? R.string.projects_voice_cancel_label : R.string.projects_voice_leave));
        mDecline.setVisibility(GONE);
        mJoin.setVisibility(GONE);
        mRow.setClickable(true);
        setContentDescription(c.getString(R.string.projects_voice_bar_label, mTitle.getText()));
    }

    private LinearLayout.LayoutParams faceLp(int index) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(36));
        // Overlapping a little, the desktop's pill.
        if (index > 0) lp.setMarginStart(-dp(10));
        return lp;
    }

    private void reveal(boolean show, boolean animate) {
        animate().cancel();
        if (show) {
            setVisibility(VISIBLE);
            if (!animate || !Motion.enabled(getContext())) {
                setAlpha(1f);
                setTranslationY(0f);
                return;
            }
            setAlpha(0f);
            setTranslationY(dp(12));
            animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(Motion.EASE_OUT).start();
            announceForAccessibility(mTitle.getText());
        } else {
            if (getVisibility() != VISIBLE) return;
            if (!Motion.enabled(getContext())) {
                setVisibility(GONE);
                return;
            }
            animate().alpha(0f).translationY(dp(8)).setDuration(160).setInterpolator(Motion.EASE_IN)
                    .withEndAction(() -> {
                        if (mMode == Mode.NONE) setVisibility(GONE);
                    }).start();
        }
    }

    private void openRoom() {
        if (mMode == Mode.STAY && getContext() instanceof FragmentActivity) VoiceSheet.show((FragmentActivity) getContext());
    }

    private String names(VoiceHub hub, List<String> ids) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < ids.size() && i < 2; i++) {
            if (i > 0) out.append(", ");
            out.append(hub.nameOr(ids.get(i)));
        }
        if (ids.size() > 2) return getContext().getString(R.string.projects_voice_names_more, out, ids.size() - 2);
        return out.toString();
    }

    // ---- pieces ----

    private int dp(int value) {
        return Math.round(value * mDp);
    }

    private TextView text(Context c, int sp, int color, int font) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(ContextCompat.getColor(c, color));
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        try {
            t.setTypeface(ResourcesCompat.getFont(c, font));
        } catch (RuntimeException ignored) {
            // The theme's font.
        }
        return t;
    }

    private ImageButton iconButton(Context c) {
        ImageButton b = new ImageButton(c);
        b.setBackgroundResource(R.drawable.projects_icon_button_ripple);
        b.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        b.setPadding(dp(12), dp(12), dp(12), dp(12));
        Press.attach(b, Press.Style.ICON);
        return b;
    }

    /** Reddet (quiet) and Katıl (the plate with the jade call mark: the one gold stays OYNA's). */
    private TextView chip(Context c, int text, boolean join) {
        TextView t = text(c, 15, join ? R.color.projects_bone_100 : R.color.projects_ash_300, R.font.figtree_semibold);
        t.setText(text);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), 0, dp(14), 0);
        if (join) {
            t.setBackground(ChamferDrawable.secondary(c));
            android.graphics.drawable.Drawable glyph = ContextCompat.getDrawable(c, R.drawable.projects_ic_voice_call);
            if (glyph != null) {
                glyph = glyph.mutate();
                glyph.setTint(ContextCompat.getColor(c, R.color.projects_jade_400));
                glyph.setBounds(0, 0, dp(18), dp(18));
                t.setCompoundDrawablesRelative(glyph, null, null, null);
                t.setCompoundDrawablePadding(dp(8));
            }
        } else {
            t.setBackgroundResource(R.drawable.projects_row_ripple);
        }
        t.setVisibility(GONE);
        Press.attach(t, Press.Style.BUTTON);
        return t;
    }
}
