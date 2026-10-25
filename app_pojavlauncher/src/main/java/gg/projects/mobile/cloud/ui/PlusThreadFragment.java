package gg.projects.mobile.cloud.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.R;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import gg.projects.mobile.cloud.CloudModel;
import gg.projects.mobile.cloud.CloudProblem;
import gg.projects.mobile.cloud.CloudService;
import gg.projects.mobile.cloud.CloudWords;
import gg.projects.mobile.ui.Haptics;
import gg.projects.mobile.ui.Press;

/**
 * One conversation of "Mesajlar": the player's lines on the left, the assistant's and the owner's on the right (the
 * assistant's marked as such). The owner may answer a player who wrote in the last 24 hours; the reply goes in game
 * as their own words, and the assistant stays quiet in this conversation for a while ("Şimdi devret" hands it back).
 * A line the assistant held back as unsafe stays folded until "Göster".
 */
public final class PlusThreadFragment extends CloudScreen {
    public static final String TAG = "ProjectsPlusThreadFragment";
    public static final String ARG_NAME = "gg.projects.mobile.cloud.NAME";
    private static final long REFRESH_MS = 10_000;

    private RecyclerView mList;
    private final Lines mAdapter = new Lines();
    private LinearLayout mStrip;
    private EditText mInput;
    private ImageButton mSend;
    private TextView mNote;
    private View mComposer;
    @Nullable
    private CloudModel.InboxPage mPage;
    private boolean mFailed;
    private boolean mSending;
    @Nullable
    private String mError;
    private final Set<Integer> mUnfolded = new HashSet<>();
    private String mStripShown = "";
    private final Runnable mRefresh = new Runnable() {
        @Override
        public void run() {
            load();
            View view = getView();
            if (view != null) view.postDelayed(this, REFRESH_MS);
        }
    };

    public PlusThreadFragment() {
        super(R.layout.fragment_projects_cloud_stream);
    }

    private String name() {
        String n = getArguments() == null ? null : getArguments().getString(ARG_NAME);
        return n == null ? "" : n;
    }

    @Override
    protected void created(View view) {
        mHeader.setTitle(name());
        mStrip = view.findViewById(R.id.projects_cloud_stream_top);
        mList = view.findViewById(R.id.projects_cloud_stream_list);
        mInput = view.findViewById(R.id.projects_cloud_stream_input);
        mSend = view.findViewById(R.id.projects_cloud_stream_send);
        mNote = view.findViewById(R.id.projects_cloud_stream_note);
        mComposer = view.findViewById(R.id.projects_cloud_stream_composer);
        LinearLayoutManager layout = new LinearLayoutManager(view.getContext());
        layout.setStackFromEnd(true);
        mList.setLayoutManager(layout);
        mList.setItemAnimator(null);
        mList.setAdapter(mAdapter);
        mInput.setHint(getString(R.string.projects_plus_inbox_reply));
        mInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        mInput.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(200)});
        mSend.setContentDescription(getString(R.string.projects_plus_inbox_send));
        Press.attach(mSend, Press.Style.ICON);
        mSend.setOnClickListener(v -> reply());
        mInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                reply();
                return true;
            }
            return false;
        });
        mInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                mError = null;
                composer();
            }
        });
    }

    @Override
    protected void started() {
        load();
    }

    @Override
    public void onResume() {
        super.onResume();
        View view = getView();
        if (view != null) view.postDelayed(mRefresh, REFRESH_MS);
    }

    @Override
    public void onPause() {
        super.onPause();
        View view = getView();
        if (view != null) view.removeCallbacks(mRefresh);
    }

    private void load() {
        final CloudModel.Account a = account();
        if (a == null) return;
        mService.thread(a.id, name(), new CloudService.Result<CloudModel.InboxPage>() {
            @Override
            public void ok(CloudModel.InboxPage page) {
                boolean grew = mPage == null || page.lines.size() != mPage.lines.size();
                mPage = page;
                mFailed = false;
                if (!alive()) return;
                mAdapter.set(page.lines);
                if (grew) mList.scrollToPosition(Math.max(0, mAdapter.getItemCount() - 1));
                if (!page.lines.isEmpty()) mService.markRead(a.id, name(), page.lines.get(page.lines.size() - 1).at);
                render();
            }

            @Override
            public void failed(CloudProblem problem) {
                mFailed = mPage == null;
                if (alive()) render();
            }
        });
    }

    @Override
    protected void render() {
        CloudModel.Account a = account();
        if (a != null) mHeader.setCaption(a.name);
        strip();
        composer();
    }

    /** The assistant's quiet after the owner wrote, with handing it back. */
    private void strip() {
        long now = mService.nowSeconds();
        CloudModel.InboxPage page = mPage;
        Long until = page == null ? null : page.handoffUntil;
        boolean quiet = until != null && until > now;
        String key = mFailed + "|" + (quiet ? until : 0);
        if (key.equals(mStripShown)) return;
        mStripShown = key;
        mStrip.removeAllViews();
        Context c = ctx();
        if (mFailed) {
            mStrip.addView(CloudUi.notice(c, R.drawable.projects_ic_warning_circle, R.color.projects_gold_300,
                    getString(R.string.projects_cloud_error_host_offline)));
            return;
        }
        if (!quiet) return;
        LinearLayout notice = CloudUi.notice(c, R.drawable.projects_ic_moon, R.color.projects_gold_300,
                getString(R.string.projects_plus_inbox_quiet, CloudWords.until(c, until, now)));
        mStrip.addView(notice);
        mStrip.addView(CloudUi.textButton(c, getString(R.string.projects_plus_inbox_hand_back), v -> handBack(v)),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void composer() {
        CloudModel.InboxPage page = mPage;
        boolean can = page != null && page.canReply;
        mComposer.setVisibility(page == null ? View.GONE : View.VISIBLE);
        mInput.setEnabled(can && !mSending);
        String typed = mInput.getText().toString().trim();
        mSend.setEnabled(can && !mSending && !typed.isEmpty());
        mSend.setAlpha(mSend.isEnabled() ? 1f : 0.5f);
        String note;
        int color = R.color.projects_ash_400;
        if (page == null) note = null;
        else if (!can) note = getString(R.string.projects_plus_inbox_cannot);
        else if (mError != null) {
            note = mError;
            color = R.color.projects_flare_400;
        } else if (page.handoffUntil == null || page.handoffUntil <= mService.nowSeconds()) note = getString(R.string.projects_plus_inbox_first);
        else note = null;
        mNote.setText(note);
        mNote.setTextColor(CloudUi.color(ctx(), color));
        mNote.setVisibility(note == null ? View.GONE : View.VISIBLE);
    }

    private void reply() {
        final CloudModel.Account a = account();
        final String text = mInput.getText().toString().trim();
        if (a == null || text.isEmpty() || mSending) return;
        if (text.contains("[AFK+]")) {
            mError = getString(R.string.projects_plus_inbox_marker);
            Haptics.reject(mSend);
            composer();
            return;
        }
        mSending = true;
        composer();
        mService.reply(a.id, name(), text, new CloudService.Result<CloudModel.InboxLine>() {
            @Override
            public void ok(CloudModel.InboxLine line) {
                mSending = false;
                if (!alive()) return;
                Haptics.confirm(mSend);
                mInput.setText("");
                mAdapter.add(line);
                mList.scrollToPosition(mAdapter.getItemCount() - 1);
                load();
            }

            @Override
            public void failed(CloudProblem problem) {
                mSending = false;
                if (!alive()) return;
                Haptics.reject(mSend);
                if (problem.is("not_contacted")) {
                    mError = getString(R.string.projects_plus_inbox_cannot);
                } else if (problem.is("text_rejected")) {
                    String reason = problem.reason();
                    mError = getString("length".equals(reason) ? R.string.projects_plus_inbox_too_long
                            : "marker".equals(reason) ? R.string.projects_plus_inbox_marker : R.string.projects_plus_inbox_rejected);
                } else {
                    mError = CloudWords.problem(ctx(), problem);
                }
                composer();
            }
        });
    }

    private void handBack(final View from) {
        CloudModel.Account a = account();
        if (a == null) return;
        mService.handBack(a.id, name(), new CloudService.Result<Void>() {
            @Override
            public void ok(Void value) {
                if (!alive()) return;
                Haptics.confirm(from);
                load();
            }

            @Override
            public void failed(CloudProblem problem) {
                if (alive()) problem(problem, from);
            }
        });
    }

    // ---- the lines ----

    private final class Lines extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final List<CloudModel.InboxLine> mLines = new ArrayList<>();

        void set(List<CloudModel.InboxLine> lines) {
            mLines.clear();
            mLines.addAll(lines);
            notifyDataSetChanged();
        }

        void add(CloudModel.InboxLine line) {
            mLines.add(line);
            notifyItemInserted(mLines.size() - 1);
        }

        @Override
        public int getItemCount() {
            return mLines.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context c = parent.getContext();
            LinearLayout box = new LinearLayout(c);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(0, CloudUi.dp(c, 3), 0, CloudUi.dp(c, 3));
            TextView who = CloudUi.text(c, "", 11.5f, R.color.projects_ash_400, true);
            who.setPadding(CloudUi.dp(c, 6), 0, CloudUi.dp(c, 6), CloudUi.dp(c, 2));
            box.addView(who, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView bubble = CloudUi.text(c, "", 15, R.color.projects_bone_100, false);
            bubble.setLineSpacing(0, 1.12f);
            bubble.setPadding(CloudUi.dp(c, 12), CloudUi.dp(c, 8), CloudUi.dp(c, 12), CloudUi.dp(c, 8));
            bubble.setMaxWidth(Math.round(c.getResources().getDisplayMetrics().widthPixels * 0.74f));
            box.addView(bubble, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView time = CloudUi.time(c, 11.5f, R.color.projects_ash_400);
            time.setPadding(CloudUi.dp(c, 6), CloudUi.dp(c, 2), CloudUi.dp(c, 6), 0);
            box.addView(time, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            box.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerView.ViewHolder(box) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, final int position) {
            final CloudModel.InboxLine line = mLines.get(position);
            Context c = holder.itemView.getContext();
            LinearLayout box = (LinearLayout) holder.itemView;
            TextView who = (TextView) box.getChildAt(0);
            TextView bubble = (TextView) box.getChildAt(1);
            TextView time = (TextView) box.getChildAt(2);
            boolean right = !"them".equals(line.from);
            box.setGravity(right ? Gravity.END : Gravity.START);
            boolean first = position == 0 || !mLines.get(position - 1).from.equals(line.from);
            String label = "bot".equals(line.from) ? c.getString(R.string.projects_plus_inbox_assistant)
                    : "you".equals(line.from) ? c.getString(R.string.projects_plus_inbox_you) : null;
            who.setText(label);
            who.setVisibility(label != null && first ? View.VISIBLE : View.GONE);
            float r = CloudUi.dp(c, 14);
            float s = CloudUi.dp(c, 5);
            GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadii(right ? new float[]{r, r, first ? r : s, first ? r : s, s, s, r, r}
                    : new float[]{first ? r : s, first ? r : s, r, r, r, r, s, s});
            int fill = "you".equals(line.from) ? R.color.projects_chat_mine : "bot".equals(line.from) ? R.color.projects_obsidian_700 : R.color.projects_chat_theirs;
            shape.setColor(CloudUi.color(c, fill));
            if ("you".equals(line.from)) shape.setStroke(Math.max(1, CloudUi.dp(c, 1)), CloudUi.color(c, R.color.projects_chat_mine_edge));
            bubble.setBackground(shape);
            if (line.hidden && !mUnfolded.contains(position)) {
                String hidden = c.getString(R.string.projects_plus_inbox_hidden);
                String show = c.getString(R.string.projects_plus_inbox_show);
                android.text.SpannableString words = new android.text.SpannableString(hidden + "  " + show);
                words.setSpan(new android.text.style.ForegroundColorSpan(CloudUi.color(c, R.color.projects_gold_300)),
                        hidden.length() + 2, words.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                bubble.setText(words);
                bubble.setTextColor(CloudUi.color(c, R.color.projects_ash_400));
                bubble.setOnClickListener(v -> {
                    mUnfolded.add(position);
                    notifyItemChanged(position);
                });
                bubble.setClickable(true);
            } else {
                bubble.setText(line.text);
                bubble.setTextColor(CloudUi.color(c, R.color.projects_bone_100));
                bubble.setOnClickListener(null);
                bubble.setClickable(false);
            }
            boolean last = position == mLines.size() - 1 || !mLines.get(position + 1).from.equals(line.from);
            time.setText(CloudWords.clock(line.at));
            time.setVisibility(last ? View.VISIBLE : View.GONE);
            ((RecyclerView.LayoutParams) box.getLayoutParams()).topMargin = first ? CloudUi.dp(c, 8) : 0;
        }
    }
}
