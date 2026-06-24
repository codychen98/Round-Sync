package ca.pkay.rcloneexplorer.Glide;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.bumptech.glide.load.engine.GlideException;

import org.junit.Test;

import java.util.Collections;
import java.util.concurrent.CancellationException;

public class RetryRequestListenerTest {

    @Test
    public void isCancelledGlideFailure_detectsCancellationException() {
        GlideException exception = new GlideException(
                "Load failed",
                Collections.singletonList(new CancellationException("Canceled")));
        assertTrue(RetryRequestListener.isCancelledGlideFailure(exception));
    }

    @Test
    public void isCancelledGlideFailure_detectsCancelledMessage() {
        GlideException exception = new GlideException(
                "Failed to load resource",
                Collections.singletonList(new RuntimeException("Fetcher was cancelled")));
        assertTrue(RetryRequestListener.isCancelledGlideFailure(exception));
    }

    @Test
    public void isCancelledGlideFailure_returnsFalseForNetworkErrors() {
        GlideException exception = new GlideException(
                "Failed to load resource",
                Collections.singletonList(new RuntimeException("HTTP 404")));
        assertFalse(RetryRequestListener.isCancelledGlideFailure(exception));
    }
}
