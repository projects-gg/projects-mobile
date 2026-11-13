package gg.projects.mobile.social.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import gg.projects.mobile.mods.Motion;
import gg.projects.mobile.social.ChatLinks;
import gg.projects.mobile.social.ChatMessage;
import gg.projects.mobile.social.ChatText;
import gg.projects.mobile.social.SocialChat;
import gg.projects.mobile.social.SocialModel;
import gg.projects.mobile.social.SocialService;
import gg.projects.mobile.ui.AppDates;
import gg.projects.mobile.ui.HapticMap;
import gg.projects.mobile.ui.Haptics;
import gg.projects.mobile.ui.Press;
import gg.projects.mobile.voice.RoomChat;
import gg.projects.mobile.voice.RoomChatEchoes;
import gg.projects.mobile.voice.VoiceLink;

/**
 * SQOL (the lead's {@code sqol.md} §1.3): the voice room's text chat inside the room sheet, for the people sitting in the
 * room. The chat's own pieces: its message rows ({@code item_projects_chat_message}: bubbles, stickers, quiet lines of
 * deleted messages, the time, a refusal under the message), its field with the emoji and "Küp" sticker picker in the
 * keyboard's place, its words for every refusal, its report dialog. No reactions, replies, edits or typing (v1), no
 * notification: the call bar counts what came while the room was closed.
 *
 * <p>Holding a message: "Kopyala", "Herkesten Sil" (the player's own; the room's leader anyone's, a moderator a member's),
 * "Rapor Et" (someone else's); a send that did not go: "Tekrar Dene" (a wait or a lost connection) and "Sil".
 */
public final class RoomChatPanel {
    /** What the room sheet tells the chat. */
    public interface Host {
        /** Someone's profile for a face (the friends feature's, else the room's card); null when unknown. */
        @Nullable
        SocialModel.Profile profile(String u);

        /** Someone's name (a neutral word when unknown). */
        String name(String u);

        /** The field took or lost the focus (the sheet folds its people away while the player types). */
        void typing(boolean on);
    }

    /** The service's limit for one message; the field holds more and the chat's check says it. */
    private static final int FIELD_MAX = 1200;
    /** An unsent text of the room's field and its room, kept while the app runs (the sheet closed with it). */
    @Nullable private static String sDraftRoom;
    @Nullable private static String sDraft;
    /** Messages of one sender this close together are one group (one name, one time). */
    private static final long GROUP_MS = 5 * 60_000L;

    private final FragmentActivity mActivity;
    private final Host mHost;
    private final float mDp;
    private final LinearLayout mRoot;
    private final RecyclerView mList;
    private final TextView mEmpty;
    private final TextView mProblem;
    private final EditText mInput;
    private final ImageButton mEmoji;
    private final ImageButton mSend;
    private final FrameLayout mPickerSlot;
    private final Adapter mAdapter = new Adapter();
    /** Sends shown the moment they go, and the rows' identity (after 0.5.4). */
    private final RoomChatEchoes mEchoes = new RoomChatEchoes();
    @Nullable private RoomChat.View mChat;
    private boolean mLive;
    private boolean mUpdated;
    private boolean mShown;
    private boolean mPickerOpen;
    /** The newest message told to the voice process as seen. */
    private long mSeenSent;

    public RoomChatPanel(FragmentActivity activity, Host host) {
        mActivity = activity;
        mHost = host;
        Context c = activity;
        mDp = c.getResources().getDisplayMetrics().density;
        mRoot = new LinearLayout(c);
        mRoot.setOrientation(LinearLayout.VERTICAL);

        FrameLayout frame = new FrameLayout(c);
        mList = new RecyclerView(c);
        LinearLayoutManager layout = new LinearLayoutManager(c);
        layout.setStackFromEnd(true);
        mList.setLayoutManager(layout);
        mList.setAdapter(mAdapter);
        mList.setClipToPadding(false);
        mList.setPadding(0, dp(4), 0, dp(4));
        mList.setVerticalScrollBarEnabled(false);
        frame.addView(mList, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // The empty chat's one sentence (the lead's words): who sees it and that it goes with the room.
        mEmpty = new TextView(c);
        mEmpty.setText(R.string.projects_room_chat_empty);
        mEmpty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mEmpty.setTextColor(ContextCompat.getColor(c, R.color.projects_ash_400));
        mEmpty.setGravity(Gravity.CENTER);
        mEmpty.setPadding(dp(24), dp(12), dp(24), dp(12));
        font(mEmpty, R.font.figtree_regular);
        frame.addView(mEmpty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        mRoot.addView(frame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // A refusal before sending, in the chat's own words (read out politely).
        mProblem = new TextView(c);
        mProblem.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        mProblem.setTextColor(ContextCompat.getColor(c, R.color.projects_flare_400));
        mProblem.setPadding(dp(8), dp(4), dp(8), 0);
        mProblem.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        mProblem.setVisibility(View.GONE);
        font(mProblem, R.font.figtree_regular);
        mRoot.addView(mProblem);

        LinearLayout composer = new LinearLayout(c);
        composer.setOrientation(LinearLayout.HORIZONTAL);
        composer.setGravity(Gravity.BOTTOM);
        composer.setPadding(0, dp(6), 0, 0);
        mEmoji = new ImageButton(c);
        mEmoji.setImageResource(R.drawable.projects_ic_chat2_smiley);
        mEmoji.setImageTintList(ContextCompat.getColorStateList(c, R.color.projects_ash_300));
        mEmoji.setBackgroundResource(R.drawable.projects_icon_button_ripple);
        mEmoji.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mEmoji.setPadding(dp(12), dp(12), dp(12), dp(12));
        mEmoji.setContentDescription(c.getString(R.string.projects_picker_open));
        mEmoji.setOnClickListener(v -> togglePicker());
        Press.attach(mEmoji, Press.Style.ICON);
        composer.addView(mEmoji, new LinearLayout.LayoutParams(dp(48), dp(48)));
        mInput = new EditText(c);
        mInput.setBackgroundResource(R.drawable.projects_chat_input);
        mInput.setHint(R.string.projects_room_chat_hint);
        mInput.setHintTextColor(ContextCompat.getColor(c, R.color.projects_ash_400));
        mInput.setTextColor(ContextCompat.getColor(c, R.color.projects_bone_100));
        mInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        mInput.setMinHeight(dp(48));
        mInput.setMaxLines(5);
        mInput.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        mInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        if (android.os.Build.VERSION.SDK_INT >= 26) mInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        mInput.setFilters(new InputFilter[] {new InputFilter.LengthFilter(FIELD_MAX)});
        mInput.setPadding(dp(16), dp(10), dp(16), dp(10));
        font(mInput, R.font.figtree_regular);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        inputLp.setMarginStart(dp(2));
        composer.addView(mInput, inputLp);
        mSend = new ImageButton(c);
        mSend.setImageResource(R.drawable.projects_ic_send);
        mSend.setBackgroundResource(R.drawable.projects_chat_send_bg);
        mSend.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mSend.setPadding(dp(13), dp(13), dp(13), dp(13));
        mSend.setContentDescription(c.getString(R.string.projects_chat_send));
        mSend.setEnabled(false);
        mSend.setOnClickListener(v -> submit());
        Press.attach(mSend, Press.Style.ICON);
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(dp(48), dp(48));
        sendLp.setMarginStart(dp(8));
        composer.addView(mSend, sendLp);
        mRoot.addView(composer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // The picker in the keyboard's place, under the field (the chat's 0.4.5 way).
        mPickerSlot = new FrameLayout(c);
        mPickerSlot.setVisibility(View.GONE);
        mRoot.addView(mPickerSlot, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (mProblem.getVisibility() == View.VISIBLE) mProblem.setVisibility(View.GONE);
                updateSend();
            }
        });
        mInput.setOnFocusChangeListener((v, focus) -> {
            if (focus) closePicker(false);
            mHost.typing(focus);
        });
        mInput.setOnClickListener(v -> {
            if (mPickerOpen) closePicker(true);
        });
    }

    public View view() {
        return mRoot;
    }

    /** The room chat as it is now; {@code live}: the room is connected (sending works). */
    public void update(@Nullable RoomChat.View chat, boolean live) {
        // The room's state comes with every speaking ring: nothing to do unless the chat or the connection changed.
        if (mUpdated && chat == mChat && live == mLive) return;
        mUpdated = true;
        if (chat != null && (mChat == null || !chat.room.equals(mChat.room)) && chat.room.equals(sDraftRoom)
                && mInput.getText().length() == 0 && sDraft != null && !sDraft.isEmpty()) {
            // The text the player had typed when the sheet went (this room's, this run of the app).
            mInput.setText(sDraft);
            mInput.setSelection(mInput.getText().length());
        }
        // A new room (a move): the last room's own sends do not carry over.
        if (chat != null && mChat != null && !chat.room.equals(mChat.room)) mEchoes.clear();
        mChat = chat;
        mLive = live;
        mInput.setEnabled(live);
        mEmoji.setEnabled(live);
        mEmoji.setAlpha(live ? 1f : 0.5f);
        updateSend();
        redraw();
    }

    /** A send shows at once as waiting (the voice process's copy takes its row about 200 ms later). */
    private void echo(@Nullable String text, @Nullable String sticker) {
        RoomChat.View chat = mChat;
        if (chat == null) return;
        mEchoes.add(text, sticker, chat.me, android.os.SystemClock.elapsedRealtime());
        redraw();
        // An echo nothing answered goes (no stay to send it in).
        mList.postDelayed(this::redraw, RoomChatEchoes.KEEP_MS + 100);
    }

    /** The rows again: the chat as last heard, with the sends not answered yet (shown the moment they went). */
    private void redraw() {
        RoomChat.View chat = mChat;
        List<Row> rows = rows(mEchoes.rows(chat, android.os.SystemClock.elapsedRealtime()));
        mEmpty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        boolean atEnd = !mList.canScrollVertically(1);
        boolean grew = mAdapter.submit(rows);
        boolean mineLast = !rows.isEmpty() && rows.get(rows.size() - 1).line.mine;
        // Follow the newest message while the player is at the end (or just sent it); else the place stays.
        if (grew && (atEnd || mineLast)) mList.post(() -> mList.scrollToPosition(Math.max(0, mAdapter.getItemCount() - 1)));
        markSeen();
    }

    /** The chat is on screen (the sheet is open with it): what shows counts as seen. */
    public void setShown(boolean shown) {
        mShown = shown;
        markSeen();
    }

    /** "Geri" while the picker is open closes it first. True when it did. */
    public boolean back() {
        if (!mPickerOpen) return false;
        closePicker(false);
        return true;
    }

    /** The sheet goes: the keyboard and the picker go with it; an unsent text waits for the room's sheet to come back. */
    public void close() {
        RoomChat.View chat = mChat;
        sDraftRoom = chat == null ? null : chat.room;
        sDraft = mInput.getText().toString();
        closePicker(false);
        InputMethodManager imm = (InputMethodManager) mActivity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(mInput.getWindowToken(), 0);
    }

    private void markSeen() {
        RoomChat.View chat = mChat;
        if (!mShown || chat == null) return;
        RoomChat.Line newest = chat.newest();
        if (newest == null || newest.at <= mSeenSent) return;
        mSeenSent = newest.at;
        VoiceLink.get(mActivity).chatSeen(newest.at);
    }

    private void updateSend() {
        boolean ready = mLive && mInput.getText().toString().trim().length() > 0;
        mSend.setEnabled(ready);
    }

    // ---- sending ----

    private void submit() {
        Context c = mActivity;
        String text = mInput.getText().toString();
        SocialChat chat = SocialService.get(c).chat();
        // The chat's own checks first (the room checks again with the same rules).
        ChatText.Verdict verdict = chat.verdict(text);
        String why;
        switch (verdict.problem) {
            case NONE:
                VoiceLink.get(c).chatSend(text.trim());
                echo(text.trim(), null);
                mInput.setText("");
                mList.post(() -> mList.scrollToPosition(Math.max(0, mAdapter.getItemCount() - 1)));
                return;
            case EMPTY:
                return;
            case TOO_LONG:
                why = c.getString(R.string.projects_chat_too_long);
                break;
            case LINK:
                why = ChatFragment.linkWords(c, verdict.rule, text);
                break;
            case PHONE:
                why = c.getString(R.string.projects_chat_phone);
                break;
            case WORDS:
            default:
                why = c.getString(R.string.projects_chat_words);
                break;
        }
        Haptics.reject(mSend);
        mProblem.setText(why);
        mProblem.setVisibility(View.VISIBLE);
    }

    private void togglePicker() {
        if (mPickerOpen) {
            closePicker(true);
            return;
        }
        InputMethodManager imm = (InputMethodManager) mActivity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(mInput.getWindowToken(), 0);
        boolean stickers = SocialService.get(mActivity).chat().stickerSets().contains("pl1");
        // The list keeps showing the newest messages while the picker takes room under it (the emulator pass saw it
        // jump to the oldest once).
        final boolean atEnd = !mList.canScrollVertically(1);
        mPickerOpen = true;
        EmojiSheet.inline(mActivity, stickers, new EmojiSheet.Pick() {
            @Override
            public void emoji(String emoji) {
                insert(emoji);
            }

            @Override
            public void sticker(String ref, View cell) {
                if (!mLive) {
                    Haptics.reject(cell);
                    return;
                }
                VoiceLink.get(mActivity).chatSticker(ref);
                echo(null, ref);
                EmojiStore.usedSticker(mActivity, ref);
                Haptics.confirm(cell);
                mList.post(() -> mList.scrollToPosition(Math.max(0, mAdapter.getItemCount() - 1)));
            }
        }, new EmojiSheet.Inline() {
            @Override
            public void ready(View panel) {
                if (!mPickerOpen) return;
                mPickerSlot.removeAllViews();
                mPickerSlot.addView(panel);
                mPickerSlot.setVisibility(View.VISIBLE);
                if (atEnd) mList.post(() -> mList.scrollToPosition(Math.max(0, mAdapter.getItemCount() - 1)));
                if (Motion.enabled(mActivity)) {
                    mPickerSlot.setAlpha(0f);
                    mPickerSlot.setTranslationY(12 * mDp);
                    mPickerSlot.animate().alpha(1f).translationY(0f).setDuration(Motion.ENTER_MS).setInterpolator(Motion.EASE_OUT).start();
                }
            }

            @Override
            public void close() {
                closePicker(false);
            }
        });
    }

    private void closePicker(boolean keyboard) {
        if (!mPickerOpen) return;
        mPickerOpen = false;
        mPickerSlot.animate().cancel();
        mPickerSlot.setAlpha(1f);
        mPickerSlot.setTranslationY(0f);
        mPickerSlot.removeAllViews();
        mPickerSlot.setVisibility(View.GONE);
        if (keyboard) {
            mInput.requestFocus();
            InputMethodManager imm = (InputMethodManager) mActivity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(mInput, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void insert(String emoji) {
        int start = Math.max(0, Math.min(mInput.getSelectionStart(), mInput.getSelectionEnd()));
        int end = Math.max(0, Math.max(mInput.getSelectionStart(), mInput.getSelectionEnd()));
        Editable text = mInput.getText();
        if (ChatText.length(text.toString()) + ChatText.length(emoji) > FIELD_MAX) return;
        text.replace(start, end, emoji);
        mInput.setSelection(Math.min(text.length(), start + emoji.length()));
    }

    // ---- what a message offers ----

    private void menu(final RoomChat.Line line) {
        final FragmentActivity activity = mActivity;
        final RoomChat.View chat = mChat;
        if (chat == null || RoomChat.DELETED.equals(line.state)) return;
        View sheet = LayoutInflater.from(activity).inflate(R.layout.dialog_projects_actions, null, false);
        TextView quote = sheet.findViewById(R.id.projects_actions_quote);
        quote.setText(line.sticker() ? EmojiStore.stickerName(activity, line.st) : line.text);
        quote.setVisibility(View.VISIBLE);
        LinearLayout actions = sheet.findViewById(R.id.projects_actions_list);
        final AlertDialog dialog = new AlertDialog.Builder(activity).setView(sheet).create();
        if (RoomChat.FAILED.equals(line.state) || RoomChat.PENDING.equals(line.state)) {
            if (line.retriable()) {
                actions.addView(CrewViews.actionRow(activity, R.drawable.projects_ic_arrows_clockwise, activity.getString(R.string.projects_retry),
                        R.color.projects_bone_100, v -> {
                            dialog.dismiss();
                            VoiceLink.get(activity).chatRetry(line.rid);
                        }));
            }
            if (!line.sticker()) actions.addView(copyRow(activity, dialog, line));
            if (RoomChat.FAILED.equals(line.state)) {
                actions.addView(CrewViews.actionRow(activity, R.drawable.projects_ic_trash, activity.getString(R.string.projects_chat_discard),
                        R.color.projects_flare_400, v -> {
                            dialog.dismiss();
                            VoiceLink.get(activity).chatDiscard(line.rid);
                        }));
            }
            if (actions.getChildCount() == 0) return;
            dialog.show();
            return;
        }
        if (!line.sticker()) actions.addView(copyRow(activity, dialog, line));
        if (line.canDelete) {
            actions.addView(CrewViews.actionRow(activity, R.drawable.projects_ic_trash, activity.getString(R.string.projects_chat_delete),
                    R.color.projects_flare_400, v -> {
                        dialog.dismiss();
                        SocialUi.confirmDanger(activity, activity.getString(R.string.projects_chat_delete_ask), null,
                                activity.getString(R.string.projects_chat_delete), () -> VoiceLink.get(activity).chatDelete(line.id, answer -> {
                                    if (answer.ok()) {
                                        SocialUi.toast(activity, activity.getString(R.string.projects_chat_deleted));
                                    } else if (answer.refused != gg.projects.mobile.voice.VoiceAnswer.Refusal.NOT_FOUND) {
                                        // Already gone is no trouble; anything else is said.
                                        SocialUi.toast(activity, activity.getString(answer.refused == gg.projects.mobile.voice.VoiceAnswer.Refusal.NOT_ALLOWED
                                                ? R.string.projects_room_chat_not_allowed : R.string.projects_room_chat_delete_failed));
                                    }
                                }));
                    }));
        }
        if (!line.mine && line.seal != null && SocialService.get(activity).gate().reports) {
            actions.addView(CrewViews.divider(activity));
            actions.addView(CrewViews.actionRow(activity, R.drawable.projects_ic_flag, activity.getString(R.string.projects_report),
                    R.color.projects_bone_100, v -> {
                        dialog.dismiss();
                        report(chat, line);
                    }));
        }
        if (actions.getChildCount() == 0) return;
        dialog.show();
    }

    private static View copyRow(final FragmentActivity activity, final AlertDialog dialog, final RoomChat.Line line) {
        return CrewViews.actionRow(activity, R.drawable.projects_ic_copy, activity.getString(R.string.projects_chat_copy),
                R.color.projects_bone_100, v -> {
                    dialog.dismiss();
                    SocialUi.copy(activity, "Projects", line.text == null ? "" : line.text);
                    Haptics.confirm(v);
                    SocialUi.toast(activity, activity.getString(R.string.projects_social_copied));
                });
    }

    /**
     * "Rapor Et": the chat's report of a message (SQOL-S: the sealed message and up to 20 earlier ones of the room, the
     * conversation {@code v:<room>}, the id as the message's time).
     */
    private void report(RoomChat.View chat, RoomChat.Line line) {
        List<ChatMessage> before = new ArrayList<>();
        for (RoomChat.Line l : chat.before(line)) before.add(message(chat.room, l));
        SocialModel.Profile sender = mHost.profile(line.u);
        ReportDialog.message(mActivity, message(chat.room, line), before, sender);
    }

    /** A room chat message as the chat's report carries one ({@link ChatMessage#wire}). */
    static ChatMessage message(String room, RoomChat.Line line) {
        return new ChatMessage("v:" + room, line.at, line.u, line.text == null ? "" : line.text, line.st,
                line.seal == null ? "" : line.seal, 0, null, 0, null, null, Collections.<ChatMessage.Reaction>emptyList());
    }

    /** What a refused send says, in the chat's own words (SQOL-S's codes; the reasons are the chat's). */
    static String failureText(Context c, RoomChat.Line line) {
        String code = line.failure == null ? "failed" : line.failure;
        switch (code) {
            case "chat_rejected":
                if ("link".equals(line.reason)) {
                    return line.rule == null ? c.getString(R.string.projects_chat_link)
                            : ChatFragment.linkWords(c, ChatLinks.Rule.of(line.rule), line.text == null ? "" : line.text);
                }
                if ("phone".equals(line.reason)) return c.getString(R.string.projects_chat_phone);
                return c.getString(R.string.projects_chat_words);
            case "rate_limited":
                return c.getString(R.string.projects_chat_rate);
            case "chat_muted":
                return line.until > 0 ? c.getString(R.string.projects_chat_muted_until, SocialUi.moment(c, line.until))
                        : c.getString(R.string.projects_chat_failed);
            case "limited":
                return c.getString(SocialUi.limitedRes(c, R.string.projects_links_limited, R.string.projects_links_limited_account));
            case "chat_feature_off":
                return c.getString(R.string.projects_chat2_feature_off);
            case "chat_off":
                return c.getString(R.string.projects_chat_off);
            case "bad_request":
                return c.getString(R.string.projects_chat_not_sent);
            default:
                return c.getString(R.string.projects_chat_failed);
        }
    }

    // ---- the list ----

    private static final class Row {
        final RoomChat.Line line;
        /** The row's identity: one row from the echo through the waiting send to the room's copy (RoomChatEchoes). */
        final String key;
        final boolean first;
        final boolean last;
        final String content;

        Row(RoomChat.Line line, String key, boolean first, boolean last) {
            this.line = line;
            this.key = key;
            this.first = first;
            this.last = last;
            content = line.id + "|" + line.state + "|" + line.text + "|" + line.st + "|" + line.by + "|" + line.failure + "|"
                    + line.canDelete + "|" + line.at + "|" + first + "|" + last;
        }
    }

    private List<Row> rows(List<RoomChat.Line> lines) {
        List<Row> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            RoomChat.Line line = lines.get(i);
            boolean first = i == 0 || !together(lines.get(i - 1), line);
            boolean last = i == lines.size() - 1 || !together(line, lines.get(i + 1));
            out.add(new Row(line, mEchoes.key(line), first, last));
        }
        return out;
    }

    private static boolean together(RoomChat.Line a, RoomChat.Line b) {
        if (!a.u.equals(b.u) || RoomChat.DELETED.equals(a.state) || RoomChat.DELETED.equals(b.state)) return false;
        // A send without the room's time yet sits right after the player's last message.
        if (a.at == 0 || b.at == 0) return true;
        return Math.abs(b.at - a.at) < GROUP_MS;
    }

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private List<Row> mRows = new ArrayList<>();
        private final Map<String, Long> mIds = new HashMap<>();
        /** Messages that came while the sheet was open (they rise in; a sticker moves once). */
        private final Set<String> mFresh = new HashSet<>();

        Adapter() {
            setHasStableIds(true);
        }

        /** True when the list gained a newest message. */
        boolean submit(final List<Row> rows) {
            final List<Row> old = mRows;
            mRows = rows;
            if (old.isEmpty()) {
                notifyDataSetChanged();
                return !rows.isEmpty();
            }
            String oldLast = old.get(old.size() - 1).key;
            boolean grew = !rows.isEmpty() && !rows.get(rows.size() - 1).key.equals(oldLast);
            if (grew) {
                Set<String> known = new HashSet<>();
                for (Row r : old) known.add(r.key);
                for (int i = rows.size() - 1; i >= 0 && !known.contains(rows.get(i).key); i--) mFresh.add(rows.get(i).key);
            }
            DiffUtil.calculateDiff(new DiffUtil.Callback() {
                @Override
                public int getOldListSize() {
                    return old.size();
                }

                @Override
                public int getNewListSize() {
                    return rows.size();
                }

                @Override
                public boolean areItemsTheSame(int o, int n) {
                    return old.get(o).key.equals(rows.get(n).key);
                }

                @Override
                public boolean areContentsTheSame(int o, int n) {
                    return old.get(o).content.equals(rows.get(n).content);
                }
            }).dispatchUpdatesTo(this);
            return grew;
        }

        @Override
        public long getItemId(int position) {
            String id = mRows.get(position).key;
            Long stable = mIds.get(id);
            if (stable == null) {
                stable = (long) mIds.size();
                mIds.put(id, stable);
            }
            return stable;
        }

        @Override
        public int getItemCount() {
            return mRows.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_projects_chat_message, parent, false);
            Press.attach(view.findViewById(R.id.projects_msg_box), Press.Style.CARD);
            Press.attach(view.findViewById(R.id.projects_msg_sticker_box), Press.Style.CARD);
            Press.attach(view.findViewById(R.id.projects_msg_failed), Press.Style.BUTTON);
            ChatLinkViews.attachTouch(view.findViewById(R.id.projects_msg_bubble));
            return new RecyclerView.ViewHolder(view) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Row row = mRows.get(position);
            bind(holder.itemView, row, mFresh.remove(row.key));
        }
    }

    private void bind(View view, final Row row, boolean fresh) {
        final Context context = view.getContext();
        final RoomChat.Line e = row.line;
        final boolean mine = e.mine;
        final boolean deleted = RoomChat.DELETED.equals(e.state);
        final boolean pending = RoomChat.PENDING.equals(e.state);
        final boolean failed = RoomChat.FAILED.equals(e.state);
        LinearLayout root = (LinearLayout) view;
        root.setGravity((mine ? Gravity.END : Gravity.START) | Gravity.BOTTOM);
        root.setPadding(root.getPaddingLeft(), dp(row.first ? 6 : 1), root.getPaddingRight(), 0);
        final LinearLayout column = view.findViewById(R.id.projects_msg_column);
        column.setGravity(mine ? Gravity.END : Gravity.START);

        // A room has many people: others' messages carry their face and name (the crew chat's way).
        View faceSlot = view.findViewById(R.id.projects_msg_face_slot);
        AvatarView avatar = view.findViewById(R.id.projects_msg_avatar);
        View nameRow = view.findViewById(R.id.projects_msg_name_row);
        TextView name = view.findViewById(R.id.projects_msg_name);
        view.findViewById(R.id.projects_msg_tag).setVisibility(View.GONE);
        String who = mine ? context.getString(R.string.projects_crew_you) : mHost.name(e.u);
        faceSlot.setVisibility(mine ? View.GONE : View.VISIBLE);
        avatar.setVisibility(!mine && row.last ? View.VISIBLE : View.INVISIBLE);
        if (!mine && row.last) {
            avatar.setProfile(mHost.profile(e.u));
            avatar.setSurfaceColor(ContextCompat.getColor(context, R.color.projects_obsidian_900));
        }
        nameRow.setVisibility(!mine && row.first ? View.VISIBLE : View.GONE);
        if (!mine && row.first) name.setText(who);

        // No replies, reactions or link cards in a room's chat (v1).
        view.findViewById(R.id.projects_msg_quote).setVisibility(View.GONE);
        view.findViewById(R.id.projects_msg_reactions).setVisibility(View.GONE);
        view.findViewById(R.id.projects_msg_card).setVisibility(View.GONE);
        view.findViewById(R.id.projects_msg_link_refused).setVisibility(View.GONE);
        view.findViewById(R.id.projects_msg_seen).setVisibility(View.GONE);

        final LinearLayout box = view.findViewById(R.id.projects_msg_box);
        final TextView bubble = view.findViewById(R.id.projects_msg_bubble);
        View stickerBox = view.findViewById(R.id.projects_msg_sticker_box);
        ImageView sticker = view.findViewById(R.id.projects_msg_sticker);
        TextView unknown = view.findViewById(R.id.projects_msg_sticker_unknown);
        int listWidth = mList.getWidth() > 0 ? mList.getWidth() : context.getResources().getDisplayMetrics().widthPixels;
        bubble.setMaxWidth(Math.round(Math.max(200 * mDp, listWidth * 0.78f)) - dp(28));
        boolean isSticker = e.sticker() && !deleted;
        boolean big = !isSticker && !deleted && EmojiStore.isBigEmoji(e.text);
        stickerBox.setVisibility(isSticker ? View.VISIBLE : View.GONE);
        box.setVisibility(isSticker ? View.GONE : View.VISIBLE);
        String said;
        if (isSticker) {
            int res = EmojiStore.stickerRes(context, e.st);
            String stickerName = EmojiStore.stickerName(context, e.st);
            sticker.setVisibility(res != 0 ? View.VISIBLE : View.GONE);
            unknown.setVisibility(res != 0 ? View.GONE : View.VISIBLE);
            if (res != 0) sticker.setImageResource(res);
            said = mine ? context.getString(R.string.projects_stickers_sent_mine, stickerName == null ? "" : stickerName)
                    : context.getString(R.string.projects_stickers_sent, who, stickerName == null ? "" : stickerName);
            stickerBox.setContentDescription(said);
            // The row's key, not the message's id: a send that the room answered is the same row (its motion goes on).
            boolean sameRow = row.key.equals(sticker.getTag(R.id.projects_chat_sticker_row));
            sticker.setTag(R.id.projects_chat_sticker_row, row.key);
            if (!sameRow) EmojiStore.stopSticker(sticker);
            if (fresh && res != 0) EmojiStore.playSticker(sticker, e.st, true);
        } else if (deleted) {
            // The chat's quiet line in the message's place: who took it, no text, nothing to do.
            said = deletedText(context, e);
            bubble.setText(said);
            bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            bubble.setTypeface(bubble.getTypeface(), android.graphics.Typeface.ITALIC);
            bubble.setTextColor(ContextCompat.getColor(context, R.color.projects_ash_400));
            box.setBackground(ChatFragment.deletedShape(context, mine, row.first, row.last));
            box.setPadding(dp(14), dp(9), dp(14), dp(9));
            Drawable gone = ContextCompat.getDrawable(context, R.drawable.projects_ic_prohibit);
            if (gone != null) {
                gone = gone.mutate();
                gone.setTint(ContextCompat.getColor(context, R.color.projects_ash_400));
                gone.setBounds(0, 0, dp(15), dp(15));
            }
            bubble.setCompoundDrawablesRelative(gone, null, null, null);
            bubble.setCompoundDrawablePadding(dp(6));
        } else {
            String text = e.text == null ? "" : e.text;
            said = text;
            // Links the chat's rules let through are tappable (the unknown-site warning first); no card in a room.
            List<ChatLinks.MessageLink> links = ChatLinks.messageLinks(text, SocialService.get(context).chat().linkLists());
            bubble.setText(ChatLinkViews.withLinks(context, text, links,
                    link -> ChatLinkViews.open(mActivity, link.link.url, link.host, link.trusted)));
            bubble.setTypeface(ChatFragment.regularFace(context), android.graphics.Typeface.NORMAL);
            bubble.setTextColor(ContextCompat.getColor(context, R.color.projects_bone_100));
            bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, big ? 36 : 15);
            bubble.setCompoundDrawablesRelative(null, null, null, null);
            if (big) {
                box.setBackground(null);
                box.setPadding(dp(4), 0, dp(4), 0);
            } else {
                box.setBackground(ChatFragment.bubbleShape(context, mine, row.first, row.last, failed));
                box.setPadding(dp(14), dp(9), dp(14), dp(9));
            }
        }
        final View content = isSticker ? stickerBox : box;
        content.setAlpha(pending ? 0.7f : 1f);

        TextView time = view.findViewById(R.id.projects_msg_time);
        ImageView status = view.findViewById(R.id.projects_msg_status);
        View meta = view.findViewById(R.id.projects_msg_meta);
        TextView failedLine = view.findViewById(R.id.projects_msg_failed);
        String clock = e.at > 0 ? AppDates.time(e.at) : "";
        meta.setVisibility((row.last || pending) && !failed ? View.VISIBLE : View.GONE);
        time.setText(pending ? context.getString(R.string.projects_chat_pending) : clock);
        if (mine && (pending || e.sent())) {
            status.setVisibility(View.VISIBLE);
            status.setImageResource(pending ? R.drawable.projects_ic_clock : R.drawable.projects_ic_check);
            status.setImageTintList(ContextCompat.getColorStateList(context, pending ? R.color.projects_ash_400 : R.color.projects_gold_300));
        } else {
            status.setVisibility(View.GONE);
        }
        if (failed) {
            failedLine.setVisibility(View.VISIBLE);
            final boolean retriable = e.retriable();
            String reason = failureText(context, e);
            failedLine.setText(retriable ? gg.projects.mobile.ui.Apart.of(context, reason, context.getString(R.string.projects_retry),
                    R.color.projects_bone_100) : reason);
            Drawable warn = ContextCompat.getDrawable(context, R.drawable.projects_ic_warning_circle);
            if (warn != null) {
                warn = warn.mutate();
                warn.setTint(ContextCompat.getColor(context, R.color.projects_flare_400));
                warn.setBounds(0, 0, dp(15), dp(15));
                failedLine.setCompoundDrawablesRelative(warn, null, null, null);
            }
            failedLine.setOnClickListener(v -> {
                if (retriable) {
                    Haptics.press(v);
                    VoiceLink.get(mActivity).chatRetry(e.rid);
                } else {
                    menu(e);
                }
            });
        } else {
            failedLine.setVisibility(View.GONE);
            failedLine.setOnClickListener(null);
        }

        // A new message rises into place as it fades in (the list's animator fades it); animations off: it is there.
        column.animate().cancel();
        column.setAlpha(1f);
        column.setTranslationX(0f);
        if (fresh && Motion.enabled(context)) {
            column.setTranslationY(10 * mDp);
            column.animate().translationY(0f).setDuration(260).setInterpolator(Motion.EASE_OUT).start();
        } else {
            column.setTranslationY(0f);
        }

        String state = failed ? context.getString(R.string.projects_chat_failed)
                : pending ? context.getString(R.string.projects_chat_pending) : "";
        if (!isSticker) {
            content.setContentDescription(who + ": " + said + (clock.isEmpty() ? "" : ", " + clock) + (state.isEmpty() ? "" : ", " + state));
        }
        if (deleted) {
            content.setOnLongClickListener(null);
            content.setLongClickable(false);
            bubble.setOnLongClickListener(null);
            bubble.setLongClickable(false);
            content.setOnClickListener(null);
            content.setClickable(false);
            return;
        }
        View.OnLongClickListener hold = v -> {
            Haptics.perform(v, HapticMap.Kind.LONG_PRESS);
            menu(e);
            return true;
        };
        content.setLongClickable(true);
        content.setOnLongClickListener(hold);
        bubble.setOnLongClickListener(hold);
        if (isSticker) {
            final ImageView image = sticker;
            final String ref = e.st;
            content.setOnClickListener(v -> EmojiStore.playSticker(image, ref, false));
            content.setClickable(true);
        } else {
            content.setOnClickListener(failed ? (View.OnClickListener) v -> menu(e) : null);
            content.setClickable(failed);
        }
    }

    /** Who took a message: the player, its sender, the room's leader or a moderator, or Projects' moderators. */
    static String deletedText(Context context, RoomChat.Line line) {
        if ("staff".equals(line.by)) return context.getString(R.string.projects_chat_deleted_staff);
        if ("mod".equals(line.by)) return context.getString(R.string.projects_room_chat_deleted_mod);
        return context.getString(line.mine ? R.string.projects_chat_deleted_mine : R.string.projects_chat_deleted_sender);
    }

    private int dp(int value) {
        return Math.round(value * mDp);
    }

    private static void font(TextView view, int font) {
        try {
            view.setTypeface(ResourcesCompat.getFont(view.getContext(), font));
        } catch (RuntimeException ignored) {
            // The theme's font.
        }
    }
}
