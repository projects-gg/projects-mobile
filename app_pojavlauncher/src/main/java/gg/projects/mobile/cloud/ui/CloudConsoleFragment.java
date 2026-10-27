package gg.projects.mobile.cloud.ui;

import android.content.Context;
import android.os.Build;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
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
import gg.projects.mobile.cloud.console.Bars;
import gg.projects.mobile.cloud.console.ConsoleKinds;
import gg.projects.mobile.cloud.console.ConsoleShare;
import gg.projects.mobile.cloud.console.ConsoleViews;
import gg.projects.mobile.ui.Haptics;
import gg.projects.mobile.ui.Press;

/**
 * "Konsol": the account's lines as they come (the live socket), earlier lines from the log on request, and a line
 * to send while it is in the game. A login command is encrypted on this phone before it leaves (the line under the
 * field says so as soon as it looks like one) and the console never shows it back. The list sticks to the bottom
 * while the player is there; scrolled up, "Yeni mesajlar" brings them back.
 *
 * <p>With console kinds (CAFK-FEAT §3.2, the account's {@code features.consoleKinds}): lines in their kind's colour,
 * filter chips (Hepsi, Sohbet, Özel, Sunucu, Komutlarım, Bağlantı, Adın geçenler), a search over the loaded lines, the
 * strip of what the server keeps on screen in the game, "Envanterde göster" beside a menu's line, and the log shared or
 * saved as a file. Without them the console is as it was.
 */
public final class CloudConsoleFragment extends CloudScreen implements CloudService.LiveListener, ConsoleShare.Host {
    public static final String TAG = "ProjectsCloudConsoleFragment";
    private static final int MAX_LINES = 1500;

    private RecyclerView mList;
    private LinearLayoutManager mLayout;
    private final Lines mAdapter = new Lines();
    private LinearLayout mStrip;
    private CloudUi.StateLine mState;
    /** The console's filter row (kinds of lines), under the state; empty without console kinds. */
    private LinearLayout mFilters;
    private LinearLayout mNotice;
    private LinearLayout mEmpty;
    private TextView mJump;
    private EditText mInput;
    private ImageButton mSend;
    private TextView mNote;
    @Nullable
    private ConsoleViews.Chips mChips;
    @Nullable
    private ConsoleViews.Strip mBarsView;
    @Nullable
    private EditText mSearch;
    @Nullable
    private View mSearchRow;
    private boolean mKinds;
    private boolean mActionsAdded;
    /** 0–5 the kinds' chips, 6 "Adın geçenler". */
    private int mFilter;
    private String mQuery = "";
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
    @Nullable
    private byte[] mPendingSave;
    private final ActivityResultLauncher<android.content.Intent> mSaveAs = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                byte[] bytes = mPendingSave;
                mPendingSave = null;
                android.net.Uri uri = result.getData() == null ? null : result.getData().getData();
                if (result.getResultCode() == android.app.Activity.RESULT_OK && uri != null && bytes != null && getContext() != null) {
                    ConsoleShare.write(requireContext(), uri, bytes);
                }
            });

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
        mFilters.setOrientation(LinearLayout.VERTICAL);
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
        kinds();
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
        kinds();
        tick();
        composer();
        notice();
        empty();
    }

    // ---- console kinds: the chips, the search, the strip, sharing ----

    /** Shows the kinds' tools while the account has them (they come and go with the service's switch). */
    private void kinds() {
        CloudModel.Account a = account();
        boolean on = a != null && a.features.consoleKinds;
        if (on == mKinds && (mChips != null || !on)) return;
        mKinds = on;
        Context c = ctx();
        mFilters.removeAllViews();
        mChips = null;
        mBarsView = null;
        mSearch = null;
        mSearchRow = null;
        if (!on) {
            mFilters.setVisibility(View.GONE);
            mFilter = 0;
            mQuery = "";
            mAdapter.refilter();
            return;
        }
        if (!mActionsAdded) {
            // Header actions: search the loaded lines, download the log (to share or save). Added once; hidden without kinds.
            mActionsAdded = true;
            mHeader.addAction(R.drawable.projects_ic_magnifying_glass, R.string.projects_feat_console_search, v -> toggleSearch());
            mHeader.addAction(R.drawable.projects_ic_download_simple, R.string.projects_feat_console_export_open, v -> {
                CloudModel.Account account = account();
                if (account != null) ConsoleShare.open(this, account, lang());
            });
        }
        CharSequence[] labels = {getString(R.string.projects_feat_console_all), getString(R.string.projects_feat_console_chat),
                getString(R.string.projects_feat_console_private), getString(R.string.projects_feat_console_server),
                getString(R.string.projects_feat_console_mine), getString(R.string.projects_feat_console_connection),
                getString(R.string.projects_feat_console_mentions)};
        mChips = new ConsoleViews.Chips(c, labels, mFilter, i -> {
            mFilter = i;
            mAdapter.refilter();
            empty();
        });
        LinearLayout.LayoutParams chipsLp = CloudUi.matchWrap();
        chipsLp.leftMargin = -CloudUi.dp(c, 4);
        mFilters.addView(mChips.view, chipsLp);

        LinearLayout searchRow = new LinearLayout(c);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        EditText search = new EditText(c);
        search.setBackgroundResource(R.drawable.projects_input);
        search.setHint(getString(R.string.projects_feat_console_search));
        search.setHintTextColor(CloudUi.color(c, R.color.projects_ash_400));
        search.setTextColor(CloudUi.color(c, R.color.projects_bone_100));
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        search.setSingleLine(true);
        search.setMinHeight(CloudUi.dp(c, 44));
        search.setPadding(CloudUi.dp(c, 14), 0, CloudUi.dp(c, 14), 0);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setCompoundDrawablesRelative(CloudUi.tinted(c, R.drawable.projects_ic_magnifying_glass, R.color.projects_ash_400, 18), null, null, null);
        search.setCompoundDrawablePadding(CloudUi.dp(c, 8));
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                mQuery = s.toString();
                mAdapter.refilter();
                empty();
            }
        });
        searchRow.addView(search, CloudUi.weight(1f));
        ImageButton clear = new ImageButton(c);
        clear.setBackgroundResource(R.drawable.projects_icon_button_ripple);
        clear.setImageDrawable(CloudUi.tinted(c, R.drawable.projects_ic_x, R.color.projects_bone_100, 20));
        clear.setContentDescription(getString(R.string.projects_feat_console_search_close));
        Press.attach(clear, Press.Style.ICON);
        clear.setOnClickListener(v -> toggleSearch());
        searchRow.addView(clear, new LinearLayout.LayoutParams(CloudUi.dp(c, 48), CloudUi.dp(c, 48)));
        LinearLayout.LayoutParams searchLp = CloudUi.matchWrap();
        searchLp.topMargin = CloudUi.dp(c, 2);
        searchRow.setVisibility(mQuery.isEmpty() ? View.GONE : View.VISIBLE);
        if (!mQuery.isEmpty()) search.setText(mQuery);
        mFilters.addView(searchRow, searchLp);
        mSearch = search;
        mSearchRow = searchRow;

        ConsoleViews.Strip strip = new ConsoleViews.Strip(c);
        strip.setClock(() -> mService.nowMs());
        LinearLayout.LayoutParams stripLp = CloudUi.matchWrap();
        stripLp.topMargin = CloudUi.dp(c, 4);
        // Room under the card, so the first line below it never touches it (the second look saw them meet at 360 dp).
        stripLp.bottomMargin = CloudUi.dp(c, 10);
        mFilters.addView(strip, stripLp);
        mBarsView = strip;
        mFilters.setVisibility(View.VISIBLE);
        mAdapter.refilter();
    }

    private void toggleSearch() {
        if (mSearchRow == null || mSearch == null) return;
        boolean open = mSearchRow.getVisibility() != View.VISIBLE;
        mSearchRow.setVisibility(open ? View.VISIBLE : View.GONE);
        InputMethodManager imm = (InputMethodManager) ctx().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (open) {
            mSearch.requestFocus();
            if (imm != null) imm.showSoftInput(mSearch, InputMethodManager.SHOW_IMPLICIT);
        } else {
            mSearch.setText("");
            if (imm != null) imm.hideSoftInputFromWindow(mSearch.getWindowToken(), 0);
        }
    }

    @Override
    public android.app.Activity activity() {
        return requireActivity();
    }

    @Override
    public CloudService service() {
        return mService;
    }

    @Override
    public void saveAs(String name, byte[] bytes) {
        mPendingSave = bytes;
        try {
            mSaveAs.launch(new android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("text/plain")
                    .putExtra(android.content.Intent.EXTRA_TITLE, name));
        } catch (RuntimeException e) {
            mPendingSave = null;
            toast(getString(R.string.projects_feat_console_export_failed));
        }
    }

    @Override
    public boolean alive() {
        return super.alive();
    }

    // ---- the state and the composer ----

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

    /** "Bağlanılıyor" before the first lines, "Henüz mesaj yok" when there are none, the filter's own when it hides all. */
    private void empty() {
        if (mEmpty == null) return;
        mEmpty.removeAllViews();
        if (mNoHost) {
            mEmpty.setVisibility(View.GONE);
            return;
        }
        Context c = ctx();
        if (mAdapter.lineCount() > 0) {
            if (mAdapter.visibleLines() > 0) {
                mEmpty.setVisibility(View.GONE);
                return;
            }
            // The lead's shared wording while something is searched; a kind chip alone says what it hides.
            boolean searched = !mQuery.trim().isEmpty();
            TextView none = CloudUi.text(c, getString(searched ? R.string.projects_feat_console_none
                    : R.string.projects_feat_console_none_kind), 14.5f, R.color.projects_ash_300, false);
            none.setGravity(Gravity.CENTER);
            none.setPadding(CloudUi.dp(c, 16), CloudUi.dp(c, 32), CloudUi.dp(c, 16), CloudUi.dp(c, 16));
            mEmpty.addView(none, CloudUi.matchWrap());
            mEmpty.setVisibility(View.VISIBLE);
            return;
        }
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
                empty();
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
            int before = mAdapter.getItemCount();
            mAdapter.append(lines, skipped);
            if (atBottom) mList.scrollToPosition(Math.max(0, mAdapter.getItemCount() - 1));
            else if (mAdapter.getItemCount() > before) mJump.setVisibility(View.VISIBLE);
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

    @Override
    public void bars(Bars bars) {
        if (alive() && mBarsView != null) mBarsView.set(bars);
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

    private boolean filtering() {
        return mKinds && (mFilter != 0 || !mQuery.trim().isEmpty());
    }

    private boolean shows(Item item) {
        if (item.type != TYPE_LINE || item.line == null) return item.type == TYPE_EARLIER || !filtering();
        if (!mKinds) return true;
        CloudModel.ConsoleLine l = item.line;
        boolean kind;
        if (mFilter == 6) {
            CloudModel.Account a = account();
            kind = ConsoleKinds.mentions(l, a == null ? null : a.name);
        } else {
            kind = ConsoleKinds.shows(ConsoleKinds.Filter.values()[Math.max(0, Math.min(5, mFilter))], l);
        }
        return kind && ConsoleKinds.found(l, mQuery);
    }

    private final class Lines extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final List<Item> mAll = new ArrayList<>();
        /** What shows: all items, or those the filter and search keep. */
        private List<Item> mItems = mAll;

        int lineCount() {
            int n = 0;
            for (Item i : mAll) if (i.type == TYPE_LINE) n++;
            return n;
        }

        int visibleLines() {
            int n = 0;
            for (Item i : mItems) if (i.type == TYPE_LINE) n++;
            return n;
        }

        @Nullable
        CloudModel.ConsoleLine firstLine() {
            for (Item i : mAll) if (i.type == TYPE_LINE) return i.line;
            return null;
        }

        boolean hasLine(CloudModel.ConsoleLine l) {
            for (Item i : mAll) {
                if (i.line != null && i.line.at == l.at && TextUtils.equals(i.line.text, l.text)) return true;
            }
            return false;
        }

        void refilter() {
            if (!filtering()) {
                mItems = mAll;
            } else {
                List<Item> kept = new ArrayList<>();
                for (Item i : mAll) if (shows(i)) kept.add(i);
                mItems = kept;
            }
            notifyDataSetChanged();
            if (mList != null && getItemCount() > 0) mList.scrollToPosition(getItemCount() - 1);
        }

        void replace(List<CloudModel.ConsoleLine> lines, boolean earlier) {
            mAll.clear();
            if (earlier && !lines.isEmpty()) mAll.add(new Item(TYPE_EARLIER, null, 0));
            for (CloudModel.ConsoleLine l : lines) mAll.add(new Item(TYPE_LINE, l, 0));
            if (filtering()) refilter();
            else {
                mItems = mAll;
                notifyDataSetChanged();
            }
        }

        void append(List<CloudModel.ConsoleLine> lines, int skipped) {
            int start = mAll.size();
            if (skipped > 0) mAll.add(new Item(TYPE_SKIPPED, null, skipped));
            if (mAll.isEmpty() && !lines.isEmpty() && !mNoEarlier) mAll.add(new Item(TYPE_EARLIER, null, 0));
            for (CloudModel.ConsoleLine l : lines) mAll.add(new Item(TYPE_LINE, l, 0));
            int over = mAll.size() - MAX_LINES;
            if (over > 0) {
                boolean head = !mAll.isEmpty() && mAll.get(0).type == TYPE_EARLIER;
                for (int i = 0; i < over; i++) mAll.remove(head ? 1 : 0);
            }
            if (filtering()) {
                List<Item> kept = new ArrayList<>();
                for (Item i : mAll) if (shows(i)) kept.add(i);
                int was = mItems.size();
                mItems = kept;
                if (over > 0 || kept.size() < was) notifyDataSetChanged();
                else notifyItemRangeInserted(was, kept.size() - was);
            } else {
                mItems = mAll;
                if (over > 0) notifyDataSetChanged();
                else notifyItemRangeInserted(start, mAll.size() - start);
            }
        }

        void prepend(List<CloudModel.ConsoleLine> older, boolean keepEarlier) {
            boolean head = !mAll.isEmpty() && mAll.get(0).type == TYPE_EARLIER;
            if (head) mAll.remove(0);
            List<Item> before = new ArrayList<>();
            if (keepEarlier) before.add(new Item(TYPE_EARLIER, null, 0));
            for (CloudModel.ConsoleLine l : older) before.add(new Item(TYPE_LINE, l, 0));
            mAll.addAll(0, before);
            int shownBefore = before.size();
            if (filtering()) {
                List<Item> kept = new ArrayList<>();
                shownBefore = 0;
                for (Item i : mAll) {
                    if (!shows(i)) continue;
                    kept.add(i);
                    if (before.contains(i)) shownBefore++;
                }
                mItems = kept;
            } else {
                mItems = mAll;
            }
            notifyDataSetChanged();
            // Keep the line the player was reading in place.
            mLayout.scrollToPositionWithOffset(shownBefore, CloudUi.dp(ctx(), 24));
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
                TextView time = CloudUi.time(c, 12, R.color.projects_ash_400);
                time.setPadding(0, CloudUi.dp(c, 3), 0, 0);
                row.addView(time, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                LinearLayout body = new LinearLayout(c);
                body.setOrientation(LinearLayout.VERTICAL);
                TextView text = CloudUi.text(c, "", 14, R.color.projects_bone_100, false);
                text.setLineSpacing(0, 1.12f);
                if (Build.VERSION.SDK_INT >= 23) text.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE);
                body.addView(text, CloudUi.matchWrap());
                // "Envanterde göster" beside a menu's line (shown only on such lines).
                Button link = CloudUi.textButton(c, c.getString(R.string.projects_feat_console_show_inventory), null);
                link.setMinHeight(CloudUi.dp(c, 40));
                link.setMinimumHeight(CloudUi.dp(c, 40));
                link.setPadding(0, 0, CloudUi.dp(c, 12), 0);
                link.setVisibility(View.GONE);
                body.addView(link, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lp.setMarginStart(CloudUi.dp(c, 10));
                row.addView(body, lp);
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
                final CloudModel.ConsoleLine line = item.line;
                ViewGroup row = (ViewGroup) holder.itemView;
                TextView time = (TextView) row.getChildAt(0);
                ViewGroup body = (ViewGroup) row.getChildAt(1);
                TextView text = (TextView) body.getChildAt(0);
                Button link = (Button) body.getChildAt(1);
                time.setText(CloudWords.clock(line.at));
                text.setTextColor(CloudUi.color(c, toneColor(line)));
                text.setAlpha("s".equals(line.k) && line.c != null ? 0.9f : 1f);
                text.setText(styled(c, line));
                text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                final CloudModel.Account a = account();
                boolean go = "inventory".equals(line.go) && a != null && a.features.inventory();
                link.setVisibility(go ? View.VISIBLE : View.GONE);
                link.setOnClickListener(go ? v -> {
                    Haptics.tick(v);
                    openFor(gg.projects.mobile.cloud.inventory.CloudInventoryFragment.class, a.id);
                } : null);
            }
        }
    }

    /** The kind's colour (docs/tasks/cafk-feat.md §3.2); lines without a kind as before (by {@code k}). */
    private static int toneColor(CloudModel.ConsoleLine line) {
        switch (ConsoleKinds.tone(line)) {
            case CHAT: return R.color.projects_bone_100;
            case PRIVATE: return R.color.projects_gold_200;
            case SERVER: return R.color.projects_ash_300;
            case MINE: return R.color.projects_gold_300;
            case CLIENT: return R.color.projects_ash_400;
            case ERROR: return R.color.projects_flare_400;
            default: return R.color.projects_bone_100;
        }
    }

    /** A private line's other player in gold-300 (the rest gold-200); every other line plain. */
    private static CharSequence styled(Context c, CloudModel.ConsoleLine line) {
        if (!"pm".equals(line.c) || line.who == null) return line.text;
        int at = line.text.indexOf(line.who);
        if (at < 0) return line.text;
        SpannableString s = new SpannableString(line.text);
        s.setSpan(new ForegroundColorSpan(CloudUi.color(c, R.color.projects_gold_300)), at, at + line.who.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }
}
