package gg.projects.mobile.social.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.FragmentActivity;

import com.google.gson.JsonObject;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import gg.projects.mobile.mods.Motion;
import gg.projects.mobile.social.BadgeRules;
import gg.projects.mobile.social.SocialModel;
import gg.projects.mobile.social.SocialModel.Badge;
import gg.projects.mobile.social.SocialModel.Crew;
import gg.projects.mobile.social.SocialProblem;
import gg.projects.mobile.social.SocialService;
import gg.projects.mobile.social.SocialState;
import gg.projects.mobile.ui.AppDates;
import gg.projects.mobile.ui.ChamferDrawable;
import gg.projects.mobile.ui.Haptics;
import gg.projects.mobile.ui.Press;

/**
 * TEAM6 (team6.md §4; Android: a sheet): the card of a crew tag, opened from a tag that stands alone
 * (a friend's card, a direct conversation's title, the player's own card). The crew's mark, name and
 * tag, its motto (two lines at most), "Kuruluş Eylül 2026, 12 üye"; never its members, leader, code or
 * link. One row at its bottom, or none: the player's own tag ("Etiketi çıkar", "Ekibi aç"), a crew the
 * player is in ("Sen de bu ekiptesin", "Ekibi aç"), invited to ("Kabul et"), waiting for ("İsteğin
 * bekliyor", "Geri çek"), or "Katılma isteği gönder" when the request may go (a friend's tag, the crew
 * takes requests from it, the player's limits allow): never a greyed button. A refusal is one quiet
 * line and the button stays away on this phone for that crew until the Turkey day ends. "⋯" holds the
 * crew's menu for its members and "Ekibi rapor et" for everyone else. Opening it sends nothing; it
 * follows live changes and goes when the tag does.
 */
final class BadgeCard {
    private BadgeCard() {}

    private static final String PREFS = "projects_badges";
    private static final String REFUSED = "refused";

    /** A card's title chip (LG; a friend's or a member's card): a button to the tag's card. */
    static void bindTitleChip(final FragmentActivity activity, CrewTagChip chip, final String wearerId) {
        SocialService service = SocialService.get(activity);
        Badge badge = BadgeRules.shown(service.gate(), service.state().badgeOf(wearerId));
        chip.setBadge(badge, CrewTagChip.Size.LG, false);
        if (badge != null) chip.makeButton(badge, v -> show(activity, wearerId));
    }

    static void show(final FragmentActivity activity, final String wearerId) {
        final SocialService service = SocialService.get(activity);
        if (!service.gate().badges || service.state().badgeOf(wearerId) == null) return;
        final LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        final BottomPanel panel = BottomPanel.of(activity, content, 0.6f);
        final String[] line = {null};
        final boolean[] sent = {false};
        final SocialService.Listener listener = new SocialService.Listener() {
            @Override
            public void onSocialChanged() {
                if (!render(activity, panel, content, wearerId, line, sent)) panel.dismiss();
            }
        };
        panel.onClosed(() -> service.removeListener(listener));
        service.addListener(listener);
        if (!render(activity, panel, content, wearerId, line, sent)) {
            service.removeListener(listener);
            return;
        }
        panel.show();
    }

    /** Draws the card as it is now; false when the tag is gone (the sheet goes). */
    private static boolean render(final FragmentActivity activity, final BottomPanel panel, final LinearLayout content,
                                  final String wearerId, final String[] line, final boolean[] sent) {
        final SocialService service = SocialService.get(activity);
        final SocialState state = service.state();
        final String me = state.myId();
        final Badge badge = state.badgeOf(wearerId);
        if (badge == null || me == null || !service.gate().badges) return false;
        Context context = activity;
        float dp = context.getResources().getDisplayMetrics().density;
        final BadgeRules.CardInput in = input(context, service, badge, wearerId);
        final BadgeRules.Row row = BadgeRules.cardRow(in);
        // Only a change it shows redraws it: the service changes often (typing, presence), and a rebuilt card
        // would take its button from under the finger.
        String key = badge.crew + "|" + badge.tag + "|" + badge.color + "|" + badge.frame + "|" + badge.emblem + "|" + badge.name
                + "|" + badge.motto + "|" + badge.createdOn + "|" + badge.members + "|" + badge.requests + "|" + row + "|" + line[0]
                + "|" + sent[0] + "|" + metaLine(context, service, badge, row);
        if (key.equals(content.getTag(R.id.projects_chat_state)) && content.getChildCount() > 0) return true;
        content.setTag(R.id.projects_chat_state, key);
        content.removeAllViews();
        content.setPadding(Math.round(18 * dp), Math.round(2 * dp), Math.round(12 * dp), Math.round(16 * dp));

        // The head: the mark, the name and the tag, the menu.
        LinearLayout head = new LinearLayout(context);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        CrewMarkView mark = new CrewMarkView(context);
        mark.setLook(badge.emblem, badge.color, badge.frame);
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        head.addView(mark, new LinearLayout.LayoutParams(Math.round(44 * dp), Math.round(44 * dp)));
        LinearLayout names = new LinearLayout(context);
        names.setOrientation(LinearLayout.HORIZONTAL);
        names.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(context);
        name.setText(badge.name);
        name.setTextSize(17);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setTextColor(ContextCompat.getColor(context, R.color.projects_bone_50));
        try {
            name.setTypeface(ResourcesCompat.getFont(context, R.font.tektur_semibold));
        } catch (RuntimeException ignored) {
            // The theme's font.
        }
        ViewCompat.setAccessibilityHeading(name, true);
        names.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        CrewTagChip chip = CrewTagChip.make(context, CrewTagChip.Size.MD);
        chip.setBadge(badge, CrewTagChip.Size.MD, false);
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipLp.setMarginStart(Math.round(8 * dp));
        names.addView(chip, chipLp);
        LinearLayout.LayoutParams namesLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        namesLp.setMarginStart(Math.round(12 * dp));
        head.addView(names, namesLp);
        ImageButton more = new ImageButton(context);
        more.setImageResource(R.drawable.projects_ic_dots);
        more.setImageTintList(ContextCompat.getColorStateList(context, R.color.projects_ash_300));
        more.setBackgroundResource(R.drawable.projects_icon_button_ripple);
        more.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = Math.round(12 * dp);
        more.setPadding(pad, pad, pad, pad);
        more.setContentDescription(context.getString(R.string.projects_menu_options));
        Press.attach(more, Press.Style.ICON);
        more.setOnClickListener(v -> {
            if (BadgeRules.menuIsCrews(row)) {
                panel.dismiss();
                SocialMenuSheet.crew(activity, badge.crew, false, false, v);
            } else {
                panel.dismiss();
                ReportDialog.crewFromTag(activity, summary(badge), wearerId);
            }
        });
        head.addView(more, new LinearLayout.LayoutParams(Math.round(48 * dp), Math.round(48 * dp)));
        content.addView(head);

        // The motto and the line under it.
        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(Math.round(56 * dp), 0, Math.round(8 * dp), 0);
        if (!badge.motto.isEmpty()) {
            TextView motto = new TextView(context);
            motto.setText(badge.motto);
            motto.setTextSize(14);
            motto.setMaxLines(2);
            motto.setEllipsize(android.text.TextUtils.TruncateAt.END);
            motto.setTextColor(ContextCompat.getColor(context, R.color.projects_ash_300));
            texts.addView(motto);
        }
        TextView meta = new TextView(context);
        meta.setTextSize(13);
        meta.setTextColor(ContextCompat.getColor(context, R.color.projects_ash_400));
        meta.setPadding(0, Math.round(4 * dp), 0, 0);
        meta.setText(metaLine(context, service, badge, row));
        texts.addView(meta);
        content.addView(texts);

        // The row (one cell: one thing turns into the next in place).
        LinearLayout bottom = new LinearLayout(context);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setMinimumHeight(Math.round(52 * dp));
        bottom.setPadding(Math.round(4 * dp), Math.round(14 * dp), 0, 0);
        bottom.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        final Crew crew = state.crew(badge.crew);
        switch (row) {
            case OWN:
                bottom.addView(button(context, R.string.projects_badge_take_off, false, v -> {
                    panel.dismiss();
                    BadgeActions.wear(activity, null, v);
                }), weight());
                bottom.addView(button(context, R.string.projects_menu_open_crew, true, v -> {
                    panel.dismiss();
                    openCrew(activity, badge.crew);
                }), weightStart(dp));
                break;
            case MEMBER:
                bottom.addView(quiet(context, context.getString(R.string.projects_badge_member)), weight());
                bottom.addView(button(context, R.string.projects_menu_open_crew, true, v -> {
                    panel.dismiss();
                    openCrew(activity, badge.crew);
                }), wrap());
                break;
            case INVITED:
                bottom.addView(quiet(context, context.getString(R.string.projects_badge_invited)), weight());
                bottom.addView(button(context, R.string.projects_social_accept, true, v -> {
                    Haptics.press(v);
                    service.acceptTeamInvite(badge.crew, new SocialService.Result<JsonObject>() {
                        @Override
                        public void ok(JsonObject value) {
                            Haptics.confirm(v);
                        }

                        @Override
                        public void failed(SocialProblem problem) {
                            SocialUi.toastProblem(activity, problem, v);
                        }
                    });
                }), wrap());
                break;
            case WAITING: {
                String waiting = sent[0] ? context.getString(R.string.projects_requests_sent) : context.getString(R.string.projects_requests_pending);
                bottom.addView(quiet(context, waiting), weight());
                bottom.addView(button(context, R.string.projects_requests_withdraw, false, v -> {
                    Haptics.press(v);
                    service.withdrawJoinRequest(badge.crew, new SocialService.Result<Void>() {
                        @Override
                        public void ok(Void value) {
                            sent[0] = false;
                        }

                        @Override
                        public void failed(SocialProblem problem) {
                            SocialUi.toastProblem(activity, problem, v);
                        }
                    });
                }), wrap());
                break;
            }
            case ASK:
                if (line[0] != null) {
                    bottom.addView(quiet(context, line[0]), weight());
                    break;
                }
                // The service's answer redraws the card itself: a refusal changes no state the listener would see.
                final Runnable redraw = () -> {
                    if (panel.showing() && !render(activity, panel, content, wearerId, line, sent)) panel.dismiss();
                };
                bottom.addView(button(context, R.string.projects_requests_send, true, v -> ask(activity, badge, wearerId, v, line, sent, redraw)),
                        weight());
                break;
            case NONE:
            default:
                if (line[0] != null) bottom.addView(quiet(context, line[0]), weight());
                break;
        }
        if (bottom.getChildCount() > 0) content.addView(bottom);
        return true;
    }

    /** "Katılma isteği gönder": the request waits for the crew's answer; a refusal says why, quietly, in place. */
    private static void ask(final FragmentActivity activity, final Badge badge, String wearerId, final View button,
                            final String[] line, final boolean[] sent, final Runnable redraw) {
        button.setEnabled(false);
        Haptics.press(button);
        SocialService.get(activity).requestFromBadge(badge.crew, wearerId, new SocialService.Result<JsonObject>() {
            @Override
            public void ok(JsonObject value) {
                Haptics.confirm(button);
                sent[0] = true;
                redraw.run();
            }

            @Override
            public void failed(SocialProblem problem) {
                button.setEnabled(true);
                BadgeRules.Refusal refusal = BadgeRules.refusalOf(problem.code);
                if (refusal == null) {
                    SocialUi.toastProblem(activity, problem, button);
                    return;
                }
                Haptics.reject(button);
                if (refusal != BadgeRules.Refusal.MEMBER) {
                    long now = System.currentTimeMillis();
                    remember(activity, badge.crew, BadgeRules.turkeyDayEnd(now), now);
                }
                line[0] = refusalText(activity, refusal);
                redraw.run();
            }
        });
    }

    private static String refusalText(Context context, BadgeRules.Refusal refusal) {
        switch (refusal) {
            case CLOSED: return context.getString(R.string.projects_badge_refused_closed);
            case TODAY: return context.getString(R.string.projects_badge_refused_today);
            case WAITING_FULL: return context.getString(R.string.projects_badge_refused_waiting_full);
            case FULL: return context.getString(R.string.projects_badge_refused_full);
            case BLOCKED: return context.getString(R.string.projects_badge_refused_blocked);
            case OWN_DAILY: return context.getString(R.string.projects_badge_refused_own_daily);
            case OWN_WAITING: return context.getString(R.string.projects_social_error_too_many_join_requests);
            case OWN_CREWS: {
                int limit = SocialService.get(context).state().crewLimit();
                return limit > 0 ? context.getResources().getQuantityString(R.plurals.projects_crews_limit, limit, limit)
                        : context.getString(R.string.projects_social_error_crew_limit);
            }
            case MEMBER:
            default:
                return context.getString(R.string.projects_badge_member);
        }
    }

    /** The player's side of the card's rules (their crews, invites, waiting requests, limits, this phone's refusals). */
    private static BadgeRules.CardInput input(Context context, SocialService service, Badge badge, String wearerId) {
        SocialState state = service.state();
        long now = SocialService.nowSeconds();
        BadgeRules.CardInput in = new BadgeRules.CardInput();
        in.crew = badge.crew;
        in.requests = badge.requests;
        in.own = wearerId.equals(state.myId());
        in.friend = state.isFriend(wearerId);
        in.crews = state.crewIds();
        in.crewLimit = state.crewLimit();
        in.multi = service.gate().multi;
        List<String> invites = new ArrayList<>();
        for (SocialModel.TeamInvite t : state.teamInvites()) invites.add(t.team.id);
        in.invites = invites;
        List<String> waiting = new ArrayList<>();
        for (SocialModel.MyRequest r : state.myRequests(now)) waiting.add(r.team.id);
        in.waiting = waiting;
        in.requestsOn = service.gate().badgeRequests;
        in.refusedToday = BadgeRules.refusedNow(memory(context), badge.crew, System.currentTimeMillis());
        return in;
    }

    /** "Kuruluş Eylül 2026, 12 üye" ("12/30 üye" for a member); a hidden crew says so to its members. */
    private static String metaLine(Context context, SocialService service, Badge badge, BadgeRules.Row row) {
        StringBuilder out = new StringBuilder();
        String founded = foundedOn(context, badge.createdOn);
        if (founded != null) out.append(context.getString(R.string.projects_crew_founded, founded));
        int limit = service.state().limits().teamMembers;
        boolean member = row == BadgeRules.Row.OWN || row == BadgeRules.Row.MEMBER;
        String members = member && limit > 0 ? context.getString(R.string.projects_crew_members, badge.members, limit)
                : context.getResources().getQuantityString(R.plurals.projects_crew_member_count, badge.members, badge.members);
        if (out.length() > 0) out.append(", ");
        out.append(members);
        return out.toString();
    }

    /** The month and year a crew was founded ("Eylül 2026", "September 2026"; no day: the desktop's card). */
    @Nullable
    static String foundedOn(Context context, @Nullable String isoDay) {
        Long at = AppDates.isoDay(isoDay);
        return at == null ? null : AppDates.monthYear(AppDates.turkish(context), at, AppDates.UTC);
    }

    private static SocialModel.TeamSummary summary(Badge badge) {
        return new SocialModel.TeamSummary(badge.crew, badge.name, badge.tag, badge.emblem, badge.color, badge.frame,
                badge.motto.isEmpty() ? null : badge.motto, badge.members);
    }

    private static void openCrew(FragmentActivity activity, String crewId) {
        Bundle args = new Bundle();
        args.putString(CrewFragment.ARG_CREW, crewId);
        if (!activity.getSupportFragmentManager().isStateSaved()) Tools.swapFragment(activity, CrewFragment.class, CrewFragment.TAG, args);
    }

    private static Button button(Context context, int label, boolean primary, View.OnClickListener click) {
        float dp = context.getResources().getDisplayMetrics().density;
        Button button = new Button(context, null, android.R.attr.borderlessButtonStyle);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setMinHeight(Math.round(48 * dp));
        button.setPadding(Math.round(18 * dp), 0, Math.round(18 * dp), 0);
        button.setTextColor(ContextCompat.getColor(context, primary ? R.color.projects_on_gold : R.color.projects_bone_100));
        button.setBackground(primary ? ChamferDrawable.primary(context) : ChamferDrawable.outlined(context));
        try {
            // The gold action speaks like every gold button of the app (MineButton: Tektur), the outlined one like a row.
            button.setTypeface(ResourcesCompat.getFont(context, primary ? R.font.tektur_semibold : R.font.figtree_semibold));
        } catch (RuntimeException ignored) {
            // The theme's font.
        }
        Press.attach(button, Press.Style.BUTTON);
        button.setOnClickListener(click);
        return button;
    }

    private static TextView quiet(Context context, String text) {
        TextView line = new TextView(context);
        line.setText(text);
        line.setTextSize(14);
        line.setTextColor(ContextCompat.getColor(context, R.color.projects_ash_300));
        return line;
    }

    private static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private static LinearLayout.LayoutParams weightStart(float dp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginStart(Math.round(8 * dp));
        return lp;
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    // ---- this phone's refusals (until the Turkey day ends) ----

    private static Map<String, Long> memory(Context context) {
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return BadgeRules.readMemory(prefs.getString(REFUSED, null));
    }

    private static void remember(Context context, String crew, long until, long nowMs) {
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Map<String, Long> next = BadgeRules.withRefusal(memory(context), crew, until, nowMs);
        prefs.edit().putString(REFUSED, BadgeRules.writeMemory(next)).apply();
    }
}
