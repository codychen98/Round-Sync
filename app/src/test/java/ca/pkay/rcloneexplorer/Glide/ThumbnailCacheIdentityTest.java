package ca.pkay.rcloneexplorer.Glide;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ThumbnailCacheIdentityTest {

    @Test
    public void stableServePath_normalizesLeadingSlash() {
        assertEquals(
                "/drive/Anime/episode 01.mkv",
                ThumbnailCacheIdentity.stableServePath("drive", "/Anime/episode 01.mkv"));
        assertEquals(
                "/drive/Anime/episode 01.mkv",
                ThumbnailCacheIdentity.stableServePath("drive", "Anime/episode 01.mkv"));
    }

    @Test
    public void fileDataCacheKey_matchesHttpThumbnailModelIdentity() {
        String stablePath = "/drive/Pictures/cover.jpg";

        assertEquals(
                ReadableCacheKey.fromStablePath(stablePath, "thumbFile"),
                ThumbnailCacheIdentity.fileDataCacheKey("drive", "/Pictures/cover.jpg"));
    }

    @Test
    public void videoDataCacheKey_includesVideoVersionToken() {
        String stablePath = "/drive/Videos/clip.mp4";
        String fileKey = ThumbnailCacheIdentity.fileDataCacheKey("drive", "/Videos/clip.mp4");
        String videoKey = ThumbnailCacheIdentity.videoDataCacheKey("drive", "/Videos/clip.mp4");

        assertEquals(
                ReadableCacheKey.fromStablePath(stablePath + "|thumbV2", "thumbVideo"),
                videoKey);
        assertNotEquals(fileKey, videoKey);
    }

    @Test
    public void buildCacheProbeModel_usesStableImageIdentity() {
        Object model = ThumbnailCacheIdentity.buildCacheProbeModel("drive", "/Pictures/cover.jpg", "image/jpeg");

        assertTrue(model instanceof HttpServeThumbnailGlideUrl);
        assertEquals(
                ThumbnailCacheIdentity.fileDataCacheKey("drive", "/Pictures/cover.jpg"),
                ((HttpServeThumbnailGlideUrl) model).getCacheKey());
    }

    @Test
    public void buildCacheProbeModel_usesVideoModelForVideos() {
        Object model = ThumbnailCacheIdentity.buildCacheProbeModel("drive", "/Videos/clip.mp4", "video/mp4");

        assertTrue(model instanceof VideoThumbnailUrl);
        assertEquals(
                ThumbnailCacheIdentity.stableServePath("drive", "/Videos/clip.mp4"),
                ((VideoThumbnailUrl) model).getStablePath());
    }

    @Test
    public void buildCacheProbeModel_skipsUnsupportedMimeTypes() {
        assertNull(ThumbnailCacheIdentity.buildCacheProbeModel("drive", "/Docs/readme.txt", "text/plain"));
    }
}
