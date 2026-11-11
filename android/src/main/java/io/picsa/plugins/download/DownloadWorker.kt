package io.picsa.plugins.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.json.JSONObject
import java.io.File

/**
 * Jetpack CoroutineWorker acting as the resilient execution engine for background downloads.
 *
 * When notification.enabled == true, calls setForeground(ForegroundInfo(...)) within runCatching.
 * If notification permissions are not granted or OS limits prevent foreground service promotion,
 * the exception is caught, logged to telemetry, and the exact same worker seamlessly proceeds in-app.
 */
class DownloadWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_DOWNLOAD_ID = "downloadId"
        const val KEY_URL = "url"
        const val KEY_DEST_PATH = "destPath"
        const val KEY_HEADERS_JSON = "headersJson"
        const val KEY_NOTIFICATION_ENABLED = "notificationEnabled"
        const val KEY_NOTIFICATION_TITLE = "notificationTitle"
        const val KEY_NOTIFICATION_SUBTITLE = "notificationSubtitle"
        const val KEY_CHANNEL_NAME = "channelName"
        const val KEY_CHANNEL_ID = "channelId"

        const val KEY_OUTPUT_URI = "outputUri"
        const val KEY_OUTPUT_SIZE = "outputSize"
        const val KEY_OUTPUT_RESUMED = "outputResumed"
        const val KEY_OUTPUT_ERROR = "outputError"

        private const val DEFAULT_CHANNEL_ID = "picsa_file_downloads"
        private const val DEFAULT_CHANNEL_NAME = "File Downloads"

        // Active pause flags tracked by downloadId
        private val pausedTasks = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

        fun setPaused(downloadId: String, paused: Boolean) {
            pausedTasks[downloadId] = paused
        }

        fun isPaused(downloadId: String): Boolean {
            return pausedTasks[downloadId] == true
        }

        fun clearPause(downloadId: String) {
            pausedTasks.remove(downloadId)
        }
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private var notificationId = 0

    override suspend fun doWork(): Result {
        val downloadId = inputData.getString(KEY_DOWNLOAD_ID) ?: return Result.failure()
        val url = inputData.getString(KEY_URL) ?: return Result.failure()
        val destPath = inputData.getString(KEY_DEST_PATH) ?: return Result.failure()
        val headersJson = inputData.getString(KEY_HEADERS_JSON)
        val notificationEnabled = inputData.getBoolean(KEY_NOTIFICATION_ENABLED, false)
        val title = inputData.getString(KEY_NOTIFICATION_TITLE) ?: "Downloading file"
        val subtitle = inputData.getString(KEY_NOTIFICATION_SUBTITLE)
        val channelId = inputData.getString(KEY_CHANNEL_ID) ?: DEFAULT_CHANNEL_ID
        val channelName = inputData.getString(KEY_CHANNEL_NAME) ?: DEFAULT_CHANNEL_NAME

        notificationId = kotlin.math.abs(downloadId.hashCode())

        val headers = mutableMapOf<String, String>()
        if (!headersJson.isNullOrBlank()) {
            try {
                val json = JSONObject(headersJson)
                val keys = json.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    headers[k] = json.getString(k)
                }
            } catch (_: Exception) {
                // Ignore headers parse errors
            }
        }

        createNotificationChannel(channelId, channelName)

        if (notificationEnabled) {
            runCatching {
                val initialInfo = createForegroundInfo(
                    channelId = channelId,
                    title = title,
                    subtitle = subtitle ?: "Starting download...",
                    progress = 0,
                    indeterminate = true
                )
                setForeground(initialInfo)
            }.onFailure { err ->
                ResumableDownloadPlugin.emitDiagnostic(
                    downloadId = downloadId,
                    level = "warn",
                    message = "Could not promote to foreground service: ${err.message}. Proceeding with in-app download."
                )
            }
        }

        val destinationFile = File(destPath)
        val downloader = HttpRangeDownloader()

        clearPause(downloadId)

        val outcome = downloader.execute(
            request = HttpRangeDownloader.DownloadRequest(
                downloadId = downloadId,
                url = url,
                destinationFile = destinationFile,
                headers = headers
            ),
            onProgress = { snapshot ->
                // Emit event to Capacitor plugin
                ResumableDownloadPlugin.emitProgress(snapshot)

                // Update notification if enabled
                if (notificationEnabled && snapshot.status == HttpRangeDownloader.ProgressSnapshot.Status.DOWNLOADING) {
                    runCatching {
                        val progressText = formatProgressText(snapshot.bytesDownloaded, snapshot.totalBytes)
                        val builder = NotificationCompat.Builder(context, channelId)
                            .setContentTitle(title)
                            .setContentText(subtitle ?: progressText)
                            .setSmallIcon(android.R.drawable.stat_sys_download)
                            .setOngoing(true)
                            .setOnlyAlertOnce(true)
                            .setProgress(100, snapshot.progress, snapshot.totalBytes <= 0)

                        notificationManager.notify(notificationId, builder.build())
                    }
                }
            },
            onDiagnostic = { level, message ->
                ResumableDownloadPlugin.emitDiagnostic(downloadId, level, message)
            },
            isCancelled = { isStopped },
            isPaused = { isPaused(downloadId) }
        )

        clearPause(downloadId)

        return when (outcome) {
            is HttpRangeDownloader.DownloadOutcome.Success -> {
                if (notificationEnabled) {
                    showCompletionNotification(channelId, title, destinationFile.name)
                }
                Result.success(
                    workDataOf(
                        KEY_DOWNLOAD_ID to downloadId,
                        KEY_OUTPUT_URI to destinationFile.toURI().toString(),
                        KEY_OUTPUT_SIZE to outcome.size,
                        KEY_OUTPUT_RESUMED to outcome.resumed
                    )
                )
            }
            is HttpRangeDownloader.DownloadOutcome.Paused -> {
                if (notificationEnabled) {
                    notificationManager.cancel(notificationId)
                }
                Result.success(
                    workDataOf(
                        KEY_DOWNLOAD_ID to downloadId,
                        KEY_OUTPUT_SIZE to outcome.bytesDownloaded,
                        KEY_OUTPUT_RESUMED to true
                    )
                )
            }
            is HttpRangeDownloader.DownloadOutcome.Cancelled -> {
                if (notificationEnabled) {
                    notificationManager.cancel(notificationId)
                }
                Result.failure(
                    workDataOf(
                        KEY_DOWNLOAD_ID to downloadId,
                        KEY_OUTPUT_ERROR to "Cancelled"
                    )
                )
            }
            is HttpRangeDownloader.DownloadOutcome.Failure -> {
                if (notificationEnabled) {
                    notificationManager.cancel(notificationId)
                }
                Result.failure(
                    workDataOf(
                        KEY_DOWNLOAD_ID to downloadId,
                        KEY_OUTPUT_ERROR to (outcome.error.message ?: "Download failed")
                    )
                )
            }
        }
    }

    private fun createForegroundInfo(
        channelId: String,
        title: String,
        subtitle: String,
        progress: Int,
        indeterminate: Boolean
    ): ForegroundInfo {
        val builder = NotificationCompat.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, indeterminate)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                builder.build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(notificationId, builder.build())
        }
    }

    private fun showCompletionNotification(channelId: String, title: String, filename: String) {
        val builder = NotificationCompat.Builder(context, channelId)
            .setContentTitle("Download Complete")
            .setContentText(filename)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)

        notificationManager.notify(notificationId, builder.build())
    }

    private fun createNotificationChannel(channelId: String, channelName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Displays file download progress"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun formatProgressText(bytes: Long, total: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (total > 0) {
            val totalMb = total / (1024.0 * 1024.0)
            String.format(java.util.Locale.US, "%.1f MB / %.1f MB", mb, totalMb)
        } else {
            String.format(java.util.Locale.US, "%.1f MB downloaded", mb)
        }
    }
}
