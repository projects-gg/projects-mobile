package gg.projects.mobile.cloud.ui;

import android.app.Activity;
import android.app.TimePickerDialog;
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
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.gson.JsonObject;

import net.kdt.pojavlaunch.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import gg.projects.mobile.cloud.CloudEdit;
import gg.projects.mobile.cloud.CloudModel;
import gg.projects.mobile.cloud.CloudProblem;
import gg.projects.mobile.cloud.CloudService;
import gg.projects.mobile.cloud.CloudWords;
import gg.projects.mobile.cloud.PlusConfig;
import gg.projects.mobile.cloud.PlusModel;
import gg.projects.mobile.cloud.plus.PlusText;
import gg.projects.mobile.social.ChatText;
import gg.projects.mobile.social.Json;
import gg.projects.mobile.social.ui.SocialUi;
import gg.projects.mobile.ui.Haptics;

/**
 * The AFK+ settings as questions (SETUP-UX, 28 Oct 2026; the site's and the launcher's questions, names and order): each
 * question's editor, its one-line answer and what its folded "Gelişmiş" holds. The question screens and the first setup
 * draw the same editors. The fields keep their own words; the sentences are the site's (afk-web tr/en-setup.ts).
 */
final class PlusEditors {
    private PlusEditors() {}

    static final String ORDERS = "orders";
    static final String MONEY = "money";
    static final String ACT = "act";
    static final String CHAT = "chat";
    static final String TALK = "talk";
    static final String WHO = "who";
    static final String TIME = "time";
    static final String OTHER = "other";

    /** The summary's questions, in the site's order ("Ne alıp satsın?" is the setup's only). */
    static final String[] QUESTIONS = {MONEY, ACT, CHAT, TALK, WHO, TIME, OTHER};

    /** What an editor needs from the screen it is drawn on. */
    interface Host {
        Context ctx();

        Activity activity();

        PlusConfig edit();

        @Nullable
        PlusConfig original();

        @Nullable
        CloudModel.PlusDoc doc();

        CloudService service();

        @Nullable
        String accountId();

        /** The words for a problem at {@code field}, or null. */
        @Nullable
        String problemAt(String field);

        /** After an edit. */
        void changed();

        /** After an edit that changes what is shown: the editor is drawn again. */
        void rebuild();

        void toast(CharSequence text);

        void problem(CloudProblem problem, @Nullable View from);

        /** "Sipariş ekle": the orders screen. */
        void openOrders();

        boolean alive();

        /** The setup's "Şimdilik alım yapmasın" (the setup's own choice, not a setting); false elsewhere. */
        boolean notNow();

        void setNotNow(boolean on);
    }

    /** The field views an editor drew, by the document's path, for "Sınırlara git" (the screen scrolls to it). */
    static final class Drawn {
        final Map<String, View> fields = new HashMap<>();
        /** The "Gelişmiş" boxes by question: open while one of their fields is asked for. */
        final Map<String, View> advanced = new HashMap<>();
    }

    static int title(String question) {
        switch (question) {
            case ORDERS: return R.string.projects_setup_q_orders;
            case MONEY: return R.string.projects_setup_q_money;
            case ACT: return R.string.projects_setup_q_act;
            case CHAT: return R.string.projects_setup_q_chat;
            case TALK: return R.string.projects_setup_q_talk;
            case WHO: return R.string.projects_setup_q_who;
            case TIME: return R.string.projects_setup_q_time;
            default: return R.string.projects_setup_other;
        }
    }

    @Nullable
    static String lede(Context c, String question) {
        switch (question) {
            case ORDERS: return c.getString(R.string.projects_setup_lede_orders);
            case MONEY: return c.getString(R.string.projects_setup_lede_money);
            case CHAT: return c.getString(R.string.projects_setup_lede_chat);
            case TALK: return c.getString(R.string.projects_setup_lede_talk);
            default: return null;
        }
    }

    static int icon(String question) {
        switch (question) {
            case ORDERS: return R.drawable.projects_ic_arrows_left_right;
            case MONEY: return R.drawable.projects_ic_wallet;
            case ACT: return R.drawable.projects_ic_check_circle;
            case CHAT: return R.drawable.projects_ic_chats;
            case TALK: return R.drawable.projects_ic_user_circle;
            case WHO: return R.drawable.projects_ic_users;
            case TIME: return R.drawable.projects_ic_moon;
            default: return R.drawable.projects_ic_sliders;
        }
    }

    /** Which question holds a field ("Sınırlara git"). */
    static String questionOf(String field) {
        switch (field) {
            case "authority":
            case "limits.approvalAbove":
            case "limits.newCounterpartApprovalAbove":
            case "limits.approvalTimeoutMin":
            case "pay":
                return ACT;
            default:
                return MONEY;
        }
    }

    /** Whether a field sits under its question's "Gelişmiş". */
    static boolean advancedField(String field) {
        return "limits.dailySpendMax".equals(field) || "limits.balanceFloor".equals(field) || "trade.currency".equals(field)
                || "limits.approvalTimeoutMin".equals(field) || "pay".equals(field);
    }

    // ---- the answers ----

    /** A question's answer in plain words, under it in the summary. */
    static String answer(Context c, String question, PlusConfig e, @Nullable CloudModel.PlusDoc doc, CloudService service) {
        switch (question) {
            case MONEY: {
                if (!e.allows("buy")) return c.getString(R.string.projects_setup_sell_only);
                if (!e.canSpend()) return c.getString(R.string.projects_setup_a_no_buying);
                return c.getString(R.string.projects_setup_a_money, price(c, doc, e.limit("perDealMax")), price(c, doc, e.limit("totalBudget")));
            }
            case ACT: {
                String authority = e.authority();
                StringBuilder out = new StringBuilder(c.getString("reply".equals(authority) ? R.string.projects_setup_a_reply
                        : "deal".equals(authority) ? R.string.projects_setup_a_deal : R.string.projects_setup_a_ask));
                Long above = e.limit("approvalAbove");
                if ("deal".equals(authority) && above != null) {
                    out.append(' ').append(c.getString(R.string.projects_setup_a_deal_asks, price(c, doc, above)));
                }
                boolean buy = e.allows("buy");
                boolean sell = e.allows("sell");
                if (!buy || !sell) {
                    out.append(' ').append(c.getString(buy ? R.string.projects_setup_a_buy_only
                            : sell ? R.string.projects_setup_a_sell_only : R.string.projects_setup_a_neither));
                }
                return out.toString();
            }
            case CHAT: {
                PlusModel.AdPreview preview = doc == null ? null : doc.adPreview;
                String ads;
                if (!e.ads()) ads = c.getString(R.string.projects_setup_a_no_ads);
                else if (preview == null) ads = c.getString(R.string.projects_setup_a_ads_plain);
                else if (preview.stoppedToday) ads = c.getString(R.string.projects_setup_a_ads_stopped);
                else ads = capitalize(c, c.getString(R.string.projects_setup_a_ads, everyWords(c, preview.everyMin)));
                return ads + c.getString(e.proactivePm() ? R.string.projects_setup_a_writes
                        : e.scanChat() ? R.string.projects_setup_a_scans : R.string.projects_setup_a_no_scan);
            }
            case TALK: {
                String preset = presetName(c, e.preset());
                return e.playerTalk() ? c.getString(R.string.projects_setup_a_tone_player, preset) : preset;
            }
            case WHO: {
                boolean friends = "friends".equals(e.whoMode());
                String scope = e.scope();
                int words = "all".equals(scope) ? (friends ? R.string.projects_setup_a_who_all_friends : R.string.projects_setup_a_who_all)
                        : "off".equals(scope) ? R.string.projects_setup_a_who_off
                        : (friends ? R.string.projects_setup_a_who_trade_friends : R.string.projects_setup_a_who_trade);
                StringBuilder out = new StringBuilder(c.getString(words));
                out.append(c.getString(e.away() ? R.string.projects_setup_a_away : R.string.projects_setup_a_no_away));
                int blocked = e.blocked().size();
                if (blocked > 0) out.append(c.getResources().getQuantityString(R.plurals.projects_setup_a_blocked, blocked, blocked));
                return out.toString();
            }
            case TIME: {
                Long pause = e.pauseUntil();
                long now = service.nowSeconds();
                if (pause != null && pause > now) return c.getString(R.string.projects_plus_ai_break, CloudWords.until(c, pause, now));
                return c.getString(e.scheduleOn() ? R.string.projects_setup_a_hours : R.string.projects_setup_a_always);
            }
            default:
                return c.getString(service.plusFeature("saver") ? R.string.projects_setup_a_more : R.string.projects_setup_a_more_no_saver);
        }
    }

    /** An amount with the server's money name ("40.000 ripple"), as the cards write it. */
    static String price(Context c, @Nullable CloudModel.PlusDoc doc, @Nullable Long value) {
        PlusModel.Server server = doc == null ? null : doc.server;
        return PlusWords.money(c, value == null ? 0 : value, server);
    }

    /** "saatte", "2 saatte", "45 dakikada" / "every hour", "every 2 hours", "every 45 minutes". */
    static String everyWords(Context c, int minutes) {
        if (minutes % 60 == 0) {
            return minutes == 60 ? c.getString(R.string.projects_setup_every_hour) : c.getString(R.string.projects_setup_every_hours, minutes / 60);
        }
        return c.getString(R.string.projects_setup_every_minutes, minutes);
    }

    private static String capitalize(Context c, String text) {
        if (text.isEmpty()) return text;
        return text.substring(0, 1).toUpperCase(CloudWords.locale(c)) + text.substring(1);
    }

    static String presetName(Context c, String preset) {
        switch (preset) {
            case "brief": return c.getString(R.string.projects_plus_preset_brief);
            case "polite": return c.getString(R.string.projects_plus_preset_polite);
            case "playful": return c.getString(R.string.projects_plus_preset_playful);
            default: return c.getString(R.string.projects_plus_preset_friendly);
        }
    }

    /** What a question's "Gelişmiş" holds, said while it is folded; null: it has none. */
    @Nullable
    static String advancedWords(Context c, String question, @Nullable CloudModel.PlusDoc doc) {
        switch (question) {
            case MONEY: return c.getString(currencyShown(doc) ? R.string.projects_setup_adv_money_currency : R.string.projects_setup_adv_money);
            case ACT: return c.getString(doc != null && doc.rules.itemPay ? R.string.projects_setup_adv_act : R.string.projects_setup_adv_act_no_pay);
            case CHAT: return c.getString(R.string.projects_setup_adv_chat);
            case TALK: return c.getString(R.string.projects_setup_adv_talk);
            default: return null;
        }
    }

    /** "Para birimi" shows where Studio lets players name the money, or where the profile names it (read-only). */
    static boolean currencyShown(@Nullable CloudModel.PlusDoc doc) {
        return doc != null && (doc.rules.currency || doc.server.moneyFixed);
    }

    // ---- the editors ----

    /** Draws {@code question}'s editor into {@code content}; {@code openAdvanced} unfolds its "Gelişmiş". */
    static void draw(String question, LinearLayout content, Host h, Drawn drawn, boolean openAdvanced, boolean setup) {
        switch (question) {
            case MONEY: money(content, h, drawn, openAdvanced, setup); break;
            case ACT: act(content, h, drawn, openAdvanced); break;
            case CHAT: chat(content, h, drawn, openAdvanced); break;
            case TALK: talk(content, h, drawn, openAdvanced); break;
            case WHO: who(content, h); break;
            case OTHER: other(content, h); break;
            default: break;
        }
    }

    /** A folded "Gelişmiş": a quiet row with what it holds; the box opens in place. */
    private static LinearLayout advanced(LinearLayout content, Host h, Drawn drawn, String question, boolean open) {
        Context c = h.ctx();
        final LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(CloudUi.dp(c, 8), 0, CloudUi.dp(c, 8), CloudUi.dp(c, 12));
        box.setVisibility(open ? View.VISIBLE : View.GONE);
        final String words = advancedWords(c, question, h.doc());
        final CloudUi.Row row = CloudUi.row(c, R.drawable.projects_ic_sliders, c.getString(open ? R.string.projects_setup_hide
                : R.string.projects_setup_advanced), null);
        row.setLine(open ? null : words, R.color.projects_ash_400);
        final android.widget.ImageView caret = new android.widget.ImageView(c);
        caret.setImageResource(R.drawable.projects_ic_caret_down_bold);
        caret.setImageTintList(android.content.res.ColorStateList.valueOf(CloudUi.color(c, R.color.projects_ash_400)));
        caret.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        caret.setRotation(open ? 180f : 0f);
        row.view.addView(caret, new LinearLayout.LayoutParams(CloudUi.dp(c, 18), CloudUi.dp(c, 18)));
        row.view.setBackgroundResource(R.drawable.projects_row_ripple);
        row.view.setClickable(true);
        row.view.setFocusable(true);
        row.view.setOnClickListener(v -> {
            boolean show = box.getVisibility() != View.VISIBLE;
            box.setVisibility(show ? View.VISIBLE : View.GONE);
            row.title.setText(show ? R.string.projects_setup_hide : R.string.projects_setup_advanced);
            row.setLine(show ? null : words, R.color.projects_ash_400);
            // The caret turns on a transform; with motion off it simply is the other way round.
            if (gg.projects.mobile.mods.Motion.enabled(c)) caret.animate().rotation(show ? 180f : 0f).setDuration(180).start();
            else caret.setRotation(show ? 180f : 0f);
            Haptics.tick(v);
        });
        gg.projects.mobile.ui.Press.attach(row.view, gg.projects.mobile.ui.Press.Style.ROW);
        LinearLayout group = CloudUi.group(c);
        group.addView(row.view);
        // The folded fields open inside the card (they opened below it, outside).
        group.addView(box, CloudUi.matchWrap());
        LinearLayout.LayoutParams lp = CloudUi.matchWrap();
        lp.topMargin = CloudUi.dp(c, 16);
        content.addView(group, lp);
        drawn.advanced.put(question, box);
        return box;
    }

    private static TextWatcher watcher(final Runnable changed) {
        return new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                changed.run();
            }
        };
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

    private static int index(String[] values, @Nullable String value, int fallback) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(value)) return i;
        return fallback;
    }

    private static TextView errorLine(Context c, String words) {
        TextView error = CloudRowSheet.error(c);
        CloudRowSheet.show(error, words);
        return error;
    }

    /** A choice's words with its one line under it (the outcome of these choices is not obvious). */
    private static CharSequence choiceText(Context c, int title, int help) {
        android.text.SpannableStringBuilder out = new android.text.SpannableStringBuilder(c.getString(title));
        int start = out.length();
        out.append('\n').append(c.getString(help));
        out.setSpan(new android.text.style.AbsoluteSizeSpan(13, true), start, out.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new android.text.style.ForegroundColorSpan(CloudUi.color(c, R.color.projects_ash_400)), start, out.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return out;
    }

    /** An amount field for a limit: its label, the words under it, its problem; null clears it. */
    private static PlusAmount limitField(final Host h, Drawn drawn, final String key, @Nullable String help, @Nullable String hint) {
        return limitField(h, drawn, key, help, hint, null);
    }

    private static PlusAmount limitField(final Host h, Drawn drawn, final String key, @Nullable final String help,
                                         @Nullable String hint, @Nullable final Runnable after) {
        Context c = h.ctx();
        final PlusConfig e = h.edit();
        Long value = e.limit(key);
        final PlusAmount amount = new PlusAmount(c, CloudWords.limitLabel(c, key), value == null ? 0 : value);
        if (hint != null) amount.field.input.setHint(hint);
        String unit = moneyName(h);
        if (unit != null) amount.field.unit(unit);
        String problem = h.problemAt("limits." + key);
        if (problem != null) amount.field.setError(problem);
        else if (help != null && value == null) amount.field.setHelp(help);
        amount.onChange(() -> {
            Long typed = amount.value();
            e.setLimit(key, typed);
            // Emptied again: the field's own line ("Boş bırakırsan ...") instead of the amount in words.
            if (typed == null && help != null && !amount.unreadable()) amount.field.setHelp(help);
            h.changed();
            if (after != null) after.run();
        });
        drawn.fields.put("limits." + key, amount.field.view);
        return amount;
    }

    /** "Ne kadar harcayabilsin?": the two limits buying needs, the spent meter, the secret line; the rest folded. */
    private static void money(LinearLayout content, final Host h, Drawn drawn, boolean openAdvanced, boolean setup) {
        final Context c = h.ctx();
        final PlusConfig e = h.edit();
        CloudModel.PlusDoc doc = h.doc();
        if (setup) {
            // The setup's one choice (the site's rule, SETUP-UX 29 Oct, worded plainly after TASTE-SWEEP r2): "Alım yapsın"
            // with two amounts, or off. Off, both limits are written as null and the typed amounts and the total's line
            // stay, dimmed, for a change of mind; typing an amount turns it on.
            final boolean[] quiet = {false};
            final PlusAmount[] amounts = new PlusAmount[2];
            final String[] keys = {"perDealMax", "totalBudget"};
            final TextView[] total = new TextView[1];
            LinearLayout group = CloudUi.group(c);
            final androidx.appcompat.widget.SwitchCompat buy = CloudUi.toggle(c, c.getString(R.string.projects_setup_buy),
                    !h.notNow(), on -> {
                        if (quiet[0]) return;
                        h.setNotNow(!on);
                        for (int i = 0; i < amounts.length; i++) {
                            if (amounts[i] == null) continue;
                            e.setLimit(keys[i], on ? amounts[i].value() : null);
                            amounts[i].field.view.setAlpha(on ? 1f : 0.45f);
                        }
                        if (total[0] != null) total[0].setAlpha(on ? 1f : 0.45f);
                        h.changed();
                    });
            group.addView(buy);
            TextView help = CloudUi.text(c, c.getString(R.string.projects_setup_buy_help), 13, R.color.projects_ash_400, false);
            help.setPadding(CloudUi.dp(c, 16), 0, CloudUi.dp(c, 16), CloudUi.dp(c, 12));
            group.addView(help);
            content.addView(group);
            Runnable typed = () -> {
                if (quiet[0] || buy.isChecked()) return;
                boolean any = false;
                for (PlusAmount a : amounts) if (a != null && a.value() != null) any = true;
                if (!any) return;
                quiet[0] = true;
                buy.setChecked(true);
                quiet[0] = false;
                h.setNotNow(false);
                for (int i = 0; i < amounts.length; i++) {
                    if (amounts[i] == null) continue;
                    e.setLimit(keys[i], amounts[i].value());
                    amounts[i].field.view.setAlpha(1f);
                }
                if (total[0] != null) total[0].setAlpha(1f);
            };
            // No "Boş bırakırsan …" under each: the step asks for both amounts or the switch off, said once by its rule.
            amounts[0] = limitField(h, drawn, keys[0], null, null, typed);
            amounts[1] = limitField(h, drawn, keys[1], null, null, typed);
            content.addView(amounts[0].field.view);
            content.addView(amounts[1].field.view);
            total[0] = CloudUi.note(c, c.getString(R.string.projects_setup_total_help));
            content.addView(total[0]);
            if (!buy.isChecked()) {
                for (PlusAmount a : amounts) a.field.view.setAlpha(0.45f);
                total[0].setAlpha(0.45f);
            }
        } else {
            content.addView(limitField(h, drawn, "perDealMax", c.getString(R.string.projects_limit_no_buy), null).field.view);
            content.addView(limitField(h, drawn, "totalBudget", c.getString(R.string.projects_limit_no_buy), null).field.view);
        }
        // What the total is (the site's line under it); on the Ayarlar page without "Ayarlar'dan sıfırlarsın".
        if (!setup) content.addView(CloudUi.note(c, c.getString(R.string.projects_setup_total_help_settings)));
        if (setup) {
            String required = h.problemAt("setup.money");
            if (required != null) content.addView(errorLine(c, required));
        }
        CloudModel.Spent spent = doc == null ? null : doc.spent;
        if (spent != null && (e.canSpend() || spent.total > 0)) {
            content.addView(CloudUi.note(c, c.getString(R.string.projects_setup_spent_of_total,
                    PlusWords.money(c, spent.total, doc.server))));
            // "Harcananı sıfırla" beside what it resets (it sat at the end of "Gelişmiş").
            if (!setup && spent.total > 0) {
                content.addView(CloudUi.textButton(c, c.getString(R.string.projects_plus_reset_spent), v -> askReset(h, v)),
                        new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
        }
        // The limits' one quiet line (afkplus-free.md §3.5): never said to the other player.
        content.addView(CloudUi.note(c, c.getString(R.string.projects_fine_secret)));
        if (setup) return;
        LinearLayout box = advanced(content, h, drawn, MONEY, openAdvanced);
        box.addView(limitField(h, drawn, "dailySpendMax", null, c.getString(R.string.projects_plus_limit_none)).field.view);
        final PlusAmount floor = new PlusAmount(c, CloudWords.limitLabel(c, "balanceFloor"), e.balanceFloor());
        floor.field.input.setHint("0");
        String floorUnit = moneyName(h);
        if (floorUnit != null) floor.field.unit(floorUnit);
        String floorProblem = h.problemAt("limits.balanceFloor");
        if (floorProblem != null) floor.field.setError(floorProblem);
        floor.onChange(() -> {
            Long v = floor.value();
            e.setLimit("balanceFloor", v == null ? 0L : v);
            h.changed();
        });
        drawn.fields.put("limits.balanceFloor", floor.field.view);
        box.addView(floor.field.view);
        if (currencyShown(doc)) currency(box, h, drawn);
    }

    /** The money's name as amounts carry it ("ripple"), or null for bare amounts. */
    @Nullable
    static String moneyName(Host h) {
        CloudModel.PlusDoc doc = h.doc();
        if (doc == null || doc.server.moneyLabel == null) return null;
        return doc.server.moneyLabel.in(CloudWords.lang(h.ctx()));
    }

    /** "Para birimi": the player's name for the money, or the profile's, read-only (TRADE-ANY decision 23). */
    private static void currency(LinearLayout box, final Host h, Drawn drawn) {
        final Context c = h.ctx();
        final PlusConfig e = h.edit();
        CloudModel.PlusDoc doc = h.doc();
        if (doc != null && doc.server.moneyFixed && doc.server.moneyLabel != null) {
            LinearLayout group = CloudUi.group(c);
            group.addView(CloudUi.figureRow(c, c.getString(R.string.projects_currency_label), doc.server.moneyLabel.in(CloudWords.lang(c))).view);
            box.addView(group);
            box.addView(CloudUi.note(c, c.getString(R.string.projects_currency_fixed_help)));
            return;
        }
        String stored = e.currency();
        final CloudUi.Field field = CloudUi.field(c, c.getString(R.string.projects_currency_label), stored == null ? "" : stored, null,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(24)});
        String problem = h.problemAt("trade.currency");
        if (problem != null) field.setError(c.getString(R.string.projects_currency_refused));
        else field.setHelp(c.getString(R.string.projects_currency_help));
        final PlusConfig original = h.original();
        watch(field.input, s -> {
            String typed = s.trim();
            if (typed.isEmpty()) {
                // Empty clears a stored name (null); a document without one stays without the key.
                if (original != null && !original.currencySet()) e.removeCurrency();
                else e.setCurrency(null);
                field.setError(null);
                field.setHelp(c.getString(R.string.projects_currency_help));
            } else {
                e.setCurrency(PlusText.cleanCurrency(typed));
                String why = PlusText.checkCurrency(typed);
                if (why == null) {
                    field.setError(null);
                    field.setHelp(c.getString(R.string.projects_currency_help));
                } else {
                    field.setError(c.getString(R.string.projects_currency_refused));
                }
            }
            h.changed();
        });
        drawn.fields.put("trade.currency", field.view);
        box.addView(field.view);
    }

    private static void askReset(final Host h, final View from) {
        final Context c = h.ctx();
        SocialUi.confirm(h.activity(), c.getString(R.string.projects_plus_reset_spent), c.getString(R.string.projects_plus_reset_spent_body),
                c.getString(R.string.projects_plus_reset_spent), () -> {
                    String id = h.accountId();
                    if (id == null) return;
                    h.service().control(id, "reset_spent", null, new CloudService.Result<JsonObject>() {
                        @Override
                        public void ok(JsonObject value) {
                            if (!h.alive()) return;
                            Haptics.confirm(from);
                            h.toast(c.getString(R.string.projects_plus_reset_spent_done));
                        }

                        @Override
                        public void failed(CloudProblem problem) {
                            if (h.alive()) h.problem(problem, from);
                        }
                    });
                });
    }

    /** "ProBot AI ne yapsın?": the authority (with its approval limits under "tamamla"), the sides; the rest folded. */
    private static void act(LinearLayout content, final Host h, Drawn drawn, boolean openAdvanced) {
        final Context c = h.ctx();
        final PlusConfig e = h.edit();
        CloudModel.PlusDoc doc = h.doc();
        LinearLayout authority = CloudUi.group(c);
        authority.addView(CloudUi.choices(c, new CharSequence[]{
                choiceText(c, R.string.projects_fine_reply, R.string.projects_fine_reply_help),
                choiceText(c, R.string.projects_fine_ask, R.string.projects_fine_ask_help),
                choiceText(c, R.string.projects_fine_deal, R.string.projects_fine_deal_help)},
                index(PlusConfig.AUTHORITIES, e.authority(), 1), true, i -> {
                    boolean was = "deal".equals(e.authority());
                    e.setAuthority(PlusConfig.AUTHORITIES[i]);
                    h.changed();
                    // The approval limits work only under "tamamla": they show there (their values stay).
                    if (was != "deal".equals(e.authority())) h.rebuild();
                }));
        drawn.fields.put("authority", authority);
        content.addView(authority);
        String authorityProblem = h.problemAt("authority");
        if (authorityProblem != null) content.addView(errorLine(c, authorityProblem));
        if ("deal".equals(e.authority())) {
            content.addView(limitField(h, drawn, "approvalAbove", c.getString(R.string.projects_limit_no_ask), null).field.view);
            PlusAmount fresh = limitField(h, drawn, "newCounterpartApprovalAbove", c.getString(R.string.projects_limit_new_counterpart_help), null);
            fresh.field.label.setText(c.getString(R.string.projects_limit_new_counterpart));
            content.addView(fresh.field.view);
        }

        LinearLayout sides = CloudUi.group(c);
        sides.addView(CloudUi.toggle(c, c.getString(R.string.projects_fine_buy), e.allows("buy"), on -> {
            e.setAllows("buy", on);
            h.changed();
        }));
        sides.addView(help(c, R.string.projects_setup_sides_buy));
        sides.addView(CloudUi.textDivider(c));
        sides.addView(CloudUi.toggle(c, c.getString(R.string.projects_fine_sell), e.allows("sell"), on -> {
            e.setAllows("sell", on);
            h.changed();
        }));
        sides.addView(help(c, R.string.projects_setup_sides_sell));
        LinearLayout.LayoutParams sidesLp = CloudUi.matchWrap();
        sidesLp.topMargin = CloudUi.dp(c, 12);
        content.addView(sides, sidesLp);

        LinearLayout box = advanced(content, h, drawn, ACT, openAdvanced);
        // Item swaps only while Studio has them on (features.itemPay); without them the payment is money.
        if (doc != null && doc.rules.itemPay) {
            box.addView(CloudRowSheet.label(c, c.getString(R.string.projects_fine_pay)));
            LinearLayout pay = CloudUi.segmented(c, new CharSequence[]{c.getString(R.string.projects_fine_pay_money),
                    c.getString(R.string.projects_fine_pay_items), c.getString(R.string.projects_fine_pay_both)},
                    index(PlusConfig.PAYS, e.pay(), 0), i -> {
                        e.setPay(PlusConfig.PAYS[i]);
                        h.changed();
                    });
            drawn.fields.put("pay", pay);
            box.addView(pay);
            String payProblem = h.problemAt("pay");
            if (payProblem != null) box.addView(errorLine(c, payProblem));
        }
        final CloudUi.Field timeout = CloudUi.field(c, CloudWords.limitLabel(c, "approvalTimeoutMin"), String.valueOf(e.approvalTimeoutMin()), null,
                InputType.TYPE_CLASS_NUMBER);
        timeout.input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2)});
        timeout.setHelp(c.getString(R.string.projects_plus_limit_timeout_help));
        String timeoutProblem = h.problemAt("limits.approvalTimeoutMin");
        if (timeoutProblem != null) timeout.setError(timeoutProblem);
        timeout.input.addTextChangedListener(watcher(() -> {
            e.setLimit("approvalTimeoutMin", (long) CloudRowSheet.number(timeout.value(), 0));
            h.changed();
        }));
        View timeoutView = timeout.unit(c.getString(R.string.projects_plus_limit_minutes)).view;
        drawn.fields.put("limits.approvalTimeoutMin", timeoutView);
        box.addView(timeoutView);
    }

    private static TextView help(Context c, int words) {
        TextView help = CloudUi.text(c, c.getString(words), 13, R.color.projects_ash_400, false);
        help.setPadding(CloudUi.dp(c, 16), 0, CloudUi.dp(c, 16), CloudUi.dp(c, 12));
        return help;
    }

    /**
     * "Sohbette ne yapsın?": the ads (trade-any-4: the interval from the Worker's floor, the next lines, a warning, a stop,
     * the names the machine has not given yet); a Worker without {@code adPreview} keeps the switch alone.
     */
    private static void chat(LinearLayout content, final Host h, Drawn drawn, boolean openAdvanced) {
        final Context c = h.ctx();
        final PlusConfig e = h.edit();
        CloudModel.PlusDoc doc = h.doc();
        final PlusModel.AdPreview preview = doc == null ? null : doc.adPreview;
        LinearLayout group = CloudUi.group(c);
        androidx.appcompat.widget.SwitchCompat ads = CloudUi.toggle(c, c.getString(R.string.projects_ads_switch), e.ads(), on -> {
            e.setAds(on);
            h.changed();
            h.rebuild();
        });
        group.addView(ads);
        if (doc != null && preview == null) {
            // An older service without the ads' preview: the switch and its title dimmed, one line why (design.md's rule
            // for a setting that cannot work here; the lead, 29 Oct).
            ads.setEnabled(false);
            ads.setAlpha(0.45f);
            group.addView(help(c, R.string.projects_ads_old_service));
        }
        content.addView(group);
        if (e.ads() && preview != null) {
            long now = h.service().nowSeconds();
            content.addView(CloudRowSheet.label(c, c.getString(R.string.projects_ads_how)));
            final int[] every = PlusConfig.AD_EVERY;
            Integer chosen = e.adEveryMin();
            int current = chosen == null ? PlusConfig.AD_EVERY_DEFAULT : chosen;
            // The first choice at or above both the stored interval and the floor (a floor between two choices too).
            int want = Math.max(current, preview.floorMin);
            int at = every.length - 1;
            for (int i = every.length - 1; i >= 0; i--) if (every[i] >= want) at = i;
            int[] words = {R.string.projects_ads_every_30, R.string.projects_ads_every_45, R.string.projects_ads_every_60, R.string.projects_ads_every_120};
            CharSequence[] labels = new CharSequence[every.length];
            for (int i = 0; i < every.length; i++) labels[i] = c.getString(words[i]);
            LinearLayout chips = CloudUi.segmented(c, labels, at, i -> {
                if (every[i] < preview.floorMin) return;
                e.setAdEveryMin(every[i]);
                h.changed();
            });
            // Below this server's floor a choice is closed, said once.
            boolean floored = false;
            for (int i = 0; i < every.length && i < chips.getChildCount(); i++) {
                if (every[i] < preview.floorMin) {
                    chips.getChildAt(i).setEnabled(false);
                    chips.getChildAt(i).setAlpha(0.45f);
                    floored = true;
                }
            }
            content.addView(chips);
            if (floored) content.addView(CloudUi.note(c, c.getString(R.string.projects_ads_floor, everyWords(c, preview.floorMin))));
            if (preview.stoppedToday) {
                content.addView(CloudUi.notice(c, R.drawable.projects_ic_hand_palm, R.color.projects_ash_300, c.getString(R.string.projects_ads_stopped)));
            } else if (preview.warningAt != null) {
                String when = preview.warningAt > now - 86_400L && sameDay(preview.warningAt, now)
                        ? c.getString(R.string.projects_ads_today, CloudWords.at(c, preview.warningAt, now))
                        : c.getString(R.string.projects_ads_yesterday, CloudWords.at(c, preview.warningAt, now));
                content.addView(CloudUi.notice(c, R.drawable.projects_ic_warning_circle, R.color.projects_ash_300,
                        c.getString(R.string.projects_ads_warned, when, everyWords(c, preview.everyMin))));
            }
            if (preview.namesPending) {
                content.addView(CloudUi.note(c, c.getString(R.string.projects_ads_names_pending)));
            } else if (preview.lines.isEmpty()) {
                // No active order: it says it posts nothing for now, with the way to one (no spam note: nothing goes out).
                content.addView(CloudUi.note(c, c.getString(R.string.projects_ads_empty)));
                content.addView(CloudUi.textButton(c, c.getString(R.string.projects_ads_add_order), v -> h.openOrders()),
                        new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            } else {
                String next = c.getString(R.string.projects_ads_next);
                if (preview.stoppedToday) next = next + " " + c.getString(R.string.projects_ads_tomorrow);
                else if (preview.nextAt != null) next = c.getString(R.string.projects_ads_next_at, CloudWords.at(c, preview.nextAt, now));
                content.addView(CloudRowSheet.label(c, next));
                final TextView line = chatLine(c, preview.lines.get(0));
                content.addView(line, CloudUi.matchWrap());
                TextView caption = CloudUi.text(c, c.getString(R.string.projects_setup_chat_caption), 12.5f, R.color.projects_ash_400, false);
                caption.setPadding(CloudUi.dp(c, 8), CloudUi.dp(c, 4), CloudUi.dp(c, 8), 0);
                content.addView(caption);
                if (preview.lines.size() > 1) {
                    final int[] shown = {0};
                    content.addView(CloudUi.textButton(c, c.getString(R.string.projects_ads_other), v -> {
                        shown[0] = (shown[0] + 1) % preview.lines.size();
                        line.setText(preview.lines.get(shown[0]));
                        Haptics.tick(v);
                    }), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                }
            }
            boolean quiet = preview.stoppedToday || preview.warningAt != null || (!preview.namesPending && preview.lines.isEmpty());
            if (!quiet) content.addView(CloudUi.note(c, c.getString(R.string.projects_ads_note)));
        }
        // Writing to a seller is in plain sight (the site's r7 switch): ProBot AI writes to strangers by itself here.
        LinearLayout sellers = CloudUi.group(c);
        sellers.addView(CloudUi.toggle(c, c.getString(R.string.projects_ads_write_sellers), e.proactivePm(), on -> {
            e.setProactivePm(on);
            h.changed();
        }));
        LinearLayout.LayoutParams sellersLp = CloudUi.matchWrap();
        sellersLp.topMargin = CloudUi.dp(c, 12);
        content.addView(sellers, sellersLp);
        LinearLayout box = advanced(content, h, drawn, CHAT, openAdvanced);
        LinearLayout look = CloudUi.group(c);
        look.addView(CloudUi.toggle(c, c.getString(R.string.projects_ads_scan), e.scanChat(), on -> {
            e.setScanChat(on);
            h.changed();
        }));
        box.addView(look);
    }

    private static boolean sameDay(long a, long b) {
        java.util.Calendar x = java.util.Calendar.getInstance();
        x.setTimeInMillis(a * 1000L);
        java.util.Calendar y = java.util.Calendar.getInstance();
        y.setTimeInMillis(b * 1000L);
        return x.get(java.util.Calendar.YEAR) == y.get(java.util.Calendar.YEAR) && x.get(java.util.Calendar.DAY_OF_YEAR) == y.get(java.util.Calendar.DAY_OF_YEAR);
    }

    /** A line as the game's chat shows it: light words on the chat's dark strip. */
    private static TextView chatLine(Context c, String text) {
        TextView line = CloudUi.text(c, text, 14.5f, R.color.projects_bone_100, false);
        // Figtree, not the system's monospace (a "technical" costume; the site draws its pixel font, which the app lacks).
        line.setTypeface(CloudUi.font(c, R.font.figtree_regular));
        line.setPadding(CloudUi.dp(c, 12), CloudUi.dp(c, 8), CloudUi.dp(c, 12), CloudUi.dp(c, 8));
        line.setBackgroundColor(0x6B000000);
        return line;
    }

    /** "Nasıl konuşsun?": the tone, "Oyuncu gibi konuşsun" and how a reply reads; the rest folded. */
    private static void talk(LinearLayout content, final Host h, Drawn drawn, boolean openAdvanced) {
        final Context c = h.ctx();
        final PlusConfig e = h.edit();
        final CloudModel.PlusDoc doc = h.doc();
        content.addView(CloudRowSheet.label(c, c.getString(R.string.projects_plus_persona)));
        final TextView[] sample = new TextView[1];
        content.addView(CloudUi.segmented(c, new CharSequence[]{c.getString(R.string.projects_plus_preset_friendly),
                c.getString(R.string.projects_plus_preset_brief), c.getString(R.string.projects_plus_preset_polite),
                c.getString(R.string.projects_plus_preset_playful)}, index(PlusConfig.PRESETS, e.preset(), 2), i -> {
            e.setPreset(PlusConfig.PRESETS[i]);
            h.changed();
            sample(c, e, sample[0]);
        }));
        LinearLayout group = CloudUi.group(c);
        group.addView(CloudUi.toggle(c, c.getString(R.string.projects_talk_player), e.playerTalk(), on -> {
            e.setPlayerTalk(on);
            h.changed();
            sample(c, e, sample[0]);
        }));
        group.addView(help(c, R.string.projects_talk_player_help));
        LinearLayout.LayoutParams groupLp = CloudUi.matchWrap();
        groupLp.topMargin = CloudUi.dp(c, 12);
        content.addView(group, groupLp);
        // How a reply reads with these settings, as a private message shows it.
        TextView line = chatLine(c, "");
        sample[0] = line;
        LinearLayout.LayoutParams lineLp = CloudUi.matchWrap();
        lineLp.topMargin = CloudUi.dp(c, 12);
        content.addView(line, lineLp);
        TextView caption = CloudUi.text(c, c.getString(R.string.projects_setup_pm_caption), 12.5f, R.color.projects_ash_400, false);
        caption.setPadding(CloudUi.dp(c, 8), CloudUi.dp(c, 4), CloudUi.dp(c, 8), 0);
        content.addView(caption);
        sample(c, e, line);

        LinearLayout box = advanced(content, h, drawn, TALK, openAdvanced);
        CloudUi.Field intro = CloudUi.field(c, c.getString(R.string.projects_plus_intro), e.intro(), c.getString(R.string.projects_plus_intro_hint),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        intro.input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(PlusConfig.INTRO_MAX)});
        intro.setHelp(c.getString(R.string.projects_plus_intro_help));
        judge(h, intro, doc == null ? null : doc.intro, "persona.intro");
        watch(intro.input, s -> {
            e.setIntro(s.trim());
            h.changed();
        });
        box.addView(intro.view);
        tag(box, h, sample);
        box.addView(CloudRowSheet.label(c, c.getString(R.string.projects_plus_lang)));
        final String[] langs = {"auto", "tr", "en"};
        LinearLayout langGroup = CloudUi.group(c);
        langGroup.addView(CloudUi.choices(c, new CharSequence[]{c.getString(R.string.projects_plus_lang_auto), c.getString(R.string.projects_plus_lang_tr),
                c.getString(R.string.projects_plus_lang_en)}, index(langs, e.lang(), 0), true, i -> {
            e.setLang(langs[i]);
            h.changed();
        }));
        box.addView(langGroup);
        LinearLayout topic = CloudUi.group(c);
        topic.addView(CloudUi.toggle(c, c.getString(R.string.projects_talk_on_topic), !e.smallTalk(), on -> {
            e.setSmallTalk(!on);
            h.changed();
        }));
        LinearLayout.LayoutParams topicLp = CloudUi.matchWrap();
        topicLp.topMargin = CloudUi.dp(c, 12);
        box.addView(topic, topicLp);
    }

    /** "Mesajlarının başına etiket koysun" with its field (the player's choice, off by default), checked while typed. */
    private static void tag(LinearLayout box, final Host h, final TextView[] sample) {
        final Context c = h.ctx();
        final PlusConfig e = h.edit();
        LinearLayout group = CloudUi.group(c);
        final LinearLayout tagBox = new LinearLayout(c);
        tagBox.setOrientation(LinearLayout.VERTICAL);
        tagBox.setPadding(CloudUi.dp(c, 8), 0, CloudUi.dp(c, 8), CloudUi.dp(c, 8));
        final CloudUi.Field tag = CloudUi.field(c, c.getString(R.string.projects_tag_label), e.tagText(), PlusConfig.TAG_DEFAULT,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        tag.input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(32)});
        String stored = h.problemAt("persona.prefix.text");
        if (stored != null) tag.setError(stored);
        watch(tag.input, s -> {
            e.setTag(e.tagOn(), s);
            String typed = s.trim();
            String problem = e.tagOn() ? PlusText.checkPrefix(typed, names(e)) : null;
            tag.setError(problem == null ? null : PlusWords.tagProblem(c, typed.isEmpty() ? "empty" : problem));
            h.changed();
            sample(c, e, sample[0]);
        });
        tagBox.addView(tag.view);
        tagBox.setVisibility(e.tagOn() ? View.VISIBLE : View.GONE);
        group.addView(CloudUi.toggle(c, c.getString(R.string.projects_tag_switch), e.tagOn(), on -> {
            String text = tag.value().trim().isEmpty() ? PlusConfig.TAG_DEFAULT : tag.value();
            // Off: a tag that would not pass goes back to what is stored, so turning it off never saves a bad one.
            PlusConfig original = h.original();
            if (!on && PlusText.checkPrefix(text, names(e)) != null && original != null) text = original.tagText();
            e.setTag(on, text);
            if (!text.equals(tag.value())) tag.input.setText(text);
            tag.setError(null);
            tagBox.setVisibility(on ? View.VISIBLE : View.GONE);
            h.changed();
            sample(c, e, sample[0]);
        }));
        group.addView(tagBox, CloudUi.matchWrap());
        LinearLayout.LayoutParams lp = CloudUi.matchWrap();
        lp.topMargin = CloudUi.dp(c, 12);
        box.addView(group, lp);
    }

    /** How a reply reads for the chosen tone, in the player's talk when that is on, after the tag when it is on. */
    private static void sample(Context c, PlusConfig e, @Nullable TextView line) {
        if (line == null) return;
        int i = index(PlusConfig.PRESETS, e.preset(), 2);
        int[] plain = {R.string.projects_talk_plain_friendly, R.string.projects_talk_plain_brief, R.string.projects_talk_plain_polite,
                R.string.projects_talk_plain_playful};
        int[] player = {R.string.projects_talk_sample_friendly, R.string.projects_talk_sample_brief, R.string.projects_talk_sample_polite,
                R.string.projects_talk_sample_playful};
        String words = c.getString(e.playerTalk() ? player[i] : plain[i]);
        String tag = e.tagText().trim();
        if (e.tagOn() && PlusText.checkPrefix(tag, names(e)) == null) words = ChatText.clean(tag) + " " + words;
        line.setText(words);
    }

    /** The names a tag may not hold: the friends and the blocked players of these settings. */
    private static List<String> names(PlusConfig e) {
        List<String> names = new ArrayList<>(e.friends());
        names.addAll(e.blocked());
        return names;
    }

    private static void judge(Host h, CloudUi.Field field, @Nullable CloudModel.Judge judge, String path) {
        String problem = h.problemAt(path);
        if (problem != null) {
            field.setError(problem);
            return;
        }
        if (judge == null || judge.state == null) return;
        Context c = h.ctx();
        if ("pending".equals(judge.state)) field.setHelp(c.getString(R.string.projects_plus_judge_pending));
        else if (!"ok".equals(judge.state)) field.setError(CloudWords.judgeText(c, judge.reason));
    }

    /** "Kimlere cevap versin?": which messages, with whom (and the friends), away and what it says then, the blocked. */
    private static void who(LinearLayout content, final Host h) {
        final Context c = h.ctx();
        final PlusConfig e = h.edit();
        final CloudModel.PlusDoc doc = h.doc();
        content.addView(CloudRowSheet.label(c, c.getString(R.string.projects_plus_scope)));
        final String[] scopes = {"all", "trade", "off"};
        LinearLayout scopeGroup = CloudUi.group(c);
        scopeGroup.addView(CloudUi.choices(c, new CharSequence[]{c.getString(R.string.projects_plus_scope_all), c.getString(R.string.projects_plus_scope_trade),
                c.getString(R.string.projects_plus_scope_off)}, index(scopes, e.scope(), 1), true, i -> {
            e.setScope(scopes[i]);
            h.changed();
        }));
        content.addView(scopeGroup);

        content.addView(CloudRowSheet.label(c, c.getString(R.string.projects_fine_who)));
        final boolean friendsOnly = "friends".equals(e.whoMode());
        content.addView(CloudUi.segmented(c, new CharSequence[]{c.getString(R.string.projects_fine_who_all),
                c.getString(R.string.projects_fine_who_friends)}, friendsOnly ? 1 : 0, i -> {
            e.setWho(i == 1 ? "friends" : "all", e.friends());
            h.changed();
            h.rebuild();
        }));
        if (friendsOnly) {
            names(content, h, true);
        }

        LinearLayout away = CloudUi.group(c);
        away.addView(CloudUi.toggle(c, c.getString(R.string.projects_plus_away), e.away(), on -> {
            e.setAway(on);
            h.changed();
        }));
        LinearLayout.LayoutParams awayLp = CloudUi.matchWrap();
        awayLp.topMargin = CloudUi.dp(c, 12);
        content.addView(away, awayLp);

        content.addView(CloudRowSheet.label(c, c.getString(R.string.projects_plus_status)));
        final String[] presets = PlusConfig.STATUS_PRESETS;
        CharSequence[] statusLabels = {c.getString(R.string.projects_plus_status_back_soon), c.getString(R.string.projects_plus_status_back_evening),
                c.getString(R.string.projects_plus_status_back_tomorrow), c.getString(R.string.projects_plus_status_weekend),
                c.getString(R.string.projects_plus_status_busy), c.getString(R.string.projects_plus_status_own)};
        String preset = e.statusPreset();
        int selected = preset != null ? index(presets, preset, -1) : e.status().isEmpty() ? -1 : presets.length;
        final LinearLayout own = new LinearLayout(c);
        own.setOrientation(LinearLayout.VERTICAL);
        final CloudUi.Field status = CloudUi.field(c, c.getString(R.string.projects_plus_status_own), e.status(), c.getString(R.string.projects_plus_status_hint),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        status.input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(PlusConfig.STATUS_MAX)});
        judge(h, status, doc == null ? null : doc.status, "persona.status");
        watch(status.input, s -> {
            if (e.statusPreset() != null) return;
            e.setStatus(s.trim());
            h.changed();
        });
        own.addView(status.view);
        own.setVisibility(selected == presets.length ? View.VISIBLE : View.GONE);
        LinearLayout statusGroup = CloudUi.group(c);
        statusGroup.addView(CloudUi.choices(c, statusLabels, selected, true, i -> {
            if (i < presets.length) {
                e.setStatusPreset(presets[i]);
                own.setVisibility(View.GONE);
            } else {
                e.setStatusPreset(null);
                e.setStatus(status.value().trim());
                own.setVisibility(View.VISIBLE);
                status.input.requestFocus();
            }
            h.changed();
        }));
        content.addView(statusGroup);
        content.addView(own, CloudUi.matchWrap());

        content.addView(CloudUi.section(c, c.getString(R.string.projects_plus_blocked)));
        names(content, h, false);
    }

    /** The friends' or the blocked players' in-game names: the list, its remove buttons and the add field. */
    private static void names(LinearLayout content, final Host h, final boolean friends) {
        final Context c = h.ctx();
        final PlusConfig e = h.edit();
        final List<String> names = new ArrayList<>(friends ? e.friends() : e.blocked());
        if (names.isEmpty()) {
            content.addView(CloudUi.note(c, c.getString(friends ? R.string.projects_fine_friends_empty : R.string.projects_plus_blocked_empty)));
        } else {
            LinearLayout group = CloudUi.group(c);
            for (int i = 0; i < names.size(); i++) {
                final String name = names.get(i);
                if (i > 0) group.addView(CloudUi.textDivider(c));
                LinearLayout row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinimumHeight(CloudUi.dp(c, 52));
                row.setPadding(CloudUi.dp(c, 16), 0, CloudUi.dp(c, 4), 0);
                row.addView(CloudUi.text(c, name, 15, R.color.projects_bone_100, false), CloudUi.weight(1f));
                if (friends) {
                    String problem = h.problemAt("who.friends[" + i + "]");
                    if (problem != null) row.addView(CloudUi.text(c, problem, 12.5f, R.color.projects_flare_400, false));
                }
                Button remove = CloudUi.textButton(c, c.getString(friends ? R.string.projects_fine_friends_remove_short : R.string.projects_plus_unblock), v -> {
                    List<String> next = new ArrayList<>(friends ? e.friends() : e.blocked());
                    next.remove(name);
                    if (friends) e.setWho("friends", next);
                    else e.setBlocked(next);
                    Haptics.tick(v);
                    h.changed();
                    h.rebuild();
                });
                remove.setContentDescription(friends ? c.getString(R.string.projects_fine_friends_remove, name)
                        : c.getString(R.string.projects_plus_unblock) + ", " + name);
                row.addView(remove);
                group.addView(row, CloudUi.matchWrap());
            }
            content.addView(group);
        }
        int max = friends ? PlusConfig.FRIENDS_MAX : PlusConfig.BLOCKED_MAX;
        if (names.size() >= max) {
            if (friends) content.addView(CloudUi.note(c, c.getResources().getQuantityString(R.plurals.projects_fine_friends_full, max, max)));
            return;
        }
        LinearLayout add = new LinearLayout(c);
        add.setOrientation(LinearLayout.HORIZONTAL);
        add.setGravity(Gravity.BOTTOM);
        final CloudUi.Field field = CloudUi.field(c, c.getString(friends ? R.string.projects_fine_friends_add : R.string.projects_plus_blocked_add), null,
                c.getString(friends ? R.string.projects_fine_friends_hint : R.string.projects_plus_blocked_hint),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(16)});
        String listProblem = h.problemAt(friends ? "who.friends" : "blocked");
        if (listProblem != null) field.setError(listProblem);
        add.addView(field.view, CloudUi.weight(1f));
        Button addButton = CloudUi.button(c, c.getString(friends ? R.string.projects_fine_friends_add : R.string.projects_plus_blocked_add), false, v -> {
            String name = field.value().trim();
            if (!CloudEdit.isGameName(name)) {
                field.setError(c.getString(friends ? R.string.projects_fine_friends_format : R.string.projects_cloud_issue_name));
                Haptics.reject(v);
                return;
            }
            for (String n : friends ? e.friends() : e.blocked()) {
                if (n.equalsIgnoreCase(name)) {
                    if (friends) field.setError(c.getString(R.string.projects_fine_friends_duplicate));
                    Haptics.reject(v);
                    return;
                }
            }
            if (friends) {
                for (String n : e.blocked()) {
                    if (n.equalsIgnoreCase(name)) {
                        field.setError(c.getString(R.string.projects_fine_friends_blocked));
                        Haptics.reject(v);
                        return;
                    }
                }
            }
            List<String> next = new ArrayList<>(friends ? e.friends() : e.blocked());
            next.add(name);
            if (friends) e.setWho("friends", next);
            else e.setBlocked(next);
            Haptics.confirm(v);
            h.changed();
            h.rebuild();
        });
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addLp.setMarginStart(CloudUi.dp(c, 4));
        addLp.bottomMargin = CloudUi.dp(c, 2);
        add.addView(addButton, addLp);
        content.addView(add, CloudUi.matchWrap());
        field.label.setVisibility(View.GONE);
    }

    /** "Diğer ayarlar": the low-credit saver and the daily summary's time (the account type stays on the account screen). */
    private static void other(LinearLayout content, final Host h) {
        final Context c = h.ctx();
        final PlusConfig e = h.edit();
        CloudService service = h.service();
        if (service.plusFeature("saver")) {
            content.addView(CloudRowSheet.label(c, c.getString(R.string.projects_plus_saver)));
            Integer saveAt = e.saveAt();
            int at = saveAt == null ? 2 : saveAt == 80 ? 0 : 1;
            content.addView(CloudUi.segmented(c, new CharSequence[]{c.getString(R.string.projects_plus_saver_80), c.getString(R.string.projects_plus_saver_50),
                    c.getString(R.string.projects_plus_saver_off)}, at, i -> {
                e.setSaveAt(i == 0 ? Integer.valueOf(80) : i == 1 ? Integer.valueOf(50) : null);
                h.changed();
            }));
        }
        if (service.plusFeature("report")) {
            LinearLayout look = CloudUi.group(c);
            final CloudUi.Row[] summary = new CloudUi.Row[1];
            summary[0] = CloudUi.valueRow(c, c.getString(R.string.projects_plus_summary_at), e.summaryAt(),
                    v -> pickTime(h.activity(), e.summaryAt(), time -> {
                        e.setSummaryAt(time);
                        summary[0].count.setText(time);
                        h.changed();
                    }));
            android.widget.ImageView caret = new android.widget.ImageView(c);
            caret.setImageResource(R.drawable.projects_ic_caret_right);
            caret.setImageTintList(android.content.res.ColorStateList.valueOf(CloudUi.color(c, R.color.projects_ash_400)));
            caret.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams caretLp = new LinearLayout.LayoutParams(CloudUi.dp(c, 20), CloudUi.dp(c, 20));
            caretLp.setMarginStart(CloudUi.dp(c, 6));
            summary[0].view.addView(caret, caretLp);
            look.addView(summary[0].view);
            LinearLayout.LayoutParams lp = CloudUi.matchWrap();
            lp.topMargin = CloudUi.dp(c, 16);
            content.addView(look, lp);
        }
    }

    private interface Picked {
        void picked(String time);
    }

    private static void pickTime(Activity activity, String current, final Picked picked) {
        int hour = 21;
        int minute = 0;
        try {
            hour = Integer.parseInt(current.substring(0, 2));
            minute = Integer.parseInt(current.substring(3, 5));
        } catch (RuntimeException ignored) {
            // Not a time: start from 21:00.
        }
        new TimePickerDialog(activity, (view, hh, mm) -> picked.picked(String.format(Locale.ROOT, "%02d:%02d", hh, mm)), hour, minute, true).show();
    }
}
