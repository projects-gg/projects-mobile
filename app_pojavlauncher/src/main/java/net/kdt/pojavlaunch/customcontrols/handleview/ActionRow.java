package net.kdt.pojavlaunch.customcontrols.handleview;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.customcontrols.ControlLayout;
import net.kdt.pojavlaunch.customcontrols.buttons.ControlInterface;

import gg.projects.mobile.controls.EditorBar;

/**
 * Layout floating around a Control Button, displaying contextual actions
 *
 * <p>Projects fork (MOB-NEXT, the lead's decision 2 Nov): the row never covers a key. While the key's panel is open the
 * row lives in the panel's header (an embedded row, {@link EditControlSideDialog}) and the floating one stays away; with
 * the panel closed the floating row docks at the free place nearest the key, under it first, never over a key or an
 * open drawer's keys ({@link ControlLayout#actionRowSpot}), and stays away when no place is free. Upstream put it over
 * the key, on the keys above it.
 */
public class ActionRow extends LinearLayout {

    public static final int SIDE_LEFT = 0x0;
    public static final int SIDE_TOP = 0x1;
    public static final int SIDE_RIGHT = 0x2;
    public static final int SIDE_BOTTOM = 0x3;
    public static final int SIDE_AUTO = 0x4;

    /** In the key panel's header: it neither follows nor docks. */
    private final boolean mEmbedded;

    public ActionRow(Context context) {
        this(context, false);
    }
    public ActionRow(Context context, boolean embedded) {
        super(context); mEmbedded = embedded; init();
    }
    public ActionRow(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs); mEmbedded = false; init();
    }

    public final ViewTreeObserver.OnPreDrawListener mFollowedViewListener = new ViewTreeObserver.OnPreDrawListener() {
        @Override
        public boolean onPreDraw() {
            if(mFollowedView == null || !mFollowedView.isShown()){
                hide();
                return true;
            }

            if (!mEmbedded) setNewPosition();
            return true;
        }
    };
    private final ActionButtonInterface[] actionButtons = new ActionButtonInterface[3];
    private View mFollowedView = null;

    /** Add action buttons and configure them */
    private void init(){
        setTranslationZ(11);
        setVisibility(GONE);
        setOrientation(HORIZONTAL);
        setLayoutParams(new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                getResources().getDimensionPixelOffset(R.dimen._40sdp)
        ));

        actionButtons[0] = new DeleteButton(getContext());
        actionButtons[1] = new CloneButton(getContext());
        actionButtons[2] = new AddSubButton(getContext());

        // This is not pretty code, don't do this.
        float dp = getResources().getDisplayMetrics().density;
        for(int i = 0; i < actionButtons.length; i++){
            View button = ((View)(actionButtons[i]));
            // Projects fork (MOB-NEXT, TASTE-2's second look): in the editor bar's dress (the keys' dark, a bone
            // hairline, chamfered, Figtree as written), each as wide as its words and 6 dp apart; upstream's were the
            // platform's grey capitals in equal thirds (equal shares of the words' widths cut "Kopyala" in two).
            if (button instanceof Button) EditorBar.dressAction((Button) button);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
            if (i > 0) params.setMarginStart(Math.round(6 * dp));
            addView(button, params);
        }

        setElevation(5F);
    }

    public void setFollowedButton(ControlInterface controlInterface){
        if(mFollowedView != null)
            mFollowedView.getViewTreeObserver().removeOnPreDrawListener(mFollowedViewListener);

        for(ActionButtonInterface buttonInterface: actionButtons){
            buttonInterface.setFollowedView(controlInterface);
            ((View)(buttonInterface)).setVisibility(buttonInterface.shouldBeVisible() ? VISIBLE : GONE);
        }

        mFollowedView = (View) controlInterface;
        if (mFollowedView == null) {
            setVisibility(GONE);
            return;
        }
        // A floating row shows once it has a place (its first frame decides); the panel's row at once.
        setVisibility(mEmbedded ? VISIBLE : INVISIBLE);
        mFollowedView.getViewTreeObserver().addOnPreDrawListener(mFollowedViewListener);
    }

    /** The floating row: at the free place nearest the key while the key's panel is closed, else away. */
    private void setNewPosition(){
        if(mFollowedView == null) return;
        float[] spot = null;
        if (getParent() instanceof ControlLayout) {
            ControlLayout layout = (ControlLayout) getParent();
            if (!layout.keyPanelWanted()) spot = layout.actionRowSpot(this, mFollowedView);
        }
        if (spot == null) {
            if (getVisibility() == VISIBLE) setVisibility(INVISIBLE);
            return;
        }
        setX(spot[0]);
        setY(spot[1]);
        if (getVisibility() != VISIBLE) setVisibility(VISIBLE);
    }

    public void hide(){
        if(mFollowedView != null)
            mFollowedView.getViewTreeObserver().removeOnPreDrawListener(mFollowedViewListener);
        setVisibility(GONE);
    }
}
