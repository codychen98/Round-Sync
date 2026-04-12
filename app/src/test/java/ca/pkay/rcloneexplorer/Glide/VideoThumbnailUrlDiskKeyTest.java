package ca.pkay.rcloneexplorer.Glide;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

/**
 * G1: JPEG disk cache for video thumbs must survive localhost port changes (same remote object path).
 */
public class VideoThumbnailUrlDiskKeyTest {

    @Test
    public void equals_sameEncodedPathDifferentPort() {
        String lowPort = "http://127.0.0.1:29179/hiddenAuth%2Fmyremote/Archive/Cosplay/a.mkv";
        String highPort = "http://127.0.0.1:38472/hiddenAuth%2Fmyremote/Archive/Cosplay/a.mkv";
        VideoThumbnailUrl a = new VideoThumbnailUrl(lowPort);
        VideoThumbnailUrl b = new VideoThumbnailUrl(highPort);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    public void notEquals_differentRemoteFilePath() {
        String u1 = "http://127.0.0.1:29179/hiddenAuth%2Fmyremote/Archive/a.mkv";
        String u2 = "http://127.0.0.1:29179/hiddenAuth%2Fmyremote/Archive/b.mkv";
        assertNotEquals(new VideoThumbnailUrl(u1), new VideoThumbnailUrl(u2));
    }
}
