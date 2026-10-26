package gg.projects.mobile.cloud.console;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import net.kdt.pojavlaunch.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import gg.projects.mobile.cloud.CloudModel;
import gg.projects.mobile.cloud.CloudProblem;
import gg.projects.mobile.cloud.CloudService;
import gg.projects.mobile.cloud.ui.CloudUi;
import gg.projects.mobile.social.ui.BottomPanel;
import gg.projects.mobile.social.ui.SocialUi;
import gg.projects.mobile.ui.Haptics;

/**
 * "Paylaş ya da kaydet" (CAFK-FEAT §3.2 on the phone): the masked log of the last hour, today or the last three days,
 * read page by page from the service, as the contract's text file (ConsoleExport), handed to Android's share sheet or
 * saved where the player picks. Nothing in it is a secret: it is the masked log, as the console shows it.
 */
public final class ConsoleShare {
    private ConsoleShare() {}

    /** The authority of the app's own share provider (AndroidManifest: {@code <applicationId>.projects.share}). */
    public static String authority(Context c) {
        return c.getPackageName() + ".projects.share";
    }

    /** What the console screen gives the sheet. */
    public interface Host {
        Activity activity();

        CloudService service();

        /** Asks the system where to save {@code name}; the screen writes the bytes it is handed back ({@link #write}). */
        void saveAs(String name, byte[] bytes);

        boolean alive();
    }

    /** The sheet: when (one choice), then "Paylaş" or "Dosyaya kaydet". */
    @MainThread
    public static void open(final Host host, final CloudModel.Account account, final String lang) {
        final Context c = host.activity();
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(CloudUi.dp(c, 16), 0, CloudUi.dp(c, 16), CloudUi.dp(c, 16));
        TextView title = CloudUi.text(c, c.getString(R.string.projects_feat_console_export), 17, R.color.projects_bone_100, true);
        title.setPadding(CloudUi.dp(c, 8), CloudUi.dp(c, 2), CloudUi.dp(c, 8), CloudUi.dp(c, 8));
        root.addView(title);
        final ConsoleExport.Range[] ranges = {ConsoleExport.Range.HOUR, ConsoleExport.Range.TODAY, ConsoleExport.Range.DAYS3};
        final int[] chosen = {1};
        root.addView(CloudUi.choices(c, new CharSequence[]{c.getString(R.string.projects_feat_console_hour),
                c.getString(R.string.projects_feat_console_today), c.getString(R.string.projects_feat_console_days3)}, 1, i -> chosen[0] = i));
        final TextView line = CloudUi.note(c, "");
        line.setVisibility(View.GONE);
        root.addView(line);
        LinearLayout buttons = new LinearLayout(c);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(CloudUi.dp(c, 4), CloudUi.dp(c, 14), CloudUi.dp(c, 4), 0);
        final BottomPanel[] panel = new BottomPanel[1];
        final boolean[] busy = {false};
        final Button share = CloudUi.button(c, c.getString(R.string.projects_feat_console_share), true, null);
        final Button save = CloudUi.button(c, c.getString(R.string.projects_feat_console_save), false, null);
        View.OnClickListener go = v -> {
            if (busy[0]) return;
            busy[0] = true;
            share.setEnabled(false);
            save.setEnabled(false);
            final boolean toShare = v == share;
            collect(host, account, ranges[chosen[0]], new Collected() {
                @Override
                public void done(List<CloudModel.ConsoleLine> lines) {
                    busy[0] = false;
                    share.setEnabled(true);
                    save.setEnabled(true);
                    if (!host.alive()) return;
                    if (lines.isEmpty()) {
                        Haptics.reject(v);
                        line.setText(c.getString(R.string.projects_feat_console_export_empty));
                        line.setVisibility(View.VISIBLE);
                        return;
                    }
                    String name = ConsoleExport.fileName(account.name, lang, host.service().nowSeconds());
                    byte[] bytes = ConsoleExport.bytes(ConsoleExport.text(lines, names(c)));
                    Haptics.confirm(v);
                    if (panel[0] != null) panel[0].dismiss();
                    if (toShare) share(host.activity(), name, bytes);
                    else host.saveAs(name, bytes);
                }

                @Override
                public void failed() {
                    busy[0] = false;
                    share.setEnabled(true);
                    save.setEnabled(true);
                    if (!host.alive()) return;
                    Haptics.reject(v);
                    line.setText(c.getString(R.string.projects_feat_console_export_failed));
                    line.setVisibility(View.VISIBLE);
                }
            });
        };
        share.setOnClickListener(go);
        save.setOnClickListener(go);
        buttons.addView(share, CloudUi.weight(1f));
        LinearLayout.LayoutParams saveLp = CloudUi.weight(1f);
        saveLp.setMarginStart(CloudUi.dp(c, 10));
        buttons.addView(save, saveLp);
        root.addView(buttons, CloudUi.matchWrap());
        panel[0] = BottomPanel.of(c, root, 0.85f);
        panel[0].show();
    }

    /** The kinds' names in the app's language. */
    public static ConsoleExport.Names names(final Context c) {
        return kind -> {
            switch (kind) {
                case CHAT: return c.getString(R.string.projects_feat_console_kind_chat);
                case PRIVATE: return c.getString(R.string.projects_feat_console_kind_private);
                case SERVER: return c.getString(R.string.projects_feat_console_kind_server);
                case TITLE: return c.getString(R.string.projects_feat_console_kind_title);
                case COMMAND: return c.getString(R.string.projects_feat_console_kind_command);
                case CONNECTION: return c.getString(R.string.projects_feat_console_kind_connection);
                case ERROR: return c.getString(R.string.projects_feat_console_kind_error);
                default: return c.getString(R.string.projects_feat_console_kind_console);
            }
        };
    }

    interface Collected {
        void done(List<CloudModel.ConsoleLine> lines);

        void failed();
    }

    /** Reads the log's pages from the range's start to now (at most {@link ConsoleExport#MAX_PAGES} of them). */
    private static void collect(final Host host, final CloudModel.Account account, ConsoleExport.Range range, final Collected collected) {
        final long now = host.service().nowSeconds();
        final long from = ConsoleExport.from(range, now);
        final List<CloudModel.ConsoleLine> lines = new ArrayList<>();
        final int[] pages = {0};
        final CloudService.Result<CloudModel.LogPage>[] next = new CloudService.Result[1];
        next[0] = new CloudService.Result<CloudModel.LogPage>() {
            @Override
            public void ok(CloudModel.LogPage page) {
                lines.addAll(page.lines);
                pages[0]++;
                if (page.next != null && pages[0] < ConsoleExport.MAX_PAGES && host.alive()) {
                    host.service().logs(account.id, from, now, page.next, next[0]);
                } else {
                    collected.done(lines);
                }
            }

            @Override
            public void failed(CloudProblem problem) {
                collected.failed();
            }
        };
        host.service().logs(account.id, from, now, null, next[0]);
    }

    /** Hands the file to the share sheet (through the app's own provider; read permission for that one file). */
    static void share(Activity activity, String name, byte[] bytes) {
        try {
            File dir = new File(activity.getCacheDir(), "console");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
                f.delete();
            File file = new File(dir, name);
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(bytes);
            }
            Uri uri = FileProvider.getUriForFile(activity, authority(activity), file);
            Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_SUBJECT, name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            send.setClipData(ClipData.newRawUri(name, uri));
            activity.startActivity(Intent.createChooser(send, activity.getString(R.string.projects_feat_console_share)));
        } catch (IOException | RuntimeException e) {
            SocialUi.toastLong(activity, activity.getString(R.string.projects_feat_console_export_failed));
        }
    }

    /** Writes the saved file where the player picked it. */
    public static void write(Context c, @Nullable Uri uri, @Nullable byte[] bytes) {
        if (uri == null || bytes == null) return;
        try (OutputStream out = c.getContentResolver().openOutputStream(uri, "w")) {
            if (out == null) throw new IOException("no stream");
            out.write(bytes);
            SocialUi.toast(c, c.getString(R.string.projects_feat_console_saved));
        } catch (IOException | RuntimeException e) {
            SocialUi.toastLong(c, c.getString(R.string.projects_feat_console_export_failed));
        }
    }
}
