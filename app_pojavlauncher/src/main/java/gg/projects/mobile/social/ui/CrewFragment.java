package gg.projects.mobile.social.ui;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
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
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.gson.JsonObject;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import gg.projects.mobile.mods.Motion;
import gg.projects.mobile.social.Json;
import gg.projects.mobile.social.SocialCodes;
import gg.projects.mobile.social.SocialGate;
import gg.projects.mobile.social.SocialModel;
import gg.projects.mobile.social.SocialModel.Call;
import gg.projects.mobile.social.SocialModel.Member;
import gg.projects.mobile.social.SocialModel.Profile;
import gg.projects.mobile.social.SocialModel.Team;
import gg.projects.mobile.social.SocialModel.TeamInvite;
import gg.projects.mobile.social.SocialModel.TeamNotifications;
import gg.projects.mobile.social.SocialModel.TeamSummary;
import gg.projects.mobile.social.SocialProblem;
import gg.projects.mobile.social.SocialService;
import gg.projects.mobile.social.SocialState;
import gg.projects.mobile.ui.ChamferDrawable;
import gg.projects.mobile.ui.Haptics;
import gg.projects.mobile.ui.Press;
import gg.projects.mobile.ui.ScreenHeader;

/**
 * The crew page (M19; the desktop's crew page, TEAM3-L): the crew's card in its look, the call
 * ("Ekibi çağır", Geliyorum / Birazdan / Gelemem; "Geliyorum" to a crewmate on Projects joins them),
 * members by where they are with their role and "Katıl", the crew code, inviting friends, and
 * leaving. Without a crew: crew invites, joining with a code and creating one. Editing the look,
 * the permissions and moderators stay on the desktop launcher; the phone does what a player needs
 * on the go. Each part shows only while the service offers it ({@code teamFeatures}).
 */
public class CrewFragment extends Fragment implements SocialService.Listener {
    public static final String TAG = "ProjectsCrewFragment";

    private static final int T_CARD = 0;
    private static final int T_CALL = 1;
    private static final int T_CALL_START = 2;
    private static final int T_HEADER = 3;
    private static final int T_MEMBER = 4;
    private static final int T_CODE = 5;
    private static final int T_ACTION = 6;
    private static final int T_TEXT = 7;

    private final Adapter mAdapter = new Adapter();
    private RecyclerView mList;
    private View mNone;
    private LinearLayout mInvites;
    private EditText mJoinCode;
    private TextView mJoinMessage;
    private View mJoinResult;
    private Button mJoinFind;
    private Button mJoinSubmit;
    private ImageButton mBell;
    private boolean mBusy;
    private final Runnable mTick = this::render;

    public CrewFragment() {
        super(R.layout.fragment_projects_crew);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        ScreenHeader header = view.findViewById(R.id.projects_crew_header);
        mBell = header.addAction(R.drawable.projects_ic_bell, R.string.projects_crew_notifications, v -> CrewSheets.notifications(requireActivity()));
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
        mNone = view.findViewById(R.id.projects_crew_none);
        mInvites = view.findViewById(R.id.projects_crew_invites);
        mJoinCode = view.findViewById(R.id.projects_crew_join_code);
        mJoinMessage = view.findViewById(R.id.projects_crew_join_message);
        mJoinResult = view.findViewById(R.id.projects_crew_join_result);
        mJoinFind = view.findViewById(R.id.projects_crew_join_find);
        mJoinSubmit = view.findViewById(R.id.projects_crew_join_submit);
        mJoinSubmit.setBackground(ChamferDrawable.outlined(view.getContext()));
        Press.attach(mJoinSubmit, Press.Style.BUTTON);
        Press.attach(mJoinFind, Press.Style.BUTTON);
        View create = view.findViewById(R.id.projects_crew_create);
        Press.attach(create, Press.Style.BUTTON);
        create.setOnClickListener(v -> Tools.swapFragment(requireActivity(), CrewCreateFragment.class, CrewCreateFragment.TAG, null));
        mJoinFind.setOnClickListener(v -> lookup());
        mJoinCode.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_UP;
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                lookup();
                return true;
            }
            return false;
        });
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
        // The adapter is the fragment's and outlives this view (back stack): let go of the old list.
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

    private void render() {
        if (!isAdded() || getView() == null) return;
        SocialService service = SocialService.get(requireContext());
        SocialGate gate = service.gate();
        if (!service.enabled() || !service.signedIn() || (service.state().loaded() && !gate.teams)) {
            SocialUi.leave(this);
            return;
        }
        Team team = service.state().team();
        boolean inCrew = team != null;
        mList.setVisibility(inCrew ? View.VISIBLE : View.GONE);
        mNone.setVisibility(inCrew ? View.GONE : View.VISIBLE);
        TeamNotifications notifications = service.state().teamNotifications();
        long now = SocialService.nowSeconds();
        boolean muted = gate.mute && notifications.mutedAt(service.state().hubNow(now));
        mBell.setVisibility(gate.calls ? View.VISIBLE : View.GONE);
        mBell.setImageResource(muted || (gate.mute && !notifications.calls) ? R.drawable.projects_ic_bell_slash : R.drawable.projects_ic_bell);
        mList.removeCallbacks(mTick);
        if (inCrew) {
            mAdapter.submit(rows(service, team, now));
            // The call's minutes and its end, and a timed mute's end: once a minute while there is one.
            boolean ticking = !service.state().calls(now).isEmpty() || service.callWaitSeconds() > 0
                    || (muted && notifications.muteUntil != null);
            if (ticking) mList.postDelayed(mTick, 30_000);
        } else {
            renderNone(service);
        }
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

    private List<Row> rows(SocialService service, Team team, long now) {
        SocialState state = service.state();
        SocialGate gate = service.gate();
        String me = state.myId();
        List<Row> rows = new ArrayList<>();
        rows.add(new Row(T_CARD, "card", team.name + team.tag + team.motto + team.emblem + team.color + team.frame + team.members.size()
                + "|" + service.state().limits().teamMembers, team));
        if (gate.calls) {
            List<Call> calls = state.calls(now);
            if (calls.isEmpty()) {
                if (me != null && team.allows(me, team.permissions.call)) {
                    long wait = service.callWaitSeconds();
                    rows.add(new Row(T_CALL_START, "call-start", "start|" + ((wait + 59) / 60), null));
                }
            } else {
                for (Call call : calls) {
                    long left = state.secondsLeft(call, now);
                    rows.add(new Row(T_CALL, "call-" + call.id, call.kind + "|" + call.answers + "|" + ((left + 59) / 60), call));
                }
            }
        }
        Map<SocialState.Place, List<Member>> roster = state.roster();
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
                case T_CALL_START: {
                    view = inflater.inflate(R.layout.item_projects_crew_call_start, parent, false);
                    Button start = view.findViewById(R.id.projects_call_start);
                    start.setBackground(ChamferDrawable.outlined(context));
                    Drawable icon = ContextCompat.getDrawable(context, R.drawable.projects_ic_megaphone);
                    if (icon != null) {
                        icon = icon.mutate();
                        icon.setTint(ContextCompat.getColor(context, R.color.projects_gold_300));
                        int size = Math.round(20 * context.getResources().getDisplayMetrics().density);
                        icon.setBounds(0, 0, size, size);
                        start.setCompoundDrawablesRelative(icon, null, null, null);
                    }
                    Press.attach(start, Press.Style.BUTTON);
                    break;
                }
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
                    bindCard(view, (Team) row.data);
                    break;
                case T_CALL:
                    bindCall(view, (Call) row.data);
                    break;
                case T_CALL_START:
                    bindCallStart(view);
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

    private void bindCard(View view, Team team) {
        Context context = view.getContext();
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
        int limit = SocialService.get(context).state().limits().teamMembers;
        String members = limit > 0 ? getString(R.string.projects_crew_members, team.members.size(), limit)
                : context.getResources().getQuantityString(R.plurals.projects_crew_member_count, team.members.size(), team.members.size());
        String founded = CrewViews.day(context, team.createdOn);
        ((TextView) view.findViewById(R.id.projects_crew_card_meta)).setText(founded == null ? members
                : members + " · " + getString(R.string.projects_crew_founded, founded));
    }

    private void bindCallStart(View view) {
        final Button start = view.findViewById(R.id.projects_call_start);
        TextView wait = view.findViewById(R.id.projects_call_wait);
        final SocialService service = SocialService.get(view.getContext());
        long seconds = service.callWaitSeconds();
        boolean waiting = seconds > 0;
        start.setEnabled(!waiting);
        start.setAlpha(waiting ? 0.55f : 1f);
        int minutes = (int) ((seconds + 59) / 60);
        wait.setText(waiting ? getResources().getQuantityString(R.plurals.projects_crew_call_wait, minutes, minutes) : "");
        wait.setVisibility(waiting ? View.VISIBLE : View.GONE);
        start.setOnClickListener(v -> {
            final FragmentActivity activity = requireActivity();
            start.setEnabled(false);
            service.startCall(new SocialService.Result<Void>() {
                @Override
                public void ok(Void value) {
                    Haptics.confirm(start);
                    SocialUi.toast(activity, activity.getString(R.string.projects_crew_call_sent));
                }

                @Override
                public void failed(SocialProblem problem) {
                    start.setEnabled(true);
                    if (problem.kind == SocialProblem.Kind.RATE_LIMITED && problem.retryAfter > 0) {
                        int m = (int) ((problem.retryAfter + 59) / 60);
                        Haptics.reject(start);
                        SocialUi.toast(activity, activity.getResources().getQuantityString(R.plurals.projects_crew_call_wait, m, m));
                        service.prefs().setCalledAt(SocialService.nowSeconds() - 300 + problem.retryAfter);
                        render();
                        return;
                    }
                    SocialUi.toastProblem(activity, problem, start);
                }
            });
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
        if (mine) {
            title.setText(R.string.projects_crew_call_mine);
            line.setText(left);
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
            title.setText(getString(R.string.projects_crew_call_theirs, caller == null ? getString(R.string.projects_crew_someone) : caller.name()));
            line.setText((call.joinable() ? getString(R.string.projects_social_on_projects) : getString(R.string.projects_crew_call_ready)) + " · " + left);
            end.setVisibility(View.GONE);
            answers.setVisibility(View.VISIBLE);
            String mineAnswer = me == null ? null : call.answers.get(me);
            bindAnswer(view.findViewById(R.id.projects_call_yes), call, "yes", mineAnswer);
            bindAnswer(view.findViewById(R.id.projects_call_soon), call, "soon", mineAnswer);
            bindAnswer(view.findViewById(R.id.projects_call_no), call, "no", mineAnswer);
        }
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
        boolean primary = "yes".equals(answer) && call.joinable();
        if (chosen) {
            button.setBackground(ChamferDrawable.primary(context));
            button.setTextColor(ContextCompat.getColor(context, R.color.projects_on_gold));
        } else {
            button.setBackground(ChamferDrawable.outlined(context));
            button.setTextColor(ContextCompat.getColor(context, R.color.projects_bone_100));
        }
        // "Geliyorum" to a call from Projects also opens the game: its text is gold until it is chosen.
        if (primary && !chosen) button.setTextColor(ContextCompat.getColor(context, R.color.projects_gold_300));
        button.setSelected(chosen);
        button.setOnClickListener(v -> answer(button, call, answer));
    }

    /**
     * An answer is sent at once (shown before the socket confirms it). "Geliyorum" to a call from
     * Projects then asks whether to open the game (the desktop's rule 6).
     */
    private void answer(final Button button, final Call call, final String answer) {
        final FragmentActivity activity = requireActivity();
        final SocialService service = SocialService.get(activity);
        Haptics.confirm(button);
        service.answerCall(call.id, answer, new SocialService.Result<Void>() {
            @Override
            public void ok(Void value) {
                if ("yes".equals(answer) && call.joinable() && isAdded()) SocialUi.joinProjects(activity);
            }

            @Override
            public void failed(SocialProblem problem) {
                SocialUi.toastProblem(activity, problem, button);
            }
        });
    }

    private void bindCode(View view, final String code) {
        final String shown = SocialCodes.crewCode(code);
        ((TextView) view.findViewById(R.id.projects_crew_code_text)).setText(shown);
        view.findViewById(R.id.projects_crew_code_copy).setOnClickListener(v -> {
            SocialUi.copy(requireContext(), getString(R.string.projects_crew_code), shown);
            Haptics.confirm(v);
            SocialUi.toast(requireContext(), getString(R.string.projects_social_copied));
        });
        view.findViewById(R.id.projects_crew_code_share).setOnClickListener(v ->
                SocialUi.share(requireActivity(), getString(R.string.projects_crew_share_text, shown)));
    }

    private void bindAction(FrameLayout frame, final Action action) {
        frame.removeAllViews();
        frame.addView(CrewViews.actionRow(frame.getContext(), action.icon, getString(action.label), action.tint, v -> onAction(action)));
    }

    private void onAction(Action action) {
        final FragmentActivity activity = requireActivity();
        final SocialService service = SocialService.get(activity);
        final Team team = service.state().team();
        if (team == null) return;
        switch (action.kind) {
            case "invite":
                CrewSheets.inviteFriends(activity);
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
        view.setOnClickListener(v -> CrewSheets.member(requireActivity(), member.profile.id));
    }

    // ---- without a crew ----

    /** Crew invites with an answer on its way (their buttons stay off until it comes). */
    private final java.util.Set<String> mInviteBusy = new java.util.HashSet<>();
    /** What the invite rows show now: they are built again only when it changes (not for every presence). */
    private String mInvitesShown = "";

    private void renderNone(SocialService service) {
        Context context = requireContext();
        List<TeamInvite> invites = service.state().teamInvites();
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
        String shown = SocialCodes.CREW_PREFIX + "-" + code;
        mJoinCode.setText(shown);
        mJoinCode.setSelection(shown.length());
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
        TeamSummary team = TeamSummary.read(Json.obj(answer, "team"));
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
        switch (relation) {
            case "member": line = getString(R.string.projects_crew_rel_member); break;
            case "elsewhere": line = getString(R.string.projects_crew_rel_elsewhere); break;
            case "blocked": line = getString(R.string.projects_crew_rel_blocked); break;
            case "full": line = getString(R.string.projects_crew_rel_full); break;
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
                }

                @Override
                public void failed(SocialProblem problem) {
                    mJoinSubmit.setEnabled(true);
                    SocialUi.toastProblem(activity, problem, mJoinSubmit);
                }
            });
        });
        mJoinResult.setVisibility(View.VISIBLE);
        gg.projects.mobile.ui.CardMotion.fadeIn(mJoinResult);
    }

    static ColorStateList tint(Context context, int color) {
        return ContextCompat.getColorStateList(context, color);
    }
}
