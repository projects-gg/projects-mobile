package gg.projects.mobile.social;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import gg.projects.mobile.social.SocialModel.Permissions;

/**
 * MOB-PARITY "Yetkiler" and "Düzenle" (the desktop's {@code teams.ts} and the service's {@code roles.ts}): which rights a
 * crew's leader sets, how a chip changes a right's level, and who may edit the crew. A right's level is {@code leader},
 * {@code moderators} (and the leader) or {@code members} (everyone); removing members, editing the look and managing
 * requests never go to plain members. Pure Java.
 */
public final class CrewRoleRules {
    private CrewRoleRules() {}

    public static final class Right {
        public final String key;
        /** Plain members may get it. */
        public final boolean members;

        Right(String key, boolean members) {
            this.key = key;
            this.members = members;
        }
    }

    /** The rights in the desktop's order; managing requests only while the service has them. */
    public static List<Right> rights(boolean requests) {
        List<Right> out = new ArrayList<>();
        out.add(new Right("invite", true));
        out.add(new Right("call", true));
        out.add(new Right("kick", false));
        out.add(new Right("edit", false));
        if (requests) out.add(new Right("admit", false));
        return Collections.unmodifiableList(out);
    }

    public static String level(Permissions p, String key) {
        switch (key) {
            case "invite": return p.invite;
            case "call": return p.call;
            case "kick": return p.kick;
            case "edit": return p.edit;
            default: return p.admit;
        }
    }

    public static boolean moderatorsMay(String level) {
        return Permissions.MODERATORS.equals(level) || Permissions.MEMBERS.equals(level);
    }

    public static boolean membersMay(String level) {
        return Permissions.MEMBERS.equals(level);
    }

    /** "Moderatörler" tapped: on gives moderators the right; off takes it from everyone but the leader. */
    public static String toggleModerators(String level) {
        return moderatorsMay(level) ? Permissions.LEADER : Permissions.MODERATORS;
    }

    /** "Üyeler" tapped: on gives everyone the right (moderators too); off leaves it with moderators. */
    public static String toggleMembers(String level) {
        return membersMay(level) ? Permissions.MODERATORS : Permissions.MEMBERS;
    }

    /**
     * "Düzenle": the leader always (name and tag are theirs); a moderator or member when the crew's {@code edit} right
     * lets them and the service has roles (then only the look and motto).
     */
    public static boolean mayEdit(SocialModel.Team team, String me, boolean roles) {
        SocialModel.Member m = team.member(me);
        if (m == null) return false;
        if (m.founder) return true;
        return roles && Permissions.allows(team.permissions.edit, false, m.moderator);
    }

    // CREW-MODS (0.5.4): a leader names as many moderators as they like; the service no longer has a cap.
}
