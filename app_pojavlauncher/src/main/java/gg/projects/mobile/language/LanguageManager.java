package gg.projects.mobile.language;

import android.app.Activity;
import android.app.LocaleManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import net.kdt.pojavlaunch.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import gg.projects.mobile.controls.ControlLayouts;

/**
 * Applies {@link AppLanguage}: the language the whole app speaks, chosen in Settings → "Dil"
 * ("Otomatik", "Türkçe", "English").
 *
 * <p>Android 13 and later keep an app locale per app and show it on the system's "App languages"
 * page ({@code res/xml/locales_config.xml}); the app sets it to what it speaks and reads back a
 * change made on that page as the player's choice. Older versions have no such setting, so the
 * app's contexts are given the language here (activities through {@code BaseActivity}, the
 * application through {@code PojavApplication}; the application keeps a plain {@code ContextImpl},
 * which manifest receivers need).
 */
public final class LanguageManager {
    private LanguageManager() {}

    private static final String TAG = "ProjectsLanguage";
    /** Settings → "Dil": {@link AppLanguage#AUTO}, {@link AppLanguage#TURKISH} or {@link AppLanguage#ENGLISH}. */
    public static final String KEY_CHOICE = "projects_language";
    /** The app locale this app last gave Android 13+ ("" for the system default), to notice changes made elsewhere. */
    private static final String KEY_APPLIED = "projects_language_applied";
    /** Upstream's "show the interface in English" switch, replaced by {@link #KEY_CHOICE}. */
    private static final String KEY_FORCE_ENGLISH = "force_english";

    private static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext() != null
                ? context.getApplicationContext() : context);
    }

    /** The saved choice; upstream's English switch, when it was on, becomes "English" once. */
    public static String choice(Context context) {
        SharedPreferences prefs = prefs(context);
        if (prefs.contains(KEY_FORCE_ENGLISH)) {
            SharedPreferences.Editor editor = prefs.edit().remove(KEY_FORCE_ENGLISH);
            if (prefs.getBoolean(KEY_FORCE_ENGLISH, false) && !prefs.contains(KEY_CHOICE)) {
                editor.putString(KEY_CHOICE, AppLanguage.ENGLISH);
            }
            editor.commit();
        }
        return AppLanguage.normalizeChoice(prefs.getString(KEY_CHOICE, AppLanguage.AUTO));
    }

    /** {@link AppLanguage#inTurkey} from the time zone and the mobile network and SIM countries (no permission needed). */
    public static boolean inTurkey(Context context) {
        String network = null;
        String sim = null;
        try {
            TelephonyManager telephony = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (telephony != null) {
                network = telephony.getNetworkCountryIso();
                sim = telephony.getSimCountryIso();
            }
        } catch (RuntimeException e) {
            // A phone without telephony, or a vendor that guards it: the time zone alone decides.
            Log.i(TAG, "no telephony country: " + e);
        }
        return AppLanguage.inTurkey(TimeZone.getDefault().getID(), network, sim);
    }

    /**
     * The phone's own languages, most preferred first, never the app locale (Android 13+ applies
     * that to the process, so the system's list is asked for directly there).
     */
    @SuppressWarnings("deprecation")
    public static List<String> phoneLanguages(Context context) {
        List<String> tags = new ArrayList<>();
        LocaleList locales = null;
        if (Build.VERSION.SDK_INT >= 33) {
            LocaleManager manager = context.getSystemService(LocaleManager.class);
            if (manager != null) locales = manager.getSystemLocales();
        }
        Configuration system = Resources.getSystem().getConfiguration();
        if (locales == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) locales = system.getLocales();
        if (locales != null) {
            for (int i = 0; i < locales.size(); i++) tags.add(locales.get(i).toLanguageTag());
        } else if (system.locale != null) {
            tags.add(system.locale.toLanguageTag());
        }
        return tags;
    }

    /** {@link AppLanguage#TURKISH} or {@link AppLanguage#ENGLISH}: what the app speaks now. */
    public static String effective(Context context) {
        return AppLanguage.resolve(choice(context), inTurkey(context), phoneLanguages(context));
    }

    public static boolean isTurkish(Context context) {
        return AppLanguage.TURKISH.equals(effective(context));
    }

    /** The locale of {@link #effective}: the phone's own variant of it when it has one (tr-TR, en-GB). */
    public static Locale effectiveLocale(Context context) {
        String language = effective(context);
        for (String tag : phoneLanguages(context)) {
            Locale locale = Locale.forLanguageTag(tag);
            if (language.equals(locale.getLanguage())) return locale;
        }
        return AppLanguage.TURKISH.equals(language) ? new Locale("tr", "TR") : Locale.ENGLISH;
    }

    /**
     * {@code base} speaking the app's language: unchanged when it already does, else a configuration
     * context (a {@code ContextImpl}, so it may serve as an application's base context too).
     */
    public static Context wrap(Context base) {
        try {
            Locale locale = effectiveLocale(base);
            Configuration current = base.getResources().getConfiguration();
            if (sameLanguage(locale, primary(current))) return base;
            Configuration config = new Configuration(current);
            setLocale(config, locale);
            return base.createConfigurationContext(config);
        } catch (RuntimeException e) {
            // Never block a start over the language.
            Log.w(TAG, "language not applied", e);
            return base;
        }
    }

    /**
     * Brings the process in line with the app's language: Java's default locale (dates, relative
     * times), the application's resources on Android 12 and older, and Android 13+'s app locale
     * (which also takes in a change made on the system's "App languages" page as the choice).
     */
    public static void apply(Context context) {
        try {
            if (Build.VERSION.SDK_INT >= 33) syncSystemLocale(context);
        } catch (RuntimeException e) {
            Log.w(TAG, "app locale not set", e);
        }
        applyLocal(context);
    }

    /**
     * The part of {@link #apply} that stays in this process: Java's default locale and, on Android 12
     * and older, the application's resources. Safe in the game's process (it never asks Android to
     * change the app locale, which would start running activities again).
     */
    public static void applyLocal(Context context) {
        try {
            Locale locale = effectiveLocale(context);
            if (!sameLanguage(locale, Locale.getDefault())) Locale.setDefault(locale);
            if (Build.VERSION.SDK_INT < 33) updateResources(context.getApplicationContext(), locale);
        } catch (RuntimeException e) {
            Log.w(TAG, "language not applied", e);
        }
    }

    /**
     * True on Android 12 and older when {@code activity} still shows another language than the app
     * speaks now ("Otomatik" after the time zone or the phone's language changed): it should start
     * again. Android 13+ restarts activities itself when the app locale changes.
     */
    public static boolean outdated(Activity activity) {
        if (Build.VERSION.SDK_INT >= 33) return false;
        try {
            return !sameLanguage(effectiveLocale(activity), primary(activity.getResources().getConfiguration()));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Saves the player's choice and applies it; on Android 12 and older {@code activity} starts again in it. */
    public static void setChoice(Activity activity, String choice) {
        String before = effective(activity);
        prefs(activity).edit().putString(KEY_CHOICE, AppLanguage.normalizeChoice(choice)).commit();
        apply(activity);
        String after = effective(activity);
        ControlLayouts.syncInBackground(activity.getApplicationContext());
        // Android 13+ starts the activity again itself when the app locale changes.
        if (!before.equals(after) && Build.VERSION.SDK_INT < 33) activity.recreate();
    }

    /** The "Otomatik" entry's label, with what it resolves to: "Otomatik (Türkçe)". */
    public static String autoLabel(Context context) {
        String resolved = AppLanguage.resolve(AppLanguage.AUTO, inTurkey(context), phoneLanguages(context));
        String name = context.getString(AppLanguage.TURKISH.equals(resolved)
                ? R.string.projects_language_turkish : R.string.projects_language_english);
        return context.getString(R.string.projects_language_auto, name);
    }

    @androidx.annotation.RequiresApi(33)
    private static void syncSystemLocale(Context context) {
        LocaleManager manager = context.getSystemService(LocaleManager.class);
        if (manager == null) return;
        SharedPreferences prefs = prefs(context);
        String current = manager.getApplicationLocales().toLanguageTags();
        String applied = prefs.getString(KEY_APPLIED, null);
        if (applied != null ? !applied.equals(current) : !current.isEmpty()) {
            // Changed on the system's "App languages" page (or set there before the first start).
            prefs.edit().putString(KEY_CHOICE, AppLanguage.choiceFromSystem(current)).commit();
        }
        String choice = choice(context);
        List<String> phone = phoneLanguages(context);
        String effective = AppLanguage.resolve(choice, inTurkey(context), phone);
        String wanted = AppLanguage.localeToApply(choice, effective, phone);
        if (!wanted.equals(current)) {
            manager.setApplicationLocales(wanted.isEmpty() ? LocaleList.getEmptyLocaleList() : LocaleList.forLanguageTags(wanted));
        }
        if (!wanted.equals(applied)) prefs.edit().putString(KEY_APPLIED, wanted).commit();
    }

    @SuppressWarnings("deprecation")
    private static void updateResources(@Nullable Context context, Locale locale) {
        if (context == null) return;
        Resources resources = context.getResources();
        Configuration config = new Configuration(resources.getConfiguration());
        if (sameLanguage(locale, primary(config))) return;
        setLocale(config, locale);
        resources.updateConfiguration(config, resources.getDisplayMetrics());
    }

    @SuppressWarnings("deprecation")
    private static Locale primary(Configuration config) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) return config.getLocales().isEmpty() ? null : config.getLocales().get(0);
        return config.locale;
    }

    private static boolean sameLanguage(Locale a, @Nullable Locale b) {
        return b != null && a.getLanguage().equals(b.getLanguage());
    }

    private static void setLocale(Configuration config, Locale locale) {
        config.setLocale(locale);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) config.setLocales(new LocaleList(locale));
    }
}
