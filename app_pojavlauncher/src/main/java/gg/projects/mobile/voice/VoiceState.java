package gg.projects.mobile.voice;

import androidx.annotation.Nullable;

import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.List;

/**
 * What the screens show of the player's stay in a room (the desktop's {@code VoiceRoomView} and the call part of
 * {@code VoiceState}). The voice process builds it ({@link VoiceMachine#view}) and hands it to the launcher's screens as
 * JSON ({@link #json}, {@link #read}); null there means no stay.
 */
public final class VoiceState {
    public static final String JOINING = "joining";
    public static final String LIVE = "live";
    public static final String RECONNECTING = "reconnecting";

    public static final String LEADER = "leader";
    public static final String MODERATOR = "moderator";
    public static final String MEMBER = "member";

    public String room;
    public boolean crewRoom;
    @Nullable public String crew;
    /** A friend room: none until its leader names it; a crew room: always ("Genel", "Oda 2", or its name). */
    @Nullable public String name;
    public boolean general;
    public int max;
    /** {@link #JOINING}, {@link #LIVE} or {@link #RECONNECTING}. */
    public String phase = JOINING;
    public Me me = new Me();
    public List<Person> people = new ArrayList<>();
    /** The player's own invites that ring. */
    public List<OutRing> ringing = new ArrayList<>();
    public boolean canRename;
    public boolean canClose;
    /** The friend the player calls ("Aranıyor…"), until the call is answered. */
    @Nullable public String calling;
    public long callingUntil;
    /** VOICE-CALL: since when someone else shares the room (Unix seconds, this phone's clock): the call's timer. */
    @Nullable public Long togetherSince;
    /** The top of a person's volume (percent). */
    public int volumeMax = VoiceMachine.VOLUME_MAX;
    /** The microphone could not be had (the player listens only): {@code denied}, {@code missing}, or null. */
    @Nullable public String mic;
    /** SQOL: the room offers its text chat ({@code hello.room.chat}); its messages come apart ({@link RoomChat.View}). */
    public boolean chat;
    /** SQOL: a friend room that once had three people or more (its chat is the room's, never the friend's messages). */
    public boolean grew;
    /** SQOL: room chat messages of others the player has not seen (the call bar's count while the room is closed). */
    public int chatUnread;
    /** SQOL: grows with every change of the room chat. */
    public long chatVersion;

    public static final class Me {
        public String id = "";
        public String role = MEMBER;
        public boolean mute;
        public boolean deaf;
        public boolean mutedByModerator;
        public boolean talking;
        /** 0 good, 1 weak, 2 bad. */
        public int quality;
        /** M051: a screen share of the player's (started on the desktop) is live here. */
        public boolean sharing;
    }

    public static final class Person {
        public String id;
        public String role = MEMBER;
        public boolean mute;
        public boolean deaf;
        public boolean mutedByModerator;
        public int quality;
        public boolean speaking;
        /** Percent, 0 … {@link VoiceState#volumeMax}. */
        public int volume = 100;
        public boolean mutedForMe;
        public long joinedAt;
        public boolean canMute;
        public boolean canKick;
        public boolean canMove;
        public boolean canModerator;
        /** VOICE-S2: the name the room's card gives (a friend's friend), or null. */
        @Nullable public String cardName;
        @Nullable public String cardAvatar;
        @Nullable public String cardAvatarHash;
        @Nullable public String cardDiscordId;
        /**
         * M051 (SHARE-LIVE): they share their screen ("YAYINDA"); their first live share's number, what it shows
         * ({@code game}, {@code window}, {@code screen}), how many watch it and its last picture's time (0: none yet).
         */
        public boolean sharing;
        public int shareN;
        @Nullable public String shareSrc;
        public int shareWatchers;
        public long shareTh;
        /** SQOL ({@code share2}): {@code hold} (paused), {@code min} (the shared window minimized), or null while live. */
        @Nullable public String shareSt;
    }

    public static final class OutRing {
        public String to;
        public long until;
    }

    public boolean live() {
        return LIVE.equals(phase);
    }

    public boolean reconnecting() {
        return RECONNECTING.equals(phase);
    }

    @Nullable
    public Person person(String id) {
        for (Person p : people) if (p.id.equals(id)) return p;
        return null;
    }

    /** A two-person call (a friend room with one other person, or still calling one): the call's own look. */
    public boolean oneToOne() {
        return !crewRoom && (calling != null ? people.isEmpty() : people.size() == 1);
    }

    private static final Gson GSON = new Gson();

    public String json() {
        return GSON.toJson(this);
    }

    @Nullable
    public static VoiceState read(@Nullable String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            VoiceState state = GSON.fromJson(json, VoiceState.class);
            if (state == null || state.room == null) return null;
            if (state.people == null) state.people = new ArrayList<>();
            if (state.ringing == null) state.ringing = new ArrayList<>();
            if (state.me == null) state.me = new Me();
            return state;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
