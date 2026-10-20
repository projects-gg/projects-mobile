package gg.projects.mobile.social;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * One presence WebSocket ({@code GET /v1/presence}; README "Presence WebSocket"). OkHttp sends the
 * protocol pings ({@link SocialApi}'s client: every 45 s, free for the service and answered without
 * waking the hub) and fails the socket when a pong does not come. Nothing else is sent on a timer:
 * only the player's own crew call messages, each with a {@code rid} whose {@code ack} or
 * {@code error} comes back to its callback (10 s at most). Every callback runs on the main thread.
 */
public final class PresenceConnection {
    private static final String TAG = "ProjectsSocial";
    private static final long REPLY_TIMEOUT_MS = 10_000;

    public interface Listener {
        void onOpen(PresenceConnection connection);

        void onMessage(PresenceConnection connection, JsonObject message);

        /** The socket ended: {@code code} is the close code (0 when the connection failed). */
        void onEnded(PresenceConnection connection, int code, boolean failed);
    }

    public interface Reply {
        /** {@code error} null for an {@code ack}; else the socket's error code ({@code retryAfter} in seconds). */
        void onReply(@Nullable String error, long retryAfter);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final Listener mListener;
    private final WebSocket mSocket;
    private final Map<Integer, Reply> mReplies = new HashMap<>();
    private int mNextRid = 1;
    private boolean mEnded;
    /** A refused upgrade's Retry-After (seconds; 0 when none). */
    private volatile long mRetryAfter;

    private PresenceConnection(SocialApi api, String token, Listener listener) {
        mListener = listener;
        Request request = new Request.Builder()
                .url(api.base() + "v1/presence")
                .header("Authorization", "Bearer " + token)
                .header("User-Agent", api.userAgent())
                .build();
        mSocket = api.client().newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                MAIN.post(() -> {
                    if (!mEnded) mListener.onOpen(PresenceConnection.this);
                });
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                final JsonObject message = Json.parseObject(text);
                if (message == null) return;
                MAIN.post(() -> deliver(message));
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                webSocket.close(1000, null);
                MAIN.post(() -> end(code, false));
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                MAIN.post(() -> end(code, false));
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, @Nullable Response response) {
                int status = response == null ? 0 : response.code();
                if (response != null && (status == 429 || status == 503)) {
                    try {
                        String header = response.header("Retry-After");
                        mRetryAfter = header == null ? 0 : Math.max(0, Math.min(3600, Long.parseLong(header.trim())));
                    } catch (NumberFormatException e) {
                        mRetryAfter = 0;
                    }
                }
                if (!mEnded) Log.i(TAG, "presence socket failed: " + t.getClass().getSimpleName() + (status == 0 ? "" : " HTTP " + status));
                // A refused upgrade with 401 is a session that ended, like close code 4001.
                MAIN.post(() -> end(status == 401 ? 4001 : 0, true));
            }
        });
    }

    @MainThread
    public static PresenceConnection open(SocialApi api, String token, Listener listener) {
        return new PresenceConnection(api, token, listener);
    }

    @MainThread
    private void deliver(JsonObject message) {
        if (mEnded) return;
        String type = Json.str(message, "t", "");
        if (type.equals("ack") || type.equals("error")) {
            Long rid = Json.numOrNull(message, "rid");
            Reply reply = rid == null ? null : mReplies.remove(rid.intValue());
            if (reply != null) {
                reply.onReply(type.equals("ack") ? null : Json.str(message, "code", "bad_request"), Json.num(message, "retryAfter", 0));
                return;
            }
            if (type.equals("ack")) return;
        }
        mListener.onMessage(this, message);
    }

    @MainThread
    private void end(int code, boolean failed) {
        if (mEnded) return;
        mEnded = true;
        for (Reply reply : mReplies.values()) reply.onReply("unavailable", 0);
        mReplies.clear();
        mListener.onEnded(this, code, failed);
    }

    /** Sends {@code message} with a fresh {@code rid}; the reply comes to {@code reply}. */
    @MainThread
    public void request(JsonObject message, final Reply reply) {
        if (mEnded) {
            reply.onReply("unavailable", 0);
            return;
        }
        final int rid = mNextRid++;
        message.addProperty("rid", rid);
        mReplies.put(rid, reply);
        if (!mSocket.send(message.toString())) {
            mReplies.remove(rid);
            reply.onReply("unavailable", 0);
            return;
        }
        MAIN.postDelayed(() -> {
            Reply waiting = mReplies.remove(rid);
            if (waiting != null) waiting.onReply("unavailable", 0);
        }, REPLY_TIMEOUT_MS);
    }

    /** Sends a message that needs no answer (a status change). */
    @MainThread
    public boolean send(JsonObject message) {
        return !mEnded && mSocket.send(message.toString());
    }

    /** Closes it on purpose (the app went to the background, sign-out): no reconnect follows. */
    @MainThread
    public void close() {
        if (mEnded) return;
        mEnded = true;
        for (Reply reply : mReplies.values()) reply.onReply("unavailable", 0);
        mReplies.clear();
        mSocket.close(1000, null);
    }

    public boolean ended() {
        return mEnded;
    }

    /** Seconds the service asked to wait before the next try (a refused upgrade's Retry-After). */
    public long retryAfterSeconds() {
        return mRetryAfter;
    }
}
