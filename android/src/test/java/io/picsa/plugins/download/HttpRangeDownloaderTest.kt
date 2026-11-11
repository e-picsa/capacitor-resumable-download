package io.picsa.plugins.download

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class HttpRangeDownloaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var downloader: HttpRangeDownloader

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        downloader = HttpRangeDownloader()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun testFreshDownloadSuccess() {
        val payload = ByteArray(10_000) { (it % 128).toByte() }

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", payload.size)
                .setHeader("Content-Type", "application/octet-stream")
                .setHeader("ETag", "\"tag-fresh-123\"")
                .setBody(Buffer().write(payload))
        )

        val destFile = File(tempFolder.root, "fresh_download.bin")
        val snapshots = mutableListOf<HttpRangeDownloader.ProgressSnapshot>()

        val outcome = downloader.execute(
            request = HttpRangeDownloader.DownloadRequest(
                downloadId = "test-fresh",
                url = server.url("/file.bin").toString(),
                destinationFile = destFile
            ),
            onProgress = { snapshots.add(it) }
        )

        assertTrue("Outcome should be Success", outcome is HttpRangeDownloader.DownloadOutcome.Success)
        val success = outcome as HttpRangeDownloader.DownloadOutcome.Success
        assertEquals(payload.size.toLong(), success.size)
        assertFalse("Fresh download should not be flagged as resumed", success.resumed)
        assertTrue("Destination file should exist", destFile.exists())
        assertEquals(payload.size.toLong(), destFile.length())
        assertArrayEquals(payload, destFile.readBytes())

        val partFile = File(destFile.absolutePath + ".part")
        val metaFile = File(destFile.absolutePath + ".part.meta.json")
        assertFalse(".part file must be cleaned up on success", partFile.exists())
        assertFalse("Sidecar metadata must be cleaned up on success", metaFile.exists())

        val lastSnapshot = snapshots.lastOrNull()
        assertNotNull("Should have recorded progress snapshots", lastSnapshot)
        assertEquals(HttpRangeDownloader.ProgressSnapshot.Status.COMPLETED, lastSnapshot?.status)
        assertEquals(100, lastSnapshot?.progress)
    }

    @Test
    fun testResumeWithPartialContent206() {
        val totalSize = 10_000
        val fullPayload = ByteArray(totalSize) { (it % 128).toByte() }
        val offset = 4_000
        val remainingPayload = fullPayload.copyOfRange(offset, totalSize)

        // Simulate pre-existing partial file and sidecar
        val destFile = File(tempFolder.root, "resumable_download.bin")
        val partFile = File(destFile.absolutePath + ".part")
        val metaFile = File(destFile.absolutePath + ".part.meta.json")

        partFile.writeBytes(fullPayload.copyOfRange(0, offset))
        val sidecar = HttpRangeDownloader.MetaSidecar(
            url = server.url("/resumable.bin").toString(),
            etag = "\"strong-etag-abc\"",
            totalBytes = totalSize.toLong()
        )
        metaFile.writeText(sidecar.toJson())

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val rangeHeader = request.getHeader("Range")
                val ifRangeHeader = request.getHeader("If-Range")

                assertEquals("bytes=4000-", rangeHeader)
                assertEquals("\"strong-etag-abc\"", ifRangeHeader)

                return MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 4000-9999/10000")
                    .setHeader("Content-Length", remainingPayload.size)
                    .setHeader("Content-Type", "application/octet-stream")
                    .setHeader("ETag", "\"strong-etag-abc\"")
                    .setBody(Buffer().write(remainingPayload))
            }
        }

        val outcome = downloader.execute(
            request = HttpRangeDownloader.DownloadRequest(
                downloadId = "test-resume",
                url = server.url("/resumable.bin").toString(),
                destinationFile = destFile
            )
        )

        assertTrue(outcome is HttpRangeDownloader.DownloadOutcome.Success)
        val success = outcome as HttpRangeDownloader.DownloadOutcome.Success
        assertTrue("Should be marked as resumed", success.resumed)
        assertEquals(totalSize.toLong(), success.size)
        assertTrue(destFile.exists())
        assertEquals(totalSize.toLong(), destFile.length())
        assertArrayEquals(fullPayload, destFile.readBytes())
    }

    @Test
    fun testServerReset200OKWhenETagChanges() {
        val totalSize = 8_000
        val updatedPayload = ByteArray(totalSize) { ((it + 1) % 128).toByte() }

        // Existing part file from a previous version
        val destFile = File(tempFolder.root, "reset_download.bin")
        val partFile = File(destFile.absolutePath + ".part")
        val metaFile = File(destFile.absolutePath + ".part.meta.json")

        partFile.writeBytes(ByteArray(3_000) { 0 })
        val oldSidecar = HttpRangeDownloader.MetaSidecar(
            url = server.url("/reset.bin").toString(),
            etag = "\"old-etag-999\"",
            totalBytes = 8_000L
        )
        metaFile.writeText(oldSidecar.toJson())

        var diagnosticWarnCalled = false
        var diagnosticCode: String? = null

        // Server rejects If-Range and sends 200 OK with new content
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", updatedPayload.size)
                .setHeader("ETag", "\"new-etag-111\"")
                .setBody(Buffer().write(updatedPayload))
        )

        val outcome = downloader.execute(
            request = HttpRangeDownloader.DownloadRequest(
                downloadId = "test-reset",
                url = server.url("/reset.bin").toString(),
                destinationFile = destFile
            ),
            onDiagnostic = { level, _, code, _ ->
                if (level == "warn") {
                    diagnosticWarnCalled = true
                    diagnosticCode = code
                }
            }
        )

        assertTrue(outcome is HttpRangeDownloader.DownloadOutcome.Success)
        val success = outcome as HttpRangeDownloader.DownloadOutcome.Success
        assertFalse("Should not be treated as partial resume when server returned 200", success.resumed)
        assertTrue(diagnosticWarnCalled)
        assertEquals("RANGE_RESET_200", diagnosticCode)
        assertEquals(totalSize.toLong(), destFile.length())
        assertArrayEquals(updatedPayload, destFile.readBytes())
    }

    @Test
    fun test416RangeNotSatisfiableRetryFromZero() {
        val payload = ByteArray(5_000) { (it % 100).toByte() }

        val destFile = File(tempFolder.root, "corrupt_range.bin")
        val partFile = File(destFile.absolutePath + ".part")
        val metaFile = File(destFile.absolutePath + ".part.meta.json")

        // Invalidate by pretending we have 9,999 bytes when file is only 5,000 bytes
        partFile.writeBytes(ByteArray(9_999) { 0 })
        val staleSidecar = HttpRangeDownloader.MetaSidecar(
            url = server.url("/file416.bin").toString(),
            etag = "\"etag-416\"",
            totalBytes = 5_000L
        )
        metaFile.writeText(staleSidecar.toJson())

        // 1st request -> 416
        server.enqueue(
            MockResponse()
                .setResponseCode(416)
                .setHeader("Content-Range", "bytes */5000")
        )
        // 2nd request (auto retry from 0) -> 200 OK
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", payload.size)
                .setHeader("ETag", "\"etag-416-new\"")
                .setBody(Buffer().write(payload))
        )

        val outcome = downloader.execute(
            request = HttpRangeDownloader.DownloadRequest(
                downloadId = "test-416",
                url = server.url("/file416.bin").toString(),
                destinationFile = destFile
            )
        )

        assertTrue(outcome is HttpRangeDownloader.DownloadOutcome.Success)
        assertEquals(payload.size.toLong(), destFile.length())
        assertArrayEquals(payload, destFile.readBytes())
    }

    @Test
    fun testRepeated416FailsCleanlyWithoutInfiniteLoop() {
        val destFile = File(tempFolder.root, "persist_416.bin")
        val partFile = File(destFile.absolutePath + ".part")
        val metaFile = File(destFile.absolutePath + ".part.meta.json")

        partFile.writeBytes(ByteArray(2_000) { 0 })
        metaFile.writeText(HttpRangeDownloader.MetaSidecar(server.url("/416").toString(), "\"etag\"", 2000L).toJson())

        // Both requests return 416 (broken server configuration)
        server.enqueue(MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */0"))
        server.enqueue(MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */0"))

        val outcome = downloader.execute(
            request = HttpRangeDownloader.DownloadRequest(
                downloadId = "test-416-persist",
                url = server.url("/416").toString(),
                destinationFile = destFile
            )
        )

        assertTrue("Outcome should be Failure", outcome is HttpRangeDownloader.DownloadOutcome.Failure)
        val failure = outcome as HttpRangeDownloader.DownloadOutcome.Failure
        assertFalse("Cannot resume after repeated 416", failure.canResume)
        assertTrue(failure.error is HttpRangeDownloader.HttpException)
        assertEquals(416, (failure.error as HttpRangeDownloader.HttpException).statusCode)
    }

    @Test
    fun testMidStreamSocketDropAndResume() {
        val totalSize = 20_000
        val fullPayload = ByteArray(totalSize) { (it % 64).toByte() }
        val destFile = File(tempFolder.root, "socket_drop.bin")

        // 1st request: disconnect mid-transfer using DISCONNECT_DURING_RESPONSE_BODY
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", totalSize)
                .setHeader("ETag", "\"socket-etag\"")
                .setBody(Buffer().write(fullPayload))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )

        val firstOutcome = downloader.execute(
            request = HttpRangeDownloader.DownloadRequest(
                downloadId = "test-socket-drop",
                url = server.url("/drop.bin").toString(),
                destinationFile = destFile
            )
        )

        assertTrue("First attempt should fail on socket drop", firstOutcome is HttpRangeDownloader.DownloadOutcome.Failure)
        val failure = firstOutcome as HttpRangeDownloader.DownloadOutcome.Failure
        assertTrue("Can resume should be true after partial stream", failure.canResume)

        val partFile = File(destFile.absolutePath + ".part")
        assertTrue(".part file must exist on socket drop", partFile.exists())
        val savedBytes = partFile.length()
        assertTrue("Should have saved at least some bytes before drop", savedBytes > 0)

        // 2nd request: Resumes from byte <savedBytes>
        val remainingBytes = fullPayload.copyOfRange(savedBytes.toInt(), totalSize)
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes $savedBytes-${totalSize - 1}/$totalSize")
                .setHeader("Content-Length", remainingBytes.size)
                .setHeader("ETag", "\"socket-etag\"")
                .setBody(Buffer().write(remainingBytes))
        )

        val secondOutcome = downloader.execute(
            request = HttpRangeDownloader.DownloadRequest(
                downloadId = "test-socket-drop",
                url = server.url("/drop.bin").toString(),
                destinationFile = destFile
            )
        )

        assertTrue("Second attempt should succeed", secondOutcome is HttpRangeDownloader.DownloadOutcome.Success)
        val secondSuccess = secondOutcome as HttpRangeDownloader.DownloadOutcome.Success
        assertTrue("Second attempt should be resumed", secondSuccess.resumed)
        assertEquals(totalSize.toLong(), destFile.length())
        assertArrayEquals(fullPayload, destFile.readBytes())
    }

    @Test
    fun testPauseAndResume() {
        val totalSize = 10_000
        val fullPayload = ByteArray(totalSize) { (it % 99).toByte() }
        val destFile = File(tempFolder.root, "pause_resume.bin")

        val pauseFlag = AtomicBoolean(false)

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", totalSize)
                .setHeader("ETag", "\"pause-etag\"")
                .setBody(Buffer().write(fullPayload))
        )

        val firstOutcome = downloader.execute(
            request = HttpRangeDownloader.DownloadRequest(
                downloadId = "test-pause",
                url = server.url("/pause.bin").toString(),
                destinationFile = destFile
            ),
            onProgress = {
                if (it.bytesDownloaded >= 4_000) {
                    pauseFlag.set(true)
                }
            },
            isPaused = { pauseFlag.get() }
        )

        assertTrue("First attempt should pause", firstOutcome is HttpRangeDownloader.DownloadOutcome.Paused)
        val partFile = File(destFile.absolutePath + ".part")
        assertTrue(partFile.exists())
        val pausedLength = partFile.length()

        val remaining = fullPayload.copyOfRange(pausedLength.toInt(), totalSize)
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes $pausedLength-${totalSize - 1}/$totalSize")
                .setHeader("Content-Length", remaining.size)
                .setHeader("ETag", "\"pause-etag\"")
                .setBody(Buffer().write(remaining))
        )

        val secondOutcome = downloader.execute(
            request = HttpRangeDownloader.DownloadRequest(
                downloadId = "test-pause",
                url = server.url("/pause.bin").toString(),
                destinationFile = destFile
            )
        )

        assertTrue(secondOutcome is HttpRangeDownloader.DownloadOutcome.Success)
        assertEquals(totalSize.toLong(), destFile.length())
        assertArrayEquals(fullPayload, destFile.readBytes())
    }

    @Test
    fun testAcceptEncodingIdentityHeaderSent() {
        val payload = ByteArray(1_000) { 1 }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", payload.size)
                .setBody(Buffer().write(payload))
        )

        val destFile = File(tempFolder.root, "identity_header.bin")
        downloader.execute(
            request = HttpRangeDownloader.DownloadRequest(
                downloadId = "test-identity",
                url = server.url("/test-identity").toString(),
                destinationFile = destFile
            )
        )

        val recorded = server.takeRequest()
        assertEquals("identity", recorded.getHeader("Accept-Encoding"))
    }

    @Test
    fun testWeakETagOmitsIfRangeAndEmitsDiagnostic() {
        val totalSize = 6_000
        val fullPayload = ByteArray(totalSize) { 7 }
        val offset = 2_000

        val destFile = File(tempFolder.root, "weak_etag.bin")
        val partFile = File(destFile.absolutePath + ".part")
        val metaFile = File(destFile.absolutePath + ".part.meta.json")

        partFile.writeBytes(fullPayload.copyOfRange(0, offset))
        val sidecar = HttpRangeDownloader.MetaSidecar(
            url = server.url("/weak-etag").toString(),
            etag = "W/\"weak-etag-123\"",
            totalBytes = totalSize.toLong()
        )
        metaFile.writeText(sidecar.toJson())

        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes $offset-${totalSize - 1}/$totalSize")
                .setHeader("Content-Length", totalSize - offset)
                .setBody(Buffer().write(fullPayload.copyOfRange(offset, totalSize)))
        )

        var diagnosticCode: String? = null

        val outcome = downloader.execute(
            request = HttpRangeDownloader.DownloadRequest(
                downloadId = "test-weak",
                url = server.url("/weak-etag").toString(),
                destinationFile = destFile
            ),
            onDiagnostic = { _, _, code, _ ->
                if (code == "WEAK_ETAG_DETECTED") {
                    diagnosticCode = code
                }
            }
        )

        assertTrue(outcome is HttpRangeDownloader.DownloadOutcome.Success)
        val recorded = server.takeRequest()
        assertEquals("bytes=2000-", recorded.getHeader("Range"))
        assertNull("If-Range must NOT be sent for weak ETags", recorded.getHeader("If-Range"))
        assertEquals("WEAK_ETAG_DETECTED", diagnosticCode)
    }

    @Test
    fun testWorkerPreemptionPreservesPartialFile() {
        val totalSize = 10_000
        val payload = ByteArray(totalSize) { 3 }
        val destFile = File(tempFolder.root, "preempted.bin")

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", totalSize)
                .setBody(Buffer().write(payload))
        )

        val stopFlag = AtomicBoolean(false)
        var preemptionReported = false

        val outcome = downloader.execute(
            request = HttpRangeDownloader.DownloadRequest(
                downloadId = "test-preempt",
                url = server.url("/preempt").toString(),
                destinationFile = destFile
            ),
            onProgress = {
                if (it.bytesDownloaded >= 3_000) {
                    stopFlag.set(true)
                }
            },
            onDiagnostic = { _, _, code, _ ->
                if (code == "WORKER_PREEMPTED") {
                    preemptionReported = true
                }
            },
            isStopped = { stopFlag.get() },
            isCancelled = { false } // NOT user cancelled!
        )

        assertTrue("Outcome must be Paused on system preemption", outcome is HttpRangeDownloader.DownloadOutcome.Paused)
        val paused = outcome as HttpRangeDownloader.DownloadOutcome.Paused
        assertTrue(paused.isPreempted)
        assertTrue(preemptionReported)

        val partFile = File(destFile.absolutePath + ".part")
        val metaFile = File(destFile.absolutePath + ".part.meta.json")
        assertTrue(".part file MUST be preserved on preemption", partFile.exists())
        assertTrue("Meta sidecar MUST be preserved on preemption", metaFile.exists())
        assertTrue("Preserved part file length must be > 0", partFile.length() > 0L)
    }

    @Test
    fun testExplicitCancellationPurgesPartialFile() {
        val totalSize = 10_000
        val payload = ByteArray(totalSize) { 5 }
        val destFile = File(tempFolder.root, "cancelled.bin")

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", totalSize)
                .setBody(Buffer().write(payload))
        )

        val cancelFlag = AtomicBoolean(false)

        val outcome = downloader.execute(
            request = HttpRangeDownloader.DownloadRequest(
                downloadId = "test-cancel",
                url = server.url("/cancel").toString(),
                destinationFile = destFile
            ),
            onProgress = {
                if (it.bytesDownloaded >= 2_000) {
                    cancelFlag.set(true)
                }
            },
            isCancelled = { cancelFlag.get() }
        )

        assertTrue(outcome is HttpRangeDownloader.DownloadOutcome.Cancelled)
        val partFile = File(destFile.absolutePath + ".part")
        val metaFile = File(destFile.absolutePath + ".part.meta.json")
        assertFalse(".part file must be purged on explicit cancellation", partFile.exists())
        assertFalse("Sidecar metadata must be purged on explicit cancellation", metaFile.exists())
    }

    @Test
    fun testZeroByteDownloadSuccess() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", 0)
                .setHeader("Content-Type", "text/plain")
                .setBody("")
        )

        val destFile = File(tempFolder.root, "empty.txt")
        val snapshots = mutableListOf<HttpRangeDownloader.ProgressSnapshot>()

        val outcome = downloader.execute(
            request = HttpRangeDownloader.DownloadRequest(
                downloadId = "test-empty",
                url = server.url("/empty.txt").toString(),
                destinationFile = destFile
            ),
            onProgress = { snapshots.add(it) }
        )

        assertTrue("Outcome should be Success for zero-byte file", outcome is HttpRangeDownloader.DownloadOutcome.Success)
        val success = outcome as HttpRangeDownloader.DownloadOutcome.Success
        assertEquals(0L, success.size)
        assertTrue("Destination file should exist", destFile.exists())
        assertEquals(0L, destFile.length())

        val partFile = File(destFile.absolutePath + ".part")
        val metaFile = File(destFile.absolutePath + ".part.meta.json")
        assertFalse(".part file must be cleaned up on success", partFile.exists())
        assertFalse("Sidecar metadata must be cleaned up on success", metaFile.exists())
    }
}
