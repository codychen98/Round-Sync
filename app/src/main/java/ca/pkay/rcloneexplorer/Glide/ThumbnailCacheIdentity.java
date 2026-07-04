package ca.pkay.rcloneexplorer.Glide;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import ca.pkay.rcloneexplorer.Items.FileItem;

public final class ThumbnailCacheIdentity {

    private static final String FILE_NAMESPACE = "thumbFile";
    private static final String VIDEO_NAMESPACE = "thumbVideo";
    private static final String VIDEO_RELOAD_NAMESPACE = "thumbVideoReload";
    private static final String VIDEO_VERSION_TOKEN = "|thumbV2";
    private static final String CACHE_PROBE_URL_PREFIX = "http://127.0.0.1/cacheProbe";

    private ThumbnailCacheIdentity() {
    }

    @NonNull
    public static String fileDataCacheKey(@NonNull FileItem item) {
        return fileDataCacheKey(item.getRemote().getName(), item.getPath());
    }

    @NonNull
    public static String fileDataCacheKey(@NonNull String remoteName, @NonNull String remoteFilePath) {
        return ReadableCacheKey.fromStablePath(stableServePath(remoteName, remoteFilePath), FILE_NAMESPACE);
    }

    @NonNull
    public static String videoDataCacheKey(@NonNull FileItem item) {
        return videoDataCacheKey(item.getRemote().getName(), item.getPath());
    }

    @NonNull
    public static String videoDataCacheKey(@NonNull String remoteName, @NonNull String remoteFilePath) {
        return ReadableCacheKey.fromStablePath(
                stableServePath(remoteName, remoteFilePath) + VIDEO_VERSION_TOKEN,
                VIDEO_NAMESPACE);
    }

    @NonNull
    public static String videoReloadDataCacheKey(
            @NonNull String remoteName,
            @NonNull String remoteFilePath,
            int reloadEpoch) {
        return ReadableCacheKey.fromStablePath(
                stableServePath(remoteName, remoteFilePath) + "|reload" + reloadEpoch,
                VIDEO_RELOAD_NAMESPACE);
    }

    @NonNull
    public static String videoReloadDataCacheKey(@NonNull FileItem item, int reloadEpoch) {
        return videoReloadDataCacheKey(item.getRemote().getName(), item.getPath(), reloadEpoch);
    }

    public static boolean isPrefetchThumbnailCached(@NonNull Context context, @NonNull FileItem item) {
        String label = prefetchDiskCacheKeyLabel(
                item.getRemote().getName(), item.getPath(), item.getMimeType());
        if (label == null) {
            return false;
        }
        return ThumbnailDiskCacheEvictor.isCachedByLabel(context, label);
    }

    /**
     * Readable disk-cache label aligned with Glide HTTP-serve / video thumbnail keys.
     * Returns null when the file is not a prefetch thumbnail target (unsupported mime).
     */
    @Nullable
    public static String prefetchDiskCacheKeyLabel(
            @NonNull String remoteName,
            @NonNull String remoteFilePath,
            @Nullable String mimeType) {
        Object model = buildCacheProbeModel(remoteName, remoteFilePath, mimeType);
        if (model instanceof HttpServeThumbnailGlideUrl) {
            return ((HttpServeThumbnailGlideUrl) model).getCacheKey();
        }
        if (model instanceof VideoThumbnailUrl) {
            VideoThumbnailUrl video = (VideoThumbnailUrl) model;
            int reloadEpoch = ThumbnailReloadEpoch.get(
                    ThumbnailStablePath.normalize(video.getStablePath()));
            return videoDiskCacheKeyLabel(remoteName, remoteFilePath, reloadEpoch);
        }
        return null;
    }

    @Nullable
    public static Object buildDiskCacheLoadModel(
            @NonNull String remoteName,
            @NonNull String remoteFilePath,
            @Nullable String mimeType) {
        return buildCacheProbeModel(remoteName, remoteFilePath, mimeType);
    }

    @Nullable
    static Object buildCacheProbeModel(
            @NonNull String remoteName,
            @NonNull String remoteFilePath,
            @Nullable String mimeType) {
        if (mimeType == null) {
            return null;
        }
        String probeUrl = buildCacheProbeUrl(remoteName, remoteFilePath);
        if (mimeType.startsWith("video/")) {
            return new VideoThumbnailUrl(probeUrl);
        }
        if (mimeType.startsWith("image/")) {
            return new HttpServeThumbnailGlideUrl(probeUrl);
        }
        return null;
    }

    @NonNull
    public static String stableServePath(@NonNull String remoteName, @NonNull String remoteFilePath) {
        String normalizedPath = remoteFilePath;
        while (normalizedPath.startsWith("/")) {
            normalizedPath = normalizedPath.substring(1);
        }
        String stable = normalizedPath.isEmpty()
                ? "/" + remoteName
                : "/" + remoteName + "/" + normalizedPath;
        return ThumbnailStablePath.normalize(stable);
    }

    /**
     * Probe URL whose legacy path (after the dummy first segment) matches real HTTP-serve URLs:
     * path segments are percent-encoded via {@link Uri.Builder#appendPath(String)}.
     */
    @NonNull
    static String buildCacheProbeUrl(@NonNull String remoteName, @NonNull String remoteFilePath) {
        Uri.Builder builder = Uri.parse(CACHE_PROBE_URL_PREFIX).buildUpon();
        for (String segment : stableServePath(remoteName, remoteFilePath).split("/")) {
            if (!segment.isEmpty()) {
                builder.appendPath(segment);
            }
        }
        return builder.build().toString();
    }

    /** Legacy percent-encoded stable path aligned with [VideoThumbnailUrl] / [HttpServeThumbnailGlideUrl] disk keys. */
    @NonNull
    public static String legacyEncodedServePath(@NonNull String remoteName, @NonNull String remoteFilePath) {
        return ThumbnailStablePath.legacyPathFromServeUrl(buildCacheProbeUrl(remoteName, remoteFilePath));
    }

    /** Disk-cache label for video thumbnail eviction (matches [VideoThumbnailLoader] ObjectKey). */
    @NonNull
    public static String videoDiskCacheKeyLabel(
            @NonNull String remoteName,
            @NonNull String remoteFilePath,
            int reloadEpoch) {
        String legacy = legacyEncodedServePath(remoteName, remoteFilePath);
        if (reloadEpoch > 0) {
            return ReadableCacheKey.fromStablePath(
                    legacy + "|reload" + reloadEpoch,
                    VIDEO_RELOAD_NAMESPACE);
        }
        return ReadableCacheKey.fromStablePath(legacy + VIDEO_VERSION_TOKEN, VIDEO_NAMESPACE);
    }
}
