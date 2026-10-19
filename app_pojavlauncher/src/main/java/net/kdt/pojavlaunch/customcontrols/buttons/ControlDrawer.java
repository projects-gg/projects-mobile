package net.kdt.pojavlaunch.customcontrols.buttons;

import android.annotation.SuppressLint;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.customcontrols.ControlData;
import net.kdt.pojavlaunch.customcontrols.ControlDrawerData;
import net.kdt.pojavlaunch.customcontrols.ControlLayout;
import net.kdt.pojavlaunch.customcontrols.handleview.EditControlSideDialog;

import java.util.ArrayList;



@SuppressLint("ViewConstructor")
public class ControlDrawer extends ControlButton {


    public final ArrayList<ControlSubButton> buttons;
    public final ControlDrawerData drawerData;
    public final ControlLayout parentLayout;
    public boolean areButtonsVisible;


    public ControlDrawer(ControlLayout layout, ControlDrawerData drawerData) {
        super(layout, drawerData.properties);

        buttons = new ArrayList<>(drawerData.buttonProperties.size());
        this.parentLayout = layout;
        this.drawerData = drawerData;
        areButtonsVisible = layout.getModifiable();
    }


    public void addButton(ControlData properties){
        addButton(new ControlSubButton(parentLayout, properties, this));
    }

    public void addButton(ControlSubButton button){
        buttons.add(button);
        syncButtons();
        setControlButtonVisibility(button, areButtonsVisible);
    }

    private void setControlButtonVisibility(ControlButton button, boolean isVisible){
        button.getControlView().setVisibility(isVisible ? VISIBLE : GONE);
    }

    private void switchButtonVisibility(){
        areButtonsVisible = !areButtonsVisible;
        // Projects fork (M15): one panel at a time, buttons that grow out of the drawer, the drawer
        // button gold with a close mark while open, and a latch felt on open and close.
        if (areButtonsVisible) parentLayout.closeDrawers(this);
        showButtons(areButtonsVisible);
        gg.projects.mobile.ui.Haptics.toggle(this, areButtonsVisible);
    }

    /** Projects fork (M15): closes the drawer if it is open (another drawer opened). */
    public void close() {
        if (!areButtonsVisible || parentLayout.getModifiable()) return;
        areButtonsVisible = false;
        showButtons(false);
    }

    private void showButtons(boolean show) {
        gg.projects.mobile.controls.DrawerMotion.Opacity opacity = view -> ((ControlButton) view).getProperties().opacity;
        if (show) gg.projects.mobile.controls.DrawerMotion.reveal(this, buttons, opacity);
        else gg.projects.mobile.controls.DrawerMotion.conceal(this, buttons, opacity, () -> !areButtonsVisible);
        if (mSkin != null) {
            mSkin.setOn(show);
            mSkin.setAlt(show, "close", null);
        }
    }

    //Syncing stuff
    private void alignButtons(){
        if(buttons == null) return;
        if(drawerData.orientation == ControlDrawerData.Orientation.FREE) return;
        int margin = (int) ControlInterface.getMarginDistance();

        for(int i = 0; i < buttons.size(); ++i){
            switch (drawerData.orientation){
                case RIGHT:
                    buttons.get(i).setDynamicX(generateDynamicX(getX() + (drawerData.properties.getWidth() + margin)*(i+1) ));
                    buttons.get(i).setDynamicY(generateDynamicY(getY()));
                    break;

                case LEFT:
                    buttons.get(i).setDynamicX(generateDynamicX(getX() - (drawerData.properties.getWidth() + margin)*(i+1)));
                    buttons.get(i).setDynamicY(generateDynamicY(getY()));
                    break;

                case UP:
                    buttons.get(i).setDynamicY(generateDynamicY(getY() - (drawerData.properties.getHeight() + margin)*(i+1)));
                    buttons.get(i).setDynamicX(generateDynamicX(getX()));
                    break;

                case DOWN:
                    buttons.get(i).setDynamicY(generateDynamicY(getY() + (drawerData.properties.getHeight() + margin)*(i+1)));
                    buttons.get(i).setDynamicX(generateDynamicX(getX()));
                    break;
            }
            buttons.get(i).updateProperties();
        }
    }


    private void resizeButtons(){
        if (buttons == null || drawerData.orientation == ControlDrawerData.Orientation.FREE) return;
        for(ControlSubButton subButton : buttons){
            subButton.mProperties.setWidth(mProperties.getWidth());
            subButton.mProperties.setHeight(mProperties.getHeight());

            subButton.updateProperties();
        }
    }

    public void syncButtons(){
        alignButtons();
        resizeButtons();
    }

    /**
     * Check whether or not the button passed as a parameter belongs to this drawer.
     *
     * @param button The button to look for
     * @return Whether the button is in the buttons list of the drawer.
     */
    public boolean containsChild(ControlInterface button){
        for(ControlButton childButton : buttons){
            if (childButton == button) return true;
        }
        return false;
    }

    @Override
    public ControlData preProcessProperties(ControlData properties, ControlLayout layout) {
        ControlData data = super.preProcessProperties(properties, layout);
        data.isHideable = true;
        return data;
    }

    @Override
    public void setVisible(boolean isVisible) {
        int visibility = isVisible ? VISIBLE : GONE;
        setVisibility(visibility);
        if(visibility == GONE || areButtonsVisible) {
            for(ControlSubButton button : buttons){
                button.getControlView().setVisibility(isVisible ? VISIBLE : (!mProperties.isHideable && getVisibility() == GONE) ? VISIBLE : View.GONE);
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if(!getControlLayoutParent().getModifiable()){
            switch (event.getActionMasked()){
                case MotionEvent.ACTION_DOWN: // 0
                case MotionEvent.ACTION_POINTER_DOWN: // 5
                    // Projects fork (M15): the drawer button presses like the others.
                    mFingerDown = true;
                    refreshPressed();
                    gg.projects.mobile.ui.Haptics.press(this);
                    break;
                case MotionEvent.ACTION_UP: // 1
                case MotionEvent.ACTION_POINTER_UP: // 6
                    mFingerDown = false;
                    refreshPressed();
                    switchButtonVisibility();
                    break;
                case MotionEvent.ACTION_CANCEL: // 3
                    mFingerDown = false;
                    refreshPressed();
                    break;
            }
            return true;
        }

        return super.onTouchEvent(event);
    }


    @Override
    public void setX(float x) {
        super.setX(x);
        alignButtons();
    }

    @Override
    public void setY(float y) {
        super.setY(y);
        alignButtons();
    }

    @Override
    public void setLayoutParams(ViewGroup.LayoutParams params) {
        super.setLayoutParams(params);
        syncButtons();
    }

    @Override
    public boolean canSnap(ControlInterface button) {
        boolean result = super.canSnap(button);
        return result && !containsChild(button);
    }

    //Getters
    public ControlDrawerData getDrawerData() {
        return drawerData;
    }

    @Override
    public void loadEditValues(EditControlSideDialog editControlPopup) {
        editControlPopup.loadValues(drawerData);
    }

    @Override
    public void cloneButton() {
        ControlDrawerData cloneData = new ControlDrawerData(getDrawerData());
        cloneData.properties.dynamicX = "0.5 * ${screen_width}";
        cloneData.properties.dynamicY = "0.5 * ${screen_height}";
        ((ControlLayout) getParent()).addDrawer(cloneData);
    }

    @Override
    public void removeButton() {
        ControlLayout layout = getControlLayoutParent();
        for(ControlSubButton subButton : buttons){
            layout.removeView(subButton);
        }

        layout.getLayout().mDrawerDataList.remove(getDrawerData());
        layout.removeView(this);
    }

}
