package net.kdt.pojavlaunch.customcontrols.buttons;

import static net.kdt.pojavlaunch.LwjglGlfwKeycode.GLFW_KEY_UNKNOWN;
import static org.lwjgl.glfw.CallbackBridge.sendKeyPress;
import static org.lwjgl.glfw.CallbackBridge.sendMouseButton;

import android.annotation.SuppressLint;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import net.kdt.pojavlaunch.EfficientAndroidLWJGLKeycode;
import net.kdt.pojavlaunch.LwjglGlfwKeycode;
import net.kdt.pojavlaunch.MainActivity;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.customcontrols.ControlData;
import net.kdt.pojavlaunch.customcontrols.ControlLayout;
import net.kdt.pojavlaunch.customcontrols.handleview.EditControlSideDialog;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import org.lwjgl.glfw.CallbackBridge;

@SuppressLint({"ViewConstructor", "AppCompatCustomView"})
public class ControlButton extends TextView implements ControlInterface {
    private final Paint mRectPaint = new Paint();
    protected ControlData mProperties;
    private final ControlLayout mControlLayout;

    /* Cache value from the ControlData radius for drawing purposes */
    private float mComputedRadius;

    protected boolean mIsToggled = false;
    protected boolean mIsPointerOutOfBounds = false;

    // Projects fork (M15): the Projects look for buttons with a glyph, and press feedback.
    protected gg.projects.mobile.controls.ControlSkin mSkin;
    protected boolean mFingerDown;
    private boolean mShownPressed;
    private final Runnable mHeld = () -> {
        if (mShownPressed && mSkin != null) mSkin.setHeld(true);
    };

    public ControlButton(ControlLayout layout, ControlData properties) {
        super(layout.getContext());
        mControlLayout = layout;
        setGravity(Gravity.CENTER);
        // Projects fork: capitals with Locale.ROOT, so a Turkish phone shows "HIDE GUI", not "HİDE GUI".
        setTransformationMethod(LauncherPreferences.PREF_BUTTON_ALL_CAPS ? gg.projects.mobile.controls.ControlCaps.INSTANCE : null);
        setTextColor(Color.WHITE);
        setPadding(4, 4, 4, 4);
        setTextSize(14); // Nullify the default size setting
        setOutlineProvider(null); // Disable shadow casting, removing one drawing pass

        //setOnLongClickListener(this);

        //When a button is created, the width/height has yet to be processed to fit the scaling.
        setProperties(preProcessProperties(properties, layout));

        injectBehaviors();
    }

    @Override
    public View getControlView() {return this;}

    public ControlData getProperties() {
        return mProperties;
    }

    public void setProperties(ControlData properties, boolean changePos) {
        mProperties = properties;
        ControlInterface.super.setProperties(properties, changePos);
        mComputedRadius = ControlInterface.super.computeCornerRadius(mProperties.cornerRadius);

        if (mProperties.isToggle) {
            //For the toggle layer
            final TypedValue value = new TypedValue();
            getContext().getTheme().resolveAttribute(R.attr.colorAccent, value, true);
            mRectPaint.setColor(value.data);
            mRectPaint.setAlpha(128);
        } else {
            mRectPaint.setColor(Color.WHITE);
            mRectPaint.setAlpha(60);
        }

        setText(properties.name);
        mSkin = gg.projects.mobile.controls.ControlSkin.attach(this, mSkin, properties, LauncherPreferences.PREF_BUTTON_ALL_CAPS);
        if (mSkin != null) mSkin.setOn(mProperties.isToggle && mIsToggled);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mSkin != null) {
            mSkin.draw(canvas);
            return;
        }
        super.onDraw(canvas);
        if (mIsToggled || (!mProperties.isToggle && isActivated()))
            canvas.drawRoundRect(0, 0, getWidth(), getHeight(), mComputedRadius, mComputedRadius, mRectPaint);
    }

    /** Projects fork (M15): a skinned button draws its own key, so it has no background drawable. */
    @Override
    public void setBackground() {
        // The editor's colour, ring and corner controls end here: the skin takes them again.
        if (mSkin != null && mProperties != null) {
            mSkin = gg.projects.mobile.controls.ControlSkin.attach(this, mSkin, mProperties, LauncherPreferences.PREF_BUTTON_ALL_CAPS);
        }
        if (mSkin != null) {
            setBackground((android.graphics.drawable.Drawable) null);
            invalidate();
            return;
        }
        ControlInterface.super.setBackground();
    }

    /**
     * Projects fork (M15): the hide button's glyph and label follow whether the controls are shown
     * (ControlLayout tells it; the editor always shows its own). A key not laid out yet (a layout
     * just loaded) takes the state without a cross-fade.
     */
    public void onControlsShown(boolean shown) {
        if (mSkin == null || mProperties == null || !mProperties.containsKeycode(ControlData.SPECIALBTN_TOGGLECTRL)) return;
        mSkin.setAlt(!shown, "show", getContext().getString(R.string.projects_control_show));
        if (getWidth() == 0) mSkin.settle();
    }

    /** Projects fork (M15): a drawer button fading out takes no new touch (it reaches what is under it). */
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && gg.projects.mobile.controls.DrawerMotion.isLeaving(this)) return false;
        return super.dispatchTouchEvent(event);
    }

    /** Projects fork (M15): a press clicks down and ticks up (a toggle latches instead, see triggerToggle). */
    @Override
    public void setActivated(boolean activated) {
        boolean was = isActivated();
        super.setActivated(activated);
        if (was == activated || mProperties == null) return;
        if (!mProperties.isToggle) {
            if (activated) gg.projects.mobile.ui.Haptics.press(this);
            else gg.projects.mobile.ui.Haptics.release(this);
        }
        refreshPressed();
    }

    /** Projects fork (M15): the pressed look follows the finger, or the key for a swipeable button. */
    protected void refreshPressed() {
        if (mSkin == null || mProperties == null) return;
        boolean pressed = gg.projects.mobile.controls.ControlStyle.shownPressed(
                mProperties.isSwipeable, mProperties.isToggle, mFingerDown, isActivated());
        if (pressed == mShownPressed) return;
        mShownPressed = pressed;
        mSkin.setPressed(pressed);
        removeCallbacks(mHeld);
        if (pressed && !mProperties.isToggle && !(this instanceof ControlDrawer)) {
            postDelayed(mHeld, gg.projects.mobile.controls.ControlStyle.HOLD_AFTER_MS);
        } else {
            mSkin.setHeld(false);
        }
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        // Projects fork (M15): a key that disappears mid-press does not come back pressed.
        if (visibility != VISIBLE && mSkin != null && mFingerDown) {
            mFingerDown = false;
            refreshPressed();
            mSkin.settle();
        }
    }


    public void loadEditValues(EditControlSideDialog editControlPopup){
        editControlPopup.loadValues(getProperties());
    }

    /** Add another instance of the ControlButton to the parent layout */
    public void cloneButton(){
        ControlData cloneData = new ControlData(getProperties());
        cloneData.dynamicX = "0.5 * ${screen_width}";
        cloneData.dynamicY = "0.5 * ${screen_height}";
        ((ControlLayout) getParent()).addControlButton(cloneData);
    }

    /** Remove any trace of this button from the layout */
    public void removeButton() {
        getControlLayoutParent().getLayout().mControlDataList.remove(getProperties());
        getControlLayoutParent().removeView(this);
    }


    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()){
            case MotionEvent.ACTION_MOVE:
                //Send the event to be taken as a mouse action
                if(getProperties().passThruEnabled && CallbackBridge.isGrabbing()){
                    View gameSurface = getControlLayoutParent().getGameSurface();
                    if(gameSurface != null) gameSurface.dispatchTouchEvent(event);
                }

                //If out of bounds
                if(event.getX() < getControlView().getLeft() || event.getX() > getControlView().getRight() ||
                        event.getY() < getControlView().getTop()  || event.getY() > getControlView().getBottom()){
                    if(getProperties().isSwipeable && !mIsPointerOutOfBounds){
                        //Remove keys
                        if(!triggerToggle()) {
                            sendKeyPresses(false);
                        }
                    }
                    mIsPointerOutOfBounds = true;
                    getControlLayoutParent().onTouch(this, event);
                    break;
                }

                //Else if we now are in bounds
                if(mIsPointerOutOfBounds) {
                    getControlLayoutParent().onTouch(this, event);
                    //RE-press the button
                    if(getProperties().isSwipeable && !getProperties().isToggle){
                        sendKeyPresses(true);
                    }
                }
                mIsPointerOutOfBounds = false;
                break;

            case MotionEvent.ACTION_DOWN: // 0
            case MotionEvent.ACTION_POINTER_DOWN: // 5
                // Projects fork (M15): the finger shows on the key; a toggle clicks down here and latches on release.
                mFingerDown = true;
                refreshPressed();
                if(!getProperties().isToggle){
                    sendKeyPresses(true);
                } else {
                    gg.projects.mobile.ui.Haptics.press(this);
                }
                break;

            case MotionEvent.ACTION_UP: // 1
            case MotionEvent.ACTION_CANCEL: // 3
            case MotionEvent.ACTION_POINTER_UP: // 6
                mFingerDown = false;
                refreshPressed();
                if(getProperties().passThruEnabled){
                    View gameSurface = getControlLayoutParent().getGameSurface();
                    if(gameSurface != null) gameSurface.dispatchTouchEvent(event);
                }
                if(mIsPointerOutOfBounds) getControlLayoutParent().onTouch(this, event);
                mIsPointerOutOfBounds = false;

                if(!triggerToggle()) {
                    sendKeyPresses(false);
                }
                break;

            default:
                return false;
        }

        return super.onTouchEvent(event);
    }



    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean triggerToggle(){
        //returns true a the toggle system is triggered
        if(mProperties.isToggle){
            mIsToggled = !mIsToggled;
            invalidate();
            sendKeyPresses(mIsToggled);
            // Projects fork (M15): the latch is felt and seen (gold while on).
            gg.projects.mobile.ui.Haptics.toggle(this, mIsToggled);
            if (mSkin != null) mSkin.setOn(mIsToggled);
            return true;
        }
        return false;
    }

    public void sendKeyPresses(boolean isDown){
        setActivated(isDown);
        for(int keycode : mProperties.keycodes){
            if(keycode >= GLFW_KEY_UNKNOWN){
                sendKeyPress(keycode, EfficientAndroidLWJGLKeycode.getLwjglChar(keycode), CallbackBridge.getCurrentMods(), isDown);
                CallbackBridge.setModifiers(keycode, isDown);
            }else{
                Log.i("punjabilauncher", "sendSpecialKey("+keycode+","+isDown+")");
                sendSpecialKey(keycode, isDown);
            }
        }
    }

    private void sendSpecialKey(int keycode, boolean isDown){
        switch (keycode) {
            case ControlData.SPECIALBTN_KEYBOARD:
                if(isDown) MainActivity.switchKeyboardState();
                break;

            case ControlData.SPECIALBTN_TOGGLECTRL:
                if(isDown)getControlLayoutParent().toggleControlVisible();
                break;

            case ControlData.SPECIALBTN_VIRTUALMOUSE:
                if(isDown) MainActivity.toggleMouse(getContext());
                break;

            case ControlData.SPECIALBTN_MOUSEPRI:
                sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, isDown);
                break;

            case ControlData.SPECIALBTN_MOUSEMID:
                sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_MIDDLE, isDown);
                break;

            case ControlData.SPECIALBTN_MOUSESEC:
                sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT, isDown);
                break;

            case ControlData.SPECIALBTN_SCROLLDOWN:
                if (!isDown) CallbackBridge.sendScroll(0, 1f);
                break;

            case ControlData.SPECIALBTN_SCROLLUP:
                if (!isDown) CallbackBridge.sendScroll(0, -1f);
                break;

            case ControlData.SPECIALBTN_MENU:
                mControlLayout.notifyAppMenu();
                break;

            case ControlData.SPECIALBTN_MOUSEBCK:
                sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_4, isDown);
                break;

            case ControlData.SPECIALBTN_MOUSEFWD:
                sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_5, isDown);
                break;

            default:
                Toast.makeText(getContext(), R.string.error_key_unsupported, Toast.LENGTH_LONG).show();
                break;
        }
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }
}
