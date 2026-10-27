package gg.projects.mobile.social.ui;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import gg.projects.mobile.mods.Motion;
import gg.projects.mobile.social.ChatBook;
import gg.projects.mobile.social.ChatConv;
import gg.projects.mobile.social.SocialModel.Crew;
import gg.projects.mobile.social.SocialModel.Friend;
import gg.projects.mobile.social.SocialModel.Profile;
import gg.projects.mobile.social.SocialService;
import gg.projects.mobile.social.SocialState;
import gg.projects.mobile.ui.ContentTiles;
import gg.projects.mobile.ui.PixelSprites;
import gg.projects.mobile.ui.Press;
import gg.projects.mobile.ui.ScreenHeader;

/**
 * "Mesajlar" (M20): every conversation the player can open, the newest first, with what was said last
 * ("Sen: …" for the player's own) and the unread count in gold; the crews' chats are there even before
 * the first message. "+" starts a conversation with a friend.
 */
public class ChatListFragment extends Fragment implements SocialService.Listener {
    public static final String TAG = "ProjectsChatListFragment";

    private RecyclerView mList;
    private TextView mEmpty;
    private final Adapter mAdapter = new Adapter();

    public ChatListFragment() {
        super(R.layout.fragment_projects_chat_list);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        ScreenHeader header = view.findViewById(R.id.projects_chats_header);
        header.addAction(R.drawable.projects_ic_plus, R.string.projects_chat_message, v -> pickFriend());
        mList = view.findViewById(R.id.projects_chats_list);
        mList.setLayoutManager(new LinearLayoutManager(view.getContext()));
        mList.setAdapter(mAdapter);
        if (Motion.enabled(view.getContext())) {
            DefaultItemAnimator animator = new DefaultItemAnimator();
            animator.setSupportsChangeAnimations(false);
            animator.setMoveDuration(Motion.ENTER_MS);
            mList.setItemAnimator(animator);
        } else {
            mList.setItemAnimator(null);
        }
        mEmpty = view.findViewById(R.id.projects_chats_empty);
        // "Henuz mesaj yok" and where a conversation starts (the desktop's empty list).
        SpannableStringBuilder empty = new SpannableStringBuilder(getString(R.string.projects_chat_empty));
        empty.setSpan(new ForegroundColorSpan(ContextCompat.getColor(view.getContext(), R.color.projects_bone_100)), 0, empty.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        // MOB1b: the title as "Henüz arkadaşın yok" has it (Figtree semibold, 16 sp), the two empty states side by side.
        try {
            android.graphics.Typeface semibold = androidx.core.content.res.ResourcesCompat.getFont(view.getContext(), R.font.figtree_semibold);
            if (semibold != null) empty.setSpan(new gg.projects.mobile.ui.FontSpan(semibold), 0, empty.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } catch (RuntimeException ignored) {
            // The text's own font.
        }
        empty.setSpan(new android.text.style.AbsoluteSizeSpan(Math.round(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 16, getResources().getDisplayMetrics()))), 0, empty.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        empty.append('\n').append(getString(R.string.projects_chat_empty_body));
        mEmpty.setText(empty);
        // ICON2: the desktop's speech bubble sprite on the friends' icon tile, as its empty list.
        ContentTiles.onTileAbove(mEmpty, PixelSprites.Sprite.CHAT, 56, 14);
    }

    @Nullable
    @Override
    public Animator onCreateAnimator(int transit, boolean enter, int nextAnim) {
        View view = getView();
        if (view == null || !Motion.enabled(view.getContext())) return null;
        float distance = 28 * view.getResources().getDisplayMetrics().density;
        AnimatorSet set = new AnimatorSet();
        if (enter) {
            set.playTogether(ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f), ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, distance, 0f));
            set.setDuration(Motion.ENTER_MS);
            set.setInterpolator(Motion.EASE_OUT);
        } else {
            set.playTogether(ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0f), ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, 0f, distance * 0.6f));
            set.setDuration(Motion.EXIT_MS);
            set.setInterpolator(Motion.EASE_IN);
        }
        return set;
    }

    @Override
    public void onResume() {
        super.onResume();
        SocialService service = SocialService.get(requireContext());
        if (!service.signedIn()) {
            SocialUi.leave(this);
            return;
        }
        service.addListener(this);
        service.refresh(false);
        render();
    }

    @Override
    public void onPause() {
        super.onPause();
        SocialService.get(requireContext()).removeListener(this);
    }

    @Override
    public void onDestroyView() {
        if (mList != null) mList.setAdapter(null);
        super.onDestroyView();
    }

    @Override
    public void onSocialChanged() {
        render();
    }

    private static final class Row {
        final String conv;
        final String content;
        @Nullable
        final ChatBook.Summary summary;

        Row(String conv, String content, @Nullable ChatBook.Summary summary) {
            this.conv = conv;
            this.content = content;
            this.summary = summary;
        }
    }

    private void render() {
        if (!isAdded() || getView() == null) return;
        SocialService service = SocialService.get(requireContext());
        if (!service.enabled() || !service.signedIn() || (service.state().loaded() && !service.gate().chat)) {
            SocialUi.leave(this);
            return;
        }
        // MOD1: a moderator's note shows on whichever friends screen is open when it comes.
        if (isResumed()) AccountNotes.showNotices(requireActivity());
        List<Row> rows = new ArrayList<>();
        Set<String> listed = new HashSet<>();
        for (ChatBook.Summary s : service.chat().visibleSummaries()) {
            listed.add(s.conv);
            rows.add(new Row(s.conv, s.conv + "|" + (s.last == null ? "" : s.last.key() + s.last.text() + s.last.sticker()) + "|" + s.unread
                    + "|" + faceKey(service.state(), s.conv) + "|" + extrasKey(service, s.conv), s));
        }
        // A crew's chat is there before its first message.
        for (Crew crew : service.state().crews()) {
            String conv = ChatConv.crew(crew.id());
            if (listed.add(conv)) rows.add(new Row(conv, conv + "||0|" + faceKey(service.state(), conv) + "|" + extrasKey(service, conv), null));
        }
        mAdapter.submit(rows);
        mEmpty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /** CHAT2 "yazıyor…" and TEAM6's tag of the friend: a change binds the row again. */
    private static String extrasKey(SocialService service, String conv) {
        StringBuilder key = new StringBuilder();
        key.append(service.chat().writers(conv).isEmpty() ? "" : "typing");
        if (service.gate().badges && ChatConv.crewId(conv) == null) {
            Profile other = service.state().profile(ChatConv.other(conv, service.state().myId()));
            if (other != null && other.badge != null) key.append('|').append(other.badge.key());
        }
        return key.toString();
    }

    private static String faceKey(SocialState state, String conv) {
        String crewId = ChatConv.crewId(conv);
        if (crewId != null) {
            Crew crew = state.crew(crewId);
            return crew == null ? "" : crew.team.name + crew.team.emblem + crew.team.color + crew.team.frame;
        }
        Profile other = state.profile(ChatConv.other(conv, state.myId()));
        return other == null ? "" : other.name() + other.faceKey();
    }

    /** "+": a friend to write to. */
    private void pickFriend() {
        final FragmentActivity activity = requireActivity();
        final SocialService service = SocialService.get(activity);
        View sheet = LayoutInflater.from(activity).inflate(R.layout.dialog_projects_actions, null, false);
        LinearLayout list = sheet.findViewById(R.id.projects_actions_list);
        final AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(R.string.projects_chat_message).setView(sheet)
                .setNegativeButton(R.string.projects_close, null).create();
        SocialState.Sections sections = service.state().sections(service.prefs().favourites());
        List<Friend> friends = new ArrayList<>();
        friends.addAll(sections.playing);
        friends.addAll(sections.online);
        friends.addAll(sections.offline);
        if (friends.isEmpty()) {
            TextView none = new TextView(activity);
            none.setText(R.string.projects_social_empty_title);
            none.setTextColor(ContextCompat.getColor(activity, R.color.projects_ash_400));
            none.setPadding(16, 16, 16, 16);
            list.addView(none);
        }
        for (final Friend friend : friends) {
            View row = LayoutInflater.from(activity).inflate(R.layout.item_projects_social_person, list, false);
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) row.getLayoutParams();
            lp.setMarginStart(0);
            lp.setMarginEnd(0);
            AvatarView avatar = row.findViewById(R.id.projects_person_avatar);
            avatar.setProfile(friend.profile);
            avatar.setSurfaceColor(ContextCompat.getColor(activity, R.color.projects_obsidian_800));
            avatar.setDot(SocialUi.dot(service.state().presence(friend.profile.id)));
            ((TextView) row.findViewById(R.id.projects_person_name)).setText(friend.profile.name());
            ((TextView) row.findViewById(R.id.projects_person_line)).setText(SocialUi.presenceText(activity, service.state().presence(friend.profile.id)));
            Press.attach(row, Press.Style.ROW);
            row.setOnClickListener(v -> {
                dialog.dismiss();
                String me = service.state().myId();
                if (me != null) ChatFragment.open(ChatListFragment.this, ChatConv.direct(me, friend.profile.id));
            });
            list.addView(row);
        }
        dialog.show();
    }

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private List<Row> mRows = new ArrayList<>();
        private final Map<String, Long> mIds = new HashMap<>();

        Adapter() {
            setHasStableIds(true);
        }

        void submit(final List<Row> rows) {
            final List<Row> old = mRows;
            mRows = rows;
            if (old.isEmpty()) {
                notifyDataSetChanged();
                return;
            }
            DiffUtil.calculateDiff(new DiffUtil.Callback() {
                @Override public int getOldListSize() { return old.size(); }
                @Override public int getNewListSize() { return rows.size(); }
                @Override public boolean areItemsTheSame(int o, int n) { return old.get(o).conv.equals(rows.get(n).conv); }
                @Override public boolean areContentsTheSame(int o, int n) { return old.get(o).content.equals(rows.get(n).content); }
            }).dispatchUpdatesTo(this);
        }

        @Override
        public long getItemId(int position) {
            String id = mRows.get(position).conv;
            Long stable = mIds.get(id);
            if (stable == null) {
                stable = (long) mIds.size();
                mIds.put(id, stable);
            }
            return stable;
        }

        @Override
        public int getItemCount() {
            return mRows.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_projects_social_person, parent, false);
            Press.attach(view, Press.Style.ROW);
            return new RecyclerView.ViewHolder(view) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            bind(holder.itemView, mRows.get(position));
        }
    }

    private void bind(View view, final Row row) {
        Context context = view.getContext();
        SocialService service = SocialService.get(context);
        SocialState state = service.state();
        AvatarView avatar = view.findViewById(R.id.projects_person_avatar);
        CrewMarkView mark = view.findViewById(R.id.projects_person_mark);
        TextView name = view.findViewById(R.id.projects_person_name);
        TextView line = view.findViewById(R.id.projects_person_line);
        TextView badge = view.findViewById(R.id.projects_person_badge);
        view.findViewById(R.id.projects_person_star).setVisibility(View.GONE);
        view.findViewById(R.id.projects_person_action).setVisibility(View.GONE);
        view.findViewById(R.id.projects_person_secondary).setVisibility(View.GONE);
        String crewId = ChatConv.crewId(row.conv);
        String me = state.myId();
        if (crewId != null) {
            Crew crew = state.crew(crewId);
            avatar.setVisibility(View.GONE);
            mark.setVisibility(View.VISIBLE);
            if (crew != null) mark.setLook(crew.team.emblem, crew.team.color, crew.team.frame);
            name.setText(crew == null ? getString(R.string.projects_chat_crew) : crew.team.name);
            ((CrewTagChip) view.findViewById(R.id.projects_person_tag)).setBadgeOf(null, null, CrewTagChip.Size.MD);
            CrewTagChip.labelName(name, null);
        } else {
            Profile other = state.profile(ChatConv.other(row.conv, me));
            mark.setVisibility(View.GONE);
            avatar.setVisibility(View.VISIBLE);
            avatar.setProfile(other);
            avatar.setSurfaceColor(ContextCompat.getColor(context, R.color.projects_obsidian_950));
            avatar.setDot(other == null ? AvatarView.Dot.NONE : SocialUi.dot(state.presence(other.id)));
            avatar.setDimmed(false);
            name.setText(other == null ? "" : other.name());
            // TEAM6: the friend's tag before their name.
            gg.projects.mobile.social.SocialModel.Badge worn = other == null ? null
                    : gg.projects.mobile.social.BadgeRules.shown(service.gate(), other.badge);
            ((CrewTagChip) view.findViewById(R.id.projects_person_tag)).setBadgeOf(other == null ? null : other.id, worn,
                    CrewTagChip.Size.MD);
            CrewTagChip.labelName(name, worn);
        }
        ChatBook.Summary s = row.summary;
        int unread = s == null ? 0 : s.unread;
        // CHAT2: "yazıyor…" while someone writes there (gold), else the newest message.
        boolean typing = !service.chat().writers(row.conv).isEmpty();
        if (typing) {
            line.setText(R.string.projects_typing_list);
        } else if (s == null || s.last == null) {
            line.setText(R.string.projects_chat_crew);
        } else {
            // FIX2: a deleted newest message says so; CHAT2: a sticker says which.
            line.setText(ChatPreview.line(context, state, s.last, me, crewId != null));
        }
        line.setTextColor(ContextCompat.getColor(context, typing ? R.color.projects_gold_300
                : unread > 0 ? R.color.projects_bone_100 : R.color.projects_ash_400));
        if (unread > 0) {
            badge.setVisibility(View.VISIBLE);
            badge.setText(unread > 99 ? "99+" : String.valueOf(unread));
            badge.setBackgroundResource(R.drawable.projects_chat_unread_badge);
            badge.setTextColor(ContextCompat.getColor(context, R.color.projects_on_gold));
            badge.setContentDescription(context.getResources().getQuantityString(R.plurals.projects_chat_unread, unread, unread));
        } else {
            badge.setVisibility(View.GONE);
        }
        ChatBook book = service.chat().book();
        if (book != null && book.mutedAt(row.conv, SocialService.nowSeconds())) {
            Drawable slash = ContextCompat.getDrawable(context, R.drawable.projects_ic_bell_slash);
            if (slash != null) {
                slash = slash.mutate();
                slash.setTint(ContextCompat.getColor(context, R.color.projects_ash_400));
                int size = Math.round(14 * context.getResources().getDisplayMetrics().density);
                slash.setBounds(0, 0, size, size);
            }
            name.setCompoundDrawablesRelative(null, null, slash, null);
            name.setCompoundDrawablePadding(Math.round(6 * context.getResources().getDisplayMetrics().density));
        } else {
            name.setCompoundDrawablesRelative(null, null, null, null);
        }
        view.setOnClickListener(v -> ChatFragment.open(ChatListFragment.this, row.conv));
        // FIX2: a conversation's row holds the friend's or the crew's menu.
        final String other = crewId == null ? ChatConv.other(row.conv, me) : null;
        SocialMenuSheet.attach(view, name.getText(), v -> {
            if (!isAdded()) return true;
            if (crewId != null) SocialMenuSheet.crew(requireActivity(), crewId, false, false, v);
            else if (other != null) SocialMenuSheet.person(requireActivity(), other, null, SocialMenuSheet.Place.CHAT_LIST, v);
            return true;
        });
    }

    private static String oneLine(String text) {
        return text.replace('\n', ' ');
    }
}
