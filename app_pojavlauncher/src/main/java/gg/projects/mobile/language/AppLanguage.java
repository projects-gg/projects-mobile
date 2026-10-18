package gg.projects.mobile.language;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which language the app speaks (the owner's rule of 18 October 2026, shared with the desktop
 * launcher and the website). Projects is a Turkish server and its news are Turkish, so:
 *
 * <ol>
 * <li>the player's own choice in Settings → "Dil", when they made one;</li>
 * <li>in Turkey: Turkish, whatever the phone's language;</li>
 * <li>a phone that prefers Turkish: Turkish;</li>
 * <li>otherwise English.</li>
 * </ol>
 *
 * <p>"In Turkey" is read without network requests or permissions: the phone's time zone (Turkey,
 * and Northern Cyprus, which speaks Turkish), or the country of its mobile network or SIM. The
 * game's first language and the default control labels follow the same answer.
 */
public final class AppLanguage {
    private AppLanguage() {}

    /** Follow the rule above (the default). */
    public static final String AUTO = "auto";
    public static final String TURKISH = "tr";
    public static final String ENGLISH = "en";

    /**
     * Time zones of Turkey (with the old aliases) and of Northern Cyprus ({@code Asia/Famagusta}:
     * Turkish-speaking, on Turkish mobile networks; the Republic of Cyprus is {@code Asia/Nicosia}).
     */
    static final Set<String> TURKEY_ZONES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "Europe/Istanbul", "Asia/Istanbul", "Turkey", "Asia/Famagusta")));

    /** A stored choice as one of {@link #AUTO}, {@link #TURKISH} and {@link #ENGLISH}. */
    public static String normalizeChoice(String stored) {
        if (TURKISH.equals(stored) || ENGLISH.equals(stored)) return stored;
        return AUTO;
    }

    /**
     * True when the phone is in Turkey: its time zone is Turkey's (or Northern Cyprus'), or its mobile
     * network or SIM is Turkish (country codes as {@code TelephonyManager} gives them, any case).
     */
    public static boolean inTurkey(String timeZoneId, String networkCountry, String simCountry) {
        if (timeZoneId != null && TURKEY_ZONES.contains(timeZoneId.trim())) return true;
        return isTurkey(networkCountry) || isTurkey(simCountry);
    }

    private static boolean isTurkey(String country) {
        return country != null && country.trim().equalsIgnoreCase("tr");
    }

    /**
     * True when the phone's language list (BCP 47 tags, most preferred first) puts Turkish before
     * English: the one of the app's two languages Android itself would pick from it.
     */
    public static boolean phonePrefersTurkish(List<String> phoneLanguageTags) {
        if (phoneLanguageTags == null) return false;
        for (String tag : phoneLanguageTags) {
            String language = language(tag);
            if (TURKISH.equals(language)) return true;
            if (ENGLISH.equals(language)) return false;
        }
        return false;
    }

    /** The language the app speaks: {@link #TURKISH} or {@link #ENGLISH} (see the class comment). */
    public static String resolve(String choice, boolean inTurkey, List<String> phoneLanguageTags) {
        String normalized = normalizeChoice(choice);
        if (!AUTO.equals(normalized)) return normalized;
        if (inTurkey) return TURKISH;
        return phonePrefersTurkish(phoneLanguageTags) ? TURKISH : ENGLISH;
    }

    /**
     * The app locale to give Android 13+ ({@code LocaleManager}), as a language tag: the chosen
     * language for an explicit choice, and for {@link #AUTO} nothing ({@code ""}, the system default)
     * while the phone already speaks the resolved language, else that language (an English phone in
     * Turkey gets Turkish set, so the system's "App languages" page says what the app shows).
     */
    public static String localeToApply(String choice, String effective, List<String> phoneLanguageTags) {
        if (!AUTO.equals(normalizeChoice(choice))) return effective;
        boolean phoneTurkish = phonePrefersTurkish(phoneLanguageTags);
        boolean phoneMatches = TURKISH.equals(effective) ? phoneTurkish : !phoneTurkish;
        return phoneMatches ? "" : effective;
    }

    /**
     * What an app locale set outside the app (Android's "App languages" page) means as a choice:
     * the system default is {@link #AUTO}, Turkish is Turkish, anything else English (the app has
     * only those two).
     */
    public static String choiceFromSystem(String languageTags) {
        if (languageTags == null || languageTags.trim().isEmpty()) return AUTO;
        String first = languageTags.split(",")[0];
        return TURKISH.equals(language(first)) ? TURKISH : ENGLISH;
    }

    /** The language part of a tag, lower case ("tr-TR" and "tr_TR" give "tr"). */
    static String language(String tag) {
        if (tag == null) return "";
        String trimmed = tag.trim();
        int cut = trimmed.length();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '-' || c == '_') {
                cut = i;
                break;
            }
        }
        return trimmed.substring(0, cut).toLowerCase(Locale.ROOT);
    }
}
