package gg.projects.mobile.social.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import net.kdt.pojavlaunch.R;

import java.util.HashMap;
import java.util.Map;

import gg.projects.mobile.social.CrewLook;

/**
 * A crew's mark, as the desktop draws it (team-emblem.tsx): the emblem (a Phosphor icon, fill weight)
 * on its frame, the frame filled with the crew's colour under a faint light from above (an enamel
 * badge, not a glow) with an edge that thins as the mark grows. Crews from before looks show gold and
 * the cut frame; an emblem this version does not know is a shield.
 */
public final class CrewMarkView extends View {
    private static final Map<String, Path> FRAMES = new HashMap<>();

    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLight = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();
    private final Matrix mMatrix = new Matrix();
    private String mColor = CrewLook.DEFAULT_COLOR;
    private String mFrame = CrewLook.DEFAULT_FRAME;
    private String mEmblem = "shield";
    @Nullable
    private Drawable mGlyph;
    private float mLastSize = -1;

    public CrewMarkView(Context context) {
        this(context, null);
    }

    public CrewMarkView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mEdge.setStyle(Paint.Style.STROKE);
        mEdge.setStrokeJoin(Paint.Join.ROUND);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        apply();
    }

    public void setLook(@Nullable String emblem, @Nullable String color, @Nullable String frame) {
        String e = CrewLook.emblem(emblem);
        String c = CrewLook.color(color);
        String f = CrewLook.frame(frame);
        if (e.equals(mEmblem) && c.equals(mColor) && f.equals(mFrame) && mGlyph != null) return;
        mEmblem = e;
        mColor = c;
        mFrame = f;
        apply();
    }

    private void apply() {
        CrewLook.Colors colors = CrewLook.colors(mColor);
        mFill.setColor(colors.fill);
        mEdge.setColor(colors.edge);
        mGlyph = emblemDrawable(getContext(), mEmblem);
        if (mGlyph != null) mGlyph.setTint(colors.glyph);
        mLastSize = -1;
        invalidate();
    }

    @Nullable
    public static Drawable emblemDrawable(Context context, String emblem) {
        int id = context.getResources().getIdentifier("projects_emblem_" + CrewLook.emblem(emblem), "drawable", context.getPackageName());
        if (id == 0) id = R.drawable.projects_emblem_shield;
        Drawable d = ContextCompat.getDrawable(context, id);
        return d == null ? null : d.mutate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float size = Math.min(getWidth() - getPaddingLeft() - getPaddingRight(), getHeight() - getPaddingTop() - getPaddingBottom());
        if (size <= 0) return;
        float left = getPaddingLeft() + (getWidth() - getPaddingLeft() - getPaddingRight() - size) / 2f;
        float top = getPaddingTop() + (getHeight() - getPaddingTop() - getPaddingBottom() - size) / 2f;
        float unit = size / 40f;
        if (size != mLastSize) {
            mLastSize = size;
            mMatrix.setScale(unit, unit);
            mPath.set(frame(mFrame));
            mPath.transform(mMatrix);
            mLight.setShader(new LinearGradient(0, 0, 0, size, 0x24FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            float dp = getResources().getDisplayMetrics().density;
            mEdge.setStrokeWidth(CrewLook.edgeWidth(size / dp) * unit);
        }
        canvas.save();
        canvas.translate(left, top);
        canvas.drawPath(mPath, mFill);
        canvas.drawPath(mPath, mLight);
        canvas.drawPath(mPath, mEdge);
        canvas.restore();
        if (mGlyph != null) {
            int glyph = Math.round(size * 0.48f);
            float cy = top + CrewLook.shape(mFrame).glyphY * unit;
            int gl = Math.round(left + (size - glyph) / 2f);
            int gt = Math.round(cy - glyph / 2f);
            mGlyph.setBounds(gl, gt, gl + glyph, gt + glyph);
            mGlyph.draw(canvas);
        }
    }

    /** The frame's outline in its 40 × 40 box (parsed once per frame). */
    static Path frame(String frame) {
        Path path = FRAMES.get(frame);
        if (path == null) {
            path = parse(CrewLook.shape(frame).path);
            FRAMES.put(frame, path);
        }
        return path;
    }

    /** The absolute M, H, V, L, C and Z commands the frames use. */
    static Path parse(String d) {
        Path path = new Path();
        String[] tokens = d.replaceAll("([MHVLCZ])", " $1 ").trim().split("[\\s,]+");
        float x = 0, y = 0;
        char command = 'M';
        int i = 0;
        while (i < tokens.length) {
            String token = tokens[i];
            if (token.length() == 1 && Character.isLetter(token.charAt(0))) {
                command = token.charAt(0);
                i++;
                if (command == 'Z') {
                    path.close();
                    continue;
                }
            }
            switch (command) {
                case 'M':
                    x = Float.parseFloat(tokens[i++]);
                    y = Float.parseFloat(tokens[i++]);
                    path.moveTo(x, y);
                    command = 'L';
                    break;
                case 'L':
                    x = Float.parseFloat(tokens[i++]);
                    y = Float.parseFloat(tokens[i++]);
                    path.lineTo(x, y);
                    break;
                case 'H':
                    x = Float.parseFloat(tokens[i++]);
                    path.lineTo(x, y);
                    break;
                case 'V':
                    y = Float.parseFloat(tokens[i++]);
                    path.lineTo(x, y);
                    break;
                case 'C': {
                    float x1 = Float.parseFloat(tokens[i++]);
                    float y1 = Float.parseFloat(tokens[i++]);
                    float x2 = Float.parseFloat(tokens[i++]);
                    float y2 = Float.parseFloat(tokens[i++]);
                    x = Float.parseFloat(tokens[i++]);
                    y = Float.parseFloat(tokens[i++]);
                    path.cubicTo(x1, y1, x2, y2, x, y);
                    break;
                }
                default:
                    i++;
                    break;
            }
        }
        return path;
    }
}
