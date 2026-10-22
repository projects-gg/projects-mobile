package gg.projects.mobile.social;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * FIX2: who answered a plan (Geliyorum / Belki / Gelemem) or a crew call (Geliyorum / Birazdan /
 * Gelemem), grouped in the answers' order with a count each, for the card's list that opens in place.
 * In a group the player comes first, then everyone by name. A crew holds at most 30, so a group shows
 * its first {@link #PREVIEW} (the desktop's five) and "N kişi daha" brings the rest. Pure Java.
 */
public final class AnswerPeople {
    private AnswerPeople() {}

    /** A group shows this many before "Tümünü göster". */
    public static final int PREVIEW = 5;

    /** The answers of a plan, in the card's order. */
    public static final String[] PLAN_ORDER = {SocialModel.Plan.YES, SocialModel.Plan.MAYBE, SocialModel.Plan.NO};
    /** The answers of a crew call, in the card's order. */
    public static final String[] CALL_ORDER = {"yes", "soon", "no"};

    /** A player's name as the screen shows it (null when not known). */
    public interface Names {
        @Nullable
        String nameOf(String userId);
    }

    public static final class Group {
        public final String answer;
        /** Everyone in it, in the order shown. */
        public final List<String> ids;

        Group(String answer, List<String> ids) {
            this.answer = answer;
            this.ids = Collections.unmodifiableList(ids);
        }

        public int count() {
            return ids.size();
        }

        /** More than {@link #PREVIEW}: "N kişi daha" is offered. */
        public boolean folds() {
            return ids.size() > PREVIEW;
        }

        /** Who shows: the first {@link #PREVIEW}, or all of them once opened. */
        public List<String> shown(boolean all) {
            return all || !folds() ? ids : ids.subList(0, PREVIEW);
        }

        /** How many "Tümünü göster" brings. */
        public int hidden() {
            return Math.max(0, ids.size() - PREVIEW);
        }
    }

    /** The groups that have someone, in {@code order}; the player first in theirs, then by name. */
    public static List<Group> of(Map<String, String> answers, String[] order, @Nullable final String me, final Names names) {
        List<Group> out = new ArrayList<>();
        for (String answer : order) {
            List<String> ids = new ArrayList<>();
            for (Map.Entry<String, String> e : answers.entrySet()) if (answer.equals(e.getValue())) ids.add(e.getKey());
            if (ids.isEmpty()) continue;
            Collections.sort(ids, new Comparator<String>() {
                @Override
                public int compare(String a, String b) {
                    boolean meA = a.equals(me);
                    boolean meB = b.equals(me);
                    if (meA != meB) return meA ? -1 : 1;
                    String na = names.nameOf(a);
                    String nb = names.nameOf(b);
                    // Unknown names last (a crewmate who left meanwhile), then by id.
                    if (na == null || nb == null) {
                        if (na != null) return -1;
                        if (nb != null) return 1;
                        return a.compareTo(b);
                    }
                    int byName = na.toLowerCase(Locale.ROOT).compareTo(nb.toLowerCase(Locale.ROOT));
                    return byName != 0 ? byName : a.compareTo(b);
                }
            });
            out.add(new Group(answer, ids));
        }
        return out;
    }

    /** How many answered at all (the folded line's count). */
    public static int total(List<Group> groups) {
        int n = 0;
        for (Group g : groups) n += g.count();
        return n;
    }
}
