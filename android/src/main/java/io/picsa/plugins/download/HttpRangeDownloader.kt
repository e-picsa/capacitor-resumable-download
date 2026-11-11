package io.picsa.plugins.download

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

/**
 * Pure Kotlin HTTP Range Downloader with zero Android framework dependencies.
 *
 * Designed for 100% JVM unit testability with OkHttp MockWebServer.
 * Manages .part files and .part.meta.json sidecars to support robust,
 * interrupted-stream resumption over unstable mobile networks.
 */
class HttpRangeDownloader(
    private val client: OkHttpClient = defaultClient()
) {

    companion object {
        fun defaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }
    }

    data class DownloadRequest(
        val downloadId: String,
        val url: String,
        val destinationFile: File,
        val headers: Map<String, String> = emptyMap()
    )

    data class ProgressSnapshot(
        val downloadId: String,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val progress: Int, // 0..100
        val status: Status,
        val resumedFromBytes: Long = 0,
        val throughputBytesPerSecond: Long = 0
    ) {
        enum class Status {
            DOWNLOADING,
            PAUSED,
            COMPLETED,
            ERROR,
            CANCELLED
        }
    }

    sealed class DownloadOutcome {
        data class Success(
            val downloadId: String,
            val destinationFile: File,
            val size: Long,
            val mimeType: String?,
            val resumed: Boolean
        ) : DownloadOutcome()

        data class Paused(
            val downloadId: String,
            val bytesDownloaded: Long,
            val totalBytes: Long
        ) : DownloadOutcome()

        data class Cancelled(
            val downloadId: String,
            val bytesDownloaded: Long
        ) : DownloadOutcome()

        data class Failure(
            val downloadId: String,
            val error: Throwable,
            val bytesDownloaded: Long,
            val canResume: Boolean
        ) : DownloadOutcome()
    }

    data class MetaSidecar(
        val url: String,
        val etag: String?,
        val totalBytes: Long
    ) {
        fun toJson(): String {
            val json = JSONObject()
            json.put("url", url)
            if (etag != null) json.put("etag", etag)
            json.put("totalBytes", totalBytes)
            return json.toString()
        }

        companion object {
            fun fromJson(jsonStr: String): MetaSidecar? {
                return try {
                    val json = JSONObject(jsonStr)
                    MetaSidecar(
                        url = json.getString("url"),
                        etag = if (json.has("etag")) json.getString("etag") else null,
                        totalBytes = json.optLong("totalBytes", -1L)
                    )
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    /**
     * Executes the download with support for pause, cancellation, and byte-range resumption.
     */
    fun execute(
        request: DownloadRequest,
        onProgress: (ProgressSnapshot) -> Unit = {},
        onDiagnostic: (level: String, message: String) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
        isPaused: () -> Boolean = { false }
    ): DownloadOutcome {
        val destFile = request.destinationFile
        val parentDir = destFile.parentFile
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs()
        }

        val partFile = File(destFile.absolutePath + ".part")
        val metaFile = File(destFile.absolutePath + ".part.meta.json")

        var sidecar = readMetaSidecar(metaFile)
        var partLength = if (partFile.exists()) partFile.length() else 0L

        // If part file exists but sidecar URL is different, discard corrupt/stale partial file
        if (partFile.exists() && sidecar != null && sidecar.url != request.url) {
            onDiagnostic("warn", "Existing .part file belongs to a different URL. Purging.")
            partFile.delete()
            metaFile.delete()
            partLength = 0L
            sidecar = null
        }

        val initialOffset = partLength
        var resumed = false

        val httpRequestBuilder = Request.Builder().url(request.url)
        for ((k, v) in request.headers) {
            httpRequestBuilder.addHeader(k, v)
        }

        if (partLength > 0L) {
            httpRequestBuilder.addHeader("Range", "bytes=$partLength-")
            if (!sidecar?.etag.isNullOrBlank()) {
                httpRequestBuilder.addHeader("If-Range", sidecar!!.etag!!)
            }
        }

        val call = client.newCall(httpRequestBuilder.build())
        val response: Response
        try {
            response = call.execute()
        } catch (e: IOException) {
            onProgress(
                ProgressSnapshot(
                    downloadId = request.downloadId,
                    bytesDownloaded = partLength,
                    totalBytes = sidecar?.totalBytes ?: -1L,
                    progress = calculateProgress(partLength, sidecar?.totalBytes ?: -1L),
                    status = ProgressSnapshot.Status.ERROR
                )
            )
            return DownloadOutcome.Failure(
                downloadId = request.downloadId,
                error = e,
                bytesDownloaded = partLength,
                canResume = partFile.exists() && partFile.length() > 0
            )
        }

        return response.use { resp ->
            val code = resp.code
            val etagHeader = resp.header("ETag")?.trim()
            val mimeType = resp.header("Content-Type")

            // Case 1: 416 Range Not Satisfiable
            if (code == 416) {
                onDiagnostic("warn", "Server responded with HTTP 416 Range Not Satisfiable. Resetting partial file.")
                partFile.delete()
                metaFile.delete()
                // Retry once from byte 0
                return execute(
                    request = request,
                    onProgress = onProgress,
                    onDiagnostic = onDiagnostic,
                    isCancelled = isCancelled,
                    isPaused = isPaused
                )
            }

            // Case 2: Server returned non-success code
            if (!resp.isSuccessful && code != 206) {
                val err = IOException("HTTP ${resp.code} ${resp.message}")
                onProgress(
                    ProgressSnapshot(
                        downloadId = request.downloadId,
                        bytesDownloaded = partLength,
                        totalBytes = -1L,
                        progress = 0,
                        status = ProgressSnapshot.Status.ERROR
                    )
                )
                return DownloadOutcome.Failure(
                    downloadId = request.downloadId,
                    error = err,
                    bytesDownloaded = partLength,
                    canResume = false
                )
            }

            var totalBytes: Long
            val writeMode: String

            if (code == 206) {
                // Partial Content accepted
                resumed = true
                val contentRange = resp.header("Content-Range")
                totalBytes = parseTotalBytesFromContentRange(contentRange)
                if (totalBytes <= 0 && sidecar != null && sidecar.totalBytes > 0) {
                    totalBytes = sidecar.totalBytes
                }
                writeMode = "rw"
            } else {
                // HTTP 200 OK: either brand new download or range was rejected/reset
                if (partLength > 0L) {
                    onDiagnostic("warn", "Server returned HTTP 200 OK for Range request. Restarting from byte 0.")
                    partLength = 0L
                }
                val cl = resp.header("Content-Length")
                totalBytes = cl?.toLongOrNull() ?: -1L
                writeMode = "rw"
            }

            // Persist updated sidecar
            val newSidecar = MetaSidecar(
                url = request.url,
                etag = etagHeader ?: sidecar?.etag,
                totalBytes = totalBytes
            )
            writeMetaSidecar(metaFile, newSidecar)

            val body = resp.body ?: return DownloadOutcome.Failure(
                downloadId = request.downloadId,
                error = IOException("Response body is null"),
                bytesDownloaded = partLength,
                canResume = false
            )

            var currentBytes = partLength
            var lastEmitTimeMs = System.currentTimeMillis()
            var lastEmitPercent = calculateProgress(currentBytes, totalBytes)
            var bytesSinceLastEmit = 0L

            try {
                RandomAccessFile(partFile, writeMode).use { raf ->
                    if (code == 200) {
                        raf.setLength(0L)
                    } else {
                        raf.seek(currentBytes)
                    }

                    body.byteStream().use { inputStream ->
                        val buffer = ByteArray(8192)
                        var read: Int

                        while (inputStream.read(buffer).also { read = it } != -1) {
                            if (isPaused()) {
                                onProgress(
                                    ProgressSnapshot(
                                        downloadId = request.downloadId,
                                        bytesDownloaded = currentBytes,
                                        totalBytes = totalBytes,
                                        progress = calculateProgress(currentBytes, totalBytes),
                                        status = ProgressSnapshot.Status.PAUSED
                                    )
                                )
                                return DownloadOutcome.Paused(request.downloadId, currentBytes, totalBytes)
                            }

                            if (isCancelled()) {
                                onProgress(
                                    ProgressSnapshot(
                                        downloadId = request.downloadId,
                                        bytesDownloaded = currentBytes,
                                        totalBytes = totalBytes,
                                        progress = calculateProgress(currentBytes, totalBytes),
                                        status = ProgressSnapshot.Status.CANCELLED
                                    )
                                )
                                partFile.delete()
                                metaFile.delete()
                                return DownloadOutcome.Cancelled(request.downloadId, currentBytes)
                            }

                            raf.write(buffer, 0, read)
                            currentBytes += read
                            bytesSinceLastEmit += read

                            val now = System.currentTimeMillis()
                            val elapsed = now - lastEmitTimeMs
                            val currentPercent = calculateProgress(currentBytes, totalBytes)

                            // Throttle progress events: max 1 event per 250ms OR >= 1% change
                            if (elapsed >= 250 || kotlin.math.abs(currentPercent - lastEmitPercent) >= 1) {
                                val throughput = if (elapsed > 0) (bytesSinceLastEmit * 1000) / elapsed else 0L
                                onProgress(
                                    ProgressSnapshot(
                                        downloadId = request.downloadId,
                                        bytesDownloaded = currentBytes,
                                        totalBytes = totalBytes,
                                        progress = currentPercent,
                                        status = ProgressSnapshot.Status.DOWNLOADING,
                                        resumedFromBytes = initialOffset,
                                        throughputBytesPerSecond = throughput
                                    )
                                )
                                lastEmitTimeMs = now
                                lastEmitPercent = currentPercent
                                bytesSinceLastEmit = 0L
                            }
                        }
                    }
                }
            } catch (ioe: IOException) {
                onProgress(
                    ProgressSnapshot(
                        downloadId = request.downloadId,
                        bytesDownloaded = currentBytes,
                        totalBytes = totalBytes,
                        progress = calculateProgress(currentBytes, totalBytes),
                        status = ProgressSnapshot.Status.ERROR
                    )
                )
                return DownloadOutcome.Failure(
                    downloadId = request.downloadId,
                    error = ioe,
                    bytesDownloaded = currentBytes,
                    canResume = partFile.exists() && partFile.length() > 0
                )
            }

            // Verify size if totalBytes was known
            if (totalBytes > 0 && currentBytes != totalBytes) {
                val sizeMismatchErr = IOException("Downloaded size ($currentBytes) does not match expected total ($totalBytes)")
                return DownloadOutcome.Failure(
                    downloadId = request.downloadId,
                    error = sizeMismatchErr,
                    bytesDownloaded = currentBytes,
                    canResume = true
                )
            }

            // Atomically rename .part to final destination
            if (destFile.exists()) {
                destFile.delete()
            }
            try {
                Files.move(
                    partFile.toPath(),
                    destFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: Exception) {
                // Fallback to standard file rename if filesystem doesn't support atomic move
                partFile.renameTo(destFile)
            }

            metaFile.delete()

            val finalSnapshot = ProgressSnapshot(
                downloadId = request.downloadId,
                bytesDownloaded = currentBytes,
                totalBytes = currentBytes,
                progress = 100,
                status = ProgressSnapshot.Status.COMPLETED,
                resumedFromBytes = initialOffset
            )
            onProgress(finalSnapshot)

            DownloadOutcome.Success(
                downloadId = request.downloadId,
                destinationFile = destFile,
                size = currentBytes,
                mimeType = mimeType,
                resumed = resumed
            )
        }
    }

    private fun calculateProgress(current: Long, total: Long): Int {
        if (total <= 0L) return 0
        val p = (current * 100L) / total
        return p.coerceIn(0L, 100L).toInt()
    }

    private fun parseTotalBytesFromContentRange(contentRange: String?): Long {
        if (contentRange == null) return -1L
        // Formats: bytes 0-1023/1024 or bytes 100-200/*
        val slashIndex = contentRange.lastIndexOf('/')
        if (slashIndex != -1 && slashIndex + 1 < contentRange.length) {
            val totalStr = contentRange.substring(slashIndex + 1).trim()
            return totalStr.toLongOrNull() ?: -1L
        }
        return -1L
    }

    private fun readMetaSidecar(file: File): MetaSidecar? {
        if (!file.exists()) return null
        return try {
            val content = file.readText(Charsets.UTF_8)
            MetaSidecar.fromJson(content)
        } catch (_: Exception) {
            null
        }
    }

    private fun writeMetaSidecar(file: File, meta: MetaSidecar) {
        try {
            file.writeText(meta.toJson(), Charsets.UTF_8)
        } catch (_: Exception) {
            // Non-fatal if sidecar write fails
        }
    }
}
