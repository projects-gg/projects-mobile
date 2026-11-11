package gg.projects.mobile.accounts;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.gson.JsonObject;

import net.kdt.pojavlaunch.R;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import gg.projects.mobile.cloud.ui.CloudUi;
import gg.projects.mobile.content.AtomicFiles;
import gg.projects.mobile.social.SocialModel;
import gg.projects.mobile.social.SocialProblem;
import gg.projects.mobile.social.SocialService;
import gg.projects.mobile.social.ui.SocialUi;
import gg.projects.mobile.ui.AppDates;
import gg.projects.mobile.ui.ConfirmDialog;
import gg.projects.mobile.ui.ScreenHeader;

/**
 * M053 (SKIN1 on the phone): "Görünüm", a player-name account's Projects skin, the desktop's Görünüm panel
 * ({@code features/projects-skin}): the Discord link (the 6-digit code from {@code /discord eşle} in the game, the link's
 * name, removing it), then the skin: a PNG from the phone or a premium player's skin, the arm model, a head preview,
 * "Kaydet" and "Varsayılana dön". Every change is a job the Projects server applies; the screen hears its end on the
 * presence socket ({@code mc}) and asks for it itself only while the socket is down (3 s for 30 s, then 10 s, at most
 * 5 minutes, only while the screen is open). Opened from the account list's hold menu while the signed config has
 * {@code skins}; the service's own switches say the rest ("Projects skini şu an kapalı.").
 */
public final class ProjectsSkinFragment extends Fragment implements ProjectsSkinHub.Listener {
    public static final String TAG = "ProjectsSkinFragment";
    public static final String ARG_ACCOUNT = "account";
    private static final String LINK_COMMAND = "/discord eşle";
    private static final String UNLINK_COMMAND = "/discord eşlemekaldır";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final long POLL_FAST_MS = 3_000;
    private static final long POLL_FAST_FOR_MS = 30_000;
    private static final long POLL_SLOW_MS = 10_000;
    private static final long POLL_FOR_MS = 5 * 60_000;
    private static final long LONG_WAIT_MS = 60_000;

    private enum Body { LOADING, CONNECT, SESSION, DISCORD, OFF, UNREACHABLE, EDITOR }

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private LinearLayout mContent;
    private String mAccount = "";
    private Body mBody = Body.LOADING;
    @Nullable private ProjectsSkinState.State mState;
    private boolean mChecked;
    // The code box
    private boolean mLinking;
    @Nullable private String mLinkNote;
    // The draft
    private int mSource;
    @Nullable private SkinPng.Result mPng;
    @Nullable private String mFileNote;
    private boolean mSlim;
    private String mTypedName = "";
    @Nullable private MojangSkinLookup.Result mPremium;
    @Nullable private String mNameNote;
    private boolean mLooking;
    // The current skin's picture
    @Nullable private int[] mCurrentArgb;
    @Nullable private String mCurrentFor;
    // A job of ours that runs
    @Nullable private String mJob;
    private long mJobSince;
    private boolean mSending;
    @Nullable private String mNote;
    private int mToken;

    private final ActivityResultLauncher<String[]> mPick = registerForActivityResult(new ActivityResultContracts.OpenDocument(),
            this::onPicked);

    private final Runnable mPoll = this::poll;

    public ProjectsSkinFragment() {
        super(R.layout.fragment_projects_afk_page);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Bundle args = getArguments();
        mAccount = args == null || args.getString(ARG_ACCOUNT) == null ? "" : args.getString(ARG_ACCOUNT);
        ScreenHeader header = view.findViewById(R.id.projects_afk_header);
        header.setTitle(getString(R.string.projects_skin_title));
        header.setCaption(mAccount + " · " + getString(R.string.projects_skin_subtitle));
        mContent = view.findViewById(R.id.projects_afk_content);
        render();
        load();
    }

    @Override
    public void onResume() {
        super.onResume();
        ProjectsSkinHub.add(this);
        if (mJob != null) schedulePoll();
    }

    @Override
    public void onPause() {
        super.onPause();
        ProjectsSkinHub.remove(this);
        mMain.removeCallbacks(mPoll);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mToken++;
        mMain.removeCallbacksAndMessages(null);
    }

    // ---- loading ----

    private void load() {
        Context c = requireContext();
        SocialService service = SocialService.get(c);
        if (!service.signedIn()) {
            mBody = Body.CONNECT;
            render();
            return;
        }
        SocialModel.Me me = service.state().me();
        if (me != null && !me.profile.hasDiscord()) {
            mBody = Body.DISCORD;
            render();
            return;
        }
        final int token = ++mToken;
        IO.execute(() -> {
            ProjectsSkinState.State state = null;
            ProjectsSkinState.Refusal refused = null;
            try {
                state = ProjectsSkinState.State.read(service.api().mcState());
            } catch (SocialProblem problem) {
                refused = refusal(problem);
            } catch (RuntimeException e) {
                refused = ProjectsSkinState.Refusal.UNREACHABLE;
            }
            final ProjectsSkinState.State got = state;
            final ProjectsSkinState.Refusal why = refused;
            mMain.post(() -> {
                if (token != mToken || !isAdded()) return;
                if (got == null) {
                    mBody = why == ProjectsSkinState.Refusal.SESSION || why == ProjectsSkinState.Refusal.RESTRICTED ? Body.SESSION
                            : why == ProjectsSkinState.Refusal.OFF ? Body.OFF : Body.UNREACHABLE;
                    render();
                    return;
                }
                onState(got);
            });
        });
    }

    private void onState(ProjectsSkinState.State state) {
        mState = state;
        if (!state.skinsOn()) mBody = Body.OFF;
        else if (!state.botHealthy) mBody = Body.UNREACHABLE;
        else mBody = Body.EDITOR;
        // A job of ours that was open when the screen came back.
        if (mJob == null) {
            ProjectsSkinState.Job open = state.openJob("skin");
            if (open == null) open = state.openJob("link");
            if (open != null) follow(open.id);
        }
        if (mBody == Body.EDITOR && !mChecked && ProjectsSkinState.needsCheck(state, System.currentTimeMillis() / 1000)) {
            mChecked = true;
            check();
        }
        loadCurrentPicture();
        render();
    }

    private void check() {
        final SocialService service = SocialService.get(requireContext());
        IO.execute(() -> {
            try {
                JsonObject answer = service.api().mcCheck();
                ProjectsSkinState.Job job = ProjectsSkinState.Job.read(ProjectsSkinState.obj(answer, "job"));
                mMain.post(() -> {
                    if (!isAdded()) return;
                    if (job != null) follow(job.id);
                    else load();
                });
            } catch (SocialProblem | RuntimeException ignored) {
                // The link stays as it was read.
            }
        });
    }

    /** The current Projects skin's picture (the service names its texture on Mojang's host). */
    private void loadCurrentPicture() {
        ProjectsSkinState.State s = mState;
        final String texture = s == null || s.skin == null || s.skin.isDefault() ? null : s.skin.texture;
        if (texture == null) {
            mCurrentArgb = null;
            mCurrentFor = null;
            return;
        }
        if (texture.equals(mCurrentFor)) return;
        mCurrentFor = texture;
        final int token = mToken;
        IO.execute(() -> {
            byte[] png = MojangSkinLookup.texturePng(texture);
            final int[] argb = png == null ? null : decode(png, null);
            mMain.post(() -> {
                if (token != mToken || !isAdded() || !texture.equals(mCurrentFor)) return;
                mCurrentArgb = argb;
                render();
            });
        });
    }

    // ---- the socket and the job ----

    @Override
    public void onMcFrame(JsonObject frame) {
        if (mState != null) mState = mState.withFrame(frame);
        ProjectsSkinState.Job job = ProjectsSkinState.Job.read(ProjectsSkinState.obj(frame, "job"));
        if (job != null && job.id.equals(mJob)) {
            finished(job);
            return;
        }
        if (job == null) {
            loadCurrentPicture();
            render();
        }
    }

    private void follow(String id) {
        mJob = id;
        mJobSince = System.currentTimeMillis();
        schedulePoll();
    }

    private void schedulePoll() {
        mMain.removeCallbacks(mPoll);
        if (mJob == null) return;
        long open = System.currentTimeMillis() - mJobSince;
        if (open > POLL_FOR_MS) return;
        mMain.postDelayed(mPoll, open < POLL_FAST_FOR_MS ? POLL_FAST_MS : POLL_SLOW_MS);
    }

    private void poll() {
        final String id = mJob;
        if (id == null || !isAdded()) return;
        long open = System.currentTimeMillis() - mJobSince;
        if (open > LONG_WAIT_MS && mNote == null && mSending) {
            mNote = getString(R.string.projects_skin_long_wait);
            render();
        }
        final SocialService service = SocialService.get(requireContext());
        if (service.socketLive()) {
            // The socket brings the end; only look again later in case it drops.
            schedulePoll();
            return;
        }
        IO.execute(() -> {
            ProjectsSkinState.Job job = null;
            try {
                job = ProjectsSkinState.Job.read(ProjectsSkinState.obj(service.api().mcJob(id), "job"));
            } catch (SocialProblem | RuntimeException ignored) {
                // Asked again at the next turn.
            }
            final ProjectsSkinState.Job got = job;
            mMain.post(() -> {
                if (!isAdded() || !id.equals(mJob)) return;
                if (got != null && !got.open()) finished(got);
                else {
                    if (got != null && got.queued() && mSending) {
                        mNote = getString(R.string.projects_skin_server_away);
                        render();
                    }
                    schedulePoll();
                }
            });
        });
    }

    private void finished(ProjectsSkinState.Job job) {
        mMain.removeCallbacks(mPoll);
        mJob = null;
        mSending = false;
        mLinking = false;
        Context c = requireContext();
        String code = job.resultCode == null ? "" : job.resultCode;
        switch (job.kind) {
            case "skin":
                if ("applied".equals(code)) {
                    boolean back = mPng == null && mPremium == null;
                    clearDraft();
                    mNote = null;
                    SocialUi.toastLong(c, getString(back ? R.string.projects_skin_saved_default : R.string.projects_skin_saved) + ". "
                            + getString(R.string.projects_skin_rejoin));
                } else if (!"superseded".equals(code) && !"cancelled".equals(code)) {
                    mNote = skinResultText(job);
                }
                break;
            case "link":
                if ("linked".equals(code)) {
                    mLinkNote = null;
                    SocialUi.toast(c, getString(R.string.projects_skin_linked, job.name == null ? mAccount : job.name));
                } else {
                    mLinkNote = linkResultText(code);
                }
                break;
            case "unlink":
                if ("unlinked".equals(code)) SocialUi.toast(c, getString(R.string.projects_skin_unlinked));
                else mLinkNote = getString(R.string.projects_skin_link_failed);
                break;
            default:
                break;
        }
        load();
    }

    private String skinResultText(ProjectsSkinState.Job job) {
        String code = job.resultCode == null ? "" : job.resultCode;
        switch (code) {
            case "not_linked":
            case "other_account": return getString(R.string.projects_skin_link_title);
            case "skin_blocked":
                return job.resultUntil == null || job.resultUntil == 0 ? getString(R.string.projects_skin_blocked_forever)
                        : getString(R.string.projects_skin_blocked, AppDates.longDate(requireContext(), job.resultUntil * 1000L));
            case "premium_invalid": return getString(R.string.projects_skin_premium_invalid);
            case "premium_disabled": return getString(R.string.projects_skin_premium_disabled);
            case "invalid_image": return getString(R.string.projects_skin_invalid_image);
            case "mineskin_busy": return getString(R.string.projects_skin_busy);
            default: return getString(R.string.projects_skin_save_failed);
        }
    }

    private String linkResultText(String code) {
        switch (code) {
            case "pin_invalid":
            case "other_account": return getString(R.string.projects_skin_pin_invalid);
            case "pin_expired": return getString(R.string.projects_skin_pin_expired);
            case "minecraft_taken": return getString(R.string.projects_skin_taken, UNLINK_COMMAND);
            default: return getString(R.string.projects_skin_link_failed);
        }
    }

    private static ProjectsSkinState.Refusal refusal(SocialProblem p) {
        return ProjectsSkinState.refusal(p.status, p.code, p.item, p.reason);
    }

    // ---- actions ----

    private void sendPin(String pin) {
        if (mLinking || pin.length() != 6) return;
        mLinking = true;
        mLinkNote = null;
        render();
        final SocialService service = SocialService.get(requireContext());
        IO.execute(() -> {
            String jobId = null;
            ProjectsSkinState.Refusal why = null;
            try {
                ProjectsSkinState.Job job = ProjectsSkinState.Job.read(ProjectsSkinState.obj(service.api().mcLink(mAccount, pin), "job"));
                jobId = job == null ? null : job.id;
            } catch (SocialProblem problem) {
                why = refusal(problem);
            } catch (RuntimeException e) {
                why = ProjectsSkinState.Refusal.UNREACHABLE;
            }
            final String id = jobId;
            final ProjectsSkinState.Refusal refused = why;
            mMain.post(() -> {
                if (!isAdded()) return;
                if (id != null) {
                    follow(id);
                    render();
                    return;
                }
                mLinking = false;
                if (refused == ProjectsSkinState.Refusal.ALREADY_LINKED) {
                    load();
                    return;
                }
                mLinkNote = refused == ProjectsSkinState.Refusal.INVALID_PIN ? getString(R.string.projects_skin_pin_invalid)
                        : refused == ProjectsSkinState.Refusal.TRIES ? getString(R.string.projects_skin_tries)
                        : refused == ProjectsSkinState.Refusal.OTHER_ACCOUNT ? getString(R.string.projects_skin_pin_invalid)
                        : getString(R.string.projects_skin_link_failed);
                render();
            });
        });
    }

    private void unlink(boolean thenLinkThis) {
        final SocialService service = SocialService.get(requireContext());
        IO.execute(() -> {
            String jobId = null;
            try {
                ProjectsSkinState.Job job = ProjectsSkinState.Job.read(ProjectsSkinState.obj(service.api().mcUnlink(), "job"));
                jobId = job == null ? null : job.id;
            } catch (SocialProblem | RuntimeException ignored) {
                // Said below.
            }
            final String id = jobId;
            mMain.post(() -> {
                if (!isAdded()) return;
                if (id == null) {
                    mLinkNote = getString(R.string.projects_skin_link_failed);
                    render();
                    return;
                }
                follow(id);
                render();
            });
        });
    }

    private void save(boolean toDefault) {
        if (mSending) return;
        final JsonObject source = new JsonObject();
        if (toDefault) {
            source.addProperty("kind", "default");
        } else if (mSource == 0 && mPng != null && mPng.ok()) {
            source.addProperty("kind", "png");
            source.addProperty("variant", mSlim && !mPng.legacy ? "slim" : "classic");
            source.addProperty("png", android.util.Base64.encodeToString(mPng.png, android.util.Base64.NO_WRAP));
            source.addProperty("sha256", mPng.sha256);
        } else if (mSource == 1 && mPremium != null && mPremium.kind == MojangSkinLookup.Kind.FOUND) {
            JsonObject premium = new JsonObject();
            premium.addProperty("name", mPremium.name);
            premium.addProperty("id", mPremium.id);
            premium.addProperty("texture", mPremium.texture);
            premium.addProperty("variant", mPremium.slim ? "slim" : "classic");
            premium.addProperty("value", mPremium.value);
            premium.addProperty("signature", mPremium.signature);
            source.addProperty("kind", "premium");
            source.add("premium", premium);
        } else {
            return;
        }
        mSending = true;
        mNote = null;
        render();
        final SocialService service = SocialService.get(requireContext());
        IO.execute(() -> {
            String jobId = null;
            ProjectsSkinState.Refusal why = null;
            try {
                ProjectsSkinState.Job job = ProjectsSkinState.Job.read(ProjectsSkinState.obj(service.api().mcSkin(mAccount, source), "job"));
                jobId = job == null ? null : job.id;
            } catch (SocialProblem problem) {
                why = refusal(problem);
            } catch (RuntimeException e) {
                why = ProjectsSkinState.Refusal.UNREACHABLE;
            }
            final String id = jobId;
            final ProjectsSkinState.Refusal refused = why;
            mMain.post(() -> {
                if (!isAdded()) return;
                if (id != null) {
                    follow(id);
                    render();
                    return;
                }
                mSending = false;
                mNote = refusalText(refused);
                render();
                if (refused == ProjectsSkinState.Refusal.NOT_LINKED || refused == ProjectsSkinState.Refusal.OTHER_ACCOUNT) load();
            });
        });
    }

    private String refusalText(@Nullable ProjectsSkinState.Refusal refused) {
        if (refused == null) return getString(R.string.projects_skin_save_failed);
        switch (refused) {
            case QUOTA: return getString(R.string.projects_skin_quota);
            case PENDING: return getString(R.string.projects_skin_pending);
            case INVALID_SKIN: return getString(R.string.projects_skin_invalid_image);
            case RESTRICTED: return getString(R.string.projects_skin_restricted);
            case BLOCKED: return getString(R.string.projects_skin_blocked_forever);
            case OFF: return getString(R.string.projects_skin_off);
            case NOT_LINKED:
            case OTHER_ACCOUNT: return getString(R.string.projects_skin_link_title);
            case UNREACHABLE: return getString(R.string.projects_skin_unreachable);
            default: return getString(R.string.projects_skin_save_failed);
        }
    }

    private void clearDraft() {
        mPng = null;
        mFileNote = null;
        mPremium = null;
        mNameNote = null;
    }

    private void onPicked(@Nullable Uri uri) {
        if (uri == null || !isAdded()) return;
        final Context app = requireContext().getApplicationContext();
        final int token = mToken;
        IO.execute(() -> {
            SkinPng.Result result;
            try (InputStream in = app.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("no stream");
                byte[] bytes = AtomicFiles.readBounded(in, SkinPng.MAX_FILE);
                result = read(bytes);
            } catch (IOException e) {
                result = null;
            }
            final SkinPng.Result got = result;
            mMain.post(() -> {
                if (token != mToken || !isAdded()) return;
                mPremium = null;
                if (got == null) {
                    mPng = null;
                    mFileNote = getString(R.string.projects_skin_file_read_failed);
                } else if (!got.ok()) {
                    mPng = null;
                    mFileNote = fileProblem(got.problem);
                } else {
                    mPng = got;
                    mSlim = SkinPng.looksSlim(got.argb, got.legacy);
                    mFileNote = got.legacy ? getString(R.string.projects_skin_file_legacy)
                            : got.blackParts ? getString(R.string.projects_skin_file_black) : getString(R.string.projects_skin_file_pending);
                }
                render();
            });
        });
    }

    /** A picked file's bytes as the service's PNG (size first, then the pixels as they are, not premultiplied). */
    private static SkinPng.Result read(byte[] bytes) {
        if (bytes.length >= SkinPng.MAX_FILE) return SkinPng.Result.failed(SkinPng.Problem.TOO_BIG);
        if (!SkinPng.looksLikePng(bytes)) return SkinPng.Result.failed(SkinPng.Problem.NOT_PNG);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0) return SkinPng.Result.failed(SkinPng.Problem.UNREADABLE);
        if (!SkinPng.sizeFits(bounds.outWidth, bounds.outHeight)) return SkinPng.Result.failed(SkinPng.Problem.DIMENSIONS);
        int[] size = new int[2];
        int[] argb = decode(bytes, size);
        if (argb == null) return SkinPng.Result.failed(SkinPng.Problem.UNREADABLE);
        return SkinPng.clean(size[0], size[1], argb);
    }

    /** Non-premultiplied ARGB of a PNG ({@code size}: width and height out), or null. */
    @Nullable
    private static int[] decode(byte[] png, @Nullable int[] size) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPremultiplied = false;
        options.inScaled = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap = BitmapFactory.decodeByteArray(png, 0, png.length, options);
        if (bitmap == null) return null;
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int[] argb = new int[w * h];
        bitmap.getPixels(argb, 0, w, 0, 0, w, h);
        bitmap.recycle();
        if (size != null) {
            size[0] = w;
            size[1] = h;
        }
        if (size == null && !(w == 64 && (h == 64 || h == 32))) return null;
        return argb;
    }

    private String fileProblem(@Nullable SkinPng.Problem problem) {
        if (problem == null) return "";
        switch (problem) {
            case NOT_PNG: return getString(R.string.projects_skin_file_not_png);
            case DIMENSIONS: return getString(R.string.projects_skin_file_dimensions);
            case TOO_BIG: return getString(R.string.projects_skin_file_too_big);
            case EMPTY: return getString(R.string.projects_skin_file_empty);
            case UNREADABLE:
            default: return getString(R.string.projects_skin_file_read_failed);
        }
    }

    private void lookUp(final String name) {
        mTypedName = name.trim();
        if (mLooking || mTypedName.isEmpty()) return;
        mPng = null;
        mFileNote = null;
        if (!ProjectsSkinState.premiumName(mTypedName)) {
            mPremium = null;
            mNameNote = getString(R.string.projects_skin_name_no_account);
            render();
            return;
        }
        mLooking = true;
        mNameNote = getString(R.string.projects_skin_name_looking);
        render();
        final int token = mToken;
        final String asked = mTypedName;
        IO.execute(() -> {
            MojangSkinLookup.Result result = MojangSkinLookup.lookUp(asked);
            mMain.post(() -> {
                if (token != mToken || !isAdded()) return;
                mLooking = false;
                switch (result.kind) {
                    case FOUND:
                        mPremium = result;
                        mNameNote = getString(R.string.projects_skin_name_found, result.name);
                        break;
                    case NO_ACCOUNT:
                        mPremium = null;
                        mNameNote = getString(R.string.projects_skin_name_no_account);
                        break;
                    case NO_SKIN:
                        mPremium = null;
                        mNameNote = getString(R.string.projects_skin_name_no_skin);
                        break;
                    default:
                        mPremium = null;
                        mNameNote = getString(R.string.projects_skin_name_unreachable);
                        break;
                }
                render();
            });
        });
    }

    // ---- drawing ----

    private void render() {
        if (mContent == null || !isAdded()) return;
        Context c = requireContext();
        mContent.removeAllViews();
        switch (mBody) {
            case LOADING:
                mContent.addView(CloudUi.loading(c));
                return;
            case CONNECT:
                mContent.addView(CloudUi.notice(c, R.drawable.projects_ic_info, R.color.projects_ash_300,
                        getString(R.string.projects_skin_connect_title)));
                mContent.addView(centered(c, CloudUi.discordButton(c, getString(R.string.projects_social_sign_in),
                        v -> SocialService.get(requireContext()).beginSignIn(requireActivity()))));
                return;
            case SESSION:
                mContent.addView(CloudUi.notice(c, R.drawable.projects_ic_info, R.color.projects_ash_300, getString(R.string.projects_skin_session)));
                mContent.addView(centered(c, CloudUi.discordButton(c, getString(R.string.projects_social_sign_in),
                        v -> SocialService.get(requireContext()).beginSignIn(requireActivity()))));
                return;
            case DISCORD:
                mContent.addView(CloudUi.notice(c, R.drawable.projects_ic_info, R.color.projects_ash_300,
                        getString(R.string.projects_skin_needs_discord)));
                return;
            case OFF:
                mContent.addView(CloudUi.notice(c, R.drawable.projects_ic_info, R.color.projects_ash_300, getString(R.string.projects_skin_off)));
                return;
            case UNREACHABLE:
                mContent.addView(CloudUi.notice(c, R.drawable.projects_ic_warning, R.color.projects_gold_300,
                        getString(R.string.projects_skin_unreachable)));
                mContent.addView(centered(c, CloudUi.button(c, getString(R.string.projects_retry), false, v -> {
                    mBody = Body.LOADING;
                    render();
                    load();
                })));
                return;
            case EDITOR:
            default:
                editor(c);
        }
    }

    private void editor(Context c) {
        ProjectsSkinState.State s = mState;
        if (s == null) return;
        ProjectsSkinState.LinkArea area = ProjectsSkinState.linkArea(s, mAccount);
        mContent.addView(CloudUi.section(c, getString(R.string.projects_skin_link_label)));
        switch (area) {
            case CHECKING:
                mContent.addView(CloudUi.note(c, getString(R.string.projects_skin_link_checking)));
                return;
            case CODE:
                codeBox(c, s);
                return;
            case OTHER:
                mContent.addView(CloudUi.note(c, getString(R.string.projects_skin_link_other, s.link.name)));
                Button move = CloudUi.textButton(c, getString(R.string.projects_skin_link_this), v -> ConfirmDialog.show(requireActivity(),
                        getString(R.string.projects_skin_link_move_title, s.link.name), getString(R.string.projects_skin_link_remove_body),
                        getString(R.string.projects_skin_link_remove_confirm), true, () -> unlink(true)));
                mContent.addView(start(c, move));
                if (mLinkNote != null) mContent.addView(CloudUi.note(c, mLinkNote));
                return;
            case LINKED:
            default:
                break;
        }
        // Linked to this account: the link's line, then the skin.
        LinearLayout linkRow = new LinearLayout(c);
        linkRow.setOrientation(LinearLayout.HORIZONTAL);
        linkRow.setGravity(Gravity.CENTER_VERTICAL);
        // M054 (M053's P3): the section already says "Discord eşlemesi": the line is the linked name alone.
        TextView linked = CloudUi.text(c, s.link.name, 14, R.color.projects_bone_100, false);
        linkRow.addView(linked, CloudUi.weight(1f));
        linkRow.addView(CloudUi.textButton(c, getString(R.string.projects_skin_link_remove), v -> ConfirmDialog.show(requireActivity(),
                getString(R.string.projects_skin_link_remove_title), getString(R.string.projects_skin_link_remove_body),
                getString(R.string.projects_skin_link_remove_confirm), true, () -> unlink(false))));
        linkRow.setPadding(CloudUi.dp(c, 8), 0, 0, 0);
        mContent.addView(linkRow, CloudUi.matchWrap());
        if (mLinkNote != null) mContent.addView(CloudUi.note(c, mLinkNote));

        // M054 (M053's P3): "Skin", not the screen's title again.
        mContent.addView(CloudUi.section(c, getString(R.string.projects_skin_section)));
        mContent.addView(preview(c, s));

        CharSequence[] sources = {getString(R.string.projects_skin_source_png), getString(R.string.projects_skin_source_name)};
        LinearLayout sourceRow = CloudUi.segmented(c, sources, mSource, index -> {
            if (index == mSource) return;
            mSource = index;
            render();
        });
        LinearLayout.LayoutParams sourceLp = CloudUi.matchWrap();
        sourceLp.topMargin = CloudUi.dp(c, 12);
        mContent.addView(sourceRow, sourceLp);

        if (mSource == 0) {
            TextView help = CloudUi.note(c, getString(R.string.projects_skin_file_help));
            mContent.addView(help);
            mContent.addView(start(c, CloudUi.button(c, getString(R.string.projects_skin_file_choose), false,
                    v -> mPick.launch(new String[]{"image/png"}))));
            if (mFileNote != null) mContent.addView(CloudUi.note(c, mFileNote));
            if (mPng != null && mPng.ok() && !mPng.legacy) {
                mContent.addView(CloudUi.section(c, getString(R.string.projects_skin_model_title)));
                CharSequence[] models = {getString(R.string.projects_skin_model_classic), getString(R.string.projects_skin_model_slim)};
                mContent.addView(CloudUi.segmented(c, models, mSlim ? 1 : 0, index -> {
                    mSlim = index == 1;
                    render();
                }));
            }
        } else {
            final CloudUi.Field field = CloudUi.field(c, getString(R.string.projects_skin_name_label), mTypedName, null,
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            field.input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(16)});
            field.input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
            field.input.setOnEditorActionListener((v, action, event) -> {
                if (action != EditorInfo.IME_ACTION_SEARCH) return false;
                lookUp(field.value());
                return true;
            });
            mContent.addView(field.view);
            mContent.addView(start(c, CloudUi.button(c, getString(R.string.projects_skin_name_find), false, v -> lookUp(field.value()))));
            if (mNameNote != null) mContent.addView(CloudUi.note(c, mNameNote));
        }

        // What holds a change back, said where it acts.
        long now = System.currentTimeMillis() / 1000;
        ProjectsSkinState.Hold hold = ProjectsSkinState.hold(s, now);
        String holdText = null;
        if (hold == ProjectsSkinState.Hold.BLOCKED) {
            holdText = s.blockedUntil == 0 ? getString(R.string.projects_skin_blocked_forever)
                    : getString(R.string.projects_skin_blocked, AppDates.longDate(c, s.blockedUntil * 1000L));
        } else if (hold == ProjectsSkinState.Hold.LIMITED) {
            int days = (int) Math.max(1, (s.limitedUntil - now + 86_399) / 86_400);
            holdText = getResources().getQuantityString(R.plurals.projects_skin_limited, days, days);
        } else if (hold == ProjectsSkinState.Hold.QUOTA) {
            holdText = getString(R.string.projects_skin_quota);
        } else if (hold == ProjectsSkinState.Hold.PENDING && !mSending) {
            holdText = getString(R.string.projects_skin_pending);
        } else if (s.skinsLeft == 1 && s.skinsPerDay > 1) {
            holdText = getString(R.string.projects_skin_last_change);
        }
        if (mNote != null) mContent.addView(inColumn(c, CloudUi.notice(c, R.drawable.projects_ic_info, R.color.projects_ash_300, mNote)));
        else if (holdText != null) mContent.addView(inColumn(c, CloudUi.notice(c, R.drawable.projects_ic_info, R.color.projects_ash_300, holdText)));

        boolean draft = (mSource == 0 && mPng != null && mPng.ok()) || (mSource == 1 && mPremium != null
                && mPremium.kind == MojangSkinLookup.Kind.FOUND);
        boolean free = hold == null || (hold == ProjectsSkinState.Hold.PENDING && mSending);
        LinearLayout actions = new LinearLayout(c);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        if (s.skin != null && !s.skin.isDefault()) {
            Button reset = CloudUi.textButton(c, getString(R.string.projects_skin_reset), v -> save(true));
            reset.setEnabled(free && !mSending);
            reset.setAlpha(free && !mSending ? 1f : 0.5f);
            actions.addView(reset);
        }
        Button save = CloudUi.button(c, getString(mSending ? R.string.projects_skin_sending : R.string.projects_skin_save), true, v -> save(false));
        save.setEnabled(draft && free && !mSending);
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        saveLp.setMarginStart(CloudUi.dp(c, 8));
        actions.addView(save, saveLp);
        LinearLayout.LayoutParams actionsLp = CloudUi.matchWrap();
        actionsLp.topMargin = CloudUi.dp(c, 16);
        actionsLp.setMarginEnd(CloudUi.dp(c, COLUMN_DP));
        mContent.addView(actions, actionsLp);
    }

    /**
     * M054 (M053's P3): the text column's edge (sections and notes are padded 8 dp): panels and buttons line up with it
     * instead of standing out of it.
     */
    private static final int COLUMN_DP = 8;

    private static View inColumn(Context c, View panel) {
        LinearLayout.LayoutParams lp = panel.getLayoutParams() instanceof LinearLayout.LayoutParams
                ? (LinearLayout.LayoutParams) panel.getLayoutParams() : CloudUi.matchWrap();
        lp.setMarginStart(CloudUi.dp(c, COLUMN_DP));
        lp.setMarginEnd(CloudUi.dp(c, COLUMN_DP));
        panel.setLayoutParams(lp);
        return panel;
    }

    /** The 6-digit code box: sends at the sixth digit; pasted text keeps its digits. */
    private void codeBox(Context c, ProjectsSkinState.State s) {
        // M054 (M053's P3): on the column, as the section above it.
        TextView title = CloudUi.text(c, getString(R.string.projects_skin_link_title_head), 15, R.color.projects_bone_100, true);
        title.setPadding(CloudUi.dp(c, COLUMN_DP), 0, CloudUi.dp(c, COLUMN_DP), 0);
        mContent.addView(title);
        TextView body = CloudUi.note(c, getString(R.string.projects_skin_link_body, LINK_COMMAND));
        mContent.addView(body);
        final CloudUi.Field field = CloudUi.field(c, getString(R.string.projects_skin_link_code), null, "000000",
                InputType.TYPE_CLASS_NUMBER);
        field.label.setVisibility(View.GONE);
        field.input.setContentDescription(getString(R.string.projects_skin_link_code));
        field.input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(12)});
        field.input.setLetterSpacing(0.3f);
        field.input.setGravity(Gravity.CENTER);
        field.input.setEnabled(!mLinking && s.linkOn());
        field.input.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence t, int a, int b, int d) {}

            @Override
            public void onTextChanged(CharSequence t, int a, int b, int d) {}

            @Override
            public void afterTextChanged(android.text.Editable e) {
                String digits = ProjectsSkinState.pinDigits(e);
                if (!digits.contentEquals(e)) {
                    e.replace(0, e.length(), digits);
                    return;
                }
                if (digits.length() == 6) sendPin(digits);
            }
        });
        mContent.addView(field.view);
        if (mLinking) mContent.addView(CloudUi.note(c, getString(R.string.projects_skin_linking)));
        else if (!s.linkOn()) mContent.addView(CloudUi.note(c, getString(R.string.projects_skin_link_failed)));
        else if (mLinkNote != null) {
            field.error.setText(mLinkNote);
            field.error.setVisibility(View.VISIBLE);
        }
    }

    /** The head of the skin on show: the draft ("Önizleme") or the Projects skin ("Projects'te"), pixel-sharp. */
    private View preview(Context c, ProjectsSkinState.State s) {
        int[] argb = null;
        boolean draft = false;
        if (mSource == 0 && mPng != null && mPng.ok()) {
            argb = mPng.argb;
            draft = true;
        } else if (mSource == 1 && mPremium != null && mPremium.png != null) {
            argb = decode(mPremium.png, null);
            draft = argb != null;
        }
        if (argb == null) argb = mCurrentArgb;
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        int size = CloudUi.dp(c, 72);
        ImageView head = new ImageView(c);
        head.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int[] face = argb == null ? null : SkinFace.face(argb, 64, argb.length / 64);
        if (face != null) {
            int n = SkinFace.faceSize(64);
            Bitmap bitmap = Bitmap.createBitmap(face, n, n, Bitmap.Config.ARGB_8888);
            BitmapDrawable drawable = new BitmapDrawable(getResources(), bitmap);
            drawable.setFilterBitmap(false);
            drawable.setAntiAlias(false);
            head.setImageDrawable(drawable);
            head.setScaleType(ImageView.ScaleType.FIT_XY);
        } else {
            head.setImageDrawable(new gg.projects.mobile.ui.HeadPlaceholderDrawable(c, size, CloudUi.dp(c, 8)));
        }
        GradientDrawable plate = new GradientDrawable();
        plate.setCornerRadius(CloudUi.dp(c, 10));
        plate.setColor(CloudUi.color(c, R.color.projects_obsidian_800));
        FrameLayout frame = new FrameLayout(c);
        frame.setBackground(plate);
        int pad = CloudUi.dp(c, 10);
        frame.setPadding(pad, pad, pad, pad);
        frame.addView(head, new FrameLayout.LayoutParams(size, size));
        box.addView(frame);
        LinearLayout words = new LinearLayout(c);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(CloudUi.text(c, getString(draft ? R.string.projects_skin_chip_preview : R.string.projects_skin_chip_projects), 14,
                R.color.projects_bone_100, true));
        words.addView(CloudUi.text(c, mAccount, 13, R.color.projects_ash_300, false));
        LinearLayout.LayoutParams wordsLp = CloudUi.weight(1f);
        wordsLp.setMarginStart(CloudUi.dp(c, 14));
        box.addView(words, wordsLp);
        LinearLayout.LayoutParams lp = CloudUi.matchWrap();
        lp.topMargin = CloudUi.dp(c, 4);
        box.setLayoutParams(lp);
        box.setPadding(CloudUi.dp(c, 8), 0, CloudUi.dp(c, 8), 0);
        return box;
    }

    private static View centered(Context c, View child) {
        FrameLayout box = new FrameLayout(c);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.topMargin = CloudUi.dp(c, 16);
        box.addView(child, lp);
        box.setLayoutParams(CloudUi.matchWrap());
        return box;
    }

    private static View start(Context c, View child) {
        FrameLayout box = new FrameLayout(c);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.START;
        lp.topMargin = CloudUi.dp(c, 8);
        lp.setMarginStart(CloudUi.dp(c, COLUMN_DP));
        box.addView(child, lp);
        box.setLayoutParams(CloudUi.matchWrap());
        return box;
    }
}
