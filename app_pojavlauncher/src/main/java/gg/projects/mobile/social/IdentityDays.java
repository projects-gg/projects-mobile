package gg.projects.mobile.social;

import androidx.annotation.Nullable;

import java.util.Calendar;

import gg.projects.mobile.cloud.TrGrammar;
import gg.projects.mobile.ui.AppDates;

/**
 * SOC-ID: the day a way in was linked, as "Bağlı hesaplar" says it: "25 Ekim'de bağlandı" (the locative after the
 * month as it is read: Ekim'de, Kasım'da, Aralık'ta; another year's after the year: "3 Ocak 2027'de") / "Linked on 25
 * October". The service sends a Turkey day ({@code YYYY-MM-DD}); it is written as that calendar day. Pure Java.
 */
public final class IdentityDays {
    private IdentityDays() {}

    /** "25 Ekim'de" / "25 October" (another year than {@code nowMillis}'s: with it), or null without a day. */
    @Nullable
    public static String linkedDay(boolean turkish, @Nullable String isoDay, long nowMillis) {
        Long at = AppDates.isoDay(isoDay);
        if (at == null) return null;
        Calendar day = Calendar.getInstance(AppDates.UTC);
        day.setTimeInMillis(at);
        Calendar now = Calendar.getInstance(AppDates.UTC);
        now.setTimeInMillis(nowMillis);
        boolean thisYear = day.get(Calendar.YEAR) == now.get(Calendar.YEAR);
        if (turkish) {
            int d = day.get(Calendar.DAY_OF_MONTH);
            int m = day.get(Calendar.MONTH) + 1;
            return thisYear ? TrGrammar.locativeDate(d, m) : TrGrammar.locativeDate(d, m, day.get(Calendar.YEAR));
        }
        return thisYear ? AppDates.dayMonth(false, at, AppDates.UTC) : AppDates.longDate(false, at, AppDates.UTC);
    }
}
