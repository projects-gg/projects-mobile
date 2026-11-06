package gg.projects.mobile.afk.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationManagerCompat;

import net.kdt.pojavlaunch.R;

import gg.projects.mobile.afk.api.PhoneAfk;
import gg.projects.mobile.cloud.ui.CloudUi;
import gg.projects.mobile.social.ui.BottomPanel;
import gg.projects.mobile.ui.ProjectsUi;
import gg.projects.mobile.ui.Press;

/**
 * The phone's own settings phone AFK needs (phone-afk.md §4, §6): the notification permission, asked at the first start
 * on Android 13+ (AFK runs without it; the screen says so); then "Ekran kapalıyken de sürsün", Android's own battery
 * question (APK only); then, on phones whose maker closes apps in the background, one step more ("Bir ayar daha") that
 * opens the maker's setting, or dontkillmyapp's page when it cannot be opened. Each sheet comes once by itself; the AFK
 * list's "Telefon" rows open them again.
 */
final class AfkBattery {
    private AfkBattery() {}

    private static final String PREFS = "projects_afk_ui";
    private static final String KEY_NOTIFY_ASKED = "notify_asked";
    private static final String KEY_BATTERY_ASKED = "battery_asked";
    private static final String KEY_MAKER_ASKED = "maker_asked";
    static final String KEY_CLOUD_SEEN = "cloud_offer_seen";

    /** The system's battery question is open: the maker's step may follow when the player comes back. */
    private static boolean sAwaitingBattery;

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean notificationsOn(Context c) {
        return NotificationManagerCompat.from(c).areNotificationsEnabled();
    }

    /** After a start the player made: the questions that have not been asked yet, one at a time. */
    static void afterStart(AfkScreen screen, ActivityResultLauncher<String> notifyAsk) {
        Context c = screen.ctx();
        SharedPreferences p = prefs(c);
        if (Build.VERSION.SDK_INT >= 33 && !notificationsOn(c) && !p.getBoolean(KEY_NOTIFY_ASKED, false)) {
            p.edit().putBoolean(KEY_NOTIFY_ASKED, true).apply();
            try {
                notifyAsk.launch(Manifest.permission.POST_NOTIFICATIONS);
                return;
            } catch (RuntimeException ignored) {
                // No permission screen: go on with the battery.
            }
        }
        afterNotifications(screen);
    }

    /** The notification question answered (either way): the battery's, once. */
    static void afterNotifications(AfkScreen screen) {
        Context c = screen.ctx();
        PhoneAfk afk = PhoneAfk.get(c);
        SharedPreferences p = prefs(c);
        if (!afk.batteryUnrestricted() && afk.batteryIntent() != null && !p.getBoolean(KEY_BATTERY_ASKED, false)) {
            p.edit().putBoolean(KEY_BATTERY_ASKED, true).apply();
            askBattery(screen);
        } else if (afk.batteryUnrestricted()) {
            maybeMaker(screen);
        }
    }

    /** The screen is back on top: after the system's battery question, the maker's step (once). */
    static void onResume(AfkScreen screen) {
        if (!sAwaitingBattery) return;
        sAwaitingBattery = false;
        if (PhoneAfk.get(screen.ctx()).batteryUnrestricted()) maybeMaker(screen);
    }

    private static void maybeMaker(AfkScreen screen) {
        Context c = screen.ctx();
        SharedPreferences p = prefs(c);
        if (PhoneAfk.batteryMaker().isEmpty() || p.getBoolean(KEY_MAKER_ASKED, false)) return;
        p.edit().putBoolean(KEY_MAKER_ASKED, true).apply();
        askMaker(screen);
    }

    /** The "Telefon" row: Android's question while AFK is limited, else the maker's step where there is one. */
    static void openFromRow(AfkScreen screen) {
        PhoneAfk afk = PhoneAfk.get(screen.ctx());
        if (!afk.batteryUnrestricted() && afk.batteryIntent() != null) askBattery(screen);
        else if (!PhoneAfk.batteryMaker().isEmpty()) askMaker(screen);
        else openAppSettings(screen.requireActivity());
    }

    static void askBattery(final AfkScreen screen) {
        final Context c = screen.ctx();
        final BottomPanel[] panel = new BottomPanel[1];
        View content = sheet(c, c.getString(R.string.projects_afk_ui_battery), c.getString(R.string.projects_afk_ui_battery_body),
                c.getString(R.string.projects_afk_ui_allow), v -> {
                    panel[0].dismiss();
                    openSystem(screen);
                }, () -> panel[0].dismiss());
        panel[0] = screen.keep(BottomPanel.of(c, content, 0.9f));
        panel[0].show();
    }

    private static void openSystem(AfkScreen screen) {
        Activity activity = screen.getActivity();
        if (activity == null) return;
        Intent intent = PhoneAfk.get(activity).batteryIntent();
        if (intent == null) return;
        try {
            activity.startActivity(intent);
            sAwaitingBattery = true;
        } catch (RuntimeException e) {
            // No such screen on this phone: the app's own settings page holds the battery choice.
            openAppSettings(activity);
        }
    }

    static void askMaker(final AfkScreen screen) {
        final Context c = screen.ctx();
        final BottomPanel[] panel = new BottomPanel[1];
        String body = c.getString(R.string.projects_afk_ui_maker_body, makerName(PhoneAfk.batteryMaker()),
                c.getApplicationInfo().loadLabel(c.getPackageManager()).toString());
        View content = sheet(c, c.getString(R.string.projects_afk_ui_maker_title), body,
                c.getString(R.string.projects_afk_ui_maker_open), v -> {
                    panel[0].dismiss();
                    openMaker(screen.getActivity());
                }, () -> panel[0].dismiss());
        panel[0] = screen.keep(BottomPanel.of(c, content, 0.9f));
        panel[0].show();
    }

    private static void openMaker(@Nullable Activity activity) {
        if (activity == null) return;
        Intent intent = PhoneAfk.batteryMakerIntent();
        if (intent != null) {
            try {
                activity.startActivity(intent);
                return;
            } catch (RuntimeException e) {
                // Some versions moved or hid the setting: the maker's page tells the way.
            }
        }
        String page = PhoneAfk.batteryMakerPage();
        if (page != null) ProjectsUi.openUrl(activity, page);
        else openAppSettings(activity);
    }

    static void openNotificationSettings(Activity activity) {
        try {
            activity.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName()));
        } catch (RuntimeException e) {
            openAppSettings(activity);
        }
    }

    static void openAppSettings(Activity activity) {
        try {
            activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", activity.getPackageName(), null)));
        } catch (RuntimeException ignored) {
            // Nothing opens the app's settings on this phone.
        }
    }

    /** The maker as the player knows it. */
    static String makerName(String key) {
        switch (key) {
            case "xiaomi": return "Xiaomi";
            case "samsung": return "Samsung";
            case "huawei": return "Huawei";
            case "honor": return "Honor";
            case "oppo": return "OPPO";
            case "realme": return "realme";
            case "oneplus": return "OnePlus";
            case "vivo": return "vivo";
            default: return key;
        }
    }

    /**
     * A sheet's content: the Tektur title, one or two sentences, the gold action and a quiet "Şimdi değil" under it
     * (the app's sheets: full-width actions, 48 dp targets).
     */
    static View sheet(Context c, CharSequence title, CharSequence body, CharSequence action, View.OnClickListener onAction,
                      final Runnable notNow) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(AfkUi.dp(c, 20), AfkUi.dp(c, 8), AfkUi.dp(c, 20), AfkUi.dp(c, 16));
        TextView name = CloudUi.text(c, title, 18, R.color.projects_bone_100, false);
        name.setTypeface(AfkUi.font(c, R.font.tektur_semibold));
        androidx.core.view.ViewCompat.setAccessibilityHeading(name, true);
        box.addView(name, CloudUi.matchWrap());
        TextView words = CloudUi.text(c, body, 15, R.color.projects_bone_100, false);
        words.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams wordsLp = CloudUi.matchWrap();
        wordsLp.topMargin = AfkUi.dp(c, 8);
        box.addView(words, wordsLp);
        Button go = CloudUi.button(c, action, true, onAction);
        LinearLayout.LayoutParams goLp = CloudUi.matchWrap();
        goLp.topMargin = AfkUi.dp(c, 20);
        box.addView(go, goLp);
        Button later = new Button(c, null, android.R.attr.borderlessButtonStyle);
        later.setText(R.string.projects_afk_ui_not_now);
        later.setAllCaps(false);
        later.setTypeface(AfkUi.font(c, R.font.figtree_semibold));
        later.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        later.setTextColor(AfkUi.color(c, R.color.projects_ash_300));
        later.setBackgroundResource(R.drawable.projects_row_ripple);
        later.setMinHeight(AfkUi.dp(c, 48));
        later.setMinimumHeight(AfkUi.dp(c, 48));
        Press.attach(later, Press.Style.BUTTON);
        later.setOnClickListener(v -> notNow.run());
        LinearLayout.LayoutParams laterLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        laterLp.topMargin = AfkUi.dp(c, 4);
        box.addView(later, laterLp);
        return box;
    }
}
