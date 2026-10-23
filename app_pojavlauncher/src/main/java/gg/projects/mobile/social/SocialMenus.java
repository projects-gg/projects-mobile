package gg.projects.mobile.social;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * FIX2: what a long press (or "⋮") on a player, a crew or a plan offers, wherever it shows, decided
 * once here (the desktop's {@code menus.ts}, FIX2-D's table in {@code docs/tasks/fix2-d.md}): the same
 * groups in the same order everywhere, only what the rules and the player's rights allow, a separator
 * ({@code null}) only between groups that have rows. The screens add the words, icons and actions.
 * Pure Java.
 *
 * <p>Different from the desktop on purpose (the phone does not do these at all): "Birlikte oyna" (the
 * launcher is not on a server while its screens show), "Çevrimiçi olunca bildir" (no socket in the
 * background), friend categories, naming moderators and editing the crew (they stay on the desktop,
 * M19), calling the crew (CALL1).
 */
public final class SocialMenus {
    private SocialMenus() {}

    /** One row of a menu: an action, maybe dim (shown, not offered now), maybe a checked choice. */
    public static final class Row<A> {
        public final A action;
        public final boolean disabled;

        Row(A action, boolean disabled) {
            this.action = action;
            this.disabled = disabled;
        }

        @Override
        public String toString() {
            return disabled ? action + "(off)" : String.valueOf(action);
        }
    }

    private static <A> Row<A> row(A action) {
        return new Row<>(action, false);
    }

    private static <A> Row<A> row(A action, boolean disabled) {
        return new Row<>(action, disabled);
    }

    /** Separators only between groups that have rows (none first, last or twice). */
    static <A> List<Row<A>> tidy(List<Row<A>> plan) {
        List<Row<A>> out = new ArrayList<>();
        for (Row<A> r : plan) {
            if (r == null && (out.isEmpty() || out.get(out.size() - 1) == null)) continue;
            out.add(r);
        }
        while (!out.isEmpty() && out.get(out.size() - 1) == null) out.remove(out.size() - 1);
        return Collections.unmodifiableList(out);
    }

    // ---- a player ----

    public enum PersonAction {
        /** A friend request to the player, or a join request to a crew the player answers for. */
        ACCEPT, DECLINE,
        /** The player's own waiting friend request ("İsteği geri al"). */
        CANCEL_REQUEST,
        /** They play on projects.gg. */
        JOIN,
        /** A direct conversation (CHAT1; friends only). */
        MESSAGE,
        DISCORD,
        /** FIX2 ({@code crewFriendRequests}): a crewmate who is not a friend, without their code. */
        ADD_FRIEND,
        /** ...the request went (dim). */
        REQUEST_SENT,
        /** ...they asked the player first. */
        ACCEPT_REQUEST,
        FAVOURITE, UNFAVOURITE,
        /** "Ekibe davet et" (several crews: which one). */
        CREW_INVITE,
        /** In a crew's context, the player leads it. */
        HAND_OVER,
        /** In a crew's context: the leader anyone, a moderator (as the crew lets them) plain members. */
        REMOVE_FROM_CREW,
        REMOVE_FRIEND,
        BLOCK, UNBLOCK,
        REPORT
    }

    /** What a row asks the player to answer, when it is about a request. */
    public enum Pending { NONE, FRIEND_REQUEST, JOIN_REQUEST, OUTGOING }

    /**
     * "Ekibe davet et" (the desktop's rule): none (teams off, no crew lets the player invite, the friend is in
     * every such crew), open, or dim: already invited to the crews they are not in ("Davet edildi"), or those are
     * full.
     */
    public enum Invite { NONE, OPEN, INVITED, FULL }

    /** Who the row shows and what the player may do about them. */
    public static final class PersonInput {
        public boolean self;
        public boolean friend;
        public boolean crewmate;
        /** The player blocked them. */
        public boolean blocked;
        /** They sent the player a friend request that waits. */
        public boolean incoming;
        /** The player sent them one that waits. */
        public boolean outgoing;
        /** The row is about one of the requests above, or a join request. */
        public Pending pending = Pending.NONE;
        /** They are on projects.gg. */
        public boolean onProjects;
        /** Chat is on (CHAT1). */
        public boolean chat;
        /** The menu is in that friend's own conversation: no "Mesaj gönder". */
        public boolean inTheirConversation;
        /** The service has {@code crewFriendRequests}. */
        public boolean crewFriendRequests;
        public boolean favourite;
        public Invite crewInvite = Invite.NONE;
        /** In a crew's context (its page, plan, call, chat): the player leads that crew and they are in it. */
        public boolean leaderHere;
        /** In a crew's context: the player may remove them from it. */
        public boolean removableHere;
        /** Reports are on (MOD1). */
        public boolean reports;
        /** The service takes a report about them (a friend, a crewmate, a request's sender, a join request's). */
        public boolean reportable;
    }

    public static List<Row<PersonAction>> person(PersonInput in) {
        List<Row<PersonAction>> out = new ArrayList<>();
        if (in.self) return Collections.unmodifiableList(out);
        if (in.blocked) {
            // Someone the player blocked: Discord, unblock and report only.
            if (in.friend || in.crewmate) out.add(row(PersonAction.DISCORD));
            out.add(null);
            out.add(row(PersonAction.UNBLOCK));
            if (in.reports && in.reportable) out.add(row(PersonAction.REPORT));
            return tidy(out);
        }
        // 1. The request the row is about.
        switch (in.pending) {
            case FRIEND_REQUEST:
            case JOIN_REQUEST:
                out.add(row(PersonAction.ACCEPT));
                out.add(row(PersonAction.DECLINE));
                break;
            case OUTGOING:
                out.add(row(PersonAction.CANCEL_REQUEST));
                break;
            default:
                break;
        }
        out.add(null);
        // 2. Together.
        if (in.onProjects) out.add(row(PersonAction.JOIN));
        if (in.friend && in.chat && !in.inTheirConversation) out.add(row(PersonAction.MESSAGE));
        if (in.friend || in.crewmate) out.add(row(PersonAction.DISCORD));
        if (in.crewmate && !in.friend && in.crewFriendRequests) {
            if (in.incoming) {
                if (in.pending != Pending.FRIEND_REQUEST) out.add(row(PersonAction.ACCEPT_REQUEST));
            } else if (in.outgoing) {
                if (in.pending != Pending.OUTGOING) out.add(row(PersonAction.REQUEST_SENT, true));
            } else {
                out.add(row(PersonAction.ADD_FRIEND));
            }
        }
        out.add(null);
        // 3. Friend.
        if (in.friend) {
            out.add(row(in.favourite ? PersonAction.UNFAVOURITE : PersonAction.FAVOURITE));
            if (in.crewInvite != Invite.NONE) out.add(row(PersonAction.CREW_INVITE, in.crewInvite != Invite.OPEN));
        }
        out.add(null);
        // 4. The crew whose context the menu is in.
        if (in.leaderHere) out.add(row(PersonAction.HAND_OVER));
        if (in.removableHere) out.add(row(PersonAction.REMOVE_FROM_CREW));
        out.add(null);
        // 5. Safety.
        if (in.friend) out.add(row(PersonAction.REMOVE_FRIEND));
        out.add(row(PersonAction.BLOCK));
        if (in.reports && in.reportable) out.add(row(PersonAction.REPORT));
        return tidy(out);
    }

    // ---- a crew ----

    public enum CrewAction {
        OPEN, CHAT, COPY_CODE, INVITE, MAKE_MAIN, MOVE_LEFT, MOVE_RIGHT, MUTE, UNMUTE, REPORT, LEAVE, CLOSE, DISBAND
    }

    public enum Mute { HIDDEN, MUTED, UNMUTED }

    public static final class CrewInput {
        /** The menu is on the crew's own page (nothing to open). */
        public boolean onItsPage;
        /** Its chat is there (CHAT1). */
        public boolean chat;
        public boolean hasCode;
        /** The crew's permissions let the player invite. */
        public boolean mayInvite;
        /** Dim "Ekibe davet et": the crew is full or every friend is in it. */
        public boolean inviteDim;
        /** Several crews (TEAM4): where this one stands; null with one crew. */
        @Nullable
        public Boolean first;
        public boolean last;
        public Mute mute = Mute.HIDDEN;
        public boolean reports;
        /** The player leads it. */
        public boolean leader;
        /** Its only member. */
        public boolean alone;
    }

    public static List<Row<CrewAction>> crew(CrewInput in) {
        List<Row<CrewAction>> out = new ArrayList<>();
        if (!in.onItsPage) out.add(row(CrewAction.OPEN));
        out.add(null);
        if (in.chat) out.add(row(CrewAction.CHAT));
        if (in.hasCode) out.add(row(CrewAction.COPY_CODE));
        if (in.mayInvite) out.add(row(CrewAction.INVITE, in.inviteDim));
        out.add(null);
        if (in.first != null) {
            if (!in.first) out.add(row(CrewAction.MAKE_MAIN));
            out.add(row(CrewAction.MOVE_LEFT, in.first));
            out.add(row(CrewAction.MOVE_RIGHT, in.last));
        }
        out.add(null);
        if (in.mute != Mute.HIDDEN) out.add(row(in.mute == Mute.MUTED ? CrewAction.UNMUTE : CrewAction.MUTE));
        out.add(null);
        // The lead's rule (as the desktop's 03f1689): a leader does not report their own crew; members and moderators do.
        if (in.reports && !in.leader) out.add(row(CrewAction.REPORT));
        out.add(row(!in.leader ? CrewAction.LEAVE : in.alone ? CrewAction.CLOSE : CrewAction.DISBAND));
        return tidy(out);
    }

    // ---- a plan ----

    public enum PlanAction { ADD_NOTE, EDIT_NOTE, REMOVE_NOTE, REPORT, CANCEL }

    public static final class PlanInput {
        /** The service has {@code planNotes}. */
        public boolean notes;
        /** The player made it. */
        public boolean maker;
        /** It is still ahead (the maker writes its note until it starts, FIX2-D's rule). */
        public boolean upcoming;
        public boolean hasNote;
        /** The player leads the crew or moderates it. */
        public boolean crewMod;
        public boolean reports;
        /** Its maker, the leader or a moderator. */
        public boolean mayCancel;
    }

    public static List<Row<PlanAction>> plan(PlanInput in) {
        List<Row<PlanAction>> out = new ArrayList<>();
        if (in.notes && in.maker && in.upcoming) out.add(row(in.hasNote ? PlanAction.EDIT_NOTE : PlanAction.ADD_NOTE));
        if (in.notes && in.hasNote && (in.maker || in.crewMod)) out.add(row(PlanAction.REMOVE_NOTE));
        out.add(null);
        if (in.reports && !in.maker) out.add(row(PlanAction.REPORT));
        if (in.mayCancel) out.add(row(PlanAction.CANCEL));
        return tidy(out);
    }
}
