package com.kdt.mcgui;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static net.kdt.pojavlaunch.fragments.ProfileEditorFragment.DELETED_PROFILE;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.transition.Slide;
import android.transition.Transition;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ListView;
import android.widget.PopupWindow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;
import androidx.fragment.app.FragmentActivity;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.fragments.ProfileEditorFragment;
import net.kdt.pojavlaunch.fragments.ProfileTypeSelectFragment;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.profiles.ProfileAdapter;
import net.kdt.pojavlaunch.profiles.ProfileAdapterExtra;

import fr.spse.extended_view.ExtendedTextView;

/**
 * A class implementing custom spinner like behavior, notably:
 * dropdown popup view with a custom direction.
 */
public class mcVersionSpinner extends ExtendedTextView {
    private static final int VERSION_SPINNER_PROFILE_CREATE = 0;
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

    private final ProfileAdapter mProfileAdapter = new ProfileAdapter(new ProfileAdapterExtra[]{
            new ProfileAdapterExtra(VERSION_SPINNER_PROFILE_CREATE,
                    R.string.create_profile,
                    ResourcesCompat.getDrawable(getResources(), R.drawable.ic_add, null)),
    });


    /** Set the selection AND saves it as a shared preference */
    public void setProfileSelection(int position){
        setSelection(position);
        LauncherPreferences.DEFAULT_PREF.edit()
                .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE,
                        mProfileAdapter.getItem(position).toString())
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

        int profileIndex;
        String extra_value = (String) ExtraCore.consumeValue(ExtraConstants.REFRESH_VERSION_SPINNER);
        if(extra_value != null){
            profileIndex = extra_value.equals(DELETED_PROFILE) ? 0
                    : getProfileAdapter().resolveProfileIndex(extra_value);
        }else
            profileIndex = mProfileAdapter.resolveProfileIndex(
                    LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE,""));

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
            }else if(item instanceof ProfileAdapterExtra) {
                hidePopup(false);
                performExtraAction((ProfileAdapterExtra) item);
            }
        });

        mPopupWindow = new PopupWindow(mListView, MATCH_PARENT, getContext().getResources().getDimensionPixelOffset(R.dimen._184sdp));
        mPopupWindow.setElevation(5);
        mPopupWindow.setOnDismissListener(() -> turnCaret(false)); // Projects fork (M14)
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
        if(!animate && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            mPopupWindow.setEnterTransition(null);
            mPopupWindow.setExitTransition(null);
            mPopupWindow.dismiss();
            mPopupWindow.setEnterTransition((Transition) mPopupAnimation);
            mPopupWindow.setExitTransition((Transition) mPopupAnimation);
        }else {
            mPopupWindow.dismiss();
        }
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
