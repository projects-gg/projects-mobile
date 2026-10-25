package gg.projects.mobile.cloud.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.gson.JsonObject;

import net.kdt.pojavlaunch.R;

import java.util.ArrayList;
import java.util.List;

import gg.projects.mobile.cloud.CloudModel;
import gg.projects.mobile.cloud.CloudProblem;
import gg.projects.mobile.cloud.CloudService;
import gg.projects.mobile.cloud.CloudWords;
import gg.projects.mobile.cloud.PlusConfig;
import gg.projects.mobile.ui.Haptics;

/**
 * "Alım ve satım": what the assistant buys and sells. A sentence typed at the top ("Elytra alırım, en fazla 40 bin")
 * becomes a draft card to add, edit or discard; the lists below hold the rows, each edited in a sheet. Without a
 * budget and a per-deal limit it only reports finds, which the screen says once above the lists.
 */
public final class PlusRulesFragment extends PlusScreen implements PlusRowSheet.Host {
    public static final String TAG = "ProjectsPlusRulesFragment";

    @Nullable
    private CloudModel.ParseDraft mDraft;
    @Nullable
    private String mDraftProblem;
    private String mTyped = "";
    private boolean mParsing;

    @Override
    protected int title() {
        return R.string.projects_plus_rules;
    }

    @Override
    protected void draw(LinearLayout content) {
        final Context c = ctx();
        final PlusConfig edit = mEdit;
        if (edit == null) return;
        if (mService.plusFeature("parse")) typed(content);
        if (!edit.canSpend()) {
            content.addView(CloudUi.notice(c, R.drawable.projects_ic_wallet, R.color.projects_ash_300,
                    getString(R.string.projects_plus_notify_only_note)));
        }
        list(content, true);
        list(content, false);
    }

    // ---- the typed rule ----

    private void typed(LinearLayout content) {
        final Context c = ctx();
        final CloudUi.Field field = CloudUi.field(c, getString(R.string.projects_plus_parse_field), mTyped,
                getString(R.string.projects_plus_parse_hint), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(200)});
        field.input.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(android.text.Editable s) {
                mTyped = s.toString();
            }
        });
        if (mDraftProblem != null) field.setError(mDraftProblem);
        content.addView(field.view);
        Button go = CloudUi.button(c, getString(R.string.projects_plus_parse_go), false, v -> parse(v));
        go.setEnabled(!mParsing);
        LinearLayout.LayoutParams goLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        goLp.setMarginStart(CloudUi.dp(c, 8));
        goLp.topMargin = CloudUi.dp(c, 8);
        content.addView(go, goLp);
        if (mDraft != null) content.addView(draft(c, mDraft));
    }

    private void parse(final View from) {
        final CloudModel.Account a = account();
        String text = mTyped.trim();
        if (a == null || text.isEmpty() || mParsing) return;
        mParsing = true;
        mDraftProblem = null;
        redraw();
        mService.parse(a.id, text, lang(), new CloudService.Result<CloudModel.ParseDraft>() {
            @Override
            public void ok(CloudModel.ParseDraft draft) {
                mParsing = false;
                if (!alive()) return;
                mDraft = draft;
                mDraftProblem = draftProblem(draft);
                if (mDraftProblem != null && draft.item == null && draft.candidates.isEmpty()) mDraft = null;
                redraw();
            }

            @Override
            public void failed(CloudProblem problem) {
                mParsing = false;
                if (!alive()) return;
                String reason = problem.reason();
                if (problem.is("parse_failed")) {
                    mDraftProblem = parseWords(reason);
                    redraw();
                } else if (problem.kind == CloudProblem.Kind.RATE_LIMITED && "daily".equals(reason)) {
                    mDraftProblem = getString(R.string.projects_plus_parse_daily);
                    redraw();
                } else {
                    redraw();
                    problem(problem, from);
                }
            }
        });
    }

    @Nullable
    private String draftProblem(CloudModel.ParseDraft draft) {
        for (String issue : draft.issues) {
            if ("no_amount".equals(issue)) continue;
            if ("no_item".equals(issue) && !draft.candidates.isEmpty()) continue;
            return parseWords(issue);
        }
        return null;
    }

    private String parseWords(@Nullable String reason) {
        if (reason == null) return getString(R.string.projects_plus_parse_unsafe);
        switch (reason) {
            case "no_item": return getString(R.string.projects_plus_parse_no_item);
            case "no_amount": return getString(R.string.projects_plus_parse_no_amount);
            case "many_items":
            case "many": return getString(R.string.projects_plus_parse_many);
            case "daily": return getString(R.string.projects_plus_parse_daily);
            default: return getString(R.string.projects_plus_parse_unsafe);
        }
    }

    /** The draft: the item, the side, the amounts, and what to do with it. */
    private View draft(final Context c, final CloudModel.ParseDraft draft) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(CloudUi.dp(c, 10));
        bg.setColor(CloudUi.color(c, R.color.projects_obsidian_800));
        bg.setStroke(Math.max(1, CloudUi.dp(c, 1)), CloudUi.color(c, R.color.projects_obsidian_700));
        card.setBackground(bg);
        card.setPadding(CloudUi.dp(c, 14), CloudUi.dp(c, 12), CloudUi.dp(c, 14), CloudUi.dp(c, 6));
        LinearLayout.LayoutParams cardLp = CloudUi.matchWrap();
        cardLp.topMargin = CloudUi.dp(c, 12);
        card.setLayoutParams(cardLp);
        final boolean buy = !"sell".equals(draft.side);

        if (draft.item == null && !draft.candidates.isEmpty()) {
            card.addView(CloudUi.text(c, getString(R.string.projects_plus_parse_which), 15, R.color.projects_bone_100, true));
            for (final String id : draft.candidates) {
                View option = PlusRowSheet.itemPicker(c, id, itemName(id), v -> {
                    Haptics.tick(v);
                    mDraft = withItem(draft, id);
                    mDraftProblem = null;
                    redraw();
                });
                ((LinearLayout.LayoutParams) option.getLayoutParams()).topMargin = CloudUi.dp(c, 8);
                card.addView(option);
            }
            card.addView(CloudUi.textButton(c, getString(R.string.projects_plus_parse_discard), v -> discard()),
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return card;
        }

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(CloudItemArt.tile(c, draft.item == null ? "" : draft.item, 40), new LinearLayout.LayoutParams(CloudUi.dp(c, 40), CloudUi.dp(c, 40)));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        String name = draft.item == null ? "" : itemName(draft.item);
        texts.addView(CloudUi.text(c, draft.qty > 1 ? draft.qty + " " + name : name, 16, R.color.projects_bone_100, true));
        texts.addView(CloudUi.text(c, getString(buy ? R.string.projects_plus_buy_list : R.string.projects_plus_sell_list), 13, R.color.projects_ash_300, false));
        LinearLayout.LayoutParams textsLp = CloudUi.weight(1f);
        textsLp.setMarginStart(CloudUi.dp(c, 12));
        head.addView(texts, textsLp);
        card.addView(head);

        Long bound = buy ? draft.max : draft.min;
        Long target = draft.target != null ? draft.target : bound != null && buy ? Long.valueOf(PlusConfig.suggestedTarget(bound)) : bound;
        if (target != null && bound != null) {
            String line = getString(buy ? R.string.projects_plus_buy_line : R.string.projects_plus_sell_line,
                    CloudWords.money(c, target), CloudWords.money(c, bound));
            if ("each".equals(draft.unit) && draft.qty > 1) line = getString(R.string.projects_plus_each_line, line);
            TextView amounts = CloudUi.figure(c, line, 15, R.color.projects_gold_300);
            amounts.setTypeface(CloudUi.font(c, R.font.figtree_semibold));
            amounts.setPadding(0, CloudUi.dp(c, 10), 0, 0);
            card.addView(amounts);
        } else {
            TextView missing = CloudUi.text(c, getString(R.string.projects_plus_parse_no_amount), 13.5f, R.color.projects_ash_300, false);
            missing.setPadding(0, CloudUi.dp(c, 10), 0, 0);
            card.addView(missing);
        }

        LinearLayout buttons = new LinearLayout(c);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.setPadding(0, CloudUi.dp(c, 10), 0, 0);
        final JsonObject row = rowOf(draft, target, bound);
        if (row != null && target != null && bound != null) {
            buttons.addView(CloudUi.button(c, getString(R.string.projects_plus_parse_add), true, v -> {
                if (mEdit == null) return;
                mEdit.addRow(buy, row);
                Haptics.confirm(v);
                discard();
                changed();
            }));
        }
        buttons.addView(CloudUi.textButton(c, getString(R.string.projects_plus_parse_edit), v -> {
            if (row != null) PlusRowSheet.open(this, buy, null, row);
        }));
        buttons.addView(CloudUi.textButton(c, getString(R.string.projects_plus_parse_discard), v -> discard()));
        card.addView(buttons);
        return card;
    }

    private void discard() {
        mDraft = null;
        mDraftProblem = null;
        mTyped = "";
        redraw();
    }

    private static CloudModel.ParseDraft withItem(CloudModel.ParseDraft d, String item) {
        JsonObject o = new JsonObject();
        o.addProperty("side", d.side);
        o.addProperty("item", item);
        o.add("candidates", new com.google.gson.JsonArray());
        o.addProperty("qty", d.qty);
        o.addProperty("unit", d.unit);
        if (d.target != null) o.addProperty("target", d.target);
        if (d.max != null) o.addProperty("max", d.max);
        if (d.min != null) o.addProperty("min", d.min);
        o.addProperty("style", d.style);
        o.addProperty("approval", d.approval);
        JsonObject wrap = new JsonObject();
        wrap.add("draft", o);
        wrap.add("issues", new com.google.gson.JsonArray());
        return CloudModel.ParseDraft.read(wrap);
    }

    @Nullable
    private JsonObject rowOf(CloudModel.ParseDraft d, @Nullable Long target, @Nullable Long bound) {
        if (mEdit == null || d.item == null) return null;
        boolean buy = !"sell".equals(d.side);
        return PlusConfig.newRow(buy, mEdit.newRowId(new java.util.Random()), d.item, Math.max(1, d.qty), d.unit == null ? "total" : d.unit,
                target == null ? 1 : target, bound == null ? 1 : bound, d.style == null ? "balanced" : d.style, d.approval == null ? "above" : d.approval);
    }

    // ---- the lists ----

    private void list(LinearLayout content, final boolean buy) {
        Context c = ctx();
        PlusConfig edit = mEdit;
        if (edit == null) return;
        content.addView(CloudUi.section(c, getString(buy ? R.string.projects_plus_buy_list : R.string.projects_plus_sell_list)));
        List<PlusConfig.Row> rows = edit.rows(buy);
        String key = buy ? "buy" : "sell";
        String listProblem = null;
        for (String[] p : mProblems) if (p[0].equals(key)) listProblem = words(p[0], p[1]);
        if (listProblem != null) {
            TextView error = CloudRowSheet.error(c);
            CloudRowSheet.show(error, listProblem);
            content.addView(error);
        }
        if (rows.isEmpty()) {
            content.addView(CloudUi.note(c, getString(buy ? R.string.projects_plus_buy_empty : R.string.projects_plus_sell_empty)));
        } else {
            LinearLayout group = CloudUi.group(c);
            List<View> views = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) views.add(rowView(c, rows.get(i), key + "[" + i + "]"));
            CloudUi.fill(group, views);
            content.addView(group);
        }
        int max = mCatalog == null ? PlusConfig.LIST_MAX : Math.min(PlusConfig.LIST_MAX, mCatalog.listMax);
        Button add = CloudUi.textButton(c, getString(buy ? R.string.projects_plus_buy_add : R.string.projects_plus_sell_add),
                v -> PlusRowSheet.open(this, buy, null, null));
        add.setEnabled(rows.size() < max && mCatalog != null);
        add.setAlpha(add.isEnabled() ? 1f : 0.5f);
        content.addView(add, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private View rowView(Context c, final PlusConfig.Row r, String path) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(CloudUi.dp(c, 64));
        row.setPaddingRelative(CloudUi.dp(c, 12), CloudUi.dp(c, 10), CloudUi.dp(c, 12), CloudUi.dp(c, 10));
        row.setBackgroundResource(R.drawable.projects_row_ripple);
        row.setClickable(true);
        row.setFocusable(true);
        row.addView(CloudItemArt.tile(c, r.item(), 40), new LinearLayout.LayoutParams(CloudUi.dp(c, 40), CloudUi.dp(c, 40)));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        String name = itemName(r.item());
        TextView title = CloudUi.text(c, r.qty() > 1 ? r.qty() + " " + name : name, 15, R.color.projects_bone_100, true);
        texts.addView(title);
        String line = getString(r.buy ? R.string.projects_plus_buy_line : R.string.projects_plus_sell_line,
                CloudWords.money(c, r.target()), CloudWords.money(c, r.bound()));
        if ("each".equals(r.unit()) && r.qty() > 1) line = getString(R.string.projects_plus_each_line, line);
        String problem = problemAt(path);
        TextView sub = CloudUi.text(c, problem != null ? problem : line, 13, problem != null ? R.color.projects_flare_400 : R.color.projects_ash_300, false);
        texts.addView(sub);
        LinearLayout.LayoutParams textsLp = CloudUi.weight(1f);
        textsLp.setMarginStart(CloudUi.dp(c, 14));
        row.addView(texts, textsLp);
        android.widget.ImageView caret = new android.widget.ImageView(c);
        caret.setImageResource(R.drawable.projects_ic_caret_right);
        caret.setImageTintList(android.content.res.ColorStateList.valueOf(CloudUi.color(c, R.color.projects_ash_400)));
        caret.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(caret, new LinearLayout.LayoutParams(CloudUi.dp(c, 20), CloudUi.dp(c, 20)));
        if (!r.enabled()) row.setAlpha(0.55f);
        row.setOnClickListener(v -> PlusRowSheet.open(this, r.buy, r.id(), null));
        gg.projects.mobile.ui.Press.attach(row, gg.projects.mobile.ui.Press.Style.ROW);
        row.setContentDescription(title.getText() + ", " + sub.getText());
        row.setLayoutParams(CloudUi.matchWrap());
        return row;
    }

    // ---- PlusRowSheet.Host ----

    @Override
    public Context context() {
        return requireContext();
    }

    @Override
    public PlusConfig edit() {
        return mEdit;
    }

    @Nullable
    @Override
    public CloudModel.PlusCatalog catalog() {
        return mCatalog;
    }

    @Override
    public void changed() {
        mProblems.clear();
        redraw();
        super.changed();
    }
}
