package gg.projects.mobile.cloud.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.kdt.pojavlaunch.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import gg.projects.mobile.cloud.CloudModel;
import gg.projects.mobile.cloud.CloudProblem;
import gg.projects.mobile.cloud.CloudService;
import gg.projects.mobile.cloud.CloudWords;
import gg.projects.mobile.cloud.PlusConfig;
import gg.projects.mobile.cloud.PlusModel;
import gg.projects.mobile.cloud.TrGrammar;
import gg.projects.mobile.cloud.inventory.CloudInventoryFragment;
import gg.projects.mobile.cloud.items.ItemData;
import gg.projects.mobile.cloud.plus.OrderDraft;
import gg.projects.mobile.cloud.plus.OrderRules;
import gg.projects.mobile.social.Json;
import gg.projects.mobile.social.ui.BottomPanel;
import gg.projects.mobile.ui.Haptics;

/**
 * "Siparişler" (afkplus-free.md §7.1, §7.4; the launcher's orders section): what the assistant buys and sells, one kind
 * of item, a quantity in all, a term. The player writes it in their own words ("Elytra alırım, en fazla 40 bin"), the free
 * rule check answers while they type, "Kontrol et" asks the service once and the answer becomes the order's card: its
 * lines, at most two questions with ready answers (answered here, no second model call), "Vazgeç", "Düzenle", "Kaydet".
 * The form and "Envanterden seç" are always there too. Below, the active orders and the finished ones, each with its
 * progress and its status in a word; a row opens its actions (edit, pause or resume, extend, start again, delete with
 * "Geri al"). Nothing is stored before "Kaydet"; each write is its own revision.
 */
public final class PlusOrdersFragment extends CloudScreen implements CloudService.LiveListener, PlusOrderSheet.Host {
    public static final String TAG = "ProjectsPlusOrdersFragment";
    private static final String PICK_KEY = "gg.projects.mobile.plus.ORDER_PICK";
    private static final long UNDO_MS = 8000;

    @Nullable
    private CloudModel.PlusDoc mDoc;
    private boolean mFailed;
    @Nullable
    private ItemData mData;
    private boolean mDataFailed;
    @Nullable
    private CloudService.LiveHandle mLive;

    // The intake: kept across redraws so typing never loses its field.
    @Nullable
    private LinearLayout mIntake;
    @Nullable
    private CloudUi.Field mField;
    @Nullable
    private Button mCheck;
    @Nullable
    private TextView mHint;
    @Nullable
    private TextView mLeft;
    private boolean mChecking;
    /** The line under the field: the service's or the rule check's refusal, or why only the form works now. */
    @Nullable
    private String mIntakeProblem;
    private int mChecksLeft = -1;

    // The card.
    @Nullable
    private JsonObject mDraft;
    private final List<String> mNotes = new ArrayList<>();
    /** Settings a chip saves with the order ({@code {pay}}, {@code {limits: {perDealMax}}}). */
    @Nullable
    private JsonObject mCardSettings;
    /** The "Başka" amount field of a question, while it is open (by the question's kind). */
    @Nullable
    private String mOtherOpen;
    private boolean mSaving;
    /** The card's question a pick answers ({@code item} or {@code pay}), while the inventory is open for it. */
    @Nullable
    private String mPickFor;

    /** The order just deleted, its "Geri al" open until {@link #mUndoUntil}. */
    @Nullable
    private JsonObject mUndo;
    private long mUndoUntil;
    private final Runnable mUndoEnd = this::undoEnded;
    /** Whether "Kontrol et" is the gold action now (not while a card's "Kaydet" is). */
    private boolean mCheckLoud = true;
    /** The player said the typed text's money is the game's ("Bu oyun parası"); goes with a change of the text. */
    private boolean mInGame;
    /** The service asked whether the money is real ({@code question:rmt}): "Bu oyun parası" checks again at once. */
    private boolean mServerAsked;
    @Nullable
    private Button mInGameButton;

    @Override
    protected void created(View view) {
        mHeader.setTitle(getString(R.string.projects_plus_rules));
        getParentFragmentManager().setFragmentResultListener(PICK_KEY, getViewLifecycleOwner(), (key, result) -> picked(result));
    }

    @Override
    protected void started() {
        CloudModel.Account a = account();
        if (a == null) return;
        if (mLive == null) mLive = mService.watch(a.id, true, this);
        load();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mLive != null) {
            mLive.close();
            mLive = null;
        }
        View view = getView();
        if (view != null) view.removeCallbacks(mUndoEnd);
    }

    private void load() {
        final CloudModel.Account a = account();
        if (a == null) return;
        mFailed = false;
        mService.plus(a.id, new CloudService.Result<CloudModel.PlusDoc>() {
            @Override
            public void ok(CloudModel.PlusDoc doc) {
                if (!alive()) return;
                takeDoc(doc);
                if (doc.rules.checksLeft != null) mChecksLeft = doc.rules.checksLeft;
                loadData(doc);
                draw();
            }

            @Override
            public void failed(CloudProblem problem) {
                if (!alive()) return;
                mFailed = mDoc == null;
                draw();
            }
        });
    }

    private void loadData(CloudModel.PlusDoc doc) {
        ItemData known = mService.cachedItemData(doc.server.mc);
        if (known != null) {
            mData = known;
            return;
        }
        mService.itemData(doc.server.mc, new CloudService.Result<ItemData>() {
            @Override
            public void ok(ItemData value) {
                mData = value;
                mDataFailed = false;
                if (alive()) draw();
            }

            @Override
            public void failed(CloudProblem problem) {
                mDataFailed = true;
            }
        });
    }

    @Override
    protected void render() {
        CloudModel.Account a = account();
        if (a != null) mHeader.setCaption(a.name);
        if (mContent != null && mContent.getChildCount() == 0) draw();
    }

    // ---- what the card asks against ----

    private PlusConfig config() {
        return new PlusConfig(mDoc == null ? new JsonObject() : mDoc.config);
    }

    /** The draft's context: the item data, the custom items, the money, the payment setting, Studio's switches, the limits. */
    private OrderDraft.Ctx draftCtx() {
        CloudModel.PlusDoc doc = mDoc;
        PlusConfig cfg = config();
        OrderDraft.Ctx ctx = new OrderDraft.Ctx().data(mData).custom(cfg.customItems())
                .money(doc == null ? PlusModel.GENERIC_MAX_DEAL : doc.server.maxDeal, (JsonArray) null)
                .pay(cfg.pay())
                .features(doc != null && doc.rules.itemPay, doc == null || doc.rules.customItems)
                .limits(cfg.limit("perDealMax"), cfg.limit("totalBudget"))
                .trades(doc == null ? "owner" : doc.server.trade);
        return mCardSettings == null ? ctx : ctx.withSettings(mCardSettings);
    }

    private boolean maintenance() {
        CloudModel.Offer offer = mService.offer();
        return offer != null && offer.plus != null && "paused".equals(offer.plus.mode);
    }

    private boolean typedOn() {
        return mDoc != null && mDoc.rules.orders && mService.plusFeature("orders");
    }

    private boolean machineAway() {
        return mDoc != null && mDoc.asOf != null;
    }

    private boolean serverOff() {
        return mDoc != null && !mDoc.server.on;
    }

    private long now() {
        return mService.nowSeconds();
    }

    private String status(JsonObject order) {
        CloudModel.PlusDoc doc = mDoc;
        String id = Json.str(order, "id", "");
        PlusModel.OrderView view = doc == null ? null : doc.orders.get(id);
        // The sides straight from the document (a PlusConfig would copy it for every row).
        JsonObject allow = doc == null ? null : Json.obj(doc.config, "allow");
        OrderDraft.State state = new OrderDraft.State(now(), view == null ? 0 : view.got, view == null ? null : view.live,
                Json.bool(allow, "buy", true), Json.bool(allow, "sell", true));
        return OrderDraft.statusOf(order, state).wire();
    }

    private long got(JsonObject order) {
        PlusModel.OrderView view = mDoc == null ? null : mDoc.orders.get(Json.str(order, "id", ""));
        return view == null ? 0 : view.got;
    }

    // ---- drawing ----

    private void draw() {
        LinearLayout content = clear();
        if (content == null) return;
        Context c = ctx();
        CloudModel.PlusDoc doc = mDoc;
        if (doc == null) {
            if (mFailed) {
                content.addView(CloudUi.empty(c, gg.projects.mobile.ui.PixelSprites.Sprite.FAILED, getString(R.string.projects_plus_load_failed)));
                content.addView(CloudUi.textButton(c, getString(R.string.projects_cloud_retry), v -> load()),
                        new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            } else {
                content.addView(CloudUi.loading(c));
            }
            return;
        }
        // One note above the section for what holds everything (afkplus-free.md §7.1): maintenance, the server, the machine.
        if (maintenance()) {
            content.addView(notice(c, R.string.projects_order_service_title, R.string.projects_order_service_body));
        } else if (serverOff()) {
            content.addView(CloudUi.notice(c, R.drawable.projects_ic_warning_circle, R.color.projects_ash_300, getString(R.string.projects_order_server_off)));
        } else if (machineAway()) {
            content.addView(notice(c, R.string.projects_order_away_title, R.string.projects_order_away_body));
        }
        if (!doc.server.botTrades() && !serverOff()) content.addView(CloudUi.note(c, getString(R.string.projects_order_server_owner)));
        intake(content);
        if (mDraft != null) content.addView(card(c, mDraft));
        lists(content);
        bottom();
    }

    /** One note for what holds the section: its title, and under it one sentence (quiet). */
    private View notice(Context c, int title, int body) {
        android.text.SpannableStringBuilder words = new android.text.SpannableStringBuilder(getString(title));
        words.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, words.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int start = words.length();
        words.append('\n').append(getString(body));
        words.setSpan(new android.text.style.ForegroundColorSpan(CloudUi.color(c, R.color.projects_ash_300)), start, words.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return CloudUi.notice(c, R.drawable.projects_ic_info, R.color.projects_ash_300, words);
    }

    // ---- the intake ----

    private void intake(LinearLayout content) {
        Context c = ctx();
        if (mIntake == null) buildIntake(c);
        LinearLayout intake = mIntake;
        if (intake == null) return;
        if (intake.getParent() instanceof ViewGroup) ((ViewGroup) intake.getParent()).removeView(intake);
        content.addView(intake);
        refreshIntake();
    }

    private void buildIntake(final Context c) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setLayoutParams(CloudUi.matchWrap());
        final CloudUi.Field field = CloudUi.field(c, getString(R.string.projects_order_intake_label), null,
                getString(R.string.projects_order_intake_hint),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(220)});
        field.input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        field.input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                check(v);
                return true;
            }
            return false;
        });
        field.input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                mIntakeProblem = null;
                mInGame = false;
                mServerAsked = false;
                refreshIntake();
            }
        });
        box.addView(field.view);
        TextView hint = CloudUi.text(c, "", 13, R.color.projects_ash_300, false);
        hint.setCompoundDrawablePadding(CloudUi.dp(c, 6));
        hint.setPadding(CloudUi.dp(c, 8), CloudUi.dp(c, 2), CloudUi.dp(c, 8), CloudUi.dp(c, 2));
        hint.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        box.addView(hint);
        // "Bu oyun parası" (TRADE-ANY decision 31): money that may be the game's is a question, never a refusal.
        Button inGame = CloudUi.textButton(c, getString(R.string.projects_money_in_game), v -> {
            mInGame = true;
            boolean again = mServerAsked;
            mServerAsked = false;
            mIntakeProblem = null;
            Haptics.tick(v);
            refreshIntake();
            if (again) check(v);
        });
        inGame.setVisibility(View.GONE);
        box.addView(inGame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mInGameButton = inGame;
        // The one loud thing of the section; a fixed width so "Okunuyor" never moves it.
        Button check = CloudUi.button(c, getString(R.string.projects_order_check), true, this::check);
        check.setMinWidth(CloudUi.dp(c, 150));
        check.setMinimumWidth(CloudUi.dp(c, 150));
        LinearLayout.LayoutParams checkLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        checkLp.topMargin = CloudUi.dp(c, 8);
        checkLp.setMarginStart(CloudUi.dp(c, 8));
        box.addView(check, checkLp);
        TextView left = CloudUi.text(c, "", 13, R.color.projects_ash_400, false);
        left.setPadding(CloudUi.dp(c, 8), CloudUi.dp(c, 6), CloudUi.dp(c, 8), 0);
        box.addView(left);
        LinearLayout ways = new LinearLayout(c);
        ways.setOrientation(LinearLayout.HORIZONTAL);
        ways.setPadding(0, CloudUi.dp(c, 2), 0, 0);
        Button form = CloudUi.textButton(c, getString(R.string.projects_order_form), v -> openForm(null, null, null));
        ways.addView(form);
        Button pick = CloudUi.textButton(c, getString(R.string.projects_order_pick), v -> startPick(null));
        pick.setTag("pick");
        ways.addView(pick);
        box.addView(ways);
        mIntake = box;
        mField = field;
        mCheck = check;
        mCheckLoud = true;
        mHint = hint;
        mLeft = left;
    }

    /** What the inventory needs: the feature, the account in game with the inventory on, Studio's custom items allowed. */
    private boolean canPick() {
        CloudModel.Account a = account();
        return a != null && a.features.inventoryMode != null && !"off".equals(a.features.inventoryMode) && a.features.inventoryOn && !machineAway()
                && !maintenance() && !serverOff();
    }

    /** The intake's state from what holds now (typing never redraws the section). */
    private void refreshIntake() {
        Context c = ctx();
        CloudUi.Field field = mField;
        Button check = mCheck;
        TextView hint = mHint;
        if (field == null || check == null || hint == null || mIntake == null) return;
        boolean typed = typedOn();
        boolean blocked = maintenance() || serverOff();
        boolean loud = mDraft == null;
        if (loud != mCheckLoud) {
            mCheckLoud = loud;
            CloudUi.styleButton(c, check, loud);
        }
        field.view.setVisibility(typed ? View.VISIBLE : View.GONE);
        check.setVisibility(typed ? View.VISIBLE : View.GONE);
        field.input.setEnabled(!blocked && !mChecking);
        String text = field.value().trim();
        String line = null;
        boolean warning = false;
        boolean asks = false;
        if (blocked) {
            line = getString(maintenance() ? R.string.projects_order_maintenance : R.string.projects_order_offline);
        } else if (!typed) {
            line = getString(R.string.projects_order_typed_off);
        } else if (machineAway()) {
            line = getString(R.string.projects_order_offline);
        } else if (mIntakeProblem != null) {
            line = mIntakeProblem;
            warning = true;
        } else if (mServerAsked) {
            line = getString(R.string.projects_money_question);
            asks = true;
        } else if (text.codePointCount(0, text.length()) >= 3) {
            OrderRules.RuleCheck rc = OrderRules.checkOrderText(text, ruleContext());
            OrderRules.Hint rule = OrderRules.ruleHint(rc);
            if (rule != null) {
                line = rejectWords(rule.reason, rule.rule);
                warning = true;
            } else if (rc.asksMoney()) {
                line = getString(R.string.projects_money_question);
                asks = true;
            }
        }
        if (mInGameButton != null) mInGameButton.setVisibility(asks ? View.VISIBLE : View.GONE);
        hint.setText(line == null ? "" : line);
        hint.setVisibility(line == null ? View.GONE : View.VISIBLE);
        // The colour of a problem only in its mark; the sentence stays plain.
        hint.setCompoundDrawablesRelativeWithIntrinsicBounds(warning ? R.drawable.projects_ic_warning_circle : 0, 0, 0, 0);
        androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(hint, ColorStateList.valueOf(CloudUi.color(c, R.color.projects_flare_400)));
        check.setText(getString(mChecking ? R.string.projects_order_reading : R.string.projects_order_check));
        boolean ready = !mChecking && !blocked && !machineAway() && text.codePointCount(0, text.length()) >= 3;
        check.setEnabled(ready);
        check.setAlpha(ready || mChecking ? 1f : 0.5f);
        TextView left = mLeft;
        if (left != null) {
            boolean say = typed && !blocked && !machineAway() && mChecksLeft >= 0 && mChecksLeft <= 5;
            left.setText(say ? getResources().getQuantityString(R.plurals.projects_order_left, mChecksLeft, mChecksLeft) : "");
            left.setVisibility(say ? View.VISIBLE : View.GONE);
        }
        View pick = mIntake.findViewWithTag("pick");
        if (pick != null) pick.setVisibility(canPick() ? View.VISIBLE : View.GONE);
    }

    private int activeCount() {
        int n = 0;
        for (JsonObject o : config().orders()) if (!PlusWords.finished(status(o)) && Json.bool(o, "on", true)) n++;
        return n;
    }

    /** A refusal of a typed order in words (the service's {@code order_rejected} or the rule check's hint). */
    private String rejectWords(@Nullable String reason, @Nullable String rule) {
        if (reason == null) return getString(R.string.projects_order_check_failed);
        switch (reason) {
            case "length": return getString(R.string.projects_order_reject_length);
            case "link": return getString(R.string.projects_order_reject_link);
            case "command": return getString(R.string.projects_order_reject_command);
            case "personal": return getString(R.string.projects_order_reject_personal);
            case "unsafe": return getString(R.string.projects_order_reject_unsafe);
            case "too_big": return getString(R.string.projects_order_reject_too_big);
            case "orders_full": {
                int max = mDoc == null ? 10 : mDoc.rules.orderMax;
                return getResources().getQuantityString(R.plurals.projects_order_reject_orders_full, max, max);
            }
            case "no_item": return getString(R.string.projects_order_reject_no_item);
            case "many_items": return getString(R.string.projects_order_reject_many_items);
            case "loosen": {
                if (rule == null) return getString(R.string.projects_order_reject_unsafe);
                switch (rule) {
                    case "prepay": return getString(R.string.projects_order_rule_prepay);
                    case "pay_cmd": return getString(R.string.projects_order_rule_pay_cmd);
                    case "disclose": return getString(R.string.projects_order_rule_disclose);
                    case "hide_ai": return getString(R.string.projects_order_rule_hide_ai);
                    case "off_platform": return getString(R.string.projects_order_rule_off_platform);
                    case "third_party": return getString(R.string.projects_order_rule_third_party);
                    case "rmt": return getString(R.string.projects_order_rule_rmt);
                    default: return getString(R.string.projects_order_reject_unsafe);
                }
            }
            default: return getString(R.string.projects_order_check_failed);
        }
    }

    /** "Kontrol et": the free rules first; then the service reads the text once. */
    private void check(View from) {
        final CloudModel.Account a = account();
        CloudUi.Field field = mField;
        if (a == null || field == null || mChecking || mDoc == null) return;
        String text = field.value().trim();
        if (text.codePointCount(0, text.length()) < 3) return;
        OrderRules.RuleCheck rc = OrderRules.checkOrderText(text, ruleContext());
        OrderRules.Hint rule = OrderRules.ruleHint(rc);
        if (rule != null) {
            mIntakeProblem = rejectWords(rule.reason, rule.rule);
            Haptics.reject(from);
            refreshIntake();
            return;
        }
        if (rc.asksMoney()) {
            // Asked under the field; "Bu oyun parası" lets it through.
            Haptics.reject(from);
            refreshIntake();
            return;
        }
        mChecking = true;
        mIntakeProblem = null;
        refreshIntake();
        mService.checkOrder(a.id, text, lang(), mInGame, new CloudService.Result<PlusModel.Check>() {
            @Override
            public void ok(PlusModel.Check answer) {
                mChecking = false;
                if (!alive()) return;
                mChecksLeft = answer.left;
                mDraft = OrderDraft.fill(answer.draft.deepCopy(), draftCtx());
                mNotes.clear();
                mNotes.addAll(answer.notes);
                mCardSettings = null;
                mOtherOpen = null;
                Haptics.confirm(from);
                draw();
            }

            @Override
            public void failed(CloudProblem problem) {
                mChecking = false;
                if (!alive()) return;
                if (problem.asksMoney()) {
                    mServerAsked = true;
                    mIntakeProblem = null;
                } else {
                    mIntakeProblem = checkProblem(problem);
                }
                Haptics.reject(from);
                refreshIntake();
            }
        });
    }

    /** The rule check's context: the profile's cap, the counters, the server's money name and the player's answer. */
    private OrderRules.RuleContext ruleContext() {
        CloudModel.PlusDoc doc = mDoc;
        String currency = doc == null || doc.server.moneyLabel == null ? null : doc.server.moneyLabel.in(lang());
        return new OrderRules.RuleContext()
                .maxDeal(doc == null ? PlusModel.GENERIC_MAX_DEAL : doc.server.maxDeal).active(activeCount())
                .orderMax(doc == null ? 10 : doc.rules.orderMax).currency(currency).inGame(mInGame);
    }

    private String checkProblem(CloudProblem p) {
        String reason = p.reason();
        if (p.is("order_rejected")) return rejectWords(reason, p.string("rule"));
        if (p.kind == CloudProblem.Kind.RATE_LIMITED) {
            if ("daily".equals(reason)) {
                mChecksLeft = 0;
                return getString(R.string.projects_order_daily);
            }
            return getString(R.string.projects_order_rate);
        }
        if (p.kind == CloudProblem.Kind.HOST_OFFLINE) return getString(R.string.projects_order_offline);
        if (p.is("ai_unavailable")) {
            if ("paused".equals(reason) || "off".equals(reason) || "budget".equals(reason)) return getString(R.string.projects_order_ai_paused);
            return getString(R.string.projects_order_ai_away);
        }
        if (p.is("feature_off")) return getString(R.string.projects_order_typed_off);
        if (p.paused()) return getString(R.string.projects_order_maintenance);
        return getString(R.string.projects_order_check_failed);
    }

    // ---- the card ----

    private View card(final Context c, final JsonObject draft) {
        CloudModel.PlusDoc doc = mDoc;
        OrderDraft.Ctx ctx = draftCtx();
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(CloudUi.dp(c, 10));
        bg.setColor(CloudUi.color(c, R.color.projects_obsidian_800));
        bg.setStroke(Math.max(1, CloudUi.dp(c, 1)), CloudUi.color(c, R.color.projects_obsidian_700));
        card.setBackground(bg);
        card.setPadding(CloudUi.dp(c, 14), CloudUi.dp(c, 14), CloudUi.dp(c, 14), CloudUi.dp(c, 8));
        LinearLayout.LayoutParams cardLp = CloudUi.matchWrap();
        cardLp.topMargin = CloudUi.dp(c, 14);
        card.setLayoutParams(cardLp);

        String side = Json.str(draft, "side");
        String item = Json.str(draft, "item");
        String pay = Json.str(draft, "pay");
        boolean sell = "sell".equals(side);
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(CloudItemArt.tile(c, item == null ? "" : item, baseOf(item, draft), 44),
                new LinearLayout.LayoutParams(CloudUi.dp(c, 44), CloudUi.dp(c, 44)));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(CloudUi.text(c, item == null ? getString(R.string.projects_card_no_item) : draftName(item, draft), 16,
                item == null ? R.color.projects_ash_300 : R.color.projects_bone_100, true));
        texts.addView(CloudUi.text(c, side == null ? getString(R.string.projects_card_unset)
                : getString(sell ? R.string.projects_card_to_sell : R.string.projects_card_to_buy), 13.5f, R.color.projects_ash_300, false));
        LinearLayout.LayoutParams textsLp = CloudUi.weight(1f);
        textsLp.setMarginStart(CloudUi.dp(c, 12));
        head.addView(texts, textsLp);
        card.addView(head);

        // The figures: what is not written yet says so (its question waits below).
        LinearLayout figures = new LinearLayout(c);
        figures.setOrientation(LinearLayout.VERTICAL);
        figures.setPadding(0, CloudUi.dp(c, 10), 0, 0);
        Long qty = Json.numOrNull(draft, "qty");
        figures.addView(figure(c, getString(R.string.projects_card_qty), qty == null ? null : String.valueOf(qty), null));
        boolean each = "each".equals(Json.str(draft, "unit")) && qty != null && qty > 1;
        Long target = Json.numOrNull(draft, "target");
        Long limit = Json.numOrNull(draft, "limit");
        figures.addView(figure(c, getString(R.string.projects_card_target), target == null || pay == null ? null : perPiece(price(target, pay), each), null));
        // The words under a figure only from 10.000 (the launcher's WORDS_FROM): below that they only repeat it.
        String words = limit != null && limit >= 10_000 && "money".equals(pay) ? TrGrammar.words(limit, lang()) : null;
        figures.addView(figure(c, getString(sell ? R.string.projects_card_at_least : R.string.projects_card_at_most),
                limit == null || pay == null ? null : perPiece(price(limit, pay), each), words));
        card.addView(figures);

        // The terms in plain lines.
        List<String> lines = new ArrayList<>();
        if (pay != null) {
            if ("money".equals(pay)) {
                lines.add(getString(doc != null && doc.server.moneyLabel != null ? R.string.projects_card_for_money : R.string.projects_card_for_money_bare));
            } else {
                lines.add(getString(R.string.projects_card_for_item, draftName(pay, draft)));
            }
        }
        lines.add(getString(R.string.projects_card_style, styleName(Json.str(draft, "style", "balanced"))));
        long until = Json.num(draft, "until", now() + 14 * 86_400L);
        int days = (int) Math.max(1, Math.round((until - now()) / 86_400.0));
        lines.add(getResources().getQuantityString(R.plurals.projects_card_valid, days, days));
        boolean bot = doc != null && doc.server.tradesItself(Json.str(draft, "trade", "auto"));
        lines.add(getString(bot ? R.string.projects_card_bot : R.string.projects_card_owner));
        JsonObject req = Json.obj(draft, "req");
        if (req != null) {
            String received = OrderDraft.receivedOf(draft);
            Boolean wears = OrderDraft.receivedWears(received, ctx);
            Long dur = Json.numOrNull(req, "dur");
            long pct = dur != null ? dur : (wears != null && wears ? 90 : 0);
            List<String> cond = new ArrayList<>();
            if (pct > 0) cond.add(getString(R.string.projects_card_durability, (int) pct));
            JsonObject ench = Json.obj(req, "ench");
            if (ench != null) for (Map.Entry<String, JsonElement> e : ench.entrySet()) cond.add(enchText(e.getKey(), PlusWords.level(e.getValue())));
            if (Json.bool(req, "named", false)) cond.add(getString(R.string.projects_card_named));
            if (Json.bool(req, "cursed", false)) cond.add(getString(R.string.projects_card_cursed));
            if (!cond.isEmpty()) lines.add(capitalize(join(cond)));
        }
        String note = Json.str(draft, "note", "");
        if (!note.isEmpty()) lines.add(getString(R.string.projects_card_wrote, note));
        for (String l : lines) {
            TextView t = CloudUi.text(c, l, 14, R.color.projects_ash_300, false);
            t.setLineSpacing(0, 1.12f);
            t.setPadding(0, CloudUi.dp(c, 4), 0, 0);
            card.addView(t);
        }
        for (String n : mNotes) {
            String words2 = noteWords(n);
            if (words2 == null) continue;
            TextView t = CloudUi.text(c, words2, 13.5f, R.color.projects_ash_300, false);
            t.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.projects_ic_info, 0, 0, 0);
            t.setCompoundDrawablePadding(CloudUi.dp(c, 8));
            androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(t, ColorStateList.valueOf(CloudUi.color(c, R.color.projects_ash_400)));
            t.setPadding(0, CloudUi.dp(c, 8), 0, 0);
            card.addView(t);
            if ("notify_only".equals(n)) {
                // The site's and the launcher's quiet button beside a limit reason: the first limit buying lacks.
                PlusConfig conf = config();
                final String field = conf.limit("perDealMax") == null ? "limits.perDealMax" : "limits.totalBudget";
                card.addView(CloudUi.textButton(c, getString(R.string.projects_to_limits), v -> {
                    String id = accountId();
                    if (id != null) PlusQuestionFragment.openLimit(this, id, field);
                }), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
        }

        // At most two questions, with ready answers.
        List<JsonObject> questions = OrderDraft.questionsOf(draft, ctx);
        int shown = Math.min(OrderDraft.SHOWN_QUESTIONS, questions.size());
        if (shown > 0) {
            View line = new View(c);
            line.setBackgroundColor(CloudUi.color(c, R.color.projects_obsidian_700));
            LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, CloudUi.dp(c, 1)));
            lineLp.topMargin = CloudUi.dp(c, 12);
            card.addView(line, lineLp);
            for (int i = 0; i < shown; i++) card.addView(question(c, draft, questions.get(i)));
        }

        // The one AI note of the order surface: where the assistant's reading is shown.
        TextView ai = CloudUi.text(c, getString(R.string.projects_ai_note), 12.5f, R.color.projects_ash_400, false);
        ai.setPadding(0, CloudUi.dp(c, 12), 0, 0);
        card.addView(ai);

        LinearLayout buttons = new LinearLayout(c);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        buttons.setPadding(0, CloudUi.dp(c, 8), 0, 0);
        Button discard = CloudUi.textButton(c, getString(R.string.projects_card_discard), v -> discard());
        discard.setTextColor(CloudUi.color(c, R.color.projects_ash_300));
        buttons.addView(discard);
        Button edit = CloudUi.button(c, getString(R.string.projects_card_edit), false, v -> openForm(null, draft, null));
        LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        editLp.setMarginStart(CloudUi.dp(c, 6));
        buttons.addView(edit, editLp);
        Button save = CloudUi.button(c, getString(R.string.projects_card_save), true, v -> saveCard(v, questions.size()));
        save.setEnabled(!mSaving);
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        saveLp.setMarginStart(CloudUi.dp(c, 8));
        buttons.addView(save, saveLp);
        card.addView(buttons, CloudUi.matchWrap());
        return card;
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(java.util.Locale.getDefault()) + s.substring(1);
    }

    private static String join(List<String> parts) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) out.append(", ");
            out.append(parts.get(i));
        }
        return out.toString();
    }

    private View figure(Context c, String label, @Nullable String value, @Nullable String under) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        row.setPadding(0, CloudUi.dp(c, 3), 0, CloudUi.dp(c, 3));
        row.addView(CloudUi.text(c, label, 14, R.color.projects_ash_300, false), CloudUi.weight(1f));
        LinearLayout right = new LinearLayout(c);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        right.addView(CloudUi.text(c, value == null ? getString(R.string.projects_card_unset) : value, 15,
                value == null ? R.color.projects_ash_400 : R.color.projects_bone_100, value != null));
        if (under != null) {
            TextView u = CloudUi.text(c, under, 12.5f, R.color.projects_ash_400, false);
            u.setGravity(Gravity.END);
            right.addView(u);
        }
        row.addView(right);
        return row;
    }

    @Nullable
    private String noteWords(String note) {
        switch (note) {
            case "approval_pref": return getString(R.string.projects_card_note_approval_pref);
            case "named_player": return getString(R.string.projects_card_note_named_player);
            case "notify_only": return getString(R.string.projects_card_note_notify_only);
            case "item_pay_off": return getString(R.string.projects_card_note_item_pay_off);
            default: return null;
        }
    }

    private String styleName(String style) {
        switch (style) {
            case "patient": return getString(R.string.projects_plus_style_patient);
            case "quick": return getString(R.string.projects_plus_style_quick);
            default: return getString(R.string.projects_plus_style_balanced);
        }
    }

    private String enchText(String id, int level) {
        String name = mData == null ? null : mData.enchantmentName(id, lang());
        if (name == null) name = gg.projects.mobile.cloud.CloudPrefs.bareName(id);
        return mData == null ? name + " " + level : name + " " + ItemData.levelText(level);
    }

    /** An item of the draft: a new custom item of the card by its name, else as every item is named. */
    private String draftName(String ref, JsonObject draft) {
        if (ref.startsWith("custom:")) {
            String k = ref.substring("custom:".length());
            for (JsonObject custom : Json.objects(draft, "customItems")) {
                if (k.equals(Json.str(custom, "k"))) {
                    String name = Json.str(custom, "name", "");
                    return name.isEmpty() ? getString(R.string.projects_items_unnamed) : name;
                }
            }
        }
        return itemName(ref);
    }

    @Nullable
    private String baseOf(@Nullable String ref, @Nullable JsonObject draft) {
        if (ref == null || !ref.startsWith("custom:")) return null;
        String k = ref.substring("custom:".length());
        if (draft != null) {
            for (JsonObject custom : Json.objects(draft, "customItems")) if (k.equals(Json.str(custom, "k"))) return Json.str(custom, "base");
        }
        JsonObject known = config().customItem(k);
        return known == null ? null : Json.str(known, "base");
    }

    private String price(long amount, String pay) {
        return PlusWords.price(ctx(), amount, pay, mDoc == null ? null : mDoc.server, this::itemName);
    }

    /** One question with its ready answers (chips); "Başka" opens an amount field in place. */
    private View question(final Context c, final JsonObject draft, final JsonObject q) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, CloudUi.dp(c, 12), 0, 0);
        final String kind = Json.str(q, "kind", "");
        String text;
        PlusChips chips = new PlusChips(c);
        switch (kind) {
            case "which": {
                text = getString(R.string.projects_q_which);
                for (final String option : Json.strings(q, "options")) {
                    chips.add(draftName(option, draft), v -> answer(q, OrderDraft.Choice.option(option)));
                }
                break;
            }
            case "side":
                text = getString(R.string.projects_q_side);
                chips.add(getString(R.string.projects_q_buying), v -> answer(q, OrderDraft.Choice.of("buy")));
                chips.add(getString(R.string.projects_q_selling), v -> answer(q, OrderDraft.Choice.of("sell")));
                break;
            case "custom": {
                text = getString(R.string.projects_q_custom, Json.str(q, "mention", ""));
                for (String chip : Json.strings(q, "chips")) {
                    final String word = chip;
                    int label = "pick".equals(chip) ? R.string.projects_q_custom_pick
                            : "first_seen".equals(chip) ? R.string.projects_q_custom_first_seen : R.string.projects_q_custom_fix;
                    if ("pick".equals(chip) && !canPick()) continue;
                    chips.add(getString(label), v -> answer(q, OrderDraft.Choice.of(word)));
                }
                break;
            }
            case "limit": {
                boolean sell = "sell".equals(Json.str(q, "side"));
                text = getString(sell ? R.string.projects_q_limit_sell : R.string.projects_q_limit_buy);
                for (JsonElement a : Json.arr(q, "amounts")) {
                    if (!PlusWords.isNumber(a)) continue;
                    final long amount = a.getAsLong();
                    chips.add(price(amount, Json.str(draft, "pay", "money")), v -> answer(q, OrderDraft.Choice.amount(amount)));
                }
                chips.add(getString(R.string.projects_q_other), v -> {
                    mOtherOpen = kind;
                    draw();
                }).setActivated(kind.equals(mOtherOpen));
                break;
            }
            case "pay": {
                boolean sell = "sell".equals(Json.str(draft, "side"));
                text = getString(sell ? R.string.projects_q_pay_sell : R.string.projects_q_pay_buy);
                chips.add(getString(R.string.projects_q_pay_money), v -> answer(q, OrderDraft.Choice.of("money")));
                String payItem = Json.str(q, "item");
                if (payItem != null) chips.add(draftName(payItem, draft), v -> answer(q, OrderDraft.Choice.of("item")));
                break;
            }
            case "pay_off": {
                boolean wantItem = "item".equals(Json.str(q, "want"));
                text = getString(wantItem ? R.string.projects_q_pay_off_item : R.string.projects_q_pay_off_money);
                chips.add(getString(R.string.projects_q_turn_on), v -> answer(q, OrderDraft.Choice.of("turn_on")));
                chips.add(getString(wantItem ? R.string.projects_q_use_money : R.string.projects_q_use_item), v -> answer(q, OrderDraft.Choice.of("switch")));
                break;
            }
            case "per":
                text = getString(R.string.projects_q_per, price(Json.num(q, "price", 0), Json.str(draft, "pay", "money")));
                chips.add(getString(R.string.projects_q_each), v -> answer(q, OrderDraft.Choice.of("each")));
                chips.add(getString(R.string.projects_q_total), v -> answer(q, OrderDraft.Choice.of("total")));
                break;
            case "qty": {
                text = getString(R.string.projects_q_qty);
                for (JsonElement a : Json.arr(q, "amounts")) {
                    if (!PlusWords.isNumber(a)) continue;
                    final long amount = a.getAsLong();
                    chips.add(String.valueOf(amount), v -> answer(q, OrderDraft.Choice.amount(amount)));
                }
                chips.add(getString(R.string.projects_q_other), v -> {
                    mOtherOpen = kind;
                    draw();
                }).setActivated(kind.equals(mOtherOpen));
                break;
            }
            case "far": {
                String pay = Json.str(draft, "pay", "money");
                text = getString(R.string.projects_q_far, price(Json.num(q, "limit", 0), pay), price(Json.num(q, "target", 0), pay));
                chips.add(getString(R.string.projects_q_yes_right), v -> answer(q, OrderDraft.Choice.of("yes")));
                chips.add(getString(R.string.projects_q_fix), v -> answer(q, OrderDraft.Choice.of("fix")));
                break;
            }
            case "over_cap": {
                String limit = price(Json.num(q, "limit", 0), "money");
                String cap = price(Json.num(q, "cap", 0), "money");
                text = getString(R.string.projects_q_over_cap, cap);
                chips.add(getString(R.string.projects_q_raise, limit), v -> answer(q, OrderDraft.Choice.of("raise")));
                chips.add(getString(R.string.projects_q_use_cap, cap), v -> answer(q, OrderDraft.Choice.of("use_cap")));
                break;
            }
            default:
                text = getString(R.string.projects_card_fix_first);
                break;
        }
        TextView title = CloudUi.text(c, text, 15, R.color.projects_bone_100, true);
        title.setLineSpacing(0, 1.12f);
        box.addView(title);
        LinearLayout.LayoutParams chipsLp = CloudUi.matchWrap();
        chipsLp.topMargin = CloudUi.dp(c, 8);
        box.addView(chips, chipsLp);
        if (kind.equals(mOtherOpen)) box.addView(other(c, q, kind));
        return box;
    }

    /** "Başka": an amount (or a count) of the player's own, then "Bunu kullan". */
    private View other(final Context c, final JsonObject q, final String kind) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.BOTTOM);
        final PlusAmount amount = new PlusAmount(c, getString(R.string.projects_q_other_label), 0);
        amount.field.view.setPadding(0, CloudUi.dp(c, 6), CloudUi.dp(c, 4), 0);
        row.addView(amount.field.view, CloudUi.weight(1f));
        Button use = CloudUi.button(c, getString(R.string.projects_q_use), false, v -> {
            Long value = amount.value();
            long max = "qty".equals(kind) ? PlusConfig.QTY_MAX : mDoc == null ? PlusModel.GENERIC_MAX_DEAL : mDoc.server.maxDeal;
            if (value == null || value < 1 || value > max) {
                amount.field.setError(getString(R.string.projects_form_error_amount));
                Haptics.reject(v);
                return;
            }
            mOtherOpen = null;
            answer(q, OrderDraft.Choice.amount(value));
        });
        LinearLayout.LayoutParams useLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        useLp.bottomMargin = CloudUi.dp(c, 2);
        row.addView(use, useLp);
        amount.field.input.requestFocus();
        return row;
    }

    /** A chip's answer: the card goes on with the new draft, or the form or the inventory takes over. */
    private void answer(JsonObject question, JsonElement choice) {
        JsonObject draft = mDraft;
        if (draft == null) return;
        OrderDraft.Answer a = OrderDraft.answer(draft, question, choice, draftCtx(), newKey(draft));
        switch (a.kind) {
            case FORM:
                openForm(null, draft, a.field);
                return;
            case PICK:
                startPick(a.field == null ? "item" : a.field);
                return;
            default:
                if (a.draft != null) mDraft = a.draft;
                if (a.settings != null) mCardSettings = merge(mCardSettings, a.settings);
                draw();
        }
    }

    private static JsonObject merge(@Nullable JsonObject into, JsonObject add) {
        JsonObject out = into == null ? new JsonObject() : into.deepCopy();
        for (Map.Entry<String, JsonElement> e : add.entrySet()) {
            if (e.getValue().isJsonObject() && out.has(e.getKey()) && out.get(e.getKey()).isJsonObject()) {
                JsonObject inner = out.getAsJsonObject(e.getKey());
                for (Map.Entry<String, JsonElement> f : e.getValue().getAsJsonObject().entrySet()) inner.add(f.getKey(), f.getValue().deepCopy());
            } else {
                out.add(e.getKey(), e.getValue().deepCopy());
            }
        }
        return out;
    }

    /** A price as the lists say it: "tanesi 1.800" for one piece, else as it is. */
    private String perPiece(String price, boolean each) {
        return each ? getString(R.string.projects_card_each_price, price) : price;
    }

    /** A custom item key in neither the account's items nor the card's. */
    private String newKey(JsonObject draft) {
        java.util.Set<String> taken = new java.util.HashSet<>();
        for (JsonObject custom : Json.objects(draft, "customItems")) taken.add(Json.str(custom, "k", ""));
        return config().newCustomKey(new java.util.Random(), taken);
    }

    private void discard() {
        mDraft = null;
        mNotes.clear();
        mCardSettings = null;
        mOtherOpen = null;
        draw();
    }

    private void saveCard(View from, int open) {
        JsonObject draft = mDraft;
        if (draft == null || mSaving) return;
        if (open > 0) {
            Haptics.reject(from);
            toast(getString(open > 1 ? R.string.projects_card_answer_first_many : R.string.projects_card_answer_first));
            return;
        }
        JsonObject order = OrderDraft.orderOf(draft, draftCtx());
        if (order == null) {
            Haptics.reject(from);
            toast(getString(R.string.projects_card_fix_first));
            return;
        }
        JsonArray custom = new JsonArray();
        for (JsonObject item : Json.objects(draft, "customItems")) custom.add(newCustom(item));
        if (!write(from, order, custom, mCardSettings, true, this::cardSaved, null)) busy(from);
    }

    /** The card's order is saved: the card and its words go. */
    private void cardSaved() {
        mDraft = null;
        mNotes.clear();
        mCardSettings = null;
        mOtherOpen = null;
        if (mField != null) mField.input.setText("");
    }

    /** Another write is still out: nothing was sent. */
    private void busy(@Nullable View from) {
        if (from != null) Haptics.reject(from);
        toast(getString(R.string.projects_order_busy));
    }

    /** A new custom item as the order write takes it (without {@code at}: the service sets it). */
    private static JsonObject newCustom(JsonObject item) {
        JsonObject out = item.deepCopy();
        out.remove("at");
        return out;
    }

    // ---- writes ----

    private interface Done {
        void done();
    }

    /**
     * {@code POST …/plus/orders}: the order (new, or put back with its id and {@code at}), its new custom items, a chip's
     * settings. False when another write is still out (nothing sent); else {@code after} hears how it ended (the
     * problem already shown).
     */
    private boolean write(final View from, final JsonObject order, @Nullable final JsonArray custom, @Nullable final JsonObject settings,
                          final boolean retry, @Nullable final Done done, @Nullable final PlusOrderSheet.Saved after) {
        final CloudModel.Account a = account();
        final CloudModel.PlusDoc doc = mDoc;
        if (mSaving) return false;
        if (a == null || doc == null) {
            if (after != null) after.saved(false);
            return true;
        }
        mSaving = true;
        mService.postOrder(a.id, doc.aiRev, order, custom, settings, new CloudService.Result<PlusModel.Written>() {
            @Override
            public void ok(PlusModel.Written w) {
                mSaving = false;
                written(w);
                if (done != null && isAdded()) done.done();
                if (after != null) after.saved(true);
                if (!alive()) return;
                Haptics.confirm(from);
                JsonObject saved = w.order == null ? order : w.order;
                toast(getString(R.string.projects_order_saved, itemName(Json.str(saved, "item", ""))));
                draw();
            }

            @Override
            public void failed(CloudProblem problem) {
                mSaving = false;
                if (!alive()) {
                    if (after != null) after.saved(false);
                    return;
                }
                if (problem.kind == CloudProblem.Kind.CONFLICT && retry) {
                    // Changed elsewhere (another device, an approval): read the new revision and send once more.
                    reloadThen(() -> {
                        String id = Json.str(order, "id");
                        if (id != null && config().order(id) != null) {
                            // Put back elsewhere meanwhile: it is there.
                            if (after != null) after.saved(true);
                            draw();
                            return;
                        }
                        if (!write(from, order, custom, settings, false, done, after)) {
                            busy(from);
                            if (after != null) after.saved(false);
                        }
                    }, after);
                    return;
                }
                writeProblem(problem, from);
                if (after != null) after.saved(false);
                draw();
            }
        });
        return true;
    }

    /** {@code PATCH …/plus/orders/<id>}: false when another write is still out (nothing sent). */
    private boolean patch(final View from, final String orderId, final JsonObject fields, final boolean retry, final int toastWords,
                          @Nullable final PlusOrderSheet.Saved after) {
        final CloudModel.Account a = account();
        final CloudModel.PlusDoc doc = mDoc;
        if (mSaving) return false;
        if (a == null || doc == null) {
            if (after != null) after.saved(false);
            return true;
        }
        mSaving = true;
        mService.patchOrder(a.id, doc.aiRev, orderId, fields, new CloudService.Result<PlusModel.Written>() {
            @Override
            public void ok(PlusModel.Written w) {
                mSaving = false;
                written(w);
                if (after != null) after.saved(true);
                if (!alive()) return;
                Haptics.confirm(from);
                JsonObject saved = w.order;
                String name = saved == null ? "" : itemName(Json.str(saved, "item", ""));
                if (toastWords == R.plurals.projects_order_extended) {
                    int days = mDoc == null ? 14 : mDoc.rules.orderDaysDefault;
                    toast(getResources().getQuantityString(R.plurals.projects_order_extended, days, days));
                } else {
                    toast(getString(toastWords, name));
                }
                draw();
            }

            @Override
            public void failed(CloudProblem problem) {
                mSaving = false;
                if (!alive()) {
                    if (after != null) after.saved(false);
                    return;
                }
                if (problem.kind == CloudProblem.Kind.CONFLICT && retry) {
                    reloadThen(() -> {
                        if (!patch(from, orderId, fields, false, toastWords, after)) {
                            busy(from);
                            if (after != null) after.saved(false);
                        }
                    }, after);
                    return;
                }
                writeProblem(problem, from);
                if (after != null) after.saved(false);
                draw();
            }
        });
        return true;
    }

    /** {@code DELETE …/plus/orders/<id>}: false when another write is still out (nothing sent). */
    private boolean delete(final View from, final JsonObject order, final boolean retry) {
        final CloudModel.Account a = account();
        final CloudModel.PlusDoc doc = mDoc;
        final String id = Json.str(order, "id");
        if (mSaving) return false;
        if (a == null || doc == null || id == null) return true;
        mSaving = true;
        mService.deleteOrder(a.id, doc.aiRev, id, new CloudService.Result<PlusModel.Written>() {
            @Override
            public void ok(PlusModel.Written w) {
                mSaving = false;
                written(w);
                if (!alive()) return;
                Haptics.confirm(from);
                if (w.deferred) {
                    toast(getString(R.string.projects_order_remove_deferred));
                    mUndo = null;
                } else {
                    mUndo = order;
                    mUndoUntil = System.currentTimeMillis() + UNDO_MS;
                }
                draw();
            }

            @Override
            public void failed(CloudProblem problem) {
                mSaving = false;
                if (!alive()) return;
                if (problem.kind == CloudProblem.Kind.CONFLICT && retry) {
                    reloadThen(() -> {
                        if (!delete(from, order, false)) busy(from);
                    }, null);
                    return;
                }
                writeProblem(problem, from);
                draw();
            }
        });
        return true;
    }

    /** An order write's answer: the new settings and revision (the order views stay until the machine sends new ones). */
    private void written(PlusModel.Written w) {
        CloudModel.PlusDoc doc = mDoc;
        String id = accountId();
        if (doc == null || id == null) return;
        mDoc = doc.with(w.config, w.aiRev, null, w.adPreview, null);
        mService.rememberDoc(id, mDoc);
    }

    /** Reads the settings again (after a 412), then {@code then}; {@code after} hears a failed read. */
    private void reloadThen(final Runnable then, @Nullable final PlusOrderSheet.Saved after) {
        CloudModel.Account a = account();
        if (a == null) {
            if (after != null) after.saved(false);
            return;
        }
        mService.plus(a.id, new CloudService.Result<CloudModel.PlusDoc>() {
            @Override
            public void ok(CloudModel.PlusDoc doc) {
                takeDoc(doc);
                if (alive()) then.run();
                else if (after != null) after.saved(false);
            }

            @Override
            public void failed(CloudProblem problem) {
                if (alive()) problem(problem, null);
                if (after != null) after.saved(false);
            }
        });
    }

    /** A read's settings, unless a write here already holds a newer revision (a slow read must not take a saved order back). */
    private void takeDoc(CloudModel.PlusDoc doc) {
        CloudModel.PlusDoc known = mDoc;
        if (known == null || doc.aiRev >= known.aiRev) mDoc = doc;
    }

    private void writeProblem(CloudProblem p, @Nullable View from) {
        Context c = ctx();
        if (from != null) Haptics.reject(from);
        if (p.is("limit_orders")) {
            toast(getString("stored".equals(p.string("scope")) ? R.string.projects_form_error_stored : R.string.projects_form_error_active));
        } else if (p.is("limit_rows") && "customItems".equals(p.field())) {
            toast(getString(R.string.projects_form_error_custom_full));
        } else if (p.kind == CloudProblem.Kind.RATE_LIMITED) {
            toast(getString("daily_writes".equals(p.reason()) ? R.string.projects_order_writes_daily : R.string.projects_order_rate));
        } else if (p.is("order_not_found")) {
            toast(getString(R.string.projects_order_gone));
            load();
        } else if (p.is("invalid_plus_config")) {
            toast(PlusOrderSheet.problemWords(c, p.field(), p.reason(), mDoc));
        } else {
            problem(p, from);
        }
    }

    // ---- the lists ----

    private void lists(LinearLayout content) {
        Context c = ctx();
        List<JsonObject> active = new ArrayList<>();
        List<JsonObject> finished = new ArrayList<>();
        final List<JsonObject> orders = config().orders();
        for (JsonObject o : orders) (PlusWords.finished(status(o)) ? finished : active).add(o);
        if (active.isEmpty() && finished.isEmpty()) {
            TextView empty = CloudUi.text(c, getString(R.string.projects_order_empty), 14.5f, R.color.projects_ash_300, false);
            empty.setPadding(CloudUi.dp(c, 8), CloudUi.dp(c, 20), CloudUi.dp(c, 8), CloudUi.dp(c, 8));
            content.addView(empty);
            return;
        }
        Collections.sort(active, (x, y) -> {
            int rx = rank(status(x));
            int ry = rank(status(y));
            if (rx != ry) return Integer.compare(rx, ry);
            return Long.compare(Json.num(y, "at", 0), Json.num(x, "at", 0));
        });
        Collections.sort(finished, (x, y) -> Long.compare(finishedAt(y), finishedAt(x)));
        if (!active.isEmpty()) group(content, getString(R.string.projects_order_active), active);
        if (!finished.isEmpty()) group(content, getString(R.string.projects_order_finished), finished);
        if (mDoc != null && mDoc.asOf != null) {
            content.addView(CloudUi.note(c, getString(R.string.projects_order_as_of_line, gg.projects.mobile.ui.AppDates.time(mDoc.asOf * 1000L))));
        }
    }

    private long finishedAt(JsonObject order) {
        PlusModel.OrderView view = mDoc == null ? null : mDoc.orders.get(Json.str(order, "id", ""));
        if ("done".equals(status(order)) && view != null && view.at > 0) return view.at;
        return Json.num(order, "until", 0);
    }

    private static int rank(String status) {
        switch (status) {
            case "waiting_ok":
            case "your_trade": return 0;
            case "trading":
            case "haggling": return 1;
            case "looking": return 2;
            default: return 3;
        }
    }

    private void group(LinearLayout content, String title, List<JsonObject> orders) {
        Context c = ctx();
        content.addView(CloudUi.section(c, title));
        LinearLayout group = CloudUi.group(c);
        List<View> rows = new ArrayList<>();
        for (JsonObject o : orders) rows.add(row(c, o));
        CloudUi.fill(group, rows);
        content.addView(group);
    }

    private View row(final Context c, final JsonObject order) {
        String status = status(order);
        String item = Json.str(order, "item", "");
        String side = Json.str(order, "side", "buy");
        boolean sell = "sell".equals(side);
        long qty = Json.num(order, "qty", 1);
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(CloudUi.dp(c, 68));
        row.setPaddingRelative(CloudUi.dp(c, 12), CloudUi.dp(c, 10), CloudUi.dp(c, 14), CloudUi.dp(c, 10));
        row.setBackgroundResource(R.drawable.projects_row_ripple);
        row.setClickable(true);
        row.setFocusable(true);
        row.addView(CloudItemArt.tile(c, item, baseOf(item, null), 40), new LinearLayout.LayoutParams(CloudUi.dp(c, 40), CloudUi.dp(c, 40)));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout top = new LinearLayout(c);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = CloudUi.text(c, itemName(item), 15, R.color.projects_bone_100, true);
        name.setMaxLines(2);
        top.addView(name, CloudUi.weight(1f));
        int tone;
        switch (status) {
            case "waiting_ok": case "your_trade": case "trading": case "haggling": tone = R.color.projects_bone_50; break;
            case "side_off": case "paused": case "expired": tone = R.color.projects_ash_400; break;
            default: tone = R.color.projects_ash_300; break;
        }
        TextView word = CloudUi.text(c, maintenance() ? getString(R.string.projects_order_status_service) : PlusWords.status(c, status, side), 13, tone,
                PlusWords.waitsForPlayer(status));
        LinearLayout.LayoutParams wordLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wordLp.setMarginStart(CloudUi.dp(c, 8));
        top.addView(word, wordLp);
        texts.addView(top);
        texts.addView(CloudUi.text(c, meta(c, order, status), 13, R.color.projects_ash_300, false));
        texts.addView(CloudUi.text(c, getString(sell ? R.string.projects_order_progress_sold : R.string.projects_order_progress_bought,
                (int) Math.min(got(order), qty), (int) qty), 12.5f, R.color.projects_ash_400, false));
        LinearLayout.LayoutParams textsLp = CloudUi.weight(1f);
        textsLp.setMarginStart(CloudUi.dp(c, 12));
        row.addView(texts, textsLp);
        ImageView more = new ImageView(c);
        more.setImageResource(R.drawable.projects_ic_dots);
        more.setImageTintList(ColorStateList.valueOf(CloudUi.color(c, R.color.projects_ash_400)));
        more.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams moreLp = new LinearLayout.LayoutParams(CloudUi.dp(c, 20), CloudUi.dp(c, 20));
        moreLp.setMarginStart(CloudUi.dp(c, 8));
        row.addView(more, moreLp);
        row.setOnClickListener(v -> actions(order));
        gg.projects.mobile.ui.Press.attach(row, gg.projects.mobile.ui.Press.Style.ROW);
        row.setContentDescription(name.getText() + ", " + word.getText() + ", " + meta(c, order, status));
        row.setLayoutParams(CloudUi.matchWrap());
        return row;
    }

    /** "Alınacak, en fazla 40.000, 2 gün kaldı": the side, the bound, the time left. */
    private String meta(Context c, JsonObject order, String status) {
        boolean sell = "sell".equals(Json.str(order, "side"));
        long qty = Json.num(order, "qty", 1);
        String pay = Json.str(order, "pay", "money");
        String bound = price(Json.num(order, "limit", 0), pay);
        String terms = getString(sell ? R.string.projects_order_meta_at_least : R.string.projects_order_meta_at_most, bound);
        if (qty > 1) {
            terms = getString("each".equals(Json.str(order, "unit")) ? R.string.projects_order_per_piece : R.string.projects_order_in_total, terms);
        }
        boolean finished = PlusWords.finished(status);
        int side = finished ? (sell ? R.string.projects_order_meta_sell_done : R.string.projects_order_meta_buy_done)
                : (sell ? R.string.projects_card_to_sell : R.string.projects_card_to_buy);
        StringBuilder out = new StringBuilder(getString(side)).append(", ").append(terms);
        if (!finished) {
            long left = Json.num(order, "until", 0) - now();
            int days = (int) (left / 86_400);
            // One piece when the line wraps (big text, 360 dp): "9 gün kaldı" never breaks after its number.
            String time = days < 1 ? getString(R.string.projects_order_ends_today)
                    : getResources().getQuantityString(R.plurals.projects_order_days_left, days, days);
            out.append(", ").append(time.replace(' ', '\u00A0'));
        }
        return out.toString();
    }

    /** A row's actions, as it allows them. */
    private void actions(final JsonObject order) {
        final Context c = ctx();
        final String status = status(order);
        final String id = Json.str(order, "id", "");
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(CloudUi.dp(c, 16), 0, CloudUi.dp(c, 16), CloudUi.dp(c, 12));
        TextView title = CloudUi.text(c, itemName(Json.str(order, "item", "")), 17, R.color.projects_bone_100, true);
        title.setTypeface(CloudUi.font(c, R.font.tektur_semibold));
        title.setPadding(CloudUi.dp(c, 8), 0, CloudUi.dp(c, 8), CloudUi.dp(c, 2));
        root.addView(title);
        TextView sub = CloudUi.text(c, meta(c, order, status), 13.5f, R.color.projects_ash_300, false);
        sub.setPadding(CloudUi.dp(c, 8), 0, CloudUi.dp(c, 8), CloudUi.dp(c, 8));
        root.addView(sub);
        final BottomPanel panel = keep(BottomPanel.of(c, root, 0.7f));
        LinearLayout group = CloudUi.group(c);
        List<View> rows = new ArrayList<>();
        boolean finished = PlusWords.finished(status);
        boolean writable = !maintenance();
        if (PlusWords.waitsForPlayer(status)) {
            rows.add(CloudUi.actionRow(c, R.drawable.projects_ic_check_circle, getString(R.string.projects_order_to_approvals), v -> {
                panel.dismiss();
                openFor(PlusApprovalsFragment.class, accountId());
            }).view);
        }
        if (writable && !finished) {
            rows.add(CloudUi.actionRow(c, R.drawable.projects_ic_pencil, getString(R.string.projects_order_edit), v -> {
                panel.dismiss();
                openForm(order, null, null);
            }).view);
            final boolean on = Json.bool(order, "on", true);
            rows.add(CloudUi.actionRow(c, on ? R.drawable.projects_ic_hand_palm : R.drawable.projects_ic_arrows_clockwise,
                    getString(on ? R.string.projects_order_pause : R.string.projects_order_resume), v -> {
                        panel.dismiss();
                        JsonObject fields = new JsonObject();
                        fields.addProperty("on", !on);
                        if (!patch(v, id, fields, true, R.string.projects_order_changed, null)) busy(v);
                    }).view);
            rows.add(CloudUi.actionRow(c, R.drawable.projects_ic_clock, getString(R.string.projects_order_extend), v -> {
                panel.dismiss();
                JsonObject fields = new JsonObject();
                fields.addProperty("extend", true);
                if (!patch(v, id, fields, true, R.plurals.projects_order_extended, null)) busy(v);
            }).view);
        }
        if (writable && finished) {
            rows.add(CloudUi.actionRow(c, R.drawable.projects_ic_arrows_clockwise, getString(R.string.projects_order_restart), v -> {
                panel.dismiss();
                restart(v, order);
            }).view);
        }
        if (writable) {
            CloudUi.Row remove = CloudUi.actionRow(c, R.drawable.projects_ic_trash, getString(R.string.projects_order_remove), v -> {
                panel.dismiss();
                if (!delete(v, order, true)) busy(v);
            });
            remove.title.setTextColor(CloudUi.color(c, R.color.projects_flare_400));
            rows.add(remove.view);
        }
        CloudUi.fill(group, rows);
        root.addView(group);
        panel.show();
    }

    /** "Yeniden başlat": the old order's terms as a new order (a new id, a new term, no progress). */
    private void restart(View from, JsonObject old) {
        JsonObject order = old.deepCopy();
        order.remove("id");
        order.remove("at");
        order.addProperty("on", true);
        int days = mDoc == null ? 14 : mDoc.rules.orderDaysDefault;
        order.addProperty("until", now() + days * 86_400L);
        if (!write(from, order, null, null, true, null, null)) busy(from);
    }

    // ---- "Geri al" ----

    private void bottom() {
        FrameLayout bottom = mBottom;
        if (bottom == null) return;
        bottom.removeAllViews();
        View view = getView();
        if (mUndo == null || System.currentTimeMillis() >= mUndoUntil) {
            bottom.setVisibility(View.GONE);
            return;
        }
        Context c = ctx();
        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(CloudUi.color(c, R.color.projects_obsidian_850));
        bar.setPadding(CloudUi.dp(c, 16), CloudUi.dp(c, 6), CloudUi.dp(c, 8), CloudUi.dp(c, 6));
        TextView text = CloudUi.text(c, getString(R.string.projects_order_removed), 14.5f, R.color.projects_bone_100, false);
        text.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        bar.addView(text, CloudUi.weight(1f));
        final JsonObject undo = mUndo;
        bar.addView(CloudUi.textButton(c, getString(R.string.projects_order_undo), v -> {
            // Put back with its id and "at", so its progress returns; the bar goes once it is back.
            boolean sent = write(v, undo, null, null, true, null, ok -> {
                if (ok && mUndo == undo) mUndo = null;
                if (alive()) bottom();
            });
            if (!sent) busy(v);
        }));
        bottom.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bottom.setVisibility(View.VISIBLE);
        if (view != null) {
            view.removeCallbacks(mUndoEnd);
            view.postDelayed(mUndoEnd, Math.max(0, mUndoUntil - System.currentTimeMillis()));
        }
    }

    private void undoEnded() {
        mUndo = null;
        if (alive()) bottom();
    }

    // ---- the form and the inventory ----

    /** The form: an order to edit, a draft to finish (the card's "Düzenle", a chip's "fix"), or a new one. */
    private void openForm(@Nullable JsonObject order, @Nullable JsonObject draft, @Nullable String focus) {
        CloudModel.PlusDoc doc = mDoc;
        if (doc == null) return;
        PlusOrderSheet.open(this, order, draft, focus);
    }

    private void startPick(@Nullable String forCard) {
        String id = accountId();
        if (id == null) return;
        mPickFor = forCard;
        CloudInventoryFragment.pick(this, id, PICK_KEY);
    }

    /** The inventory's answer: the machine reads the slot, then the card or a new form takes the item. */
    private void picked(Bundle result) {
        final CloudModel.Account a = account();
        if (a == null || !a.id.equals(result.getString(CloudInventoryFragment.PICKED_ACCOUNT))) return;
        final String forCard = mPickFor;
        mPickFor = null;
        if (!"inv".equals(result.getString(CloudInventoryFragment.PICKED_AREA))) {
            toast(getString(R.string.projects_pick_own_only));
            return;
        }
        int slot = result.getInt(CloudInventoryFragment.PICKED_SLOT, -1);
        String itemId = result.getString(CloudInventoryFragment.PICKED_ID);
        if (slot < 5 || slot > 45 || itemId == null) {
            toast(getString(R.string.projects_pick_own_only));
            return;
        }
        Long cmd = result.containsKey(CloudInventoryFragment.PICKED_CMD) ? (long) result.getInt(CloudInventoryFragment.PICKED_CMD) : null;
        String model = result.getString(CloudInventoryFragment.PICKED_MODEL);
        toast(getString(R.string.projects_pick_reading));
        mService.pick(a.id, slot, itemId, cmd, model, new CloudService.Result<PlusModel.Pick>() {
            @Override
            public void ok(PlusModel.Pick pick) {
                if (!alive()) return;
                usePick(pick, forCard);
            }

            @Override
            public void failed(CloudProblem problem) {
                if (!alive()) return;
                toast(pickProblem(problem));
            }
        });
    }

    private String pickProblem(CloudProblem p) {
        if (p.is("pick_failed")) {
            String reason = p.reason();
            if (reason != null) {
                switch (reason) {
                    case "empty": return getString(R.string.projects_pick_failed_empty);
                    case "stale": return getString(R.string.projects_pick_failed_stale);
                    case "unparsed": return getString(R.string.projects_pick_failed_unparsed);
                    case "container": return getString(R.string.projects_pick_failed_container);
                    case "not_in_game": return getString(R.string.projects_pick_failed_not_in_game);
                    case "inventory_off": return getString(R.string.projects_pick_failed_inventory_off);
                    case "custom_off": return getString(R.string.projects_pick_failed_custom_off);
                    default: break;
                }
            }
            return getString(R.string.projects_pick_error);
        }
        if (p.kind == CloudProblem.Kind.HOST_OFFLINE) return getString(R.string.projects_pick_offline);
        if (p.kind == CloudProblem.Kind.RATE_LIMITED) return getString(R.string.projects_order_rate);
        return getString(R.string.projects_pick_error);
    }

    /**
     * A picked item: a plain game item with its conditions, a custom item the account knows (the same fingerprint: no new
     * record), or a new custom item. For the card it answers the question it was asked for; else a form starts with it,
     * sold by default (it is in hand).
     */
    private void usePick(PlusModel.Pick pick, @Nullable String forCard) {
        JsonObject newCustom = null;
        String ref;
        if (pick.ref != null) {
            ref = pick.ref;
        } else {
            JsonObject custom = pick.custom == null ? new JsonObject() : pick.custom;
            String fp = Json.str(custom, "fp");
            String known = null;
            for (JsonObject item : config().customItems()) if (fp != null && fp.equals(Json.str(item, "fp"))) known = Json.str(item, "k");
            if (known != null) {
                ref = "custom:" + known;
            } else {
                JsonObject draft = mDraft;
                String k = draft != null ? newKey(draft) : config().newCustomKey(new java.util.Random(), new java.util.HashSet<String>());
                newCustom = new JsonObject();
                newCustom.addProperty("k", k);
                String name = Json.str(custom, "name");
                newCustom.addProperty("name", name == null ? "" : OrderDraft.cutName(name));
                newCustom.add("base", custom.get("base") == null ? com.google.gson.JsonNull.INSTANCE : custom.get("base").deepCopy());
                newCustom.add("fp", custom.get("fp") == null ? com.google.gson.JsonNull.INSTANCE : custom.get("fp").deepCopy());
                newCustom.add("look", custom.get("look") == null ? com.google.gson.JsonNull.INSTANCE : custom.get("look").deepCopy());
                newCustom.addProperty("from", "inv");
                ref = "custom:" + k;
            }
        }
        JsonObject draft = mDraft;
        if (forCard != null && draft != null) {
            JsonObject next = draft.deepCopy();
            next.addProperty(forCard, ref);
            if (pick.ref != null && pick.req != null && ref.equals(OrderDraft.receivedOf(next)) && Json.obj(next, "req") != null) {
                JsonObject req = Json.obj(next, "req");
                req.add("ench", pick.req.get("ench") == null ? new JsonObject() : pick.req.get("ench").deepCopy());
                req.add("dur", pick.req.get("dur") == null ? com.google.gson.JsonNull.INSTANCE : pick.req.get("dur").deepCopy());
            }
            if (newCustom != null) {
                JsonArray own = next.has("customItems") && next.get("customItems").isJsonArray() ? next.getAsJsonArray("customItems") : new JsonArray();
                own.add(newCustom);
                next.add("customItems", own);
            }
            JsonObject open = Json.obj(next, "open");
            if (open != null) open.remove(forCard);
            mDraft = OrderDraft.fill(next, draftCtx());
            draw();
            // A new custom item without a name of its own asks for one in the form.
            if (newCustom != null && Json.str(newCustom, "name", "").isEmpty()) openForm(null, mDraft, "name");
            return;
        }
        PlusOrderSheet.openPicked(this, ref, pick.ref != null ? pick.req : null, newCustom);
    }

    // ---- PlusOrderSheet.Host ----

    @Override
    public Context context() {
        return requireContext();
    }

    @Nullable
    @Override
    public CloudModel.PlusDoc doc() {
        return mDoc;
    }

    @Nullable
    @Override
    public ItemData data() {
        return mData;
    }

    @Override
    public boolean dataFailed() {
        return mDataFailed;
    }

    @Override
    public void retryData() {
        if (mDoc == null) return;
        mDataFailed = false;
        loadData(mDoc);
    }

    @Override
    public String name(String ref) {
        return itemName(ref);
    }

    @Override
    public String lang() {
        return super.lang();
    }

    @Override
    public boolean pickAllowed() {
        return canPick();
    }

    @Override
    public void pickForForm() {
        startPick(null);
    }

    @Override
    public long nowSeconds() {
        return now();
    }

    @Override
    public BottomPanel sheet(BottomPanel panel) {
        return keep(panel);
    }

    @Override
    public boolean saveNew(View from, JsonObject order, @Nullable JsonArray custom, boolean fromCard, PlusOrderSheet.Saved saved) {
        return write(from, order, custom, fromCard ? mCardSettings : null, true, fromCard ? this::cardSaved : null, saved);
    }

    @Override
    public boolean saveChange(View from, String orderId, JsonObject fields, PlusOrderSheet.Saved saved) {
        return patch(from, orderId, fields, true, R.string.projects_order_changed, saved);
    }

    @Override
    public void removeOrder(View from, JsonObject order) {
        if (!delete(from, order, true)) busy(from);
    }

    // ---- the live socket ----

    @Override
    public void liveState(CloudService.Live state) {}

    @Override
    public void console(List<CloudModel.ConsoleLine> lines, int skipped, boolean snapshot) {}

    @Override
    public void approval(CloudModel.Approval approval) {}

    @Override
    public void orders(Map<String, PlusModel.OrderView> orders) {
        CloudModel.PlusDoc doc = mDoc;
        if (doc == null || orders.isEmpty()) return;
        mDoc = doc.withOrders(orders);
        if (alive()) draw();
    }

    @Override
    public void hostOnline(boolean online) {
        // The machine is back: the views from its last snapshot give way to live ones.
        if (online && machineAway()) load();
    }
}
