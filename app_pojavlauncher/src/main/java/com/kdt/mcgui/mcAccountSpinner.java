package com.kdt.mcgui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.LinearInterpolator;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SpinnerAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatSpinner;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.view.ViewCompat;


import net.kdt.pojavlaunch.PojavProfile;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.authenticator.listener.DoneListener;
import net.kdt.pojavlaunch.authenticator.listener.ErrorListener;
import net.kdt.pojavlaunch.authenticator.listener.ProgressListener;
import net.kdt.pojavlaunch.authenticator.microsoft.PresentedException;
import net.kdt.pojavlaunch.authenticator.microsoft.MicrosoftBackgroundLogin;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.extra.ExtraListener;
import net.kdt.pojavlaunch.value.MinecraftAccount;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import gg.projects.mobile.accounts.AccountOrder;
import gg.projects.mobile.accounts.ChipSwipe;
import gg.projects.mobile.mods.Motion;
import gg.projects.mobile.social.ui.CrewViews;
import gg.projects.mobile.ui.HapticMap;
import gg.projects.mobile.ui.Haptics;
import gg.projects.mobile.ui.HeadPlaceholderDrawable;
import gg.projects.mobile.ui.PixelHeadDrawable;
import gg.projects.mobile.ui.Press;
import gg.projects.mobile.ui.TopBarLine;

/**
 * The account picker. Projects fork (M14): drawn as the top bar's account chip (the desktop's
 * account switcher: crisp head, name, small caret; a quiet "Hesap ekle" without an account) with
 * a branded drop-down list; the sign-in progress moved to the bar's line ({@link TopBarLine}).
 *
 * <p>SWIPE-M (the owner, 1 November 2026: quick account switches on the phone): a sideways swipe on
 * the chip switches to the next account (left) or the previous one (right), around the ends, through
 * the list's own pick ({@link #onItemSelected}, so an expired Microsoft session refreshes as it does
 * there). Taps, holds and vertical drags still open the list ({@link ChipSwipe} tells them apart).
 * The head and name follow the finger, slide out and the next ones come in from the other side
 * while the pill glides to the new width (transform and opacity, the pill drawn narrower than the
 * chip meanwhile, so nothing beside it moves); a swipe during the motion carries on from where it
 * is. The list and the swipe keep the desktop's order ({@link AccountOrder}), pinned accounts first;
 * holding a row offers "Sabitle". TalkBack gets "Sonraki hesap" and "Önceki hesap" actions; a calm
 * line under the chip tells about the swipe once.
 */
public class mcAccountSpinner extends AppCompatSpinner implements AdapterView.OnItemSelectedListener {
    public mcAccountSpinner(@NonNull Context context) {
        this(context, null);
    }
    public mcAccountSpinner(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private final List<String> mAccountList = new ArrayList<>(2);
    private MinecraftAccount mSelectecAccount = null;
    /** Projects fork (M14): when the last touch on the chip began (a held chip opens the list). */
    private long mTouchDownAt;

    /* When a login is performed in the background, we need to know where we are */
    private final static int MAX_LOGIN_STEP = 5;
    private int mLoginStep = 0;

    /* Projects fork: the chip measured on the selected account alone (see onMeasure). */
    private View mMeasureView;
    /** SWIPE-M: the width spec of the last measure (the next account's width is worked out against it). */
    private int mWidthSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);

    // ---- SWIPE-M: the order, the swipe and its motion ----

    /** The order and the one-time line's state (the account files stay as they are). */
    private static final String PREFS = "projects_accounts";
    private static final String KEY_PINNED = "pinned";
    private static final String KEY_PLAYED = "played";
    private static final String KEY_ADDED = "added";
    private static final String KEY_HINT_SEEN = "swipe_hint_seen_ms";
    private static final String KEY_HINT_DONE = "swipe_hint_done";

    private static final int IDLE = 0;
    /** The finger moves the face. */
    private static final int DRAG = 1;
    /** The face leaves; the selection changes at its end. */
    private static final int OUT = 2;
    /** The next face comes in and the pill glides to its width. */
    private static final int IN = 3;
    /** The face goes back to rest (a swipe that did not switch). */
    private static final int SETTLE = 4;

    private ChipSwipe mSwipe;
    /** The finger the swipe follows while this touch may still be one (-1: none). */
    private int mSwipePointer = -1;
    /** This touch became a swipe: every event of it is the chip's until it ends. */
    private boolean mSwiping;
    /** The swipe was released (its finger lifted while another stays down). */
    private boolean mReleased;
    /** The pinned accounts, in the order they were pinned (read when the list is built). */
    private List<String> mPinned = new ArrayList<>();
    /** The account the chip is heading to while the face is on its way, else null. */
    @Nullable
    private String mTarget;
    private int mPhase = IDLE;
    /** The phase a drag interrupted (a cancelled swipe goes back to it). */
    private int mDragFrom = IDLE;
    /** Where the face last left to: -1 left, 1 right. */
    private int mExitSign = -1;
    /** The face's translation and opacity now, and where a drag took them from. */
    private float mShift;
    private float mAlpha = 1f;
    private float mBaseShift;
    private float mBaseAlpha = 1f;
    /** The caret's translation while the pill's end is not where the caret was laid out. */
    private float mCaretShift;
    @Nullable
    private ValueAnimator mFaceAnim;
    @Nullable
    private ValueAnimator mPillAnim;
    /** The chip's pill, drawable narrower than the chip while it glides between widths. */
    @Nullable
    private PillWidth mPill;
    /** The width the chip keeps during a switch (the wider of the two accounts), else 0. */
    private int mHoldWidth;
    private int mActionNext = -1;
    private int mActionPrevious = -1;
    private final List<Rect> mGestureExclusion = new ArrayList<>(1);
    /** The one-time line under the chip while it shows, its host (the window's root) and since when. */
    @Nullable
    private TextView mHint;
    @Nullable
    private ViewGroup mHintHost;
    private long mHintSince;
    private final Runnable mHintShow = this::showHint;
    private final Runnable mHintTimeout = () -> hideHint(false, true);

    /* Login listeners */
    private final ProgressListener mProgressListener = step -> {
        mLoginStep = step;
        TopBarLine line = loginLine();
        if (line != null) line.showProgress(step, MAX_LOGIN_STEP);
    };

    /** SWIPE-M: the account whose Microsoft session is being renewed now, or null. */
    @Nullable
    private String mRenewing;

    private final DoneListener mDoneListener = account -> {
        mRenewing = null;
        // Projects fork: an offline account was added, nobody signed in.
        Context toastContext = getContext();
        Toast.makeText(toastContext, account.isLocal()
                ? toastContext.getString(R.string.projects_offline_added, account.username)
                : toastContext.getString(R.string.main_login_done), Toast.LENGTH_SHORT).show();
        TopBarLine line = loginLine();
        if (line != null) line.finish();

        // Check if the account being added is not one that is already existing
        // Like login twice on the same mc account...
        for(String mcAccountName : mAccountList){
            if(mcAccountName.equals(account.username)) return;
        }

        mSelectecAccount = account;
        invalidate();
        noteAdded(account.username);
        mAccountList.add(account.username);
        reloadAccounts(false, account.username);
    };

    private final ErrorListener mErrorListener = errorMessage -> {
        mRenewing = null;
        TopBarLine line = loginLine();
        if (line != null) line.fail();
        Context context = getContext();
        if(errorMessage instanceof PresentedException) {
            PresentedException exception = (PresentedException) errorMessage;
            Throwable cause = exception.getCause();
            if(cause == null) {
                Tools.dialog(context, context.getString(R.string.global_error), exception.toString(context));
            }else {
                Tools.showError(context, exception.toString(context), exception.getCause());
            }
        }else {
            Tools.showError(getContext(), errorMessage);
        }
        invalidate();
    };

    /* Triggered when we need to do microsoft login */
    private final ExtraListener<Uri> mMicrosoftLoginListener = (key, value) -> {
        TopBarLine line = loginLine();
        if (line != null) line.showProgress(0, MAX_LOGIN_STEP);
        new MicrosoftBackgroundLogin(false, value.getQueryParameter("code")).performLogin(
                mProgressListener, mDoneListener, mErrorListener);
        return false;
    };

    /* Triggered when we need to perform mojang login */
    private final ExtraListener<String[]> mMojangLoginListener = (key, value) -> {
        if(value[1].isEmpty()){ // Test mode
            // Projects fork: a name already in the list (ignoring case) switches to that account,
            // so no account file is ever overwritten, a Microsoft account's included.
            String existing = gg.projects.mobile.accounts.OfflineAccounts.findExisting(value[0],
                    mAccountList.subList(1, mAccountList.size()));
            if (existing != null) {
                pickAccount(mAccountList.indexOf(existing));
                Toast.makeText(getContext(), getContext().getString(R.string.projects_offline_switched, existing), Toast.LENGTH_SHORT).show();
                return false;
            }
            MinecraftAccount account = new MinecraftAccount();
            account.username = value[0];
            try {
                account.save();
            }catch (IOException e){
                Log.e("McAccountSpinner", "Failed to save the account : " + e);
            }

            mDoneListener.onLoginDone(account);
        }
        return false;
    };


    private void init(){
        // Projects fork (M14): a chip in the top bar, with a branded drop-down below it.
        Context context = getContext();
        setPopupBackgroundDrawable(ContextCompat.getDrawable(context, R.drawable.projects_popup_background));
        float density = getResources().getDisplayMetrics().density;
        setDropDownWidth(Math.round(300 * density));
        setDropDownVerticalOffset(Math.round(52 * density));
        // SWIPE-M: the pill is set once (the quiet pill with or without an account, FREE-M 0.4.5), wrapped so it can be
        // drawn narrower than the chip while it glides to the next account's width; the face sits at the start, so a
        // chip held wider for a moment keeps its head where it was.
        Drawable pill = ContextCompat.getDrawable(context, R.drawable.projects_account_chip);
        if (pill != null) {
            mPill = new PillWidth(pill);
            setBackground(mPill);
            mPill.jumpToCurrentState();
        }
        setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        Press.attach(this, Press.Style.BUTTON);
        setOnLongClickListener(v -> mAccountList.size() <= 1 && performClick());
        // The list's own hold opens it halfway between a tap and a long press (AppCompat's ForwardingListener); the
        // swipe decides a little before that.
        long listHold = (ViewConfiguration.getTapTimeout() + ViewConfiguration.getLongPressTimeout()) / 2;
        mSwipe = new ChipSwipe(density, ViewConfiguration.get(context).getScaledTouchSlop(), ChipSwipe.holdFor(listHold));

        // Set behavior
        reloadAccounts(true, null);
        setOnItemSelectedListener(this);

        ExtraCore.addExtraListener(ExtraConstants.MOJANG_LOGIN_TODO, mMojangLoginListener);
        ExtraCore.addExtraListener(ExtraConstants.MICROSOFT_LOGIN_TODO, mMicrosoftLoginListener);
    }

    @Nullable
    private TopBarLine loginLine() {
        View root = getRootView();
        View line = root == null ? null : root.findViewById(R.id.projects_top_line);
        return line instanceof TopBarLine ? (TopBarLine) line : null;
    }


    @Override
    public final void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        if(position == 0){  // Add account button
            if(mAccountList.size() > 1){
                ExtraCore.setValue(ExtraConstants.SELECT_AUTH_METHOD, true);
                // Projects fork: the chip keeps showing the account in use (upstream left "add
                // account" selected when the player backed out of adding one).
                int current = mSelectecAccount == null ? -1 : mAccountList.indexOf(mSelectecAccount.username);
                if (current > 0) post(() -> setSelection(current, false));
            }
            return;
        }

        pickAccount(position);
        if(mSelectecAccount != null)
            performLogin(mSelectecAccount);
    }

    @Override
    public final void onNothingSelected(AdapterView<?> parent) {}

    /**
     * Projects fork: with no account the chip is a button to the account screen (upstream used a
     * touch listener that never released the pressed state); otherwise the list opens and the
     * caret turns up until it closes.
     */
    @Override
    public boolean performClick() {
        // SWIPE-M: a switch on its way lands before the list opens, and the one-time line has been seen.
        finishSwap(LAND);
        hideHint(true, true);
        if (mAccountList.size() <= 1) {
            sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED);
            playSoundEffect(android.view.SoundEffectConstants.CLICK);
            ExtraCore.setValue(ExtraConstants.SELECT_AUTH_METHOD, true);
            return true;
        }
        boolean opened = super.performClick();
        if (opened) turnCaret(180f);
        return opened;
    }

    /**
     * SWIPE-M: the chip's touches. A touch that may still be a swipe is watched beside the spinner's
     * own handling (its press, its tap and the list's hold and drag-to-open); once {@link ChipSwipe}
     * calls it a swipe, the spinner gets a cancel and the rest of the touch is the chip's.
     */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            mTouchDownAt = SystemClock.uptimeMillis();
            // The chip is touched: the one-time line has done its job.
            hideHint(true, true);
            mSwiping = false;
            mReleased = false;
            mSwipePointer = -1;
            if (swipeable() && mSwipe != null) {
                mSwipePointer = event.getPointerId(0);
                mSwipe.down(event.getX(), event.getY(), event.getEventTime(), isRtl());
            }
            return super.onTouchEvent(event);
        }
        if (mSwiping) {
            onSwipeEvent(event, action);
            return true;
        }
        if (mSwipePointer != -1) {
            if (action == MotionEvent.ACTION_MOVE && watchMove(event)) return true;
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                mSwipePointer = -1;
                mSwipe.cancel();
            }
        }
        return super.onTouchEvent(event);
    }

    /** A move of a touch that may still be a swipe: true once it is one (the move is then the chip's). */
    private boolean watchMove(MotionEvent event) {
        int index = event.findPointerIndex(mSwipePointer);
        if (index < 0) return false;
        for (int h = 0; h < event.getHistorySize(); h++) {
            mSwipe.move(event.getHistoricalX(index, h), event.getHistoricalY(index, h), event.getHistoricalEventTime(h));
        }
        float x = event.getX(index);
        float y = event.getY(index);
        ChipSwipe.Touch touch = mSwipe.move(x, y, event.getEventTime());
        // About to leave the chip, where the list's drag-to-open would take the touch: it is decided now.
        int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        if (touch == ChipSwipe.Touch.UNDECIDED
                && (x < -slop || y < -slop || x >= getWidth() + slop || y >= getHeight() + slop)) {
            touch = mSwipe.decideNow();
        }
        if (touch == ChipSwipe.Touch.SWIPE) {
            claim(event);
            dragTo(mSwipe.follow());
            return true;
        }
        if (touch == ChipSwipe.Touch.OTHER) mSwipePointer = -1;
        return false;
    }

    /** The touch is a swipe: the press, its ripple and the list's hold end, and the chip takes the rest of it. */
    private void claim(MotionEvent event) {
        mSwiping = true;
        // The press's release tick stays silent (a swipe is not a press); Haptics reads this mark.
        setTag(R.id.projects_haptic_strong, SystemClock.uptimeMillis());
        MotionEvent cancel = MotionEvent.obtain(event);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        super.onTouchEvent(cancel);
        cancel.recycle();
        ViewParent parent = getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
        // A focus change after this is no list opened by holding the chip.
        mTouchDownAt = 0;
        beginDrag();
    }

    private void onSwipeEvent(MotionEvent event, int action) {
        int index = event.findPointerIndex(mSwipePointer);
        switch (action) {
            case MotionEvent.ACTION_MOVE:
                if (mReleased || index < 0) return;
                for (int h = 0; h < event.getHistorySize(); h++) {
                    mSwipe.move(event.getHistoricalX(index, h), event.getHistoricalY(index, h), event.getHistoricalEventTime(h));
                }
                mSwipe.move(event.getX(index), event.getY(index), event.getEventTime());
                dragTo(mSwipe.follow());
                return;
            case MotionEvent.ACTION_POINTER_UP:
                // Other fingers are ignored; the swipe ends when its own finger lifts.
                if (!mReleased && event.getPointerId(event.getActionIndex()) == mSwipePointer) {
                    mReleased = true;
                    release(mSwipe.up(event.getX(event.getActionIndex()), event.getY(event.getActionIndex()), event.getEventTime()));
                }
                return;
            case MotionEvent.ACTION_UP:
                if (!mReleased) {
                    mReleased = true;
                    float x = index < 0 ? event.getX() : event.getX(index);
                    float y = index < 0 ? event.getY() : event.getY(index);
                    release(mSwipe.up(x, y, event.getEventTime()));
                }
                endSwipeTouch();
                return;
            case MotionEvent.ACTION_CANCEL:
                if (!mReleased) {
                    mReleased = true;
                    mSwipe.cancel();
                    release(ChipSwipe.Result.BACK);
                }
                endSwipeTouch();
                return;
            default:
        }
    }

    private void endSwipeTouch() {
        mSwiping = false;
        mSwipePointer = -1;
    }

    /** Two or more accounts: there is somewhere to swipe to. */
    private boolean swipeable() {
        return mAccountList.size() > 2 && isEnabled();
    }

    private boolean isRtl() {
        return getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
    }

    private float density() {
        return getResources().getDisplayMetrics().density;
    }

    // ---- SWIPE-M: the switch's motion ----

    /** A drag starts: whatever was moving stops where it is and the finger takes the face from there. */
    private void beginDrag() {
        mDragFrom = mPhase;
        stopFace();
        stopPill();
        mBaseShift = mShift;
        mBaseAlpha = mAlpha;
        mPhase = DRAG;
    }

    private void dragTo(float follow) {
        if (mPhase != DRAG) return;
        mShift = mBaseShift + follow;
        mAlpha = mBaseAlpha * ChipSwipe.followAlpha(follow, density());
        applyFace();
    }

    /** The finger let go: a switch, or back to what the drag interrupted. */
    private void release(ChipSwipe.Result result) {
        if (mPhase != DRAG) return;
        int step = ChipSwipe.step(result);
        if (step != 0) {
            markHintDone();
            if (commit(step, ChipSwipe.sign(result, isRtl()))) return;
        }
        if (mDragFrom == OUT && mTarget != null && !mTarget.equals(shown())) {
            startOut(mExitSign);
        } else {
            mTarget = null;
            settle();
        }
    }

    /**
     * Heads one account on from where the chip is heading (1 the next, -1 the previous) and plays the switch; the face
     * leaves towards {@code sign}. False when there is no other account.
     */
    private boolean commit(int step, int sign) {
        String shown = shown();
        String to = AccountOrder.neighbour(order(), mTarget != null ? mTarget : shown, step);
        if (to == null) return false;
        Haptics.perform(this, HapticMap.Kind.TICK);
        if (to.equals(shown)) {
            // Back to the account on the chip (two accounts, there and back): the face returns.
            mTarget = null;
            settle();
            return true;
        }
        mTarget = to;
        if (!Motion.enabled(getContext())) {
            swapNow(LAND);
            return true;
        }
        startOut(sign);
        return true;
    }

    /** The face leaves towards {@code sign} from where it is; the next account comes in at the end. */
    private void startOut(final int sign) {
        stopPill();
        mPhase = OUT;
        mExitSign = sign;
        // The next head is drawn now (a first time reads its picture), not in the frame the faces swap.
        if (mTarget != null && getAdapter() instanceof AccountAdapter) ((AccountAdapter) getAdapter()).head(mTarget, 24);
        // The fade runs evenly to the end: with the slide's front-loaded curve the face would be gone halfway and the
        // chip would stand empty until the next one comes in.
        animateFace(ChipSwipe.exitShift(mShift, sign, density()), 0f, ChipSwipe.outMs(mAlpha), true, () -> swapIn(sign));
    }

    /**
     * The face is out of sight: the chip selects the target (the list's way, so the account is picked when the spinner
     * says so), and the next face comes in from the other side while the pill glides from its width now to the new one.
     */
    private void swapIn(int sign) {
        String target = mTarget;
        mTarget = null;
        int position = target == null ? -1 : mAccountList.indexOf(target);
        if (position <= 0 || position == getSelectedItemPosition()) {
            settle();
            return;
        }
        int from = pillWidthNow();
        setSelection(position, false);
        int to = wantedWidth(position);
        holdWidth(Math.max(from, to));
        mPhase = IN;
        mShift = ChipSwipe.enterShift(sign, density());
        mAlpha = 0f;
        mCaretShift = (from - to) * (isRtl() ? -1f : 1f);
        applyFace();
        applyCaret();
        animatePill(from, to, ChipSwipe.IN_MS);
        animateFace(0f, 1f, ChipSwipe.IN_MS, false, this::settleIdle);
    }

    /** The face goes back to rest and the pill to the shown account's width. */
    private void settle() {
        if (!Motion.enabled(getContext())) {
            settleIdle();
            return;
        }
        mPhase = SETTLE;
        animatePill(pillWidthNow(), wantedWidth(getSelectedItemPosition()), ChipSwipe.SETTLE_MS);
        animateFace(0f, 1f, ChipSwipe.SETTLE_MS, false, this::settleIdle);
    }

    /** Everything at rest; the chip lets go of a held width (the pill keeps the shown width until that layout). */
    private void settleIdle() {
        stopFace();
        stopPill();
        mPhase = IDLE;
        mShift = 0f;
        mAlpha = 1f;
        mCaretShift = 0f;
        applyFace();
        applyCaret();
        if (mHoldWidth != 0) {
            mHoldWidth = 0;
            requestLayout();
        } else if (mPill != null) {
            mPill.setVisibleWidth(-1, isRtl());
        }
        invalidate();
    }

    /** {@link #swapNow}: the chip shows the account, which the spinner picks when it notices the new selection. */
    private static final int LAND = 0;
    /** ...and it is picked here and now (OYNA reads it in the same moment). */
    private static final int PICK = 1;
    /**
     * ...the chip is leaving its window (its children are already detached; a dialog could not show): the account is
     * only saved as the one in use, which the next chip (a new screen size, the next start) shows.
     */
    private static final int LEAVING = 2;

    /** Lands a switch on its way at once ({@link #LAND}, {@link #PICK} or {@link #LEAVING}). */
    private void swapNow(int how) {
        String target = mTarget;
        mTarget = null;
        stopFace();
        stopPill();
        int position = target == null ? -1 : mAccountList.indexOf(target);
        if (position > 0 && position != getSelectedItemPosition()) {
            if (how == LEAVING) {
                PojavProfile.setCurrentProfile(getContext(), target);
            } else {
                setSelection(position, false);
                if (how == PICK) pickAccount(position);
            }
        }
        mPhase = IDLE;
        mShift = 0f;
        mAlpha = 1f;
        mCaretShift = 0f;
        applyFace();
        applyCaret();
        if (mPill != null) mPill.setVisibleWidth(-1, isRtl());
        mHoldWidth = 0;
        if (how == LEAVING) return;
        mMeasureView = null;
        requestLayout();
        invalidate();
    }

    /** Ends any switch motion at once (a tap on the chip, the list changing, the chip leaving the screen). */
    private void finishSwap(int how) {
        if (mPhase != IDLE || mTarget != null || mFaceAnim != null || mPillAnim != null) swapNow(how);
    }

    /**
     * Moves the face to {@code toShift} and {@code toAlpha} from where it is. The slide eases out (a swipe's face is
     * already moving when the animation takes it over); the fade does too, or runs evenly ({@code evenFade}).
     */
    private void animateFace(final float toShift, final float toAlpha, long ms, final boolean evenFade, @Nullable final Runnable then) {
        stopFace();
        final float fromShift = mShift;
        final float fromAlpha = mAlpha;
        final ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(ms);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            float eased = Motion.EASE_OUT.getInterpolation(t);
            float fade = evenFade ? t : eased;
            mShift = fromShift + (toShift - fromShift) * eased;
            mAlpha = fromAlpha + (toAlpha - fromAlpha) * fade;
            applyFace();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (mCancelled || mFaceAnim != anim) return;
                mFaceAnim = null;
                mShift = toShift;
                mAlpha = toAlpha;
                applyFace();
                if (then != null) then.run();
            }
        });
        mFaceAnim = anim;
        anim.start();
    }

    private void stopFace() {
        ValueAnimator anim = mFaceAnim;
        mFaceAnim = null;
        if (anim != null) anim.cancel();
    }

    /** The pill's end glides from {@code from} to {@code to} pixels wide, the caret with it and the face clipped to it. */
    private void animatePill(final int from, final int to, long ms) {
        stopPill();
        if (mPill == null) return;
        final boolean rtl = isRtl();
        final float caretFrom = mCaretShift;
        mPill.setVisibleWidth(from, rtl);
        final ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(ms);
        anim.setInterpolator(Motion.EASE_OUT);
        anim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            if (mPill != null) mPill.setVisibleWidth(Math.round(from + (to - from) * f), rtl);
            mCaretShift = caretFrom * (1f - f);
            applyCaret();
            invalidate();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (mPillAnim == anim) mPillAnim = null;
            }
        });
        mPillAnim = anim;
        anim.start();
    }

    private void stopPill() {
        ValueAnimator anim = mPillAnim;
        mPillAnim = null;
        if (anim != null) anim.cancel();
    }

    /** The pill's width now: where its end is drawn, else the chip's width. */
    private int pillWidthNow() {
        int visible = mPill == null ? -1 : mPill.visibleWidth();
        return visible >= 0 ? visible : getWidth();
    }

    /** The chip keeps at least {@code width} pixels until the switch ends (so nothing beside it moves meanwhile). */
    private void holdWidth(int width) {
        if (mHoldWidth == width) return;
        mHoldWidth = width;
        requestLayout();
    }

    @Nullable
    private View chipPart(int id) {
        View selected = getSelectedView();
        return selected == null ? null : selected.findViewById(id);
    }

    private void applyFace() {
        View face = chipPart(R.id.projects_account_chip_face);
        if (face == null) return;
        face.setTranslationX(mShift);
        face.setAlpha(mAlpha);
    }

    private void applyCaret() {
        View caret = chipPart(R.id.projects_account_chip_caret);
        if (caret != null) caret.setTranslationX(mCaretShift);
    }

    /** SWIPE-M: the face is drawn only inside the pill while the pill is narrower than the chip. */
    @Override
    protected void dispatchDraw(Canvas canvas) {
        int visible = mPill == null ? -1 : mPill.visibleWidth();
        if (visible < 0 || visible >= getWidth()) {
            super.dispatchDraw(canvas);
            return;
        }
        int save = canvas.save();
        if (isRtl()) {
            canvas.clipRect(getWidth() - visible, 0, getWidth(), getHeight());
        } else {
            canvas.clipRect(0, 0, visible, getHeight());
        }
        super.dispatchDraw(canvas);
        canvas.restoreToCount(save);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        // A held width let go: the pill is the chip's own again, in the same frame as the chip's new width.
        if (mPhase == IDLE && mHoldWidth == 0 && mPill != null && mPill.visibleWidth() >= 0) mPill.setVisibleWidth(-1, isRtl());
        updateGestureExclusion();
    }

    /**
     * SWIPE-M: the chip sits 10 dp from the screen's edge, where Android's back gesture starts: with somewhere to swipe
     * to, the chip's own 48 dp are excluded from it (Android 10+; well within the system's 200 dp per edge).
     */
    private void updateGestureExclusion() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        mGestureExclusion.clear();
        if (swipeable() && getWidth() > 0) mGestureExclusion.add(new Rect(0, 0, getWidth(), getHeight()));
        setSystemGestureExclusionRects(mGestureExclusion);
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        // The list is a popup window: the bar's window gets its focus back when it closes. A list
        // opened by holding the chip (drag-to-open, no performClick) takes the focus right after
        // the touch began.
        if (hasWindowFocus) turnCaret(0f);
        else if (mAccountList.size() > 1 && SystemClock.uptimeMillis() - mTouchDownAt < 1500) turnCaret(180f);
        // SWIPE-M: another window (the list, a dialog, the game) lands a switch and puts the line away for now.
        if (hasWindowFocus) {
            maybeShowHint();
        } else {
            finishSwap(LAND);
            hideHint(false, true);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        maybeShowHint();
    }

    @Override
    protected void onDetachedFromWindow() {
        // SWIPE-M: the chip leaves the window (a new screen size, the app closing): a switch on its way is saved as the
        // account in use (the spinner's notice of the selection is dropped below, and its children are detached already).
        hideHint(false, false);
        finishSwap(LEAVING);
        super.onDetachedFromWindow();
    }

    /** SWIPE-M: where the caret points (up while the list is open), for a chip view made while the list is open. */
    private float mCaretDegrees;

    private void turnCaret(float degrees) {
        mCaretDegrees = degrees;
        View selected = getSelectedView();
        View caret = selected == null ? null : selected.findViewById(R.id.projects_account_chip_caret);
        if (caret == null || caret.getRotation() == degrees) return;
        caret.animate().cancel();
        if (!Motion.enabled(getContext())) {
            caret.setRotation(degrees);
            return;
        }
        caret.animate().rotation(degrees).setDuration(200).setInterpolator(Motion.EASE_OUT).start();
    }

    /**
     * Projects fork: a chip as wide as the account it shows (the platform spinner takes the width
     * of its widest entry). SWIPE-M: during a switch it keeps the wider of the two accounts.
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        mWidthSpec = widthMeasureSpec;
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) return;
        SpinnerAdapter adapter = getAdapter();
        int position = getSelectedItemPosition();
        if (adapter == null || position < 0 || position >= adapter.getCount()) return;
        int width = Math.max(wantedWidth(position), mHoldWidth);
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.AT_MOST) width = Math.min(width, MeasureSpec.getSize(widthMeasureSpec));
        setMeasuredDimension(width, getMeasuredHeight());
    }

    /** The chip's width for the account at {@code position}: its head, name and caret, within the room it has. */
    private int wantedWidth(int position) {
        SpinnerAdapter adapter = getAdapter();
        if (adapter == null || position < 0 || position >= adapter.getCount()) return getWidth();
        mMeasureView = adapter.getView(position, mMeasureView, this);
        if (mMeasureView.getLayoutParams() == null) {
            mMeasureView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        mMeasureView.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.AT_MOST));
        int width = mMeasureView.getMeasuredWidth() + getPaddingLeft() + getPaddingRight();
        if (MeasureSpec.getMode(mWidthSpec) == MeasureSpec.AT_MOST) width = Math.min(width, MeasureSpec.getSize(mWidthSpec));
        return width;
    }

    public void removeCurrentAccount(){
        removeAccount(getSelectedItemPosition());
    }

    private void removeAccount(int position) {
        if(position <= 0 || position >= mAccountList.size()) return;
        String name = mAccountList.get(position);
        File accountFile = new File(Tools.DIR_ACCOUNT_NEW, name+".json");
        if(accountFile.exists()) accountFile.delete();
        mAccountList.remove(position);
        forget(name);

        reloadAccounts(false, null);
    }

    /** Allows checking whether we have an online account */
    public boolean isAccountOnline(){
        return mSelectecAccount != null && !mSelectecAccount.accessToken.equals("0");
    }

    public MinecraftAccount getSelectedAccount(){
        // SWIPE-M: a switch on its way lands first, so OYNA pressed mid-swipe plays the account the chip is heading to
        // (the spinner's own notice of the new selection may not have come yet).
        if (mTarget != null || mPhase == IN) {
            finishSwap(PICK);
            int position = getSelectedItemPosition();
            if (position > 0 && position < mAccountList.size()
                    && (mSelectecAccount == null || !mAccountList.get(position).equals(mSelectecAccount.username))) {
                pickAccount(position);
            }
        }
        return mSelectecAccount;
    }

    public int getLoginState(){
        return mLoginStep;
    }

    public boolean isLoginDone(){
        return mLoginStep >= MAX_LOGIN_STEP;
    }

    /**
     * Reload the spinner, from memory or from scratch, in the desktop's order (SWIPE-M; kept until
     * the list is built again, so swiping walks every account).
     * @param fromFiles Whether we use files as the source of truth
     * @param select The account to select (it is the active one for the order), or null for the
     *               one in settings
     */
    private void reloadAccounts(boolean fromFiles, @Nullable String select){
        finishSwap(LAND);
        List<String> names = new ArrayList<>();
        if(fromFiles){
            File accountFolder = new File(Tools.DIR_ACCOUNT_NEW);
            String[] files = accountFolder.exists() ? accountFolder.list() : null;
            if (files != null) {
                for (String fileName : files) {
                    if (fileName.endsWith(".json") && fileName.length() > 5) names.add(fileName.substring(0, fileName.length() - 5));
                }
            }
        } else if (mAccountList.size() > 1) {
            names.addAll(mAccountList.subList(1, mAccountList.size()));
        }
        SharedPreferences prefs = accountPrefs();
        mPinned = AccountOrder.splitNames(prefs.getString(KEY_PINNED, null));
        String active = select != null ? select : PojavProfile.getCurrentProfileName(getContext());
        List<String> sorted = AccountOrder.sort(names, mPinned, active,
                AccountOrder.splitTimes(prefs.getString(KEY_PLAYED, null)), AccountOrder.splitTimes(prefs.getString(KEY_ADDED, null)));
        mAccountList.clear();
        mAccountList.add(getContext().getString(R.string.main_add_account));
        mAccountList.addAll(sorted);

        String[] accountArray = mAccountList.toArray(new String[0]);
        AccountAdapter accountAdapter = new AccountAdapter(getContext(), R.layout.item_minecraft_account, accountArray);
        accountAdapter.setDropDownViewResource(R.layout.item_minecraft_account);
        setAdapter(accountAdapter);
        mMeasureView = null;

        // Pick what's available, might just be the the add account "button"
        int position = select == null ? -1 : mAccountList.indexOf(select);
        pickAccount(position <= 0 ? -1 : position);
        if(mSelectecAccount != null)
            performLogin(mSelectecAccount);

        updateChipBackground();
        requestLayout();
        updateGestureExclusion();
        if (mAccountList.size() > 2) maybeShowHint();
        else hideHint(false, true);
    }

    /**
     * MOB-POLISH: the gold "Hesap ekle" invitation only on the home screen, beside OYNA (the desktop's
     * title bar pill); on the other screens (7/24 Bulut, friends, settings) the same pill is quiet, as
     * a gold pill there competed with each page's own actions (FEAT-M2's second look).
     */
    private boolean mOnHome = true;

    public void setOnHome(boolean home) {
        if (mOnHome == home) return;
        mOnHome = home;
        finishSwap(LAND);
        updateChipBackground();
        if (getAdapter() instanceof android.widget.BaseAdapter) ((android.widget.BaseAdapter) getAdapter()).notifyDataSetChanged();
        if (home) maybeShowHint();
        else hideHint(false, true);
    }

    /**
     * Projects fork: the quiet pill, with or without an account. FREE-M 0.4.5 (TASTE-2): a gold "Hesap ekle" on the
     * home screen was a second gold beside OYNA, with the home rows' gold words a third; OYNA stays the one gold there.
     * SWIPE-M: the pill itself is set once ({@link #init}); this keeps the hold, the description and TalkBack's actions.
     */
    private void updateChipBackground() {
        boolean empty = mAccountList.size() <= 1;
        // A held chip opens the list by drag-to-open, which skips performClick; with no account that
        // list holds only "Hesap ekle", whose tap selects nothing new. A long-clickable view gets no
        // drag-to-open: the hold opens the account screen like a tap.
        setLongClickable(empty);
        setContentDescription(empty ? getContext().getString(R.string.main_add_account)
                : mSelectecAccount == null ? null
                : getContext().getString(R.string.projects_account_chip, mSelectecAccount.username));
        updateAccountActions();
    }

    /** SWIPE-M: without an account the chip is a button to the account screen, not a list. */
    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (mAccountList.size() <= 1) {
            info.setClassName(android.widget.Button.class.getName());
            info.setCanOpenPopup(false);
        }
    }

    /**
     * SWIPE-M: TalkBack owns one-finger swipes, so the switch is an action of the chip: "Sonraki hesap: {ad}" and, with
     * three or more accounts, "Önceki hesap: {ad}" (with two both would name the same account). The list stays a tap away.
     */
    private void updateAccountActions() {
        if (mActionNext != -1) ViewCompat.removeAccessibilityAction(this, mActionNext);
        if (mActionPrevious != -1) ViewCompat.removeAccessibilityAction(this, mActionPrevious);
        mActionNext = -1;
        mActionPrevious = -1;
        List<String> order = order();
        String shown = shown();
        if (order.size() < 2 || shown == null) return;
        Context context = getContext();
        String next = AccountOrder.neighbour(order, shown, 1);
        String previous = AccountOrder.neighbour(order, shown, -1);
        if (next != null) {
            mActionNext = ViewCompat.addAccessibilityAction(this, context.getString(R.string.projects_account_next, next),
                    (view, arguments) -> switchByAction(1));
        }
        if (previous != null && !previous.equals(next)) {
            mActionPrevious = ViewCompat.addAccessibilityAction(this, context.getString(R.string.projects_account_previous, previous),
                    (view, arguments) -> switchByAction(-1));
        }
    }

    private boolean switchByAction(int step) {
        finishSwap(LAND);
        String to = AccountOrder.neighbour(order(), shown(), step);
        if (to == null) return false;
        boolean switched = commit(step, ChipSwipe.sign(step > 0 ? ChipSwipe.Result.NEXT : ChipSwipe.Result.PREVIOUS, isRtl()));
        if (switched) announceForAccessibility(getContext().getString(R.string.projects_account_chip, to));
        return switched;
    }

    /** The accounts in the chip's order (the list without "Hesap ekle"). */
    private List<String> order() {
        if (mAccountList.size() <= 1) return Collections.emptyList();
        return new ArrayList<>(mAccountList.subList(1, mAccountList.size()));
    }

    /** The account the chip shows now, or null for "Hesap ekle". */
    @Nullable
    private String shown() {
        int position = getSelectedItemPosition();
        return position > 0 && position < mAccountList.size() ? mAccountList.get(position) : null;
    }

    private SharedPreferences accountPrefs() {
        return getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** SWIPE-M: the game starts with the selected account: the order's "last play" (it applies when the list is next built). */
    public void notePlayed() {
        MinecraftAccount account = mSelectecAccount;
        if (account == null || account.username == null) return;
        putTime(KEY_PLAYED, account.username, System.currentTimeMillis());
    }

    private void noteAdded(String name) {
        putTime(KEY_ADDED, name, System.currentTimeMillis());
    }

    private void putTime(String key, String name, long at) {
        SharedPreferences prefs = accountPrefs();
        Map<String, Long> times = AccountOrder.splitTimes(prefs.getString(key, null));
        times.put(name, at);
        prefs.edit().putString(key, AccountOrder.joinTimes(times)).apply();
    }

    /** A removed account's pin and times go with it (one added again with that name starts afresh). */
    private void forget(String name) {
        SharedPreferences prefs = accountPrefs();
        Map<String, Long> played = AccountOrder.splitTimes(prefs.getString(KEY_PLAYED, null));
        Map<String, Long> added = AccountOrder.splitTimes(prefs.getString(KEY_ADDED, null));
        played.remove(name);
        added.remove(name);
        prefs.edit()
                .putString(KEY_PINNED, AccountOrder.joinNames(AccountOrder.setPinned(
                        AccountOrder.splitNames(prefs.getString(KEY_PINNED, null)), name, false)))
                .putString(KEY_PLAYED, AccountOrder.joinTimes(played))
                .putString(KEY_ADDED, AccountOrder.joinTimes(added))
                .apply();
    }

    /** SWIPE-M: pins an account (it joins the end of the pinned ones, at the top of the list) or unpins it. */
    private void setPinned(String name, boolean pin) {
        SharedPreferences prefs = accountPrefs();
        List<String> pinned = AccountOrder.setPinned(AccountOrder.splitNames(prefs.getString(KEY_PINNED, null)), name, pin);
        prefs.edit().putString(KEY_PINNED, AccountOrder.joinNames(pinned)).apply();
        // The list is built again in the new order; the chip keeps its account (an open list shows the change at once).
        reloadAccounts(false, shown());
        announceForAccessibility(getContext().getString(pin ? R.string.projects_account_pinned : R.string.projects_account_unpinned, name));
    }

    /**
     * SWIPE-M: a held row's menu, the app's hold menu everywhere (MessageMenu, the people menus): the account's head and
     * name, "Sabitle" or "Sabitlemeyi kaldır", and "Kaldır" (the row's remove button, with its question).
     */
    private void openAccountMenu(final String name) {
        final Context context = getContext();
        final boolean pinned = mPinned.contains(name);
        View sheet = LayoutInflater.from(context).inflate(R.layout.dialog_projects_actions, null, false);
        LinearLayout list = sheet.findViewById(R.id.projects_actions_list);
        list.addView(accountHeader(context, name));
        final AlertDialog dialog = new AlertDialog.Builder(context).setView(sheet).create();
        list.addView(CrewViews.actionRow(context, pinned ? R.drawable.projects_ic_push_pin_slash : R.drawable.projects_ic_push_pin,
                context.getString(pinned ? R.string.projects_account_unpin : R.string.projects_account_pin),
                R.color.projects_bone_100, v -> {
                    dialog.dismiss();
                    setPinned(name, !pinned);
                }));
        list.addView(CrewViews.actionRow(context, R.drawable.projects_ic_trash, context.getString(R.string.global_remove),
                R.color.projects_flare_400, v -> {
                    dialog.dismiss();
                    int position = mAccountList.indexOf(name);
                    if (position > 0 && getAdapter() instanceof AccountAdapter) {
                        ((AccountAdapter) getAdapter()).showDeleteDialog(context, position);
                    }
                }));
        if (dialog.getWindow() != null) dialog.getWindow().setWindowAnimations(R.style.ProjectsMenuSheetAnimation);
        dialog.show();
    }

    /** The menu's top: whose menu it is (the head and the name), a heading for TalkBack. */
    private View accountHeader(Context context, String name) {
        float dp = context.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Math.round(56 * dp));
        row.setPaddingRelative(Math.round(10 * dp), Math.round(4 * dp), Math.round(10 * dp), Math.round(8 * dp));
        ImageView head = new ImageView(context);
        head.setScaleType(ImageView.ScaleType.CENTER);
        head.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (getAdapter() instanceof AccountAdapter) head.setImageDrawable(((AccountAdapter) getAdapter()).head(name, 32));
        row.addView(head, new LinearLayout.LayoutParams(Math.round(36 * dp), Math.round(36 * dp)));
        TextView title = new TextView(context);
        title.setText(name);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setTextColor(ContextCompat.getColor(context, R.color.projects_bone_100));
        try {
            title.setTypeface(ResourcesCompat.getFont(context, R.font.figtree_semibold));
        } catch (RuntimeException ignored) {
            // The theme's font.
        }
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.setMarginStart(Math.round(12 * dp));
        row.addView(title, titleParams);
        ViewCompat.setAccessibilityHeading(row, true);
        row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    // ---- SWIPE-M: the one-time line under the chip ----

    private void maybeShowHint() {
        removeCallbacks(mHintShow);
        if (mHint != null || !hintPossible() || !hintDue()) return;
        postDelayed(mHintShow, ChipSwipe.HINT_DELAY_MS);
    }

    /** The home screen is in front with the chip on it. */
    private boolean hintPossible() {
        return mOnHome && isAttachedToWindow() && hasWindowFocus() && isShown() && getWidth() > 0;
    }

    private boolean hintDue() {
        SharedPreferences prefs = accountPrefs();
        return ChipSwipe.hintDue(mAccountList.size() - 1, prefs.getBoolean(KEY_HINT_DONE, false), prefs.getLong(KEY_HINT_SEEN, 0));
    }

    /**
     * "Hesaplar arasında geçmek için kaydır." in a small panel of the list's surface right under the chip, lined up with
     * it, in the window's overlay: it takes no touch and moves nothing. It rises 4 dp as it fades in (160 ms) and leaves
     * by itself after {@link ChipSwipe#HINT_SHOW_MS}.
     */
    private void showHint() {
        if (mHint != null || !hintPossible() || !hintDue()) return;
        View root = getRootView();
        if (!(root instanceof ViewGroup) || root.getWidth() <= 0) return;
        ViewGroup host = (ViewGroup) root;
        Context context = getContext();
        float dp = density();
        TextView hint = new TextView(context);
        hint.setText(R.string.projects_account_swipe_hint);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hint.setTextColor(ContextCompat.getColor(context, R.color.projects_ash_300));
        try {
            hint.setTypeface(ResourcesCompat.getFont(context, R.font.figtree_regular));
        } catch (RuntimeException ignored) {
            // The theme's font.
        }
        hint.setBackgroundResource(R.drawable.projects_popup_background);
        hint.setPadding(Math.round(12 * dp), Math.round(8 * dp), Math.round(12 * dp), Math.round(8 * dp));
        hint.setMaxLines(2);
        hint.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        int[] chip = new int[2];
        int[] origin = new int[2];
        getLocationInWindow(chip);
        host.getLocationInWindow(origin);
        View bar = getParent() instanceof View ? (View) getParent() : this;
        int[] barAt = new int[2];
        bar.getLocationInWindow(barAt);
        int margin = Math.round(8 * dp);
        int room = host.getWidth() - 2 * margin;
        hint.measure(MeasureSpec.makeMeasureSpec(room, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int width = hint.getMeasuredWidth();
        int height = hint.getMeasuredHeight();
        int left = (isRtl() ? chip[0] + getWidth() - width : chip[0]) - origin[0];
        left = Math.max(margin, Math.min(left, host.getWidth() - margin - width));
        int top = barAt[1] + bar.getHeight() - origin[1] + Math.round(6 * dp);
        hint.layout(left, top, left + width, top + height);
        host.getOverlay().add(hint);
        mHint = hint;
        mHintHost = host;
        mHintSince = SystemClock.uptimeMillis();
        if (Motion.enabled(context)) {
            hint.setAlpha(0f);
            hint.setTranslationY(-4 * dp);
            hint.animate().alpha(1f).translationY(0f).setDuration(160).setInterpolator(Motion.EASE_OUT).start();
        }
        postDelayed(mHintTimeout, ChipSwipe.HINT_SHOW_MS);
    }

    /**
     * Puts the line away: {@code done} when the chip was touched while it showed (it never comes back), else it counts
     * how long it was on screen (3 s in all and it never comes back either).
     */
    private void hideHint(boolean done, boolean animate) {
        removeCallbacks(mHintShow);
        removeCallbacks(mHintTimeout);
        final TextView hint = mHint;
        final ViewGroup host = mHintHost;
        if (hint == null || host == null) return;
        long seen = SystemClock.uptimeMillis() - mHintSince;
        mHint = null;
        mHintHost = null;
        mHintSince = 0;
        SharedPreferences prefs = accountPrefs();
        SharedPreferences.Editor edit = prefs.edit().putLong(KEY_HINT_SEEN, prefs.getLong(KEY_HINT_SEEN, 0) + Math.max(0, seen));
        if (done) edit.putBoolean(KEY_HINT_DONE, true);
        edit.apply();
        hint.animate().cancel();
        if (!animate || !Motion.enabled(getContext()) || !host.isShown()) {
            host.getOverlay().remove(hint);
            return;
        }
        hint.animate().alpha(0f).setDuration(110).setInterpolator(Motion.EASE_OUT)
                .withEndAction(() -> host.getOverlay().remove(hint)).start();
    }

    /** A player who switched by swiping knows the swipe: the line never shows. */
    private void markHintDone() {
        SharedPreferences prefs = accountPrefs();
        if (!prefs.getBoolean(KEY_HINT_DONE, false)) prefs.edit().putBoolean(KEY_HINT_DONE, true).apply();
        hideHint(true, true);
    }

    private void performLogin(MinecraftAccount minecraftAccount){
        // Logging in when there's no internet is useless. This should really be turned into a network callback though.
        if(!Tools.isOnline(getContext())){
            return;
        }
        if(minecraftAccount.isLocal()) return;

        if(minecraftAccount.isMicrosoft){
            if(System.currentTimeMillis() > minecraftAccount.expiresAt){
                // SWIPE-M: one renewal per account at a time (a swipe there and back, or the list built again, would
                // start a second one with the same refresh token).
                if (minecraftAccount.username != null && minecraftAccount.username.equals(mRenewing)) return;
                mRenewing = minecraftAccount.username;
                // Perform login only if needed
                TopBarLine line = loginLine();
                if (line != null) line.showProgress(0, MAX_LOGIN_STEP);
                new MicrosoftBackgroundLogin(true, minecraftAccount.msaRefreshToken)
                        .performLogin(mProgressListener, mDoneListener, mErrorListener);
            }
            return;
        }
    }

    /** Pick the selected account, the one in settings if 0 is passed */
    private void pickAccount(int position){
        MinecraftAccount selectedAccount;
        if(position != -1){
            PojavProfile.setCurrentProfile(getContext(), mAccountList.get(position));
            selectedAccount = PojavProfile.getCurrentProfileContent(getContext(), mAccountList.get(position));

            // WORKAROUND
            // Account file corrupted due to previous versions having improper encoding
            if (selectedAccount == null){
                Context ctx = Objects.requireNonNull(getContext());

                new AlertDialog.Builder(ctx)
                        .setCancelable(false)
                        .setTitle(R.string.account_corrupted)
                        .setMessage(R.string.login_again)
                        .setPositiveButton(R.string.delete_account_and_login, (dialog, which) -> {
                            removeCurrentAccount();
                            pickAccount(-1);
                            setSelection(0);
                        })
                        .show();


            }
            setSelection(position);
        }else {
            // Get the current profile, or the first available profile if the wanted one is unavailable
            selectedAccount = PojavProfile.getCurrentProfileContent(getContext(), null);
            int spinnerPosition = selectedAccount == null
                    ? mAccountList.size() <= 1 ? 0 : 1
                    : mAccountList.indexOf(selectedAccount.username);
            setSelection(spinnerPosition, false);
        }

        mSelectecAccount = selectedAccount;
        updateChipBackground();
        mMeasureView = null;
        requestLayout();
    }

    private class AccountAdapter extends ArrayAdapter<String> {

        private final HashMap<String, Drawable> mImageCache = new HashMap<>();
        private final HashMap<Integer, Drawable> mPlaceholders = new HashMap<>();
        public AccountAdapter(@NonNull Context context, int resource, @NonNull String[] objects) {
            super(context, resource, objects);
        }

        /**
         * Projects fork: the crisp head of {@code username} at {@code sizeDp}, or a placeholder tile
         * while there is none yet (an offline account's head comes from a game jar on the phone).
         * The placeholder is not cached with the heads, so the real head shows once it exists.
         */
        @NonNull
        private Drawable head(String username, int sizeDp) {
            String key = username + "@" + sizeDp;
            Drawable cached = mImageCache.get(key);
            if (cached != null) return cached;
            Bitmap face = MinecraftAccount.getSkinFace(username);
            float density = getContext().getResources().getDisplayMetrics().density;
            if (face == null) {
                Drawable placeholder = mPlaceholders.get(sizeDp);
                if (placeholder == null) {
                    placeholder = new HeadPlaceholderDrawable(getContext(), Math.round(sizeDp * density), 3 * density);
                    mPlaceholders.put(sizeDp, placeholder);
                }
                return placeholder;
            }
            Drawable drawable = new PixelHeadDrawable(face, Math.round(sizeDp * density), 3 * density);
            mImageCache.put(key, drawable);
            return drawable;
        }

        @Override
        public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            if(convertView == null){
                convertView = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_minecraft_account, parent, false);
                Press.attach(convertView, Press.Style.ROW);
            }
            holdRowsOf(parent);

            TextView textview = convertView.findViewById(R.id.account_item);
            ImageView headView = convertView.findViewById(R.id.projects_account_row_head);
            ImageView pinView = convertView.findViewById(R.id.projects_account_row_pin);
            ImageView deleteButton = convertView.findViewById(R.id.delete_account_button);
            textview.setText(super.getItem(position));
            // SWIPE-M: no accessibility delegate on a row (no custom action, no compat state description): the list
            // gives its rows its own delegate (click, select, the hold that opens the menu) only when they have none.
            convertView.setContentDescription(null);

            // MOB-NEXT (TASTE-2): the account in use sits on the quiet plate (design.md §1, the chosen item's tint; the
            // desktop's switcher marks it too), and TalkBack hears it as chosen.
            boolean active = position > 0 && position == mcAccountSpinner.this.getSelectedItemPosition();
            // The plate's inset would become the row's padding: the row keeps its own, so every name lines up.
            int start = convertView.getPaddingStart(), top = convertView.getPaddingTop();
            int end = convertView.getPaddingEnd(), bottom = convertView.getPaddingBottom();
            convertView.setBackgroundResource(active ? R.drawable.projects_account_row_active : 0);
            convertView.setPaddingRelative(start, top, end, bottom);

            // Handle the "Add account section"
            if(position == 0) {
                headView.setImageResource(R.drawable.projects_ic_user_plus);
                headView.setImageTintList(ContextCompat.getColorStateList(parent.getContext(), R.color.projects_gold_300));
                textview.setTextColor(ContextCompat.getColor(parent.getContext(), R.color.projects_gold_200));
                deleteButton.setVisibility(View.GONE);
                pinView.setVisibility(View.GONE);
            }
            else {
                final String username = super.getItem(position);
                headView.setImageTintList(null);
                headView.setImageDrawable(head(username, 32));
                textview.setTextColor(ContextCompat.getColor(parent.getContext(), R.color.projects_bone_100));

                deleteButton.setVisibility(View.VISIBLE);
                Press.attach(deleteButton, Press.Style.ICON);
                deleteButton.setContentDescription(parent.getContext().getString(R.string.projects_account_remove, username));
                deleteButton.setOnClickListener(v -> {
                    showDeleteDialog(getContext(), position);
                });

                // SWIPE-M: a pinned account's small pin after its name; TalkBack hears "{name}, sabitlenmiş" (pinning is
                // in the hold menu, which TalkBack reaches as the row's long press).
                boolean pinned = mPinned.contains(username);
                pinView.setVisibility(pinned ? View.VISIBLE : View.GONE);
                if (active) {
                    convertView.setContentDescription(parent.getContext().getString(
                            pinned ? R.string.projects_account_row_active_pinned : R.string.projects_account_row_active, username));
                } else if (pinned) {
                    convertView.setContentDescription(parent.getContext().getString(R.string.projects_account_row_pinned, username));
                }
            }
            return convertView;
        }

        /**
         * SWIPE-M: holding an account's row opens its menu. The list is the drop-down's own (it comes as the rows' parent);
         * it plays the hold's haptic itself, so it follows the app's "Dokunma titreşimi" switch.
         */
        private void holdRowsOf(ViewGroup parent) {
            if (!(parent instanceof AbsListView) || parent.getTag(R.id.projects_menu_action) != null) return;
            AbsListView list = (AbsListView) parent;
            list.setTag(R.id.projects_menu_action, Boolean.TRUE);
            list.setHapticFeedbackEnabled(Haptics.enabled(getContext()));
            list.setOnItemLongClickListener((adapterView, row, position, id) -> {
                if (position <= 0 || position >= mAccountList.size()) return false;
                openAccountMenu(mAccountList.get(position));
                return true;
            });
        }



        /** Projects fork (M14): the chip itself (head, name, caret), or "Hesap ekle" in gold. */
        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            if (convertView == null || convertView.findViewById(R.id.projects_account_chip_name) == null) {
                convertView = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_projects_account_chip, parent, false);
            }
            ImageView headView = convertView.findViewById(R.id.projects_account_chip_head);
            TextView name = convertView.findViewById(R.id.projects_account_chip_name);
            View caret = convertView.findViewById(R.id.projects_account_chip_caret);
            // SWIPE-M: a view used again starts at rest (the switch's motion sets the shown chip's own).
            View face = convertView.findViewById(R.id.projects_account_chip_face);
            if (face != null) {
                face.setTranslationX(0f);
                face.setAlpha(1f);
            }
            caret.setTranslationX(0f);
            caret.animate().cancel();
            caret.setRotation(mCaretDegrees);
            Context context = parent.getContext();
            if (position == 0) {
                headView.setImageResource(R.drawable.projects_ic_user_plus);
                headView.setImageTintList(ContextCompat.getColorStateList(context, R.color.projects_bone_100));
                name.setText(R.string.main_add_account);
                name.setTextColor(ContextCompat.getColor(context, R.color.projects_bone_100));
                caret.setVisibility(View.GONE);
            } else {
                String username = super.getItem(position);
                Drawable head = head(username, 24);
                headView.setImageTintList(null);
                headView.setImageDrawable(head);
                headView.setVisibility(View.VISIBLE);
                name.setText(username);
                name.setTextColor(ContextCompat.getColor(context, R.color.projects_bone_100));
                caret.setVisibility(View.VISIBLE);
            }
            return convertView;
        }

        /**
         * Projects fork (M14): names the account and asks; "Kaldır" stands where the confirming
         * choice does, in the error colour, with "İptal" beside it (upstream offered "Sil" on the
         * far side and "İptal" as the main choice, under "Bu hesap kaldırılacak!").
         */
        private void showDeleteDialog(Context context, int position) {
            AlertDialog dialog = new AlertDialog.Builder(context)
                    .setMessage(context.getString(R.string.projects_account_remove_question, getItem(position)))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.global_remove, (d, which) -> {
                        onDetachedFromWindow();
                        removeAccount(position);
                    })
                    .show();
            android.widget.Button remove = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (remove != null) remove.setTextColor(ContextCompat.getColor(context, R.color.projects_flare_400));
        }
    }

    /**
     * SWIPE-M: the chip's pill drawn narrower than the chip while it glides to the next account's width (the chip keeps
     * the wider width meanwhile, so nothing beside it moves); otherwise the pill itself, untouched. Every call the view
     * makes on its background (state, hotspot, padding, outline, visibility) reaches the pill.
     */
    private static final class PillWidth extends Drawable implements Drawable.Callback {
        private final Drawable mPillDrawable;
        private int mVisible = -1;
        private boolean mRtl;

        PillWidth(Drawable pill) {
            mPillDrawable = pill;
            pill.setCallback(this);
        }

        /** Draws the pill {@code width} pixels wide from the chip's start (-1: the chip's own width). */
        void setVisibleWidth(int width, boolean rtl) {
            if (mVisible == width && mRtl == rtl) return;
            mVisible = width;
            mRtl = rtl;
            fit(getBounds());
            invalidateSelf();
        }

        int visibleWidth() {
            return mVisible;
        }

        private void fit(Rect bounds) {
            if (mVisible < 0) {
                mPillDrawable.setBounds(bounds);
            } else if (mRtl) {
                mPillDrawable.setBounds(Math.max(bounds.left, bounds.right - mVisible), bounds.top, bounds.right, bounds.bottom);
            } else {
                mPillDrawable.setBounds(bounds.left, bounds.top, Math.min(bounds.right, bounds.left + mVisible), bounds.bottom);
            }
        }

        @Override
        protected void onBoundsChange(@NonNull Rect bounds) {
            fit(bounds);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            mPillDrawable.draw(canvas);
        }

        @Override
        public boolean isStateful() {
            return mPillDrawable.isStateful();
        }

        @Override
        protected boolean onStateChange(@NonNull int[] state) {
            return mPillDrawable.setState(state);
        }

        @Override
        public boolean setVisible(boolean visible, boolean restart) {
            mPillDrawable.setVisible(visible, restart);
            return super.setVisible(visible, restart);
        }

        @Override
        public void jumpToCurrentState() {
            mPillDrawable.jumpToCurrentState();
        }

        @Override
        public void setHotspot(float x, float y) {
            mPillDrawable.setHotspot(x, y);
        }

        @Override
        public void setHotspotBounds(int left, int top, int right, int bottom) {
            mPillDrawable.setHotspotBounds(left, top, right, bottom);
        }

        @Override
        public boolean getPadding(@NonNull Rect padding) {
            return mPillDrawable.getPadding(padding);
        }

        @Override
        public void getOutline(@NonNull Outline outline) {
            mPillDrawable.getOutline(outline);
        }

        @Override
        public int getIntrinsicWidth() {
            return mPillDrawable.getIntrinsicWidth();
        }

        @Override
        public int getIntrinsicHeight() {
            return mPillDrawable.getIntrinsicHeight();
        }

        @Override
        public void setAlpha(int alpha) {
            mPillDrawable.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            mPillDrawable.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        /**
         * The ripple draws its own keyboard focus (Android 8+ asks the background before it adds its default highlight);
         * before Android 12 the call is not public, and the pill is always a ripple.
         */
        @Override
        public boolean hasFocusStateSpecified() {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || mPillDrawable.hasFocusStateSpecified();
        }

        @Override
        public void invalidateDrawable(@NonNull Drawable who) {
            invalidateSelf();
        }

        @Override
        public void scheduleDrawable(@NonNull Drawable who, @NonNull Runnable what, long when) {
            scheduleSelf(what, when);
        }

        @Override
        public void unscheduleDrawable(@NonNull Drawable who, @NonNull Runnable what) {
            unscheduleSelf(what);
        }
    }
}
