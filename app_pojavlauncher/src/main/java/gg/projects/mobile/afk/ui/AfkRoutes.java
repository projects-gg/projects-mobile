package gg.projects.mobile.afk.ui;

import androidx.annotation.MainThread;
import androidx.fragment.app.FragmentActivity;

import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.R;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import gg.projects.mobile.afk.api.AfkStatus;
import gg.projects.mobile.afk.api.PhoneAfk;
import gg.projects.mobile.social.SocialRoutes;
import gg.projects.mobile.social.ui.SocialUi;

/**
 * The launcher on screen again (its {@code onPostResume}, beside the friends' routes): an AFK notification's tap opens
 * the AFK list or the account's console, and after a game the accounts "AFK'yı durdur ve oyna" stopped start again
 * ("{ad} yeniden AFK'ya bağlanıyor"). Nothing while the game runs or a start is still being prepared.
 */
public final class AfkRoutes {
    private AfkRoutes() {}

    @MainThread
    public static void onLauncherShown(FragmentActivity activity) {
        if (!PhoneAfk.phoneSupported()) return;
        String open = PhoneAfk.takeOpenRequest();
        if (!AfkUi.available(activity)) return;
        if (open != null) {
            if (open.isEmpty()) AfkUi.openList(activity);
            else AfkUi.openAccount(activity, open);
        }
        if (SocialRoutes.gameRunning(activity) || ProgressLayout.hasProcesses()) return;
        PhoneAfk afk = PhoneAfk.get(activity);
        Set<String> before = running(afk.statuses());
        afk.afterGame();
        List<String> started = new ArrayList<>();
        for (AfkStatus s : afk.statuses()) {
            if (s.state.running() && !before.contains(s.accountId)) started.add(s.name);
        }
        if (started.size() == 1) {
            SocialUi.toast(activity, activity.getString(R.string.projects_afk_ui_restarted, started.get(0)));
        } else if (started.size() > 1) {
            SocialUi.toast(activity, activity.getResources().getQuantityString(R.plurals.projects_afk_ui_restarted_many,
                    started.size(), started.size()));
        }
    }

    private static Set<String> running(List<AfkStatus> statuses) {
        Set<String> out = new HashSet<>();
        for (AfkStatus s : statuses) if (s.state.running()) out.add(s.accountId);
        return out;
    }
}
