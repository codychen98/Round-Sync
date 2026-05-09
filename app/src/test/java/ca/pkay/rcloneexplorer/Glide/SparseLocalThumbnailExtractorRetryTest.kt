package ca.pkay.rcloneexplorer.Glide

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private typealias FetchResult = SparseLocalThumbnailExtractor.FetchRangeResult

class SparseLocalThumbnailExtractorRetryTest {

    private val noOpLogger = SparseLocalThumbnailExtractor.FetchLogger { _, _ -> }

    // region — parsePinnedUrlPort

    @Test
    fun `parsePinnedUrlPort extracts port from full url`() {
        assertEquals(29179,
            SparseLocalThumbnailExtractor.parsePinnedUrlPort(
                "http://127.0.0.1:29179/ryaRUeCs2Vg/MyRemote/video.mkv"))
    }

    @Test
    fun `parsePinnedUrlPort extracts port from base url without path`() {
        assertEquals(29180,
            SparseLocalThumbnailExtractor.parsePinnedUrlPort(
                "http://127.0.0.1:29180/Ku1bOuPMoru"))
    }

    @Test
    fun `parsePinnedUrlPort returns minus one for non url`() {
        assertEquals(-1, SparseLocalThumbnailExtractor.parsePinnedUrlPort("not-a-url"))
    }

    // endregion

    // region — parsePinnedUrlAuth6

    @Test
    fun `parsePinnedUrlAuth6 extracts first six chars of auth from full url`() {
        assertEquals("Ku1bOu",
            SparseLocalThumbnailExtractor.parsePinnedUrlAuth6(
                "http://127.0.0.1:29180/Ku1bOuPMoru/MyRemote/video.mkv"))
    }

    @Test
    fun `parsePinnedUrlAuth6 extracts auth from base url`() {
        assertEquals("WwfsAE",
            SparseLocalThumbnailExtractor.parsePinnedUrlAuth6(
                "http://127.0.0.1:29180/WwfsAEzhNneo"))
    }

    @Test
    fun `parsePinnedUrlAuth6 returns question mark for non url`() {
        assertEquals("?", SparseLocalThumbnailExtractor.parsePinnedUrlAuth6("not-a-url"))
    }

    // endregion

    // region — staleness detection via port mismatch

    @Test
    fun `different ports indicate staleness`() {
        val pinnedPort = SparseLocalThumbnailExtractor.parsePinnedUrlPort(
            "http://127.0.0.1:29179/AuthAAA/remote/file.mkv")
        val mgrPort = SparseLocalThumbnailExtractor.parsePinnedUrlPort(
            "http://127.0.0.1:29180/AuthBBB")
        assertNotEquals(pinnedPort, mgrPort)
    }

    // endregion

    // region — fetchRange URL composition

    @Test
    fun `fetchRange builds request url from base url and stable path`() {
        val requested = mutableListOf<String>()
        val supplier = SparseLocalThumbnailExtractor.BaseUrlSupplier {
            "http://127.0.0.1:29180/AuthToken123"
        }
        val awaiter = SparseLocalThumbnailExtractor.ServerReadyAwaiter { true }
        val requester = SparseLocalThumbnailExtractor.RangeRequester { url, _, _ ->
            requested.add(url)
            byteArrayOf(1, 2, 3)
        }

        val result = SparseLocalThumbnailExtractor.fetchRange(
            "/MyRemote/video.mkv", 0L, 1024L, 1,
            supplier, awaiter, requester, noOpLogger, "video.mkv")

        assertEquals(1, requested.size)
        assertEquals("http://127.0.0.1:29180/AuthToken123/MyRemote/video.mkv", requested[0])
        assertTrue(result is FetchResult.Success)
        assertArrayEquals(byteArrayOf(1, 2, 3), (result as FetchResult.Success).bytes)
    }

    // endregion

    // region — retry-once branching

    @Test
    fun `fetchRange retries once on first failure and returns Success on second attempt`() {
        var callCount = 0
        val supplier = SparseLocalThumbnailExtractor.BaseUrlSupplier {
            "http://127.0.0.1:29180/Auth"
        }
        val awaiter = SparseLocalThumbnailExtractor.ServerReadyAwaiter { true }
        val requester = SparseLocalThumbnailExtractor.RangeRequester { _, _, _ ->
            callCount++
            if (callCount == 1) null else byteArrayOf(42)
        }

        val result = SparseLocalThumbnailExtractor.fetchRange(
            "/remote/file.mkv", 0L, 1024L, 1,
            supplier, awaiter, requester, noOpLogger, "file.mkv")

        assertEquals(2, callCount)
        assertTrue(result is FetchResult.Success)
        assertArrayEquals(byteArrayOf(42), (result as FetchResult.Success).bytes)
    }

    @Test
    fun `fetchRange returns NetworkFailed when both attempts fail`() {
        var callCount = 0
        val supplier = SparseLocalThumbnailExtractor.BaseUrlSupplier {
            "http://127.0.0.1:29180/Auth"
        }
        val awaiter = SparseLocalThumbnailExtractor.ServerReadyAwaiter { true }
        val requester = SparseLocalThumbnailExtractor.RangeRequester { _, _, _ ->
            callCount++
            null
        }

        val result = SparseLocalThumbnailExtractor.fetchRange(
            "/remote/file.mkv", 0L, 1024L, 1,
            supplier, awaiter, requester, noOpLogger, "file.mkv")

        assertEquals(2, callCount)
        assertEquals(FetchResult.NetworkFailed, result)
    }

    // endregion

    // region — manager not ready

    @Test
    fun `fetchRange returns ManagerNotReady and calls awaiter when server not ready on attempt 1`() {
        var awaiterCalled = false
        val supplier = SparseLocalThumbnailExtractor.BaseUrlSupplier { null }
        val awaiter = SparseLocalThumbnailExtractor.ServerReadyAwaiter { _ ->
            awaiterCalled = true
            false
        }
        val requester = SparseLocalThumbnailExtractor.RangeRequester { _, _, _ ->
            fail("should not execute HTTP when manager not ready")
            null
        }

        val result = SparseLocalThumbnailExtractor.fetchRange(
            "/remote/file.mkv", 0L, 1024L, 1,
            supplier, awaiter, requester, noOpLogger, "file.mkv")

        assertTrue(awaiterCalled)
        assertEquals(FetchResult.ManagerNotReady, result)
    }

    @Test
    fun `fetchRange returns ManagerNotReady without awaiter call when server not ready on attempt 2`() {
        var awaiterCalled = false
        val supplier = SparseLocalThumbnailExtractor.BaseUrlSupplier { null }
        val awaiter = SparseLocalThumbnailExtractor.ServerReadyAwaiter { _ ->
            awaiterCalled = true
            true
        }
        val requester = SparseLocalThumbnailExtractor.RangeRequester { _, _, _ -> null }

        val result = SparseLocalThumbnailExtractor.fetchRange(
            "/remote/file.mkv", 0L, 1024L, 2,
            supplier, awaiter, requester, noOpLogger, "file.mkv")

        assertTrue("awaiter should not be called on attempt 2", !awaiterCalled)
        assertEquals(FetchResult.ManagerNotReady, result)
    }

    // endregion
}
