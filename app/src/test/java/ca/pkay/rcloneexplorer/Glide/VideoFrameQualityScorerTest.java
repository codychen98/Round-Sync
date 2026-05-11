package ca.pkay.rcloneexplorer.Glide;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Color;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class VideoFrameQualityScorerTest {

    @Test
    public void score_flatBrightMonochromeFrameLosesToDetailedFrame() {
        Bitmap flatFrame = createSolidFrame(Color.rgb(235, 235, 235));
        Bitmap detailedFrame = createCheckerboardFrame(
                Color.rgb(36, 60, 96),
                Color.rgb(224, 176, 72));
        try {
            VideoFrameQualityScorer.FrameQuality flatQuality = VideoFrameQualityScorer.score(flatFrame);
            VideoFrameQualityScorer.FrameQuality detailedQuality = VideoFrameQualityScorer.score(detailedFrame);

            assertTrue(flatQuality.isFlatColorLike());
            assertTrue(flatQuality.isPoorRepresentativeCandidate());
            assertFalse(detailedQuality.isFlatColorLike());
            assertFalse(detailedQuality.isPoorRepresentativeCandidate());
            assertTrue(detailedQuality.informativeScore() > flatQuality.informativeScore());
        } finally {
            recycleQuietly(flatFrame, detailedFrame);
        }
    }

    @Test
    public void bestFrame_prefersDetailedDarkFrameOverBrighterFlatFallback() throws Exception {
        Bitmap brightFlatFrame = createSolidFrame(Color.rgb(245, 245, 245));
        Bitmap darkDetailedFrame = createCheckerboardFrame(
                Color.rgb(22, 22, 22),
                Color.rgb(76, 76, 76));
        Object chooser = newBestFrameSoFar();
        try {
            VideoFrameQualityScorer.FrameQuality brightFlatQuality =
                    VideoFrameQualityScorer.score(brightFlatFrame);
            VideoFrameQualityScorer.FrameQuality darkDetailedQuality =
                    VideoFrameQualityScorer.score(darkDetailedFrame);

            assertTrue(brightFlatQuality.isPoorRepresentativeCandidate());
            assertFalse(darkDetailedQuality.isPoorRepresentativeCandidate());
            assertTrue(darkDetailedQuality.brightness() < brightFlatQuality.brightness());

            consider(chooser, brightFlatFrame);
            consider(chooser, darkDetailedFrame);

            assertSame(darkDetailedFrame, getFrame(chooser));
            assertTrue(hasRepresentative(chooser));
        } finally {
            recycleQuietly(brightFlatFrame, darkDetailedFrame);
        }
    }

    @Test
    public void bestFrame_fallsBackToBrighterFrameWhenAllCandidatesArePoor() throws Exception {
        Bitmap darkerPoorFrame = createSolidFrame(Color.rgb(70, 70, 70));
        Bitmap brighterPoorFrame = createSolidFrame(Color.rgb(220, 220, 220));
        Object chooser = newBestFrameSoFar();
        try {
            VideoFrameQualityScorer.FrameQuality darkerQuality =
                    VideoFrameQualityScorer.score(darkerPoorFrame);
            VideoFrameQualityScorer.FrameQuality brighterQuality =
                    VideoFrameQualityScorer.score(brighterPoorFrame);

            assertTrue(darkerQuality.isPoorRepresentativeCandidate());
            assertTrue(brighterQuality.isPoorRepresentativeCandidate());
            assertTrue(brighterQuality.brightness() > darkerQuality.brightness());

            consider(chooser, darkerPoorFrame);
            consider(chooser, brighterPoorFrame);

            assertSame(brighterPoorFrame, getFrame(chooser));
            assertFalse(hasRepresentative(chooser));
        } finally {
            recycleQuietly(darkerPoorFrame, brighterPoorFrame);
        }
    }

    private static Object newBestFrameSoFar() throws Exception {
        Class<?> chooserClass =
                Class.forName("ca.pkay.rcloneexplorer.Glide.VideoThumbnailFetcher$BestFrameSoFar");
        Constructor<?> constructor = chooserClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static void consider(Object chooser, Bitmap bitmap) throws Exception {
        Method method = chooser.getClass().getDeclaredMethod("consider", Bitmap.class);
        method.setAccessible(true);
        method.invoke(chooser, bitmap);
    }

    private static Bitmap getFrame(Object chooser) throws Exception {
        Method method = chooser.getClass().getDeclaredMethod("getFrame");
        method.setAccessible(true);
        return (Bitmap) method.invoke(chooser);
    }

    private static boolean hasRepresentative(Object chooser) throws Exception {
        Method method = chooser.getClass().getDeclaredMethod("hasRepresentative");
        method.setAccessible(true);
        return (boolean) method.invoke(chooser);
    }

    private static Bitmap createSolidFrame(int color) {
        Bitmap bitmap = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++) {
                bitmap.setPixel(x, y, color);
            }
        }
        return bitmap;
    }

    private static Bitmap createCheckerboardFrame(int colorA, int colorB) {
        Bitmap bitmap = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++) {
                boolean useA = ((x / 2) + (y / 2)) % 2 == 0;
                bitmap.setPixel(x, y, useA ? colorA : colorB);
            }
        }
        return bitmap;
    }

    private static void recycleQuietly(Bitmap... bitmaps) {
        for (Bitmap bitmap : bitmaps) {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
    }
}
