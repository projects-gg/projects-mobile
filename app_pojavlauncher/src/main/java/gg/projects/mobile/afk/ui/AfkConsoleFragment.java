package gg.projects.mobile.afk.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import net.kdt.pojavlaunch.R;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import gg.projects.mobile.afk.api.AfkAccount;
import gg.projects.mobile.afk.api.AfkLimits;
import gg.projects.mobile.afk.api.AfkLine;
import gg.projects.mobile.afk.api.AfkSendResult;
import gg.projects.mobile.afk.api.AfkStartResult;
import gg.projects.mobile.afk.api.AfkState;
import gg.projects.mobile.afk.api.AfkStatus;
import gg.projects.mobile.afk.api.AfkTexts;
import gg.projects.mobile.cloud.console.ConsoleViews;
import gg.projects.mobile.cloud.ui.CloudUi;
import gg.projects.mobile.mods.Motion;
import gg.projects.mobile.ui.Haptics;
import gg.projects.mobile.ui.PixelSprites;
import gg.projects.mobile.ui.Press;

/**
 * The AFK console (phone-afk.md §5, the desktop's {@code afk-console.tsx}): one page per account that swipes sideways,
 * with chips over them for the accounts (the running ones and the one opened; the set only grows while it is on screen,
 * {@link AfkTabs}). A page: the state line and the server with the main action and the settings, then the account's
 * lines as they come (the game's colours made readable, CONSOLE-COLOR; a line with the account's name on a quiet plate;
 * the app's own lines quieter), following the newest while the player is at the bottom, else "Yeni mesajlar". Under the
 * pages one field sends a chat line or a command for the page's account while it is in the game (the engine's rate
 * limit answers in words; the password stays hidden in the console).
 */
public final class AfkConsoleFragment extends AfkScreen {
    public static final String TAG = "ProjectsAfkConsoleFragment";
    private static final String ARG_ACCOUNT = "gg.projects.mobile.afk.ACCOUNT";

    private final List<String> mTabs = new ArrayList<>();
    private final Map<String, List<AfkLine>> mLines = new HashMap<>();
    private final Map<String, Page> mPages = new HashMap<>();
    private final Map<String, AfkStatus> mStatuses = new HashMap<>();
    private FrameLayout mTabsBox;
    @Nullable
    private ConsoleViews.Chips mChips;
    private ViewPager2 mPager;
    private final Pages mAdapter = new Pages();
    private EditText mInput;
    private ImageButton mSend;
    private TextView mNote;
    private boolean mSending;
    @Nullable
    private String mFailNote;
    private final Runnable mTick = this::tick;
    /** The account whose page is shown (kept while another screen is on top, so the console comes back on it). */
    @Nullable
    private String mShownId;

    public AfkConsoleFragment() {
        super(R.layout.fragment_projects_afk_console);
    }

    public static Bundle args(String accountId) {
        Bundle args = new Bundle();
        args.putString(ARG_ACCOUNT, accountId);
        return args;
    }

    @Nullable
    private String opened() {
        Bundle args = getArguments();
        return args == null ? null : args.getString(ARG_ACCOUNT);
    }

    @Override
    protected void created(View view, @Nullable Bundle savedInstanceState) {
        Context c = view.getContext();
        mTabsBox = view.findViewById(R.id.projects_afk_console_tabs);
        mPager = view.findViewById(R.id.projects_afk_console_pager);
        mInput = view.findViewById(R.id.projects_afk_console_input);
        mSend = view.findViewById(R.id.projects_afk_console_send);
        mNote = view.findViewById(R.id.projects_afk_console_note);
        // A view made again (back from the settings): its own chips and pages; the tabs and lines stay.
        mChips = null;
        mPages.clear();
        mPager.setAdapter(mAdapter);
        mPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                if (position >= 0 && position < mTabs.size()) mShownId = mTabs.get(position);
                if (mChips != null && mChips.selected() != position) mChips.select(position);
                mFailNote = null;
                String id = current();
                if (id != null) AfkSeen.seen(id);
                header();
                composer();
                tick();
            }
        });

        String opened = opened();
        if (opened == null || mAfk.account(opened) == null) {
            leaveNow();
            return;
        }
        AfkSeen.seen(opened);
        mInput.setHint(R.string.projects_afk_ui_console_input);
        mInput.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(AfkLimits.MAX_LINE_CHARS)});
        mSend.setContentDescription(getString(R.string.projects_afk_ui_console_send));
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
                mFailNote = null;
                composer();
            }
        });
    }

    @Override
    public void onDestroyView() {
        if (mPager != null) mPager.setAdapter(null);
        mPages.clear();
        mChips = null;
        super.onDestroyView();
    }

    @Override
    public void onPause() {
        super.onPause();
        View view = getView();
        if (view != null) view.removeCallbacks(mTick);
    }

    @Nullable
    private String current() {
        int at = mPager == null ? -1 : mPager.getCurrentItem();
        return at >= 0 && at < mTabs.size() ? mTabs.get(at) : null;
    }

    // ---- the accounts ----

    @Override
    public void onStatuses(List<AfkStatus> all) {
        if (!alive()) return;
        mStatuses.clear();
        for (AfkStatus s : all) mStatuses.put(s.accountId, s);
        String opened = opened();
        List<String> tabs = mTabs.isEmpty() ? AfkTabs.initial(all, opened) : AfkTabs.grow(mTabs, all);
        if (tabs.isEmpty() || (opened != null && mTabs.isEmpty() && !tabs.contains(opened))) {
            leaveNow();
            return;
        }
        setTabs(tabs, mTabs.isEmpty() ? Math.max(0, tabs.indexOf(opened)) : -1);
        for (Page page : mPages.values()) page.bindHead();
        header();
        composer();
        tick();
    }

    @Override
    public void onStatus(AfkStatus status) {
        if (!alive()) return;
        mStatuses.put(status.accountId, status);
        if (!mTabs.contains(status.accountId) && status.state.running()) {
            setTabs(AfkTabs.grow(mTabs, mAfk.statuses()), -1);
        }
        Page page = mPages.get(status.accountId);
        if (page != null) page.bindHead();
        if (status.accountId.equals(current())) {
            composer();
            tick();
        }
    }

    @Override
    public void onAlert(gg.projects.mobile.afk.api.AfkAlert alert) {
        super.onAlert(alert);
        // The player is looking at that account's lines: nothing waits for the list to say.
        if (alert.accountId.equals(current())) AfkSeen.seen(alert.accountId);
    }

    @Override
    public void onBacklog(String accountId, List<AfkLine> lines) {
        if (!alive()) return;
        List<AfkLine> kept = new ArrayList<>(lines);
        while (kept.size() > AfkLimits.CONSOLE_LINES) kept.remove(0);
        mLines.put(accountId, kept);
        Page page = mPages.get(accountId);
        if (page != null) page.replace();
    }

    @Override
    public void onLine(AfkLine line) {
        if (!alive()) return;
        List<AfkLine> list = mLines.get(line.accountId);
        if (list == null) {
            list = new ArrayList<>();
            mLines.put(line.accountId, list);
        }
        list.add(line);
        int over = Math.max(0, list.size() - AfkLimits.CONSOLE_LINES);
        for (int i = 0; i < over; i++) list.remove(0);
        Page page = mPages.get(line.accountId);
        if (page != null) page.appended(over);
        if (line.accountId.equals(current())) AfkSeen.seen(line.accountId);
    }

    /** The tabs as they are now; {@code select} the page to show (-1: keep the one shown). */
    private void setTabs(List<String> tabs, int select) {
        String shown = mShownId;
        boolean changed = !tabs.equals(mTabs);
        if (changed) {
            mTabs.clear();
            mTabs.addAll(tabs);
            for (String gone : new ArrayList<>(mPages.keySet())) if (!mTabs.contains(gone)) mPages.remove(gone);
            mAdapter.notifyDataSetChanged();
        }
        mPager.setOffscreenPageLimit(Math.max(1, mTabs.size()));
        int target = select >= 0 ? select : shown == null ? 0 : Math.max(0, mTabs.indexOf(shown));
        if (target < mTabs.size()) mShownId = mTabs.get(target);
        if (changed || mChips == null) chips(target);
        if (mPager.getCurrentItem() != target) mPager.setCurrentItem(target, false);
        if (mChips != null) mChips.select(target);
    }

    /** The account chips over the pages, with two accounts or more (one account names itself in the title). */
    private void chips(int selected) {
        mTabsBox.removeAllViews();
        mChips = null;
        if (mTabs.size() < 2) {
            mTabsBox.setVisibility(View.GONE);
            return;
        }
        CharSequence[] labels = new CharSequence[mTabs.size()];
        for (int i = 0; i < mTabs.size(); i++) {
            AfkAccount a = mAfk.account(mTabs.get(i));
            labels[i] = a == null ? "" : a.name;
        }
        mChips = new ConsoleViews.Chips(ctx(), labels, Math.max(0, selected),
                index -> mPager.setCurrentItem(index, Motion.enabled(ctx())));
        mChips.view.setContentDescription(getString(R.string.projects_afk_ui_console_accounts));
        mTabsBox.addView(mChips.view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mTabsBox.setVisibility(View.VISIBLE);
    }

    private void header() {
        if (mTabs.size() == 1) {
            AfkAccount a = mAfk.account(mTabs.get(0));
            mHeader.setTitle(getString(R.string.projects_afk_ui_console_title, a == null ? "" : a.name));
        } else {
            mHeader.setTitle(getString(R.string.projects_afk_ui_console));
        }
    }

    private AfkStatus status(String id) {
        AfkStatus s = mStatuses.get(id);
        if (s != null) return s;
        AfkAccount a = mAfk.account(id);
        return AfkStatus.stopped(id, a == null ? "" : a.name);
    }

    /** The shown page's state line ticks while its words change (uptime, a countdown); nothing else does. */
    private void tick() {
        View view = getView();
        if (view == null) return;
        view.removeCallbacks(mTick);
        String id = current();
        if (id == null) return;
        Page page = mPages.get(id);
        if (page != null) page.bindHead();
        long next = AfkLook.nextChange(status(id), System.currentTimeMillis());
        // Only while on screen: a status already on its way after onPause must not start a tick in the background.
        if (next > 0 && isResumed()) view.postDelayed(mTick, Math.max(250, next));
    }

    // ---- sending ----

    /** The field for the shown account: open while it is in the game; the line under it says what the player needs. */
    private void composer() {
        if (mInput == null) return;
        String id = current();
        boolean inGame = id != null && status(id).state == AfkState.IN_GAME;
        mInput.setEnabled(inGame && !mSending);
        mInput.setHint(inGame ? R.string.projects_afk_ui_console_input : R.string.projects_afk_ui_console_offline);
        String typed = mInput.getText().toString().trim();
        boolean can = inGame && !mSending && !typed.isEmpty();
        mSend.setEnabled(can);
        mSend.setAlpha(can ? 1f : 0.5f);
        if (mFailNote != null) {
            note(mFailNote, 0);
        } else if (inGame && AfkFormRules.secret(typed)) {
            note(getString(R.string.projects_afk_ui_console_secret), R.drawable.projects_ic_lock);
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

    private void send() {
        final String id = current();
        if (id == null || mSending) return;
        final String typed = mInput.getText().toString().trim();
        if (typed.isEmpty()) return;
        mSending = true;
        composer();
        mAfk.send(id, typed, result -> {
            mSending = false;
            if (!alive()) return;
            if (result == AfkSendResult.SENT) {
                Haptics.confirm(mSend);
                // A line typed meanwhile stays; the one sent goes.
                if (mInput.getText().toString().trim().equals(typed)) mInput.setText("");
                mFailNote = null;
            } else {
                Haptics.reject(mSend);
                mFailNote = AfkTexts.send(ctx(), result);
            }
            composer();
            Page page = mPages.get(id);
            if (page != null) page.toBottom();
        });
    }

    // ---- the account's main action (the list's) ----

    private void act(String id, View from) {
        AfkStatus s = status(id);
        switch (AfkLook.action(s)) {
            case STOP:
                Haptics.tick(from);
                mAfk.stop(id);
                break;
            case FIX_PASSWORD:
            case FIX_LOGIN:
                open(AfkEditFragment.class, AfkEditFragment.args(id, true));
                break;
            default:
                AfkStartResult result = mAfk.start(id);
                if (result.ok()) {
                    Haptics.confirm(from);
                    afterStart();
                } else {
                    Haptics.reject(from);
                    toast(AfkTexts.start(ctx(), result));
                }
                break;
        }
    }

    // ---- pages ----

    private final class Pages extends RecyclerView.Adapter<PageHolder> {
        @Override
        public int getItemCount() {
            return mTabs.size();
        }

        @Override
        public long getItemId(int position) {
            return mTabs.get(position).hashCode();
        }

        @NonNull
        @Override
        public PageHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Page page = new Page(parent.getContext());
            page.root.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return new PageHolder(page);
        }

        @Override
        public void onBindViewHolder(@NonNull PageHolder holder, int position) {
            String id = mTabs.get(position);
            for (Map.Entry<String, Page> e : new ArrayList<>(mPages.entrySet())) {
                if (e.getValue() == holder.page) mPages.remove(e.getKey());
            }
            mPages.put(id, holder.page);
            holder.page.bind(id);
        }
    }

    private static final class PageHolder extends RecyclerView.ViewHolder {
        final Page page;

        PageHolder(Page page) {
            super(page.root);
            this.page = page;
        }
    }

    /** One account's page: its head (state, server, main action, settings) over its lines. */
    private final class Page {
        final FrameLayout root;
        final AfkUi.StateLine state;
        final TextView address;
        final Button main;
        final ImageButton settings;
        final RecyclerView list;
        final LinearLayoutManager layout;
        final Lines lines = new Lines();
        final FrameLayout empty;
        final TextView jump;
        String id;
        private AfkLook.Action mAction;
        private boolean mJumpShown;
        private String mEmptyKey = "";

        Page(Context c) {
            root = new FrameLayout(c);
            LinearLayout column = new LinearLayout(c);
            column.setOrientation(LinearLayout.VERTICAL);
            LinearLayout head = new LinearLayout(c);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.setPaddingRelative(AfkUi.dp(c, 16), AfkUi.dp(c, 4), AfkUi.dp(c, 6), AfkUi.dp(c, 6));
            LinearLayout words = new LinearLayout(c);
            words.setOrientation(LinearLayout.VERTICAL);
            state = new AfkUi.StateLine(c, 14);
            words.addView(state.view, CloudUi.matchWrap());
            address = CloudUi.text(c, "", 13, R.color.projects_ash_400, false);
            address.setSingleLine(true);
            address.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            LinearLayout.LayoutParams addressLp = CloudUi.matchWrap();
            addressLp.topMargin = AfkUi.dp(c, 2);
            words.addView(address, addressLp);
            head.addView(words, CloudUi.weight(1f));
            main = CloudUi.button(c, "", false, v -> {
                if (id != null) act(id, v);
            });
            main.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
            main.setMinHeight(AfkUi.dp(c, 40));
            main.setMinimumHeight(AfkUi.dp(c, 40));
            main.setMinWidth(AfkUi.dp(c, 84));
            main.setMinimumWidth(AfkUi.dp(c, 84));
            main.setPadding(AfkUi.dp(c, 14), 0, AfkUi.dp(c, 14), 0);
            LinearLayout.LayoutParams mainLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mainLp.setMarginStart(AfkUi.dp(c, 10));
            head.addView(main, mainLp);
            settings = new ImageButton(c);
            settings.setImageResource(R.drawable.projects_ic_gear_six);
            settings.setImageTintList(ColorStateList.valueOf(AfkUi.color(c, R.color.projects_bone_100)));
            settings.setBackgroundResource(R.drawable.projects_icon_button_ripple);
            settings.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            int pad = AfkUi.dp(c, 12);
            settings.setPadding(pad, pad, pad, pad);
            settings.setContentDescription(getString(R.string.projects_afk_ui_settings));
            Press.attach(settings, Press.Style.ICON);
            settings.setOnClickListener(v -> {
                if (id != null) open(AfkEditFragment.class, AfkEditFragment.args(id, false));
            });
            head.addView(settings, new LinearLayout.LayoutParams(AfkUi.dp(c, 48), AfkUi.dp(c, 48)));
            column.addView(head, CloudUi.matchWrap());
            View line = new View(c);
            line.setBackgroundColor(AfkUi.color(c, R.color.projects_obsidian_700));
            LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, AfkUi.dp(c, 1)));
            lineLp.setMarginStart(AfkUi.dp(c, 12));
            lineLp.setMarginEnd(AfkUi.dp(c, 12));
            column.addView(line, lineLp);

            list = new RecyclerView(c);
            list.setClipToPadding(false);
            list.setPadding(AfkUi.dp(c, 12), AfkUi.dp(c, 6), AfkUi.dp(c, 12), AfkUi.dp(c, 8));
            list.setVerticalScrollBarEnabled(true);
            layout = new LinearLayoutManager(c);
            layout.setStackFromEnd(true);
            list.setLayoutManager(layout);
            list.setItemAnimator(null);
            list.setAdapter(lines);
            list.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                    if (!recyclerView.canScrollVertically(1)) showJump(false);
                }
            });
            column.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            root.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            empty = new FrameLayout(c);
            empty.setVisibility(View.GONE);
            FrameLayout.LayoutParams emptyLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            emptyLp.topMargin = AfkUi.dp(c, 64);
            root.addView(empty, emptyLp);

            jump = CloudUi.text(c, getString(R.string.projects_afk_ui_console_new), 13, R.color.projects_gold_300, true);
            jump.setBackgroundResource(R.drawable.projects_chat_jump_bg);
            jump.setGravity(Gravity.CENTER);
            jump.setPadding(AfkUi.dp(c, 14), 0, AfkUi.dp(c, 14), 0);
            jump.setElevation(AfkUi.dp(c, 4));
            jump.setVisibility(View.GONE);
            Press.attach(jump, Press.Style.BUTTON);
            jump.setOnClickListener(v -> {
                Haptics.tick(v);
                showJump(false);
                list.smoothScrollToPosition(Math.max(0, lines.getItemCount() - 1));
            });
            FrameLayout.LayoutParams jumpLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, AfkUi.dp(c, 36));
            jumpLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            jumpLp.bottomMargin = AfkUi.dp(c, 10);
            root.addView(jump, jumpLp);
        }

        void bind(String accountId) {
            boolean other = !accountId.equals(id);
            id = accountId;
            mAction = null;
            mEmptyKey = "";
            bindHead();
            if (other) replace();
        }

        void bindHead() {
            if (id == null) return;
            Context c = root.getContext();
            AfkAccount a = mAfk.account(id);
            AfkStatus s = status(id);
            state.set(s, System.currentTimeMillis());
            address.setText(a == null ? "" : a.address());
            AfkLook.Action action = AfkLook.action(s);
            if (action != mAction) {
                mAction = action;
                main.setText(AfkFragment.actionWords(c, action));
                // The console's own copy of the action is the page's gold while the account waits for it (the
                // desktop's console); while it runs, "Durdur" stays outlined and the composer is the page's action.
                CloudUi.styleButton(c, main, action == AfkLook.Action.CONNECT);
                main.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
            }
            main.setContentDescription(AfkFragment.actionWords(c, action) + ": " + (a == null ? "" : a.name));
            emptyState();
        }

        void replace() {
            List<AfkLine> all = mLines.get(id);
            lines.set(all == null ? new ArrayList<AfkLine>() : all);
            lines.notifyDataSetChanged();
            if (lines.getItemCount() > 0) list.scrollToPosition(lines.getItemCount() - 1);
            showJump(false);
            emptyState();
        }

        /** A line came ({@code removed} old ones went from the top): the reader's place stays where it is. */
        void appended(int removed) {
            boolean atBottom = !list.canScrollVertically(1);
            List<AfkLine> all = mLines.get(id);
            lines.set(all == null ? new ArrayList<AfkLine>() : all);
            // In the old list's numbering: the new line at its end, then the oldest ones from the top.
            lines.notifyItemInserted(lines.getItemCount() - 1 + removed);
            if (removed > 0) lines.notifyItemRangeRemoved(0, removed);
            if (atBottom) list.scrollToPosition(Math.max(0, lines.getItemCount() - 1));
            else showJump(true);
            emptyState();
        }

        void toBottom() {
            if (lines.getItemCount() > 0) list.scrollToPosition(lines.getItemCount() - 1);
            showJump(false);
        }

        /** "Henüz mesaj yok." with the scroll sprite, or "Hesap bağlı değil" while it is not running. */
        private void emptyState() {
            boolean none = lines.getItemCount() == 0;
            boolean running = id != null && status(id).state.running();
            String key = none ? (running ? "running" : "stopped") : "";
            if (key.equals(mEmptyKey)) return;
            mEmptyKey = key;
            empty.removeAllViews();
            if (!none) {
                empty.setVisibility(View.GONE);
                return;
            }
            Context c = root.getContext();
            empty.addView(CloudUi.empty(c, PixelSprites.Sprite.LOGS, getString(running ? R.string.projects_afk_ui_console_empty
                    : R.string.projects_afk_ui_console_offline)));
            empty.setVisibility(View.VISIBLE);
        }

        /** "Yeni mesajlar" rises 6 dp in 200 ms and leaves in 140 ms (the desktop's pill); without animations it just shows. */
        private void showJump(boolean show) {
            if (show == mJumpShown) return;
            mJumpShown = show;
            jump.animate().cancel();
            boolean motion = Motion.enabled(jump.getContext());
            float rise = AfkUi.dp(jump.getContext(), 6);
            if (show) {
                jump.setVisibility(View.VISIBLE);
                if (!motion) {
                    jump.setAlpha(1f);
                    jump.setTranslationY(0f);
                    return;
                }
                jump.setAlpha(0f);
                jump.setTranslationY(rise);
                jump.animate().alpha(1f).translationY(0f).setDuration(200).setInterpolator(Motion.EASE_OUT).start();
            } else {
                if (!motion || jump.getVisibility() != View.VISIBLE) {
                    jump.setVisibility(View.GONE);
                    return;
                }
                jump.animate().alpha(0f).translationY(rise).setDuration(140).setInterpolator(Motion.EASE_IN)
                        .withEndAction(() -> {
                            if (!mJumpShown) jump.setVisibility(View.GONE);
                        }).start();
            }
        }
    }

    // ---- lines ----

    private static final SimpleDateFormat CLOCK = new SimpleDateFormat("HH:mm", Locale.ROOT);

    private final class Lines extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private List<AfkLine> mItems = new ArrayList<>();

        void set(List<AfkLine> items) {
            mItems = items;
        }

        @Override
        public int getItemCount() {
            return mItems.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context c = parent.getContext();
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(AfkUi.dp(c, 4), AfkUi.dp(c, 3), AfkUi.dp(c, 4), AfkUi.dp(c, 3));
            TextView time = CloudUi.time(c, 12, R.color.projects_ash_400);
            time.setPadding(0, AfkUi.dp(c, 3), 0, 0);
            row.addView(time, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView text = CloudUi.text(c, "", 14, R.color.projects_bone_100, false);
            text.setLineSpacing(0, 1.12f);
            text.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE);
            text.setTextIsSelectable(false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMarginStart(AfkUi.dp(c, 10));
            row.addView(text, lp);
            row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerView.ViewHolder(row) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            AfkLine line = mItems.get(position);
            Context c = holder.itemView.getContext();
            ViewGroup row = (ViewGroup) holder.itemView;
            TextView time = (TextView) row.getChildAt(0);
            TextView text = (TextView) row.getChildAt(1);
            time.setText(CLOCK.format(new Date(line.time)));
            text.setTextColor(AfkUi.color(c, toneColor(line.kind)));
            text.setText(styled(line));
            // A line with the account's name (someone talking to it, a bot check): a quiet plate, never gold.
            boolean plate = line.mentioned && line.kind != AfkLine.Kind.OWN;
            row.setBackground(plate ? plate(c) : null);
        }
    }

    /** The kind's colour (the phone's 7/24 Bulut console: chat bone, private gold-200, own gold-300, the app's quieter). */
    private static int toneColor(AfkLine.Kind kind) {
        switch (kind) {
            case PRIVATE: return R.color.projects_gold_200;
            case OWN: return R.color.projects_gold_300;
            case SYSTEM: return R.color.projects_ash_300;
            case NOTICE: return R.color.projects_ash_400;
            case CHAT:
            default:
                return R.color.projects_bone_100;
        }
    }

    /** The line with the game's colours and formats ({@link AfkLineStyle}); text outside the runs keeps the kind's. */
    private static CharSequence styled(AfkLine line) {
        List<AfkLineStyle.Run> runs = AfkLineStyle.runs(line);
        if (runs.isEmpty()) return line.text;
        SpannableString s = new SpannableString(line.text);
        for (AfkLineStyle.Run r : runs) {
            if (r.color != AfkLine.NO_COLOR) s.setSpan(new ForegroundColorSpan(0xff000000 | r.color), r.start, r.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            int style = (r.bold ? Typeface.BOLD : 0) | (r.italic ? Typeface.ITALIC : 0);
            if (style != 0) s.setSpan(new StyleSpan(style), r.start, r.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (r.underlined) s.setSpan(new UnderlineSpan(), r.start, r.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (r.strikethrough) s.setSpan(new StrikethroughSpan(), r.start, r.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return s;
    }

    private static GradientDrawable plate(Context c) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(AfkUi.dp(c, 6));
        bg.setColor(AfkUi.color(c, R.color.projects_obsidian_800));
        return bg;
    }
}
