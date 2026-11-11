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
import okhttp3.Call
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Jetpack CoroutineWorker acting as the resilient execution engine for background downloads.
 *
 * When notification.enabled == true, attempts to promote to foreground service via setForeground.
 * If notification permissions are not granted or OS limits prevent foreground service promotion,
 * the error is captured in diagnostic telemetry, and the download seamlessly proceeds in-app.
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

        const val KEY_OUTPUT_STATUS = "outputStatus"
        const val KEY_OUTPUT_URI = "outputUri"
        const val KEY_OUTPUT_SIZE = "outputSize"
        const val KEY_OUTPUT_RESUMED = "outputResumed"
        const val KEY_OUTPUT_ERROR = "outputError"
        const val KEY_OUTPUT_ERROR_CLASS = "outputErrorClass"
        const val KEY_OUTPUT_HTTP_STATUS = "outputHttpStatus"
        const val KEY_OUTPUT_CAN_RESUME = "outputCanResume"

        private const val DEFAULT_CHANNEL_ID = "picsa_file_downloads"
        private const val DEFAULT_CHANNEL_NAME = "File Downloads"

        // Active pause flags tracked by downloadId
        private val pausedTasks = ConcurrentHashMap<String, Boolean>()
        // Explicit user cancel flags tracked by downloadId
        private val cancelledTasks = ConcurrentHashMap<String, Boolean>()
        // In-flight OkHttp calls for immediate socket cancellation
        private val activeCalls = ConcurrentHashMap<String, Call>()

        fun setPaused(downloadId: String, paused: Boolean) {
            pausedTasks[downloadId] = paused
            if (paused) {
                activeCalls[downloadId]?.cancel()
            }
        }

        fun isPaused(downloadId: String): Boolean = pausedTasks[downloadId] == true

        fun clearPause(downloadId: String) {
            pausedTasks.remove(downloadId)
        }

        fun setCancelled(downloadId: String, cancelled: Boolean) {
            cancelledTasks[downloadId] = cancelled
            if (cancelled) {
                activeCalls[downloadId]?.cancel()
            }
        }

        fun isCancelled(downloadId: String): Boolean = cancelledTasks[downloadId] == true

        fun clearCancel(downloadId: String) {
            cancelledTasks.remove(downloadId)
        }

        fun registerCall(downloadId: String, call: Call) {
            activeCalls[downloadId] = call
        }

        fun unregisterCall(downloadId: String) {
            activeCalls.remove(downloadId)
        }
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private var notificationId = 0
    private var foregroundPromoted = false

    override suspend fun doWork(): Result {
        val downloadId = inputData.getString(KEY_DOWNLOAD_ID) ?: return Result.failure()

        // Automatically cancel any active HTTP call when coroutine is cancelled / worker stopped
        kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.invokeOnCompletion {
            activeCalls[downloadId]?.cancel()
        }

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
            } catch (e: Exception) {
                ResumableDownloadPlugin.emitDiagnostic(
                    downloadId = downloadId,
                    level = "warn",
                    message = "Could not parse headers JSON: ${e.message}",
                    code = "HEADERS_PARSE_ERROR"
                )
            }
        }

        createNotificationChannel(channelId, channelName)

        if (notificationEnabled) {
            // Android 13+ (API 33+) notification permission verification
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (!notificationManager.areNotificationsEnabled()) {
                    ResumableDownloadPlugin.emitDiagnostic(
                        downloadId = downloadId,
                        level = "warn",
                        message = "Notification requested but POST_NOTIFICATIONS permission is not granted. Notification will not be visible.",
                        code = "NOTIFICATION_PERMISSION_MISSING"
                    )
                }
            }

            runCatching {
                val initialInfo = createForegroundInfo(
                    channelId = channelId,
                    title = title,
                    subtitle = subtitle ?: "Starting download...",
                    progress = 0,
                    indeterminate = true
                )
                setForeground(initialInfo)
                foregroundPromoted = true
            }.onFailure { err ->
                if (err is kotlinx.coroutines.CancellationException) throw err
                foregroundPromoted = false
                ResumableDownloadPlugin.emitDiagnostic(
                    downloadId = downloadId,
                    level = "warn",
                    message = "Could not promote to foreground service (${err.javaClass.simpleName}: ${err.message}). Proceeding with in-app download.",
                    code = "FOREGROUND_SERVICE_FAILED",
                    contextMap = mapOf("exceptionClass" to err.javaClass.simpleName)
                )
            }
        }

        val destinationFile = File(destPath)
        val downloader = HttpRangeDownloader()

        val outcome = try {
            downloader.execute(
                request = HttpRangeDownloader.DownloadRequest(
                    downloadId = downloadId,
                    url = url,
                    destinationFile = destinationFile,
                    headers = headers
                ),
                onProgress = { snapshot ->
                    ResumableDownloadPlugin.emitProgress(snapshot)

                    if (notificationEnabled && foregroundPromoted && snapshot.status == HttpRangeDownloader.ProgressSnapshot.Status.DOWNLOADING) {
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
                onDiagnostic = { level, message, code, contextMap ->
                    ResumableDownloadPlugin.emitDiagnostic(downloadId, level, message, code, contextMap)
                },
                onCallCreated = { call ->
                    registerCall(downloadId, call)
                    // Close the race window if pause/cancel/stop occurred before call registration
                    if (isPaused(downloadId) || isCancelled(downloadId) || isStopped) {
                        call.cancel()
                    }
                },
                isCancelled = { isCancelled(downloadId) },
                isPaused = { isPaused(downloadId) },
                isStopped = { isStopped && !isCancelled(downloadId) }
            )
        } finally {
            unregisterCall(downloadId)
        }

        return when (outcome) {
            is HttpRangeDownloader.DownloadOutcome.Success -> {
                clearPause(downloadId)
                clearCancel(downloadId)
                if (notificationEnabled && foregroundPromoted) {
                    showCompletionNotification(channelId, title, destinationFile.name)
                }
                Result.success(
                    workDataOf(
                        KEY_DOWNLOAD_ID to downloadId,
                        KEY_OUTPUT_STATUS to "completed",
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
                        KEY_OUTPUT_STATUS to "paused",
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
                        KEY_OUTPUT_STATUS to "cancelled",
                        KEY_OUTPUT_ERROR to "Download was cancelled.",
                        KEY_OUTPUT_ERROR_CLASS to "CancellationException",
                        KEY_OUTPUT_SIZE to outcome.bytesDownloaded,
                        KEY_OUTPUT_CAN_RESUME to false
                    )
                )
            }
            is HttpRangeDownloader.DownloadOutcome.Failure -> {
                if (notificationEnabled) {
                    notificationManager.cancel(notificationId)
                }
                val err = outcome.error
                val errClass = err.javaClass.simpleName
                val errMsg = err.message ?: errClass
                val httpStatus = if (err is HttpRangeDownloader.HttpException) err.statusCode else -1
                Result.failure(
                    workDataOf(
                        KEY_DOWNLOAD_ID to downloadId,
                        KEY_OUTPUT_STATUS to "error",
                        KEY_OUTPUT_ERROR to errMsg,
                        KEY_OUTPUT_ERROR_CLASS to errClass,
                        KEY_OUTPUT_SIZE to outcome.bytesDownloaded,
                        KEY_OUTPUT_CAN_RESUME to outcome.canResume,
                        KEY_OUTPUT_HTTP_STATUS to httpStatus
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
