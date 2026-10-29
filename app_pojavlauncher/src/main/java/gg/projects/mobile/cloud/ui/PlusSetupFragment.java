package gg.projects.mobile.cloud.ui;

import android.app.Activity;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
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
import gg.projects.mobile.cloud.PlusConfig;
import gg.projects.mobile.social.Json;
import gg.projects.mobile.ui.Haptics;

/**
 * ProBot AI's first setup (SETUP-UX, 28 Oct 2026; TRADE-ANY decision 26): while a first grant waits for the player, one
 * question a step, "N / 5" with five bars: Ne alıp satsın? (the orders, saved on their own screen), Ne kadar
 * harcayabilsin? (two amounts or "Şimdilik alım yapmasın"; skipped when every order sells), ProBot AI ne yapsın?, Sohbette
 * ne yapsın? (ads off), Nasıl konuşsun? and "Başlat": one save and the start ({@code control resume}). Every step passes
 * untouched (the defaults are safe) but the spending rule; "Daha sonra" keeps the choices on this device.
 */
public final class PlusSetupFragment extends CloudScreen implements PlusEditors.Host {
    public static final String TAG = "ProjectsPlusSetupFragment";
    private static final String[] STEPS = {PlusEditors.ORDERS, PlusEditors.MONEY, PlusEditors.ACT, PlusEditors.CHAT, PlusEditors.TALK};

    @Nullable
    private CloudModel.PlusDoc mDoc;
    @Nullable
    private PlusConfig mOriginal;
    @Nullable
    private PlusConfig mEdit;
    private int mStep;
    /** "Şimdilik alım yapmasın" as the player set it; null: as the orders say (on without a buy order). */
    @Nullable
    private Boolean mNotNow;
    private boolean mStarting;
    private boolean mFailed;
    private final List<String[]> mProblems = new ArrayList<>();

    @Override
    protected void created(View view) {
        mHeader.setTitle(getString(R.string.projects_setup_title));
    }

    @Override
    protected void started() {
        load();
    }

    private void load() {
        final CloudModel.Account a = account();
        if (a == null) return;
        mFailed = false;
        mService.plus(a.id, new CloudService.Result<CloudModel.PlusDoc>() {
            @Override
            public void ok(CloudModel.PlusDoc doc) {
                if (!alive()) return;
                begin(doc);
            }

            @Override
            public void failed(CloudProblem problem) {
                if (!alive()) return;
                mFailed = true;
                render();
            }
        });
    }

    private void begin(CloudModel.PlusDoc doc) {
        boolean first = mEdit == null;
        mDoc = doc;
        mOriginal = new PlusConfig(doc.config);
        if (first) {
            // The choices kept on this device ("Daha sonra") come back over the stored settings.
            String id = accountId();
            JsonObject kept = id == null ? null : mService.setupDraft(id);
            JsonObject keptConfig = Json.obj(kept, "config");
            mEdit = keptConfig != null ? new PlusConfig(keptConfig) : mOriginal.copy();
            mStep = kept == null ? 0 : Math.max(0, Math.min(STEPS.length - 1, (int) Json.num(kept, "step", 0)));
            com.google.gson.JsonElement notNow = kept == null ? null : kept.get("notNow");
            mNotNow = notNow == null || !notNow.isJsonPrimitive() ? null : notNow.getAsBoolean();
        }
        draw();
    }

    @Override
    public void onResume() {
        super.onResume();
        // Back from the orders: the settings' revision moved with them (the draft stays).
        String id = accountId();
        CloudModel.PlusDoc latest = id == null ? null : mService.lastPlus(id);
        if (latest != null && mDoc != null && latest.aiRev > mDoc.aiRev && mEdit != null) {
            mDoc = latest;
            mOriginal = new PlusConfig(latest.config);
            draw();
        }
    }

    @Override
    protected void render() {
        CloudModel.Account a = account();
        if (a != null) mHeader.setCaption(a.name);
        if (mEdit != null) return;
        LinearLayout content = clear();
        if (content == null) return;
        Context c = ctx();
        if (mFailed) {
            content.addView(CloudUi.empty(c, gg.projects.mobile.ui.PixelSprites.Sprite.FAILED, getString(R.string.projects_plus_load_failed)));
            content.addView(CloudUi.textButton(c, getString(R.string.projects_cloud_retry), v -> load()),
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            content.addView(CloudUi.loading(c));
        }
    }

    /** The orders as they are now (step 1 saves them on their own screen, so the latest document has them). */
    private List<JsonObject> currentOrders() {
        CloudModel.PlusDoc latest = mDoc;
        if (latest != null) return new PlusConfig(latest.config).orders();
        return mEdit == null ? new ArrayList<JsonObject>() : mEdit.orders();
    }

    /** The active orders (a paused order is not counted: SETUP-UX's rule, 29 Oct). */
    private List<JsonObject> activeOrders() {
        List<JsonObject> active = new ArrayList<>();
        for (JsonObject o : currentOrders()) if (Json.bool(o, "on", true)) active.add(o);
        return active;
    }

    private static boolean buys(List<JsonObject> orders) {
        for (JsonObject o : orders) if (!"sell".equals(Json.str(o, "side"))) return true;
        return false;
    }

    /** Whether the money step is skipped: every active order sells (no buying needs spending). */
    private boolean skipsMoney() {
        List<JsonObject> active = activeOrders();
        return !active.isEmpty() && !buys(active);
    }

    private void draw() {
        LinearLayout content = clear();
        if (content == null || mEdit == null) return;
        final Context c = ctx();
        String step = STEPS[mStep];
        content.addView(progress(c));
        TextView question = CloudUi.text(c, getString(PlusEditors.title(step)), 19, R.color.projects_bone_100, true);
        question.setTypeface(CloudUi.font(c, R.font.tektur_semibold));
        question.setPadding(CloudUi.dp(c, 8), CloudUi.dp(c, 14), CloudUi.dp(c, 8), CloudUi.dp(c, 2));
        if (android.os.Build.VERSION.SDK_INT >= 28) question.setAccessibilityHeading(true);
        content.addView(question);
        String lede = PlusEditors.lede(c, step);
        if (lede != null) content.addView(CloudUi.note(c, lede));
        if (PlusEditors.ORDERS.equals(step)) orders(content);
        else PlusEditors.draw(step, content, this, new PlusEditors.Drawn(), false, true);
        if (mStep == STEPS.length - 1) content.addView(CloudUi.note(c, getString(R.string.projects_setup_change_later)));
        bottom();
    }

    /** "2 / 5" and five bars: done and now in bone, the rest quiet (the one gold is the step's button). */
    private View progress(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(CloudUi.dp(c, 8), CloudUi.dp(c, 6), CloudUi.dp(c, 8), 0);
        for (int i = 0; i < STEPS.length; i++) {
            View bar = new View(c);
            bar.setBackgroundColor(CloudUi.color(c, i <= mStep ? R.color.projects_bone_100 : R.color.projects_obsidian_700));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, CloudUi.dp(c, 3), 1f);
            if (i > 0) lp.setMarginStart(CloudUi.dp(c, 4));
            row.addView(bar, lp);
        }
        TextView count = CloudUi.text(c, getString(R.string.projects_setup_count, mStep + 1, STEPS.length), 13, R.color.projects_ash_300, false);
        LinearLayout.LayoutParams countLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        countLp.setMarginStart(CloudUi.dp(c, 12));
        row.addView(count, countLp);
        return row;
    }

    /** Step 1: the orders as they are (they save on their own screen), and "Sipariş ekle". */
    private void orders(LinearLayout content) {
        final Context c = ctx();
        if (mEdit == null) return;
        List<JsonObject> orders = currentOrders();
        if (orders.isEmpty()) {
            content.addView(CloudUi.note(c, getString(R.string.projects_setup_no_orders)));
        } else {
            LinearLayout group = CloudUi.group(c);
            List<View> rows = new ArrayList<>();
            for (JsonObject o : orders) {
                String item = Json.str(o, "item", "");
                boolean sell = "sell".equals(Json.str(o, "side"));
                CloudUi.Row row = CloudUi.row(c, R.drawable.projects_ic_arrows_left_right, itemName(item), null);
                row.setLine(getString(sell ? R.string.projects_card_to_sell : R.string.projects_card_to_buy), R.color.projects_ash_400);
                rows.add(row.view);
            }
            CloudUi.fill(group, rows);
            content.addView(group);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = CloudUi.dp(c, 8);
        content.addView(CloudUi.textButton(c, getString(R.string.projects_ads_add_order), v -> openOrders()), lp);
    }

    /** The step's buttons: "Geri" from the second, "Daha sonra", the gold "Devam" or "Başlat". */
    private void bottom() {
        FrameLayout bottom = mBottom;
        if (bottom == null) return;
        bottom.removeAllViews();
        final Context c = ctx();
        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(CloudUi.color(c, R.color.projects_obsidian_850));
        bar.setPadding(CloudUi.dp(c, 12), CloudUi.dp(c, 8), CloudUi.dp(c, 12), CloudUi.dp(c, 8));
        if (mStep > 0) {
            Button back = CloudUi.textButton(c, getString(R.string.projects_setup_back), v -> {
                mStep = previous(mStep);
                mProblems.clear();
                draw();
            });
            back.setEnabled(!mStarting);
            bar.addView(back);
        }
        Button later = CloudUi.textButton(c, getString(R.string.projects_setup_later), v -> later());
        later.setEnabled(!mStarting);
        later.setTextColor(CloudUi.color(c, R.color.projects_ash_300));
        bar.addView(later);
        // A spacer with no height of its own (a bare View with WRAP_CONTENT takes all the height it is offered).
        bar.addView(new View(c), new LinearLayout.LayoutParams(0, 0, 1f));
        boolean last = mStep == STEPS.length - 1;
        Button go = CloudUi.button(c, getString(last ? (mStarting ? R.string.projects_setup_starting : R.string.projects_setup_start)
                : R.string.projects_setup_next), true, v -> {
            if (last) start(v);
            else next(v);
        });
        go.setMinWidth(CloudUi.dp(c, 120));
        go.setEnabled(!mStarting);
        bar.addView(go);
        bottom.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bottom.setVisibility(View.VISIBLE);
    }

    private int previous(int step) {
        int back = Math.max(0, step - 1);
        if (PlusEditors.MONEY.equals(STEPS[back]) && skipsMoney()) back = Math.max(0, back - 1);
        return back;
    }

    /** "Devam": the money step's one rule (two amounts or "Şimdilik alım yapmasın"), then the next question. */
    private void next(View from) {
        PlusConfig e = mEdit;
        if (e == null) return;
        mProblems.clear();
        if (PlusEditors.MONEY.equals(STEPS[mStep])) {
            if (!notNow() && !e.canSpend()) {
                mProblems.add(new String[]{"setup.money", getString(R.string.projects_setup_required)});
                Haptics.reject(from);
                draw();
                return;
            }
        }
        int next = mStep + 1;
        if (next < STEPS.length && PlusEditors.MONEY.equals(STEPS[next]) && skipsMoney()) next++;
        mStep = Math.min(STEPS.length - 1, next);
        keep();
        Haptics.tick(from);
        draw();
        if (mScroll != null) mScroll.scrollTo(0, 0);
    }

    /** "Daha sonra": the choices stay on this device; the account says "ProBot AI henüz başlatılmadı." with its start. */
    private void later() {
        keep();
        leaveNow();
    }

    private void keep() {
        String id = accountId();
        PlusConfig e = mEdit;
        if (id == null || e == null) return;
        JsonObject draft = new JsonObject();
        draft.addProperty("step", mStep);
        if (mNotNow != null) draft.addProperty("notNow", mNotNow);
        draft.add("config", e.settingsJson());
        mService.setSetupDraft(id, draft);
    }

    /** "Başlat": one save of what changed, then the start ({@code control resume}, which drops {@code ready}). */
    private void start(final View from) {
        final CloudModel.Account a = account();
        final CloudModel.PlusDoc doc = mDoc;
        final PlusConfig e = mEdit;
        final PlusConfig original = mOriginal;
        if (a == null || doc == null || e == null || original == null || mStarting) return;
        mProblems.clear();
        for (PlusConfig.Problem p : e.check(doc.server.maxDeal, doc.rules.approvalTimeoutMin, doc.rules.approvalTimeoutMax, mService.nowSeconds())) {
            mProblems.add(new String[]{p.field, null});
        }
        if (!mProblems.isEmpty()) {
            Haptics.reject(from);
            toast(getString(R.string.projects_cloud_settings_fix_first));
            goToProblem();
            return;
        }
        mStarting = true;
        bottom();
        if (e.same(original)) {
            resume(a.id, from);
            return;
        }
        mService.putPlus(a.id, doc.aiRev, e.settingsJson(), new CloudService.Result<CloudModel.PlusDoc>() {
            @Override
            public void ok(CloudModel.PlusDoc saved) {
                if (!alive()) return;
                mDoc = saved;
                mOriginal = new PlusConfig(saved.config);
                resume(a.id, from);
            }

            @Override
            public void failed(CloudProblem problem) {
                mStarting = false;
                if (!alive()) return;
                String field = problem.field();
                if (problem.kind == CloudProblem.Kind.REFUSED && field != null) {
                    mProblems.add(new String[]{field, null});
                    goToProblem();
                    toast(getString(R.string.projects_cloud_settings_fix_first));
                    return;
                }
                bottom();
                problem(problem, from);
            }
        });
    }

    private void resume(final String id, final View from) {
        mService.control(id, "resume", null, new CloudService.Result<JsonObject>() {
            @Override
            public void ok(JsonObject value) {
                mStarting = false;
                mService.setSetupDraft(id, null);
                if (!alive()) return;
                Haptics.confirm(from);
                toast(getString(R.string.projects_setup_started));
                mService.refresh(true);
                leaveNow();
            }

            @Override
            public void failed(CloudProblem problem) {
                mStarting = false;
                if (!alive()) return;
                bottom();
                problem(problem, from);
            }
        });
    }

    /** The step of the first problem's field. */
    private void goToProblem() {
        if (mProblems.isEmpty()) {
            draw();
            return;
        }
        String field = mProblems.get(0)[0];
        String q = field.startsWith("persona") ? PlusEditors.TALK : field.startsWith("discovery") ? PlusEditors.CHAT
                : PlusEditors.questionOf(field);
        for (int i = 0; i < STEPS.length; i++) if (STEPS[i].equals(q)) mStep = i;
        draw();
    }

    @Override
    protected void onBack() {
        if (mStep > 0 && !mStarting) {
            mStep = previous(mStep);
            mProblems.clear();
            draw();
            return;
        }
        later();
    }

    @Override
    public void onPause() {
        super.onPause();
        // Leaving for the orders or the system: the choices stay.
        if (mEdit != null && !mStarting) keep();
    }

    // ---- PlusEditors.Host ----

    @Override
    public Context ctx() {
        return super.ctx();
    }

    @Override
    public Activity activity() {
        return requireActivity();
    }

    @Override
    public PlusConfig edit() {
        return mEdit == null ? new PlusConfig(new JsonObject()) : mEdit;
    }

    @Nullable
    @Override
    public PlusConfig original() {
        return mOriginal;
    }

    @Nullable
    @Override
    public CloudModel.PlusDoc doc() {
        return mDoc;
    }

    @Override
    public CloudService service() {
        return mService;
    }

    @Nullable
    @Override
    public String accountId() {
        return super.accountId();
    }

    @Nullable
    @Override
    public String problemAt(String field) {
        for (String[] p : mProblems) {
            if (p[0].equals(field)) return p[1] != null ? p[1] : getString(R.string.projects_plus_problem_range);
        }
        return null;
    }

    @Override
    public void changed() {
        // Nothing is sent before "Başlat": the choices are kept when the step changes or the screen goes.
    }

    @Override
    public void rebuild() {
        draw();
    }

    @Override
    public void toast(CharSequence text) {
        super.toast(text);
    }

    @Override
    public void problem(CloudProblem problem, @Nullable View from) {
        super.problem(problem, from);
    }

    @Override
    public void openOrders() {
        String id = super.accountId();
        if (id == null) return;
        keep();
        openFor(PlusOrdersFragment.class, id);
    }

    @Override
    public boolean alive() {
        return super.alive();
    }

    /**
     * "Şimdilik alım yapmasın" (the site's rule, SETUP-UX 29 Oct): off with an active buy order or a typed amount, on
     * without any active order; then as the player set it.
     */
    @Override
    public boolean notNow() {
        if (mNotNow != null) return mNotNow;
        PlusConfig e = mEdit;
        if (e != null && (e.limit("perDealMax") != null || e.limit("totalBudget") != null)) return false;
        return !buys(activeOrders());
    }

    @Override
    public void setNotNow(boolean on) {
        mNotNow = on;
    }
}
