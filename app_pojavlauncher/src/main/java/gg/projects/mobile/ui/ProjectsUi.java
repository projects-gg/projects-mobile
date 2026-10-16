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

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import gg.projects.mobile.content.ContentException;
import gg.projects.mobile.content.ContentPaths;
import gg.projects.mobile.content.HomeContent;
import gg.projects.mobile.content.HttpStatusException;
import gg.projects.mobile.content.NewsFeed;
import gg.projects.mobile.content.Snapshot;

/** Shared formatting and dialogs for the Projects screens. */
public final class ProjectsUi {
    private ProjectsUi() {}

    public static String categoryLabel(Context context, NewsFeed.NewsItem item) {
        switch (item.categoryOrDefault()) {
            case "update": return context.getString(R.string.projects_category_update);
            case "event": return context.getString(R.string.projects_category_event);
            case "patchNotes": return context.getString(R.string.projects_category_patch_notes);
            case "community": return context.getString(R.string.projects_category_community);
            default: return context.getString(R.string.projects_category_announcement);
        }
    }

    private static Locale locale(Context context) {
        return context.getResources().getConfiguration().locale;
    }

    public static String date(Context context, long millis) {
        return DateFormat.getDateInstance(DateFormat.LONG, locale(context)).format(new Date(millis));
    }

    public static String dateTime(Context context, long millis) {
        return DateUtils.formatDateTime(context, millis,
                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_ABBREV_MONTH);
    }

    /** "2 saat önce", "3 gün önce"; a date for older moments. */
    public static String relative(long millis, long now) {
        return DateUtils.getRelativeTimeSpanString(millis, now, DateUtils.MINUTE_IN_MILLIS).toString();
    }

    /** "5 gün 3 sa", "2 sa 14 dk", "38 dk", "45 sn": time left in short form. */
    public static String span(Context context, long ms) {
        long total = Math.max(0, ms) / 1000;
        long days = total / 86_400;
        long hours = (total % 86_400) / 3_600;
        long minutes = (total % 3_600) / 60;
        if (days > 0) {
            return hours > 0 ? context.getString(R.string.projects_span_days_hours, days, hours)
                    : context.getString(R.string.projects_span_days, days);
        }
        if (hours > 0) {
            return minutes > 0 ? context.getString(R.string.projects_span_hours_minutes, hours, minutes)
                    : context.getString(R.string.projects_span_hours, hours);
        }
        if (minutes > 0) return context.getString(R.string.projects_span_minutes, minutes);
        return context.getString(R.string.projects_span_seconds, total);
    }

    /** When an upcoming event starts: "Cumartesi 20:00" within a week, else "20 Ekim 20:00". */
    public static String eventMoment(Context context, long millis, long now) {
        String pattern = millis - now < 6 * DateUtils.DAY_IN_MILLIS ? "EEEE HH:mm" : "d MMMM HH:mm";
        return new SimpleDateFormat(pattern, locale(context)).format(new Date(millis));
    }

    /** True when every mirror answered "not found": nothing is published yet, not a connection problem. */
    public static boolean nothingPublished(@Nullable Snapshot snapshot) {
        if (snapshot == null || snapshot.lastError == null) return false;
        for (Throwable t = snapshot.lastError; t != null; t = t.getCause()) {
            if (t instanceof HttpStatusException) return ((HttpStatusException) t).status == 404;
        }
        return false;
    }

    public static boolean updateRequired(@Nullable Snapshot snapshot) {
        return snapshot != null && snapshot.lastError != null
                && snapshot.lastError.kind == ContentException.Kind.UNSUPPORTED_SCHEMA;
    }

    public static void openUrl(Activity activity, String url) {
        if (!ContentPaths.isHttpsUrl(url)) return;
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (RuntimeException ignored) {
            // No browser available.
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

    /** The events behind "+N etkinlik": what runs and what comes, each opening its article or link. */
    public static void showOtherEvents(final Activity activity, final Snapshot snapshot, final HomeContent home,
                                       List<HomeContent.Event> events, long now) {
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
            TextView state = row.findViewById(R.id.projects_event_row_state);
            TextView time = row.findViewById(R.id.projects_event_row_time);
            boolean live = event.isLive(now);
            state.setVisibility(live ? View.VISIBLE : View.GONE);
            if (live) {
                time.setText(event.openEnded
                        ? activity.getString(R.string.projects_event_started, relative(event.startsAt, now))
                        : activity.getString(R.string.projects_event_ends_in, span(activity, event.endsAt - now)));
            } else {
                time.setText(eventMoment(activity, event.startsAt, now));
            }
            if (eventOpens(home, event)) {
                // A caret opens the article on the home screen; the external mark opens the browser.
                boolean article = event.newsId != null && home.find(event.newsId) != null;
                ImageView mark = row.findViewById(R.id.projects_event_row_caret);
                mark.setImageResource(article ? R.drawable.projects_ic_caret_right : R.drawable.projects_ic_arrow_square_out);
                mark.setVisibility(View.VISIBLE);
                row.setBackgroundResource(R.drawable.projects_row_ripple);
                row.setOnClickListener(v -> {
                    dialog.dismiss();
                    openEvent(activity, snapshot, home, event);
                });
            }
            list.addView(row);
        }
        dialog.show();
    }

    /** Full article: cover, category, date, title, the Markdown body and, for Discord posts, the original. */
    public static void showArticle(final Activity activity, final Snapshot snapshot, final NewsFeed.NewsItem item) {
        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_projects_news, null, false);
        ((TextView) view.findViewById(R.id.projects_article_category)).setText(categoryLabel(activity, item));
        ((TextView) view.findViewById(R.id.projects_article_date)).setText(date(activity, item.publishedAtMillis()));
        ((TextView) view.findViewById(R.id.projects_article_title)).setText(item.title);
        TextView body = view.findViewById(R.id.projects_article_body);
        CharSequence rendered = MarkdownLite.render(item.body.isEmpty() ? item.summary : item.body);
        body.setText(rendered);
        body.setMovementMethod(LinkMovementMethod.getInstance());
        TextView author = view.findViewById(R.id.projects_article_author);
        if (item.author != null && !item.author.trim().isEmpty()) {
            author.setText(item.author.trim());
            author.setVisibility(View.VISIBLE);
        }
        CoverView cover = view.findViewById(R.id.projects_article_cover);
        cover.setMaxCoverHeight(Math.round(220 * activity.getResources().getDisplayMetrics().density));
        cover.bind(snapshot, item, true);
        final String source = item.sourceUrl();
        View discord = view.findViewById(R.id.projects_article_discord);
        if (source != null && ContentPaths.isHttpsUrl(source)) {
            discord.setVisibility(View.VISIBLE);
            discord.setOnClickListener(v -> openUrl(activity, source));
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setView(view)
                .setPositiveButton(R.string.projects_close, null);
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
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null);
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
