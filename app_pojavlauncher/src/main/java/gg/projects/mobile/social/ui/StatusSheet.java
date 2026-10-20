package gg.projects.mobile.social.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.fragment.app.FragmentActivity;

import com.google.gson.JsonObject;

import net.kdt.pojavlaunch.R;

import gg.projects.mobile.social.SocialModel;
import gg.projects.mobile.social.SocialModel.Me;
import gg.projects.mobile.social.SocialProblem;
import gg.projects.mobile.social.SocialService;
import gg.projects.mobile.ui.Haptics;
import gg.projects.mobile.ui.Press;

/**
 * "Durumunu değiştir": Çevrimiçi, Rahatsız etmeyin (nothing pops up, friends see it), Görünmez
 * (offline to everyone). The choice is the account's: every device follows it.
 */
public final class StatusSheet {
    private StatusSheet() {}

    public static void show(final FragmentActivity activity) {
        final SocialService service = SocialService.get(activity);
        Me me = service.state().me();
        if (me == null) return;
        final String current = me.status;
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        float dp = activity.getResources().getDisplayMetrics().density;
        list.setPadding(Math.round(12 * dp), Math.round(8 * dp), Math.round(12 * dp), 0);
        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.projects_social_status_change)
                .setView(list)
                .setNegativeButton(R.string.projects_close, null)
                .create();
        String[] statuses = {SocialModel.Status.ONLINE, SocialModel.Status.DND, SocialModel.Status.INVISIBLE};
        for (final String status : statuses) {
            View row = row(activity, status, status.equals(current));
            row.setOnClickListener(v -> {
                dialog.dismiss();
                if (status.equals(current)) return;
                service.setStatus(status, new SocialService.Result<JsonObject>() {
                    @Override
                    public void ok(JsonObject value) {
                        Haptics.confirm(v);
                    }

                    @Override
                    public void failed(SocialProblem problem) {
                        SocialUi.toast(activity, activity.getString(R.string.projects_social_status_failed));
                    }
                });
            });
            list.addView(row);
        }
        dialog.show();
    }

    private static View row(Context context, String status, boolean selected) {
        float dp = context.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Math.round(56 * dp));
        row.setPaddingRelative(Math.round(8 * dp), 0, Math.round(8 * dp), 0);
        row.setBackgroundResource(R.drawable.projects_row_ripple);
        row.setClickable(true);
        row.setFocusable(true);
        StatusDot glyph = new StatusDot(context, SocialUi.statusDot(status));
        row.addView(glyph, new LinearLayout.LayoutParams(Math.round(24 * dp), Math.round(24 * dp)));
        TextView label = new TextView(context);
        label.setText(SocialUi.statusLabel(status));
        label.setTextSize(16);
        label.setTextColor(ContextCompat.getColor(context, R.color.projects_bone_100));
        try {
            label.setTypeface(ResourcesCompat.getFont(context, selected ? R.font.figtree_semibold : R.font.figtree_regular));
        } catch (RuntimeException ignored) {
            // The theme's font.
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginStart(Math.round(14 * dp));
        row.addView(label, lp);
        if (selected) {
            ImageView check = new ImageView(context);
            check.setImageResource(R.drawable.projects_ic_check);
            check.setImageTintList(ContextCompat.getColorStateList(context, R.color.projects_gold_400));
            row.addView(check, new LinearLayout.LayoutParams(Math.round(22 * dp), Math.round(22 * dp)));
        }
        row.setSelected(selected);
        Press.attach(row, Press.Style.ROW);
        return row;
    }

    /** The presence dot alone (the desktop's PresenceDot), for the status choices. */
    static final class StatusDot extends View {
        private final android.graphics.Paint mPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final AvatarView.Dot mKind;

        StatusDot(Context context, AvatarView.Dot kind) {
            super(context);
            mKind = kind;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            float dp = getResources().getDisplayMetrics().density;
            float r = 7 * dp;
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            int jade = ContextCompat.getColor(getContext(), R.color.projects_jade_400);
            mPaint.setStyle(android.graphics.Paint.Style.FILL);
            switch (mKind) {
                case DND:
                    mPaint.setColor(ContextCompat.getColor(getContext(), R.color.projects_gold_400));
                    canvas.drawCircle(cx, cy, r, mPaint);
                    mPaint.setColor(ContextCompat.getColor(getContext(), R.color.projects_obsidian_950));
                    canvas.drawRoundRect(cx - r / 2f - dp, cy - dp, cx + r / 2f + dp, cy + dp, dp, dp, mPaint);
                    break;
                case INVISIBLE:
                    mPaint.setStyle(android.graphics.Paint.Style.STROKE);
                    mPaint.setStrokeWidth(2 * dp);
                    mPaint.setColor(ContextCompat.getColor(getContext(), R.color.projects_ash_400));
                    canvas.drawCircle(cx, cy, r - dp, mPaint);
                    break;
                default:
                    mPaint.setColor(jade);
                    canvas.drawCircle(cx, cy, r, mPaint);
                    break;
            }
        }
    }
}
