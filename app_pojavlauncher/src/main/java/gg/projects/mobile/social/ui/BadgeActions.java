package gg.projects.mobile.social.ui;

import android.content.Context;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

import com.google.gson.JsonObject;

import net.kdt.pojavlaunch.R;

import gg.projects.mobile.social.BadgeRules;
import gg.projects.mobile.social.SocialModel.Badge;
import gg.projects.mobile.social.SocialModel.Crew;
import gg.projects.mobile.social.SocialProblem;
import gg.projects.mobile.social.SocialService;
import gg.projects.mobile.social.SocialState;
import gg.projects.mobile.ui.Haptics;

/**
 * TEAM6 (team6.md §3.2, §5): putting a crew tag on and taking it off. One request; the line says what
 * changed ("GK etiketini taktın.", "Etiketin GK oldu.", "Etiketini çıkardın.") with "Geri al", which
 * puts back the tag worn before (quietly: no second line). After joining or founding a crew while no
 * tag is worn, the line that says so carries "Etiketi tak" (the tag is never put on by itself).
 */
final class BadgeActions {
    private BadgeActions() {}

    /** Wears {@code crewId}'s tag, or takes the worn one off (null). */
    static void wear(final FragmentActivity activity, @Nullable final String crewId, @Nullable final View feedback) {
        final SocialService service = SocialService.get(activity);
        final String me = service.state().myId();
        Badge before = me == null ? null : service.state().badgeOf(me);
        final String beforeCrew = before == null ? null : before.crew;
        if (feedback != null) Haptics.press(feedback);
        service.setBadge(crewId, new SocialService.Result<JsonObject>() {
            @Override
            public void ok(JsonObject value) {
                if (feedback != null) Haptics.confirm(feedback);
                Badge after = me == null ? null : service.state().badgeOf(me);
                BadgeRules.Outcome outcome = BadgeRules.wearOutcome(beforeCrew, after == null ? null : after.crew);
                if (outcome == null) return;
                String text;
                switch (outcome) {
                    case WORE:
                        text = activity.getString(R.string.projects_badge_wore, after.tag);
                        break;
                    case SWITCHED:
                        text = activity.getString(R.string.projects_badge_switched, after.tag);
                        break;
                    case OFF:
                    default:
                        text = activity.getString(R.string.projects_badge_off);
                        break;
                }
                ActionToast.show(activity, text, activity.getString(R.string.projects_undo), () -> putBack(activity, beforeCrew));
            }

            @Override
            public void failed(SocialProblem problem) {
                if (feedback != null) Haptics.reject(feedback);
                SocialUi.toast(activity, problemText(activity, problem));
            }
        });
    }

    /** "Geri al": the tag worn before, without a second line (a failure still says why). */
    private static void putBack(final FragmentActivity activity, @Nullable String crewId) {
        SocialService.get(activity).setBadge(crewId, new SocialService.Result<JsonObject>() {
            @Override
            public void ok(JsonObject value) {
                // The lists show it.
            }

            @Override
            public void failed(SocialProblem problem) {
                SocialUi.toast(activity, problemText(activity, problem));
            }
        });
    }

    /** A refusal while putting a tag on (Ek B), else the usual line. */
    static String problemText(Context context, SocialProblem problem) {
        switch (problem.kind) {
            case TEXT_LOCKED:
                if (problem.until != null) return context.getString(R.string.projects_badge_locked_why, SocialUi.day(context, problem.until));
                return context.getString(R.string.projects_badge_locked_now);
            case CREW_NAME_REJECTED:
                return context.getString(R.string.projects_badge_error_name_rejected);
            case NOT_IN_CREW:
            case NO_CREW_WITH_CODE:
                return context.getString(R.string.projects_badge_error_gone);
            default:
                return SocialUi.problemText(context, problem);
        }
    }

    /**
     * The line after joining or founding a crew ("Gece Kartalları ekibine katıldın"): with "Etiketi tak"
     * while tags are on, no tag is worn and the crew is not hidden; a plain toast otherwise.
     */
    static void joined(final FragmentActivity activity, String text, @Nullable final String crewId) {
        SocialService service = SocialService.get(activity);
        SocialState state = service.state();
        String me = state.myId();
        boolean offer = crewId != null && me != null && service.gate().badges && state.badgeOf(me) == null;
        if (offer) {
            Crew crew = state.crew(crewId);
            offer = crew == null || !crew.team.hidden;
        }
        if (!offer || activity.isFinishing()) {
            SocialUi.toast(activity, text);
            return;
        }
        ActionToast.show(activity, text, activity.getString(R.string.projects_badge_wear), () -> wear(activity, crewId, null));
    }
}
