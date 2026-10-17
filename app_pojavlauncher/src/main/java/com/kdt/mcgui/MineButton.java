package com.kdt.mcgui;

import android.content.*;
import android.graphics.*;
import android.util.*;

import androidx.core.content.res.ResourcesCompat;

import net.kdt.pojavlaunch.R;

public class MineButton extends androidx.appcompat.widget.AppCompatButton {
	
	public MineButton(Context ctx) {
		this(ctx, null);
	}
	
	public MineButton(Context ctx, AttributeSet attrs) {
		super(ctx, attrs);
		init();
		// Projects fork: sentence case unless the layout asks for capitals (only OYNA does; design.md).
		// Turkish capitals also turned brand names into "MİCROSOFT", "FABRİC" and "OPTİFİNE".
		setAllCaps(attrs != null && attrs.getAttributeBooleanValue("http://schemas.android.com/apk/res/android", "textAllCaps", false));
	}

	public void init() {
		// Projects fork: gold "hextech cut" primary button in Tektur (was the purple Amethyst 9-patch)
		setTypeface(ResourcesCompat.getFont(getContext(), R.font.tektur_semibold));
		setBackground(gg.projects.mobile.ui.ChamferDrawable.primary(getContext()));
		setTextColor(androidx.core.content.ContextCompat.getColorStateList(getContext(), R.color.projects_on_gold_text));
		setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen._13ssp));
	}

}
