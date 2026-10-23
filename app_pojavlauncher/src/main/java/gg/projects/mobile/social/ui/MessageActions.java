package gg.projects.mobile.social.ui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import net.kdt.pojavlaunch.R;

import java.util.List;

import gg.projects.mobile.mods.Motion;
import gg.projects.mobile.social.ChatBook;
import gg.projects.mobile.social.ChatConv;
import gg.projects.mobile.social.ChatLinks;
import gg.projects.mobile.social.SocialChat;
import gg.projects.mobile.social.SocialModel;
import gg.projects.mobile.social.SocialModel.Crew;
import gg.projects.mobile.social.SocialModel.Profile;
import gg.projects.mobile.social.SocialProblem;
import gg.projects.mobile.social.SocialService;
import gg.projects.mobile.ui.HapticMap;
import gg.projects.mobile.ui.Haptics;
import gg.projects.mobile.ui.Press;

/**
 * MOB1 (CHAT2 §6, §7, Android): holding a message (WhatsApp's way): the screen dims, the message stays
 * lit where it was, six quick reactions and "+" float above it and its menu under it: "Yanıtla",
 * "Kopyala", "Bağlantıyı kopyala", "Düzenle", "Herkesten sil", "Rapor et" (a sticker: no copy, no
 * edit). A message that did not go keeps "Tekrar dene", "Kopyala" and "Sil". When the message is too
 * tall for both, it moves up just enough (or the strip and the menu stand alone). The strip rises and
 * the emoji pop in one after another (20 ms apart), the menu settles in; everything leaves faster
 * than it came; with animations off it just appears.
 */
final class MessageActions {
    private MessageActions() {}

    static void show(final FragmentActivity activity, final ChatFragment screen, final String conv, final ChatBook.Entry entry,
                     @Nullable final View anchor) {
        final SocialService service = SocialService.get(activity);
        final SocialChat chat = service.chat();
        final ChatBook book = chat.book();
        if (book == null) return;
        final Context context = activity;
        final float dp = context.getResources().getDisplayMetrics().density;
        final Dialog dialog = new Dialog(context, R.style.ProjectsBottomPanel);
        final FrameLayout root = new FrameLayout(context);
        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setDimAmount(0.6f);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setNavigationBarColor(Color.TRANSPARENT);
        }
        final Closer close = new Closer(dialog, root);
        root.setOnClickListener(v -> close.close());
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.getAction() == android.view.KeyEvent.ACTION_UP) {
                close.close();
                return true;
            }
            return false;
        });

        long hubNow = book.hubNowMs(System.currentTimeMillis());
        final boolean writable = chat.canWrite(conv) == SocialChat.Refusal.NONE;
        // The quick reactions (sent messages younger than a week, while the service takes them).
        LinearLayout strip = null;
        if (!entry.failed() && writable && chat.offers(SocialModel.ChatState.REACTIONS) && book.reactable(entry, hubNow)) {
            strip = reactionStrip(activity, conv, entry, close);
        }
        LinearLayout menu = menu(activity, screen, conv, entry, close, writable, hubNow);
        // Nothing to offer (a sticker still on its way): no overlay at all, rather than an empty card.
        if (strip == null && menu.getChildCount() == 0) return;

        // The lit message: a picture of it where it is on screen.
        final ImageView lit = new ImageView(context);
        Bitmap shot = anchor == null ? null : snapshot(anchor);
        int[] at = new int[2];
        if (shot != null) {
            anchor.getLocationOnScreen(at);
            lit.setImageBitmap(shot);
            lit.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            root.addView(lit, new FrameLayout.LayoutParams(shot.getWidth(), shot.getHeight()));
        }
        if (strip != null) root.addView(strip, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(menu, new FrameLayout.LayoutParams(Math.round(248 * dp), ViewGroup.LayoutParams.WRAP_CONTENT));
        final LinearLayout stripView = strip;
        final boolean mine = entry.mine(service.state().myId() == null ? "" : service.state().myId());
        final int anchorX = at[0];
        final int anchorY = at[1];
        final int shotW = shot == null ? 0 : shot.getWidth();
        final int shotH = shot == null ? 0 : shot.getHeight();
        dialog.show();
        root.post(() -> place(root, lit, stripView, menu, anchorX, anchorY, shotW, shotH, mine, dp));
    }

    /** Puts the lit message, the strip above it and the menu under it, moving the message up only as much as needed. */
    private static void place(FrameLayout root, ImageView lit, @Nullable LinearLayout strip, LinearLayout menu, int anchorX, int anchorY,
                              int shotW, int shotH, boolean mine, float dp) {
        int[] origin = new int[2];
        root.getLocationOnScreen(origin);
        int width = root.getWidth();
        int height = root.getHeight();
        int margin = Math.round(12 * dp);
        int gap = Math.round(8 * dp);
        int stripH = strip == null ? 0 : strip.getHeight();
        int stripW = strip == null ? 0 : strip.getWidth();
        int menuH = menu.getHeight();
        int menuW = menu.getWidth();
        int top = anchorY - origin[1];
        int left = anchorX - origin[0];
        boolean fits = shotH > 0 && stripH + (stripH > 0 ? gap : 0) + shotH + gap + menuH <= height - 2 * margin;
        boolean motion = Motion.enabled(root.getContext());
        int litTop = top;
        if (fits) {
            int minTop = margin + stripH + (stripH > 0 ? gap : 0);
            int maxTop = height - margin - menuH - gap - shotH;
            litTop = Math.max(minTop, Math.min(top, maxTop));
            lit.setX(left);
            lit.setY(top);
            lit.setPivotX(mine ? shotW : 0);
            lit.setPivotY(shotH / 2f);
            if (motion) {
                lit.animate().y(litTop).scaleX(1.02f).scaleY(1.02f).setDuration(220).setInterpolator(Motion.EASE_OUT).start();
            } else {
                lit.setY(litTop);
            }
        } else {
            lit.setVisibility(View.GONE);
            litTop = Math.max(margin + stripH + gap, (height - menuH) / 2 - shotH / 4);
            shotH = 0;
        }
        int side = mine ? width - margin : margin;
        if (strip != null) {
            float x = mine ? Math.max(margin, Math.min(left + shotW - stripW, side - stripW)) : Math.max(margin, Math.min(left, width - margin - stripW));
            strip.setX(x);
            strip.setY(fits ? litTop - gap - stripH : litTop - gap - stripH);
            strip.setPivotX(mine ? stripW : 0);
            strip.setPivotY(stripH);
            if (motion) {
                strip.setAlpha(0f);
                strip.setScaleX(0.9f);
                strip.setScaleY(0.9f);
                strip.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(Motion.EASE_OUT).start();
                for (int i = 0; i < strip.getChildCount(); i++) {
                    View child = strip.getChildAt(i);
                    child.setScaleX(0.6f);
                    child.setScaleY(0.6f);
                    child.setAlpha(0f);
                    child.animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(40 + 20L * i).setDuration(200)
                            .setInterpolator(Motion.EASE_OUT).start();
                }
            }
        }
        float menuX = mine ? Math.max(margin, Math.min(left + shotW - menuW, width - margin - menuW)) : Math.max(margin, Math.min(left, width - margin - menuW));
        float menuY = fits ? litTop + shotH * 1.02f + gap : litTop;
        menu.setX(menuX);
        menu.setY(Math.min(menuY, height - margin - menuH));
        if (motion) {
            menu.setAlpha(0f);
            menu.setTranslationY(menu.getTranslationY() + 8 * dp);
            menu.animate().alpha(1f).translationYBy(-8 * dp).setDuration(200).setInterpolator(Motion.EASE_OUT).start();
        }
    }

    /**
     * Closes the overlay once. Every action asks it first: a second tap during the 130 ms fade does nothing,
     * so no confirm opens twice and no reaction or retry goes twice (CR1-F).
     */
    private static final class Closer {
        private final Dialog dialog;
        private final View root;
        private boolean closed;

        Closer(Dialog dialog, View root) {
            this.dialog = dialog;
            this.root = root;
        }

        /** True the first time (the caller may act), false once it is already closing. */
        boolean close() {
            if (closed) return false;
            closed = true;
            leave(dialog, root);
            return true;
        }
    }

    private static void leave(final Dialog dialog, View root) {
        if (!dialog.isShowing()) return;
        if (!Motion.enabled(root.getContext())) {
            dismiss(dialog);
            return;
        }
        root.animate().alpha(0f).setDuration(130).setInterpolator(Motion.EASE_IN).withEndAction(() -> dismiss(dialog)).start();
    }

    private static void dismiss(Dialog dialog) {
        try {
            dialog.dismiss();
        } catch (RuntimeException ignored) {
            // The activity went first.
        }
    }

    @Nullable
    private static Bitmap snapshot(View view) {
        if (view.getWidth() == 0 || view.getHeight() == 0) return null;
        try {
            Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            view.draw(canvas);
            return bitmap;
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /** Six quick reactions and "+" (the whole picker in its reaction mode). */
    private static LinearLayout reactionStrip(final FragmentActivity activity, final String conv, final ChatBook.Entry entry,
                                              final Closer close) {
        Context context = activity;
        float dp = context.getResources().getDisplayMetrics().density;
        LinearLayout strip = new LinearLayout(context);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Math.round(6 * dp);
        strip.setPadding(pad, pad, pad, pad);
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(28 * dp);
        pill.setColor(ContextCompat.getColor(context, R.color.projects_obsidian_800));
        pill.setStroke(Math.max(1, Math.round(dp)), ContextCompat.getColor(context, R.color.projects_hairline));
        strip.setBackground(pill);
        strip.setElevation(10 * dp);
        strip.setClickable(true);
        List<String> quick = EmojiStore.quickReactions(context);
        for (final String emoji : quick) {
            TextView cell = new TextView(context);
            cell.setText(emoji);
            cell.setTextSize(24);
            cell.setGravity(Gravity.CENTER);
            cell.setBackgroundResource(R.drawable.projects_icon_button_ripple);
            boolean mineAlready = false;
            for (gg.projects.mobile.social.ChatMessage.Reaction r : entry.reactions) {
                if (ChatBook.sameEmoji(r.e, emoji) && r.has(SocialService.get(context).state().myId())) mineAlready = true;
            }
            if (mineAlready) {
                GradientDrawable ring = new GradientDrawable();
                ring.setShape(GradientDrawable.OVAL);
                ring.setColor(ContextCompat.getColor(context, R.color.projects_obsidian_700));
                ring.setStroke(Math.max(1, Math.round(1.5f * dp)), ContextCompat.getColor(context, R.color.projects_gold_700));
                cell.setBackground(ring);
            }
            cell.setContentDescription(context.getString(R.string.projects_reactions_give, emoji));
            cell.setOnClickListener(v -> {
                if (!close.close()) return;
                react(activity, conv, entry, emoji, v);
            });
            Press.attach(cell, Press.Style.ICON);
            strip.addView(cell, new LinearLayout.LayoutParams(Math.round(44 * dp), Math.round(44 * dp)));
        }
        ImageView more = new ImageView(context);
        more.setImageResource(R.drawable.projects_ic_plus);
        more.setImageTintList(ContextCompat.getColorStateList(context, R.color.projects_ash_300));
        int inner = Math.round(11 * dp);
        more.setPadding(inner, inner, inner, inner);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(ContextCompat.getColor(context, R.color.projects_obsidian_700));
        more.setBackground(circle);
        more.setContentDescription(context.getString(R.string.projects_reactions_more));
        more.setOnClickListener(v -> {
            if (!close.close()) return;
            EmojiSheet.pickReaction(activity, emoji -> react(activity, conv, entry, emoji, v));
        });
        Press.attach(more, Press.Style.ICON);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.round(40 * dp), Math.round(40 * dp));
        lp.setMarginStart(Math.round(4 * dp));
        strip.addView(more, lp);
        return strip;
    }

    /** A press on an emoji for the message: given (or taken back); the limits say why not. */
    static void react(final FragmentActivity activity, String conv, ChatBook.Entry entry, String emoji, final View feedback) {
        SocialChat chat = SocialService.get(activity).chat();
        SocialChat.React result = chat.react(conv, entry, emoji, new SocialChat.Done() {
            @Override
            public void ok() {
            }

            @Override
            public void failed(SocialProblem problem) {
                SocialUi.toastProblem(activity, problem, null);
            }
        });
        switch (result) {
            case ADDED:
                EmojiStore.usedEmoji(activity, emoji);
                Haptics.confirm(feedback);
                break;
            case REMOVED:
                Haptics.perform(feedback, HapticMap.Kind.TOGGLE_OFF);
                break;
            case FULL_MINE:
                Haptics.reject(feedback);
                SocialUi.toast(activity, activity.getString(R.string.projects_reactions_full));
                break;
            case FULL_KINDS:
                Haptics.reject(feedback);
                SocialUi.toast(activity, activity.getString(R.string.projects_reactions_kinds));
                break;
            case REFUSED:
                Haptics.reject(feedback);
                SocialUi.toast(activity, activity.getString(R.string.projects_reactions_refused));
                break;
            default:
                Haptics.reject(feedback);
                break;
        }
    }

    /** The message's menu (the desktop's order). */
    private static LinearLayout menu(final FragmentActivity activity, final ChatFragment screen, final String conv,
                                     final ChatBook.Entry entry, final Closer close, boolean writable, long hubNow) {
        final SocialService service = SocialService.get(activity);
        final SocialChat chat = service.chat();
        final ChatBook book = chat.book();
        Context context = activity;
        float dp = context.getResources().getDisplayMetrics().density;
        LinearLayout menu = new LinearLayout(context);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(0, Math.round(6 * dp), 0, Math.round(6 * dp));
        GradientDrawable card = new GradientDrawable();
        card.setCornerRadius(14 * dp);
        card.setColor(ContextCompat.getColor(context, R.color.projects_obsidian_900));
        card.setStroke(Math.max(1, Math.round(dp)), ContextCompat.getColor(context, R.color.projects_hairline));
        menu.setBackground(card);
        menu.setElevation(12 * dp);
        menu.setClickable(true);
        if (entry.failed()) {
            if (ChatFragment.retriable(entry.outbox.failure)) {
                menu.addView(row(activity, R.drawable.projects_ic_arrows_clockwise, R.string.projects_retry, R.color.projects_bone_100, close,
                        () -> chat.retry(entry.outbox.nonce)));
            }
            if (entry.sticker() == null) menu.addView(copyRow(activity, entry, close));
            menu.addView(row(activity, R.drawable.projects_ic_trash, R.string.projects_chat_discard, R.color.projects_flare_400, close,
                    () -> chat.discard(entry.outbox.nonce)));
            return menu;
        }
        if (book != null && entry.message != null && book.canReply(entry) && writable && chat.offers(SocialModel.ChatState.REPLIES)) {
            menu.addView(row(activity, R.drawable.projects_ic_chat2_reply, R.string.projects_reply_action, R.color.projects_bone_100, close,
                    () -> screen.startReply(entry.message)));
        }
        // A message still on its way can be copied too (its text is the player's own).
        String text = entry.text();
        if (entry.sticker() == null && text != null && !text.isEmpty()) {
            menu.addView(copyRow(activity, entry, close));
            final String link = firstLink(chat, text);
            if (link != null) {
                menu.addView(row(activity, R.drawable.projects_ic_link, R.string.projects_links_copy, R.color.projects_bone_100, close, () -> {
                    SocialUi.copy(activity, "Projects", link);
                    SocialUi.toast(activity, activity.getString(R.string.projects_links_copied));
                }));
            }
        }
        if (book != null && entry.message != null && book.canEdit(entry, hubNow) && writable) {
            menu.addView(row(activity, R.drawable.projects_ic_pencil, R.string.projects_chat_edit, R.color.projects_bone_100, close,
                    () -> screen.startEditing(entry.message)));
        }
        if (book != null && entry.message != null && book.canDelete(entry, hubNow, moderator(service, conv))) {
            menu.addView(row(activity, R.drawable.projects_ic_trash, R.string.projects_chat_delete, R.color.projects_flare_400, close,
                    () -> SocialUi.confirm(activity, activity.getString(R.string.projects_chat_delete_ask), null,
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
                                    }))));
        }
        if (book != null && book.canReport(entry) && service.gate().reports) {
            final Profile sender = service.state().profile(entry.message.from);
            menu.addView(CrewViews.divider(activity));
            menu.addView(row(activity, R.drawable.projects_ic_flag, R.string.projects_report, R.color.projects_ash_300, close,
                    () -> ReportDialog.message(activity, entry.message, screen.context(entry.message), sender)));
        }
        return menu;
    }

    @Nullable
    private static String firstLink(SocialChat chat, String text) {
        for (ChatLinks.MessageLink link : ChatLinks.messageLinks(text, chat.linkLists())) if (link.ok) return link.link.url;
        return null;
    }

    private static View copyRow(final FragmentActivity activity, final ChatBook.Entry entry, Closer close) {
        return row(activity, R.drawable.projects_ic_copy, R.string.projects_chat_copy, R.color.projects_bone_100, close, () -> {
            SocialUi.copy(activity, "Projects", entry.text());
            SocialUi.toast(activity, activity.getString(R.string.projects_social_copied));
        });
    }

    private static View row(FragmentActivity activity, int icon, int label, int tint, final Closer close, final Runnable action) {
        TextView row = CrewViews.actionRow(activity, icon, activity.getString(label), tint, v -> {
            if (!close.close()) return;
            Haptics.press(v);
            action.run();
        });
        float dp = activity.getResources().getDisplayMetrics().density;
        row.setMinHeight(Math.round(48 * dp));
        row.setPaddingRelative(Math.round(16 * dp), row.getPaddingTop(), Math.round(16 * dp), row.getPaddingBottom());
        return row;
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
