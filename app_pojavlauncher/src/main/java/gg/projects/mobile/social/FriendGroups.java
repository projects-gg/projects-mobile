package gg.projects.mobile.social;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * MOB-PARITY: the player's friend categories (the desktop's {@code features/social/groups.ts}, the service's
 * {@code groups.ts}): named sections the player puts friends in, in the player's order, the same on every device. The
 * document comes in {@code GET /v1/friends} ({@code groups}, absent from a service without categories) and live in a
 * {@code groups} frame; {@code PUT /v1/friends/groups} replaces it whole ({@code {groups, members}}, last write wins).
 * Friends in no category (or in one that is gone) are in "Arkadaşlar", always last. Pure Java, immutable.
 */
public final class FriendGroups {
    /** The service's limits ({@code limits.groups}, {@code names.ts}). */
    public static final int MAX_GROUPS = 20;
    public static final int NAME_MAX = 24;
    public static final int MAX_MEMBERS = 200;
    /** The implicit last section ("Arkadaşlar"); never a category's id. */
    public static final String REST = "rest";
    private static final String ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int ID_LENGTH = 8;

    public static final class Group {
        public final String id;
        public final String name;

        public Group(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    public final long version;
    public final List<Group> groups;
    /** Friend id to category id. */
    public final Map<String, String> members;

    public static final FriendGroups EMPTY = new FriendGroups(0, Collections.<Group>emptyList(), Collections.<String, String>emptyMap());

    public FriendGroups(long version, List<Group> groups, Map<String, String> members) {
        this.version = version;
        this.groups = Collections.unmodifiableList(new ArrayList<>(groups));
        this.members = Collections.unmodifiableMap(new LinkedHashMap<>(members));
    }

    /** The document as the service sends it; null when there is none (a service without categories). */
    @Nullable
    public static FriendGroups read(@Nullable JsonObject o) {
        if (o == null) return null;
        List<Group> groups = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonObject g : Json.objects(o, "groups")) {
            String id = Json.str(g, "id");
            String name = cleanName(Json.str(g, "name"));
            if (!validId(id) || name.isEmpty() || !ids.add(id)) continue;
            groups.add(new Group(id, name));
        }
        Map<String, String> members = new LinkedHashMap<>();
        JsonObject m = Json.obj(o, "members");
        if (m != null) {
            for (Map.Entry<String, JsonElement> e : m.entrySet()) {
                if (!e.getValue().isJsonPrimitive()) continue;
                String group = e.getValue().getAsString();
                if (SocialModel.isUserId(e.getKey()) && ids.contains(group)) members.put(e.getKey(), group);
            }
        }
        return new FriendGroups(Json.num(o, "version", 0), groups, members);
    }

    /** {@code PUT /v1/friends/groups}'s body: {@code {groups, members}} and nothing else. */
    public JsonObject body() {
        JsonObject o = new JsonObject();
        JsonArray list = new JsonArray();
        for (Group g : groups) {
            JsonObject item = new JsonObject();
            item.addProperty("id", g.id);
            item.addProperty("name", g.name);
            list.add(item);
        }
        o.add("groups", list);
        JsonObject m = new JsonObject();
        for (Map.Entry<String, String> e : members.entrySet()) m.addProperty(e.getKey(), e.getValue());
        o.add("members", m);
        return o;
    }

    /** What the screens compare: the categories and who is where (not the version). */
    public String key() {
        StringBuilder b = new StringBuilder();
        for (Group g : groups) b.append(g.id).append('=').append(g.name).append(';');
        b.append('|');
        for (Map.Entry<String, String> e : members.entrySet()) b.append(e.getKey()).append('>').append(e.getValue()).append(';');
        return b.toString();
    }

    public boolean hasGroups() {
        return !groups.isEmpty();
    }

    public boolean full() {
        return groups.size() >= MAX_GROUPS;
    }

    @Nullable
    public Group group(@Nullable String id) {
        if (id == null) return null;
        for (Group g : groups) if (g.id.equals(id)) return g;
        return null;
    }

    /** The category a friend is in; null for "Arkadaşlar". */
    @Nullable
    public String groupOf(String friendId) {
        String id = members.get(friendId);
        return group(id) == null ? null : id;
    }

    /** A new category at the end, or {@code this} when the name is empty or there are twenty already. */
    public FriendGroups withNew(String name, Random random) {
        String clean = cleanName(name);
        if (clean.isEmpty() || full()) return this;
        List<Group> next = new ArrayList<>(groups);
        next.add(new Group(newId(random), clean));
        return new FriendGroups(version, next, members);
    }

    /** The id the last {@link #withNew} gave (the new category is always last). */
    @Nullable
    public String lastId() {
        return groups.isEmpty() ? null : groups.get(groups.size() - 1).id;
    }

    public FriendGroups withName(String id, String name) {
        String clean = cleanName(name);
        if (clean.isEmpty() || group(id) == null) return this;
        List<Group> next = new ArrayList<>();
        for (Group g : groups) next.add(g.id.equals(id) ? new Group(id, clean) : g);
        return new FriendGroups(version, next, members);
    }

    /** Without the category; its friends go back to "Arkadaşlar". */
    public FriendGroups without(String id) {
        if (group(id) == null) return this;
        List<Group> next = new ArrayList<>();
        for (Group g : groups) if (!g.id.equals(id)) next.add(g);
        Map<String, String> m = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : members.entrySet()) if (!e.getValue().equals(id)) m.put(e.getKey(), e.getValue());
        return new FriendGroups(version, next, m);
    }

    /** The category one place up ({@code step} -1) or down (+1); {@code this} at an end. */
    public FriendGroups moved(String id, int step) {
        int at = -1;
        for (int i = 0; i < groups.size(); i++) if (groups.get(i).id.equals(id)) at = i;
        int to = at + Integer.signum(step);
        if (at < 0 || to < 0 || to >= groups.size()) return this;
        List<Group> next = new ArrayList<>(groups);
        Collections.swap(next, at, to);
        return new FriendGroups(version, next, members);
    }

    /** The friend in {@code groupId}, or back in "Arkadaşlar" ({@code null} or {@link #REST}). */
    public FriendGroups withMember(String friendId, @Nullable String groupId) {
        Map<String, String> m = new LinkedHashMap<>(members);
        if (groupId == null || REST.equals(groupId) || group(groupId) == null) m.remove(friendId);
        else m.put(friendId, groupId);
        return new FriendGroups(version, groups, m);
    }

    /** Without people who are no longer friends (the desktop prunes before every save). */
    public FriendGroups pruned(Set<String> friendIds) {
        Map<String, String> m = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : members.entrySet()) {
            if (friendIds.contains(e.getKey()) && group(e.getValue()) != null && m.size() < MAX_MEMBERS) m.put(e.getKey(), e.getValue());
        }
        return m.size() == members.size() ? this : new FriendGroups(version, groups, m);
    }

    public FriendGroups withVersion(long newVersion) {
        return new FriendGroups(newVersion, groups, members);
    }

    /** Whether the service takes it (its own checks, so a refusal never surprises). */
    public boolean valid() {
        if (groups.size() > MAX_GROUPS || members.size() > MAX_MEMBERS) return false;
        Set<String> ids = new HashSet<>();
        for (Group g : groups) {
            if (!validId(g.id) || !ids.add(g.id)) return false;
            int length = g.name.codePointCount(0, g.name.length());
            if (length < 1 || length > NAME_MAX) return false;
        }
        for (Map.Entry<String, String> e : members.entrySet()) {
            if (!SocialModel.isUserId(e.getKey()) || !ids.contains(e.getValue())) return false;
        }
        return true;
    }

    static boolean validId(@Nullable String id) {
        return id != null && id.matches("[a-z0-9]{1,12}") && !REST.equals(id);
    }

    private String newId(Random random) {
        Set<String> taken = new HashSet<>();
        for (Group g : groups) taken.add(g.id);
        while (true) {
            StringBuilder b = new StringBuilder(ID_LENGTH);
            for (int i = 0; i < ID_LENGTH; i++) b.append(ID_CHARS.charAt(random.nextInt(ID_CHARS.length())));
            String id = b.toString();
            if (!REST.equals(id) && !taken.contains(id)) return id;
        }
    }

    /** NFC, control and format characters out, spaces collapsed, trimmed, at most 24 characters (the service's rule). */
    public static String cleanName(@Nullable String name) {
        if (name == null) return "";
        String n = Normalizer.normalize(name, Normalizer.Form.NFC);
        StringBuilder b = new StringBuilder();
        boolean space = false;
        for (int i = 0; i < n.length(); ) {
            int cp = n.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                space = b.length() > 0;
                continue;
            }
            if (type == Character.CONTROL || type == Character.FORMAT || type == Character.UNASSIGNED
                    || type == Character.SURROGATE || type == Character.PRIVATE_USE) {
                continue;
            }
            if (space) b.append(' ');
            space = false;
            b.appendCodePoint(cp);
        }
        String out = b.toString();
        if (out.codePointCount(0, out.length()) > NAME_MAX) out = out.substring(0, out.offsetByCodePoints(0, NAME_MAX)).trim();
        return out;
    }

    /** The friends of one section in the list's order ({@code ordered}: in game, on, off; favourites first in each). */
    public static <F> List<F> inGroup(List<F> ordered, FriendGroups doc, @Nullable String groupId, IdOf<F> idOf) {
        List<F> out = new ArrayList<>();
        for (F f : ordered) {
            String in = doc.groupOf(idOf.id(f));
            if (groupId == null ? in == null : groupId.equals(in)) out.add(f);
        }
        return out;
    }

    public interface IdOf<F> {
        String id(F friend);
    }
}
