package gg.projects.mobile.social;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * One presence WebSocket ({@code GET /v1/presence}; README "Presence WebSocket"). OkHttp sends the
 * protocol pings ({@link SocialApi}'s client: every 45 s, free for the service and answered without
 * waking the hub) and fails the socket when a pong does not come. Nothing else is sent on a timer:
 * only the player's own messages, each with a {@code rid} whose {@code ack} or {@code error} comes back
 * to its callback (10 s at most). Every callback runs on the main thread.
 *
 * <p>M20: the socket says what the app understands in its query ({@code ?caps=callServer,crews,chat});
 * an older service ignores it. A chat message's {@code ack} carries the message as the service stored
 * it, and a sync's answer ends with {@code chat_synced} (which echoes the {@code rid}): {@link
 * #request(JsonObject, Answer)} hands those over whole.
 */
public final class PresenceConnection {
    private static final String TAG = "ProjectsSocial";
    private static final long REPLY_TIMEOUT_MS = 10_000;
    /** {@link Listener#onEnded}'s code for an upgrade refused with 403 (a restricted account). */
    public static final int FORBIDDEN = 4403;

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

    /** M20: the whole answer ({@code ack}, {@code error} or {@code chat_synced}); null when none came (no socket, timeout). */
    public interface Answer {
        void onAnswer(@Nullable JsonObject answer);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final Listener mListener;
    private final WebSocket mSocket;
    private final Map<Integer, Answer> mReplies = new HashMap<>();
    private int mNextRid = 1;
    private boolean mEnded;
    /** A refused upgrade's Retry-After (seconds; 0 when none). */
    private volatile long mRetryAfter;

    private PresenceConnection(SocialApi api, String token, List<String> caps, @Nullable String rel, Listener listener) {
        mListener = listener;
        // M048 (SCALE-SOC): the relations version the phone's copy is, when it holds one; the hello names the service's.
        String url = api.base() + RelVersion.presencePath(caps, rel);
        Request request = new Request.Builder()
                .url(url)
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
                // A refused upgrade with 401 is a session that ended, like close code 4001; 403 a restricted
                // account (a request then says until when).
                MAIN.post(() -> end(status == 401 ? 4001 : status == 403 ? FORBIDDEN : 0, true));
            }
        });
    }

    /** {@code rel}: the relations version held ({@link RelVersion}), named only when it is one; null: none. */
    @MainThread
    public static PresenceConnection open(SocialApi api, String token, List<String> caps, @Nullable String rel, Listener listener) {
        return new PresenceConnection(api, token, caps, rel, listener);
    }

    @MainThread
    private void deliver(JsonObject message) {
        if (mEnded) return;
        String type = Json.str(message, "t", "");
        if (type.equals("ack") || type.equals("error") || type.equals("chat_synced")) {
            Long rid = Json.numOrNull(message, "rid");
            Answer reply = rid == null ? null : mReplies.remove(rid.intValue());
            if (reply != null) {
                reply.onAnswer(message);
                // The end of a sync is the chat's to read too (the hub's clock, "more").
                if (!type.equals("chat_synced")) return;
            } else if (type.equals("ack")) {
                return;
            }
        }
        mListener.onMessage(this, message);
    }

    @MainThread
    private void end(int code, boolean failed) {
        if (mEnded) return;
        mEnded = true;
        failAll();
        mListener.onEnded(this, code, failed);
    }

    private void failAll() {
        Map<Integer, Answer> waiting = new HashMap<>(mReplies);
        mReplies.clear();
        for (Answer reply : waiting.values()) reply.onAnswer(null);
    }

    /** Sends {@code message} with a fresh {@code rid}; the code of its answer comes to {@code reply}. */
    @MainThread
    public void request(JsonObject message, final Reply reply) {
        request(message, answer -> {
            if (answer == null) {
                reply.onReply("unavailable", 0);
            } else if (Json.str(answer, "t", "").equals("error")) {
                reply.onReply(Json.str(answer, "code", "bad_request"), Json.num(answer, "retryAfter", 0));
            } else {
                reply.onReply(null, 0);
            }
        });
    }

    /** Sends {@code message} with a fresh {@code rid}; its whole answer comes to {@code answer} (null: none came). */
    @MainThread
    public void request(JsonObject message, final Answer answer) {
        if (mEnded) {
            answer.onAnswer(null);
            return;
        }
        final int rid = mNextRid++;
        message.addProperty("rid", rid);
        mReplies.put(rid, answer);
        if (!mSocket.send(message.toString())) {
            mReplies.remove(rid);
            answer.onAnswer(null);
            return;
        }
        MAIN.postDelayed(() -> {
            Answer waiting = mReplies.remove(rid);
            if (waiting != null) waiting.onAnswer(null);
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
        failAll();
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
