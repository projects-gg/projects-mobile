package gg.projects.mobile.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewParent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceScreen;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.R;

/**
 * MOB-POLISH: the settings' rows in the app's panels (as the cloud account's Konsol / Envanter / Ayarlar): consecutive
 * rows of one kind share one 10 dp container with hairlines from the title's start, 12 dp in from the screen's sides.
 * Kinds: rows that open a screen, settings, the tools ({@code projects_tool_*}); the header, category titles and rows
 * that only inform stay outside (both second looks: the only uncontained list in the app). Drawn behind the rows, so
 * their ripples and press feedback are untouched.
 */
public final class PreferencePanels extends RecyclerView.ItemDecoration {
    private final PreferenceFragmentCompat mFragment;
    private final Drawable mPanel;
    private final Paint mLine = new Paint();
    private final int mInset;
    private final int mGap;
    private final int mRadius;
    private final int mLineStartFallback;

    public PreferencePanels(PreferenceFragmentCompat fragment) {
        mFragment = fragment;
        Context c = fragment.requireContext();
        float dp = c.getResources().getDisplayMetrics().density;
        mPanel = ContextCompat.getDrawable(c, R.drawable.projects_menu_group);
        mLine.setColor(ContextCompat.getColor(c, R.color.projects_obsidian_700));
        mLine.setStrokeWidth(Math.max(1, Math.round(dp)));
        mInset = Math.round(12 * dp);
        mGap = Math.round(12 * dp);
        mRadius = Math.round(10 * dp);
        mLineStartFallback = Math.round(56 * dp);
    }

    /** 0: outside any panel; else the panel kind the row belongs to. */
    static int kind(@Nullable Preference p) {
        if (p == null || p instanceof PreferenceCategory || p instanceof net.kdt.pojavlaunch.prefs.BackButtonPreference
                || !p.isSelectable()) return 0;
        String key = p.getKey();
        if (key != null && key.startsWith("projects_tool_")) return 3;
        return p.getFragment() != null ? 1 : 2;
    }

    /** Each adapter position's kind (the adapter flattens the screen and its categories). */
    private int[] kinds(RecyclerView parent) {
        RecyclerView.Adapter<?> adapter = parent.getAdapter();
        int count = adapter == null ? 0 : adapter.getItemCount();
        int[] kinds = new int[count];
        if (adapter instanceof PreferenceGroup.PreferencePositionCallback) {
            fill(mFragment.getPreferenceScreen(), (PreferenceGroup.PreferencePositionCallback) adapter, kinds);
        }
        return kinds;
    }

    private static void fill(@Nullable PreferenceGroup group, PreferenceGroup.PreferencePositionCallback positions, int[] kinds) {
        if (group == null) return;
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference p = group.getPreference(i);
            if (!p.isVisible()) continue;
            int at = positions.getPreferenceAdapterPosition(p);
            if (at >= 0 && at < kinds.length) kinds[at] = kind(p);
            if (p instanceof PreferenceGroup && !(p instanceof PreferenceScreen)) fill((PreferenceGroup) p, positions, kinds);
        }
    }

    private static int at(int[] kinds, int position) {
        return position >= 0 && position < kinds.length ? kinds[position] : 0;
    }

    @Override
    public void getItemOffsets(@NonNull Rect out, @NonNull View view, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        int position = parent.getChildAdapterPosition(view);
        if (position == RecyclerView.NO_POSITION) return;
        int[] kinds = kinds(parent);
        int kind = at(kinds, position);
        if (kind == 0) return;
        out.left = mInset;
        out.right = mInset;
        if (at(kinds, position - 1) != kind) out.top = mGap / 2;
        if (at(kinds, position + 1) != kind) out.bottom = mGap / 2;
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        if (mPanel == null) return;
        int[] kinds = kinds(parent);
        int count = parent.getChildCount();
        // The panels first, one per run of visible rows of one kind; a run cut by the screen's edge reaches past it
        // (no rounded corner where the panel goes on).
        int runKind = 0;
        int runTop = 0;
        int runBottom = 0;
        int runLeft = 0;
        int runRight = 0;
        int lastPosition = -2;
        for (int i = 0; i < count; i++) {
            View row = parent.getChildAt(i);
            int position = parent.getChildAdapterPosition(row);
            int kind = position == RecyclerView.NO_POSITION ? 0 : at(kinds, position);
            int top = row.getTop() + Math.round(row.getTranslationY());
            int bottom = row.getBottom() + Math.round(row.getTranslationY());
            boolean continues = kind != 0 && kind == runKind && position == lastPosition + 1;
            if (!continues) {
                if (runKind != 0) drawPanel(c, runLeft, runTop, runRight, runBottom);
                runKind = kind;
                runTop = at(kinds, position - 1) == kind ? top - 2 * mRadius : top;
            }
            if (kind != 0) {
                runBottom = at(kinds, position + 1) == kind && i == count - 1 ? bottom + 2 * mRadius : bottom;
                runLeft = row.getLeft();
                runRight = row.getRight();
            }
            lastPosition = position;
        }
        if (runKind != 0) drawPanel(c, runLeft, runTop, runRight, runBottom);
        // Then the hairlines between the rows of a panel, from the title's start.
        lastPosition = -2;
        int lastKind = 0;
        for (int i = 0; i < count; i++) {
            View row = parent.getChildAt(i);
            int position = parent.getChildAdapterPosition(row);
            int kind = position == RecyclerView.NO_POSITION ? 0 : at(kinds, position);
            if (kind != 0 && kind == lastKind && position == lastPosition + 1) {
                float y = row.getTop() + row.getTranslationY();
                c.drawLine(row.getLeft() + titleStart(row), y, row.getRight(), y, mLine);
            }
            lastKind = kind;
            lastPosition = position;
        }
    }

    private void drawPanel(Canvas c, int left, int top, int right, int bottom) {
        mPanel.setBounds(left, top, right, bottom);
        mPanel.draw(c);
    }

    /** Where the row's title starts, from the row's left (the icon column before it). */
    private int titleStart(View row) {
        View title = row.findViewById(android.R.id.title);
        if (title == null) return mLineStartFallback;
        int x = 0;
        View at = title;
        while (at != null && at != row) {
            x += at.getLeft();
            ViewParent up = at.getParent();
            at = up instanceof View ? (View) up : null;
        }
        return at == row ? x : mLineStartFallback;
    }
}
