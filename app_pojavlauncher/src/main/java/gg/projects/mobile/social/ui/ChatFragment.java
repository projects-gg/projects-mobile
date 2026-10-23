package gg.projects.mobile.social.ui;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.R;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import gg.projects.mobile.mods.Motion;
import gg.projects.mobile.social.ChatBook;
import gg.projects.mobile.social.ChatConv;
import gg.projects.mobile.social.ChatMessage;
import gg.projects.mobile.social.ChatText;
import gg.projects.mobile.social.MutePresets;
import gg.projects.mobile.social.Reports;
import gg.projects.mobile.social.SocialChat;
import gg.projects.mobile.social.SocialModel;
import gg.projects.mobile.social.SocialModel.Crew;
import gg.projects.mobile.social.SocialModel.Profile;
import gg.projects.mobile.social.SocialProblem;
import gg.projects.mobile.social.SocialService;
import gg.projects.mobile.social.SocialState;
import gg.projects.mobile.ui.ContentTiles;
import gg.projects.mobile.ui.Haptics;
import gg.projects.mobile.ui.PixelSprites;
import gg.projects.mobile.ui.Press;
import gg.projects.mobile.ui.ScreenHeader;

/**
 * A conversation (M20, CHAT1): a crew's chat or a friend's direct messages. Text only; the newest
 * message at the bottom, the day above its messages and "Yeni mesajlar" above the first unread one.
 * The player's own messages show at once and say how they are doing (going, sent, not sent with
 * "Tekrar dene"); an edit shows its new text at once and "düzenlendi" after; "Herkesten sil" fades the
 * message until the service takes it. The field and the send button are round (the owner's wish), a
 * link or a phone number is named before anything is sent, and the field gives way to one line when
 * the player may not write (muted by a moderator, chat off, no longer friends or in the crew).
 *
 * <p>Motion answers the player: a new message rises 10 dp as it fades in, a state changes by a short
 * cross-fade, every one of them interruptible; nothing moves by itself, and with animations off
 * nothing moves at all.
 *
 * <p>FIX2: a message deleted for everyone stays in its place as a quiet bubble that says who deleted
 * it ("Bu mesajı sildin", "Ekip lideri sildi", ...; the desktop's words), without its text and without
 * actions. "Düzenle" puts the field in focus with the keyboard up once the menu's window has given the
 * focus back (asking while the menu still held it was why the keyboard stayed down).
 */
public class ChatFragment extends Fragment implements SocialService.Listener {
    public static final String TAG = "ProjectsChatFragment";
    public static final String ARG_CONV = "conv";

    private static final int T_MESSAGE = 0;
    private static final int T_DAY = 1;
    private static final int T_NEW = 2;
    /** M21: a crew plan as a local line at the time it was made ("Ayşe Cumartesi 21:00 için plan yaptı"). */
    private static final int T_PLAN = 3;
    /** Messages of one player closer together than this form a group (one name, one face, one time). */
    private static final long GROUP_MS = 5 * 60_000L;

    private String mConv;
    private ScreenHeader mHeader;
    private ImageButton mBell;
    private RecyclerView mList;
    private LinearLayoutManager mLayout;
    private final Adapter mAdapter = new Adapter();
    private TextView mNotice;
    private TextView mEmpty;
    private TextView mJump;
    private TextView mClosed;
    private TextView mProblem;
    private TextView mCounter;
    private View mProblemRow;
    private View mComposer;
    private View mEditBar;
    private TextView mEditText;
    private View mQuick;
    private LinearLayout mQuickRow;
    private EditText mInput;
    private ImageButton mSend;
    private Button mDiscord;
    /** The message being edited (the field holds its new text), or null. */
    @Nullable
    private ChatMessage mEditing;
    /** The first unread message when the screen opened (its "Yeni mesajlar" line); -1 before, 0 none. */
    private long mUnreadAt = -1;
    /** The list follows new messages while the player is at its bottom. */
    private boolean mFollow = true;
    /** New messages that came while the player read older ones (the "Yeni mesaj" pill). */
    private int mUnseenBelow;
    private boolean mFirstRender = true;
    private final Runnable mTick = this::render;

    public ChatFragment() {
        super(R.layout.fragment_projects_chat);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle args = getArguments();
        mConv = args == null ? null : args.getString(ARG_CONV);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Context context = view.getContext();
        mHeader = view.findViewById(R.id.projects_chat_header);
        mBell = mHeader.addAction(R.drawable.projects_ic_bell, R.string.projects_chat_notifications, v -> openMute());
        mHeader.addAction(R.drawable.projects_ic_dots, R.string.projects_chat_more, v -> openMore());
        mList = view.findViewById(R.id.projects_chat_list);
        mLayout = new LinearLayoutManager(context);
        mLayout.setStackFromEnd(true);
        mList.setLayoutManager(mLayout);
        mList.setAdapter(mAdapter);
        if (Motion.enabled(context)) {
            DefaultItemAnimator animator = new DefaultItemAnimator();
            animator.setSupportsChangeAnimations(false);
            animator.setAddDuration(220);
            animator.setRemoveDuration(Motion.EXIT_MS);
            animator.setMoveDuration(Motion.ENTER_MS);
            mList.setItemAnimator(animator);
        } else {
            mList.setItemAnimator(null);
        }
        mList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                boolean atBottom = !recyclerView.canScrollVertically(1);
                if (atBottom != mFollow) {
                    mFollow = atBottom;
                    if (atBottom) {
                        mUnseenBelow = 0;
                        showJump(false);
                    }
                }
                // Near the top: the next older page from the phone's file.
                if (dy < 0 && mLayout.findFirstVisibleItemPosition() <= 3) {
                    SocialService.get(recyclerView.getContext()).chat().loadOlder(mConv);
                }
            }
        });
        mNotice = view.findViewById(R.id.projects_chat_notice);
        mEmpty = view.findViewById(R.id.projects_chat_empty);
        mJump = view.findViewById(R.id.projects_chat_jump);
        mClosed = view.findViewById(R.id.projects_chat_closed);
        mProblem = view.findViewById(R.id.projects_chat_problem);
        mCounter = view.findViewById(R.id.projects_chat_counter);
        mProblemRow = view.findViewById(R.id.projects_chat_problem_row);
        mComposer = view.findViewById(R.id.projects_chat_composer);
        mEditBar = view.findViewById(R.id.projects_chat_edit_bar);
        mEditText = view.findViewById(R.id.projects_chat_edit_text);
        mQuick = view.findViewById(R.id.projects_chat_quick);
        mQuickRow = view.findViewById(R.id.projects_chat_quick_row);
        mInput = view.findViewById(R.id.projects_chat_input);
        mSend = view.findViewById(R.id.projects_chat_send);
        mDiscord = view.findViewById(R.id.projects_chat_discord);
        mSend.setImageTintList(ContextCompat.getColorStateList(context, R.color.projects_chat_send_ink));
        Press.attach(mSend, Press.Style.ICON);
        mSend.setOnClickListener(v -> submit());
        View cancel = view.findViewById(R.id.projects_chat_edit_cancel);
        Press.attach(cancel, Press.Style.ICON);
        cancel.setOnClickListener(v -> stopEditing(true));
        Drawable down = ContextCompat.getDrawable(context, R.drawable.projects_ic_arrow_down);
        if (down != null) {
            down = down.mutate();
            down.setTint(ContextCompat.getColor(context, R.color.projects_gold_300));
            int size = Math.round(16 * getResources().getDisplayMetrics().density);
            down.setBounds(0, 0, size, size);
            mJump.setCompoundDrawablesRelative(null, null, down, null);
        }
        Press.attach(mJump, Press.Style.BUTTON);
        mJump.setOnClickListener(v -> scrollToBottom(true));
        // ICON2: the desktop's speech bubble sprite, a gold step quieter (its crew chat's empty line).
        ContentTiles.above(mEmpty, PixelSprites.Sprite.CHAT, true, 32);
        mInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                updateComposer();
            }
        });
        buildQuickReplies(context);
        if (ChatConv.isDirect(mConv)) {
            Drawable glyph = ContextCompat.getDrawable(context, R.drawable.projects_ic_discord_glyph);
            if (glyph != null) {
                glyph = glyph.mutate();
                glyph.setTint(ContextCompat.getColor(context, R.color.projects_ash_300));
                float dp = getResources().getDisplayMetrics().density;
                glyph.setBounds(0, 0, Math.round(17 * dp), Math.round(13 * dp));
                mDiscord.setCompoundDrawablesRelative(glyph, null, null, null);
            }
            mDiscord.setVisibility(View.VISIBLE);
            Press.attach(mDiscord, Press.Style.BUTTON);
            mDiscord.setOnClickListener(v -> {
                SocialService service = SocialService.get(requireContext());
                Profile other = service.state().profile(ChatConv.other(mConv, service.state().myId()));
                if (other != null) SocialUi.openDiscord(requireActivity(), other);
            });
        }
        updateComposer();
    }

    /** Opens "Mesajlar" with {@code conv} (a crew's chat or a friend's). */
    public static void open(Fragment from, String conv) {
        Bundle args = new Bundle();
        args.putString(ARG_CONV, conv);
        SocialUi.open(from, ChatFragment.class, TAG, args);
    }

    @Nullable
    @Override
    public Animator onCreateAnimator(int transit, boolean enter, int nextAnim) {
        View view = getView();
        if (view == null || !Motion.enabled(view.getContext())) return null;
        float distance = 28 * view.getResources().getDisplayMetrics().density;
        AnimatorSet set = new AnimatorSet();
        if (enter) {
            set.playTogether(ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f), ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, distance, 0f));
            set.setDuration(Motion.ENTER_MS);
            set.setInterpolator(Motion.EASE_OUT);
        } else {
            set.playTogether(ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0f), ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, 0f, distance * 0.6f));
            set.setDuration(Motion.EXIT_MS);
            set.setInterpolator(Motion.EASE_IN);
        }
        return set;
    }

    @Override
    public void onResume() {
        super.onResume();
        SocialService service = SocialService.get(requireContext());
        if (!service.signedIn() || !ChatConv.valid(mConv)) {
            SocialUi.leave(this);
            return;
        }
        ChatBook book = service.chat().book();
        if (mUnreadAt < 0 && book != null) mUnreadAt = book.firstUnread(mConv);
        service.addListener(this);
        service.conversationShown(mConv);
        service.refresh(false);
        render();
    }

    @Override
    public void onPause() {
        super.onPause();
        SocialService service = SocialService.get(requireContext());
        service.removeListener(this);
        service.conversationShown(null);
        if (mList != null) mList.removeCallbacks(mTick);
    }

    @Override
    public void onDestroyView() {
        if (mList != null) {
            mList.removeCallbacks(mTick);
            mList.setAdapter(null);
        }
        super.onDestroyView();
    }

    @Override
    public void onSocialChanged() {
        render();
    }

    // ---- what the screen shows ----

    private void render() {
        if (!isAdded() || getView() == null) return;
        Context context = requireContext();
        SocialService service = SocialService.get(context);
        if (!service.enabled() || !service.signedIn()) {
            SocialUi.leave(this);
            return;
        }
        // MOD1: a moderator's note shows on whichever friends screen is open when it comes.
        if (isResumed()) AccountNotes.showNotices(requireActivity());
        SocialChat chat = service.chat();
        SocialState state = service.state();
        ChatBook book = chat.book();
        if (book != null && mUnreadAt < 0) mUnreadAt = book.firstUnread(mConv);
        renderHeader(context, service);
        List<Row> rows = rows(context, service, book);
        int before = mAdapter.getItemCount();
        boolean grew = mAdapter.submit(rows);
        mEmpty.setVisibility(rows.isEmpty() && book != null ? View.VISIBLE : View.GONE);
        if (mFirstRender && !rows.isEmpty()) {
            mFirstRender = false;
            int unread = positionOf("new");
            if (unread >= 0) mList.scrollToPosition(unread);
            else scrollToBottom(false);
        } else if (grew && before > 0) {
            if (mFollow) {
                scrollToBottom(true);
            } else {
                mUnseenBelow++;
                showJump(true);
            }
        }
        if (isResumed()) chat.seen(mConv);
        renderComposer(context, service, state);
        // A mute by moderators ends by itself: the field comes back then.
        mList.removeCallbacks(mTick);
        SocialModel.ChatState chatState = state.chat();
        long hubNow = state.hubNow(SocialService.nowSeconds());
        if (chatState.mutedUntil != null && chatState.mutedUntil > hubNow) {
            mList.postDelayed(mTick, Math.min(3600, chatState.mutedUntil - hubNow) * 1000L + 500);
        }
    }

    private void renderHeader(Context context, SocialService service) {
        SocialState state = service.state();
        String crewId = ChatConv.crewId(mConv);
        if (crewId != null) {
            Crew crew = state.crew(crewId);
            mHeader.setTitle(crew == null ? getString(R.string.projects_chat_crew) : crew.team.name);
            int online = crew == null ? 0 : state.onlineInCrew(crewId);
            mHeader.setCaption(crew == null ? null : context.getResources().getQuantityString(R.plurals.projects_crew_online, online, online));
        } else {
            Profile other = state.profile(ChatConv.other(mConv, state.myId()));
            mHeader.setTitle(other == null ? getString(R.string.projects_chat_title) : other.name());
            mHeader.setCaption(other == null || !state.isFriend(other.id) ? null
                    : SocialUi.presenceText(context, state.presence(other.id)));
        }
        // FIX2: holding the title opens the friend's or the crew's menu (no "Mesaj gönder" / "Sohbeti aç" here).
        View title = mHeader.findViewById(R.id.projects_header_title);
        final String crewOfChat = ChatConv.crewId(mConv);
        final String other = crewOfChat == null ? ChatConv.other(mConv, state.myId()) : null;
        if (crewOfChat != null || other != null) {
            SocialMenuSheet.attach(title, mHeader.getTitleText(), v -> {
                if (!isAdded()) return true;
                if (crewOfChat != null) SocialMenuSheet.crew(requireActivity(), crewOfChat, false, true, v);
                else SocialMenuSheet.person(requireActivity(), other, null, SocialMenuSheet.Place.DIRECT_CHAT, v);
                return true;
            });
        }
        ChatBook book = service.chat().book();
        boolean muted = book != null && book.mutedAt(mConv, SocialService.nowSeconds());
        mBell.setImageResource(muted ? R.drawable.projects_ic_bell_slash : R.drawable.projects_ic_bell);
        mBell.setContentDescription(muted ? getString(R.string.projects_chat_notifications) + ", " + getString(R.string.projects_crew_muted)
                : getString(R.string.projects_chat_notifications));
    }

    private void renderComposer(Context context, SocialService service, SocialState state) {
        SocialChat chat = service.chat();
        SocialChat.Refusal can = chat.canWrite(mConv);
        String closed = null;
        switch (can) {
            case MUTED: {
                Long until = state.chat().mutedUntil;
                closed = until == null ? getString(R.string.projects_chat_not_sent)
                        : getString(R.string.projects_chat_muted_until, SocialUi.moment(context, until));
                break;
            }
            case OFF: closed = getString(R.string.projects_chat_off); break;
            case NOT_FRIENDS: closed = getString(R.string.projects_chat_not_friends); break;
            case NOT_IN_CREW: closed = getString(R.string.projects_chat_not_in_crew); break;
            case UNAVAILABLE:
                if (!service.gate().chat && state.loaded()) closed = getString(R.string.projects_chat_off);
                break;
            default:
                break;
        }
        boolean open = closed == null;
        if (!open && mEditing != null) stopEditing(false);
        mComposer.setVisibility(open ? View.VISIBLE : View.GONE);
        mClosed.setVisibility(open ? View.GONE : View.VISIBLE);
        if (!open) mClosed.setText(closed);
        if (!open) mProblemRow.setVisibility(View.GONE);
        // "Mesajlar şu an yalnız çevrimiçi olanlara gidiyor.": the mailbox's budget is spent for today.
        boolean liveOnly = open && state.chat().liveOnlyAt(state.hubNow(SocialService.nowSeconds()));
        mNotice.setVisibility(liveOnly ? View.VISIBLE : View.GONE);
        if (liveOnly) mNotice.setText(R.string.projects_chat_live_only);
        boolean quick = open && ChatConv.isCrew(mConv) && mEditing == null && mInput.getText().length() == 0;
        mQuick.setVisibility(quick ? View.VISIBLE : View.GONE);
        updateComposer();
    }

    // ---- the field ----

    private void updateComposer() {
        if (mInput == null || getContext() == null) return;
        String text = mInput.getText().toString();
        ChatText.Problem problem = ChatText.check(text);
        int length = ChatText.length(ChatText.clean(text));
        String line = null;
        switch (problem) {
            case LINK: line = getString(R.string.projects_chat_link); break;
            case PHONE: line = getString(R.string.projects_chat_phone); break;
            case TOO_LONG: line = getString(R.string.projects_chat_too_long); break;
            default: break;
        }
        boolean counter = length >= ChatText.COUNTER_FROM;
        boolean row = mComposer.getVisibility() == View.VISIBLE && (line != null || counter);
        mProblemRow.setVisibility(row ? View.VISIBLE : View.GONE);
        mProblem.setText(line);
        mCounter.setVisibility(counter ? View.VISIBLE : View.GONE);
        mCounter.setText(getString(R.string.projects_crew_count, length, ChatText.MAX));
        mCounter.setTextColor(ContextCompat.getColor(requireContext(), length > ChatText.MAX ? R.color.projects_flare_400 : R.color.projects_ash_400));
        boolean enabled = problem == ChatText.Problem.NONE
                && SocialService.get(requireContext()).chat().canWrite(mConv) == SocialChat.Refusal.NONE
                && (mEditing == null || !ChatText.clean(text).equals(mEditing.text));
        if (enabled != mSend.isEnabled()) {
            mSend.setEnabled(enabled);
            // The button answers the field: it grows a little into gold, or settles back.
            if (Motion.enabled(requireContext()) && mSend.isAttachedToWindow()) {
                mSend.animate().cancel();
                mSend.setScaleX(enabled ? 0.86f : 1.04f);
                mSend.setScaleY(enabled ? 0.86f : 1.04f);
                mSend.animate().scaleX(1f).scaleY(1f).setDuration(enabled ? 220 : 160)
                        .setInterpolator(enabled ? Motion.EASE_OUT : Motion.EASE_DRAWER).start();
            }
        }
        boolean quick = mComposer.getVisibility() == View.VISIBLE && ChatConv.isCrew(mConv) && mEditing == null && text.isEmpty();
        if (quick != (mQuick.getVisibility() == View.VISIBLE)) mQuick.setVisibility(quick ? View.VISIBLE : View.GONE);
    }

    private void submit() {
        FragmentActivity activity = requireActivity();
        SocialService service = SocialService.get(activity);
        String text = mInput.getText().toString();
        if (mEditing != null) {
            final ChatMessage editing = mEditing;
            stopEditing(false);
            mInput.setText("");
            service.chat().edit(editing.conv, editing.id, text, new SocialChat.Done() {
                @Override
                public void ok() {
                }

                @Override
                public void failed(SocialProblem problem) {
                    SocialUi.toastProblem(getContext(), problem, mSend);
                }
            });
            return;
        }
        SocialChat.Refusal refusal = service.chat().send(mConv, text);
        if (refusal == SocialChat.Refusal.NONE) {
            mInput.setText("");
            mFollow = true;
            scrollToBottom(true);
            return;
        }
        Haptics.reject(mSend);
        String why;
        switch (refusal) {
            case LINK: why = getString(R.string.projects_chat_link); break;
            case PHONE: why = getString(R.string.projects_chat_phone); break;
            case TOO_LONG: why = getString(R.string.projects_chat_too_long); break;
            case EMPTY: return;
            default: why = getString(R.string.projects_chat_not_sent); break;
        }
        mProblemRow.setVisibility(View.VISIBLE);
        mProblem.setText(why);
    }

    private void sendQuick(View chip, String text) {
        SocialChat.Refusal refusal = SocialService.get(requireContext()).chat().send(mConv, text);
        if (refusal != SocialChat.Refusal.NONE) {
            Haptics.reject(chip);
            return;
        }
        mFollow = true;
        scrollToBottom(true);
    }

    private void buildQuickReplies(Context context) {
        if (!ChatConv.isCrew(mConv)) return;
        int[] replies = {R.string.projects_crew_call_yes, R.string.projects_chat_quick_five, R.string.projects_chat_quick_where,
                R.string.projects_chat_quick_come};
        float dp = getResources().getDisplayMetrics().density;
        for (int reply : replies) {
            final String text = getString(reply);
            TextView chip = new TextView(context);
            chip.setText(text);
            chip.setTextSize(14);
            chip.setTextColor(ContextCompat.getColor(context, R.color.projects_gold_300));
            chip.setBackgroundResource(R.drawable.projects_chat_chip);
            chip.setGravity(Gravity.CENTER);
            chip.setMinHeight(Math.round(36 * dp));
            chip.setPadding(Math.round(14 * dp), 0, Math.round(14 * dp), 0);
            chip.setClickable(true);
            chip.setFocusable(true);
            try {
                chip.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(context, R.font.figtree_semibold));
            } catch (RuntimeException ignored) {
                // The theme's font.
            }
            Press.attach(chip, Press.Style.BUTTON);
            chip.setOnClickListener(v -> sendQuick(v, text));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(36 * dp));
            lp.setMarginEnd(Math.round(8 * dp));
            mQuickRow.addView(chip, lp);
        }
    }

    void startEditing(ChatMessage message) {
        mEditing = message;
        mEditText.setText(message.text);
        mEditBar.setVisibility(View.VISIBLE);
        gg.projects.mobile.ui.CardMotion.fadeIn(mEditBar);
        mSend.setImageResource(R.drawable.projects_ic_check);
        mInput.setText(message.text);
        mInput.setSelection(mInput.getText().length());
        showKeyboard(mInput);
        updateComposer();
    }

    /**
     * FIX2: the field takes the focus and the keyboard comes up. "Düzenle" is a row of the message's
     * menu, a dialog: when its row is tapped the dialog's window still has the focus, so asking for the
     * keyboard then was dropped (InputMethodManager serves only the focused window) and the focus the
     * field took went back to nothing visible. The keyboard is asked for once this window has the focus
     * again (at once when it has it), through the insets controller, with the old call as a fallback.
     */
    private void showKeyboard(final EditText field) {
        field.requestFocus();
        if (field.hasWindowFocus()) {
            field.post(() -> raiseKeyboard(field));
            return;
        }
        final ViewTreeObserver observer = field.getViewTreeObserver();
        observer.addOnWindowFocusChangeListener(new ViewTreeObserver.OnWindowFocusChangeListener() {
            @Override
            public void onWindowFocusChanged(boolean hasFocus) {
                if (!hasFocus) return;
                ViewTreeObserver live = field.getViewTreeObserver();
                if (live.isAlive()) live.removeOnWindowFocusChangeListener(this);
                field.post(() -> raiseKeyboard(field));
            }
        });
    }

    private void raiseKeyboard(EditText field) {
        if (!isAdded() || !field.isAttachedToWindow() || mEditing == null) return;
        field.requestFocus();
        android.view.Window window = requireActivity().getWindow();
        WindowCompat.getInsetsController(window, field).show(WindowInsetsCompat.Type.ime());
        InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(field, 0);
    }

    private void stopEditing(boolean clear) {
        if (mEditing == null) return;
        mEditing = null;
        mEditBar.setVisibility(View.GONE);
        mSend.setImageResource(R.drawable.projects_ic_send);
        if (clear) mInput.setText("");
        updateComposer();
    }

    // ---- header actions ----

    private void openMute() {
        final FragmentActivity activity = requireActivity();
        final SocialService service = SocialService.get(activity);
        ChatBook book = service.chat().book();
        if (book == null) return;
        long now = SocialService.nowSeconds();
        String state = null;
        if (book.mutedAt(mConv, now)) {
            long until = book.mutedUntil(mConv);
            state = until == ChatBook.MUTED_FOREVER ? getString(R.string.projects_crew_muted)
                    : getString(R.string.projects_crew_muted_until, CrewViews.moment(activity, until, now));
        }
        MuteSheet.show(activity, getString(R.string.projects_chat_notifications), mHeader.getTitleText(), state, now, null, null,
                new MuteSheet.Choice() {
                    @Override
                    public void preset(MutePresets.Preset preset, View row) {
                        Long until = MutePresets.until(preset, SocialService.nowSeconds(), java.util.TimeZone.getDefault());
                        service.chat().setMuted(mConv, until == null ? ChatBook.MUTED_FOREVER : until);
                        Haptics.confirm(row);
                    }

                    @Override
                    public void unmute(View button) {
                        service.chat().setMuted(mConv, 0);
                        Haptics.confirm(button);
                    }
                });
    }

    private void openMore() {
        final FragmentActivity activity = requireActivity();
        final SocialService service = SocialService.get(activity);
        View sheet = LayoutInflater.from(activity).inflate(R.layout.dialog_projects_actions, null, false);
        LinearLayout actions = sheet.findViewById(R.id.projects_actions_list);
        final androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(activity).setView(sheet).create();
        final Profile other = ChatConv.isDirect(mConv) ? service.state().profile(ChatConv.other(mConv, service.state().myId())) : null;
        if (other != null && service.gate().reports && service.state().isFriend(other.id)) {
            actions.addView(CrewViews.actionRow(activity, R.drawable.projects_ic_flag, getString(R.string.projects_report),
                    R.color.projects_bone_100, v -> {
                        dialog.dismiss();
                        ReportDialog.player(activity, other);
                    }));
        }
        actions.addView(CrewViews.actionRow(activity, R.drawable.projects_ic_trash, getString(R.string.projects_chat_clear),
                R.color.projects_flare_400, v -> {
                    dialog.dismiss();
                    SocialUi.confirm(activity, getString(R.string.projects_chat_clear), getString(R.string.projects_chat_clear_ask),
                            getString(R.string.projects_chat_clear), () -> {
                                service.chat().clearHistory(mConv);
                                SocialUi.toast(activity, getString(R.string.projects_chat_cleared));
                            });
                }));
        dialog.show();
    }

    // ---- rows ----

    private static final class Row {
        final int type;
        final String id;
        final String content;
        @Nullable
        final ChatBook.Entry entry;
        @Nullable
        final String label;
        final boolean first;
        final boolean last;

        Row(int type, String id, String content, @Nullable ChatBook.Entry entry, @Nullable String label, boolean first, boolean last) {
            this.type = type;
            this.id = id;
            this.content = content;
            this.entry = entry;
            this.label = label;
            this.first = first;
            this.last = last;
        }
    }

    private int positionOf(String id) {
        List<Row> rows = mAdapter.mRows;
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).id.equals(id)) return i;
        return -1;
    }

    /** When an entry was written: the hub's ms for a sent message or a tombstone, the phone's for one on its way. */
    private static long timeOf(ChatBook.Entry e) {
        return e.time();
    }

    private List<Row> rows(Context context, SocialService service, @Nullable ChatBook book) {
        List<Row> rows = new ArrayList<>();
        if (book == null) return rows;
        String me = service.state().myId();
        if (me == null) return rows;
        List<ChatBook.Entry> entries = book.entries(mConv);
        // M21 (TEAM5): the crew's plans as local lines when they were made (the mailbox holds nothing of plans).
        List<SocialModel.Plan> plans = new ArrayList<>();
        long nowSeconds = SocialService.nowSeconds();
        if (ChatConv.isCrew(mConv) && service.gate().plans) {
            plans.addAll(service.state().plans(nowSeconds, ChatConv.crewId(mConv)));
            java.util.Collections.sort(plans, (a, b) -> a.createdAt != b.createdAt ? Long.compare(a.createdAt, b.createdAt) : a.id.compareTo(b.id));
        }
        // How many plan lines come before each message (a line between two messages ends their group).
        int[] plansBefore = new int[entries.size() + 1];
        int k = 0;
        for (int i = 0; i < entries.size(); i++) {
            long at = timeOf(entries.get(i));
            while (k < plans.size() && plans.get(k).createdAt * 1000L <= at) k++;
            plansBefore[i] = k;
        }
        plansBefore[entries.size()] = plans.size();
        long lastDay = -1;
        int emitted = 0;
        for (int i = 0; i < entries.size(); i++) {
            ChatBook.Entry e = entries.get(i);
            long at = timeOf(e);
            for (; emitted < plansBefore[i]; emitted++) lastDay = addPlanLine(context, service, rows, plans.get(emitted), lastDay, nowSeconds);
            long day = dayOf(at);
            if (day != lastDay) {
                String label = dayLabel(context, at);
                rows.add(new Row(T_DAY, "day:" + day, label, null, label, false, false));
                lastDay = day;
            }
            if (mUnreadAt > 0 && e.message != null && e.message.id == mUnreadAt) {
                rows.add(new Row(T_NEW, "new", "new", null, getString(R.string.projects_chat_new), false, false));
            }
            ChatBook.Entry prev = i > 0 ? entries.get(i - 1) : null;
            ChatBook.Entry next = i + 1 < entries.size() ? entries.get(i + 1) : null;
            boolean lineBefore = i > 0 && plansBefore[i] > plansBefore[i - 1];
            boolean lineAfter = plansBefore[i + 1] > plansBefore[i];
            boolean first = prev == null || !together(prev, e, me) || (mUnreadAt > 0 && e.message != null && e.message.id == mUnreadAt)
                    || lineBefore;
            boolean last = next == null || !together(e, next, me) || (mUnreadAt > 0 && next.message != null && next.message.id == mUnreadAt)
                    || lineAfter;
            String state = e.deleted() ? "deleted:" + e.tombstone.by : e.failed() ? "failed:" + e.outbox.failure
                    : e.pending() ? "pending" : "sent";
            // "Bağlantı bekleniyor" under a waiting message follows the socket, so the row knows it too.
            boolean waiting = e.outbox != null && service.connection() != SocialService.Connection.LIVE;
            String content = e.key() + "|" + e.text() + "|" + state + "|" + (e.message != null ? e.message.editedAt : 0) + "|"
                    + first + last + "|" + e.deleting + "|" + waiting;
            if (!e.mine(me)) {
                // The sender's name and face (TalkBack reads the name in a direct conversation too): a row
                // bound before the list came is bound again once they are known.
                Profile p = service.state().profile(e.from(me));
                content += "|" + (p == null ? "" : p.name() + p.faceKey());
            }
            if (e.deleted()) content += "|" + deletedText(context, e.tombstone, me, service.state());
            rows.add(new Row(T_MESSAGE, e.key(), content, e, null, first, last));
        }
        for (; emitted < plans.size(); emitted++) lastDay = addPlanLine(context, service, rows, plans.get(emitted), lastDay, nowSeconds);
        return rows;
    }

    /** A plan's local line (with the day above it when it is another day); returns the day it is on. */
    private long addPlanLine(Context context, SocialService service, List<Row> rows, SocialModel.Plan plan, long lastDay, long nowSeconds) {
        long at = plan.createdAt * 1000L;
        long day = dayOf(at);
        if (day != lastDay) {
            String label = dayLabel(context, at);
            rows.add(new Row(T_DAY, "day:" + day, label, null, label, false, false));
        }
        String me = service.state().myId();
        Profile maker = service.state().profile(plan.by);
        // The player's own plan says "Yarın 23:30 için plan yaptın" (the sentence starts with the day).
        String text = plan.by.equals(me) ? getString(R.string.projects_plans_made_you, PlanViews.when(context, plan.at, nowSeconds))
                : getString(R.string.projects_plans_made, maker == null ? getString(R.string.projects_crew_someone) : maker.name(),
                        PlanViews.when(context, plan.at, nowSeconds, true));
        rows.add(new Row(T_PLAN, "plan:" + plan.id, "plan|" + text, null, text, false, false));
        return day;
    }

    /** Two lines of one group: the same player, the same day, at most five minutes apart. */
    private static boolean together(ChatBook.Entry a, ChatBook.Entry b, String me) {
        if (!a.from(me).equals(b.from(me))) return false;
        long ta = timeOf(a);
        long tb = timeOf(b);
        return dayOf(ta) == dayOf(tb) && Math.abs(tb - ta) <= GROUP_MS;
    }

    private static long dayOf(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return c.get(Calendar.YEAR) * 1000L + c.get(Calendar.DAY_OF_YEAR);
    }

    private static String dayLabel(Context context, long ms) {
        Calendar today = Calendar.getInstance();
        long now = dayOf(today.getTimeInMillis());
        today.add(Calendar.DAY_OF_YEAR, -1);
        long yesterday = dayOf(today.getTimeInMillis());
        long day = dayOf(ms);
        if (day == now) return context.getString(R.string.projects_chat_today);
        if (day == yesterday) return context.getString(R.string.projects_chat_yesterday);
        return DateFormat.getDateInstance(DateFormat.LONG, Locale.getDefault()).format(new Date(ms));
    }

    private void scrollToBottom(boolean smooth) {
        int count = mAdapter.getItemCount();
        if (count == 0) return;
        mUnseenBelow = 0;
        showJump(false);
        int last = count - 1;
        int visible = mLayout.findLastVisibleItemPosition();
        if (smooth && Motion.enabled(requireContext()) && visible >= 0 && last - visible < 12) mList.smoothScrollToPosition(last);
        else mList.scrollToPosition(last);
    }

    private void showJump(boolean show) {
        if (show == (mJump.getVisibility() == View.VISIBLE)) return;
        if (show) {
            mJump.setVisibility(View.VISIBLE);
            if (Motion.enabled(requireContext())) {
                mJump.setAlpha(0f);
                mJump.setTranslationY(8 * getResources().getDisplayMetrics().density);
                mJump.animate().alpha(1f).translationY(0f).setDuration(200).setInterpolator(Motion.EASE_OUT).start();
            }
        } else {
            mJump.animate().cancel();
            mJump.setVisibility(View.GONE);
        }
    }

    // ---- the adapter ----

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private List<Row> mRows = new ArrayList<>();
        private final Map<String, Long> mIds = new HashMap<>();
        /** Messages that came while the screen was open (they rise in). */
        private final Set<String> mFresh = new HashSet<>();

        Adapter() {
            setHasStableIds(true);
        }

        /** True when the list gained a newest line. */
        boolean submit(final List<Row> rows) {
            final List<Row> old = mRows;
            mRows = rows;
            if (old.isEmpty()) {
                notifyDataSetChanged();
                return !rows.isEmpty();
            }
            String oldLast = old.get(old.size() - 1).id;
            boolean grew = !rows.isEmpty() && !rows.get(rows.size() - 1).id.equals(oldLast);
            if (grew) {
                Set<String> known = new HashSet<>();
                for (Row r : old) known.add(r.id);
                for (int i = rows.size() - 1; i >= 0 && !known.contains(rows.get(i).id); i--) {
                    if (rows.get(i).type == T_MESSAGE) mFresh.add(rows.get(i).id);
                }
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
                    return old.get(o).id.equals(rows.get(n).id);
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
            String id = mRows.get(position).id;
            Long stable = mIds.get(id);
            if (stable == null) {
                stable = (long) mIds.size();
                mIds.put(id, stable);
            }
            return stable;
        }

        @Override
        public int getItemViewType(int position) {
            return mRows.get(position).type;
        }

        @Override
        public int getItemCount() {
            return mRows.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            View view;
            if (viewType == T_MESSAGE) {
                view = inflater.inflate(R.layout.item_projects_chat_message, parent, false);
                Press.attach(view.findViewById(R.id.projects_msg_bubble), Press.Style.CARD);
                Press.attach(view.findViewById(R.id.projects_msg_failed), Press.Style.BUTTON);
            } else {
                view = inflater.inflate(R.layout.item_projects_chat_day, parent, false);
            }
            return new RecyclerView.ViewHolder(view) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Row row = mRows.get(position);
            if (row.type == T_MESSAGE) {
                bindMessage(holder.itemView, row, mFresh.remove(row.id));
            } else {
                bindDay(holder.itemView, row);
            }
        }
    }

    private void bindDay(View view, Row row) {
        boolean unread = row.type == T_NEW;
        boolean plan = row.type == T_PLAN;
        TextView text = view.findViewById(R.id.projects_day_text);
        text.setText(row.label);
        text.setTextColor(ContextCompat.getColor(view.getContext(), unread || plan ? R.color.projects_gold_300 : R.color.projects_ash_400));
        // M21: a plan's line carries the calendar glyph.
        android.graphics.drawable.Drawable glyph = null;
        if (plan) {
            glyph = ContextCompat.getDrawable(view.getContext(), R.drawable.projects_ic_calendar);
            if (glyph != null) {
                glyph = glyph.mutate();
                glyph.setTint(ContextCompat.getColor(view.getContext(), R.color.projects_gold_300));
                int size = Math.round(14 * view.getResources().getDisplayMetrics().density);
                glyph.setBounds(0, 0, size, size);
            }
        }
        text.setCompoundDrawablesRelative(glyph, null, null, null);
        text.setCompoundDrawablePadding(Math.round(6 * view.getResources().getDisplayMetrics().density));
        text.setBackgroundResource(unread ? 0 : R.drawable.projects_pill_background);
        view.findViewById(R.id.projects_day_line_start).setVisibility(unread ? View.VISIBLE : View.GONE);
        view.findViewById(R.id.projects_day_line_end).setVisibility(unread ? View.VISIBLE : View.GONE);
        int line = ContextCompat.getColor(view.getContext(), unread ? R.color.projects_gold_700 : R.color.projects_obsidian_700);
        view.findViewById(R.id.projects_day_line_start).setBackgroundColor(line);
        view.findViewById(R.id.projects_day_line_end).setBackgroundColor(line);
    }

    private void bindMessage(View view, final Row row, boolean fresh) {
        final Context context = view.getContext();
        final SocialService service = SocialService.get(context);
        final ChatBook.Entry e = row.entry;
        if (e == null) return;
        String me = service.state().myId();
        if (me == null) return;
        boolean mine = e.mine(me);
        boolean crewChat = ChatConv.isCrew(mConv);
        float dp = context.getResources().getDisplayMetrics().density;
        LinearLayout root = (LinearLayout) view;
        root.setGravity((mine ? Gravity.END : Gravity.START) | Gravity.BOTTOM);
        root.setPadding(root.getPaddingLeft(), Math.round((row.first ? 6 : 1) * dp), root.getPaddingRight(), 0);
        LinearLayout column = view.findViewById(R.id.projects_msg_column);
        column.setGravity(mine ? Gravity.END : Gravity.START);

        View faceSlot = view.findViewById(R.id.projects_msg_face_slot);
        AvatarView avatar = view.findViewById(R.id.projects_msg_avatar);
        TextView name = view.findViewById(R.id.projects_msg_name);
        Profile sender = mine ? null : service.state().profile(e.from(me));
        boolean faces = crewChat && !mine;
        faceSlot.setVisibility(faces ? View.VISIBLE : View.GONE);
        avatar.setVisibility(faces && row.last ? View.VISIBLE : View.INVISIBLE);
        if (faces && row.last) {
            avatar.setProfile(sender);
            avatar.setSurfaceColor(ContextCompat.getColor(context, R.color.projects_obsidian_950));
        }
        name.setVisibility(faces && row.first ? View.VISIBLE : View.GONE);
        if (faces && row.first) name.setText(sender == null ? getString(R.string.projects_crew_someone) : sender.name());

        TextView bubble = view.findViewById(R.id.projects_msg_bubble);
        bubble.setMaxWidth(Math.round(Math.max(200 * dp, (mList.getWidth() > 0 ? mList.getWidth() : context.getResources().getDisplayMetrics().widthPixels) * 0.78f)));
        if (e.deleted()) {
            // FIX2: a quiet bubble in the message's place: who deleted it, no text, no actions.
            bubble.setText(deletedText(context, e.tombstone, me, service.state()));
            bubble.setTypeface(bubble.getTypeface(), android.graphics.Typeface.ITALIC);
            bubble.setTextColor(ContextCompat.getColor(context, R.color.projects_ash_400));
            bubble.setBackground(deletedShape(context, mine, row.first, row.last));
            Drawable gone = ContextCompat.getDrawable(context, R.drawable.projects_ic_prohibit);
            if (gone != null) {
                gone = gone.mutate();
                gone.setTint(ContextCompat.getColor(context, R.color.projects_ash_400));
                int size = Math.round(15 * dp);
                gone.setBounds(0, 0, size, size);
            }
            bubble.setCompoundDrawablesRelative(gone, null, null, null);
            bubble.setCompoundDrawablePadding(Math.round(6 * dp));
        } else {
            bubble.setText(e.text());
            bubble.setTypeface(regularFace(context), android.graphics.Typeface.NORMAL);
            bubble.setTextColor(ContextCompat.getColor(context, R.color.projects_bone_100));
            bubble.setBackground(bubbleShape(context, mine, row.first, row.last, e.failed()));
            bubble.setCompoundDrawablesRelative(null, null, null, null);
        }
        float targetAlpha = e.deleting ? 0.4f : e.pending() ? 0.7f : 1f;

        TextView time = view.findViewById(R.id.projects_msg_time);
        ImageView status = view.findViewById(R.id.projects_msg_status);
        View meta = view.findViewById(R.id.projects_msg_meta);
        TextView failed = view.findViewById(R.id.projects_msg_failed);
        boolean showMeta = row.last || e.pending() || (e.message != null && e.message.edited());
        meta.setVisibility(showMeta && !e.failed() ? View.VISIBLE : View.GONE);
        String clock = android.text.format.DateFormat.getTimeFormat(context).format(new Date(timeOf(e)));
        boolean edited = e.message != null && e.message.edited();
        boolean waiting = e.outbox != null && service.connection() != SocialService.Connection.LIVE;
        time.setText(waiting ? getString(R.string.projects_chat_waiting)
                : edited ? clock + " · " + getString(R.string.projects_chat_edited) : clock);
        String stateKey;
        if (e.deleted()) {
            status.setVisibility(View.GONE);
            stateKey = "deleted";
        } else if (mine && !e.failed()) {
            status.setVisibility(View.VISIBLE);
            boolean pending = e.pending();
            status.setImageResource(pending ? R.drawable.projects_ic_clock : R.drawable.projects_ic_check);
            status.setImageTintList(ContextCompat.getColorStateList(context, pending ? R.color.projects_ash_400 : R.color.projects_gold_300));
            stateKey = pending ? "pending" : "sent";
        } else {
            status.setVisibility(View.GONE);
            stateKey = e.failed() ? "failed" : "theirs";
        }
        if (e.failed()) {
            failed.setVisibility(View.VISIBLE);
            final boolean retriable = retriable(e.outbox.failure);
            String reason = failureText(e.outbox.failure);
            failed.setText(retriable ? reason + " · " + getString(R.string.projects_retry) : reason);
            Drawable warn = ContextCompat.getDrawable(context, R.drawable.projects_ic_warning_circle);
            if (warn != null) {
                warn = warn.mutate();
                warn.setTint(ContextCompat.getColor(context, R.color.projects_flare_400));
                int size = Math.round(15 * dp);
                warn.setBounds(0, 0, size, size);
                failed.setCompoundDrawablesRelative(warn, null, null, null);
            }
            failed.setOnClickListener(v -> {
                if (retriable) {
                    Haptics.press(v);
                    service.chat().retry(e.outbox.nonce);
                } else {
                    MessageMenu.show(requireActivity(), ChatFragment.this, mConv, e);
                }
            });
        } else {
            failed.setVisibility(View.GONE);
        }

        // A state change cross-fades (the clock turns into the check, the faded message becomes whole).
        Object before = view.getTag(R.id.projects_chat_state);
        boolean changed = before != null && before instanceof String && ((String) before).startsWith(row.id + "#")
                && !before.equals(row.id + "#" + stateKey);
        view.setTag(R.id.projects_chat_state, row.id + "#" + stateKey);
        boolean motion = Motion.enabled(context);
        bubble.animate().cancel();
        status.animate().cancel();
        if (changed && motion && e.deleted()) {
            // FIX2: the message settles into its tombstone (a quick fade and a small give).
            bubble.setAlpha(0.25f);
            bubble.setScaleX(0.97f);
            bubble.setScaleY(0.97f);
            bubble.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(Motion.EASE_OUT).start();
        } else if (changed && motion) {
            bubble.setScaleX(1f);
            bubble.setScaleY(1f);
            bubble.animate().alpha(targetAlpha).setDuration(180).setInterpolator(Motion.EASE_OUT).start();
            status.setAlpha(0f);
            status.setScaleX(0.6f);
            status.setScaleY(0.6f);
            status.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(Motion.EASE_OUT).start();
        } else {
            bubble.setAlpha(targetAlpha);
            bubble.setScaleX(1f);
            bubble.setScaleY(1f);
            status.setAlpha(1f);
            status.setScaleX(1f);
            status.setScaleY(1f);
        }
        // A new message rises into place as it fades in (the item animator fades the row).
        column.animate().cancel();
        if (fresh && motion) {
            column.setTranslationY(10 * dp);
            column.animate().translationY(0f).setDuration(260).setInterpolator(Motion.EASE_OUT).start();
        } else {
            column.setTranslationY(0f);
        }

        String who = mine ? getString(R.string.projects_crew_you) : sender == null ? "" : sender.name();
        if (e.deleted()) {
            // FIX2: a tombstone offers nothing; TalkBack reads who deleted it and when it was written.
            bubble.setContentDescription(who + ": " + bubble.getText() + ", " + clock);
            bubble.setOnLongClickListener(null);
            bubble.setLongClickable(false);
            bubble.setOnClickListener(null);
            bubble.setClickable(false);
            bindSenderMenu(view, e, me);
            return;
        }
        // TalkBack says what the line under the bubble says ("Baglanti bekleniyor" while offline).
        String state = e.failed() ? getString(R.string.projects_chat_failed) : waiting ? getString(R.string.projects_chat_waiting)
                : mine ? getString(e.pending() ? R.string.projects_chat_pending : R.string.projects_chat_sent) : "";
        bubble.setContentDescription(who + ": " + e.text() + ", " + clock + (edited ? ", " + getString(R.string.projects_chat_edited) : "")
                + (state.isEmpty() ? "" : ", " + state));
        bubble.setLongClickable(true);
        bubble.setOnLongClickListener(v -> {
            gg.projects.mobile.ui.Haptics.perform(v, gg.projects.mobile.ui.HapticMap.Kind.LONG_PRESS);
            MessageMenu.show(requireActivity(), ChatFragment.this, mConv, e);
            return true;
        });
        bindSenderMenu(view, e, me);
        bubble.setOnClickListener(e.failed() ? (View.OnClickListener) v -> MessageMenu.show(requireActivity(), ChatFragment.this, mConv, e) : null);
        bubble.setClickable(e.failed());
    }

    /**
     * FIX2: what a tombstone says, by who deleted the message (the desktop's words): the player, the
     * sender, the crew's leader or a moderator, or Projects' moderators.
     */
    static String deletedText(Context context, ChatBook.Tombstone t, @Nullable String me, SocialState state) {
        if (me != null && t.by.equals(me)) return context.getString(R.string.projects_chat_deleted_mine);
        if (t.byStaff()) return context.getString(R.string.projects_chat_deleted_staff);
        if (t.by.isEmpty() || t.bySender()) return context.getString(R.string.projects_chat_deleted_sender);
        Crew crew = state.crew(ChatConv.crewId(t.conv));
        SocialModel.Member member = crew == null ? null : crew.team.member(t.by);
        if (member != null && member.founder) return context.getString(R.string.projects_chat_deleted_leader);
        if (member != null && member.moderator) return context.getString(R.string.projects_chat_deleted_moderator);
        return context.getString(R.string.projects_chat_deleted_sender);
    }

    @Nullable
    private static android.graphics.Typeface sRegular;

    /** Figtree as the layout sets it (a bubble that showed a tombstone's italic gets it back). */
    @Nullable
    private static android.graphics.Typeface regularFace(Context context) {
        if (sRegular == null) {
            try {
                sRegular = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.figtree_regular);
            } catch (RuntimeException e) {
                sRegular = android.graphics.Typeface.DEFAULT;
            }
        }
        return sRegular;
    }

    /**
     * FIX2: in crew chat, holding someone's face or name opens the same menu as their row anywhere
     * (the crew's context: its leader may hand over or remove from here too).
     */
    private void bindSenderMenu(View view, ChatBook.Entry e, String me) {
        View avatar = view.findViewById(R.id.projects_msg_avatar);
        View name = view.findViewById(R.id.projects_msg_name);
        final String from = e.from(me);
        final String crewId = ChatConv.crewId(mConv);
        boolean menu = crewId != null && !from.equals(me);
        for (View target : new View[]{avatar, name}) {
            if (!menu) {
                target.setOnLongClickListener(null);
                target.setLongClickable(false);
                continue;
            }
            target.setOnLongClickListener(v -> {
                SocialMenuSheet.person(requireActivity(), from, crewId, SocialMenuSheet.Place.CHAT, v);
                return true;
            });
        }
    }

    /** FIX2: a tombstone's bubble: the message's shape as a quiet dashed outline, no fill. */
    private static Drawable deletedShape(Context context, boolean mine, boolean first, boolean last) {
        GradientDrawable shape = (GradientDrawable) bubbleShape(context, mine, first, last, false);
        float dp = context.getResources().getDisplayMetrics().density;
        shape.setColor(android.graphics.Color.TRANSPARENT);
        shape.setStroke(Math.max(1, Math.round(dp)), ContextCompat.getColor(context, R.color.projects_obsidian_700), 4 * dp, 3 * dp);
        return shape;
    }

    /** The bubble: rounded, with the corners toward the sender's side tighter where the group goes on. */
    private static Drawable bubbleShape(Context context, boolean mine, boolean first, boolean last, boolean failed) {
        float dp = context.getResources().getDisplayMetrics().density;
        float big = 18 * dp;
        float small = 5 * dp;
        float tl, tr, br, bl;
        if (mine) {
            tl = big;
            bl = big;
            tr = first ? big : small;
            br = small;
        } else {
            tr = big;
            br = big;
            tl = first ? big : small;
            bl = small;
        }
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadii(new float[]{tl, tl, tr, tr, br, br, bl, bl});
        shape.setColor(ContextCompat.getColor(context, mine ? R.color.projects_chat_mine : R.color.projects_chat_theirs));
        if (failed) shape.setStroke(Math.max(1, Math.round(dp)), ContextCompat.getColor(context, R.color.projects_flare_400));
        else if (mine) shape.setStroke(Math.max(1, Math.round(dp)), ContextCompat.getColor(context, R.color.projects_chat_mine_edge));
        return shape;
    }

    static boolean retriable(@Nullable ChatBook.Failure failure) {
        if (failure == null) return true;
        switch (failure) {
            case LINK:
            case PHONE:
            case WORDS:
            case INVALID:
            case NOT_FRIENDS:
            case NOT_IN_CREW:
                return false;
            default:
                return true;
        }
    }

    private String failureText(@Nullable ChatBook.Failure failure) {
        if (failure == null) return getString(R.string.projects_chat_failed);
        switch (failure) {
            case LINK: return getString(R.string.projects_chat_link);
            case PHONE: return getString(R.string.projects_chat_phone);
            case WORDS: return getString(R.string.projects_chat_words);
            case RATE: return getString(R.string.projects_chat_rate);
            case DAILY: {
                SocialModel.Me me = SocialService.get(requireContext()).state().me();
                if (me != null && me.limited != null && me.limited.active(SocialService.nowSeconds())
                        && me.limited.has(SocialModel.Limited.CHAT_MESSAGES)) {
                    return getString(R.string.projects_limited_chat_today, me.limited.perDay(SocialModel.Limited.CHAT_MESSAGES));
                }
                return getString(R.string.projects_chat_daily);
            }
            case MUTED: return getString(R.string.projects_chat_failed);
            case OFF: return getString(R.string.projects_chat_off);
            case NOT_FRIENDS: return getString(R.string.projects_chat_not_friends);
            case NOT_IN_CREW: return getString(R.string.projects_chat_not_in_crew);
            default: return getString(R.string.projects_chat_failed);
        }
    }

    /** The reports' context: up to 20 earlier messages of this conversation. */
    List<ChatMessage> context(ChatMessage message) {
        ChatBook book = SocialService.get(requireContext()).chat().book();
        return book == null ? new ArrayList<ChatMessage>() : book.before(message, Reports.CONTEXT);
    }
}
