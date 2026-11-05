package gg.projects.mobile.voice.ui;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.R;

import java.util.ArrayList;
import java.util.List;

import gg.projects.mobile.social.ui.CrewViews;
import gg.projects.mobile.ui.ChamferDrawable;
import gg.projects.mobile.ui.Haptics;
import gg.projects.mobile.ui.Press;
import gg.projects.mobile.voice.VoiceHub;
import gg.projects.mobile.voice.VoiceLink;
import gg.projects.mobile.voice.VoiceRings;
import gg.projects.mobile.voice.VoiceState;

/**
 * The crew page's "Sesli" (the desktop's crew voice section, VOICE-ROOMS): the crew's rooms, "Genel" always first (also
 * while empty: no explanation, just "Katıl"), each with the faces in it (as many as fit, then "+N"), "2/4" when the room
 * has its own limit, and "Katıl" (or "Ayrıl" for the room the player is in); under them "Yeni oda" while the crew may
 * open one. Managing rooms (rename, order, delete, dragging people) stays on the desktop.
 */
public final class VoiceCrewRows {
    private VoiceCrewRows() {}

    /** One row: a room, or "Yeni oda". */
    public static final class Item {
        public final String crew;
        @Nullable public final VoiceRings.CrewRoom room;
        final boolean mine;

        Item(String crew, @Nullable VoiceRings.CrewRoom room, boolean mine) {
            this.crew = crew;
            this.room = room;
            this.mine = mine;
        }

        /** What the row shows, for the list's diff. */
        public String key() {
            if (room == null) return "new|" + crew;
            return room.id + "|" + room.name + "|" + room.ids + "|" + room.max + "|" + mine;
        }

        public String id() {
            return room == null ? "voice-new-" + crew : "voice-" + room.id;
        }
    }

    /** The section's rows for {@code crew}; empty while voice chat does not show. */
    public static List<Item> items(Context context, String crew) {
        List<Item> out = new ArrayList<>();
        VoiceHub hub = VoiceUi.hub(context);
        if (hub == null || !hub.prefs().enabled()) return out;
        VoiceState state = VoiceLink.get(context).state();
        for (VoiceRings.CrewRoom room : hub.rings().crewRooms(crew)) {
            out.add(new Item(crew, room, state != null && room.id.equals(state.room)));
        }
        if (hub.rings().canAddRoom(crew)) out.add(new Item(crew, null, false));
        return out;
    }

    /** A row's frame (the list's view holder makes one per row). */
    public static FrameLayout create(Context context) {
        FrameLayout frame = new FrameLayout(context);
        int side = Math.round(12 * context.getResources().getDisplayMetrics().density);
        frame.setPadding(side, 0, side, 0);
        frame.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return frame;
    }

    public static void bind(FrameLayout frame, final FragmentActivity activity, final Item item) {
        frame.removeAllViews();
        Context c = frame.getContext();
        float dp = c.getResources().getDisplayMetrics().density;
        if (item.room == null) {
            frame.addView(CrewViews.actionRow(c, R.drawable.projects_ic_voice_headphones, c.getString(R.string.projects_voice_new_room),
                    R.color.projects_bone_100, v -> VoiceUi.newCrewRoom(activity, item.crew)));
            return;
        }
        final VoiceRings.CrewRoom room = item.room;
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Math.round(56 * dp));
        row.setPaddingRelative(Math.round(8 * dp), 0, Math.round(4 * dp), 0);

        TextView name = new TextView(c);
        name.setText(VoiceHub.roomName(c, room.name, room.general()));
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        name.setTextColor(ContextCompat.getColor(c, R.color.projects_bone_100));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        font(name, R.font.figtree_semibold);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row.addView(name, nameLp);

        // The faces in it: as many as fit beside the name (five on a phone), then "+N".
        LinearLayout faces = new LinearLayout(c);
        faces.setOrientation(LinearLayout.HORIZONTAL);
        faces.setGravity(Gravity.CENTER_VERTICAL);
        int shown = 0;
        for (String id : room.ids) {
            if (shown == 5) break;
            VoiceFace face = new VoiceFace(c, 28);
            face.avatar().setProfile(VoiceFace.profileOf(c, id, null));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.round(28 * dp), Math.round(28 * dp));
            if (shown > 0) lp.setMarginStart(-Math.round(6 * dp));
            faces.addView(face, lp);
            shown++;
        }
        if (room.ids.size() > shown) {
            TextView more = new TextView(c);
            more.setText("+" + (room.ids.size() - shown));
            more.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            more.setTextColor(ContextCompat.getColor(c, R.color.projects_ash_400));
            font(more, R.font.tektur_semibold);
            more.setPaddingRelative(Math.round(4 * dp), 0, 0, 0);
            faces.addView(more);
        }
        LinearLayout.LayoutParams facesLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        facesLp.setMarginStart(Math.round(10 * dp));
        row.addView(faces, facesLp);

        if (room.max > 0) {
            TextView count = new TextView(c);
            count.setText(c.getString(R.string.projects_voice_room_count, room.ids.size(), room.max));
            count.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            count.setTextColor(ContextCompat.getColor(c, R.color.projects_ash_400));
            font(count, R.font.tektur_semibold);
            count.setPaddingRelative(0, 0, Math.round(8 * dp), 0);
            row.addView(count);
        }

        TextView go = new TextView(c);
        go.setText(item.mine ? R.string.projects_voice_leave : R.string.projects_voice_join);
        go.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        go.setTextColor(ContextCompat.getColor(c, item.mine ? R.color.projects_ash_300 : R.color.projects_bone_100));
        font(go, R.font.figtree_semibold);
        go.setGravity(Gravity.CENTER);
        go.setPadding(Math.round(14 * dp), 0, Math.round(14 * dp), 0);
        go.setBackground(item.mine ? ChamferDrawable.outlined(c) : ChamferDrawable.secondary(c));
        go.setContentDescription(c.getString(item.mine ? R.string.projects_voice_leave : R.string.projects_voice_join) + ", "
                + name.getText());
        go.setOnClickListener(v -> {
            Haptics.confirm(v);
            if (item.mine) VoiceLink.get(activity).leave();
            else VoiceUi.joinCrew(activity, item.crew, room.id);
        });
        Press.attach(go, Press.Style.BUTTON);
        row.addView(go, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(40 * dp)));
        frame.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private static void font(TextView t, int font) {
        try {
            t.setTypeface(ResourcesCompat.getFont(t.getContext(), font));
        } catch (RuntimeException ignored) {
            // The theme's font.
        }
    }
}
