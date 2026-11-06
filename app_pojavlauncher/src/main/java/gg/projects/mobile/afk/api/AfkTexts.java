package gg.projects.mobile.afk.api;

import android.content.Context;

import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.R;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import gg.projects.mobile.cloud.TrGrammar;
import gg.projects.mobile.language.LanguageManager;

/**
 * PHONE-AFK's words for the screens and the notifications ({@code projects_afk_core_strings.xml}, Turkish and
 * English): pass a context in the app's language (an activity's, or {@code LanguageManager.wrap}). Short, no codes.
 */
public final class AfkTexts {
    private AfkTexts() {}

    /** Why an account dropped, waits or stopped ("" for none). */
    public static String reason(Context c, @Nullable AfkReason reason) {
        if (reason == null) return "";
        switch (reason) {
            case KICKED: return c.getString(R.string.projects_afk_reason_kicked);
            case LOGIN_REJECTED: return c.getString(R.string.projects_afk_reason_login_rejected);
            case CONNECTION_LOST: return c.getString(R.string.projects_afk_reason_connection_lost);
            case UNREACHABLE: return c.getString(R.string.projects_afk_reason_unreachable);
            case ADDRESS_NOT_FOUND: return c.getString(R.string.projects_afk_reason_address_not_found);
            case NO_NETWORK: return c.getString(R.string.projects_afk_reason_no_network);
            case WAITING_WIFI: return c.getString(R.string.projects_afk_reason_waiting_wifi);
            case ERROR: return c.getString(R.string.projects_afk_reason_error);
            case BANNED: return c.getString(R.string.projects_afk_reason_banned);
            case NOT_WHITELISTED: return c.getString(R.string.projects_afk_reason_not_whitelisted);
            case WRONG_PASSWORD: return c.getString(R.string.projects_afk_reason_wrong_password);
            case PREMIUM_ONLY: return c.getString(R.string.projects_afk_reason_premium_only);
            case LOGGED_IN_ELSEWHERE: return c.getString(R.string.projects_afk_reason_logged_in_elsewhere);
            case ALREADY_CONNECTED: return c.getString(R.string.projects_afk_reason_already_connected);
            case LOGIN_TIMEOUT: return c.getString(R.string.projects_afk_reason_login_timeout);
            case UNSUPPORTED_VERSION: return c.getString(R.string.projects_afk_reason_unsupported_version);
            case BATTERY_LOW: return c.getString(R.string.projects_afk_reason_battery_low);
            case TOO_HOT: return c.getString(R.string.projects_afk_reason_too_hot);
            case PROCESS_ENDED: return c.getString(R.string.projects_afk_reason_process_ended);
            case APP_UPDATED: return c.getString(R.string.projects_afk_reason_app_updated);
            case DISCONNECTED:
            default:
                return c.getString(R.string.projects_afk_reason_disconnected);
        }
    }

    /** The one-line text of a draft problem. */
    public static String problem(Context c, AfkProblem problem) {
        switch (problem) {
            case NAME_INVALID: return c.getString(R.string.projects_afk_problem_name_invalid);
            case NAME_TAKEN: return c.getString(R.string.projects_afk_problem_name_taken);
            case ADDRESS_MISSING: return c.getString(R.string.projects_afk_problem_address_missing);
            case ADDRESS_INVALID: return c.getString(R.string.projects_afk_problem_address_invalid);
            case PORT_INVALID: return c.getString(R.string.projects_afk_problem_port_invalid);
            case LOCAL_ADDRESS: return c.getString(R.string.projects_afk_problem_local_address);
            case TOO_MANY_COMMANDS: return c.getString(R.string.projects_afk_problem_too_many_commands);
            case COMMAND_TOO_LONG: return c.getString(R.string.projects_afk_problem_command_too_long);
            case TOO_MANY_ACCOUNTS: return c.getString(R.string.projects_afk_problem_too_many_accounts);
            case NOT_SAVED:
            default:
                return c.getString(R.string.projects_afk_problem_not_saved);
        }
    }

    /** What a start answered ("Başlatılıyor", "Aynı anda en çok 3 hesap AFK'da kalabilir."). */
    public static String start(Context c, AfkStartResult result) {
        switch (result) {
            case STARTED: return c.getString(R.string.projects_afk_start_started);
            case ALREADY_RUNNING: return c.getString(R.string.projects_afk_start_already_running);
            case LIMIT_REACHED: return c.getString(R.string.projects_afk_start_limit_reached);
            case NOT_AVAILABLE: return c.getString(R.string.projects_afk_start_not_available);
            case NO_ACCOUNT: return c.getString(R.string.projects_afk_start_no_account);
            case NOT_ALLOWED:
            default:
                return c.getString(R.string.projects_afk_start_not_allowed);
        }
    }

    /** What a send answered. */
    public static String send(Context c, AfkSendResult result) {
        switch (result) {
            case SENT: return c.getString(R.string.projects_afk_send_sent);
            case EMPTY: return c.getString(R.string.projects_afk_send_empty);
            case TOO_LONG: return c.getString(R.string.projects_afk_send_too_long);
            case TOO_FAST: return c.getString(R.string.projects_afk_send_too_fast);
            case NOT_IN_GAME: return c.getString(R.string.projects_afk_send_not_in_game);
            case NOT_RUNNING:
            default:
                return c.getString(R.string.projects_afk_send_not_running);
        }
    }

    /**
     * An account's state line: "Oyunda", "Bağlanıyor", "45 saniye sonra yeniden bağlanacak", "Wi-Fi bekleniyor", a stop's
     * reason ("Sunucudan yasaklandı") or "Durduruldu". {@code now}: wall-clock ms.
     */
    public static String state(Context c, AfkStatus s, long now) {
        switch (s.state) {
            case IN_GAME:
                return c.getString(R.string.projects_afk_state_in_game);
            case WAITING:
                if (s.nextAttemptAt > now) {
                    return c.getString(R.string.projects_afk_state_waiting, duration(c, s.nextAttemptAt - now));
                }
                return c.getString(R.string.projects_afk_state_reconnecting);
            case NO_NETWORK:
                return reason(c, s.reason == null ? AfkReason.NO_NETWORK : s.reason);
            case STOPPED:
                return s.reason != null ? reason(c, s.reason) : c.getString(R.string.projects_afk_state_stopped);
            case CONNECTING:
            case LOGGING_IN:
            case CONFIGURING:
            default:
                return s.attempt > 0 ? c.getString(R.string.projects_afk_state_reconnecting)
                        : c.getString(R.string.projects_afk_state_connecting);
        }
    }

    /** A wait in words, rounded up: "45 saniye", "4 dakika" ("45 s", "4 min"). */
    public static String duration(Context c, long ms) {
        long seconds = Math.max(1, (ms + 999) / 1000);
        if (seconds < 60) return c.getString(R.string.projects_afk_seconds, (int) seconds);
        return c.getString(R.string.projects_afk_minutes, (int) ((seconds + 59) / 60));
    }

    /** A clock time to put after "at": Turkish with its ending ("03:12'de"), English as the phone shows times. */
    public static String timeAt(Context c, long wallMs) {
        Date date = new Date(wallMs);
        if (LanguageManager.isTurkish(c)) {
            return TrGrammar.locativeTime(new SimpleDateFormat("HH:mm", Locale.ROOT).format(date));
        }
        return android.text.format.DateFormat.getTimeFormat(c).format(date);
    }

    /** The morning sentence about an {@link AfkEnd} ("Telefonun AFK'yı 03:12'de kapattı."). */
    public static String end(Context c, AfkEnd end) {
        String at = timeAt(c, end.at);
        switch (end.kind) {
            case CLOSED_BY_PHONE: return c.getString(R.string.projects_afk_end_closed_by_phone, at);
            case LOW_MEMORY: return c.getString(R.string.projects_afk_end_low_memory, at);
            case CRASHED: return c.getString(R.string.projects_afk_end_crashed, at);
            case APP_UPDATED: return c.getString(R.string.projects_afk_end_app_updated, at);
            case STOPPED_BY_PLAYER:
            case UNKNOWN:
            default:
                return c.getString(R.string.projects_afk_end_unknown, at);
        }
    }
}
