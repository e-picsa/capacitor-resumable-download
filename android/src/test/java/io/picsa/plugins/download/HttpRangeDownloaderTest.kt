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
            onDiagnostic = { level, _ ->
                if (level == "warn") diagnosticWarnCalled = true
            }
        )

        assertTrue(outcome is HttpRangeDownloader.DownloadOutcome.Success)
        val success = outcome as HttpRangeDownloader.DownloadOutcome.Success
        assertFalse("Should not be treated as partial resume when server returned 200", success.resumed)
        assertTrue(diagnosticWarnCalled)
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

        // First attempt should fail due to unexpected EOF/socket drop
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
}
