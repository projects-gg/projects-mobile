package gg.projects.mobile.cloud.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import net.kdt.pojavlaunch.R;

import java.util.ArrayList;
import java.util.List;

import gg.projects.mobile.cloud.CloudModel;
import gg.projects.mobile.cloud.CloudProblem;
import gg.projects.mobile.cloud.CloudService;
import gg.projects.mobile.cloud.CloudWords;
import gg.projects.mobile.cloud.PlusConfig;
import gg.projects.mobile.ui.Haptics;

/**
 * An AFK+ settings screen (buying and selling, the assistant, working hours, spending limits): it reads the account's
 * AFK+ document, edits a copy in place (fields the screen does not know stay as stored), checks it with the Worker's
 * rules and saves it with the revision it read. A save that meets a newer revision does not overwrite it: the screen
 * offers the newer settings. Leaving with unsaved changes asks first.
 */
abstract class PlusScreen extends CloudScreen {
    @Nullable
    protected CloudModel.PlusDoc mDoc;
    @Nullable
    protected PlusConfig mOriginal;
    @Nullable
    protected PlusConfig mEdit;
    @Nullable
    protected CloudModel.PlusCatalog mCatalog;
    /** What the last check or refusal found: the document's path and a reason key. */
    protected final List<String[]> mProblems = new ArrayList<>();
    protected boolean mSaving;
    private boolean mFailed;
    private boolean mConflict;
    @Nullable
    private SaveBar mBar;

    @Override
    protected final void created(View view) {
        mHeader.setTitle(getString(title()));
        if (mBottom != null) mBar = new SaveBar(view.getContext(), mBottom, () -> {
            if (mDoc != null && !mSaving) begin(mDoc);
        }, this::save);
    }

    protected abstract int title();

    /** Draws the editor from {@link #mEdit} (called when the document arrives, is saved or put back). */
    protected abstract void draw(LinearLayout content);

    /** Whether the screen needs the item catalog (names, the money's limits). */
    protected boolean wantsCatalog() {
        return true;
    }

    @Override
    protected void started() {
        if (mDoc == null) load();
    }

    protected void load() {
        final CloudModel.Account a = account();
        if (a == null) return;
        mFailed = false;
        mService.plus(a.id, new CloudService.Result<CloudModel.PlusDoc>() {
            @Override
            public void ok(CloudModel.PlusDoc doc) {
                if (!alive()) return;
                if (wantsCatalog() && doc.profileId != null) catalog(doc.profileId);
                begin(doc);
            }

            @Override
            public void failed(CloudProblem problem) {
                if (!alive()) return;
                mFailed = true;
                render();
            }
        });
    }

    private void catalog(String profile) {
        CloudModel.PlusCatalog cached = mService.cachedCatalog(profile);
        if (cached != null) {
            mCatalog = cached;
            return;
        }
        mService.catalog(profile, new CloudService.Result<CloudModel.PlusCatalog>() {
            @Override
            public void ok(CloudModel.PlusCatalog value) {
                mCatalog = value;
                if (alive() && mEdit != null && !dirty()) redraw();
            }

            @Override
            public void failed(CloudProblem problem) {}
        });
    }

    protected void begin(CloudModel.PlusDoc doc) {
        mDoc = doc;
        mOriginal = new PlusConfig(doc.config);
        mEdit = mOriginal.copy();
        mProblems.clear();
        mConflict = false;
        redraw();
        bar();
    }

    @Override
    protected void render() {
        CloudModel.Account a = account();
        if (a != null) mHeader.setCaption(a.name);
        if (mEdit != null) return;
        LinearLayout content = clear();
        if (content == null) return;
        Context c = ctx();
        if (mFailed) {
            content.addView(CloudUi.empty(c, gg.projects.mobile.ui.PixelSprites.Sprite.FAILED, getString(R.string.projects_plus_load_failed)));
            content.addView(CloudUi.textButton(c, getString(R.string.projects_cloud_retry), v -> {
                load();
                render();
            }), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            content.addView(CloudUi.loading(c));
        }
    }

    protected final void redraw() {
        LinearLayout content = clear();
        if (content == null || mEdit == null) return;
        if (mConflict) {
            Context c = ctx();
            content.addView(CloudUi.notice(c, R.drawable.projects_ic_warning_circle, R.color.projects_gold_300,
                    getString(R.string.projects_cloud_settings_conflict)));
            content.addView(CloudUi.textButton(c, getString(R.string.projects_cloud_settings_load_latest), v -> {
                mDoc = null;
                mEdit = null;
                render();
                load();
            }), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        draw(content);
    }

    protected boolean dirty() {
        return mEdit != null && mOriginal != null && !mEdit.same(mOriginal);
    }

    /** After an edit: the bar shows while something differs from what is stored. */
    protected void changed() {
        bar();
    }

    protected void bar() {
        if (mBar == null) return;
        boolean dirty = dirty();
        holdBack(dirty);
        mBar.set(dirty || mSaving, mSaving, getString(R.string.projects_cloud_settings_dirty));
    }

    /** The words for a problem at {@code field}, or null when there is none there. */
    @Nullable
    protected String problemAt(String field) {
        for (String[] p : mProblems) {
            if (p[0].equals(field) || p[0].startsWith(field + ".") || p[0].startsWith(field + "[")) return words(p[0], p[1]);
        }
        return null;
    }

    protected String words(String field, String reason) {
        Context c = ctx();
        switch (reason) {
            case "order": return getString(field.startsWith("sell") ? R.string.projects_plus_problem_order_sell : R.string.projects_plus_problem_order_buy);
            case "item": return getString(R.string.projects_plus_problem_item);
            case "limit": return getString(R.string.projects_plus_problem_limit, mCatalog == null ? PlusConfig.LIST_MAX : Math.min(PlusConfig.LIST_MAX, mCatalog.listMax));
            case "limits": return getString(R.string.projects_plus_limit_order);
            case "name": return getString(R.string.projects_cloud_issue_name);
            case "text": return getString(R.string.projects_plus_text_length, field.endsWith("intro") ? PlusConfig.INTRO_MAX : PlusConfig.STATUS_MAX);
            case "range": return getString(R.string.projects_plus_problem_range);
            default: {
                String judged = CloudWords.judgeText(c, reason);
                return judged != null ? judged : getString(R.string.projects_cloud_issue_other);
            }
        }
    }

    protected void save() {
        final CloudModel.Account a = account();
        final CloudModel.PlusDoc doc = mDoc;
        final PlusConfig edit = mEdit;
        if (a == null || doc == null || edit == null || mSaving) return;
        mProblems.clear();
        for (PlusConfig.Problem p : edit.check(mCatalog, mService.nowSeconds())) mProblems.add(new String[]{p.field, p.reason});
        if (!mProblems.isEmpty()) {
            Haptics.reject(mBar == null ? null : mBar.saveButton());
            redraw();
            toast(getString(R.string.projects_cloud_settings_fix_first));
            return;
        }
        mSaving = true;
        bar();
        mService.putPlus(a.id, doc.aiRev, edit.toJson(), new CloudService.Result<CloudModel.PlusDoc>() {
            @Override
            public void ok(CloudModel.PlusDoc saved) {
                mSaving = false;
                if (!alive()) return;
                Haptics.confirm(mBar == null ? null : mBar.saveButton());
                toast(getString(R.string.projects_plus_saved));
                begin(saved);
            }

            @Override
            public void failed(CloudProblem problem) {
                mSaving = false;
                if (!alive()) return;
                if (problem.kind == CloudProblem.Kind.CONFLICT) {
                    mConflict = true;
                    redraw();
                    bar();
                    return;
                }
                String field = problem.field();
                if (problem.kind == CloudProblem.Kind.REFUSED && field != null) {
                    String reason = problem.reason();
                    mProblems.add(new String[]{field, reason == null ? "range" : reason});
                    redraw();
                    bar();
                    Haptics.reject(mBar == null ? null : mBar.saveButton());
                    toast(getString(R.string.projects_cloud_settings_fix_first));
                    return;
                }
                bar();
                problem(problem, mBar == null ? null : mBar.saveButton());
            }
        });
    }

    @Override
    protected void onBack() {
        if (!dirty()) {
            leaveNow();
            return;
        }
        new AlertDialog.Builder(requireActivity())
                .setTitle(R.string.projects_cloud_discard_title)
                .setMessage(R.string.projects_cloud_discard_body)
                .setPositiveButton(R.string.projects_cloud_discard_confirm, (d, w) -> leaveNow())
                .setNegativeButton(R.string.projects_cloud_discard_keep, null)
                .show();
    }

    public String itemName(String id) {
        if (mCatalog != null) return mCatalog.itemName(id, lang());
        return mService.itemName(id, lang());
    }
}
