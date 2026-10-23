package gg.projects.mobile.social.ui;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
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
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.R;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import gg.projects.mobile.mods.Motion;
import gg.projects.mobile.social.BadgeRules;
import gg.projects.mobile.social.ChatBook;
import gg.projects.mobile.social.ChatCards;
import gg.projects.mobile.social.ChatConv;
import gg.projects.mobile.social.ChatLinks;
import gg.projects.mobile.social.ChatMessage;
import gg.projects.mobile.social.ChatText;
import gg.projects.mobile.social.ChatTyping;
import gg.projects.mobile.social.MutePresets;
import gg.projects.mobile.social.Reports;
import gg.projects.mobile.social.SocialChat;
import gg.projects.mobile.social.SocialModel;
import gg.projects.mobile.social.SocialModel.Crew;
import gg.projects.mobile.social.SocialModel.Profile;
import gg.projects.mobile.social.SocialProblem;
import gg.projects.mobile.social.SocialService;
import gg.projects.mobile.social.SocialState;
import gg.projects.mobile.ui.HapticMap;
import gg.projects.mobile.ui.Haptics;
import gg.projects.mobile.ui.Press;
import gg.projects.mobile.ui.ScreenHeader;

/**
 * A conversation (M20, CHAT1): a crew's chat or a friend's direct messages. The newest message at
 * the bottom, the day above its messages and "Yeni mesajlar" above the first unread one. The player's
 * own messages show at once and say how they are doing (going, sent, not sent with "Tekrar dene"); an
 * edit shows its new text at once and "düzenlendi" after; "Herkesten sil" fades the message until the
 * service takes it. The field and the send button are round (the owner's wish), what cannot be sent is
 * named before anything goes, and the field gives way to one line when the player may not write (muted
 * by a moderator, chat off, no longer friends or in the crew).
 *
 * <p>Motion answers the player: a new message rises 10 dp as it fades in, a state changes by a short
 * cross-fade, every one of them interruptible; nothing moves by itself, and with animations off
 * nothing moves at all.
 *
 * <p>FIX2: a message deleted for everyone stays in its place as a quiet bubble that says who deleted
 * it ("Bu mesajı sildin", "Ekip lideri sildi", ...; the desktop's words), without its text and without
 * actions. "Düzenle" puts the field in focus with the keyboard up once the menu's window has given the
 * focus back (asking while the menu still held it was why the keyboard stayed down).
 *
 * <p>MOB1 (CHAT2): holding a message opens its quick reactions and its menu over the dimmed screen
 * ({@link MessageActions}); swiping a message to the right past 64 dp (a tick there, the reply arrow
 * showing) or "Yanıtla" starts a reply (the strip over the field: "Yanıtlanıyor: Ayşe", × or a sent
 * message ends it); a reply's quote sits above its bubble and a press on it goes to the message (it
 * glows once). Reactions are chips under the bubble (the player's own with a gold edge; a press opens
 * who reacted). Stickers stand without a bubble at 120 dp and move once, as they arrive and when
 * pressed (their own pattern; with animations off they only fade in). One to three emoji alone are
 * drawn big without a bubble. Links the rules let through are bone and underlined, with the hub's card
 * in the bubble. Who writes floats over the list's bottom; "Görüldü" stands under the player's last
 * message the friend has seen (direct messages, both sharing it). The emoji button starts the field.
 * TEAM6: the sender's crew tag before their name in crew chat, the friend's in a direct conversation's
 * title (a press opens its card), and "Ece ekibe katıldı" with "Selam ver" for ten minutes.
 */
public class ChatFragment extends Fragment implements SocialService.Listener {
    public static final String TAG = "ProjectsChatFragment";
    public static final String ARG_CONV = "conv";

    private static final int T_MESSAGE = 0;
    private static final int T_DAY = 1;
    private static final int T_NEW = 2;
    /** M21: a crew plan as a local line at the time it was made ("Ayşe Cumartesi 21:00 için plan yaptı"). */
    private static final int T_PLAN = 3;
    /** TEAM6: someone joined the crew ("Ece ekibe katıldı" and "Selam ver"; "Ekibe katıldın"). */
    private static final int T_JOIN = 4;
    /** Messages of one player closer together than this form a group (one name, one face, one time). */
    private static final long GROUP_MS = 5 * 60_000L;
    /** A friend's newest message counts as seen after this long on screen. */
    private static final long READ_AFTER_MS = 1_000;
    /** A typed link's card is asked for this long after the player stops typing. */
    private static final long COMPOSER_CARD_MS = 600;

    private String mConv;
    private ScreenHeader mHeader;
    private ImageButton mBell;
    @Nullable
    private CrewTagChip mHeaderTag;
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
    private ImageButton mEmoji;
    private Button mDiscord;
    private View mReplyBar;
    private TextView mReplyName;
    private TextView mReplyText;
    private ImageView mReplySticker;
    private FrameLayout mComposerCard;
    private View mTyping;
    private TextView mTypingText;
    private AvatarView mTypingFace;
    /** The message being edited (the field holds its new text), or null. */
    @Nullable
    private ChatMessage mEditing;
    /** CHAT2: the message being answered, or null. */
    @Nullable
    private ChatMessage mReplyTo;
    /** The first unread message when the screen opened (its "Yeni mesajlar" line); -1 before, 0 none. */
    private long mUnreadAt = -1;
    /** The list follows new messages while the player is at its bottom. */
    private boolean mFollow = true;
    /** New messages that came while the player read older ones (the "Yeni mesaj" pill). */
    private int mUnseenBelow;
    private boolean mFirstRender = true;
    private final Runnable mTick = this::render;
    private final Runnable mSeenCheck = this::seenCheck;
    private final Runnable mComposerCardAsk = this::askComposerCard;
    /** CHAT2: the newest of the others' messages being watched for a second (0: none). */
    private long mWatching;
    /** CHAT2: whether the "yazıyor" pill is meant to show (it still fades out while this is false). */
    private boolean mTypingShown;
    /** CHAT2: a quote pressed before its message was in the list (0: none), and when (it is given up after a while). */
    private long mPendingGoTo;
    private long mPendingGoToAt;
    private static final long PENDING_GOTO_MS = 10_000;
    /** TEAM6: joins the player said hi to in this conversation ("Selam ver" goes then). */
    private final Set<String> mGreeted = new HashSet<>();
    /** CHAT2: a message to glow once it is drawn (a quote was pressed), or null. */
    @Nullable
    private String mGlow;

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
                watchNewest();
            }
        });
        new ItemTouchHelper(new SwipeToReply(context)).attachToRecyclerView(mList);
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
        mEmoji = view.findViewById(R.id.projects_chat_emoji);
        mDiscord = view.findViewById(R.id.projects_chat_discord);
        mReplyBar = view.findViewById(R.id.projects_chat_reply_bar);
        mReplyName = view.findViewById(R.id.projects_chat_reply_name);
        mReplyText = view.findViewById(R.id.projects_chat_reply_text);
        mReplySticker = view.findViewById(R.id.projects_chat_reply_sticker);
        mComposerCard = view.findViewById(R.id.projects_chat_composer_card);
        mTyping = view.findViewById(R.id.projects_chat_typing);
        mTypingText = view.findViewById(R.id.projects_chat_typing_text);
        mTypingFace = view.findViewById(R.id.projects_chat_typing_face);
        mTypingFace.setSurfaceColor(ContextCompat.getColor(context, R.color.projects_obsidian_850));
        mSend.setImageTintList(ContextCompat.getColorStateList(context, R.color.projects_chat_send_ink));
        Press.attach(mSend, Press.Style.ICON);
        mSend.setOnClickListener(v -> submit());
        Press.attach(mEmoji, Press.Style.ICON);
        mEmoji.setOnClickListener(v -> openPicker());
        View cancel = view.findViewById(R.id.projects_chat_edit_cancel);
        Press.attach(cancel, Press.Style.ICON);
        cancel.setOnClickListener(v -> stopEditing(true));
        View replyCancel = view.findViewById(R.id.projects_chat_reply_cancel);
        Press.attach(replyCancel, Press.Style.ICON);
        replyCancel.setOnClickListener(v -> stopReply());
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
        Drawable chat = ContextCompat.getDrawable(context, R.drawable.projects_ic_chat);
        if (chat != null) {
            chat = chat.mutate();
            chat.setTint(ContextCompat.getColor(context, R.color.projects_obsidian_600));
            int size = Math.round(40 * getResources().getDisplayMetrics().density);
            chat.setBounds(0, 0, size, size);
            mEmpty.setCompoundDrawablesRelative(null, chat, null, null);
        }
        mInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                updateComposer();
                typed();
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
            // TEAM6: the friend's crew tag before their name in the title (a press opens its card).
            mHeaderTag = CrewTagChip.make(context, CrewTagChip.Size.LG);
            mHeader.setTitleLead(mHeaderTag);
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
        // CHAT2: the emoji data is read now (the quick reactions and the picker use it).
        EmojiStore.whenLoaded(requireContext(), () -> { });
        render();
    }

    @Override
    public void onPause() {
        super.onPause();
        SocialService service = SocialService.get(requireContext());
        service.removeListener(this);
        service.conversationShown(null);
        if (mList != null) {
            mList.removeCallbacks(mTick);
            mList.removeCallbacks(mSeenCheck);
            mList.removeCallbacks(mComposerCardAsk);
        }
        mWatching = 0;
        // CHAT2: no one writes while the screen is away (the pill's dots stop with it).
        if (mTyping != null) {
            mTyping.animate().cancel();
            mTyping.setVisibility(View.GONE);
        }
        mTypingShown = false;
    }

    @Override
    public void onDestroyView() {
        if (mList != null) {
            mList.removeCallbacks(mTick);
            mList.removeCallbacks(mSeenCheck);
            mList.removeCallbacks(mComposerCardAsk);
            mList.setAdapter(null);
        }
        // A new view starts without the edit and reply bars: their states go with this one.
        mEditing = null;
        mReplyTo = null;
        mTypingShown = false;
        mPendingGoTo = 0;
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
        // CHAT2: a reply whose message went (deleted, or the history cleared) ends by itself.
        if (mReplyTo != null && (book == null || book.message(mConv, mReplyTo.id) == null)) stopReply();
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
        // CHAT2: a quote pressed before its message was in the list goes there once it is (older pages are read).
        if (mPendingGoTo != 0) {
            if (android.os.SystemClock.elapsedRealtime() - mPendingGoToAt > PENDING_GOTO_MS || showMessage(mPendingGoTo)) {
                mPendingGoTo = 0;
            } else {
                chat.loadOlder(mConv);
            }
        }
        if (isResumed()) chat.seen(mConv);
        renderComposer(context, service, state);
        renderTyping(context, service);
        // A mute by moderators ends by itself: the field comes back then; "Selam ver" goes after ten minutes.
        mList.removeCallbacks(mTick);
        SocialModel.ChatState chatState = state.chat();
        long now = SocialService.nowSeconds();
        long hubNow = state.hubNow(now);
        long next = 0;
        if (chatState.mutedUntil != null && chatState.mutedUntil > hubNow) next = Math.min(3600, chatState.mutedUntil - hubNow);
        String crewId = ChatConv.crewId(mConv);
        if (crewId != null) {
            long greet = BadgeRules.nextGreetEnd(state.joins(crewId, now), state.myId(), now);
            if (greet > 0 && (next == 0 || greet - now < next)) next = greet - now;
        }
        if (next > 0) mList.postDelayed(mTick, next * 1000L + 500);
        watchNewest();
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
            final Profile other = state.profile(ChatConv.other(mConv, state.myId()));
            mHeader.setTitle(other == null ? getString(R.string.projects_chat_title) : other.name());
            mHeader.setCaption(other == null || !state.isFriend(other.id) ? null
                    : SocialUi.presenceText(context, state.presence(other.id)));
            if (mHeaderTag != null) {
                SocialModel.Badge badge = other == null ? null : BadgeRules.shown(service.gate(), other.badge);
                mHeaderTag.setBadge(badge, CrewTagChip.Size.LG, true);
                if (badge != null && other != null) {
                    final String friendId = other.id;
                    mHeaderTag.makeButton(badge, v -> BadgeCard.show(requireActivity(), friendId));
                }
            }
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
        if (!open && mReplyTo != null) stopReply();
        mComposer.setVisibility(open ? View.VISIBLE : View.GONE);
        mClosed.setVisibility(open ? View.GONE : View.VISIBLE);
        if (!open) mClosed.setText(closed);
        if (!open) mProblemRow.setVisibility(View.GONE);
        if (!open) mComposerCard.setVisibility(View.GONE);
        mEmoji.setVisibility(open ? View.VISIBLE : View.GONE);
        // "Mesajlar şu an yalnız çevrimiçi olanlara gidiyor.": the mailbox's budget is spent for today.
        boolean liveOnly = open && state.chat().liveOnlyAt(state.hubNow(SocialService.nowSeconds()));
        mNotice.setVisibility(liveOnly ? View.VISIBLE : View.GONE);
        if (liveOnly) mNotice.setText(R.string.projects_chat_live_only);
        boolean quick = open && ChatConv.isCrew(mConv) && mEditing == null && mReplyTo == null && mInput.getText().length() == 0;
        mQuick.setVisibility(quick ? View.VISIBLE : View.GONE);
        updateComposer();
    }

    /** CHAT2: who writes here now ("Ayşe yazıyor", "Ayşe ve Mert yazıyor", "Ayşe, Mert ve 2 kişi daha yazıyor"). */
    private void renderTyping(Context context, SocialService service) {
        SocialChat chat = service.chat();
        float dp = context.getResources().getDisplayMetrics().density;
        boolean typingOn = chat.offers(SocialModel.ChatState.TYPING);
        // The list keeps the pill's room at its bottom while typing is on (no jump when it shows).
        int bottom = Math.round((typingOn ? 42 : 8) * dp);
        if (mList.getPaddingBottom() != bottom) mList.setPadding(mList.getPaddingLeft(), mList.getPaddingTop(), mList.getPaddingRight(), bottom);
        SocialState state = service.state();
        List<String> names = new ArrayList<>();
        Profile first = null;
        for (String id : typingOn ? chat.writers(mConv) : Collections.<String>emptyList()) {
            if (!state.presence(id).online()) continue;
            Profile p = state.profile(id);
            if (p == null) continue;
            if (first == null) first = p;
            names.add(p.name());
        }
        int[] shape = ChatTyping.shape(names);
        boolean show = shape != null;
        if (show) {
            String text;
            if (shape[0] == 1) text = getString(R.string.projects_typing_one, names.get(0));
            else if (shape[0] == 2) text = getString(R.string.projects_typing_two, names.get(0), names.get(1));
            else text = context.getResources().getQuantityString(R.plurals.projects_typing_more, shape[1], names.get(0), names.get(1), shape[1]);
            mTypingText.setText(text);
            mTypingFace.setProfile(first);
        }
        if (show == mTypingShown) return;
        mTypingShown = show;
        mTyping.animate().cancel();
        if (show) {
            mTyping.setVisibility(View.VISIBLE);
            if (Motion.enabled(context)) {
                mTyping.setAlpha(0f);
                mTyping.setTranslationY(6 * dp);
                mTyping.animate().alpha(1f).translationY(0f).setDuration(180).setInterpolator(Motion.EASE_OUT).start();
            }
        } else if (Motion.enabled(context)) {
            mTyping.animate().alpha(0f).setDuration(140).setInterpolator(Motion.EASE_IN)
                    .withEndAction(() -> mTyping.setVisibility(View.GONE)).start();
        } else {
            mTyping.setVisibility(View.GONE);
        }
    }

    // ---- read marks (CHAT2) ----

    /**
     * The newest of the others' messages is watched while it is on screen with the app in front: after
     * a second the read mark goes ({@link SocialChat#seenOnScreen}).
     */
    private void watchNewest() {
        if (!isResumed() || mList == null) return;
        SocialService service = SocialService.get(requireContext());
        ChatBook book = service.chat().book();
        if (book == null || !service.chat().offers(SocialModel.ChatState.READS)) return;
        long newest = book.newestTheirs(mConv);
        boolean onScreen = newest > 0 && visible(newest);
        if (!onScreen) {
            mWatching = 0;
            mList.removeCallbacks(mSeenCheck);
            return;
        }
        if (newest == mWatching) return;
        mWatching = newest;
        mList.removeCallbacks(mSeenCheck);
        mList.postDelayed(mSeenCheck, READ_AFTER_MS);
    }

    private void seenCheck() {
        if (!isResumed() || mWatching == 0) return;
        if (visible(mWatching)) SocialService.get(requireContext()).chat().seenOnScreen(mConv, mWatching);
        // Watched again on the next look: a mark that could not go yet (no connection) is tried then; the chat
        // remembers the ones that went, so nothing goes twice.
        mWatching = 0;
    }

    /** Whether the message with this id is drawn on screen now. */
    private boolean visible(long id) {
        int first = mLayout.findFirstVisibleItemPosition();
        int last = mLayout.findLastVisibleItemPosition();
        if (first < 0 || last < 0) return false;
        for (int i = first; i <= last && i < mAdapter.mRows.size(); i++) {
            Row row = mAdapter.mRows.get(i);
            if (row.entry != null && row.entry.message != null && row.entry.message.id == id) return true;
        }
        return false;
    }

    // ---- the field ----

    private void updateComposer() {
        if (mInput == null || getContext() == null) return;
        SocialChat chat = SocialService.get(requireContext()).chat();
        String text = mInput.getText().toString();
        ChatText.Verdict verdict = chat.verdict(text);
        int length = ChatText.length(ChatText.clean(text));
        String line = null;
        switch (verdict.problem) {
            case LINK: line = linkWords(verdict.rule, text); break;
            case PHONE: line = getString(R.string.projects_chat_phone); break;
            case TOO_LONG: line = getString(R.string.projects_chat_too_long); break;
            case WORDS: line = getString(R.string.projects_chat_words); break;
            default: break;
        }
        boolean hasLinks = verdict.problem == ChatText.Problem.NONE && !ChatLinks.find(ChatText.clean(text)).isEmpty();
        SocialModel.Limited limited = hasLinks ? chat.linksLimited() : null;
        if (limited != null) line = limitedLinks(limited);
        boolean counter = length >= ChatText.COUNTER_FROM;
        boolean row = mComposer.getVisibility() == View.VISIBLE && (line != null || counter);
        mProblemRow.setVisibility(row ? View.VISIBLE : View.GONE);
        mProblem.setText(line);
        mCounter.setVisibility(counter ? View.VISIBLE : View.GONE);
        mCounter.setText(getString(R.string.projects_crew_count, length, ChatText.MAX));
        mCounter.setTextColor(ContextCompat.getColor(requireContext(), length > ChatText.MAX ? R.color.projects_flare_400 : R.color.projects_ash_400));
        boolean enabled = verdict.problem == ChatText.Problem.NONE && limited == null
                && chat.canWrite(mConv) == SocialChat.Refusal.NONE
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
        boolean quick = mComposer.getVisibility() == View.VISIBLE && ChatConv.isCrew(mConv) && mEditing == null && mReplyTo == null
                && text.isEmpty();
        if (quick != (mQuick.getVisibility() == View.VISIBLE)) mQuick.setVisibility(quick ? View.VISIBLE : View.GONE);
        renderComposerCard(text);
    }

    /** What a refused link says, by its rule ("https:// ile yaz." for a bare domain). */
    private String linkWords(@Nullable ChatLinks.Rule rule, String text) {
        String key = rule == null ? "other" : rule.wire();
        if ("form".equals(key) && !text.toLowerCase(Locale.ROOT).contains("://")) key = "formHint";
        switch (gg.projects.mobile.social.ChatLinkUi.ruleWords(key)) {
            case "off": return getString(R.string.projects_links_rule_off);
            case "form": return getString(R.string.projects_links_rule_form);
            case "formHint": return getString(R.string.projects_links_rule_form_hint);
            case "count": return getString(R.string.projects_links_rule_count);
            case "http": return getString(R.string.projects_links_rule_http);
            case "long": return getString(R.string.projects_links_rule_long);
            case "shortener": return getString(R.string.projects_links_rule_shortener);
            case "lookalike": return getString(R.string.projects_links_rule_lookalike);
            case "file": return getString(R.string.projects_links_rule_file);
            case "invite": return getString(R.string.projects_links_rule_invite);
            default: return getString(R.string.projects_links_rule_other);
        }
    }

    /** SPAM1's new account: "Discord hesabın yeni olduğu için bağlantı gönderemezsin. 3 gün sonra açılır." */
    private String limitedLinks(SocialModel.Limited limited) {
        int days = SocialModel.Limited.daysLeft(limited.until, SocialService.nowSeconds());
        return getString(R.string.projects_links_limited) + " "
                + getResources().getQuantityString(R.plurals.projects_links_limited_opens, Math.max(1, days), Math.max(1, days));
    }

    /** CHAT2: the card of the link the field holds (asked for once the player stops typing for 600 ms). */
    private void renderComposerCard(String text) {
        SocialChat chat = SocialService.get(requireContext()).chat();
        ChatLinks.MessageLink link = null;
        if (mComposer.getVisibility() == View.VISIBLE && mEditing == null && chat.linkLists() != null) {
            for (ChatLinks.MessageLink l : ChatLinks.messageLinks(ChatText.clean(text), chat.linkLists())) {
                if (l.ok) {
                    link = l;
                    break;
                }
            }
        }
        mList.removeCallbacks(mComposerCardAsk);
        if (link == null) {
            mComposerCard.setVisibility(View.GONE);
            mComposerCard.removeAllViews();
            mComposerCard.setTag(R.id.projects_chat_composer_link, null);
            return;
        }
        ChatCards.Card card = chat.composerCard(link.link.url);
        if (card == null) {
            mComposerCard.setTag(R.id.projects_chat_composer_link, link);
            mList.postDelayed(mComposerCardAsk, COMPOSER_CARD_MS);
            if (mComposerCard.getVisibility() != View.VISIBLE) return;
        }
        if (card != null && card.state == ChatCards.State.REFUSED) {
            mComposerCard.setVisibility(View.GONE);
            mProblemRow.setVisibility(View.VISIBLE);
            mProblem.setText(R.string.projects_links_refused);
            return;
        }
        final ChatLinks.MessageLink shown = link;
        ChatLinkViews.bindCard(mComposerCard, card, link, card == null, card != null, v ->
                ChatLinkViews.open(requireActivity(), shown.link.url, shown.host, shown.trusted));
    }

    private void askComposerCard() {
        if (!isAdded()) return;
        Object tag = mComposerCard.getTag(R.id.projects_chat_composer_link);
        if (!(tag instanceof ChatLinks.MessageLink)) return;
        ChatLinks.MessageLink link = (ChatLinks.MessageLink) tag;
        SocialService.get(requireContext()).chat().wantComposerCard(link.link.url, link.host);
    }

    /** CHAT2: "yazıyor" goes while the field has text and the focus, not while editing. */
    private void typed() {
        if (mInput == null || mEditing != null || !mInput.hasFocus() || mInput.getText().toString().trim().isEmpty()) return;
        SocialService.get(requireContext()).chat().typed(mConv);
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
        SocialChat.Refusal refusal = service.chat().send(mConv, text, mReplyTo);
        if (refusal == SocialChat.Refusal.NONE) {
            mInput.setText("");
            stopReply();
            mFollow = true;
            scrollToBottom(true);
            return;
        }
        Haptics.reject(mSend);
        String why;
        switch (refusal) {
            case LINK: why = linkWords(service.chat().verdict(text).rule, text); break;
            case PHONE: why = getString(R.string.projects_chat_phone); break;
            case TOO_LONG: why = getString(R.string.projects_chat_too_long); break;
            case WORDS: why = getString(R.string.projects_chat_words); break;
            case LIMITED_LINKS: {
                SocialModel.Limited limited = service.chat().linksLimited();
                why = limited == null ? getString(R.string.projects_links_limited) : limitedLinks(limited);
                break;
            }
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

    /** CHAT2: the emoji and sticker sheet (the keyboard goes down; emoji go into the field, a sticker goes at once). */
    private void openPicker() {
        final FragmentActivity activity = requireActivity();
        InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(mInput.getWindowToken(), 0);
        SocialChat chat = SocialService.get(activity).chat();
        boolean stickers = mEditing == null && chat.stickerSets().contains("pl1");
        EmojiSheet.pick(activity, stickers, new EmojiSheet.Pick() {
            @Override
            public void emoji(String emoji) {
                insert(emoji);
            }

            @Override
            public void sticker(String ref, View cell) {
                sendSticker(ref, cell);
            }
        });
    }

    /** An emoji goes in where the cursor is (a selection is replaced). */
    private void insert(String emoji) {
        if (mInput == null) return;
        int start = Math.max(0, Math.min(mInput.getSelectionStart(), mInput.getSelectionEnd()));
        int end = Math.max(0, Math.max(mInput.getSelectionStart(), mInput.getSelectionEnd()));
        Editable text = mInput.getText();
        if (ChatText.length(text.toString()) + ChatText.length(emoji) > 1200) return;
        text.replace(start, end, emoji);
        mInput.setSelection(Math.min(text.length(), start + emoji.length()));
    }

    private void sendSticker(String ref, View cell) {
        if (!isAdded() || mList == null) return;
        SocialChat.Refusal refusal = SocialService.get(requireContext()).chat().sendSticker(mConv, ref, mReplyTo);
        if (refusal != SocialChat.Refusal.NONE) {
            Haptics.reject(cell);
            return;
        }
        EmojiStore.usedSticker(requireContext(), ref);
        Haptics.confirm(cell);
        stopReply();
        mFollow = true;
        scrollToBottom(true);
    }

    void startEditing(ChatMessage message) {
        stopReply();
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

    /** CHAT2 "Yanıtla": the strip over the field ("Yanıtlanıyor: Ayşe" and one line of it), the field in focus. */
    void startReply(ChatMessage message) {
        if (!isAdded()) return;
        if (mEditing != null) stopEditing(true);
        mReplyTo = message;
        Context context = requireContext();
        SocialService service = SocialService.get(context);
        String me = service.state().myId();
        Profile from = service.state().profile(message.from);
        String name = message.from.equals(me) ? getString(R.string.projects_reply_you)
                : from == null ? getString(R.string.projects_crew_someone) : from.name();
        mReplyName.setText(getString(R.string.projects_reply_replying, name));
        if (message.isSticker()) {
            int res = EmojiStore.stickerRes(context, message.sticker);
            mReplySticker.setVisibility(res != 0 ? View.VISIBLE : View.GONE);
            if (res != 0) mReplySticker.setImageResource(res);
            mReplyText.setText(R.string.projects_stickers_unknown);
        } else {
            mReplySticker.setVisibility(View.GONE);
            mReplyText.setText(message.text.replace('\n', ' '));
        }
        boolean was = mReplyBar.getVisibility() == View.VISIBLE;
        mReplyBar.setVisibility(View.VISIBLE);
        if (!was) gg.projects.mobile.ui.CardMotion.fadeIn(mReplyBar);
        mQuick.setVisibility(View.GONE);
        showKeyboard(mInput);
        updateComposer();
    }

    private void stopReply() {
        if (mReplyTo == null) return;
        mReplyTo = null;
        if (mReplyBar != null) mReplyBar.setVisibility(View.GONE);
        if (mInput != null && getContext() != null) updateComposer();
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
        if (!isAdded() || !field.isAttachedToWindow() || (mEditing == null && mReplyTo == null)) return;
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
                                SocialUi.toast(activity, activity.getString(R.string.projects_chat_cleared));
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
        /** TEAM6: the join a T_JOIN line is about. */
        @Nullable
        final SocialState.CrewJoin join;
        /** CHAT2: "Görüldü" goes under this message (the friend read up to it). */
        boolean seen;

        Row(int type, String id, String content, @Nullable ChatBook.Entry entry, @Nullable String label, boolean first, boolean last) {
            this(type, id, content, entry, label, first, last, null);
        }

        Row(int type, String id, String content, @Nullable ChatBook.Entry entry, @Nullable String label, boolean first, boolean last,
            @Nullable SocialState.CrewJoin join) {
            this.type = type;
            this.id = id;
            this.content = content;
            this.entry = entry;
            this.label = label;
            this.first = first;
            this.last = last;
            this.join = join;
        }
    }

    /** A local line of the crew chat (a plan made, someone joined), at its time (ms). */
    private static final class Local {
        final long at;
        @Nullable
        final SocialModel.Plan plan;
        @Nullable
        final SocialState.CrewJoin join;

        Local(long at, @Nullable SocialModel.Plan plan, @Nullable SocialState.CrewJoin join) {
            this.at = at;
            this.plan = plan;
            this.join = join;
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
        SocialState state = service.state();
        String me = state.myId();
        if (me == null) return rows;
        SocialChat chat = service.chat();
        List<ChatBook.Entry> entries = book.entries(mConv);
        // M21 (TEAM5): the crew's plans, and TEAM6's joins, as local lines when they happened (the mailbox holds neither).
        List<Local> locals = new ArrayList<>();
        long nowSeconds = SocialService.nowSeconds();
        String crewId = ChatConv.crewId(mConv);
        if (crewId != null && service.gate().plans) {
            for (SocialModel.Plan p : state.plans(nowSeconds, crewId)) locals.add(new Local(p.createdAt * 1000L, p, null));
        }
        if (crewId != null && service.gate().teams) {
            for (SocialState.CrewJoin j : state.joins(crewId, nowSeconds)) {
                // The player's own line always; a newcomer the player blocked gets none.
                boolean blocked = false;
                for (Profile b : state.blocked()) if (b.id.equals(j.userId)) blocked = true;
                if (!blocked) locals.add(new Local(book.hubNowMs(j.at * 1000L), null, j));
            }
        }
        Collections.sort(locals, (a, b) -> Long.compare(a.at, b.at));
        // How many local lines come before each message (a line between two messages ends their group).
        int[] localsBefore = new int[entries.size() + 1];
        int k = 0;
        for (int i = 0; i < entries.size(); i++) {
            long at = timeOf(entries.get(i));
            while (k < locals.size() && locals.get(k).at <= at) k++;
            localsBefore[i] = k;
        }
        localsBefore[entries.size()] = locals.size();
        String seen = chat.offers(SocialModel.ChatState.READS) && state.me() != null && state.me().shareRead ? book.seenKey(mConv) : null;
        ChatLinks.Lists lists = chat.linkLists();
        boolean badges = service.gate().badges;
        long lastDay = -1;
        int emitted = 0;
        for (int i = 0; i < entries.size(); i++) {
            ChatBook.Entry e = entries.get(i);
            long at = timeOf(e);
            for (; emitted < localsBefore[i]; emitted++) lastDay = addLocal(context, service, rows, locals.get(emitted), lastDay, nowSeconds);
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
            boolean lineBefore = i > 0 && localsBefore[i] > localsBefore[i - 1];
            boolean lineAfter = localsBefore[i + 1] > localsBefore[i];
            boolean first = prev == null || !together(prev, e, me) || (mUnreadAt > 0 && e.message != null && e.message.id == mUnreadAt)
                    || lineBefore || e.replyId() > 0;
            boolean last = next == null || !together(e, next, me) || (mUnreadAt > 0 && next.message != null && next.message.id == mUnreadAt)
                    || lineAfter || next.replyId() > 0;
            String rowState = e.deleted() ? "deleted:" + e.tombstone.by : e.failed() ? "failed:" + e.outbox.failure
                    : e.pending() ? "pending" : "sent";
            // "Bağlantı bekleniyor" under a waiting message follows the socket, so the row knows it too.
            boolean waiting = e.outbox != null && service.connection() != SocialService.Connection.LIVE;
            StringBuilder content = new StringBuilder(e.key()).append('|').append(e.text()).append('|').append(e.sticker())
                    .append('|').append(rowState).append('|').append(e.message != null ? e.message.editedAt : 0).append('|')
                    .append(first).append(last).append('|').append(e.deleting).append('|').append(waiting);
            if (!e.mine(me)) {
                // The sender's name, face and tag (TalkBack reads the name in a direct conversation too): a row
                // bound before the list came is bound again once they are known.
                Profile p = state.profile(e.from(me));
                content.append('|').append(p == null ? "" : p.name() + p.faceKey() + (badges && p.badge != null ? p.badge.key() : ""));
            }
            if (e.deleted()) content.append('|').append(deletedText(context, e.tombstone, me, state));
            // CHAT2: the quote, the reactions, the card, "Görüldü", the rules the links are drawn by.
            if (e.replyId() > 0) {
                ChatBook.Quote q = chat.quote(mConv, e.replyId(), e.replyFrom());
                content.append("|q").append(q.kind).append(q.message == null ? "" : q.message.text + q.message.sticker);
            }
            for (ChatMessage.Reaction r : e.reactions) content.append("|r").append(r.e).append(r.by);
            if (e.message != null) {
                ChatCards.Card card = chat.card(mConv, e.message.id);
                if (card != null) content.append("|c").append(card.state).append(card.title);
            }
            boolean seenHere = e.key().equals(seen);
            content.append("|s").append(seenHere);
            content.append("|l").append(lists == null ? "off" : lists.trusted.hashCode() + "/" + lists.blocked.hashCode() + lists.invites);
            Row messageRow = new Row(T_MESSAGE, e.key(), content.toString(), e, null, first, last);
            messageRow.seen = seenHere;
            rows.add(messageRow);
        }
        for (; emitted < locals.size(); emitted++) lastDay = addLocal(context, service, rows, locals.get(emitted), lastDay, nowSeconds);
        return rows;
    }

    /** A local line (with the day above it when it is another day); returns the day it is on. */
    private long addLocal(Context context, SocialService service, List<Row> rows, Local local, long lastDay, long nowSeconds) {
        long day = dayOf(local.at);
        if (day != lastDay) {
            String label = dayLabel(context, local.at);
            rows.add(new Row(T_DAY, "day:" + day, label, null, label, false, false));
        }
        String me = service.state().myId();
        if (local.plan != null) {
            SocialModel.Plan plan = local.plan;
            Profile maker = service.state().profile(plan.by);
            // The player's own plan says "Yarın 23:30 için plan yaptın" (the sentence starts with the day).
            String text = plan.by.equals(me) ? getString(R.string.projects_plans_made_you, PlanViews.when(context, plan.at, nowSeconds))
                    : getString(R.string.projects_plans_made, maker == null ? getString(R.string.projects_crew_someone) : maker.name(),
                            PlanViews.when(context, plan.at, nowSeconds, true));
            rows.add(new Row(T_PLAN, "plan:" + plan.id, "plan|" + text, null, text, false, false));
            return day;
        }
        SocialState.CrewJoin join = local.join;
        if (join == null) return day;
        boolean mine = join.userId.equals(me);
        Profile who = service.state().profile(join.userId);
        String text = mine ? getString(R.string.projects_welcome_you)
                : getString(R.string.projects_requests_accepted, who == null ? getString(R.string.projects_crew_someone) : who.name());
        boolean greet = BadgeRules.canGreet(join, me, nowSeconds) && !mGreeted.contains(join.crewId + join.userId + join.at);
        rows.add(new Row(T_JOIN, "join:" + join.userId + ":" + join.at, "join|" + text + "|" + greet, null, text, false, false, join));
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

    /** CHAT2: a press on a quote goes to the answered message (older pages read when needed) and it glows once. */
    private void goTo(long id) {
        if (showMessage(id)) return;
        // Not in the list yet: older pages are read and the list goes there once it is (render()).
        mPendingGoTo = id;
        mPendingGoToAt = android.os.SystemClock.elapsedRealtime();
        SocialService.get(requireContext()).chat().loadOlder(mConv);
    }

    /** Scrolls to the message and makes it glow once; false when it is not in the list. */
    private boolean showMessage(long id) {
        String key = null;
        for (Row row : mAdapter.mRows) {
            if (row.entry != null && row.entry.message != null && row.entry.message.id == id) key = row.id;
        }
        if (key == null) return false;
        final int position = positionOf(key);
        if (position < 0) return false;
        mGlow = key;
        mLayout.scrollToPositionWithOffset(position, mList.getHeight() / 3);
        mList.post(() -> {
            if (mList == null) return;
            RecyclerView.ViewHolder holder = mList.findViewHolderForAdapterPosition(position);
            if (holder != null) glow(holder.itemView);
        });
        return true;
    }

    private void glow(View row) {
        mGlow = null;
        View column = row.findViewById(R.id.projects_msg_column);
        if (column == null || !Motion.enabled(row.getContext())) return;
        column.animate().cancel();
        column.setAlpha(0.45f);
        column.animate().alpha(1f).setDuration(600).setInterpolator(Motion.EASE_OUT).start();
    }

    // ---- the adapter ----

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private List<Row> mRows = new ArrayList<>();
        private final Map<String, Long> mIds = new HashMap<>();
        /** Messages that came while the screen was open (they rise in; stickers move once). */
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
                Press.attach(view.findViewById(R.id.projects_msg_box), Press.Style.CARD);
                Press.attach(view.findViewById(R.id.projects_msg_failed), Press.Style.BUTTON);
                ChatLinkViews.attachTouch(view.findViewById(R.id.projects_msg_bubble));
            } else if (viewType == T_JOIN) {
                view = joinLine(parent.getContext());
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
                if (row.id.equals(mGlow)) glow(holder.itemView);
            } else if (row.type == T_JOIN) {
                bindJoin(holder.itemView, row);
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

    /** TEAM6: "Ece ekibe katıldı" in a quiet pill, with "Selam ver" beside it while it lasts. */
    private View joinLine(Context context) {
        float dp = context.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(Math.round(16 * dp), Math.round(8 * dp), Math.round(16 * dp), Math.round(8 * dp));
        row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView text = new TextView(context);
        text.setId(R.id.projects_day_text);
        text.setTextSize(13);
        text.setTextColor(ContextCompat.getColor(context, R.color.projects_ash_300));
        text.setBackgroundResource(R.drawable.projects_pill_background);
        text.setGravity(Gravity.CENTER_VERTICAL);
        text.setMinHeight(Math.round(28 * dp));
        text.setPadding(Math.round(12 * dp), 0, Math.round(12 * dp), 0);
        Drawable glyph = ContextCompat.getDrawable(context, R.drawable.projects_ic_user_plus);
        if (glyph != null) {
            glyph = glyph.mutate();
            glyph.setTint(ContextCompat.getColor(context, R.color.projects_gold_300));
            int size = Math.round(14 * dp);
            glyph.setBounds(0, 0, size, size);
            text.setCompoundDrawablesRelative(glyph, null, null, null);
            text.setCompoundDrawablePadding(Math.round(6 * dp));
        }
        row.addView(text);
        TextView hi = new TextView(context);
        hi.setId(R.id.projects_chat_state);
        hi.setText(R.string.projects_welcome_say_hi);
        hi.setTextSize(13);
        hi.setTextColor(ContextCompat.getColor(context, R.color.projects_gold_300));
        hi.setGravity(Gravity.CENTER);
        hi.setMinHeight(Math.round(36 * dp));
        hi.setPadding(Math.round(14 * dp), 0, Math.round(14 * dp), 0);
        hi.setBackgroundResource(R.drawable.projects_chat_chip);
        try {
            hi.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(context, R.font.figtree_semibold));
        } catch (RuntimeException ignored) {
            // The theme's font.
        }
        Press.attach(hi, Press.Style.BUTTON);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(Math.round(8 * dp));
        row.addView(hi, lp);
        return row;
    }

    private void bindJoin(View view, final Row row) {
        TextView text = view.findViewById(R.id.projects_day_text);
        text.setText(row.label);
        final TextView hi = view.findViewById(R.id.projects_chat_state);
        final SocialState.CrewJoin join = row.join;
        boolean greet = join != null && row.content.endsWith("|true");
        hi.animate().cancel();
        hi.setAlpha(1f);
        hi.setVisibility(greet ? View.VISIBLE : View.GONE);
        if (!greet) return;
        hi.setOnClickListener(v -> {
            if (join == null) return;
            // The hi sticker (or "Hoş geldin! 👋" without stickers) goes as a usual message, once.
            SocialChat chat = SocialService.get(requireContext()).chat();
            SocialChat.Refusal sent = chat.stickerSets().contains("pl1") && EmojiStore.stickerRes(requireContext(), "pl1/hi") != 0
                    ? chat.sendSticker(mConv, "pl1/hi", null) : chat.send(mConv, getString(R.string.projects_welcome_text));
            if (sent != SocialChat.Refusal.NONE) {
                Haptics.reject(v);
                return;
            }
            Haptics.confirm(v);
            mGreeted.add(join.crewId + join.userId + join.at);
            mFollow = true;
            if (Motion.enabled(v.getContext())) {
                hi.animate().alpha(0f).setDuration(160).setInterpolator(Motion.EASE_OUT).withEndAction(this::render).start();
            } else {
                render();
            }
        });
    }

    private void bindMessage(View view, final Row row, boolean fresh) {
        final Context context = view.getContext();
        final SocialService service = SocialService.get(context);
        final SocialChat chat = service.chat();
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
        final LinearLayout column = view.findViewById(R.id.projects_msg_column);
        column.setGravity(mine ? Gravity.END : Gravity.START);

        View faceSlot = view.findViewById(R.id.projects_msg_face_slot);
        AvatarView avatar = view.findViewById(R.id.projects_msg_avatar);
        View nameRow = view.findViewById(R.id.projects_msg_name_row);
        TextView name = view.findViewById(R.id.projects_msg_name);
        CrewTagChip tag = view.findViewById(R.id.projects_msg_tag);
        Profile sender = mine ? null : service.state().profile(e.from(me));
        boolean faces = crewChat && !mine;
        faceSlot.setVisibility(faces ? View.VISIBLE : View.GONE);
        avatar.setVisibility(faces && row.last ? View.VISIBLE : View.INVISIBLE);
        if (faces && row.last) {
            avatar.setProfile(sender);
            avatar.setSurfaceColor(ContextCompat.getColor(context, R.color.projects_obsidian_950));
        }
        nameRow.setVisibility(faces && row.first ? View.VISIBLE : View.GONE);
        if (faces && row.first) {
            name.setText(sender == null ? getString(R.string.projects_crew_someone) : sender.name());
            // TEAM6: the sender's tag before their name.
            tag.setBadge(sender == null ? null : BadgeRules.shown(service.gate(), sender.badge), CrewTagChip.Size.SM, !fresh);
        }

        bindQuote(view, e, me);

        final LinearLayout box = view.findViewById(R.id.projects_msg_box);
        final TextView bubble = view.findViewById(R.id.projects_msg_bubble);
        View stickerBox = view.findViewById(R.id.projects_msg_sticker_box);
        ImageView sticker = view.findViewById(R.id.projects_msg_sticker);
        TextView unknown = view.findViewById(R.id.projects_msg_sticker_unknown);
        FrameLayout card = view.findViewById(R.id.projects_msg_card);
        TextView refused = view.findViewById(R.id.projects_msg_link_refused);
        int maxWidth = Math.round(Math.max(200 * dp, (mList.getWidth() > 0 ? mList.getWidth() : context.getResources().getDisplayMetrics().widthPixels) * 0.78f));
        bubble.setMaxWidth(maxWidth - Math.round(28 * dp));
        String stickerRef = e.sticker();
        boolean isSticker = stickerRef != null && !e.deleted();
        boolean big = !isSticker && !e.deleted() && EmojiStore.isBigEmoji(e.text());
        stickerBox.setVisibility(isSticker ? View.VISIBLE : View.GONE);
        box.setVisibility(isSticker ? View.GONE : View.VISIBLE);
        card.setVisibility(View.GONE);
        refused.setVisibility(View.GONE);
        if (isSticker) {
            int res = EmojiStore.stickerRes(context, stickerRef);
            String stickerName = EmojiStore.stickerName(context, stickerRef);
            sticker.setVisibility(res != 0 ? View.VISIBLE : View.GONE);
            unknown.setVisibility(res != 0 ? View.GONE : View.VISIBLE);
            if (res != 0) sticker.setImageResource(res);
            String who = mine ? getString(R.string.projects_crew_you) : sender == null ? "" : sender.name();
            stickerBox.setContentDescription(mine ? getString(R.string.projects_stickers_sent_mine, stickerName == null ? "" : stickerName)
                    : getString(R.string.projects_stickers_sent, who, stickerName == null ? "" : stickerName));
            // The motion stops only when the row now shows another message: a sent sticker's rebind (sending →
            // sent) lets its arrival finish.
            boolean sameRow = row.id.equals(sticker.getTag(R.id.projects_chat_sticker_row));
            sticker.setTag(R.id.projects_chat_sticker_row, row.id);
            if (fresh && res != 0) EmojiStore.playSticker(sticker, stickerRef, true);
            else if (!sameRow) EmojiStore.stopSticker(sticker);
        } else if (e.deleted()) {
            // FIX2: a quiet bubble in the message's place: who deleted it, no text, no actions.
            bubble.setText(deletedText(context, e.tombstone, me, service.state()));
            bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            bubble.setTypeface(bubble.getTypeface(), android.graphics.Typeface.ITALIC);
            bubble.setTextColor(ContextCompat.getColor(context, R.color.projects_ash_400));
            box.setBackground(deletedShape(context, mine, row.first, row.last));
            box.setPadding(Math.round(14 * dp), Math.round(9 * dp), Math.round(14 * dp), Math.round(9 * dp));
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
            // CHAT2: links the rules let through now (Studio's lists may have changed since it was written).
            List<ChatLinks.MessageLink> links = e.message != null || e.outbox != null
                    ? ChatLinks.messageLinks(e.text(), chat.linkLists()) : Collections.<ChatLinks.MessageLink>emptyList();
            ChatLinks.MessageLink cardLink = null;
            for (ChatLinks.MessageLink l : links) {
                if (l.ok) {
                    cardLink = l;
                    break;
                }
            }
            ChatCards.Card kept = e.message == null ? null : chat.card(mConv, e.message.id);
            if (kept != null && cardLink != null && !kept.url.equals(cardLink.link.url)) kept = null;
            boolean linkRefused = kept != null && kept.state == ChatCards.State.REFUSED;
            CharSequence text = linkRefused ? e.text() : ChatLinkViews.withLinks(context, e.text(), links,
                    link -> ChatLinkViews.open(requireActivity(), link.link.url, link.host, link.trusted));
            bubble.setText(text);
            bubble.setTypeface(regularFace(context), android.graphics.Typeface.NORMAL);
            bubble.setTextColor(ContextCompat.getColor(context, R.color.projects_bone_100));
            bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, big ? 36 : 15);
            bubble.setCompoundDrawablesRelative(null, null, null, null);
            if (big) {
                box.setBackground(null);
                box.setPadding(Math.round(4 * dp), 0, Math.round(4 * dp), 0);
            } else {
                box.setBackground(bubbleShape(context, mine, row.first, row.last, e.failed()));
                box.setPadding(Math.round(14 * dp), Math.round(9 * dp), Math.round(14 * dp), Math.round(9 * dp));
            }
            if (linkRefused) {
                refused.setVisibility(View.VISIBLE);
            } else if (cardLink != null && e.message != null && !big) {
                final ChatLinks.MessageLink shown = cardLink;
                boolean asked = e.message.id > 0 && service.chat().book() != null
                        && service.chat().book().hubNowMs(System.currentTimeMillis()) - e.message.id < ChatCards.MAX_AGE_MS;
                if (kept == null && asked) chat.wantCard(mConv, e.message);
                if (kept != null || asked) {
                    ChatLinkViews.bindCard(card, kept, cardLink, kept == null, false,
                            v -> ChatLinkViews.open(requireActivity(), shown.link.url, shown.host, shown.trusted));
                }
            }
        }
        float targetAlpha = e.deleting ? 0.4f : e.pending() ? 0.7f : 1f;
        final View content = isSticker ? stickerBox : box;

        bindReactions(view, e, me);

        TextView time = view.findViewById(R.id.projects_msg_time);
        ImageView status = view.findViewById(R.id.projects_msg_status);
        View meta = view.findViewById(R.id.projects_msg_meta);
        TextView failed = view.findViewById(R.id.projects_msg_failed);
        boolean seen = row.seen;
        boolean showMeta = row.last || e.pending() || (e.message != null && e.message.edited()) || seen;
        meta.setVisibility(showMeta && !e.failed() ? View.VISIBLE : View.GONE);
        String clock = android.text.format.DateFormat.getTimeFormat(context).format(new Date(timeOf(e)));
        boolean edited = e.message != null && e.message.edited();
        boolean waiting = e.outbox != null && service.connection() != SocialService.Connection.LIVE;
        time.setText(waiting ? getString(R.string.projects_chat_waiting)
                : seen ? getString(R.string.projects_seen)
                : edited ? clock + " · " + getString(R.string.projects_chat_edited) : clock);
        String stateKey;
        if (e.deleted()) {
            status.setVisibility(View.GONE);
            stateKey = "deleted";
        } else if (mine && !e.failed() && !seen) {
            status.setVisibility(View.VISIBLE);
            boolean pending = e.pending();
            status.setImageResource(pending ? R.drawable.projects_ic_clock : R.drawable.projects_ic_check);
            status.setImageTintList(ContextCompat.getColorStateList(context, pending ? R.color.projects_ash_400 : R.color.projects_gold_300));
            stateKey = pending ? "pending" : "sent";
        } else {
            status.setVisibility(View.GONE);
            stateKey = e.failed() ? "failed" : seen ? "seen" : "theirs";
        }
        if (e.failed()) {
            failed.setVisibility(View.VISIBLE);
            final boolean retriable = retriable(e.outbox.failure);
            String reason = failureText(e.outbox.failure, e.outbox.rule, e.text());
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
                    chat.retry(e.outbox.nonce);
                } else {
                    MessageActions.show(requireActivity(), ChatFragment.this, mConv, e, column);
                }
            });
        } else {
            failed.setVisibility(View.GONE);
        }

        // A state change cross-fades (the clock turns into the check, the faded message becomes whole).
        Object before = view.getTag(R.id.projects_chat_state);
        boolean changed = before instanceof String && ((String) before).startsWith(row.id + "#")
                && !before.equals(row.id + "#" + stateKey);
        view.setTag(R.id.projects_chat_state, row.id + "#" + stateKey);
        boolean motion = Motion.enabled(context);
        content.animate().cancel();
        status.animate().cancel();
        if (changed && motion && e.deleted()) {
            // FIX2: the message settles into its tombstone (a quick fade and a small give).
            content.setAlpha(0.25f);
            content.setScaleX(0.97f);
            content.setScaleY(0.97f);
            content.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(Motion.EASE_OUT).start();
        } else if (changed && motion) {
            content.setScaleX(1f);
            content.setScaleY(1f);
            content.animate().alpha(targetAlpha).setDuration(180).setInterpolator(Motion.EASE_OUT).start();
            status.setAlpha(0f);
            status.setScaleX(0.6f);
            status.setScaleY(0.6f);
            status.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(Motion.EASE_OUT).start();
        } else {
            content.setAlpha(targetAlpha);
            content.setScaleX(1f);
            content.setScaleY(1f);
            status.setAlpha(1f);
            status.setScaleX(1f);
            status.setScaleY(1f);
        }
        // A new message rises into place as it fades in (the item animator fades the row).
        column.animate().cancel();
        column.setTranslationX(0f);
        if (fresh && motion) {
            column.setTranslationY(10 * dp);
            column.animate().translationY(0f).setDuration(260).setInterpolator(Motion.EASE_OUT).start();
        } else {
            column.setTranslationY(0f);
        }

        String who = mine ? getString(R.string.projects_crew_you) : sender == null ? "" : sender.name();
        if (e.deleted()) {
            // FIX2: a tombstone offers nothing; TalkBack reads who deleted it and when it was written.
            box.setContentDescription(who + ": " + bubble.getText() + ", " + clock);
            box.setOnLongClickListener(null);
            box.setLongClickable(false);
            box.setOnClickListener(null);
            box.setClickable(false);
            // A recycled row's holds and taps belonged to another message: they go too.
            content.setOnLongClickListener(null);
            content.setLongClickable(false);
            content.setOnClickListener(null);
            content.setClickable(false);
            bubble.setOnLongClickListener(null);
            bubble.setLongClickable(false);
            bindSenderMenu(view, e, me);
            return;
        }
        // TalkBack says what the line under the bubble says ("Baglanti bekleniyor" while offline).
        String state = e.failed() ? getString(R.string.projects_chat_failed) : waiting ? getString(R.string.projects_chat_waiting)
                : seen ? getString(R.string.projects_seen)
                : mine ? getString(e.pending() ? R.string.projects_chat_pending : R.string.projects_chat_sent) : "";
        if (!isSticker) {
            content.setContentDescription(who + ": " + e.text() + ", " + clock + (edited ? ", " + getString(R.string.projects_chat_edited) : "")
                    + (state.isEmpty() ? "" : ", " + state));
        }
        View.OnLongClickListener hold = v -> {
            Haptics.perform(v, HapticMap.Kind.LONG_PRESS);
            MessageActions.show(requireActivity(), ChatFragment.this, mConv, e, column);
            return true;
        };
        content.setLongClickable(true);
        content.setOnLongClickListener(hold);
        bubble.setOnLongClickListener(hold);
        bindSenderMenu(view, e, me);
        if (isSticker) {
            // A press plays the sticker's motion once (a running one finishes first).
            final ImageView image = sticker;
            final String ref = stickerRef;
            content.setOnClickListener(v -> EmojiStore.playSticker(image, ref, false));
            content.setClickable(true);
        } else {
            content.setOnClickListener(e.failed() ? (View.OnClickListener) v -> MessageActions.show(requireActivity(), ChatFragment.this, mConv, e, column) : null);
            content.setClickable(e.failed());
        }
    }

    /** CHAT2: a reply's quote above the bubble, from this phone's own history; a press goes to the message. */
    private void bindQuote(View view, ChatBook.Entry e, String me) {
        View quote = view.findViewById(R.id.projects_msg_quote);
        long replyId = e.deleted() ? 0 : e.replyId();
        if (replyId <= 0) {
            quote.setVisibility(View.GONE);
            quote.setOnClickListener(null);
            return;
        }
        Context context = view.getContext();
        SocialService service = SocialService.get(context);
        ChatBook.Quote q = service.chat().quote(mConv, replyId, e.replyFrom());
        TextView name = view.findViewById(R.id.projects_msg_quote_name);
        TextView text = view.findViewById(R.id.projects_msg_quote_text);
        ImageView sticker = view.findViewById(R.id.projects_msg_quote_sticker);
        sticker.setVisibility(View.GONE);
        quote.setVisibility(View.VISIBLE);
        boolean blocked = false;
        if (q.from != null) for (Profile b : service.state().blocked()) if (b.id.equals(q.from)) blocked = true;
        if (q.kind == ChatBook.Quote.Kind.MESSAGE && q.message != null && !blocked) {
            Profile from = service.state().profile(q.message.from);
            name.setVisibility(View.VISIBLE);
            name.setText(q.message.from.equals(me) ? getString(R.string.projects_reply_you)
                    : from == null ? getString(R.string.projects_crew_someone) : from.name());
            if (q.message.isSticker()) {
                int res = EmojiStore.stickerRes(context, q.message.sticker);
                if (res != 0) {
                    sticker.setVisibility(View.VISIBLE);
                    sticker.setImageResource(res);
                }
                text.setText(R.string.projects_stickers_unknown);
            } else {
                text.setText(q.message.text.replace('\n', ' '));
            }
            final long target = q.message.id;
            quote.setOnClickListener(v -> goTo(target));
            quote.setClickable(true);
            quote.setContentDescription(getString(R.string.projects_reply_quoted) + ": " + name.getText() + ", " + text.getText());
        } else {
            name.setVisibility(View.GONE);
            text.setText(q.kind == ChatBook.Quote.Kind.DELETED && !blocked ? R.string.projects_reply_deleted : R.string.projects_reply_earlier);
            quote.setOnClickListener(null);
            quote.setClickable(false);
            quote.setContentDescription(getString(R.string.projects_reply_quoted) + ": " + text.getText());
        }
    }

    /** CHAT2: the reactions as chips under the message ([👍 3]); the player's own with a gold edge; a press opens who reacted. */
    private void bindReactions(View view, final ChatBook.Entry e, String me) {
        WrapLayout chips = view.findViewById(R.id.projects_msg_reactions);
        List<ChatMessage.Reaction> reactions = e.deleted() ? Collections.<ChatMessage.Reaction>emptyList() : e.reactions;
        if (reactions.isEmpty() || e.message == null) {
            chips.setVisibility(View.GONE);
            chips.removeAllViews();
            return;
        }
        Context context = view.getContext();
        float dp = context.getResources().getDisplayMetrics().density;
        chips.setVisibility(View.VISIBLE);
        Set<String> before = new HashSet<>();
        for (int i = 0; i < chips.getChildCount(); i++) {
            Object key = chips.getChildAt(i).getTag(R.id.projects_chat_state);
            if (key instanceof String) before.add((String) key);
        }
        boolean rebinding = Boolean.TRUE.equals(chips.getTag(R.id.projects_chat_state)) && e.key().equals(chips.getTag(R.id.projects_menu_action));
        chips.removeAllViews();
        final long id = e.message.id;
        for (final ChatMessage.Reaction r : reactions) {
            boolean mine = r.has(me);
            TextView chip = new TextView(context);
            chip.setText(r.e + "  " + r.count());
            chip.setTextSize(13);
            chip.setGravity(Gravity.CENTER);
            chip.setMinHeight(Math.round(28 * dp));
            chip.setPadding(Math.round(9 * dp), 0, Math.round(10 * dp), 0);
            chip.setTextColor(ContextCompat.getColor(context, mine ? R.color.projects_gold_200 : R.color.projects_bone_100));
            GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(14 * dp);
            pill.setColor(ContextCompat.getColor(context, mine ? R.color.projects_chat_mine : R.color.projects_obsidian_800));
            pill.setStroke(Math.max(1, Math.round(dp)), ContextCompat.getColor(context, mine ? R.color.projects_gold_700 : R.color.projects_obsidian_700));
            chip.setBackground(pill);
            String people = getResources().getQuantityString(R.plurals.projects_reactions_chip, r.count(), r.e, r.count());
            if (mine) {
                people = r.count() == 1 ? getString(R.string.projects_reactions_chip_mine_alone, r.e)
                        : getResources().getQuantityString(R.plurals.projects_reactions_chip_mine, r.count() - 1, r.e, r.count() - 1);
            }
            chip.setContentDescription(people);
            chip.setTag(R.id.projects_chat_state, r.e);
            chip.setOnClickListener(v -> {
                Haptics.press(v);
                ReactionSheet.show(requireActivity(), mConv, id, r.e);
            });
            Press.attach(chip, Press.Style.BUTTON);
            chips.addView(chip, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(28 * dp)));
            // A new kind pops in (the one just given lands with a small spring).
            if (rebinding && !before.contains(r.e) && Motion.enabled(context)) {
                chip.setScaleX(0.6f);
                chip.setScaleY(0.6f);
                chip.setAlpha(0f);
                chip.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220).setInterpolator(Motion.EASE_OUT).start();
            }
        }
        chips.setTag(R.id.projects_chat_state, Boolean.TRUE);
        chips.setTag(R.id.projects_menu_action, e.key());
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
        View name = view.findViewById(R.id.projects_msg_name_row);
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
            case LIMITED_LINKS:
            case FEATURE_OFF:
                return false;
            default:
                return true;
        }
    }

    private String failureText(@Nullable ChatBook.Failure failure, @Nullable String rule, String text) {
        if (failure == null) return getString(R.string.projects_chat_failed);
        switch (failure) {
            case LINK: return rule == null ? getString(R.string.projects_chat_link) : linkWords(ChatLinks.Rule.of(rule), text);
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
            case STICKERS: return getString(R.string.projects_chat2_failure_stickers);
            case LINKS: return getString(R.string.projects_chat2_failure_links);
            case LIMITED_LINKS: return getString(R.string.projects_links_limited);
            case FEATURE_OFF: return getString(R.string.projects_chat2_feature_off);
            case MUTED: return getString(R.string.projects_chat_failed);
            case OFF: return getString(R.string.projects_chat_off);
            case NOT_FRIENDS: return getString(R.string.projects_chat_not_friends);
            case NOT_IN_CREW: return getString(R.string.projects_chat_not_in_crew);
            default: return getString(R.string.projects_chat_failed);
        }
    }

    /** The reports' context: up to 20 earlier messages of this conversation. */
    List<ChatMessage> context(ChatMessage message) {
        Context context = getContext();
        if (context == null) return new ArrayList<>();
        ChatBook book = SocialService.get(context).chat().book();
        return book == null ? new ArrayList<ChatMessage>() : book.before(message, Reports.CONTEXT);
    }

    // ---- swipe to reply (CHAT2, Android) ----

    /**
     * A message swiped to the right: past 64 dp the reply arrow shows full and a tick answers, and
     * letting go there starts a reply; the row always springs back (nothing is swiped away).
     */
    private final class SwipeToReply extends ItemTouchHelper.Callback {
        private final float mThreshold;
        private final float mMax;
        private final Drawable mArrow;
        private boolean mArmed;
        @Nullable
        private RecyclerView.ViewHolder mSwiping;

        SwipeToReply(Context context) {
            float dp = context.getResources().getDisplayMetrics().density;
            mThreshold = 64 * dp;
            mMax = 88 * dp;
            Drawable arrow = ContextCompat.getDrawable(context, R.drawable.projects_ic_chat2_reply);
            mArrow = arrow == null ? null : arrow.mutate();
            if (mArrow != null) mArrow.setTint(ContextCompat.getColor(context, R.color.projects_gold_300));
        }

        private boolean replyable(RecyclerView.ViewHolder holder) {
            int position = holder.getBindingAdapterPosition();
            if (position < 0 || position >= mAdapter.mRows.size() || !isAdded()) return false;
            Row row = mAdapter.mRows.get(position);
            if (row.type != T_MESSAGE || row.entry == null || row.entry.message == null) return false;
            SocialChat chat = SocialService.get(requireContext()).chat();
            ChatBook book = chat.book();
            return book != null && book.canReply(row.entry) && chat.offers(SocialModel.ChatState.REPLIES)
                    && chat.canWrite(mConv) == SocialChat.Refusal.NONE && mEditing == null;
        }

        @Override
        public int getMovementFlags(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder holder) {
            if (!replyable(holder)) return 0;
            int direction = recyclerView.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? ItemTouchHelper.LEFT : ItemTouchHelper.RIGHT;
            return makeMovementFlags(0, direction);
        }

        @Override
        public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder from, @NonNull RecyclerView.ViewHolder to) {
            return false;
        }

        @Override
        public void onSwiped(@NonNull RecyclerView.ViewHolder holder, int direction) {
            // Never swiped away (the threshold is out of reach).
        }

        @Override
        public float getSwipeThreshold(@NonNull RecyclerView.ViewHolder holder) {
            return Float.MAX_VALUE;
        }

        @Override
        public float getSwipeEscapeVelocity(float defaultValue) {
            return Float.MAX_VALUE;
        }

        @Override
        public void onChildDraw(@NonNull Canvas c, @NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder holder,
                                float dX, float dY, int actionState, boolean active) {
            View column = holder.itemView.findViewById(R.id.projects_msg_column);
            if (column == null) return;
            float pull = Math.abs(dX);
            // Resistance past the threshold.
            float moved = pull <= mThreshold ? pull : mThreshold + (Math.min(pull, mMax * 2) - mThreshold) * 0.25f;
            float sign = dX < 0 ? -1f : 1f;
            column.setTranslationX(sign * Math.min(moved, mMax));
            if (active) {
                mSwiping = holder;
                boolean armed = pull >= mThreshold;
                if (armed && !mArmed) Haptics.perform(column, HapticMap.Kind.TICK);
                mArmed = armed;
            }
            if (mArrow != null && pull > 4) {
                float dp = holder.itemView.getResources().getDisplayMetrics().density;
                int size = Math.round(22 * dp);
                float progress = Math.min(1f, pull / mThreshold);
                int cy = holder.itemView.getTop() + holder.itemView.getHeight() / 2;
                int cx = Math.round(holder.itemView.getLeft() + 18 * dp + size / 2f);
                if (sign < 0) cx = Math.round(holder.itemView.getRight() - 18 * dp - size / 2f);
                mArrow.setAlpha(Math.round(255 * progress));
                int half = Math.round(size * (0.6f + 0.4f * progress) / 2f);
                mArrow.setBounds(cx - half, cy - half, cx + half, cy + half);
                mArrow.draw(c);
            }
        }

        @Override
        public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder holder) {
            super.clearView(recyclerView, holder);
            View column = holder.itemView.findViewById(R.id.projects_msg_column);
            if (column != null) {
                if (Motion.enabled(recyclerView.getContext())) {
                    column.animate().translationX(0f).setDuration(200).setInterpolator(Motion.EASE_OUT).start();
                } else {
                    column.setTranslationX(0f);
                }
            }
            if (mArmed && holder == mSwiping) {
                int position = holder.getBindingAdapterPosition();
                if (position >= 0 && position < mAdapter.mRows.size()) {
                    Row row = mAdapter.mRows.get(position);
                    if (row.entry != null && row.entry.message != null) startReply(row.entry.message);
                }
            }
            mArmed = false;
            mSwiping = null;
        }
    }
}
