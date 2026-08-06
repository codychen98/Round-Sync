package ca.pkay.rcloneexplorer.Glide;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.bumptech.glide.load.HttpException;
import com.bumptech.glide.load.engine.GlideException;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class RetryRequestListenerDecodeFailureTest {

    @Test
    public void isDecodeStageFailure_trueForSetDataSourceRootCause() {
        GlideException exception = new GlideException(
                "Failed to load resource",
                Collections.singletonList(
                        new RuntimeException("setDataSource failed: status = 0x80000000")));
        assertTrue(RetryRequestListener.isDecodeStageFailure(exception));
    }

    @Test
    public void isDecodeStageFailure_falseWhenRootCauseIsHttpException() {
        GlideException exception = new GlideException(
                "Failed to load resource",
                Collections.singletonList(new HttpException(404)));
        assertFalse(RetryRequestListener.isDecodeStageFailure(exception));
    }

    @Test
    public void isDecodeStageFailure_falseWhenAnyRootCauseIsHttpException() {
        GlideException exception = new GlideException(
                "Failed to load resource",
                Arrays.asList(
                        new RuntimeException("setDataSource failed: status = 0x80000000"),
                        new HttpException(404)));
        assertFalse(RetryRequestListener.isDecodeStageFailure(exception));
    }

    @Test
    public void isDecodeStageFailure_falseForNullException() {
        assertFalse(RetryRequestListener.isDecodeStageFailure(null));
    }

    @Test
    public void isDecodeStageFailure_falseForEmptyRootCauses() {
        GlideException exception = new GlideException(
                "Failed to load resource",
                Collections.emptyList());
        assertFalse(RetryRequestListener.isDecodeStageFailure(exception));
    }
}
