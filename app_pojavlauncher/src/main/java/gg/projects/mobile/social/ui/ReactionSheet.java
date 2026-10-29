package gg.projects.mobile.social.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.fragment.app.FragmentActivity;

import net.kdt.pojavlaunch.R;

import java.util.ArrayList;
import java.util.List;

import gg.projects.mobile.mods.Motion;
import gg.projects.mobile.social.ChatBook;
import gg.projects.mobile.social.ChatConv;
import gg.projects.mobile.social.ChatMessage;
import gg.projects.mobile.social.SocialChat;
import gg.projects.mobile.social.SocialModel.Profile;
import gg.projects.mobile.social.SocialProblem;
import gg.projects.mobile.social.SocialService;
import gg.projects.mobile.social.SocialState;
import gg.projects.mobile.ui.ChamferDrawable;
import gg.projects.mobile.ui.Haptics;
import gg.projects.mobile.ui.Press;

/**
 * MOB1 (CHAT2 §7, Android): a message's reactions, opened from a chip: tabs "Tümü 5 · 👍 3 · 😂 2"
 * (the chip's own selected), who gave each (the player first as "Sen", then in the order they
 * reacted; holding a person opens their menu, "Rapor et" included) and one button for the selected
 * emoji: "Ben de 👍", or "Tepkimi kaldır" when it is the player's; with five of theirs already, a
 * short line says so instead. The list follows live changes while it is open.
 */
final class ReactionSheet {
    private ReactionSheet() {}

    static void show(final FragmentActivity activity, final String conv, final long id, @Nullable String selected) {
        final SocialService service = SocialService.get(activity);
        final ChatBook book = service.chat().book();
        if (book == null) return;
        final Context context = activity;
        final float dp = context.getResources().getDisplayMetrics().density;
        final LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Math.round(16 * dp), 0, Math.round(16 * dp), Math.round(16 * dp));
        TextView title = new TextView(context);
        title.setText(R.string.projects_reactions_list);
        title.setTextSize(17);
        title.setTextColor(ContextCompat.getColor(context, R.color.projects_bone_100));
        androidx.core.view.ViewCompat.setAccessibilityHeading(title, true);
        try {
            title.setTypeface(ResourcesCompat.getFont(context, R.font.tektur_semibold));
        } catch (RuntimeException ignored) {
            // The theme's font.
        }
        title.setPadding(Math.round(4 * dp), Math.round(2 * dp), 0, Math.round(10 * dp));
        content.addView(title);
        final LinearLayout tabs = new LinearLayout(context);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView tabScroll = new HorizontalScrollView(context);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabScroll.addView(tabs);
        content.addView(tabScroll);
        final LinearLayout people = new LinearLayout(context);
        people.setOrientation(LinearLayout.VERTICAL);
        // MOB-POLISH: the list fades out under the tabs instead of being cut.
        ScrollView scroll = new FadingScrollView(context);
        scroll.addView(people);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollLp.topMargin = Math.round(8 * dp);
        content.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(260 * dp)));
        final Button action = new Button(context, null, android.R.attr.borderlessButtonStyle);
        action.setAllCaps(false);
        action.setTextSize(15);
        action.setMinHeight(Math.round(48 * dp));
        action.setTextColor(ContextCompat.getColor(context, R.color.projects_bone_100));
        action.setBackground(ChamferDrawable.outlined(context));
        Press.attach(action, Press.Style.BUTTON);
        LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionLp.topMargin = Math.round(12 * dp);
        content.addView(action, actionLp);
        final TextView note = new TextView(context);
        note.setTextSize(13);
        note.setTextColor(ContextCompat.getColor(context, R.color.projects_ash_400));
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, Math.round(12 * dp), 0, 0);
        note.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(note);
        final BottomPanel panel = BottomPanel.of(context, content, 0.7f);
        final String[] tab = {selected};
        final String[] drawn = {null};
        final SocialService.Listener listener = new SocialService.Listener() {
            @Override
            public void onSocialChanged() {
                if (!render(activity, panel, conv, id, tab, drawn, false, tabs, people, action, note)) panel.dismiss();
            }
        };
        panel.onClosed(() -> service.removeListener(listener));
        service.addListener(listener);
        if (!render(activity, panel, conv, id, tab, drawn, false, tabs, people, action, note)) {
            service.removeListener(listener);
            return;
        }
        panel.show();
    }

    /**
     * Draws the sheet for the message as it is now; false when it has no reactions any more (the sheet goes).
     * Only a change it shows redraws it ({@code drawn}: what it shows now): the service changes often (typing,
     * presence), and a rebuilt list would flicker and take a button from under the finger. {@code fade}: a tab
     * switch, whose list fades in.
     */
    private static boolean render(final FragmentActivity activity, final BottomPanel panel, final String conv, final long id,
                                  final String[] tab, final String[] drawn, final boolean fade, final LinearLayout tabs,
                                  final LinearLayout people, final Button action, final TextView note) {
        final SocialService service = SocialService.get(activity);
        final ChatBook book = service.chat().book();
        final String me = service.state().myId();
        if (book == null || me == null) return false;
        ChatBook.Entry entry = null;
        for (ChatBook.Entry e : book.entries(conv)) if (e.message != null && e.message.id == id) entry = e;
        if (entry == null || entry.reactions.isEmpty()) return false;
        final ChatBook.Entry shown = entry;
        List<ChatMessage.Reaction> reactions = entry.reactions;
        if (tab[0] != null) {
            boolean still = false;
            for (ChatMessage.Reaction r : reactions) still |= ChatBook.sameEmoji(r.e, tab[0]);
            if (!still) tab[0] = null;
        }
        String key = renderKey(service, book, shown, tab[0]);
        if (key.equals(drawn[0])) return true;
        drawn[0] = key;
        Context context = activity;
        float dp = context.getResources().getDisplayMetrics().density;
        tabs.removeAllViews();
        int total = 0;
        for (ChatMessage.Reaction r : reactions) total += r.count();
        // MOB1b: the list is as tall as "Tümü" needs (one row per reaction, so every tab fits and a switch never
        // jumps), at most 260 dp; a fixed 260 dp left one or two rows above a large empty space.
        View list = (View) people.getParent();
        ViewGroup.LayoutParams listLp = list.getLayoutParams();
        int listHeight = Math.round(Math.min(260f, Math.max(1, total) * 52f) * dp);
        if (listLp != null && listLp.height != listHeight) {
            listLp.height = listHeight;
            list.setLayoutParams(listLp);
        }
        tabs.addView(tabButton(context, context.getString(R.string.projects_reactions_all, total), tab[0] == null, v -> {
            tab[0] = null;
            Haptics.press(v);
            render(activity, panel, conv, id, tab, drawn, true, tabs, people, action, note);
        }));
        for (final ChatMessage.Reaction r : reactions) {
            tabs.addView(tabButton(context, r.e + "  " + r.count(), tab[0] != null && ChatBook.sameEmoji(r.e, tab[0]), v -> {
                tab[0] = r.e;
                Haptics.press(v);
                render(activity, panel, conv, id, tab, drawn, true, tabs, people, action, note);
            }));
        }
        people.removeAllViews();
        SocialState state = service.state();
        List<String[]> rows = new ArrayList<>();
        for (ChatMessage.Reaction r : reactions) {
            if (tab[0] != null && !ChatBook.sameEmoji(r.e, tab[0])) continue;
            if (r.has(me)) rows.add(0, new String[]{me, r.e});
            for (String who : r.by) if (!who.equals(me)) rows.add(new String[]{who, r.e});
        }
        // The player first ("Sen"), each of their emoji once in the list.
        List<String[]> ordered = new ArrayList<>();
        for (String[] row : rows) if (row[0].equals(me)) ordered.add(row);
        for (String[] row : rows) if (!row[0].equals(me)) ordered.add(row);
        for (final String[] row : ordered) people.addView(personRow(activity, state, row[0], row[1], me, conv, id));
        // The button for the selected emoji.
        note.setVisibility(View.GONE);
        action.setVisibility(View.GONE);
        if (tab[0] != null && service.chat().offers(gg.projects.mobile.social.SocialModel.ChatState.REACTIONS)
                && book.reactable(shown, book.hubNowMs(System.currentTimeMillis()))) {
            final String emoji = tab[0];
            ChatBook.ReactVerdict verdict = book.reactVerdict(shown.reactions, emoji);
            if (verdict == ChatBook.ReactVerdict.MINE) {
                note.setText(R.string.projects_reactions_full);
                note.setVisibility(View.VISIBLE);
            } else if (verdict == ChatBook.ReactVerdict.ADD || verdict == ChatBook.ReactVerdict.REMOVE) {
                action.setVisibility(View.VISIBLE);
                action.setText(verdict == ChatBook.ReactVerdict.REMOVE ? context.getString(R.string.projects_reactions_remove)
                        : context.getString(R.string.projects_reactions_me_too, emoji));
                action.setOnClickListener(v -> {
                    SocialChat.React done = service.chat().react(conv, shown, emoji, new SocialChat.Done() {
                        @Override
                        public void ok() {
                        }

                        @Override
                        public void failed(SocialProblem problem) {
                            SocialUi.toastProblem(activity, problem, v);
                        }
                    });
                    if (done == SocialChat.React.ADDED || done == SocialChat.React.REMOVED) Haptics.confirm(v);
                    else Haptics.reject(v);
                });
            }
        }
        if (fade && Motion.enabled(context) && people.getChildCount() > 0) {
            people.setAlpha(0.6f);
            people.animate().alpha(1f).setDuration(140).setInterpolator(Motion.EASE_OUT).start();
        }
        return true;
    }

    /** What the sheet shows, as one string: the tab, who gave what (as they look), the button's state. */
    private static String renderKey(SocialService service, ChatBook book, ChatBook.Entry entry, @Nullable String tab) {
        StringBuilder key = new StringBuilder(String.valueOf(tab));
        SocialState state = service.state();
        boolean badges = service.gate().badges;
        for (ChatMessage.Reaction r : entry.reactions) {
            key.append('|').append(r.e);
            for (String who : r.by) {
                key.append(',').append(who);
                Profile p = state.profile(who);
                if (p == null) continue;
                key.append('/').append(p.name()).append('/').append(p.avatar);
                if (badges && p.badge != null) key.append('/').append(p.badge.tag).append(p.badge.color);
            }
        }
        boolean reactable = service.chat().offers(gg.projects.mobile.social.SocialModel.ChatState.REACTIONS)
                && book.reactable(entry, book.hubNowMs(System.currentTimeMillis()));
        key.append("|r").append(reactable);
        if (tab != null && reactable) key.append("|v").append(book.reactVerdict(entry.reactions, tab));
        return key.toString();
    }

    private static TextView tabButton(Context context, String text, boolean on, View.OnClickListener click) {
        float dp = context.getResources().getDisplayMetrics().density;
        TextView tab = new TextView(context);
        tab.setText(text);
        tab.setTextSize(14);
        tab.setGravity(Gravity.CENTER);
        tab.setMinHeight(Math.round(36 * dp));
        tab.setMinWidth(Math.round(48 * dp));
        tab.setPadding(Math.round(12 * dp), 0, Math.round(12 * dp), 0);
        tab.setTextColor(ContextCompat.getColor(context, on ? R.color.projects_bone_100 : R.color.projects_ash_300));
        // MOB-POLISH: chamfered like every action (it was a pill); the tab shown is the chosen one.
        tab.setBackground(SocialChips.choice(context, 7));
        tab.setClickable(true);
        tab.setFocusable(true);
        tab.setSelected(on);
        try {
            tab.setTypeface(ResourcesCompat.getFont(context, R.font.figtree_semibold));
        } catch (RuntimeException ignored) {
            // The theme's font.
        }
        tab.setOnClickListener(click);
        Press.attach(tab, Press.Style.BUTTON);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(36 * dp));
        lp.setMarginEnd(Math.round(8 * dp));
        tab.setLayoutParams(lp);
        return tab;
    }

    private static View personRow(final FragmentActivity activity, SocialState state, final String userId, String emoji, String me,
                                  final String conv, final long messageId) {
        Context context = activity;
        float dp = context.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Math.round(52 * dp));
        row.setPaddingRelative(Math.round(4 * dp), 0, Math.round(4 * dp), 0);
        row.setBackgroundResource(R.drawable.projects_row_ripple);
        Profile profile = state.profile(userId);
        AvatarView avatar = new AvatarView(context);
        avatar.setProfile(profile);
        avatar.setSurfaceColor(ContextCompat.getColor(context, R.color.projects_obsidian_900));
        avatar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(avatar, new LinearLayout.LayoutParams(Math.round(32 * dp), Math.round(32 * dp)));
        LinearLayout names = new LinearLayout(context);
        names.setOrientation(LinearLayout.HORIZONTAL);
        names.setGravity(Gravity.CENTER_VERTICAL);
        boolean mine = userId.equals(me);
        if (!mine && profile != null && SocialService.get(context).gate().badges && profile.badge != null) {
            CrewTagChip chip = CrewTagChip.make(context, CrewTagChip.Size.SM);
            chip.setBadge(profile.badge, CrewTagChip.Size.SM, false);
            names.addView(chip);
        }
        TextView name = new TextView(context);
        name.setText(mine ? context.getString(R.string.projects_reactions_you)
                : profile == null ? context.getString(R.string.projects_crew_someone) : profile.name());
        name.setTextSize(15);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setTextColor(ContextCompat.getColor(context, R.color.projects_bone_100));
        names.addView(name);
        LinearLayout.LayoutParams namesLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        namesLp.setMarginStart(Math.round(12 * dp));
        row.addView(names, namesLp);
        TextView mark = new TextView(context);
        mark.setText(emoji);
        mark.setTextSize(20);
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(mark);
        row.setContentDescription(name.getText() + ", " + emoji);
        if (!mine && profile != null) {
            final String crewId = ChatConv.crewId(conv);
            // "Rapor et" here reports the reaction itself (CHAT2 README "Reports").
            final Profile reacted = profile;
            final String reactedWith = emoji;
            final Runnable report = () -> ReportDialog.reaction(activity, reacted, conv, messageId, reactedWith);
            SocialMenuSheet.attach(row, name.getText(), v -> {
                SocialMenuSheet.person(activity, userId, crewId, SocialMenuSheet.Place.CHAT, v, report);
                return true;
            });
            row.setOnClickListener(v -> SocialMenuSheet.person(activity, userId, crewId, SocialMenuSheet.Place.CHAT, v, report));
            Press.attach(row, Press.Style.ROW);
        }
        row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }
}
