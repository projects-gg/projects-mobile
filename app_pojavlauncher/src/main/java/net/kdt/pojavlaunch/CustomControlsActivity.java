package net.kdt.pojavlaunch;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import androidx.drawerlayout.widget.DrawerLayout;

import net.kdt.pojavlaunch.customcontrols.ControlData;
import net.kdt.pojavlaunch.customcontrols.ControlDrawerData;
import net.kdt.pojavlaunch.customcontrols.ControlJoystickData;
import net.kdt.pojavlaunch.customcontrols.ControlLayout;
import net.kdt.pojavlaunch.customcontrols.CustomControls;
import net.kdt.pojavlaunch.customcontrols.EditorExitable;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import java.io.File;
import java.io.IOException;

import gg.projects.mobile.controls.EditorHost;
import gg.projects.mobile.controls.LayoutBackups;
import gg.projects.mobile.controls.SavedLayoutNotice;


public class CustomControlsActivity extends BaseActivity implements EditorExitable, EditorHost {
	/** Projects fork (CTRL-SAVE): unsaved keys kept while Android stops the app in the background. */
	private static final String STATE_UNSAVED = "projects_ctrl_unsaved";

	private DrawerLayout mDrawerLayout;
	private ListView mDrawerNavigationView;
	private ControlLayout mControlLayout;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		setContentView(R.layout.activity_custom_controls);

		mControlLayout = findViewById(R.id.customctrl_controllayout);
		mDrawerLayout = findViewById(R.id.customctrl_drawerlayout);
		mDrawerNavigationView = findViewById(R.id.customctrl_navigation_view);
		// Projects fork (CTRL-SAVE): the editor's "Menü" (EditorBar) opens the menu; upstream's faint cog is gone.
		mDrawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);

		mDrawerNavigationView.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1,getResources().getStringArray(R.array.menu_customcontrol_customactivity)));
		mDrawerNavigationView.setOnItemClickListener((parent, view, position, id) -> {
			switch(position) {
				case 0: mControlLayout.addControlButton(new ControlData("New")); break;
				case 1: mControlLayout.addDrawer(new ControlDrawerData()); break;
				case 2: mControlLayout.addJoystickButton(new ControlJoystickData()); break;
				case 3: mControlLayout.openLoadDialog(); break;
				// Projects fork (CTRL-SAVE): "Kaydet" writes the layout in use (no file name to type).
				case 4: mControlLayout.saveActive(false, null); break;
				case 5: mControlLayout.openSetDefaultDialog(); break;
				case 6: // Sharing the layout in use (saved first when it holds a change)
					mControlLayout.saveIfChanged(this::shareLayout);
					break;
			}
			mDrawerLayout.closeDrawers();
		});
		mControlLayout.setEditorHost(this);
		mControlLayout.setModifiable(true);
		String unsaved = savedInstanceState == null ? null : savedInstanceState.getString(STATE_UNSAVED);
		if (unsaved != null && restoreUnsaved(unsaved)) return;
		try {
			mControlLayout.loadLayout(LauncherPreferences.PREF_DEFAULTCTRL_PATH);
		}catch (IOException e) {
			Tools.showError(this, e);
		}
	}

	/** Projects fork (CTRL-SAVE): the keys of an editor Android stopped in the background, still to be saved. */
	private boolean restoreUnsaved(String json) {
		try {
			CustomControls layout = Tools.GLOBAL_GSON.fromJson(json, CustomControls.class);
			if (layout == null) return false;
			mControlLayout.loadLayout(layout);
			mControlLayout.setModified(true);
			return true;
		} catch (RuntimeException e) {
			Log.w("CustomControlsActivity", "unsaved layout not restored", e);
			return false;
		}
	}

	@Override
	protected void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);
		if (mControlLayout != null && mControlLayout.isModified() && mControlLayout.getLayout() != null) {
			outState.putString(STATE_UNSAVED, mControlLayout.getLayout().toJson());
		}
	}

	private void shareLayout() {
		try {
			String path = activeLayoutPath();
			Uri contentUri = DocumentsContract.buildDocumentUri(getString(R.string.storageProviderAuthorities), path);

			Intent shareIntent = new Intent();
			shareIntent.setAction(Intent.ACTION_SEND);
			shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
			shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
			shareIntent.setType("application/json");
			// Projects fork (CTRL-SAVE): one share sheet (upstream opened the share target and a chooser both).
			startActivity(Intent.createChooser(shareIntent, new File(path).getName()));
		}catch (Exception e) {
			Tools.showError(this, e);
		}
	}

	@Override
	public void onBackPressed() {
		// Projects fork (CTRL-SAVE): back closes the open menu first; leaving asks only when something changed.
		if (mDrawerLayout.isDrawerOpen(mDrawerNavigationView)) {
			mDrawerLayout.closeDrawers();
			return;
		}
		mControlLayout.askToExit(this);
	}

	/**
	 * TABLET-M: a resized window (a split screen, a free window) or a turn keeps the editor (configChanges): the keys
	 * take their places on the new size, as in the game (MainActivity), once the new size is laid out.
	 */
	@Override
	public void onConfigurationChanged(@androidx.annotation.NonNull android.content.res.Configuration newConfig) {
		super.onConfigurationChanged(newConfig);
		mControlLayout.requestLayout();
		mControlLayout.post(() -> {
			Tools.updateWindowSize(this);
			mControlLayout.refreshControlButtonPositions();
		});
	}

	@Override
	public void exitEditor() {
		super.onBackPressed();
	}

	/** Projects fork (CTRL-SAVE): the launcher's editor edits the default layout, as upstream's did. */
	@Override
	public String activeLayoutPath() {
		return LauncherPreferences.PREF_DEFAULTCTRL_PATH;
	}

	@Override
	public void openEditorMenu() {
		mDrawerLayout.openDrawer(mDrawerNavigationView);
	}

	/** Projects fork (CTRL-SAVE): the screen the player returns to offers "Geri al" (LauncherActivity). */
	@Override
	public void editorSavedAndLeft(LayoutBackups.Saved saved) {
		SavedLayoutNotice.offerOnReturn(saved);
	}
}
