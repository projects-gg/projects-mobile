package gg.projects.mobile.cloud;

import androidx.annotation.Nullable;

import com.google.gson.JsonObject;

import gg.projects.mobile.social.Json;

/**
 * What went wrong with a request to 7/24 Bulut (services/cloud-afk README "Errors", "AFK+ errors"): the HTTP status,
 * the service's code and its extra fields ({@code field}, {@code reason}, {@code problem}, {@code until},
 * {@code rev}...). Codes are for the program: the screens word them ({@code CloudWords}) and never show them. A
 * Cloudflare page without the envelope counts as the service being away.
 */
public final class CloudProblem extends Exception {
    public enum Kind {
        /** No connection, a timeout, 5xx, 503 {@code unavailable} or a platform page. */
        UNAVAILABLE,
        /** 401: the friends session is gone (sign in again). */
        SIGNED_OUT,
        /** 429. */
        RATE_LIMITED,
        /** 503 {@code host_offline}: the account's machine is not connected now. */
        HOST_OFFLINE,
        /** 412 {@code rev_conflict}: the account (or its AFK+ settings) changed elsewhere. */
        CONFLICT,
        /** Any other answer the service gave on purpose (4xx with its code). */
        REFUSED,
        /** Something the phone itself could not do (sealing, a malformed answer). */
        FAILED
    }

    public final Kind kind;
    public final int status;
    /** The service's code (for the log and the wording), null without one. */
    @Nullable
    public final String code;
    /** Seconds to wait ({@code retryAfter} / {@code Retry-After}), or 0. */
    public final long retryAfter;
    /** The envelope's error object with its extra fields (never shown). */
    public final JsonObject error;

    public CloudProblem(Kind kind, int status, @Nullable String code, long retryAfter, @Nullable JsonObject error) {
        this(kind, status, code, retryAfter, error, kind + (code == null ? "" : " " + code) + (status > 0 ? " (" + status + ")" : ""));
    }

    private CloudProblem(Kind kind, int status, @Nullable String code, long retryAfter, @Nullable JsonObject error, String message) {
        super(message);
        this.kind = kind;
        this.status = status;
        this.code = code;
        this.retryAfter = retryAfter;
        this.error = error == null ? new JsonObject() : error;
    }

    /** No answer (a connection failure, a timeout); {@code why} only goes to the log. */
    public static CloudProblem unavailable(String why) {
        return new CloudProblem(Kind.UNAVAILABLE, 0, null, 0, null, "unavailable: " + why);
    }

    /** Something on the phone's side; {@code why} only goes to the log. */
    public static CloudProblem failed(String why) {
        return new CloudProblem(Kind.FAILED, 0, null, 0, null, "failed: " + why);
    }

    /** From an answer that was not a success: its status, body and {@code Retry-After}. */
    public static CloudProblem of(int status, @Nullable String body, @Nullable String retryAfterHeader) {
        JsonObject envelope = Json.parseObject(body);
        JsonObject error = Json.obj(envelope, "error");
        String code = Json.str(error, "code");
        long retry = Json.num(error, "retryAfter", 0);
        if (retry <= 0 && retryAfterHeader != null) {
            try {
                retry = Long.parseLong(retryAfterHeader.trim());
            } catch (NumberFormatException ignored) {
                retry = 0;
            }
        }
        Kind kind;
        if (code == null) {
            // A platform page (Cloudflare's own error) or an unexpected body: treated like 503 (README).
            kind = status == 401 ? Kind.SIGNED_OUT : status == 429 ? Kind.RATE_LIMITED : Kind.UNAVAILABLE;
        } else if (status == 401) {
            kind = Kind.SIGNED_OUT;
        } else if (status == 429) {
            kind = Kind.RATE_LIMITED;
        } else if ("host_offline".equals(code)) {
            kind = Kind.HOST_OFFLINE;
        } else if (status == 412 || "rev_conflict".equals(code)) {
            kind = Kind.CONFLICT;
        } else if (status >= 500) {
            kind = Kind.UNAVAILABLE;
        } else {
            kind = Kind.REFUSED;
        }
        return new CloudProblem(kind, status, code, Math.max(0, retry), error);
    }

    public boolean is(String code) {
        return code.equals(this.code);
    }

    @Nullable
    public String field() {
        return Json.str(error, "field");
    }

    @Nullable
    public String reason() {
        return Json.str(error, "reason");
    }

    /** {@code invalid_pattern}'s and {@code invalid_plus_config}'s {@code problem}. */
    @Nullable
    public String problem() {
        return Json.str(error, "problem");
    }

    /** A time the answer names ({@code until}), null when none or {@code null}. */
    @Nullable
    public Long until() {
        return Json.numOrNull(error, "until");
    }

    /** A number the answer names ({@code rev}, {@code aiRev}, {@code cap}, {@code max}...), or the fallback. */
    public long number(String key, long fallback) {
        return Json.num(error, key, fallback);
    }

    @Nullable
    public String string(String key) {
        return Json.str(error, key);
    }

    /** The error's object under {@code key} (a blocked server's {@code reason: {tr, en}}). */
    @Nullable
    public JsonObject object(String key) {
        return Json.obj(error, key);
    }

    /** The service paused or switched off ({@code service_paused}, {@code plus_off}). */
    public boolean paused() {
        return is("service_paused") || is("plus_off");
    }
}
