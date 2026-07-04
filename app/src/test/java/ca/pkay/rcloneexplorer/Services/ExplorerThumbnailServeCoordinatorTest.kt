package ca.pkay.rcloneexplorer.Services

import org.junit.After
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ExplorerThumbnailServeCoordinatorTest {

    @After
    fun tearDown() {
        ExplorerThumbnailServeCoordinator.setServerDesired(false)
        ExplorerThumbnailServeCoordinator.unregister()
    }

    @Test
    fun requestRestartAndAwait_withoutHost_returnsFalseWhenNotReady() {
        ExplorerThumbnailServeCoordinator.setServerDesired(false)
        ExplorerThumbnailServeCoordinator.unregister()
        val ready = ExplorerThumbnailServeCoordinator.requestRestartAndAwait(
            RuntimeEnvironment.getApplication(),
            100L,
        )
        Assert.assertFalse(ready)
    }

    @Test
    fun registerAndDesired_tracksServerDesired() {
        ExplorerThumbnailServeCoordinator.setServerDesired(true)
        Assert.assertTrue(ExplorerThumbnailServeCoordinator.isServerDesired())
        ExplorerThumbnailServeCoordinator.setServerDesired(false)
        Assert.assertFalse(ExplorerThumbnailServeCoordinator.isServerDesired())
    }
}
