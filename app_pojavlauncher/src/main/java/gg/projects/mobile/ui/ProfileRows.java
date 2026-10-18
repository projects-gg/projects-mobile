package gg.projects.mobile.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

/**
 * How a profile reads in the play bar and in the profile list (M14): its name on the first line,
 * what it plays on the second in the meta style ("Minecraft 26.1.2, Fabric"); Amethyst's
 * "(Default)" profile is called "Varsayılan"/"Default" in the player's language.
 */
public final class ProfileRows {
    private ProfileRows() {}

    private static Typeface sRegular;

    public static CharSequence label(Context context, @Nullable String name, @Nullable String versionId) {
        // Historically, the profile name "New" was hardcoded as the default profile name (upstream).
        String profileName = name != null && !name.trim().isEmpty() && !"New".equalsIgnoreCase(name) ? name : null;
        if (VersionLabel.isDefaultName(profileName)) profileName = context.getString(R.string.projects_profile_default_name);
        String version;
        if (MinecraftProfile.LATEST_RELEASE.equalsIgnoreCase(versionId)) version = context.getString(R.string.profiles_latest_release);
        else if (MinecraftProfile.LATEST_SNAPSHOT.equalsIgnoreCase(versionId)) version = context.getString(R.string.profiles_latest_snapshot);
        else version = VersionLabel.describe(versionId);

        if (profileName == null) return version;
        if (version.isEmpty()) return profileName;
        SpannableStringBuilder text = new SpannableStringBuilder(profileName).append('\n');
        int start = text.length();
        text.append(version);
        if (sRegular == null) sRegular = ResourcesCompat.getFont(context, R.font.figtree_regular);
        if (sRegular != null) text.setSpan(new FontSpan(sRegular), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new AbsoluteSizeSpan(13, true), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new ForegroundColorSpan(ContextCompat.getColor(context, R.color.projects_ash_400)),
                start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }

    /** A row of the profile list: the gold ripple, a gold tint on the profile in use. */
    public static void listRowBackground(View row, boolean selected) {
        int start = row.getPaddingStart();
        int top = row.getPaddingTop();
        int end = row.getPaddingEnd();
        int bottom = row.getPaddingBottom();
        row.setBackgroundResource(selected ? R.drawable.projects_profile_item_selected : R.drawable.projects_profile_item);
        // The backgrounds are inset from the sheet's sides, and a background's insets replace the
        // view's padding: the icons sat on the tint's edge instead of inside it.
        row.setPaddingRelative(start, top, end, bottom);
    }
}
