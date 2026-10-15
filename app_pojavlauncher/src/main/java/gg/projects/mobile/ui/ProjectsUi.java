package gg.projects.mobile.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import net.kdt.pojavlaunch.R;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

import gg.projects.mobile.ProjectsContent;
import gg.projects.mobile.content.ContentException;
import gg.projects.mobile.content.ContentPaths;
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

    public static String date(Context context, long millis) {
        Locale locale = context.getResources().getConfiguration().locale;
        return DateFormat.getDateInstance(DateFormat.LONG, locale).format(new Date(millis));
    }

    public static String dateTime(Context context, long millis) {
        return android.text.format.DateUtils.formatDateTime(context, millis,
                android.text.format.DateUtils.FORMAT_SHOW_DATE | android.text.format.DateUtils.FORMAT_SHOW_TIME
                        | android.text.format.DateUtils.FORMAT_ABBREV_MONTH);
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

    /** Full article: cover, category, date, title and the Markdown body. */
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
        ImageView cover = view.findViewById(R.id.projects_article_cover);
        ProjectsContent.loadCover(activity, snapshot, item.cover, cover, activity.getResources().getDisplayMetrics().widthPixels);

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setView(view)
                .setPositiveButton(R.string.projects_close, null);
        final NewsFeed.Cta cta = item.cta;
        if (cta != null && cta.label != null && !cta.label.trim().isEmpty()) {
            if (ContentPaths.isHttpsUrl(cta.url)) {
                builder.setNeutralButton(cta.label.trim(), (d, w) -> openUrl(activity, cta.url));
            } else if (cta.newsId != null && snapshot.news != null) {
                final NewsFeed.NewsItem target = snapshot.news.get(cta.newsId);
                if (target != null && target != item && target.isVisible(System.currentTimeMillis())) {
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
