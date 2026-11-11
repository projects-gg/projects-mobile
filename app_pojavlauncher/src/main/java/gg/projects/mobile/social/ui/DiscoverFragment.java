package gg.projects.mobile.social.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.gson.JsonObject;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import gg.projects.mobile.social.DiscoverModel;
import gg.projects.mobile.social.Json;
import gg.projects.mobile.social.SocialModel;
import gg.projects.mobile.social.SocialProblem;
import gg.projects.mobile.social.SocialService;
import gg.projects.mobile.social.SocialState;
import gg.projects.mobile.ui.Haptics;
import gg.projects.mobile.ui.Press;

/**
 * MOB-PARITY "Ekip bul" (CREW-DISCOVER, the desktop's {@code discover-page.tsx} and {@code discover-card.tsx}): the
 * listed crews in the day's order (the same for everyone), searched and filtered on the phone: the search, the tag
 * chips and "Filtreler" (hemen katılınabilenler, bu hafta aktif olanlar, büyüklük). Each card: the crew's mark, name
 * and tag, size, the leader's text, its tags and one quiet action ("Katıl", "Katılma isteği gönder", or what is true
 * instead); its "⋮" (and a long press) holds "Ekibi rapor et". The listing is asked for only when the screen opens and
 * kept until the next wave (a quarter-hourly ETag check); nothing polls.
 */
public class DiscoverFragment extends Fragment implements SocialService.Listener {
    public static final String TAG = "ProjectsDiscoverFragment";

    private RecyclerView mList;
    private View mState;
    private TextView mStateTitle, mStateBody;
    private Button mRetry;
    private View mProgress;
    private LinearLayout mChips;
    private final Adapter mAdapter = new Adapter();
    private DiscoverModel.Filter mFilter = DiscoverModel.Filter.NONE;
    @Nullable
    private DiscoverModel.Listing mListing;
    /** Off (Studio's switch), failed, or loading the first time. */
    private boolean mOff, mFailed, mLoading;
    private final Set<String> mBusy = new HashSet<>();

    public DiscoverFragment() {
        super(R.layout.fragment_projects_discover);
    }

    public static void open(FragmentActivity activity) {
        Tools.swapFragment(activity, DiscoverFragment.class, TAG, null);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mList = view.findViewById(R.id.projects_discover_list);
        mState = view.findViewById(R.id.projects_discover_state);
        mStateTitle = view.findViewById(R.id.projects_discover_state_title);
        mStateBody = view.findViewById(R.id.projects_discover_state_body);
        mRetry = view.findViewById(R.id.projects_discover_retry);
        mProgress = view.findViewById(R.id.projects_discover_progress);
        mChips = view.findViewById(R.id.projects_discover_chips);
        mList.setLayoutManager(new LinearLayoutManager(view.getContext()));
        mList.setAdapter(mAdapter);
        Press.attach(mRetry, Press.Style.BUTTON);
        mRetry.setOnClickListener(v -> load(true));
        EditText search = view.findViewById(R.id.projects_discover_search);
        search.setContentDescription(getString(R.string.projects_discover_search_label));
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                mFilter = mFilter.withQuery(s.toString());
                show();
            }
        });
        buildChips();
    }

    @Override
    public void onResume() {
        super.onResume();
        SocialService service = SocialService.get(requireContext());
        if (!service.signedIn()) {
            SocialUi.leave(this);
            return;
        }
        service.addListener(this);
        mListing = service.discoverListing();
        load(false);
        show();
    }

    @Override
    public void onPause() {
        super.onPause();
        SocialService.get(requireContext()).removeListener(this);
    }

    /** A crew joined or asked for elsewhere: the cards' actions follow. */
    @Override
    public void onSocialChanged() {
        if (isResumed()) show();
    }

    private void load(boolean force) {
        final SocialService service = SocialService.get(requireContext());
        mLoading = true;
        mFailed = false;
        show();
        service.loadDiscover(force, new SocialService.Result<DiscoverModel.Listing>() {
            @Override
            public void ok(DiscoverModel.Listing value) {
                mLoading = false;
                mOff = false;
                mListing = value;
                if (isAdded()) show();
            }

            @Override
            public void failed(SocialProblem problem) {
                mLoading = false;
                mOff = "discover_off".equals(problem.code);
                // A kept copy stays on screen when a check fails.
                mListing = service.discoverListing();
                mFailed = mListing == null;
                if (isAdded()) show();
            }
        });
    }

    // ---- what the screen shows ----

    private void show() {
        if (getView() == null) return;
        Context context = requireContext();
        SocialService service = SocialService.get(context);
        refreshChips();
        List<DiscoverModel.Crew> crews = mListing == null ? new ArrayList<>()
                : DiscoverModel.filter(mListing.crews, mFilter, tag -> getString(tagWords(tag)));
        mProgress.setVisibility(mLoading && mListing == null ? View.VISIBLE : View.GONE);
        int title = 0, body = 0;
        boolean retry = false;
        if (mOff) {
            title = R.string.projects_discover_off;
        } else if (mFailed) {
            title = R.string.projects_discover_failed_title;
            body = R.string.projects_discover_failed_body;
            retry = true;
        } else if (mListing != null && mListing.crews.isEmpty()) {
            title = R.string.projects_discover_empty_title;
            body = R.string.projects_discover_empty_body;
        } else if (mListing != null && crews.isEmpty()) {
            title = R.string.projects_discover_no_match_title;
            body = R.string.projects_discover_no_match_body;
        }
        mState.setVisibility(title != 0 ? View.VISIBLE : View.GONE);
        if (title != 0) mStateTitle.setText(title);
        mStateBody.setVisibility(body != 0 ? View.VISIBLE : View.GONE);
        if (body != 0) mStateBody.setText(body);
        mRetry.setVisibility(retry ? View.VISIBLE : View.GONE);
        mList.setVisibility(mOff || mFailed ? View.GONE : View.VISIBLE);
        mAdapter.set(mOff || mFailed ? new ArrayList<>() : crews, service);
    }

    static int tagWords(String tag) {
        switch (tag) {
            case "survival": return R.string.projects_discover_tag_survival;
            case "skyblock": return R.string.projects_discover_tag_skyblock;
            case "smp": return R.string.projects_discover_tag_smp;
            case "hg": return R.string.projects_discover_tag_hg;
            case "pvp": return R.string.projects_discover_tag_pvp;
            case "build": return R.string.projects_discover_tag_build;
            case "events": return R.string.projects_discover_tag_events;
            case "chat": return R.string.projects_discover_tag_chat;
            case "voice": return R.string.projects_discover_tag_voice;
            default: return R.string.projects_discover_tag_newcomers;
        }
    }

    // ---- chips: "Filtreler" and the tags ----

    private void buildChips() {
        Context context = requireContext();
        mChips.removeAllViews();
        mChips.addView(chip(context, getString(R.string.projects_discover_filters), v -> filtersMenu()));
        for (final String tag : DiscoverModel.TAGS) {
            TextView chip = chip(context, getString(tagWords(tag)), v -> {
                boolean on = !mFilter.tags.contains(tag);
                Haptics.toggle(v, on);
                mFilter = mFilter.withTag(tag, on);
                show();
            });
            chip.setTag(tag);
            mChips.addView(chip);
        }
        refreshChips();
    }

    private void refreshChips() {
        if (mChips.getChildCount() == 0) return;
        TextView filters = (TextView) mChips.getChildAt(0);
        int count = mFilter.menuCount();
        filters.setText(count == 0 ? getString(R.string.projects_discover_filters) : getString(R.string.projects_discover_filters_count, count));
        mark(filters, count > 0);
        for (int i = 1; i < mChips.getChildCount(); i++) {
            TextView chip = (TextView) mChips.getChildAt(i);
            mark(chip, mFilter.tags.contains((String) chip.getTag()));
        }
    }

    private void mark(TextView chip, boolean on) {
        chip.setSelected(on);
        chip.setTextColor(ContextCompat.getColor(chip.getContext(), on ? R.color.projects_bone_100 : R.color.projects_ash_300));
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            chip.setStateDescription(getString(on ? R.string.projects_parity_role_on : R.string.projects_parity_role_off));
        }
    }

    private TextView chip(Context context, String text, View.OnClickListener click) {
        TextView chip = new TextView(context);
        chip.setText(text);
        chip.setTextSize(14);
        chip.setGravity(Gravity.CENTER);
        chip.setSingleLine(true);
        int d = dp(context, 1);
        chip.setPadding(14 * d, 0, 14 * d, 0);
        int inset = 6 * d;
        chip.setBackground(new android.graphics.drawable.InsetDrawable(SocialChips.choice(context, 7), 0, inset, 0, inset));
        chip.setClickable(true);
        chip.setFocusable(true);
        Press.attach(chip, Press.Style.BUTTON);
        chip.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 48));
        lp.setMarginEnd(6 * d);
        chip.setLayoutParams(lp);
        return chip;
    }

    /** "Filtreler": the two switches and the size, each applied at once; "Filtreleri temizle" while any is on. */
    private void filtersMenu() {
        final FragmentActivity activity = requireActivity();
        View sheet = LayoutInflater.from(activity).inflate(R.layout.dialog_projects_actions, null, false);
        LinearLayout list = sheet.findViewById(R.id.projects_actions_list);
        final AlertDialog dialog = new AlertDialog.Builder(activity).setView(sheet).create();
        list.addView(check(activity, R.string.projects_discover_filter_open, mFilter.open, () -> {
            dialog.dismiss();
            mFilter = mFilter.withOpen(!mFilter.open);
            show();
        }));
        list.addView(check(activity, R.string.projects_discover_filter_active, mFilter.active, () -> {
            dialog.dismiss();
            mFilter = mFilter.withActive(!mFilter.active);
            show();
        }));
        list.addView(CrewViews.divider(activity));
        DiscoverModel.Size[] sizes = DiscoverModel.Size.values();
        int[] words = {R.string.projects_discover_size_any, R.string.projects_discover_size_small, R.string.projects_discover_size_mid,
                R.string.projects_discover_size_big};
        for (int i = 0; i < sizes.length; i++) {
            final DiscoverModel.Size size = sizes[i];
            list.addView(check(activity, words[i], mFilter.size == size, () -> {
                dialog.dismiss();
                mFilter = mFilter.withSize(size);
                show();
            }));
        }
        if (mFilter.menuCount() > 0) {
            list.addView(CrewViews.divider(activity));
            list.addView(CrewViews.actionRow(activity, R.drawable.projects_ic_x, getString(R.string.projects_discover_filters_clear),
                    R.color.projects_ash_300, v -> {
                        dialog.dismiss();
                        mFilter = mFilter.withOpen(false).withActive(false).withSize(DiscoverModel.Size.ANY);
                        show();
                    }));
        }
        if (dialog.getWindow() != null) dialog.getWindow().setWindowAnimations(R.style.ProjectsMenuSheetAnimation);
        dialog.show();
        gg.projects.mobile.ui.FocusRing.attach(dialog);
    }

    private static View check(Context context, int text, boolean on, Runnable run) {
        TextView row = CrewViews.actionRow(context, R.drawable.projects_ic_check, context.getString(text),
                on ? R.color.projects_bone_100 : R.color.projects_ash_300, v -> run.run());
        android.graphics.drawable.Drawable mark = row.getCompoundDrawablesRelative()[0];
        if (mark != null && !on) mark.setAlpha(0);
        row.setSelected(on);
        return row;
    }

    // ---- the cards ----

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final List<DiscoverModel.Crew> mCrews = new ArrayList<>();
        private final List<String> mKeys = new ArrayList<>();

        void set(List<DiscoverModel.Crew> crews, SocialService service) {
            List<String> keys = new ArrayList<>();
            for (DiscoverModel.Crew c : crews) keys.add(c.key() + "|" + slot(c, service) + "|" + mBusy.contains(c.id));
            if (keys.equals(mKeys)) return;
            mCrews.clear();
            mCrews.addAll(crews);
            mKeys.clear();
            mKeys.addAll(keys);
            notifyDataSetChanged();
        }

        @Override
        public int getItemCount() {
            return mCrews.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new RecyclerView.ViewHolder(new FrameLayout(parent.getContext())) {
            };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            FrameLayout frame = (FrameLayout) holder.itemView;
            frame.removeAllViews();
            frame.addView(card(frame.getContext(), mCrews.get(position)));
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(frame.getContext(), 10);
            frame.setLayoutParams(lp);
        }
    }

    private static DiscoverModel.Slot slot(DiscoverModel.Crew crew, SocialService service) {
        SocialState state = service.state();
        boolean member = state.crew(crew.id) != null;
        boolean waiting = false;
        for (SocialModel.MyRequest r : state.myRequests(SocialService.nowSeconds())) waiting |= r.team.id.equals(crew.id);
        boolean multi = service.gate().multi;
        boolean canAdd = multi ? !state.atCrewLimit() : state.crews().isEmpty();
        return DiscoverModel.slot(crew, member, waiting, canAdd);
    }

    private View card(final Context context, final DiscoverModel.Crew crew) {
        SocialService service = SocialService.get(context);
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.projects_social_section_background);
        card.setPadding(dp(context, 16), dp(context, 12), dp(context, 8), dp(context, 10));
        card.setContentDescription(crew.name);

        LinearLayout head = new LinearLayout(context);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        CrewMarkView mark = new CrewMarkView(context);
        mark.setLook(crew.emblem, crew.color, crew.frame);
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        head.addView(mark, new LinearLayout.LayoutParams(dp(context, 40), dp(context, 40)));
        LinearLayout names = new LinearLayout(context);
        names.setOrientation(LinearLayout.VERTICAL);
        LinearLayout title = new LinearLayout(context);
        title.setOrientation(LinearLayout.HORIZONTAL);
        title.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(context, crew.name, 15, R.color.projects_bone_100, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        title.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (!crew.tag.isEmpty()) {
            TextView tag = text(context, crew.tag, 12, R.color.projects_bone_100, true);
            tag.setPadding(dp(context, 5), 0, dp(context, 5), 0);
            CrewViews.tintTag(tag, crew.color);
            LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tagLp.setMarginStart(dp(context, 8));
            title.addView(tag, tagLp);
        }
        names.addView(title);
        // CREW-CAP: the cap beside the count only once the crew is nearly full ("26/28 üye").
        String size = gg.projects.mobile.social.CrewRules.nearlyFull(crew.members, crew.maxMembers)
                ? getResources().getQuantityString(crew.active ? R.plurals.projects_discover_members_of_active
                        : R.plurals.projects_discover_members_of, crew.members, crew.members, crew.maxMembers)
                : getResources().getQuantityString(crew.active ? R.plurals.projects_discover_members_active
                        : R.plurals.projects_discover_members, crew.members, crew.members);
        names.addView(text(context, size, 13, R.color.projects_ash_400, false));
        LinearLayout.LayoutParams namesLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        namesLp.setMarginStart(dp(context, 12));
        head.addView(names, namesLp);
        ImageButton more = new ImageButton(context);
        more.setImageResource(R.drawable.projects_ic_dots);
        more.setImageTintList(ContextCompat.getColorStateList(context, R.color.projects_ash_300));
        more.setBackgroundResource(R.drawable.projects_icon_button_ripple);
        more.setContentDescription(getString(R.string.projects_discover_menu, crew.name));
        Press.attach(more, Press.Style.ICON);
        more.setOnClickListener(v -> menu(crew));
        head.addView(more, new LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)));
        card.addView(head);

        TextView words = text(context, crew.text, 14, R.color.projects_bone_100, false);
        LinearLayout.LayoutParams wordsLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wordsLp.topMargin = dp(context, 8);
        wordsLp.setMarginEnd(dp(context, 8));
        card.addView(words, wordsLp);

        LinearLayout foot = new LinearLayout(context);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        WrapLayout tags = new WrapLayout(context);
        tags.setGap(dp(context, 6));
        for (String tag : crew.tags) {
            TextView t = text(context, getString(tagWords(tag)), 12, R.color.projects_ash_300, true);
            t.setPadding(dp(context, 8), dp(context, 3), dp(context, 8), dp(context, 3));
            t.setBackground(SocialChips.quiet(context, 5));
            tags.addView(t);
        }
        foot.addView(tags, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        foot.addView(action(context, crew, slot(crew, service)));
        LinearLayout.LayoutParams footLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        footLp.topMargin = dp(context, 10);
        card.addView(foot, footLp);

        card.setOnLongClickListener(v -> {
            Haptics.perform(v, gg.projects.mobile.ui.HapticMap.Kind.LONG_PRESS);
            menu(crew);
            return true;
        });
        return card;
    }

    /** The card's one action: a quiet button, or what is true instead (never gold: a page of cards has none). */
    private View action(final Context context, final DiscoverModel.Crew crew, DiscoverModel.Slot slot) {
        switch (slot) {
            case JOIN:
            case ASK: {
                final Button button = new Button(context, null, android.R.attr.borderlessButtonStyle);
                button.setText(slot == DiscoverModel.Slot.JOIN ? R.string.projects_discover_join : R.string.projects_discover_ask);
                button.setAllCaps(false);
                button.setTextSize(14);
                button.setTextColor(ContextCompat.getColor(context, R.color.projects_bone_100));
                button.setBackground(gg.projects.mobile.ui.ChamferDrawable.outlined(context));
                button.setMinHeight(dp(context, 40));
                button.setMinimumHeight(dp(context, 40));
                boolean busy = mBusy.contains(crew.id);
                button.setEnabled(!busy);
                button.setAlpha(busy ? 0.6f : 1f);
                Press.attach(button, Press.Style.BUTTON);
                button.setOnClickListener(v -> join(crew, button));
                return button;
            }
            case MEMBER: {
                final Button go = new Button(context, null, android.R.attr.borderlessButtonStyle);
                go.setText(R.string.projects_discover_go_to);
                go.setAllCaps(false);
                go.setTextSize(14);
                go.setTextColor(ContextCompat.getColor(context, R.color.projects_bone_100));
                go.setContentDescription(getString(R.string.projects_discover_member) + ". " + getString(R.string.projects_discover_go_to));
                go.setMinHeight(dp(context, 40));
                go.setMinimumHeight(dp(context, 40));
                go.setBackgroundResource(R.drawable.projects_row_ripple);
                Press.attach(go, Press.Style.BUTTON);
                go.setOnClickListener(v -> openCrew(crew.id));
                return go;
            }
            default: {
                int words = slot == DiscoverModel.Slot.WAITING ? R.string.projects_discover_waiting
                        : slot == DiscoverModel.Slot.FULL ? R.string.projects_discover_full : R.string.projects_discover_limit;
                TextView line = text(context, getString(words), 13,
                        slot == DiscoverModel.Slot.WAITING ? R.color.projects_bone_100 : R.color.projects_ash_300, false);
                line.setPadding(dp(context, 8), 0, dp(context, 8), 0);
                return line;
            }
        }
    }

    private void join(final DiscoverModel.Crew crew, final View button) {
        if (mBusy.contains(crew.id)) return;
        mBusy.add(crew.id);
        show();
        final FragmentActivity activity = requireActivity();
        SocialService.get(activity).joinFromDiscover(crew.id, new SocialService.Result<JsonObject>() {
            @Override
            public void ok(JsonObject value) {
                mBusy.remove(crew.id);
                Haptics.confirm(button);
                if (Json.obj(value, "request") != null) {
                    SocialUi.toast(activity, activity.getString(R.string.projects_requests_sent));
                } else {
                    SocialUi.toastAction(activity, activity.getString(R.string.projects_crew_joined, crew.name),
                            activity.getString(R.string.projects_discover_go_to), () -> openCrew(crew.id));
                }
                if (isAdded()) show();
            }

            @Override
            public void failed(SocialProblem problem) {
                mBusy.remove(crew.id);
                if ("discover_gone".equals(problem.code)) {
                    Haptics.reject(button);
                    SocialUi.toast(activity, activity.getString(R.string.projects_discover_gone));
                    mListing = SocialService.get(activity).discoverListing();
                } else {
                    SocialUi.toastProblem(activity, problem, button);
                }
                if (isAdded()) show();
            }
        });
    }

    private void openCrew(String crewId) {
        FragmentActivity activity = getActivity();
        if (activity == null || activity.getSupportFragmentManager().isStateSaved()) return;
        CrewFragment.selectNext(crewId);
        Tools.swapFragment(activity, CrewFragment.class, CrewFragment.TAG, null);
    }

    /** The card's menu: "Ekibi rapor et". */
    private void menu(final DiscoverModel.Crew crew) {
        final FragmentActivity activity = requireActivity();
        View sheet = LayoutInflater.from(activity).inflate(R.layout.dialog_projects_actions, null, false);
        LinearLayout list = sheet.findViewById(R.id.projects_actions_list);
        TextView quote = sheet.findViewById(R.id.projects_actions_quote);
        quote.setText(crew.name);
        quote.setVisibility(View.VISIBLE);
        final AlertDialog dialog = new AlertDialog.Builder(activity).setView(sheet).create();
        list.addView(CrewViews.actionRow(activity, R.drawable.projects_ic_flag, getString(R.string.projects_discover_report),
                R.color.projects_ash_300, v -> {
                    dialog.dismiss();
                    ReportDialog.crewFromDiscover(activity, crew.summary());
                }));
        if (dialog.getWindow() != null) dialog.getWindow().setWindowAnimations(R.style.ProjectsMenuSheetAnimation);
        dialog.show();
        gg.projects.mobile.ui.FocusRing.attach(dialog);
    }

    private static TextView text(Context context, String value, int sp, int color, boolean semibold) {
        TextView t = new TextView(context);
        t.setText(value);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(ContextCompat.getColor(context, color));
        if (semibold) {
            try {
                t.setTypeface(ResourcesCompat.getFont(context, R.font.figtree_semibold));
            } catch (RuntimeException ignored) {
                t.setTypeface(Typeface.DEFAULT_BOLD);
            }
        }
        return t;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
