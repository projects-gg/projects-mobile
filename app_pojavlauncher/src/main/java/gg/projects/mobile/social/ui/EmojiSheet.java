package gg.projects.mobile.social.ui;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.graphics.PaintCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import gg.projects.mobile.language.LanguageManager;
import gg.projects.mobile.mods.Motion;
import gg.projects.mobile.social.emoji.EmojiData;
import gg.projects.mobile.social.emoji.EmojiRecents;
import gg.projects.mobile.social.emoji.EmojiSearch;
import gg.projects.mobile.social.emoji.EmojiTone;
import gg.projects.mobile.social.emoji.QuickReactions;
import gg.projects.mobile.social.emoji.StickerSet;
import gg.projects.mobile.ui.Haptics;
import gg.projects.mobile.ui.Press;

/**
 * MOB1 (CHAT2 §3.1, §8.5, Android): the emoji and sticker sheet. It rises to half the screen (the
 * keyboard goes down): "Emoji | Çıkartmalar"; the emoji side has the search ("Emoji ara", Turkish and
 * accent-blind with an English fallback), the skin tone (one choice for every emoji, this phone only),
 * "Son kullanılanlar" as they were when it opened (cells never move under the finger), the nine groups
 * and their rail. A tap puts an emoji in and the sheet stays (several in a row are common); a sticker
 * goes at once and the sheet closes; holding a sticker shows it big with its name. From a message's
 * "+" it is the reaction picker: emoji only, and the first tap reacts and closes it. Every cell answers
 * a press with a give and a tick. Emoji the phone cannot draw are left out (unless the downloadable
 * emoji font draws them).
 */
final class EmojiSheet {
    private EmojiSheet() {}

    /** What the composer gets. */
    interface Pick {
        void emoji(String emoji);

        void sticker(String ref, View cell);
    }

    interface Reaction {
        void picked(String emoji);
    }

    private static final int H_HEADER = 0;
    private static final int H_EMOJI = 1;
    private static final int H_STICKER = 2;
    private static final int H_EMPTY = 3;
    private static final int COLUMNS = 8;
    private static final int STICKER_COLUMNS = 4;

    /** The rail's group marks (an emoji of each group, drawn quiet). */
    private static final int[][] GROUP_MARKS = {
            {0x1F600}, {0x1F9D1}, {0x1F43B}, {0x1F354}, {0x2708, 0xFE0F}, {0x26BD}, {0x1F4A1}, {0x1F523}, {0x1F3C1}};
    private static final int[] GROUP_NAMES = {R.string.projects_picker_group_smileys, R.string.projects_picker_group_people,
            R.string.projects_picker_group_nature, R.string.projects_picker_group_food, R.string.projects_picker_group_travel,
            R.string.projects_picker_group_activities, R.string.projects_picker_group_objects, R.string.projects_picker_group_symbols,
            R.string.projects_picker_group_flags};

    /** Emoji the phone can draw (checked once per process; warmed off the main thread when the data is read). */
    private static final Map<String, Boolean> DRAWABLE = new java.util.concurrent.ConcurrentHashMap<>();

    static void pick(FragmentActivity activity, boolean stickers, Pick pick) {
        open(activity, stickers, pick, null);
    }

    static void pickReaction(FragmentActivity activity, Reaction reaction) {
        open(activity, false, null, reaction);
    }

    private static void open(final FragmentActivity activity, final boolean stickers, @Nullable final Pick pick,
                             @Nullable final Reaction reaction) {
        EmojiStore.whenLoaded(activity, () -> {
            if (activity.isFinishing() || activity.getSupportFragmentManager().isStateSaved()) return;
            EmojiData data = EmojiStore.data();
            if (data == null) return;
            new Sheet(activity, data, stickers, pick, reaction).show();
        });
    }

    /** One open sheet. */
    private static final class Sheet {
        final FragmentActivity activity;
        final EmojiData data;
        final boolean stickers;
        @Nullable
        final Pick pick;
        @Nullable
        final Reaction reaction;
        final List<String> recentSnapshot;
        final List<String> recentStickers;
        final float dp;
        final BottomPanel panel;
        final RecyclerView grid;
        final GridLayoutManager layout;
        final GridAdapter adapter = new GridAdapter();
        final EditText search;
        final LinearLayout rail;
        final LinearLayout tones;
        final TextView toneButton;
        final View emojiTools;
        boolean stickerTab;
        String query = "";
        /** Where each group's header is in the grid (to jump there from the rail). */
        final int[] groupAt = new int[9];

        Sheet(FragmentActivity activity, EmojiData data, boolean stickers, @Nullable Pick pick, @Nullable Reaction reaction) {
            this.activity = activity;
            this.data = data;
            this.stickers = stickers;
            this.pick = pick;
            this.reaction = reaction;
            this.recentSnapshot = new ArrayList<>(EmojiStore.recentEmoji(activity));
            this.recentStickers = new ArrayList<>(EmojiStore.recentStickers(activity));
            Context context = activity;
            dp = context.getResources().getDisplayMetrics().density;
            LinearLayout content = new LinearLayout(context);
            content.setOrientation(LinearLayout.VERTICAL);
            int height = Math.round(context.getResources().getDisplayMetrics().heightPixels * 0.5f);
            content.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));
            // The tabs: "Emoji | Çıkartmalar" (the reaction picker has emoji only).
            if (stickers && reaction == null) {
                LinearLayout tabs = new LinearLayout(context);
                tabs.setOrientation(LinearLayout.HORIZONTAL);
                tabs.setPadding(Math.round(12 * dp), 0, Math.round(12 * dp), Math.round(8 * dp));
                final TextView emojiTab = tab(context, R.string.projects_picker_tab_emoji);
                final TextView stickerTabView = tab(context, R.string.projects_picker_tab_stickers);
                tabs.addView(emojiTab, new LinearLayout.LayoutParams(0, Math.round(36 * dp), 1f));
                LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(0, Math.round(36 * dp), 1f);
                second.setMarginStart(Math.round(8 * dp));
                tabs.addView(stickerTabView, second);
                emojiTab.setOnClickListener(v -> {
                    Haptics.press(v);
                    showTab(false, emojiTab, stickerTabView);
                });
                stickerTabView.setOnClickListener(v -> {
                    Haptics.press(v);
                    showTab(true, emojiTab, stickerTabView);
                });
                content.addView(tabs);
                styleTab(emojiTab, true);
                styleTab(stickerTabView, false);
            }
            // The search and the skin tone.
            LinearLayout tools = new LinearLayout(context);
            tools.setOrientation(LinearLayout.VERTICAL);
            emojiTools = tools;
            LinearLayout searchRow = new LinearLayout(context);
            searchRow.setOrientation(LinearLayout.HORIZONTAL);
            searchRow.setGravity(Gravity.CENTER_VERTICAL);
            searchRow.setPadding(Math.round(12 * dp), 0, Math.round(8 * dp), Math.round(6 * dp));
            search = new EditText(context);
            search.setHint(R.string.projects_picker_search);
            search.setSingleLine(true);
            search.setTextSize(15);
            search.setTextColor(ContextCompat.getColor(context, R.color.projects_bone_100));
            search.setHintTextColor(ContextCompat.getColor(context, R.color.projects_ash_400));
            search.setBackgroundResource(R.drawable.projects_chat_input);
            search.setPadding(Math.round(14 * dp), 0, Math.round(14 * dp), 0);
            search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
            searchRow.addView(search, new LinearLayout.LayoutParams(0, Math.round(40 * dp), 1f));
            toneButton = new AppCompatTextView(context);
            toneButton.setTextSize(20);
            toneButton.setGravity(Gravity.CENTER);
            toneButton.setBackgroundResource(R.drawable.projects_icon_button_ripple);
            toneButton.setText(EmojiTone.hand(EmojiStore.tone(context)));
            toneButton.setContentDescription(context.getString(R.string.projects_picker_tone));
            Press.attach(toneButton, Press.Style.ICON);
            LinearLayout.LayoutParams toneLp = new LinearLayout.LayoutParams(Math.round(44 * dp), Math.round(44 * dp));
            toneLp.setMarginStart(Math.round(4 * dp));
            searchRow.addView(toneButton, toneLp);
            tools.addView(searchRow);
            tones = new LinearLayout(context);
            tones.setOrientation(LinearLayout.HORIZONTAL);
            tones.setGravity(Gravity.CENTER);
            tones.setVisibility(View.GONE);
            int[] toneNames = {R.string.projects_picker_tone_default, R.string.projects_picker_tone_light,
                    R.string.projects_picker_tone_medium_light, R.string.projects_picker_tone_medium,
                    R.string.projects_picker_tone_medium_dark, R.string.projects_picker_tone_dark};
            for (int t = 0; t < 6; t++) {
                final int tone = t;
                TextView option = new AppCompatTextView(context);
                option.setText(EmojiTone.hand(t));
                option.setTextSize(22);
                option.setGravity(Gravity.CENTER);
                option.setContentDescription(context.getString(toneNames[t]));
                option.setBackgroundResource(R.drawable.projects_icon_button_ripple);
                Press.attach(option, Press.Style.ICON);
                option.setOnClickListener(v -> {
                    Haptics.press(v);
                    EmojiStore.setTone(v.getContext(), tone);
                    toneButton.setText(EmojiTone.hand(tone));
                    tones.setVisibility(View.GONE);
                    refill();
                });
                tones.addView(option, new LinearLayout.LayoutParams(Math.round(48 * dp), Math.round(48 * dp)));
            }
            tools.addView(tones);
            toneButton.setOnClickListener(v -> {
                Haptics.press(v);
                boolean show = tones.getVisibility() != View.VISIBLE;
                tones.setVisibility(show ? View.VISIBLE : View.GONE);
                if (show && Motion.enabled(v.getContext())) {
                    tones.setAlpha(0f);
                    tones.setTranslationY(-6 * dp);
                    tones.animate().alpha(1f).translationY(0f).setDuration(180).setInterpolator(Motion.EASE_OUT).start();
                }
            });
            content.addView(tools);
            // The grid.
            grid = new RecyclerView(context);
            layout = new GridLayoutManager(context, COLUMNS * STICKER_COLUMNS);
            layout.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
                @Override
                public int getSpanSize(int position) {
                    int type = adapter.getItemViewType(position);
                    if (type == H_EMOJI) return STICKER_COLUMNS;
                    if (type == H_STICKER) return COLUMNS;
                    return COLUMNS * STICKER_COLUMNS;
                }
            });
            grid.setLayoutManager(layout);
            grid.setAdapter(adapter);
            grid.setItemAnimator(null);
            grid.setClipToPadding(false);
            grid.setPadding(Math.round(6 * dp), 0, Math.round(6 * dp), Math.round(8 * dp));
            content.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            // The groups' rail.
            rail = new LinearLayout(context);
            rail.setOrientation(LinearLayout.HORIZONTAL);
            rail.setGravity(Gravity.CENTER);
            rail.setPadding(0, Math.round(2 * dp), 0, Math.round(6 * dp));
            for (int g = 0; g < 9; g++) {
                final int group = g;
                TextView mark = new AppCompatTextView(context);
                StringBuilder glyph = new StringBuilder();
                for (int cp : GROUP_MARKS[g]) glyph.appendCodePoint(cp);
                mark.setText(glyph);
                mark.setTextSize(18);
                mark.setAlpha(0.7f);
                mark.setGravity(Gravity.CENTER);
                mark.setContentDescription(context.getString(GROUP_NAMES[g]));
                mark.setBackgroundResource(R.drawable.projects_icon_button_ripple);
                Press.attach(mark, Press.Style.ICON);
                mark.setOnClickListener(v -> {
                    Haptics.press(v);
                    if (!query.isEmpty()) search.setText("");
                    layout.scrollToPositionWithOffset(groupAt[group], 0);
                });
                rail.addView(mark, new LinearLayout.LayoutParams(0, Math.round(40 * dp), 1f));
            }
            content.addView(rail);
            search.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(Editable s) {
                    query = s.toString().trim();
                    refill();
                }
            });
            panel = BottomPanel.of(context, content, 0.62f);
            refill();
        }

        void show() {
            panel.show();
        }

        private TextView tab(Context context, int label) {
            TextView tab = new TextView(context);
            tab.setText(label);
            tab.setTextSize(14);
            tab.setGravity(Gravity.CENTER);
            try {
                tab.setTypeface(ResourcesCompat.getFont(context, R.font.figtree_semibold));
            } catch (RuntimeException ignored) {
                // The theme's font.
            }
            Press.attach(tab, Press.Style.BUTTON);
            return tab;
        }

        private void styleTab(TextView tab, boolean on) {
            Context context = tab.getContext();
            GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(18 * dp);
            pill.setColor(ContextCompat.getColor(context, on ? R.color.projects_obsidian_800 : R.color.projects_obsidian_900));
            pill.setStroke(Math.max(1, Math.round(dp)), ContextCompat.getColor(context, on ? R.color.projects_gold_700 : R.color.projects_obsidian_700));
            tab.setBackground(pill);
            tab.setTextColor(ContextCompat.getColor(context, on ? R.color.projects_gold_300 : R.color.projects_ash_300));
            tab.setSelected(on);
        }

        private void showTab(boolean stickersOn, TextView emojiTab, TextView stickerTabView) {
            if (stickerTab == stickersOn) return;
            stickerTab = stickersOn;
            styleTab(emojiTab, !stickersOn);
            styleTab(stickerTabView, stickersOn);
            emojiTools.setVisibility(stickersOn ? View.GONE : View.VISIBLE);
            rail.setVisibility(stickersOn ? View.GONE : View.VISIBLE);
            refill();
            if (Motion.enabled(grid.getContext())) {
                grid.setAlpha(0f);
                grid.animate().alpha(1f).setDuration(160).setInterpolator(Motion.EASE_OUT).start();
            }
            layout.scrollToPosition(0);
        }

        /** The grid's cells for the tab, the search and the tone now. */
        private void refill() {
            Context context = activity;
            List<Object[]> cells = new ArrayList<>();
            if (stickerTab) {
                StickerSet set = EmojiStore.stickers(context);
                if (set != null) {
                    String lang = LanguageManager.isTurkish(context) ? "tr" : "en";
                    List<StickerSet.Sticker> recent = set.recent(recentStickers);
                    if (!recent.isEmpty()) {
                        cells.add(new Object[]{H_HEADER, context.getString(R.string.projects_picker_recent)});
                        for (StickerSet.Sticker s : recent) cells.add(new Object[]{H_STICKER, s});
                    }
                    for (StickerSet.Theme theme : set.themes()) {
                        cells.add(new Object[]{H_HEADER, theme.name(lang)});
                        for (StickerSet.Sticker s : set.inTheme(theme.id)) cells.add(new Object[]{H_STICKER, s});
                    }
                }
                adapter.set(cells);
                return;
            }
            int tone = EmojiStore.tone(context);
            if (!query.isEmpty()) {
                List<EmojiData.Entry> found = EmojiSearch.search(data, query);
                for (EmojiData.Entry e : found) {
                    String emoji = shown(e, tone);
                    if (emoji != null) cells.add(new Object[]{H_EMOJI, emoji, e});
                }
                if (cells.isEmpty()) cells.add(new Object[]{H_EMPTY, context.getString(R.string.projects_picker_empty)});
                adapter.set(cells);
                return;
            }
            List<EmojiData.Entry> recent = EmojiRecents.recentEntries(data, recentSnapshot);
            if (!recent.isEmpty()) {
                cells.add(new Object[]{H_HEADER, context.getString(R.string.projects_picker_recent)});
                for (EmojiData.Entry e : recent) {
                    String emoji = shown(e, -1);
                    if (emoji != null) cells.add(new Object[]{H_EMOJI, recentForm(e), e});
                }
            }
            for (int g = 0; g < 9; g++) {
                groupAt[g] = cells.size();
                cells.add(new Object[]{H_HEADER, context.getString(GROUP_NAMES[g])});
                for (EmojiData.Entry e : data.group(g)) {
                    String emoji = shown(e, tone);
                    if (emoji != null) cells.add(new Object[]{H_EMOJI, emoji, e});
                }
            }
            adapter.set(cells);
        }

        /** A recent emoji as it was used (with its tone). */
        private String recentForm(EmojiData.Entry e) {
            for (String used : recentSnapshot) if (e.emoji.equals(data.canonical(used)) || used.equals(e.emoji)) return used;
            return e.emoji;
        }

        /** The emoji as the sheet draws it in {@code tone} (-1: as it is), or null when it is left out here. */
        @Nullable
        private String shown(EmojiData.Entry e, int tone) {
            String emoji = tone > 0 && e.hasTones() ? EmojiTone.withTone(e, tone, data) : e.emoji;
            if (reaction != null && QuickReactions.isRefused(emoji)) return null;
            return drawable(emoji) ? emoji : null;
        }

        private void picked(String emoji, View cell) {
            // A second tap while the sheet leaves would give a second reaction (CR1-F).
            if (reaction != null && !panel.showing()) return;
            Haptics.press(cell);
            if (reaction != null) {
                panel.dismiss();
                reaction.picked(emoji);
                return;
            }
            if (pick != null) pick.emoji(emoji);
            EmojiStore.usedEmoji(cell.getContext(), emoji);
            // A small "it went in" beat on the cell.
            if (Motion.enabled(cell.getContext())) {
                cell.animate().cancel();
                cell.setScaleX(0.86f);
                cell.setScaleY(0.86f);
                cell.animate().scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(Motion.EASE_OUT).start();
            }
        }

        private void pickedSticker(StickerSet.Sticker sticker, View cell) {
            // One sticker per open sheet: a double tap during its exit sends nothing more (CR1-F).
            if (!panel.showing()) return;
            panel.dismiss();
            if (pick != null) pick.sticker(sticker.ref, cell);
        }

        private void preview(StickerSet.Sticker sticker) {
            Context context = activity;
            String lang = LanguageManager.isTurkish(context) ? "tr" : "en";
            LinearLayout box = new LinearLayout(context);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER_HORIZONTAL);
            box.setPadding(Math.round(24 * dp), Math.round(20 * dp), Math.round(24 * dp), Math.round(8 * dp));
            final ImageView image = new ImageView(context);
            int res = EmojiStore.stickerRes(context, sticker.ref);
            if (res != 0) image.setImageResource(res);
            box.addView(image, new LinearLayout.LayoutParams(Math.round(160 * dp), Math.round(160 * dp)));
            TextView name = new TextView(context);
            name.setText(sticker.name(lang));
            name.setTextSize(16);
            name.setGravity(Gravity.CENTER);
            name.setTextColor(ContextCompat.getColor(context, R.color.projects_bone_100));
            name.setPadding(0, Math.round(12 * dp), 0, 0);
            box.addView(name);
            AlertDialog dialog = new AlertDialog.Builder(context).setView(box).create();
            dialog.setOnShowListener(d -> image.post(() -> EmojiStore.playSticker(image, sticker.ref, true)));
            dialog.show();
        }

        private final class GridAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
            private List<Object[]> mCells = new ArrayList<>();

            void set(List<Object[]> cells) {
                mCells = cells;
                notifyDataSetChanged();
            }

            @Override
            public int getItemViewType(int position) {
                return (Integer) mCells.get(position)[0];
            }

            @Override
            public int getItemCount() {
                return mCells.size();
            }

            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                Context context = parent.getContext();
                View view;
                if (viewType == H_EMOJI) {
                    TextView cell = new AppCompatTextView(context);
                    cell.setTextSize(26);
                    cell.setGravity(Gravity.CENTER);
                    cell.setBackgroundResource(R.drawable.projects_icon_button_ripple);
                    cell.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(46 * dp)));
                    Press.attach(cell, Press.Style.ICON);
                    view = cell;
                } else if (viewType == H_STICKER) {
                    FrameLayout frame = new FrameLayout(context);
                    frame.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(84 * dp)));
                    frame.setBackgroundResource(R.drawable.projects_icon_button_ripple);
                    ImageView image = new ImageView(context);
                    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    frame.addView(image, new FrameLayout.LayoutParams(Math.round(72 * dp), Math.round(72 * dp), Gravity.CENTER));
                    Press.attach(frame, Press.Style.ICON);
                    view = frame;
                } else {
                    TextView header = new TextView(context);
                    header.setTextSize(viewType == H_EMPTY ? 14 : 12);
                    header.setTextColor(ContextCompat.getColor(context, R.color.projects_ash_400));
                    header.setPadding(Math.round(8 * dp), Math.round((viewType == H_EMPTY ? 24 : 10) * dp), Math.round(8 * dp), Math.round(4 * dp));
                    header.setGravity(viewType == H_EMPTY ? Gravity.CENTER : Gravity.START);
                    try {
                        header.setTypeface(ResourcesCompat.getFont(context, R.font.figtree_semibold));
                    } catch (RuntimeException ignored) {
                        // The theme's font.
                    }
                    header.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    view = header;
                }
                return new RecyclerView.ViewHolder(view) {};
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                Object[] cell = mCells.get(position);
                int type = (Integer) cell[0];
                if (type == H_EMOJI) {
                    final String emoji = (String) cell[1];
                    EmojiData.Entry entry = (EmojiData.Entry) cell[2];
                    TextView view = (TextView) holder.itemView;
                    view.setText(emoji);
                    view.setContentDescription(EmojiData.displayName(entry.name, data.lang()));
                    view.setOnClickListener(v -> picked(emoji, v));
                } else if (type == H_STICKER) {
                    final StickerSet.Sticker sticker = (StickerSet.Sticker) cell[1];
                    FrameLayout frame = (FrameLayout) holder.itemView;
                    ImageView image = (ImageView) frame.getChildAt(0);
                    int res = EmojiStore.stickerRes(frame.getContext(), sticker.ref);
                    if (res != 0) image.setImageResource(res);
                    String lang = LanguageManager.isTurkish(frame.getContext()) ? "tr" : "en";
                    frame.setContentDescription(frame.getContext().getString(R.string.projects_stickers_label, sticker.name(lang)));
                    frame.setOnClickListener(v -> pickedSticker(sticker, v));
                    frame.setOnLongClickListener(v -> {
                        Haptics.perform(v, gg.projects.mobile.ui.HapticMap.Kind.LONG_PRESS);
                        preview(sticker);
                        return true;
                    });
                } else {
                    ((TextView) holder.itemView).setText((String) cell[1]);
                }
            }
        }
    }

    /**
     * Whether the phone draws {@code emoji} (its fonts, or the downloadable emoji font when it is
     * loaded); checked once per emoji per process.
     */
    private static boolean drawable(String emoji) {
        if (emojiFontLoaded()) return true;
        Boolean known = DRAWABLE.get(emoji);
        if (known != null) return known;
        boolean has = hasGlyph(PAINT, emoji);
        DRAWABLE.put(emoji, has);
        return has;
    }

    private static boolean hasGlyph(Paint paint, String emoji) {
        try {
            return PaintCompat.hasGlyph(paint, emoji);
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** Checks every base emoji once, off the main thread (the sheet then opens without a pause). */
    static void warm(EmojiData data) {
        if (emojiFontLoaded()) return;
        Paint paint = new Paint();
        for (EmojiData.Entry e : data.entries()) {
            if (!DRAWABLE.containsKey(e.emoji)) DRAWABLE.put(e.emoji, hasGlyph(paint, e.emoji));
        }
    }

    private static final Paint PAINT = new Paint();
    @Nullable
    private static Boolean sEmojiFont;

    /** The downloadable emoji font (androidx.emoji2 through AppCompat) is loaded: every emoji draws. */
    private static boolean emojiFontLoaded() {
        if (sEmojiFont != null && sEmojiFont) return true;
        boolean loaded = false;
        try {
            Class<?> c = Class.forName("androidx.emoji2.text.EmojiCompat");
            Object configured = c.getMethod("isConfigured").invoke(null);
            if (Boolean.TRUE.equals(configured)) {
                Object compat = c.getMethod("get").invoke(null);
                Object state = c.getMethod("getLoadState").invoke(compat);
                loaded = Integer.valueOf(1).equals(state);
            }
        } catch (Throwable t) {
            loaded = false;
        }
        sEmojiFont = loaded;
        return loaded;
    }
}
