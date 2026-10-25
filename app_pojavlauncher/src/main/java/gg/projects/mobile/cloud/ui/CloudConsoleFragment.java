package gg.projects.mobile.cloud.ui;

import android.content.Context;
import android.os.Build;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.R;

import java.util.ArrayList;
import java.util.List;

import gg.projects.mobile.cloud.CloudEdit;
import gg.projects.mobile.cloud.CloudModel;
import gg.projects.mobile.cloud.CloudProblem;
import gg.projects.mobile.cloud.CloudService;
import gg.projects.mobile.cloud.CloudStatus;
import gg.projects.mobile.cloud.CloudText;
import gg.projects.mobile.cloud.CloudWords;
import gg.projects.mobile.ui.Haptics;
import gg.projects.mobile.ui.Press;

/**
 * "Konsol": the account's lines as they come (the live socket), earlier lines from the log on request, and a line
 * to send while it is in the game. A login command is encrypted on this phone before it leaves (the line under the
 * field says so as soon as it looks like one) and the console never shows it back. The list sticks to the bottom
 * while the player is there; scrolled up, "Yeni mesajlar" brings them back.
 *
 * <p>Room is kept for CAFK-FEAT (FEAT-M): the console's filter row goes into {@link #mFilters}, under the state.
 */
public final class CloudConsoleFragment extends CloudScreen implements CloudService.LiveListener {
    public static final String TAG = "ProjectsCloudConsoleFragment";
    private static final int MAX_LINES = 1500;

    private RecyclerView mList;
    private LinearLayoutManager mLayout;
    private final Lines mAdapter = new Lines();
    private LinearLayout mStrip;
    private CloudUi.StateLine mState;
    /** FEAT-M: the console's filter row (kinds of lines), under the state. Empty until then. */
    private LinearLayout mFilters;
    private LinearLayout mNotice;
    private LinearLayout mEmpty;
    private TextView mJump;
    private EditText mInput;
    private ImageButton mSend;
    private TextView mNote;
    @Nullable
    private CloudService.LiveHandle mLive;
    private CloudService.Live mLiveState = CloudService.Live.CONNECTING;
    private boolean mNoHost;
    private boolean mHostOnline = true;
    private boolean mSending;
    private boolean mLoadingEarlier;
    private boolean mNoEarlier;
    private String mNoticeShown = "";
    private final Runnable mTick = this::tick;

    public CloudConsoleFragment() {
        super(R.layout.fragment_projects_cloud_stream);
    }

    @Override
    protected void created(View view) {
        Context c = view.getContext();
        mHeader.setTitle(getString(R.string.projects_cloud_console));
        mStrip = view.findViewById(R.id.projects_cloud_stream_top);
        mList = view.findViewById(R.id.projects_cloud_stream_list);
        mEmpty = view.findViewById(R.id.projects_cloud_stream_empty);
        mJump = view.findViewById(R.id.projects_cloud_stream_jump);
        mInput = view.findViewById(R.id.projects_cloud_stream_input);
        mSend = view.findViewById(R.id.projects_cloud_stream_send);
        mNote = view.findViewById(R.id.projects_cloud_stream_note);

        mState = CloudUi.stateLine(c, 14);
        mState.view.setPadding(CloudUi.dp(c, 8), CloudUi.dp(c, 4), CloudUi.dp(c, 8), CloudUi.dp(c, 6));
        mStrip.addView(mState.view, CloudUi.matchWrap());
        mFilters = new LinearLayout(c);
        mFilters.setOrientation(LinearLayout.HORIZONTAL);
        mFilters.setVisibility(View.GONE);
        mStrip.addView(mFilters, CloudUi.matchWrap());
        mNotice = new LinearLayout(c);
        mNotice.setOrientation(LinearLayout.VERTICAL);
        mStrip.addView(mNotice, CloudUi.matchWrap());

        mLayout = new LinearLayoutManager(c);
        mLayout.setStackFromEnd(true);
        mList.setLayoutManager(mLayout);
        mList.setItemAnimator(null);
        mList.setAdapter(mAdapter);
        mList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (!recyclerView.canScrollVertically(1)) mJump.setVisibility(View.GONE);
            }
        });
        mJump.setText(getString(R.string.projects_cloud_console_new));
        Press.attach(mJump, Press.Style.BUTTON);
        mJump.setOnClickListener(v -> {
            mJump.setVisibility(View.GONE);
            if (mAdapter.getItemCount() > 0) mList.scrollToPosition(mAdapter.getItemCount() - 1);
        });

        mInput.setHint(getString(R.string.projects_cloud_console_input));
        mInput.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(256)});
        mSend.setContentDescription(getString(R.string.projects_cloud_console_send));
        Press.attach(mSend, Press.Style.ICON);
        mSend.setOnClickListener(v -> send());
        mInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                send();
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
                composer();
            }
        });
    }

    @Override
    protected void started() {
        CloudModel.Account a = account();
        if (a != null) {
            mHeader.setCaption(a.name);
            if (mLive == null) {
                mLive = mService.watch(a.id, false, this);
                mNoHost = mLive == null;
            }
        }
        notice();
        empty();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mLive != null) {
            mLive.close();
            mLive = null;
        }
        View view = getView();
        if (view != null) view.removeCallbacks(mTick);
    }

    @Override
    protected void render() {
        CloudModel.Account a = account();
        if (a == null) return;
        mHeader.setCaption(a.name);
        tick();
        composer();
        notice();
        empty();
    }

    private void tick() {
        View view = getView();
        CloudModel.Account a = account();
        if (view == null || a == null) return;
        view.removeCallbacks(mTick);
        CloudStatus.Line line = CloudStatus.of(a, mService.nowMs(), lang());
        mState.set(CloudWords.state(ctx(), line), line);
        if (line.nextChangeMs > 0) view.postDelayed(mTick, Math.max(1000, line.nextChangeMs));
    }

    /** The field, the send button and the line under them. */
    private void composer() {
        CloudModel.Account a = account();
        if (a == null || mInput == null) return;
        boolean inGame = CloudStatus.inGame(a);
        mInput.setEnabled(inGame && !mSending);
        String typed = mInput.getText().toString().trim();
        mSend.setEnabled(inGame && !mSending && !typed.isEmpty());
        mSend.setAlpha(mSend.isEnabled() ? 1f : 0.5f);
        Context c = ctx();
        if (!inGame) {
            note(getString(R.string.projects_cloud_console_offline), 0);
        } else if (!typed.isEmpty() && CloudText.looksSensitive(typed)) {
            note(getString(R.string.projects_cloud_console_sealed), R.drawable.projects_ic_lock);
        } else {
            note(null, 0);
        }
    }

    private void note(@Nullable String text, int icon) {
        if (text == null) {
            mNote.setVisibility(View.GONE);
            return;
        }
        mNote.setText(text);
        mNote.setCompoundDrawablesRelative(icon == 0 ? null : CloudUi.tinted(ctx(), icon, R.color.projects_ash_400, 14), null, null, null);
        mNote.setVisibility(View.VISIBLE);
    }

    /** The live connection's state, where it needs words. */
    private void notice() {
        if (mNotice == null) return;
        final CloudModel.Account a = account();
        String key;
        if (mNoHost) key = "nohost";
        else if (!mHostOnline) key = "host";
        else key = mLiveState.name();
        if (key.equals(mNoticeShown)) return;
        mNoticeShown = key;
        mNotice.removeAllViews();
        Context c = ctx();
        int words = 0;
        int icon = R.drawable.projects_ic_warning_circle;
        int tone = R.color.projects_gold_300;
        boolean reopen = false;
        if (mNoHost) {
            words = R.string.projects_cloud_console_no_host;
            icon = R.drawable.projects_ic_clock;
            tone = R.color.projects_ash_300;
        } else if (!mHostOnline || mLiveState == CloudService.Live.HOST_OFFLINE) {
            words = R.string.projects_cloud_console_host_offline;
        } else {
            switch (mLiveState) {
                case LOST: words = R.string.projects_cloud_console_link_lost; break;
                case REPLACED: words = R.string.projects_cloud_console_replaced; reopen = true; break;
                case SIGNED_OUT: words = R.string.projects_cloud_console_ended; break;
                case REFUSED: words = R.string.projects_cloud_console_refused; tone = R.color.projects_flare_400; break;
                default: break;
            }
        }
        if (words == 0) return;
        mNotice.addView(CloudUi.notice(c, icon, tone, getString(words)));
        if (reopen && a != null) {
            Button here = CloudUi.textButton(c, getString(R.string.projects_cloud_console_reopen), v -> {
                mService.reopen(a.id);
                Haptics.tick(v);
            });
            mNotice.addView(here, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    /** "Bağlanılıyor" before the first lines, "Henüz mesaj yok" when there are none. */
    private void empty() {
        if (mEmpty == null) return;
        mEmpty.removeAllViews();
        if (mAdapter.lineCount() > 0 || mNoHost) {
            mEmpty.setVisibility(View.GONE);
            return;
        }
        Context c = ctx();
        boolean waiting = mLiveState == CloudService.Live.CONNECTING || mLiveState == CloudService.Live.LOST;
        mEmpty.addView(CloudUi.empty(c, gg.projects.mobile.ui.PixelSprites.Sprite.LOGS,
                getString(waiting ? R.string.projects_cloud_console_connecting : R.string.projects_cloud_console_empty)));
        mEmpty.setVisibility(View.VISIBLE);
    }

    private void send() {
        final CloudModel.Account a = account();
        if (a == null || mSending) return;
        final String typed = mInput.getText().toString().trim();
        if (typed.isEmpty()) return;
        CloudText.Problem problem = CloudText.check(typed);
        if (problem != null) {
            Haptics.reject(mSend);
            note(CloudWords.issue(ctx(), new CloudEdit.Issue("console", -1, "text", problem.code), mService.limits()), 0);
            return;
        }
        mSending = true;
        composer();
        mService.send(a.id, typed, new CloudService.Result<Void>() {
            @Override
            public void ok(Void value) {
                mSending = false;
                if (!alive()) return;
                Haptics.confirm(mSend);
                mInput.setText("");
                composer();
            }

            @Override
            public void failed(CloudProblem p) {
                mSending = false;
                if (!alive()) return;
                composer();
                Haptics.reject(mSend);
                if (p.kind == CloudProblem.Kind.FAILED || p.kind == CloudProblem.Kind.UNAVAILABLE) {
                    note(getString(R.string.projects_cloud_console_send_failed), 0);
                } else {
                    note(CloudWords.problem(ctx(), p), 0);
                }
            }
        });
    }

    private void earlier(final View from) {
        final CloudModel.Account a = account();
        CloudModel.ConsoleLine first = mAdapter.firstLine();
        if (a == null || first == null || mLoadingEarlier) return;
        mLoadingEarlier = true;
        mAdapter.notifyItemChanged(0);
        final long oldest = first.at;
        mService.logs(a.id, oldest - 3600, oldest, null, new CloudService.Result<CloudModel.LogPage>() {
            @Override
            public void ok(CloudModel.LogPage page) {
                mLoadingEarlier = false;
                if (!alive()) return;
                List<CloudModel.ConsoleLine> older = new ArrayList<>();
                for (CloudModel.ConsoleLine l : page.lines) {
                    if (l.at < oldest || (l.at == oldest && !mAdapter.hasLine(l))) older.add(l);
                }
                if (older.isEmpty()) mNoEarlier = true;
                mAdapter.prepend(older, !mNoEarlier);
            }

            @Override
            public void failed(CloudProblem p) {
                mLoadingEarlier = false;
                if (!alive()) return;
                mAdapter.notifyItemChanged(0);
                problem(p, from);
            }
        });
    }

    // ---- the live socket ----

    @Override
    public void liveState(CloudService.Live state) {
        mLiveState = state;
        if (state == CloudService.Live.LIVE) mHostOnline = true;
        if (!alive()) return;
        notice();
        empty();
    }

    @Override
    public void console(List<CloudModel.ConsoleLine> lines, int skipped, boolean snapshot) {
        if (!alive()) return;
        boolean atBottom = !mList.canScrollVertically(1);
        if (snapshot) {
            mAdapter.replace(lines, !mNoEarlier);
            mList.scrollToPosition(Math.max(0, mAdapter.getItemCount() - 1));
        } else {
            mAdapter.append(lines, skipped);
            if (atBottom) mList.scrollToPosition(mAdapter.getItemCount() - 1);
            else if (!lines.isEmpty()) mJump.setVisibility(View.VISIBLE);
        }
        empty();
    }

    @Override
    public void feed(List<CloudModel.FeedItem> items, boolean snapshot) {}

    @Override
    public void approval(CloudModel.Approval approval) {}

    @Override
    public void hostOnline(boolean online) {
        mHostOnline = online;
        if (alive()) notice();
    }

    // ---- the list ----

    private static final int TYPE_EARLIER = 0;
    private static final int TYPE_LINE = 1;
    private static final int TYPE_SKIPPED = 2;

    /** One entry: a line, a "skipped" marker, or "Önceki satırlar" at the top. */
    private static final class Item {
        final int type;
        @Nullable
        final CloudModel.ConsoleLine line;
        final int skipped;

        Item(int type, @Nullable CloudModel.ConsoleLine line, int skipped) {
            this.type = type;
            this.line = line;
            this.skipped = skipped;
        }
    }

    private final class Lines extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final List<Item> mItems = new ArrayList<>();

        int lineCount() {
            int n = 0;
            for (Item i : mItems) if (i.type == TYPE_LINE) n++;
            return n;
        }

        @Nullable
        CloudModel.ConsoleLine firstLine() {
            for (Item i : mItems) if (i.type == TYPE_LINE) return i.line;
            return null;
        }

        boolean hasLine(CloudModel.ConsoleLine l) {
            for (Item i : mItems) {
                if (i.line != null && i.line.at == l.at && TextUtils.equals(i.line.text, l.text)) return true;
            }
            return false;
        }

        void replace(List<CloudModel.ConsoleLine> lines, boolean earlier) {
            mItems.clear();
            if (earlier && !lines.isEmpty()) mItems.add(new Item(TYPE_EARLIER, null, 0));
            for (CloudModel.ConsoleLine l : lines) mItems.add(new Item(TYPE_LINE, l, 0));
            notifyDataSetChanged();
        }

        void append(List<CloudModel.ConsoleLine> lines, int skipped) {
            int start = mItems.size();
            if (skipped > 0) mItems.add(new Item(TYPE_SKIPPED, null, skipped));
            if (mItems.isEmpty() && !lines.isEmpty() && !mNoEarlier) mItems.add(new Item(TYPE_EARLIER, null, 0));
            for (CloudModel.ConsoleLine l : lines) mItems.add(new Item(TYPE_LINE, l, 0));
            int over = mItems.size() - MAX_LINES;
            if (over > 0) {
                boolean head = !mItems.isEmpty() && mItems.get(0).type == TYPE_EARLIER;
                for (int i = 0; i < over; i++) mItems.remove(head ? 1 : 0);
                notifyDataSetChanged();
            } else {
                notifyItemRangeInserted(start, mItems.size() - start);
            }
        }

        void prepend(List<CloudModel.ConsoleLine> older, boolean keepEarlier) {
            boolean head = !mItems.isEmpty() && mItems.get(0).type == TYPE_EARLIER;
            if (head) mItems.remove(0);
            List<Item> before = new ArrayList<>();
            if (keepEarlier) before.add(new Item(TYPE_EARLIER, null, 0));
            for (CloudModel.ConsoleLine l : older) before.add(new Item(TYPE_LINE, l, 0));
            mItems.addAll(0, before);
            notifyDataSetChanged();
            // Keep the line the player was reading in place.
            mLayout.scrollToPositionWithOffset(before.size(), CloudUi.dp(ctx(), 24));
        }

        @Override
        public int getItemViewType(int position) {
            return mItems.get(position).type;
        }

        @Override
        public int getItemCount() {
            return mItems.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context c = parent.getContext();
            View view;
            if (viewType == TYPE_EARLIER) {
                Button button = CloudUi.textButton(c, c.getString(R.string.projects_cloud_console_earlier), v -> earlier(v));
                LinearLayout box = new LinearLayout(c);
                box.setGravity(Gravity.CENTER_HORIZONTAL);
                box.addView(button);
                view = box;
            } else if (viewType == TYPE_SKIPPED) {
                TextView marker = CloudUi.text(c, "", 12.5f, R.color.projects_ash_400, false);
                marker.setGravity(Gravity.CENTER);
                marker.setPadding(0, CloudUi.dp(c, 6), 0, CloudUi.dp(c, 6));
                view = marker;
            } else {
                LinearLayout row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(CloudUi.dp(c, 4), CloudUi.dp(c, 3), CloudUi.dp(c, 4), CloudUi.dp(c, 3));
                TextView time = CloudUi.figure(c, "", 11.5f, R.color.projects_ash_400);
                time.setPadding(0, CloudUi.dp(c, 3), 0, 0);
                row.addView(time, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                TextView text = CloudUi.text(c, "", 14, R.color.projects_bone_100, false);
                text.setLineSpacing(0, 1.12f);
                if (Build.VERSION.SDK_INT >= 23) text.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lp.setMarginStart(CloudUi.dp(c, 10));
                row.addView(text, lp);
                view = row;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerView.ViewHolder(view) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Item item = mItems.get(position);
            Context c = holder.itemView.getContext();
            if (item.type == TYPE_EARLIER) {
                View button = ((ViewGroup) holder.itemView).getChildAt(0);
                button.setEnabled(!mLoadingEarlier);
                button.setAlpha(mLoadingEarlier ? 0.5f : 1f);
            } else if (item.type == TYPE_SKIPPED) {
                ((TextView) holder.itemView).setText(c.getResources().getQuantityString(R.plurals.projects_cloud_console_skipped, item.skipped, item.skipped));
            } else if (item.line != null) {
                ViewGroup row = (ViewGroup) holder.itemView;
                TextView time = (TextView) row.getChildAt(0);
                TextView text = (TextView) row.getChildAt(1);
                time.setText(CloudWords.clock(item.line.at));
                text.setText(item.line.text);
                int color = "e".equals(item.line.k) ? R.color.projects_flare_400
                        : "s".equals(item.line.k) ? R.color.projects_gold_300 : R.color.projects_bone_100;
                text.setTextColor(CloudUi.color(c, color));
                text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            }
        }
    }
}
