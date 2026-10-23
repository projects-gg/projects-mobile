package gg.projects.mobile.social.ui;

import android.content.Context;

import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.R;

import gg.projects.mobile.social.ChatBook;
import gg.projects.mobile.social.SocialModel.Profile;
import gg.projects.mobile.social.SocialState;

/**
 * MOB1 (CHAT2): the one line a conversation's newest message makes in the lists: "Sen: selam",
 * "Ayşe: selam", a sticker as "Çıkartma: GG" ("Sen: Çıkartma, GG", "Ayşe: Çıkartma, GG"; one the app
 * does not have is just "Çıkartma"), a deleted one as its quiet line says.
 */
final class ChatPreview {
    private ChatPreview() {}

    /** {@code named}: the other side's name goes first (a crew's chat, the "Mesajlar" row), not in a direct row. */
    static String line(Context context, SocialState state, ChatBook.Entry last, @Nullable String me, boolean named) {
        if (last.deleted()) return ChatFragment.deletedText(context, last.tombstone, me, state);
        boolean mine = me != null && last.mine(me);
        Profile from = named && me != null && !mine ? state.profile(last.from(me)) : null;
        String sticker = last.sticker();
        if (sticker != null) {
            String name = EmojiStore.stickerName(context, sticker);
            if (name == null) {
                String plain = context.getString(R.string.projects_stickers_unknown);
                if (mine) return context.getString(R.string.projects_chat_you, plain);
                return from != null ? from.name() + ": " + plain : plain;
            }
            if (mine) return context.getString(R.string.projects_stickers_preview_mine, name);
            if (from != null) return context.getString(R.string.projects_stickers_preview_of, from.name(), name);
            return context.getString(R.string.projects_stickers_preview, name);
        }
        String text = last.text().replace('\n', ' ');
        if (mine) return context.getString(R.string.projects_chat_you, text);
        return from != null ? from.name() + ": " + text : text;
    }
}
