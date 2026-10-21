package gg.projects.mobile.social;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

import net.kdt.pojavlaunch.LauncherActivity;
import net.kdt.pojavlaunch.TestStorageActivity;
import net.kdt.pojavlaunch.Tools;

import java.util.List;

import gg.projects.mobile.social.ui.AddFriendFragment;
import gg.projects.mobile.social.ui.ChatFragment;
import gg.projects.mobile.social.ui.CrewFragment;
import gg.projects.mobile.social.ui.SocialFragment;

/**
 * Where a notification tap or a {@code projects://} link takes the player. The screen opens when the
 * launcher is on screen; a game on screen is never taken away (an activity started over it would
 * end the launcher's task above the game): the place waits until the launcher comes back.
 */
public final class SocialRoutes {
    private SocialRoutes() {}

    public static final String FRIENDS = "friends";
    /** The crew page; M20: the argument is a crew id (that crew opens) or a call id (its first crew). */
    public static final String CREW = "crew";
    public static final String INVITE = "invite";
    public static final String ADD = "add";
    /** M20: a conversation ({@code c:<team>} or {@code d:<id>:<id>}). */
    public static final String CHAT = "chat";
    /** M21: a crew invite link ({@code XXXX-XXXX-XXXX}): the crew page's "+" looks it up. */
    public static final String CREW_LINK = "crew_link";

    @Nullable
    private static String sTarget;
    @Nullable
    private static String sArg;
    private static int sLaunchers;

    /** Remembers where to go; the launcher opens it ({@link #consume}). */
    public static void set(String target, @Nullable String arg) {
        sTarget = target;
        sArg = arg;
    }

    public static void launcherCreated() {
        sLaunchers++;
    }

    public static void launcherDestroyed() {
        sLaunchers = Math.max(0, sLaunchers - 1);
    }

    /**
     * Brings the launcher up for a route set just now: back to the running launcher (whatever is
     * above it, a Custom Tab after sign-in, goes), the app's normal start when it is not running,
     * or nothing while the game runs.
     */
    public static void bringUp(Activity from) {
        if (gameRunning(from)) {
            // The game stays where it is (nothing above it is cleared). Only a launcher already on screen
            // over it comes forward and opens the place now; otherwise it waits for the launcher.
            if (sLaunchers > 0 && SocialService.get(from).foreground()) {
                from.startActivity(new Intent(from, LauncherActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            }
            return;
        }
        Intent intent;
        if (sLaunchers > 0) {
            // The app's own task to the front, back to its launcher (a Custom Tab above it goes).
            intent = new Intent(from, LauncherActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        } else {
            // As the home screen's icon does: an existing task (its launcher gone with the process) comes back
            // as it was instead of a second launcher piled on it; otherwise the app starts.
            intent = from.getPackageManager().getLaunchIntentForPackage(from.getPackageName());
            if (intent == null) intent = new Intent(from, TestStorageActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        }
        from.startActivity(intent);
    }

    /** The game's process is there (it plays, or it is starting). */
    public static boolean gameRunning(Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> processes = manager == null ? null : manager.getRunningAppProcesses();
        if (processes == null) return false;
        String game = context.getPackageName() + ":game";
        for (ActivityManager.RunningAppProcessInfo process : processes) {
            if (game.equals(process.processName)) return true;
        }
        return false;
    }

    /** The launcher is on screen: opens the waiting place, if any. */
    public static void consume(FragmentActivity activity) {
        String target = sTarget;
        String arg = sArg;
        sTarget = null;
        sArg = null;
        if (target == null || !SocialService.get(activity).enabled()) return;
        if (activity.getSupportFragmentManager().isStateSaved()) {
            set(target, arg);
            return;
        }
        // From the home screen, so back leads home and screens do not pile up.
        Tools.backToMainMenu(activity);
        Bundle args = new Bundle();
        switch (target) {
            case CREW:
                // The crew page shows its call at the top (the crew the call names, or the crew asked for).
                if (arg != null) args.putString(CrewFragment.ARG_CREW, arg);
                Tools.swapFragment(activity, CrewFragment.class, CrewFragment.TAG, args);
                break;
            case CREW_LINK:
                if (arg != null) args.putString(CrewFragment.ARG_LINK, arg);
                Tools.swapFragment(activity, CrewFragment.class, CrewFragment.TAG, args);
                break;
            case CHAT:
                if (arg == null || !ChatConv.valid(arg)) {
                    Tools.swapFragment(activity, SocialFragment.class, SocialFragment.TAG, args);
                    break;
                }
                args.putString(ChatFragment.ARG_CONV, arg);
                Tools.swapFragment(activity, ChatFragment.class, ChatFragment.TAG, args);
                break;
            case ADD:
                if (arg != null) args.putString(AddFriendFragment.ARG_CODE, arg);
                Tools.swapFragment(activity, AddFriendFragment.class, AddFriendFragment.TAG, args);
                break;
            case INVITE:
                if (arg != null) args.putString(SocialFragment.ARG_INVITE, arg);
                Tools.swapFragment(activity, SocialFragment.class, SocialFragment.TAG, args);
                break;
            case FRIENDS:
            default:
                Tools.swapFragment(activity, SocialFragment.class, SocialFragment.TAG, args);
                break;
        }
    }
}
