package ca.pkay.rcloneexplorer.workmanager

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ca.pkay.rcloneexplorer.Items.RemoteItem
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.Rclone
import ca.pkay.rcloneexplorer.Services.ThumbnailServerManager
import ca.pkay.rcloneexplorer.Services.ThumbnailServerService
import ca.pkay.rcloneexplorer.util.NotificationUtils
import ca.pkay.rcloneexplorer.util.SyncLog
import ca.pkay.rcloneexplorer.util.ThumbnailPrefetchExecutor
import ca.pkay.rcloneexplorer.util.ThumbnailPrefetchTargets
import ca.pkay.rcloneexplorer.util.VideoProblemClassifier
import ca.pkay.rcloneexplorer.util.VideoProblemLister
import ca.pkay.rcloneexplorer.util.VideoProblemProbe
import ca.pkay.rcloneexplorer.util.VideoProblemScanCache
import ca.pkay.rcloneexplorer.util.VideoProblemScanExport
import ca.pkay.rcloneexplorer.util.VideoProblemVerdict
import java.io.IOException
import java.net.ServerSocket
import java.security.SecureRandom
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * On-demand recursive video problem scan. Serial probes over localhost rclone HTTP serve.
 */
class VideoProblemScanWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext
        val remoteName = inputData.getString(KEY_REMOTE_NAME)?.trim().orEmpty()
        val rootPath = inputData.getString(KEY_ROOT_PATH)?.trim().orEmpty()
        if (remoteName.isEmpty()) {
            return@withContext Result.failure(errorData("missing_remote"))
        }
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)
        val rclone = Rclone(app)
        val remote = rclone.getRemoteItemFromName(remoteName)
            ?: return@withContext Result.failure(errorData("remote_not_found"))
        if (remote.isRemoteType(RemoteItem.SAFW)) {
            return@withContext Result.failure(errorData("safw_unsupported"))
        }

        setForeground(createForegroundInfo(app, 0, 0, rootPath.ifEmpty { "/" }))

        val startAtRoot = ThumbnailPrefetchTargets.readStartAtRoot(prefs, app)
        val listed = VideoProblemLister.listVideosRecursive(
            rclone = rclone,
            remote = remote,
            rootPath = rootPath,
            startAtRoot = startAtRoot,
            isStopped = { isStopped },
        ) ?: return@withContext Result.failure(errorData("list_failed"))

        if (isStopped) {
            return@withContext Result.success(cancelData())
        }

        val toProbe = ArrayList<VideoProblemLister.ListedVideo>()
        val problemRows = ArrayList<VideoProblemScanExport.ProblemRow>()
        var skippedUnchanged = 0
        for (listedVideo in listed) {
            val item = listedVideo.item
            val path = item.path ?: continue
            val cached = VideoProblemScanCache.readEntry(
                prefs,
                remoteName,
                path,
                item.size,
                item.modTime,
            )
            if (cached != null) {
                skippedUnchanged++
                val verdict = cached.verdict
                if (verdict != null) {
                    problemRows.add(
                        VideoProblemScanExport.ProblemRow(
                            verdict = verdict,
                            relativePath = listedVideo.relativePath,
                            detail = cached.detail,
                        ),
                    )
                }
            } else {
                toProbe.add(listedVideo)
            }
        }

        val total = listed.size
        setProgress(progressData(0, total, skippedUnchanged, problemRows.size, rootPath))
        setForeground(createForegroundInfo(app, 0, total, rootPath.ifEmpty { "/" }))

        if (toProbe.isEmpty()) {
            val report = VideoProblemScanCache.LastReport(
                remote = remoteName,
                rootPath = rootPath,
                scannedCount = total,
                skippedUnchanged = skippedUnchanged,
                problemRows = sortRows(problemRows),
                finishedAtEpochMs = System.currentTimeMillis(),
            )
            VideoProblemScanCache.writeLastReport(prefs, report)
            return@withContext Result.success(successData(report))
        }

        var serveLeaseId = 0
        val auth = randomAuthToken()
        val port = allocatePort(ThumbnailPrefetchExecutor.THUMB_PORT_PREFERRED)
        val hidden = ThumbnailPrefetchTargets.hiddenServePath(auth, remote.name)
        val newCacheEntries = ArrayList<VideoProblemScanCache.CachedEntry>()
        var probed = 0
        try {
            serveLeaseId = ThumbnailServerManager.getInstance().acquireServeLease(app, remote, port, auth)
            if (serveLeaseId == 0) {
                return@withContext Result.failure(errorData("serve_lease_failed"))
            }
            ThumbnailServerService.startServing(app, remote, port, auth, rootPath.ifEmpty { "/" }, true)
            if (!waitForThumbnailServerReady()) {
                return@withContext Result.failure(errorData("serve_timeout"))
            }

            for (listedVideo in toProbe) {
                if (isStopped) {
                    break
                }
                val item = listedVideo.item
                val path = item.path ?: continue
                val url = ThumbnailPrefetchTargets.buildThumbnailHttpUrl(hidden, port, path)
                val facts = VideoProblemProbe.probeHttpUrl(app, url)
                val classification = VideoProblemClassifier.classify(facts)
                val verdict = classification.verdict
                newCacheEntries.add(
                    VideoProblemScanCache.CachedEntry(
                        remote = remoteName,
                        path = path,
                        size = item.size,
                        modTime = item.modTime,
                        verdict = verdict,
                        detail = classification.detail,
                    ),
                )
                if (verdict != null) {
                    problemRows.add(
                        VideoProblemScanExport.ProblemRow(
                            verdict = verdict,
                            relativePath = listedVideo.relativePath,
                            detail = classification.detail,
                        ),
                    )
                    logProblem(app, verdict, item.name ?: path, classification.detail)
                }
                probed++
                val done = skippedUnchanged + probed
                setProgress(progressData(done, total, skippedUnchanged, problemRows.size, rootPath))
                if (probed % 3 == 0 || probed == toProbe.size) {
                    setForeground(createForegroundInfo(app, done, total, rootPath.ifEmpty { "/" }))
                }
            }
        } finally {
            if (newCacheEntries.isNotEmpty()) {
                VideoProblemScanCache.putEntries(prefs, newCacheEntries)
            }
            if (serveLeaseId != 0) {
                ThumbnailServerManager.getInstance().releaseServeLease(serveLeaseId)
            }
        }

        val report = VideoProblemScanCache.LastReport(
            remote = remoteName,
            rootPath = rootPath,
            scannedCount = total,
            skippedUnchanged = skippedUnchanged,
            problemRows = sortRows(problemRows),
            finishedAtEpochMs = System.currentTimeMillis(),
        )
        VideoProblemScanCache.writeLastReport(prefs, report)
        if (isStopped) {
            return@withContext Result.success(cancelData(report))
        }
        Result.success(successData(report))
    }

    private fun logProblem(app: Context, verdict: VideoProblemVerdict, basename: String, detail: String) {
        val safeBase = basename.replace('\n', ' ').replace('\r', ' ')
        val safeDetail = detail.replace('\n', ' ').replace('\r', ' ')
        SyncLog.info(
            app,
            SYNC_LOG_TITLE,
            "event=problem verdict=${verdict.name} basename=$safeBase detail=$safeDetail",
        )
    }

    private fun sortRows(rows: List<VideoProblemScanExport.ProblemRow>): List<VideoProblemScanExport.ProblemRow> {
        val order = mapOf(
            VideoProblemVerdict.AUDIO to 0,
            VideoProblemVerdict.SEEK to 1,
            VideoProblemVerdict.BOTH to 2,
            VideoProblemVerdict.PROBE_FAILED to 3,
        )
        return rows.sortedWith(
            compareBy<VideoProblemScanExport.ProblemRow> { order[it.verdict] ?: 9 }
                .thenBy { it.relativePath },
        )
    }

    private fun createForegroundInfo(context: Context, done: Int, total: Int, folder: String): ForegroundInfo {
        NotificationUtils.createNotificationChannel(
            context,
            ThumbnailServerService.NOTIFICATION_CHANNEL_ID,
            context.getString(R.string.background_media_prep_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
            context.getString(R.string.thumbnail_server_notification_channel_description),
        )
        val text = if (total <= 0) {
            context.getString(R.string.video_problem_scan_notification_listing, folder)
        } else {
            context.getString(R.string.video_problem_scan_notification_progress, done, total, folder)
        }
        val notification = NotificationCompat.Builder(context, ThumbnailServerService.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_streaming)
            .setContentTitle(context.getString(R.string.video_problem_scan_notification_title))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(total.coerceAtLeast(0), done.coerceAtLeast(0), total <= 0)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                WM_FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(WM_FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

    private suspend fun waitForThumbnailServerReady(): Boolean {
        val deadline = System.currentTimeMillis() + SERVER_READY_TIMEOUT_MS
        var sawStarting = false
        while (System.currentTimeMillis() < deadline) {
            when (
                ThumbnailServerManager.getInstance().getSyncState()
                    ?: ThumbnailServerManager.ServerState.STOPPED
            ) {
                ThumbnailServerManager.ServerState.READY -> return true
                ThumbnailServerManager.ServerState.FAILED -> return false
                ThumbnailServerManager.ServerState.STARTING -> {
                    sawStarting = true
                    delay(100L)
                }
                ThumbnailServerManager.ServerState.STOPPED -> {
                    if (sawStarting) {
                        return false
                    }
                    delay(100L)
                }
            }
        }
        return false
    }

    private fun randomAuthToken(): String {
        val random = SecureRandom()
        val values = ByteArray(16)
        random.nextBytes(values)
        return Base64.encodeToString(values, Base64.NO_PADDING or Base64.NO_WRAP or Base64.URL_SAFE)
    }

    private fun allocatePort(preferred: Int): Int {
        try {
            ServerSocket(preferred).use { socket ->
                socket.reuseAddress = true
                return socket.localPort
            }
        } catch (_: IOException) {
            ServerSocket(0).use { socket -> return socket.localPort }
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "video_problem_scan_v1"
        const val KEY_REMOTE_NAME = "remote_name"
        const val KEY_ROOT_PATH = "root_path"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_SKIPPED = "skipped"
        const val KEY_PROBLEMS = "problems"
        const val KEY_FOLDER = "folder"
        const val KEY_ERROR = "error"
        const val KEY_CANCELLED = "cancelled"
        const val KEY_SCANNED = "scanned"
        private const val SYNC_LOG_TITLE = "VideoScanDbg"
        private const val WM_FOREGROUND_NOTIFICATION_ID = 187
        private const val SERVER_READY_TIMEOUT_MS = 45_000L

        @JvmStatic
        fun enqueue(context: Context, remoteName: String, rootPath: String) {
            val app = context.applicationContext
            val input = Data.Builder()
                .putString(KEY_REMOTE_NAME, remoteName)
                .putString(KEY_ROOT_PATH, rootPath)
                .build()
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<VideoProblemScanWorker>()
                .setConstraints(constraints)
                .setInputData(input)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(app).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
            SyncLog.info(app, SYNC_LOG_TITLE, "event=enqueued remote=$remoteName")
        }

        @JvmStatic
        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK_NAME)
        }

        private fun progressData(
            done: Int,
            total: Int,
            skipped: Int,
            problems: Int,
            folder: String,
        ): Data =
            Data.Builder()
                .putInt(KEY_DONE, done)
                .putInt(KEY_TOTAL, total)
                .putInt(KEY_SKIPPED, skipped)
                .putInt(KEY_PROBLEMS, problems)
                .putString(KEY_FOLDER, folder)
                .build()

        private fun successData(report: VideoProblemScanCache.LastReport): Data =
            Data.Builder()
                .putInt(KEY_SCANNED, report.scannedCount)
                .putInt(KEY_SKIPPED, report.skippedUnchanged)
                .putInt(KEY_PROBLEMS, report.problemRows.size)
                .putBoolean(KEY_CANCELLED, false)
                .build()

        private fun cancelData(report: VideoProblemScanCache.LastReport? = null): Data =
            Data.Builder()
                .putBoolean(KEY_CANCELLED, true)
                .putInt(KEY_SCANNED, report?.scannedCount ?: 0)
                .putInt(KEY_SKIPPED, report?.skippedUnchanged ?: 0)
                .putInt(KEY_PROBLEMS, report?.problemRows?.size ?: 0)
                .build()

        private fun errorData(code: String): Data =
            Data.Builder().putString(KEY_ERROR, code).build()
    }
}
