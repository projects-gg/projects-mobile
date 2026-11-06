package gg.projects.mobile.voice.ui;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.text.InputFilter;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
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

import gg.projects.mobile.social.SocialModel;
import gg.projects.mobile.social.SocialService;
import gg.projects.mobile.social.SocialState;
import gg.projects.mobile.social.ui.BottomPanel;
import gg.projects.mobile.social.ui.CrewViews;
import gg.projects.mobile.social.ui.SocialUi;
import gg.projects.mobile.ui.ChamferDrawable;
import gg.projects.mobile.ui.Haptics;
import gg.projects.mobile.ui.Press;
import gg.projects.mobile.voice.VoiceHub;
import gg.projects.mobile.voice.VoiceAnswer;
import gg.projects.mobile.voice.VoiceLink;
import gg.projects.mobile.voice.VoiceNotify;
import gg.projects.mobile.voice.VoiceRings;
import gg.projects.mobile.voice.VoiceRoomRules;
import gg.projects.mobile.voice.VoiceState;

/**
 * The crew page's "Sesli" (the desktop's crew voice section, VOICE-ROOMS): the crew's rooms, "Genel" always first (also
 * while empty: no explanation, just "Katıl"), each with the faces in it (as many as fit, then "+N"), "2/4" when the room
 * has its own limit, and "Katıl" (or "Ayrıl" for the room the player is in); under them "Yeni oda" while the crew may
 * open one. M051 (SHARE-LIVE): "YAYINDA" after a room's faces and count while someone in it shares their screen (the
 * desktop's crew page). M053: managing rooms while Studio lets crews do it ({@link VoiceRoomRules}): a room's "⋯" (and a
 * long press) opens "Odayı düzenle" (name and people limit), "Yukarı taşı" / "Aşağı taşı" and "Odayı sil" for the leader
 * and moderators, edit and delete for the room's opener. Dragging people between rooms stays on the desktop (the room
 * sheet's "Taşı" moves one person).
 */
public final class VoiceCrewRows {
    private VoiceCrewRows() {}

    /** One row: a room, or "Yeni oda". */
    public static final class Item {
        public final String crew;
        @Nullable public final VoiceRings.CrewRoom room;
        final boolean mine;

        /** M053: what the player may do with the room. */
        final VoiceRoomRules.Powers powers;

        Item(String crew, @Nullable VoiceRings.CrewRoom room, boolean mine, VoiceRoomRules.Powers powers) {
            this.crew = crew;
            this.room = room;
            this.mine = mine;
            this.powers = powers;
        }

        /** What the row shows, for the list's diff. */
        public String key() {
            if (room == null) return "new|" + crew;
            return room.id + "|" + room.name + "|" + room.ids + "|" + room.max + "|" + mine + "|" + room.live + "|"
                    + powers.edit + powers.order + powers.remove;
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
        boolean manage = hub.rings().service().manage;
        SocialState social = SocialService.get(context).state();
        String me = social.myId();
        SocialModel.Crew here = social.crew(crew);
        SocialModel.Member mine = here == null || me == null ? null : here.team.member(me);
        boolean founder = mine != null && mine.founder;
        boolean moderator = mine != null && mine.moderator;
        for (VoiceRings.CrewRoom room : hub.rings().crewRooms(crew)) {
            out.add(new Item(crew, room, state != null && room.id.equals(state.room),
                    VoiceRoomRules.powers(manage, founder, moderator, room)));
        }
        if (hub.rings().canAddRoom(crew)) out.add(new Item(crew, null, false, VoiceRoomRules.powers(false, false, false, null)));
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
        boolean live = !room.live.isEmpty();
        if (live) {
            TextView tag = LiveTag.make(c, false);
            LiveTag.show(tag, true);
            LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            tagLp.setMarginEnd(Math.round(8 * dp));
            row.addView(tag, tagLp);
        }

        if (item.powers.any()) {
            // M053: the room's own menu (the desktop's "⋯"); a long press on the row opens it too.
            final String roomName = name.getText().toString();
            ImageView more = new ImageView(c);
            more.setImageResource(R.drawable.projects_ic_dots);
            more.setImageTintList(android.content.res.ColorStateList.valueOf(ContextCompat.getColor(c, R.color.projects_ash_300)));
            more.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            int pad = Math.round(10 * dp);
            more.setPadding(pad, pad, pad, pad);
            more.setBackgroundResource(R.drawable.projects_icon_button_ripple);
            more.setContentDescription(c.getString(R.string.projects_voice_room_more, roomName));
            more.setOnClickListener(v -> menu(activity, item));
            Press.attach(more, Press.Style.BUTTON);
            LinearLayout.LayoutParams moreLp = new LinearLayout.LayoutParams(Math.round(40 * dp), Math.round(40 * dp));
            moreLp.setMarginEnd(Math.round(4 * dp));
            row.addView(more, moreLp);
            row.setOnLongClickListener(v -> {
                Haptics.confirm(v);
                menu(activity, item);
                return true;
            });
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
                + LiveTag.spoken(c, name.getText().toString(), live));
        go.setOnClickListener(v -> {
            Haptics.confirm(v);
            if (item.mine) VoiceLink.get(activity).leave();
            else VoiceUi.joinCrew(activity, item.crew, room.id);
        });
        Press.attach(go, Press.Style.BUTTON);
        row.addView(go, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(40 * dp)));
        frame.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    // ---- M053: a room's menu ----

    private static void menu(final FragmentActivity activity, final Item item) {
        final VoiceRings.CrewRoom room = item.room;
        if (room == null) return;
        final VoiceHub hub = VoiceUi.hub(activity);
        if (hub == null) return;
        Context c = activity;
        final String roomName = VoiceHub.roomName(c, room.name, room.general());
        LinearLayout content = new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);
        int side = Math.round(16 * c.getResources().getDisplayMetrics().density);
        content.setPadding(side, 0, side, Math.round(12 * c.getResources().getDisplayMetrics().density));
        TextView title = new TextView(c);
        title.setText(roomName);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTextColor(ContextCompat.getColor(c, R.color.projects_bone_100));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        font(title, R.font.tektur_semibold);
        title.setPaddingRelative(Math.round(4 * c.getResources().getDisplayMetrics().density), 0, 0,
                Math.round(8 * c.getResources().getDisplayMetrics().density));
        content.addView(title);
        final BottomPanel[] panel = new BottomPanel[1];
        if (item.powers.edit) {
            content.addView(CrewViews.actionRow(c, R.drawable.projects_ic_pencil, c.getString(R.string.projects_voice_room_edit),
                    R.color.projects_bone_100, v -> {
                        panel[0].dismiss();
                        edit(activity, item.crew, room);
                    }));
        }
        if (item.powers.order) {
            final List<VoiceRings.CrewRoom> rooms = hub.rings().crewRooms(item.crew);
            final List<String> up = VoiceRoomRules.moved(rooms, room.id, -1);
            final List<String> down = VoiceRoomRules.moved(rooms, room.id, 1);
            if (up != null) {
                content.addView(CrewViews.actionRow(c, R.drawable.projects_ic_arrow_up_bold, c.getString(R.string.projects_voice_room_up),
                        R.color.projects_bone_100, v -> {
                            panel[0].dismiss();
                            order(activity, item.crew, up);
                        }));
            }
            if (down != null) {
                content.addView(CrewViews.actionRow(c, R.drawable.projects_ic_arrow_down, c.getString(R.string.projects_voice_room_down),
                        R.color.projects_bone_100, v -> {
                            panel[0].dismiss();
                            order(activity, item.crew, down);
                        }));
            }
        }
        if (item.powers.remove) {
            content.addView(CrewViews.divider(c));
            content.addView(CrewViews.actionRow(c, R.drawable.projects_ic_trash, c.getString(R.string.projects_voice_room_remove),
                    R.color.projects_flare_400, v -> {
                        panel[0].dismiss();
                        remove(activity, item.crew, room, roomName);
                    }));
        }
        panel[0] = BottomPanel.of(c, content, 0.6f);
        panel[0].show();
    }

    private static void order(final FragmentActivity activity, String crew, List<String> rooms) {
        VoiceHub hub = VoiceUi.hub(activity);
        if (hub == null) return;
        hub.orderCrewRooms(crew, rooms, answer -> {
            if (!answer.ok()) {
                VoiceUi.refused(activity, answer, null);
                return;
            }
            // The list redraws from the hub's voice_rooms; screen readers hear it.
            android.view.View root = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
            if (root != null) root.announceForAccessibility(activity.getString(R.string.projects_voice_room_ordered));
        });
    }

    /**
     * "Odayı sil": always asked (the desktop deletes an empty room at once with a 5 s "Geri al"; on the phone the row has
     * no place to wait in, and a deleted room cannot come back by its id). An empty room is sent with {@code empty}, so
     * someone who came in meanwhile keeps it.
     */
    private static void remove(final FragmentActivity activity, final String crew, final VoiceRings.CrewRoom room, final String roomName) {
        final boolean empty = room.ids.isEmpty();
        SocialUi.confirmDanger(activity, activity.getString(R.string.projects_voice_room_remove_title, roomName),
                empty ? null : activity.getString(R.string.projects_voice_room_remove_ask), activity.getString(R.string.projects_voice_room_remove),
                () -> {
                    VoiceHub hub = VoiceUi.hub(activity);
                    if (hub == null) return;
                    hub.removeCrewRoom(crew, room.id, empty, answer -> {
                        if (answer.ok()) {
                            SocialUi.toast(activity, activity.getString(R.string.projects_voice_room_removed, roomName));
                        } else if (answer.refused == VoiceAnswer.Refusal.NOTHING) {
                            SocialUi.toast(activity, activity.getString(R.string.projects_voice_room_not_removed));
                        } else {
                            VoiceUi.refused(activity, answer, null);
                        }
                    });
                });
    }

    /** "Odayı düzenle": the name and the people limit; "Kaydet" sends only what changed. */
    private static void edit(final FragmentActivity activity, final String crew, final VoiceRings.CrewRoom room) {
        final VoiceHub hub = VoiceUi.hub(activity);
        if (hub == null) return;
        final Context c = activity;
        final float dp = c.getResources().getDisplayMetrics().density;
        final int crewMax = hub.rings().service().crewMax;
        final List<Integer> choices = VoiceRoomRules.limitChoices(crewMax);
        final int[] limit = {VoiceRoomRules.limitShown(room.max, crewMax)};
        final int startLimit = limit[0];
        final String startName = VoiceHub.roomName(c, room.name, room.general());

        LinearLayout content = new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Math.round(20 * dp), 0, Math.round(20 * dp), Math.round(16 * dp));
        TextView title = new TextView(c);
        title.setText(R.string.projects_voice_room_edit);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTextColor(ContextCompat.getColor(c, R.color.projects_bone_100));
        font(title, R.font.tektur_semibold);
        content.addView(title);

        content.addView(label(c, R.string.projects_voice_name_hint, 16));
        final EditText field = new EditText(c);
        field.setSingleLine(true);
        field.setText(startName);
        field.setSelection(field.getText().length());
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(VoiceRoomRules.NAME_MAX)});
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        field.setTextColor(ContextCompat.getColor(c, R.color.projects_bone_100));
        field.setBackgroundResource(R.drawable.projects_input);
        field.setPadding(Math.round(14 * dp), 0, Math.round(14 * dp), 0);
        if (android.os.Build.VERSION.SDK_INT >= 26) field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        field.setContentDescription(c.getString(R.string.projects_voice_name_hint));
        font(field, R.font.figtree_regular);
        content.addView(field, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(48 * dp)));

        content.addView(label(c, R.string.projects_voice_room_limit, 16));
        LinearLayout stepper = new LinearLayout(c);
        stepper.setOrientation(LinearLayout.HORIZONTAL);
        stepper.setGravity(Gravity.CENTER_VERTICAL);
        final TextView value = new TextView(c);
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        value.setTextColor(ContextCompat.getColor(c, R.color.projects_bone_100));
        value.setGravity(Gravity.CENTER);
        value.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        font(value, R.font.figtree_semibold);
        final ImageView fewer = stepButton(c, R.drawable.projects_ic_minus, R.string.projects_voice_room_fewer);
        final ImageView moreButton = stepButton(c, R.drawable.projects_ic_plus, R.string.projects_voice_room_more_people);
        final Runnable showLimit = () -> {
            value.setText(c.getResources().getQuantityString(R.plurals.projects_voice_room_limit_value, limit[0], limit[0]));
            int at = choices.indexOf(limit[0]);
            fewer.setEnabled(at > 0);
            fewer.setAlpha(at > 0 ? 1f : 0.4f);
            moreButton.setEnabled(at >= 0 && at < choices.size() - 1);
            moreButton.setAlpha(at >= 0 && at < choices.size() - 1 ? 1f : 0.4f);
        };
        fewer.setOnClickListener(v -> {
            int at = choices.indexOf(limit[0]);
            if (at > 0) limit[0] = choices.get(at - 1);
            Haptics.tick(v);
            showLimit.run();
        });
        moreButton.setOnClickListener(v -> {
            int at = choices.indexOf(limit[0]);
            if (at >= 0 && at < choices.size() - 1) limit[0] = choices.get(at + 1);
            Haptics.tick(v);
            showLimit.run();
        });
        showLimit.run();
        int step = Math.round(44 * dp);
        stepper.addView(fewer, new LinearLayout.LayoutParams(step, step));
        stepper.addView(value, new LinearLayout.LayoutParams(Math.round(112 * dp), ViewGroup.LayoutParams.WRAP_CONTENT));
        stepper.addView(moreButton, new LinearLayout.LayoutParams(step, step));
        content.addView(stepper);

        final TextView error = new TextView(c);
        error.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        error.setTextColor(ContextCompat.getColor(c, R.color.projects_flare_400));
        error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        error.setVisibility(View.GONE);
        font(error, R.font.figtree_regular);
        LinearLayout.LayoutParams errorLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        errorLp.topMargin = Math.round(10 * dp);
        content.addView(error, errorLp);

        LinearLayout buttons = new LinearLayout(c);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        final BottomPanel[] panel = new BottomPanel[1];
        android.widget.Button cancel = gg.projects.mobile.cloud.ui.CloudUi.textButton(c, c.getString(R.string.projects_voice_room_cancel),
                v -> panel[0].dismiss());
        final android.widget.Button save = gg.projects.mobile.cloud.ui.CloudUi.button(c, c.getString(R.string.projects_voice_room_save), true, null);
        save.setOnClickListener(v -> {
            String typed = field.getText().toString().trim();
            if (!VoiceRoomRules.nameFits(typed)) {
                error.setText(R.string.projects_voice_refused_invalid_name);
                error.setVisibility(View.VISIBLE);
                return;
            }
            final String name = typed.equals(startName) ? null : typed;
            final boolean keepMax = limit[0] == startLimit;
            if (name == null && keepMax) {
                panel[0].dismiss();
                return;
            }
            save.setEnabled(false);
            hub.editCrewRoom(crew, room.id, name, keepMax, VoiceRoomRules.limitToSend(limit[0], crewMax), answer -> {
                save.setEnabled(true);
                if (answer.ok()) {
                    panel[0].dismiss();
                    return;
                }
                String text = VoiceNotify.refusalText(c, answer, null);
                error.setText(text.isEmpty() ? c.getString(R.string.projects_voice_refused_failed) : text);
                error.setVisibility(View.VISIBLE);
            });
        });
        buttons.addView(cancel);
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        saveLp.setMarginStart(Math.round(8 * dp));
        buttons.addView(save, saveLp);
        LinearLayout.LayoutParams buttonsLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonsLp.topMargin = Math.round(16 * dp);
        content.addView(buttons, buttonsLp);

        panel[0] = BottomPanel.of(c, content, 0.9f);
        panel[0].show();
    }

    private static TextView label(Context c, int text, int topDp) {
        TextView label = new TextView(c);
        label.setText(text);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        label.setTextColor(ContextCompat.getColor(c, R.color.projects_ash_300));
        font(label, R.font.figtree_semibold);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        float dp = c.getResources().getDisplayMetrics().density;
        lp.topMargin = Math.round(topDp * dp);
        lp.bottomMargin = Math.round(8 * dp);
        label.setLayoutParams(lp);
        return label;
    }

    /** A 44 dp minus or plus (Phosphor) on the outlined chamfer, read out by what it does. */
    private static ImageView stepButton(Context c, int icon, int spoken) {
        ImageView button = new ImageView(c);
        button.setImageResource(icon);
        button.setImageTintList(android.content.res.ColorStateList.valueOf(ContextCompat.getColor(c, R.color.projects_bone_100)));
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int pad = Math.round(12 * c.getResources().getDisplayMetrics().density);
        button.setPadding(pad, pad, pad, pad);
        button.setBackground(ChamferDrawable.outlined(c));
        button.setContentDescription(c.getString(spoken));
        button.setClickable(true);
        button.setFocusable(true);
        Press.attach(button, Press.Style.BUTTON);
        return button;
    }

    private static void font(TextView t, int font) {
        try {
            t.setTypeface(ResourcesCompat.getFont(t.getContext(), font));
        } catch (RuntimeException ignored) {
            // The theme's font.
        }
    }
}
