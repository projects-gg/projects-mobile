package gg.projects.mobile.afk.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.R;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import gg.projects.mobile.afk.api.AfkAccount;
import gg.projects.mobile.afk.api.AfkAlert;
import gg.projects.mobile.afk.api.AfkEnd;
import gg.projects.mobile.afk.api.AfkLimits;
import gg.projects.mobile.afk.api.AfkStartResult;
import gg.projects.mobile.afk.api.AfkState;
import gg.projects.mobile.afk.api.AfkStatus;
import gg.projects.mobile.afk.api.AfkTexts;
import gg.projects.mobile.afk.api.PhoneAfk;
import gg.projects.mobile.cloud.ui.CloudUi;
import gg.projects.mobile.social.ui.CrewViews;
import gg.projects.mobile.ui.CardMotion;
import gg.projects.mobile.ui.ConfirmDialog;
import gg.projects.mobile.ui.Haptics;
import gg.projects.mobile.ui.PixelSprites;
import gg.projects.mobile.ui.Press;
import gg.projects.mobile.ui.ProjectsUi;

/**
 * "AFK hesapları" (PHONE-AFK; the desktop's AFK page, {@code features/afk/afk-page.tsx}): the phone's AFK accounts in
 * one rounded group under "Bu telefonda" and how many are in the game; each row the account's head, name, state line
 * (the server bars and the desktop's words), its server and its main action ("Bağla", "Durdur", "Şifreyi düzelt",
 * "Girişi düzelt"); a tap opens its console, a hold its menu. "Hepsini bağla" never takes an account the server
 * refused; at most {@link AfkLimits#MAX_RUNNING} run at once. Above the list, once, why AFK ended while nobody stopped it
 * (the phone, the memory) with the setting to fix and one calm line about 7/24 Bulut; under it the phone's own
 * settings AFK needs (the battery, notifications). The rows keep their views, so a change glides instead of
 * blinking; words tick only while they change.
 */
public final class AfkFragment extends AfkScreen {
    public static final String TAG = "ProjectsAfkFragment";

    private final Map<String, AccountRow> mRows = new LinkedHashMap<>();
    private List<AfkStatus> mStatuses = new ArrayList<>();
    private String mShape = "";
    private boolean mBuilt;
    /** This view of the screen has been built once (a later change glides; the first build is simply there). */
    private boolean mViewBuilt;
    @Nullable
    private AlertDialog mMenu;
    @Nullable
    private androidx.appcompat.app.AlertDialog mConfirm;
    @Nullable
    private TextView mSection;
    @Nullable
    private TextView mCount;
    @Nullable
    private LinearLayout mGroup;
    @Nullable
    private View mAddDivider;
    @Nullable
    private LinearLayout mBulk;
    @Nullable
    private TextView mPhoneSection;
    @Nullable
    private LinearLayout mPhoneGroup;
    @Nullable
    private CloudUi.Row mAddRow;
    @Nullable
    private Button mConnectAll;
    @Nullable
    private Button mStopAll;
    @Nullable
    private TextView mLimitNote;
    @Nullable
    private CloudUi.Row mBatteryRow;
    @Nullable
    private CloudUi.Row mNotifyRow;
    private final Runnable mTick = this::update;

    @Override
    protected void created(View view, @Nullable Bundle savedInstanceState) {
        mHeader.setTitle(getString(R.string.projects_afk_ui_title));
    }

    /** The view goes (another screen on top, a rotation): its blocks go with it, the next view builds its own. */
    @Override
    public void onDestroyView() {
        if (mMenu != null) mMenu.dismiss();
        if (mConfirm != null) mConfirm.dismiss();
        mMenu = null;
        mConfirm = null;
        mRows.clear();
        mSection = null;
        mCount = null;
        mGroup = null;
        mAddDivider = null;
        mAddRow = null;
        mBulk = null;
        mConnectAll = null;
        mStopAll = null;
        mLimitNote = null;
        mPhoneSection = null;
        mPhoneGroup = null;
        mBatteryRow = null;
        mNotifyRow = null;
        mShape = "";
        mViewBuilt = false;
        super.onDestroyView();
    }

    @Override
    public void onPause() {
        super.onPause();
        View view = getView();
        if (view != null) view.removeCallbacks(mTick);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (alive()) render();
    }

    @Override
    public void onStatuses(List<AfkStatus> all) {
        mStatuses = new ArrayList<>(all);
        if (alive()) render();
    }

    @Override
    public void onStatus(AfkStatus status) {
        for (int i = 0; i < mStatuses.size(); i++) {
            if (mStatuses.get(i).accountId.equals(status.accountId)) {
                mStatuses.set(i, status);
                if (alive()) render();
                return;
            }
        }
        // An account the list does not know yet: read them all again.
        mStatuses = new ArrayList<>(mAfk.statuses());
        if (alive()) render();
    }

    @Override
    public void onAlert(AfkAlert alert) {
        super.onAlert(alert);
        if (alive()) update();
    }

    // ---- building ----

    /** What decides the screen's blocks (the words inside them, the phone's settings included, change in place). */
    private String shape() {
        StringBuilder key = new StringBuilder();
        AfkEnd end = mAfk.lastEnd();
        key.append(end == null ? "-" : end.at + ":" + end.kind).append('|');
        key.append(cloudOffer(end)).append('|');
        for (AfkAccount a : mAfk.accounts()) key.append(a.id).append(',');
        return key.toString();
    }

    private void render() {
        View view = getView();
        if (view == null || mContent == null) return;
        String shape = shape();
        if (!shape.equals(mShape)) {
            // A change of blocks (an account added or removed, a notice): what stays glides, the rest fades.
            if (mViewBuilt) CardMotion.beginLayoutChange(mContent);
            mViewBuilt = true;
            mShape = shape;
            rebuild();
        }
        update();
    }

    private void rebuild() {
        LinearLayout content = mContent;
        if (content == null) return;
        Context c = ctx();
        boolean first = !mBuilt;
        mBuilt = true;
        content.removeAllViews();
        // The screen's one authored moment: the accounts' rows arriving one after another (the desktop's cards);
        // everything else is simply there.
        int entering = 0;

        AfkEnd end = mAfk.lastEnd();
        if (end != null) content.addView(endNotice(c, end));

        List<AfkAccount> accounts = mAfk.accounts();
        if (accounts.isEmpty()) {
            mRows.clear();
            LinearLayout empty = CloudUi.empty(c, PixelSprites.Sprite.AFK, getString(R.string.projects_afk_ui_empty_body));
            TextView title = CloudUi.text(c, getString(R.string.projects_afk_ui_empty_title), 17, R.color.projects_bone_100, true);
            title.setGravity(Gravity.CENTER);
            androidx.core.view.ViewCompat.setAccessibilityHeading(title, true);
            empty.addView(title, 1, CloudUi.matchWrap());
            ((LinearLayout.LayoutParams) title.getLayoutParams()).topMargin = AfkUi.dp(c, 14);
            Button add = CloudUi.button(c, getString(R.string.projects_afk_ui_add), true, v -> addAccount());
            FrameLayout box = new FrameLayout(c);
            FrameLayout.LayoutParams addLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            addLp.gravity = Gravity.CENTER_HORIZONTAL;
            addLp.topMargin = AfkUi.dp(c, 18);
            box.addView(add, addLp);
            empty.addView(box, CloudUi.matchWrap());
            content.addView(empty);
            return;
        }

        // The blocks are made once and put back each time: what stays is the same view, so a change glides
        // (CardMotion) instead of cross-fading identical rows.
        if (mSection == null) makeBlocks(c);
        LinearLayout.LayoutParams sectionLp = CloudUi.matchWrap();
        sectionLp.topMargin = AfkUi.dp(c, end == null ? 8 : 20);
        content.addView(detached(mSection), sectionLp);
        LinearLayout.LayoutParams countLp = CloudUi.matchWrap();
        countLp.setMarginStart(AfkUi.dp(c, 4));
        countLp.topMargin = AfkUi.dp(c, 2);
        content.addView(detached(mCount), countLp);

        LinearLayout group = mGroup;
        group.removeAllViews();
        Map<String, AccountRow> kept = new LinkedHashMap<>();
        for (AfkAccount a : accounts) {
            AccountRow row = mRows.get(a.id);
            if (row == null) {
                row = new AccountRow(c, a.id);
                if (first) enter(row.wrap, entering++);
            }
            row.divider.setVisibility(kept.isEmpty() ? View.GONE : View.VISIBLE);
            kept.put(a.id, row);
            group.addView(detached(row.wrap));
        }
        mRows.clear();
        mRows.putAll(kept);
        group.addView(detached(mAddDivider));
        CloudUi.enable(mAddRow, accounts.size() < AfkLimits.MAX_ACCOUNTS);
        group.addView(detached(mAddRow.view));
        LinearLayout.LayoutParams groupLp = CloudUi.matchWrap();
        groupLp.topMargin = AfkUi.dp(c, 8);
        content.addView(detached(group), groupLp);

        // Several accounts: both bulk actions, quiet (the desktop's "Buradakileri bağla / durdur").
        if (accounts.size() >= 2) {
            LinearLayout.LayoutParams bulkLp = CloudUi.matchWrap();
            bulkLp.topMargin = AfkUi.dp(c, 10);
            content.addView(detached(mBulk), bulkLp);
        }
        content.addView(detached(mLimitNote));

        // The phone's own settings AFK needs.
        LinearLayout.LayoutParams phoneLp = CloudUi.matchWrap();
        phoneLp.topMargin = AfkUi.dp(c, 24);
        content.addView(detached(mPhoneSection), phoneLp);
        LinearLayout.LayoutParams settingsLp = CloudUi.matchWrap();
        settingsLp.topMargin = AfkUi.dp(c, 8);
        content.addView(detached(mPhoneGroup), settingsLp);
    }

    /** The list's blocks, made once per screen. */
    private void makeBlocks(Context c) {
        mSection = CloudUi.section(c, getString(R.string.projects_afk_ui_here));
        mCount = CloudUi.text(c, "", 13, R.color.projects_ash_400, false);
        mGroup = CloudUi.group(c);
        mAddDivider = CloudUi.rowDivider(c);
        mAddRow = CloudUi.addRow(c, getString(R.string.projects_afk_ui_add), v -> addAccount());
        mBulk = new LinearLayout(c);
        mBulk.setOrientation(LinearLayout.HORIZONTAL);
        mConnectAll = CloudUi.button(c, getString(R.string.projects_afk_ui_connect_all), false, this::connectAll);
        mStopAll = CloudUi.button(c, getString(R.string.projects_afk_ui_stop_all), false, v -> {
            Haptics.tick(v);
            mAfk.stopAll();
        });
        LinearLayout.LayoutParams leftLp = CloudUi.weight(1f);
        LinearLayout.LayoutParams rightLp = CloudUi.weight(1f);
        rightLp.setMarginStart(AfkUi.dp(c, 8));
        mBulk.addView(mConnectAll, leftLp);
        mBulk.addView(mStopAll, rightLp);
        mLimitNote = CloudUi.note(c, AfkTexts.start(c, AfkStartResult.LIMIT_REACHED));
        mLimitNote.setVisibility(View.GONE);
        mPhoneSection = CloudUi.section(c, getString(R.string.projects_afk_ui_phone));
        mPhoneGroup = CloudUi.group(c);
        mBatteryRow = CloudUi.row(c, R.drawable.projects_ic_battery_charging, getString(R.string.projects_afk_ui_battery),
                v -> AfkBattery.openFromRow(this));
        mNotifyRow = CloudUi.row(c, R.drawable.projects_ic_bell, getString(R.string.projects_afk_ui_notifications),
                v -> AfkBattery.openNotificationSettings(requireActivity()));
        List<View> rows = new ArrayList<>();
        rows.add(mBatteryRow.view);
        rows.add(mNotifyRow.view);
        CloudUi.fill(mPhoneGroup, rows);
    }

    /** {@code view} out of the parent it had, ready to be put back. */
    private static <T extends View> T detached(T view) {
        if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
        return view;
    }

    /** Why AFK ended while nobody stopped it (the morning's notice), its fix, and once the calm 7/24 Bulut line. */
    private View endNotice(Context c, final AfkEnd end) {
        LinearLayout panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(AfkUi.dp(c, 10));
        bg.setColor(AfkUi.color(c, R.color.projects_obsidian_800));
        bg.setStroke(Math.max(1, AfkUi.dp(c, 1)), AfkUi.color(c, R.color.projects_obsidian_700));
        panel.setBackground(bg);
        panel.setPaddingRelative(AfkUi.dp(c, 14), AfkUi.dp(c, 12), AfkUi.dp(c, 4), AfkUi.dp(c, 4));
        ImageView glyph = new ImageView(c);
        glyph.setImageResource(R.drawable.projects_ic_warning_circle);
        glyph.setImageTintList(ColorStateList.valueOf(AfkUi.color(c, R.color.projects_gold_300)));
        glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams glyphLp = new LinearLayout.LayoutParams(AfkUi.dp(c, 20), AfkUi.dp(c, 20));
        glyphLp.topMargin = AfkUi.dp(c, 1);
        panel.addView(glyph, glyphLp);
        LinearLayout column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        TextView words = CloudUi.text(c, AfkTexts.end(c, end), 14, R.color.projects_bone_100, false);
        words.setLineSpacing(0, 1.15f);
        words.setPaddingRelative(0, 0, AfkUi.dp(c, 10), 0);
        column.addView(words, CloudUi.matchWrap());
        boolean offer = cloudOffer(end);
        if (offer) {
            // phone-afk.md §1.5, §6: one calm line, once, right after the phone could not keep AFK.
            mCloudShownFor = end.at;
            AfkBattery.prefs(c).edit().putBoolean(AfkBattery.KEY_CLOUD_SEEN, true).apply();
            TextView cloud = CloudUi.text(c, getString(R.string.projects_afk_ui_cloud_line), 13.5f, R.color.projects_ash_300, false);
            cloud.setLineSpacing(0, 1.15f);
            LinearLayout.LayoutParams cloudLp = CloudUi.matchWrap();
            cloudLp.topMargin = AfkUi.dp(c, 8);
            cloud.setPaddingRelative(0, 0, AfkUi.dp(c, 10), 0);
            column.addView(cloud, cloudLp);
        }
        LinearLayout actions = new LinearLayout(c);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        if (end.batteryFix()) {
            Button fix = CloudUi.textButton(c, getString(R.string.projects_afk_end_fix), v -> AfkBattery.openFromRow(this));
            fix.setPadding(0, 0, AfkUi.dp(c, 16), 0);
            actions.addView(fix);
        }
        if (offer) {
            Button see = CloudUi.siteLink(c, getString(R.string.projects_afk_ui_cloud_link),
                    v -> ProjectsUi.openUrl(requireActivity(), AfkUi.CLOUD_URL));
            see.setPadding(0, 0, AfkUi.dp(c, 16), 0);
            actions.addView(see);
        }
        View spacer = new View(c);
        actions.addView(spacer, CloudUi.weight(1f));
        Button close = CloudUi.textButton(c, getString(R.string.projects_close), v -> {
            Haptics.tick(v);
            mAfk.dismissLastEnd();
            render();
        });
        close.setPaintFlags(close.getPaintFlags() & ~android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        close.setTextColor(AfkUi.color(c, R.color.projects_ash_300));
        actions.addView(close);
        LinearLayout.LayoutParams actionsLp = CloudUi.matchWrap();
        actionsLp.topMargin = AfkUi.dp(c, 2);
        column.addView(actions, actionsLp);
        LinearLayout.LayoutParams columnLp = CloudUi.weight(1f);
        columnLp.setMarginStart(AfkUi.dp(c, 12));
        panel.addView(column, columnLp);
        LinearLayout.LayoutParams panelLp = CloudUi.matchWrap();
        panelLp.topMargin = AfkUi.dp(c, 8);
        panel.setLayoutParams(panelLp);
        return panel;
    }

    /** The end is one 7/24 Bulut answers (the phone closed AFK, its memory ran out, or it is not known why). */
    private static boolean cloudEnd(AfkEnd end) {
        return end.kind == AfkEnd.Kind.CLOSED_BY_PHONE || end.kind == AfkEnd.Kind.LOW_MEMORY || end.kind == AfkEnd.Kind.UNKNOWN;
    }

    /** The end this screen shows the 7/24 Bulut line for (it stays while the screen does). */
    private long mCloudShownFor;

    /** The calm line shows: such an end, and the line was never shown before (or is showing for this end now). */
    private boolean cloudOffer(@Nullable AfkEnd end) {
        if (end == null || !cloudEnd(end)) return false;
        return mCloudShownFor == end.at || !AfkBattery.prefs(ctx()).getBoolean(AfkBattery.KEY_CLOUD_SEEN, false);
    }

    // ---- updating in place ----

    /** Writes every row's words and the blocks' states; ticks again when the words next change by themselves. */
    private void update() {
        View view = getView();
        if (view == null) return;
        view.removeCallbacks(mTick);
        Context c = ctx();
        long now = System.currentTimeMillis();
        long next = 0;
        int running = 0;
        int inGame = 0;
        boolean canConnect = false;
        Map<String, AfkStatus> byId = new LinkedHashMap<>();
        for (AfkStatus s : mStatuses) byId.put(s.accountId, s);
        for (AfkStatus s : mStatuses) {
            if (s.state.running()) running++;
            if (s.state == AfkState.IN_GAME) inGame++;
            if (AfkLook.bulkConnects(s)) canConnect = true;
        }
        for (AfkAccount a : mAfk.accounts()) {
            AccountRow row = mRows.get(a.id);
            if (row == null) continue;
            AfkStatus s = byId.get(a.id);
            if (s == null) s = AfkStatus.stopped(a.id, a.name);
            row.bind(c, a, s, now);
            long n = AfkLook.nextChange(s, now);
            if (n > 0 && (next == 0 || n < next)) next = n;
        }
        int count = mRows.size();
        if (mCount != null) {
            mCount.setText(inGame > 0
                    ? getResources().getQuantityString(R.plurals.projects_afk_ui_count_online, count, count, inGame)
                    : getResources().getQuantityString(R.plurals.projects_afk_ui_count, count, count));
        }
        boolean full = running >= AfkLimits.MAX_RUNNING;
        if (mConnectAll != null) setEnabled(mConnectAll, canConnect && !full);
        if (mStopAll != null) setEnabled(mStopAll, running > 0);
        if (mLimitNote != null) mLimitNote.setVisibility(full && count > running ? View.VISIBLE : View.GONE);
        if (mBatteryRow != null) {
            boolean free = mAfk.batteryUnrestricted();
            mBatteryRow.setLine(getString(free ? R.string.projects_afk_ui_on : R.string.projects_afk_ui_allow),
                    free ? R.color.projects_ash_400 : R.color.projects_bone_100);
            CloudUi.warnMark(mBatteryRow.line, !free);
        }
        if (mNotifyRow != null) {
            boolean on = AfkBattery.notificationsOn(c);
            mNotifyRow.setLine(getString(on ? R.string.projects_afk_ui_on : R.string.projects_afk_ui_off),
                    on ? R.color.projects_ash_400 : R.color.projects_bone_100);
            CloudUi.warnMark(mNotifyRow.line, !on);
        }
        // Only while on screen: a status already on its way after onPause must not start a tick in the background.
        if (next > 0 && isResumed()) view.postDelayed(mTick, Math.max(250, next));
    }

    private static void setEnabled(Button button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.5f);
    }

    // ---- actions ----

    private void addAccount() {
        open(AfkEditFragment.class, null);
    }

    private void connect(String id, View from) {
        AfkStartResult result = mAfk.start(id);
        if (result.ok()) {
            Haptics.confirm(from);
            afterStart();
        } else {
            Haptics.reject(from);
            toast(AfkTexts.start(ctx(), result));
        }
    }

    /** "Hepsini bağla": the stopped accounts in the list's order while places are free, never one the server refused. */
    private void connectAll(View from) {
        int started = 0;
        boolean skipped = false;
        boolean full = false;
        AfkStartResult failed = null;
        for (AfkStatus s : mAfk.statuses()) {
            if (s.state.running()) continue;
            if (!AfkLook.bulkConnects(s)) {
                skipped = true;
                continue;
            }
            if (mAfk.runningCount() >= AfkLimits.MAX_RUNNING) {
                full = true;
                break;
            }
            AfkStartResult r = mAfk.start(s.accountId);
            if (r == AfkStartResult.STARTED) started++;
            else if (!r.ok()) {
                failed = r;
                break;
            }
        }
        if (started > 0) {
            Haptics.confirm(from);
            afterStart();
        } else {
            Haptics.reject(from);
        }
        if (failed != null) toast(AfkTexts.start(ctx(), failed));
        else if (full) toast(AfkTexts.start(ctx(), AfkStartResult.LIMIT_REACHED));
        else if (skipped) toast(getString(R.string.projects_afk_ui_bulk_skipped));
    }

    private void act(AfkAccount account, AfkStatus status, View from) {
        switch (AfkLook.action(status)) {
            case STOP:
                Haptics.tick(from);
                mAfk.stop(account.id);
                break;
            case FIX_PASSWORD:
            case FIX_LOGIN:
                open(AfkEditFragment.class, AfkEditFragment.args(account.id, true));
                break;
            default:
                connect(account.id, from);
                break;
        }
    }

    private void openConsole(String id) {
        open(AfkConsoleFragment.class, AfkConsoleFragment.args(id));
    }

    private void openSettings(String id) {
        open(AfkEditFragment.class, AfkEditFragment.args(id, false));
    }

    /** The app's hold menu: whose it is, the main action, Konsol, Ayarlar, and Sil with its question. */
    private void menu(final AfkAccount account, final AfkStatus status) {
        final Context c = ctx();
        View sheet = LayoutInflater.from(c).inflate(R.layout.dialog_projects_actions, null, false);
        LinearLayout list = sheet.findViewById(R.id.projects_actions_list);
        list.addView(menuHeader(c, account.name));
        final AlertDialog dialog = new AlertDialog.Builder(c).setView(sheet).create();
        mMenu = dialog;
        AfkLook.Action action = AfkLook.action(status);
        int icon = action == AfkLook.Action.STOP ? R.drawable.projects_ic_stop
                : action == AfkLook.Action.CONNECT ? R.drawable.projects_ic_play : R.drawable.projects_ic_pencil;
        list.addView(CrewViews.actionRow(c, icon, actionWords(c, action), R.color.projects_bone_100, v -> {
            dialog.dismiss();
            AccountRow row = mRows.get(account.id);
            act(account, mAfk.status(account.id), row != null ? row.main : v);
        }));
        list.addView(CrewViews.actionRow(c, R.drawable.projects_ic_terminal, getString(R.string.projects_afk_ui_console),
                R.color.projects_bone_100, v -> {
                    dialog.dismiss();
                    openConsole(account.id);
                }));
        list.addView(CrewViews.actionRow(c, R.drawable.projects_ic_gear_six, getString(R.string.projects_afk_ui_settings),
                R.color.projects_bone_100, v -> {
                    dialog.dismiss();
                    openSettings(account.id);
                }));
        list.addView(CrewViews.actionRow(c, R.drawable.projects_ic_trash, getString(R.string.projects_afk_ui_delete),
                R.color.projects_flare_400, v -> {
                    dialog.dismiss();
                    confirmRemove(account);
                }));
        if (dialog.getWindow() != null) dialog.getWindow().setWindowAnimations(R.style.ProjectsMenuSheetAnimation);
        dialog.show();
    }

    private View menuHeader(Context c, String name) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(AfkUi.dp(c, 56));
        row.setPaddingRelative(AfkUi.dp(c, 10), AfkUi.dp(c, 4), AfkUi.dp(c, 10), AfkUi.dp(c, 8));
        ImageView head = new ImageView(c);
        head.setScaleType(ImageView.ScaleType.CENTER);
        head.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        head.setImageDrawable(AfkUi.head(c, name, 32));
        row.addView(head, new LinearLayout.LayoutParams(AfkUi.dp(c, 36), AfkUi.dp(c, 36)));
        TextView title = CloudUi.text(c, name, 16, R.color.projects_bone_100, true);
        androidx.core.view.ViewCompat.setAccessibilityHeading(title, true);
        LinearLayout.LayoutParams titleLp = CloudUi.weight(1f);
        titleLp.setMarginStart(AfkUi.dp(c, 12));
        row.addView(title, titleLp);
        return row;
    }

    private void confirmRemove(final AfkAccount account) {
        mConfirm = ConfirmDialog.show(requireActivity(), getString(R.string.projects_afk_ui_remove_title, account.name),
                getString(R.string.projects_afk_ui_remove_body), getString(R.string.projects_afk_ui_remove_confirm), true, () -> {
                    if (!alive()) return;
                    // A running account stops first (the engine's remove looks it up after it is gone).
                    if (mAfk.status(account.id).state.running()) mAfk.stop(account.id);
                    mAfk.remove(account.id);
                    AfkSeen.seen(account.id);
                    toast(getString(R.string.projects_afk_ui_removed, account.name));
                    mStatuses = new ArrayList<>(mAfk.statuses());
                    render();
                });
    }

    static String actionWords(Context c, AfkLook.Action action) {
        switch (action) {
            case STOP: return c.getString(R.string.projects_afk_ui_stop);
            case FIX_PASSWORD: return c.getString(R.string.projects_afk_ui_fix_password);
            case FIX_LOGIN: return c.getString(R.string.projects_afk_ui_fix_login);
            default: return c.getString(R.string.projects_afk_ui_connect);
        }
    }

    // ---- one account's row ----

    /** One account: the head, the name, the state line, the server, what waits since the console was last opened. */
    private final class AccountRow {
        final String id;
        final LinearLayout wrap;
        final View divider;
        final LinearLayout row;
        final ImageView head;
        final TextView name;
        final AfkUi.StateLine state;
        final TextView address;
        final TextView extra;
        final Button main;
        private String mName = "";
        private AfkLook.Action mAction;

        AccountRow(Context c, final String accountId) {
            id = accountId;
            wrap = new LinearLayout(c);
            wrap.setOrientation(LinearLayout.VERTICAL);
            divider = CloudUi.rowDivider(c);
            // The hairline starts where the words do (after the 40 dp head).
            ((LinearLayout.LayoutParams) divider.getLayoutParams()).setMarginStart(AfkUi.dp(c, 68));
            wrap.addView(divider);
            row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(AfkUi.dp(c, 76));
            row.setPaddingRelative(AfkUi.dp(c, 14), AfkUi.dp(c, 12), AfkUi.dp(c, 12), AfkUi.dp(c, 12));
            row.setBackgroundResource(R.drawable.projects_row_ripple);
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> openConsole(id));
            row.setOnLongClickListener(v -> {
                AfkAccount a = mAfk.account(id);
                if (a == null) return false;
                gg.projects.mobile.ui.Haptics.perform(v, gg.projects.mobile.ui.HapticMap.Kind.LONG_PRESS);
                menu(a, mAfk.status(id));
                return true;
            });
            Press.attach(row, Press.Style.ROW);

            head = new ImageView(c);
            head.setScaleType(ImageView.ScaleType.CENTER);
            head.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams headLp = new LinearLayout.LayoutParams(AfkUi.dp(c, 40), AfkUi.dp(c, 40));
            headLp.gravity = Gravity.TOP;
            headLp.topMargin = AfkUi.dp(c, 2);
            row.addView(head, headLp);

            LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(LinearLayout.VERTICAL);
            name = CloudUi.text(c, "", 15.5f, R.color.projects_bone_100, true);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(name, CloudUi.matchWrap());
            state = new AfkUi.StateLine(c, 13.5f);
            LinearLayout.LayoutParams stateLp = CloudUi.matchWrap();
            stateLp.topMargin = AfkUi.dp(c, 3);
            texts.addView(state.view, stateLp);
            address = CloudUi.text(c, "", 13, R.color.projects_ash_400, false);
            address.setSingleLine(true);
            address.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            LinearLayout.LayoutParams addressLp = CloudUi.matchWrap();
            addressLp.topMargin = AfkUi.dp(c, 2);
            texts.addView(address, addressLp);
            extra = CloudUi.text(c, "", 13, R.color.projects_ash_300, false);
            extra.setVisibility(View.GONE);
            texts.addView(extra, addressLp);
            LinearLayout.LayoutParams textsLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            textsLp.setMarginStart(AfkUi.dp(c, 14));
            row.addView(texts, textsLp);

            // The main action, never gold on a row (the desktop's cards): outlined, a little smaller than a sheet's.
            main = CloudUi.button(c, "", false, v -> {
                AfkAccount a = mAfk.account(id);
                if (a != null) act(a, mAfk.status(id), v);
            });
            main.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
            main.setMinHeight(AfkUi.dp(c, 40));
            main.setMinimumHeight(AfkUi.dp(c, 40));
            main.setMinWidth(AfkUi.dp(c, 84));
            main.setMinimumWidth(AfkUi.dp(c, 84));
            main.setPadding(AfkUi.dp(c, 14), 0, AfkUi.dp(c, 14), 0);
            LinearLayout.LayoutParams mainLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mainLp.setMarginStart(AfkUi.dp(c, 10));
            row.addView(main, mainLp);
            wrap.addView(row, CloudUi.matchWrap());
            wrap.setLayoutParams(CloudUi.matchWrap());
        }

        void bind(Context c, AfkAccount a, AfkStatus s, long now) {
            if (!a.name.equals(mName)) {
                mName = a.name;
                name.setText(a.name);
                head.setImageDrawable(AfkUi.head(c, a.name, 32));
            }
            state.set(s, now);
            address.setText(a.address());
            AfkAlert.Kind waiting = AfkSeen.waiting(a.id);
            String more = null;
            if (waiting == AfkAlert.Kind.SERVER_WINDOW) more = c.getString(R.string.projects_afk_line_window_untitled);
            else if (waiting == AfkAlert.Kind.PRIVATE_MESSAGE) more = c.getString(R.string.projects_afk_ui_alert_private);
            else if (waiting == AfkAlert.Kind.MENTION) more = c.getString(R.string.projects_afk_ui_alert_mention);
            extra.setText(more);
            extra.setVisibility(more == null ? View.GONE : View.VISIBLE);
            AfkLook.Action action = AfkLook.action(s);
            if (action != mAction) {
                // The label swaps in place (focus stays on the button for TalkBack, as the desktop's).
                mAction = action;
                main.setText(actionWords(c, action));
            }
            main.setContentDescription(actionWords(c, action) + ": " + a.name);
            StringBuilder said = new StringBuilder(a.name).append(", ").append(state.words()).append(", ").append(a.address());
            if (more != null) said.append(", ").append(more);
            row.setContentDescription(said);
        }
    }
}
