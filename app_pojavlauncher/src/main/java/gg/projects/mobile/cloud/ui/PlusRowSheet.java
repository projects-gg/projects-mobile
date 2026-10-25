package gg.projects.mobile.cloud.ui;

import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;

import com.google.gson.JsonObject;

import net.kdt.pojavlaunch.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import gg.projects.mobile.cloud.CloudModel;
import gg.projects.mobile.cloud.CloudWords;
import gg.projects.mobile.cloud.PlusConfig;
import gg.projects.mobile.cloud.TrGrammar;
import gg.projects.mobile.social.ui.BottomPanel;
import gg.projects.mobile.ui.Haptics;
import gg.projects.mobile.ui.Press;

/**
 * The sheet that edits one buy or sell row of AFK+: the item (picked from the profile's catalog), how many, the
 * target and the limit (amounts also written out in words under the field, so a missing zero shows), the style, when
 * it asks, a daily cap, and for buying what it accepts. "Tamam" checks the row with the Worker's rules; a limit more
 * than ten times the target asks once more.
 */
final class PlusRowSheet {
    private PlusRowSheet() {}

    interface Host {
        Context context();

        PlusConfig edit();

        @Nullable
        CloudModel.PlusCatalog catalog();

        String itemName(String id);

        /** The row was put back, added or deleted. */
        void changed();
    }

    /** Edits {@code id} of the list, or a new row ({@code id} null) starting from {@code start} when given. */
    static void open(final Host host, final boolean buy, @Nullable final String id, @Nullable JsonObject start) {
        final Context c = host.context();
        final PlusConfig edit = host.edit();
        final boolean isNew = id == null;
        JsonObject base;
        if (!isNew) {
            PlusConfig.Row stored = edit.row(buy, id);
            if (stored == null) return;
            base = stored.json();
        } else if (start != null) {
            base = start.deepCopy();
        } else {
            base = PlusConfig.newRow(buy, edit.newRowId(new java.util.Random()), "", 1, "total", 1, 1, "balanced", "above");
        }
        final PlusConfig.Row row = PlusConfig.Row.of(buy, base);
        final CloudRowSheet.Sheet sheet = new CloudRowSheet.Sheet(c, c.getString(buy ? R.string.projects_plus_row_buy_title : R.string.projects_plus_row_sell_title));

        // The item.
        sheet.body.addView(CloudRowSheet.label(c, c.getString(R.string.projects_plus_row_item)));
        final LinearLayout itemBox = new LinearLayout(c);
        itemBox.setOrientation(LinearLayout.VERTICAL);
        sheet.body.addView(itemBox, CloudUi.matchWrap());
        final TextView itemError = CloudRowSheet.error(c);
        sheet.body.addView(itemError);
        final LinearLayout buyOnly = new LinearLayout(c);
        buyOnly.setOrientation(LinearLayout.VERTICAL);
        final Runnable[] drawItem = new Runnable[1];
        drawItem[0] = () -> {
            itemBox.removeAllViews();
            String item = row.item();
            itemBox.addView(itemPicker(c, item.isEmpty() ? null : item, item.isEmpty() ? c.getString(R.string.projects_plus_row_pick) : host.itemName(item),
                    v -> pick(host, picked -> {
                        row.setItem(picked);
                        drawItem[0].run();
                    })));
        };
        drawItem[0].run();

        // How many and the price.
        final CloudUi.Field qty = CloudUi.field(c, c.getString(R.string.projects_plus_row_qty), String.valueOf(row.qty()), null, InputType.TYPE_CLASS_NUMBER);
        qty.input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(4)});
        sheet.body.addView(qty.view);
        final LinearLayout unitBox = new LinearLayout(c);
        unitBox.setOrientation(LinearLayout.VERTICAL);
        unitBox.addView(CloudRowSheet.label(c, c.getString(R.string.projects_plus_row_unit)));
        unitBox.addView(CloudUi.segmented(c, new CharSequence[]{c.getString(R.string.projects_plus_row_unit_total), c.getString(R.string.projects_plus_row_unit_each)},
                "each".equals(row.unit()) ? 1 : 0, i -> row.setUnit(i == 1 ? "each" : "total")));
        unitBox.setVisibility(row.qty() > 1 ? View.VISIBLE : View.GONE);
        sheet.body.addView(unitBox, CloudUi.matchWrap());
        watch(qty.input, s -> unitBox.setVisibility(CloudRowSheet.number(s, 1) > 1 ? View.VISIBLE : View.GONE));

        // A new row starts with empty amounts (the placeholders in its document are not the player's numbers).
        boolean blank = isNew && start == null;
        final Amount target = new Amount(c, c.getString(R.string.projects_plus_row_target), blank ? 0 : row.target());
        sheet.body.addView(target.field.view);
        final Amount bound = new Amount(c, c.getString(buy ? R.string.projects_plus_row_max : R.string.projects_plus_row_min), blank ? 0 : row.bound());
        sheet.body.addView(bound.field.view);

        // How it bargains and when it asks.
        sheet.body.addView(CloudRowSheet.label(c, c.getString(R.string.projects_plus_row_style)));
        sheet.body.addView(CloudUi.segmented(c, new CharSequence[]{c.getString(R.string.projects_plus_style_patient),
                c.getString(R.string.projects_plus_style_balanced), c.getString(R.string.projects_plus_style_quick)},
                index(PlusConfig.STYLES, row.style(), 1), i -> row.setStyle(PlusConfig.STYLES[i])));
        sheet.body.addView(CloudRowSheet.label(c, c.getString(R.string.projects_plus_row_approval)));
        sheet.body.addView(CloudUi.choices(c, new CharSequence[]{c.getString(R.string.projects_plus_approval_auto),
                c.getString(R.string.projects_plus_approval_above), c.getString(R.string.projects_plus_approval_always)},
                index(PlusConfig.APPROVALS, row.approval(), 1), i -> row.setApproval(PlusConfig.APPROVALS[i])));
        final CloudUi.Field daily = CloudUi.field(c, c.getString(R.string.projects_plus_row_daily), String.valueOf(row.dailyQty()), null, InputType.TYPE_CLASS_NUMBER);
        daily.input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(4)});
        sheet.body.addView(daily.view);

        // What a bought item may be.
        final CloudUi.Field durability = CloudUi.field(c, c.getString(R.string.projects_plus_row_durability), String.valueOf(row.minDurabilityPct()), null,
                InputType.TYPE_CLASS_NUMBER);
        durability.input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(3)});
        if (buy) {
            buyOnly.addView(CloudUi.gap(c, 6));
            SwitchCompat notify = CloudUi.toggle(c, c.getString(R.string.projects_plus_row_notify), row.notifyFound(), row::setNotifyFound);
            notify.setPadding(CloudUi.dp(c, 8), 0, CloudUi.dp(c, 4), 0);
            buyOnly.addView(notify);
            CloudModel.PlusCatalog catalog = host.catalog();
            CloudModel.PlusCatalog.Item item = catalog == null ? null : catalog.item(row.item());
            if (item == null || item.damageable) buyOnly.addView(durability.unit("%").view);
            SwitchCompat renamed = CloudUi.toggle(c, c.getString(R.string.projects_plus_row_custom_name), row.allowCustomName(), row::setAllowCustomName);
            renamed.setPadding(CloudUi.dp(c, 8), 0, CloudUi.dp(c, 4), 0);
            buyOnly.addView(renamed);
            SwitchCompat curses = CloudUi.toggle(c, c.getString(R.string.projects_plus_row_curses), row.allowCurses(), row::setAllowCurses);
            curses.setPadding(CloudUi.dp(c, 8), 0, CloudUi.dp(c, 4), 0);
            buyOnly.addView(curses);
            sheet.body.addView(buyOnly, CloudUi.matchWrap());
        }
        SwitchCompat use = CloudUi.toggle(c, c.getString(R.string.projects_plus_row_use), row.enabled(), row::setEnabled);
        use.setPadding(CloudUi.dp(c, 8), 0, CloudUi.dp(c, 4), 0);
        sheet.body.addView(use);

        final boolean[] confirmedTenTimes = {false};
        sheet.actions(isNew ? null : c.getString(R.string.projects_plus_row_remove), () -> {
            edit.removeRow(buy, id);
            host.changed();
            sheet.panel.dismiss();
        }, null, () -> {
            itemError.setVisibility(View.GONE);
            qty.setError(null);
            daily.setError(null);
            durability.setError(null);
            Long t = target.value();
            Long b = bound.value();
            boolean typedOk = true;
            if (t == null) {
                target.field.setError(c.getString(R.string.projects_plus_problem_range));
                typedOk = false;
            }
            if (b == null) {
                bound.field.setError(c.getString(R.string.projects_plus_problem_range));
                typedOk = false;
            }
            if (!typedOk) {
                Haptics.reject(sheet.body);
                return false;
            }
            row.setQty(CloudRowSheet.number(qty.value(), 0));
            row.setTarget(t);
            row.setBound(b);
            row.setDailyQty(CloudRowSheet.number(daily.value(), 0));
            if (buy) row.setMinDurabilityPct(Math.max(0, Math.min(100, CloudRowSheet.number(durability.value(), 0))));
            PlusConfig trial = edit.copy();
            String rowId = row.id();
            trial.replaceRow(buy, rowId, row.json());
            int at = -1;
            List<PlusConfig.Row> rows = trial.rows(buy);
            for (int i = 0; i < rows.size(); i++) if (rows.get(i).id().equals(rowId)) at = i;
            String path = (buy ? "buy" : "sell") + "[" + at + "]";
            boolean ok = true;
            for (PlusConfig.Problem p : trial.check(host.catalog(), System.currentTimeMillis() / 1000L)) {
                if (!p.field.startsWith(path)) continue;
                ok = false;
                String rest = p.field.substring(path.length());
                String words = problemWords(c, buy, p.reason, host.catalog());
                switch (rest) {
                    case ".item": CloudRowSheet.show(itemError, words); break;
                    case ".qty": qty.setError(words); break;
                    case ".target": target.field.setError(words); break;
                    case ".max":
                    case ".min": bound.field.setError(words); break;
                    case ".dailyQty": daily.setError(words); break;
                    default: CloudRowSheet.show(itemError, words); break;
                }
            }
            if (row.item().isEmpty()) {
                CloudRowSheet.show(itemError, c.getString(R.string.projects_plus_problem_item));
                ok = false;
            }
            if (!ok) {
                Haptics.reject(sheet.body);
                return false;
            }
            if (buy && b > 10 * t && !confirmedTenTimes[0]) {
                new AlertDialog.Builder(c)
                        .setMessage(c.getString(R.string.projects_plus_row_ten_times))
                        .setPositiveButton(R.string.projects_plus_row_ten_times_yes, (d, w) -> {
                            confirmedTenTimes[0] = true;
                            put(edit, buy, isNew, row);
                            host.changed();
                            sheet.panel.dismiss();
                        })
                        .setNegativeButton(R.string.projects_cloud_discard_keep, null)
                        .show();
                return false;
            }
            put(edit, buy, isNew, row);
            host.changed();
            return true;
        });
        sheet.show();
    }

    private static void put(PlusConfig edit, boolean buy, boolean isNew, PlusConfig.Row row) {
        if (isNew) edit.addRow(buy, row.json());
        else edit.replaceRow(buy, row.id(), row.json());
    }

    static String problemWords(Context c, boolean buy, String reason, @Nullable CloudModel.PlusCatalog catalog) {
        switch (reason) {
            case "order": return c.getString(buy ? R.string.projects_plus_problem_order_buy : R.string.projects_plus_problem_order_sell);
            case "item": return c.getString(R.string.projects_plus_problem_item);
            case "limit": return c.getString(R.string.projects_plus_problem_limit, catalog == null ? PlusConfig.LIST_MAX : Math.min(PlusConfig.LIST_MAX, catalog.listMax));
            default: return c.getString(R.string.projects_plus_problem_range);
        }
    }

    private static int index(String[] values, String value, int fallback) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(value)) return i;
        return fallback;
    }

    private interface Typed {
        void typed(String text);
    }

    private static void watch(EditText input, final Typed typed) {
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                typed.typed(s.toString());
            }
        });
    }

    /** An in-game amount: "40k", "37.500" or digits; the words under it say what was typed. */
    static final class Amount {
        final CloudUi.Field field;
        private final Context mContext;

        Amount(Context c, String label, long value) {
            mContext = c;
            field = CloudUi.field(c, label, value > 0 ? CloudWords.money(c, value) : "", null,
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            field.input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(16)});
            say(field.value());
            watch(field.input, s -> {
                field.setError(null);
                say(s);
            });
        }

        private void say(String typed) {
            Long v = PlusConfig.parseAmount(typed);
            field.setHelp(v == null ? null : TrGrammar.words(v, CloudWords.lang(mContext)));
        }

        @Nullable
        Long value() {
            return PlusConfig.parseAmount(field.value());
        }
    }

    // ---- the item picker ----

    interface Picked {
        void picked(String item);
    }

    /** A field-like row with the item's tile and name. */
    static View itemPicker(Context c, @Nullable String item, String name, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.projects_input);
        row.setMinimumHeight(CloudUi.dp(c, 56));
        row.setPadding(CloudUi.dp(c, 10), CloudUi.dp(c, 6), CloudUi.dp(c, 12), CloudUi.dp(c, 6));
        row.setClickable(true);
        row.setFocusable(true);
        row.addView(CloudItemArt.tile(c, item == null ? "" : item, 36), new LinearLayout.LayoutParams(CloudUi.dp(c, 36), CloudUi.dp(c, 36)));
        TextView text = CloudUi.text(c, name, 16, item == null ? R.color.projects_ash_400 : R.color.projects_bone_100, item != null);
        LinearLayout.LayoutParams lp = CloudUi.weight(1f);
        lp.setMarginStart(CloudUi.dp(c, 12));
        row.addView(text, lp);
        row.setOnClickListener(click);
        row.setContentDescription(c.getString(R.string.projects_plus_row_item) + ", " + name);
        Press.attach(row, Press.Style.ROW);
        LinearLayout.LayoutParams rowLp = CloudUi.matchWrap();
        rowLp.leftMargin = CloudUi.dp(c, 8);
        rowLp.rightMargin = CloudUi.dp(c, 8);
        row.setLayoutParams(rowLp);
        return row;
    }

    /** The catalog's items with a search (Turkish and English names both match). */
    static void pick(final Host host, final Picked picked) {
        final Context c = host.context();
        final CloudModel.PlusCatalog catalog = host.catalog();
        if (catalog == null) return;
        final String lang = CloudWords.lang(c);
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(CloudUi.dp(c, 16), 0, CloudUi.dp(c, 16), CloudUi.dp(c, 12));
        TextView title = CloudUi.text(c, c.getString(R.string.projects_plus_row_pick), 18, R.color.projects_bone_100, true);
        title.setPadding(CloudUi.dp(c, 8), 0, CloudUi.dp(c, 8), CloudUi.dp(c, 4));
        root.addView(title);
        final EditText search = new EditText(c);
        search.setBackgroundResource(R.drawable.projects_input);
        search.setHint(c.getString(R.string.projects_plus_row_search));
        search.setHintTextColor(CloudUi.color(c, R.color.projects_ash_400));
        search.setTextColor(CloudUi.color(c, R.color.projects_bone_100));
        search.setTypeface(CloudUi.font(c, R.font.figtree_regular));
        search.setSingleLine(true);
        search.setMinHeight(CloudUi.dp(c, 48));
        search.setPadding(CloudUi.dp(c, 14), 0, CloudUi.dp(c, 14), 0);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        LinearLayout.LayoutParams searchLp = CloudUi.matchWrap();
        searchLp.topMargin = CloudUi.dp(c, 6);
        searchLp.bottomMargin = CloudUi.dp(c, 6);
        root.addView(search, searchLp);
        ScrollView scroll = new ScrollView(c);
        final LinearLayout list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, CloudUi.dp(c, 380)));
        final BottomPanel panel = BottomPanel.of(c, root, 0.88f);
        final Runnable fill = () -> {
            list.removeAllViews();
            String q = search.getText().toString().trim().toLowerCase(new Locale("tr"));
            String qEn = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            List<CloudModel.PlusCatalog.Item> shown = new ArrayList<>();
            for (CloudModel.PlusCatalog.Item item : catalog.items) {
                if (q.isEmpty() || item.tr.toLowerCase(new Locale("tr")).contains(q) || item.en.toLowerCase(Locale.ROOT).contains(qEn)) shown.add(item);
                if (shown.size() >= 80) break;
            }
            if (shown.isEmpty()) {
                list.addView(CloudUi.note(c, c.getString(R.string.projects_plus_row_none)));
                return;
            }
            for (final CloudModel.PlusCatalog.Item item : shown) {
                LinearLayout row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinimumHeight(CloudUi.dp(c, 52));
                row.setPadding(CloudUi.dp(c, 6), CloudUi.dp(c, 4), CloudUi.dp(c, 8), CloudUi.dp(c, 4));
                row.setBackgroundResource(R.drawable.projects_row_ripple);
                row.setClickable(true);
                row.setFocusable(true);
                row.addView(CloudItemArt.tile(c, item.id, 32), new LinearLayout.LayoutParams(CloudUi.dp(c, 32), CloudUi.dp(c, 32)));
                TextView name = CloudUi.text(c, item.name(lang), 15, R.color.projects_bone_100, false);
                LinearLayout.LayoutParams lp = CloudUi.weight(1f);
                lp.setMarginStart(CloudUi.dp(c, 12));
                row.addView(name, lp);
                row.setOnClickListener(v -> {
                    Haptics.tick(v);
                    picked.picked(item.id);
                    panel.dismiss();
                });
                Press.attach(row, Press.Style.ROW);
                list.addView(row, CloudUi.matchWrap());
            }
        };
        watch(search, s -> fill.run());
        fill.run();
        panel.show();
    }
}
