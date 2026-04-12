package ca.pkay.rcloneexplorer.Glide;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ThumbnailRetryEpochsTest {

    @Test
    public void isStale_trueWhenEpochsDiffer() {
        assertTrue(ThumbnailRetryEpochs.isStale(0, 1));
        assertTrue(ThumbnailRetryEpochs.isStale(2, 0));
    }

    @Test
    public void isStale_falseWhenEpochsMatch() {
        assertFalse(ThumbnailRetryEpochs.isStale(0, 0));
        assertFalse(ThumbnailRetryEpochs.isStale(7, 7));
    }
}
