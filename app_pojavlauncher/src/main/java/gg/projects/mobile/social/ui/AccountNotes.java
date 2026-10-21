package gg.projects.mobile.social.ui;

import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import net.kdt.pojavlaunch.R;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import gg.projects.mobile.social.SocialModel.Limited;
import gg.projects.mobile.social.SocialModel.Me;
import gg.projects.mobile.social.SocialModel.ModNotice;
import gg.projects.mobile.social.SocialService;

/**
 * What the account says about itself (M20): "Sınırlı hesap" (SPAM1, only the player sees it) and the
 * moderators' one-time notes (MOD1: a warning, a profile picture put back), each shown once on the
 * first screen that sees it and then marked seen.
 */
public final class AccountNotes {
    private AccountNotes() {}

    /** A notes dialog is up (two screens may ask in the same moment). */
    private static boolean sShowing;

    /** "Sınırlı hesap": until when, what the phone knows of the limits, "Bu sınırlar kendiliğinden kalkar." */
    public static void limited(FragmentActivity activity, Limited limited) {
        float dp = activity.getResources().getDisplayMetrics().density;
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Math.round(22 * dp), Math.round(4 * dp), Math.round(22 * dp), 0);
        TextView lead = new TextView(activity);
        lead.setText(activity.getString(R.string.projects_limited_body, SocialUi.day(activity, limited.until)));
        lead.setTextSize(15);
        lead.setTextColor(ContextCompat.getColor(activity, R.color.projects_bone_100));
        content.addView(lead);
        if (limited.has(Limited.CREW_FOUND)) item(activity, content, activity.getString(R.string.projects_limited_crew_found));
        if (limited.has(Limited.CREW_INVITES)) {
            item(activity, content, activity.getString(R.string.projects_limited_crew_invites, limited.perDay(Limited.CREW_INVITES)));
        }
        if (limited.has(Limited.CODE_LOOKUPS)) {
            item(activity, content, activity.getString(R.string.projects_limited_code_lookups, limited.perDay(Limited.CODE_LOOKUPS)));
        }
        // The phone keeps no settings backup: that limit says nothing here.
        if (limited.has(Limited.CHAT_MESSAGES)) {
            item(activity, content, activity.getString(R.string.projects_limited_chat, limited.perDay(Limited.CHAT_MESSAGES)));
        }
        // M21 (TEAM5): invite links and plans (joining, asking to join and answering plans stay free).
        if (limited.has(Limited.CREW_LINK)) item(activity, content, activity.getString(R.string.projects_limited_crew_link));
        if (limited.has(Limited.CREW_PLANS)) item(activity, content, activity.getString(R.string.projects_limited_crew_plans));
        TextView lifts = new TextView(activity);
        lifts.setText(R.string.projects_limited_lifts);
        lifts.setTextSize(13);
        lifts.setTextColor(ContextCompat.getColor(activity, R.color.projects_ash_400));
        lifts.setPadding(0, Math.round(12 * dp), 0, 0);
        content.addView(lifts);
        new AlertDialog.Builder(activity)
                .setTitle(R.string.projects_limited_chip)
                .setView(content)
                .setPositiveButton(R.string.projects_mod_ok, null)
                .show();
    }

    private static void item(FragmentActivity activity, LinearLayout content, String text) {
        float dp = activity.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, Math.round(8 * dp), 0, 0);
        ImageView lock = new ImageView(activity);
        lock.setImageResource(R.drawable.projects_ic_lock);
        lock.setImageTintList(ContextCompat.getColorStateList(activity, R.color.projects_gold_300));
        lock.setImportantForAccessibility(ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams lockLp = new LinearLayout.LayoutParams(Math.round(16 * dp), Math.round(16 * dp));
        lockLp.topMargin = Math.round(2 * dp);
        row.addView(lock, lockLp);
        TextView label = new TextView(activity);
        label.setText(text);
        label.setTextSize(14);
        label.setTextColor(ContextCompat.getColor(activity, R.color.projects_ash_300));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginStart(Math.round(10 * dp));
        row.addView(label, lp);
        content.addView(row);
    }

    /**
     * The moderators' notes not seen yet, in one dialog ("Anladım", "Topluluk kuralları"); marked seen
     * when it closes. True when one shows.
     */
    public static boolean showNotices(final FragmentActivity activity) {
        if (sShowing || activity.isFinishing() || activity.getSupportFragmentManager().isStateSaved()) return false;
        final SocialService service = SocialService.get(activity);
        Me me = service.state().me();
        if (me == null || me.notices.isEmpty() || !service.gate().reports) return false;
        List<ModNotice> notices = me.notices;
        final Set<String> ids = new HashSet<>();
        StringBuilder text = new StringBuilder();
        boolean warning = false;
        for (ModNotice notice : notices) {
            ids.add(notice.id);
            String line;
            if (ModNotice.PROFILE_RESET.equals(notice.kind)) {
                line = activity.getString(R.string.projects_mod_profile_reset);
            } else {
                line = activity.getString(R.string.projects_mod_warning);
                warning = true;
            }
            // The moderator's reason, as the report dialog names it ("Neden: Spam"); none when unknown.
            int reason = ReportDialog.noticeReason(notice.reason);
            if (reason != 0) line += "\n" + activity.getString(R.string.projects_mod_reason, activity.getString(reason));
            // The same note twice says it once.
            if (text.indexOf(line) >= 0) continue;
            if (text.length() > 0) text.append("\n\n");
            text.append(line);
        }
        sShowing = true;
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(warning ? R.string.projects_mod_warning_title : R.string.projects_mod_profile_reset_title)
                .setMessage(text.toString())
                .setPositiveButton(R.string.projects_mod_ok, null)
                .setNeutralButton(R.string.projects_mod_rules, (d, w) -> SocialUi.openRules(activity))
                .create();
        dialog.setOnDismissListener(d -> {
            sShowing = false;
            service.noticesSeen(ids);
        });
        dialog.show();
        return true;
    }
}
