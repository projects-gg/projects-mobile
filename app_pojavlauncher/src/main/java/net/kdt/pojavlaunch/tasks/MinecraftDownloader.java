package net.kdt.pojavlaunch.tasks;

import static net.kdt.pojavlaunch.PojavApplication.sExecutorService;

import android.app.Activity;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.JAssetInfo;
import net.kdt.pojavlaunch.JAssets;
import net.kdt.pojavlaunch.JMinecraftVersionList;
import net.kdt.pojavlaunch.NewJREUtil;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.mirrors.DownloadMirror;
import net.kdt.pojavlaunch.mirrors.MirrorTamperedException;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.utils.DownloadUtils;
import net.kdt.pojavlaunch.utils.FileUtils;
import net.kdt.pojavlaunch.value.DependentLibrary;
import net.kdt.pojavlaunch.value.MinecraftClientInfo;
import net.kdt.pojavlaunch.value.MinecraftLibraryArtifact;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import gg.projects.mobile.launch.CheckedFiles;

public class MinecraftDownloader {
    // Projects fork (M16): the start's file check in two passes. Upstream hashed every file (some
    // 600 MB, 10-12 s on the emulator, minutes on slow storage) and sent a HEAD request and a .sha1
    // request for each Fabric library before every start, while its progress line said "Downloading".
    // Now the check pass settles each file (one stat when it is unchanged since its hash last matched,
    // CheckedFiles; a hash with a large buffer otherwise) and shows "Checking game files"; the download
    // pass then fetches only what is missing or wrong, with its real size.
    private static final String CHECK_TAG = "ProjectsFileCheck";
    private CheckedFiles mChecked;
    private AtomicLong mCheckedCounter; // files the check pass has settled or marked for download
    private AtomicLong mKnownCounter; // ... settled without reading them
    private AtomicLong mHashedCounter, mHashedBytes; // ... hashed, and how much was read
    private AtomicLong mDownloadedCounter; // files the download pass has finished
    private StringBuilder mLaps;
    private long mLapAt;
    private static final double ONE_MEGABYTE = (1024d * 1024d);
    public static final String MINECRAFT_RES = "https://resources.download.minecraft.net/";
    private static final String MAVEN_CENTRAL_REPO1 = "https://repo1.maven.org/maven2/";
    private AtomicReference<Exception> mDownloaderThreadException;
    private ArrayList<DownloaderTask> mScheduledDownloadTasks;
    private ArrayList<File> mDeclaredNatives;
    private AtomicLong mProcessedFileCounter;
    private AtomicLong mProcessedSizeCounter; // Total bytes of processed files (passed SHA1 or downloaded)
    private AtomicLong mInternetUsageCounter; // How many bytes downloaded over Internet
    private long mTotalFileCount;
    private long mTotalSize;
    private File mSourceJarFile; // The source client JAR picked during the inheritance process
    private File mTargetJarFile; // The destination client JAR to which the source will be copied to.
    private boolean mUseFileCounter; // Whether a file counter or a size counter should be used for progress

    private static final ThreadLocal<byte[]> sThreadLocalDownloadBuffer = new ThreadLocal<>();

    private boolean isOnline;

    /**
     * Start the game version download process on the global executor service.
     * @param activity Activity, used for automatic installation of JRE 17 if needed
     * @param version The JMinecraftVersionList.Version from the version list, if available
     * @param realVersion The version ID (necessary)
     * @param listener The download status listener
     */
    public void start(@Nullable Activity activity, @Nullable JMinecraftVersionList.Version version,
                      @NonNull String realVersion,
                      @NonNull AsyncMinecraftDownloader.DoneListener listener) {
        if(activity != null){
            isOnline = Tools.isOnline(activity);
            Tools.switchDemo(Tools.isDemoProfile(activity));

        } else {
            // Projects fork: no account to check without an activity, and every account may
            // download, so try it (upstream assumed a local account and the demo folder here).
            isOnline = true;
        }

        sExecutorService.execute(() -> {
            try {
                // Projects fork: only a missing connection limits us to the installed files. Upstream
                // kept local accounts to them as well; since the owner's decision of 17 October 2026
                // offline accounts download like any other.
                if(!isOnline) {
                    String versionMessage = realVersion; // Use provided version unless we find its a modded instance

                    // See if provided version is a modded version and if that version depends on another jar, check for presence of both jar's .json.
                    try {
                        // This reads the .json associated with the provided version. If it fails, we can assume it's not installed.
                        File providedJsonFile = new File(Tools.DIR_HOME_VERSION + "/" + realVersion + "/" + realVersion + ".json");
                        JMinecraftVersionList.Version providedJson = Tools.GLOBAL_GSON.fromJson(Tools.read(providedJsonFile.getAbsolutePath()), JMinecraftVersionList.Version.class);

                        // This checks if running modded version that depends on other jars, so we use that for the error message.
                        File vanillaJsonFile = new File(Tools.DIR_HOME_VERSION + "/" + providedJson.inheritsFrom + "/" + providedJson.inheritsFrom + ".json");
                        versionMessage = providedJson.inheritsFrom != null ? providedJson.inheritsFrom : versionMessage;

                        // Ensure they're both not some 0 byte corrupted json
                        if (providedJsonFile.length() == 0 || vanillaJsonFile.exists() && vanillaJsonFile.length() == 0){
                            throw new RuntimeException("Minecraft "+versionMessage+ " is needed by " +realVersion); }

                        listener.onDownloadDone();
                    } catch (Exception e) {
                        Tools.showErrorRemote(activity.getString(R.string.projects_version_needs_internet, versionMessage), e);
                    }
                }else {
                downloadGame(activity, version, realVersion);
                listener.onDownloadDone();
                }
            }catch (Exception e) {
                listener.onDownloadFailed(e);
            }
            ProgressLayout.clearProgress(ProgressLayout.DOWNLOAD_MINECRAFT);
        });
    }

    /**
     * Download the game version.
     * @param activity Activity, used for automatic installation of JRE 17 if needed
     * @param verInfo The JMinecraftVersionList.Version from the version list, if available
     * @param versionName The version ID (necessary)
     * @throws Exception when an exception occurs in the function body or in any of the downloading threads.
     */
    private void downloadGame(Activity activity, JMinecraftVersionList.Version verInfo, String versionName) throws Exception {
        // Put up a dummy progress line, for the activity to start the service and do all the other necessary
        // work to keep the launcher alive. We will replace this line when we will start downloading stuff.
        ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_MINECRAFT, 0, R.string.newdl_starting);
        SpeedCalculator speedCalculator = new SpeedCalculator();

        mTargetJarFile = createGameJarPath(versionName);
        mScheduledDownloadTasks = new ArrayList<>();
        mDeclaredNatives = new ArrayList<>();
        mProcessedFileCounter = new AtomicLong(0);
        mProcessedSizeCounter = new AtomicLong(0);
        mInternetUsageCounter = new AtomicLong(0);
        mDownloaderThreadException = new AtomicReference<>(null);
        mUseFileCounter = false;
        mCheckedCounter = new AtomicLong(0);
        mKnownCounter = new AtomicLong(0);
        mHashedCounter = new AtomicLong(0);
        mHashedBytes = new AtomicLong(0);
        mDownloadedCounter = new AtomicLong(0);
        long started = SystemClock.elapsedRealtime();
        mLaps = new StringBuilder();
        mLapAt = started;
        File checkedStore = checkedStore();
        mChecked = CheckedFiles.load(checkedStore, new File(Tools.DIR_GAME_HOME), CheckedFiles.day(System.currentTimeMillis()));
        lap("store");

        downloadAndProcessMetadata(activity, verInfo, versionName);
        long metadataDone = SystemClock.elapsedRealtime();

        // Projects fork (M16): first every file is checked ("Checking game files", by count)...
        long checkedFiles = mTotalFileCount;
        long checkDone;
        List<DownloaderTask> needed = new ArrayList<>();
        boolean done;
        try {
            if (!runPool(mScheduledDownloadTasks, this::reportCheckProgress)) return;
            checkDone = SystemClock.elapsedRealtime();
            for (DownloaderTask task : mScheduledDownloadTasks) {
                if (task.mNeedsDownload) needed.add(task);
            }
            // ...then only what is missing or wrong is downloaded, its total known before it starts.
            prepareDownloadPass(needed);
            done = runPool(needed, () -> {
                double speed = speedCalculator.feed(mInternetUsageCounter.get()) / ONE_MEGABYTE;
                if (mUseFileCounter) reportProgressFileCounter(speed);
                else reportProgressSizeCounter(speed);
            });
        } finally {
            // What was checked stays known, also when a download failed or was cancelled.
            try {
                mChecked.save(checkedStore);
            } catch (IOException e) {
                Log.w(CHECK_TAG, "checked files not saved", e);
            }
        }
        long downloadDone = SystemClock.elapsedRealtime();
        Log.i(CHECK_TAG, String.format(Locale.ROOT,
                "%s: metadata %d ms (%s); check %d files in %d ms (%d unchanged, %d hashed %.1f MB, %d to download); "
                        + "download %d files %.1f MB in %d ms; %d files known",
                versionName, metadataDone - started, mLaps, checkedFiles, checkDone - metadataDone, mKnownCounter.get(),
                mHashedCounter.get(), mHashedBytes.get() / ONE_MEGABYTE, needed.size(), mDownloadedCounter.get(),
                mInternetUsageCounter.get() / ONE_MEGABYTE, downloadDone - checkDone, mChecked.size()));
        if (!done) return;
        ensureJarFileCopy();
        extractNatives(versionName);
        Log.i(CHECK_TAG, String.format(Locale.ROOT, "%s ready in %d ms", versionName, SystemClock.elapsedRealtime() - started));
    }

    /** Projects fork (M16): the metadata steps' times for the check's log line. */
    private void lap(String step) {
        long now = SystemClock.elapsedRealtime();
        if (mLaps.length() > 0) mLaps.append(", ");
        mLaps.append(step).append(' ').append(now - mLapAt);
        mLapAt = now;
    }

    /** Projects fork (M16): where {@link CheckedFiles} keeps what the check knows (the app's own storage). */
    private static File checkedStore() {
        return new File(Tools.DIR_DATA, "projects" + File.separator + "checked-files.txt");
    }

    /**
     * Projects fork (M16): runs {@code tasks} on four threads (upstream's pool) and reports progress
     * about 30 times a second. False when the wait was interrupted (the download was cancelled); a
     * task's exception is thrown.
     */
    private boolean runPool(List<DownloaderTask> tasks, Runnable report) throws Exception {
        if (tasks.isEmpty()) return true;
        ArrayBlockingQueue<Runnable> taskQueue = new ArrayBlockingQueue<>(tasks.size(), false);
        ThreadPoolExecutor pool = new ThreadPoolExecutor(4, 4, 500, TimeUnit.MILLISECONDS, taskQueue);
        // I have tried pre-filling the queue directly instead of doing this, but it didn't work.
        // What a shame.
        for (DownloaderTask task : tasks) pool.execute(task);
        pool.shutdown();
        try {
            while (mDownloaderThreadException.get() == null && !pool.awaitTermination(33, TimeUnit.MILLISECONDS)) {
                report.run();
            }
        } catch (InterruptedException e) {
            // Interrupted while waiting, which means that the download was cancelled.
            // Kill all downloading threads immediately, and ignore any exceptions thrown by them
            pool.shutdownNow();
            return false;
        }
        Exception thrownException = mDownloaderThreadException.get();
        if (thrownException != null) {
            // The first failure also drops the files still queued (upstream let them download on
            // after the error was shown).
            pool.shutdownNow();
            throw thrownException;
        }
        return true;
    }

    /**
     * Projects fork (M16): readies the download pass, on this one thread. Each file's folder is made
     * here: two pool threads making the same folder at once can fail one of them. Sizes the metadata
     * does not give are asked for here too (HEAD requests, one at a time, while the check's line
     * stays up), only for the files that are downloaded; upstream asked for every such file before
     * every start. When one cannot be had, progress counts files instead and no more are asked for.
     */
    private void prepareDownloadPass(List<DownloaderTask> needed) throws IOException {
        mTotalSize = 0;
        mUseFileCounter = false;
        mProcessedFileCounter.set(0);
        mProcessedSizeCounter.set(0);
        for (DownloaderTask task : needed) {
            task.mDownloadPass = true;
            FileUtils.ensureParentDirectory(task.mTargetPath);
            long size = task.mDownloadSize;
            if (size <= 0 && !mUseFileCounter) {
                size = DownloadMirror.getContentLengthMirrored(task.mDownloadClass, task.mTargetUrl);
                if (size < 0) {
                    mUseFileCounter = true;
                    Log.i("MinecraftDownloader", "Failed to determine size of " + task.mTargetPath.getName() + ", switching to file counter");
                }
            }
            if (size > 0) mTotalSize += size;
        }
        if (mTotalSize <= 0) mUseFileCounter = true;
        mTotalFileCount = needed.size();
    }

    /** Projects fork (M16): the check pass's line, files checked of all (nothing is downloaded in it). */
    private void reportCheckProgress() {
        long checked = mCheckedCounter.get();
        int progress = (int) ((checked * 100L) / Math.max(1, mTotalFileCount));
        ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_MINECRAFT, progress,
                R.string.projects_check_files, checked, mTotalFileCount);
    }

    private void reportProgressFileCounter(double speed) {
        long dlFileCounter = mProcessedFileCounter.get();
        int progress = (int)((dlFileCounter * 100L) / Math.max(1, mTotalFileCount));
        ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_MINECRAFT, progress,
                R.string.newdl_downloading_game_files, dlFileCounter,
                mTotalFileCount, speed);
    }

    private void reportProgressSizeCounter(double speed) {
        long dlFileSize = mProcessedSizeCounter.get();
        double dlSizeMegabytes = (double) dlFileSize / ONE_MEGABYTE;
        double dlTotalMegabytes = (double) mTotalSize / ONE_MEGABYTE;
        int progress = (int)((dlFileSize * 100L) / mTotalSize);
        ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_MINECRAFT, progress,
                R.string.newdl_downloading_game_files_size, dlSizeMegabytes, dlTotalMegabytes, speed);
    }

    private File createGameJsonPath(String versionId) {
        return new File(Tools.DIR_HOME_VERSION, versionId + File.separator + versionId + ".json");
    }

    private File createGameJarPath(String versionId) {
        return new File(Tools.DIR_HOME_VERSION, versionId + File.separator + versionId + ".jar");
    }

    /**
     * Ensure that there is a copy of the client JAR file in the version folder, if a copy is
     * needed.
     * @throws IOException if the copy fails
     */
    private void ensureJarFileCopy() throws IOException {
        if(mSourceJarFile == null) return;
        if(mSourceJarFile.equals(mTargetJarFile)) return;
        if(mTargetJarFile.exists()) return;
        FileUtils.ensureParentDirectory(mTargetJarFile);
        Log.i("NewMCDownloader", "Copying " + mSourceJarFile.getName() + " to "+mTargetJarFile.getAbsolutePath());
        org.apache.commons.io.FileUtils.copyFile(mSourceJarFile, mTargetJarFile, false);
    }

    private void extractNatives(String versionName) throws IOException {
        if(mDeclaredNatives.isEmpty()) return;
        int totalCount = mDeclaredNatives.size();

        ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_MINECRAFT, 0,
                R.string.newdl_extracting_native_libraries, 0, totalCount);

        File targetDirectory = new File(Tools.DIR_CACHE, "natives/"+versionName);
        FileUtils.ensureDirectory(targetDirectory);
        NativesExtractor nativesExtractor = new NativesExtractor(targetDirectory);
        int extractedCount = 0;
        for(File source : mDeclaredNatives) {
            nativesExtractor.extractFromAar(source);
            extractedCount++;
            ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_MINECRAFT, extractedCount * 100 / totalCount,
                    R.string.newdl_extracting_native_libraries, extractedCount, totalCount);
        }
    }

    private File downloadGameJson(JMinecraftVersionList.Version verInfo) throws IOException, MirrorTamperedException {
        File targetFile = createGameJsonPath(verInfo.id);
        if(verInfo.sha1 == null && targetFile.canRead() && targetFile.isFile())
            return targetFile;
        FileUtils.ensureParentDirectory(targetFile);
        try {
            DownloadUtils.ensureSha1(targetFile, LauncherPreferences.PREF_VERIFY_MANIFEST ? verInfo.sha1 : null, () -> {
                ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_MINECRAFT, 0,
                        R.string.newdl_downloading_metadata, targetFile.getName());
                DownloadMirror.downloadFileMirrored(DownloadMirror.DOWNLOAD_CLASS_METADATA, verInfo.url, targetFile);
                return null;
            });
        }catch (DownloadUtils.SHA1VerificationException e) {
            if(DownloadMirror.isMirrored()) throw new MirrorTamperedException();
            else throw e;
        }
        return targetFile;
    }

    private JAssets downloadAssetsIndex(JMinecraftVersionList.Version verInfo) throws IOException{
        JMinecraftVersionList.AssetIndex assetIndex = verInfo.assetIndex;
        if(assetIndex == null || verInfo.assets == null) return null;
        File targetFile = new File(Tools.ASSETS_PATH, "indexes"+ File.separator + verInfo.assets + ".json");
        FileUtils.ensureParentDirectory(targetFile);
        DownloadUtils.ensureSha1(targetFile, assetIndex.sha1, ()-> {
            ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_MINECRAFT, 0,
                    R.string.newdl_downloading_metadata, targetFile.getName());
            DownloadMirror.downloadFileMirrored(DownloadMirror.DOWNLOAD_CLASS_METADATA, assetIndex.url, targetFile);
            return null;
        });
        return Tools.GLOBAL_GSON.fromJson(Tools.read(targetFile), JAssets.class);
    }
    
    private MinecraftClientInfo getClientInfo(JMinecraftVersionList.Version verInfo) {
        Map<String, MinecraftClientInfo> downloads = verInfo.downloads;
        if(downloads == null) return null;
        return downloads.get("client");
    }

    /**
     * Download (if necessary) and process a version's metadata, scheduling all downloads that this
     * version needs.
     * @param activity Activity, used for automatic installation of JRE 17 if needed
     * @param verInfo The JMinecraftVersionList.Version from the version list, if available
     * @param versionName The version ID (necessary)
     * @return false if JRE17 installation failed, true otherwise
     * @throws IOException if the download of any of the metadata files fails
     */
    private boolean downloadAndProcessMetadata(Activity activity, JMinecraftVersionList.Version verInfo, String versionName) throws IOException, MirrorTamperedException {
        File versionJsonFile;
        if(verInfo != null) versionJsonFile = downloadGameJson(verInfo);
        else versionJsonFile = createGameJsonPath(versionName);
        if(versionJsonFile.canRead())  {
            verInfo = Tools.GLOBAL_GSON.fromJson(Tools.read(versionJsonFile), JMinecraftVersionList.Version.class);
        } else {
            throw new IOException("Unable to read Version JSON for version " + versionName);
        }
        lap(versionName + ".json");

        if(activity != null && !NewJREUtil.installNewJreIfNeeded(activity, verInfo)){
            throw new RuntimeException(activity.getString(R.string.exception_failed_to_unpack_jre17));
        }
        lap("java");

        JAssets assets = downloadAssetsIndex(verInfo);
        lap("asset index");
        if(assets != null) scheduleAssetDownloads(assets);


        MinecraftClientInfo minecraftClientInfo = getClientInfo(verInfo);
        if(minecraftClientInfo != null) scheduleGameJarDownload(minecraftClientInfo, versionName);

        if(verInfo.libraries != null) scheduleLibraryDownloads(verInfo.libraries);

        if(verInfo.logging != null) scheduleLoggingAssetDownloadIfNeeded(verInfo.logging);
        lap("files");

        if(Tools.isValidString(verInfo.inheritsFrom)) {
            JMinecraftVersionList.Version inheritedVersion = AsyncMinecraftDownloader.getListedVersion(verInfo.inheritsFrom);
            // Infinite inheritance !?! :noway:
            return downloadAndProcessMetadata(activity, inheritedVersion, verInfo.inheritsFrom);
        }
        return true;
    }

    private void growDownloadList(int addedElementCount) {
        mScheduledDownloadTasks.ensureCapacity(mScheduledDownloadTasks.size() + addedElementCount);
    }

    private void scheduleDownload(File targetFile, int downloadClass, String url, String sha1,
                                  long size, boolean skipIfFailed) throws IOException {
        // Projects fork (M16): nothing is touched or asked for here. Upstream made every file's parent
        // folder and sent a HEAD request for each file without a size before every start; the
        // download pass does both, on one thread, for the files it downloads (prepareDownloadPass).
        mTotalFileCount++;
        mScheduledDownloadTasks.add(
                new DownloaderTask(targetFile, downloadClass, url, sha1, Math.max(0, size), skipIfFailed)
        );
    }

    /**
     * Schedule the download of an AAR library containing the required natives, for later extraction
     * and adding to the library path.
     * @param baseRepository the source Maven repository to download from.
     * @param dependentLibrary the DependentLibrary to get the path from
     * @throws IOException in case if download scheduling fails.
     */
    private void scheduleNativeLibraryDownload(String baseRepository, DependentLibrary dependentLibrary) throws IOException {
        String path = FileUtils.removeExtension(Tools.artifactToPath(dependentLibrary)) + ".aar";
        String downloadUrl = baseRepository + path;
        File targetPath = new File(Tools.DIR_HOME_LIBRARY, path);
        mDeclaredNatives.add(targetPath);
        scheduleDownload(targetPath, DownloadMirror.DOWNLOAD_CLASS_LIBRARIES, downloadUrl, null, 0, true);
    }

    private void scheduleLibraryDownloads(DependentLibrary[] dependentLibraries) throws IOException {
        Tools.preProcessLibraries(dependentLibraries);
        growDownloadList(dependentLibraries.length);
        for(DependentLibrary dependentLibrary : dependentLibraries) {
            // Don't download lwjgl, we have our own bundled in.
            if(dependentLibrary.name.startsWith("org.lwjgl")) continue;
            // Special handling for JNA Android natives
            if(dependentLibrary.name.startsWith("net.java.dev.jna:jna:")) {
                scheduleNativeLibraryDownload(MAVEN_CENTRAL_REPO1, dependentLibrary);
            }
            String libArtifactPath = Tools.artifactToPath(dependentLibrary);
            String sha1 = null, url = null;
            long size = 0;
            boolean skipIfFailed = false;
            if(dependentLibrary.downloads != null) {
                if(dependentLibrary.downloads.artifact != null) {
                    MinecraftLibraryArtifact artifact = dependentLibrary.downloads.artifact;
                    sha1 = artifact.sha1;
                    url = artifact.url;
                    size = artifact.size;
                } else {
                    // If the library has a downloads section but doesn't have an artifact in
                    // it, it is likely natives-only, which means it can be skipped.
                    Log.i("NewMCDownloader", "Skipped library " + dependentLibrary.name + " due to lack of artifact");
                    continue;
                }
            }
            if(url == null) {
                url = (dependentLibrary.url == null
                        ? "https://libraries.minecraft.net/"
                        : dependentLibrary.url.replace("http://","https://")) + libArtifactPath;
                skipIfFailed = true;
                // Projects fork (M16): Fabric's and Quilt's entries give the SHA-1 and size themselves
                // (from the same repository as the .sha1 upstream fetched before every start).
                if (dependentLibrary.sha1 != null && dependentLibrary.sha1.trim().length() == 40) sha1 = dependentLibrary.sha1.trim();
                if (dependentLibrary.size > 0) size = dependentLibrary.size;
            }
            if(!LauncherPreferences.PREF_CHECK_LIBRARY_SHA) sha1 = null;
            scheduleDownload(new File(Tools.DIR_HOME_LIBRARY, libArtifactPath),
                    DownloadMirror.DOWNLOAD_CLASS_LIBRARIES,
                    url, sha1, size, skipIfFailed
            );
        }
    }
    
    private void scheduleAssetDownloads(JAssets assets) throws IOException {
        Map<String, JAssetInfo> assetObjects = assets.objects;
        if(assetObjects == null) return;
        Set<String> assetNames = assetObjects.keySet();
        growDownloadList(assetNames.size());
        for(String asset : assetNames) {
            JAssetInfo assetInfo = assetObjects.get(asset);
            if(assetInfo == null) continue;
            File targetFile;
            String hashedPath = assetInfo.hash.substring(0, 2) + File.separator + assetInfo.hash;
            String basePath = assets.mapToResources ? Tools.OBSOLETE_RESOURCES_PATH : Tools.ASSETS_PATH;
            if(assets.virtual || assets.mapToResources) {
                targetFile = new File(basePath, asset);
            } else {
                targetFile = new File(basePath, "objects" + File.separator + hashedPath);
            }
            String sha1 = LauncherPreferences.PREF_CHECK_LIBRARY_SHA ? assetInfo.hash : null;
            scheduleDownload(targetFile,
                    DownloadMirror.DOWNLOAD_CLASS_ASSETS,
                    MINECRAFT_RES + hashedPath,
                    sha1,
                    assetInfo.size,
                    false);
        }
    }

    private void scheduleLoggingAssetDownloadIfNeeded(JMinecraftVersionList.LoggingConfig loggingConfig) throws IOException {
        if(loggingConfig.client == null || loggingConfig.client.file == null) return;
        JMinecraftVersionList.FileProperties loggingFileProperties = loggingConfig.client.file;
        File internalLoggingConfig = new File(Tools.DIR_DATA + File.separator + "security",
                loggingFileProperties.id.replace("client", "log4j-rce-patch"));
        if(internalLoggingConfig.exists()) return;
        File destination = new File(Tools.DIR_GAME_NEW, loggingFileProperties.id);
        scheduleDownload(destination,
                DownloadMirror.DOWNLOAD_CLASS_LIBRARIES,
                loggingFileProperties.url,
                loggingFileProperties.sha1,
                loggingFileProperties.size,
                false);
    }

    private void scheduleGameJarDownload(MinecraftClientInfo minecraftClientInfo, String versionName) throws IOException {
        File clientJar = createGameJarPath(versionName);
        String clientSha1 = LauncherPreferences.PREF_CHECK_LIBRARY_SHA ?
                minecraftClientInfo.sha1 : null;
        growDownloadList(1);
        scheduleDownload(clientJar,
                DownloadMirror.DOWNLOAD_CLASS_LIBRARIES,
                minecraftClientInfo.url,
                clientSha1,
                minecraftClientInfo.size,
                false
        );
        // Store the path of the JAR to copy it into our new version folder later.
        mSourceJarFile = clientJar;
    }

    private static byte[] getLocalBuffer() {
        byte[] tlb = sThreadLocalDownloadBuffer.get();
        if(tlb != null) return tlb;
        tlb = new byte[32768];
        sThreadLocalDownloadBuffer.set(tlb);
        return tlb;
    }

    /**
     * Projects fork (M16): what identifies a file's content without reading it ({@link CheckedFiles}),
     * or null when it is missing or not a regular file. One stat call.
     */
    private static CheckedFiles.Stamp stampOf(File file) {
        try {
            StructStat st = Os.stat(file.getAbsolutePath());
            if (!OsConstants.S_ISREG(st.st_mode)) return null;
            long modified, changed;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                modified = st.st_mtim.tv_sec * 1_000_000_000L + st.st_mtim.tv_nsec;
                changed = st.st_ctim.tv_sec * 1_000_000_000L + st.st_ctim.tv_nsec;
            } else {
                modified = st.st_mtime * 1_000_000_000L;
                changed = st.st_ctime * 1_000_000_000L;
            }
            return new CheckedFiles.Stamp(st.st_size, modified, changed, st.st_ino);
        } catch (ErrnoException e) {
            return null;
        }
    }

    private final class DownloaderTask implements Runnable, Tools.DownloaderFeedback {
        private final File mTargetPath;
        private final String mTargetUrl;
        private String mTargetSha1;
        private final int mDownloadClass;
        private final boolean mSkipIfFailed;
        private int mLastCurr;
        private final long mDownloadSize;
        // Projects fork (M16): which pass runs the task, and what the check pass found.
        boolean mDownloadPass;
        volatile boolean mNeedsDownload;
        private CheckedFiles.Stamp mStamp;

        DownloaderTask(File targetPath, int downloadClass, String targetUrl, String targetSha1,
                       long downloadSize, boolean skipIfFailed) {
            this.mTargetPath = targetPath;
            this.mTargetUrl = targetUrl;
            this.mTargetSha1 = targetSha1;
            this.mDownloadClass = downloadClass;
            this.mDownloadSize = downloadSize;
            this.mSkipIfFailed = skipIfFailed;
        }

        private String downloadSha1() throws IOException {
            String downloadedHash = DownloadMirror.downloadStringMirrored(
                    mDownloadClass, mTargetUrl + ".sha1"
            );
            if(!Tools.isValidString(downloadedHash)) return null;
            // Ensure that we don't have leading/trailing whitespaces before checking hash length
            downloadedHash = downloadedHash.trim();
            // SHA1 is made up of 20 bytes, which means 40 hexadecimal digits, which means 40 chars
            if(downloadedHash.length() != 40) return null;
            return downloadedHash;
        }

        /*
         * Maven repositories usually have the hash of a library near it, like:
         * .../libraryName-1.0.jar
         * .../libraryName.1.0.jar.sha1
         * Since Minecraft libraries are stored in maven repositories, try to use
         * this when downloading libraries without hashes in the json.
         */
        private void tryGetLibrarySha1() throws IOException {
            File sha1CacheDir = new File(Tools.DIR_CACHE + "/sha1hashes");
            File cacheFile = new File(sha1CacheDir.getAbsolutePath() + FileUtils.getFileName(mTargetUrl) + ".sha");

            // Only use cache when its offline. No point in having cache invalidation now!
            if (!isOnline || !LauncherPreferences.PREF_CHECK_LIBRARY_SHA) { // Well not only offlines..this setting speeds up launch times at least!
                try (BufferedReader cacheFileReader = new BufferedReader(new FileReader(cacheFile))) {
                    mTargetSha1 = cacheFileReader.readLine();
                    if (mTargetSha1 != null) {
                        Log.i("MinecraftDownloader", "Reading Hash from cache: " + mTargetSha1 + " from " + cacheFile);
                    } else if (cacheFile.exists()) {
                        Log.i("MinecraftDownloader", "Deleting invalid hash from cache: " + cacheFile);
                        cacheFile.delete();
                    }
                } catch (FileNotFoundException ignored) {
                    mTargetSha1 = null;
                    Log.w("MinecraftDownloader", "Failed to read hash for " + cacheFile);
                }
                return;
            }

            String resultHash = null;
            try {
                resultHash = downloadSha1();
                // The hash is a 40-byte download.
                mInternetUsageCounter.getAndAdd(40);
            } catch (IOException e) {
                Log.i("MinecraftDownloader", "Failed to download hash", e);
                if (cacheFile.exists() && new BufferedReader(new FileReader(cacheFile)).readLine() == null) {
                    Log.i("MinecraftDownloader", "Deleting failed hash download from cache: " + cacheFile);
                    cacheFile.delete();
                }
            }
            if (resultHash != null) {
                Log.i("MinecraftDownloader", "Got hash: " + resultHash + " for " + FileUtils.getFileName(mTargetUrl));
                mTargetSha1 = resultHash;
                if (!sha1CacheDir.exists()) {
                    sha1CacheDir.mkdir(); // If mkdir() fails, something went wrong with initializing /data/data/. mkdirs() isn't used on purpose
                }
                try (FileWriter writeHash = new FileWriter(cacheFile)) {
                    Log.i("MinecraftDownloader", "Saving hash: " + resultHash + " for " + FileUtils.getFileName(mTargetUrl) + " to " + cacheFile);
                    writeHash.write(resultHash);
                }
            }
        }

        @Override
        public void run() {
            try {
                if (mDownloadPass) downloadPass();
                else checkPass();
            }catch (Exception e) {
                mDownloaderThreadException.set(e);
            }
        }

        /**
         * Projects fork (M16): the check pass. A file unchanged since its hash last matched is settled
         * by its stamp; another one is hashed (or, without a hash to compare, settled by being there,
         * as upstream did); what is missing or wrong waits for the download pass.
         */
        private void checkPass() throws Exception {
            try {
                mStamp = stampOf(mTargetPath);
                if (mStamp != null && knownGood()) {
                    mKnownCounter.incrementAndGet();
                    return;
                }
                if(mDownloadClass == DownloadMirror.DOWNLOAD_CLASS_LIBRARIES && !Tools.isValidString(mTargetSha1)) {
                    // If we're downloading a library, try to get sha1 since it might be available as a file
                    // (Projects: only for a file its stamp did not settle, so this request is rare now
                    // and happens while "Checking game files" shows).
                    tryGetLibrarySha1();
                }
                if(Tools.isValidString(mTargetSha1)) {
                    if (mStamp != null && hashMatches()) return;
                }else {
                    mTargetSha1 = null; // Nullify SHA1 as DownloadUtils.ensureSha1 only checks for null,
                                        // not for string validity
                    if(mTargetPath.exists()) return;
                }
                mNeedsDownload = true;
            } finally {
                mCheckedCounter.incrementAndGet();
            }
        }

        /** Unchanged since it was checked, with the size the metadata gives and the hash it expects. */
        private boolean knownGood() {
            if (mDownloadSize > 0 && mStamp.size != mDownloadSize) return false;
            String known = mChecked.known(mTargetPath, mStamp);
            if (known == null) return false;
            // A library the metadata names no hash for: the one recorded was checked against its
            // repository's .sha1 when it was recorded.
            return !Tools.isValidString(mTargetSha1) || known.equalsIgnoreCase(mTargetSha1.trim());
        }

        /** Hashes the file (32 KB reads; upstream read 1 KB at a time) and remembers a match. */
        private boolean hashMatches() {
            try {
                String actual = CheckedFiles.sha1(mTargetPath, getLocalBuffer());
                mHashedCounter.incrementAndGet();
                mHashedBytes.addAndGet(mStamp.size);
                if (!actual.equalsIgnoreCase(mTargetSha1.trim())) return false;
                mChecked.put(mTargetPath, mStamp, actual);
                return true;
            } catch (IOException e) {
                // Unreadable: downloaded again (upstream counted a read error as a match).
                Log.w(CHECK_TAG, "could not read " + mTargetPath.getName(), e);
                return false;
            }
        }

        /**
         * Projects fork (M16): the download pass (its folder was made by prepareDownloadPass). A
         * download whose SHA-1 matched is remembered with its stamp.
         */
        private void downloadPass() throws Exception {
            mChecked.forget(mTargetPath);
            CheckedFiles.Stamp verified = downloadFile();
            if (verified != null) mChecked.put(mTargetPath, verified, mTargetSha1.trim());
            mDownloadedCounter.incrementAndGet();
        }

        /**
         * Projects fork (M16): downloads the file, up to five times while its SHA-1 does not match
         * (as upstream's ensureSha1), and returns its stamp once the hash matched; null without a hash
         * to compare or when a failure was skipped. The check pass found the file missing or wrong, so
         * it is not hashed again first: ensureSha1 did, and counted a file it could not read as a match.
         * Here a file that cannot be read after its download does not match.
         */
        private CheckedFiles.Stamp downloadFile() throws Exception {
            CheckedFiles.Stamp verified = null;
            try {
                String expected = Tools.isValidString(mTargetSha1) ? mTargetSha1.trim() : null;
                for (int attempt = 1; ; attempt++) {
                    // A try again counts its bytes from zero (the speed line keeps all that was fetched).
                    mProcessedSizeCounter.addAndGet(-mLastCurr);
                    mLastCurr = 0;
                    DownloadMirror.downloadFileMirrored(mDownloadClass, mTargetUrl, mTargetPath,
                            getLocalBuffer(), this);
                    if (expected == null) break;
                    // The stamp before the read: a change during it shows as a new stamp next time.
                    CheckedFiles.Stamp stamp = stampOf(mTargetPath);
                    if (stamp != null && expected.equalsIgnoreCase(readSha1())) {
                        verified = stamp;
                        break;
                    }
                    if (attempt == 5) {
                        throw new DownloadUtils.SHA1VerificationException("SHA1 verifcation failed after 5 download attempts");
                    }
                }
            }catch (Exception e) {
                if(!mSkipIfFailed) throw e;
            }
            mProcessedFileCounter.incrementAndGet();
            return verified;
        }

        /** The file's SHA-1, or null when it cannot be read. */
        private String readSha1() {
            try {
                return CheckedFiles.sha1(mTargetPath, getLocalBuffer());
            } catch (IOException e) {
                Log.w(CHECK_TAG, "could not read " + mTargetPath.getName(), e);
                return null;
            }
        }

        @Override
        public void updateProgress(int curr, int max) {
            int delta = curr - mLastCurr;
            mProcessedSizeCounter.addAndGet(delta);
            mInternetUsageCounter.addAndGet(delta);
            mLastCurr = curr;
        }
    }
}
