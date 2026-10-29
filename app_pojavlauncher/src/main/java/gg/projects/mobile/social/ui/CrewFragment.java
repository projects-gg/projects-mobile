package gg.projects.mobile.social.ui;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
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
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.gson.JsonObject;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import gg.projects.mobile.mods.Motion;
import gg.projects.mobile.social.BadgeRules;
import gg.projects.mobile.social.ChatBook;
import gg.projects.mobile.social.ChatConv;
import gg.projects.mobile.social.Json;
import gg.projects.mobile.social.SocialCodes;
import gg.projects.mobile.social.SocialGate;
import gg.projects.mobile.social.SocialModel;
import gg.projects.mobile.social.SocialModel.Call;
import gg.projects.mobile.social.SocialModel.Crew;
import gg.projects.mobile.social.SocialModel.JoinRequest;
import gg.projects.mobile.social.SocialModel.Member;
import gg.projects.mobile.social.SocialModel.MyRequest;
import gg.projects.mobile.social.SocialModel.Plan;
import gg.projects.mobile.social.SocialModel.Profile;
import gg.projects.mobile.social.SocialModel.Team;
import gg.projects.mobile.social.SocialModel.TeamInvite;
import gg.projects.mobile.social.SocialModel.TeamNotifications;
import gg.projects.mobile.social.SocialModel.TeamSummary;
import gg.projects.mobile.social.SocialNotifications;
import gg.projects.mobile.social.SocialProblem;
import gg.projects.mobile.social.SocialService;
import gg.projects.mobile.social.SocialState;
import gg.projects.mobile.ui.ChamferDrawable;
import gg.projects.mobile.ui.Haptics;
import gg.projects.mobile.ui.Press;
import gg.projects.mobile.ui.ScreenHeader;

/**
 * The crew page (M19; the desktop's crew page, TEAM3-L): the crew's card in its look, the call
 * (Geliyorum / Birazdan / Gelemem; "Geliyorum" takes the player where the caller is), members by where
 * they are with their role and "Katıl", the crew code, inviting friends, and leaving. Without a crew:
 * crew invites, joining with a code and creating one. Editing the look, the permissions and moderators
 * stay on the desktop launcher; the phone does what a player needs on the go. Each part shows only
 * while the service offers it ({@code teamFeatures}).
 *
 * <p>M20 (SOC7): with several crews ({@code multi}) a rail of their marks sits under the header in the
 * player's order, the main crew first and "+" last; a tap shows that crew (the page slides in from its
 * side), holding a mark moves it along the rail, and a tap on the crew shown offers "Ana ekip yap",
 * "Sola taşı", "Sağa taşı". "+" lists the crew invites, the code and "Ekip kur", or at the limit "En
 * çok N ekipte olabilirsin." The crew's chat opens from its page; "Rapor et" reports the crew. The
 * phone does not call the crew (CALL1: calls start only from a multiplayer server, and the phone cannot
 * say it is in game yet).
 *
 * <p>M21 (TEAM5): those who may answer see the crew's join requests at the top ("Kabul et", ×, and
 * "Engelle" / "Rapor et" on the row); "Planlar" shows the crew's plans with Geliyorum / Belki / Gelemem
 * and "Plan yap"; under the code, the invite link (copy, share; "Yeni bağlantı" and "Bağlantıyı kapat"
 * for whoever manages it) and, for the leader, "Yeni üyeleri onayla". "+" lists the player's own join
 * requests ("Geri çek"); the code field takes an invite link too, and a {@code projects://crew/} link
 * fills it and looks it up once. A crew that approves newcomers offers "Katılma isteği gönder".
 *
 * <p>FIX2: a plan carries its note (written when it is made, changed from its menu); who answered a plan
 * or a call opens in place under its card ({@link AnswerList}); members, join requests, the crew's card,
 * a second tap on its mark and the "+" invites hold the same menus as anywhere else ({@link SocialMenuSheet}).
 */
public class CrewFragment extends Fragment implements SocialService.Listener {
    public static final String TAG = "ProjectsCrewFragment";
    /** A crew id (that crew shows) or a call id (its first crew), from a notification. */
    public static final String ARG_CREW = "crew";

    private static final String ADD = "+";

    private static final int T_CARD = 0;
    private static final int T_CALL = 1;
    private static final int T_HEADER = 3;
    private static final int T_MEMBER = 4;
    private static final int T_CODE = 5;
    private static final int T_ACTION = 6;
    private static final int T_TEXT = 7;
    private static final int T_CHAT = 8;
    private static final int T_NOTICE = 9;
    private static final int T_REQUEST = 10;
    private static final int T_PLAN = 11;
    private static final int T_LINK = 12;
    private static final int T_SWITCH = 13;
    /** TEAM6: the leader's "Görünürlük" and "Etiketten istek". */
    private static final int T_TAGS = 14;
    /** M21: a crew invite link ({@code XXXX-XXXX-XXXX}) to look up on "+" (a {@code projects://crew/} link). */
    public static final String ARG_LINK = "link";

    /** The crew a page that just made or joined one shows next (it comes with the answer). */
    @Nullable
    private static String sSelectNext;

    private final Adapter mAdapter = new Adapter();
    private final RailAdapter mRailAdapter = new RailAdapter();
    private RecyclerView mList;
    private RecyclerView mRail;
    private View mRailBox;
    private View mRailLine;
    private View mPage;
    private View mNone;
    private LinearLayout mInvites;
    private TextView mLimitText;
    private View mJoinGroup;
    private EditText mJoinCode;
    private TextView mJoinMessage;
    private View mJoinResult;
    private Button mJoinFind;
    private Button mJoinSubmit;
    private TextView mCreateReason;
    private ImageButton mBell;
    private boolean mBusy;
    /** The crew shown ({@link #ADD} for "+"); null before the first render. */
    @Nullable
    private String mSelected;
    @Nullable
    private String mPendingArg;
    /** M21: an invite link to look up once the list is there. */
    @Nullable
    private String mPendingLink;
    /** M21: join requests with an answer on its way ("crew|user"), plans with one ("plan"). */
    private final java.util.Set<String> mRequestBusy = new java.util.HashSet<>();
    private final java.util.Set<String> mPlanBusy = new java.util.HashSet<>();
    /** M21: own join requests being withdrawn (crew ids). */
    private final java.util.Set<String> mWithdrawBusy = new java.util.HashSet<>();
    /** M21: "Yeni üyeleri onayla" waits for the service. */
    private boolean mApprovalBusy;
    /** TEAM6: a tag setting's answer is on its way. */
    private boolean mTagsBusy;
    /** M21: crews "+" shows the player's waiting request to (its card, their list): one that comes is shown. */
    private final java.util.Set<String> mAwaiting = new java.util.HashSet<>();
    /** FIX2: plans and calls whose answers are open ("Kimler geliyor"), and groups that show everyone. */
    private final java.util.Set<String> mOpenAnswers = new java.util.HashSet<>();
    private final java.util.Set<String> mAllAnswers = new java.util.HashSet<>();
    private final AnswerList.Host mAnswers = new AnswerList.Host() {
        @Override
        public boolean isOpen(String key) {
            return mOpenAnswers.contains(key);
        }

        @Override
        public void setOpen(String key, boolean open) {
            if (open) mOpenAnswers.add(key);
            else mOpenAnswers.remove(key);
        }

        @Override
        public boolean showsAll(String key, String answer) {
            return mAllAnswers.contains(key + "|" + answer);
        }

        @Override
        public void setShowsAll(String key, String answer, boolean all) {
            if (all) mAllAnswers.add(key + "|" + answer);
            else mAllAnswers.remove(key + "|" + answer);
        }

        @Override
        public void person(String userId, View anchor) {
            Crew crew = shown();
            if (isAdded()) SocialMenuSheet.person(requireActivity(), userId, crew == null ? null : crew.id(), SocialMenuSheet.Place.PLAN, anchor);
        }
    };
    private final Runnable mTick = this::render;

    public CrewFragment() {
        super(R.layout.fragment_projects_crew);
    }

    /** The next crew page shows {@code crewId} (a crew made or joined just now). */
    static void selectNext(@Nullable String crewId) {
        sSelectNext = crewId;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle args = getArguments();
        if (args != null) mPendingArg = args.getString(ARG_CREW);
        if (args != null && savedInstanceState == null) mPendingLink = args.getString(ARG_LINK);
        if (savedInstanceState != null) mSelected = savedInstanceState.getString("selected");
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mSelected != null) outState.putString("selected", mSelected);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        ScreenHeader header = view.findViewById(R.id.projects_crew_header);
        mBell = header.addAction(R.drawable.projects_ic_bell, R.string.projects_crew_notifications, v -> openNotifications());
        mList = view.findViewById(R.id.projects_crew_list);
        mList.setLayoutManager(new LinearLayoutManager(view.getContext()));
        mList.setAdapter(mAdapter);
        if (Motion.enabled(view.getContext())) {
            DefaultItemAnimator animator = new DefaultItemAnimator();
            animator.setSupportsChangeAnimations(false);
            animator.setAddDuration(Motion.ENTER_MS);
            animator.setMoveDuration(Motion.ENTER_MS);
            animator.setRemoveDuration(Motion.EXIT_MS);
            mList.setItemAnimator(animator);
        } else {
            mList.setItemAnimator(null);
        }
        mRailBox = view.findViewById(R.id.projects_crew_rail_box);
        mRail = view.findViewById(R.id.projects_crew_rail);
        mRailLine = view.findViewById(R.id.projects_crew_rail_line);
        mRail.setLayoutManager(new LinearLayoutManager(view.getContext(), LinearLayoutManager.HORIZONTAL, false));
        mRail.setAdapter(mRailAdapter);
        mRail.setItemAnimator(Motion.enabled(view.getContext()) ? new DefaultItemAnimator() : null);
        mRail.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                placeLine(false);
            }
        });
        mRail.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> placeLine(false));
        new ItemTouchHelper(new RailDrag()).attachToRecyclerView(mRail);
        mPage = view.findViewById(R.id.projects_crew_page);
        mNone = view.findViewById(R.id.projects_crew_none);
        mInvites = view.findViewById(R.id.projects_crew_invites);
        mLimitText = view.findViewById(R.id.projects_crew_limit_text);
        mJoinGroup = view.findViewById(R.id.projects_crew_join_group);
        mJoinCode = view.findViewById(R.id.projects_crew_join_code);
        mJoinMessage = view.findViewById(R.id.projects_crew_join_message);
        mJoinResult = view.findViewById(R.id.projects_crew_join_result);
        mJoinFind = view.findViewById(R.id.projects_crew_join_find);
        mJoinSubmit = view.findViewById(R.id.projects_crew_join_submit);
        mCreateReason = view.findViewById(R.id.projects_crew_create_reason);
        mJoinSubmit.setBackground(ChamferDrawable.outlined(view.getContext()));
        Press.attach(mJoinSubmit, Press.Style.BUTTON);
        Press.attach(mJoinFind, Press.Style.BUTTON);
        View create = view.findViewById(R.id.projects_crew_create);
        Press.attach(create, Press.Style.BUTTON);
        create.setOnClickListener(this::create);
        mJoinFind.setOnClickListener(v -> lookup());
        mJoinCode.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_UP;
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                lookup();
                return true;
            }
            return false;
        });
        Drawable lock = ContextCompat.getDrawable(view.getContext(), R.drawable.projects_ic_crew);
        if (lock != null) {
            lock = lock.mutate();
            lock.setTint(ContextCompat.getColor(view.getContext(), R.color.projects_gold_300));
            int size = Math.round(20 * getResources().getDisplayMetrics().density);
            lock.setBounds(0, 0, size, size);
            mLimitText.setCompoundDrawablesRelative(lock, null, null, null);
        }
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
        if (!service.signedIn()) {
            // Signed out meanwhile (elsewhere or by the service): the crew page goes away. Not a swap to the
            // friends screen: back from there would come here again.
            SocialUi.leave(this);
            return;
        }
        service.addListener(this);
        service.surfaceShown(SocialService.Surface.CREW, true);
        service.refresh(false);
        render();
    }

    @Override
    public void onPause() {
        super.onPause();
        SocialService service = SocialService.get(requireContext());
        service.removeListener(this);
        service.surfaceShown(SocialService.Surface.CREW, false);
        if (mList != null) mList.removeCallbacks(mTick);
    }

    @Override
    public void onDestroyView() {
        // The adapters are the fragment's and outlive this view (back stack): let go of the old lists.
        if (mList != null) {
            mList.removeCallbacks(mTick);
            mList.setAdapter(null);
        }
        if (mRail != null) mRail.setAdapter(null);
        super.onDestroyView();
    }

    @Override
    public void onSocialChanged() {
        render();
    }

    /** The crew shown: what a notification asked for, a new crew, the one shown before, else the main crew. */
    private void resolveSelection(SocialState state, boolean multi) {
        List<Crew> crews = state.crews();
        if (sSelectNext != null && state.crew(sSelectNext) != null) {
            mSelected = sSelectNext;
            sSelectNext = null;
        }
        if (mPendingArg != null && state.loaded()) {
            String arg = mPendingArg;
            mPendingArg = null;
            if (state.crew(arg) != null) {
                mSelected = arg;
            } else {
                Call call = state.call(arg);
                List<String> named = call == null ? Collections.<String>emptyList() : state.callCrewIds(call);
                if (!named.isEmpty()) mSelected = named.get(0);
            }
        }
        if (!multi) {
            mSelected = crews.isEmpty() ? ADD : crews.get(0).id();
            return;
        }
        if (mSelected == null || (!ADD.equals(mSelected) && state.crew(mSelected) == null)) {
            mSelected = crews.isEmpty() ? ADD : crews.get(0).id();
        }
    }

    private void render() {
        if (!isAdded() || getView() == null) return;
        SocialService service = SocialService.get(requireContext());
        SocialGate gate = service.gate();
        if (!service.enabled() || !service.signedIn() || (service.state().loaded() && !gate.teams)) {
            SocialUi.leave(this);
            return;
        }
        // MOD1: a moderator's note shows on whichever friends screen is open when it comes.
        if (isResumed()) AccountNotes.showNotices(requireActivity());
        SocialState state = service.state();
        // M21: an invite link opened from outside shows on "+" (looked up below, once).
        String openLink = null;
        if (mPendingLink != null && state.loaded()) {
            openLink = mPendingLink;
            mPendingLink = null;
            if (gate.multi || state.crews().isEmpty()) mSelected = ADD;
        }
        // M21: a crew the player asked to join on "+" (its card or their list) that came now (accepted) shows itself.
        if (ADD.equals(mSelected) && gate.multi) {
            for (String id : mAwaiting) {
                if (state.crew(id) == null) continue;
                mSelected = id;
                if (id.equals(mLookupTeam)) {
                    mLookupTeam = null;
                    mJoinResult.setVisibility(View.GONE);
                    mJoinCode.setText("");
                }
                break;
            }
        }
        mAwaiting.clear();
        resolveSelection(state, gate.multi);
        renderRail(state, gate);
        Crew crew = ADD.equals(mSelected) ? null : state.crew(mSelected);
        boolean inCrew = crew != null;
        mList.setVisibility(inCrew ? View.VISIBLE : View.GONE);
        mNone.setVisibility(inCrew ? View.GONE : View.VISIBLE);
        long now = SocialService.nowSeconds();
        long hub = state.hubNow(now);
        TeamNotifications all = state.teamNotifications();
        boolean muted = gate.mute && (all.mutedAt(hub) || !all.calls || (gate.multi && crew != null && crew.mute.mutedAt(hub)));
        mBell.setVisibility(gate.calls && (inCrew || !gate.multi) ? View.VISIBLE : View.GONE);
        mBell.setImageResource(muted ? R.drawable.projects_ic_bell_slash : R.drawable.projects_ic_bell);
        mList.removeCallbacks(mTick);
        if (inCrew) {
            mAdapter.submit(rows(service, crew, now));
            // The call's minutes and its end, and a timed mute's end: twice a minute while there is one.
            boolean ticking = !state.calls(now).isEmpty() || (muted && (all.muteUntil != null || crew.mute.muteUntil != null))
                    || crew.team.textLocked(hub);
            if (ticking) {
                mList.postDelayed(mTick, 30_000);
            } else {
                // M21: a plan starts or ends with no message: the page looks again then (nothing ticks meanwhile).
                long next = nextPlanChange(state.plans(now, crew.id()), hub);
                if (next > 0) mList.postDelayed(mTick, Math.min(next, 3600) * 1000L + 500);
            }
        } else {
            renderNone(service);
        }
        if (openLink != null) openInviteLink(service, openLink);
    }

    /** Seconds until one of {@code plans} starts or is over by the hub's clock (0: none). */
    private static long nextPlanChange(List<Plan> plans, long hub) {
        long next = 0;
        for (Plan p : plans) {
            long at = p.at > hub ? p.at - hub : p.until - hub;
            if (at > 0 && (next == 0 || at < next)) next = at;
        }
        return next;
    }

    /** M21: a {@code projects://crew/} link: its crew on "+" through the code field (looked up once). */
    private void openInviteLink(SocialService service, String link) {
        SocialState state = service.state();
        if (!service.gate().multi && !state.crews().isEmpty()) {
            SocialUi.toast(requireContext(), getString(R.string.projects_crew_rel_elsewhere));
            return;
        }
        if (service.gate().multi && state.atCrewLimit() && state.crewLimit() > 0) {
            SocialUi.toast(requireContext(), getResources().getQuantityString(R.plurals.projects_crews_limit, state.crewLimit(), state.crewLimit()));
            return;
        }
        String shown = SocialCodes.inviteLinkShort(service.serviceBase(), link);
        mJoinCode.setText(shown);
        mJoinCode.setSelection(shown.length());
        mBusy = false;
        lookup();
    }

    // ---- the rail (TEAM4) ----

    private void renderRail(SocialState state, SocialGate gate) {
        List<String> ids = new ArrayList<>();
        if (gate.multi && !state.crews().isEmpty()) {
            ids.addAll(state.crewIds());
            ids.add(ADD);
        }
        mRailBox.setVisibility(ids.isEmpty() ? View.GONE : View.VISIBLE);
        mRailAdapter.submit(ids, railContent(state, ids));
        placeLine(false);
    }

    /** What each mark shows (a mark re-binds only when it changes). */
    private Map<String, String> railContent(SocialState state, List<String> ids) {
        Map<String, String> out = new HashMap<>();
        long now = SocialService.nowSeconds();
        long hub = state.hubNow(now);
        ChatBook book = SocialService.get(requireContext()).chat().book();
        for (String id : ids) {
            if (ADD.equals(id)) {
                out.put(id, "add|" + state.teamInvites().size() + "|" + state.atCrewLimit());
                continue;
            }
            Crew c = state.crew(id);
            if (c == null) continue;
            boolean call = !state.calls(now, id).isEmpty();
            int unread = book == null ? 0 : book.unread(ChatConv.crew(id));
            out.put(id, c.team.name + c.team.tag + c.team.emblem + c.team.color + c.team.frame + "|" + call + "|" + unread
                    + "|" + c.mute.mutedAt(hub) + "|" + id.equals(mSelected) + "|" + id.equals(state.crewIds().get(0))
                    + "|" + state.requests(id, now).size());
        }
        return out;
    }

    /** The gold line slides under the crew shown (at once while the rail scrolls). */
    private void placeLine(boolean animate) {
        if (mRail == null || mRailLine == null || mSelected == null) return;
        int position = mRailAdapter.indexOf(mSelected);
        RecyclerView.ViewHolder holder = position < 0 ? null : mRail.findViewHolderForAdapterPosition(position);
        if (holder == null) {
            mRailLine.setVisibility(View.INVISIBLE);
            return;
        }
        View item = holder.itemView;
        mRailLine.setVisibility(View.VISIBLE);
        float x = item.getLeft() + item.getTranslationX() + (item.getWidth() - mRailLine.getWidth()) / 2f;
        mRailLine.animate().cancel();
        if (animate && Motion.enabled(requireContext()) && mRailLine.isLaidOut()) {
            mRailLine.animate().translationX(x).setDuration(260).setInterpolator(Motion.EASE_OUT).start();
        } else {
            mRailLine.setTranslationX(x);
        }
    }

    /** Shows another crew (or "+"): the page comes in from the side of the new mark. */
    private void select(String id) {
        if (id.equals(mSelected)) return;
        int from = mRailAdapter.indexOf(mSelected);
        int to = mRailAdapter.indexOf(id);
        mSelected = id;
        mBusy = false;
        render();
        mList.scrollToPosition(0);
        int position = mRailAdapter.indexOf(id);
        if (position >= 0) mRail.smoothScrollToPosition(position);
        placeLine(true);
        if (Motion.enabled(requireContext()) && mPage.isLaidOut()) {
            float distance = 28 * getResources().getDisplayMetrics().density * (to >= from ? 1 : -1);
            mPage.animate().cancel();
            mPage.setTranslationX(distance);
            mPage.setAlpha(0.35f);
            mPage.animate().translationX(0f).alpha(1f).setDuration(Motion.ENTER_MS).setInterpolator(Motion.EASE_OUT).start();
        }
    }

    /** Moves a crew to {@code position} in the player's order (0 makes it the main crew). */
    private void move(final String crewId, final int position) {
        final FragmentActivity activity = requireActivity();
        final SocialService service = SocialService.get(activity);
        List<String> order = new ArrayList<>(service.state().crewIds());
        if (!order.remove(crewId)) return;
        order.add(Math.max(0, Math.min(position, order.size())), crewId);
        commitOrder(order);
        Crew crew = service.state().crew(crewId);
        if (crew != null) {
            SocialUi.toast(activity, position == 0 ? getString(R.string.projects_crews_made_main, crew.team.name)
                    : getString(R.string.projects_crews_moved, crew.team.name, position + 1));
        }
    }

    private void commitOrder(List<String> order) {
        final FragmentActivity activity = requireActivity();
        SocialService.get(activity).setCrewOrder(order, new SocialService.Result<Void>() {
            @Override
            public void ok(Void value) {
            }

            @Override
            public void failed(SocialProblem problem) {
                SocialUi.toastProblem(activity, problem, mRail);
            }
        });
    }

    /** Holding a mark lifts it; it moves along the rail and lands where the finger leaves it. */
    private final class RailDrag extends ItemTouchHelper.Callback {
        private boolean mMoved;

        @Override
        public int getMovementFlags(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            if (position < 0 || ADD.equals(mRailAdapter.idAt(position)) || mRailAdapter.getItemCount() < 3) return 0;
            return makeMovementFlags(ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT, 0);
        }

        @Override
        public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder from, @NonNull RecyclerView.ViewHolder to) {
            int a = from.getAdapterPosition();
            int b = to.getAdapterPosition();
            if (a < 0 || b < 0 || ADD.equals(mRailAdapter.idAt(b))) return false;
            mRailAdapter.moveItem(a, b);
            mMoved = true;
            Haptics.tick(to.itemView);
            placeLine(false);
            return true;
        }

        @Override
        public void onSwiped(@NonNull RecyclerView.ViewHolder holder, int direction) {
        }

        @Override
        public boolean isLongPressDragEnabled() {
            return true;
        }

        @Override
        public void onSelectedChanged(@Nullable RecyclerView.ViewHolder holder, int actionState) {
            super.onSelectedChanged(holder, actionState);
            if (holder != null && actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                mMoved = false;
                holder.itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                if (Motion.enabled(holder.itemView.getContext())) {
                    holder.itemView.animate().scaleX(1.12f).scaleY(1.12f).setDuration(140).setInterpolator(Motion.EASE_OUT).start();
                }
            }
        }

        @Override
        public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder holder) {
            super.clearView(recyclerView, holder);
            holder.itemView.animate().scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(Motion.EASE_OUT).start();
            if (mMoved) {
                mMoved = false;
                List<String> order = new ArrayList<>();
                for (String id : mRailAdapter.ids()) if (!ADD.equals(id)) order.add(id);
                commitOrder(order);
            }
            placeLine(true);
        }
    }

    private final class RailAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private List<String> mIds = new ArrayList<>();
        private Map<String, String> mContent = new HashMap<>();
        private final Map<String, Long> mStable = new HashMap<>();

        RailAdapter() {
            setHasStableIds(true);
        }

        List<String> ids() {
            return mIds;
        }

        String idAt(int position) {
            return mIds.get(position);
        }

        int indexOf(@Nullable String id) {
            return id == null ? -1 : mIds.indexOf(id);
        }

        void moveItem(int from, int to) {
            String id = mIds.remove(from);
            mIds.add(to, id);
            notifyItemMoved(from, to);
        }

        void submit(final List<String> ids, final Map<String, String> content) {
            final List<String> old = mIds;
            final Map<String, String> oldContent = mContent;
            mIds = new ArrayList<>(ids);
            mContent = content;
            DiffUtil.calculateDiff(new DiffUtil.Callback() {
                @Override public int getOldListSize() { return old.size(); }
                @Override public int getNewListSize() { return ids.size(); }
                @Override public boolean areItemsTheSame(int o, int n) { return old.get(o).equals(ids.get(n)); }
                @Override public boolean areContentsTheSame(int o, int n) {
                    String a = oldContent.get(old.get(o));
                    return a != null && a.equals(content.get(ids.get(n)));
                }
            }).dispatchUpdatesTo(this);
        }

        @Override
        public long getItemId(int position) {
            String id = mIds.get(position);
            Long stable = mStable.get(id);
            if (stable == null) {
                stable = (long) mStable.size();
                mStable.put(id, stable);
            }
            return stable;
        }

        @Override
        public int getItemCount() {
            return mIds.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_projects_crew_rail, parent, false);
            Press.attach(view, Press.Style.ICON);
            return new RecyclerView.ViewHolder(view) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            bindRail(holder.itemView, mIds.get(position), position);
        }
    }

    private void bindRail(final View view, final String id, int position) {
        Context context = view.getContext();
        SocialService service = SocialService.get(context);
        SocialState state = service.state();
        CrewMarkView mark = view.findViewById(R.id.projects_rail_mark);
        ImageView plus = view.findViewById(R.id.projects_rail_plus);
        View dot = view.findViewById(R.id.projects_rail_dot);
        ImageView muted = view.findViewById(R.id.projects_rail_muted);
        TextView tag = view.findViewById(R.id.projects_rail_tag);
        boolean selected = id.equals(mSelected);
        view.setSelected(selected);
        // 0.4.5: the chosen crew's tag in bone, the one selected look (it was gold).
        tag.setTextColor(ContextCompat.getColor(context, selected ? R.color.projects_bone_100 : R.color.projects_ash_300));
        if (ADD.equals(id)) {
            mark.setVisibility(View.GONE);
            plus.setVisibility(View.VISIBLE);
            muted.setVisibility(View.GONE);
            // No caption under the plus (it said "+" twice); the line keeps the tiles level.
            tag.setText("");
            boolean full = state.atCrewLimit();
            // At the limit "+" stays (an invite can still be declined there), dimmer.
            plus.setAlpha(full && state.teamInvites().isEmpty() ? 0.5f : 1f);
            dot.setVisibility(state.teamInvites().isEmpty() ? View.GONE : View.VISIBLE);
            view.setContentDescription(full ? getResources().getQuantityString(R.plurals.projects_crews_limit,
                    state.crewLimit(), state.crewLimit()) : getString(R.string.projects_crew_row_none));
            view.setOnClickListener(v -> select(ADD));
            ViewCompat.setAccessibilityDelegate(view, null);
            return;
        }
        Crew crew = state.crew(id);
        if (crew == null) return;
        plus.setVisibility(View.GONE);
        mark.setVisibility(View.VISIBLE);
        mark.setLook(crew.team.emblem, crew.team.color, crew.team.frame);
        tag.setText(crew.team.tag.isEmpty() ? crew.team.name : crew.team.tag);
        long now = SocialService.nowSeconds();
        ChatBook book = service.chat().book();
        boolean call = !state.calls(now, id).isEmpty();
        boolean unread = book != null && book.unread(ChatConv.crew(id)) > 0;
        // M21: join requests waiting for the player's answer light the dot too.
        int requests = service.gate().requests ? state.requests(id, now).size() : 0;
        dot.setVisibility(call || unread || requests > 0 ? View.VISIBLE : View.GONE);
        boolean isMuted = crew.mute.mutedAt(state.hubNow(now));
        muted.setVisibility(isMuted ? View.VISIBLE : View.GONE);
        boolean main = position == 0;
        StringBuilder description = new StringBuilder(crew.team.name);
        if (main) description.append(", ").append(getString(R.string.projects_crews_main));
        if (call) {
            // Whose call it is, as the call card says it (not the "Ekibi çağır" action).
            Call first = state.calls(now, id).get(0);
            Profile caller = state.profile(first.from);
            description.append(", ").append(first.from.equals(state.myId()) ? getString(R.string.projects_crew_call_mine)
                    : getString(R.string.projects_crew_call_theirs, caller == null ? getString(R.string.projects_crew_someone) : caller.name()));
        }
        if (unread) {
            int count = book.unread(ChatConv.crew(id));
            description.append(", ").append(getResources().getQuantityString(R.plurals.projects_chat_unread, count, count));
        }
        if (requests > 0) {
            description.append(", ").append(getResources().getQuantityString(R.plurals.projects_requests_count, requests, requests));
        }
        if (isMuted) description.append(", ").append(getString(R.string.projects_crew_muted));
        view.setContentDescription(description);
        view.setOnClickListener(v -> {
            // FIX2: a second tap on the crew shown opens its menu (making it the main crew, moving it, and the rest).
            if (id.equals(mSelected)) SocialMenuSheet.crew(requireActivity(), id, true, false, v);
            else select(id);
        });
        ViewCompat.setAccessibilityDelegate(view, new AccessibilityDelegateCompat() {
            @Override
            public void onInitializeAccessibilityNodeInfo(@NonNull View host, @NonNull AccessibilityNodeInfoCompat info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                List<String> order = SocialService.get(host.getContext()).state().crewIds();
                int at = order.indexOf(id);
                if (at > 0) {
                    info.addAction(new AccessibilityNodeInfoCompat.AccessibilityActionCompat(R.id.projects_crew_rail_id,
                            getString(R.string.projects_crews_make_main)));
                }
            }

            @Override
            public boolean performAccessibilityAction(@NonNull View host, int action, @Nullable Bundle args) {
                if (action == R.id.projects_crew_rail_id) {
                    move(id, 0);
                    return true;
                }
                return super.performAccessibilityAction(host, action, args);
            }
        });
    }

    // ---- in a crew ----

    private static final class Row {
        final int type;
        final String id;
        final String content;
        @Nullable
        final Object data;

        Row(int type, String id, String content, @Nullable Object data) {
            this.type = type;
            this.id = id;
            this.content = content;
            this.data = data;
        }
    }

    /** M21: a join request row and the crew it waits in. */
    private static final class RequestRow {
        final String crew;
        final JoinRequest request;

        RequestRow(String crew, JoinRequest request) {
            this.crew = crew;
            this.request = request;
        }
    }

    private static final class Action {
        final String kind;
        final int icon;
        final int label;
        final int tint;

        Action(String kind, int icon, int label, int tint) {
            this.kind = kind;
            this.icon = icon;
            this.label = label;
            this.tint = tint;
        }
    }

    private List<Row> rows(SocialService service, Crew crew, long now) {
        SocialState state = service.state();
        SocialGate gate = service.gate();
        Team team = crew.team;
        String me = state.myId();
        List<Row> rows = new ArrayList<>();
        boolean main = gate.multi && state.crews().size() > 1 && state.crewIds().get(0).equals(team.id);
        long hub = state.hubNow(now);
        rows.add(new Row(T_CARD, "card", team.name + team.tag + team.motto + team.emblem + team.color + team.frame + team.members.size()
                + "|" + state.limits().teamMembers + "|" + main + "|" + wearKey(service, team, hub), crew));
        if (team.textLocked(hub)) {
            String text = getString(R.string.projects_mod_crew_reset) + " " + getString(R.string.projects_mod_crew_locked_until,
                    SocialUi.day(requireContext(), team.textLockedUntil));
            rows.add(new Row(T_NOTICE, "locked", text, text));
        }
        if (service.chat().available()) {
            ChatBook book = service.chat().book();
            String conv = ChatConv.crew(team.id);
            ChatBook.Summary summary = null;
            if (book != null) for (ChatBook.Summary s : book.summaries()) if (s.conv.equals(conv)) summary = s;
            String last = summary == null || summary.last == null ? "" : summary.last.key() + summary.last.text();
            rows.add(new Row(T_CHAT, "chat", team.id + "|" + last + "|" + (summary == null ? 0 : summary.unread), summary));
        }
        if (gate.calls) {
            for (Call call : state.calls(now, team.id)) {
                long left = state.secondsLeft(call, now);
                rows.add(new Row(T_CALL, "call-" + call.id, call.kind + "|" + call.place() + call.serverHost + "|" + call.answers
                        + "|" + ((left + 59) / 60) + "|" + call.crews + "|" + answersState(call.id), call));
            }
        }
        // M21 (TEAM5): join requests for whoever may answer them, at the top: they wait for an answer.
        if (gate.requests) {
            List<JoinRequest> requests = state.requests(team.id, now);
            if (!requests.isEmpty()) {
                String text = getString(R.string.projects_requests_title) + " · " + requests.size();
                rows.add(new Row(T_HEADER, "h-requests", text, text));
                for (JoinRequest r : requests) {
                    rows.add(new Row(T_REQUEST, "r-" + team.id + "-" + r.from.id, r.from.name() + r.from.faceKey() + "|" + viaName(team, r) + "|"
                            + PlanViews.ago(requireContext(), r.sentAt, now) + "|" + mRequestBusy.contains(team.id + "|" + r.from.id),
                            new RequestRow(team.id, r)));
                }
            }
        }
        // M21 (TEAM5): "Planlar": the crew's plans and "Plan yap" for whoever may call the crew.
        if (gate.plans) {
            List<Plan> plans = state.plans(now, team.id);
            boolean may = state.mayPlan(team.id);
            if (!plans.isEmpty() || may) {
                String text = plans.isEmpty() ? getString(R.string.projects_plans_title)
                        : getString(R.string.projects_plans_title) + " · " + plans.size();
                rows.add(new Row(T_HEADER, "h-plans", text, text));
                for (Plan p : plans) {
                    String mineAnswer = me == null ? null : p.answers.get(me);
                    rows.add(new Row(T_PLAN, "p-" + p.id, p.at + "|" + p.by + "|" + p.answers + "|" + mineAnswer + "|" + p.underWay(hub)
                            + "|" + state.mayCancel(p) + "|" + mPlanBusy.contains(p.id) + "|" + PlanViews.when(requireContext(), p.at, now)
                            + "|" + p.note + "|" + answersState(p.id), p));
                }
                if (may) {
                    rows.add(new Row(T_ACTION, "plan-make", "plan-make", new Action("plan", R.drawable.projects_ic_calendar_plus,
                            R.string.projects_plans_make, R.color.projects_gold_300)));
                }
            }
        }
        Map<SocialState.Place, List<Member>> roster = state.roster(team.id);
        int[] titles = {R.string.projects_social_on_projects, R.string.projects_social_presence_playing,
                R.string.projects_social_presence_launcher, R.string.projects_social_presence_offline};
        int i = 0;
        for (Map.Entry<SocialState.Place, List<Member>> group : roster.entrySet()) {
            int title = titles[i++];
            if (group.getValue().isEmpty()) continue;
            String text = getString(title) + " · " + group.getValue().size();
            rows.add(new Row(T_HEADER, "h-" + group.getKey(), text, text));
            for (Member m : group.getValue()) {
                SocialModel.Presence p = state.presence(m.profile.id);
                // The player's own row shows their status (their presence is not sent to themselves).
                String mine = m.profile.id.equals(state.myId()) && state.me() != null ? state.me().status : "";
                rows.add(new Row(T_MEMBER, "m-" + m.profile.id, m.profile.name() + m.profile.faceKey() + m.founder + m.moderator
                        + p.state + p.dnd + p.where + p.host + state.isFriend(m.profile.id) + mine + "|" + badgeKey(gate, m.profile.badge), m));
            }
        }
        if (!team.code.isEmpty()) rows.add(new Row(T_CODE, "code", team.code, team.code));
        // M21 (TEAM5): the invite link under the code (every member shares it; who may admit manages it).
        boolean admit = gate.requests && state.mayAdmit(team.id);
        if (gate.link) {
            if (team.link != null) {
                rows.add(new Row(T_LINK, "link", team.link + "|" + admit, team.link));
            } else if (admit) {
                rows.add(new Row(T_ACTION, "link-make", "link-make", new Action("link", R.drawable.projects_ic_link,
                        R.string.projects_link_create, R.color.projects_bone_100)));
            }
        }
        Member leader = me == null ? null : team.member(me);
        if (gate.requests && leader != null && leader.founder) {
            rows.add(new Row(T_SWITCH, "approve", "approve|" + team.approval() + "|" + mApprovalBusy, team));
        }
        // TEAM6: the leader's "Görünürlük" and "Etiketten istek" (a crew without a tag has nothing to show).
        if (gate.badges && leader != null && leader.founder && !team.tag.isEmpty()) {
            rows.add(new Row(T_TAGS, "tags", "tags|" + team.hidden + "|" + team.badgeRequests + "|" + gate.badgeRequests + "|" + mTagsBusy, team));
        }
        if (me != null && team.allows(me, team.permissions.invite)) {
            rows.add(new Row(T_ACTION, "invite", "invite", new Action("invite", R.drawable.projects_ic_user_plus,
                    R.string.projects_crew_invite_title, R.color.projects_bone_100)));
        }
        // MOB1 (the lead's rule, as the desktop's 03f1689): a leader does not report their own crew.
        if (gate.reports && (leader == null || !leader.founder)) {
            rows.add(new Row(T_ACTION, "report", "report", new Action("report", R.drawable.projects_ic_flag,
                    R.string.projects_report_crew, R.color.projects_ash_300)));
        }
        rows.add(new Row(T_TEXT, "privacy", "privacy", getString(R.string.projects_crew_privacy)));
        Member mine = me == null ? null : team.member(me);
        if (mine != null && mine.founder) {
            boolean alone = team.members.size() <= 1;
            rows.add(new Row(T_ACTION, "end", "end" + alone, new Action(alone ? "close" : "disband", R.drawable.projects_ic_trash,
                    alone ? R.string.projects_crew_close : R.string.projects_crew_disband, R.color.projects_flare_400)));
        } else {
            rows.add(new Row(T_ACTION, "leave", "leave", new Action("leave", R.drawable.projects_ic_sign_out,
                    R.string.projects_crew_leave, R.color.projects_flare_400)));
        }
        return rows;
    }

    // ---- crew tags (TEAM6) ----

    /** What the card's tag control shows: a change binds the card again. */
    private static String wearKey(SocialService service, Team team, long hub) {
        if (!service.gate().badges || team.tag.isEmpty()) return "";
        String me = service.state().myId();
        SocialModel.Badge worn = me == null ? null : service.state().badgeOf(me);
        return BadgeRules.wearState(team, worn == null ? null : worn.crew, hub) + "|" + team.textLockedUntil;
    }

    private static String badgeKey(SocialGate gate, @Nullable SocialModel.Badge badge) {
        return gate.badges && badge != null ? badge.key() : "";
    }

    /** The member whose tag a join request came through ("Ayşe aracılığıyla"), or null (none, or gone from the crew). */
    @Nullable
    private static String viaName(Team team, JoinRequest request) {
        if (request.via == null) return null;
        Member via = team.member(request.via);
        return via == null ? null : via.profile.name();
    }

    /** FIX2: what a card's answers show (open, and which groups show everyone): a change binds it again. */
    private String answersState(String key) {
        if (!mOpenAnswers.contains(key)) return "closed";
        StringBuilder out = new StringBuilder("open");
        for (String all : mAllAnswers) if (all.startsWith(key + "|")) out.append(',').append(all);
        return out.toString();
    }

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private List<Row> mRows = new ArrayList<>();
        private final Map<String, Long> mIds = new HashMap<>();

        /** Stable ids once, here: the adapter outlives the fragment's view (back stack), and RecyclerView refuses the
         *  change while a list is attached. */
        Adapter() {
            setHasStableIds(true);
        }

        void submit(final List<Row> rows) {
            final List<Row> old = mRows;
            mRows = rows;
            if (old.isEmpty()) {
                notifyDataSetChanged();
                return;
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
            Context context = parent.getContext();
            View view;
            switch (viewType) {
                case T_CARD: {
                    view = inflater.inflate(R.layout.item_projects_crew_card, parent, false);
                    // MOB1b: the tag toggle is an action, so it is chamfered and presses like every button (with its tick).
                    View wear = view.findViewById(R.id.projects_crew_card_wear);
                    float d = context.getResources().getDisplayMetrics().density;
                    wear.setBackground(ChamferDrawable.toggle(context));
                    wear.setPaddingRelative(Math.round(12 * d), Math.round(6 * d), Math.round(14 * d), Math.round(6 * d));
                    Press.attach(wear, Press.Style.BUTTON);
                    break;
                }
                case T_CALL:
                    view = inflater.inflate(R.layout.item_projects_crew_call, parent, false);
                    for (int id : new int[]{R.id.projects_call_yes, R.id.projects_call_soon, R.id.projects_call_no, R.id.projects_call_end}) {
                        Press.attach(view.findViewById(id), Press.Style.BUTTON);
                    }
                    view.findViewById(R.id.projects_call_end).setBackground(ChamferDrawable.outlined(context));
                    break;
                case T_CHAT:
                    view = inflater.inflate(R.layout.item_projects_social_crew, parent, false);
                    Press.attach(view, Press.Style.CARD);
                    break;
                case T_HEADER:
                    view = inflater.inflate(R.layout.item_projects_social_header, parent, false);
                    break;
                case T_CODE:
                case T_LINK:
                    view = inflater.inflate(R.layout.item_projects_crew_code, parent, false);
                    Press.attach(view.findViewById(R.id.projects_crew_code_copy), Press.Style.ICON);
                    Press.attach(view.findViewById(R.id.projects_crew_code_share), Press.Style.ICON);
                    Press.attach(view.findViewById(R.id.projects_crew_code_more), Press.Style.ICON);
                    break;
                case T_PLAN:
                    view = inflater.inflate(R.layout.item_projects_crew_plan, parent, false);
                    for (int id : new int[]{R.id.projects_plan_yes, R.id.projects_plan_maybe, R.id.projects_plan_no}) {
                        Press.attach(view.findViewById(id), Press.Style.BUTTON);
                    }
                    Press.attach(view.findViewById(R.id.projects_plan_more), Press.Style.ICON);
                    break;
                case T_SWITCH:
                case T_TAGS: {
                    FrameLayout frame = new FrameLayout(context);
                    int side = Math.round(12 * context.getResources().getDisplayMetrics().density);
                    frame.setPadding(side, Math.round(6 * context.getResources().getDisplayMetrics().density), side, 0);
                    frame.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    view = frame;
                    break;
                }
                case T_ACTION: {
                    FrameLayout frame = new FrameLayout(context);
                    int side = Math.round(12 * context.getResources().getDisplayMetrics().density);
                    frame.setPadding(side, Math.round(6 * context.getResources().getDisplayMetrics().density), side, 0);
                    frame.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    view = frame;
                    break;
                }
                case T_NOTICE: {
                    TextView text = new TextView(context);
                    float dp = context.getResources().getDisplayMetrics().density;
                    RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.setMargins(Math.round(12 * dp), Math.round(10 * dp), Math.round(12 * dp), 0);
                    text.setLayoutParams(lp);
                    text.setBackgroundResource(R.drawable.projects_social_section_background);
                    text.setPadding(Math.round(14 * dp), Math.round(12 * dp), Math.round(14 * dp), Math.round(12 * dp));
                    text.setTextColor(ContextCompat.getColor(context, R.color.projects_ash_300));
                    text.setTextSize(13);
                    view = text;
                    break;
                }
                case T_TEXT: {
                    TextView text = new TextView(context);
                    float dp = context.getResources().getDisplayMetrics().density;
                    text.setPadding(Math.round(16 * dp), Math.round(16 * dp), Math.round(16 * dp), Math.round(4 * dp));
                    text.setTextColor(ContextCompat.getColor(context, R.color.projects_ash_400));
                    text.setTextSize(13);
                    text.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    view = text;
                    break;
                }
                case T_REQUEST:
                case T_MEMBER:
                default:
                    view = inflater.inflate(R.layout.item_projects_social_person, parent, false);
                    Press.attach(view, Press.Style.ROW);
                    Button primary = view.findViewById(R.id.projects_person_action);
                    primary.setBackground(ChamferDrawable.outlined(context));
                    Press.attach(primary, Press.Style.BUTTON);
                    Press.attach(view.findViewById(R.id.projects_person_secondary), Press.Style.ICON);
                    break;
            }
            return new RecyclerView.ViewHolder(view) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Row row = mRows.get(position);
            View view = holder.itemView;
            switch (row.type) {
                case T_CARD:
                    bindCard(view, (Crew) row.data);
                    break;
                case T_CALL:
                    bindCall(view, (Call) row.data);
                    break;
                case T_CHAT:
                    bindChat(view, (ChatBook.Summary) row.data);
                    break;
                case T_HEADER:
                    ((TextView) view).setText((String) row.data);
                    break;
                case T_CODE:
                    bindCode(view, (String) row.data);
                    break;
                case T_LINK:
                    bindLink(view, (String) row.data);
                    break;
                case T_REQUEST:
                    bindRequest(view, (RequestRow) row.data);
                    break;
                case T_PLAN:
                    bindPlan(view, (Plan) row.data);
                    break;
                case T_SWITCH:
                    bindApproval((FrameLayout) view, (Team) row.data);
                    break;
                case T_TAGS:
                    bindTags((FrameLayout) view, (Team) row.data);
                    break;
                case T_ACTION:
                    bindAction((FrameLayout) view, (Action) row.data);
                    break;
                case T_NOTICE:
                case T_TEXT:
                    ((TextView) view).setText((String) row.data);
                    break;
                case T_MEMBER:
                default:
                    bindMember(view, (Member) row.data);
                    break;
            }
        }
    }

    @Nullable
    private Crew shown() {
        if (mSelected == null || ADD.equals(mSelected)) return null;
        return SocialService.get(requireContext()).state().crew(mSelected);
    }

    private void bindCard(View view, Crew crew) {
        Context context = view.getContext();
        Team team = crew.team;
        SocialService service = SocialService.get(context);
        // FIX2: holding the crew's card opens the crew's menu (the one its row has anywhere).
        final String crewId = crew.id();
        SocialMenuSheet.attach(view, team.name, v -> {
            if (isAdded()) SocialMenuSheet.crew(requireActivity(), crewId, true, false, v);
            return true;
        });
        CrewMarkView mark = view.findViewById(R.id.projects_crew_card_mark);
        mark.setLook(team.emblem, team.color, team.frame);
        ImageView watermark = view.findViewById(R.id.projects_crew_card_watermark);
        Drawable emblem = CrewMarkView.emblemDrawable(context, team.emblem);
        if (emblem != null) emblem.setTint(gg.projects.mobile.social.CrewLook.colors(team.color).edge);
        watermark.setImageDrawable(emblem);
        ((TextView) view.findViewById(R.id.projects_crew_card_name)).setText(team.name);
        TextView tag = view.findViewById(R.id.projects_crew_card_tag);
        tag.setText(team.tag);
        tag.setVisibility(team.tag.isEmpty() ? View.GONE : View.VISIBLE);
        CrewViews.tintTag(tag, team.color);
        TextView motto = view.findViewById(R.id.projects_crew_card_motto);
        motto.setText(team.motto);
        motto.setVisibility(team.motto == null ? View.GONE : View.VISIBLE);
        int limit = service.state().limits().teamMembers;
        String members = limit > 0 ? getString(R.string.projects_crew_members, team.members.size(), limit)
                : context.getResources().getQuantityString(R.plurals.projects_crew_member_count, team.members.size(), team.members.size());
        String founded = CrewViews.day(context, team.createdOn);
        String meta = founded == null ? members : members + " · " + getString(R.string.projects_crew_founded, founded);
        if (service.gate().multi && service.state().crews().size() > 1 && service.state().crewIds().get(0).equals(team.id)) {
            meta = meta + " · " + getString(R.string.projects_crews_main);
        }
        ((TextView) view.findViewById(R.id.projects_crew_card_meta)).setText(meta);
        bindWear(view.findViewById(R.id.projects_crew_card_wear), team);
    }

    /**
     * TEAM6: under the card, "Etiketi tak", or "Etiket takılı" (a tap takes it off; the line says so with
     * "Geri al"). While the leader hides the crew it reads "Gizli ekip", while moderators lock its texts
     * "… tarihine kadar kilitli": quiet, and a tap says why.
     */
    private void bindWear(TextView control, final Team team) {
        Context context = control.getContext();
        SocialService service = SocialService.get(context);
        if (!service.gate().badges || team.tag.isEmpty()) {
            control.setVisibility(View.GONE);
            return;
        }
        String me = service.state().myId();
        SocialModel.Badge worn = me == null ? null : service.state().badgeOf(me);
        long hub = service.state().hubNow(SocialService.nowSeconds());
        final BadgeRules.Wear wear = BadgeRules.wearState(team, worn == null ? null : worn.crew, hub);
        int icon = R.drawable.projects_ic_team6_tag;
        int color;
        String text;
        final String why;
        switch (wear) {
            case WORN:
                color = R.color.projects_gold_300;
                text = getString(R.string.projects_badge_worn);
                why = null;
                break;
            case HIDDEN:
                icon = R.drawable.projects_ic_team6_eye_slash;
                color = R.color.projects_ash_400;
                text = getString(R.string.projects_badge_hidden_chip);
                why = getString(R.string.projects_badge_hidden_why);
                break;
            case LOCKED: {
                String day = team.textLockedUntil == null ? "" : SocialUi.day(context, team.textLockedUntil);
                color = R.color.projects_ash_400;
                text = getString(R.string.projects_badge_locked_until, day);
                why = getString(R.string.projects_badge_locked_why, day);
                break;
            }
            case WEAR:
            default:
                color = R.color.projects_bone_100;
                text = getString(R.string.projects_badge_wear);
                why = null;
                break;
        }
        int tint = ContextCompat.getColor(context, color);
        control.setVisibility(View.VISIBLE);
        control.setText(text);
        control.setTextColor(tint);
        Drawable glyph = ContextCompat.getDrawable(context, icon);
        if (glyph != null) {
            glyph = glyph.mutate();
            glyph.setTint(tint);
            int size = Math.round(16 * context.getResources().getDisplayMetrics().density);
            glyph.setBounds(0, 0, size, size);
        }
        control.setCompoundDrawablesRelative(glyph, null, null, null);
        control.setActivated(wear == BadgeRules.Wear.WORN);
        control.setSelected(why != null);
        if (wear == BadgeRules.Wear.WORN) {
            ViewCompat.replaceAccessibilityAction(control, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
                    getString(R.string.projects_badge_take_off), null);
        } else {
            ViewCompat.removeAccessibilityAction(control, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK.getId());
        }
        control.setOnClickListener(v -> {
            if (!isAdded()) return;
            if (why != null) {
                Haptics.reject(v);
                SocialUi.toast(requireContext(), why);
                return;
            }
            BadgeActions.wear(requireActivity(), wear == BadgeRules.Wear.WORN ? null : team.id, v);
        });
    }

    /** "Ekip sohbeti": what was said last and the unread count; a tap opens the crew's chat. */
    private void bindChat(View view, @Nullable ChatBook.Summary summary) {
        Context context = view.getContext();
        final Crew crew = shown();
        if (crew == null) return;
        SocialService service = SocialService.get(context);
        view.findViewById(R.id.projects_crew_row_mark).setVisibility(View.GONE);
        ImageView icon = view.findViewById(R.id.projects_crew_row_icon);
        icon.setVisibility(View.VISIBLE);
        icon.setImageResource(R.drawable.projects_ic_chat);
        TextView name = view.findViewById(R.id.projects_crew_row_name);
        name.setText(R.string.projects_chat_crew);
        TextView tag = view.findViewById(R.id.projects_crew_row_tag);
        int unread = summary == null ? 0 : summary.unread;
        if (unread > 0) {
            tag.setVisibility(View.VISIBLE);
            tag.setText(unread > 99 ? "99+" : String.valueOf(unread));
            tag.setBackgroundResource(R.drawable.projects_chat_unread_badge);
            tag.setTextColor(ContextCompat.getColor(context, R.color.projects_on_gold));
            tag.setContentDescription(context.getResources().getQuantityString(R.plurals.projects_chat_unread, unread, unread));
        } else {
            tag.setVisibility(View.GONE);
        }
        TextView line = view.findViewById(R.id.projects_crew_row_line);
        String me = service.state().myId();
        if (summary == null || summary.last == null || me == null) {
            line.setVisibility(View.GONE);
        } else {
            line.setVisibility(View.VISIBLE);
            // MOB1b: the "Mesajlar" list's own line (a deleted message's words, a sticker's "Çıkartma, Selam"): a
            // sticker read "Sen:" with nothing after it here (the emulator, 24 Oct).
            line.setText(ChatPreview.line(context, service.state(), summary.last, me, true));
            line.setTextColor(ContextCompat.getColor(context, unread > 0 ? R.color.projects_bone_100 : R.color.projects_ash_400));
        }
        // The crew shown when tapped (the row is not bound again when two crews' rows look the same).
        view.setOnClickListener(v -> {
            Crew now = shown();
            if (now != null) ChatFragment.open(CrewFragment.this, ChatConv.crew(now.id()));
        });
    }

    private void bindCall(View view, final Call call) {
        Context context = view.getContext();
        final SocialService service = SocialService.get(context);
        SocialState state = service.state();
        String me = state.myId();
        boolean mine = call.from.equals(me);
        long now = SocialService.nowSeconds();
        long minutes = Math.max(1, (state.secondsLeft(call, now) + 59) / 60);
        AvatarView avatar = view.findViewById(R.id.projects_call_avatar);
        View icon = view.findViewById(R.id.projects_call_icon);
        TextView title = view.findViewById(R.id.projects_call_title);
        TextView line = view.findViewById(R.id.projects_call_line);
        Button end = view.findViewById(R.id.projects_call_end);
        View answers = view.findViewById(R.id.projects_call_answers);
        avatar.setVisibility(mine ? View.GONE : View.VISIBLE);
        icon.setVisibility(mine ? View.VISIBLE : View.GONE);
        String left = getString(R.string.projects_crew_call_left, minutes);
        String place = SocialNotifications.callPlace(context, call.place(), call.serverHost);
        if (mine) {
            title.setText(R.string.projects_crew_call_mine);
            line.setText(place + " · " + left);
            end.setVisibility(View.VISIBLE);
            answers.setVisibility(View.GONE);
            end.setOnClickListener(v -> service.endCall(call.id, new SocialService.Result<Void>() {
                @Override
                public void ok(Void value) {
                }

                @Override
                public void failed(SocialProblem problem) {
                    SocialUi.toastProblem(getContext(), problem, end);
                }
            }));
        } else {
            Profile caller = state.profile(call.from);
            avatar.setProfile(caller);
            avatar.setSurfaceColor(ContextCompat.getColor(context, R.color.projects_obsidian_800));
            avatar.setDot(SocialUi.dot(state.presence(call.from)));
            // On the crew's own page the short sentence; the other crews it names come after it.
            title.setText(getString(R.string.projects_crew_call_theirs, caller == null ? getString(R.string.projects_crew_someone) : caller.name()));
            line.setText(place + " · " + left);
            end.setVisibility(View.GONE);
            answers.setVisibility(View.VISIBLE);
            String mineAnswer = me == null ? null : call.answers.get(me);
            bindAnswer(view.findViewById(R.id.projects_call_yes), call, "yes", mineAnswer);
            bindAnswer(view.findViewById(R.id.projects_call_soon), call, "soon", mineAnswer);
            bindAnswer(view.findViewById(R.id.projects_call_no), call, "no", mineAnswer);
        }
        line.setTextColor(ContextCompat.getColor(context, call.onProjects() ? R.color.projects_jade_400 : R.color.projects_gold_300));
        // FIX2: who answered opens in place; the caller holds their menu.
        AnswerList.bind(view, call.id, call.answers, gg.projects.mobile.social.AnswerPeople.CALL_ORDER,
                new int[]{R.string.projects_crew_call_said_yes, R.string.projects_crew_call_said_soon, R.string.projects_crew_call_said_no},
                tally(context, state, call), mAnswers);
        View caller = view.findViewById(R.id.projects_call_avatar);
        if (!mine) {
            Profile who = state.profile(call.from);
            SocialMenuSheet.attach(caller, who == null ? null : who.name(), v -> {
                Crew crew = shown();
                if (isAdded()) SocialMenuSheet.person(requireActivity(), call.from, crew == null ? null : crew.id(), SocialMenuSheet.Place.CALL, v);
                return true;
            });
        } else {
            SocialMenuSheet.detach(caller);
        }
    }

    /** "Geliyor: Ece, Kaan · Birazdan: Deniz · Gelemiyor: Can". */
    private static String tally(Context context, SocialState state, Call call) {
        StringBuilder out = new StringBuilder();
        String[] answers = {"yes", "soon", "no"};
        int[] labels = {R.string.projects_crew_call_said_yes, R.string.projects_crew_call_said_soon, R.string.projects_crew_call_said_no};
        for (int i = 0; i < answers.length; i++) {
            StringBuilder names = new StringBuilder();
            for (Map.Entry<String, String> e : call.answers.entrySet()) {
                if (!answers[i].equals(e.getValue())) continue;
                Profile p = state.profile(e.getKey());
                String name = e.getKey().equals(state.myId()) ? context.getString(R.string.projects_crew_you)
                        : p == null ? context.getString(R.string.projects_crew_someone) : p.name();
                if (names.length() > 0) names.append(", ");
                names.append(name);
            }
            if (names.length() == 0) continue;
            if (out.length() > 0) out.append(" · ");
            out.append(context.getString(labels[i])).append(": ").append(names);
        }
        return out.toString();
    }

    private void bindAnswer(final Button button, final Call call, final String answer, @Nullable String current) {
        Context context = button.getContext();
        boolean chosen = answer.equals(current);
        boolean leads = "yes".equals(answer) && (call.onProjects() || call.otherHost() != null);
        if (chosen) {
            button.setBackground(ChamferDrawable.primary(context));
            button.setTextColor(ContextCompat.getColor(context, R.color.projects_on_gold));
        } else {
            button.setBackground(ChamferDrawable.outlined(context));
            button.setTextColor(ContextCompat.getColor(context, R.color.projects_bone_100));
        }
        // "Geliyorum" takes the player where the caller is: its text is gold until it is chosen.
        if (leads && !chosen) button.setTextColor(ContextCompat.getColor(context, R.color.projects_gold_300));
        button.setSelected(chosen);
        button.setOnClickListener(v -> answer(button, call, answer));
    }

    /**
     * An answer is sent at once (shown before the socket confirms it). "Geliyorum" to a call on
     * Projects then asks whether to open the game (the desktop's rule 6); on another server it says the
     * address to join from the game's server list (the phone joins other servers from there).
     */
    private void answer(final Button button, final Call call, final String answer) {
        final FragmentActivity activity = requireActivity();
        final SocialService service = SocialService.get(activity);
        Haptics.confirm(button);
        service.answerCall(call.id, answer, new SocialService.Result<Void>() {
            @Override
            public void ok(Void value) {
                if (!"yes".equals(answer) || !isAdded()) return;
                // FIX2: "Bağlan" takes the player into the call's server.
                if (call.onProjects()) {
                    ConnectFlow.projects(activity);
                } else if (call.otherHost() != null) {
                    ConnectFlow.server(activity, call.otherHost());
                }
            }

            @Override
            public void failed(SocialProblem problem) {
                SocialUi.toastProblem(activity, problem, button);
            }
        });
    }

    private void bindCode(View view, final String code) {
        final String shownCode = SocialCodes.crewCode(code);
        ((TextView) view.findViewById(R.id.projects_crew_code_label)).setText(R.string.projects_crew_code);
        view.findViewById(R.id.projects_crew_code_more).setVisibility(View.GONE);
        view.findViewById(R.id.projects_crew_code_copy).setContentDescription(getString(R.string.projects_crew_copy));
        view.findViewById(R.id.projects_crew_code_share).setContentDescription(getString(R.string.projects_social_share));
        ((TextView) view.findViewById(R.id.projects_crew_code_text)).setText(shownCode);
        view.findViewById(R.id.projects_crew_code_copy).setOnClickListener(v -> {
            SocialUi.copy(requireContext(), getString(R.string.projects_crew_code), shownCode);
            Haptics.confirm(v);
            SocialUi.toast(requireContext(), getString(R.string.projects_social_copied));
        });
        view.findViewById(R.id.projects_crew_code_share).setOnClickListener(v ->
                SocialUi.share(requireActivity(), getString(R.string.projects_crew_share_text, shownCode)));
    }

    private void bindAction(FrameLayout frame, final Action action) {
        frame.removeAllViews();
        frame.addView(CrewViews.actionRow(frame.getContext(), action.icon, getString(action.label), action.tint, v -> onAction(action)));
    }

    private void onAction(Action action) {
        final FragmentActivity activity = requireActivity();
        final SocialService service = SocialService.get(activity);
        final Crew crew = shown();
        if (crew == null) return;
        final Team team = crew.team;
        switch (action.kind) {
            case "invite":
                CrewSheets.inviteFriends(activity, team.id);
                break;
            case "report":
                ReportDialog.crew(activity, team, null);
                break;
            case "plan":
                makePlan(action, crew);
                break;
            case "link":
                makeLink(crew.id(), false);
                break;
            case "leave":
                SocialUi.confirmDanger(activity, getString(R.string.projects_crew_leave), getString(R.string.projects_crew_leave_ask),
                        getString(R.string.projects_crew_leave), () -> service.leaveTeam(team.id, FriendCard.done(activity, getString(R.string.projects_crew_left))));
                break;
            case "close":
                SocialUi.confirmDanger(activity, getString(R.string.projects_crew_close), getString(R.string.projects_crew_close_ask),
                        getString(R.string.projects_crew_close), () -> service.disbandTeam(team.id, FriendCard.done(activity, getString(R.string.projects_crew_disbanded_done))));
                break;
            case "disband":
                SocialUi.confirmDanger(activity, getString(R.string.projects_crew_disband), getString(R.string.projects_crew_disband_ask),
                        getString(R.string.projects_crew_disband), () -> service.disbandTeam(team.id, FriendCard.done(activity, getString(R.string.projects_crew_disbanded_done))));
                break;
            default:
                break;
        }
    }

    // ---- join requests (TEAM5) ----

    /** A player who asked to join: their face, "12 dk önce istedi", "Kabul et" and ×; the row: "Engelle", "Rapor et". */
    private void bindRequest(View view, final RequestRow row) {
        Context context = view.getContext();
        final JoinRequest request = row.request;
        AvatarView avatar = view.findViewById(R.id.projects_person_avatar);
        avatar.setVisibility(View.VISIBLE);
        view.findViewById(R.id.projects_person_mark).setVisibility(View.GONE);
        avatar.setProfile(request.from);
        avatar.setSurfaceColor(ContextCompat.getColor(context, R.color.projects_obsidian_950));
        avatar.setDot(AvatarView.Dot.NONE);
        avatar.setDimmed(false);
        ((TextView) view.findViewById(R.id.projects_person_name)).setText(request.from.name());
        ((CrewTagChip) view.findViewById(R.id.projects_person_tag)).setBadgeOf(null, null, CrewTagChip.Size.MD);
        view.findViewById(R.id.projects_person_badge).setVisibility(View.GONE);
        view.findViewById(R.id.projects_person_star).setVisibility(View.GONE);
        TextView line = view.findViewById(R.id.projects_person_line);
        String asked = getString(R.string.projects_requests_asked, PlanViews.ago(context, request.sentAt, SocialService.nowSeconds()));
        // TEAM6: a request from a tag's card says whose tag it came through ("Ayşe aracılığıyla · 12 dk önce istedi").
        Crew asking = SocialService.get(context).state().crew(row.crew);
        String via = asking == null ? null : viaName(asking.team, request);
        if (via != null) asked = getString(R.string.projects_badge_via, via) + " · " + asked;
        line.setText(asked);
        line.setTextColor(ContextCompat.getColor(context, R.color.projects_ash_400));
        final Button accept = view.findViewById(R.id.projects_person_action);
        accept.setVisibility(View.VISIBLE);
        accept.setText(R.string.projects_social_accept);
        final ImageButton decline = view.findViewById(R.id.projects_person_secondary);
        decline.setVisibility(View.VISIBLE);
        decline.setContentDescription(getString(R.string.projects_social_decline));
        boolean busy = mRequestBusy.contains(row.crew + "|" + request.from.id);
        accept.setEnabled(!busy);
        decline.setEnabled(!busy);
        accept.setOnClickListener(v -> answerRequest(row, true, accept));
        decline.setOnClickListener(v -> answerRequest(row, false, decline));
        view.setOnClickListener(v -> requestMenu(row, v));
        SocialMenuSheet.attach(view, request.from.name(), v -> {
            requestMenu(row, v);
            return true;
        });
        view.setContentDescription(request.from.name() + ", " + asked);
    }

    /** "Kabul et" or ×: the row goes when the service answers (a decline tells its sender nothing). */
    private void answerRequest(final RequestRow row, final boolean accept, final View button) {
        final FragmentActivity activity = requireActivity();
        final SocialService service = SocialService.get(activity);
        final String key = row.crew + "|" + row.request.from.id;
        if (!mRequestBusy.add(key)) return;
        render();
        final String name = row.request.from.name();
        SocialService.Result<Object> done = new SocialService.Result<Object>() {
            @Override
            public void ok(Object value) {
                mRequestBusy.remove(key);
                Haptics.confirm(button);
                if (accept) SocialUi.toast(activity, activity.getString(R.string.projects_requests_accepted, name));
                if (isAdded()) render();
            }

            @Override
            public void failed(SocialProblem problem) {
                mRequestBusy.remove(key);
                if (problem.kind == SocialProblem.Kind.JOIN_REQUEST_GONE) {
                    // Answered elsewhere, withdrawn or gone with a block: it leaves the list here too.
                    service.forgetJoinRequest(row.crew, row.request.from.id);
                    SocialUi.toastProblem(activity, problem, button);
                } else if (accept && problem.kind == SocialProblem.Kind.CREW_LIMIT) {
                    // The player who asked is in as many crews as they may be: the request keeps waiting.
                    Haptics.reject(button);
                    SocialUi.toast(activity, activity.getString(R.string.projects_requests_player_limit));
                } else {
                    SocialUi.toastProblem(activity, problem, button);
                }
                if (isAdded()) render();
            }
        };
        if (accept) {
            service.acceptJoinRequest(row.crew, row.request.from.id, new SocialService.Result<JsonObject>() {
                @Override
                public void ok(JsonObject value) {
                    done.ok(value);
                }

                @Override
                public void failed(SocialProblem problem) {
                    done.failed(problem);
                }
            });
        } else {
            service.declineJoinRequest(row.crew, row.request.from.id, new SocialService.Result<Void>() {
                @Override
                public void ok(Void value) {
                    done.ok(null);
                }

                @Override
                public void failed(SocialProblem problem) {
                    done.failed(problem);
                }
            });
        }
    }

    /**
     * A join request's menu (FIX2: the shared one): "Kabul et" and "Reddet" first, then whatever the
     * player may do about them ("Engelle" takes the request with it, "Rapor et").
     */
    private void requestMenu(final RequestRow row, View anchor) {
        if (!isAdded()) return;
        SocialMenuSheet.person(requireActivity(), row.request.from, row.crew, SocialMenuSheet.Place.REQUEST,
                gg.projects.mobile.social.SocialMenus.Pending.JOIN_REQUEST, anchor);
    }

    // ---- plans (TEAM5) ----

    private void bindPlan(View view, final Plan plan) {
        Context context = view.getContext();
        SocialService service = SocialService.get(context);
        SocialState state = service.state();
        long now = SocialService.nowSeconds();
        long hub = state.hubNow(now);
        boolean under = plan.underWay(hub);
        TextView when = view.findViewById(R.id.projects_plan_when);
        when.setText(PlanViews.when(context, plan.at, now));
        TextView line = view.findViewById(R.id.projects_plan_line);
        String by = PlanViews.by(context, state, plan);
        line.setText(under ? by + " · " + getString(R.string.projects_plans_started_line) : by);
        line.setTextColor(ContextCompat.getColor(context, under ? R.color.projects_jade_400 : R.color.projects_ash_400));
        ((ImageView) view.findViewById(R.id.projects_plan_icon)).setImageTintList(
                ContextCompat.getColorStateList(context, under ? R.color.projects_jade_400 : R.color.projects_gold_300));
        View top = view.findViewById(R.id.projects_plan_top);
        // FIX2: the plan's note under its time.
        View noteBox = view.findViewById(R.id.projects_plan_note_box);
        TextView note = view.findViewById(R.id.projects_plan_note);
        boolean hasNote = plan.note != null && service.gate().planNotes;
        noteBox.setVisibility(hasNote ? View.VISIBLE : View.GONE);
        note.setText(hasNote ? plan.note : null);
        top.setContentDescription(when.getText() + ", " + line.getText()
                + (hasNote ? ". " + getString(R.string.projects_plans_note_label, plan.note) : ""));
        View more = view.findViewById(R.id.projects_plan_more);
        boolean menu = !planMenuRows(plan).isEmpty();
        more.setVisibility(menu ? View.VISIBLE : View.GONE);
        more.setOnClickListener(v -> planMenu(plan, v));
        if (menu) {
            SocialMenuSheet.attach(top, PlanViews.when(context, plan.at, now), v -> {
                planMenu(plan, v);
                return true;
            });
        } else {
            SocialMenuSheet.detach(top);
        }
        String mine = state.myPlanAnswer(plan.id);
        boolean busy = mPlanBusy.contains(plan.id);
        bindPlanAnswer(view.findViewById(R.id.projects_plan_yes), plan, Plan.YES, mine, under, busy);
        bindPlanAnswer(view.findViewById(R.id.projects_plan_maybe), plan, Plan.MAYBE, mine, under, busy);
        bindPlanAnswer(view.findViewById(R.id.projects_plan_no), plan, Plan.NO, mine, under, busy);
        // FIX2: who answered opens in place ("Kimler geliyor"), each person with the shared menu.
        AnswerList.bind(view, plan.id, plan.answers, gg.projects.mobile.social.AnswerPeople.PLAN_ORDER,
                new int[]{R.string.projects_plans_said_yes, R.string.projects_plans_said_maybe, R.string.projects_plans_said_no},
                PlanViews.tally(context, state, plan), mAnswers);
    }

    private void bindPlanAnswer(final Button button, final Plan plan, final String answer, @Nullable String current, boolean under,
                                boolean busy) {
        Context context = button.getContext();
        boolean chosen = answer.equals(current);
        if (chosen) {
            button.setBackground(ChamferDrawable.primary(context));
            button.setTextColor(ContextCompat.getColor(context, R.color.projects_on_gold));
        } else {
            button.setBackground(ChamferDrawable.outlined(context));
            // While it runs "Geliyorum" takes the player to Projects: gold until it is chosen.
            button.setTextColor(ContextCompat.getColor(context, under && Plan.YES.equals(answer) ? R.color.projects_gold_300
                    : R.color.projects_bone_100));
        }
        button.setSelected(chosen);
        button.setEnabled(!busy);
        button.setOnClickListener(v -> answerPlan(button, plan, answer));
    }

    /**
     * An answer shows at once (the socket confirms it); "Geliyorum" to a plan that runs now asks
     * whether to open the game, as a call on Projects does.
     */
    private void answerPlan(final Button button, final Plan plan, final String answer) {
        final FragmentActivity activity = requireActivity();
        final SocialService service = SocialService.get(activity);
        if (!service.plansLive()) {
            SocialUi.toast(activity, getString(R.string.projects_social_error_unavailable));
            Haptics.reject(button);
            return;
        }
        if (answer.equals(service.state().myPlanAnswer(plan.id))) {
            if (Plan.YES.equals(answer) && plan.underWay(service.state().hubNow(SocialService.nowSeconds()))) ConnectFlow.projects(activity);
            return;
        }
        Haptics.confirm(button);
        service.answerPlan(plan.id, answer, new SocialService.Result<Void>() {
            @Override
            public void ok(Void value) {
                if (!isAdded() || !Plan.YES.equals(answer)) return;
                // FIX2: a plan under way leads to Projects ("Bağlan").
                if (plan.underWay(service.state().hubNow(SocialService.nowSeconds()))) ConnectFlow.projects(activity);
            }

            @Override
            public void failed(SocialProblem problem) {
                if (problem.kind == SocialProblem.Kind.RATE_LIMITED) {
                    Haptics.reject(button);
                    SocialUi.toast(activity, activity.getString(R.string.projects_plans_answers_limit));
                } else if (problem.kind == SocialProblem.Kind.PLAN_GONE) {
                    SocialUi.toast(activity, activity.getString(R.string.projects_social_error_plan_gone));
                } else {
                    SocialUi.toastProblem(activity, problem, button);
                }
            }
        });
    }

    /** FIX2: what the plan's menu offers the player ({@link gg.projects.mobile.social.SocialMenus#plan}). */
    private List<gg.projects.mobile.social.SocialMenus.Row<gg.projects.mobile.social.SocialMenus.PlanAction>> planMenuRows(Plan plan) {
        SocialService service = SocialService.get(requireContext());
        SocialState state = service.state();
        String me = state.myId();
        gg.projects.mobile.social.SocialMenus.PlanInput in = new gg.projects.mobile.social.SocialMenus.PlanInput();
        in.notes = service.gate().planNotes;
        in.maker = me != null && plan.by.equals(me);
        in.upcoming = plan.upcoming(state.hubNow(SocialService.nowSeconds()));
        in.hasNote = plan.note != null;
        Crew crew = state.crew(plan.crew);
        Member mine = crew == null || me == null ? null : crew.team.member(me);
        in.crewMod = mine != null && (mine.founder || mine.moderator) && state.mayClearNote(plan, SocialService.nowSeconds());
        in.reports = service.gate().reports;
        in.mayCancel = state.mayCancel(plan);
        return gg.projects.mobile.social.SocialMenus.plan(in);
    }

    /**
     * The plan's menu ("⋮", or holding its card): "Not ekle" / "Notu düzenle" (its maker, before it
     * starts), "Notu kaldır" (its maker, the leader, moderators), "Rapor et" (someone else's plan: its
     * maker, with the plan as context) and "Planı sil", asked once: it goes for everyone.
     */
    private void planMenu(final Plan plan, @Nullable View anchor) {
        final FragmentActivity activity = requireActivity();
        final SocialService service = SocialService.get(activity);
        List<gg.projects.mobile.social.SocialMenus.Row<gg.projects.mobile.social.SocialMenus.PlanAction>> rows = planMenuRows(plan);
        if (rows.isEmpty()) return;
        Haptics.perform(anchor, gg.projects.mobile.ui.HapticMap.Kind.LONG_PRESS);
        View sheet = LayoutInflater.from(activity).inflate(R.layout.dialog_projects_actions, null, false);
        TextView quote = sheet.findViewById(R.id.projects_actions_quote);
        String when = getString(R.string.projects_report_plan, PlanViews.when(activity, plan.at, SocialService.nowSeconds()));
        quote.setText(plan.note == null ? when : when + "\n" + plan.note);
        quote.setVisibility(View.VISIBLE);
        LinearLayout actions = sheet.findViewById(R.id.projects_actions_list);
        final AlertDialog dialog = new AlertDialog.Builder(activity).setView(sheet).create();
        for (gg.projects.mobile.social.SocialMenus.Row<gg.projects.mobile.social.SocialMenus.PlanAction> row : rows) {
            if (row == null) {
                actions.addView(CrewViews.divider(activity));
                continue;
            }
            switch (row.action) {
                case ADD_NOTE:
                case EDIT_NOTE:
                    actions.addView(CrewViews.actionRow(activity, R.drawable.projects_ic_pencil, getString(row.action
                                    == gg.projects.mobile.social.SocialMenus.PlanAction.ADD_NOTE ? R.string.projects_plans_note_add
                                    : R.string.projects_plans_note_edit), R.color.projects_bone_100, v -> {
                                dialog.dismiss();
                                PlanSheet.note(activity, plan, stored -> {
                                    if (isAdded()) render();
                                });
                            }));
                    break;
                case REMOVE_NOTE:
                    actions.addView(CrewViews.actionRow(activity, R.drawable.projects_ic_x, getString(R.string.projects_plans_note_remove),
                            R.color.projects_bone_100, v -> {
                                dialog.dismiss();
                                PlanSheet.clearNote(activity, plan, stored -> {
                                    if (isAdded()) render();
                                });
                            }));
                    break;
                case REPORT: {
                    final Profile maker = service.state().profile(plan.by);
                    if (maker == null) break;
                    actions.addView(CrewViews.actionRow(activity, R.drawable.projects_ic_flag, getString(R.string.projects_report),
                            R.color.projects_ash_300, v -> {
                                dialog.dismiss();
                                ReportDialog.plan(activity, plan, maker);
                            }));
                    break;
                }
                case CANCEL:
                default:
                    actions.addView(cancelRow(activity, service, dialog, plan));
                    break;
            }
        }
        if (dialog.getWindow() != null) dialog.getWindow().setWindowAnimations(R.style.ProjectsMenuSheetAnimation);
        dialog.show();
    }

    /** "Planı sil", asked once: it goes for everyone. */
    private View cancelRow(final FragmentActivity activity, final SocialService service, final AlertDialog dialog, final Plan plan) {
        return CrewViews.actionRow(activity, R.drawable.projects_ic_trash, getString(R.string.projects_plans_cancel),
                R.color.projects_flare_400, v -> {
                    dialog.dismiss();
                    SocialUi.confirmDanger(activity, getString(R.string.projects_plans_cancel), getString(R.string.projects_plans_cancel_ask),
                            getString(R.string.projects_plans_cancel), () -> {
                                if (!mPlanBusy.add(plan.id)) return;
                                render();
                                service.cancelPlan(plan.id, new SocialService.Result<Void>() {
                                    @Override
                                    public void ok(Void value) {
                                        mPlanBusy.remove(plan.id);
                                        SocialUi.toast(activity, activity.getString(R.string.projects_plans_cancelled));
                                        if (isAdded()) render();
                                    }

                                    @Override
                                    public void failed(SocialProblem problem) {
                                        mPlanBusy.remove(plan.id);
                                        if (problem.kind == SocialProblem.Kind.PLAN_GONE) {
                                            SocialUi.toast(activity, activity.getString(R.string.projects_social_error_plan_gone));
                                        } else {
                                            SocialUi.toastProblem(activity, problem, null);
                                        }
                                        if (isAdded()) render();
                                    }
                                });
                            });
                });
    }

    /** "Plan yap": the reason in place for a new Discord account and at the crew's 3 plans, else the sheet. */
    private void makePlan(Action action, Crew crew) {
        final FragmentActivity activity = requireActivity();
        SocialService service = SocialService.get(activity);
        SocialModel.Me me = service.state().me();
        long now = SocialService.nowSeconds();
        View feedback = mList;
        if (me != null && me.limited != null && me.limited.active(now) && me.limited.has(SocialModel.Limited.CREW_PLANS)) {
            Haptics.reject(feedback);
            SocialUi.toast(activity, PlanSheet.limitedText(activity, me.limited.until));
            return;
        }
        if (service.state().upcomingPlans(now, crew.id()) >= Plan.PER_CREW) {
            Haptics.reject(feedback);
            SocialUi.toast(activity, getString(R.string.projects_plans_full));
            return;
        }
        if (!service.plansLive()) {
            Haptics.reject(feedback);
            SocialUi.toast(activity, getString(R.string.projects_social_error_unavailable));
            return;
        }
        PlanSheet.show(activity, crew.id(), plan -> {
            if (isAdded()) render();
        });
    }

    // ---- the invite link and the join setting (TEAM5) ----

    /** The invite link: the 12 symbols; copy and share carry the whole address; "more" for whoever manages it. */
    private void bindLink(View view, final String link) {
        final FragmentActivity activity = requireActivity();
        final SocialService service = SocialService.get(activity);
        final String url = SocialCodes.inviteLinkUrl(service.serviceBase(), link);
        ((TextView) view.findViewById(R.id.projects_crew_code_label)).setText(R.string.projects_link_title);
        ((TextView) view.findViewById(R.id.projects_crew_code_text)).setText(link);
        View copy = view.findViewById(R.id.projects_crew_code_copy);
        copy.setContentDescription(getString(R.string.projects_link_copy));
        copy.setOnClickListener(v -> {
            SocialUi.copy(activity, getString(R.string.projects_link_title), url);
            Haptics.confirm(v);
            SocialUi.toast(activity, getString(R.string.projects_link_copied));
        });
        View share = view.findViewById(R.id.projects_crew_code_share);
        share.setContentDescription(getString(R.string.projects_link_share));
        share.setOnClickListener(v -> SocialUi.share(activity, getString(R.string.projects_link_share_text, url)));
        Crew crew = shown();
        boolean admit = crew != null && service.gate().requests && service.state().mayAdmit(crew.id());
        View more = view.findViewById(R.id.projects_crew_code_more);
        more.setVisibility(admit ? View.VISIBLE : View.GONE);
        more.setOnClickListener(v -> linkMenu());
    }

    /** "Yeni bağlantı" and "Bağlantıyı kapat", each asked once (the old link stops at once). */
    private void linkMenu() {
        final FragmentActivity activity = requireActivity();
        final SocialService service = SocialService.get(activity);
        final Crew crew = shown();
        if (crew == null) return;
        View sheet = LayoutInflater.from(activity).inflate(R.layout.dialog_projects_actions, null, false);
        TextView quote = sheet.findViewById(R.id.projects_actions_quote);
        quote.setText(R.string.projects_link_title);
        quote.setVisibility(View.VISIBLE);
        LinearLayout actions = sheet.findViewById(R.id.projects_actions_list);
        final AlertDialog dialog = new AlertDialog.Builder(activity).setView(sheet).create();
        actions.addView(CrewViews.actionRow(activity, R.drawable.projects_ic_arrows_clockwise, getString(R.string.projects_link_renew),
                R.color.projects_bone_100, v -> {
                    dialog.dismiss();
                    SocialUi.confirm(activity, getString(R.string.projects_link_renew), getString(R.string.projects_link_renew_ask),
                            getString(R.string.projects_link_renew), () -> makeLink(crew.id(), true));
                }));
        actions.addView(CrewViews.actionRow(activity, R.drawable.projects_ic_x, getString(R.string.projects_link_off),
                R.color.projects_flare_400, v -> {
                    dialog.dismiss();
                    SocialUi.confirmDanger(activity, getString(R.string.projects_link_off), getString(R.string.projects_link_off_ask),
                            getString(R.string.projects_link_off), () -> service.deleteInviteLink(crew.id(), new SocialService.Result<Void>() {
                                @Override
                                public void ok(Void value) {
                                    SocialUi.toast(activity, activity.getString(R.string.projects_link_closed));
                                }

                                @Override
                                public void failed(SocialProblem problem) {
                                    SocialUi.toastProblem(activity, problem, null);
                                }
                            }));
                }));
        dialog.show();
    }

    /** "Davet bağlantısı oluştur" / "Yeni bağlantı": a new Discord account is told why not, in place. */
    private void makeLink(final String crewId, final boolean renew) {
        final FragmentActivity activity = requireActivity();
        SocialService service = SocialService.get(activity);
        SocialModel.Me me = service.state().me();
        long now = SocialService.nowSeconds();
        if (me != null && me.limited != null && me.limited.active(now) && me.limited.has(SocialModel.Limited.CREW_LINK)) {
            int days = Math.max(1, SocialModel.Limited.daysLeft(me.limited.until, now));
            Haptics.reject(mList);
            SocialUi.toast(activity, getResources().getQuantityString(R.plurals.projects_limited_no_link, days, days));
            return;
        }
        service.createInviteLink(crewId, new SocialService.Result<String>() {
            @Override
            public void ok(String link) {
                Haptics.confirm(mList);
                SocialUi.toast(activity, activity.getString(renew ? R.string.projects_link_renewed : R.string.projects_link_ready));
            }

            @Override
            public void failed(SocialProblem problem) {
                if (problem.kind == SocialProblem.Kind.ACCOUNT_LIMITED) {
                    long at = SocialService.nowSeconds();
                    int days = Math.max(1, SocialModel.Limited.daysLeft(problem.until == null ? at + 86_400 : problem.until, at));
                    Haptics.reject(mList);
                    SocialUi.toast(activity, activity.getResources().getQuantityString(R.plurals.projects_limited_no_link, days, days));
                } else {
                    SocialUi.toastProblem(activity, problem, mList);
                }
            }
        });
    }

    /** "Yeni üyeleri onayla" (the leader's): newcomers with the code or the link ask first. */
    private void bindApproval(FrameLayout frame, final Team team) {
        frame.removeAllViews();
        Context context = frame.getContext();
        float dp = context.getResources().getDisplayMetrics().density;
        SwitchCompat toggle = new SwitchCompat(context);
        toggle.setText(R.string.projects_join_approve);
        toggle.setTextSize(15);
        toggle.setTextColor(ContextCompat.getColor(context, R.color.projects_bone_100));
        try {
            toggle.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(context, R.font.figtree_semibold));
        } catch (RuntimeException ignored) {
            // The theme's font.
        }
        toggle.setMinHeight(Math.round(52 * dp));
        toggle.setPaddingRelative(Math.round(10 * dp), 0, Math.round(10 * dp), 0);
        toggle.setChecked(team.approval());
        toggle.setEnabled(!mApprovalBusy);
        toggle.setOnCheckedChangeListener((button, on) -> {
            Haptics.toggle(button, on);
            mApprovalBusy = true;
            button.setEnabled(false);
            final FragmentActivity activity = requireActivity();
            SocialService.get(activity).setJoinApproval(team.id, on, new SocialService.Result<JsonObject>() {
                @Override
                public void ok(JsonObject value) {
                    mApprovalBusy = false;
                    if (isAdded()) render();
                }

                @Override
                public void failed(SocialProblem problem) {
                    mApprovalBusy = false;
                    SocialUi.toastProblem(activity, problem, null);
                    if (isAdded()) {
                        // The switch shows the crew's setting again.
                        mAdapter.notifyItemChanged(indexOfRow("approve"));
                        render();
                    }
                }
            });
        });
        frame.addView(toggle, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /**
     * TEAM6 (the leader's): "Görünürlük" (hiding takes every worn tag off: asked first while someone wears
     * it) and, while the tag shows, "Etiketten istek". The line under each says what it means now.
     */
    private void bindTags(FrameLayout frame, final Team team) {
        frame.removeAllViews();
        Context context = frame.getContext();
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(tagSwitch(context, R.string.projects_badge_visibility,
                team.hidden ? R.string.projects_badge_visibility_hidden_line : R.string.projects_badge_visibility_visible_line,
                !team.hidden, (button, on) -> setCrewVisible(team, on, button)));
        if (SocialService.get(context).gate().badgeRequests && !team.hidden) {
            column.addView(tagSwitch(context, R.string.projects_badge_requests_label,
                    team.badgeRequests ? R.string.projects_badge_requests_on_line : R.string.projects_badge_requests_off_line,
                    team.badgeRequests, (button, on) -> saveTags(team, false, on, button)));
        }
        frame.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private View tagSwitch(Context context, int label, int line, boolean on,
                           android.widget.CompoundButton.OnCheckedChangeListener flip) {
        float dp = context.getResources().getDisplayMetrics().density;
        LinearLayout cell = new LinearLayout(context);
        cell.setOrientation(LinearLayout.VERTICAL);
        SwitchCompat toggle = new SwitchCompat(context);
        toggle.setText(label);
        toggle.setTextSize(15);
        toggle.setTextColor(ContextCompat.getColor(context, R.color.projects_bone_100));
        try {
            toggle.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(context, R.font.figtree_semibold));
        } catch (RuntimeException ignored) {
            // The theme's font.
        }
        toggle.setMinHeight(Math.round(48 * dp));
        toggle.setPaddingRelative(Math.round(10 * dp), 0, Math.round(10 * dp), 0);
        toggle.setChecked(on);
        toggle.setEnabled(!mTagsBusy);
        toggle.setOnCheckedChangeListener((button, now) -> {
            Haptics.toggle(button, now);
            flip.onCheckedChanged(button, now);
        });
        TextView sub = new TextView(context);
        sub.setText(line);
        sub.setTextSize(13);
        sub.setTextColor(ContextCompat.getColor(context, R.color.projects_ash_400));
        sub.setPaddingRelative(Math.round(10 * dp), 0, Math.round(72 * dp), Math.round(8 * dp));
        cell.addView(toggle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        cell.addView(sub, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return cell;
    }

    /** Hiding while members wear the tag asks first ("Etiketi takan 3 üyenin etiketi çıkarılacak."). */
    private void setCrewVisible(final Team team, boolean visible, final View button) {
        int wearers = 0;
        for (Member m : team.members) if (m.profile.badge != null && team.id.equals(m.profile.badge.crew)) wearers++;
        if (visible || wearers == 0) {
            saveTags(team, true, visible, button);
            return;
        }
        // The switch shows the crew's setting until "Gizle".
        int row = indexOfRow("tags");
        if (row >= 0) mAdapter.notifyItemChanged(row);
        SocialUi.confirm(requireActivity(), null, getResources().getQuantityString(R.plurals.projects_badge_visibility_ask, wearers, wearers),
                getString(R.string.projects_badge_visibility_hide), () -> saveTags(team, true, false, null));
    }

    private void saveTags(Team team, boolean visibility, boolean on, @Nullable View button) {
        if (mTagsBusy || !isAdded()) return;
        mTagsBusy = true;
        if (button != null) button.setEnabled(false);
        final FragmentActivity activity = requireActivity();
        SocialService.Result<JsonObject> done = new SocialService.Result<JsonObject>() {
            @Override
            public void ok(JsonObject value) {
                mTagsBusy = false;
                if (isAdded()) render();
            }

            @Override
            public void failed(SocialProblem problem) {
                mTagsBusy = false;
                if (problem.kind == SocialProblem.Kind.RATE_LIMITED) {
                    SocialUi.toast(activity, activity.getString(R.string.projects_badge_settings_today));
                } else {
                    SocialUi.toastProblem(activity, problem, null);
                }
                if (isAdded()) {
                    // The switches show the crew's settings again.
                    int row = indexOfRow("tags");
                    if (row >= 0) mAdapter.notifyItemChanged(row);
                    render();
                }
            }
        };
        SocialService service = SocialService.get(activity);
        if (visibility) service.setCrewHidden(team.id, !on, done);
        else service.setCrewBadgeRequests(team.id, on, done);
    }

    private int indexOfRow(String id) {
        for (int i = 0; i < mAdapter.mRows.size(); i++) if (mAdapter.mRows.get(i).id.equals(id)) return i;
        return -1;
    }

    private void bindMember(View view, final Member member) {
        Context context = view.getContext();
        SocialService service = SocialService.get(context);
        SocialState state = service.state();
        boolean me = member.profile.id.equals(state.myId());
        AvatarView avatar = view.findViewById(R.id.projects_person_avatar);
        avatar.setVisibility(View.VISIBLE);
        view.findViewById(R.id.projects_person_mark).setVisibility(View.GONE);
        avatar.setProfile(member.profile);
        avatar.setSurfaceColor(ContextCompat.getColor(context, R.color.projects_obsidian_950));
        TextView name = view.findViewById(R.id.projects_person_name);
        name.setText(me ? member.profile.name() + " (" + getString(R.string.projects_crew_you) + ")" : member.profile.name());
        // TEAM6: the tag the member wears (any of their crews') before their name.
        SocialModel.Badge worn = BadgeRules.shown(service.gate(), member.profile.badge);
        ((CrewTagChip) view.findViewById(R.id.projects_person_tag)).setBadgeOf(member.profile.id, worn, CrewTagChip.Size.MD);
        CrewTagChip.labelName(name, worn);
        TextView badge = view.findViewById(R.id.projects_person_badge);
        badge.setBackgroundResource(R.drawable.projects_social_badge);
        badge.setTextColor(ContextCompat.getColor(context, R.color.projects_gold_300));
        boolean roles = service.gate().roles || member.founder;
        if (member.founder || (member.moderator && roles)) {
            badge.setText(member.founder ? R.string.projects_crew_leader : R.string.projects_crew_moderator);
            badge.setVisibility(View.VISIBLE);
        } else {
            badge.setVisibility(View.GONE);
        }
        view.findViewById(R.id.projects_person_star).setVisibility(View.GONE);
        view.findViewById(R.id.projects_person_secondary).setVisibility(View.GONE);
        view.setContentDescription(null);
        TextView line = view.findViewById(R.id.projects_person_line);
        Button join = view.findViewById(R.id.projects_person_action);
        join.setEnabled(true);
        if (me) {
            SocialModel.Me self = state.me();
            String status = self == null ? SocialModel.Status.ONLINE : self.status;
            avatar.setDot(SocialUi.statusDot(status));
            avatar.setDimmed(false);
            line.setText(SocialUi.statusLabel(status));
            line.setTextColor(ContextCompat.getColor(context, R.color.projects_ash_400));
            join.setVisibility(View.GONE);
            view.setOnClickListener(v -> StatusSheet.show(requireActivity()));
            // No menu on the player's own row.
            SocialMenuSheet.detach(view);
            return;
        }
        SocialModel.Presence presence = state.presence(member.profile.id);
        avatar.setDot(SocialUi.dot(presence));
        avatar.setDimmed(!presence.online());
        line.setText(SocialUi.presenceLine(context, presence));
        line.setTextColor(ContextCompat.getColor(context, presence.onProjects() ? R.color.projects_jade_400 : R.color.projects_ash_400));
        join.setVisibility(presence.onProjects() ? View.VISIBLE : View.GONE);
        join.setText(R.string.projects_social_join);
        join.setOnClickListener(v -> ConnectFlow.projects(requireActivity()));
        // The crew shown when tapped: a row the same in two crews is not bound again on a switch.
        view.setOnClickListener(v -> {
            Crew crew = shown();
            if (crew != null) CrewSheets.member(requireActivity(), crew.id(), member.profile.id);
        });
        // FIX2: holding a member opens the shared menu, in this crew's context.
        SocialMenuSheet.attach(view, member.profile.name(), v -> {
            Crew crew = shown();
            if (crew != null && isAdded()) SocialMenuSheet.person(requireActivity(), member.profile.id, crew.id(), SocialMenuSheet.Place.CREW, v);
            return true;
        });
    }

    // ---- notifications (the bell) ----

    /**
     * With several crews the bell mutes the crew shown (its own "Sessize al"), and its last line every
     * crew together ("Hepsini sessize al" / "Bütün ekipler sessizde · …"); without, M19's sheet.
     */
    private void openNotifications() {
        final FragmentActivity activity = requireActivity();
        final SocialService service = SocialService.get(activity);
        SocialGate gate = service.gate();
        final Crew crew = shown();
        if (!gate.multi || crew == null) {
            CrewSheets.notifications(activity);
            return;
        }
        long hub = service.state().hubNow(SocialService.nowSeconds());
        String state = crew.mute.mutedAt(hub) ? CrewSheets.mutedText(activity, crew.mute, hub) : null;
        TeamNotifications all = service.state().teamNotifications();
        final AlertDialog[] dialog = new AlertDialog[1];
        View bottom;
        if (all.mutedAt(hub)) {
            String text = all.muteUntil == null ? getString(R.string.projects_crews_all_muted)
                    : getString(R.string.projects_crews_all_muted_until, CrewViews.moment(activity, all.muteUntil, hub));
            bottom = MuteSheet.line(activity, text + " · " + getString(R.string.projects_crew_unmute), v -> {
                if (dialog[0] != null) dialog[0].dismiss();
                service.unmute(CrewSheets.result(activity, v));
            });
        } else {
            bottom = MuteSheet.line(activity, getString(R.string.projects_crews_mute_all), v -> {
                if (dialog[0] != null) dialog[0].dismiss();
                CrewSheets.muteAll(activity);
            });
        }
        dialog[0] = MuteSheet.show(activity, getString(R.string.projects_crew_notifications), crew.team.name, state, hub, null, bottom,
                new MuteSheet.Choice() {
                    @Override
                    public void preset(gg.projects.mobile.social.MutePresets.Preset preset, View row) {
                        service.muteCrew(crew.id(), preset, CrewSheets.result(activity, row));
                    }

                    @Override
                    public void unmute(View button) {
                        service.unmuteCrew(crew.id(), CrewSheets.result(activity, button));
                    }
                });
    }

    // ---- "+" (no crew, or another one) ----

    /** Crew invites with an answer on its way (their buttons stay off until it comes). */
    private final java.util.Set<String> mInviteBusy = new java.util.HashSet<>();
    /** What the invite rows show now: they are built again only when it changes (not for every presence). */
    private String mInvitesShown = "";

    private void renderNone(SocialService service) {
        Context context = requireContext();
        SocialState state = service.state();
        boolean multi = service.gate().multi;
        // At the limit: no code and no "Ekip kur", one sentence (invites stay: they can be declined there).
        boolean full = multi && state.atCrewLimit() && state.crewLimit() > 0;
        mLimitText.setVisibility(full ? View.VISIBLE : View.GONE);
        if (full) mLimitText.setText(getResources().getQuantityString(R.plurals.projects_crews_limit, state.crewLimit(), state.crewLimit()));
        mJoinGroup.setVisibility(full ? View.GONE : View.VISIBLE);
        // M21: a crew the player asked to join that came meanwhile (accepted): its result card goes.
        if (mLookupTeam != null && state.crew(mLookupTeam) != null) {
            mLookupTeam = null;
            mJoinResult.setVisibility(View.GONE);
        }
        List<TeamInvite> invites = state.teamInvites();
        long now = SocialService.nowSeconds();
        List<MyRequest> mine = new ArrayList<>();
        if (service.gate().requests) {
            // The crew of the result card is not listed twice (the card says it waits).
            boolean card = mJoinResult.getVisibility() == View.VISIBLE;
            for (MyRequest r : state.myRequests(now)) {
                mAwaiting.add(r.team.id);
                if (!card || !r.team.id.equals(mLookupTeam)) mine.add(r);
            }
        }
        StringBuilder key = new StringBuilder();
        for (TeamInvite invite : invites) {
            key.append(invite.team.id).append(mInviteBusy.contains(invite.team.id) ? "*" : "")
                    .append(invite.team.name).append(invite.from.name()).append(';');
        }
        for (MyRequest r : mine) {
            key.append("r:").append(r.team.id).append(mWithdrawBusy.contains(r.team.id) ? "*" : "").append(r.team.name)
                    .append(r.team.emblem).append(r.team.color).append(r.team.frame).append(PlanViews.ago(context, r.sentAt, now)).append(';');
        }
        boolean any = !invites.isEmpty() || !mine.isEmpty();
        if (key.toString().equals(mInvitesShown) && mInvites.getChildCount() > 0 == any) return;
        mInvitesShown = key.toString();
        mInvites.removeAllViews();
        if (!invites.isEmpty()) {
            mInvites.addView(noneHeader(context, getString(R.string.projects_crew_invites)));
            for (final TeamInvite invite : invites) mInvites.addView(inviteRow(invite));
        }
        if (!mine.isEmpty()) {
            mInvites.addView(noneHeader(context, getString(R.string.projects_requests_mine)));
            for (MyRequest r : mine) mInvites.addView(myRequestRow(r));
        }
    }

    private TextView noneHeader(Context context, String text) {
        TextView header = (TextView) LayoutInflater.from(context).inflate(R.layout.item_projects_social_header, mInvites, false);
        header.setText(text);
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) header.getLayoutParams();
        lp.setMarginStart(0);
        lp.topMargin = Math.round(4 * getResources().getDisplayMetrics().density);
        return header;
    }

    /** M21: one of the player's own join requests: the crew's mark, "İsteğin bekliyor · 2 sa önce", "Geri çek". */
    private View myRequestRow(final MyRequest request) {
        Context context = requireContext();
        View row = LayoutInflater.from(context).inflate(R.layout.item_projects_social_person, mInvites, false);
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) row.getLayoutParams();
        lp.setMarginStart(0);
        lp.setMarginEnd(0);
        row.findViewById(R.id.projects_person_avatar).setVisibility(View.GONE);
        CrewMarkView mark = row.findViewById(R.id.projects_person_mark);
        mark.setVisibility(View.VISIBLE);
        mark.setLook(request.team.emblem, request.team.color, request.team.frame);
        ((TextView) row.findViewById(R.id.projects_person_name)).setText(request.team.name);
        ((TextView) row.findViewById(R.id.projects_person_line)).setText(getString(R.string.projects_requests_pending) + " · "
                + PlanViews.ago(context, request.sentAt, SocialService.nowSeconds()));
        final Button withdraw = row.findViewById(R.id.projects_person_action);
        withdraw.setVisibility(View.VISIBLE);
        withdraw.setText(R.string.projects_requests_withdraw);
        withdraw.setBackground(ChamferDrawable.outlined(context));
        Press.attach(withdraw, Press.Style.BUTTON);
        final String teamId = request.team.id;
        withdraw.setEnabled(!mWithdrawBusy.contains(teamId));
        final FragmentActivity activity = requireActivity();
        withdraw.setOnClickListener(v -> {
            if (!mWithdrawBusy.add(teamId)) return;
            withdraw.setEnabled(false);
            SocialService.get(activity).withdrawJoinRequest(teamId, new SocialService.Result<Void>() {
                @Override
                public void ok(Void value) {
                    mWithdrawBusy.remove(teamId);
                    Haptics.confirm(withdraw);
                    SocialUi.toast(activity, activity.getString(R.string.projects_requests_withdrawn));
                    if (isAdded()) render();
                }

                @Override
                public void failed(SocialProblem problem) {
                    mWithdrawBusy.remove(teamId);
                    withdraw.setEnabled(true);
                    SocialUi.toastProblem(activity, problem, withdraw);
                    if (isAdded()) render();
                }
            });
        });
        return row;
    }

    private View inviteRow(final TeamInvite invite) {
        Context context = requireContext();
        View row = LayoutInflater.from(context).inflate(R.layout.item_projects_social_person, mInvites, false);
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) row.getLayoutParams();
        lp.setMarginStart(0);
        lp.setMarginEnd(0);
        row.findViewById(R.id.projects_person_avatar).setVisibility(View.GONE);
        CrewMarkView mark = row.findViewById(R.id.projects_person_mark);
        mark.setVisibility(View.VISIBLE);
        mark.setLook(invite.team.emblem, invite.team.color, invite.team.frame);
        ((TextView) row.findViewById(R.id.projects_person_name)).setText(invite.team.name);
        ((TextView) row.findViewById(R.id.projects_person_line)).setText(getString(R.string.projects_crew_invited_by, invite.from.name()));
        final Button accept = row.findViewById(R.id.projects_person_action);
        accept.setVisibility(View.VISIBLE);
        accept.setText(R.string.projects_social_accept);
        accept.setBackground(ChamferDrawable.outlined(context));
        Press.attach(accept, Press.Style.BUTTON);
        final ImageButton decline = row.findViewById(R.id.projects_person_secondary);
        decline.setVisibility(View.VISIBLE);
        decline.setContentDescription(getString(R.string.projects_social_decline));
        Press.attach(decline, Press.Style.ICON);
        final FragmentActivity activity = requireActivity();
        final SocialService service = SocialService.get(activity);
        final String teamId = invite.team.id;
        boolean busy = mInviteBusy.contains(teamId);
        accept.setEnabled(!busy);
        decline.setEnabled(!busy);
        // FIX2: holding the invite opens its menu: answering it, reporting the crew (MOD1), then who invited.
        Press.attach(row, Press.Style.ROW);
        row.setOnClickListener(v -> InviteCard.show(activity, invite));
        SocialMenuSheet.attach(row, invite.team.name, v -> {
            List<SocialMenuSheet.Own> own = new ArrayList<>();
            own.add(new SocialMenuSheet.Own(R.drawable.projects_ic_check, getString(R.string.projects_social_accept), R.color.projects_bone_100,
                    accept::performClick));
            own.add(new SocialMenuSheet.Own(R.drawable.projects_ic_x, getString(R.string.projects_social_decline), R.color.projects_bone_100,
                    decline::performClick));
            if (service.gate().reports) {
                own.add(new SocialMenuSheet.Own(R.drawable.projects_ic_flag, getString(R.string.projects_report_crew), R.color.projects_ash_300,
                        () -> ReportDialog.crew(activity, invite.team, null)));
            }
            SocialMenuSheet.people(activity, invite.team.name, own, java.util.Collections.singletonList(invite.from.id), null, v);
            return true;
        });
        accept.setOnClickListener(v -> {
            if (!mInviteBusy.add(teamId)) return;
            accept.setEnabled(false);
            decline.setEnabled(false);
            service.acceptTeamInvite(teamId, new SocialService.Result<JsonObject>() {
                @Override
                public void ok(JsonObject value) {
                    mInviteBusy.remove(teamId);
                    Haptics.confirm(accept);
                    BadgeActions.joined(activity, activity.getString(R.string.projects_crew_joined, invite.team.name), teamId);
                    Team joined = Team.read(Json.obj(value, "team"));
                    if (joined != null) mSelected = joined.id;
                    render();
                }

                @Override
                public void failed(SocialProblem problem) {
                    mInviteBusy.remove(teamId);
                    // The row looks the same after a refusal (it is not bound again): its buttons come back here.
                    accept.setEnabled(true);
                    decline.setEnabled(true);
                    SocialUi.toastProblem(activity, problem, accept);
                    render();
                }
            });
        });
        decline.setOnClickListener(v -> {
            if (!mInviteBusy.add(teamId)) return;
            accept.setEnabled(false);
            decline.setEnabled(false);
            service.declineTeamInvite(teamId, new SocialService.Result<Void>() {
                @Override
                public void ok(Void value) {
                    mInviteBusy.remove(teamId);
                    render();
                }

                @Override
                public void failed(SocialProblem problem) {
                    mInviteBusy.remove(teamId);
                    accept.setEnabled(true);
                    decline.setEnabled(true);
                    SocialUi.toastProblem(activity, problem, decline);
                    render();
                }
            });
        });
        return row;
    }

    /** "Ekip kur": for a new Discord account, the reason in place (SPAM1) instead of the form. */
    private void create(View button) {
        SocialService service = SocialService.get(requireContext());
        SocialModel.Me me = service.state().me();
        long now = SocialService.nowSeconds();
        if (me != null && me.limited != null && me.limited.active(now) && me.limited.has(SocialModel.Limited.CREW_FOUND)) {
            int days = Math.max(1, SocialModel.Limited.daysLeft(me.limited.until, now));
            mCreateReason.setText(getResources().getQuantityString(R.plurals.projects_limited_no_crew, days, days));
            mCreateReason.setVisibility(View.VISIBLE);
            gg.projects.mobile.ui.CardMotion.fadeIn(mCreateReason);
            Haptics.reject(button);
            return;
        }
        mCreateReason.setVisibility(View.GONE);
        Tools.swapFragment(requireActivity(), CrewCreateFragment.class, CrewCreateFragment.TAG, null);
    }

    private void showJoinMessage(@Nullable String text) {
        mJoinMessage.setText(text);
        mJoinMessage.setVisibility(text == null ? View.GONE : View.VISIBLE);
        mJoinCode.setActivated(text != null);
    }

    private void lookup() {
        if (mBusy || !isAdded()) return;
        SocialService service = SocialService.get(requireContext());
        String text = mJoinCode.getText().toString();
        // M21 (TEAM5): an invite link (its address, a projects://crew/ link or its 12 symbols) or a crew code.
        final String link = service.gate().link ? SocialCodes.inviteLinkIn(text) : null;
        final String code = link == null ? SocialCodes.crewCodeIn(text) : null;
        mJoinResult.setVisibility(View.GONE);
        mLookupTeam = null;
        if (code == null && link == null) {
            showJoinMessage(getString(R.string.projects_crew_not_code));
            Haptics.reject(mJoinFind);
            return;
        }
        showJoinMessage(null);
        String shownCode = link != null ? SocialCodes.inviteLinkShort(service.serviceBase(), link) : SocialCodes.CREW_PREFIX + "-" + code;
        mJoinCode.setText(shownCode);
        mJoinCode.setSelection(shownCode.length());
        InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(mJoinCode.getWindowToken(), 0);
        mBusy = true;
        mJoinFind.setEnabled(false);
        final FragmentActivity activity = requireActivity();
        SocialService.get(activity).lookupTeam(code, link, new SocialService.Result<JsonObject>() {
            @Override
            public void ok(JsonObject value) {
                mBusy = false;
                if (!isAdded()) return;
                mJoinFind.setEnabled(true);
                showLookup(code, link, value);
            }

            @Override
            public void failed(SocialProblem problem) {
                mBusy = false;
                if (!isAdded()) return;
                mJoinFind.setEnabled(true);
                Haptics.reject(mJoinFind);
                showJoinMessage(SocialUi.problemText(activity, problem));
            }
        });
    }

    /** The crew of the result card (M21: it goes when that crew comes, a request accepted). */
    @Nullable
    private String mLookupTeam;

    /** The player leads {@code crewId} (one of their crews). */
    private static boolean leads(SocialService service, String crewId) {
        Crew crew = service.state().crew(crewId);
        Member founder = crew == null ? null : crew.team.founder();
        String me = service.state().myId();
        return founder != null && me != null && founder.profile.id.equals(me);
    }

    private void showLookup(@Nullable final String code, @Nullable final String link, JsonObject answer) {
        final TeamSummary team = TeamSummary.read(Json.obj(answer, "team"));
        if (team == null) {
            showJoinMessage(getString(R.string.projects_social_error_no_crew));
            return;
        }
        View view = getView();
        if (view == null) return;
        ((CrewMarkView) view.findViewById(R.id.projects_crew_join_mark)).setLook(team.emblem, team.color, team.frame);
        ((TextView) view.findViewById(R.id.projects_crew_join_name)).setText(team.tag.isEmpty() ? team.name : team.name + " [" + team.tag + "]");
        String relation = Json.str(answer, "relation", "none");
        // TEAM5: the player's join request to it that waits (its time), else -1.
        JsonObject request = Json.obj(answer, "request");
        bindLookup(team, code, link, relation, request == null ? -1 : Json.num(request, "sentAt", 0));
        // A crew found by its code or its invite link can be reported from here (the code or the link goes with it),
        // not by its own leader (the lead's rule, as the desktop's 03f1689). The card shows one crew at a time: a
        // hold left from another lookup goes.
        SocialService service = SocialService.get(requireContext());
        if (service.gate().reports && !leads(service, team.id)) {
            mJoinResult.setOnLongClickListener(v -> {
                ReportDialog.crew(requireActivity(), team, link != null ? link : SocialCodes.CREW_PREFIX + "-" + code);
                return true;
            });
        } else {
            mJoinResult.setOnLongClickListener(null);
            mJoinResult.setLongClickable(false);
        }
        mLookupTeam = team.id;
        mJoinResult.setVisibility(View.VISIBLE);
        gg.projects.mobile.ui.CardMotion.fadeIn(mJoinResult);
    }

    /**
     * The result card's line and its one action: "Ekibe katıl", for a crew that approves newcomers
     * "Katılma isteği gönder" (TEAM5), and "Geri çek" while the player's request waits ({@code sentAt} &ge; 0).
     */
    private void bindLookup(final TeamSummary team, @Nullable final String code, @Nullable final String link, final String relation,
                            final long sentAt) {
        View view = getView();
        if (view == null) return;
        SocialService service = SocialService.get(requireContext());
        SocialState state = service.state();
        String line;
        boolean canAct = false;
        int label = R.string.projects_crew_join;
        switch (relation) {
            case "member": line = getString(R.string.projects_crew_rel_member); break;
            case "elsewhere": line = getString(R.string.projects_crew_rel_elsewhere); break;
            case "blocked": line = getString(R.string.projects_crew_rel_blocked); break;
            case "full": line = getString(R.string.projects_crew_rel_full); break;
            case "limit": {
                int limit = Math.max(1, state.crewLimit());
                line = getResources().getQuantityString(R.plurals.projects_crews_limit, limit, limit);
                break;
            }
            default: {
                String members = getResources().getQuantityString(R.plurals.projects_crew_member_count, team.memberCount, team.memberCount);
                if (sentAt >= 0) {
                    line = getString(R.string.projects_requests_pending) + " · " + PlanViews.ago(requireContext(), sentAt, SocialService.nowSeconds());
                    label = R.string.projects_requests_withdraw;
                } else if (team.approval() && service.gate().requests) {
                    line = members + " · " + getString(R.string.projects_requests_approval);
                    label = R.string.projects_requests_send;
                } else {
                    line = members + (team.motto == null ? "" : " · " + team.motto);
                }
                canAct = true;
                break;
            }
        }
        ((TextView) view.findViewById(R.id.projects_crew_join_line)).setText(line);
        mJoinSubmit.setText(label);
        mJoinSubmit.setVisibility(canAct ? View.VISIBLE : View.GONE);
        mJoinSubmit.setEnabled(true);
        mJoinSubmit.setOnClickListener(v -> {
            if (sentAt >= 0) withdrawFromCard(team, code, link, relation);
            else joinFromCard(team, code, link, relation);
        });
    }

    private void joinFromCard(final TeamSummary team, @Nullable final String code, @Nullable final String link, final String relation) {
        final FragmentActivity activity = requireActivity();
        mJoinSubmit.setEnabled(false);
        SocialService.get(activity).joinOrAsk(code, link, new SocialService.Result<JsonObject>() {
            @Override
            public void ok(JsonObject value) {
                Haptics.confirm(mJoinSubmit);
                JsonObject asked = Json.obj(value, "request");
                if (asked != null) {
                    // TEAM5: the crew approves newcomers: the request waits ("Geri çek" now).
                    SocialUi.toast(activity, activity.getString(R.string.projects_requests_sent));
                    if (isAdded()) {
                        bindLookup(team, code, link, relation, Json.num(asked, "sentAt", SocialService.nowSeconds()));
                        render();
                    }
                    return;
                }
                BadgeActions.joined(activity, activity.getString(R.string.projects_crew_joined, team.name), team.id);
                Team joined = Team.read(Json.obj(value, "team"));
                if (joined != null && isAdded()) {
                    mJoinCode.setText("");
                    mJoinResult.setVisibility(View.GONE);
                    mLookupTeam = null;
                    select(joined.id);
                }
            }

            @Override
            public void failed(SocialProblem problem) {
                mJoinSubmit.setEnabled(true);
                if (problem.kind == SocialProblem.Kind.RATE_LIMITED && team.approval()) {
                    Haptics.reject(mJoinSubmit);
                    SocialUi.toast(activity, activity.getString(R.string.projects_requests_today));
                } else {
                    SocialUi.toastProblem(activity, problem, mJoinSubmit);
                }
            }
        });
    }

    private void withdrawFromCard(final TeamSummary team, @Nullable final String code, @Nullable final String link, final String relation) {
        final FragmentActivity activity = requireActivity();
        mJoinSubmit.setEnabled(false);
        SocialService.get(activity).withdrawJoinRequest(team.id, new SocialService.Result<Void>() {
            @Override
            public void ok(Void value) {
                Haptics.confirm(mJoinSubmit);
                SocialUi.toast(activity, activity.getString(R.string.projects_requests_withdrawn));
                if (isAdded()) {
                    bindLookup(team, code, link, relation, -1);
                    render();
                }
            }

            @Override
            public void failed(SocialProblem problem) {
                mJoinSubmit.setEnabled(true);
                SocialUi.toastProblem(activity, problem, mJoinSubmit);
            }
        });
    }

    static ColorStateList tint(Context context, int color) {
        return ContextCompat.getColorStateList(context, color);
    }
}
