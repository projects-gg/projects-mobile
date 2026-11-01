package gg.projects.mobile.afk.ui;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import net.kdt.pojavlaunch.R;

import java.util.List;

import gg.projects.mobile.ProjectsContent;
import gg.projects.mobile.afk.api.AfkListener;
import gg.projects.mobile.afk.api.AfkStatus;
import gg.projects.mobile.afk.api.AfkWatch;
import gg.projects.mobile.afk.api.PhoneAfk;
import gg.projects.mobile.content.Snapshot;
import gg.projects.mobile.ui.Press;

/**
 * Phone AFK on the main menu (PHONE-AFK): a player with AFK accounts on this phone gets a row under 7/24 Bulut's with
 * the line that matters most ({@link AfkHomeLine}); everyone else finds "AFK hesapları" in the menu's tools while the
 * feature is on (Studio's {@code features.phoneAfk}, the APK, Android 8+). Nothing shows while it is off. The row
 * follows the accounts live while the menu is on screen and asks nothing of the AFK process otherwise.
 */
public final class AfkHomeRow implements AfkListener, ProjectsContent.Listener {
    private final Fragment mFragment;
    private final View mRow;
    private final TextView mLine;
    private final View mMenuButton;
    private final View mMenuDivider;
    @Nullable
    private AfkWatch mWatch;
    private List<AfkStatus> mStatuses;
    private boolean mResumed;
    private final Runnable mTick = this::render;

    public AfkHomeRow(Fragment fragment, View root) {
        mFragment = fragment;
        mRow = root.findViewById(R.id.projects_afk_row);
        mLine = mRow.findViewById(R.id.projects_afk_row_line);
        mMenuButton = root.findViewById(R.id.afk_button);
        mMenuDivider = root.findViewById(R.id.afk_divider);
        Press.attach(mRow, Press.Style.CARD);
        mRow.setOnClickListener(v -> open());
        mMenuButton.setOnClickListener(v -> open());
        render();
    }

    public void onResume() {
        mResumed = true;
        ProjectsContent.addListener(this);
        watch();
        render();
    }

    public void onPause() {
        mResumed = false;
        ProjectsContent.removeListener(this);
        mRow.removeCallbacks(mTick);
        if (mWatch != null) {
            mWatch.close();
            mWatch = null;
        }
    }

    /** Watches the accounts only while the feature shows (the AFK process is never woken for a hidden row). */
    private void watch() {
        if (!mResumed || mWatch != null) return;
        Context c = mFragment.getContext();
        if (c == null || !AfkUi.available(c)) return;
        mWatch = PhoneAfk.get(c).watch(this);
    }

    @Override
    public void onContent(@Nullable Snapshot snapshot, boolean refreshing) {
        // Studio's switch may have come with the content: show or hide, and start watching once it shows.
        watch();
        render();
    }

    @Override
    public void onStatuses(List<AfkStatus> all) {
        mStatuses = all;
        render();
    }

    @Override
    public void onStatus(AfkStatus status) {
        Context c = mFragment.getContext();
        if (c == null) return;
        mStatuses = PhoneAfk.get(c).statuses();
        render();
    }

    private void open() {
        FragmentActivity activity = mFragment.getActivity();
        if (activity == null || !mFragment.isResumed()) return;
        AfkUi.openList(activity);
    }

    private void render() {
        Context c = mRow.getContext();
        mRow.removeCallbacks(mTick);
        boolean on = AfkUi.available(c);
        boolean has = on && !PhoneAfk.get(c).accounts().isEmpty();
        mRow.setVisibility(has ? View.VISIBLE : View.GONE);
        mMenuButton.setVisibility(on && !has ? View.VISIBLE : View.GONE);
        mMenuDivider.setVisibility(on && !has ? View.VISIBLE : View.GONE);
        if (!has) return;
        List<AfkStatus> statuses = mStatuses != null ? mStatuses : PhoneAfk.get(c).statuses();
        AfkHomeLine line = AfkHomeLine.of(statuses);
        long now = System.currentTimeMillis();
        String words;
        switch (line.kind) {
            case STOPPED_ONE:
                words = c.getString(R.string.projects_afk_ui_home_stopped_one, line.status.name);
                break;
            case STOPPED_MANY:
                words = c.getResources().getQuantityString(R.plurals.projects_afk_ui_home_stopped_many, line.count, line.count);
                break;
            case ALL_IN_GAME:
                words = c.getResources().getQuantityString(R.plurals.projects_afk_ui_home_in_game, line.count, line.count);
                break;
            case SOME_IN_GAME:
                words = c.getResources().getQuantityString(R.plurals.projects_afk_ui_home_some_in_game, line.count, line.count, line.inGame);
                break;
            case ONE_ON_THE_WAY:
                words = AfkUi.state(c, line.status, now);
                long next = AfkLook.nextChange(line.status, now);
                if (next > 0 && mResumed) mRow.postDelayed(mTick, Math.max(500, next));
                break;
            case MANY_ON_THE_WAY:
                words = c.getResources().getQuantityString(R.plurals.projects_afk_ui_home_many_on_the_way, line.count, line.count);
                break;
            default:
                words = c.getString(R.string.projects_afk_ui_home_none_running);
                break;
        }
        // 7/24 Bulut's row's tones: a stop in flare, accounts on their way a step brighter, the rest quiet. Never gold.
        int color = line.alarming() ? R.color.projects_flare_400 : line.waiting() ? R.color.projects_ash_300 : R.color.projects_ash_400;
        mLine.setText(words);
        mLine.setTextColor(AfkUi.color(c, color));
        mRow.setContentDescription(c.getString(R.string.projects_afk_ui_title) + ", " + words);
    }
}
