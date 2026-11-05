package gg.projects.mobile.voice;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import com.google.gson.JsonObject;

import net.kdt.pojavlaunch.R;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import gg.projects.mobile.language.LanguageManager;
import gg.projects.mobile.social.Json;
import gg.projects.mobile.social.SocialApi;
import gg.projects.mobile.social.SocialOpenActivity;
import gg.projects.mobile.social.SocialProblem;
import gg.projects.mobile.social.SocialRoutes;

/**
 * Voice chat in the launcher's process (the friends feature's process): what the presence socket says of it
 * ({@link VoiceRings}), calling a friend ({@code voice_call}), answering and declining rings, crew rooms' tickets, and the
 * ring itself: a card and our own chime while a screen of the app shows ({@link #ringing}), else a notification with
 * Katıl and Reddet. The stay itself runs in the voice process ({@link VoiceLink}).
 *
 * <p>Gates: nothing of it shows unless the signed config has {@code features.voice} (inside friends), the presence socket
 * took {@code voice1} and Studio's {@code voiceOn} is on ({@code hello.voice.on}); the player's own switch ("Sesli sohbeti
 * kullan") turns rings and calls off. Old services ignore the {@code voice1} capability and send none of it.
 */
public final class VoiceHub {
    /** What the screens hear. */
    public interface Listener {
        @MainThread
        void onVoiceChanged();
    }

    /** The friends feature as voice chat needs it (implemented by {@code SocialService}). */
    public interface Host {
        /** Sends a message on the presence socket (false: no socket). */
        boolean send(JsonObject message);

        /** Sends a request on the presence socket; its whole answer (null: none came). */
        void request(JsonObject message, Answer answer);

        boolean foreground();

        SocialApi api();

        @Nullable
        String myId();

        /** The name screens show for someone (a friend's or crewmate's), or null. */
        @Nullable
        String nameOf(String id);

        /** The player's own status is "Rahatsız etmeyin" (no ring card or tone). */
        boolean doNotDisturb();
    }

    public interface Answer {
        void onAnswer(@Nullable JsonObject answer);
    }

    /** The outcome of a call, a ring's answer or a crew room's join. */
    public interface Done {
        void done(VoiceAnswer answer);
    }

    private static VoiceHub sInstance;

    private final Context mApp;
    private final Host mHost;
    private final VoiceRings mRings = new VoiceRings();
    private final VoicePrefs mPrefs;
    private final VoiceRingTone mTone;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final ExecutorService mIo = Executors.newSingleThreadExecutor();
    private final List<Listener> mListeners = new ArrayList<>();
    private boolean mConfig;
    private final Runnable mTick = this::tick;

    public VoiceHub(Context context, Host host) {
        mApp = context.getApplicationContext();
        mHost = host;
        mPrefs = new VoicePrefs(mApp);
        mTone = new VoiceRingTone(mApp);
        mRings.setEnabled(mPrefs.enabled());
        sInstance = this;
    }

    /** The launcher process's hub, once the friends feature made it (null before). */
    @Nullable
    public static VoiceHub instance() {
        return sInstance;
    }

    public void addListener(Listener listener) {
        if (!mListeners.contains(listener)) mListeners.add(listener);
    }

    public void removeListener(Listener listener) {
        mListeners.remove(listener);
    }

    public VoiceRings rings() {
        return mRings;
    }

    public VoicePrefs prefs() {
        return mPrefs;
    }

    // ---- the friends feature's events ----

    /** The signed config's {@code features.voice}. */
    @MainThread
    public void setConfig(boolean voice) {
        mConfig = voice;
        run(mRings.setFlag(voice));
    }

    public boolean config() {
        return mConfig;
    }

    /** The presence socket's capabilities: {@code voice1} while the config has voice chat. */
    public void addCaps(List<String> caps) {
        if (mConfig) caps.add(VoiceRings.VOICE_CAP);
    }

    @MainThread
    public void signedIn(@Nullable String me) {
        run(mRings.setMe(me));
    }

    /** One frame of the presence socket; true when it was voice chat's alone (the rest of the app need not see it). */
    @MainThread
    public boolean onFrame(JsonObject message) {
        String type = Json.str(message, "t", "");
        List<VoiceRings.Effect> out = new ArrayList<>();
        if ("hello".equals(type)) {
            out.addAll(mRings.hubHello(message));
            run(out);
            return false;
        }
        boolean own = mRings.onFrame(message, now(), unix(), out);
        if ("presence".equals(type)) out.add(new VoiceRings.Effect(VoiceRings.Effect.Kind.CHANGED, null, null, false));
        run(out);
        return own;
    }

    @MainThread
    public void socketEnded() {
        run(mRings.hubDown());
    }

    /** The app's screens came up or went: the ring moves between the card and the notification. */
    @MainThread
    public void foregroundChanged() {
        syncRingSurface();
    }

    /** The player blocked someone: their rings go, and a two-person call with them ends. */
    @MainThread
    public void blocked(String user) {
        run(mRings.blocked(user));
    }

    // ---- what the screens may do ----

    /** Voice chat shows at all (the config, Studio's switch, signed in). */
    public boolean available() {
        return mRings.available();
    }

    /** New calls and joins may start (also the player's switch and a connected hub). */
    public boolean usable() {
        return mRings.usable();
    }

    /** "Sesli arama başlat" works for {@code friend} now ({@code ready} or {@code in}). */
    public boolean callable(String friend) {
        return usable() && mRings.presenceOf(friend) != null;
    }

    /** The player's own switch ("Sesli sohbeti kullan"). */
    @MainThread
    public void setEnabled(boolean on) {
        mPrefs.setEnabled(on);
        run(mRings.setEnabled(on));
        if (!on && VoiceLink.get(mApp).state() != null) VoiceLink.get(mApp).leave();
    }

    // ---- calls and joins ----

    /** "Sesli arama başlat": a new friend room that rings {@code friend}; the player is in it at once. */
    @MainThread
    public void call(final String friend, final Done done) {
        if (!usable()) {
            done.done(VoiceAnswer.refused(VoiceAnswer.Refusal.OFF));
            return;
        }
        JsonObject message = new JsonObject();
        message.addProperty("t", "voice_call");
        message.addProperty("to", friend);
        mHost.request(message, answer -> {
            if (answer == null) {
                done.done(VoiceAnswer.refused(VoiceAnswer.Refusal.FAILED));
                return;
            }
            if ("error".equals(Json.str(answer, "t"))) {
                done.done(VoiceAnswer.fromCode(Json.str(answer, "code"), Json.numOrNull(answer, "retryAfter"),
                        Json.numOrNull(answer, "until")));
                return;
            }
            String room = Json.str(answer, "room");
            String ticket = Json.str(answer, "ticket");
            if (!VoiceWire.isRoomId(room) || ticket == null) {
                done.done(VoiceAnswer.refused(VoiceAnswer.Refusal.FAILED));
                return;
            }
            start(room, ticket, friend, false, nameOr(friend));
            done.done(VoiceAnswer.OK);
        });
    }

    /** "Katıl" on a ring or a missed call that may still be joined. */
    @MainThread
    public void answer(final String room, final Done done) {
        VoiceRings.Ring ring = mRings.ringOf(room);
        final String from = ring != null ? ring.from : fromOfMissed(room);
        String ticket = mRings.ticketFor(room, unix());
        if (ticket != null) {
            start(room, ticket, null, false, title(room, from));
            done.done(VoiceAnswer.OK);
            return;
        }
        // A ring that came as a push: the service hands its ticket now.
        mIo.execute(() -> {
            VoiceAnswer outcome;
            String got = null;
            try {
                JsonObject answer = mHost.api().voiceRing(room, true);
                got = Json.str(answer, "ticket");
                outcome = got == null ? VoiceAnswer.refused(VoiceAnswer.Refusal.ROOM_GONE) : VoiceAnswer.OK;
            } catch (SocialProblem problem) {
                outcome = refusal(problem);
            }
            final String ticketGot = got;
            final VoiceAnswer result = outcome;
            mMain.post(() -> {
                if (ticketGot != null) start(room, ticketGot, null, false, title(room, from));
                else run(mRings.ringEnd(room, "ended"));
                done.done(result);
            });
        });
    }

    /** "Reddet": the ring stops on every device. */
    @MainThread
    public void decline(final String room) {
        decline(room, null);
    }

    /** "Reddet" ({@code after}: once the service heard it, for a notification's action that waits). */
    @MainThread
    public void decline(final String room, @Nullable final Runnable after) {
        VoiceRings.Ring ring = mRings.ringOf(room);
        boolean pushed = ring != null && ring.ticket == null;
        List<VoiceRings.Effect> out = mRings.decline(room);
        VoiceNotify.cancelRing(mApp, room);
        boolean bySocket = false;
        if (!pushed && !out.isEmpty()) {
            JsonObject m = new JsonObject();
            m.addProperty("t", "voice_decline");
            m.addProperty("room", room);
            bySocket = mHost.send(m);
        }
        List<VoiceRings.Effect> rest = new ArrayList<>();
        for (VoiceRings.Effect e : out) if (e.kind != VoiceRings.Effect.Kind.HUB_DECLINE) rest.add(e);
        run(rest);
        if (bySocket) {
            if (after != null) after.run();
            return;
        }
        // A ring the app knows only from its push, or no socket now: the service hears it by its route.
        mIo.execute(() -> {
            try {
                mHost.api().voiceRing(room, false);
            } catch (SocialProblem ignored) {
                // The ring ends by itself at its time.
            }
            if (after != null) mMain.post(after);
        });
    }

    /** "Katıl" on a crew room ({@code room} null: its "Genel"). */
    @MainThread
    public void joinCrew(final String crew, @Nullable final String room, final Done done) {
        crewTicket(crew, room, false, done);
    }

    /** "Yeni oda": a new room of the crew, the player in it (they may rename it). */
    @MainThread
    public void newCrewRoom(final String crew, final Done done) {
        crewTicket(crew, null, true, done);
    }

    private void crewTicket(final String crew, @Nullable final String room, final boolean fresh, final Done done) {
        if (!usable()) {
            done.done(VoiceAnswer.refused(VoiceAnswer.Refusal.OFF));
            return;
        }
        mIo.execute(() -> {
            JsonObject answer = null;
            VoiceAnswer outcome;
            try {
                answer = fresh ? mHost.api().voiceCrewNew(crew) : mHost.api().voiceCrew(crew, room);
                outcome = VoiceAnswer.OK;
            } catch (SocialProblem problem) {
                outcome = refusal(problem);
            }
            final JsonObject got = answer;
            final VoiceAnswer result = outcome;
            mMain.post(() -> {
                String id = Json.str(got, "room");
                String ticket = Json.str(got, "ticket");
                if (result.ok() && VoiceWire.isRoomId(id) && ticket != null) {
                    start(id, ticket, null, fresh, crewTitle(crew, Json.str(got, "name")));
                    done.done(VoiceAnswer.OK);
                } else {
                    done.done(result.ok() ? VoiceAnswer.refused(VoiceAnswer.Refusal.FAILED) : result);
                }
            });
        });
    }

    private void start(String room, String ticket, @Nullable String calling, boolean opened, String title) {
        String me = mHost.myId();
        if (me == null) return;
        run(mRings.joining(room, calling));
        VoiceLink.join(mApp, room, ticket, calling, opened, mRings.service().forStay(), mPrefs.soundsSaved(), me, title);
    }

    // ---- the effects ----

    private void run(List<VoiceRings.Effect> effects) {
        boolean changed = false;
        Context c = LanguageManager.wrap(mApp);
        for (VoiceRings.Effect e : effects) {
            switch (e.kind) {
                case HUB_DECLINE: {
                    JsonObject m = new JsonObject();
                    m.addProperty("t", "voice_decline");
                    m.addProperty("room", e.room);
                    if (!mHost.send(m)) {
                        final String room = e.room;
                        mIo.execute(() -> {
                            try {
                                mHost.api().voiceRing(room, false);
                            } catch (SocialProblem ignored) {
                                // It ends by itself.
                            }
                        });
                    }
                    break;
                }
                case HUB_SWITCH: {
                    JsonObject m = new JsonObject();
                    m.addProperty("t", "status");
                    m.addProperty("state", "launcher");
                    m.addProperty("voice", e.on ? "on" : "off");
                    mHost.send(m);
                    break;
                }
                case RING:
                    changed = true;
                    break;
                case RING_GONE:
                    if (e.room != null) VoiceNotify.cancelRing(mApp, e.room);
                    changed = true;
                    break;
                case MISSED:
                    if (e.ring != null && !mHost.foreground()) {
                        VoiceNotify.showMissed(mApp, e.ring.from, nameOr(e.ring.from));
                    } else if (e.ring != null && VoiceWire.isFriendRoom(e.ring.room)) {
                        // On screen: one line with "Geri ara" (a new call, no second question: the desktop's rule).
                        final String from = e.ring.from;
                        gg.projects.mobile.social.ui.SocialUi.toastAction(mApp,
                                c.getString(R.string.projects_voice_missed_from, nameOr(from)),
                                c.getString(R.string.projects_voice_call_back), () -> call(from, answer -> {
                                    if (!answer.ok()) {
                                        gg.projects.mobile.social.ui.SocialUi.toastLong(mApp,
                                                VoiceNotify.refusalText(LanguageManager.wrap(mApp), answer, nameOr(from)));
                                    }
                                }));
                    }
                    changed = true;
                    break;
                case CHANGED:
                    changed = true;
                    break;
                default:
                    break;
            }
        }
        if (changed) {
            syncRingSurface();
            for (Listener l : new ArrayList<>(mListeners)) l.onVoiceChanged();
        }
        schedule();
    }

    /** On screen: the card and our chime; away: the notification (with its own ring). */
    private void syncRingSurface() {
        List<VoiceRings.Ring> rings = mRings.rings();
        boolean front = mHost.foreground();
        mTone.set(front && !rings.isEmpty() && !mHost.doNotDisturb());
        for (VoiceRings.Ring ring : rings) {
            if (front) VoiceNotify.cancelRing(mApp, ring.room);
            else showRingNotification(ring);
        }
    }

    private void showRingNotification(VoiceRings.Ring ring) {
        long left = Math.max(0, ring.until - unix()) * 1000;
        if (left <= 0) return;
        Context c = LanguageManager.wrap(mApp);
        VoiceNotify.showRing(mApp, ring.room, nameOr(ring.from), others(c, ring.with), left,
                VoiceActionActivity.join(mApp, ring.room), VoiceActionReceiver.decline(mApp, ring.room),
                SocialOpenActivity.open(mApp, SocialRoutes.FRIENDS, null));
    }

    /** A ring that came as a push while the app had no socket (the push decided it should ring). */
    public static void pushedRing(Context context, String room, String from, @Nullable String name, long at, long until) {
        VoiceHub hub = sInstance;
        if (hub != null && hub.mHost.foreground()) {
            // The app is up: its own socket brings the ring (with its ticket) if it has one.
            List<String> none = new ArrayList<>();
            hub.run(hub.mRings.ring(new VoiceRings.Ring(room, from, at, until, null, none), false, now(), unix()));
            return;
        }
        if (hub != null) {
            List<String> none = new ArrayList<>();
            hub.mRings.ring(new VoiceRings.Ring(room, from, at, until, null, none), false, now(), unix());
            hub.schedule();
        }
        long left = Math.max(0, until - unix()) * 1000;
        if (left <= 0) return;
        Context c = LanguageManager.wrap(context);
        String who = name == null || name.isEmpty() ? c.getString(R.string.projects_voice_someone) : name;
        VoiceNotify.showRing(context, room, who, null, left, VoiceActionActivity.join(context, room),
                VoiceActionReceiver.decline(context, room), SocialOpenActivity.open(context, SocialRoutes.FRIENDS, null));
    }

    /** The ring that came as a push ended (answered or declined elsewhere, or taken back). */
    public static void pushedRingEnd(Context context, String room) {
        VoiceNotify.cancelRing(context, room);
        VoiceHub hub = sInstance;
        if (hub != null) hub.run(hub.mRings.ringEnd(room, "ended"));
    }

    // ---- time ----

    private void schedule() {
        mMain.removeCallbacks(mTick);
        Long at = mRings.nextDeadline();
        if (at != null) mMain.postDelayed(mTick, Math.max(0, at - now()));
    }

    private void tick() {
        run(mRings.tick(now()));
    }

    private static long now() {
        return SystemClock.elapsedRealtime();
    }

    static long unix() {
        return System.currentTimeMillis() / 1000;
    }

    // ---- words ----

    public String nameOr(String id) {
        String name = mHost.nameOf(id);
        return name == null || name.isEmpty() ? LanguageManager.wrap(mApp).getString(R.string.projects_voice_someone) : name;
    }

    @Nullable
    private String fromOfMissed(String room) {
        for (VoiceRings.Missed m : mRings.missed()) if (m.room.equals(room)) return m.from;
        return null;
    }

    /** The stay's line in its notification: the friend's name for a call, the crew room's for a crew room. */
    private String title(String room, @Nullable String from) {
        if (!VoiceWire.isFriendRoom(room)) {
            VoiceRings.CrewRoom crewRoom = mRings.crewRoom(room);
            return crewRoom == null ? LanguageManager.wrap(mApp).getString(R.string.projects_voice_title)
                    : roomName(LanguageManager.wrap(mApp), crewRoom.name, crewRoom.general());
        }
        return from == null ? LanguageManager.wrap(mApp).getString(R.string.projects_voice_call_kind) : nameOr(from);
    }

    private String crewTitle(String crew, @Nullable String name) {
        Context c = LanguageManager.wrap(mApp);
        String room = roomName(c, name == null ? "Genel" : name, name == null || "Genel".equals(name));
        String crewName = mHost.nameOf("crew:" + crew);
        return crewName == null ? room : c.getString(R.string.projects_voice_crew_room, room, crewName);
    }

    /** A crew room's name as screens show it: "Genel" and "Oda N" in the player's language, a given name as it is. */
    public static String roomName(Context c, String name, boolean general) {
        if (general || "Genel".equals(name)) return c.getString(R.string.projects_voice_general);
        if (name.matches("Oda \\d{1,2}")) return c.getString(R.string.projects_voice_numbered, name.substring(4));
        return name;
    }

    @Nullable
    private String others(Context c, List<String> with) {
        if (with.isEmpty()) return null;
        List<String> names = new ArrayList<>();
        for (String id : with) names.add(nameOr(id));
        if (names.size() <= 2) return android.text.TextUtils.join(", ", names);
        return c.getString(R.string.projects_voice_names_more, names.get(0) + ", " + names.get(1), names.size() - 2);
    }

    /** A route's refusal as voice chat reads it (the desktop's codes). */
    static VoiceAnswer refusal(SocialProblem problem) {
        if (problem.code == null) return VoiceAnswer.refused(problem.status >= 500 || problem.status == 0
                ? VoiceAnswer.Refusal.FAILED : VoiceAnswer.Refusal.OFF);
        return VoiceAnswer.fromCode(problem.code, problem.retryAfter > 0 ? problem.retryAfter : null, problem.until);
    }
}
