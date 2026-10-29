package gg.projects.mobile.cloud.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.R;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import gg.projects.mobile.cloud.CloudModel;
import gg.projects.mobile.cloud.CloudProblem;
import gg.projects.mobile.cloud.CloudService;
import gg.projects.mobile.cloud.CloudWords;
import gg.projects.mobile.cloud.PlusConfig;
import gg.projects.mobile.cloud.PlusModel;
import gg.projects.mobile.cloud.plus.ActivityRows;
import gg.projects.mobile.mods.Motion;
import gg.projects.mobile.ui.AppDates;
import gg.projects.mobile.ui.Press;

/**
 * "Etkinlik" (PLUS-ACTIVITY: the site's and the launcher's one place for what ProBot AI did; it was Akış, Kayıtlar and
 * Mesajlar). Over the list, while something waits for the player, "Seni bekleyenler" with its count (that screen holds
 * the decisions and the messages to answer); the filters (Tümü, Anlaşmalar, Yazışmalar, Retler), a search by player or
 * item, and today's two figures. Under day headings a player's day is one row (how it went, its time); a tap opens it in
 * place, its steps fading in under it (nothing grows by animation), with "Cevap yaz" while the machine keeps a
 * conversation with them. An event without a player is the log's own line. Live from the machine; what this phone saw
 * stays 7 days and shows while the machine is away; older trades are in Özet. Counterparts' texts are masked by the
 * service; an unsuitable one stays folded until "Göster". Each part draws on its own, so the search keeps its focus.
 */
public final class PlusActivityFragment extends CloudScreen implements CloudService.LiveListener {
    public static final String TAG = "ProjectsPlusActivityFragment";
    private static final String[] FILTERS = {null, ActivityRows.DEALS, ActivityRows.MESSAGES, ActivityRows.REFUSALS};
    /** Entries drawn at once (the phone keeps up to 3.000; each live batch draws the list again). */
    private static final int AUDIT_PAGE = 100;

    private int mFilter;
    private String mQuery = "";
    private int mDrawLimit = AUDIT_PAGE;
    /** The log as shown: what the phone kept and what the machine sent, newest first, each entry once. */
    private final List<PlusModel.AuditEntry> mAudit = new ArrayList<>();
    private final Set<Long> mSeen = new HashSet<>();
    private boolean mKeptRead;
    private boolean mAuditLoaded;
    /** {@code feature_off}, {@code host_offline}, {@code rate_limited} or another failure of the last page; null when it came. */
    @Nullable
    private String mAuditProblem;
    private boolean mAuditMore;
    private boolean mAuditLoading;
    /** Rows opened in place, by day and player; they stay open while the screen is up. */
    private final Set<String> mOpen = new HashSet<>();
    /** Folded entries the player opened ("Göster"), by sequence. */
    private final Set<Long> mShownHidden = new HashSet<>();
    /** The conversations the machine keeps (who wrote in the last 24 hours): where "Cevap yaz" goes. */
    private final List<CloudModel.InboxThread> mThreads = new ArrayList<>();
    @Nullable
    private CloudService.LiveHandle mLive;

    @Nullable
    private LinearLayout mWaitingPart;
    @Nullable
    private LinearLayout mControls;
    @Nullable
    private LinearLayout mSummaryPart;
    @Nullable
    private LinearLayout mListPart;
    /** The list after the search's letters stop for a moment (a redraw per letter stuttered the typing). */
    private final Runnable mSearchDraw = this::drawList;

    @Override
    protected void created(View view) {
        mHeader.setTitle(getString(R.string.projects_plus_activity));
    }

    @Override
    protected void started() {
        CloudModel.Account a = account();
        if (a == null) return;
        if (mLive == null) mLive = mService.watch(a.id, true, this);
        load(false);
        loadThreads();
        ensureItemNames();
    }

    @Override
    protected void namesArrived() {
        drawList();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mLive != null) {
            mLive.close();
            mLive = null;
        }
    }

    @Nullable
    private PlusModel.Server server() {
        String id = accountId();
        CloudModel.PlusDoc doc = id == null ? null : mService.lastPlus(id);
        return doc == null ? null : doc.server;
    }

    private void load(final boolean more) {
        final CloudModel.Account a = account();
        if (a == null) return;
        if (!mKeptRead) {
            mKeptRead = true;
            mService.keptAudit(a.id, new CloudService.Result<List<PlusModel.AuditEntry>>() {
                @Override
                public void ok(List<PlusModel.AuditEntry> value) {
                    if (merge(value) && alive()) drawList();
                }

                @Override
                public void failed(CloudProblem problem) {}
            });
        }
        if ((mAuditLoaded && !more) || mAuditLoading) return;
        mAuditLoading = true;
        Long before = more && !mAudit.isEmpty() ? mAudit.get(mAudit.size() - 1).s : null;
        // The filters work on what is here (the site's rule): the page is the whole log's.
        mService.audit(a.id, before, null, new CloudService.Result<PlusModel.AuditPage>() {
            @Override
            public void ok(PlusModel.AuditPage page) {
                mAuditLoading = false;
                merge(page.items);
                mAuditMore = page.more;
                mAuditLoaded = true;
                mAuditProblem = null;
                if (alive()) drawList();
            }

            @Override
            public void failed(CloudProblem problem) {
                mAuditLoading = false;
                mAuditLoaded = true;
                mAuditMore = false;
                mAuditProblem = problem.is("feature_off") ? "feature_off" : problem.is("rate_limited") ? "rate_limited"
                        : problem.kind == CloudProblem.Kind.HOST_OFFLINE ? "host_offline" : "failed";
                if (alive()) drawList();
            }
        });
    }

    /** The conversations (for "Cevap yaz"); the service counts what waits from the same read. */
    private void loadThreads() {
        CloudModel.Account a = account();
        if (a == null || !mService.plusFeature("inbox")) return;
        mService.inbox(a.id, new CloudService.Result<gg.projects.mobile.cloud.CloudApi.InboxList>() {
            @Override
            public void ok(gg.projects.mobile.cloud.CloudApi.InboxList value) {
                mThreads.clear();
                mThreads.addAll(value.threads);
                if (alive()) drawList();
            }

            @Override
            public void failed(CloudProblem problem) {}
        });
    }

    /** Adds entries once each (by their sequence), newest first; true when something was new. */
    private boolean merge(List<PlusModel.AuditEntry> entries) {
        boolean changed = false;
        for (PlusModel.AuditEntry e : entries) {
            if (mSeen.add(e.s)) {
                mAudit.add(e);
                changed = true;
            }
        }
        if (changed) Collections.sort(mAudit, (x, y) -> Long.compare(y.s, x.s));
        return changed;
    }

    @Override
    protected void render() {
        CloudModel.Account a = account();
        if (a != null) mHeader.setCaption(a.name);
        if (getView() == null || mContent == null) return;
        if (mListPart == null || mListPart.getParent() != mContent) build();
        drawWaiting();
    }

    private static LinearLayout column(Context c) {
        LinearLayout part = new LinearLayout(c);
        part.setOrientation(LinearLayout.VERTICAL);
        part.setLayoutParams(CloudUi.matchWrap());
        return part;
    }

    /** The screen's parts, once a view: what waits, the filters and the search, today's figures, the list. */
    private void build() {
        LinearLayout content = clear();
        if (content == null) return;
        Context c = ctx();
        mWaitingPart = column(c);
        mControls = column(c);
        mSummaryPart = column(c);
        mListPart = column(c);
        content.addView(mWaitingPart);
        content.addView(mControls);
        content.addView(mSummaryPart);
        content.addView(mListPart);
        mControls.addView(CloudUi.segmented(c, new CharSequence[]{getString(R.string.projects_audit_filter_all),
                getString(R.string.projects_audit_filter_deals), getString(R.string.projects_audit_filter_messages),
                getString(R.string.projects_audit_filter_warnings)}, mFilter, i -> {
            mFilter = i;
            mDrawLimit = AUDIT_PAGE;
            drawList();
        }));
        LinearLayout.LayoutParams searchLp = CloudUi.matchWrap();
        searchLp.topMargin = CloudUi.dp(c, 10);
        mControls.addView(search(c), searchLp);
        mControls.setVisibility(View.GONE);
        mSummaryPart.setPadding(CloudUi.dp(c, 4), CloudUi.dp(c, 12), 0, 0);
        drawWaiting();
        drawList();
    }

    /** "Oyuncu ya da eşya ara": the list follows each letter; the field itself is never drawn again. */
    private EditText search(Context c) {
        EditText search = new EditText(c);
        search.setBackgroundResource(R.drawable.projects_input);
        search.setHint(getString(R.string.projects_audit_search));
        search.setHintTextColor(CloudUi.color(c, R.color.projects_ash_400));
        search.setTextColor(CloudUi.color(c, R.color.projects_bone_100));
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        search.setSingleLine(true);
        search.setMinHeight(CloudUi.dp(c, 48));
        search.setPadding(CloudUi.dp(c, 14), 0, CloudUi.dp(c, 14), 0);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setCompoundDrawablesRelative(CloudUi.tinted(c, R.drawable.projects_ic_magnifying_glass, R.color.projects_ash_400, 18), null, null, null);
        search.setCompoundDrawablePadding(CloudUi.dp(c, 8));
        search.setText(mQuery);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (s.toString().equals(mQuery)) return;
                mQuery = s.toString();
                LinearLayout part = mListPart;
                if (part == null) return;
                part.removeCallbacks(mSearchDraw);
                part.postDelayed(mSearchDraw, 150);
            }
        });
        return search;
    }

    /** "Seni bekleyenler" over the list while a decision or a message waits for the player (its count, no gold). */
    private void drawWaiting() {
        LinearLayout part = mWaitingPart;
        final CloudModel.Account a = account();
        if (part == null || a == null) return;
        int waiting = (a.ai == null ? 0 : a.ai.pendingApprovals) + mService.waiting(a.id);
        String shape = String.valueOf(waiting);
        if (shape.equals(part.getTag())) return;
        part.setTag(shape);
        part.removeAllViews();
        if (waiting <= 0) return;
        Context c = ctx();
        LinearLayout group = CloudUi.group(c);
        CloudUi.Row row = CloudUi.row(c, R.drawable.projects_ic_check_circle, getString(R.string.projects_plus_approvals),
                v -> openFor(PlusApprovalsFragment.class, a.id));
        row.setCount(waiting);
        group.addView(row.view);
        part.addView(group);
        part.addView(CloudUi.gap(c, 14));
    }

    /** Today's figures (the site's slim line): trades done today, and what was spent against the total limit. */
    private void drawSummary(boolean show) {
        LinearLayout part = mSummaryPart;
        if (part == null) return;
        part.removeAllViews();
        if (!show) return;
        Context c = ctx();
        int trades = ActivityRows.tradesSince(mAudit, startOfDay(mService.nowMs()));
        part.addView(figure(c, R.drawable.projects_ic_hand_coins, trades > 0
                ? getResources().getQuantityString(R.plurals.projects_audit_trades_today, trades, trades)
                : getString(R.string.projects_audit_no_trades_today)));
        String id = accountId();
        CloudModel.PlusDoc doc = id == null ? null : mService.lastPlus(id);
        Long total = doc == null ? null : new PlusConfig(doc.config).limit("totalBudget");
        if (doc != null && doc.spent != null && total != null && total > 0) {
            part.addView(figure(c, R.drawable.projects_ic_coins, getString(R.string.projects_audit_spent_of,
                    CloudWords.money(c, doc.spent.total), PlusWords.money(c, total, doc.server))));
        }
    }

    private static TextView figure(Context c, int icon, CharSequence words) {
        TextView line = CloudUi.text(c, words, 13.5f, R.color.projects_ash_300, false);
        line.setCompoundDrawablesRelative(CloudUi.tinted(c, icon, R.color.projects_ash_400, 16), null, null, null);
        line.setCompoundDrawablePadding(CloudUi.dp(c, 8));
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, CloudUi.dp(c, 2), 0, CloudUi.dp(c, 2));
        return line;
    }

    private static long startOfDay(long nowMs) {
        Calendar day = Calendar.getInstance();
        day.setTimeInMillis(nowMs);
        day.set(Calendar.HOUR_OF_DAY, 0);
        day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0);
        day.set(Calendar.MILLISECOND, 0);
        return day.getTimeInMillis();
    }

    private View more(Context c, int words, View.OnClickListener click) {
        android.widget.Button button = CloudUi.textButton(c, getString(words), click);
        LinearLayout box = new LinearLayout(c);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.addView(button);
        box.setLayoutParams(CloudUi.matchWrap());
        return box;
    }

    private void drawList() {
        LinearLayout part = mListPart;
        LinearLayout controls = mControls;
        if (part == null || controls == null || !alive()) return;
        part.removeAllViews();
        Context c = ctx();
        if ("feature_off".equals(mAuditProblem)) {
            controls.setVisibility(View.GONE);
            drawSummary(false);
            part.addView(CloudUi.empty(c, gg.projects.mobile.ui.PixelSprites.Sprite.LOGS, getString(R.string.projects_audit_off)));
            return;
        }
        boolean away = "host_offline".equals(mAuditProblem) || "failed".equals(mAuditProblem);
        boolean limited = "rate_limited".equals(mAuditProblem);
        // The filters and the search only once there is something to filter (the site's rule).
        controls.setVisibility(mAudit.isEmpty() ? View.GONE : View.VISIBLE);
        drawSummary(!mAudit.isEmpty());
        if (away || limited) {
            part.addView(CloudUi.notice(c, R.drawable.projects_ic_warning_circle, R.color.projects_ash_300, getString(limited
                    ? R.string.projects_audit_limited : mAudit.isEmpty() ? R.string.projects_audit_offline_empty : R.string.projects_audit_offline)));
        }
        if (!mAuditLoaded && mAudit.isEmpty()) {
            part.addView(CloudUi.loading(c));
            return;
        }
        if (mAudit.isEmpty()) {
            // ProBot AI waits for its first "Başlat": nothing has happened yet (the site's line).
            CloudModel.Account a = account();
            boolean notStarted = a != null && a.ai != null && a.ai.waitsForStart();
            if (!away && !limited) part.addView(CloudUi.empty(c, gg.projects.mobile.ui.PixelSprites.Sprite.AFK,
                    getString(notStarted ? R.string.projects_audit_not_started : R.string.projects_audit_empty)));
            return;
        }
        List<PlusModel.AuditEntry> shown = new ArrayList<>();
        for (PlusModel.AuditEntry e : mAudit) if (ActivityRows.in(FILTERS[mFilter], e)) shown.add(e);
        if (shown.isEmpty()) {
            part.addView(CloudUi.note(c, getString(R.string.projects_audit_none_here)));
        } else {
            // By day ("Bugün", "Dün", a date), then a player's day in one row.
            long now = mService.nowMs();
            int drawn = Math.min(shown.size(), mDrawLimit);
            int rows = 0;
            String heading = null;
            List<PlusModel.AuditEntry> day = new ArrayList<>();
            for (int i = 0; i < drawn; i++) {
                PlusModel.AuditEntry e = shown.get(i);
                String h = dayHeading(c, e.t, now);
                if (heading != null && !h.equals(heading)) {
                    rows += drawDay(c, part, heading, day);
                    day = new ArrayList<>();
                }
                heading = h;
                day.add(e);
            }
            if (heading != null) rows += drawDay(c, part, heading, day);
            if (rows == 0) part.addView(CloudUi.note(c, getString(R.string.projects_audit_no_match)));
        }
        // Earlier days: first the ones the phone holds, then the service's next page.
        if (shown.size() > mDrawLimit) {
            part.addView(more(c, R.string.projects_audit_older, v -> {
                mDrawLimit += AUDIT_PAGE;
                drawList();
            }));
        } else if (mAuditMore && !away && !limited) {
            final int have = shown.size();
            part.addView(more(c, R.string.projects_audit_older, v -> {
                mDrawLimit = have + AUDIT_PAGE;
                load(true);
            }));
        }
        part.addView(CloudUi.note(c, getString(R.string.projects_audit_kept)));
        part.addView(CloudUi.note(c, getString(R.string.projects_ai_note)));
    }

    private static String dayHeading(Context c, long ms, long nowMs) {
        Calendar day = Calendar.getInstance();
        day.setTimeInMillis(ms);
        Calendar today = Calendar.getInstance();
        today.setTimeInMillis(nowMs);
        long a = day.get(Calendar.YEAR) * 400L + day.get(Calendar.DAY_OF_YEAR);
        long b = today.get(Calendar.YEAR) * 400L + today.get(Calendar.DAY_OF_YEAR);
        if (a == b) return c.getString(R.string.projects_audit_today);
        if (b - a == 1) return c.getString(R.string.projects_audit_yesterday);
        return AppDates.day(c, ms, nowMs);
    }

    /** One day: its heading over its rows (a player's day together) that the search finds; how many it drew. */
    private int drawDay(Context c, LinearLayout part, String heading, List<PlusModel.AuditEntry> entries) {
        List<ActivityRows.Group> rows = new ArrayList<>();
        for (ActivityRows.Group g : ActivityRows.groups(entries)) {
            if (ActivityRows.found(g, mQuery, this::itemName, CloudWords.locale(c))) rows.add(g);
        }
        if (rows.isEmpty()) return 0;
        part.addView(CloudUi.section(c, heading));
        LinearLayout group = CloudUi.group(c);
        for (int i = 0; i < rows.size(); i++) {
            ActivityRows.Group g = rows.get(i);
            if (i > 0) group.addView(CloudUi.textDivider(c));
            group.addView(g.who == null ? entryRow(c, g.newest()) : playerRow(c, heading + "|" + g.key, g));
        }
        part.addView(group);
        return rows.size();
    }

    /** The row's tile: the day's item, else the mark of how it went (a quiet person when nothing marks it). */
    private View tile(Context c, @Nullable String item, String kind) {
        if (item != null) return CloudItemArt.tile(c, item, 36);
        int[] icon = PlusWords.auditIcon(kind);
        FrameLayout box = new FrameLayout(c);
        box.setBackgroundResource(R.drawable.projects_menu_group);
        ImageView glyph = new ImageView(c);
        glyph.setImageResource(icon[0] != 0 ? icon[0] : R.drawable.projects_ic_user_circle);
        glyph.setImageTintList(ColorStateList.valueOf(CloudUi.color(c, icon[0] != 0 ? icon[1] : R.color.projects_ash_400)));
        glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        box.addView(glyph, new FrameLayout.LayoutParams(CloudUi.dp(c, 18), CloudUi.dp(c, 18), Gravity.CENTER));
        return box;
    }

    /** A player's day: the tile, who, how it went (a message's own words after it) and when; opens in place. */
    private View playerRow(final Context c, final String key, final ActivityRows.Group g) {
        final LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setMinimumHeight(CloudUi.dp(c, 64));
        head.setPaddingRelative(CloudUi.dp(c, 12), CloudUi.dp(c, 10), CloudUi.dp(c, 12), CloudUi.dp(c, 10));
        head.setBackgroundResource(R.drawable.projects_row_ripple);
        PlusModel.AuditEntry outcome = ActivityRows.outcome(g);
        head.addView(tile(c, g.item(), outcome.k), new LinearLayout.LayoutParams(CloudUi.dp(c, 36), CloudUi.dp(c, 36)));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView who = CloudUi.text(c, g.who, 15, R.color.projects_bone_100, true);
        who.setSingleLine(true);
        who.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(who);
        String said = outcome.x != null && !outcome.x.isEmpty() && !outcome.h ? " \"" + outcome.x + "\"" : "";
        final String line = PlusWords.auditLine(c, outcome, server(), this::itemName) + said;
        TextView words = CloudUi.text(c, line, 13.5f, R.color.projects_ash_300, false);
        words.setMaxLines(2);
        words.setEllipsize(TextUtils.TruncateAt.END);
        words.setLineSpacing(0, 1.1f);
        texts.addView(words);
        LinearLayout.LayoutParams textsLp = CloudUi.weight(1f);
        textsLp.setMarginStart(CloudUi.dp(c, 12));
        head.addView(texts, textsLp);
        final TextView time = CloudUi.time(c, 13, R.color.projects_ash_400);
        time.setText(AppDates.time(g.newest().t));
        LinearLayout.LayoutParams timeLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        timeLp.setMarginStart(CloudUi.dp(c, 8));
        head.addView(time, timeLp);
        // One step says all in the row itself: it opens only to "Cevap yaz", and without it the row stays still (the
        // site's r2: no step that repeats the row).
        boolean single = g.entries.size() == 1;
        boolean answerable = g.who != null && keepsConversation(g.who);
        final ImageView caret = new ImageView(c);
        caret.setImageResource(R.drawable.projects_ic_caret_down_bold);
        caret.setImageTintList(ColorStateList.valueOf(CloudUi.color(c, R.color.projects_ash_400)));
        caret.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams caretLp = new LinearLayout.LayoutParams(CloudUi.dp(c, 16), CloudUi.dp(c, 16));
        caretLp.setMarginStart(CloudUi.dp(c, 8));
        head.addView(caret, caretLp);
        box.addView(head);
        if (single && !answerable) {
            caret.setVisibility(View.INVISIBLE);
            head.setBackground(null);
            head.setContentDescription(g.who + ", " + line + ", " + time.getText());
            head.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            return box;
        }
        boolean open = mOpen.contains(key);
        caret.setRotation(open ? 180f : 0f);
        if (open) box.addView(body(c, g));
        final String spoken = g.who + ", " + line + ", " + time.getText();
        head.setContentDescription(spoken + ", " + getString(open ? R.string.projects_answers_expanded : R.string.projects_answers_collapsed));
        head.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        head.setOnClickListener(v -> {
            boolean now = toggle(key, box, caret, g);
            head.setContentDescription(spoken + ", " + getString(now ? R.string.projects_answers_expanded : R.string.projects_answers_collapsed));
        });
        Press.attach(head, Press.Style.ROW);
        return box;
    }

    /**
     * Opens or folds a player's day in place: the steps fade in under the row (also under reduced motion: a fade only),
     * the caret turns (at once under reduced motion); folding is at once. Returns whether it is open now.
     */
    private boolean toggle(String key, LinearLayout box, ImageView caret, ActivityRows.Group g) {
        Context c = ctx();
        boolean motion = Motion.enabled(c);
        caret.animate().cancel();
        if (mOpen.remove(key)) {
            while (box.getChildCount() > 1) box.removeViewAt(1);
            if (motion) caret.animate().rotation(0f).setDuration(180).setInterpolator(Motion.EASE_OUT).start();
            else caret.setRotation(0f);
            return false;
        }
        mOpen.add(key);
        View body = body(c, g);
        body.setAlpha(0f);
        box.addView(body);
        body.animate().alpha(1f).setDuration(motion ? 200 : 120).setInterpolator(Motion.EASE_OUT).start();
        if (motion) caret.animate().rotation(180f).setDuration(180).setInterpolator(Motion.EASE_OUT).start();
        else caret.setRotation(180f);
        return true;
    }

    /** A player's day opened: every step in order (oldest first), then "Cevap yaz" when the machine keeps the conversation. */
    private View body(Context c, final ActivityRows.Group g) {
        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPaddingRelative(CloudUi.dp(c, 36), 0, 0, CloudUi.dp(c, 6));
        if (g.entries.size() > 1) {
            List<PlusModel.AuditEntry> steps = new ArrayList<>(g.entries);
            Collections.reverse(steps);
            for (PlusModel.AuditEntry e : steps) body.addView(entryRow(c, e));
        }
        if (g.who != null && keepsConversation(g.who)) {
            android.widget.Button reply = CloudUi.textButton(c, getString(R.string.projects_audit_answer), v -> openThread(g.who));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginStart(CloudUi.dp(c, 4));
            body.addView(reply, lp);
        }
        return body;
    }

    /** The machine keeps a conversation with who wrote in the last 24 hours, and only while it can be reached. */
    private boolean keepsConversation(String name) {
        if (!mService.plusFeature("inbox") || mAuditProblem != null) return false;
        for (CloudModel.InboxThread t : mThreads) if (t.name.equalsIgnoreCase(name)) return true;
        return false;
    }

    private void openThread(String name) {
        String id = accountId();
        if (id == null) return;
        Bundle args = args(id);
        args.putString(PlusThreadFragment.ARG_NAME, name);
        open(PlusThreadFragment.class, args);
    }

    /** One entry: the time in its column, the line (an icon only where the kind means something), a message's text under it. */
    private View entryRow(final Context c, final PlusModel.AuditEntry e) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(CloudUi.dp(c, 16), CloudUi.dp(c, 12), CloudUi.dp(c, 16), CloudUi.dp(c, 12));
        TextView time = CloudUi.time(c, 13, R.color.projects_ash_400);
        time.setText(AppDates.time(e.t));
        row.addView(time, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout first = new LinearLayout(c);
        first.setOrientation(LinearLayout.HORIZONTAL);
        first.setGravity(Gravity.TOP);
        int[] icon = PlusWords.auditIcon(e.k);
        ImageView glyph = new ImageView(c);
        if (icon[0] != 0) {
            glyph.setImageResource(icon[0]);
            glyph.setImageTintList(ColorStateList.valueOf(CloudUi.color(c, icon[1])));
        }
        glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams glyphLp = new LinearLayout.LayoutParams(CloudUi.dp(c, 16), CloudUi.dp(c, 16));
        glyphLp.topMargin = CloudUi.dp(c, 2);
        glyphLp.setMarginEnd(CloudUi.dp(c, 8));
        first.addView(glyph, glyphLp);
        String line = PlusWords.auditLine(c, e, server(), this::itemName);
        TextView words = CloudUi.text(c, line, 14.5f, R.color.projects_bone_100, false);
        words.setLineSpacing(0, 1.12f);
        first.addView(words, CloudUi.weight(1f));
        texts.addView(first);
        boolean folded = e.h && !mShownHidden.contains(e.s);
        if (e.x != null && !e.x.isEmpty()) {
            final TextView said = CloudUi.text(c, folded ? getString(R.string.projects_audit_hidden) : e.x, 14,
                    R.color.projects_ash_300, false);
            said.setLineSpacing(0, 1.12f);
            said.setPadding(0, CloudUi.dp(c, 4), 0, 0);
            texts.addView(said);
            if (folded) {
                android.widget.Button show = CloudUi.textButton(c, getString(R.string.projects_audit_show), v -> {
                    mShownHidden.add(e.s);
                    drawList();
                });
                show.setPadding(0, 0, CloudUi.dp(c, 12), 0);
                texts.addView(show, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
        }
        LinearLayout.LayoutParams textsLp = CloudUi.weight(1f);
        textsLp.setMarginStart(CloudUi.dp(c, 12));
        row.addView(texts, textsLp);
        row.setContentDescription(time.getText() + ", " + line + (e.x != null && !folded ? ", " + e.x : ""));
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        row.setLayoutParams(CloudUi.matchWrap());
        return row;
    }

    // ---- the live socket ----

    @Override
    public void liveState(CloudService.Live state) {}

    @Override
    public void console(List<CloudModel.ConsoleLine> lines, int skipped, boolean snapshot) {}

    @Override
    public void audit(List<PlusModel.AuditEntry> items) {
        if (merge(items) && alive()) drawList();
    }

    @Override
    public void approval(CloudModel.Approval approval) {}

    @Override
    public void hostOnline(boolean online) {
        // The machine is back: the page the phone could not read comes now, and the conversations.
        if (online && "host_offline".equals(mAuditProblem)) {
            mAuditLoaded = false;
            load(false);
            loadThreads();
        }
    }
}
