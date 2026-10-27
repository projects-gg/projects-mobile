package gg.projects.mobile.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.format.DateUtils;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import net.kdt.pojavlaunch.R;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import gg.projects.mobile.content.ContentException;
import gg.projects.mobile.content.ContentPaths;
import gg.projects.mobile.content.HomeContent;
import gg.projects.mobile.content.HttpStatusException;
import gg.projects.mobile.content.NewsFeed;
import gg.projects.mobile.content.Snapshot;
import gg.projects.mobile.reminders.Reminders;

/** Shared formatting and dialogs for the Projects screens. */
public final class ProjectsUi {
    private ProjectsUi() {}

    public static String categoryLabel(Context context, NewsFeed.NewsItem item) {
        // M17: Minecraft's own updates carry "Minecraft" in place of a category.
        if (item.isMinecraft()) return context.getString(R.string.projects_minecraft);
        switch (item.categoryOrDefault()) {
            case "update": return context.getString(R.string.projects_category_update);
            case "event": return context.getString(R.string.projects_category_event);
            case "patchNotes": return context.getString(R.string.projects_category_patch_notes);
            case "community": return context.getString(R.string.projects_category_community);
            default: return context.getString(R.string.projects_category_announcement);
        }
    }

    /**
     * What a story is, in its quiet meta line beside the time ("Yama notları · 3 gün önce"; MOB-POLISH,
     * the desktop's HOME-TASTE: no coloured category pill). Minecraft's own updates say "Minecraft"
     * with the cube mark (M17), so the server's own news reads first by its place, not by colour.
     */
    public static void bindCategory(TextView chip, NewsFeed.NewsItem item) {
        Context context = chip.getContext();
        CharSequence label = categoryLabel(context, item);
        if (!android.text.TextUtils.equals(chip.getText(), label)) chip.setText(label);
        boolean minecraft = item.isMinecraft();
        Object styled = chip.getTag(R.id.projects_chip_minecraft);
        if (styled instanceof Boolean && (Boolean) styled == minecraft) return;
        chip.setTag(R.id.projects_chip_minecraft, minecraft);
        chip.setCompoundDrawablesRelativeWithIntrinsicBounds(minecraft ? R.drawable.projects_minecraft_glyph : 0, 0, 0, 0);
        chip.setCompoundDrawablePadding(minecraft ? Math.round(4 * context.getResources().getDisplayMetrics().density) : 0);
    }

    /**
     * M17: {@code item} in the app's language: a Minecraft entry's English texts on an English
     * interface ({@code translations.en}); Projects news stays as written.
     */
    public static NewsFeed.NewsItem localized(Context context, NewsFeed.NewsItem item) {
        if (item == null || !item.isMinecraft()) return item;
        return gg.projects.mobile.language.LanguageManager.isTurkish(context) ? item : item.inEnglish();
    }

    private static Locale locale(Context context) {
        return context.getResources().getConfiguration().locale;
    }

    /** "17 Ekim 2026" / "17 October 2026" (MOB-POLISH: British English, never the phone's US order). */
    public static String date(Context context, long millis) {
        return AppDates.longDate(context, millis);
    }

    /** "17 Ekim 14:30" / "17 October 14:30": 24 hours whatever the phone's clock setting (design.md §7). */
    public static String dateTime(Context context, long millis) {
        return AppDates.dayMonth(context, millis) + " " + AppDates.time(millis);
    }

    /**
     * "az önce", "2 saat önce", "3 gün önce"; from a week on (or a clock ahead of the post) the day
     * itself in the app's dates ("17 Ekim" / "17 October", another year's with its year), never the
     * phone's US "Oct 17, 2026" nor a "0 dakika önce".
     */
    public static String relative(Context context, long millis, long now) {
        long ago = now - millis;
        if (ago >= DateUtils.WEEK_IN_MILLIS || ago < -DateUtils.MINUTE_IN_MILLIS) return AppDates.day(context, millis, now);
        if (ago < DateUtils.MINUTE_IN_MILLIS) return context.getString(R.string.projects_time_just_now);
        return DateUtils.getRelativeTimeSpanString(millis, now, DateUtils.MINUTE_IN_MILLIS).toString();
    }

    /**
     * "2 sa 14 dk", "38 dk", "45 sn" / "2 h 14 min": a compact figure in the short units (docs/design.md
     * §7). From a day on it takes whole words ("5 gün 3 saat"): Turkish "gün" has no short form, and
     * "5 gün 3 sa" mixed two styles.
     */
    public static String span(Context context, long ms) {
        long total = Math.max(0, ms) / 1000;
        long days = total / 86_400;
        long hours = (total % 86_400) / 3_600;
        long minutes = (total % 3_600) / 60;
        if (days > 0) return spanWords(context, ms);
        if (hours > 0) {
            return minutes > 0 ? context.getString(R.string.projects_span_hours_minutes, hours, minutes)
                    : context.getString(R.string.projects_span_hours, hours);
        }
        if (minutes > 0) return context.getString(R.string.projects_span_minutes, minutes);
        return context.getString(R.string.projects_span_seconds, total);
    }

    /**
     * A span in a sentence, in whole words like the column's "5 saat önce" (the desktop's
     * {@code timeLeftText}): "4 gün 23 saat", "2 saat 14 dakika", "38 dakika", "45 saniye" /
     * "2 hours 14 minutes". Two units at most, the larger first, a zero second unit left out; it
     * rounds down and says "1 saniye" to the end rather than "0 saniye".
     */
    public static String spanWords(Context context, long ms) {
        long secs = Math.max(1, ms / 1000);
        long days = secs / 86_400;
        long hours = (secs % 86_400) / 3_600;
        long minutes = (secs % 3_600) / 60;
        android.content.res.Resources r = context.getResources();
        if (days > 0) {
            String d = r.getQuantityString(R.plurals.projects_words_days, (int) days, (int) days);
            return hours > 0 ? d + " " + r.getQuantityString(R.plurals.projects_words_hours, (int) hours, (int) hours) : d;
        }
        if (hours > 0) {
            String h = r.getQuantityString(R.plurals.projects_words_hours, (int) hours, (int) hours);
            return minutes > 0 ? h + " " + r.getQuantityString(R.plurals.projects_words_minutes, (int) minutes, (int) minutes) : h;
        }
        if (minutes > 0) return r.getQuantityString(R.plurals.projects_words_minutes, (int) minutes, (int) minutes);
        return r.getQuantityString(R.plurals.projects_words_seconds, (int) secs, (int) secs);
    }

    /**
     * The event's time as one sentence in every state (the desktop's card, HOME-TASTE): "2 saat 14
     * dakika kaldı" while it runs, "20 dakika önce başladı" for one without an end, else when it
     * starts ({@link #startMoment}).
     */
    public static String eventTime(Context context, HomeContent.Event event, long now) {
        if (event.isLive(now)) {
            if (event.openEnded) {
                // "17 Ekim'de başladı" / "Started on 17 October" once "… önce" turned into a date.
                if (now - event.startsAt >= DateUtils.WEEK_IN_MILLIS) {
                    return context.getString(R.string.projects_event_started_on,
                            gg.projects.mobile.cloud.CloudWords.dayLocative(context, event.startsAt / 1000, now / 1000));
                }
                return context.getString(R.string.projects_event_started, relative(context, event.startsAt, now));
            }
            return context.getString(R.string.projects_event_ends_in, spanWords(context, event.endsAt - now));
        }
        return startMoment(context, event.startsAt, now);
    }

    /**
     * When an upcoming event starts, as a sentence like the live ones (the desktop's {@code startsText}):
     * "Bugün 20:00'de başlıyor", "Yarın …", "Cumartesi …" within the week, "25 Ekim …" beyond it (a
     * weekday alone would point at the wrong week); the time takes the ending it is read with
     * ("20:00'de", "19:30'da", "15:00'te"). English: "Starts today at 20:00", "Starts Saturday at
     * 20:00", "Starts 25 October at 20:00" (en-GB, 24 hours).
     */
    public static String startMoment(Context context, long millis, long now) {
        boolean turkish = gg.projects.mobile.language.LanguageManager.isTurkish(context);
        Locale locale = turkish ? locale(context) : Locale.UK;
        String clock = new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date(millis));
        String time = turkish ? gg.projects.mobile.cloud.TrGrammar.locativeTime(clock) : clock;
        long days = Math.round((startOfDay(millis) - startOfDay(now)) / (double) DateUtils.DAY_IN_MILLIS);
        if (days == 0) return context.getString(R.string.projects_event_starts_today, time);
        if (days == 1) return context.getString(R.string.projects_event_starts_tomorrow, time);
        String day = new SimpleDateFormat(days > 1 && days < 7 ? "EEEE" : "d MMMM", locale).format(new Date(millis));
        return context.getString(R.string.projects_event_starts_on, day, time);
    }

    private static long startOfDay(long millis) {
        Calendar day = Calendar.getInstance();
        day.setTimeInMillis(millis);
        day.set(Calendar.HOUR_OF_DAY, 0);
        day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0);
        day.set(Calendar.MILLISECOND, 0);
        return day.getTimeInMillis();
    }

    /** What a "Hatırlat" bell does when it is tapped (the home card sets or removes the reminder). */
    public interface BellHandler {
        void onBell(ReminderBellView bell, HomeContent.Event event);
    }

    /** True when every mirror answered "not found": nothing is published yet, not a connection problem. */
    public static boolean nothingPublished(@Nullable Snapshot snapshot) {
        if (snapshot == null || snapshot.lastError == null) return false;
        for (Throwable t = snapshot.lastError; t != null; t = t.getCause()) {
            if (t instanceof HttpStatusException) return ((HttpStatusException) t).status == 404;
        }
        return false;
    }

    /**
     * "Çevrim dışı. Son güncelleme: …" once a refresh failed, else null. The cached copy shown at
     * start is not news of an outage (the desktop's rule), and a retry while offline keeps the line
     * instead of hiding it until the retry fails again.
     */
    @Nullable
    public static String offlineSince(Context context, @Nullable Snapshot snapshot) {
        if (snapshot == null || !snapshot.hasContent() || !snapshot.offline || snapshot.lastError == null) return null;
        // The date and time stay on one line when the text wraps ("17 Eki 02:24", not "17" / "Eki 02:24").
        String when = dateTime(context, snapshot.fetchedAt).replace(' ', '\u00A0');
        return context.getString(R.string.projects_offline_since, when);
    }

    public static boolean updateRequired(@Nullable Snapshot snapshot) {
        return snapshot != null && snapshot.lastError != null
                && snapshot.lastError.kind == ContentException.Kind.UNSUPPORTED_SCHEMA;
    }

    /**
     * Opens an https link in the browser ({@link ContentPaths#browserUrl}; anything else is ignored).
     * When no app can open it, a short message says so.
     */
    public static void openUrl(Activity activity, String url) {
        String target = ContentPaths.browserUrl(url);
        if (target == null) return;
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(target)).addCategory(Intent.CATEGORY_BROWSABLE);
        try {
            activity.startActivity(intent);
        } catch (RuntimeException e) {
            // ActivityNotFoundException: no browser, or nothing else that opens web links.
            Toast.makeText(activity, R.string.projects_link_no_app, Toast.LENGTH_SHORT).show();
        }
    }

    /** True when tapping the event opens something: its article on the home screen or an https link. */
    public static boolean eventOpens(HomeContent home, HomeContent.Event event) {
        return (event.newsId != null && home.find(event.newsId) != null) || ContentPaths.isHttpsUrl(event.url);
    }

    /** Opens the event's article while it is on the home screen, else its https link. */
    public static void openEvent(Activity activity, Snapshot snapshot, HomeContent home, HomeContent.Event event) {
        NewsFeed.NewsItem article = event.newsId == null ? null : home.find(event.newsId);
        if (article != null) showArticle(activity, snapshot, article);
        else openUrl(activity, event.url);
    }

    /**
     * The events behind "+N etkinlik": what runs and what comes, each opening its article or link;
     * upcoming ones with their "Hatırlat" bell.
     */
    public static void showOtherEvents(final Activity activity, final Snapshot snapshot, final HomeContent home,
                                       List<HomeContent.Event> events, long now, final BellHandler bells) {
        LayoutInflater inflater = LayoutInflater.from(activity);
        View view = inflater.inflate(R.layout.dialog_projects_events, null, false);
        ViewGroup list = view.findViewById(R.id.projects_events_list);
        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.projects_events_other)
                .setView(view)
                .setPositiveButton(R.string.projects_close, null)
                .create();
        for (final HomeContent.Event event : events) {
            View row = inflater.inflate(R.layout.item_projects_event_row, list, false);
            ((TextView) row.findViewById(R.id.projects_event_row_title)).setText(event.title);
            TextView time = row.findViewById(R.id.projects_event_row_time);
            boolean live = event.isLive(now);
            // The card's sentences, no state word (the desktop's list): "2 saat 14 dakika kaldı".
            time.setText(eventTime(activity, event, now));
            if (!live) {
                final ReminderBellView bell = row.findViewById(R.id.projects_event_row_bell);
                bell.setVisibility(View.VISIBLE);
                bell.setSet(Reminders.isSet(activity, event.id), false);
                Press.attach(bell, Press.Style.ICON); // M14
                bell.setOnClickListener(v -> bells.onBell(bell, event));
            }
            if (eventOpens(home, event)) {
                // A caret opens the article on the home screen; the external mark opens the browser.
                boolean article = event.newsId != null && home.find(event.newsId) != null;
                ImageView mark = row.findViewById(R.id.projects_event_row_caret);
                mark.setImageResource(article ? R.drawable.projects_ic_caret_right : R.drawable.projects_ic_arrow_square_out);
                mark.setVisibility(View.VISIBLE);
                row.setBackgroundResource(R.drawable.projects_row_ripple);
                Press.attach(row, Press.Style.ROW); // M14
                row.setOnClickListener(v -> {
                    dialog.dismiss();
                    openEvent(activity, snapshot, home, event);
                });
            }
            list.addView(row);
        }
        dialog.show();
    }

    /**
     * Full article: cover, category, date, title, the Markdown body and, for Discord posts, the
     * original. Opening it counts as reading it: its "new" dot goes out.
     */
    public static void showArticle(final Activity activity, final Snapshot snapshot, final NewsFeed.NewsItem shown) {
        // M17: a Minecraft update in the app's language; it never moves the "new" marks of Projects news.
        final NewsFeed.NewsItem item = localized(activity, shown);
        boolean minecraft = item.isMinecraft();
        if (!minecraft) NewsSeenStore.markRead(activity, item);
        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_projects_news, null, false);
        bindCategory(view.findViewById(R.id.projects_article_category), item);
        ((TextView) view.findViewById(R.id.projects_article_date)).setText(date(activity, item.publishedAtMillis()));
        ((TextView) view.findViewById(R.id.projects_article_title)).setText(item.title);
        TextView body = view.findViewById(R.id.projects_article_body);
        CharSequence rendered = MarkdownLite.render(item.body.isEmpty() ? item.summary : item.body);
        body.setText(rendered);
        body.setMovementMethod(LinkMovementMethod.getInstance());
        TextView author = view.findViewById(R.id.projects_article_author);
        if (minecraft) {
            // The source, named under the title; the article itself opens from the dialog's button.
            author.setText(R.string.projects_minecraft_source);
            author.setVisibility(View.VISIBLE);
        } else if (item.author != null && !item.author.trim().isEmpty()) {
            author.setText(item.author.trim());
            author.setVisibility(View.VISIBLE);
        }
        CoverView cover = view.findViewById(R.id.projects_article_cover);
        cover.setMaxCoverHeight(Math.round(220 * activity.getResources().getDisplayMetrics().density));
        cover.bind(snapshot, item, true);
        final String source = item.sourceUrl();
        View discord = view.findViewById(R.id.projects_article_discord);
        if (!minecraft && source != null && ContentPaths.isHttpsUrl(source)) {
            discord.setVisibility(View.VISIBLE);
            // An action: chamfered and quiet like the app's other secondary actions (the two-shape rule).
            discord.setBackground(ChamferDrawable.secondary(activity));
            Press.attach(discord, Press.Style.BUTTON); // M14
            discord.setOnClickListener(v -> openUrl(activity, source));
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setView(view)
                .setPositiveButton(R.string.projects_close, null);
        if (minecraft) {
            // M17: "minecraft.net'te oku" in the system browser (https only), never "Discord'da aç".
            if (source != null && ContentPaths.isHttpsUrl(source)) {
                builder.setNeutralButton(R.string.projects_minecraft_read, (d, w) -> openUrl(activity, source));
            }
            builder.show();
            return;
        }
        final NewsFeed.Cta cta = item.cta;
        if (cta != null && cta.label != null && !cta.label.trim().isEmpty()) {
            if (ContentPaths.isHttpsUrl(cta.url)) {
                builder.setNeutralButton(cta.label.trim(), (d, w) -> openUrl(activity, cta.url));
            } else if (cta.newsId != null) {
                final NewsFeed.NewsItem target = snapshot.home(System.currentTimeMillis()).find(cta.newsId);
                if (target != null && target != item) {
                    builder.setNeutralButton(cta.label.trim(), (d, w) -> showArticle(activity, snapshot, target));
                }
            }
        }
        builder.show();
    }

    /** An error that says what happened, with "copy details" for support (no codes in the text). */
    public static void showError(final Activity activity, int title, int message, final String details) {
        showError(activity, title, message, details, null);
    }

    /**
     * M18: with {@code retry}, the main button is "Tekrar dene" and "Kapat" closes; the technical details
     * stay behind "Ayrıntıları kopyala", for support only.
     */
    public static void showError(final Activity activity, int title, int message, final String details,
                                 @Nullable final Runnable retry) {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message);
        if (retry != null) {
            builder.setPositiveButton(R.string.projects_retry, (d, w) -> retry.run())
                    .setNegativeButton(R.string.projects_close, null);
        } else {
            builder.setPositiveButton(android.R.string.ok, null);
        }
        if (details != null && !details.isEmpty()) {
            builder.setNeutralButton(R.string.projects_copy_details, (d, w) -> {
                ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("Projects Launcher", details));
                    Toast.makeText(activity, R.string.projects_details_copied, Toast.LENGTH_SHORT).show();
                }
            });
        }
        builder.show();
    }
}
