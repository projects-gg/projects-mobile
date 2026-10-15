package gg.projects.mobile.join;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.WorkerThread;

import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.modloaders.ModloaderDownloadListener;
import net.kdt.pojavlaunch.modloaders.modpacks.api.ModLoader;
import net.kdt.pojavlaunch.modloaders.modpacks.api.NotificationDownloadListener;
import net.kdt.pojavlaunch.tasks.AsyncMinecraftDownloader;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import gg.projects.mobile.ProjectsContent;
import gg.projects.mobile.content.ContentException;
import gg.projects.mobile.content.PackDownloader;
import gg.projects.mobile.content.PackManifest;
import gg.projects.mobile.content.PackState;
import gg.projects.mobile.content.PackSyncPlanner;
import gg.projects.mobile.content.RootIndex;
import gg.projects.mobile.content.Snapshot;
import gg.projects.mobile.launch.QuickPlay;
import gg.projects.mobile.servers.ServersDat;

/**
 * "Projects'e katıl": makes sure the Projects profile exists and is up to date, adds the server
 * to its servers.dat and hands over to the normal launch flow (which auto-joins the server for
 * this profile, see ProjectsLaunchHook).
 *
 * <p>With an official pack in the signed index the profile follows that pack (files synced with
 * the desktop rules, loader installed); without one it is vanilla on the newest release, pinned
 * when created. Account rules are upstream's: installing a pack needs an online account, exactly
 * like upstream's own modpack installers; launching goes through the regular Play checks.
 */
public final class ProjectsJoin {
    private ProjectsJoin() {}

    private static final String TAG = "ProjectsJoin";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean sBusy = new AtomicBoolean(false);

    public interface Callback {
        /** The profile is ready: select it and start the game. */
        void onReady(String profileKey);

        /** Installing the pack needs an online account (upstream's rule for modpacks). */
        void onNeedsOnlineAccount();

        /** Forge/NeoForge packs need the installer GUI that upstream starts from a notification. */
        void onFinishLoaderInstall();

        void onFailed(boolean network, String details);
    }

    public static boolean isBusy() {
        return sBusy.get();
    }

    /** Starts the join flow; returns false when one is already running. */
    @MainThread
    public static boolean start(Context context, final Callback callback) {
        if (!sBusy.compareAndSet(false, true)) return false;
        final Context app = context.getApplicationContext();
        PojavApplication.sExecutorService.execute(() -> {
            Runnable outcome;
            try {
                outcome = prepare(app, callback);
            } catch (ContentException e) {
                Log.w(TAG, "join failed", e);
                final boolean network = e.kind == ContentException.Kind.NETWORK;
                final String details = details(e);
                outcome = () -> callback.onFailed(network, details);
            } catch (IOException e) {
                Log.w(TAG, "join failed", e);
                final String details = details(e);
                outcome = () -> callback.onFailed(true, details);
            } catch (Throwable e) {
                Log.e(TAG, "join failed", e);
                final String details = details(e);
                outcome = () -> callback.onFailed(false, details);
            }
            // Clear our progress before the callback runs: Play refuses to start while tasks run.
            ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
            sBusy.set(false);
            post(outcome);
        });
        return true;
    }

    @WorkerThread
    private static Runnable prepare(Context app, final Callback callback) throws ContentException, IOException {
        ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 0, R.string.projects_join_preparing);
        Snapshot snapshot = ProjectsContent.latestBlocking(app);
        RootIndex.PackRef pack = snapshot.officialPack();
        if (pack != null) {
            // Upstream lets only online accounts install modpacks; the official pack is one.
            if (!Tools.hasOnlineProfile()) return callback::onNeedsOnlineAccount;
            if (!installPack(app, snapshot, pack)) return callback::onFinishLoaderInstall;
        } else {
            ensureVanilla();
        }
        return () -> callback.onReady(ProjectsProfile.KEY);
    }

    @WorkerThread
    private static void ensureVanilla() throws IOException {
        String version = null;
        if (ProjectsProfile.find() == null) {
            // Pin the newest release at creation, like the desktop launcher. Without the version
            // list (offline) upstream resolves "latest-release" at launch instead.
            version = AsyncMinecraftDownloader.normalizeVersionId(MinecraftProfile.LATEST_RELEASE);
        }
        MinecraftProfile profile = ProjectsProfile.ensure(version);
        addServers(ProjectsProfile.gameDir(profile), new ArrayList<PackManifest.PackServer>());
    }

    /** @return false when the mod loader still needs upstream's GUI installer */
    @WorkerThread
    private static boolean installPack(Context app, Snapshot snapshot, RootIndex.PackRef pack) throws ContentException, IOException {
        PackManifest manifest = ProjectsContent.repository(app).packManifest(snapshot, pack);
        ModLoader loader = loaderOf(manifest);
        String versionId = loader != null ? loader.getVersionId() : manifest.minecraftVersion();
        MinecraftProfile profile = ProjectsProfile.ensure(versionId);
        File gameDir = ProjectsProfile.gameDir(profile);
        if (!gameDir.isDirectory() && !gameDir.mkdirs()) throw new IOException("cannot create " + gameDir);

        PackSyncPlanner.Plan plan = PackSyncPlanner.plan(manifest, PackState.load(gameDir), PackSyncPlanner.directoryProbe(gameDir));
        if (!plan.downloads.isEmpty()) {
            PackDownloader.downloadAll(gameDir, plan.downloads, PackDownloader.httpTransport(ProjectsContent.userAgent()),
                    new PackDownloader.Progress() {
                        long lastPercent = -1;

                        @Override
                        public void onProgress(long done, long total) {
                            long percent = total <= 0 ? 0 : Math.min(100, done * 100 / total);
                            if (percent == lastPercent) return;
                            lastPercent = percent;
                            ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, (int) percent,
                                    R.string.projects_join_downloading, done / Tools.BYTE_TO_MB, total / Tools.BYTE_TO_MB);
                        }
                    }, 4);
        }
        for (String path : plan.deletes) {
            File file = new File(gameDir, path);
            if (file.isFile() && !file.delete()) Log.w(TAG, "could not delete " + path);
        }
        plan.nextState.save(gameDir);
        Log.i(TAG, String.format(Locale.ROOT, "pack %s %s: %d downloaded, %d deleted, %d kept",
                pack.id, manifest.versionId, plan.downloads.size(), plan.deletes.size(), plan.kept.size()));

        addServers(gameDir, manifest.projects.servers);
        return loader == null || installLoader(app, loader);
    }

    private static ModLoader loaderOf(PackManifest manifest) {
        String mc = manifest.minecraftVersion();
        String version;
        if ((version = manifest.dependencies.get("fabric-loader")) != null) return new ModLoader(ModLoader.MOD_LOADER_FABRIC, version, mc);
        if ((version = manifest.dependencies.get("quilt-loader")) != null) return new ModLoader(ModLoader.MOD_LOADER_QUILT, version, mc);
        if ((version = manifest.dependencies.get("forge")) != null) return new ModLoader(ModLoader.MOD_LOADER_FORGE, version, mc);
        if ((version = manifest.dependencies.get("neoforge")) != null) return new ModLoader(ModLoader.MOD_LOADER_NEOFORGE, version, mc);
        return null;
    }

    /** Installs the loader's version JSON when missing. Returns false when a GUI step remains. */
    @WorkerThread
    private static boolean installLoader(Context app, ModLoader loader) throws IOException {
        String versionId = loader.getVersionId();
        if (new File(Tools.DIR_HOME_VERSION, versionId + "/" + versionId + ".json").isFile()) return true;
        if (loader.requiresGuiInstallation()) {
            // Upstream's flow: download the installer, then a notification starts the GUI.
            loader.getDownloadTask(new NotificationDownloadListener(app, loader)).run();
            return false;
        }
        final IOException[] failure = new IOException[1];
        loader.getDownloadTask(new ModloaderDownloadListener() {
            @Override
            public void onDownloadFinished(File downloadedFile) {}

            @Override
            public void onDataNotAvailable() {
                failure[0] = new IOException("no " + loader.getVersionId() + " build available");
            }

            @Override
            public void onDownloadError(Exception e) {
                failure[0] = e instanceof IOException ? (IOException) e : new IOException(e);
            }
        }).run();
        if (failure[0] != null) throw failure[0];
        return true;
    }

    @WorkerThread
    private static void addServers(File gameDir, List<PackManifest.PackServer> packServers) {
        List<ServersDat.Entry> entries = new ArrayList<>();
        entries.add(new ServersDat.Entry(ProjectsProfile.NAME, QuickPlay.PROJECTS_SERVER));
        for (PackManifest.PackServer server : packServers) {
            if (server == null || !QuickPlay.isValidAddress(server.address)) continue;
            String name = server.name == null || server.name.trim().isEmpty() ? server.address : server.name.trim();
            entries.add(new ServersDat.Entry(name, server.address));
        }
        try {
            if (!gameDir.isDirectory() && !gameDir.mkdirs()) throw new IOException("cannot create " + gameDir);
            ServersDat.addServers(gameDir, entries);
        } catch (IOException e) {
            // Never replace a list we could not read; the auto-join still works.
            Log.w(TAG, "servers.dat not updated", e);
        }
    }

    private static String details(Throwable e) {
        StringBuilder out = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (out.length() > 0) out.append("\ncaused by: ");
            out.append(t);
        }
        return out.toString();
    }

    private static void post(Runnable runnable) {
        MAIN.post(runnable);
    }
}
