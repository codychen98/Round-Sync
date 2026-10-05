package ca.pkay.rcloneexplorer.Glide;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import ca.pkay.rcloneexplorer.util.ThumbnailPrefetchTargets;

import com.bumptech.glide.load.engine.cache.SafeKeyGenerator;
import com.bumptech.glide.signature.ObjectKey;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/** Robolectric because the probe / serve URLs are built with {@code android.net.Uri}. */
@RunWith(RobolectricTestRunner.class)
public class ThumbnailCacheIdentityTest {

    private static final String ENCODED_NAME_PATH = "Photo/Photo/(Life)/2016-02-19 {19.53.07}__IMG_1558.jpg";
    private static final String SPECIAL_CHARS_PATH = "Photo/Trip #3/a+b & c\u00e9 \u65e5\u672c.jpg";

    /** What the explorer and the prefetch executor actually load for {@code remote/path}. */
    private static String realServeUrl(String remoteName, String remoteFilePath) {
        return ThumbnailPrefetchTargets.buildThumbnailHttpUrl(
                ThumbnailPrefetchTargets.hiddenServePath("authToken", remoteName), 29179, remoteFilePath);
    }

    @Test
    public void fileDataCacheKey_matchesGlideKeyForPercentEncodedNames() {
        for (String path : new String[] {ENCODED_NAME_PATH, SPECIAL_CHARS_PATH}) {
            String probeKey = ThumbnailCacheIdentity.fileDataCacheKey("pCloud", path);
            HttpServeThumbnailGlideUrl probeModel = new HttpServeThumbnailGlideUrl(
                    ThumbnailCacheIdentity.buildCacheProbeUrl("pCloud", path));
            HttpServeThumbnailGlideUrl realModel = new HttpServeThumbnailGlideUrl(realServeUrl("pCloud", path));

            assertEquals(path, probeModel.getCacheKey(), probeKey);
            assertEquals(path, realModel.getCacheKey(), probeKey);
            // The decoded form must no longer be what the probe computes.
            assertNotEquals(path,
                    ReadableCacheKey.fromStablePath("/pCloud/" + path, "thumbFile"), probeKey);
        }
    }

    @Test
    public void fileDataCacheKey_hashesToGlideSafeKey() {
        // Glide's DATA entry key is DataCacheKey(model, EmptySignature); EmptySignature adds no
        // bytes, so the SafeKeyGenerator hash of the model alone is the on-disk file name.
        SafeKeyGenerator safeKeys = new SafeKeyGenerator();
        HttpServeThumbnailGlideUrl model = new HttpServeThumbnailGlideUrl(realServeUrl("pCloud", ENCODED_NAME_PATH));
        String label = ThumbnailCacheIdentity.fileDataCacheKey("pCloud", ENCODED_NAME_PATH);

        assertEquals(safeKeys.getSafeKey(model), safeKeys.getSafeKey(new ObjectKey(label)));
    }

    @Test
    public void videoDataCacheKey_matchesEncodedDiskLabel() {
        assertEquals(
                ThumbnailCacheIdentity.videoDiskCacheKeyLabel("pCloud", "Video Archive/clip (1).mkv", 0),
                ThumbnailCacheIdentity.videoDataCacheKey("pCloud", "Video Archive/clip (1).mkv"));
        assertEquals(
                ThumbnailCacheIdentity.videoDiskCacheKeyLabel("pCloud", "Video Archive/clip (1).mkv", 3),
                ThumbnailCacheIdentity.videoReloadDataCacheKey("pCloud", "Video Archive/clip (1).mkv", 3));
    }

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
                ThumbnailCacheIdentity.legacyEncodedServePath("drive", "/Videos/clip.mp4"),
                ((VideoThumbnailUrl) model).getStablePath());
    }

    @Test
    public void buildCacheProbeUrl_percentEncodesPathSegments() {
        String probeUrl = ThumbnailCacheIdentity.buildCacheProbeUrl(
                "pCloudLock",
                "Video Archive/Anime/clip.mkv");
        assertEquals(
                "/pCloudLock/Video%20Archive/Anime/clip.mkv",
                ThumbnailCacheIdentity.legacyEncodedServePath("pCloudLock", "Video Archive/Anime/clip.mkv"));
        assertEquals(
                ThumbnailStablePath.legacyPathFromServeUrl(
                        "http://127.0.0.1:29179/authToken/pCloudLock/Video%20Archive/Anime/clip.mkv"),
                ThumbnailStablePath.legacyPathFromServeUrl(probeUrl));
    }

    @Test
    public void videoDiskCacheKeyLabel_matchesLoaderKeyForEncodedPath() {
        String legacy = ThumbnailCacheIdentity.legacyEncodedServePath(
                "pCloudLock",
                "Video Archive/Anime/clip.mkv");
        String probeUrl = ThumbnailCacheIdentity.buildCacheProbeUrl(
                "pCloudLock",
                "Video Archive/Anime/clip.mkv");
        VideoThumbnailUrl model = new VideoThumbnailUrl(probeUrl);
        assertEquals(legacy, model.getStablePath());
        assertEquals(
                ReadableCacheKey.fromStablePath(legacy + "|thumbV2", "thumbVideo"),
                ThumbnailCacheIdentity.videoDiskCacheKeyLabel("pCloudLock", "Video Archive/Anime/clip.mkv", 0));
    }

    @Test
    public void buildCacheProbeModel_skipsUnsupportedMimeTypes() {
        assertNull(ThumbnailCacheIdentity.buildCacheProbeModel("drive", "/Docs/readme.txt", "text/plain"));
    }

    @Test
    public void prefetchDiskCacheKeyLabel_matchesImageAndVideoModels() {
        assertEquals(
                ThumbnailCacheIdentity.fileDataCacheKey("drive", "/Pictures/cover.jpg"),
                ThumbnailCacheIdentity.prefetchDiskCacheKeyLabel(
                        "drive", "/Pictures/cover.jpg", "image/jpeg"));
        assertEquals(
                ThumbnailCacheIdentity.videoDiskCacheKeyLabel("pCloudLock", "Video Archive/clip.mkv", 0),
                ThumbnailCacheIdentity.prefetchDiskCacheKeyLabel(
                        "pCloudLock", "Video Archive/clip.mkv", "video/x-matroska"));
        assertNull(ThumbnailCacheIdentity.prefetchDiskCacheKeyLabel("drive", "/Docs/readme.txt", "text/plain"));
    }

    @Test
    public void videoReloadDataCacheKey_usesReloadNamespace() {
        String key = ThumbnailCacheIdentity.videoReloadDataCacheKey("drive", "/Videos/clip.mp4", 2);
        assertEquals(
                ReadableCacheKey.fromStablePath("/drive/Videos/clip.mp4|reload2", "thumbVideoReload"),
                key);
        assertNotEquals(
                ThumbnailCacheIdentity.videoDataCacheKey("drive", "/Videos/clip.mp4"),
                key);
    }
}
