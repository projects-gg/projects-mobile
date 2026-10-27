package net.kdt.pojavlaunch.profiles;

import android.content.Context;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import fr.spse.extended_view.ExtendedTextView;
import gg.projects.mobile.join.ProfileOrder;
import gg.projects.mobile.join.ProjectsProfile;

/*
 * Adapter for listing launcher profiles in a Spinner
 */
public class ProfileAdapter extends BaseAdapter {
    /**
     * MOB2: the "Resmî paketi kur" row (the Projects emblem dimmed, "Kurulu değil" under it), first in
     * the list while the Projects profile is missing: where that profile stands otherwise.
     */
    public static final Object OFFICIAL_ROW = new Object() {
        @NonNull
        @Override
        public String toString() {
            return "projects_official_row";
        }
    };

    private Map<String, MinecraftProfile> mProfiles;
    private final MinecraftProfile dummy = new MinecraftProfile();
    private List<String> mProfileList;
    private ProfileAdapterExtra[] mExtraEntires;
    private boolean mOfficialRow;
    private Drawable mOfficialIcon;
    private CharSequence mOfficialLabel;

    public ProfileAdapter(ProfileAdapterExtra[] extraEntries) {
        reloadProfiles(extraEntries);
    }

    /** MOB2: offers {@link #OFFICIAL_ROW} while the Projects profile is missing (the play bar's list). */
    public void setOfficialRow(boolean offered) {
        if (mOfficialRow == offered) return;
        mOfficialRow = offered;
        super.notifyDataSetChanged();
    }

    /** Rows before the profiles: the official pack's row while it stands in for the Projects profile. */
    private int leadingRows() {
        return mOfficialRow && !mProfiles.containsKey(ProjectsProfile.KEY) ? 1 : 0;
    }

    /*
     * Gets how much profiles are loaded in the adapter right now
     * @returns loaded profile count
     */
    @Override
    public int getCount() {
        return leadingRows() + mProfileList.size() + mExtraEntires.length;
    }
    /*
     * Gets the profile at a given index
     * @param position index to retreive
     * @returns MinecraftProfile name or null
     */
    @Override
    public Object getItem(int position) {
        int leading = leadingRows();
        if (position >= 0 && position < leading) return OFFICIAL_ROW;
        position -= leading;
        int profileListSize = mProfileList.size();
        int extraPosition = position - profileListSize;
        if(position >= 0 && position < profileListSize){
            String profileName = mProfileList.get(position);
            if(mProfiles.containsKey(profileName)) return profileName;
        }else if(extraPosition >= 0 && extraPosition < mExtraEntires.length) {
            return mExtraEntires[extraPosition];
        }
        return null;
    }



    public int resolveProfileIndex(String name) {
        int index = mProfileList.indexOf(name);
        return index < 0 ? -1 : index + leadingRows();
    }

    /**
     * MOB2: where the list's first profile is ({@link ProfileOrder}), or -1 without profiles. A choice
     * that is gone falls here, never to a HashMap's first entry or to a row that is no profile.
     */
    public int firstProfilePosition() {
        return mProfileList.isEmpty() ? -1 : leadingRows();
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public void notifyDataSetChanged() {
        mProfiles = new HashMap<>(LauncherProfiles.mainProfileJson.profiles);
        // MOB2: the list's own order (Projects, the default profile, then by name), not a HashMap's.
        mProfileList = ProfileOrder.keys(mProfiles, Locale.getDefault());
        super.notifyDataSetChanged();
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View v = convertView;
        if (v == null) {
            v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_version_profile_layout,parent,false);
            gg.projects.mobile.ui.Press.attach(v, gg.projects.mobile.ui.Press.Style.ROW); // Projects fork (M14)
        }
        setView(v, getItem(position), true);
        return v;
    }

    public void setViewProfile(View v, String nm, boolean displaySelection) {
        ExtendedTextView extendedTextView = (ExtendedTextView) v;

        MinecraftProfile minecraftProfile = mProfiles.get(nm);
        if(minecraftProfile == null) minecraftProfile = dummy;
        Drawable cachedIcon = ProfileIconCache.fetchIcon(v.getResources(), nm, minecraftProfile.icon);
        extendedTextView.setCompoundDrawablesRelative(cachedIcon, null, extendedTextView.getCompoundsDrawables()[2], null);

        // Projects fork (M14): the name on the first line, what it plays on the second ("Minecraft
        // 26.1.2, Fabric"); upstream wrote "Name - fabric-loader-0.19.5-26.1.2" on one line.
        extendedTextView.setText(gg.projects.mobile.ui.ProfileRows.label(v.getContext(), minecraftProfile.name, minecraftProfile.lastVersionId));

        // Set selected background if needed (Projects fork: a gold tint in the list; the play bar's
        // own row keeps its ripple instead of being painted transparent)
        if(displaySelection){
            String selectedProfile = LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE,"");
            gg.projects.mobile.ui.ProfileRows.listRowBackground(extendedTextView, selectedProfile.equals(nm));
        }
    }

    public void setViewExtra(View v, ProfileAdapterExtra extra) {
        ExtendedTextView extendedTextView = (ExtendedTextView) v;
        extendedTextView.setCompoundDrawablesRelative(extra.icon, null, extendedTextView.getCompoundsDrawables()[2], null);
        extendedTextView.setText(extra.name);
        if (!(v instanceof com.kdt.mcgui.mcVersionSpinner)) gg.projects.mobile.ui.ProfileRows.listRowBackground(extendedTextView, false);
    }

    /**
     * MOB2: the official pack's quiet row, as the desktop's "Kurulu değil" card: the emblem at half
     * strength without its colours, "Resmî paketi kur" over "Kurulu değil" in the meta style. No gold,
     * never the selected tint.
     */
    public void setViewOfficial(View v) {
        ExtendedTextView extendedTextView = (ExtendedTextView) v;
        Context context = v.getContext();
        if (mOfficialIcon == null) mOfficialIcon = dimmedEmblem(context);
        if (mOfficialLabel == null) mOfficialLabel = officialLabel(context);
        extendedTextView.setCompoundDrawablesRelative(mOfficialIcon, null, extendedTextView.getCompoundsDrawables()[2], null);
        extendedTextView.setText(mOfficialLabel);
        if (!(v instanceof com.kdt.mcgui.mcVersionSpinner)) gg.projects.mobile.ui.ProfileRows.listRowBackground(extendedTextView, false);
    }

    private static Drawable dimmedEmblem(Context context) {
        Drawable emblem = ResourcesCompat.getDrawable(context.getResources(), R.drawable.ic_projects_profile, null);
        if (emblem == null) return null;
        // Its own copy: the profile icon cache hands the same drawable to the Projects profile's rows.
        emblem = emblem.mutate();
        ColorMatrix gray = new ColorMatrix();
        gray.setSaturation(0f);
        emblem.setColorFilter(new ColorMatrixColorFilter(gray));
        emblem.setAlpha(128);
        return emblem;
    }

    private static CharSequence officialLabel(Context context) {
        SpannableStringBuilder text = new SpannableStringBuilder(context.getString(R.string.mob_profile_official_install)).append('\n');
        int start = text.length();
        text.append(context.getString(R.string.mob_profile_official_missing));
        Typeface regular = ResourcesCompat.getFont(context, R.font.figtree_regular);
        if (regular != null) text.setSpan(new gg.projects.mobile.ui.FontSpan(regular), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new AbsoluteSizeSpan(Math.round(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 13, context.getResources().getDisplayMetrics()))), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new ForegroundColorSpan(ContextCompat.getColor(context, R.color.projects_ash_400)),
                start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }

    public void setView(View v, Object object, boolean displaySelection) {
        if(object instanceof String) {
            setViewProfile(v, (String) object, displaySelection);
        }else if(object instanceof ProfileAdapterExtra) {
            setViewExtra(v, (ProfileAdapterExtra) object);
        }else if(object == OFFICIAL_ROW) {
            setViewOfficial(v);
        }
    }

    /** Reload profiles from the file */
    public void reloadProfiles(){
        LauncherProfiles.load();
        notifyDataSetChanged();
    }

    /** Reload profiles from the file, with additional extra entries */
    public void reloadProfiles(ProfileAdapterExtra[] extraEntries) {
        if(extraEntries == null) mExtraEntires = new ProfileAdapterExtra[0];
        else mExtraEntires = extraEntries;
        this.reloadProfiles();
    }
}
