package gg.projects.mobile.cloud.ui;

import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.NotificationManagerCompat;

import net.kdt.pojavlaunch.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import gg.projects.mobile.cloud.CloudModel;
import gg.projects.mobile.cloud.CloudProblem;
import gg.projects.mobile.cloud.CloudService;

/**
 * "Bildirimler" for 7/24 Bulut: one switch per event (the account's, the same on the site and in the launcher; the
 * service sends only what is on), the AFK+ ones while AFK+ is offered, and quiet hours (what happens then comes in one
 * notification in the morning). When Android keeps the app's notifications off, the screen says so first.
 */
public final class CloudNotifyFragment extends CloudScreen {
    public static final String TAG = "ProjectsCloudNotifyFragment";
    private static final String[] ACCOUNT_EVENTS = {"stopped", "renewal", "expired"};
    /** The site's order (afk.projects.gg/bildirimler). */
    private static final String[] PLUS_EVENTS = {"approval", "found", "deal_done", "deal_failed", "message", "quota_full", "quota_80",
            "gift_ending", "paused", "incident", "summary", "weekly"};

    @Nullable
    private CloudModel.NotifyPrefs mPrefs;
    private boolean mFailed;
    private String mShown = "";

    @Override
    protected void created(View view) {
        mHeader.setTitle(getString(R.string.projects_cloud_notifications));
    }

    @Override
    protected boolean needsAccount() {
        return false;
    }

    @Override
    public void onResume() {
        mShown = "";
        super.onResume();
        if (!mService.signedIn()) {
            leaveNow();
            return;
        }
        load();
    }

    private void load() {
        mService.notifyPrefs(new CloudService.Result<CloudModel.NotifyPrefs>() {
            @Override
            public void ok(CloudModel.NotifyPrefs value) {
                mPrefs = value;
                mFailed = false;
                if (alive()) render();
            }

            @Override
            public void failed(CloudProblem problem) {
                mFailed = mPrefs == null;
                if (alive()) render();
            }
        });
    }

    @Override
    protected void render() {
        Context c = ctx();
        boolean osOn = NotificationManagerCompat.from(c).areNotificationsEnabled();
        // AFK+'s switches only for a player with an AFK+ account (as on the site); the others never get those notices.
        boolean plus = mService.plusOn() && hasPlusAccount();
        String key = osOn + "|" + mFailed + "|" + (mPrefs == null ? "-" : mPrefs.toJson().toString()) + "|" + plus;
        if (key.equals(mShown)) return;
        mShown = key;
        LinearLayout content = clear();
        if (content == null) return;
        if (!osOn) {
            content.addView(CloudUi.notice(c, R.drawable.projects_ic_bell_slash, R.color.projects_gold_300,
                    getString(R.string.projects_social_notifications_off)));
            content.addView(CloudUi.textButton(c, getString(R.string.projects_cloud_notify_os_open), v -> openSystemSettings()),
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        if (mPrefs == null) {
            if (mFailed) {
                LinearLayout empty = CloudUi.empty(c, gg.projects.mobile.ui.PixelSprites.Sprite.FAILED, getString(R.string.projects_cloud_notify_load_failed));
                content.addView(empty);
                content.addView(CloudUi.textButton(c, getString(R.string.projects_cloud_retry), v -> load()),
                        new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            } else {
                content.addView(CloudUi.loading(c));
            }
            return;
        }
        final CloudModel.NotifyPrefs prefs = mPrefs;
        content.addView(CloudUi.section(c, getString(R.string.projects_cloud_notify_accounts)));
        content.addView(switches(c, prefs, ACCOUNT_EVENTS));
        if (plus) {
            List<String> events = new ArrayList<>();
            for (String e : PLUS_EVENTS) {
                if ("message".equals(e) && !mService.plusFeature("inbox")) continue;
                // The daily summary is the assistant's own; the weekly report goes only while the report feature is on
                // (services/cloud-afk README, "AFK+ notifications"): two events, two switches.
                if ("weekly".equals(e) && !mService.plusFeature("report")) continue;
                events.add(e);
            }
            content.addView(CloudUi.section(c, getString(R.string.projects_plus_title)));
            content.addView(switches(c, prefs, events.toArray(new String[0])));
        }

        // No section title here: the switch names itself ("Sessiz saatler" twice in a row read as a mistake).
        content.addView(CloudUi.gap(c, 24));
        LinearLayout quiet = CloudUi.group(c);
        SwitchCompat toggle = CloudUi.toggle(c, getString(R.string.projects_cloud_notify_quiet), prefs.quiet,
                on -> put(prefs.withQuiet(on, prefs.from, prefs.to)));
        quiet.addView(toggle);
        if (prefs.quiet) {
            // Picked in place, so no icons and no carets: the times line up with the switch above them.
            quiet.addView(CloudUi.textDivider(c));
            quiet.addView(CloudUi.valueRow(c, getString(R.string.projects_cloud_notify_from), prefs.from,
                    v -> pick(prefs.from, time -> put(prefs.withQuiet(true, time, prefs.to)))).view);
            quiet.addView(CloudUi.textDivider(c));
            quiet.addView(CloudUi.valueRow(c, getString(R.string.projects_cloud_notify_to), prefs.to,
                    v -> pick(prefs.to, time -> put(prefs.withQuiet(true, prefs.from, time)))).view);
        }
        content.addView(quiet);
        content.addView(CloudUi.note(c, getString(R.string.projects_cloud_notify_quiet_help)));
    }

    private boolean hasPlusAccount() {
        for (CloudModel.Account a : mService.accounts()) if (a.plus()) return true;
        return false;
    }

    private View switches(Context c, final CloudModel.NotifyPrefs prefs, String[] events) {
        LinearLayout group = CloudUi.group(c);
        for (int i = 0; i < events.length; i++) {
            final String event = events[i];
            if (i > 0) {
                View line = new View(c);
                line.setBackgroundColor(CloudUi.color(c, R.color.projects_obsidian_700));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, CloudUi.dp(c, 1)));
                lp.setMarginStart(CloudUi.dp(c, 16));
                group.addView(line, lp);
            }
            group.addView(CloudUi.toggle(c, getString(label(event)), prefs.on(event), on -> {
                if (mPrefs != null) put(mPrefs.with(event, on));
            }));
        }
        return group;
    }

    private static int label(String event) {
        switch (event) {
            case "stopped": return R.string.projects_cloud_notify_stopped;
            case "renewal": return R.string.projects_cloud_notify_renewal;
            case "expired": return R.string.projects_cloud_notify_expired;
            case "approval": return R.string.projects_cloud_notify_approval;
            case "found": return R.string.projects_cloud_notify_found;
            case "deal_done": return R.string.projects_cloud_notify_deal_done;
            case "deal_failed": return R.string.projects_cloud_notify_deal_failed;
            case "message": return R.string.projects_cloud_notify_message;
            case "quota_full": return R.string.projects_cloud_notify_quota_full;
            case "quota_80": return R.string.projects_cloud_notify_quota_80;
            case "gift_ending": return R.string.projects_cloud_notify_gift_ending;
            case "summary": return R.string.projects_cloud_notify_summary;
            case "weekly": return R.string.projects_cloud_notify_weekly;
            case "paused": return R.string.projects_cloud_notify_paused;
            default: return R.string.projects_cloud_notify_incident;
        }
    }

    private interface Picked {
        void picked(String time);
    }

    private void pick(String current, final Picked picked) {
        int hour = 23;
        int minute = 0;
        try {
            hour = Integer.parseInt(current.substring(0, 2));
            minute = Integer.parseInt(current.substring(3, 5));
        } catch (RuntimeException ignored) {
            // A time the service wrote differently: start from 23:00.
        }
        new TimePickerDialog(requireActivity(), (view, h, m) -> picked.picked(String.format(Locale.ROOT, "%02d:%02d", h, m)), hour, minute, true).show();
    }

    /** Shows the change at once and stores it; a refusal puts the screen back as it was. */
    private void put(CloudModel.NotifyPrefs next) {
        final CloudModel.NotifyPrefs before = mPrefs;
        mPrefs = next;
        render();
        mService.putNotifyPrefs(next, new CloudService.Result<CloudModel.NotifyPrefs>() {
            @Override
            public void ok(CloudModel.NotifyPrefs value) {
                mPrefs = value;
                if (alive()) render();
            }

            @Override
            public void failed(CloudProblem problem) {
                mPrefs = before;
                if (!alive()) return;
                mShown = "";
                render();
                problem(problem, getView());
            }
        });
    }

    private void openSystemSettings() {
        Context c = ctx();
        Intent intent;
        if (Build.VERSION.SDK_INT >= 26) {
            intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, c.getPackageName());
        } else {
            intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", c.getPackageName(), null));
        }
        try {
            startActivity(intent);
        } catch (RuntimeException ignored) {
            // No settings screen for it on this phone.
        }
    }
}
