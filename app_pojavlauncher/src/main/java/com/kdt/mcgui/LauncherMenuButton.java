package com.kdt.mcgui;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;


import net.kdt.pojavlaunch.R;

import fr.spse.extended_view.ExtendedButton;

public class LauncherMenuButton extends ExtendedButton {

    public LauncherMenuButton(@NonNull Context context) {
        super(context);
        setSettings();
    }
    public LauncherMenuButton(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setSettings();
    }


    /** Set style stuff */
    private void setSettings(){
        Resources resources = getContext().getResources();

        // Projects fork (M14): a calm 56dp row: a 24dp Phosphor icon, Figtree 16sp, the gold ripple
        // of the theme and the row press (a light tick, barely moves).
        int padding = resources.getDimensionPixelSize(R.dimen.projects_space_lg);
        setCompoundDrawablePadding(padding);
        setPaddingRelative(padding, 0, padding, 0);
        setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        setMinHeight(resources.getDimensionPixelSize(R.dimen.projects_menu_row));
        setMinimumHeight(resources.getDimensionPixelSize(R.dimen.projects_menu_row));

        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        setTextColor(androidx.core.content.ContextCompat.getColor(getContext(), R.color.projects_bone_100));
        setTypeface(ResourcesCompat.getFont(getContext(), R.font.figtree_regular));
        setSingleLine(true);
        setEllipsize(android.text.TextUtils.TruncateAt.END);

        // Set drawable size
        int[] sizes = getExtendedViewData().getSizeCompounds();
        sizes[0] = resources.getDimensionPixelSize(R.dimen.projects_icon);
        getExtendedViewData().setSizeCompounds(sizes);
        postProcessDrawables();
        gg.projects.mobile.ui.Press.attach(this, gg.projects.mobile.ui.Press.Style.ROW);
    }
}
