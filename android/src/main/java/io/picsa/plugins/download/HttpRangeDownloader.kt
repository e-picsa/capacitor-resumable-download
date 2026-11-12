package io.picsa.plugins.download

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
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

    class HttpException(val statusCode: Int, message: String) : IOException(message)

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
            val totalBytes: Long,
            val isPreempted: Boolean = false
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
     * Executes the download with support for pause, cancellation, system preemption,
     * and byte-range resumption.
     */
    fun execute(
        request: DownloadRequest,
        onProgress: (ProgressSnapshot) -> Unit = {},
        onDiagnostic: (level: String, message: String, code: String?, context: Map<String, Any?>?) -> Unit = { _, _, _, _ -> },
        onCallCreated: (Call) -> Unit = {},
        isCancelled: () -> Boolean = { false },
        isPaused: () -> Boolean = { false },
        isStopped: () -> Boolean = { false },
        retryOn416Count: Int = 0
    ): DownloadOutcome {
        val destFile = request.destinationFile
        val parentDir = destFile.parentFile
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs()
        }

        val partFile = File(destFile.absolutePath + ".part")
        val metaFile = File(destFile.absolutePath + ".part.meta.json")

        var sidecar = readMetaSidecar(metaFile, onDiagnostic)
        var partLength = if (partFile.exists()) partFile.length() else 0L

        // If part file exists but sidecar URL is different, discard corrupt/stale partial file
        if (partFile.exists() && sidecar != null && sidecar.url != request.url) {
            onDiagnostic(
                "warn",
                "Existing .part file belongs to a different URL (${sidecar.url} != ${request.url}). Purging.",
                "DIFFERENT_URL_PURGED",
                mapOf("oldUrl" to sidecar.url, "newUrl" to request.url)
            )
            partFile.delete()
            metaFile.delete()
            partLength = 0L
            sidecar = null
        }

        val initialOffset = partLength
        var resumed = false
        val httpRequestBuilder = try {
            Request.Builder().url(request.url)
        } catch (e: IllegalArgumentException) {
            onDiagnostic(
                "error",
                "Malformed URL (${request.url}): ${e.message}",
                "MALFORMED_URL",
                mapOf("url" to request.url)
            )
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
                canResume = false
            )
        }

        // Always request identity encoding unless caller explicitly specifies otherwise,
        // preventing transparent gzip from corrupting byte-range slicing.
        if (!request.headers.keys.any { it.equals("Accept-Encoding", ignoreCase = true) }) {
            httpRequestBuilder.addHeader("Accept-Encoding", "identity")
        }

        for ((k, v) in request.headers) {
            httpRequestBuilder.addHeader(k, v)
        }

        if (partLength > 0L) {
            httpRequestBuilder.addHeader("Range", "bytes=$partLength-")
            val storedEtag = sidecar?.etag
            if (!storedEtag.isNullOrBlank()) {
                if (storedEtag.startsWith("W/")) {
                    onDiagnostic(
                        "warn",
                        "Stored ETag is weak ($storedEtag). Omitting If-Range header per RFC 9110 byte-range specification.",
                        "WEAK_ETAG_DETECTED",
                        mapOf("etag" to storedEtag)
                    )
                } else {
                    httpRequestBuilder.addHeader("If-Range", storedEtag)
                }
            }
        }

        val call = client.newCall(httpRequestBuilder.build())
        onCallCreated(call)

        val response: Response
        try {
            response = call.execute()
        } catch (e: IOException) {
            if (isCancelled()) {
                partFile.delete()
                metaFile.delete()
                return DownloadOutcome.Cancelled(request.downloadId, partLength)
            }
            if (isPaused() || isStopped()) {
                if (isStopped()) {
                    onDiagnostic(
                        "warn",
                        "Download worker was stopped by system. Partial file preserved for resumption.",
                        "WORKER_PREEMPTED",
                        mapOf("bytesDownloaded" to partLength, "totalBytes" to (sidecar?.totalBytes ?: -1L))
                    )
                }
                onProgress(
                    ProgressSnapshot(
                        downloadId = request.downloadId,
                        bytesDownloaded = partLength,
                        totalBytes = sidecar?.totalBytes ?: -1L,
                        progress = calculateProgress(partLength, sidecar?.totalBytes ?: -1L),
                        status = ProgressSnapshot.Status.PAUSED
                    )
                )
                return DownloadOutcome.Paused(
                    request.downloadId,
                    partLength,
                    sidecar?.totalBytes ?: -1L,
                    isPreempted = isStopped()
                )
            }
            onDiagnostic(
                "warn",
                "Network connection interrupted: ${e.message ?: e.javaClass.simpleName}. Partial file preserved for resumption.",
                "SOCKET_DROP_RESUMABLE",
                mapOf("bytesDownloaded" to partLength, "canResume" to (partLength > 0))
            )
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
                onDiagnostic(
                    "warn",
                    "Server responded with HTTP 416 Range Not Satisfiable. Resetting partial file.",
                    "RANGE_NOT_SATISFIABLE_416",
                    mapOf("bytesDownloaded" to partLength)
                )
                partFile.delete()
                metaFile.delete()

                if (retryOn416Count < 1) {
                    return execute(
                        request = request,
                        onProgress = onProgress,
                        onDiagnostic = onDiagnostic,
                        onCallCreated = onCallCreated,
                        isCancelled = isCancelled,
                        isPaused = isPaused,
                        isStopped = isStopped,
                        retryOn416Count = retryOn416Count + 1
                    )
                } else {
                    val err = HttpException(416, "HTTP 416 Range Not Satisfiable persisted after resetting partial file.")
                    onProgress(
                        ProgressSnapshot(
                            downloadId = request.downloadId,
                            bytesDownloaded = 0L,
                            totalBytes = -1L,
                            progress = 0,
                            status = ProgressSnapshot.Status.ERROR
                        )
                    )
                    return DownloadOutcome.Failure(
                        downloadId = request.downloadId,
                        error = err,
                        bytesDownloaded = 0L,
                        canResume = false
                    )
                }
            }

            // Case 2: Server returned non-success code
            if (!resp.isSuccessful && code != 206) {
                val err = HttpException(resp.code, "HTTP ${resp.code} ${resp.message}")
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
                val contentRange = resp.header("Content-Range")
                totalBytes = parseTotalBytesFromContentRange(contentRange)
                if (totalBytes <= 0 && sidecar != null && sidecar.totalBytes > 0) {
                    totalBytes = sidecar.totalBytes
                }

                // When resuming without If-Range (e.g., weak ETag or missing ETag), verify the server's
                // representation has not changed by checking ETag and totalBytes against the sidecar.
                val storedEtag = sidecar?.etag
                val etagChanged = !storedEtag.isNullOrBlank() && !etagHeader.isNullOrBlank() && storedEtag != etagHeader
                val sizeChanged = sidecar != null && sidecar.totalBytes > 0 && totalBytes > 0 && sidecar.totalBytes != totalBytes

                if (etagChanged || sizeChanged) {
                    onDiagnostic(
                        "warn",
                        "Remote representation changed during resume (ETag or size mismatch). Resetting partial file and restarting from byte 0.",
                        "RANGE_REPRESENTATION_CHANGED",
                        mapOf(
                            "oldEtag" to storedEtag,
                            "newEtag" to etagHeader,
                            "oldSize" to sidecar?.totalBytes,
                            "newSize" to totalBytes
                        )
                    )
                    partFile.delete()
                    metaFile.delete()
                    if (retryOn416Count < 1) {
                        return execute(
                            request = request,
                            onProgress = onProgress,
                            onDiagnostic = onDiagnostic,
                            onCallCreated = onCallCreated,
                            isCancelled = isCancelled,
                            isPaused = isPaused,
                            isStopped = isStopped,
                            retryOn416Count = retryOn416Count + 1
                        )
                    }
                }

                resumed = true
                writeMode = "rw"
            } else {
                // HTTP 200 OK: either brand new download or range was rejected/reset
                if (partLength > 0L) {
                    onDiagnostic(
                        "warn",
                        "Server returned HTTP 200 OK for Range request. Restarting download from byte 0.",
                        "RANGE_RESET_200",
                        null
                    )
                    partLength = 0L
                }
                val cl = resp.header("Content-Length")
                totalBytes = cl?.toLongOrNull() ?: -1L
                writeMode = "rw"
            }

            var currentBytes = partLength

            if (isCancelled()) {
                partFile.delete()
                metaFile.delete()
                return DownloadOutcome.Cancelled(request.downloadId, currentBytes)
            }

            // Persist updated sidecar
            val newSidecar = MetaSidecar(
                url = request.url,
                etag = etagHeader ?: sidecar?.etag,
                totalBytes = totalBytes
            )
            writeMetaSidecar(metaFile, newSidecar, onDiagnostic)

            val body = resp.body ?: return DownloadOutcome.Failure(
                downloadId = request.downloadId,
                error = IOException("Response body is null"),
                bytesDownloaded = partLength,
                canResume = false
            )

            if (isCancelled()) {
                partFile.delete()
                metaFile.delete()
                return DownloadOutcome.Cancelled(request.downloadId, currentBytes)
            }

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
                                return DownloadOutcome.Paused(
                                    request.downloadId,
                                    currentBytes,
                                    totalBytes,
                                    isPreempted = false
                                )
                            }

                            if (isStopped()) {
                                onDiagnostic(
                                    "warn",
                                    "Download worker was stopped by system. Partial file preserved for resumption.",
                                    "WORKER_PREEMPTED",
                                    mapOf("bytesDownloaded" to currentBytes, "totalBytes" to totalBytes)
                                )
                                onProgress(
                                    ProgressSnapshot(
                                        downloadId = request.downloadId,
                                        bytesDownloaded = currentBytes,
                                        totalBytes = totalBytes,
                                        progress = calculateProgress(currentBytes, totalBytes),
                                        status = ProgressSnapshot.Status.PAUSED
                                    )
                                )
                                return DownloadOutcome.Paused(
                                    request.downloadId,
                                    currentBytes,
                                    totalBytes,
                                    isPreempted = true
                                )
                            }

                            raf.write(buffer, 0, read)
                            currentBytes += read
                            bytesSinceLastEmit += read

                            val now = System.currentTimeMillis()
                            val elapsed = now - lastEmitTimeMs
                            val currentPercent = calculateProgress(currentBytes, totalBytes)

                            // Throttle progress events: strictly time-based (max 1 event per 250ms) during active streaming
                            if (elapsed >= 250) {
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
                if (isCancelled()) {
                    partFile.delete()
                    metaFile.delete()
                    return DownloadOutcome.Cancelled(request.downloadId, currentBytes)
                }
                if (isPaused() || isStopped()) {
                    if (isStopped()) {
                        onDiagnostic(
                            "warn",
                            "Download worker was stopped by system. Partial file preserved for resumption.",
                            "WORKER_PREEMPTED",
                            mapOf("bytesDownloaded" to currentBytes, "totalBytes" to totalBytes)
                        )
                    }
                    onProgress(
                        ProgressSnapshot(
                            downloadId = request.downloadId,
                            bytesDownloaded = currentBytes,
                            totalBytes = totalBytes,
                            progress = calculateProgress(currentBytes, totalBytes),
                            status = ProgressSnapshot.Status.PAUSED
                        )
                    )
                    return DownloadOutcome.Paused(
                        request.downloadId,
                        currentBytes,
                        totalBytes,
                        isPreempted = isStopped()
                    )
                }
                onDiagnostic(
                    "warn",
                    "Network stream interrupted at $currentBytes bytes (${ioe.message ?: ioe.javaClass.simpleName}). Partial file preserved for resumption.",
                    "SOCKET_DROP_RESUMABLE",
                    mapOf("bytesDownloaded" to currentBytes, "totalBytes" to totalBytes, "canResume" to (currentBytes > 0))
                )
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

            // Move completed file to destination safely across Android 7+ and JVM
            try {
                completeFileMove(partFile, destFile, onDiagnostic)
            } catch (moveErr: IOException) {
                return DownloadOutcome.Failure(
                    downloadId = request.downloadId,
                    error = moveErr,
                    bytesDownloaded = currentBytes,
                    canResume = true
                )
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
        val slashIndex = contentRange.lastIndexOf('/')
        if (slashIndex != -1 && slashIndex + 1 < contentRange.length) {
            val totalStr = contentRange.substring(slashIndex + 1).trim()
            return totalStr.toLongOrNull() ?: -1L
        }
        return -1L
    }

    private fun readMetaSidecar(
        file: File,
        onDiagnostic: (String, String, String?, Map<String, Any?>?) -> Unit
    ): MetaSidecar? {
        if (!file.exists()) return null
        return try {
            val content = file.readText(Charsets.UTF_8)
            MetaSidecar.fromJson(content)
        } catch (e: Exception) {
            onDiagnostic(
                "warn",
                "Failed to parse sidecar metadata (${e.message}): resetting sidecar.",
                "SIDECAR_READ_ERROR",
                mapOf("path" to file.absolutePath)
            )
            null
        }
    }

    private fun writeMetaSidecar(
        file: File,
        meta: MetaSidecar,
        onDiagnostic: (String, String, String?, Map<String, Any?>?) -> Unit
    ) {
        try {
            file.writeText(meta.toJson(), Charsets.UTF_8)
        } catch (e: Exception) {
            onDiagnostic(
                "warn",
                "Failed to persist sidecar metadata (${e.message}).",
                "SIDECAR_WRITE_ERROR",
                mapOf("path" to file.absolutePath)
            )
        }
    }

    private fun completeFileMove(
        partFile: File,
        destFile: File,
        onDiagnostic: (level: String, message: String, code: String?, context: Map<String, Any?>?) -> Unit
    ) {
        var moved = false
        // Try atomic move via reflection (works on Android 8+ & host JVM without Android 7 LinkageError)
        try {
            moved = tryNioAtomicMove(partFile, destFile)
        } catch (t: Throwable) {
            onDiagnostic(
                "info",
                "Atomic move not available (${t.javaClass.simpleName}); using standard rename.",
                "NIO_MOVE_FALLBACK",
                null
            )
        }

        if (!moved) {
            if (destFile.exists()) {
                destFile.delete()
            }
            moved = partFile.renameTo(destFile)
        }

        if (!moved) {
            onDiagnostic(
                "warn",
                "File.renameTo failed; falling back to stream copy.",
                "NIO_MOVE_FALLBACK",
                null
            )
            partFile.inputStream().use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            if (!partFile.delete()) {
                onDiagnostic("warn", "Could not delete temporary .part file after copy fallback.", null, null)
            }
            moved = true
        }

        if (!destFile.exists()) {
            throw IOException("Failed to move temporary .part file to final destination: ${destFile.absolutePath}")
        }
    }

    /**
     * Attempts atomic file move via reflection to avoid direct bytecode references
     * to java.nio.file.* which trigger NewApi lint errors on Android < 26.
     */
    @Suppress("UNCHECKED_CAST")
    private fun tryNioAtomicMove(source: File, destination: File): Boolean {
        return try {
            val filesClass = Class.forName("java.nio.file.Files")
            val copyOptionClass = Class.forName("java.nio.file.CopyOption")
            val standardCopyOptionClass = Class.forName("java.nio.file.StandardCopyOption")
            val replaceExisting = java.lang.Enum.valueOf(standardCopyOptionClass as Class<out Enum<*>>, "REPLACE_EXISTING")
            val atomicMove = java.lang.Enum.valueOf(standardCopyOptionClass as Class<out Enum<*>>, "ATOMIC_MOVE")

            val optionsArray = java.lang.reflect.Array.newInstance(copyOptionClass, 2)
            java.lang.reflect.Array.set(optionsArray, 0, replaceExisting)
            java.lang.reflect.Array.set(optionsArray, 1, atomicMove)

            val toPathMethod = File::class.java.getMethod("toPath")
            val sourcePath = toPathMethod.invoke(source)
            val destPath = toPathMethod.invoke(destination)

            val pathClass = Class.forName("java.nio.file.Path")
            val moveMethod = filesClass.getMethod("move", pathClass, pathClass, optionsArray.javaClass)
            moveMethod.invoke(null, sourcePath, destPath, optionsArray)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
