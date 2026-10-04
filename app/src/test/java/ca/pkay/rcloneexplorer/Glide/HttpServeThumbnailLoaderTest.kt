package ca.pkay.rcloneexplorer.Glide

import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.HttpUrlFetcher
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.stream.HttpGlideUrlLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class HttpServeThumbnailLoaderTest {

    private val loader = HttpServeThumbnailLoader(
        RuntimeEnvironment.getApplication(),
        HttpGlideUrlLoader(),
    )
    private val url = "http://127.0.0.1:29180/auth-token/pCloud/Photos/img.jpg"

    @Test
    fun serveThumbnailModel_usesTranscodingFetcherAndModelAsKey() {
        val model = HttpServeThumbnailGlideUrl(url)
        val loadData = loader.buildLoadData(model, 200, 200, Options())
        assertNotNull(loadData)
        assertSame(model, loadData!!.sourceKey)
        assertTrue(loadData.fetcher is HttpServeThumbnailFetcher)
    }

    @Test
    fun plainAndFolderModels_delegateToStockFetcher() {
        for (model in listOf(GlideUrl(url), FolderThumbnailGlideUrl(url))) {
            val loadData = loader.buildLoadData(model, 200, 200, Options())
            assertNotNull(loadData)
            assertTrue(loadData!!.fetcher is HttpUrlFetcher)
            assertEquals(model.cacheKey, (loadData.sourceKey as GlideUrl).cacheKey)
        }
    }

    @Test
    fun headHex_formatsFirstSixteenBytes() {
        assertEquals("3c3f786d6c", HttpServeThumbnailFetcher.headHex("<?xml".toByteArray()))
        assertEquals(32, HttpServeThumbnailFetcher.headHex(ByteArray(40) { 0xAB.toByte() }).length)
        assertEquals("", HttpServeThumbnailFetcher.headHex(ByteArray(0)))
    }
}
