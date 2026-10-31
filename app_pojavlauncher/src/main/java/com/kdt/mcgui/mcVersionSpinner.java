package com.kdt.mcgui;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static net.kdt.pojavlaunch.fragments.ProfileEditorFragment.DELETED_PROFILE;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.transition.Slide;
import android.transition.Transition;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;
import androidx.fragment.app.FragmentActivity;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.fragments.ProfileEditorFragment;
import net.kdt.pojavlaunch.fragments.ProfileTypeSelectFragment;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.profiles.ProfileAdapter;
import net.kdt.pojavlaunch.profiles.ProfileAdapterExtra;

import java.io.IOException;

import fr.spse.extended_view.ExtendedTextView;
import gg.projects.mobile.join.DefaultProfiles;
import gg.projects.mobile.join.ProfileRemoval;
import gg.projects.mobile.join.ProjectsProfile;
import gg.projects.mobile.ui.Haptics;
import gg.projects.mobile.ui.UndoBar;

/**
 * A class implementing custom spinner like behavior, notably:
 * dropdown popup view with a custom direction.
 */
public class mcVersionSpinner extends ExtendedTextView {
    private static final int VERSION_SPINNER_PROFILE_CREATE = 0;
    private static final String TAG = "ProjectsProfiles";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** MOB2: frames the home screen's undo bar is waited for (it is made with the home card). */
    private static final int UNDO_BAR_TRIES = 10;
    public mcVersionSpinner(@NonNull Context context) {
        super(context);
        init();
    }
    public mcVersionSpinner(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    public mcVersionSpinner(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    /* The class is in charge of displaying its own list with adapter content being known in advance */
    private ListView mListView = null;
    private PopupWindow mPopupWindow = null;
    private Object mPopupAnimation;
    private int mSelectedIndex;
    private int mUndoBarTries;
    private final Runnable mShowPendingUndo = this::showPendingUndo;
    /** M048: the light scrim over the window while the list is open (in the window's overlay, no view of its own). */
    private android.graphics.drawable.ColorDrawable mScrim;
    @Nullable
    private View mScrimHost;
    @Nullable
    private android.animation.ValueAnimator mScrimFade;
    /** The list goes without its slide (to another screen): the scrim goes at once too. */
    private boolean mHideQuietly;

    private final ProfileAdapter mProfileAdapter = new ProfileAdapter(new ProfileAdapterExtra[]{
            new ProfileAdapterExtra(VERSION_SPINNER_PROFILE_CREATE,
                    R.string.create_profile,
                    ResourcesCompat.getDrawable(getResources(), R.drawable.ic_add, null)),
    });


    /** Set the selection AND saves it as a shared preference */
    public void setProfileSelection(int position){
        setSelection(position);
        // MOB2: only a profile is Play's choice (not the "Resmî paketi kur" or "Yeni profil" rows).
        Object item = mProfileAdapter.getItem(position);
        if (!(item instanceof String)) return;
        LauncherPreferences.DEFAULT_PREF.edit()
                .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, (String) item)
                .apply();
    }

    public void setSelection(int position){
        if(mListView != null) mListView.setSelection(position);
        mProfileAdapter.setView(this, mProfileAdapter.getItem(position), false);
        mSelectedIndex = position;
    }

    public void openProfileEditor(FragmentActivity fragmentActivity) {
        Object currentSelection = mProfileAdapter.getItem(mSelectedIndex);
        if(currentSelection instanceof ProfileAdapterExtra) {
            performExtraAction((ProfileAdapterExtra) currentSelection);
        }else{
            Tools.swapFragment(fragmentActivity, ProfileEditorFragment.class, ProfileEditorFragment.TAG, null);
        }
    }

    /** Reload profiles from the file, forcing the spinner to consider the new data */
    public void reloadProfiles(){
        mProfileAdapter.reloadProfiles();
        // MOB2: rows can move (the list has its own order, and the "Resmî paketi kur" row comes and
        // goes): the play bar keeps showing Play's choice, and its edit button opens that profile.
        int index = mProfileAdapter.resolveProfileIndex(
                LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, ""));
        if (index >= 0) setSelection(index);
    }

    /** Initialize various behaviors */
    private void init(){
        // Setup various attributes
        // Projects fork (M14): the play bar's profile row: icon, name over what it plays, a caret
        // that turns while the list is open; presses like a row.
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        setTypeface(androidx.core.content.res.ResourcesCompat.getFont(getContext(), R.font.figtree_semibold));
        setTextColor(androidx.core.content.ContextCompat.getColor(getContext(), R.color.projects_bone_100));
        setMaxLines(2);
        setEllipsize(android.text.TextUtils.TruncateAt.END);
        setGravity(Gravity.CENTER_VERTICAL);
        int startPadding = getContext().getResources().getDimensionPixelOffset(R.dimen.projects_space_sm);
        int endPadding = getContext().getResources().getDimensionPixelOffset(R.dimen.projects_space_md);
        setPaddingRelative(startPadding, 0, endPadding, 0);
        setCompoundDrawablePadding(getContext().getResources().getDimensionPixelOffset(R.dimen.projects_space_md));
        gg.projects.mobile.ui.Press.attach(this, gg.projects.mobile.ui.Press.Style.ROW);
        // MOB2: while the Projects profile is gone, the list offers it again first ("Resmî paketi kur").
        mProfileAdapter.setOfficialRow(true);

        int profileIndex = -1;
        String extra_value = (String) ExtraCore.consumeValue(ExtraConstants.REFRESH_VERSION_SPINNER);
        if(extra_value == null) {
            profileIndex = mProfileAdapter.resolveProfileIndex(
                    LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE,""));
        } else if (!extra_value.equals(DELETED_PROFILE)) {
            profileIndex = mProfileAdapter.resolveProfileIndex(extra_value);
        }
        // MOB2: a choice that is gone falls to the list's first profile, never to a HashMap's first
        // entry (practically random) nor to a row that is no profile.
        if (profileIndex < 0) profileIndex = mProfileAdapter.firstProfilePosition();

        setProfileSelection(Math.max(0,profileIndex));

        // Popup window behavior
        setOnClickListener(new OnClickListener() {
            final int offset = -getContext().getResources().getDimensionPixelOffset(R.dimen._4sdp);
            @Override
            public void onClick(View v) {
                if(mPopupWindow == null) getPopupWindow();

                if(mPopupWindow.isShowing()){
                    mPopupWindow.dismiss();
                    return;
                }
                mPopupWindow.setHeight(popupHeight()); // Projects fork (M14)
                mPopupWindow.setWidth(popupWidth()); // TABLET-M
                showScrim(); // M048
                mPopupWindow.showAsDropDown(mcVersionSpinner.this, 0, offset);
                turnCaret(true);
                // Post() is required for the layout inflation phase
                post(() -> mListView.setSelection(mSelectedIndex));
            }
        });
    }

    private void performExtraAction(ProfileAdapterExtra extra) {
        //Replace with switch-case if you want to add more extra actions
        if (extra.id == VERSION_SPINNER_PROFILE_CREATE) {
            Tools.swapFragment((FragmentActivity) getContext(), ProfileTypeSelectFragment.class,
                    ProfileTypeSelectFragment.TAG, null);
        }
    }

    /**
     * MOB2: "Resmî paketi kur": the Projects profile again, with the player's own settings when the
     * trash still has them, chosen for Play; OYNA's join brings the pack's files, as on the desktop.
     */
    private void installOfficial() {
        final String version = DefaultProfiles.recommended();
        PojavApplication.sExecutorService.execute(() -> {
            boolean made;
            try {
                made = ProjectsProfile.install(version) != null;
            } catch (RuntimeException e) {
                Log.w(TAG, "Projects profile not made", e);
                made = false;
            }
            final boolean ok = made;
            MAIN.post(() -> {
                // The choice is on disk already: a home screen made meanwhile shows it.
                if (!isAttachedToWindow()) return;
                if (!ok) {
                    Haptics.reject(this);
                    Toast.makeText(getContext(), R.string.mob_profile_official_failed, Toast.LENGTH_LONG).show();
                    return;
                }
                reloadProfiles();
                int index = mProfileAdapter.resolveProfileIndex(ProjectsProfile.KEY);
                if (index >= 0) setProfileSelection(index);
                Haptics.confirm(this);
            });
        });
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // MOB2: a profile deleted in the editor offers "Geri al" on the home screen the editor closes onto.
        if (ProfileRemoval.hasUndo()) {
            mUndoBarTries = 0;
            post(mShowPendingUndo);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(mShowPendingUndo);
        // M048: a list still open goes with its screen, and its scrim with it.
        if (mPopupWindow != null && mPopupWindow.isShowing()) hidePopup(false);
        dropScrim();
        super.onDetachedFromWindow();
    }

    /** "<Ad> silindi" with "Geri al" in the home screen's undo bar (made with the home card). */
    private void showPendingUndo() {
        if (!isAttachedToWindow() || !ProfileRemoval.hasUndo()) return;
        final UndoBar bar = UndoBar.find(this);
        if (bar == null) {
            if (++mUndoBarTries < UNDO_BAR_TRIES) postDelayed(mShowPendingUndo, 50);
            return;
        }
        final ProfileRemoval.Undo undo = ProfileRemoval.takeUndo();
        if (undo == null) return;
        bar.show(getContext().getString(R.string.mob_profile_deleted, undo.name), R.drawable.projects_ic_trash,
                () -> undoDelete(bar, undo));
    }

    /** "Geri al": the profile back from the trash under its own key, Play's choice again. */
    private void undoDelete(final UndoBar bar, final ProfileRemoval.Undo undo) {
        PojavApplication.sExecutorService.execute(() -> {
            boolean restored;
            try {
                restored = ProfileRemoval.restore(undo.key);
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "profile not taken back", e);
                restored = false;
            }
            final boolean back = restored;
            MAIN.post(() -> {
                if (!isAttachedToWindow()) return;
                if (!back) {
                    bar.dismiss();
                    Toast.makeText(getContext(), R.string.mob_profile_undo_failed, Toast.LENGTH_LONG).show();
                    return;
                }
                reloadProfiles();
                int index = mProfileAdapter.resolveProfileIndex(undo.key);
                if (index >= 0) setProfileSelection(index);
                bar.showResult(getContext().getString(R.string.mob_profile_restored, undo.name), R.drawable.projects_ic_history);
            });
        });
    }


    /** Create the listView and popup window for the interface, and set up the click behavior */
    @SuppressLint("ClickableViewAccessibility")
    private void getPopupWindow(){
        mListView = (ListView) inflate(getContext(), R.layout.spinner_mc_version, null);
        mListView.setAdapter(mProfileAdapter);
        mListView.setOnItemClickListener((parent, view, position, id) -> {
            Object item = mProfileAdapter.getItem(position);
            if(item instanceof String) {
                hidePopup(true);
                setProfileSelection(position);
            }else if(item == ProfileAdapter.OFFICIAL_ROW) { // MOB2
                hidePopup(true);
                installOfficial();
            }else if(item instanceof ProfileAdapterExtra) {
                hidePopup(false);
                performExtraAction((ProfileAdapterExtra) item);
            }
        });

        mPopupWindow = new PopupWindow(mListView, MATCH_PARENT, getContext().getResources().getDimensionPixelOffset(R.dimen._184sdp));
        mPopupWindow.setElevation(5);
        mPopupWindow.setOnDismissListener(() -> {
            turnCaret(false); // Projects fork (M14)
            hideScrim(); // M048
        });
        mPopupWindow.setClippingEnabled(false);

        // Block clicking outside of the popup window
        mPopupWindow.setOutsideTouchable(true);
        mPopupWindow.setFocusable(true);
        mPopupWindow.setTouchInterceptor((v, event) -> {
            if(event.getAction() == MotionEvent.ACTION_OUTSIDE){
                mPopupWindow.dismiss();
                return true;
            }
            return false;
        });


        // Custom animation, nice slide in
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M){
            mPopupAnimation = new Slide(Gravity.BOTTOM);
            mPopupWindow.setEnterTransition((Transition) mPopupAnimation);
            mPopupWindow.setExitTransition((Transition) mPopupAnimation);
        }
    }

    private void hidePopup(boolean animate) {
        if(mPopupWindow == null) return;
        mHideQuietly = !animate; // M048: the scrim goes the way the list goes
        try {
            if(!animate && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                mPopupWindow.setEnterTransition(null);
                mPopupWindow.setExitTransition(null);
                mPopupWindow.dismiss();
                mPopupWindow.setEnterTransition((Transition) mPopupAnimation);
                mPopupWindow.setExitTransition((Transition) mPopupAnimation);
            }else {
                mPopupWindow.dismiss();
            }
        } finally {
            mHideQuietly = false;
        }
    }

    /**
     * M048 (TASTE-2's P3 of 0.4.7): while the list is open a light scrim lies over the rest of the window, so nothing
     * behind it reads as part of the list (on a wide window the home card's close cross sat just right of the list, like
     * its own close button). The list is modal anyway: a press outside only closes it. Opacity only, in step with the
     * caret (220 ms in, 180 ms out); at once with animations off or when the list goes without its slide.
     */
    private void showScrim() {
        View root = getRootView();
        if (root == null) return;
        if (mScrim == null) {
            mScrim = new android.graphics.drawable.ColorDrawable(
                    androidx.core.content.ContextCompat.getColor(getContext(), R.color.projects_scrim_light));
            mScrim.setAlpha(0);
        }
        if (mScrimHost != root) {
            dropScrim();
            mScrim.setAlpha(0);
            root.getOverlay().add(mScrim);
            mScrimHost = root;
        }
        mScrim.setBounds(0, 0, root.getWidth(), root.getHeight());
        fadeScrim(255, 220);
    }

    private void hideScrim() {
        if (mScrimHost == null) return;
        fadeScrim(0, mHideQuietly ? 0 : 180);
    }

    /** Fades the scrim to {@code to} (0-255) from where it is; it leaves the window's overlay at 0. */
    private void fadeScrim(final int to, long ms) {
        android.animation.ValueAnimator running = mScrimFade;
        mScrimFade = null;
        if (running != null) running.cancel();
        if (ms == 0 || !gg.projects.mobile.mods.Motion.enabled(getContext()) || !isShown()) {
            mScrim.setAlpha(to);
            if (to == 0) dropScrim();
            return;
        }
        final android.animation.ValueAnimator fade = android.animation.ValueAnimator.ofInt(mScrim.getAlpha(), to);
        fade.setDuration(ms);
        fade.setInterpolator(gg.projects.mobile.mods.Motion.EASE_OUT);
        fade.addUpdateListener(animation -> mScrim.setAlpha((Integer) animation.getAnimatedValue()));
        fade.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                // A fade cut short by the next one leaves the scrim to it.
                if (mScrimFade != fade) return;
                mScrimFade = null;
                if (to == 0) dropScrim();
            }
        });
        mScrimFade = fade;
        fade.start();
    }

    private void dropScrim() {
        android.animation.ValueAnimator running = mScrimFade;
        mScrimFade = null;
        if (running != null) running.cancel();
        if (mScrimHost != null && mScrim != null) mScrimHost.getOverlay().remove(mScrim);
        mScrimHost = null;
    }

    /**
     * Projects fork (M14): the sheet hugs its rows (upstream kept one fixed height, which left an
     * empty band under a short list), up to four and a half rows and the room above the play bar.
     */
    private int popupHeight() {
        float density = getResources().getDisplayMetrics().density;
        int row = Math.round(60 * density); // item_version_profile_layout
        int padding = mListView.getPaddingTop() + mListView.getPaddingBottom();
        int content = mProfileAdapter.getCount() * row + padding;
        int[] location = new int[2];
        getLocationInWindow(location);
        androidx.core.view.WindowInsetsCompat insets = androidx.core.view.ViewCompat.getRootWindowInsets(this);
        int top = insets == null ? 0 : insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).top;
        int room = location[1] - top - Math.round(16 * density);
        int cap = Math.min(Math.round(row * 4.5f) + padding, Math.max(row + padding, room));
        return Math.min(content, cap);
    }

    /**
     * TABLET-M: on a phone the list spans the window (as before); on a wide window it is as wide as the profile row it
     * opens from (at least 320 dp), not the whole tablet.
     */
    private int popupWidth() {
        if (getResources().getConfiguration().screenWidthDp < 600) return MATCH_PARENT;
        float density = getResources().getDisplayMetrics().density;
        return Math.max(getWidth(), Math.round(320 * density));
    }

    public ProfileAdapter getProfileAdapter() {
        return mProfileAdapter;
    }

    /** Projects fork (M14): the caret turns up while the list is open, back when it closes. */
    private void turnCaret(boolean open) {
        android.graphics.drawable.Drawable caret = getCompoundsDrawables()[2];
        if (caret == null) return;
        int target = open ? 10000 : 0;
        Object running = getTag(R.id.projects_motion_ghost);
        if (running instanceof android.animation.Animator) ((android.animation.Animator) running).cancel();
        if (!gg.projects.mobile.mods.Motion.enabled(getContext()) || !isShown()) {
            caret.setLevel(target);
            return;
        }
        android.animation.ObjectAnimator turn = android.animation.ObjectAnimator.ofInt(caret, "level", caret.getLevel(), target);
        turn.setDuration(open ? 220 : 180);
        turn.setInterpolator(gg.projects.mobile.mods.Motion.EASE_OUT);
        setTag(R.id.projects_motion_ghost, turn);
        turn.start();
    }
}
