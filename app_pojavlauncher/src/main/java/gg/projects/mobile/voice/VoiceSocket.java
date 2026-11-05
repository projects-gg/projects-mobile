package gg.projects.mobile.voice;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import java.net.URLEncoder;
import java.util.concurrent.TimeUnit;

import gg.projects.mobile.social.Json;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * One room socket ({@code GET /v1/voice?ticket=…} with the presence session's bearer token), one per connection attempt
 * (the desktop's {@code socket.rs}): it reports what happens with its generation and sends the frames it is handed;
 * reconnecting is the machine's. Keep-alive: OkHttp's protocol pings every 45 s (a hibernating room answers them without
 * waking; a missing pong fails the socket). Tickets never reach a log line.
 */
final class VoiceSocket {
    private static final String TAG = "ProjectsVoice";

    interface Listener {
        void onOpen(long generation);

        void onMessage(long generation, VoiceWire.Message message);

        /** An HTTP refusal of the upgrade ({@code status}, {@code code}); status 0: the network. */
        void onRefused(long generation, int status, @Nullable String code);

        /** The socket closed ({@code code} 0: it dropped). */
        void onClosed(long generation, int code);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static OkHttpClient sClient;

    private final long mGeneration;
    private final WebSocket mSocket;
    private volatile boolean mOpened;
    private volatile boolean mEnded;

    private static synchronized OkHttpClient client() {
        if (sClient == null) {
            sClient = new OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(0, TimeUnit.SECONDS)
                    .pingInterval(45, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(false)
                    .build();
        }
        return sClient;
    }

    VoiceSocket(String base, String token, String userAgent, long generation, String ticket, final Listener listener) {
        mGeneration = generation;
        String url;
        try {
            url = base + "v1/voice?ticket=" + URLEncoder.encode(ticket, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + token)
                .header("User-Agent", userAgent)
                .build();
        mSocket = client().newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                mOpened = true;
                MAIN.post(() -> {
                    if (!mEnded) listener.onOpen(mGeneration);
                });
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                if (text.length() > 64 * 1024) return;
                final VoiceWire.Message message = VoiceWire.parse(text);
                if (message.kind == VoiceWire.Kind.UNKNOWN) return;
                MAIN.post(() -> {
                    if (!mEnded) listener.onMessage(mGeneration, message);
                });
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                webSocket.close(1000, null);
                end(listener, code);
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                end(listener, code);
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, @Nullable Response response) {
                if (!mOpened && response != null) {
                    final int status = response.code();
                    String code = null;
                    try {
                        okhttp3.ResponseBody body = response.body();
                        if (body != null) code = Json.str(Json.obj(Json.parseObject(body.string()), "error"), "code");
                    } catch (Exception ignored) {
                        // No code: the status says enough.
                    }
                    final String refused = code;
                    Log.i(TAG, "room socket refused: HTTP " + status);
                    if (mEnded) return;
                    mEnded = true;
                    MAIN.post(() -> listener.onRefused(mGeneration, status, refused));
                    return;
                }
                if (!mOpened) {
                    if (mEnded) return;
                    mEnded = true;
                    MAIN.post(() -> listener.onRefused(mGeneration, 0, null));
                    return;
                }
                end(listener, 0);
            }
        });
    }

    private void end(final Listener listener, final int code) {
        if (mEnded) return;
        mEnded = true;
        MAIN.post(() -> listener.onClosed(mGeneration, code));
    }

    long generation() {
        return mGeneration;
    }

    void send(String frame) {
        if (frame.length() > VoiceWire.MAX_FRAME_CHARS) {
            Log.w(TAG, "a room frame was too long to send");
            return;
        }
        mSocket.send(frame);
    }

    /** Closes it on purpose: nothing more is reported. */
    void close() {
        mEnded = true;
        mSocket.close(1000, null);
    }
}
