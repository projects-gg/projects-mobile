package gg.projects.mobile.cloud.ui;

import android.content.Context;

import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.R;

import gg.projects.mobile.cloud.CloudWords;
import gg.projects.mobile.cloud.PlusConfig;
import gg.projects.mobile.cloud.PlusModel;

/**
 * The words of catalog-free AFK+ on the phone, the launcher's and the site's ({@code tr-afkplus.ts}, afkplus-free.md §7.6):
 * an order's status, amounts with the server's money name, a deal ("1 Elytra = 37.500"), the audit log's lines and their
 * reasons, the tag rule's problems. No code, id or error key is ever shown; a player's name never takes an ending.
 */
final class PlusWords {
    private PlusWords() {}

    /** Names an item reference ({@code minecraft:…} or {@code custom:<k>}) in the player's language. */
    interface Namer {
        String name(String ref);
    }

    /** Whether a JSON value is a number (a list the service sends may hold anything). */
    static boolean isNumber(@Nullable com.google.gson.JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber();
    }

    /** An enchantment's level as an order keeps it; a missing or odd one reads as the lowest (I). */
    static int level(@Nullable com.google.gson.JsonElement e) {
        if (!isNumber(e)) return 1;
        long v = e.getAsLong();
        return v < 1 ? 1 : v > 255 ? 255 : (int) v;
    }

    /** An order's status in a word ({@code status} as {@code statusOf} gives it; {@code side} for the side-specific ones). */
    static String status(Context c, String status, String side) {
        boolean sell = "sell".equals(side);
        switch (status) {
            case "waiting_ok": return c.getString(R.string.projects_order_status_waiting_ok);
            case "your_trade": return c.getString(R.string.projects_order_status_your_trade);
            case "trading": return c.getString(R.string.projects_order_status_trading);
            case "done": return c.getString(R.string.projects_order_status_done);
            case "expired": return c.getString(R.string.projects_order_status_expired);
            case "paused": return c.getString(R.string.projects_order_status_paused);
            case "side_off": return c.getString(sell ? R.string.projects_order_status_sell_off : R.string.projects_order_status_buy_off);
            case "haggling": return c.getString(R.string.projects_order_status_haggling);
            case "looking":
            default:
                return c.getString(sell ? R.string.projects_order_status_looking_sell : R.string.projects_order_status_looking);
        }
    }

    /** Whether a status waits for the player (its word takes the one quiet gold of the row). */
    static boolean waitsForPlayer(String status) {
        return "waiting_ok".equals(status) || "your_trade".equals(status);
    }

    /** A finished order (the "Biten" list). */
    static boolean finished(String status) {
        return "done".equals(status) || "expired".equals(status);
    }

    /** An amount of the server's money: bare, or with the money's own name after it ("40.000 Coin"). */
    static String money(Context c, long value, @Nullable PlusModel.Server server) {
        String written = CloudWords.money(c, value);
        if (server == null || server.moneyLabel == null) return written;
        return written + "\u00A0" + server.moneyLabel.in(CloudWords.lang(c));
    }

    /** A price: money, or the paying item's count ("3 Elmas") when {@code pay} is an item. */
    static String price(Context c, long price, String pay, @Nullable PlusModel.Server server, Namer namer) {
        if (pay == null || "money".equals(pay)) return money(c, price, server);
        return CloudWords.qtyItem(c, price, namer.name(pay));
    }

    /** A deal as the launcher and the site write it: "1 Elytra = 37.500", "64 Elmas = 3 Netherit külçesi". */
    static String deal(Context c, long qty, String item, long price, String pay, @Nullable PlusModel.Server server, Namer namer) {
        return c.getString(R.string.projects_cloud_notice_deal_line, CloudWords.goods(c, qty, namer.name(item)),
                price(c, price, pay, server, namer));
    }

    /** The tag rule's problem ({@code PlusText.checkPrefix}'s or the service's {@code problem}) in one sentence. */
    static String tagProblem(Context c, @Nullable String problem) {
        if (problem == null) return c.getString(R.string.projects_tag_error_words);
        switch (problem) {
            case "empty": return c.getString(R.string.projects_tag_error_empty);
            case "length": return c.getString(R.string.projects_tag_error_length, PlusConfig.TAG_MAX);
            case "form": return c.getString(R.string.projects_tag_error_form);
            case "staff": return c.getString(R.string.projects_tag_error_staff);
            case "name": return c.getString(R.string.projects_tag_error_name);
            case "link":
            case "phone":
            case "email":
            case "iban":
            case "tckn":
            case "card":
            case "platform":
                return c.getString(R.string.projects_tag_error_contact);
            default:
                return c.getString(R.string.projects_tag_error_words);
        }
    }

    /** An audit log entry's reason ({@code r}) in words, as the end of its line. */
    static String reason(Context c, @Nullable String r) {
        if (r == null) return c.getString(R.string.projects_audit_r_other);
        switch (r) {
            case "blocked": return c.getString(R.string.projects_audit_r_blocked);
            case "friends_only": return c.getString(R.string.projects_audit_r_friends_only);
            case "side_off": return c.getString(R.string.projects_audit_r_side_off);
            case "pay_kind": return c.getString(R.string.projects_audit_r_pay_kind);
            case "rules": return c.getString(R.string.projects_audit_r_rules);
            case "unsafe": return c.getString(R.string.projects_audit_r_unsafe);
            case "injection": return c.getString(R.string.projects_audit_r_injection);
            case "rate": return c.getString(R.string.projects_audit_r_rate);
            case "hours": return c.getString(R.string.projects_audit_r_hours);
            case "break": return c.getString(R.string.projects_audit_r_break);
            case "quota": return c.getString(R.string.projects_audit_r_quota);
            case "limit": return c.getString(R.string.projects_audit_r_limit);
            case "budget": return c.getString(R.string.projects_audit_r_budget);
            case "price": return c.getString(R.string.projects_audit_r_price);
            case "mismatch": return c.getString(R.string.projects_audit_r_mismatch);
            case "timeout": return c.getString(R.string.projects_audit_r_timeout);
            case "far": return c.getString(R.string.projects_audit_r_far);
            case "busy": return c.getString(R.string.projects_audit_r_busy);
            case "owner": return c.getString(R.string.projects_audit_r_owner);
            case "service": return c.getString(R.string.projects_audit_r_service);
            case "gate": return c.getString(R.string.projects_audit_r_gate);
            case "not_looking": return c.getString(R.string.projects_audit_r_not_looking);
            default: return c.getString(R.string.projects_audit_r_other);
        }
    }

    /**
     * An audit log entry's line, as the site's (PLUS-ACTIVITY r2): a player's steps sit under their name, so no line
     * repeats it ("Yazdı:", "ProBot AI anlaştı: 1 Elitra = 37.500"); an approval's steps say their deal ("Onayın istendi:
     * 2 Netherit Külçesi = 9 Elmas"); the order menu's finds and deliveries (DONUT-ORDER) with their counts. A message's
     * own text goes under it.
     */
    static String auditLine(Context c, PlusModel.AuditEntry e, @Nullable PlusModel.Server server, Namer namer) {
        String item = e.i == null ? null : namer.name(e.i);
        String pay = e.u == null ? "money" : e.u;
        String price = e.p == null ? null : price(c, e.p, pay, server, namer);
        boolean both = item != null && price != null;
        String deal = both ? deal(c, e.q == null ? 1 : e.q, e.i, e.p, pay, server, namer) : item != null ? item : price != null ? price : "";
        // "64 Elmas": the item with its count where the entry has one (the order menu's lines).
        String counted = item == null ? null : e.q == null ? item : CloudWords.qtyItem(c, e.q, item);
        String bare = price == null ? "" : price;
        switch (e.k) {
            case "pm_in": return c.getString(R.string.projects_audit_pm_in);
            case "reply": return c.getString(R.string.projects_audit_reply);
            case "owner_msg": return c.getString(R.string.projects_audit_owner_msg);
            case "offer":
                return item != null ? c.getString(R.string.projects_audit_offer, bare, item) : c.getString(R.string.projects_audit_offer_bare, bare);
            case "counter":
                return item != null ? c.getString(R.string.projects_audit_counter, bare, item) : c.getString(R.string.projects_audit_counter_bare, bare);
            case "agree": return c.getString(R.string.projects_audit_agree, deal);
            case "ask_owner":
                return both ? withDeal(c, R.string.projects_audit_ask_owner, deal)
                        : item != null ? c.getString(R.string.projects_audit_ask_owner_of, item) : c.getString(R.string.projects_audit_ask_owner);
            case "owner_ok": return both ? withDeal(c, R.string.projects_audit_owner_ok, deal) : c.getString(R.string.projects_audit_owner_ok);
            case "owner_no": return both ? withDeal(c, R.string.projects_audit_owner_no, deal) : c.getString(R.string.projects_audit_owner_no);
            case "owner_timeout":
                return both ? withDeal(c, R.string.projects_audit_owner_timeout, deal) : c.getString(R.string.projects_audit_owner_timeout);
            case "trade_start": return c.getString(R.string.projects_audit_trade_start);
            case "trade_done":
                return deal.isEmpty() ? c.getString(R.string.projects_audit_trade_done_bare) : c.getString(R.string.projects_audit_trade_done, deal);
            case "trade_fail": return c.getString(R.string.projects_audit_trade_fail, reason(c, e.r));
            case "handoff": return c.getString(R.string.projects_audit_handoff);
            case "handoff_done": return c.getString(R.string.projects_audit_handoff_done);
            case "handoff_no": return c.getString(R.string.projects_audit_handoff_no);
            case "cancel": return c.getString(R.string.projects_audit_cancel, reason(c, e.r));
            case "refuse":
                return e.w == null || e.w.isEmpty() ? c.getString(R.string.projects_audit_refuse, reason(c, e.r))
                        : c.getString(R.string.projects_audit_refuse_who, reason(c, e.r));
            case "found": {
                // Where it was seen (DONUT-ORDER: r is menu, chat or pm): the order menu says a piece's price (p is the
                // whole for q), chat and a message the whole; an older agent's entry has no r and keeps the old line.
                String what = counted == null ? "" : counted;
                String whole = price != null ? what + ", " + price : what;
                if ("menu".equals(e.r)) {
                    String each = e.p != null && e.q != null && e.q > 0 ? money(c, Math.round((double) e.p / e.q), server) : null;
                    return each != null ? c.getString(R.string.projects_audit_found_menu, what, each) : c.getString(R.string.projects_audit_found_menu_bare, what);
                }
                if ("chat".equals(e.r)) return c.getString(R.string.projects_audit_found_chat, whole);
                if ("pm".equals(e.r)) return c.getString(R.string.projects_audit_found_pm, whole);
                if (item == null) return c.getString(R.string.projects_audit_other);
                return price == null ? c.getString(R.string.projects_audit_found_bare, item) : c.getString(R.string.projects_audit_found, item, price);
            }
            case "menu_done": return c.getString(R.string.projects_audit_menu_done, deal);
            case "menu_fail":
                return counted != null ? c.getString(R.string.projects_audit_menu_fail, counted, menuReason(c, e.r))
                        : c.getString(R.string.projects_audit_menu_fail_bare, menuReason(c, e.r));
            case "ads_stopped":
                return "spam".equals(e.r) ? c.getString(R.string.projects_audit_ads_stopped) : c.getString(R.string.projects_audit_ads_stopped_bare);
            case "confirm_item": return item == null ? c.getString(R.string.projects_audit_confirm_item) : c.getString(R.string.projects_audit_confirm_item_of, item);
            case "order_done": return item == null ? c.getString(R.string.projects_audit_order_done) : c.getString(R.string.projects_audit_order_done_of, item);
            case "order_expired":
                return item == null ? c.getString(R.string.projects_audit_order_expired) : c.getString(R.string.projects_audit_order_expired_of, item);
            case "paused": return e.r == null ? c.getString(R.string.projects_audit_paused_bare) : c.getString(R.string.projects_audit_paused, reason(c, e.r));
            case "resumed": return c.getString(R.string.projects_audit_resumed);
            case "quota": return c.getString(R.string.projects_audit_quota);
            case "gate":
                // The rule's reason when it says more than "güvenlik kuralı".
                return e.r != null && !"gate".equals(e.r) ? c.getString(R.string.projects_audit_gate_why, reason(c, e.r)) : c.getString(R.string.projects_audit_gate);
            default: return c.getString(R.string.projects_audit_other);
        }
    }

    /** An approval's step with its deal ("Onayladın: 1 Elitra = 37.500"). */
    private static String withDeal(Context c, int line, String deal) {
        return c.getString(R.string.projects_audit_with_deal, c.getString(line), deal);
    }

    /** Why the order menu's delivery did not happen (DONUT-ORDER, the site's words). */
    static String menuReason(Context c, @Nullable String r) {
        if (r == null) return c.getString(R.string.projects_audit_r_other);
        switch (r) {
            case "mismatch": return c.getString(R.string.projects_audit_mr_mismatch);
            case "window": return c.getString(R.string.projects_audit_mr_window);
            case "timeout": return c.getString(R.string.projects_audit_mr_timeout);
            case "unclear": return c.getString(R.string.projects_audit_mr_unclear);
            case "inventory": return c.getString(R.string.projects_audit_mr_inventory);
            case "gate":
            case "item":
                return c.getString(R.string.projects_audit_mr_gate);
            default: return c.getString(R.string.projects_audit_r_other);
        }
    }

    /** Which icon an audit line gets, and its tint: only lines whose kind means something to the player carry one. */
    static int[] auditIcon(String kind) {
        switch (kind) {
            case "pm_in":
            case "reply":
            case "owner_msg":
                return new int[]{R.drawable.projects_ic_chat, R.color.projects_ash_400};
            case "ask_owner":
            case "confirm_item":
            case "handoff":
                return new int[]{R.drawable.projects_ic_clock, R.color.projects_gold_300};
            case "trade_done":
            case "handoff_done":
            case "order_done":
            case "menu_done":
                return new int[]{R.drawable.projects_ic_check_circle, R.color.projects_jade_400};
            case "trade_fail":
            case "gate":
            case "refuse":
            case "menu_fail":
                return new int[]{R.drawable.projects_ic_warning_circle, R.color.projects_ash_300};
            case "paused":
            case "quota":
            case "ads_stopped":
                return new int[]{R.drawable.projects_ic_hand_palm, R.color.projects_ash_300};
            default:
                return new int[]{0, 0};
        }
    }
}
