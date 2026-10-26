package gg.projects.mobile.cloud;

import android.content.Context;

import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.R;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import gg.projects.mobile.language.LanguageManager;
import gg.projects.mobile.ui.ProjectsUi;

/**
 * 7/24 Bulut in the app's words: state lines, stop causes and hints, time left, problems, settings issues, the
 * assistant's state and the notices. Keys come from the pure classes ({@link CloudStatus}, {@link CloudEdit},
 * {@link PlusFormat}, {@link CloudNotice}); codes, ids and model names are never shown.
 */
public final class CloudWords {
    private CloudWords() {}

    /** {@code tr} or {@code en}: the app's language (amounts, catalog names, Studio's words). */
    public static String lang(Context context) {
        return LanguageManager.isTurkish(context) ? "tr" : "en";
    }

    public static boolean turkish(Context context) {
        return LanguageManager.isTurkish(context);
    }

    public static Locale locale(Context context) {
        return LanguageManager.effectiveLocale(context);
    }

    // ---- states ----

    public static String causeText(Context c, @Nullable String cause) {
        switch (cause == null ? "other" : cause) {
            case "banned": return c.getString(R.string.projects_cloud_cause_banned);
            case "notWhitelisted": return c.getString(R.string.projects_cloud_cause_notWhitelisted);
            case "wrongPassword": return c.getString(R.string.projects_cloud_cause_wrongPassword);
            case "premiumOnly": return c.getString(R.string.projects_cloud_cause_premiumOnly);
            case "loggedInElsewhere": return c.getString(R.string.projects_cloud_cause_loggedInElsewhere);
            case "alreadyConnected": return c.getString(R.string.projects_cloud_cause_alreadyConnected);
            case "loginTimeout": return c.getString(R.string.projects_cloud_cause_loginTimeout);
            case "unsupportedVersion": return c.getString(R.string.projects_cloud_cause_unsupportedVersion);
            case "secretUnreadable": return c.getString(R.string.projects_cloud_cause_secretUnreadable);
            case "invalidCommand": return c.getString(R.string.projects_cloud_cause_invalidCommand);
            case "crashLoop": return c.getString(R.string.projects_cloud_cause_crashLoop);
            case "forbiddenTarget": return c.getString(R.string.projects_cloud_cause_forbiddenTarget);
            default: return c.getString(R.string.projects_cloud_cause_other);
        }
    }

    /** "2 sa 14 dk", "38 dk", "45 sn" (the app's own short spans). */
    public static String span(Context c, long seconds) {
        return ProjectsUi.span(c, seconds * 1000L);
    }

    public static String state(Context c, CloudStatus.Line line) {
        switch (line.state) {
            case RUNNING: return c.getString(R.string.projects_cloud_state_running, span(c, line.uptime));
            case RUNNING_NOW: return c.getString(R.string.projects_cloud_state_running_now);
            case CONNECTING: return c.getString(R.string.projects_cloud_state_connecting);
            case WAITING: return c.getString(R.string.projects_cloud_state_waiting);
            case WAITING_IN: return c.getString(R.string.projects_cloud_state_waiting_in, (int) line.seconds);
            case QUEUED: return c.getString(R.string.projects_cloud_state_queued);
            case QUEUED_FULL: return c.getString(R.string.projects_cloud_state_queued_full);
            case STOPPED: return c.getString(R.string.projects_cloud_state_stopped);
            case EXPIRED: return c.getString(R.string.projects_cloud_state_expired);
            case SUSPENDED: return c.getString(R.string.projects_cloud_state_suspended);
            case RESTRICTED: return c.getString(R.string.projects_cloud_state_restricted);
            case NO_HOST: return c.getString(R.string.projects_cloud_state_no_host);
            case PAUSED: return c.getString(R.string.projects_cloud_state_paused);
            case OFF: return c.getString(R.string.projects_cloud_state_off);
            case SERVER_BLOCKED: return c.getString(R.string.projects_cloud_state_server_blocked);
            case SERVER_BLOCKED_BECAUSE: return c.getString(R.string.projects_cloud_state_server_blocked_because, line.blockedReason);
            case STOPPED_BECAUSE: return c.getString(R.string.projects_cloud_state_stopped_because, causeText(c, line.cause));
            case INVALID_COMMAND: return c.getString(R.string.projects_cloud_state_invalid_command);
            case UNKNOWN:
            default: return c.getString(R.string.projects_cloud_state_unknown);
        }
    }

    @Nullable
    public static String hint(Context c, CloudStatus.Line line) {
        if (line.hint == null) return null;
        switch (line.hint) {
            case WRONG_PASSWORD: return c.getString(R.string.projects_cloud_hint_wrong_password);
            case SECRET_UNREADABLE: return c.getString(R.string.projects_cloud_hint_secret_unreadable);
            case UNREADABLE_COMMAND_ROW: return c.getString(R.string.projects_cloud_hint_unreadable_command_row, line.hintRow);
            case UNREADABLE_REPLY_ROW: return c.getString(R.string.projects_cloud_hint_unreadable_reply_row, line.hintRow);
            case INVALID_COMMAND: return c.getString(R.string.projects_cloud_hint_invalid_command);
            case INVALID_COMMAND_ROW: return c.getString(R.string.projects_cloud_hint_invalid_command_row, line.hintRow);
            case INVALID_REPLY_ROW: return c.getString(R.string.projects_cloud_hint_invalid_reply_row, line.hintRow);
            case PREMIUM_ONLY: return c.getString(R.string.projects_cloud_hint_premium_only);
            case NOT_WHITELISTED: return c.getString(R.string.projects_cloud_hint_not_whitelisted);
            case LOGGED_IN_ELSEWHERE:
            case ALREADY_CONNECTED: return c.getString(R.string.projects_cloud_hint_logged_in_elsewhere);
            case LOGIN_TIMEOUT: return c.getString(R.string.projects_cloud_hint_login_timeout);
            case UNSUPPORTED_VERSION: return c.getString(R.string.projects_cloud_hint_unsupported_version);
            case CRASH_LOOP: return c.getString(R.string.projects_cloud_hint_crash_loop);
            case FORBIDDEN_TARGET:
            case SERVER_BLOCKED: return c.getString(R.string.projects_cloud_hint_forbidden_target);
            case NO_HOST: return c.getString(R.string.projects_cloud_hint_no_host);
            default: return null;
        }
    }

    public static String timeLeft(Context c, CloudStatus.TimeLeft left) {
        int n = (int) Math.min(Integer.MAX_VALUE, left.count);
        switch (left.unit) {
            case DAYS: return c.getResources().getQuantityString(R.plurals.projects_cloud_days_left, n, n);
            case HOURS: return c.getResources().getQuantityString(R.plurals.projects_cloud_hours_left, n, n);
            case MINUTES: return c.getResources().getQuantityString(R.plurals.projects_cloud_minutes_left, n, n);
            case GRACE_DAYS:
            default: return c.getResources().getQuantityString(R.plurals.projects_cloud_grace_left, n, n);
        }
    }

    // ---- dates and times ----

    /** "26 Ekim 2026" / "26 October 2026". */
    public static String day(Context c, long seconds) {
        return new SimpleDateFormat(turkish(c) ? "d MMMM yyyy" : "d MMMM yyyy", locale(c)).format(new Date(seconds * 1000L));
    }

    /** "14:35" on the phone's clock, 24 hours (the design guide: en-GB). */
    public static String clock(long seconds) {
        return new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date(seconds * 1000L));
    }

    /** "14:35'te" / "at 14:35" when it is within the day ahead, else "26 Ekim'de" / "on 26 October" (resets, returns). */
    public static String at(Context c, long seconds, long nowSeconds) {
        Calendar when = Calendar.getInstance();
        when.setTimeInMillis(seconds * 1000L);
        boolean soon = seconds - nowSeconds < 20 * 3600;
        if (turkish(c)) {
            return soon ? TrGrammar.locativeTime(clock(seconds)) : TrGrammar.locativeDate(when.get(Calendar.DAY_OF_MONTH), when.get(Calendar.MONTH) + 1);
        }
        return soon ? clock(seconds) : new SimpleDateFormat("d MMMM", Locale.UK).format(new Date(seconds * 1000L));
    }

    /** "30 Ekim'e" (TR, for "… kadar") / "30 October" (EN, after "until"). */
    public static String until(Context c, long seconds, long nowSeconds) {
        Calendar when = Calendar.getInstance();
        when.setTimeInMillis(seconds * 1000L);
        boolean soon = seconds - nowSeconds < 20 * 3600;
        if (turkish(c)) {
            return soon ? TrGrammar.dativeTime(clock(seconds)) : TrGrammar.dativeDate(when.get(Calendar.DAY_OF_MONTH), when.get(Calendar.MONTH) + 1);
        }
        return soon ? clock(seconds) : new SimpleDateFormat("d MMMM", Locale.UK).format(new Date(seconds * 1000L));
    }

    /** An item with its count, as the site writes it: "64 Elmas" / "Diamond ×64"; one alone is just its name. */
    public static String qtyName(Context c, long qty, String name) {
        if (qty <= 1) return name;
        return turkish(c) ? qty + " " + name : name + " ×" + qty;
    }

    /** "bugün", "yarın", "26 Ekim'de" / "today", "tomorrow", "on 26 October". */
    public static String relativeDay(Context c, long seconds, long nowSeconds) {
        long days = dayIndex(seconds) - dayIndex(nowSeconds);
        if (days <= 0) return c.getString(R.string.projects_cloud_today);
        if (days == 1) return c.getString(R.string.projects_cloud_tomorrow);
        Calendar when = Calendar.getInstance();
        when.setTimeInMillis(seconds * 1000L);
        String date = turkish(c) ? TrGrammar.locativeDate(when.get(Calendar.DAY_OF_MONTH), when.get(Calendar.MONTH) + 1)
                : new SimpleDateFormat("d MMMM", Locale.UK).format(new Date(seconds * 1000L));
        return c.getString(R.string.projects_cloud_on_day, date);
    }

    /** A moment that passed: "18:35" today ({@code withDay}: "Bugün 18:35"), "Dün 18:35", else "21 Ekim 18:35". */
    public static String past(Context c, long seconds, long nowSeconds, boolean withDay) {
        long days = dayIndex(nowSeconds) - dayIndex(seconds);
        String time = clock(seconds);
        if (days <= 0) return withDay ? c.getString(R.string.projects_cloud_today_at, time) : time;
        if (days == 1) return c.getString(R.string.projects_cloud_yesterday_at, time);
        return new SimpleDateFormat("d MMMM", locale(c)).format(new Date(seconds * 1000L)) + " " + time;
    }

    private static long dayIndex(long seconds) {
        Calendar day = Calendar.getInstance();
        day.setTimeInMillis(seconds * 1000L);
        return (day.get(Calendar.YEAR) * 400L) + day.get(Calendar.DAY_OF_YEAR);
    }

    /** Turkey's clock now as minutes of the day (daily commands and working hours are Turkey time). */
    public static int turkeyMinutes(long seconds) {
        Calendar now = Calendar.getInstance(TimeZone.getTimeZone("Europe/Istanbul"));
        now.setTimeInMillis(seconds * 1000L);
        return now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
    }

    // ---- amounts ----

    public static String money(Context c, long value) {
        return PlusFormat.money(value, lang(c));
    }

    /** "37.500'e" (TR dative, "37.500'e alındı") / "37,500" (EN). */
    public static String moneyDative(Context c, long value) {
        String written = money(c, value);
        return turkish(c) ? TrGrammar.dative(written, value) : written;
    }

    // ---- problems ----

    /** What went wrong, in one sentence; never a code. */
    public static String problem(Context c, CloudProblem p) {
        switch (p.kind) {
            case SIGNED_OUT: return c.getString(R.string.projects_cloud_error_signed_out);
            case RATE_LIMITED:
                if ("daily".equals(p.reason())) return c.getString(R.string.projects_plus_parse_daily);
                return c.getString(R.string.projects_cloud_error_rate_limited);
            case HOST_OFFLINE: return c.getString(R.string.projects_cloud_error_host_offline);
            case UNAVAILABLE: return c.getString(R.string.projects_cloud_error_unavailable);
            case FAILED:
                return p.getMessage() != null && p.getMessage().startsWith("failed: seal")
                        ? c.getString(R.string.projects_cloud_error_seal) : c.getString(R.string.projects_cloud_failed);
            default:
                break;
        }
        String code = p.code == null ? "" : p.code;
        switch (code) {
            case "service_paused": return c.getString(R.string.projects_cloud_error_paused);
            case "plus_off": return c.getString(R.string.projects_cloud_error_plus_off);
            case "not_running": return c.getString(R.string.projects_cloud_error_not_running);
            case "account_suspended": return c.getString(R.string.projects_cloud_error_suspended);
            case "player_restricted": return c.getString(R.string.projects_cloud_restricted);
            case "account_expired": return c.getString(R.string.projects_cloud_error_expired);
            case "server_blocked": return c.getString(R.string.projects_cloud_issue_blocked);
            case "server_full": return c.getString(R.string.projects_cloud_issue_full);
            case "account_not_found": return c.getString(R.string.projects_cloud_error_gone);
            case "plus_not_active": return c.getString(R.string.projects_cloud_error_plus_not_active);
            case "feature_off": return c.getString(R.string.projects_cloud_error_feature_off);
            case "approval_closed":
            case "approval_not_found": return c.getString(R.string.projects_cloud_error_approval_closed);
            case "not_contacted": return c.getString(R.string.projects_plus_inbox_cannot);
            case "text_rejected": {
                String reason = p.reason();
                if ("length".equals(reason)) return c.getString(R.string.projects_plus_inbox_too_long);
                if ("marker".equals(reason)) return c.getString(R.string.projects_plus_inbox_marker);
                return c.getString(R.string.projects_plus_inbox_rejected);
            }
            case "parse_failed": {
                String reason = p.reason();
                if ("no_item".equals(reason)) return c.getString(R.string.projects_plus_parse_no_item);
                if ("no_amount".equals(reason)) return c.getString(R.string.projects_plus_parse_no_amount);
                if ("many_items".equals(reason)) return c.getString(R.string.projects_plus_parse_many);
                return c.getString(R.string.projects_plus_parse_unsafe);
            }
            case "invalid_plus_config": return c.getString(R.string.projects_cloud_error_invalid);
            case "plus_text_rejected": return judgeText(c, p.reason());
            case "downgrade_limit": return c.getString(R.string.projects_cloud_error_downgrade_limit);
            case "secret_unreadable": return c.getString(R.string.projects_cloud_secret_unreadable);
            case "invalid_command": {
                CloudText.Problem problem = CloudText.Problem.of(p.reason());
                if ("secret".equals(p.reason())) return c.getString(R.string.projects_cloud_issue_secret);
                return problem == null ? c.getString(R.string.projects_cloud_console_send_failed) : textIssue(c, problem.code);
            }
            default:
                return c.getString(R.string.projects_cloud_failed);
        }
    }

    /** A persona text's refusal ({@code plus_text_rejected}'s reason, or a pending judgement's). */
    public static String judgeText(Context c, @Nullable String reason) {
        switch (reason == null ? "" : reason) {
            case "impersonation": return c.getString(R.string.projects_plus_judge_impersonation);
            case "contact": return c.getString(R.string.projects_plus_judge_contact);
            case "redirect": return c.getString(R.string.projects_plus_judge_redirect);
            default: return c.getString(R.string.projects_plus_judge_inappropriate);
        }
    }

    private static String textIssue(Context c, String code) {
        switch (code) {
            case "empty": return c.getString(R.string.projects_cloud_issue_empty);
            case "length": return c.getString(R.string.projects_cloud_issue_length);
            case "space": return c.getString(R.string.projects_cloud_issue_space);
            case "control": return c.getString(R.string.projects_cloud_issue_control);
            case "backslash": return c.getString(R.string.projects_cloud_issue_backslash);
            case "unicode": return c.getString(R.string.projects_cloud_issue_unicode);
            case "variable": return c.getString(R.string.projects_cloud_issue_variable);
            case "secret": return c.getString(R.string.projects_cloud_issue_secret);
            default: return c.getString(R.string.projects_cloud_issue_other);
        }
    }

    /** A settings issue in words (the launcher's {@code settings.issues}). */
    public static String issue(Context c, CloudEdit.Issue issue, CloudModel.Limits limits) {
        String p = issue.problem;
        if (p.startsWith("content/")) return c.getString(R.string.projects_cloud_issue_content, category(c, p.substring("content/".length())));
        // CAFK-FEAT: the placeholder save checks (services/cloud-afk README "Feature errors").
        if (p.startsWith("placeholder_part/")) {
            int part;
            try {
                part = Integer.parseInt(p.substring("placeholder_part/".length()));
            } catch (NumberFormatException e) {
                part = 1;
            }
            return c.getString(R.string.projects_feat_ph_issue_part, part);
        }
        switch (p) {
            case "placeholder_row": return c.getString(R.string.projects_feat_ph_issue_row);
            case "placeholder_part": return c.getString(R.string.projects_feat_ph_issue_part, 1);
            case "placeholder_sender": return c.getString(R.string.projects_feat_ph_issue_sender);
            case "placeholder_start": return c.getString(R.string.projects_feat_ph_issue_start);
            case "placeholder_command": return c.getString(R.string.projects_feat_ph_issue_command);
            case "placeholder_length": return c.getString(R.string.projects_feat_ph_issue_length);
            default: break;
        }
        // Movement turned on: switched off meanwhile ("off"), or no slot for the player ("player/<max>") or on the machine.
        if ("movement".equals(issue.field)) {
            if ("off".equals(p)) return c.getString(R.string.projects_feat_move_service_off);
            if (p.startsWith("player/")) {
                long max;
                try {
                    max = Long.parseLong(p.substring("player/".length()));
                } catch (NumberFormatException e) {
                    max = 1;
                }
                return gg.projects.mobile.cloud.movement.Movement.slot(c, "player", max);
            }
            return gg.projects.mobile.cloud.movement.Movement.slot(c, "host", 0);
        }
        switch (issue.field) {
            case "delay":
                return c.getString(R.string.projects_cloud_issue_delay);
            case "every":
                return "order".equals(p) ? c.getString(R.string.projects_cloud_issue_order)
                        : c.getString(R.string.projects_cloud_issue_every, limits.minIntervalSec);
            case "cooldown":
                return c.getString(R.string.projects_cloud_issue_every, limits.minRuleCooldownSec);
            case "antiAfk":
                return c.getString(R.string.projects_cloud_issue_anti_afk);
            case "at":
                switch (p) {
                    case "empty": return c.getString(R.string.projects_cloud_issue_times_empty);
                    case "many": return c.getString(R.string.projects_cloud_issue_times_many);
                    case "duplicate": return c.getString(R.string.projects_cloud_issue_times_duplicate);
                    default: return c.getString(R.string.projects_cloud_issue_times_format);
                }
            case "ownerOnly":
                return "server".equals(p) ? c.getString(R.string.projects_cloud_issue_owner_server) : c.getString(R.string.projects_cloud_issue_owner);
            case "owner":
            case "name":
                if ("duplicate".equals(p)) return c.getString(R.string.projects_cloud_issue_name_duplicate);
                if ("protected".equals(p)) return c.getString(R.string.projects_cloud_issue_name_protected);
                return c.getString(R.string.projects_cloud_issue_name);
            case "server":
                switch (p) {
                    case "dot": return c.getString(R.string.projects_cloud_issue_host_dot);
                    case "private": return c.getString(R.string.projects_cloud_issue_host_private);
                    case "blocked": return c.getString(R.string.projects_cloud_issue_blocked);
                    case "full": return c.getString(R.string.projects_cloud_issue_full);
                    case "changeLimit": return c.getString(R.string.projects_cloud_issue_change_limit);
                    case "protected": return c.getString(R.string.projects_cloud_issue_name_protected);
                    default: return c.getString(R.string.projects_cloud_issue_host);
                }
            case "port":
                return c.getString(R.string.projects_cloud_issue_port);
            case "version":
                return c.getString(R.string.projects_cloud_issue_version);
            case "count":
                return c.getString(R.string.projects_cloud_issue_limit);
            case "match":
                switch (p) {
                    case "empty": return c.getString(R.string.projects_cloud_issue_empty);
                    case "tooLong": return c.getString(R.string.projects_cloud_issue_length);
                    case "tooComplex": return c.getString(R.string.projects_cloud_issue_too_complex);
                    default: return c.getString(R.string.projects_cloud_issue_invalid);
                }
            default:
                break;
        }
        if ("reseal".equals(p) || "keep".equals(p)) return c.getString(R.string.projects_cloud_secret_reseal);
        if ("order".equals(p)) return c.getString(R.string.projects_cloud_issue_order);
        if ("range".equals(p)) return c.getString(R.string.projects_cloud_issue_range);
        CloudText.Problem text = CloudText.Problem.of(p);
        if (text != null || "secret".equals(p)) return textIssue(c, p);
        return c.getString(R.string.projects_cloud_issue_other);
    }

    public static String category(Context c, @Nullable String key) {
        switch (key == null ? "other" : key) {
            case "advertising": return c.getString(R.string.projects_cloud_category_advertising);
            case "spam": return c.getString(R.string.projects_cloud_category_spam);
            case "scam": return c.getString(R.string.projects_cloud_category_scam);
            case "rmt": return c.getString(R.string.projects_cloud_category_rmt);
            case "impersonation": return c.getString(R.string.projects_cloud_category_impersonation);
            case "harassment": return c.getString(R.string.projects_cloud_category_harassment);
            case "hate": return c.getString(R.string.projects_cloud_category_hate);
            case "sexual": return c.getString(R.string.projects_cloud_category_sexual);
            case "minor_safety": return c.getString(R.string.projects_cloud_category_minor_safety);
            case "personal_info": return c.getString(R.string.projects_cloud_category_personal_info);
            case "off_platform": return c.getString(R.string.projects_cloud_category_off_platform);
            case "evasion": return c.getString(R.string.projects_cloud_category_evasion);
            default: return c.getString(R.string.projects_cloud_category_other);
        }
    }

    /** The account's moderation line ("… için uyarı: reklam. …"), or null. */
    @Nullable
    public static String moderation(Context c, CloudModel.Account account) {
        CloudModel.Moderation m = account.moderation;
        if (m == null) return null;
        String category = category(c, m.category);
        if ("overturned".equals(m.appealState)) return c.getString(R.string.projects_cloud_mod_overturned, account.name);
        if ("open".equals(m.appealState)) return c.getString(R.string.projects_cloud_mod_appeal_received);
        if ("upheld".equals(m.appealState)) return c.getString(R.string.projects_cloud_mod_upheld);
        switch (m.step) {
            case "restrict_rows": return c.getString(R.string.projects_cloud_mod_rows, account.name, category);
            case "pause_ai": return c.getString(R.string.projects_cloud_mod_ai, account.name, category);
            case "suspend": return c.getString(R.string.projects_cloud_mod_suspend, account.name, category);
            case "warn":
            default: return c.getString(R.string.projects_cloud_mod_warn, account.name, category);
        }
    }

    // ---- the assistant ----

    /** The assistant's state in one short sentence. */
    public static String ai(Context c, @Nullable CloudModel.AiView view, long nowSeconds) {
        PlusFormat.Ai ai = PlusFormat.ai(view);
        Long until = view == null ? null : view.until;
        switch (ai) {
            case ON: return c.getString(R.string.projects_plus_ai_on);
            case ON_NOTIFY_ONLY: return c.getString(R.string.projects_plus_ai_notify_only);
            case PAUSED_BREAK:
                return until == null ? c.getString(R.string.projects_plus_ai_break_plain)
                        : c.getString(R.string.projects_plus_ai_break, until(c, until, nowSeconds));
            case PAUSED_SCHEDULE:
                return until == null ? c.getString(R.string.projects_plus_ai_schedule_plain)
                        : c.getString(R.string.projects_plus_ai_schedule, at(c, until, nowSeconds));
            case PAUSED_QUOTA: {
                Long back = until != null ? until : view != null && view.quota != null ? view.quota.exhaustedUntil : null;
                return back == null ? c.getString(R.string.projects_plus_ai_quota_plain)
                        : c.getString(R.string.projects_plus_ai_quota, at(c, back, nowSeconds));
            }
            case PAUSED_BUDGET: return c.getString(R.string.projects_plus_ai_budget);
            case PAUSED_OUTAGE: return c.getString(R.string.projects_plus_ai_outage);
            case PAUSED_HOST: return c.getString(R.string.projects_plus_ai_host);
            case PAUSED_SERVICE: return c.getString(R.string.projects_plus_ai_service);
            case PAUSED: return c.getString(R.string.projects_plus_ai_paused);
            case OFF_PLAYER: return c.getString(R.string.projects_plus_ai_player);
            case OFF_STAFF: return c.getString(R.string.projects_plus_ai_staff);
            case OFF_RESTRICTED: return c.getString(R.string.projects_plus_ai_restricted);
            case OFF_SERVICE: return c.getString(R.string.projects_plus_ai_service_off);
            case OFF_SERVER: return c.getString(R.string.projects_plus_ai_server);
            case OFF_GATE: return c.getString(R.string.projects_plus_ai_gate);
            case OFF_MUTED: return c.getString(R.string.projects_plus_ai_muted);
            case OFF_MODERATION: return c.getString(R.string.projects_plus_ai_moderation);
            case OFF: return c.getString(R.string.projects_plus_ai_off);
            case UNKNOWN:
            default: return c.getString(R.string.projects_plus_ai_unknown);
        }
    }

    public static String why(Context c, String reason) {
        switch (reason) {
            case "new_counterpart": return c.getString(R.string.projects_plus_why_new);
            case "always": return c.getString(R.string.projects_plus_why_always);
            case "price_unusual": return c.getString(R.string.projects_plus_why_price);
            case "budget": return c.getString(R.string.projects_plus_why_budget);
            case "daily": return c.getString(R.string.projects_plus_why_daily);
            case "above":
            default: return c.getString(R.string.projects_plus_why_above);
        }
    }

    /** A history change in words ("Elytra en fazla 40.000 yerine 45.000"). */
    public static String change(Context c, PlusFormat.Change change, CloudService service) {
        String lang = lang(c);
        String item = change.item == null ? "" : service.itemName(change.item, lang);
        switch (change.kind) {
            case AI_ON: return c.getString(R.string.projects_plus_change_ai_on);
            case AI_OFF: return c.getString(R.string.projects_plus_change_ai_off);
            case ROW_ADDED: return c.getString(change.buy ? R.string.projects_plus_change_added_buy : R.string.projects_plus_change_added_sell, item);
            case ROW_REMOVED: return c.getString(R.string.projects_plus_change_removed, item);
            case ROW_BOUND:
                return c.getString(change.buy ? R.string.projects_plus_change_bound_buy : R.string.projects_plus_change_bound_sell, item,
                        money(c, change.before == null ? 0 : change.before), money(c, change.after == null ? 0 : change.after));
            case ROW_TARGET:
                return c.getString(R.string.projects_plus_change_target, item, money(c, change.before == null ? 0 : change.before),
                        money(c, change.after == null ? 0 : change.after));
            case ROW_QTY:
                return c.getString(R.string.projects_plus_change_qty, item, String.valueOf(change.before), String.valueOf(change.after));
            case ROW_ON: return c.getString(R.string.projects_plus_change_on, item);
            case ROW_OFF: return c.getString(R.string.projects_plus_change_off, item);
            case ROW_OTHER: return c.getString(R.string.projects_plus_change_row, item);
            case LIMIT: {
                String label = limitLabel(c, change.field == null ? "" : change.field);
                if (change.after == null) return c.getString(R.string.projects_plus_change_limit_removed, label);
                String after = "approvalTimeoutMin".equals(change.field) ? change.after + " " + c.getString(R.string.projects_plus_limit_minutes)
                        : money(c, change.after);
                if (change.before == null) return c.getString(R.string.projects_plus_change_limit_set, label, after);
                String before = "approvalTimeoutMin".equals(change.field) ? change.before + " " + c.getString(R.string.projects_plus_limit_minutes)
                        : money(c, change.before);
                return c.getString(R.string.projects_plus_change_limit, label, before, after);
            }
            case SCHEDULE_ON: return c.getString(R.string.projects_plus_change_schedule_on);
            case SCHEDULE_OFF: return c.getString(R.string.projects_plus_change_schedule_off);
            case SCHEDULE_HOURS: return c.getString(R.string.projects_plus_change_schedule_hours);
            case BREAK_ON: return c.getString(R.string.projects_plus_change_break_on);
            case BREAK_OFF: return c.getString(R.string.projects_plus_change_break_off);
            case PERSONA: return c.getString(R.string.projects_plus_change_persona);
            case REPLIES: return c.getString(R.string.projects_plus_change_replies);
            case BLOCKED: return c.getString(R.string.projects_plus_change_blocked);
            case OTHER:
            default: return c.getString(R.string.projects_plus_change_other);
        }
    }

    public static String limitLabel(Context c, String key) {
        switch (key) {
            case "perDealMax": return c.getString(R.string.projects_plus_limit_perDealMax);
            case "dailySpendMax": return c.getString(R.string.projects_plus_limit_dailySpendMax);
            case "totalBudget": return c.getString(R.string.projects_plus_limit_totalBudget);
            case "balanceFloor": return c.getString(R.string.projects_plus_limit_balanceFloor);
            case "approvalAbove": return c.getString(R.string.projects_plus_limit_approvalAbove);
            case "newCounterpartApprovalAbove": return c.getString(R.string.projects_plus_limit_newCounterpartApprovalAbove);
            case "approvalTimeoutMin": return c.getString(R.string.projects_plus_limit_approvalTimeoutMin);
            default: return key;
        }
    }

    // ---- notices ----

    /** A notice's title and text in the app's language; null when the notice says nothing this app words. */
    @Nullable
    public static String[] notice(Context c, CloudNotice n, CloudPrefs prefs, long nowSeconds) {
        String lang = lang(c);
        String name = n.name() == null ? c.getString(R.string.projects_cloud_title) : n.name();
        String item = n.get("item") == null ? "" : prefs.itemName(n.get("item"), lang);
        long qty = n.number("qty", 1);
        long price = n.number("price", 0);
        String title = c.getString(R.string.projects_cloud_title);
        switch (n.event) {
            case "stopped":
                return new String[]{title, c.getString(R.string.projects_cloud_notice_stopped, name, causeText(c, n.get("cause")))};
            case "renewal": {
                int days = (int) Math.max(1, n.number("days", 1));
                return new String[]{title, c.getResources().getQuantityString(R.plurals.projects_cloud_notice_renewal, days, name, days)};
            }
            case "expired":
                return new String[]{title, c.getString(R.string.projects_cloud_notice_expired, name)};
            case "found":
                return new String[]{c.getString(R.string.projects_plus_title), c.getString(R.string.projects_cloud_notice_found, name, item, money(c, price))};
            case "approval":
                return new String[]{c.getString(R.string.projects_cloud_notice_approval, name),
                        c.getString(R.string.projects_cloud_notice_deal_line, String.valueOf(qty), item, money(c, price))};
            case "deal_done":
                return new String[]{c.getString(R.string.projects_plus_title), c.getString("sell".equals(n.get("side"))
                        ? R.string.projects_cloud_notice_sold : R.string.projects_cloud_notice_bought, name, String.valueOf(qty), item, moneyDative(c, price))};
            case "deal_failed":
                return new String[]{c.getString(R.string.projects_plus_title), c.getString(R.string.projects_cloud_notice_deal_failed, name, item)};
            case "quota": {
                String which = n.get("which");
                if ("80".equals(which)) return new String[]{c.getString(R.string.projects_plus_title), c.getString(R.string.projects_cloud_notice_quota_80, name)};
                if ("gift".equals(which)) {
                    long expires = n.number("expires", 0);
                    return new String[]{c.getString(R.string.projects_plus_title), c.getString(R.string.projects_cloud_notice_gift_ending,
                            expires > 0 ? relativeDay(c, expires, nowSeconds) : c.getString(R.string.projects_cloud_today))};
                }
                long reset = n.number("resetAt", 0);
                return new String[]{c.getString(R.string.projects_plus_title), reset > 0
                        ? c.getString(R.string.projects_cloud_notice_quota_full, at(c, reset, nowSeconds))
                        : c.getString(R.string.projects_cloud_notice_quota_full_plain)};
            }
            case "summary":
                return new String[]{c.getString(R.string.projects_plus_title), c.getString(R.string.projects_cloud_notice_summary, name,
                        (int) n.number("pms", 0), (int) n.number("deals", 0))};
            case "paused": {
                String reason = n.get("reason");
                if ("service".equals(reason)) return new String[]{c.getString(R.string.projects_plus_title), c.getString(R.string.projects_cloud_notice_paused_service)};
                if ("outage".equals(reason)) return new String[]{c.getString(R.string.projects_plus_title), c.getString(R.string.projects_cloud_notice_paused_outage, name)};
                return new String[]{c.getString(R.string.projects_plus_title), c.getString(R.string.projects_cloud_notice_paused_staff, name)};
            }
            case "incident":
                return new String[]{c.getString(R.string.projects_plus_title), c.getString(R.string.projects_cloud_notice_incident, name)};
            case "message": {
                int count = (int) n.number("count", 1);
                String text = count > 1 ? c.getResources().getQuantityString(R.plurals.projects_cloud_notice_messages, count, name, count)
                        : c.getString(R.string.projects_cloud_notice_message, name);
                return new String[]{c.getString(R.string.projects_plus_messages), text};
            }
            case "weekly": {
                int deals = (int) n.number("deals", 0);
                return new String[]{c.getString(R.string.projects_plus_title), c.getResources().getQuantityString(R.plurals.projects_cloud_notice_weekly, deals, name, deals)};
            }
            case "night":
                return new String[]{c.getString(R.string.projects_cloud_notice_night), nightText(c, n)};
            case "moderation": {
                String step = n.get("step") == null ? "warn" : n.get("step");
                String category = category(c, n.get("category"));
                String text;
                switch (step) {
                    case "restrict_rows": text = c.getString(R.string.projects_cloud_mod_rows, name, category); break;
                    case "pause_ai": text = c.getString(R.string.projects_cloud_mod_ai, name, category); break;
                    case "suspend": text = c.getString(R.string.projects_cloud_mod_suspend, name, category); break;
                    case "appeal_received": text = c.getString(R.string.projects_cloud_mod_appeal_received); break;
                    case "upheld": text = c.getString(R.string.projects_cloud_mod_upheld); break;
                    case "overturned": text = c.getString(R.string.projects_cloud_mod_overturned, name); break;
                    default: text = c.getString(R.string.projects_cloud_mod_warn, name, category); break;
                }
                return new String[]{title, text};
            }
            default:
                return null;
        }
    }

    private static String nightText(Context c, CloudNotice n) {
        StringBuilder out = new StringBuilder();
        int other = 0;
        for (java.util.Map.Entry<String, Integer> e : n.counts().entrySet()) {
            int count = e.getValue();
            String part;
            switch (e.getKey()) {
                case "deal_done": part = c.getResources().getQuantityString(R.plurals.projects_cloud_night_deals, count, count); break;
                case "found": part = c.getResources().getQuantityString(R.plurals.projects_cloud_night_found, count, count); break;
                case "stopped": part = c.getResources().getQuantityString(R.plurals.projects_cloud_night_stopped, count, count); break;
                case "message": part = c.getResources().getQuantityString(R.plurals.projects_cloud_night_messages, count, count); break;
                default: other += count; continue;
            }
            if (out.length() > 0) out.append(", ");
            out.append(part);
        }
        if (other > 0) {
            if (out.length() > 0) out.append(", ");
            out.append(c.getResources().getQuantityString(R.plurals.projects_cloud_night_other, other, other));
        }
        return out.toString();
    }

    /** "Pzt Sal Çar" for a working-hours window's days (1 Monday … 7 Sunday). */
    public static String days(Context c, List<Integer> days) {
        StringBuilder out = new StringBuilder();
        int[] ids = {R.string.projects_plus_day_1, R.string.projects_plus_day_2, R.string.projects_plus_day_3, R.string.projects_plus_day_4,
                R.string.projects_plus_day_5, R.string.projects_plus_day_6, R.string.projects_plus_day_7};
        for (int d = 1; d <= 7; d++) {
            if (!days.contains(d)) continue;
            if (out.length() > 0) out.append(' ');
            out.append(c.getString(ids[d - 1]));
        }
        return out.toString();
    }
}
