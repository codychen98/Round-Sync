package ca.pkay.rcloneexplorer.util;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class BackgroundMediaPrepWorkTrackerTest {

    @After
    public void tearDown() {
        BackgroundMediaPrepWorkTracker.resetForTests();
    }

    @Test
    public void hasActiveWork_falseByDefault() {
        Assert.assertFalse(BackgroundMediaPrepWorkTracker.hasActiveWork());
    }

    @Test
    public void explorerBatch_togglesActive() {
        BackgroundMediaPrepWorkTracker.setExplorerThumbnailBatchInProgress(true);
        Assert.assertTrue(BackgroundMediaPrepWorkTracker.hasActiveWork());
        BackgroundMediaPrepWorkTracker.setExplorerThumbnailBatchInProgress(false);
        Assert.assertFalse(BackgroundMediaPrepWorkTracker.hasActiveWork());
    }

    @Test
    public void prefetchRefCount_balanced() {
        BackgroundMediaPrepWorkTracker.incrementThumbnailPrefetchWork();
        BackgroundMediaPrepWorkTracker.incrementThumbnailPrefetchWork();
        Assert.assertTrue(BackgroundMediaPrepWorkTracker.hasActiveWork());
        BackgroundMediaPrepWorkTracker.decrementThumbnailPrefetchWork();
        BackgroundMediaPrepWorkTracker.decrementThumbnailPrefetchWork();
        Assert.assertFalse(BackgroundMediaPrepWorkTracker.hasActiveWork());
    }

    @Test
    public void decrementPrefetch_neverNegative() {
        BackgroundMediaPrepWorkTracker.decrementThumbnailPrefetchWork();
        Assert.assertFalse(BackgroundMediaPrepWorkTracker.hasActiveWork());
    }

    @Test
    public void cacheWork_togglesActive() {
        BackgroundMediaPrepWorkTracker.setCacheWorkInProgress(true);
        Assert.assertTrue(BackgroundMediaPrepWorkTracker.hasActiveWork());
        BackgroundMediaPrepWorkTracker.setCacheWorkInProgress(false);
        Assert.assertFalse(BackgroundMediaPrepWorkTracker.hasActiveWork());
    }
}
