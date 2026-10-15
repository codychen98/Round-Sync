package ca.pkay.rcloneexplorer.Glide;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.bumptech.glide.load.HttpException;
import com.bumptech.glide.load.engine.GlideException;

import org.junit.Test;

import java.util.Arrays;
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

    @Test
    public void firstHttpStatusCode_readsHttpExceptionStatus() {
        GlideException exception = new GlideException(
                "Load failed",
                Collections.singletonList(new HttpException(404)));
        assertEquals(404, RetryRequestListener.firstHttpStatusCode(exception));
    }

    @Test
    public void firstHttpStatusCode_noneWhenNoHttpException() {
        GlideException exception = new GlideException(
                "Load failed",
                Collections.singletonList(new RuntimeException("decode")));
        assertEquals(
                RetryRequestListener.NO_HTTP_STATUS,
                RetryRequestListener.firstHttpStatusCode(exception));
    }

    @Test
    public void firstHttpStatusCode_prefersFirstHttpException() {
        GlideException exception = new GlideException(
                "Load failed",
                Arrays.asList(new RuntimeException("other"), new HttpException(404)));
        assertEquals(404, RetryRequestListener.firstHttpStatusCode(exception));
    }

    @Test
    public void isTerminalHttpStatus_only404() {
        assertTrue(RetryRequestListener.isTerminalHttpStatus(404));
        assertFalse(RetryRequestListener.isTerminalHttpStatus(500));
        assertFalse(RetryRequestListener.isTerminalHttpStatus(HttpException.UNKNOWN));
        assertFalse(RetryRequestListener.isTerminalHttpStatus(RetryRequestListener.NO_HTTP_STATUS));
    }
}
