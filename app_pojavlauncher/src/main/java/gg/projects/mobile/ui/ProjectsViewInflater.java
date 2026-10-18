package gg.projects.mobile.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatViewInflater;
import androidx.appcompat.widget.AppCompatButton;
import androidx.appcompat.widget.AppCompatCheckBox;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.appcompat.widget.AppCompatRadioButton;
import androidx.appcompat.widget.AppCompatSeekBar;
import androidx.appcompat.widget.AppCompatSpinner;

/**
 * The theme's {@code viewInflaterClass} (M14): every {@code <Button>}, {@code <ImageButton>},
 * {@code <Spinner>}, {@code <SeekBar>}, {@code <CheckBox>}, {@code <RadioButton>} and switch the
 * app inflates, upstream's screens and dialogs included, gets the Projects press feedback and
 * haptics without touching each layout. Views upstream builds in code (the in-game controls) are
 * not affected.
 */
public class ProjectsViewInflater extends AppCompatViewInflater {
    public ProjectsViewInflater() {}

    @NonNull
    @Override
    protected AppCompatButton createButton(Context context, AttributeSet attrs) {
        return new PressButton(context, attrs);
    }

    @NonNull
    @Override
    protected AppCompatImageButton createImageButton(Context context, AttributeSet attrs) {
        return new PressImageButton(context, attrs);
    }

    @NonNull
    @Override
    protected AppCompatSpinner createSpinner(Context context, AttributeSet attrs) {
        return new PressSpinner(context, attrs);
    }

    @NonNull
    @Override
    protected AppCompatSeekBar createSeekBar(Context context, AttributeSet attrs) {
        return new TickSeekBar(context, attrs);
    }

    @NonNull
    @Override
    protected AppCompatCheckBox createCheckBox(Context context, AttributeSet attrs) {
        return new ToggleCheckBox(context, attrs);
    }

    @NonNull
    @Override
    protected AppCompatRadioButton createRadioButton(Context context, AttributeSet attrs) {
        return new ToggleRadioButton(context, attrs);
    }

    @Nullable
    @Override
    protected View createView(Context context, String name, AttributeSet attrs) {
        // androidx.preference's SwitchPreference uses the platform Switch, SwitchPreferenceCompat and
        // the mod screen SwitchCompat; both keep their class (the preference checks instanceof Switch).
        if ("Switch".equals(name) || "android.widget.Switch".equals(name)) return new ToggleSwitch(context, attrs);
        if ("androidx.appcompat.widget.SwitchCompat".equals(name)) return new ToggleSwitchCompat(context, attrs);
        return null;
    }
}
