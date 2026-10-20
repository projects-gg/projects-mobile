package gg.projects.mobile.social.ui;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.google.gson.JsonObject;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import net.kdt.pojavlaunch.R;

import java.util.EnumMap;
import java.util.Map;

import gg.projects.mobile.mods.Motion;
import gg.projects.mobile.social.Json;
import gg.projects.mobile.social.SocialCodes;
import gg.projects.mobile.social.SocialModel.Me;
import gg.projects.mobile.social.SocialModel.Profile;
import gg.projects.mobile.social.SocialProblem;
import gg.projects.mobile.social.SocialService;
import gg.projects.mobile.ui.ChamferDrawable;
import gg.projects.mobile.ui.Haptics;
import gg.projects.mobile.ui.Press;

/**
 * "Arkadaş ekle" (M19): the player's own code, to copy, share as a link or show as a QR (a friend's
 * camera opens the link, the landing page opens this app), and a friend's code to find and send a
 * request to. No search: only a code finds a player. A {@code projects://friend/<code>} link opens
 * this screen with the code already looked up.
 */
public class AddFriendFragment extends Fragment implements SocialService.Listener {
    public static final String TAG = "ProjectsAddFriendFragment";
    public static final String ARG_CODE = "code";

    private TextView mMyCode;
    private ImageView mQr;
    private EditText mCode;
    private TextView mMessage;
    private View mResult;
    private AvatarView mResultAvatar;
    private TextView mResultName;
    private TextView mResultLine;
    private Button mSend;
    private Button mFind;
    @Nullable
    private String mQrFor;
    @Nullable
    private String mLookedUp;
    private boolean mBusy;

    public AddFriendFragment() {
        super(R.layout.fragment_projects_social_add);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mMyCode = view.findViewById(R.id.projects_add_my_code);
        mQr = view.findViewById(R.id.projects_add_qr);
        mCode = view.findViewById(R.id.projects_add_code);
        mMessage = view.findViewById(R.id.projects_add_message);
        mResult = view.findViewById(R.id.projects_add_result);
        mResultAvatar = view.findViewById(R.id.projects_add_result_avatar);
        mResultName = view.findViewById(R.id.projects_add_result_name);
        mResultLine = view.findViewById(R.id.projects_add_result_line);
        mSend = view.findViewById(R.id.projects_add_send);
        mFind = view.findViewById(R.id.projects_add_find);
        Button copy = view.findViewById(R.id.projects_add_copy);
        Button share = view.findViewById(R.id.projects_add_share);
        View rotate = view.findViewById(R.id.projects_add_rotate);
        Context context = view.getContext();
        copy.setBackground(ChamferDrawable.outlined(context));
        share.setBackground(ChamferDrawable.outlined(context));
        mSend.setBackground(ChamferDrawable.outlined(context));
        tint(copy);
        tint(share);
        Press.attach(copy, Press.Style.BUTTON);
        Press.attach(share, Press.Style.BUTTON);
        Press.attach(rotate, Press.Style.BUTTON);
        Press.attach(mSend, Press.Style.BUTTON);
        Press.attach(mFind, Press.Style.BUTTON);
        mResultAvatar.setSurfaceColor(ContextCompat.getColor(context, R.color.projects_obsidian_800));

        copy.setOnClickListener(v -> {
            Me me = SocialService.get(requireContext()).state().me();
            if (me == null) return;
            SocialUi.copy(requireContext(), getString(R.string.projects_social_your_code), me.friendCode);
            Haptics.confirm(v);
            SocialUi.toast(requireContext(), getString(R.string.projects_social_copied));
        });
        share.setOnClickListener(v -> {
            SocialService service = SocialService.get(requireContext());
            Me me = service.state().me();
            if (me == null) return;
            String link = SocialCodes.friendShareLink(service.serviceBase(), me.friendCode);
            SocialUi.share(requireActivity(), getString(R.string.projects_social_share_text, link == null ? me.friendCode : link));
        });
        rotate.setOnClickListener(v -> SocialUi.confirm(requireActivity(), getString(R.string.projects_social_rotate),
                getString(R.string.projects_social_rotate_ask), getString(R.string.projects_social_rotate), this::rotate));
        mFind.setOnClickListener(v -> find());
        mCode.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_UP;
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                find();
                return true;
            }
            return false;
        });
        Bundle args = getArguments();
        String code = args == null ? null : args.getString(ARG_CODE);
        if (code != null && savedInstanceState == null) {
            mCode.setText(code);
            view.post(this::find);
        }
    }

    private static void tint(Button button) {
        android.graphics.drawable.Drawable[] d = button.getCompoundDrawablesRelative();
        if (d[0] != null) {
            android.graphics.drawable.Drawable icon = d[0].mutate();
            icon.setTint(ContextCompat.getColor(button.getContext(), R.color.projects_bone_100));
            float dp = button.getResources().getDisplayMetrics().density;
            icon.setBounds(0, 0, Math.round(18 * dp), Math.round(18 * dp));
            button.setCompoundDrawablesRelative(icon, null, null, null);
        }
    }

    @Nullable
    @Override
    public Animator onCreateAnimator(int transit, boolean enter, int nextAnim) {
        View view = getView();
        if (view == null || !Motion.enabled(view.getContext())) return null;
        float distance = 28 * view.getResources().getDisplayMetrics().density;
        AnimatorSet set = new AnimatorSet();
        if (enter) {
            set.playTogether(ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f), ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, distance, 0f));
            set.setDuration(Motion.ENTER_MS);
            set.setInterpolator(Motion.EASE_OUT);
        } else {
            set.playTogether(ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0f), ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, 0f, distance * 0.6f));
            set.setDuration(Motion.EXIT_MS);
            set.setInterpolator(Motion.EASE_IN);
        }
        return set;
    }

    @Override
    public void onResume() {
        super.onResume();
        SocialService service = SocialService.get(requireContext());
        service.addListener(this);
        if (!service.signedIn()) {
            // A friend link opened before signing in: sign in first, on the friends screen.
            requireActivity().onBackPressed();
            return;
        }
        render();
    }

    @Override
    public void onPause() {
        super.onPause();
        SocialService.get(requireContext()).removeListener(this);
    }

    @Override
    public void onSocialChanged() {
        render();
    }

    private void render() {
        if (!isAdded() || getView() == null) return;
        Me me = SocialService.get(requireContext()).state().me();
        String code = me == null ? "" : me.friendCode;
        mMyCode.setText(code);
        if (!code.equals(mQrFor)) {
            mQrFor = code;
            SocialService service = SocialService.get(requireContext());
            String link = SocialCodes.friendShareLink(service.serviceBase(), code);
            mQr.setImageBitmap(link == null ? null : qr(link, Math.round(168 * getResources().getDisplayMetrics().density)));
        }
    }

    /** The share link as a QR: dark modules on the light tile, a small quiet zone (the tile has its own). */
    @Nullable
    static Bitmap qr(String text, int sizePx) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 1);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints);
            int n = matrix.getWidth();
            int scale = Math.max(1, sizePx / n);
            int[] pixels = new int[n * n];
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) pixels[y * n + x] = matrix.get(x, y) ? 0xFF121016 : 0xFFFBF6EC;
            }
            Bitmap small = Bitmap.createBitmap(pixels, n, n, Bitmap.Config.ARGB_8888);
            Bitmap big = Bitmap.createScaledBitmap(small, n * scale, n * scale, false);
            if (big != small) small.recycle();
            return big;
        } catch (WriterException | RuntimeException e) {
            return null;
        }
    }

    private void rotate() {
        final FragmentActivity activity = requireActivity();
        SocialService.get(activity).rotateCode(new SocialService.Result<String>() {
            @Override
            public void ok(String value) {
                SocialUi.toast(activity, activity.getString(R.string.projects_social_rotated));
            }

            @Override
            public void failed(SocialProblem problem) {
                SocialUi.toastProblem(activity, problem, null);
            }
        });
    }

    private void showMessage(@Nullable String text) {
        mMessage.setText(text);
        mMessage.setVisibility(text == null ? View.GONE : View.VISIBLE);
        mCode.setActivated(text != null);
    }

    private void find() {
        if (mBusy || !isAdded()) return;
        final String code = SocialCodes.friendCodeIn(mCode.getText().toString());
        mResult.setVisibility(View.GONE);
        if (code == null) {
            showMessage(getString(R.string.projects_social_invalid_code));
            Haptics.reject(mFind);
            return;
        }
        showMessage(null);
        mCode.setText(code);
        mCode.setSelection(code.length());
        hideKeyboard();
        mBusy = true;
        mFind.setEnabled(false);
        final FragmentActivity activity = requireActivity();
        SocialService.get(activity).lookup(code, new SocialService.Result<JsonObject>() {
            @Override
            public void ok(JsonObject value) {
                mBusy = false;
                if (!isAdded()) return;
                mFind.setEnabled(true);
                showResult(code, value);
            }

            @Override
            public void failed(SocialProblem problem) {
                mBusy = false;
                if (!isAdded()) return;
                mFind.setEnabled(true);
                Haptics.reject(mFind);
                showMessage(SocialUi.problemText(activity, problem));
            }
        });
    }

    private void showResult(final String code, JsonObject answer) {
        Profile user = Profile.read(Json.obj(answer, "user"));
        if (user == null) {
            showMessage(getString(R.string.projects_social_error_no_one));
            return;
        }
        mLookedUp = code;
        String relation = Json.str(answer, "relation", "none");
        mResultAvatar.setProfile(user);
        mResultName.setText(user.name());
        mSend.setVisibility(View.GONE);
        mSend.setEnabled(true);
        String line;
        switch (relation) {
            case "friend": line = getString(R.string.projects_social_rel_friend); break;
            case "outgoing": line = getString(R.string.projects_social_rel_outgoing); break;
            case "blocked": line = getString(R.string.projects_social_rel_blocked); break;
            case "self": line = getString(R.string.projects_social_rel_self); break;
            case "incoming":
                line = getString(R.string.projects_social_wants_to_add);
                mSend.setVisibility(View.VISIBLE);
                mSend.setText(R.string.projects_social_accept);
                mSend.setOnClickListener(v -> accept(user));
                break;
            case "none":
            default:
                line = user.username.isEmpty() || user.username.equals(user.name()) ? "" : "@" + user.username;
                mSend.setVisibility(View.VISIBLE);
                mSend.setText(R.string.projects_social_send);
                mSend.setOnClickListener(v -> send(code));
                break;
        }
        mResultLine.setText(line);
        mResultLine.setVisibility(line.isEmpty() ? View.GONE : View.VISIBLE);
        mResult.setVisibility(View.VISIBLE);
        gg.projects.mobile.ui.CardMotion.fadeIn(mResult);
    }

    private void send(String code) {
        final FragmentActivity activity = requireActivity();
        mSend.setEnabled(false);
        SocialService.get(activity).sendRequest(code, new SocialService.Result<JsonObject>() {
            @Override
            public void ok(JsonObject value) {
                if (!isAdded()) return;
                Haptics.confirm(mSend);
                boolean friends = "friends".equals(Json.str(value, "status"));
                mResultLine.setText(friends ? R.string.projects_social_now_friends : R.string.projects_social_sent_ok);
                mResultLine.setVisibility(View.VISIBLE);
                mSend.setVisibility(View.GONE);
            }

            @Override
            public void failed(SocialProblem problem) {
                if (!isAdded()) return;
                mSend.setEnabled(true);
                SocialUi.toastProblem(activity, problem, mSend);
            }
        });
    }

    private void accept(final Profile user) {
        final FragmentActivity activity = requireActivity();
        mSend.setEnabled(false);
        SocialService.get(activity).accept(user.id, new SocialService.Result<JsonObject>() {
            @Override
            public void ok(JsonObject value) {
                if (!isAdded()) return;
                Haptics.confirm(mSend);
                mResultLine.setText(R.string.projects_social_now_friends);
                mSend.setVisibility(View.GONE);
            }

            @Override
            public void failed(SocialProblem problem) {
                if (!isAdded()) return;
                mSend.setEnabled(true);
                SocialUi.toastProblem(activity, problem, mSend);
            }
        });
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(mCode.getWindowToken(), 0);
    }
}
