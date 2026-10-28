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
        return written + " " + server.moneyLabel.in(CloudWords.lang(c));
    }

    /** A price: money, or the paying item's count ("3 Elmas") when {@code pay} is an item. */
    static String price(Context c, long price, String pay, @Nullable PlusModel.Server server, Namer namer) {
        if (pay == null || "money".equals(pay)) return money(c, price, server);
        return CloudWords.qtyName(c, price, namer.name(pay));
    }

    /** A deal as the launcher and the site write it: "1 Elytra = 37.500", "64 Elmas = 3 Netherit külçesi". */
    static String deal(Context c, long qty, String item, long price, String pay, @Nullable PlusModel.Server server, Namer namer) {
        return c.getString(R.string.projects_cloud_notice_deal_line, String.valueOf(qty), namer.name(item), price(c, price, pay, server, namer));
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

    /** An audit log entry's line ("Kaan_07 ile anlaşıldı: 1 Elytra = 37.500"); a message's own text goes under it. */
    static String auditLine(Context c, PlusModel.AuditEntry e, @Nullable PlusModel.Server server, Namer namer) {
        String who = e.w == null || e.w.isEmpty() ? c.getString(R.string.projects_audit_someone) : e.w;
        String item = e.i == null ? null : namer.name(e.i);
        String price = e.p == null ? null : price(c, e.p, e.u == null ? "money" : e.u, server, namer);
        String deal = e.i == null || e.p == null ? null
                : deal(c, e.q == null ? 1 : e.q, e.i, e.p, e.u == null ? "money" : e.u, server, namer);
        switch (e.k) {
            case "pm_in": return c.getString(R.string.projects_audit_pm_in, who);
            case "reply": return c.getString(R.string.projects_audit_reply);
            case "owner_msg": return c.getString(R.string.projects_audit_owner_msg);
            case "offer": return price == null ? c.getString(R.string.projects_audit_other) : c.getString(R.string.projects_audit_offer, who, price);
            case "counter": return price == null ? c.getString(R.string.projects_audit_other) : c.getString(R.string.projects_audit_counter, who, price);
            case "agree": return deal == null ? c.getString(R.string.projects_audit_other) : c.getString(R.string.projects_audit_agree, who, deal);
            case "ask_owner": return item == null ? c.getString(R.string.projects_audit_ask_owner) : c.getString(R.string.projects_audit_ask_owner_of, item);
            case "owner_ok": return c.getString(R.string.projects_audit_owner_ok);
            case "owner_no": return c.getString(R.string.projects_audit_owner_no);
            case "owner_timeout": return c.getString(R.string.projects_audit_owner_timeout);
            case "trade_start": return c.getString(R.string.projects_audit_trade_start);
            case "trade_done": return deal == null ? c.getString(R.string.projects_audit_trade_done_bare) : c.getString(R.string.projects_audit_trade_done, deal);
            case "trade_fail": return c.getString(R.string.projects_audit_trade_fail, reason(c, e.r));
            case "handoff": return c.getString(R.string.projects_audit_handoff);
            case "handoff_done": return c.getString(R.string.projects_audit_handoff_done);
            case "handoff_no": return c.getString(R.string.projects_audit_handoff_no);
            case "cancel": return c.getString(R.string.projects_audit_cancel, reason(c, e.r));
            case "refuse":
                return e.w == null || e.w.isEmpty() ? c.getString(R.string.projects_audit_refuse, reason(c, e.r))
                        : c.getString(R.string.projects_audit_refuse_who, e.w, reason(c, e.r));
            case "found":
                if (item == null) return c.getString(R.string.projects_audit_other);
                return price == null ? c.getString(R.string.projects_audit_found_bare, item) : c.getString(R.string.projects_audit_found, item, price);
            case "confirm_item": return item == null ? c.getString(R.string.projects_audit_confirm_item) : c.getString(R.string.projects_audit_confirm_item_of, item);
            case "order_done": return c.getString(R.string.projects_audit_order_done);
            case "order_expired": return c.getString(R.string.projects_audit_order_expired);
            case "paused": return e.r == null ? c.getString(R.string.projects_audit_paused_bare) : c.getString(R.string.projects_audit_paused, reason(c, e.r));
            case "resumed": return c.getString(R.string.projects_audit_resumed);
            case "quota": return c.getString(R.string.projects_audit_quota);
            case "gate": return e.r == null ? c.getString(R.string.projects_audit_gate) : c.getString(R.string.projects_audit_gate_why, reason(c, e.r));
            default: return c.getString(R.string.projects_audit_other);
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
                return new int[]{R.drawable.projects_ic_check_circle, R.color.projects_jade_400};
            case "trade_fail":
            case "gate":
            case "refuse":
                return new int[]{R.drawable.projects_ic_warning_circle, R.color.projects_ash_300};
            case "paused":
            case "quota":
                return new int[]{R.drawable.projects_ic_hand_palm, R.color.projects_ash_300};
            default:
                return new int[]{0, 0};
        }
    }
}
