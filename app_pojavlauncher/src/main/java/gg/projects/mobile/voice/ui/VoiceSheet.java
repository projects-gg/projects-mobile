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
import android.view.accessibility.AccessibilityManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.FragmentActivity;

import net.kdt.pojavlaunch.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import gg.projects.mobile.social.ChatBook;
import gg.projects.mobile.social.ChatConv;
import gg.projects.mobile.social.SocialModel;
import gg.projects.mobile.social.SocialService;
import gg.projects.mobile.social.SocialState;
import gg.projects.mobile.social.ui.BottomPanel;
import gg.projects.mobile.social.ui.ChatFragment;
import gg.projects.mobile.social.ui.CrewViews;
import gg.projects.mobile.social.ui.RoomChatPanel;
import gg.projects.mobile.social.ui.SocialUi;
import gg.projects.mobile.ui.ChamferDrawable;
import gg.projects.mobile.ui.Haptics;
import gg.projects.mobile.ui.Press;
import gg.projects.mobile.voice.RoomChat;
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
 * SQOL: a paused share says "DURAKLATILDI", a minimized shared window "KÜÇÜLTÜLDÜ" (the picture says it too).
 *
 * <p>SQOL (the lead's {@code sqol.md} §1.3): with a chat the sheet takes its full height: the title, the people under a
 * foldable "Odadakiler · 5" (at most about a third of the screen, their own scroll; folded while the player types), the
 * chat in the rest, the controls at the bottom. Which chat: a friend room that never had three people, with a mutual
 * friend and chat on, shows that friend's messages ("Mesajlar": the last ones and a field that opens the conversation);
 * otherwise the room's own chat when the room offers it ({@link RoomChatPanel}); a crew room also links its crew's chat.
 * TalkBack hears who came and went, who started or stopped sharing, and new messages, politely and gathered.
 */
public final class VoiceSheet implements VoiceLink.Listener {
    private enum ChatMode { NONE, ROOM, DM }

    /** TalkBack's words are gathered this long, then said at once (quick ones are never lost). */
    private static final long ANNOUNCE_GAP_MS = 1_200;

    private final FragmentActivity mActivity;
    private final LinearLayout mRoot;
    private final TextView mTitle;
    private final TextView mLine;
    private final ImageButton mInvite;
    private final ImageButton mMore;
    private final LinearLayout mPeopleHead;
    private final TextView mPeopleWord;
    private final ImageView mPeopleCaret;
    private final CappedScroll mPeopleScroll;
    private final LinearLayout mList;
    private final LinearLayout mChatSlot;
    /** Mikrofon, Sağırlaştır, Ayrıl (stepping aside while the keyboard is up for the chat). */
    private final LinearLayout mControls;
    private final TextView mMic;
    private final TextView mDeaf;
    private final TextView mLeave;
    private final float mDp;
    @Nullable private VoiceState mState;
    @Nullable private RoomChat.View mChat;
    private BottomPanel mPanel;
    /** The open person sheet's stream picture (M051), or null. */
    @Nullable private Picture mPicture;
    private ChatMode mMode = ChatMode.NONE;
    @Nullable private String mDmFriend;
    /** What the friend's messages showed last (drawn again only when it changes). */
    @Nullable private String mDmKey;
    @Nullable private Boolean mPeopleShownState;
    @Nullable private RoomChatPanel mRoomChat;
    /** The player's choice for "Odadakiler" (open by default); folded for as long as the field has the focus. */
    private boolean mPeopleOpen = true;
    private boolean mTyping;
    /** The window told whether its keyboard is up (Android 6+), and whether it is. */
    private boolean mImeKnown;
    private boolean mImeShown;
    private boolean mShowing;
    /** Names of everyone seen in the room this time (someone who left still names their messages). */
    private final Map<String, VoiceState.Person> mSeen = new HashMap<>();
    // TalkBack: what the last state and chat held, and the words waiting to be said.
    @Nullable private Set<String> mHeardPeople;
    private final Set<String> mHeardSharing = new HashSet<>();
    @Nullable private Set<String> mHeardLines;
    private final List<String> mToSay = new ArrayList<>();
    private int mNewMessages;
    @Nullable private String mOneMessage;
    private final Runnable mSay = this::say;
    private final SocialService.Listener mSocial = () -> {
        // After 0.5.4: someone blocked now leaves the room's chat at once.
        if (mRoomChat != null) mRoomChat.socialChanged();
        renderChat();
    };

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
        ViewCompat.setAccessibilityHeading(mTitle, true);
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

        // SQOL: "Odadakiler · 5" folds the people away while a chat shares the sheet.
        mPeopleHead = new LinearLayout(c);
        mPeopleHead.setOrientation(LinearLayout.HORIZONTAL);
        mPeopleHead.setGravity(Gravity.CENTER_VERTICAL);
        mPeopleHead.setMinimumHeight(dp(48));
        mPeopleHead.setPaddingRelative(dp(4), 0, dp(4), 0);
        mPeopleHead.setBackgroundResource(R.drawable.projects_row_ripple);
        mPeopleHead.setClickable(true);
        mPeopleHead.setFocusable(true);
        mPeopleWord = text(c, 13, R.color.projects_ash_300, R.font.figtree_semibold);
        mPeopleHead.addView(mPeopleWord, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mPeopleCaret = new ImageView(c);
        mPeopleCaret.setImageResource(R.drawable.projects_ic_caret_down_small);
        mPeopleCaret.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(c, R.color.projects_ash_300)));
        mPeopleCaret.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        mPeopleHead.addView(mPeopleCaret, new LinearLayout.LayoutParams(dp(18), dp(18)));
        mPeopleHead.setOnClickListener(v -> {
            Haptics.toggle(v, !mPeopleOpen);
            mPeopleOpen = !mPeopleOpen;
            layoutPeople();
        });
        Press.attach(mPeopleHead, Press.Style.ROW);
        mPeopleHead.setVisibility(View.GONE);
        LinearLayout.LayoutParams peopleHeadLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        peopleHeadLp.topMargin = dp(4);
        mRoot.addView(mPeopleHead, peopleHeadLp);

        mPeopleScroll = new CappedScroll(c, 0.5f);
        mList = new LinearLayout(c);
        mList.setOrientation(LinearLayout.VERTICAL);
        mPeopleScroll.addView(mList);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        scrollLp.topMargin = dp(8);
        mRoot.addView(mPeopleScroll, scrollLp);

        // SQOL: the chat takes the rest of the sheet (its height is the sheet's minus the rest).
        mChatSlot = new LinearLayout(c);
        mChatSlot.setOrientation(LinearLayout.VERTICAL);
        mChatSlot.setVisibility(View.GONE);
        LinearLayout.LayoutParams chatLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        chatLp.topMargin = dp(8);
        mRoot.addView(mChatSlot, chatLp);

        LinearLayout controls = new LinearLayout(c);
        mControls = controls;
        controls.setOrientation(LinearLayout.HORIZONTAL);
        // A floor, never a ceiling: larger text grows the buttons instead of cutting their words.
        controls.setMinimumHeight(dp(64));
        LinearLayout.LayoutParams controlsLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
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
        mChat = link.chat();
        mShowing = true;
        mPanel = BottomPanel.of(mActivity, mRoot, 0.85f).onClosed(() -> {
            mShowing = false;
            mActivity.getWindow().getDecorView().removeCallbacks(mSay);
            if (mRoomChat != null) {
                mRoomChat.setShown(false);
                mRoomChat.close();
            }
            SocialService.get(mActivity).removeListener(mSocial);
            link.detach(this);
        }).onBack(() -> mRoomChat != null && mRoomChat.back());
        // After 0.5.4 (the lead): while the keyboard is up for the room's chat, the people fold and the controls row
        // steps aside, so the field sits on the keyboard; the keyboard's own state, not the field's focus (a Back that
        // closes the keyboard leaves the focus in the field).
        // The window's own insets after every layout (the keyboard resizes the sheet's window): read even where a parent
        // consumed them on the way down.
        mRoot.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            if (android.os.Build.VERSION.SDK_INT < 23) return;
            WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(mRoot);
            if (insets == null) return;
            boolean ime = insets.isVisible(WindowInsetsCompat.Type.ime());
            if (!mImeKnown || ime != mImeShown) {
                mImeKnown = true;
                mImeShown = ime;
                layoutPeople();
            }
        });
        render();
        // The speaking rings are the call bar's to ask for (it shows under the sheet the whole time).
        link.attach(this);
        SocialService.get(mActivity).addListener(mSocial);
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
    public void onVoiceChat(@Nullable RoomChat.View chat) {
        if (chat != null && mState != null && !chat.room.equals(mState.room)) return;
        mChat = chat;
        hearChat(chat);
        renderChat();
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
        for (VoiceState.Person p : s.people) mSeen.put(p.id, p);
        hearState(s);
        mTitle.setText(VoiceUi.roomTitle(c, s));
        mLine.setText(s.oneToOne() && s.calling == null ? c.getString(R.string.projects_voice_call_kind) : VoiceUi.roomLine(c, s, now));
        mLine.setTextColor(ContextCompat.getColor(c, s.reconnecting() ? R.color.projects_bone_100 : R.color.projects_ash_400));
        mInvite.setVisibility(s.live() && s.calling == null ? View.VISIBLE : View.GONE);
        boolean canRename = canRename(s);
        mMore.setVisibility(canRename || s.canClose ? View.VISIBLE : View.GONE);
        mMore.setContentDescription(c.getString(canRename ? R.string.projects_voice_rename : R.string.projects_voice_close));

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
        renderChat();
    }

    // ---- SQOL: the chat ----

    /**
     * Which chat the room shows (the lead's rule): a friend room that never had three people, whose other person is a
     * mutual friend, with chat on: that friend's messages; otherwise the room's chat when it offers one; else none.
     */
    private ChatMode chatMode(VoiceState s) {
        // After 0.5.4 (the lead): sticky per room like the desktop's; a gap in the friends feature never flips it.
        SocialService service = SocialService.get(mActivity);
        String other = s.crewRoom ? null : s.people.size() == 1 ? s.people.get(0).id : s.people.isEmpty() ? s.calling : null;
        boolean ready = service.connection() == SocialService.Connection.LIVE && service.state().myId() != null;
        String choice = gg.projects.mobile.voice.RoomChatChoice.decide(gg.projects.mobile.voice.RoomChatChoice.kept(s.room),
                s.crewRoom, s.grew, other, other != null && service.state().isFriend(other), service.chat().available(), s.chat, ready);
        gg.projects.mobile.voice.RoomChatChoice.keep(s.room, choice, ready || s.crewRoom || s.grew);
        mDmFriend = gg.projects.mobile.voice.RoomChatChoice.friendOf(choice);
        if (mDmFriend != null) return ChatMode.DM;
        return gg.projects.mobile.voice.RoomChatChoice.ROOM.equals(choice) ? ChatMode.ROOM : ChatMode.NONE;
    }

    private void renderChat() {
        VoiceState s = mState;
        if (s == null || mPanel == null) return;
        String friendBefore = mDmFriend;
        ChatMode mode = chatMode(s);
        boolean modeChanged = mode != mMode || (mode == ChatMode.DM && !mDmFriend.equals(friendBefore));
        if (modeChanged) {
            mMode = mode;
            mChatSlot.removeAllViews();
            if (mRoomChat != null && mode != ChatMode.ROOM) {
                mRoomChat.setShown(false);
                mRoomChat.close();
                mRoomChat = null;
            }
            if (mode == ChatMode.ROOM) {
                mChatSlot.addView(chatHead(s));
                mRoomChat = new RoomChatPanel(mActivity, new RoomChatPanel.Host() {
                    @Nullable
                    @Override
                    public SocialModel.Profile profile(String u) {
                        return VoiceFace.profileOf(mActivity, u, mSeen.get(u));
                    }

                    @Override
                    public String name(String u) {
                        SocialModel.Profile profile = profile(u);
                        return profile != null ? profile.name() : mActivity.getString(R.string.projects_room_chat_someone);
                    }

                    @Override
                    public void typing(boolean on) {
                        mTyping = on;
                        layoutPeople();
                    }
                });
                mChatSlot.addView(mRoomChat.view(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            }
            mChatSlot.setVisibility(mode == ChatMode.NONE ? View.GONE : View.VISIBLE);
            // With the room's chat the sheet takes its full height (the chat fills it); the friend's messages (three lines
            // and a field) and no chat keep it as tall as what it shows (no empty band above the last messages).
            int full = Math.round(mActivity.getResources().getDisplayMetrics().heightPixels * 0.85f) - dp(20);
            mPanel.setContentHeight(mode == ChatMode.ROOM ? full : 0);
            if (mode != ChatMode.ROOM) mTyping = false;
        }
        layoutPeople();
        if (mode == ChatMode.ROOM && mRoomChat != null) {
            // Only this room's chat (a move just now: the new room's comes with its hello).
            RoomChat.View chat = mChat != null && mChat.room.equals(s.room) ? mChat : null;
            mRoomChat.update(chat, s.live());
            mRoomChat.setShown(mShowing);
        } else if (mode == ChatMode.DM) {
            renderDm(s);
        }
    }

    /** The chat's thin title: "Oda Sohbeti", in a crew room with the crew's chat beside it ("Ekip Sohbeti"). */
    private View chatHead(VoiceState s) {
        Context c = mActivity;
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPaddingRelative(dp(4), 0, 0, 0);
        TextView title = text(c, 13, R.color.projects_ash_300, R.font.figtree_semibold);
        title.setText(R.string.projects_room_chat_title);
        ViewCompat.setAccessibilityHeading(title, true);
        row.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final String crew = s.crewRoom ? s.crew : null;
        if (crew != null && SocialService.get(c).chat().available()) {
            TextView link = text(c, 13, R.color.projects_ash_300, R.font.figtree_semibold);
            link.setText(R.string.projects_room_chat_crew_link);
            link.setGravity(Gravity.CENTER);
            link.setMinHeight(dp(48));
            link.setPadding(dp(12), 0, dp(4), 0);
            link.setBackgroundResource(R.drawable.projects_row_ripple);
            // A caret after it says it goes somewhere (the emulator pass: plain ash words did not read as a link).
            Drawable caret = ContextCompat.getDrawable(c, R.drawable.projects_ic_caret_right_small);
            if (caret != null) {
                caret = caret.mutate();
                caret.setTint(ContextCompat.getColor(c, R.color.projects_ash_300));
                caret.setBounds(0, 0, dp(14), dp(14));
                link.setCompoundDrawablesRelative(null, null, caret, null);
                link.setCompoundDrawablePadding(dp(2));
            }
            link.setOnClickListener(v -> openConversation(ChatConv.crew(crew)));
            Press.attach(link, Press.Style.BUTTON);
            row.addView(link);
        } else {
            row.setMinimumHeight(dp(32));
        }
        return row;
    }

    /**
     * "Mesajlar": a friend room of two shows that friend's messages (the conversation itself, unchanged): the last ones
     * and a field that opens it with the keyboard up. The call bar stays under it.
     */
    private void renderDm(VoiceState s) {
        Context c = mActivity;
        SocialService service = SocialService.get(c);
        String me = service.state().myId();
        if (me == null || mDmFriend == null) return;
        final String conv = ChatConv.direct(me, mDmFriend);
        ChatBook book = service.chat().book();
        int unread = book == null ? 0 : book.unread(conv);
        List<ChatBook.Entry> entries = book == null ? new ArrayList<ChatBook.Entry>() : book.entries(conv);
        int from = Math.max(0, entries.size() - 3);
        // The room's state comes with every speaking ring: drawn again only when what it shows changed.
        StringBuilder key = new StringBuilder(conv).append('|').append(unread);
        for (int i = from; i < entries.size(); i++) {
            ChatBook.Entry e = entries.get(i);
            key.append('|').append(e.deleted()).append(e.mine(me)).append(e.sticker()).append(e.text());
        }
        if (key.toString().equals(mDmKey) && mChatSlot.getChildCount() > 0) return;
        mDmKey = key.toString();
        mChatSlot.removeAllViews();
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setMinimumHeight(dp(32));
        head.setPaddingRelative(dp(4), 0, dp(4), 0);
        TextView title = text(c, 13, R.color.projects_ash_300, R.font.figtree_semibold);
        title.setText(R.string.projects_room_chat_dm_title);
        ViewCompat.setAccessibilityHeading(title, true);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (unread > 0) {
            TextView count = unreadPill(c, unread);
            head.addView(count);
        }
        mChatSlot.addView(head);

        LinearLayout lines = new LinearLayout(c);
        lines.setOrientation(LinearLayout.VERTICAL);
        lines.setGravity(Gravity.BOTTOM);
        for (int i = from; i < entries.size(); i++) {
            ChatBook.Entry e = entries.get(i);
            if (e.deleted()) continue;
            boolean mine = e.mine(me);
            String who = mine ? c.getString(R.string.projects_crew_you) : VoiceUi.nameOf(c, mDmFriend);
            String said = e.sticker() != null ? c.getString(R.string.projects_stickers_unknown) : e.text();
            TextView line = text(c, 14, R.color.projects_bone_100, R.font.figtree_regular);
            line.setSingleLine(false);
            line.setMaxLines(2);
            line.setText(who + ": " + said);
            line.setPadding(dp(4), dp(4), dp(4), dp(4));
            lines.addView(line);
        }
        if (lines.getChildCount() == 0) {
            TextView none = text(c, 14, R.color.projects_ash_400, R.font.figtree_regular);
            none.setSingleLine(false);
            none.setText(R.string.projects_room_chat_dm_none);
            none.setGravity(Gravity.CENTER);
            none.setPadding(dp(16), dp(12), dp(16), dp(12));
            lines.addView(none);
        }
        // A tap on the last messages opens the conversation too (TalkBack reads the messages themselves).
        lines.setClickable(true);
        lines.setOnClickListener(v -> openConversation(conv));
        mChatSlot.addView(lines, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // A field that looks like the chat's: a tap opens the conversation, the keyboard comes there.
        TextView field = text(c, 15, R.color.projects_ash_400, R.font.figtree_regular);
        field.setText(R.string.projects_chat_hint);
        field.setBackgroundResource(R.drawable.projects_chat_input);
        field.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        field.setMinHeight(dp(48));
        field.setPadding(dp(16), dp(10), dp(16), dp(10));
        field.setContentDescription(c.getString(R.string.projects_room_chat_dm_open));
        field.setOnClickListener(v -> openConversation(conv));
        Press.attach(field, Press.Style.BUTTON);
        LinearLayout.LayoutParams fieldLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        fieldLp.topMargin = dp(6);
        mChatSlot.addView(field, fieldLp);
    }

    private void openConversation(String conv) {
        if (mPanel != null) mPanel.dismiss();
        ChatFragment.show(mActivity, conv);
    }

    private TextView unreadPill(Context c, int unread) {
        TextView count = text(c, 12, R.color.projects_bone_100, R.font.tektur_semibold);
        count.setText(String.valueOf(Math.min(99, unread)));
        count.setGravity(Gravity.CENTER);
        count.setMinWidth(dp(20));
        count.setPadding(dp(6), 0, dp(6), 0);
        android.graphics.drawable.GradientDrawable pill = new android.graphics.drawable.GradientDrawable();
        pill.setCornerRadius(dp(10));
        pill.setColor(ContextCompat.getColor(c, R.color.projects_obsidian_700));
        count.setBackground(pill);
        count.setContentDescription(c.getResources().getQuantityString(R.plurals.projects_room_chat_unread, unread, unread));
        return count;
    }

    /**
     * "Odadakiler · 5" while a chat shares the sheet: open or folded as the player chose (folded while typing); the
     * people take at most about a third of the screen then.
     */
    private void layoutPeople() {
        VoiceState s = mState;
        boolean chat = mMode != ChatMode.NONE;
        mPeopleHead.setVisibility(chat ? View.VISIBLE : View.GONE);
        // Typing: the field has the focus and the keyboard is up (where the window tells its keyboard; else the focus).
        boolean typing = mTyping && (!mImeKnown || mImeShown);
        mControls.setVisibility(typing && mImeKnown ? View.GONE : View.VISIBLE);
        boolean open = !chat || (mPeopleOpen && !typing);
        mPeopleScroll.setVisibility(open ? View.VISIBLE : View.GONE);
        mPeopleScroll.setShare(chat ? 0.3f : 0.5f);
        if (!chat || s == null) return;
        int count = s.people.size() + 1;
        Context c = mActivity;
        mPeopleWord.setText(c.getString(R.string.projects_room_chat_people, count));
        mPeopleCaret.setRotation(open ? 180f : 0f);
        if (!Boolean.valueOf(open).equals(mPeopleShownState)) {
            // Said once per change (TalkBack hears "açık"/"kapalı" after the header's words).
            mPeopleShownState = open;
            ViewCompat.setStateDescription(mPeopleHead, c.getString(open ? R.string.projects_room_chat_people_shown
                    : R.string.projects_room_chat_people_hidden));
        }
    }

    // ---- SQOL: TalkBack ----

    private boolean talkBack() {
        AccessibilityManager am = (AccessibilityManager) mActivity.getSystemService(Context.ACCESSIBILITY_SERVICE);
        return am != null && am.isEnabled() && am.isTouchExplorationEnabled();
    }

    /** Who came, who went, who started or stopped sharing (never on the sheet's first look). */
    private void hearState(VoiceState s) {
        // After 0.5.4: the first look is the room's first live state (people already there never read as joining);
        // nothing is said while joining or reconnecting.
        if (!s.live()) return;
        Set<String> people = new HashSet<>();
        Set<String> sharing = new HashSet<>();
        for (VoiceState.Person p : s.people) {
            people.add(p.id);
            if (p.sharing) sharing.add(p.id);
        }
        Set<String> before = mHeardPeople;
        mHeardPeople = people;
        if (before == null || !talkBack()) {
            mHeardSharing.clear();
            mHeardSharing.addAll(sharing);
            return;
        }
        Context c = mActivity;
        for (String id : people) {
            if (!before.contains(id)) queue(c.getString(R.string.projects_room_chat_joined, nameOf(id)));
        }
        for (String id : before) {
            if (!people.contains(id)) queue(c.getString(R.string.projects_room_chat_left, nameOf(id)));
        }
        for (String id : sharing) {
            if (!mHeardSharing.contains(id)) queue(c.getString(R.string.projects_room_chat_share_started, nameOf(id)));
        }
        for (String id : mHeardSharing) {
            if (!sharing.contains(id) && people.contains(id)) queue(c.getString(R.string.projects_room_chat_share_stopped, nameOf(id)));
        }
        mHeardSharing.clear();
        mHeardSharing.addAll(sharing);
    }

    /** New room messages of others, gathered ("3 yeni mesaj"; one alone is read out with its sender). */
    private void hearChat(@Nullable RoomChat.View chat) {
        Set<String> lines = new HashSet<>();
        if (chat != null) for (RoomChat.Line l : chat.lines) lines.add(l.id);
        Set<String> before = mHeardLines;
        mHeardLines = lines;
        if (before == null || chat == null || mMode != ChatMode.ROOM || !talkBack()) return;
        Context c = mActivity;
        Set<String> blocked = RoomChatPanel.blockedIds(c);
        for (RoomChat.Line l : chat.lines) {
            // After 0.5.4: never the room's past (seen; the first log among it) nor someone blocked.
            if (before.contains(l.id) || l.mine || !l.sent() || l.at <= chat.seenAt || blocked.contains(l.u)) continue;
            mNewMessages++;
            String who = nameOf(l.u);
            mOneMessage = who + ": " + (l.sticker() ? c.getString(R.string.projects_stickers_unknown) : l.text);
        }
        if (mNewMessages > 0) schedule();
    }

    private String nameOf(String id) {
        VoiceState.Person p = mSeen.get(id);
        return p != null ? VoiceUi.nameOf(mActivity, p) : VoiceUi.nameOf(mActivity, id);
    }

    private void queue(String words) {
        mToSay.add(words);
        schedule();
    }

    private void schedule() {
        View decor = mActivity.getWindow().getDecorView();
        decor.removeCallbacks(mSay);
        decor.postDelayed(mSay, ANNOUNCE_GAP_MS);
    }

    private void say() {
        if (!mShowing) {
            mToSay.clear();
            mNewMessages = 0;
            return;
        }
        StringBuilder words = new StringBuilder();
        for (String w : mToSay) {
            if (words.length() > 0) words.append(' ');
            words.append(w);
        }
        if (mNewMessages == 1 && mOneMessage != null) {
            if (words.length() > 0) words.append(' ');
            words.append(mOneMessage);
        } else if (mNewMessages > 1) {
            if (words.length() > 0) words.append(' ');
            words.append(mActivity.getResources().getQuantityString(R.plurals.projects_room_chat_new, mNewMessages, mNewMessages));
        }
        mToSay.clear();
        mNewMessages = 0;
        mOneMessage = null;
        if (words.length() > 0) mRoot.announceForAccessibility(words);
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
        row.addView(names(c, name, roleWord(c, s.showsRoles() ? s.me.role : VoiceState.MEMBER, true), s.me.sharing, null), weight());
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
        row.addView(names(c, name, roleWord(c, s.showsRoles() ? p.role : VoiceState.MEMBER, false), p.sharing, p.shareSt), weight());
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
        row.setContentDescription(LiveTag.spoken(c, name, p.sharing, p.shareSt)
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
        row.addView(names(c, VoiceUi.nameOf(c, to), c.getString(R.string.projects_voice_ringing), false, null), weight());
        if (!call) {
            TextView cancel = text(c, 14, R.color.projects_ash_300, R.font.figtree_semibold);
            cancel.setText(R.string.projects_voice_cancel);
            cancel.setGravity(Gravity.CENTER);
            cancel.setPadding(dp(12), 0, dp(12), 0);
            // SQOL: a 48 dp target (it was 44).
            cancel.setMinHeight(dp(48));
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
        head.addView(names(c, name, roleWord(c, s.showsRoles() ? p.role : VoiceState.MEMBER, false), p.sharing, p.shareSt), weight());
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
        // SQOL: TalkBack reads the value as the field shows it ("%120"), not a bare step number.
        ViewCompat.setStateDescription(bar, percent(Math.min(s.volumeMax, p.volume)));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        barLp.setMarginStart(dp(8));
        volume.addView(bar, barLp);
        final TextView value = text(c, 14, R.color.projects_ash_300, R.font.tektur_semibold);
        value.setText(percent(p.volume));
        value.setMinWidth(dp(48));
        value.setGravity(Gravity.END);
        value.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        volume.addView(value);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                value.setText(percent(progress * 10));
                ViewCompat.setStateDescription(seekBar, percent(progress * 10));
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
        // M053: "Yok say" as in the person menu (friends and crewmates, while the ignore list counts).
        SocialState social = SocialService.get(c).state();
        if (gg.projects.mobile.social.IgnoreList.on(c) && (social.isFriend(id) || !social.crewsOf(id).isEmpty())) {
            final boolean ignored = gg.projects.mobile.social.IgnoreList.all(c).contains(id);
            content.addView(CrewViews.actionRow(c, ignored ? R.drawable.projects_ic_team6_eye : R.drawable.projects_ic_team6_eye_slash,
                    c.getString(ignored ? R.string.projects_ignore_stop : R.string.projects_ignore_action), R.color.projects_ash_300, v -> {
                        panel[0].dismiss();
                        gg.projects.mobile.social.IgnoreList.set(mActivity, id, !ignored);
                        SocialUi.toast(mActivity, mActivity.getString(ignored ? R.string.projects_ignore_stopped : R.string.projects_ignore_done, name));
                    }));
        }
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
        if (p.canKick && p.sharing) {
            // M053: "Paylaşımı durdur" (the desktop's leader and moderator action; the kick's rule). The sharer is told.
            content.addView(CrewViews.actionRow(c, R.drawable.projects_ic_screencast, c.getString(R.string.projects_voice_unshare),
                    R.color.projects_bone_100, v -> {
                        panel[0].dismiss();
                        mod(VoiceWire.ModAct.UNSHARE, id, null, name, null);
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

    /** SQOL: a percent as each language writes it ("%120", "120%"); TalkBack reads it the same way. */
    private String percent(int value) {
        return mActivity.getString(R.string.projects_voice_percent, value);
    }

    /**
     * M051: the person sheet's stream picture (16:9 on a quiet plate, the share's kind and watchers under it); gone while
     * they do not share. Asks the service only for a picture time it has not shown, never polls. SQOL: a paused share
     * shows no picture, a minimized window its last one dimmed, each with a sentence on it.
     */
    private final class Picture {
        final LinearLayout view;
        private final String room;
        private final String id;
        private final ImageView image;
        private final TextView state;
        private final TextView line;
        private long shownTh = -1;
        boolean closed;

        Picture(Context c, String room, String id, String name) {
            this.room = room;
            this.id = id;
            view = new LinearLayout(c);
            view.setOrientation(LinearLayout.VERTICAL);
            view.setPadding(0, dp(8), 0, dp(8));
            FrameLayout frame = new FrameLayout(c);
            image = new ImageView(c) {
                @Override
                protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                    int width = MeasureSpec.getSize(widthMeasureSpec);
                    setMeasuredDimension(width, width * 9 / 16);
                }
            };
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            android.graphics.drawable.GradientDrawable plate = new android.graphics.drawable.GradientDrawable();
            plate.setCornerRadius(dp(8));
            plate.setColor(ContextCompat.getColor(c, R.color.projects_obsidian_800));
            image.setBackground(plate);
            image.setClipToOutline(true);
            image.setContentDescription(c.getString(R.string.projects_voice_share_picture, name));
            frame.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            state = text(c, 14, R.color.projects_bone_100, R.font.figtree_semibold);
            state.setSingleLine(false);
            state.setGravity(Gravity.CENTER);
            state.setPadding(dp(16), 0, dp(16), 0);
            state.setVisibility(View.GONE);
            frame.addView(state, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER));
            view.addView(frame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
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
            // SQOL: a paused share has no picture to show; a minimized window's last one stays, dimmed under the words.
            boolean held = "hold".equals(p.shareSt);
            boolean min = "min".equals(p.shareSt);
            state.setVisibility(held || min ? View.VISIBLE : View.GONE);
            state.setText(held ? R.string.projects_voice_share_paused : R.string.projects_voice_share_minimized);
            image.setImageAlpha(held ? 0 : min ? 90 : 255);
            final long th = p.shareTh;
            if (th == shownTh) return;
            shownTh = th;
            VoiceHub hub = VoiceHub.instance();
            if (hub == null || th <= 0) return;
            hub.thumb(room, id, p.shareN, th, picture -> {
                if (closed || shownTh != th || picture == null) return;
                boolean first = image.getDrawable() == null;
                image.setImageBitmap(picture);
                VoiceState now = mState;
                VoiceState.Person them = now == null ? null : now.person(id);
                String st = them == null ? null : them.shareSt;
                int alpha = "hold".equals(st) ? 0 : "min".equals(st) ? 90 : 255;
                // A new picture fades in (200 ms); nothing moves with animations off.
                if (first && alpha == 255 && gg.projects.mobile.mods.Motion.enabled(mActivity)) {
                    image.setImageAlpha(0);
                    android.animation.ValueAnimator fade = android.animation.ValueAnimator.ofInt(0, 255);
                    fade.setDuration(200);
                    fade.addUpdateListener(a -> image.setImageAlpha((Integer) a.getAnimatedValue()));
                    fade.start();
                } else {
                    image.setImageAlpha(alpha);
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
        ViewCompat.setAccessibilityHeading(title, true);
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

    /**
     * M053: renaming as the desktop allows it ({@code machine.rs}): the voice process's rule, or the crew room's opener
     * (the crew list's {@code own}; the room socket does not say who opened it).
     */
    private static boolean canRename(VoiceState s) {
        if (s.canRename) return true;
        VoiceHub hub = VoiceHub.instance();
        if (hub == null || s.room == null || s.crew == null || !s.crewRoom) return false;
        VoiceRings.CrewRoom room = hub.rings().crewRoom(s.room);
        return room != null && room.own && !room.general();
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
        if (canRename(s)) {
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
        ViewCompat.setAccessibilityHeading(title, true);
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
            row.addView(names(c, profile.name(), null, false, null), weight());
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
            // SQOL: a 48 dp target (it was 40).
            row.addView(go, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));
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

    private LinearLayout names(Context c, String name, @Nullable String small, boolean live, @Nullable String st) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPaddingRelative(dp(12), 0, dp(8), 0);
        TextView n = text(c, 15, R.color.projects_bone_100, R.font.figtree_semibold);
        n.setText(name);
        if (live) {
            // M051: "YAYINDA" right after the name; a long name gives way to it. SQOL: paused or minimized say so.
            gg.projects.mobile.ui.NameLine line = new gg.projects.mobile.ui.NameLine(c);
            line.addView(n, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView tag = LiveTag.make(c, false);
            LiveTag.state(tag, st);
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
        ImageView v = new ImageView(c);
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
        t.setMinHeight(dp(64));
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
        b.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
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
        private int mMax;

        CappedScroll(Context context, float share) {
            super(context);
            setShare(share);
            setVerticalScrollBarEnabled(false);
        }

        /** SQOL: a smaller share while a chat takes the rest of the room sheet. */
        void setShare(float share) {
            int max = Math.round(getContext().getResources().getDisplayMetrics().heightPixels * share);
            if (max == mMax) return;
            mMax = max;
            requestLayout();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(mMax, MeasureSpec.AT_MOST));
        }
    }
}
