package net.kdt.pojavlaunch.customcontrols.handleview;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import static net.kdt.pojavlaunch.Tools.currentDisplayMetrics;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.os.ConfigurationCompat;

import com.kdt.SideDialogView;

import gg.projects.mobile.controls.EditorRules;
import gg.projects.mobile.controls.KeyNames;
import gg.projects.mobile.language.LanguageManager;

import net.kdt.pojavlaunch.EfficientAndroidLWJGLKeycode;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.colorselector.ColorSelector;
import net.kdt.pojavlaunch.customcontrols.ControlData;
import net.kdt.pojavlaunch.customcontrols.ControlDrawerData;
import net.kdt.pojavlaunch.customcontrols.ControlJoystickData;
import net.kdt.pojavlaunch.customcontrols.ControlLayout;
import net.kdt.pojavlaunch.customcontrols.buttons.ControlDrawer;
import net.kdt.pojavlaunch.customcontrols.buttons.ControlInterface;
import net.kdt.pojavlaunch.utils.interfaces.SimpleItemSelectedListener;
import net.kdt.pojavlaunch.utils.interfaces.SimpleSeekBarListener;
import net.kdt.pojavlaunch.utils.interfaces.SimpleTextWatcher;

import java.util.List;
import java.util.Locale;

public class EditControlSideDialog extends SideDialogView {

    private final Spinner[] mKeycodeSpinners = new Spinner[4];
    public boolean internalChanges = false; // True when we programmatically change stuff.
    private final View.OnLayoutChangeListener mLayoutChangedListener = new View.OnLayoutChangeListener() {
        @Override
        public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
            if (internalChanges) return;

            internalChanges = true;
            // Projects fork (M053): the fields hold dp (the same number on every screen); a resize by the handle shows
            // its new size there. Upstream compared the field with the right edge and wrote pixels.
            float width = safeParseFloat(mWidthEditText.getText().toString());
            int widthDp = Math.round(Tools.pxToDp(right - left));
            if (width < 0 || Math.abs(widthDp - width) > 1) {
                mWidthEditText.setText(EditorRules.sizeText(widthDp));
            }
            float height = safeParseFloat(mHeightEditText.getText().toString());
            int heightDp = Math.round(Tools.pxToDp(bottom - top));
            if (height < 0 || Math.abs(heightDp - height) > 1) {
                mHeightEditText.setText(EditorRules.sizeText(heightDp));
            }

            internalChanges = false;
        }
    };
    private EditText mNameEditText, mWidthEditText, mHeightEditText;
    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private Switch mToggleSwitch, mPassthroughSwitch, mSwipeableSwitch, mForwardLockSwitch, mAbsoluteTrackingSwitch;
    private Spinner mOrientationSpinner;
    private final TextView[] mKeycodeTextviews = new TextView[4];
    private SeekBar mStrokeWidthSeekbar, mCornerRadiusSeekbar, mAlphaSeekbar;
    private TextView mStrokePercentTextView, mCornerRadiusPercentTextView, mAlphaPercentTextView;
    private TextView mSelectBackgroundColor, mSelectStrokeColor;
    private ArrayAdapter<String> mAdapter;
    private List<String> mSpecialArray;
    private CheckBox mDisplayInGameCheckbox, mDisplayInMenuCheckbox;
    private ControlInterface mCurrentlyEditedButton;
    // Decorative textviews
    private TextView mOrientationTextView, mMappingTextView, mNameTextView,
            mCornerRadiusTextView, mVisibilityTextView, mSizeTextview, mSizeXTextView;

    // Color selector related stuff
    private ColorSelector mColorSelector;
    private final ViewGroup mParent;
    // Projects fork (MOB-NEXT, the lead's decision 2 Nov): the edited key's action row ("Sil", "Kopyala", "Tuş ekle")
    // lives in the panel's header while the panel is open, so it never covers a key.
    private ActionRow mHeaderRow;
    // Projects fork (M053): the panel's row groups a key type hides as a whole.
    private View mMappingGroup, mSizeRow, mCornerRadiusHead;

    public EditControlSideDialog(Context context, ViewGroup parent) {
        super(context, parent, R.layout.dialog_control_button_setting);
        mParent = parent;
    }

    @Override
    protected void onInflate() {
        bindLayout();
        buildColorSelector();
        loadAdapter();
        setupRealTimeListeners();
        // Projects fork (MOB-NEXT): the action row at the top, in line with the panel's words.
        mHeaderRow = new ActionRow(mParent.getContext(), true);
        int side = mParent.getResources().getDimensionPixelOffset(R.dimen.padding_moderate);
        android.widget.LinearLayout.LayoutParams params = new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, mParent.getResources().getDimensionPixelOffset(R.dimen._40sdp));
        params.setMargins(side, side, side, 0);
        addHeader(mHeaderRow, params);
        if (mCurrentlyEditedButton != null) mHeaderRow.setFollowedButton(mCurrentlyEditedButton);
    }

    @Override
    protected void onDestroy() {
        mColorSelector.disappear(true);
        if (mHeaderRow != null) mHeaderRow.setFollowedButton(null);
        mHeaderRow = null;
    }

    private void buildColorSelector() {
        mColorSelector = new ColorSelector(mParent.getContext(), mParent, null);
    }

    /**
     * Slide the layout into the visible screen area
     */
    public void appearColor(boolean fromRight, int color) {
        mColorSelector.show(fromRight, color == -1 ? Color.WHITE : color);
    }

    /**
     * Slide out the layout
     */
    public void disappearColor() {
        mColorSelector.disappear(false);
    }

    /**
     * Slide out the first visible layer.
     *
     * @return True if the last layer is disappearing
     */
    public boolean disappearLayer() {
        if (mColorSelector.isDisplaying()) {
            disappearColor();
            return false;
        } else {
            disappear(false);
            return true;
        }
    }

    /**
     * Switch the panels position if needed
     */
    public void adaptPanelPosition() {
        if (mDisplaying) {
            boolean isAtRight = mCurrentlyEditedButton.getControlView().getX() + mCurrentlyEditedButton.getControlView().getWidth() / 2f < currentDisplayMetrics.widthPixels / 2f;
            appear(isAtRight);
            if (mColorSelector.isDisplaying()) {
                Tools.runOnUiThread(() -> appearColor(isAtRight, mCurrentlyEditedButton.getProperties().bgColor));
            }
        }
    }

    /** Projects fork (MOB-NEXT): the language's own percent, "%10" in Turkish (upstream's format read "10%" in both). */
    public static void setPercentageText(TextView textView, int progress) {
        Locale locale = ConfigurationCompat.getLocales(textView.getResources().getConfiguration()).get(0);
        textView.setText(EditorRules.percent(locale != null ? locale : Locale.getDefault(), progress));
    }

    /* LOADING VALUES */

    /**
     * Load values for basic control data
     */
    public void loadValues(ControlData data) {
        setDefaultVisibilitySetting();
        mOrientationTextView.setVisibility(GONE);
        mOrientationSpinner.setVisibility(GONE);
        mForwardLockSwitch.setVisibility(GONE);
        mAbsoluteTrackingSwitch.setVisibility(GONE);

        mNameEditText.setText(data.name);
        // Projects fork (MOB-NEXT): whole numbers, not the float's "121.00001" (loaded with internalChanges set: the
        // layout keeps its value until the player types one).
        // M053: in dp, the same number on a phone and a tablet (upstream's pixels read 121 on one and 88 on another).
        mWidthEditText.setText(EditorRules.sizeText(Tools.pxToDp(data.getWidth())));
        mHeightEditText.setText(EditorRules.sizeText(Tools.pxToDp(data.getHeight())));

        mAlphaSeekbar.setProgress((int) (data.opacity * 100));
        mStrokeWidthSeekbar.setProgress((int) data.strokeWidth * 10);
        mCornerRadiusSeekbar.setProgress((int) data.cornerRadius);

        setPercentageText(mAlphaPercentTextView, (int) (data.opacity * 100));
        setPercentageText(mStrokePercentTextView, (int) data.strokeWidth * 10);
        setPercentageText(mCornerRadiusPercentTextView, (int) data.cornerRadius);

        mToggleSwitch.setChecked(data.isToggle);
        mPassthroughSwitch.setChecked(data.passThruEnabled);
        mSwipeableSwitch.setChecked(data.isSwipeable);

        mDisplayInGameCheckbox.setChecked(data.displayInGame);
        mDisplayInMenuCheckbox.setChecked(data.displayInMenu);

        showSwatches(data);

        for (int i = 0; i < data.keycodes.length; i++) {
            if (data.keycodes[i] < 0) {
                mKeycodeSpinners[i].setSelection(data.keycodes[i] + mSpecialArray.size());
            } else {
                mKeycodeSpinners[i].setSelection(EfficientAndroidLWJGLKeycode.getIndexByValue(data.keycodes[i]) + mSpecialArray.size());
            }
        }
    }

    /**
     * Load values for extended control data
     */
    public void loadValues(ControlDrawerData data) {
        loadValues(data.properties);

        mOrientationSpinner.setSelection(
                ControlDrawerData.orientationToInt(data.orientation));

        mMappingTextView.setVisibility(GONE);
        for (int i = 0; i < mKeycodeSpinners.length; i++) {
            mKeycodeSpinners[i].setVisibility(GONE);
            mKeycodeTextviews[i].setVisibility(GONE);
        }
        mMappingGroup.setVisibility(GONE);

        mOrientationTextView.setVisibility(VISIBLE);
        mOrientationSpinner.setVisibility(VISIBLE);

        mSwipeableSwitch.setVisibility(View.GONE);
        mPassthroughSwitch.setVisibility(View.GONE);
        mToggleSwitch.setVisibility(View.GONE);
    }

    /**
     * Load values for the joystick
     */
    public void loadJoystickValues(ControlJoystickData data) {
        loadValues(data);

        mMappingTextView.setVisibility(GONE);
        for (int i = 0; i < mKeycodeSpinners.length; i++) {
            mKeycodeSpinners[i].setVisibility(GONE);
            mKeycodeTextviews[i].setVisibility(GONE);
        }
        mMappingGroup.setVisibility(GONE);
        mCornerRadiusHead.setVisibility(GONE);

        mNameTextView.setVisibility(GONE);
        mNameEditText.setVisibility(GONE);

        mCornerRadiusTextView.setVisibility(GONE);
        mCornerRadiusSeekbar.setVisibility(GONE);
        mCornerRadiusPercentTextView.setVisibility(GONE);

        mSwipeableSwitch.setVisibility(View.GONE);
        mPassthroughSwitch.setVisibility(View.GONE);
        mToggleSwitch.setVisibility(View.GONE);

        mForwardLockSwitch.setVisibility(VISIBLE);
        mForwardLockSwitch.setChecked(data.forwardLock);

        mAbsoluteTrackingSwitch.setVisibility(VISIBLE);
        mAbsoluteTrackingSwitch.setChecked(data.absolute);
    }

    /**
     * Load values for sub buttons
     */
    public void loadSubButtonValues(ControlData data, ControlDrawerData.Orientation drawerOrientation) {
        loadValues(data);

        // Size linked to the parent drawer depending on the drawer settings
        if(drawerOrientation != ControlDrawerData.Orientation.FREE){
            mSizeTextview.setVisibility(GONE);
            mSizeXTextView.setVisibility(GONE);
            mWidthEditText.setVisibility(GONE);
            mHeightEditText.setVisibility(GONE);
            mSizeRow.setVisibility(GONE);
        }

        // No conditional, already depends on the parent drawer visibility
        mVisibilityTextView.setVisibility(GONE);
        mDisplayInMenuCheckbox.setVisibility(GONE);
        mDisplayInGameCheckbox.setVisibility(GONE);
    }

    private void loadAdapter() {
        //Initialize adapter for keycodes
        // Projects fork (M053): the app's list rows, and keys named as a player reads them ("Sol Shift", "Sağ tık",
        // "Yok") instead of Android's code names (KeyNames; the order and so the saved codes are upstream's).
        mAdapter = new ArrayAdapter<>(mDialogContent.getContext(), R.layout.item_centered_textview);
        mSpecialArray = ControlData.buildSpecialButtonArray();

        boolean turkish = LanguageManager.isTurkish(mDialogContent.getContext());
        for (String name : mSpecialArray) mAdapter.add(KeyNames.of(name, turkish));
        for (String name : EfficientAndroidLWJGLKeycode.generateKeyName()) mAdapter.add(KeyNames.of(name, turkish));
        mAdapter.setDropDownViewResource(R.layout.item_projects_ctrl_choice);

        for (Spinner spinner : mKeycodeSpinners) {
            spinner.setAdapter(mAdapter);
        }

        // Orientation spinner. Projects fork (MOB-NEXT): the directions in the player's language, in
        // ControlDrawerData.Orientation's order (upstream showed the names in code: "Yön: FREE").
        ArrayAdapter<CharSequence> adapter = new ArrayAdapter<>(mDialogContent.getContext(), R.layout.item_projects_ctrl_value,
                mDialogContent.getResources().getTextArray(R.array.projects_ctrl_drawer_orientations));
        adapter.setDropDownViewResource(R.layout.item_projects_ctrl_choice);

        mOrientationSpinner.setAdapter(adapter);
    }

    private void setDefaultVisibilitySetting() {
        // Projects fork (M053): the panel groups rows (size, keys, slider heads): every view in it shows again.
        showAll((ViewGroup) mDialogContent);
        for(Spinner s : mKeycodeSpinners) {
            s.setVisibility(View.INVISIBLE);
        }
    }

    private static void showAll(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); ++i) {
            View child = group.getChildAt(i);
            child.setVisibility(VISIBLE);
            if (child instanceof ViewGroup && !(child instanceof Spinner)) showAll((ViewGroup) child);
        }
    }

    /** Projects fork (M053): each colour row ends in a swatch of the key's colour (round, with a hairline). */
    private void showSwatches(ControlData data) {
        swatch(mSelectBackgroundColor, data.bgColor);
        swatch(mSelectStrokeColor, data.strokeColor);
    }

    private static void swatch(TextView row, int color) {
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(color);
        float density = row.getResources().getDisplayMetrics().density;
        dot.setStroke(Math.max(1, Math.round(density)), 0x33FFFFFF);
        int size = Math.round(20 * density);
        dot.setSize(size, size);
        row.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, dot, null);
    }

    private void bindLayout() {
        mMappingGroup = mDialogContent.findViewById(R.id.projects_ctrl_mapping_group);
        mSizeRow = mDialogContent.findViewById(R.id.projects_ctrl_size_row);
        mCornerRadiusHead = mDialogContent.findViewById(R.id.projects_ctrl_corner_head);
        mNameEditText = mDialogContent.findViewById(R.id.editName_editText);
        mWidthEditText = mDialogContent.findViewById(R.id.editSize_editTextX);
        mHeightEditText = mDialogContent.findViewById(R.id.editSize_editTextY);
        mToggleSwitch = mDialogContent.findViewById(R.id.checkboxToggle);
        mPassthroughSwitch = mDialogContent.findViewById(R.id.checkboxPassThrough);
        mSwipeableSwitch = mDialogContent.findViewById(R.id.checkboxSwipeable);
        mForwardLockSwitch = mDialogContent.findViewById(R.id.checkboxForwardLock);
        mAbsoluteTrackingSwitch = mDialogContent.findViewById(R.id.checkboxAbsoluteFingerTracking);
        mKeycodeSpinners[0] = mDialogContent.findViewById(R.id.editMapping_spinner_1);
        mKeycodeSpinners[1] = mDialogContent.findViewById(R.id.editMapping_spinner_2);
        mKeycodeSpinners[2] = mDialogContent.findViewById(R.id.editMapping_spinner_3);
        mKeycodeSpinners[3] = mDialogContent.findViewById(R.id.editMapping_spinner_4);
        mKeycodeTextviews[0] = mDialogContent.findViewById(R.id.mapping_1_textview);
        mKeycodeTextviews[1] = mDialogContent.findViewById(R.id.mapping_2_textview);
        mKeycodeTextviews[2] = mDialogContent.findViewById(R.id.mapping_3_textview);
        mKeycodeTextviews[3] = mDialogContent.findViewById(R.id.mapping_4_textview);
        mOrientationSpinner = mDialogContent.findViewById(R.id.editOrientation_spinner);
        mStrokeWidthSeekbar = mDialogContent.findViewById(R.id.editStrokeWidth_seekbar);
        mCornerRadiusSeekbar = mDialogContent.findViewById(R.id.editCornerRadius_seekbar);
        mAlphaSeekbar = mDialogContent.findViewById(R.id.editButtonOpacity_seekbar);
        mSelectBackgroundColor = mDialogContent.findViewById(R.id.editBackgroundColor_textView);
        mSelectStrokeColor = mDialogContent.findViewById(R.id.editStrokeColor_textView);
        mStrokePercentTextView = mDialogContent.findViewById(R.id.editStrokeWidth_textView_percent);
        mAlphaPercentTextView = mDialogContent.findViewById(R.id.editButtonOpacity_textView_percent);
        mCornerRadiusPercentTextView = mDialogContent.findViewById(R.id.editCornerRadius_textView_percent);
        mDisplayInGameCheckbox = mDialogContent.findViewById(R.id.visibility_game_checkbox);
        mDisplayInMenuCheckbox = mDialogContent.findViewById(R.id.visibility_menu_checkbox);

        // Projects fork (MOB-NEXT): a slider's fill and thumb and a checkbox are bone (design.md §6), not the theme's gold.
        ColorStateList bone = ColorStateList.valueOf(ContextCompat.getColor(mDialogContent.getContext(), R.color.projects_bone_100));
        for (SeekBar bar : new SeekBar[]{mStrokeWidthSeekbar, mCornerRadiusSeekbar, mAlphaSeekbar}) {
            bar.setProgressTintList(bone);
            bar.setThumbTintList(bone);
        }
        mDisplayInGameCheckbox.setButtonTintList(bone);
        mDisplayInMenuCheckbox.setButtonTintList(bone);

        //Decorative stuff
        mMappingTextView = mDialogContent.findViewById(R.id.editMapping_textView);
        mOrientationTextView = mDialogContent.findViewById(R.id.editOrientation_textView);
        mNameTextView = mDialogContent.findViewById(R.id.editName_textView);
        mCornerRadiusTextView = mDialogContent.findViewById(R.id.editCornerRadius_textView);
        mVisibilityTextView = mDialogContent.findViewById(R.id.visibility_textview);
        mSizeTextview = mDialogContent.findViewById(R.id.editSize_textView);
        mSizeXTextView = mDialogContent.findViewById(R.id.editSize_x_textView);
    }

    /**
     * A long function linking all the displayed data on the popup and,
     * the currently edited mCurrentlyEditedButton
     * @noinspection SuspiciousNameCombination
     */
    private void setupRealTimeListeners() {
        // Projects fork (CTRL-SAVE): every change below is a change to save (changed()); upstream counted none of
        // them, so leaving the editor after a resize offered no save and dropped it.
        mNameEditText.addTextChangedListener((SimpleTextWatcher) s -> {
            if (internalChanges) return;

            boolean renamed = !s.toString().equals(mCurrentlyEditedButton.getProperties().name);
            mCurrentlyEditedButton.getProperties().name = s.toString();

            // Cheap and unoptimized, doesn't break the abstraction layer
            mCurrentlyEditedButton.setProperties(mCurrentlyEditedButton.getProperties(), false);
            // A keyboard committing the same text again is no change.
            if (renamed) changed();
        });

        mWidthEditText.addTextChangedListener((SimpleTextWatcher) s -> {
            if (internalChanges) return;

            float width = safeParseFloat(s.toString());
            if (width >= 0) {
                width = Tools.dpToPx(width); // M053: the field holds dp
                float before = mCurrentlyEditedButton.getProperties().getWidth();
                mCurrentlyEditedButton.getProperties().setWidth(width);
                if (mCurrentlyEditedButton.getProperties() instanceof ControlJoystickData) {
                    // Joysticks are square
                     mCurrentlyEditedButton.getProperties().setHeight(width);
                }
                mCurrentlyEditedButton.updateProperties();
                if (before != mCurrentlyEditedButton.getProperties().getWidth()) changed();
            }
        });

        mHeightEditText.addTextChangedListener((SimpleTextWatcher) s -> {
            if (internalChanges) return;

            float height = safeParseFloat(s.toString());
            if (height >= 0) {
                height = Tools.dpToPx(height); // M053: the field holds dp
                float before = mCurrentlyEditedButton.getProperties().getHeight();
                mCurrentlyEditedButton.getProperties().setHeight(height);
                if (mCurrentlyEditedButton.getProperties() instanceof ControlJoystickData) {
                    // Joysticks are square
                    mCurrentlyEditedButton.getProperties().setWidth(height);
                }
                mCurrentlyEditedButton.updateProperties();
                if (before != mCurrentlyEditedButton.getProperties().getHeight()) changed();
            }
        });

        mSwipeableSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (internalChanges) return;
            mCurrentlyEditedButton.getProperties().isSwipeable = isChecked;
            changed();
        });
        mToggleSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (internalChanges) return;
            mCurrentlyEditedButton.getProperties().isToggle = isChecked;
            changed();
        });
        mPassthroughSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (internalChanges) return;
            mCurrentlyEditedButton.getProperties().passThruEnabled = isChecked;
            changed();
        });
        mForwardLockSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (internalChanges) return;
            if(mCurrentlyEditedButton.getProperties() instanceof ControlJoystickData){
                ((ControlJoystickData) mCurrentlyEditedButton.getProperties()).forwardLock = isChecked;
                changed();
            }
        });
        mAbsoluteTrackingSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (internalChanges) return;
            if(mCurrentlyEditedButton.getProperties() instanceof ControlJoystickData){
                ((ControlJoystickData) mCurrentlyEditedButton.getProperties()).absolute = isChecked;
                changed();
            }
        });

        mAlphaSeekbar.setOnSeekBarChangeListener((SimpleSeekBarListener) (seekBar, progress, fromUser) -> {
            if (internalChanges) return;
            mCurrentlyEditedButton.getProperties().opacity = mAlphaSeekbar.getProgress() / 100f;
            mCurrentlyEditedButton.getControlView().setAlpha(mAlphaSeekbar.getProgress() / 100f);
            setPercentageText(mAlphaPercentTextView, progress);
            changed();
        });

        mStrokeWidthSeekbar.setOnSeekBarChangeListener((SimpleSeekBarListener) (seekBar, progress, fromUser) -> {
            if (internalChanges) return;
            mCurrentlyEditedButton.getProperties().strokeWidth = mStrokeWidthSeekbar.getProgress() / 10F;
            mCurrentlyEditedButton.setBackground();
            setPercentageText(mStrokePercentTextView, progress);
            changed();
        });

        mCornerRadiusSeekbar.setOnSeekBarChangeListener((SimpleSeekBarListener) (seekBar, progress, fromUser) -> {
            if (internalChanges) return;
            mCurrentlyEditedButton.getProperties().cornerRadius = mCornerRadiusSeekbar.getProgress();
            mCurrentlyEditedButton.setBackground();
            setPercentageText(mCornerRadiusPercentTextView, progress);
            changed();
        });


        for (int i = 0; i < mKeycodeSpinners.length; ++i) {
            int finalI = i;
            mKeycodeTextviews[i].setOnClickListener(v -> mKeycodeSpinners[finalI].performClick());

            mKeycodeSpinners[i].setOnItemSelectedListener((SimpleItemSelectedListener) (parent, view, position, id) -> {
                // Side note, spinner listeners are fired later than all the other ones.
                // Meaning the internalChanges bool is useless here.
                int before = mCurrentlyEditedButton.getProperties().keycodes[finalI];
                if (position < mSpecialArray.size()) {
                    mCurrentlyEditedButton.getProperties().keycodes[finalI] = mKeycodeSpinners[finalI].getSelectedItemPosition() - mSpecialArray.size();
                } else {
                    mCurrentlyEditedButton.getProperties().keycodes[finalI] = EfficientAndroidLWJGLKeycode.getValueByIndex(mKeycodeSpinners[finalI].getSelectedItemPosition() - mSpecialArray.size());
                }
                mKeycodeTextviews[finalI].setText((String) mKeycodeSpinners[finalI].getSelectedItem());
                // Showing a key's settings selects its keys too: only another key is a change.
                if (before != mCurrentlyEditedButton.getProperties().keycodes[finalI]) changed();
            });
        }


        mOrientationSpinner.setOnItemSelectedListener((SimpleItemSelectedListener) (parent, view, position, id) -> {
            // Side note, spinner listeners are fired later than all the other ones.
            // Meaning the internalChanges bool is useless here.

            if (mCurrentlyEditedButton instanceof ControlDrawer) {
                ControlDrawerData.Orientation before = ((ControlDrawer) mCurrentlyEditedButton).drawerData.orientation;
                ((ControlDrawer) mCurrentlyEditedButton).drawerData.orientation = ControlDrawerData.intToOrientation(mOrientationSpinner.getSelectedItemPosition());
                ((ControlDrawer) mCurrentlyEditedButton).syncButtons();
                if (before != ((ControlDrawer) mCurrentlyEditedButton).drawerData.orientation) changed();
            }
        });

        mDisplayInGameCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (internalChanges) return;
            mCurrentlyEditedButton.getProperties().displayInGame = isChecked;
            changed();
        });

        mDisplayInMenuCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (internalChanges) return;
            mCurrentlyEditedButton.getProperties().displayInMenu = isChecked;
            changed();
        });

        mSelectStrokeColor.setOnClickListener(v -> {
            mColorSelector.setAlphaEnabled(false);
            mColorSelector.setColorSelectionListener(color -> {
                if (mCurrentlyEditedButton.getProperties().strokeColor != color) changed();
                mCurrentlyEditedButton.getProperties().strokeColor = color;
                mCurrentlyEditedButton.setBackground();
                swatch(mSelectStrokeColor, color);
            });
            appearColor(isAtRight(), mCurrentlyEditedButton.getProperties().strokeColor);
        });

        mSelectBackgroundColor.setOnClickListener(v -> {
            mColorSelector.setAlphaEnabled(true);
            mColorSelector.setColorSelectionListener(color -> {
                if (mCurrentlyEditedButton.getProperties().bgColor != color) changed();
                mCurrentlyEditedButton.getProperties().bgColor = color;
                mCurrentlyEditedButton.setBackground();
                swatch(mSelectBackgroundColor, color);
            });
            appearColor(isAtRight(), mCurrentlyEditedButton.getProperties().bgColor);
        });
    }

    /** Projects fork (CTRL-SAVE): the player changed the key: "Kaydet" turns gold and leaving asks. */
    private void changed() {
        if (mParent instanceof ControlLayout) ((ControlLayout) mParent).setModified(true);
    }

    private float safeParseFloat(String string) {
        float out = -1; // -1
        try {
            out = Float.parseFloat(string);
        } catch (NumberFormatException e) {
            Log.e("EditControlPopup", e.toString());
        }
        return out;
    }

    public void setCurrentlyEditedButton(ControlInterface button) {
        if (mCurrentlyEditedButton != null)
            mCurrentlyEditedButton.getControlView().removeOnLayoutChangeListener(mLayoutChangedListener);
        mCurrentlyEditedButton = button;
        mCurrentlyEditedButton.getControlView().addOnLayoutChangeListener(mLayoutChangedListener);
        if (mHeaderRow != null) mHeaderRow.setFollowedButton(button);
    }

    /** Projects fork (MOB-NEXT): whether the panel rests (or is to rest) at the screen's right. */
    public boolean restsAtRight() {
        float[] span = restingSpan();
        return span != null && span[0] > currentDisplayMetrics.widthPixels / 2f;
    }

}
