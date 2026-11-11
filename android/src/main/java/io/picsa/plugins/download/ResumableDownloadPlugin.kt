package io.picsa.plugins.download

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.lifecycle.Observer
import androidx.work.*
import com.getcapacitor.*
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

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
        private const val TAG = "ResumableDownload"
        private const val PREFS_NAME = "picsa_resumable_downloads"
        private const val MAX_DIAGNOSTICS_BUFFER = 100

        private var instance: ResumableDownloadPlugin? = null
        private val diagnosticBuffer = ConcurrentLinkedQueue<JSObject>()

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

        fun emitDiagnostic(
            downloadId: String,
            level: String,
            message: String,
            code: String? = null,
            contextMap: Map<String, Any?>? = null
        ) {
            // 1. Android Logcat output for developer inspection
            val logMessage = if (code != null) "[$downloadId][$code] $message" else "[$downloadId] $message"
            when (level.lowercase()) {
                "error" -> Log.e(TAG, logMessage)
                "warn" -> Log.w(TAG, logMessage)
                else -> Log.i(TAG, logMessage)
            }

            // 2. Structured JSObject event payload
            val ret = JSObject().apply {
                put("downloadId", downloadId)
                put("level", level)
                put("message", message)
                put("timestamp", System.currentTimeMillis())
                if (code != null) put("code", code)
                if (!contextMap.isNullOrEmpty()) {
                    val ctx = JSObject()
                    for ((k, v) in contextMap) {
                        ctx.put(k, v)
                    }
                    put("context", ctx)
                }
            }

            // 3. Keep in-memory ring buffer for post-mortem diagnostics
            diagnosticBuffer.add(ret)
            while (diagnosticBuffer.size > MAX_DIAGNOSTICS_BUFFER) {
                diagnosticBuffer.poll()
            }

            // 4. Notify JS listeners if active
            instance?.notifyListeners("downloadDiagnostic", ret)
        }
    }

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
    ) {
        fun toJson(): String {
            val json = JSONObject()
            json.put("downloadId", downloadId)
            json.put("url", url)
            json.put("destinationPath", destinationPath)
            json.put("directory", directory)
            if (headersJson != null) json.put("headersJson", headersJson)
            json.put("notificationEnabled", notificationEnabled)
            if (notificationTitle != null) json.put("notificationTitle", notificationTitle)
            if (notificationSubtitle != null) json.put("notificationSubtitle", notificationSubtitle)
            if (channelName != null) json.put("channelName", channelName)
            if (channelId != null) json.put("channelId", channelId)
            return json.toString()
        }

        companion object {
            fun fromJson(jsonStr: String): DownloadConfig? {
                return try {
                    val json = JSONObject(jsonStr)
                    DownloadConfig(
                        downloadId = json.getString("downloadId"),
                        url = json.getString("url"),
                        destinationPath = json.getString("destinationPath"),
                        directory = json.optString("directory", "DATA"),
                        headersJson = if (json.has("headersJson")) json.getString("headersJson") else null,
                        notificationEnabled = json.optBoolean("notificationEnabled", false),
                        notificationTitle = if (json.has("notificationTitle")) json.getString("notificationTitle") else null,
                        notificationSubtitle = if (json.has("notificationSubtitle")) json.getString("notificationSubtitle") else null,
                        channelName = if (json.has("channelName")) json.getString("channelName") else null,
                        channelId = if (json.has("channelId")) json.getString("channelId") else null
                    )
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    private val savedOptions = ConcurrentHashMap<String, DownloadConfig>()
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

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

        saveConfig(config)
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
        WorkManager.getInstance(context).cancelUniqueWork("download_$downloadId")
        call.resolve()
    }

    @PluginMethod
    fun resume(call: PluginCall) {
        val downloadId = call.getString("downloadId")
        if (downloadId.isNullOrBlank()) {
            call.reject("downloadId is required to resume.")
            return
        }

        // 1. Try in-memory or persisted config
        var config = loadConfig(downloadId)

        // 2. If options were explicitly provided in the resume call, merge or use them
        val url = call.getString("url")
        val destRelativePath = call.getString("destinationPath")
        if (!url.isNullOrBlank() && !destRelativePath.isNullOrBlank()) {
            val directory = call.getString("directory") ?: config?.directory ?: "DATA"
            val destFile: File
            try {
                destFile = resolveDestinationFile(destRelativePath, directory)
            } catch (e: IllegalArgumentException) {
                call.reject(e.message)
                return
            }

            val headersObj = call.getObject("headers")
            val notificationObj = call.getObject("notification")

            config = DownloadConfig(
                downloadId = downloadId,
                url = url,
                destinationPath = destFile.absolutePath,
                directory = directory,
                headersJson = headersObj?.toString() ?: config?.headersJson,
                notificationEnabled = notificationObj?.optBoolean("enabled", config?.notificationEnabled ?: false) ?: (config?.notificationEnabled ?: false),
                notificationTitle = notificationObj?.optString("title", config?.notificationTitle ?: "Downloading file") ?: config?.notificationTitle,
                notificationSubtitle = notificationObj?.optString("subtitle") ?: config?.notificationSubtitle,
                channelName = notificationObj?.optString("channelName") ?: config?.channelName,
                channelId = notificationObj?.optString("channelId") ?: config?.channelId
            )
            saveConfig(config)
        }

        if (config == null) {
            call.reject("No existing download found for downloadId: $downloadId. Supply options or use download() to initiate.")
            return
        }

        DownloadWorker.clearPause(downloadId)
        DownloadWorker.clearCancel(downloadId)
        enqueueWorker(config, call)
    }

    @PluginMethod
    fun cancel(call: PluginCall) {
        val downloadId = call.getString("downloadId")
        if (downloadId.isNullOrBlank()) {
            call.reject("downloadId is required to cancel.")
            return
        }

        // Set explicit cancellation flag to abort active socket and trigger file purge in worker
        DownloadWorker.setCancelled(downloadId, true)
        WorkManager.getInstance(context).cancelUniqueWork("download_$downloadId")

        val config = removeConfig(downloadId)
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
        val output = info.outputData
        val workerStatus = output.getString(DownloadWorker.KEY_OUTPUT_STATUS)

        val statusStr = when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING -> "downloading"
            WorkInfo.State.SUCCEEDED -> workerStatus ?: "completed"
            WorkInfo.State.FAILED -> "error"
            WorkInfo.State.CANCELLED -> "cancelled"
            WorkInfo.State.BLOCKED -> "downloading"
        }

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
            } catch (e: Exception) {
                emitDiagnostic(
                    downloadId = "inspect",
                    level = "warn",
                    message = "Could not parse meta sidecar during getFileStatus: ${e.message}",
                    code = "SIDECAR_READ_ERROR"
                )
            }
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

        // Observe worker completion on main thread to resolve or reject the Capacitor PluginCall
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val liveData = workManager.getWorkInfoByIdLiveData(workRequest.id)
            val observer = object : Observer<WorkInfo?> {
                override fun onChanged(value: WorkInfo?) {
                    if (value == null) return
                    if (value.state.isFinished) {
                        liveData.removeObserver(this)
                        val output = value.outputData
                        when (value.state) {
                            WorkInfo.State.SUCCEEDED -> {
                                val status = output.getString(DownloadWorker.KEY_OUTPUT_STATUS) ?: "completed"
                                val ret = JSObject().apply {
                                    put("downloadId", config.downloadId)
                                    put("status", status)
                                    put("uri", output.getString(DownloadWorker.KEY_OUTPUT_URI) ?: "")
                                    put("size", output.getLong(DownloadWorker.KEY_OUTPUT_SIZE, 0L))
                                    put("resumed", output.getBoolean(DownloadWorker.KEY_OUTPUT_RESUMED, false))
                                }
                                if (status == "completed") {
                                    removeConfig(config.downloadId)
                                }
                                call.resolve(ret)
                            }
                            WorkInfo.State.CANCELLED -> {
                                val errorData = JSObject().apply {
                                    put("downloadId", config.downloadId)
                                    put("exceptionClass", "CancellationException")
                                    put("canResume", false)
                                    put("bytesDownloaded", 0L)
                                }
                                call.reject("Download was cancelled.", "CancellationException", errorData)
                            }
                            WorkInfo.State.FAILED -> {
                                val errMsg = output.getString(DownloadWorker.KEY_OUTPUT_ERROR)
                                    ?: "Download failed."
                                val errClass = output.getString(DownloadWorker.KEY_OUTPUT_ERROR_CLASS)
                                    ?: "IOException"
                                val canResume = output.getBoolean(DownloadWorker.KEY_OUTPUT_CAN_RESUME, false)
                                val size = output.getLong(DownloadWorker.KEY_OUTPUT_SIZE, 0L)
                                val httpStatus = output.getInt(DownloadWorker.KEY_OUTPUT_HTTP_STATUS, -1)

                                val errorData = JSObject().apply {
                                    put("downloadId", config.downloadId)
                                    put("exceptionClass", errClass)
                                    put("canResume", canResume)
                                    put("bytesDownloaded", size)
                                    if (httpStatus > 0) put("httpStatus", httpStatus)
                                }
                                call.reject(errMsg, errClass, errorData)
                            }
                            else -> {}
                        }
                    }
                }
            }
            liveData.observeForever(observer)
        }
    }

    /**
     * Resolves destination file inside the sandboxed Capacitor directory.
     * Uses canonicalPath string comparison to remain compatible with Android 7.0/7.1 (API 24/25)
     * without triggering java.nio.file.Path NewApi lint errors.
     */
    private fun resolveDestinationFile(relativePath: String, directory: String): File {
        val baseDir: File = when (directory.uppercase()) {
            "CACHE" -> context.cacheDir
            "EXTERNAL" -> context.getExternalFilesDir(null) ?: context.filesDir
            else -> context.filesDir // "DATA"
        }

        val cleaned = relativePath.trimStart('/')
        val canonicalBase = baseDir.canonicalFile
        val file = File(canonicalBase, cleaned).canonicalFile

        val basePrefix = canonicalBase.canonicalPath.trimEnd(File.separatorChar) + File.separatorChar
        val filePath = file.canonicalPath

        if (filePath != canonicalBase.canonicalPath && !filePath.startsWith(basePrefix)) {
            throw IllegalArgumentException("Destination path must remain inside the selected directory.")
        }
        val parent = file.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        return file
    }

    private fun saveConfig(config: DownloadConfig) {
        savedOptions[config.downloadId] = config
        prefs.edit().putString(config.downloadId, config.toJson()).apply()
    }

    private fun loadConfig(downloadId: String): DownloadConfig? {
        savedOptions[downloadId]?.let { return it }
        val jsonStr = prefs.getString(downloadId, null) ?: return null
        return DownloadConfig.fromJson(jsonStr)?.also {
            savedOptions[downloadId] = it
        }
    }

    private fun removeConfig(downloadId: String): DownloadConfig? {
        val removed = savedOptions.remove(downloadId)
        prefs.edit().remove(downloadId).apply()
        return removed
    }
}
