package gg.projects.mobile.afk.core;

import java.util.Collections;
import java.util.List;

import gg.projects.mobile.afk.api.AfkReason;

/** How a connection ended (the wire's report to the engine). Messages are one line, masked by the engine. */
public final class AfkDrop {
    public enum Kind {
        /** The server kicked the account (in game or while configuring). */
        KICKED(AfkReason.KICKED),
        /** The server refused the login (its login-stage disconnect). */
        LOGIN_REJECTED(AfkReason.LOGIN_REJECTED),
        /** The connection broke or timed out in game. */
        CONNECTION_LOST(AfkReason.CONNECTION_LOST),
        /** Closed during the login or the configuration without a reason. */
        DISCONNECTED(AfkReason.DISCONNECTED),
        /** No answer or refused (the connect itself failed). */
        UNREACHABLE(AfkReason.UNREACHABLE),
        /** The address does not resolve. */
        ADDRESS_NOT_FOUND(AfkReason.ADDRESS_NOT_FOUND),
        /** The server asked for encryption with authentication: Microsoft accounts only. */
        PREMIUM_ONLY(AfkReason.PREMIUM_ONLY),
        /** The server does not take the phone client's version. */
        UNSUPPORTED_VERSION(AfkReason.UNSUPPORTED_VERSION),
        /** Something failed inside the app. */
        ERROR(AfkReason.ERROR);

        public final AfkReason reason;

        Kind(AfkReason reason) {
            this.reason = reason;
        }
    }

    public final Kind kind;
    /** The server's words as shown (the app's language for vanilla keys), or null. */
    public final String message;
    /** The same in English (kick causes are matched in English and Turkish), or null. */
    public final String english;
    /** Vanilla translation keys in the reason (may be empty). */
    public final List<String> keys;

    public AfkDrop(Kind kind, String message, String english, List<String> keys) {
        this.kind = kind;
        this.message = blankToNull(message);
        this.english = blankToNull(english);
        this.keys = keys == null ? Collections.<String>emptyList() : keys;
    }

    public static AfkDrop of(Kind kind) {
        return new AfkDrop(kind, null, null, null);
    }

    public AfkDrop masked(String message, String english) {
        return new AfkDrop(kind, message, english, keys);
    }

    private static String blankToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s;
    }

    @Override
    public String toString() {
        return kind + (keys.isEmpty() ? "" : " " + keys);
    }
}
