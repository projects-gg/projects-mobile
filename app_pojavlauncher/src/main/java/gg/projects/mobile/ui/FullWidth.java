package gg.projects.mobile.ui;

/**
 * TABLET-M: a launcher screen that lays a wide window out itself (the home's columns, the chats beside a
 * conversation). Every other screen keeps to {@link WideRoot}'s centred column.
 */
public interface FullWidth {
    /** True while the screen uses the window's whole width (it may depend on the layout it has now). */
    boolean fillsWindow();
}
