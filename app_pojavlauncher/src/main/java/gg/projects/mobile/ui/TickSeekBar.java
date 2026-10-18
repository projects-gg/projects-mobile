package gg.projects.mobile.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.SeekBar;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatSeekBar;

/**
 * Every inflated {@code <SeekBar>} (M14): a light tick for each step the player drags it by
 * ({@link Haptics#tick}, at most one every 35 ms), a press when the drag starts. Changes made in
 * code play nothing. The listener the screen sets is wrapped, not replaced.
 */
public class TickSeekBar extends AppCompatSeekBar {
    public TickSeekBar(Context context) {
        this(context, null);
    }

    public TickSeekBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOnSeekBarChangeListener(null);
    }

    @Override
    public void setOnSeekBarChangeListener(@Nullable final OnSeekBarChangeListener listener) {
        super.setOnSeekBarChangeListener(new OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) Haptics.tick(seekBar);
                if (listener != null) listener.onProgressChanged(seekBar, progress, fromUser);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                Haptics.rowPress(seekBar);
                if (listener != null) listener.onStartTrackingTouch(seekBar);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (listener != null) listener.onStopTrackingTouch(seekBar);
            }
        });
    }
}
