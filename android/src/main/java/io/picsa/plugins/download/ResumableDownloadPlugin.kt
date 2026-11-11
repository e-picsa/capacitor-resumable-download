package io.picsa.plugins.download

import android.Manifest
import android.os.Build
import androidx.lifecycle.Observer
import androidx.work.*
import com.getcapacitor.*
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@CapacitorPlugin(
    name = "ResumableDownload",
    permissions = [
        Permission(
            strings = [Manifest.permission.POST_NOTIFICATIONS],
            alias = "notifications"
        )
    ]
)
class ResumableDownloadPlugin : Plugin() {

    companion object {
        private var instance: ResumableDownloadPlugin? = null

        fun emitProgress(snapshot: HttpRangeDownloader.ProgressSnapshot) {
            val inst = instance ?: return
            val ret = JSObject().apply {
                put("downloadId", snapshot.downloadId)
                put("bytesDownloaded", snapshot.bytesDownloaded)
                put("totalBytes", snapshot.totalBytes)
                put("progress", snapshot.progress)
                put("status", snapshot.status.name.lowercase())
                put("resumedFromBytes", snapshot.resumedFromBytes)
                put("throughputBytesPerSecond", snapshot.throughputBytesPerSecond)
            }
            inst.notifyListeners("downloadProgress", ret)
        }

        fun emitDiagnostic(downloadId: String, level: String, message: String) {
            val inst = instance ?: return
            val ret = JSObject().apply {
                put("downloadId", downloadId)
                put("level", level)
                put("message", message)
                put("timestamp", System.currentTimeMillis())
            }
            inst.notifyListeners("downloadDiagnostic", ret)
        }
    }

    // Cache of active or recent download configs to enable easy resume
    private val savedOptions = ConcurrentHashMap<String, DownloadConfig>()

    private data class DownloadConfig(
        val downloadId: String,
        val url: String,
        val destinationPath: String,
        val directory: String,
        val headersJson: String?,
        val notificationEnabled: Boolean,
        val notificationTitle: String?,
        val notificationSubtitle: String?,
        val channelName: String?,
        val channelId: String?
    )

    override fun load() {
        super.load()
        instance = this
    }

    override fun handleOnDestroy() {
        if (instance == this) {
            instance = null
        }
        super.handleOnDestroy()
    }

    @PluginMethod
    fun download(call: PluginCall) {
        val downloadId = call.getString("downloadId")
        val url = call.getString("url")
        val destRelativePath = call.getString("destinationPath")

        if (downloadId.isNullOrBlank() || url.isNullOrBlank() || destRelativePath.isNullOrBlank()) {
            call.reject("downloadId, url, and destinationPath are mandatory arguments.")
            return
        }

        val directory = call.getString("directory") ?: "DATA"
        val headersObj = call.getObject("headers")
        val notificationObj = call.getObject("notification")

        val notificationEnabled = notificationObj?.optBoolean("enabled", false) ?: false
        val notificationTitle = notificationObj?.optString("title", "Downloading file")
        val notificationSubtitle = notificationObj?.optString("subtitle")
        val channelName = notificationObj?.optString("channelName")
        val channelId = notificationObj?.optString("channelId")

        val destFile: File
        try {
            destFile = resolveDestinationFile(destRelativePath, directory)
        } catch (e: IllegalArgumentException) {
            call.reject(e.message)
            return
        }

        val config = DownloadConfig(
            downloadId = downloadId,
            url = url,
            destinationPath = destFile.absolutePath,
            directory = directory,
            headersJson = headersObj?.toString(),
            notificationEnabled = notificationEnabled,
            notificationTitle = notificationTitle,
            notificationSubtitle = notificationSubtitle,
            channelName = channelName,
            channelId = channelId
        )
        savedOptions[downloadId] = config

        enqueueWorker(config, call)
    }

    @PluginMethod
    fun pause(call: PluginCall) {
        val downloadId = call.getString("downloadId")
        if (downloadId.isNullOrBlank()) {
            call.reject("downloadId is required to pause.")
            return
        }

        DownloadWorker.setPaused(downloadId, true)
        call.resolve()
    }

    @PluginMethod
    fun resume(call: PluginCall) {
        val downloadId = call.getString("downloadId")
        if (downloadId.isNullOrBlank()) {
            call.reject("downloadId is required to resume.")
            return
        }

        val config = savedOptions[downloadId]
        if (config == null) {
            call.reject("No existing download found for downloadId: $downloadId. Use download() to initiate.")
            return
        }

        DownloadWorker.clearPause(downloadId)
        enqueueWorker(config, call)
    }

    @PluginMethod
    fun cancel(call: PluginCall) {
        val downloadId = call.getString("downloadId")
        if (downloadId.isNullOrBlank()) {
            call.reject("downloadId is required to cancel.")
            return
        }

        WorkManager.getInstance(context).cancelUniqueWork("download_$downloadId")

        val config = savedOptions.remove(downloadId)
        if (config != null) {
            val destFile = File(config.destinationPath)
            val partFile = File(destFile.absolutePath + ".part")
            val metaFile = File(destFile.absolutePath + ".part.meta.json")
            if (partFile.exists()) partFile.delete()
            if (metaFile.exists()) metaFile.delete()
        }

        call.resolve()
    }

    @PluginMethod
    fun getStatus(call: PluginCall) {
        val downloadId = call.getString("downloadId")
        if (downloadId.isNullOrBlank()) {
            call.reject("downloadId is required.")
            return
        }

        val workInfos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("download_$downloadId")
            .get()

        if (workInfos.isNullOrEmpty()) {
            val ret = JSObject().apply {
                put("downloadId", downloadId)
                put("status", "idle")
                put("bytesDownloaded", 0)
                put("totalBytes", -1)
            }
            call.resolve(ret)
            return
        }

        val info = workInfos[0]
        val statusStr = when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING -> "downloading"
            WorkInfo.State.SUCCEEDED -> "completed"
            WorkInfo.State.FAILED -> "error"
            WorkInfo.State.CANCELLED -> "cancelled"
            WorkInfo.State.BLOCKED -> "downloading"
        }

        val output = info.outputData
        val ret = JSObject().apply {
            put("downloadId", downloadId)
            put("status", statusStr)
            put("bytesDownloaded", output.getLong(DownloadWorker.KEY_OUTPUT_SIZE, 0L))
            put("totalBytes", -1)
            output.getString(DownloadWorker.KEY_OUTPUT_URI)?.let { put("uri", it) }
        }
        call.resolve(ret)
    }

    @PluginMethod
    fun getFileStatus(call: PluginCall) {
        val destRelativePath = call.getString("destinationPath")
        if (destRelativePath.isNullOrBlank()) {
            call.reject("destinationPath is required.")
            return
        }
        val directory = call.getString("directory") ?: "DATA"
        val file: File
        try {
            file = resolveDestinationFile(destRelativePath, directory)
        } catch (e: IllegalArgumentException) {
            call.reject(e.message)
            return
        }

        val partFile = File(file.absolutePath + ".part")
        val metaFile = File(file.absolutePath + ".part.meta.json")

        var etag: String? = null
        if (metaFile.exists()) {
            try {
                val json = JSONObject(metaFile.readText(Charsets.UTF_8))
                if (json.has("etag")) {
                    etag = json.getString("etag")
                }
            } catch (_: Exception) {}
        }

        val ret = JSObject().apply {
            put("path", file.absolutePath)
            put("exists", file.exists())
            put("partExists", partFile.exists())
            put("size", if (file.exists()) file.length() else 0L)
            put("partSize", if (partFile.exists()) partFile.length() else 0L)
            if (etag != null) {
                put("etag", etag)
            }
        }
        call.resolve(ret)
    }

    @PluginMethod
    override fun checkPermissions(call: PluginCall) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            val ret = JSObject()
            ret.put("display", "granted")
            call.resolve(ret)
            return
        }
        super.checkPermissions(call)
    }

    @PluginMethod
    override fun requestPermissions(call: PluginCall) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            val ret = JSObject()
            ret.put("display", "granted")
            call.resolve(ret)
            return
        }
        super.requestPermissions(call)
    }

    private fun enqueueWorker(config: DownloadConfig, call: PluginCall) {
        val inputData = workDataOf(
            DownloadWorker.KEY_DOWNLOAD_ID to config.downloadId,
            DownloadWorker.KEY_URL to config.url,
            DownloadWorker.KEY_DEST_PATH to config.destinationPath,
            DownloadWorker.KEY_HEADERS_JSON to config.headersJson,
            DownloadWorker.KEY_NOTIFICATION_ENABLED to config.notificationEnabled,
            DownloadWorker.KEY_NOTIFICATION_TITLE to config.notificationTitle,
            DownloadWorker.KEY_NOTIFICATION_SUBTITLE to config.notificationSubtitle,
            DownloadWorker.KEY_CHANNEL_NAME to config.channelName,
            DownloadWorker.KEY_CHANNEL_ID to config.channelId
        )

        val workRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(inputData)
            .addTag("download")
            .addTag(config.downloadId)
            .build()

        val workManager = WorkManager.getInstance(context)
        workManager.enqueueUniqueWork(
            "download_${config.downloadId}",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )

        // Observe completion on the main thread
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val liveData = workManager.getWorkInfoByIdLiveData(workRequest.id)
            val observer = object : Observer<WorkInfo?> {
                override fun onChanged(value: WorkInfo?) {
                    if (value == null) return
                    if (value.state.isFinished) {
                        liveData.removeObserver(this)
                        when (value.state) {
                            WorkInfo.State.SUCCEEDED -> {
                                val output = value.outputData
                                val ret = JSObject().apply {
                                    put("downloadId", config.downloadId)
                                    put("uri", output.getString(DownloadWorker.KEY_OUTPUT_URI) ?: "")
                                    put("size", output.getLong(DownloadWorker.KEY_OUTPUT_SIZE, 0L))
                                    put("resumed", output.getBoolean(DownloadWorker.KEY_OUTPUT_RESUMED, false))
                                }
                                call.resolve(ret)
                            }
                            WorkInfo.State.CANCELLED -> {
                                call.reject("Download was cancelled.")
                            }
                            WorkInfo.State.FAILED -> {
                                val errMsg = value.outputData.getString(DownloadWorker.KEY_OUTPUT_ERROR)
                                    ?: "Download failed."
                                call.reject(errMsg)
                            }
                            else -> {}
                        }
                    }
                }
            }
            liveData.observeForever(observer)
        }
    }

    private fun resolveDestinationFile(relativePath: String, directory: String): File {
        val baseDir: File = when (directory.uppercase()) {
            "CACHE" -> context.cacheDir
            "EXTERNAL" -> context.getExternalFilesDir(null) ?: context.filesDir
            else -> context.filesDir // "DATA"
        }

        val cleaned = relativePath.trimStart('/')
        val canonicalBase = baseDir.canonicalFile
        val file = File(canonicalBase, cleaned).canonicalFile
        if (file != canonicalBase && !file.toPath().startsWith(canonicalBase.toPath())) {
            throw IllegalArgumentException("Destination path must remain inside the selected directory.")
        }
        val parent = file.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        return file
    }
}
