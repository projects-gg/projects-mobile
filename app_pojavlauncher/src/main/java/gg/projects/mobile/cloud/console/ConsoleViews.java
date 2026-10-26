package gg.projects.mobile.cloud.console;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import net.kdt.pojavlaunch.R;

import java.util.ArrayList;
import java.util.List;

import gg.projects.mobile.cloud.ui.CloudUi;
import gg.projects.mobile.ui.ChamferDrawable;
import gg.projects.mobile.ui.Haptics;
import gg.projects.mobile.ui.Press;

/**
 * The console's own pieces (CAFK-FEAT §3.2): the filter chips that scroll sideways on a narrow phone, and the strip
 * that shows what the server keeps on screen in the game (the action bar's text, the title, boss bars with a thin
 * progress line), updated in place.
 */
public final class ConsoleViews {
    private ConsoleViews() {}

    public interface Picked {
        void picked(int index);
    }

    /** A row of chips (the settings' tab look: faint gold when chosen); one chosen at a time. */
    public static final class Chips {
        public final HorizontalScrollView view;
        private final List<Button> mButtons = new ArrayList<>();
        private int mSelected;

        public Chips(Context c, CharSequence[] labels, int selected, final Picked picked) {
            view = new HorizontalScrollView(c);
            view.setHorizontalScrollBarEnabled(false);
            view.setFillViewport(false);
            view.setClipToPadding(false);
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(CloudUi.dp(c, 2), 0, CloudUi.dp(c, 2), 0);
            for (int i = 0; i < labels.length; i++) {
                final int index = i;
                Button b = new Button(c, null, android.R.attr.borderlessButtonStyle);
                b.setText(labels[i]);
                b.setAllCaps(false);
                b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                b.setTypeface(CloudUi.text(c, "", 14, R.color.projects_bone_100, true).getTypeface());
                b.setMinHeight(CloudUi.dp(c, 48));
                b.setMinimumHeight(CloudUi.dp(c, 48));
                b.setMinWidth(0);
                b.setMinimumWidth(0);
                b.setPadding(CloudUi.dp(c, 14), 0, CloudUi.dp(c, 14), 0);
                b.setBackground(ChamferDrawable.toggle(c));
                b.setMaxLines(1);
                Press.attach(b, Press.Style.BUTTON);
                b.setOnClickListener(v -> {
                    if (index == mSelected) return;
                    select(index);
                    Haptics.tick(v);
                    picked.picked(index);
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) lp.setMarginStart(CloudUi.dp(c, 6));
                row.addView(b, lp);
                mButtons.add(b);
            }
            view.addView(row);
            select(selected);
        }

        public void select(int index) {
            mSelected = index;
            for (int i = 0; i < mButtons.size(); i++) {
                Button b = mButtons.get(i);
                boolean on = i == index;
                b.setActivated(on);
                b.setTextColor(CloudUi.color(b.getContext(), on ? R.color.projects_gold_300 : R.color.projects_ash_300));
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    b.setStateDescription(on ? b.getContext().getString(R.string.projects_cloud_selected) : null);
                }
            }
        }

        public int selected() {
            return mSelected;
        }
    }

    /**
     * The strip above the console: the action bar's text, the title (and subtitle) while they show, and up to four boss
     * bars, each its text over a thin line of its progress in the bar's own colour, muted (the game's colours are the
     * server's content; our gold stays the interface's). Nothing shows while nothing is on screen in the game.
     */
    public static final class Strip extends LinearLayout {
        private final TextView mAction;
        private final TextView mTitle;
        private final LinearLayout mBosses;
        private Bars mBars = Bars.none();
        private final Runnable mExpire = this::refresh;
        /** The service's clock (the strip's times are the machine's). */
        private Now mNow = System::currentTimeMillis;

        public interface Now {
            long now();
        }

        public void setClock(Now now) {
            mNow = now;
        }

        public Strip(Context c) {
            super(c);
            setOrientation(VERTICAL);
            setBackgroundResource(R.drawable.projects_menu_group);
            setPadding(CloudUi.dp(c, 14), CloudUi.dp(c, 10), CloudUi.dp(c, 14), CloudUi.dp(c, 10));
            setContentDescription(c.getString(R.string.projects_feat_console_strip));
            mTitle = CloudUi.text(c, "", 15, R.color.projects_bone_100, true);
            mTitle.setGravity(Gravity.CENTER_HORIZONTAL);
            addView(mTitle, CloudUi.matchWrap());
            mAction = CloudUi.text(c, "", 14, R.color.projects_bone_100, false);
            mAction.setGravity(Gravity.CENTER_HORIZONTAL);
            addView(mAction, CloudUi.matchWrap());
            mBosses = new LinearLayout(c);
            mBosses.setOrientation(VERTICAL);
            addView(mBosses, CloudUi.matchWrap());
            setVisibility(GONE);
            setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_NONE);
        }

        public void set(Bars bars) {
            mBars = bars;
            refresh();
        }

        private void refresh() {
            removeCallbacks(mExpire);
            long now = mNow.now();
            Bars b = mBars;
            if (b.empty(now)) {
                setVisibility(GONE);
                return;
            }
            setVisibility(VISIBLE);
            String action = b.actionbarAt(now);
            mAction.setText(action == null ? "" : action);
            mAction.setVisibility(action == null ? GONE : VISIBLE);
            if (b.titleShows(now)) {
                String t = b.title == null ? "" : b.title;
                if (b.subtitle != null) t = t.isEmpty() ? b.subtitle : t + "\n" + b.subtitle;
                mTitle.setText(t);
                mTitle.setVisibility(VISIBLE);
            } else {
                mTitle.setVisibility(GONE);
            }
            mBosses.removeAllViews();
            Context c = getContext();
            for (Bars.Boss boss : b.boss) {
                LinearLayout one = new LinearLayout(c);
                one.setOrientation(VERTICAL);
                TextView text = CloudUi.text(c, boss.text, 13.5f, R.color.projects_ash_300, false);
                text.setGravity(Gravity.CENTER_HORIZONTAL);
                one.addView(text, CloudUi.matchWrap());
                BossLine line = new BossLine(c, boss.progress, bossColor(boss.color));
                LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, CloudUi.dp(c, 3));
                lineLp.topMargin = CloudUi.dp(c, 5);
                one.addView(line, lineLp);
                LinearLayout.LayoutParams lp = CloudUi.matchWrap();
                lp.topMargin = CloudUi.dp(c, mBosses.getChildCount() == 0 && mAction.getVisibility() == GONE && mTitle.getVisibility() == GONE ? 0 : 8);
                mBosses.addView(one, lp);
            }
            long next = b.nextChange(now);
            if (next > now) postDelayed(mExpire, next - now + 50);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            removeCallbacks(mExpire);
        }

        /** The game's boss bar colours, a little quieter (they never compete with the app's gold). */
        static int bossColor(String color) {
            switch (color) {
                case "pink": return 0xffd879b8;
                case "blue": return 0xff5aa7e0;
                case "red": return 0xffe0605a;
                case "green": return 0xff6cc773;
                case "yellow": return 0xffe3cf5a;
                case "purple": return 0xffa678e0;
                default: return 0xffd9d4dd;
            }
        }
    }

    /** A thin progress line: an obsidian track and the bar's colour from the start (no motion of its own). */
    static final class BossLine extends View {
        private final Paint mTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRect = new RectF();
        private final float mProgress;

        BossLine(Context c, float progress, int color) {
            super(c);
            mProgress = Math.max(0f, Math.min(1f, progress));
            mTrack.setColor(CloudUi.color(c, R.color.projects_obsidian_700));
            mFill.setColor(color);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float h = getHeight();
            float r = h / 2f;
            mRect.set(0, 0, getWidth(), h);
            canvas.drawRoundRect(mRect, r, r, mTrack);
            if (mProgress > 0) {
                mRect.set(0, 0, Math.max(h, getWidth() * mProgress), h);
                canvas.drawRoundRect(mRect, r, r, mFill);
            }
        }
    }
}
