package gg.projects.mobile.cloud.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;

import net.kdt.pojavlaunch.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import gg.projects.mobile.cloud.CloudModel;
import gg.projects.mobile.cloud.auto.Auto;
import gg.projects.mobile.cloud.auto.AutoSettings;
import gg.projects.mobile.social.ui.WrapLayout;
import gg.projects.mobile.ui.ChamferDrawable;
import gg.projects.mobile.ui.Haptics;
import gg.projects.mobile.ui.Press;

/**
 * The auto modes in "Ayarlar" → "Genel" (services/cloud-afk README "Auto settings"; the site's editors, afk-web
 * {@code auto-settings.tsx}, in its words and order): the switch with its line (or why it cannot be turned on: the server's
 * limit with its public reason and the way to the list, or no slot), the note every mode carries (not tried on each
 * server), and once on the few choices a player makes: what happens when the inventory is full, the small moves between
 * casts or blocks, which blocks; the rod's or pickaxe's care folded under "Gelişmiş". A limited server shows the switch
 * as stored and disabled. Every change hands the whole setting to the screen, which keeps it only while it differs from
 * what is stored.
 */
final class AutoSettingsBlock {
    private AutoSettingsBlock() {}

    /** The settings screen's side. */
    interface Host {
        Context ctx();

        /** A save issue's words on this field ({@code fishing}, {@code fishing.keep}, {@code mining.blocks}, …), or null. */
        @Nullable
        String issue(String field);

        String itemName(String id);

        void openLimited();

        void fishing(AutoSettings.Fishing edited);

        void mining(AutoSettings.Mining edited);

        /** The game's own item names are wanted (the blocks); {@code ready} runs once they are in. */
        void needNames(Runnable ready);
    }

    /** The two blocks when "Seçtiklerim" is picked first: the generator's own (the site's). */
    private static final List<String> FIRST_CHOSEN = Arrays.asList("minecraft:cobblestone", "minecraft:stone");

    /** "Otomatik Balık Tutma". */
    static void fishing(LinearLayout content, final Host h, CloudModel.AutoFeature feature, boolean stored, AutoSettings.Fishing shown) {
        final Context c = h.ctx();
        final AutoSettings.Fishing work = shown.copy();
        final LinearLayout details = column(c);
        modeSwitch(content, h, AutoSettings.FISHING, feature, stored, work.enabled, on -> {
            work.enabled = on;
            details.setVisibility(on ? View.VISIBLE : View.GONE);
            h.fishing(work.copy());
        });
        full(details, h, AutoSettings.FISHING, work.full, () -> h.fishing(work.copy()));
        details.addView(CloudUi.gap(c, 12));
        LinearLayout lookGroup = CloudUi.group(c);
        lookGroup.addView(CloudUi.toggle(c, c.getString(R.string.projects_auto_look_label), work.look, on -> {
            work.look = on;
            h.fishing(work.copy());
        }));
        details.addView(lookGroup);
        details.addView(CloudUi.note(c, c.getString(R.string.projects_auto_look_line)));
        care(details, h, AutoSettings.FISHING, work.rod, () -> h.fishing(work.copy()));
        details.setVisibility(work.enabled && feature.limited == null ? View.VISIBLE : View.GONE);
        content.addView(details, CloudUi.matchWrap());
    }

    /** "Otomatik Kazma". */
    static void mining(LinearLayout content, final Host h, CloudModel.AutoFeature feature, boolean stored, AutoSettings.Mining shown) {
        final Context c = h.ctx();
        final AutoSettings.Mining work = shown.copy();
        final LinearLayout details = column(c);
        modeSwitch(content, h, AutoSettings.MINING, feature, stored, work.enabled, on -> {
            work.enabled = on;
            details.setVisibility(on ? View.VISIBLE : View.GONE);
            h.mining(work.copy());
        });
        blocks(details, h, work, () -> h.mining(work.copy()));
        full(details, h, AutoSettings.MINING, work.full, () -> h.mining(work.copy()));
        idle(details, h, work.idle, () -> h.mining(work.copy()));
        care(details, h, AutoSettings.MINING, work.tool, () -> h.mining(work.copy()));
        details.setVisibility(work.enabled && feature.limited == null ? View.VISIBLE : View.GONE);
        content.addView(details, CloudUi.matchWrap());
    }

    private static LinearLayout column(Context c) {
        LinearLayout column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    /** The switch, its line or why it cannot be turned on, the per-server note, and a refused save's words. */
    private static void modeSwitch(LinearLayout content, final Host h, String mode, CloudModel.AutoFeature feature, boolean stored,
                                   boolean checked, CloudUi.Toggle toggle) {
        Context c = h.ctx();
        content.addView(CloudUi.gap(c, 16));
        LinearLayout group = CloudUi.group(c);
        // Limited: shown as it is stored, not changed here (README "Auto switches"); no slot: it stays off and says why.
        boolean noSlot = !stored && !checked && feature.slot != null;
        boolean disabled = feature.limited != null || noSlot;
        SwitchCompat sw = CloudUi.toggle(c, c.getString(Auto.name(mode)), checked, toggle);
        sw.setEnabled(!disabled);
        // A switch that cannot be used dims with its title; the words under it stay readable (SHEET-UX 33).
        if (disabled) sw.setAlpha(0.55f);
        group.addView(sw);
        content.addView(group);
        if (feature.limited != null) {
            content.addView(CloudUi.note(c, Auto.limited(c, mode, feature.limited)));
            android.widget.Button see = CloudUi.siteLink(c, c.getString(R.string.projects_auto_limited_see), v -> h.openLimited());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginStart(CloudUi.dp(c, -4));
            content.addView(see, lp);
        } else {
            content.addView(CloudUi.note(c, noSlot ? Auto.slot(c, mode, feature.slot) : c.getString(Auto.line(mode))));
        }
        // The owner's note under every mode switch (20 Nov 2026), with the quiet info mark.
        TextView disclaimer = CloudUi.note(c, c.getString(R.string.projects_auto_disclaimer));
        disclaimer.setCompoundDrawablesRelative(CloudUi.tinted(c, R.drawable.projects_ic_info, R.color.projects_ash_400, 15), null, null, null);
        disclaimer.setCompoundDrawablePadding(CloudUi.dp(c, 8));
        disclaimer.setGravity(Gravity.TOP);
        content.addView(disclaimer);
        String problem = h.issue(mode);
        if (problem != null) {
            TextView error = CloudRowSheet.error(c);
            CloudRowSheet.show(error, problem);
            content.addView(error);
        }
    }

    /** "Envanter Dolunca": wait, keep going, or a command (the text rule; never a secret). */
    private static void full(LinearLayout into, final Host h, String mode, final AutoSettings.Full full, final Runnable changed) {
        final Context c = h.ctx();
        into.addView(CloudRowSheet.label(c, c.getString(R.string.projects_auto_full_label)));
        final String[] values = {AutoSettings.FULL_WAIT, AutoSettings.FULL_KEEP, AutoSettings.FULL_COMMAND};
        CharSequence[] words = {c.getString(R.string.projects_auto_full_wait), c.getString(R.string.projects_auto_full_keep),
                c.getString(R.string.projects_auto_full_command)};
        int at = Arrays.asList(values).indexOf(full.what);
        final TextView help = CloudUi.note(c, "");
        final CloudUi.Field command = CloudUi.field(c, c.getString(R.string.projects_auto_command_label), full.command,
                c.getString(R.string.projects_auto_command_hint),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_URI);
        command.input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(256)});
        String commandIssue = h.issue(mode + ".command");
        command.setError(commandIssue);
        command.setHelp(commandIssue == null ? c.getString(R.string.projects_auto_full_command_help) : null);
        watch(command.input, s -> {
            full.command = s;
            changed.run();
        });
        final Runnable show = () -> {
            boolean typed = AutoSettings.FULL_COMMAND.equals(full.what);
            command.view.setVisibility(typed ? View.VISIBLE : View.GONE);
            help.setVisibility(typed ? View.GONE : View.VISIBLE);
            help.setText(AutoSettings.FULL_KEEP.equals(full.what) ? R.string.projects_auto_full_keep_help : R.string.projects_auto_full_wait_help);
        };
        into.addView(CloudUi.segmented(c, words, at < 0 ? 0 : at, i -> {
            full.what = values[i];
            show.run();
            changed.run();
        }));
        into.addView(help);
        into.addView(command.view);
        show.run();
    }

    /** "Hangi Blokları Kırsın?": every block of the list, or the ones chosen (chips with the game's art). */
    private static void blocks(LinearLayout into, final Host h, final AutoSettings.Mining work, final Runnable changed) {
        final Context c = h.ctx();
        into.addView(CloudRowSheet.label(c, c.getString(R.string.projects_auto_blocks_label)));
        final List<String> lastChosen = new ArrayList<>(work.blocks != null && !work.blocks.isEmpty() ? work.blocks : FIRST_CHOSEN);
        final TextView allHelp = CloudUi.note(c, c.getString(R.string.projects_auto_blocks_all_help));
        final WrapLayout chips = new WrapLayout(c);
        chips.setGap(CloudUi.dp(c, 6));
        chips.setPadding(CloudUi.dp(c, 8), CloudUi.dp(c, 6), CloudUi.dp(c, 8), CloudUi.dp(c, 4));
        chips.setContentDescription(c.getString(R.string.projects_auto_blocks_pick));
        final List<TextView> labels = new ArrayList<>();
        final List<LinearLayout> chipViews = new ArrayList<>();
        for (final String id : AutoSettings.MINING_BLOCKS) {
            final LinearLayout chip = chip(c);
            View art = CloudItemArt.tile(c, id, 24);
            art.setBackground(null);
            chip.addView(art, new LinearLayout.LayoutParams(CloudUi.dp(c, 24), CloudUi.dp(c, 24)));
            TextView label = CloudUi.text(c, h.itemName(id), 14, R.color.projects_ash_300, true);
            label.setDuplicateParentStateEnabled(true);
            label.setTextColor(chipText(c));
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            llp.setMarginStart(CloudUi.dp(c, 8));
            chip.addView(label, llp);
            labels.add(label);
            chipViews.add(chip);
            chip.setOnClickListener(v -> {
                List<String> now = work.blocks == null ? new ArrayList<String>() : new ArrayList<>(work.blocks);
                if (now.contains(id)) now.remove(id);
                else now.add(id);
                work.blocks = AutoSettings.ordered(now);
                if (!work.blocks.isEmpty()) {
                    lastChosen.clear();
                    lastChosen.addAll(work.blocks);
                }
                v.setActivated(work.blocks.contains(id));
                Haptics.tick(v);
                changed.run();
            });
            chips.addView(chip);
        }
        final TextView error = CloudRowSheet.error(c);
        String blocksIssue = h.issue("mining.blocks");
        if (blocksIssue != null) CloudRowSheet.show(error, blocksIssue);
        final Runnable show = () -> {
            boolean all = work.blocks == null;
            allHelp.setVisibility(all ? View.VISIBLE : View.GONE);
            chips.setVisibility(all ? View.GONE : View.VISIBLE);
            if (all) error.setVisibility(View.GONE);
            for (int i = 0; i < chipViews.size(); i++) chipViews.get(i).setActivated(!all && work.blocks.contains(AutoSettings.MINING_BLOCKS.get(i)));
        };
        CharSequence[] words = {c.getString(R.string.projects_auto_blocks_all), c.getString(R.string.projects_auto_blocks_chosen)};
        into.addView(CloudUi.segmented(c, words, work.blocks == null ? 0 : 1, i -> {
            if (i == 0) {
                if (work.blocks != null && !work.blocks.isEmpty()) {
                    lastChosen.clear();
                    lastChosen.addAll(work.blocks);
                }
                work.blocks = null;
            } else if (work.blocks == null) {
                work.blocks = new ArrayList<>(lastChosen);
                h.needNames(() -> relabel(h, labels));
            }
            show.run();
            changed.run();
        }));
        into.addView(allHelp);
        into.addView(chips, CloudUi.matchWrap());
        into.addView(error);
        show.run();
        if (work.blocks != null) h.needNames(() -> relabel(h, labels));
    }

    private static void relabel(Host h, List<TextView> labels) {
        for (int i = 0; i < labels.size(); i++) labels.get(i).setText(h.itemName(AutoSettings.MINING_BLOCKS.get(i)));
    }

    /** "Arada Hareket Etsin": the small moves as chips that may all be on, and how often once one is. */
    private static void idle(LinearLayout into, final Host h, final AutoSettings.Idle idle, final Runnable changed) {
        final Context c = h.ctx();
        into.addView(CloudRowSheet.label(c, c.getString(R.string.projects_auto_idle_label)));
        final LinearLayout every = column(c);
        WrapLayout moves = new WrapLayout(c);
        moves.setGap(CloudUi.dp(c, 6));
        moves.setPadding(CloudUi.dp(c, 8), CloudUi.dp(c, 2), CloudUi.dp(c, 8), CloudUi.dp(c, 2));
        int[] words = {R.string.projects_auto_idle_look, R.string.projects_auto_idle_sneak, R.string.projects_auto_idle_jump,
                R.string.projects_auto_idle_step};
        for (int i = 0; i < words.length; i++) {
            final int which = i;
            final LinearLayout chip = chip(c);
            TextView label = CloudUi.text(c, c.getString(words[i]), 14, R.color.projects_ash_300, true);
            label.setDuplicateParentStateEnabled(true);
            label.setTextColor(chipText(c));
            chip.addView(label);
            chip.setActivated(which == 0 ? idle.look : which == 1 ? idle.sneak : which == 2 ? idle.jump : idle.step);
            chip.setOnClickListener(v -> {
                boolean on = !v.isActivated();
                if (which == 0) idle.look = on;
                else if (which == 1) idle.sneak = on;
                else if (which == 2) idle.jump = on;
                else idle.step = on;
                v.setActivated(on);
                Haptics.tick(v);
                every.setVisibility(idle.any() ? View.VISIBLE : View.GONE);
                changed.run();
            });
            moves.addView(chip);
        }
        into.addView(moves, CloudUi.matchWrap());
        every.addView(CloudRowSheet.label(c, c.getString(R.string.projects_auto_idle_every)));
        LinearLayout pair = new LinearLayout(c);
        pair.setOrientation(LinearLayout.HORIZONTAL);
        CloudUi.Field least = CloudUi.field(c, c.getString(R.string.projects_cloud_settings_at_least), String.valueOf(idle.min), null,
                InputType.TYPE_CLASS_NUMBER);
        least.input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});
        watch(least.input, s -> {
            idle.min = CloudRowSheet.number(s, -1);
            changed.run();
        });
        CloudUi.Field most = CloudUi.field(c, c.getString(R.string.projects_cloud_settings_at_most), String.valueOf(idle.max), null,
                InputType.TYPE_CLASS_NUMBER);
        most.input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});
        watch(most.input, s -> {
            idle.max = CloudRowSheet.number(s, -1);
            changed.run();
        });
        least.unit(c.getString(R.string.projects_cloud_settings_seconds));
        most.unit(c.getString(R.string.projects_cloud_settings_seconds));
        pair.addView(least.view, CloudUi.weight(1f));
        pair.addView(most.view, CloudUi.weight(1f));
        every.addView(pair, CloudUi.matchWrap());
        String everyIssue = h.issue("mining.every");
        if (everyIssue != null) {
            TextView error = CloudRowSheet.error(c);
            CloudRowSheet.show(error, everyIssue);
            every.addView(error);
        } else {
            every.addView(CloudUi.note(c, c.getString(R.string.projects_auto_idle_help)));
        }
        every.setVisibility(idle.any() || everyIssue != null ? View.VISIBLE : View.GONE);
        into.addView(every, CloudUi.matchWrap());
    }

    /** The rod's or the pickaxe's care, folded under "Gelişmiş" (the defaults keep any tool from breaking). */
    private static void care(LinearLayout into, final Host h, String mode, final AutoSettings.Tool tool, final Runnable changed) {
        final Context c = h.ctx();
        boolean fish = AutoSettings.FISHING.equals(mode);
        String keepIssue = h.issue(mode + ".keep");
        LinearLayout box = advanced(into, c, c.getString(fish ? R.string.projects_auto_rod_summary : R.string.projects_auto_tool_summary),
                keepIssue != null);
        CloudUi.Field keep = CloudUi.field(c, c.getString(R.string.projects_auto_keep_label), String.valueOf(tool.keep), null, InputType.TYPE_CLASS_NUMBER);
        keep.input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(3)});
        keep.setError(keepIssue);
        keep.setHelp(keepIssue == null ? c.getString(fish ? R.string.projects_auto_rod_keep_help : R.string.projects_auto_tool_keep_help) : null);
        watch(keep.input, s -> {
            tool.keep = CloudRowSheet.number(s, -1);
            changed.run();
        });
        box.addView(keep.view);
        box.addView(CloudUi.gap(c, 8));
        LinearLayout group = CloudUi.group(c);
        group.addView(CloudUi.toggle(c, c.getString(fish ? R.string.projects_auto_rod_switch : R.string.projects_auto_tool_switch), tool.change, on -> {
            tool.change = on;
            changed.run();
        }));
        box.addView(group);
        box.addView(CloudUi.note(c, c.getString(fish ? R.string.projects_auto_rod_switch_line : R.string.projects_auto_tool_switch_line)));
    }

    /** A folded "Gelişmiş" (the AFK+ setup's): a quiet row naming what it holds; the box opens in place, in its card. */
    private static LinearLayout advanced(LinearLayout into, final Context c, final String holds, boolean open) {
        final LinearLayout box = column(c);
        box.setPadding(CloudUi.dp(c, 8), 0, CloudUi.dp(c, 8), CloudUi.dp(c, 12));
        box.setVisibility(open ? View.VISIBLE : View.GONE);
        final CloudUi.Row row = CloudUi.row(c, R.drawable.projects_ic_sliders, c.getString(open ? R.string.projects_setup_hide
                : R.string.projects_setup_advanced), null);
        row.setLine(open ? null : holds, R.color.projects_ash_400);
        final ImageView caret = new ImageView(c);
        caret.setImageResource(R.drawable.projects_ic_caret_down_bold);
        caret.setImageTintList(ColorStateList.valueOf(CloudUi.color(c, R.color.projects_ash_400)));
        caret.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        caret.setRotation(open ? 180f : 0f);
        row.view.addView(caret, new LinearLayout.LayoutParams(CloudUi.dp(c, 18), CloudUi.dp(c, 18)));
        row.view.setBackgroundResource(R.drawable.projects_row_ripple);
        row.view.setClickable(true);
        row.view.setFocusable(true);
        row.view.setOnClickListener(v -> {
            boolean show = box.getVisibility() != View.VISIBLE;
            box.setVisibility(show ? View.VISIBLE : View.GONE);
            row.title.setText(show ? R.string.projects_setup_hide : R.string.projects_setup_advanced);
            row.setLine(show ? null : holds, R.color.projects_ash_400);
            // The caret turns on a transform; with motion off it simply is the other way round.
            if (gg.projects.mobile.mods.Motion.enabled(c)) caret.animate().rotation(show ? 180f : 0f).setDuration(180).start();
            else caret.setRotation(show ? 180f : 0f);
            Haptics.tick(v);
        });
        Press.attach(row.view, Press.Style.ROW);
        LinearLayout group = CloudUi.group(c);
        group.addView(row.view);
        group.addView(box, CloudUi.matchWrap());
        LinearLayout.LayoutParams lp = CloudUi.matchWrap();
        lp.topMargin = CloudUi.dp(c, 16);
        into.addView(group, lp);
        return box;
    }

    /** A quiet chip of a few that may all be on: chamfered, pressed in while on (the site's toggle chip), 44 dp tall. */
    private static LinearLayout chip(Context c) {
        LinearLayout chip = new LinearLayout(c);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setMinimumHeight(CloudUi.dp(c, 44));
        chip.setBackground(ChamferDrawable.toggle(c));
        chip.setPaddingRelative(CloudUi.dp(c, 12), CloudUi.dp(c, 6), CloudUi.dp(c, 14), CloudUi.dp(c, 6));
        chip.setClickable(true);
        chip.setFocusable(true);
        chip.setAccessibilityDelegate(TOGGLE);
        Press.attach(chip, Press.Style.BUTTON);
        return chip;
    }

    private static ColorStateList chipText(Context c) {
        return new ColorStateList(new int[][]{{android.R.attr.state_activated}, {}},
                new int[]{CloudUi.color(c, R.color.projects_bone_50), CloudUi.color(c, R.color.projects_ash_300)});
    }

    /** A chip reads as a switch, on while it is pressed in. */
    private static final View.AccessibilityDelegate TOGGLE = new View.AccessibilityDelegate() {
        @Override
        public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            info.setClassName(android.widget.ToggleButton.class.getName());
            info.setCheckable(true);
            info.setChecked(host.isActivated());
        }
    };

    private interface Typed {
        void changed(String text);
    }

    private static void watch(EditText input, final Typed typed) {
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                typed.changed(s.toString());
            }
        });
    }
}
