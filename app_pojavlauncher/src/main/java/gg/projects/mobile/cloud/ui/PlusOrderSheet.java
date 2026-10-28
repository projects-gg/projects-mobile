package gg.projects.mobile.cloud.ui;

import android.content.Context;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import net.kdt.pojavlaunch.R;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import gg.projects.mobile.cloud.CloudModel;
import gg.projects.mobile.cloud.CloudWords;
import gg.projects.mobile.cloud.PlusConfig;
import gg.projects.mobile.cloud.PlusModel;
import gg.projects.mobile.cloud.items.ItemData;
import gg.projects.mobile.cloud.plus.OrderDraft;
import gg.projects.mobile.cloud.plus.PlusText;
import gg.projects.mobile.social.Json;
import gg.projects.mobile.social.ui.BottomPanel;
import gg.projects.mobile.social.ui.SocialUi;
import gg.projects.mobile.ui.Haptics;
import gg.projects.mobile.ui.Press;

/**
 * The order form (afkplus-free.md §7.2, §7.6; the launcher's {@code order-form.tsx}), a bottom sheet: buy or sell, the
 * item (the game's items from the account version's item data, the account's custom items, a new custom item by name, or
 * the bot's inventory), how many, what it is paid with, each or in total, the target and the limit (written out in words),
 * the haggling, how long it stays, who makes the trade (only where the bot can), and the conditions of what the bot
 * receives (durability, the enchantments that fit it, anvil names, curses). It checks what the service would refuse before
 * it sends; an existing order changes only its changed fields (its item and side stay).
 */
final class PlusOrderSheet {
    private PlusOrderSheet() {}

    interface Host {
        Context context();

        @Nullable
        CloudModel.PlusDoc doc();

        @Nullable
        ItemData data();

        boolean dataFailed();

        void retryData();

        String name(String ref);

        String lang();

        boolean pickAllowed();

        /** The form closes and the bot's inventory opens; a pick starts a new form with the item. */
        void pickForForm();

        long nowSeconds();

        /** Keeps a sheet to close it with the screen; returns it. */
        BottomPanel sheet(BottomPanel panel);

        /**
         * {@code POST} a new order (with its new custom items); {@code fromCard}: it finishes the card's draft. False when
         * another write is still going (nothing was sent); else {@code saved} hears how it ended.
         */
        boolean saveNew(View from, JsonObject order, @Nullable JsonArray custom, boolean fromCard, Saved saved);

        /** {@code PATCH} an order's changed fields; false when another write is still going. */
        boolean saveChange(View from, String orderId, JsonObject fields, Saved saved);

        void removeOrder(View from, JsonObject order);
    }

    /** How a write ended: saved (the form closes), or not (it stays with what the player typed; the screen said why). */
    interface Saved {
        void saved(boolean ok);
    }

    private static final int[] DAYS = {7, 14, 30};

    /** Edits {@code order}, or finishes {@code draft} (the card's), or starts a new order; {@code focus}: the field to open at. */
    static void open(Host host, @Nullable JsonObject order, @Nullable JsonObject draft, @Nullable String focus) {
        new Form(host, order, draft, null, null, null).show(focus);
    }

    /** A new order with an item picked from the bot's inventory: sold by default (it is in hand). */
    static void openPicked(Host host, String ref, @Nullable JsonObject req, @Nullable JsonObject newCustom) {
        new Form(host, null, null, ref, req, newCustom).show(newCustom != null && Json.str(newCustom, "name", "").isEmpty() ? "name" : null);
    }

    /** An {@code invalid_plus_config} refusal of an order write, in words. */
    static String problemWords(Context c, @Nullable String field, @Nullable String reason, @Nullable CloudModel.PlusDoc doc) {
        String f = field == null ? "" : field;
        String r = reason == null ? "" : reason;
        if (f.endsWith(".item") || "item".equals(r)) return c.getString(R.string.projects_form_error_item);
        if ("same".equals(r)) return c.getString(R.string.projects_form_error_same);
        if ("order".equals(r)) return c.getString(f.contains("sell") ? R.string.projects_form_error_at_least : R.string.projects_form_error_at_most);
        if (f.contains("customItems") && f.endsWith(".name")) return c.getString(R.string.projects_form_error_name);
        return c.getString(R.string.projects_form_error_value);
    }

    /** The form's state and views. */
    private static final class Form {
        final Host host;
        final Context c;
        @Nullable
        final JsonObject order;
        @Nullable
        final JsonObject draft;
        final boolean editing;
        final PlusConfig cfg;
        @Nullable
        final CloudModel.PlusDoc doc;

        String side;
        @Nullable
        String item;
        String pay;
        String unit;
        String style;
        String trade;
        /** Days from now, or 0: keep the order's own term. */
        int days;
        JsonObject req;
        /** New custom items the order names (picked, typed, or the card's). */
        final Map<String, JsonObject> newCustom = new LinkedHashMap<>();
        final String src;
        final String note;

        final LinearLayout root;
        final LinearLayout body;
        final BottomPanel panel;
        final LinearLayout dynamic;
        CloudUi.Field qty;
        @Nullable
        CloudUi.Field customName;
        PlusAmount target;
        PlusAmount limit;
        @Nullable
        CloudUi.Field durability;
        TextView itemError;
        TextView payError;
        boolean confirmedFar;
        /** An item payment while swaps are off became money: its counts are no prices, the player writes new ones. */
        boolean payForced;
        /** A write is out: the form waits for its answer. */
        boolean saving;
        @Nullable
        Button saveButton;
        /** The quantity as typed (kept across a rebuild when the item changes). */
        @Nullable
        String qtyText;

        Form(Host host, @Nullable JsonObject order, @Nullable JsonObject draft, @Nullable String pickedRef, @Nullable JsonObject pickedReq,
             @Nullable JsonObject pickedCustom) {
            this.host = host;
            this.c = host.context();
            this.order = order;
            this.draft = draft;
            this.editing = order != null;
            this.doc = host.doc();
            this.cfg = new PlusConfig(doc == null ? new JsonObject() : doc.config);
            JsonObject base = order != null ? order : draft;
            side = Json.str(base, "side", pickedRef != null ? "sell" : "buy");
            if (!"sell".equals(side)) side = "buy";
            item = pickedRef != null ? pickedRef : Json.str(base, "item");
            String settingPay = cfg.pay();
            String storedPay = Json.str(base, "pay");
            pay = storedPay != null ? storedPay : "items".equals(settingPay) && itemPayOn() ? "" : "money";
            unit = Json.str(base, "unit", "total");
            style = Json.str(base, "style", "balanced");
            trade = Json.str(base, "trade", "auto");
            days = editing ? 0 : 14;
            if (doc != null && !editing) days = doc.rules.orderDaysDefault;
            if (draft != null) {
                long until = Json.num(draft, "until", 0);
                long left = until - host.nowSeconds();
                days = left > 0 ? (int) Math.max(1, Math.round(left / 86_400.0)) : days;
            }
            JsonObject storedReq = Json.obj(base, "req");
            req = storedReq != null ? storedReq.deepCopy() : pickedReq != null ? pickedReq.deepCopy() : emptyReq();
            if (!req.has("ench") || !req.get("ench").isJsonObject()) req.add("ench", new JsonObject());
            if (draft != null) for (JsonObject custom : Json.objects(draft, "customItems")) newCustom.put(Json.str(custom, "k", ""), custom.deepCopy());
            if (pickedCustom != null) newCustom.put(Json.str(pickedCustom, "k", ""), pickedCustom.deepCopy());
            src = draft != null ? Json.str(draft, "src", "text") : order != null ? Json.str(order, "src", "form") : pickedRef != null ? "inv" : "form";
            note = draft != null ? Json.str(draft, "note", "") : order != null ? Json.str(order, "note", "") : "";

            ScrollView scroll = new ScrollView(c);
            scroll.setClipToPadding(false);
            root = new LinearLayout(c);
            root.setOrientation(LinearLayout.VERTICAL);
            TextView heading = CloudUi.text(c, c.getString(editing ? R.string.projects_form_edit_title : R.string.projects_form_new_title), 17,
                    R.color.projects_bone_100, true);
            heading.setTypeface(CloudUi.font(c, R.font.tektur_semibold));
            heading.setPadding(CloudUi.dp(c, 24), CloudUi.dp(c, 2), CloudUi.dp(c, 24), CloudUi.dp(c, 6));
            if (android.os.Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
            root.addView(heading);
            body = new LinearLayout(c);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(CloudUi.dp(c, 16), 0, CloudUi.dp(c, 16), CloudUi.dp(c, 8));
            root.addView(body, CloudUi.matchWrap());
            dynamic = new LinearLayout(c);
            dynamic.setOrientation(LinearLayout.VERTICAL);
            scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            panel = host.sheet(BottomPanel.of(c, scroll, 0.92f));
        }

        private static JsonObject emptyReq() {
            JsonObject r = new JsonObject();
            r.add("ench", new JsonObject());
            r.add("dur", JsonNull.INSTANCE);
            r.addProperty("named", false);
            r.addProperty("cursed", false);
            return r;
        }

        boolean itemPayOn() {
            return doc != null && doc.rules.itemPay && !"money".equals(cfg.pay());
        }

        boolean moneyAllowed() {
            return !"items".equals(cfg.pay()) || !(doc != null && doc.rules.itemPay);
        }

        boolean money() {
            return "money".equals(pay);
        }

        long maxDeal() {
            return doc == null ? PlusModel.GENERIC_MAX_DEAL : doc.server.maxDeal;
        }

        /** What the bot receives: the item when buying, the paying item when selling for one; null when selling for money. */
        @Nullable
        String received() {
            if ("buy".equals(side)) return item;
            return money() || pay.isEmpty() ? null : pay;
        }

        @Nullable
        String baseOf(@Nullable String ref) {
            if (ref == null || !ref.startsWith("custom:")) return null;
            String k = ref.substring("custom:".length());
            JsonObject fresh = newCustom.get(k);
            if (fresh != null) return Json.str(fresh, "base");
            JsonObject known = cfg.customItem(k);
            return known == null ? null : Json.str(known, "base");
        }

        String nameOf(@Nullable String ref) {
            if (ref == null || ref.isEmpty()) return c.getString(R.string.projects_form_search);
            if (ref.startsWith("custom:")) {
                JsonObject fresh = newCustom.get(ref.substring("custom:".length()));
                if (fresh != null) {
                    String n = Json.str(fresh, "name", "");
                    return n.isEmpty() ? c.getString(R.string.projects_items_unnamed) : n;
                }
            }
            return host.name(ref);
        }

        @Nullable
        ItemData.ItemInfo infoOf(@Nullable String ref) {
            ItemData data = host.data();
            if (data == null || ref == null) return null;
            if (ref.startsWith("custom:")) {
                String base = baseOf(ref);
                return base == null ? null : data.itemInfo(base);
            }
            return data.itemInfo(ref);
        }

        void show(@Nullable String focus) {
            build();
            panel.show();
            if ("name".equals(focus) && customName != null) customName.input.requestFocus();
            else if ("limit".equals(focus)) limit.field.input.requestFocus();
            else if ("item".equals(focus) && !editing) searchItem(false);
            else if ("pay".equals(focus)) searchItem(true);
        }

        void build() {
            body.removeAllViews();
            dynamic.removeAllViews();
            // Buy or sell, and the item: an existing order keeps both (a new one with other terms is "Yeniden başlat").
            if (!editing) {
                body.addView(CloudRowSheet.label(c, c.getString(R.string.projects_form_side)));
                body.addView(CloudUi.segmented(c, new CharSequence[]{c.getString(R.string.projects_form_buy), c.getString(R.string.projects_form_sell)},
                        "sell".equals(side) ? 1 : 0, i -> {
                            side = i == 1 ? "sell" : "buy";
                            redrawDynamic();
                        }));
            }
            body.addView(CloudRowSheet.label(c, c.getString(R.string.projects_form_item)));
            body.addView(itemRow(item, v -> {
                if (!editing) searchItem(false);
            }, editing));
            itemError = CloudRowSheet.error(c);
            body.addView(itemError);
            if (!editing && host.pickAllowed()) {
                Button pick = CloudUi.textButton(c, c.getString(R.string.projects_order_pick), v -> {
                    panel.dismiss();
                    host.pickForForm();
                });
                body.addView(pick, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            if (host.data() == null && host.dataFailed()) {
                body.addView(CloudUi.note(c, c.getString(R.string.projects_form_data_failed)));
                body.addView(CloudUi.textButton(c, c.getString(R.string.projects_form_retry), v -> host.retryData()),
                        new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            // A new custom item's name (the one the assistant uses in chat).
            customName = null;
            if (item != null && item.startsWith("custom:") && newCustom.containsKey(item.substring("custom:".length()))) {
                final JsonObject fresh = newCustom.get(item.substring("custom:".length()));
                CloudUi.Field name = CloudUi.field(c, c.getString(R.string.projects_form_custom_name), Json.str(fresh, "name", ""), null,
                        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
                name.input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(40)});
                name.setHelp(c.getString(Json.str(fresh, "fp") == null ? R.string.projects_form_custom_first_sight : R.string.projects_form_custom_known));
                watch(name.input, s -> fresh.addProperty("name", s.trim()));
                body.addView(name.view);
                customName = name;
            }
            String startQty = qtyText != null ? qtyText : String.valueOf(Json.num(order != null ? order : draft, "qty", 1));
            qty = CloudUi.field(c, c.getString(R.string.projects_form_qty), startQty, null, InputType.TYPE_CLASS_NUMBER);
            qty.input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});
            watch(qty.input, s -> {
                qtyText = s;
                qty.setError(null);
                stacksHelp();
                redrawDynamic();
            });
            body.addView(qty.view);
            stacksHelp();
            body.addView(dynamic, CloudUi.matchWrap());
            redrawDynamic();
            actions();
        }

        void stacksHelp() {
            ItemData.ItemInfo info = infoOf(item);
            int n = CloudRowSheet.number(qty.value(), 0);
            if (info != null && info.stack > 1 && n >= info.stack && n % info.stack == 0) {
                int stacks = n / info.stack;
                qty.setHelp(c.getResources().getQuantityString(R.plurals.projects_form_stacks, stacks, stacks));
            } else {
                qty.setHelp(null);
            }
        }

        /** The parts that change with the side, the quantity and the payment. */
        void redrawDynamic() {
            dynamic.removeAllViews();
            boolean many = CloudRowSheet.number(qty.value(), 1) > 1;
            // In return: money or an item, as the account's payment setting and Studio's switch allow.
            payError = CloudRowSheet.error(c);
            if (itemPayOn()) {
                dynamic.addView(CloudRowSheet.label(c, c.getString(R.string.projects_form_in_return)));
                boolean moneyOk = moneyAllowed();
                final List<String> kinds = new ArrayList<>();
                List<CharSequence> labels = new ArrayList<>();
                if (moneyOk) {
                    kinds.add("money");
                    labels.add(c.getString(R.string.projects_form_money));
                }
                kinds.add("item");
                labels.add(c.getString(R.string.projects_form_pay_item));
                int at = money() ? 0 : kinds.indexOf("item");
                dynamic.addView(CloudUi.segmented(c, labels.toArray(new CharSequence[0]), Math.max(0, at), i -> {
                    String chosen = kinds.get(i);
                    if ("money".equals(chosen)) pay = "money";
                    else if (money()) pay = "";
                    redrawDynamic();
                }));
                if (!money()) {
                    dynamic.addView(CloudRowSheet.label(c, c.getString(R.string.projects_form_pay_with)));
                    dynamic.addView(itemRow(pay.isEmpty() ? null : pay, v -> searchItem(true), false));
                }
                dynamic.addView(payError);
            } else if (!money()) {
                // An item payment while swaps are off (an old order): it can only become money, at prices written anew.
                pay = "money";
                payForced = true;
            }
            if (payForced) dynamic.addView(CloudUi.note(c, c.getString(R.string.projects_form_pay_forced)));
            if (many) {
                dynamic.addView(CloudRowSheet.label(c, c.getString(R.string.projects_form_unit)));
                dynamic.addView(CloudUi.segmented(c, new CharSequence[]{c.getString(R.string.projects_form_each), c.getString(R.string.projects_form_total)},
                        "each".equals(unit) ? 0 : 1, i -> unit = i == 0 ? "each" : "total"));
            }
            JsonObject base = order != null ? order : draft;
            long t = target == null ? (payForced ? 0 : Json.num(base, "target", 0)) : target.value() == null ? 0 : target.value();
            long l = limit == null ? (payForced ? 0 : Json.num(base, "limit", 0)) : limit.value() == null ? 0 : limit.value();
            String payName = money() ? null : nameOf(pay.isEmpty() ? null : pay);
            String targetLabel = c.getString(R.string.projects_form_target);
            String limitLabel = c.getString("sell".equals(side) ? R.string.projects_form_at_least : R.string.projects_form_at_most);
            if (payName != null) {
                targetLabel = c.getString(R.string.projects_form_count_of, targetLabel, payName);
                limitLabel = c.getString(R.string.projects_form_count_of, limitLabel, payName);
            }
            target = new PlusAmount(c, targetLabel, t);
            limit = new PlusAmount(c, limitLabel, l);
            dynamic.addView(target.field.view);
            dynamic.addView(limit.field.view);
            if (money() && (doc == null || doc.server.moneyLabel == null)) dynamic.addView(CloudUi.note(c, c.getString(R.string.projects_form_money_bare)));

            dynamic.addView(CloudRowSheet.label(c, c.getString(R.string.projects_form_style)));
            dynamic.addView(CloudUi.segmented(c, new CharSequence[]{c.getString(R.string.projects_plus_style_patient),
                    c.getString(R.string.projects_plus_style_balanced), c.getString(R.string.projects_plus_style_quick)},
                    index(PlusConfig.STYLES, style, 1), i -> style = PlusConfig.STYLES[i]));

            dynamic.addView(CloudRowSheet.label(c, c.getString(R.string.projects_form_valid)));
            List<CharSequence> dayLabels = new ArrayList<>();
            if (editing) dayLabels.add(c.getString(R.string.projects_form_valid_keep));
            for (int d : DAYS) dayLabels.add(c.getResources().getQuantityString(R.plurals.projects_form_days, d, d));
            int dayAt = editing && days == 0 ? 0 : indexOf(DAYS, days) + (editing ? 1 : 0);
            if (dayAt < 0) dayAt = editing ? 0 : 1;
            dynamic.addView(CloudUi.segmented(c, dayLabels.toArray(new CharSequence[0]), dayAt, i -> days = editing ? (i == 0 ? 0 : DAYS[i - 1]) : DAYS[i]));

            // Who trades: only where the bot can trade at all (a verified server); elsewhere the player always does.
            if (doc != null && doc.server.botTrades()) {
                dynamic.addView(CloudRowSheet.label(c, c.getString(R.string.projects_form_who)));
                dynamic.addView(CloudUi.segmented(c, new CharSequence[]{c.getString(R.string.projects_form_who_bot), c.getString(R.string.projects_form_who_me)},
                        "owner".equals(trade) ? 1 : 0, i -> trade = i == 1 ? "owner" : "auto"));
            }
            conditions();
        }

        /** What the bot receives must be like: durability, enchantments that fit it, anvil names, curses. */
        void conditions() {
            durability = null;
            final String received = received();
            if (received == null) return;
            ItemData.ItemInfo info = infoOf(received);
            boolean custom = received.startsWith("custom:");
            boolean wears = info != null && info.maxDamage > 0;
            ItemData data = host.data();
            boolean enchantable = data != null && info != null && !custom && !data.enchantmentsFor(info).isEmpty();
            // Conditions mean something only for an item that wears or takes enchantments.
            if (!wears && !enchantable) return;
            dynamic.addView(CloudUi.section(c, c.getString(R.string.projects_form_conditions, nameOf(received))));
            if (wears) {
                Long dur = Json.numOrNull(req, "dur");
                CloudUi.Field field = CloudUi.field(c, c.getString(R.string.projects_form_durability), String.valueOf(dur == null ? 90 : dur), null,
                        InputType.TYPE_CLASS_NUMBER);
                field.input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(3)});
                dynamic.addView(field.unit("%").view);
                watch(field.input, typed -> {
                    int d = CloudRowSheet.number(typed, -1);
                    if (d >= 0 && d <= 100) req.addProperty("dur", d);
                    field.setError(null);
                });
                durability = field;
            }
            if (enchantable) {
                dynamic.addView(CloudRowSheet.label(c, c.getString(R.string.projects_form_enchantments)));
                final JsonObject ench = Json.obj(req, "ench");
                if (ench != null) {
                    for (final Map.Entry<String, JsonElement> e : new ArrayList<>(ench.entrySet())) dynamic.addView(enchRow(e.getKey(), PlusWords.level(e.getValue())));
                }
                if (ench == null || ench.size() < 8) {
                    dynamic.addView(CloudUi.textButton(c, c.getString(R.string.projects_form_add_enchantment), v -> addEnchantment(info)),
                            new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                }
            }
            LinearLayout switches = CloudUi.group(c);
            SwitchCompat named = CloudUi.toggle(c, c.getString(R.string.projects_form_named), Json.bool(req, "named", false), on -> req.addProperty("named", on));
            switches.addView(named);
            switches.addView(CloudUi.textDivider(c));
            SwitchCompat cursed = CloudUi.toggle(c, c.getString(R.string.projects_form_cursed), Json.bool(req, "cursed", false), on -> req.addProperty("cursed", on));
            switches.addView(cursed);
            LinearLayout.LayoutParams switchesLp = CloudUi.matchWrap();
            switchesLp.topMargin = CloudUi.dp(c, 10);
            dynamic.addView(switches, switchesLp);
        }

        View enchRow(final String id, final int level) {
            ItemData data = host.data();
            ItemData.EnchantmentInfo e = data == null ? null : data.enchantmentInfo(id);
            final int max = e == null ? 10 : Math.max(1, e.maxLevel);
            final String name = data == null ? id : data.enchantmentName(id, host.lang());
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(CloudUi.dp(c, 48));
            row.setPadding(CloudUi.dp(c, 8), 0, 0, 0);
            TextView label = CloudUi.text(c, name + (max > 1 ? " " + ItemData.levelText(level) : ""), 15, R.color.projects_bone_100, false);
            row.addView(label, CloudUi.weight(1f));
            if (max > 1) {
                Button down = CloudUi.button(c, "−", false, v -> setLevel(id, Math.max(1, level - 1)));
                down.setContentDescription(c.getString(R.string.projects_form_level_down, name));
                down.setEnabled(level > 1);
                row.addView(down, new LinearLayout.LayoutParams(CloudUi.dp(c, 48), CloudUi.dp(c, 44)));
                Button up = CloudUi.button(c, "+", false, v -> setLevel(id, Math.min(max, level + 1)));
                up.setContentDescription(c.getString(R.string.projects_form_level_up, name));
                up.setEnabled(level < max);
                LinearLayout.LayoutParams upLp = new LinearLayout.LayoutParams(CloudUi.dp(c, 48), CloudUi.dp(c, 44));
                upLp.setMarginStart(CloudUi.dp(c, 6));
                row.addView(up, upLp);
            }
            Button remove = CloudUi.textButton(c, "×", v -> {
                JsonObject ench = Json.obj(req, "ench");
                if (ench != null) ench.remove(id);
                redrawDynamic();
            });
            remove.setTextColor(CloudUi.color(c, R.color.projects_ash_300));
            remove.setContentDescription(c.getString(R.string.projects_form_remove_enchantment, name));
            row.addView(remove);
            return row;
        }

        void setLevel(String id, int level) {
            JsonObject ench = Json.obj(req, "ench");
            if (ench == null) return;
            ench.addProperty(id, level);
            redrawDynamic();
        }

        /** The enchantments that fit the received item and go with the chosen ones (no curse: "Lanetli olabilir" says that). */
        void addEnchantment(@Nullable final ItemData.ItemInfo info) {
            final ItemData data = host.data();
            if (data == null || info == null) return;
            final JsonObject ench = Json.obj(req, "ench");
            List<ItemData.EnchantmentInfo> pool = new ArrayList<>();
            for (ItemData.EnchantmentInfo e : data.searchEnchantments(null, host.lang(), info, -1)) {
                if (e.curse || (ench != null && ench.has(e.id))) continue;
                boolean fits = true;
                if (ench != null) for (Map.Entry<String, JsonElement> chosen : ench.entrySet()) if (!data.canCombine(e.id, chosen.getKey())) fits = false;
                if (fits) pool.add(e);
            }
            LinearLayout list = new LinearLayout(c);
            list.setOrientation(LinearLayout.VERTICAL);
            list.setPadding(CloudUi.dp(c, 16), 0, CloudUi.dp(c, 16), CloudUi.dp(c, 12));
            TextView title = CloudUi.text(c, c.getString(R.string.projects_form_add_enchantment), 17, R.color.projects_bone_100, true);
            title.setTypeface(CloudUi.font(c, R.font.tektur_semibold));
            title.setPadding(CloudUi.dp(c, 8), 0, CloudUi.dp(c, 8), CloudUi.dp(c, 6));
            list.addView(title);
            ScrollView scroll = new ScrollView(c);
            LinearLayout rows = new LinearLayout(c);
            rows.setOrientation(LinearLayout.VERTICAL);
            scroll.addView(rows);
            list.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            final BottomPanel picker = host.sheet(BottomPanel.of(c, list, 0.8f));
            for (final ItemData.EnchantmentInfo e : pool) {
                TextView row = CloudUi.text(c, data.enchantmentName(e.id, host.lang()), 15.5f, R.color.projects_bone_100, false);
                row.setMinHeight(CloudUi.dp(c, 48));
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(CloudUi.dp(c, 8), 0, CloudUi.dp(c, 8), 0);
                row.setBackgroundResource(R.drawable.projects_row_ripple);
                row.setClickable(true);
                row.setFocusable(true);
                row.setOnClickListener(v -> {
                    Haptics.tick(v);
                    if (ench != null) ench.addProperty(e.id, 1);
                    picker.dismiss();
                    redrawDynamic();
                });
                Press.attach(row, Press.Style.ROW);
                rows.addView(row, CloudUi.matchWrap());
            }
            picker.show();
        }

        /** A field-like row with the item's tile and name (quiet when nothing is picked yet). */
        View itemRow(@Nullable String ref, View.OnClickListener click, boolean fixed) {
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.projects_input);
            row.setMinimumHeight(CloudUi.dp(c, 56));
            row.setPadding(CloudUi.dp(c, 10), CloudUi.dp(c, 6), CloudUi.dp(c, 12), CloudUi.dp(c, 6));
            row.addView(CloudItemArt.tile(c, ref == null ? "" : ref, baseOf(ref), 36), new LinearLayout.LayoutParams(CloudUi.dp(c, 36), CloudUi.dp(c, 36)));
            String name = nameOf(ref);
            TextView text = CloudUi.text(c, name, 16, ref == null ? R.color.projects_ash_400 : R.color.projects_bone_100, ref != null);
            LinearLayout.LayoutParams lp = CloudUi.weight(1f);
            lp.setMarginStart(CloudUi.dp(c, 12));
            row.addView(text, lp);
            if (!fixed) {
                row.setClickable(true);
                row.setFocusable(true);
                row.setOnClickListener(click);
                Press.attach(row, Press.Style.ROW);
            } else {
                row.setAlpha(0.8f);
            }
            row.setContentDescription(c.getString(R.string.projects_form_item) + ", " + name);
            LinearLayout.LayoutParams rowLp = CloudUi.matchWrap();
            rowLp.leftMargin = CloudUi.dp(c, 8);
            rowLp.rightMargin = CloudUi.dp(c, 8);
            row.setLayoutParams(rowLp);
            return row;
        }

        /** The item search: the game's items (both languages, aliases), the account's custom items, a new one by name. */
        void searchItem(final boolean forPay) {
            final ItemData data = host.data();
            final String lang = host.lang();
            LinearLayout box = new LinearLayout(c);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(CloudUi.dp(c, 16), 0, CloudUi.dp(c, 16), CloudUi.dp(c, 12));
            TextView title = CloudUi.text(c, c.getString(forPay ? R.string.projects_form_pay_with : R.string.projects_form_item), 17,
                    R.color.projects_bone_100, true);
            title.setTypeface(CloudUi.font(c, R.font.tektur_semibold));
            title.setPadding(CloudUi.dp(c, 8), 0, CloudUi.dp(c, 8), CloudUi.dp(c, 4));
            box.addView(title);
            final EditText search = new EditText(c);
            search.setBackgroundResource(R.drawable.projects_input);
            search.setHint(c.getString(R.string.projects_form_search));
            search.setHintTextColor(CloudUi.color(c, R.color.projects_ash_400));
            search.setTextColor(CloudUi.color(c, R.color.projects_bone_100));
            search.setTypeface(CloudUi.font(c, R.font.figtree_regular));
            search.setSingleLine(true);
            search.setMinHeight(CloudUi.dp(c, 48));
            search.setPadding(CloudUi.dp(c, 14), 0, CloudUi.dp(c, 14), 0);
            search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            search.setFilters(new InputFilter[]{new InputFilter.LengthFilter(40)});
            LinearLayout.LayoutParams searchLp = CloudUi.matchWrap();
            searchLp.topMargin = CloudUi.dp(c, 6);
            searchLp.bottomMargin = CloudUi.dp(c, 6);
            box.addView(search, searchLp);
            ScrollView scroll = new ScrollView(c);
            final LinearLayout list = new LinearLayout(c);
            list.setOrientation(LinearLayout.VERTICAL);
            scroll.addView(list);
            box.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, CloudUi.dp(c, 380)));
            final BottomPanel picker = host.sheet(BottomPanel.of(c, box, 0.88f));
            final List<ItemData.Extra> extras = new ArrayList<>();
            for (JsonObject custom : cfg.customItems()) {
                String k = Json.str(custom, "k");
                String name = Json.str(custom, "name", "");
                if (k != null && !name.isEmpty()) extras.add(new ItemData.Extra("custom:" + k, java.util.Collections.singletonList(name)));
            }
            final boolean customOn = doc == null || doc.rules.customItems;
            final Runnable fill = () -> {
                list.removeAllViews();
                String q = search.getText().toString().trim();
                if (data == null) {
                    list.addView(CloudUi.note(c, c.getString(R.string.projects_form_data_failed)));
                    list.addView(CloudUi.textButton(c, c.getString(R.string.projects_form_retry), v -> {
                        picker.dismiss();
                        host.retryData();
                    }), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                }
                List<ItemData.Hit> hits = ItemData.searchItems(data, q, lang, extras, 60, false);
                for (final ItemData.Hit hit : hits) {
                    String name = hit.item != null ? (("en".equals(lang) ? hit.item.names.en : hit.item.names.tr)) : nameOf(hit.ref);
                    String hint = hit.item != null && data != null ? data.nameHint(hit.item, lang) : null;
                    list.addView(hitRow(hit.ref, name, hint, v -> {
                        Haptics.tick(v);
                        choose(hit.ref, forPay);
                        picker.dismiss();
                    }));
                }
                if (!q.isEmpty() && customOn) {
                    final String typed = OrderDraft.cutName(q);
                    if (!typed.isEmpty()) {
                        list.addView(hitRow(null, c.getString(R.string.projects_form_custom_typed, typed), c.getString(R.string.projects_form_custom_first_sight), v -> {
                            Haptics.tick(v);
                            chooseTyped(typed, forPay);
                            picker.dismiss();
                        }));
                    }
                }
                if (hits.isEmpty() && !q.isEmpty() && data != null) list.addView(CloudUi.note(c, c.getString(R.string.projects_form_no_match)), 0);
            };
            watch(search, s -> fill.run());
            fill.run();
            picker.show();
            search.requestFocus();
        }

        View hitRow(@Nullable String ref, String name, @Nullable String hint, View.OnClickListener click) {
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(CloudUi.dp(c, 52));
            row.setPadding(CloudUi.dp(c, 6), CloudUi.dp(c, 4), CloudUi.dp(c, 8), CloudUi.dp(c, 4));
            row.setBackgroundResource(R.drawable.projects_row_ripple);
            row.setClickable(true);
            row.setFocusable(true);
            row.addView(CloudItemArt.tile(c, ref == null ? "custom:new" : ref, baseOf(ref), 32), new LinearLayout.LayoutParams(CloudUi.dp(c, 32), CloudUi.dp(c, 32)));
            LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.addView(CloudUi.text(c, name, 15, R.color.projects_bone_100, false));
            if (hint != null) texts.addView(CloudUi.text(c, hint, 12.5f, R.color.projects_ash_400, false));
            LinearLayout.LayoutParams lp = CloudUi.weight(1f);
            lp.setMarginStart(CloudUi.dp(c, 12));
            row.addView(texts, lp);
            row.setOnClickListener(click);
            Press.attach(row, Press.Style.ROW);
            row.setLayoutParams(CloudUi.matchWrap());
            return row;
        }

        void choose(String ref, boolean forPay) {
            if (forPay) pay = ref;
            else item = ref;
            build();
        }

        /** A custom item by the player's name for it: the assistant asks the first time it sees one. */
        void chooseTyped(String name, boolean forPay) {
            java.util.Set<String> taken = new java.util.HashSet<>(newCustom.keySet());
            String k = cfg.newCustomKey(new java.util.Random(), taken);
            JsonObject custom = new JsonObject();
            custom.addProperty("k", k);
            custom.addProperty("name", name);
            custom.add("base", JsonNull.INSTANCE);
            custom.add("fp", JsonNull.INSTANCE);
            custom.add("look", JsonNull.INSTANCE);
            custom.addProperty("from", "text");
            newCustom.put(k, custom);
            if (forPay) pay = "custom:" + k;
            else item = "custom:" + k;
            build();
        }

        void actions() {
            LinearLayout bar = new LinearLayout(c);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setPadding(CloudUi.dp(c, 12), CloudUi.dp(c, 8), CloudUi.dp(c, 16), CloudUi.dp(c, 16));
            if (editing) {
                Button remove = CloudUi.textButton(c, c.getString(R.string.projects_order_remove), v -> {
                    panel.dismiss();
                    host.removeOrder(v, order);
                });
                remove.setTextColor(CloudUi.color(c, R.color.projects_flare_400));
                bar.addView(remove);
            }
            bar.addView(new View(c), CloudUi.weight(1f));
            Button cancel = CloudUi.textButton(c, c.getString(R.string.projects_card_discard), v -> panel.dismiss());
            cancel.setTextColor(CloudUi.color(c, R.color.projects_ash_300));
            bar.addView(cancel);
            Button save = CloudUi.button(c, c.getString(saving ? R.string.projects_cloud_settings_saving : R.string.projects_card_save), true, this::save);
            save.setMinWidth(CloudUi.dp(c, 120));
            save.setEnabled(!saving);
            LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            saveLp.setMarginStart(CloudUi.dp(c, 8));
            bar.addView(save, saveLp);
            saveButton = save;
            if (root.getChildCount() > 2) root.removeViewAt(2);
            root.addView(bar, CloudUi.matchWrap());
        }

        /** While a write is out the button says so and takes no second tap; the form closes only once it is saved. */
        void setSaving(boolean on) {
            saving = on;
            Button save = saveButton;
            if (save == null) return;
            save.setEnabled(!on);
            save.setText(on ? R.string.projects_cloud_settings_saving : R.string.projects_card_save);
        }

        void sent(View from, boolean started) {
            if (started) return;
            // Another write (a row's, the card's) is still out: nothing went, the form stays as typed.
            setSaving(false);
            Haptics.reject(from);
            SocialUi.toast(c, c.getString(R.string.projects_order_busy));
        }

        void savedResult(boolean ok) {
            setSaving(false);
            if (ok) panel.dismiss();
        }

        /** Checks the form as the service would and sends it. */
        void save(View from) {
            if (saving) return;
            boolean ok = true;
            itemError.setVisibility(View.GONE);
            payError.setVisibility(View.GONE);
            if (item == null || item.isEmpty()) {
                CloudRowSheet.show(itemError, c.getString(R.string.projects_form_error_item));
                ok = false;
            }
            if (customName != null) {
                String name = customName.value().trim();
                int length = name.codePointCount(0, name.length());
                String problem = PlusText.checkPersona("intro", name);
                if (length < 1 || length > 32) {
                    customName.setError(c.getString(R.string.projects_form_error_name));
                    ok = false;
                } else if (problem != null) {
                    customName.setError(c.getString("form".equals(problem) ? R.string.projects_form_error_name_form : R.string.projects_form_error_name_link));
                    ok = false;
                }
            }
            if (!money() && pay.isEmpty()) {
                CloudRowSheet.show(payError, c.getString(R.string.projects_form_error_item));
                ok = false;
            } else if (!money() && pay.equals(item)) {
                CloudRowSheet.show(payError, c.getString(R.string.projects_form_error_same));
                ok = false;
            }
            int n = CloudRowSheet.number(qty.value(), 0);
            if (n < 1 || n > PlusConfig.QTY_MAX) {
                qty.setError(c.getString(R.string.projects_form_error_value));
                ok = false;
            }
            long max = money() ? maxDeal() : PlusConfig.QTY_MAX;
            Long t = target.value();
            Long l = limit.value();
            if (t == null) {
                target.field.setError(c.getString(target.unreadable() ? R.string.projects_form_error_amount : R.string.projects_form_error_price));
                ok = false;
            } else if (t < 1 || t > max) {
                target.field.setError(c.getString(R.string.projects_form_error_value));
                ok = false;
            }
            if (l == null) {
                limit.field.setError(c.getString(limit.unreadable() ? R.string.projects_form_error_amount : R.string.projects_form_error_price));
                ok = false;
            } else if (l < 1 || l > max) {
                limit.field.setError(c.getString(R.string.projects_form_error_value));
                ok = false;
            } else if (t != null && ("sell".equals(side) ? l > t : l < t)) {
                limit.field.setError(c.getString("sell".equals(side) ? R.string.projects_form_error_at_least : R.string.projects_form_error_at_most));
                ok = false;
            }
            Integer dur = null;
            if (durability != null) {
                int d = CloudRowSheet.number(durability.value(), -1);
                if (d < 0 || d > 100) {
                    durability.setError(c.getString(R.string.projects_form_error_value));
                    ok = false;
                } else {
                    dur = d;
                }
            }
            if (!editing && doc != null && activeCount() >= doc.rules.orderMax) {
                CloudRowSheet.show(itemError, c.getString(R.string.projects_form_error_active));
                ok = false;
            }
            if (!ok) {
                Haptics.reject(from);
                return;
            }
            // A buy's limit far above its target is confirmed once (the contract's "far").
            if ("buy".equals(side) && l > 10 * t && !confirmedFar) {
                new AlertDialog.Builder(c)
                        .setMessage(c.getString(R.string.projects_plus_row_ten_times))
                        .setPositiveButton(R.string.projects_plus_row_ten_times_yes, (d2, w) -> {
                            confirmedFar = true;
                            save(from);
                        })
                        .setNegativeButton(R.string.projects_cloud_discard_keep, null)
                        .show();
                return;
            }
            JsonObject built = orderJson(n, t, l, dur);
            if (editing) {
                JsonObject fields = changed(built);
                if (fields.size() == 0) {
                    panel.dismiss();
                    return;
                }
                setSaving(true);
                sent(from, host.saveChange(from, Json.str(order, "id", ""), fields, this::savedResult));
                return;
            }
            JsonArray custom = new JsonArray();
            for (JsonObject fresh : newCustom.values()) {
                String ref = "custom:" + Json.str(fresh, "k", "");
                if (ref.equals(item) || ref.equals(pay)) {
                    JsonObject out = fresh.deepCopy();
                    out.remove("at");
                    custom.add(out);
                }
            }
            setSaving(true);
            sent(from, host.saveNew(from, built, custom, draft != null, this::savedResult));
        }

        int activeCount() {
            int count = 0;
            long now = host.nowSeconds();
            for (JsonObject o : cfg.orders()) {
                if (Json.bool(o, "on", true) && Json.num(o, "until", 0) > now) {
                    PlusModel.OrderView view = doc == null ? null : doc.orders.get(Json.str(o, "id", ""));
                    if (view == null || view.got < Json.num(o, "qty", 1)) count++;
                }
            }
            return count;
        }

        JsonObject orderJson(int n, long t, long l, @Nullable Integer dur) {
            JsonObject o = new JsonObject();
            o.addProperty("on", order == null || Json.bool(order, "on", true));
            o.addProperty("side", side);
            o.addProperty("item", item);
            o.addProperty("qty", n);
            String received = received();
            if (received == null) {
                o.add("req", JsonNull.INSTANCE);
            } else {
                JsonObject r = new JsonObject();
                JsonObject ench = Json.obj(req, "ench");
                // Conditions only for what the bot receives; enchantments that do not fit it are left out.
                JsonObject fitting = OrderDraft.fittingEnch(ench == null ? new JsonObject() : ench, received, host.data());
                r.add("ench", fitting);
                ItemData.ItemInfo info = infoOf(received);
                boolean wears = info != null && info.maxDamage > 0;
                r.addProperty("dur", dur != null ? dur : wears ? 90 : Json.num(req, "dur", 0));
                if (info != null && !wears) r.addProperty("dur", 0);
                r.addProperty("named", Json.bool(req, "named", false));
                r.addProperty("cursed", Json.bool(req, "cursed", false));
                o.add("req", r);
            }
            o.addProperty("pay", money() ? "money" : pay);
            o.addProperty("unit", n > 1 ? unit : "total");
            o.addProperty("target", t);
            o.addProperty("limit", l);
            o.addProperty("style", style);
            o.addProperty("trade", doc != null && doc.server.botTrades() ? trade : Json.str(order, "trade", "auto"));
            long until = days > 0 ? host.nowSeconds() + days * 86_400L : Json.num(order, "until", host.nowSeconds() + 14 * 86_400L);
            o.addProperty("until", until);
            o.addProperty("note", note);
            o.addProperty("src", src);
            return o;
        }

        /** The fields of an existing order that the form changed (what {@code PATCH} takes). */
        JsonObject changed(JsonObject built) {
            JsonObject out = new JsonObject();
            for (String key : new String[]{"qty", "req", "pay", "unit", "target", "limit", "style", "trade"}) {
                JsonElement before = order == null ? null : order.get(key);
                JsonElement after = built.get(key);
                if (after != null && (before == null || !before.equals(after))) out.add(key, after.deepCopy());
            }
            if (days > 0) out.add("until", built.get("until"));
            return out;
        }

        private static int index(String[] values, String value, int fallback) {
            for (int i = 0; i < values.length; i++) if (values[i].equals(value)) return i;
            return fallback;
        }

        private static int indexOf(int[] values, int value) {
            for (int i = 0; i < values.length; i++) if (values[i] == value) return i;
            return -1;
        }
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
}
