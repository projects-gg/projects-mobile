package gg.projects.mobile.voice.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.text.InputFilter;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.fragment.app.FragmentActivity;

import net.kdt.pojavlaunch.R;

import java.util.ArrayList;
import java.util.List;

import gg.projects.mobile.social.SocialModel;
import gg.projects.mobile.social.SocialService;
import gg.projects.mobile.social.SocialState;
import gg.projects.mobile.social.ui.BottomPanel;
import gg.projects.mobile.social.ui.CrewViews;
import gg.projects.mobile.social.ui.SocialUi;
import gg.projects.mobile.ui.ChamferDrawable;
import gg.projects.mobile.ui.Haptics;
import gg.projects.mobile.ui.Press;
import gg.projects.mobile.voice.VoiceAnswer;
import gg.projects.mobile.voice.VoiceHub;
import gg.projects.mobile.voice.VoiceLink;
import gg.projects.mobile.voice.VoiceRings;
import gg.projects.mobile.voice.VoiceState;
import gg.projects.mobile.voice.VoiceWire;

/**
 * The room (the desktop's room panel, as a sheet that rises from the bottom): its name and state, "Davet et", the
 * people in it (the player first) with their speaking rings, "Lider"/"Moderatör" in small words and their marks (the
 * microphone off, deafened, muted for everyone, a weak connection), the player's own invites that ring with "İptal", and
 * at the bottom Mikrofon, Sağırlaştır and Ayrıl. A tap on someone opens their sheet: "Ses seviyesi" (0–200 %), "Benim için
 * sustur", and what the player's role may do to them (Herkes için sustur, Odadan çıkar with "Geri al", Taşı, Moderatör
 * yap). The leader renames and closes the room from "⋯".
 *
 * <p>M051 (SHARE-LIVE): "YAYINDA" after the name of whoever shares their screen (the desktop's room panel). The phone
 * cannot watch a stream; in place of the desktop's hover card, the person's sheet starts with their stream's last small
 * picture (asked only while that sheet is open, again when the room's picture time moves) and "Oyun, 2 kişi izliyor".
 */
public final class VoiceSheet implements VoiceLink.Listener {
    private final FragmentActivity mActivity;
    private final LinearLayout mRoot;
    private final TextView mTitle;
    private final TextView mLine;
    private final ImageButton mInvite;
    private final ImageButton mMore;
    private final LinearLayout mList;
    private final TextView mMic;
    private final TextView mDeaf;
    private final TextView mLeave;
    private final float mDp;
    @Nullable private VoiceState mState;
    private BottomPanel mPanel;
    /** The open person sheet's stream picture (M051), or null. */
    @Nullable private Picture mPicture;

    public static void show(FragmentActivity activity) {
        new VoiceSheet(activity).open();
    }

    private VoiceSheet(FragmentActivity activity) {
        mActivity = activity;
        Context c = activity;
        mDp = c.getResources().getDisplayMetrics().density;
        mRoot = new LinearLayout(c);
        mRoot.setOrientation(LinearLayout.VERTICAL);
        mRoot.setPadding(dp(16), 0, dp(16), dp(16));

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout words = new LinearLayout(c);
        words.setOrientation(LinearLayout.VERTICAL);
        head.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mTitle = text(c, 18, R.color.projects_bone_100, R.font.tektur_semibold);
        mLine = text(c, 13, R.color.projects_ash_400, R.font.figtree_regular);
        words.addView(mTitle);
        words.addView(mLine);
        mInvite = icon(c, R.drawable.projects_ic_voice_invite, R.string.projects_voice_invite);
        mInvite.setOnClickListener(v -> invite());
        head.addView(mInvite, new LinearLayout.LayoutParams(dp(48), dp(48)));
        mMore = icon(c, R.drawable.projects_ic_dots, R.string.projects_voice_rename);
        mMore.setOnClickListener(v -> more());
        head.addView(mMore, new LinearLayout.LayoutParams(dp(48), dp(48)));
        mRoot.addView(head);

        ScrollView scroll = new CappedScroll(c, 0.5f);
        mList = new LinearLayout(c);
        mList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(mList);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        scrollLp.topMargin = dp(8);
        mRoot.addView(scroll, scrollLp);

        LinearLayout controls = new LinearLayout(c);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams controlsLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64));
        controlsLp.topMargin = dp(12);
        mRoot.addView(controls, controlsLp);
        mMic = control(c);
        mMic.setOnClickListener(v -> {
            Haptics.toggle(v, true);
            VoiceLink.get(mActivity).toggleMic();
        });
        mDeaf = control(c);
        mDeaf.setOnClickListener(v -> {
            Haptics.toggle(v, true);
            VoiceLink.get(mActivity).setDeaf(mState == null || !mState.me.deaf);
        });
        mLeave = control(c);
        mLeave.setOnClickListener(v -> {
            Haptics.confirm(v);
            VoiceLink.get(mActivity).leave();
            if (mPanel != null) mPanel.dismiss();
        });
        LinearLayout.LayoutParams wide = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.3f);
        LinearLayout.LayoutParams one = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        one.setMarginStart(dp(8));
        LinearLayout.LayoutParams last = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        last.setMarginStart(dp(8));
        controls.addView(mMic, wide);
        controls.addView(mDeaf, one);
        controls.addView(mLeave, last);
    }

    private void open() {
        VoiceLink link = VoiceLink.get(mActivity);
        mState = link.state();
        if (mState == null) return;
        render();
        // The speaking rings are the call bar's to ask for (it shows under the sheet the whole time).
        mPanel = BottomPanel.of(mActivity, mRoot, 0.85f).onClosed(() -> link.detach(this));
        link.attach(this);
        mPanel.show();
    }

    @Override
    public void onVoiceState(@Nullable VoiceState state) {
        mState = state;
        if (state == null) {
            if (mPanel != null) mPanel.dismiss();
            return;
        }
        render();
        if (mPicture != null) mPicture.update(state);
    }

    @Override
    public void onVoiceNotice(VoiceLink.Notice notice) {
        // The call bar says it (it is attached while the app shows).
    }

    @Override
    public void onVoiceRefused(VoiceAnswer answer) {
        // The call bar says it.
    }

    // ---- drawing ----

    private void render() {
        VoiceState s = mState;
        if (s == null) return;
        Context c = mActivity;
        long now = System.currentTimeMillis() / 1000;
        mTitle.setText(VoiceUi.roomTitle(c, s));
        mLine.setText(s.oneToOne() && s.calling == null ? c.getString(R.string.projects_voice_call_kind) : VoiceUi.roomLine(c, s, now));
        mLine.setTextColor(ContextCompat.getColor(c, s.reconnecting() ? R.color.projects_gold_300 : R.color.projects_ash_400));
        mInvite.setVisibility(s.live() && s.calling == null ? View.VISIBLE : View.GONE);
        mMore.setVisibility(s.canRename || s.canClose ? View.VISIBLE : View.GONE);
        mMore.setContentDescription(c.getString(s.canRename ? R.string.projects_voice_rename : R.string.projects_voice_close));

        mList.removeAllViews();
        mList.addView(meRow(s));
        for (VoiceState.Person p : s.people) mList.addView(personRow(s, p));
        if (s.calling != null && s.people.isEmpty()) mList.addView(ringingRow(s.calling, true));
        for (VoiceState.OutRing ring : s.ringing) {
            if (ring.to.equals(s.calling) && s.people.isEmpty()) continue;
            mList.addView(ringingRow(ring.to, false));
        }

        boolean micOff = s.me.mute || s.me.deaf || s.me.mutedByModerator || s.mic != null;
        setControl(mMic, micOff ? R.drawable.projects_ic_voice_mic_slash : R.drawable.projects_ic_voice_mic,
                micOff ? R.string.projects_voice_unmute_mic : R.string.projects_voice_mute_mic,
                micOff ? R.color.projects_flare_400 : R.color.projects_bone_100);
        mMic.setEnabled(!s.me.mutedByModerator);
        mMic.setAlpha(s.me.mutedByModerator ? 0.5f : 1f);
        setControl(mDeaf, s.me.deaf ? R.drawable.projects_ic_voice_deaf : R.drawable.projects_ic_voice_headphones,
                s.me.deaf ? R.string.projects_voice_deafen_off : R.string.projects_voice_deafen_on,
                s.me.deaf ? R.color.projects_flare_400 : R.color.projects_bone_100);
        boolean cancel = s.calling != null && s.people.isEmpty();
        setControl(mLeave, R.drawable.projects_ic_voice_leave, cancel ? R.string.projects_voice_cancel : R.string.projects_voice_leave,
                R.color.projects_flare_400);
    }

    private View meRow(VoiceState s) {
        Context c = mActivity;
        String name = c.getString(R.string.projects_voice_you);
        SocialModel.Profile mine = SocialService.get(c).state().profile(s.me.id);
        if (mine != null) name = mine.name();
        LinearLayout row = row(c);
        VoiceFace face = new VoiceFace(c, 44);
        face.avatar().setProfile(mine);
        face.setSpeaking(s.me.talking);
        row.addView(face, new LinearLayout.LayoutParams(dp(44), dp(44)));
        row.addView(names(c, name, roleWord(c, s.me.role, true), s.me.sharing), weight());
        if (s.me.sharing) row.setContentDescription(LiveTag.spoken(c, name, true));
        if (s.me.mutedByModerator) row.addView(mark(c, R.drawable.projects_ic_voice_mic_slash, R.color.projects_flare_400,
                R.string.projects_voice_muted_for_all));
        else if (s.me.mute || s.mic != null) row.addView(mark(c, R.drawable.projects_ic_voice_mic_slash, R.color.projects_ash_400,
                R.string.projects_voice_mic_off));
        if (s.me.deaf) row.addView(mark(c, R.drawable.projects_ic_voice_deaf, R.color.projects_ash_400, R.string.projects_voice_deafened));
        if (s.me.quality > 0) row.addView(mark(c, R.drawable.projects_ic_voice_weak, R.color.projects_gold_300, R.string.projects_voice_weak));
        return row;
    }

    private View personRow(final VoiceState s, final VoiceState.Person p) {
        Context c = mActivity;
        LinearLayout row = row(c);
        VoiceFace face = new VoiceFace(c, 44);
        face.setPerson(c, p.id, p);
        face.setSpeaking(p.speaking);
        row.addView(face, new LinearLayout.LayoutParams(dp(44), dp(44)));
        String name = VoiceUi.nameOf(c, p);
        row.addView(names(c, name, roleWord(c, p.role, false), p.sharing), weight());
        if (p.mutedByModerator) row.addView(mark(c, R.drawable.projects_ic_voice_mic_slash, R.color.projects_flare_400,
                R.string.projects_voice_muted_for_all));
        else if (p.mute) row.addView(mark(c, R.drawable.projects_ic_voice_mic_slash, R.color.projects_ash_400, R.string.projects_voice_mic_off));
        if (p.deaf) row.addView(mark(c, R.drawable.projects_ic_voice_deaf, R.color.projects_ash_400, R.string.projects_voice_deafened));
        if (p.mutedForMe) row.addView(mark(c, R.drawable.projects_ic_voice_speaker, R.color.projects_ash_400,
                R.string.projects_voice_muted_for_me));
        if (p.quality > 0) row.addView(mark(c, R.drawable.projects_ic_voice_weak, R.color.projects_gold_300, R.string.projects_voice_their_weak));
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackgroundResource(R.drawable.projects_row_ripple);
        row.setOnClickListener(v -> person(s, p.id, name));
        row.setContentDescription(LiveTag.spoken(c, name, p.sharing)
                + (p.speaking ? ", " + c.getString(R.string.projects_voice_speaking) : ""));
        Press.attach(row, Press.Style.ROW);
        return row;
    }

    private View ringingRow(final String to, boolean call) {
        Context c = mActivity;
        LinearLayout row = row(c);
        VoiceFace face = new VoiceFace(c, 44);
        face.avatar().setProfile(VoiceFace.profileOf(c, to, null));
        face.setAlpha(0.55f);
        row.addView(face, new LinearLayout.LayoutParams(dp(44), dp(44)));
        row.addView(names(c, VoiceUi.nameOf(c, to), c.getString(R.string.projects_voice_ringing), false), weight());
        if (!call) {
            TextView cancel = text(c, 14, R.color.projects_ash_300, R.font.figtree_semibold);
            cancel.setText(R.string.projects_voice_cancel);
            cancel.setGravity(Gravity.CENTER);
            cancel.setPadding(dp(12), 0, dp(12), 0);
            cancel.setMinHeight(dp(44));
            cancel.setBackgroundResource(R.drawable.projects_row_ripple);
            cancel.setOnClickListener(v -> VoiceLink.get(mActivity).cancelInvite(to, a -> VoiceUi.refused(mActivity, a, null)));
            Press.attach(cancel, Press.Style.BUTTON);
            row.addView(cancel);
        }
        return row;
    }

    // ---- a person ----

    private void person(final VoiceState s, final String id, final String name) {
        final Context c = mActivity;
        final VoiceState.Person p = s.person(id);
        if (p == null) return;
        LinearLayout content = new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), 0, dp(16), dp(12));
        LinearLayout head = row(c);
        VoiceFace face = new VoiceFace(c, 48);
        face.setPerson(c, id, p);
        head.addView(face, new LinearLayout.LayoutParams(dp(48), dp(48)));
        head.addView(names(c, name, roleWord(c, p.role, false), p.sharing), weight());
        content.addView(head);
        final BottomPanel[] panel = new BottomPanel[1];
        // M051: their stream's last picture, while they share.
        final Picture picture = new Picture(c, s.room, id, name);
        content.addView(picture.view);
        picture.update(s);
        mPicture = picture;

        // "Ses seviyesi": the value beside it (no notch), 0–200 % in steps of 10.
        LinearLayout volume = new LinearLayout(c);
        volume.setOrientation(LinearLayout.HORIZONTAL);
        volume.setGravity(Gravity.CENTER_VERTICAL);
        volume.setMinimumHeight(dp(52));
        TextView label = text(c, 15, R.color.projects_bone_100, R.font.figtree_semibold);
        label.setText(R.string.projects_voice_volume);
        volume.addView(label);
        SeekBar bar = new SeekBar(c);
        bar.setMax(s.volumeMax / 10);
        bar.setProgress(Math.min(s.volumeMax, p.volume) / 10);
        bar.setContentDescription(c.getString(R.string.projects_voice_volume));
        int bone = ContextCompat.getColor(c, R.color.projects_bone_100);
        bar.setProgressTintList(ColorStateList.valueOf(bone));
        bar.setThumbTintList(ColorStateList.valueOf(bone));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        barLp.setMarginStart(dp(8));
        volume.addView(bar, barLp);
        final TextView value = text(c, 14, R.color.projects_ash_300, R.font.tektur_semibold);
        value.setText(p.volume + "%");
        value.setMinWidth(dp(48));
        value.setGravity(Gravity.END);
        volume.addView(value);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                value.setText(progress * 10 + "%");
                if (fromUser) {
                    Haptics.tick(seekBar);
                    VoiceLink.get(mActivity).setVolume(id, progress * 10);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        content.addView(volume);

        content.addView(CrewViews.actionRow(c, R.drawable.projects_ic_voice_speaker,
                c.getString(p.mutedForMe ? R.string.projects_voice_muted_for_me : R.string.projects_voice_mute_for_me),
                R.color.projects_bone_100, v -> {
                    VoiceLink.get(mActivity).setMutedForMe(id, !p.mutedForMe);
                    panel[0].dismiss();
                }));
        if (p.canMute || p.canKick || p.canMove || p.canModerator) content.addView(CrewViews.divider(c));
        if (p.canMute) {
            final boolean muted = p.mutedByModerator;
            content.addView(CrewViews.actionRow(c, R.drawable.projects_ic_voice_mic_slash,
                    c.getString(muted ? R.string.projects_voice_unmute_for_all : R.string.projects_voice_mute_for_all),
                    R.color.projects_bone_100, v -> {
                        panel[0].dismiss();
                        mod(muted ? VoiceWire.ModAct.UNMUTE : VoiceWire.ModAct.MUTE, id, null, name, null);
                    }));
        }
        if (p.canMove) {
            final List<VoiceRings.CrewRoom> rooms = otherRooms(s);
            if (!rooms.isEmpty()) {
                content.addView(CrewViews.actionRow(c, R.drawable.projects_ic_arrows_left_right, c.getString(R.string.projects_voice_move),
                        R.color.projects_bone_100, v -> {
                            panel[0].dismiss();
                            move(id, name, rooms);
                        }));
            }
        }
        if (p.canModerator) {
            final boolean mod = VoiceState.MODERATOR.equals(p.role);
            content.addView(CrewViews.actionRow(c, R.drawable.projects_ic_crown_fill,
                    c.getString(mod ? R.string.projects_voice_unmake_mod : R.string.projects_voice_make_mod), R.color.projects_bone_100,
                    v -> {
                        panel[0].dismiss();
                        mod(mod ? VoiceWire.ModAct.UNMOD : VoiceWire.ModAct.MOD, id, null, name, null);
                    }));
        }
        if (p.canKick) {
            content.addView(CrewViews.actionRow(c, R.drawable.projects_ic_user_minus, c.getString(R.string.projects_voice_kick),
                    R.color.projects_flare_400, v -> {
                        panel[0].dismiss();
                        // No question: 5 s "Geri al" in the line (the desktop's rule).
                        mod(VoiceWire.ModAct.KICK, id, null, name, () -> SocialUi.toastAction(mActivity,
                                mActivity.getString(R.string.projects_voice_kicked_them, name), mActivity.getString(R.string.projects_voice_undo),
                                () -> mod(VoiceWire.ModAct.UNKICK, id, null, name, null)));
                    }));
        }
        panel[0] = BottomPanel.of(c, content, 0.7f).onClosed(() -> {
            if (mPicture == picture) mPicture = null;
            picture.closed = true;
        });
        panel[0].show();
    }

    /**
     * M051: the person sheet's stream picture (16:9 on a quiet plate, the share's kind and watchers under it); gone while
     * they do not share. Asks the service only for a picture time it has not shown, never polls.
     */
    private final class Picture {
        final LinearLayout view;
        private final String room;
        private final String id;
        private final android.widget.ImageView image;
        private final TextView line;
        private long shownTh = -1;
        boolean closed;

        Picture(Context c, String room, String id, String name) {
            this.room = room;
            this.id = id;
            view = new LinearLayout(c);
            view.setOrientation(LinearLayout.VERTICAL);
            view.setPadding(0, dp(8), 0, dp(8));
            image = new android.widget.ImageView(c) {
                @Override
                protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                    int width = MeasureSpec.getSize(widthMeasureSpec);
                    setMeasuredDimension(width, width * 9 / 16);
                }
            };
            image.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            android.graphics.drawable.GradientDrawable plate = new android.graphics.drawable.GradientDrawable();
            plate.setCornerRadius(dp(8));
            plate.setColor(ContextCompat.getColor(c, R.color.projects_obsidian_800));
            image.setBackground(plate);
            image.setClipToOutline(true);
            image.setContentDescription(c.getString(R.string.projects_voice_share_picture, name));
            view.addView(image, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            line = text(c, 13, R.color.projects_ash_400, R.font.figtree_regular);
            line.setPadding(dp(4), dp(6), dp(4), 0);
            view.addView(line);
        }

        void update(VoiceState s) {
            if (closed) return;
            VoiceState.Person p = s.person(id);
            boolean sharing = p != null && p.sharing && room.equals(s.room);
            view.setVisibility(sharing ? View.VISIBLE : View.GONE);
            if (!sharing) {
                shownTh = -1;
                image.setImageDrawable(null);
                return;
            }
            Context c = mActivity;
            String kind = c.getString("game".equals(p.shareSrc) ? R.string.projects_voice_share_game
                    : "window".equals(p.shareSrc) ? R.string.projects_voice_share_window : R.string.projects_voice_share_screen);
            line.setText(p.shareWatchers > 0
                    ? c.getResources().getQuantityString(R.plurals.projects_voice_share_watchers, p.shareWatchers, kind, p.shareWatchers)
                    : c.getString(R.string.projects_voice_share_nobody, kind));
            final long th = p.shareTh;
            if (th == shownTh) return;
            shownTh = th;
            VoiceHub hub = VoiceHub.instance();
            if (hub == null || th <= 0) return;
            hub.thumb(room, id, p.shareN, th, picture -> {
                if (closed || shownTh != th || picture == null) return;
                boolean first = image.getDrawable() == null;
                image.setImageBitmap(picture);
                // A new picture fades in (200 ms); nothing moves with animations off.
                if (first && gg.projects.mobile.mods.Motion.enabled(mActivity)) {
                    image.setImageAlpha(0);
                    android.animation.ValueAnimator fade = android.animation.ValueAnimator.ofInt(0, 255);
                    fade.setDuration(200);
                    fade.addUpdateListener(a -> image.setImageAlpha((Integer) a.getAnimatedValue()));
                    fade.start();
                }
            });
        }
    }

    private List<VoiceRings.CrewRoom> otherRooms(VoiceState s) {
        List<VoiceRings.CrewRoom> out = new ArrayList<>();
        VoiceHub hub = VoiceHub.instance();
        if (hub == null || s.crew == null) return out;
        for (VoiceRings.CrewRoom r : hub.rings().crewRooms(s.crew)) if (!r.id.equals(s.room)) out.add(r);
        return out;
    }

    private void move(final String id, final String name, List<VoiceRings.CrewRoom> rooms) {
        Context c = mActivity;
        LinearLayout content = new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), 0, dp(16), dp(12));
        TextView title = text(c, 18, R.color.projects_bone_100, R.font.tektur_semibold);
        title.setText(R.string.projects_voice_move);
        title.setPadding(dp(4), 0, 0, dp(8));
        content.addView(title);
        final BottomPanel[] panel = new BottomPanel[1];
        for (final VoiceRings.CrewRoom room : rooms) {
            content.addView(CrewViews.actionRow(c, R.drawable.projects_ic_voice_headphones, VoiceHub.roomName(c, room.name, room.general()),
                    R.color.projects_bone_100, v -> {
                        panel[0].dismiss();
                        mod(VoiceWire.ModAct.MOVE, id, room.id, name, () -> SocialUi.toast(mActivity,
                                mActivity.getString(R.string.projects_voice_moved_them, name)));
                    }));
        }
        panel[0] = BottomPanel.of(c, content, 0.7f);
        panel[0].show();
    }

    private void mod(VoiceWire.ModAct act, @Nullable String person, @Nullable String room, @Nullable String name,
                     @Nullable Runnable onDone) {
        VoiceLink.get(mActivity).moderate(act, person, room, act == VoiceWire.ModAct.NAME ? name : null, answer -> {
            if (answer.ok()) {
                if (onDone != null) onDone.run();
            } else {
                VoiceUi.refused(mActivity, answer, null);
            }
        });
    }

    // ---- the leader's ----

    private void more() {
        final VoiceState s = mState;
        if (s == null) return;
        Context c = mActivity;
        LinearLayout content = new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), 0, dp(16), dp(12));
        final BottomPanel[] panel = new BottomPanel[1];
        if (s.canRename) {
            content.addView(CrewViews.actionRow(c, R.drawable.projects_ic_pencil, c.getString(R.string.projects_voice_rename),
                    R.color.projects_bone_100, v -> {
                        panel[0].dismiss();
                        rename(s);
                    }));
        }
        if (s.canClose) {
            content.addView(CrewViews.actionRow(c, R.drawable.projects_ic_x, c.getString(R.string.projects_voice_close),
                    R.color.projects_flare_400, v -> {
                        panel[0].dismiss();
                        SocialUi.confirmDanger(mActivity, mActivity.getString(R.string.projects_voice_close),
                                mActivity.getString(R.string.projects_voice_close_ask), mActivity.getString(R.string.projects_voice_close),
                                () -> mod(VoiceWire.ModAct.CLOSE, null, null, null, null));
                    }));
        }
        panel[0] = BottomPanel.of(c, content, 0.5f);
        panel[0].show();
    }

    private void rename(VoiceState s) {
        Context c = mActivity;
        final EditText field = new EditText(c);
        field.setSingleLine(true);
        field.setHint(R.string.projects_voice_name_hint);
        field.setFilters(new InputFilter[] {new InputFilter.LengthFilter(24)});
        field.setText(s.name == null ? "" : s.name);
        field.setSelection(field.getText().length());
        field.setTextColor(ContextCompat.getColor(c, R.color.projects_bone_100));
        LinearLayout box = new LinearLayout(c);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(field, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(c)
                .setTitle(R.string.projects_voice_rename)
                .setView(box)
                .setNegativeButton(R.string.projects_social_cancel, null)
                .setPositiveButton(R.string.projects_voice_save, (d, w) -> {
                    String name = field.getText().toString().trim();
                    int chars = name.codePointCount(0, name.length());
                    if (chars < 3 || chars > 24) {
                        VoiceUi.refused(mActivity, VoiceAnswer.refused(VoiceAnswer.Refusal.INVALID_NAME), null);
                        return;
                    }
                    mod(VoiceWire.ModAct.NAME, null, null, name, null);
                })
                .show();
    }

    // ---- invites ----

    private void invite() {
        final VoiceState s = mState;
        if (s == null) return;
        Context c = mActivity;
        SocialService service = SocialService.get(c);
        SocialState state = service.state();
        VoiceHub hub = VoiceHub.instance();
        List<SocialModel.Profile> candidates = new ArrayList<>();
        if (s.crewRoom && s.crew != null) {
            SocialModel.Crew crew = state.crew(s.crew);
            if (crew != null) {
                for (SocialModel.Member m : crew.team.members) {
                    String id = m.profile.id;
                    if (id.equals(s.me.id) || s.person(id) != null || ringing(s, id)) continue;
                    if (state.presence(id).online()) candidates.add(m.profile);
                }
            }
        } else {
            for (SocialModel.Friend f : state.friends()) {
                String id = f.profile.id;
                if (s.person(id) != null || ringing(s, id)) continue;
                if (hub != null && hub.callable(id)) candidates.add(f.profile);
            }
        }
        LinearLayout content = new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), 0, dp(16), dp(12));
        TextView title = text(c, 18, R.color.projects_bone_100, R.font.tektur_semibold);
        title.setText(R.string.projects_voice_invite_title);
        title.setPadding(dp(4), 0, 0, dp(8));
        content.addView(title);
        if (candidates.isEmpty()) {
            TextView none = text(c, 15, R.color.projects_ash_300, R.font.figtree_regular);
            none.setSingleLine(false);
            none.setText(R.string.projects_voice_invite_none);
            none.setPadding(dp(4), dp(8), dp(4), dp(16));
            content.addView(none);
        }
        ScrollView scroll = new CappedScroll(c, 0.55f);
        LinearLayout list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        content.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        for (final SocialModel.Profile profile : candidates) {
            LinearLayout row = row(c);
            AvatarHolder.face(c, row, profile, dp(40));
            row.addView(names(c, profile.name(), null, false), weight());
            final TextView go = text(c, 14, R.color.projects_bone_100, R.font.figtree_semibold);
            go.setText(R.string.projects_voice_invite);
            go.setGravity(Gravity.CENTER);
            go.setPadding(dp(14), 0, dp(14), 0);
            go.setBackground(ChamferDrawable.secondary(c));
            go.setOnClickListener(v -> {
                Haptics.confirm(v);
                go.setEnabled(false);
                go.setText(R.string.projects_voice_ringing);
                go.setTextColor(ContextCompat.getColor(mActivity, R.color.projects_ash_400));
                VoiceUi.invite(mActivity, profile.id, profile.name());
            });
            Press.attach(go, Press.Style.BUTTON);
            row.addView(go, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
            list.addView(row);
        }
        BottomPanel.of(c, content, 0.75f).show();
    }

    private static boolean ringing(VoiceState s, String id) {
        for (VoiceState.OutRing r : s.ringing) if (r.to.equals(id)) return true;
        return id.equals(s.calling);
    }

    /** A plain face for a list row (no speaking ring). */
    private static final class AvatarHolder {
        static void face(Context c, LinearLayout row, SocialModel.Profile profile, int size) {
            gg.projects.mobile.social.ui.AvatarView avatar = new gg.projects.mobile.social.ui.AvatarView(c);
            avatar.setSurfaceColor(ContextCompat.getColor(c, R.color.projects_obsidian_800));
            avatar.setProfile(profile);
            row.addView(avatar, new LinearLayout.LayoutParams(size, size));
        }
    }

    // ---- pieces ----

    private static String roleWord(Context c, String role, boolean me) {
        if (VoiceState.LEADER.equals(role)) return c.getString(R.string.projects_voice_leader);
        if (VoiceState.MODERATOR.equals(role)) return c.getString(R.string.projects_voice_moderator);
        return me ? c.getString(R.string.projects_voice_you) : null;
    }

    private LinearLayout row(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        row.setPaddingRelative(dp(4), dp(4), dp(4), dp(4));
        return row;
    }

    private LinearLayout names(Context c, String name, @Nullable String small, boolean live) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPaddingRelative(dp(12), 0, dp(8), 0);
        TextView n = text(c, 15, R.color.projects_bone_100, R.font.figtree_semibold);
        n.setText(name);
        if (live) {
            // M051: "YAYINDA" right after the name; a long name gives way to it.
            gg.projects.mobile.ui.NameLine line = new gg.projects.mobile.ui.NameLine(c);
            line.addView(n, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView tag = LiveTag.make(c, false);
            LiveTag.show(tag, true);
            line.addView(tag, LiveTag.after(c));
            box.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            box.addView(n);
        }
        if (small != null) {
            TextView s = text(c, 12, R.color.projects_ash_400, R.font.figtree_regular);
            s.setText(small);
            box.addView(s);
        }
        return box;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private View mark(Context c, int icon, int tint, int description) {
        android.widget.ImageView v = new android.widget.ImageView(c);
        v.setImageResource(icon);
        v.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(c, tint)));
        v.setContentDescription(c.getString(description));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(18), dp(18));
        lp.setMarginStart(dp(8));
        v.setLayoutParams(lp);
        return v;
    }

    private TextView control(Context c) {
        TextView t = text(c, 13, R.color.projects_bone_100, R.font.figtree_semibold);
        t.setGravity(Gravity.CENTER);
        t.setBackground(ChamferDrawable.secondary(c));
        t.setCompoundDrawablePadding(dp(4));
        t.setPadding(dp(6), dp(8), dp(6), dp(8));
        Press.attach(t, Press.Style.BUTTON);
        return t;
    }

    private void setControl(TextView t, int icon, int label, int tint) {
        Context c = mActivity;
        t.setText(label);
        t.setTextColor(ContextCompat.getColor(c, tint == R.color.projects_flare_400 ? R.color.projects_flare_400 : R.color.projects_bone_100));
        Drawable glyph = ContextCompat.getDrawable(c, icon);
        if (glyph != null) {
            glyph = glyph.mutate();
            glyph.setTint(ContextCompat.getColor(c, tint));
            glyph.setBounds(0, 0, dp(22), dp(22));
            t.setCompoundDrawablesRelative(null, glyph, null, null);
        }
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

    private ImageButton icon(Context c, int icon, int description) {
        ImageButton b = new ImageButton(c);
        b.setImageResource(icon);
        b.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(c, R.color.projects_bone_100)));
        b.setBackgroundResource(R.drawable.projects_icon_button_ripple);
        b.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        b.setPadding(dp(12), dp(12), dp(12), dp(12));
        b.setContentDescription(c.getString(description));
        Press.attach(b, Press.Style.ICON);
        return b;
    }

    private int dp(int value) {
        return Math.round(value * mDp);
    }

    /** A list that grows with its rows up to a share of the screen, then scrolls (the controls stay in sight). */
    static final class CappedScroll extends ScrollView {
        private final int mMax;

        CappedScroll(Context context, float share) {
            super(context);
            mMax = Math.round(context.getResources().getDisplayMetrics().heightPixels * share);
            setVerticalScrollBarEnabled(false);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(mMax, MeasureSpec.AT_MOST));
        }
    }
}
