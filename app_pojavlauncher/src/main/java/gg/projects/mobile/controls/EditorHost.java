package gg.projects.mobile.controls;

import net.kdt.pojavlaunch.customcontrols.EditorExitable;

/**
 * CTRL-SAVE (1 Nov 2026): the screen the controls editor runs in (the launcher's {@code CustomControlsActivity}, or
 * the game's {@code MainActivity} with its editor mode).
 */
public interface EditorHost extends EditorExitable {
    /** The layout file the game reads now: "Kaydet" writes it, "Geri al" brings back its version before. */
    String activeLayoutPath();

    /** Opens the editor's menu (the drawer with "Düğme ekle", "Yükle", ...). */
    void openEditorMenu();

    /** The editor closed after "Kaydet ve çık": "Geri al" is offered wherever the player is now. */
    void editorSavedAndLeft(LayoutBackups.Saved saved);

    /** "Geri al" put another layout on screen (in the game, the menu's pull button follows its MENÜ key). */
    void editorLayoutReloaded();

    /**
     * MOB-NEXT: true for the game's editor, which opens on the keys of the screen the game showed (its menus' keys when a
     * menu was open, {@link EditorRules#firstScene}); the launcher's editor opens on play's keys.
     */
    default boolean editsInGame() {
        return false;
    }
}
