package ca.pkay.rcloneexplorer.Glide;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ThumbnailExtendedRetryDelaysTest {

    @Test
    public void delayMsForAttempt_firstAttemptsDouble() {
        assertEquals(500L, ThumbnailExtendedRetryDelays.delayMsForAttempt(0));
        assertEquals(1_000L, ThumbnailExtendedRetryDelays.delayMsForAttempt(1));
        assertEquals(2_000L, ThumbnailExtendedRetryDelays.delayMsForAttempt(2));
    }

    @Test
    public void delayMsForAttempt_negativeTreatedAsZero() {
        assertEquals(500L, ThumbnailExtendedRetryDelays.delayMsForAttempt(-1));
    }

    @Test
    public void delayMsForAttempt_capped() {
        long capped = ThumbnailExtendedRetryDelays.delayMsForAttempt(100);
        assertEquals(45_000L, capped);
    }

    @Test
    public void delayMsForAttempt_nonDecreasingUntilCap() {
        long prev = 0L;
        for (int i = 0; i < 20; i++) {
            long d = ThumbnailExtendedRetryDelays.delayMsForAttempt(i);
            assertTrue(d >= prev);
            prev = d;
        }
    }
}
