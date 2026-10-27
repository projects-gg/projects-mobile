package gg.projects.mobile.social.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;

import net.kdt.pojavlaunch.R;

import gg.projects.mobile.social.ChatBook;
import gg.projects.mobile.social.ChatConv;
import gg.projects.mobile.social.SocialChat;
import gg.projects.mobile.social.SocialModel;
import gg.projects.mobile.social.SocialModel.Crew;
import gg.projects.mobile.social.SocialModel.Profile;
import gg.projects.mobile.social.SocialProblem;
import gg.projects.mobile.social.SocialService;
import gg.projects.mobile.ui.Haptics;

/**
 * A message's menu (M20; README "Chat"), opened by holding it: "Kopyala"; for the player's own
 * "Düzenle" (15 minutes) and "Herkesten sil" (7 days; in crew chat the leader and the moderators may
 * delete any); "Rapor et" for someone else's; for one that did not go "Tekrar dene" and "Sil".
 */
final class MessageMenu {
    private MessageMenu() {}

    static void show(final FragmentActivity activity, final ChatFragment screen, final String conv, final ChatBook.Entry entry) {
        final SocialService service = SocialService.get(activity);
        final SocialChat chat = service.chat();
        final ChatBook book = chat.book();
        if (book == null) return;
        View sheet = LayoutInflater.from(activity).inflate(R.layout.dialog_projects_actions, null, false);
        TextView quote = sheet.findViewById(R.id.projects_actions_quote);
        quote.setText(entry.text());
        quote.setVisibility(View.VISIBLE);
        LinearLayout actions = sheet.findViewById(R.id.projects_actions_list);
        final AlertDialog dialog = new AlertDialog.Builder(activity).setView(sheet).create();

        if (entry.failed()) {
            if (ChatFragment.retriable(entry.outbox.failure)) {
                actions.addView(CrewViews.actionRow(activity, R.drawable.projects_ic_arrows_clockwise, activity.getString(R.string.projects_retry),
                        R.color.projects_bone_100, v -> {
                            dialog.dismiss();
                            chat.retry(entry.outbox.nonce);
                        }));
            }
            actions.addView(copyRow(activity, dialog, entry));
            actions.addView(CrewViews.actionRow(activity, R.drawable.projects_ic_trash, activity.getString(R.string.projects_chat_discard),
                    R.color.projects_flare_400, v -> {
                        dialog.dismiss();
                        chat.discard(entry.outbox.nonce);
                    }));
            dialog.show();
            return;
        }
        actions.addView(copyRow(activity, dialog, entry));
        long hubNow = book.hubNowMs(System.currentTimeMillis());
        if (entry.message != null && book.canEdit(entry, hubNow) && chat.canWrite(conv) == SocialChat.Refusal.NONE) {
            actions.addView(CrewViews.actionRow(activity, R.drawable.projects_ic_pencil, activity.getString(R.string.projects_chat_edit),
                    R.color.projects_bone_100, v -> {
                        dialog.dismiss();
                        screen.startEditing(entry.message);
                    }));
        }
        if (entry.message != null && book.canDelete(entry, hubNow, moderator(service, conv))) {
            actions.addView(CrewViews.actionRow(activity, R.drawable.projects_ic_trash, activity.getString(R.string.projects_chat_delete),
                    R.color.projects_flare_400, v -> {
                        dialog.dismiss();
                        SocialUi.confirmDanger(activity, activity.getString(R.string.projects_chat_delete_ask), null,
                                activity.getString(R.string.projects_chat_delete), () -> chat.delete(entry.message.conv, entry.message.id,
                                        new SocialChat.Done() {
                                            @Override
                                            public void ok() {
                                                SocialUi.toast(activity, activity.getString(R.string.projects_chat_deleted));
                                            }

                                            @Override
                                            public void failed(SocialProblem problem) {
                                                SocialUi.toastProblem(activity, problem, null);
                                            }
                                        }));
                    }));
        }
        if (book.canReport(entry) && service.gate().reports) {
            final Profile sender = service.state().profile(entry.message.from);
            actions.addView(CrewViews.divider(activity));
            actions.addView(CrewViews.actionRow(activity, R.drawable.projects_ic_flag, activity.getString(R.string.projects_report),
                    R.color.projects_bone_100, v -> {
                        dialog.dismiss();
                        ReportDialog.message(activity, entry.message, screen.context(entry.message), sender);
                    }));
        }
        dialog.show();
    }

    private static View copyRow(final FragmentActivity activity, final AlertDialog dialog, final ChatBook.Entry entry) {
        return CrewViews.actionRow(activity, R.drawable.projects_ic_copy, activity.getString(R.string.projects_chat_copy),
                R.color.projects_bone_100, v -> {
                    dialog.dismiss();
                    SocialUi.copy(activity, "Projects", entry.text());
                    Haptics.confirm(v);
                    SocialUi.toast(activity, activity.getString(R.string.projects_social_copied));
                });
    }

    /** The crew's leader or a moderator (they delete any message of its chat). */
    private static boolean moderator(SocialService service, String conv) {
        String crewId = ChatConv.crewId(conv);
        String me = service.state().myId();
        Crew crew = service.state().crew(crewId);
        if (crew == null || me == null) return false;
        SocialModel.Member mine = crew.team.member(me);
        return mine != null && (mine.founder || mine.moderator);
    }
}
