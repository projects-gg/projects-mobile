package net.kdt.pojavlaunch.utils;

import android.content.Context;
import android.content.ContextWrapper;

import gg.projects.mobile.language.LanguageManager;

/**
 * Projects fork: gives a context the app's language (Settings → "Dil", see
 * {@link LanguageManager}); upstream's "force English" switch became the "English" choice there.
 */
public class LocaleUtils extends ContextWrapper {

    public LocaleUtils(Context base) {
        super(base);
    }

    /**
     * {@code context} in the app's language, wrapped. An application must take the wrapped context
     * ({@link #getBaseContext()}): manifest receivers need its base to be a plain {@code ContextImpl}.
     */
    public static ContextWrapper setLocale(Context context) {
        return new LocaleUtils(LanguageManager.wrap(context));
    }
}
