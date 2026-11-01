package gg.projects.mobile.afk.ui;

import android.content.Context;
import android.os.SystemClock;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import gg.projects.mobile.afk.api.AfkStatus;
import gg.projects.mobile.afk.api.PhoneAfk;

/**
 * The account list's "AFK'da" (phone-afk.md §5, the desktop's account mark): which names are AFK on this phone. The
 * list asks once per row while it draws; the answer is read at most every 1.5 s (one read for the whole list, not one
 * per row), and nothing is read where the feature is off.
 */
public final class AfkMarks {
    private AfkMarks() {}

    private static final long FRESH_MS = 1500;
    private static long sReadAt = -FRESH_MS;
    private static Set<String> sNames = Collections.emptySet();

    /** {@code name} (any case) is AFK on this phone now. */
    @MainThread
    public static boolean afk(Context context, @Nullable String name) {
        if (name == null || !PhoneAfk.phoneSupported()) return false;
        long now = SystemClock.uptimeMillis();
        if (now - sReadAt >= FRESH_MS) {
            sReadAt = now;
            sNames = names(context);
        }
        return sNames.contains(name.toLowerCase(Locale.ROOT));
    }

    private static Set<String> names(Context context) {
        if (!AfkUi.available(context)) return Collections.emptySet();
        Set<String> out = new HashSet<>();
        for (AfkStatus s : PhoneAfk.get(context).statuses()) {
            if (s.state.running()) out.add(s.name.toLowerCase(Locale.ROOT));
        }
        return out;
    }
}
