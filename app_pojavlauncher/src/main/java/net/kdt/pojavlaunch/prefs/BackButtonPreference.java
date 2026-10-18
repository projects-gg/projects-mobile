package net.kdt.pojavlaunch.prefs;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;

public class BackButtonPreference extends Preference {
    public BackButtonPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    @SuppressWarnings("unused") public BackButtonPreference(Context context) {
        this(context, null);
    }

    private void init(){
        if(getTitle() == null){
            setTitle(R.string.preference_back_title);
        }
        // Projects fork (M14): drawn as the screen's header (back and the screen's title, set by
        // LauncherPreferenceFragment); only the back button reacts, the title is not a row.
        setLayoutResource(R.layout.projects_preference_header);
        setSelectable(false);
        setPersistent(false);
        setIconSpaceReserved(false);
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        View back = holder.findViewById(R.id.projects_header_back);
        if (back != null) back.setOnClickListener(v -> onClick());
    }


    @Override
    protected void onClick() {
        // It is caught by an ExtraListener in the LauncherActivity
        ExtraCore.setValue(ExtraConstants.BACK_PREFERENCE, "true");
    }
}
