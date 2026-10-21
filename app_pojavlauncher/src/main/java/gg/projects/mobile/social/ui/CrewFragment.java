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
import gg.projects.mobile.social.ChatBook;
import gg.projects.mobile.social.ChatConv;
import gg.projects.mobile.social.Json;
import gg.projects.mobile.social.SocialCodes;
import gg.projects.mobile.social.SocialGate;
import gg.projects.mobile.social.SocialModel;
import gg.projects.mobile.social.SocialModel.Call;
import gg.projects.mobile.social.SocialModel.Crew;
import gg.projects.mobile.social.SocialModel.Member;
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
        SocialState state = service.state();
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
            if (ticking) mList.postDelayed(mTick, 30_000);
        } else {
            renderNone(service);
        }
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
                    + "|" + c.mute.mutedAt(hub) + "|" + id.equals(mSelected) + "|" + id.equals(state.crewIds().get(0)));
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

    /** "Ana ekip yap", "Sola taşı", "Sağa taşı" for the crew shown (a tap on its mark, or TalkBack's actions). */
    private void orderMenu(final String crewId) {
        final FragmentActivity activity = requireActivity();
        final SocialService service = SocialService.get(activity);
        final List<String> order = service.state().crewIds();
        final int at = order.indexOf(crewId);
        if (at < 0 || order.size() < 2) return;
        Crew crew = service.state().crew(crewId);
        View sheet = LayoutInflater.from(activity).inflate(R.layout.dialog_projects_actions, null, false);
        TextView quote = sheet.findViewById(R.id.projects_actions_quote);
        quote.setText(crew == null ? "" : crew.team.name);
        quote.setVisibility(View.VISIBLE);
        LinearLayout actions = sheet.findViewById(R.id.projects_actions_list);
        final AlertDialog dialog = new AlertDialog.Builder(activity).setView(sheet).create();
        if (at > 0) {
            actions.addView(CrewViews.actionRow(activity, R.drawable.projects_ic_crown_fill, getString(R.string.projects_crews_make_main),
                    R.color.projects_bone_100, v -> {
                        dialog.dismiss();
                        move(crewId, 0);
                    }));
            actions.addView(CrewViews.actionRow(activity, R.drawable.projects_ic_caret_left, getString(R.string.projects_crews_move_left),
                    R.color.projects_bone_100, v -> {
                        dialog.dismiss();
                        move(crewId, at - 1);
                    }));
        }
        if (at < order.size() - 1) {
            actions.addView(CrewViews.actionRow(activity, R.drawable.projects_ic_caret_right, getString(R.string.projects_crews_move_right),
                    R.color.projects_bone_100, v -> {
                        dialog.dismiss();
                        move(crewId, at + 1);
                    }));
        }
        dialog.show();
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
        tag.setTextColor(ContextCompat.getColor(context, selected ? R.color.projects_gold_300 : R.color.projects_ash_300));
        if (ADD.equals(id)) {
            mark.setVisibility(View.GONE);
            plus.setVisibility(View.VISIBLE);
            muted.setVisibility(View.GONE);
            tag.setText("+");
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
        dot.setVisibility(call || unread ? View.VISIBLE : View.GONE);
        boolean isMuted = crew.mute.mutedAt(state.hubNow(now));
        muted.setVisibility(isMuted ? View.VISIBLE : View.GONE);
        boolean main = position == 0;
        StringBuilder description = new StringBuilder(crew.team.name);
        if (main) description.append(", ").append(getString(R.string.projects_crews_main));
        if (call) description.append(", ").append(getString(R.string.projects_crew_call_start));
        if (isMuted) description.append(", ").append(getString(R.string.projects_crew_muted));
        view.setContentDescription(description);
        view.setOnClickListener(v -> {
            if (id.equals(mSelected)) orderMenu(id);
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
        rows.add(new Row(T_CARD, "card", team.name + team.tag + team.motto + team.emblem + team.color + team.frame + team.members.size()
                + "|" + state.limits().teamMembers + "|" + main, crew));
        long hub = state.hubNow(now);
        if (team.textLocked(hub)) {
            String text = getString(R.string.projects_mod_crew_reset) + " " + getString(R.string.projects_mod_crew_locked_until,
                    SocialUi.moment(requireContext(), team.textLockedUntil));
            rows.add(new Row(T_NOTICE, "locked", text, text));
        }
        if (service.chat().available()) {
            ChatBook book = service.chat().book();
            String conv = ChatConv.crew(team.id);
            ChatBook.Summary summary = null;
            if (book != null) for (ChatBook.Summary s : book.summaries()) if (s.conv.equals(conv)) summary = s;
            String last = summary == null || summary.last == null ? "" : summary.last.key() + summary.last.text();
            rows.add(new Row(T_CHAT, "chat", last + "|" + (summary == null ? 0 : summary.unread), summary));
        }
        if (gate.calls) {
            for (Call call : state.calls(now, team.id)) {
                long left = state.secondsLeft(call, now);
                rows.add(new Row(T_CALL, "call-" + call.id, call.kind + "|" + call.place() + call.serverHost + "|" + call.answers
                        + "|" + ((left + 59) / 60) + "|" + call.crews, call));
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
                        + p.state + p.dnd + p.where + p.host + state.isFriend(m.profile.id) + mine, m));
            }
        }
        if (!team.code.isEmpty()) rows.add(new Row(T_CODE, "code", team.code, team.code));
        if (me != null && team.allows(me, team.permissions.invite)) {
            rows.add(new Row(T_ACTION, "invite", "invite", new Action("invite", R.drawable.projects_ic_user_plus,
                    R.string.projects_crew_invite_title, R.color.projects_bone_100)));
        }
        if (gate.reports) {
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
                case T_CARD:
                    view = inflater.inflate(R.layout.item_projects_crew_card, parent, false);
                    break;
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
                    view = inflater.inflate(R.layout.item_projects_crew_code, parent, false);
                    Press.attach(view.findViewById(R.id.projects_crew_code_copy), Press.Style.ICON);
                    Press.attach(view.findViewById(R.id.projects_crew_code_share), Press.Style.ICON);
                    break;
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
                case T_MEMBER:
                default:
                    view = inflater.inflate(R.layout.item_projects_social_person, parent, false);
                    Press.attach(view, Press.Style.ROW);
                    Button primary = view.findViewById(R.id.projects_person_action);
                    primary.setBackground(ChamferDrawable.outlined(context));
                    Press.attach(primary, Press.Style.BUTTON);
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
            String text = summary.last.text().replace('\n', ' ');
            Profile from = summary.last.mine(me) ? null : service.state().profile(summary.last.from(me));
            line.setText(summary.last.mine(me) ? getString(R.string.projects_chat_you, text) : (from == null ? "" : from.name() + ": ") + text);
            line.setTextColor(ContextCompat.getColor(context, unread > 0 ? R.color.projects_bone_100 : R.color.projects_ash_400));
        }
        view.setOnClickListener(v -> ChatFragment.open(CrewFragment.this, ChatConv.crew(crew.id())));
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
        TextView tally = view.findViewById(R.id.projects_call_tally);
        String text = tally(context, state, call);
        tally.setText(text);
        tally.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
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
                if (call.onProjects()) SocialUi.joinProjects(activity);
                else if (call.otherHost() != null) SocialUi.otherServer(activity, call.otherHost());
            }

            @Override
            public void failed(SocialProblem problem) {
                SocialUi.toastProblem(activity, problem, button);
            }
        });
    }

    private void bindCode(View view, final String code) {
        final String shownCode = SocialCodes.crewCode(code);
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
            case "leave":
                SocialUi.confirm(activity, getString(R.string.projects_crew_leave), getString(R.string.projects_crew_leave_ask),
                        getString(R.string.projects_crew_leave), () -> service.leaveTeam(team.id, FriendCard.done(activity, getString(R.string.projects_crew_left))));
                break;
            case "close":
                SocialUi.confirm(activity, getString(R.string.projects_crew_close), getString(R.string.projects_crew_close_ask),
                        getString(R.string.projects_crew_close), () -> service.disbandTeam(team.id, FriendCard.done(activity, getString(R.string.projects_crew_disbanded_done))));
                break;
            case "disband":
                SocialUi.confirm(activity, getString(R.string.projects_crew_disband), getString(R.string.projects_crew_disband_ask),
                        getString(R.string.projects_crew_disband), () -> service.disbandTeam(team.id, FriendCard.done(activity, getString(R.string.projects_crew_disbanded_done))));
                break;
            default:
                break;
        }
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
        TextView line = view.findViewById(R.id.projects_person_line);
        Button join = view.findViewById(R.id.projects_person_action);
        if (me) {
            SocialModel.Me self = state.me();
            String status = self == null ? SocialModel.Status.ONLINE : self.status;
            avatar.setDot(SocialUi.statusDot(status));
            avatar.setDimmed(false);
            line.setText(SocialUi.statusLabel(status));
            line.setTextColor(ContextCompat.getColor(context, R.color.projects_ash_400));
            join.setVisibility(View.GONE);
            view.setOnClickListener(v -> StatusSheet.show(requireActivity()));
            return;
        }
        SocialModel.Presence presence = state.presence(member.profile.id);
        avatar.setDot(SocialUi.dot(presence));
        avatar.setDimmed(!presence.online());
        line.setText(SocialUi.presenceText(context, presence));
        line.setTextColor(ContextCompat.getColor(context, presence.onProjects() ? R.color.projects_jade_400 : R.color.projects_ash_400));
        join.setVisibility(presence.onProjects() ? View.VISIBLE : View.GONE);
        join.setText(R.string.projects_social_join);
        join.setOnClickListener(v -> SocialUi.joinProjects(requireActivity()));
        final Crew crew = shown();
        view.setOnClickListener(v -> {
            if (crew != null) CrewSheets.member(requireActivity(), crew.id(), member.profile.id);
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
        List<TeamInvite> invites = state.teamInvites();
        StringBuilder key = new StringBuilder();
        for (TeamInvite invite : invites) {
            key.append(invite.team.id).append(mInviteBusy.contains(invite.team.id) ? "*" : "")
                    .append(invite.team.name).append(invite.from.name()).append(';');
        }
        if (key.toString().equals(mInvitesShown) && mInvites.getChildCount() > 0 == !invites.isEmpty()) return;
        mInvitesShown = key.toString();
        mInvites.removeAllViews();
        if (!invites.isEmpty()) {
            TextView header = (TextView) LayoutInflater.from(context).inflate(R.layout.item_projects_social_header, mInvites, false);
            header.setText(getString(R.string.projects_crew_invites));
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) header.getLayoutParams();
            lp.setMarginStart(0);
            lp.topMargin = Math.round(4 * getResources().getDisplayMetrics().density);
            mInvites.addView(header);
            for (final TeamInvite invite : invites) mInvites.addView(inviteRow(invite));
        }
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
        // Holding the invite reports the crew (MOD1: an invite that still shows may be reported).
        if (service.gate().reports) {
            Press.attach(row, Press.Style.ROW);
            row.setOnLongClickListener(v -> {
                ReportDialog.crew(activity, invite.team, null);
                return true;
            });
            row.setOnClickListener(v -> InviteCard.show(activity, invite));
        }
        accept.setOnClickListener(v -> {
            if (!mInviteBusy.add(teamId)) return;
            accept.setEnabled(false);
            decline.setEnabled(false);
            service.acceptTeamInvite(teamId, new SocialService.Result<JsonObject>() {
                @Override
                public void ok(JsonObject value) {
                    mInviteBusy.remove(teamId);
                    Haptics.confirm(accept);
                    SocialUi.toast(activity, activity.getString(R.string.projects_crew_joined, invite.team.name));
                    Team joined = Team.read(Json.obj(value, "team"));
                    if (joined != null) mSelected = joined.id;
                    render();
                }

                @Override
                public void failed(SocialProblem problem) {
                    mInviteBusy.remove(teamId);
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
        final String code = SocialCodes.crewCodeIn(mJoinCode.getText().toString());
        mJoinResult.setVisibility(View.GONE);
        if (code == null) {
            showJoinMessage(getString(R.string.projects_crew_not_code));
            Haptics.reject(mJoinFind);
            return;
        }
        showJoinMessage(null);
        String shownCode = SocialCodes.CREW_PREFIX + "-" + code;
        mJoinCode.setText(shownCode);
        mJoinCode.setSelection(shownCode.length());
        InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(mJoinCode.getWindowToken(), 0);
        mBusy = true;
        mJoinFind.setEnabled(false);
        final FragmentActivity activity = requireActivity();
        SocialService.get(activity).lookupTeam(code, new SocialService.Result<JsonObject>() {
            @Override
            public void ok(JsonObject value) {
                mBusy = false;
                if (!isAdded()) return;
                mJoinFind.setEnabled(true);
                showLookup(code, value);
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

    private void showLookup(final String code, JsonObject answer) {
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
        String line;
        boolean canJoin = false;
        SocialState state = SocialService.get(requireContext()).state();
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
            default:
                line = getResources().getQuantityString(R.plurals.projects_crew_member_count, team.memberCount, team.memberCount)
                        + (team.motto == null ? "" : " · " + team.motto);
                canJoin = true;
                break;
        }
        ((TextView) view.findViewById(R.id.projects_crew_join_line)).setText(line);
        mJoinSubmit.setVisibility(canJoin ? View.VISIBLE : View.GONE);
        mJoinSubmit.setEnabled(true);
        final String name = team.name;
        mJoinSubmit.setOnClickListener(v -> {
            final FragmentActivity activity = requireActivity();
            mJoinSubmit.setEnabled(false);
            SocialService.get(activity).joinTeam(code, new SocialService.Result<JsonObject>() {
                @Override
                public void ok(JsonObject value) {
                    Haptics.confirm(mJoinSubmit);
                    SocialUi.toast(activity, activity.getString(R.string.projects_crew_joined, name));
                    Team joined = Team.read(Json.obj(value, "team"));
                    if (joined != null && isAdded()) {
                        mJoinCode.setText("");
                        mJoinResult.setVisibility(View.GONE);
                        select(joined.id);
                    }
                }

                @Override
                public void failed(SocialProblem problem) {
                    mJoinSubmit.setEnabled(true);
                    SocialUi.toastProblem(activity, problem, mJoinSubmit);
                }
            });
        });
        // A crew found by its code can be reported from here (its code goes with the report).
        if (SocialService.get(requireContext()).gate().reports) {
            mJoinResult.setOnLongClickListener(v -> {
                ReportDialog.crew(requireActivity(), team, SocialCodes.CREW_PREFIX + "-" + code);
                return true;
            });
        }
        mJoinResult.setVisibility(View.VISIBLE);
        gg.projects.mobile.ui.CardMotion.fadeIn(mJoinResult);
    }

    static ColorStateList tint(Context context, int color) {
        return ContextCompat.getColorStateList(context, color);
    }
}
