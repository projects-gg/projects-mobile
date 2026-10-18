package net.kdt.pojavlaunch.prefs.screens;


import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import androidx.preference.TwoStatePreference;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.LauncherActivity;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import gg.projects.mobile.language.AppLanguage;
import gg.projects.mobile.language.LanguageManager;

/**
 * Preference for the main screen, any sub-screen should inherit this class for consistent behavior,
 * overriding only onCreatePreferences
 */
public class LauncherPreferenceFragment extends PreferenceFragmentCompat implements SharedPreferences.OnSharedPreferenceChangeListener {

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        view.setBackgroundColor(getResources().getColor(R.color.background_app));
        super.onViewCreated(view, savedInstanceState);
        // Projects fork (M14): the screen's header names it; rows press (gold ripple, a light tick);
        // no dividers between rows, the category titles group them.
        PreferenceScreen screen = getPreferenceScreen();
        for (int i = 0; screen != null && i < screen.getPreferenceCount(); i++) {
            Preference preference = screen.getPreference(i);
            if (preference instanceof net.kdt.pojavlaunch.prefs.BackButtonPreference) preference.setTitle(headerTitle());
        }
        setDivider(null);
        RecyclerView list = getListView();
        if (list != null) {
            list.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
                @Override
                public void onChildViewAttachedToWindow(@NonNull View row) {
                    gg.projects.mobile.ui.Press.attach(row, gg.projects.mobile.ui.Press.Style.ROW);
                }

                @Override
                public void onChildViewDetachedFromWindow(@NonNull View row) {}
            });
        }
    }

    /** Projects fork (M14): the title in this screen's header. */
    private int headerTitle() {
        if (this instanceof LauncherPreferenceVideoFragment) return R.string.preference_video_title;
        if (this instanceof LauncherPreferenceControlFragment) return R.string.preference_control_title;
        if (this instanceof LauncherPreferenceJavaFragment) return R.string.preference_java_title;
        if (this instanceof LauncherPreferenceMiscellaneousFragment) return R.string.preference_misc_title;
        if (this instanceof LauncherPreferenceExperimentalFragment) return R.string.preference_experimental_title;
        if (this instanceof LauncherPreferenceRendererSettingsFragment) return R.string.preference_renderer_mobileglues_settings;
        return R.string.projects_settings_title;
    }

    /** Projects fork (M14): a switch row plays the toggle haptic when it flips. */
    @Override
    public boolean onPreferenceTreeClick(@NonNull Preference preference) {
        if (preference instanceof TwoStatePreference) {
            gg.projects.mobile.ui.Haptics.toggle(getListView(), ((TwoStatePreference) preference).isChecked());
        }
        return super.onPreferenceTreeClick(preference);
    }

    @Override
    public void onCreatePreferences(Bundle b, String str) {
        addPreferencesFromResource(R.xml.pref_main);
        setupNotificationRequestPreference();
        setupLanguagePreference();
    }

    /** Projects fork: "Dil": "Otomatik (Türkçe)", "Türkçe", "English" (each language in its own words). */
    private void setupLanguagePreference() {
        ListPreference language = requirePreference(LanguageManager.KEY_CHOICE, ListPreference.class);
        language.setEntryValues(new CharSequence[]{AppLanguage.AUTO, AppLanguage.TURKISH, AppLanguage.ENGLISH});
        showLanguageChoice(language);
        language.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        language.setOnPreferenceChangeListener((preference, value) -> {
            LanguageManager.setChoice(requireActivity(), String.valueOf(value));
            return true;
        });
    }

    /**
     * The entries and the current choice. Shown again on resume: the choice may have changed since
     * (Android's "App languages" page, adopted when the launcher resumes) and so may what
     * "Otomatik" stands for (the time zone).
     */
    private void showLanguageChoice(ListPreference language) {
        language.setEntries(new CharSequence[]{LanguageManager.autoLabel(requireContext()),
                getString(R.string.projects_language_turkish), getString(R.string.projects_language_english)});
        language.setValue(LanguageManager.choice(requireContext()));
    }

    private void setupNotificationRequestPreference() {
        Preference mRequestNotificationPermissionPreference = requirePreference("notification_permission_request");
        Preference mMicrophonePermissionPreference = requirePreference("microphone_permission_request");
        Activity activity = getActivity();
        if(activity instanceof LauncherActivity) {
            LauncherActivity launcherActivity = (LauncherActivity)activity;
            mRequestNotificationPermissionPreference.setVisible(!launcherActivity.checkForNotificationPermission());
            mRequestNotificationPermissionPreference.setOnPreferenceClickListener(preference -> {
                launcherActivity.askForNotificationPermission(()->mRequestNotificationPermissionPreference.setVisible(false));
                return true;
            });
            mMicrophonePermissionPreference.setVisible(!launcherActivity.checkForMicrophonePermission());
            mMicrophonePermissionPreference.setOnPreferenceClickListener(preference -> {
                launcherActivity.askForMicrophonePermission(()->mMicrophonePermissionPreference.setVisible(false));
                return true;
            });
        }else{
            mRequestNotificationPermissionPreference.setVisible(false);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        ListPreference language = findPreference(LanguageManager.KEY_CHOICE);
        if (language != null) showLanguageChoice(language);
        SharedPreferences sharedPreferences = getPreferenceManager().getSharedPreferences();
        if(sharedPreferences != null) sharedPreferences.registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onPause() {
        SharedPreferences sharedPreferences = getPreferenceManager().getSharedPreferences();
        if(sharedPreferences != null) sharedPreferences.unregisterOnSharedPreferenceChangeListener(this);
        super.onPause();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences p, String s) {
        LauncherPreferences.loadPreferences(getContext());
    }

    protected Preference requirePreference(CharSequence key) {
        Preference preference = findPreference(key);
        if(preference != null) return preference;
        throw new IllegalStateException("Preference "+key+" is null");
    }
    @SuppressWarnings("unchecked")
    protected <T extends Preference> T requirePreference(CharSequence key, Class<T> preferenceClass) {
        Preference preference = requirePreference(key);
        if(preferenceClass.isInstance(preference)) return (T)preference;
        throw new IllegalStateException("Preference "+key+" is not an instance of "+preferenceClass.getSimpleName());
    }
}
