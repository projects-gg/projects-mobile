package gg.projects.mobile.voice;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import gg.projects.mobile.voice.VoiceWire.Message;
import gg.projects.mobile.voice.VoiceWire.Person;
import gg.projects.mobile.voice.VoiceWire.Role;

/**
 * One room as the player sees it: the room's facts, the people in it with their states, and the player's own invites
 * that ring, kept from {@code hello} and the messages after it (the desktop's {@code room.rs}).
 */
public final class VoiceRoom {
    /** What a message changed for the player. */
    public static final class Applied {
        /** Someone came in (not the player). */
        @Nullable public String joined;
        /** Someone left (not the player). */
        @Nullable public String left;
        /** The player was muted for everyone just now. */
        public boolean mutedMe;
        /** Anything the screens show changed. */
        public boolean changed;
        /** People's playback changed (who is in, who is muted for everyone). */
        public boolean playback;
    }

    public final String id;
    public final boolean crewRoom;
    @Nullable public final String crew;
    @Nullable public String name;
    @Nullable public String leader;
    public List<String> mods;
    public int max;
    public final String me;
    public Role myRole;
    /** Everyone but the player, by id. */
    public final Map<String, Person> people = new TreeMap<>();
    /** The player is muted for everyone (from {@code hello} and {@code state}). */
    public boolean mySm;
    /** The player's own invites that ring: person → until (Unix seconds). */
    public final Map<String, Long> ringing = new TreeMap<>();

    private VoiceRoom(String id, boolean crewRoom, @Nullable String crew, String me) {
        this.id = id;
        this.crewRoom = crewRoom;
        this.crew = crew;
        this.me = me;
    }

    /** The room from its {@code hello} (the player is {@code me}). */
    public static VoiceRoom fromHello(String me, Message hello) {
        VoiceRoom room = new VoiceRoom(hello.roomId, hello.crewRoom, hello.crew, me);
        room.name = hello.name;
        room.leader = hello.leader;
        room.mods = new ArrayList<>(hello.mods);
        room.max = Math.max(0, hello.max);
        room.myRole = hello.role;
        for (Person person : hello.people) {
            if (person.u.equals(me)) {
                room.mySm = person.sm;
            } else {
                room.people.put(person.u, person);
            }
        }
        room.ringing.putAll(hello.rings);
        return room;
    }

    /** Someone's role as the room says it (crew rooms: the crew's). */
    public Role roleOf(String u) {
        if (u.equals(me)) return myRole;
        if (u.equals(leader)) return Role.LEADER;
        if (mods.contains(u)) return Role.MODERATOR;
        return Role.MEMBER;
    }

    /** People, longest present first. */
    public List<Person> peopleInOrder() {
        List<Person> out = new ArrayList<>(people.values());
        Collections.sort(out, (a, b) -> a.j != b.j ? Long.compare(a.j, b.j) : a.u.compareTo(b.u));
        return out;
    }

    /** Applies one room message ({@code hello} and the stay's ends are the caller's). */
    public Applied apply(Message message) {
        Applied applied = new Applied();
        switch (message.kind) {
            case JOINED: {
                Person person = message.person;
                if (person != null && !person.u.equals(me)) {
                    boolean fresh = !people.containsKey(person.u);
                    people.put(person.u, person);
                    if (fresh) applied.joined = person.u;
                    applied.changed = true;
                    applied.playback = true;
                }
                break;
            }
            case LEFT:
                if (message.u != null && people.remove(message.u) != null) {
                    applied.left = message.u;
                    applied.changed = true;
                    applied.playback = true;
                }
                break;
            case STATE: {
                String u = message.u;
                if (u == null) break;
                if (u.equals(me)) {
                    if (message.sm != null) {
                        applied.mutedMe = message.sm && !mySm;
                        applied.changed = message.sm != mySm;
                        mySm = message.sm;
                    }
                } else {
                    Person before = people.get(u);
                    if (before != null) {
                        Person after = before.with(message.mute, message.deaf, message.sm, message.q);
                        people.put(u, after);
                        applied.changed = !after.equals(before);
                        applied.playback = after.sm != before.sm;
                    }
                }
                break;
            }
            case ROOM:
                name = message.name;
                leader = message.leader;
                mods = new ArrayList<>(message.mods);
                // VOICE-ROOMS: the crew changed the room's limit ("Oda dolu." follows it).
                if (message.max >= 0) max = message.max;
                applied.changed = true;
                break;
            case ME:
                applied.changed = message.role != myRole;
                myRole = message.role;
                break;
            case RING:
                if (message.to != null) {
                    ringing.put(message.to, message.until);
                    applied.changed = true;
                }
                break;
            case RING_END:
                if (message.to != null && ringing.remove(message.to) != null) applied.changed = true;
                break;
            default:
                break;
        }
        return applied;
    }

    /** Rings past their time (the room ends them too; this keeps the sheet honest meanwhile). */
    public boolean expireRings(long nowUnix) {
        boolean changed = false;
        // No removeIf: Android 5 and 6 have none (minSdk 21).
        for (java.util.Iterator<Long> it = ringing.values().iterator(); it.hasNext(); ) {
            if (it.next() <= nowUnix) {
                it.remove();
                changed = true;
            }
        }
        return changed;
    }

    /** The crew's "Genel". */
    public boolean isGeneral() {
        return id.startsWith("g");
    }
}
