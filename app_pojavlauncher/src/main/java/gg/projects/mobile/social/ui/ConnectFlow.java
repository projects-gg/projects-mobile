package gg.projects.mobile.social.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.fragment.app.FragmentActivity;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;

import gg.projects.mobile.launch.ConnectTarget;
import gg.projects.mobile.launch.GameConnect;
import gg.projects.mobile.social.GameActivity;
import gg.projects.mobile.social.GameWhere;
import gg.projects.mobile.social.SocialRoutes;
import gg.projects.mobile.ui.Haptics;
import gg.projects.mobile.ui.Press;
import gg.projects.mobile.ui.ProjectsHomeCard;

/**
 * FIX2 ("Bağlan", the owner's wish): a crew call, a "Birlikte oyna" invite or a plan takes the player
 * straight into the server instead of saying where to go. Projects starts the Projects profile through
 * its join ({@code play.projects.gg}); another server starts the profile Play would start, joining it
 * at once (Quick Play from 1.20, {@code --server} before; the desktop's choice). The questions are the
 * desktop's (FIX2-D): "Minecraft açılsın mı?", and while the game runs "Oyun yeniden açılsın mı?" (the game
 * closes the way its window's close would, so a singleplayer world is saved, then comes back connecting).
 * The address can still be copied from its row (a small button, or holding the address), never as the
 * dialog's button.
 */
public final class ConnectFlow {
    private ConnectFlow() {}

    /** "Katıl" or "Bağlan" to Projects (a friend there, a plan under way, a call or an invite on Projects). */
    public static void projects(FragmentActivity activity) {
        offer(activity, ConnectTarget.PROJECTS);
    }

    /** "Bağlan" to the server a call or an invite shares. */
    public static void server(FragmentActivity activity, @Nullable String address) {
        ConnectTarget target = ConnectTarget.server(address);
        if (target == null) return;
        offer(activity, target);
    }

    /** The question, then the start (or, with the game open, its close and a start that connects). */
    public static void offer(final FragmentActivity activity, final ConnectTarget target) {
        final boolean running = SocialRoutes.gameRunning(activity);
        String where = target.shown();
        String body = activity.getString(running ? R.string.projects_connect_running : R.string.projects_connect_body, where);
        // Over the game its log says whether a world is open; from the launcher it is not known, so it is said.
        boolean inGame = activity instanceof net.kdt.pojavlaunch.MainActivity;
        if (running && (!inGame || GameWhere.status().where != GameActivity.Status.Where.SERVER)) {
            body = body + " " + activity.getString(R.string.projects_connect_world);
        }
        View content = content(activity, body, target.projects ? null : target.address);
        new AlertDialog.Builder(activity)
                .setTitle(running ? R.string.projects_connect_restart_title : R.string.projects_social_join_title)
                .setView(content)
                .setPositiveButton(R.string.projects_connect, (d, w) -> {
                    if (running) closeGameAndConnect(activity, target);
                    else start(activity, target);
                })
                .setNegativeButton(R.string.projects_social_cancel, null)
                .show();
    }

    /** Starts the game into {@code target} from the home screen (its checks: an account, a start already running). */
    public static void start(FragmentActivity activity, ConnectTarget target) {
        if (target.projects) ProjectsHomeCard.requestJoin();
        else ProjectsHomeCard.requestServer(target);
        Tools.backToMainMenu(activity);
    }

    /**
     * The game runs in its own process: it is asked to close and come back connecting ({@link GameConnect}
     * in the game's activity); the player already said yes here. Asked over the game, it closes at once.
     */
    private static void closeGameAndConnect(Activity activity, ConnectTarget target) {
        if (activity instanceof net.kdt.pojavlaunch.MainActivity) {
            GameConnect.closeAndConnect(activity, target);
            return;
        }
        // The game's activity comes to the front the way its own notification brings it (its window has to
        // be drawn for the game to take the close).
        Intent intent = new Intent(activity, net.kdt.pojavlaunch.MainActivity.class)
                .putExtra(GameConnect.EXTRA_CONNECT_NOW, target.wire())
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try {
            activity.startActivity(intent);
        } catch (RuntimeException e) {
            SocialUi.toast(activity, activity.getString(R.string.projects_social_error_failed));
        }
    }

    /** The question's text and, for another server, its address with a small copy button. */
    static View content(final Context context, String body, @Nullable final String address) {
        float dp = context.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Math.round(24 * dp), Math.round(4 * dp), Math.round(24 * dp), 0);
        TextView text = new TextView(context);
        text.setText(body);
        text.setTextSize(15);
        text.setTextColor(ContextCompat.getColor(context, R.color.projects_ash_300));
        box.addView(text);
        if (address == null) return box;
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.projects_social_section_background);
        row.setPaddingRelative(Math.round(14 * dp), Math.round(2 * dp), Math.round(2 * dp), Math.round(2 * dp));
        TextView host = new TextView(context);
        host.setText(address);
        host.setTextSize(15);
        host.setTextColor(ContextCompat.getColor(context, R.color.projects_bone_100));
        host.setSingleLine(true);
        host.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        try {
            host.setTypeface(ResourcesCompat.getFont(context, R.font.tektur_semibold));
        } catch (RuntimeException ignored) {
            // The theme's font.
        }
        row.addView(host, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageButton copy = new ImageButton(context);
        copy.setImageResource(R.drawable.projects_ic_copy);
        copy.setImageTintList(ContextCompat.getColorStateList(context, R.color.projects_ash_300));
        copy.setBackgroundResource(R.drawable.projects_icon_button_ripple);
        copy.setContentDescription(context.getString(R.string.projects_social_copy_address));
        int pad = Math.round(12 * dp);
        copy.setPadding(pad, pad, pad, pad);
        copy.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        Press.attach(copy, Press.Style.ICON);
        View.OnClickListener copyIt = v -> {
            SocialUi.copy(context, "Projects", address);
            Haptics.confirm(v);
            SocialUi.toast(context, context.getString(R.string.projects_social_address_copied));
        };
        copy.setOnClickListener(copyIt);
        host.setOnLongClickListener(v -> {
            copyIt.onClick(v);
            return true;
        });
        row.addView(copy, new LinearLayout.LayoutParams(Math.round(44 * dp), Math.round(44 * dp)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(12 * dp);
        box.addView(row, lp);
        return box;
    }
}
