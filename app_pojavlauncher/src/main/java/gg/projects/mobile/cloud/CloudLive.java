package gg.projects.mobile.cloud;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import gg.projects.mobile.social.Json;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * One live console socket ({@code GET /api/v1/live?host=…}, services/cloud-afk README "Live console socket"): open
 * only while a screen that streams is up (the console, the account's AI feed), never polled. It watches the accounts
 * asked for (again after a reconnect), renews their leases every 5 minutes, and sends the contract's text ping every
 * 30-45 s (the Durable Object answers it without waking); nothing heard within 20 s of a ping ends it. Every callback
 * runs on the main thread; reconnecting is the owner's decision ({@link CloudService}).
 */
public final class CloudLive {
    private static final String TAG = "ProjectsCloud";

    public interface Listener extends CloudLiveFrames.Listener {
        /** The socket ended: what its close code asks for, and a refused upgrade's wait (seconds). */
        void ended(CloudLive live, CloudLiveFrames.After after, long retryAfter, boolean failed);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final String mHost;
    private final Listener mListener;
    private final WebSocket mSocket;
    private final Random mRandom = new Random();
    /** Account → whether its AI streams too. */
    private final Map<String, Boolean> mWatching = new LinkedHashMap<>();
    /** Account → whether its inventory streams (absent: never asked; the field then stays out of its watch). */
    private final Map<String, Boolean> mInventory = new LinkedHashMap<>();
    /** Account → whether its movement streams (absent: never asked), as the inventory. */
    private final Map<String, Boolean> mMovement = new LinkedHashMap<>();
    private boolean mHello;
    private boolean mEnded;
    private volatile long mRetryAfter;

    private final Runnable mPing = new Runnable() {
        @Override
        public void run() {
            if (mEnded) return;
            mSocket.send(CloudLiveFrames.PING);
            MAIN.removeCallbacks(mSilent);
            MAIN.postDelayed(mSilent, CloudLiveFrames.PONG_WAIT_MS);
            schedulePing();
        }
    };
    private final Runnable mSilent = () -> {
        Log.i(TAG, "live socket silent after a ping");
        fail();
    };
    private final Runnable mKeep = new Runnable() {
        @Override
        public void run() {
            if (mEnded) return;
            if (!mWatching.isEmpty()) mSocket.send(CloudLiveFrames.KEEP);
            MAIN.postDelayed(this, CloudLiveFrames.KEEP_MS);
        }
    };

    private CloudLive(CloudApi api, String token, String host, Listener listener) {
        mHost = host;
        mListener = listener;
        Request request = new Request.Builder()
                .url(api.liveUrl(host))
                .header("Authorization", "Bearer " + token)
                .header("User-Agent", api.userAgent())
                .build();
        mSocket = api.client().newWebSocket(request, new WebSocketListener() {
            @Override
            public void onMessage(WebSocket webSocket, String text) {
                final JsonObject message = Json.parseObject(text);
                if (message == null) return;
                MAIN.post(() -> deliver(message));
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                webSocket.close(1000, null);
                MAIN.post(() -> end(CloudLiveFrames.after(code), false));
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                MAIN.post(() -> end(CloudLiveFrames.after(code), false));
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
                if (!mEnded) Log.i(TAG, "live socket failed: " + t.getClass().getSimpleName() + (status == 0 ? "" : " HTTP " + status));
                // A refused upgrade with 401 is a session that ended; 404 the account is not on this machine (read again).
                MAIN.post(() -> end(status == 401 ? CloudLiveFrames.After.SIGNED_OUT
                        : status == 404 ? CloudLiveFrames.After.RECONNECT_NOW : CloudLiveFrames.After.BACKOFF, true));
            }
        });
        schedulePing();
        MAIN.postDelayed(mKeep, CloudLiveFrames.KEEP_MS);
    }

    @MainThread
    public static CloudLive open(CloudApi api, String token, String host, Listener listener) {
        return new CloudLive(api, token, host, listener);
    }

    public String host() {
        return mHost;
    }

    public boolean ended() {
        return mEnded;
    }

    public long retryAfterSeconds() {
        return mRetryAfter;
    }

    private void schedulePing() {
        long span = CloudLiveFrames.PING_MAX_MS - CloudLiveFrames.PING_MIN_MS;
        MAIN.postDelayed(mPing, CloudLiveFrames.PING_MIN_MS + (long) (mRandom.nextDouble() * span));
    }

    @MainThread
    private void deliver(JsonObject message) {
        if (mEnded) return;
        // Anything heard means the socket lives.
        MAIN.removeCallbacks(mSilent);
        if ("hello".equals(Json.str(message, "t"))) {
            mHello = true;
            for (Map.Entry<String, Boolean> w : mWatching.entrySet()) {
                // After a reconnect the inventory and movement streams are asked for again only while a screen wants them.
                mSocket.send(watchFrame(w.getKey(), w.getValue(), true).toString());
            }
        }
        if ("bye".equals(Json.str(message, "t"))) {
            CloudLiveFrames.dispatch(message, mListener);
            return;
        }
        CloudLiveFrames.dispatch(message, mListener);
    }

    /** Streams {@code acct} (its AI too when {@code ai}) from now on, also after the hello of a reconnect. */
    @MainThread
    public void watch(String acct, boolean ai) {
        Boolean before = mWatching.get(acct);
        boolean wantAi = ai || (before != null && before);
        mWatching.put(acct, wantAi);
        if (mHello && !mEnded && (before == null || before != wantAi)) {
            mSocket.send(watchFrame(acct, wantAi, true).toString());
        }
    }

    /**
     * The account's watch with the streams a screen wants. {@code fresh}: a first watch (after a hello too), where a
     * stream nobody wants is left out; otherwise both are named as they stand, so one switch never touches the other.
     */
    private JsonObject watchFrame(String acct, boolean ai, boolean fresh) {
        Boolean inv = mInventory.get(acct);
        Boolean move = mMovement.get(acct);
        if (fresh) {
            inv = inv != null && inv ? Boolean.TRUE : null;
            move = move != null && move ? Boolean.TRUE : null;
        }
        return CloudLiveFrames.watch(acct, ai, inv, move);
    }

    /**
     * Switches {@code acct}'s inventory stream on or off (CAFK-FEAT). The account must be watched; a watch with
     * {@code inv} renews its lease without a new console snapshot.
     */
    @MainThread
    public void inventory(String acct, boolean on) {
        Boolean ai = mWatching.get(acct);
        if (ai == null) return;
        Boolean before = mInventory.get(acct);
        if (before != null && before == on) return;
        if (before == null && !on) return;
        mInventory.put(acct, on);
        if (mHello && !mEnded) mSocket.send(watchFrame(acct, ai, false).toString());
    }

    /** Switches {@code acct}'s movement stream on or off (only while its panel is visible), as {@link #inventory}. */
    @MainThread
    public void movement(String acct, boolean on) {
        Boolean ai = mWatching.get(acct);
        if (ai == null) return;
        Boolean before = mMovement.get(acct);
        if (before != null && before == on) return;
        if (before == null && !on) return;
        mMovement.put(acct, on);
        if (mHello && !mEnded) mSocket.send(watchFrame(acct, ai, false).toString());
    }

    /** Sends one client frame (a move); false when the socket cannot carry it now. */
    @MainThread
    public boolean send(JsonObject frame) {
        if (!mHello || mEnded) return false;
        return mSocket.send(frame.toString());
    }

    @MainThread
    public void unwatch(String acct) {
        mInventory.remove(acct);
        mMovement.remove(acct);
        if (mWatching.remove(acct) == null) return;
        if (mHello && !mEnded) mSocket.send(CloudLiveFrames.unwatch(acct).toString());
    }

    public List<String> watching() {
        return new java.util.ArrayList<>(mWatching.keySet());
    }

    @MainThread
    private void end(CloudLiveFrames.After after, boolean failed) {
        if (mEnded) return;
        mEnded = true;
        stopTimers();
        mListener.ended(this, after, mRetryAfter, failed);
    }

    private void fail() {
        if (mEnded) return;
        mSocket.cancel();
        end(CloudLiveFrames.After.BACKOFF, true);
    }

    private void stopTimers() {
        MAIN.removeCallbacks(mPing);
        MAIN.removeCallbacks(mSilent);
        MAIN.removeCallbacks(mKeep);
    }

    /** Closed on purpose (the screen went): no reconnect follows. */
    @MainThread
    public void close() {
        if (mEnded) return;
        mEnded = true;
        stopTimers();
        mSocket.close(1000, null);
    }
}
