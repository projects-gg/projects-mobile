package gg.projects.mobile.social.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewParent;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;

/**
 * MOB1: a chat message's text. It takes the touch (its links and its hold), and its pressed state goes on to
 * the message's box, whose press feedback (the sink, the tick, the deeper sink of a hold) would otherwise see
 * only touches on the bubble's padding. Android's own timing stays: a scroll that starts on the text never
 * presses the box, and a link's card keeps its own press.
 */
public class ChatBubbleText extends AppCompatTextView {
    public ChatBubbleText(Context context) {
        super(context);
    }

    public ChatBubbleText(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public ChatBubbleText(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public void setPressed(boolean pressed) {
        super.setPressed(pressed);
        ViewParent parent = getParent();
        if (parent instanceof View && ((View) parent).isEnabled()) ((View) parent).setPressed(pressed);
    }
}
