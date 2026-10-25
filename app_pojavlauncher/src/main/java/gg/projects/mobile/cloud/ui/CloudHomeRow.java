package gg.projects.mobile.cloud.ui;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;

import gg.projects.mobile.cloud.CloudPrefs;
import gg.projects.mobile.cloud.CloudService;
import gg.projects.mobile.ui.Press;

/**
 * 7/24 Bulut on the main menu. A player with cloud accounts gets a row under the friends' row with the line that
 * matters most: an approval waiting, then an account that stopped, then new messages, then how many are in the game.
 * Everyone else finds "7/24 Bulut" in the menu's tools while the service is on. Nothing shows while it is off.
 */
public final class CloudHomeRow implements CloudService.Listener {
    private final Fragment mFragment;
    private final View mRow;
    private final TextView mLine;
    private final View mMenuButton;
    private final View mMenuDivider;

    public CloudHomeRow(Fragment fragment, View root) {
        mFragment = fragment;
        mRow = root.findViewById(R.id.projects_cloud_row);
        mLine = mRow.findViewById(R.id.projects_cloud_row_line);
        mMenuButton = root.findViewById(R.id.cloud_button);
        mMenuDivider = root.findViewById(R.id.cloud_divider);
        Press.attach(mRow, Press.Style.CARD);
        mRow.setOnClickListener(v -> open());
        mMenuButton.setOnClickListener(v -> open());
        render();
    }

    public void onResume() {
        CloudService service = CloudService.get(mFragment.requireContext());
        service.addListener(this);
        service.refresh(false);
        render();
    }

    public void onPause() {
        CloudService.get(mFragment.requireContext()).removeListener(this);
    }

    @Override
    public void onCloudChanged() {
        render();
    }

    private void open() {
        FragmentActivity activity = mFragment.getActivity();
        if (activity == null || !mFragment.isResumed()) return;
        Tools.swapFragment(activity, CloudFragment.class, CloudFragment.TAG, null);
    }

    private void render() {
        Context c = mRow.getContext();
        CloudService service = CloudService.get(c);
        boolean on = service.available();
        CloudPrefs.Summary s = on ? service.summary() : null;
        boolean row = s != null && s.count > 0;
        mRow.setVisibility(row ? View.VISIBLE : View.GONE);
        mMenuButton.setVisibility(on && !row ? View.VISIBLE : View.GONE);
        mMenuDivider.setVisibility(on && !row ? View.VISIBLE : View.GONE);
        if (!row) return;
        int color = R.color.projects_ash_400;
        String line;
        if (service.offer() != null && service.offer().paused()) {
            line = c.getString(R.string.projects_cloud_home_maintenance);
        } else if (s.approvals > 0) {
            line = c.getResources().getQuantityString(R.plurals.projects_cloud_home_approvals, s.approvals, s.approvals);
            color = R.color.projects_gold_300;
        } else if (s.stopped != null && !s.stopped.isEmpty()) {
            line = c.getString(R.string.projects_cloud_home_stopped, s.stopped);
            color = R.color.projects_flare_400;
        } else if (s.unread > 0) {
            line = c.getResources().getQuantityString(R.plurals.projects_cloud_home_messages, s.unread, s.unread);
            color = R.color.projects_gold_300;
        } else if (s.count == 1) {
            line = c.getString(s.inGame == 1 ? R.string.projects_cloud_home_one_in_game : R.string.projects_cloud_home_none_in_game);
        } else if (s.inGame == s.count) {
            line = c.getString(R.string.projects_cloud_home_all_in_game);
        } else if (s.inGame == 0) {
            line = c.getString(R.string.projects_cloud_home_none_in_game);
        } else {
            line = c.getResources().getQuantityString(R.plurals.projects_cloud_home_in_game, s.count, s.inGame, s.count);
        }
        mLine.setText(line);
        mLine.setTextColor(CloudUi.color(c, color));
        mRow.setContentDescription(c.getString(R.string.projects_cloud_title) + ", " + line);
    }
}
