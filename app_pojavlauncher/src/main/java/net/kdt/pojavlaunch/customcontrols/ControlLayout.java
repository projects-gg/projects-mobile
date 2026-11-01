package net.kdt.pojavlaunch.customcontrols;

import static android.content.Context.INPUT_METHOD_SERVICE;
import static net.kdt.pojavlaunch.Tools.currentDisplayMetrics;

import static org.lwjgl.glfw.CallbackBridge.isGrabbing;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.gson.JsonSyntaxException;
import com.kdt.pickafile.FileListView;
import com.kdt.pickafile.FileSelectedListener;

import net.kdt.pojavlaunch.MinecraftGLSurface;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.customcontrols.buttons.ControlButton;
import net.kdt.pojavlaunch.customcontrols.buttons.ControlDrawer;
import net.kdt.pojavlaunch.customcontrols.buttons.ControlInterface;
import net.kdt.pojavlaunch.customcontrols.buttons.ControlJoystick;
import net.kdt.pojavlaunch.customcontrols.buttons.ControlSubButton;
import net.kdt.pojavlaunch.customcontrols.handleview.ActionRow;
import net.kdt.pojavlaunch.customcontrols.handleview.ControlHandleView;
import net.kdt.pojavlaunch.customcontrols.handleview.EditControlSideDialog;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import gg.projects.mobile.controls.ControlHint;
import gg.projects.mobile.controls.EditorBar;
import gg.projects.mobile.controls.EditorHost;
import gg.projects.mobile.controls.EditorRules;
import gg.projects.mobile.controls.LayoutBackups;
import gg.projects.mobile.ui.ConfirmDialog;

public class ControlLayout extends FrameLayout {
	protected CustomControls mLayout;
	/* Accessible when inside the game by ControlInterface implementations, cached for perf. */
	private MinecraftGLSurface mGameSurface = null;

	/* Cache to buttons for performance purposes */
	private List<ControlInterface> mButtons;
	private boolean mModifiable = false;
	private boolean mIsModified;
	private boolean mControlVisible = false;

	private EditControlSideDialog mControlDialog = null;
	// Projects fork (M16): why a MODLAR key cannot act, beside it; the keys follow the game's bindings.
	private gg.projects.mobile.controls.ControlHint mModHint;
	private final Runnable mModKeysChanged = this::refreshModKeys;
	private ControlHandleView mHandleView;
	private ControlButtonMenuListener mMenuListener;
	public ActionRow mActionRow = null;
	public String mLayoutFileName;
	// Projects fork (CTRL-SAVE): the screen the editor runs in, its "Menü" and "Kaydet", and the line that says a
	// save happened (with "Geri al").
	private EditorHost mHost;
	private EditorBar mEditorBar;
	private ControlHint mSaveLine;
	/** True while loadLayout puts keys in place: adding them is no edit of the player's. */
	private boolean mLoading;
	private boolean mSaving;
	/** Counts the player's edits, so a save that ran meanwhile does not count a newer edit as saved. */
	private int mEdits;
	/** The save whose "Geri al" is running. */
	private LayoutBackups.Saved mUndoing;

	public ControlLayout(Context ctx) {
		super(ctx);
	}

	public ControlLayout(Context ctx, AttributeSet attrs) {
		super(ctx, attrs);
	}


	public void loadLayout(String jsonPath) throws IOException, JsonSyntaxException {
		CustomControls layout = LayoutConverter.loadAndConvertIfNecessary(jsonPath);
		if(layout != null) {
			loadLayout(layout);
			updateLoadedFileName(jsonPath);
			return;
		}

		throw new IOException("Unsupported control layout version");
	}

	public void loadLayout(CustomControls controlLayout) {
		// Projects fork (CTRL-SAVE): the keys a load puts in place are no edit of the player's; the editor's bar
		// learns the layout's state once, at the end. A save's line (and its "Geri al") belongs to what was shown.
		if (mSaveLine != null) mSaveLine.hide();
		mLoading = true;
		try {
			putLayout(controlLayout);
		} finally {
			mLoading = false;
		}
		if (mEditorBar != null) {
			mEditorBar.setCanSave(mIsModified && !mSaving);
			mEditorBar.post(this::placeEditorBar);
		}
	}

	private void putLayout(CustomControls controlLayout) {
		boolean sanitizedModified = false;
		if(controlLayout != null) {
			sanitizedModified = LayoutSanitizer.sanitizeLayout(controlLayout);
		}
		if(mActionRow == null){
			mActionRow = new ActionRow(getContext());
			addView(mActionRow);
		}

		removeAllButtons();
		hideModHint();
		if(mLayout != null) {
			mLayout.mControlDataList = null;
			mLayout = null;
		}

		System.gc();
		mapTable.clear();

		// Cleanup buttons only when input layout is null
		if (controlLayout == null) return;

		mLayout = controlLayout;
		

		// Joystick(s) first, to workaround the touch dispatch
		for(ControlJoystickData joystick : mLayout.mJoystickDataList){
			addJoystickView(joystick);
		}

		//CONTROL BUTTON
		for (ControlData button : controlLayout.mControlDataList) {
			addControlView(button);
		}

		//CONTROL DRAWER
		for(ControlDrawerData drawerData : controlLayout.mDrawerDataList){
			ControlDrawer drawer = addDrawerView(drawerData);
			if(mModifiable) drawer.areButtonsVisible = true;
		}

		mLayout.scaledAt = LauncherPreferences.PREF_BUTTONSIZE;

		setModified(sanitizedModified);
		mButtons = null;
		getButtonChildren(); // Force refresh
		// Projects fork (M15): the hide button starts in the state the controls are in.
		for (ControlInterface button : mButtons) {
			if (button instanceof ControlButton) ((ControlButton) button).onControlsShown(mModifiable || mControlVisible);
		}
	} // loadLayout

	//CONTROL BUTTON
	public void addControlButton(ControlData controlButton) {
		mLayout.mControlDataList.add(controlButton);
		addControlView(controlButton);
	}

	private void addControlView(ControlData controlButton) {
		final ControlButton view = new ControlButton(this, controlButton);

		if (!mModifiable) {
			view.setAlpha(view.getProperties().opacity);
			view.setFocusable(false);
			view.setFocusableInTouchMode(false);
		}
		addView(view);

		setModified(true);
	}

	// CONTROL DRAWER
	public void addDrawer(ControlDrawerData drawerData){
		mLayout.mDrawerDataList.add(drawerData);
		addDrawerView();
	}

	private void addDrawerView(){
		addDrawerView(null);
	}

	private ControlDrawer addDrawerView(ControlDrawerData drawerData){

		final ControlDrawer view = new ControlDrawer(this,drawerData == null ? mLayout.mDrawerDataList.get(mLayout.mDrawerDataList.size()-1) : drawerData);

		if (!mModifiable) {
			view.setAlpha(view.getProperties().opacity);
			view.setFocusable(false);
			view.setFocusableInTouchMode(false);
		}
		addView(view);
		//CONTROL SUB BUTTON
		for (ControlData subButton : view.getDrawerData().buttonProperties) {
			addSubView(view, subButton);
		}

		setModified(true);
		return view;
	}

	//CONTROL SUB-BUTTON
	public void addSubButton(ControlDrawer drawer, ControlData controlButton){
		//Yep there isn't much here
		drawer.getDrawerData().buttonProperties.add(controlButton);
		addSubView(drawer, drawer.getDrawerData().buttonProperties.get(drawer.getDrawerData().buttonProperties.size()-1 ));
	}

	private void addSubView(ControlDrawer drawer, ControlData controlButton){
		final ControlSubButton view = new ControlSubButton(this, controlButton, drawer);

		if (!mModifiable) {
			view.setAlpha(view.getProperties().opacity);
			view.setFocusable(false);
			view.setFocusableInTouchMode(false);
		}else{
			view.setVisible(true);
		}

		addView(view);
		drawer.addButton(view);


		setModified(true);
	}

	// JOYSTICK BUTTON
	public void addJoystickButton(ControlJoystickData data){
		mLayout.mJoystickDataList.add(data);
		addJoystickView(data);
		// Projects fork (CTRL-SAVE): an added joystick is a change to save, as every other added key.
		setModified(true);
	}

	private void addJoystickView(ControlJoystickData data){
		ControlJoystick view = new ControlJoystick(this, data);

		if (!mModifiable) {
			view.setAlpha(view.getProperties().opacity);
			view.setFocusable(false);
			view.setFocusableInTouchMode(false);
		}
		addView(view);

	}


	private void removeAllButtons() {
		for(ControlInterface button : getButtonChildren()){
			removeView(button.getControlView());
		}

		System.gc();
		//i wanna be sure that all the removed Views will be removed after a reload
		//because if frames will slowly go down after many control changes it will be warm and bad
	}

	public void saveLayout(String path) throws Exception {
		mLayout.save(path);
		setModified(false);
	}

	public void toggleControlVisible(){
		mControlVisible = !mControlVisible;
		setControlVisible(mControlVisible);
	}

	public float getLayoutScale(){
		return mLayout.scaledAt;
	}

	public CustomControls getLayout(){
		return mLayout;
	}

	public void setControlVisible(boolean isVisible) {
		if (mModifiable) return; // Not using on custom controls activity

		mControlVisible = isVisible;
		if (!isVisible) hideModHint();
		for(ControlInterface button : getButtonChildren()){
			button.setVisible(((button.getProperties().displayInGame && isGrabbing()) || (button.getProperties().displayInMenu && !isGrabbing())) && isVisible);
			// Projects fork (M15): the hide button says "show" while the controls are hidden.
			if (button instanceof ControlButton) ((ControlButton) button).onControlsShown(isVisible);
		}
	}

	/** Projects fork (M15): closes every open drawer but {@code except} (one panel at a time). */
	public void closeDrawers(ControlDrawer except) {
		for (ControlInterface button : getButtonChildren()) {
			if (button instanceof ControlDrawer && button != except) ((ControlDrawer) button).close();
		}
	}

	/**
	 * Projects fork (M16): shows why the MODLAR key {@code key} cannot act, beside it; it stays while
	 * {@code held} ({@link #releaseModHint} starts its time).
	 */
	public void showModHint(View key, String reason, boolean held) {
		showModHint(key, reason, held, null);
	}

	/**
	 * Projects fork (M17): the reason with a button under it (the one-tap key fix, or "Geri al" once it
	 * is asked for). Only the button takes touches; the line stays longer so it can be reached.
	 */
	public void showModHint(View key, String reason, boolean held,
							@androidx.annotation.Nullable gg.projects.mobile.controls.ControlHint.Action action) {
		if (mModifiable) return;
		if (mModHint == null) {
			mModHint = new gg.projects.mobile.controls.ControlHint(getContext());
			addView(mModHint);
		}
		// A drawer's key: the line goes under the whole open panel, so it covers none of its keys.
		android.graphics.RectF clear = new android.graphics.RectF(key.getX(), key.getY(),
				key.getX() + key.getWidth(), key.getY() + key.getHeight());
		if (key instanceof ControlSubButton) {
			for (ControlSubButton button : ((ControlSubButton) key).parentDrawer.buttons) {
				if (button.getVisibility() != VISIBLE) continue;
				clear.union(button.getX(), button.getY(), button.getX() + button.getWidth(), button.getY() + button.getHeight());
			}
		}
		mModHint.show(key, clear, reason, held, action);
	}

	/** Projects fork (M16): the finger left the key; the reason stays long enough to be read. */
	public void releaseModHint() {
		if (mModHint != null) mModHint.release();
	}

	/** Projects fork (M16): the reason goes (its panel closed, the controls hid, a new layout). */
	public void hideModHint() {
		if (mModHint != null) mModHint.hide();
	}

	/**
	 * Projects fork (M16): the game's key bindings or its mods changed, or editing started or ended:
	 * the MODLAR keys look again (none is faded in the editor).
	 */
	private void refreshModKeys() {
		for (ControlInterface button : getButtonChildren()) {
			if (button instanceof ControlButton) ((ControlButton) button).refreshModKey();
		}
	}

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		gg.projects.mobile.controls.ModKeysLive.addListener(mModKeysChanged);
		refreshModKeys();
	}

	@Override
	protected void onDetachedFromWindow() {
		gg.projects.mobile.controls.ModKeysLive.removeListener(mModKeysChanged);
		super.onDetachedFromWindow();
	}

	public void setModifiable(boolean isModifiable) {
		if(!isModifiable && mModifiable){
			removeEditWindow();
		}
		mModifiable = isModifiable;
		if(isModifiable){
			// Projects fork (M16): the reason line goes with play.
			hideModHint();
			// In edit mode, all controls have to be shown
			for(ControlInterface button : getButtonChildren()){
				button.setVisible(true);
				// Projects fork (M15): and the hide button as itself.
				if (button instanceof ControlButton) ((ControlButton) button).onControlsShown(true);
			}
		}
		// Projects fork (CTRL-SAVE): "Menü" and "Kaydet" while the editor is open.
		showEditorBar(isModifiable);
		refreshModKeys();
	}

	public boolean getModifiable(){
		return mModifiable;
	}

	public void setModified(boolean isModified) {
		mIsModified = isModified;
		// Projects fork (CTRL-SAVE): upstream counted only an added key; every edit of the player's now comes here
		// (a move, a resize, a key's settings, a removal), turns "Kaydet" gold and makes leaving ask.
		if (mLoading) return;
		if (isModified) {
			mEdits++;
			// The last save's "Geri al" would throw this edit away too: it goes.
			if (mSaveLine != null) mSaveLine.hide();
		}
		if (mEditorBar != null) mEditorBar.setCanSave(isModified && !mSaving);
	}

	/** Projects fork (CTRL-SAVE): true while the layout holds a change "Kaydet" has not written. */
	public boolean isModified() {
		return mIsModified;
	}

	/** Projects fork (CTRL-SAVE): the screen the editor runs in (the file "Kaydet" writes, the menu, leaving). */
	public void setEditorHost(EditorHost host) {
		mHost = host;
	}

	/** Projects fork (CTRL-SAVE): the layout file the game reads now. */
	public String activeLayoutPath() {
		return mHost != null ? mHost.activeLayoutPath() : LauncherPreferences.PREF_DEFAULTCTRL_PATH;
	}

	private void showEditorBar(boolean show) {
		if (!show) {
			if (mEditorBar != null) mEditorBar.disappear();
			return;
		}
		if (mEditorBar == null) {
			mEditorBar = new EditorBar(getContext(), this::openEditorMenu, () -> saveActive(false, null));
			LayoutParams params = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
					Gravity.TOP | Gravity.CENTER_HORIZONTAL);
			// 8 dp from the top to the drawn buttons (each draws 40 dp inside its 48 dp target).
			params.topMargin = Math.round(4 * getResources().getDisplayMetrics().density);
			addView(mEditorBar, params);
			// APK-REL: placed clear of the keys each time it is laid out (opened, a new window size).
			mEditorBar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> placeEditorBar());
		}
		mEditorBar.setCanSave(mIsModified && !mSaving);
		mEditorBar.appear();
		mEditorBar.post(this::placeEditorBar);
	}

	/**
	 * APK-REL (0.4.9's emulator run): the bar keeps clear of the keys in its band ({@link EditorRules#barLeft}), measured
	 * when the editor opens, when a layout is loaded and when the keys take their places on a new window size; not while
	 * a key is dragged (a key moved over the bar is drawn above it and stays reachable, as CTRL-SAVE decided). The
	 * "Tuşlar kaydedildi." line under the bar follows it (it starts at the bar's left edge).
	 */
	private void placeEditorBar() {
		if (mEditorBar == null || !mModifiable) return;
		int width = getWidth();
		int barWidth = mEditorBar.getWidth();
		if (width == 0 || barWidth == 0) return;
		int top = mEditorBar.getTop();
		int bottom = mEditorBar.getBottom();
		List<ControlInterface> keys = getButtonChildren();
		int[] lefts = new int[keys.size()];
		int[] rights = new int[keys.size()];
		int n = 0;
		for (ControlInterface key : keys) {
			View view = key.getControlView();
			if (view.getVisibility() != VISIBLE) continue;
			android.view.ViewGroup.LayoutParams size = view.getLayoutParams();
			int keyWidth = view.getWidth() > 0 ? view.getWidth() : (size != null ? size.width : 0);
			int keyHeight = view.getHeight() > 0 ? view.getHeight() : (size != null ? size.height : 0);
			if (keyWidth <= 0 || keyHeight <= 0) continue;
			float y = view.getY();
			if (y >= bottom || y + keyHeight <= top) continue;
			lefts[n] = Math.round(view.getX());
			rights[n] = Math.round(view.getX() + keyWidth);
			n++;
		}
		int room = Math.round(8 * getResources().getDisplayMetrics().density);
		int left = EditorRules.barLeft(width, barWidth, java.util.Arrays.copyOf(lefts, n),
				java.util.Arrays.copyOf(rights, n), room, room);
		mEditorBar.setTranslationX(left - mEditorBar.getLeft());
	}

	private void openEditorMenu() {
		if (mHost != null) mHost.openEditorMenu();
		else notifyAppMenu();
	}

	/**
	 * Projects fork (CTRL-SAVE): "Kaydet". Writes the layout to the file the game reads (the version there is copied
	 * first, {@link LayoutBackups}), off the main thread; then the editor closes when {@code leaving} ("Kaydet ve
	 * çık"), or {@code after} runs. A save that fails says so and changes nothing.
	 */
	public void saveActive(final boolean leaving, @Nullable final Runnable after) {
		if (mSaving || mLayout == null) return;
		if (!mIsModified) {
			// The file already holds these keys. Writing it again would only turn an untouched default into an
			// edited layout, which no longer follows the app's language or a newer default (ControlLayouts).
			if (leaving) {
				if (mHost != null) mHost.exitEditor();
				return;
			}
			showSaveLine(getContext().getString(R.string.projects_ctrl_saved), null);
			if (after != null) after.run();
			return;
		}
		final File target = new File(activeLayoutPath());
		final byte[] bytes = mLayout.toJson().getBytes(StandardCharsets.UTF_8);
		final int edits = mEdits;
		mSaving = true;
		if (mEditorBar != null) mEditorBar.setCanSave(false);
		LayoutBackups.run(() -> {
			LayoutBackups.Saved saved = null;
			try {
				saved = LayoutBackups.save(target, bytes, System.currentTimeMillis());
			} catch (IOException | RuntimeException e) {
				Log.w("ControlLayout", "layout not saved", e);
			} finally {
				// Whatever happened, the editor hears back (a save that never answers would keep "Kaydet" and
				// leaving locked).
				final LayoutBackups.Saved result = saved;
				post(() -> afterSave(result, edits, leaving, after));
			}
		});
	}

	/** Projects fork (CTRL-SAVE): "Kaydet" when there is something to save; then {@code then}. */
	public void saveIfChanged(Runnable then) {
		if (!mIsModified) {
			then.run();
			return;
		}
		saveActive(false, then);
	}

	private void afterSave(@Nullable LayoutBackups.Saved saved, int edits, boolean leaving, @Nullable Runnable after) {
		mSaving = false;
		if (saved == null) {
			if (mEditorBar != null) mEditorBar.setCanSave(mIsModified);
			showSaveLine(getContext().getString(R.string.projects_ctrl_save_failed), null);
			return;
		}
		// An edit made while the file was written stays a change to save, and keeps the editor open: leaving now would
		// drop it. Its "Geri al" would drop it too, so the line comes without one.
		boolean newer = edits != mEdits;
		if (newer) {
			if (mEditorBar != null) mEditorBar.setCanSave(mIsModified);
		} else {
			setModified(false);
		}
		if (leaving && !newer && mHost != null) {
			mHost.exitEditor();
			mHost.editorSavedAndLeft(saved);
			return;
		}
		showSaved(newer ? null : saved);
		if (after != null) after.run();
	}

	/**
	 * Projects fork (CTRL-SAVE): "Tuşlar kaydedildi." under the editor's bar (or where it stood, in the game after
	 * "Kaydet ve çık"), with "Geri al" while the save can be taken back ({@code saved} null: without it).
	 */
	public void showSaved(@Nullable final LayoutBackups.Saved saved) {
		ControlHint.Action undo = saved != null && saved.undoable()
				? new ControlHint.Action(getContext().getString(R.string.projects_undo), () -> undoSave(saved)) : null;
		showSaveLine(getContext().getString(R.string.projects_ctrl_saved), undo);
	}

	private void showSaveLine(String message, @Nullable ControlHint.Action action) {
		if (mEditorBar == null) return;
		if (mSaveLine == null) {
			mSaveLine = new ControlHint(getContext());
			addView(mSaveLine);
		}
		android.graphics.RectF clear = new android.graphics.RectF(mEditorBar.getX(), mEditorBar.getY(),
				mEditorBar.getX() + mEditorBar.getWidth(), mEditorBar.getY() + mEditorBar.getHeight());
		mSaveLine.show(mEditorBar, clear, message, false, action);
	}

	/** Projects fork (CTRL-SAVE): "Geri al": the version before the save comes back and is shown. */
	private void undoSave(final LayoutBackups.Saved saved) {
		// One decision: a second tap while the line answers does nothing.
		if (mUndoing == saved) return;
		mUndoing = saved;
		final int edits = mEdits;
		LayoutBackups.run(() -> {
			boolean undone = false;
			try {
				undone = LayoutBackups.undo(saved);
			} catch (IOException | RuntimeException e) {
				Log.w("ControlLayout", "save not taken back", e);
			} finally {
				final boolean done = undone;
				post(() -> afterUndo(done, edits));
			}
		});
	}

	private void afterUndo(boolean done, int edits) {
		mUndoing = null;
		if (!done) {
			showSaveLine(getContext().getString(R.string.projects_ctrl_undo_failed), null);
			return;
		}
		// An edit made meanwhile stays on screen (the file holds the version before the save; "Kaydet" writes it).
		if (edits != mEdits) return;
		if (mModifiable) removeEditWindow();
		reloadActive();
		showSaveLine(getContext().getString(R.string.projects_ctrl_undone), null);
	}

	/**
	 * Projects fork (CTRL-SAVE): shows the layout the game reads again; a file that is gone (a save that made it was
	 * taken back) gives way to the shipped default, as the game's start does. The host then sees what is shown.
	 */
	private void reloadActive() {
		try {
			loadLayout(activeLayoutPath());
		} catch (IOException | RuntimeException e) {
			Log.w("ControlLayout", "layout not read again, the default instead", e);
			try {
				loadLayout(Tools.CTRLDEF_FILE);
			} catch (IOException | RuntimeException e2) {
				Log.w("ControlLayout", "default layout not read", e2);
			}
		}
		if (mHost != null) mHost.editorLayoutReloaded();
	}

	public List<ControlInterface> getButtonChildren(){
		if(mModifiable || mButtons == null){
			mButtons = new ArrayList<>();
			for(int i=0; i<getChildCount(); ++i){
				View v = getChildAt(i);
				if(v instanceof ControlInterface)
					mButtons.add(((ControlInterface) v));
			}
		}

		return mButtons;
	}

	public void refreshControlButtonPositions(){
		for(ControlInterface button : getButtonChildren()){
			button.setDynamicX(button.getProperties().dynamicX);
			button.setDynamicY(button.getProperties().dynamicY);
		}
		// APK-REL: the keys took their places on a new size; the editor's bar keeps clear of them.
		if (mEditorBar != null) mEditorBar.post(this::placeEditorBar);
	}

    @Override
    public void onViewRemoved(View child) {
        super.onViewRemoved(child);
        if(child instanceof ControlInterface && mControlDialog != null){
			mControlDialog.disappearColor();
            mControlDialog.disappear(false);
        }
    }

    /**
	 * Load the layout if needed, and pass down the burden of filling values
	 * to the button at hand.
	 */
	public void editControlButton(ControlInterface button){
		if(mControlDialog == null){
			// When the panel is null, it needs to inflate first.
			// So inflate it, then process it on the next frame
			mControlDialog = new EditControlSideDialog(getContext(), this);
			post(() -> editControlButton(button));
			return;
		}

		mControlDialog.internalChanges = true;
		mControlDialog.setCurrentlyEditedButton(button);

		mControlDialog.appear(button.getControlView().getX() + button.getControlView().getWidth()/2f < currentDisplayMetrics.widthPixels/2f);
		button.loadEditValues(mControlDialog);

		mControlDialog.internalChanges = false;

		mControlDialog.disappearColor();

		if(mHandleView == null){
			mHandleView = new ControlHandleView(getContext());
			addView(mHandleView);
		}
		mHandleView.setControlButton(button);

		//mHandleView.show();
	}

	/** Swap the panel if the button position requires it */
	public void adaptPanelPosition(){
		if(mControlDialog != null) mControlDialog.adaptPanelPosition();
	}


	final HashMap<View, ControlInterface> mapTable = new HashMap<>();

	//While this is called onTouch, this should only be called from a ControlButton.
	public void onTouch(View v, MotionEvent ev) {
		ControlInterface lastControlButton = mapTable.get(v);

		// Map location to screen coordinates
		ev.offsetLocation(v.getX(), v.getY());


		//Check if the action is cancelling, reset the lastControl button associated to the view
		if (ev.getActionMasked() == MotionEvent.ACTION_UP
				|| ev.getActionMasked() == MotionEvent.ACTION_CANCEL
				|| ev.getActionMasked() == MotionEvent.ACTION_POINTER_UP) {
			if (lastControlButton != null) lastControlButton.sendKeyPresses(false);
			mapTable.put(v, null);
			return;
		}

		if (ev.getActionMasked() != MotionEvent.ACTION_MOVE) return;


		//Optimization pass to avoid looking at all children again
		if (lastControlButton != null) {
			System.out.println("last control button check" + ev.getX() + "-" + ev.getY() + "-" + lastControlButton.getControlView().getX() + "-" + lastControlButton.getControlView().getY());
			if (ev.getX() > lastControlButton.getControlView().getX()
					&& ev.getX() < lastControlButton.getControlView().getX() + lastControlButton.getControlView().getWidth()
					&& ev.getY() > lastControlButton.getControlView().getY()
					&& ev.getY() < lastControlButton.getControlView().getY() + lastControlButton.getControlView().getHeight()) {
				return;
			}
		}

		//Release last keys
		if (lastControlButton != null) lastControlButton.sendKeyPresses(false);
		mapTable.remove(v);

		// Update the state of all swipeable buttons
		for (ControlInterface button : getButtonChildren()) {
			if (!button.getProperties().isSwipeable) continue;

			if (ev.getX() > button.getControlView().getX()
					&& ev.getX() < button.getControlView().getX() + button.getControlView().getWidth()
					&& ev.getY() > button.getControlView().getY()
					&& ev.getY() < button.getControlView().getY() + button.getControlView().getHeight()) {

				//Press the new key
				if (!button.equals(lastControlButton)) {
					button.sendKeyPresses(true);
					mapTable.put(v, button);
					return;
				}

			}
		}
	}

	@SuppressLint("ClickableViewAccessibility")
	@Override
	public boolean onTouchEvent(MotionEvent event) {
		if (mModifiable && event.getActionMasked() != MotionEvent.ACTION_UP || mControlDialog == null)
			return true;

		InputMethodManager imm = (InputMethodManager) getContext().getSystemService(INPUT_METHOD_SERVICE);

		// When the input window cannot be hidden, it returns false
		if(!imm.hideSoftInputFromWindow(getWindowToken(), 0)){
			if(mControlDialog.disappearLayer()){
				mActionRow.setFollowedButton(null);
				mHandleView.hide();
			}
		}
		return true;
	}

	public void removeEditWindow() {
		InputMethodManager imm = (InputMethodManager) getContext().getSystemService(INPUT_METHOD_SERVICE);

		// When the input window cannot be hidden, it returns false
		imm.hideSoftInputFromWindow(getWindowToken(), 0);
		if(mControlDialog != null) {
			mControlDialog.disappearColor();
			mControlDialog.disappear(true);
		}

		if(mActionRow != null) mActionRow.setFollowedButton(null);
		if(mHandleView != null) mHandleView.hide();
	}

	public void save(String path){
		try {
			mLayout.save(path);
		} catch (IOException e) {Log.e("ControlLayout", "Failed to save the layout at:" + path);}
	}


	public boolean hasMenuButton() {
		for(ControlInterface controlInterface : getButtonChildren()){
			for (int keycode : controlInterface.getProperties().keycodes) {
				if (keycode == ControlData.SPECIALBTN_MENU) return true;
			}
		}
		return false;
	}

	public void setMenuListener(ControlButtonMenuListener menuListener) {
		this.mMenuListener = menuListener;
	}

	public void notifyAppMenu() {
		if(mMenuListener != null) mMenuListener.onClickedMenu();
	}

	/** Cached getter for perf purposes */
	public MinecraftGLSurface getGameSurface(){
		if(mGameSurface == null){
			mGameSurface = findViewById(R.id.main_game_render_view);
		}
		return mGameSurface;
	}

	/**
	 * Projects fork (CTRL-SAVE): leaving the editor (back, the back gesture, "Düzenleyici'den çık"). Nothing changed:
	 * the editor closes at once (upstream asked "Çıkılsın mı?" even then). Something changed: one question, "Kaydet
	 * ve çık" writes the layout the game reads (upstream's yes / no had no save and dropped every move and resize),
	 * "Kaydetmeden çık" leaves it as it was; the close mark, back or a tap outside go on editing.
	 */
	public void askToExit(EditorExitable editorExitable) {
		if (mSaving) return;
		final EditorExitable exit = mHost != null ? mHost : editorExitable;
		if (EditorRules.leave(mIsModified) == EditorRules.Leave.NOW) {
			exit.exitEditor();
			return;
		}
		Context context = getContext();
		Activity activity = activityOf(context);
		if (activity == null) return; // No window to ask in: the edits stay, nothing is dropped.
		ConfirmDialog.show(activity,
				context.getString(R.string.projects_ctrl_save_question),
				context.getString(R.string.projects_ctrl_save_question_body),
				context.getString(R.string.projects_ctrl_save_leave), false, () -> saveActive(true, null),
				context.getString(R.string.projects_ctrl_leave_unsaved), exit::exitEditor);
	}

	@Nullable
	private static Activity activityOf(Context context) {
		while (context instanceof android.content.ContextWrapper) {
			if (context instanceof Activity) return (Activity) context;
			context = ((android.content.ContextWrapper) context).getBaseContext();
		}
		return null;
	}

	public void updateLoadedFileName(String path) {
		path = path.replace(Tools.CTRLMAP_PATH, ".");
		path = path.substring(0, path.length() - 5);
		mLayoutFileName = path;
	}

	// Projects fork (CTRL-SAVE): upstream's save dialog (a file name, prefilled "./default") is gone. A name typed there
	// wrote a new file while the game kept reading the old one, so the edits seemed lost; "Kaydet" now writes the
	// layout in use (saveActive), and the menu's "Varsayılan kontrolleri seç" still picks which file that is.

	public void openLoadDialog() {
		AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
		builder.setTitle(R.string.global_load);
		builder.setPositiveButton(android.R.string.cancel, null);

		final AlertDialog dialog = builder.create();
		FileListView flv = new FileListView(dialog, "json");
		if(Build.VERSION.SDK_INT < 29)flv.listFileAt(new File(Tools.CTRLMAP_PATH));
		else flv.lockPathAt(new File(Tools.CTRLMAP_PATH));
		flv.setFileSelectedListener(new FileSelectedListener(){

			@Override
			public void onFileSelected(File file, String path) {
				dialog.dismiss();
				// Projects fork (CTRL-SAVE): a change not saved yet is asked about first.
				replaceAfterAsking(() -> {
					try {
						loadLayout(path);
						// Another file's layout in the editor is a change to the layout in use until "Kaydet"
						// writes it there.
						if (EditorRules.loadedAnother(path, activeLayoutPath())) setModified(true);
					}catch (IOException | JsonSyntaxException e) {
						Tools.showError(getContext(), e);
					}
				});
			}
		});
		dialog.setView(flv);
		dialog.show();
	}

	public void openSetDefaultDialog() {
		AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
		builder.setTitle(R.string.customctrl_selectdefault);
		builder.setPositiveButton(android.R.string.cancel, null);

		final AlertDialog dialog = builder.create();
		FileListView flv = new FileListView(dialog, "json");
		flv.lockPathAt(new File(Tools.CTRLMAP_PATH));
		flv.setFileSelectedListener(new FileSelectedListener(){

			@Override
			public void onFileSelected(File file, String path) {
				dialog.dismiss();
				// Projects fork (CTRL-SAVE): a change not saved yet is asked about first (in the game, a profile with
				// a control file of its own would just show that file again and drop the change).
				replaceAfterAsking(() -> {
					try {
						LauncherPreferences.DEFAULT_PREF.edit().putString("defaultCtrl", path).apply();
						LauncherPreferences.PREF_DEFAULTCTRL_PATH = path;
						// The editor shows the layout the game reads now, so "Kaydet" writes what is shown.
						loadLayout(activeLayoutPath());
					}catch (IOException|JsonSyntaxException e) {
						Tools.showError(getContext(), e);
					}
				});
			}
		});
		dialog.setView(flv);
		dialog.show();
	}

	/**
	 * Projects fork (CTRL-SAVE): another layout is about to take the editor's place ("Yükle", "Varsayılan kontrolleri
	 * seç"). With a change not saved, one question first: "Kaydet" writes it and goes on, "Kaydetme" goes on, the
	 * close mark (or back, or a tap outside) keeps everything as it is.
	 */
	private void replaceAfterAsking(final Runnable replace) {
		if (!mIsModified) {
			replace.run();
			return;
		}
		Context context = getContext();
		Activity activity = activityOf(context);
		if (activity == null || mSaving) return; // Nothing is dropped.
		ConfirmDialog.show(activity,
				context.getString(R.string.projects_ctrl_save_question),
				context.getString(R.string.projects_ctrl_replace_body),
				context.getString(R.string.projects_ctrl_save), false,
				// Saved, then replaced: the line still says so, without a "Geri al" for keys no longer shown.
				() -> saveActive(false, () -> {
					replace.run();
					showSaved(null);
				}),
				context.getString(R.string.projects_ctrl_dont_save), replace);
	}

	public boolean areControlVisible(){
		return mControlVisible;
	}
}
