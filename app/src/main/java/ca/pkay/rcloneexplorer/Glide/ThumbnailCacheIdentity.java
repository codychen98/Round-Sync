package ca.pkay.rcloneexplorer.Glide;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import ca.pkay.rcloneexplorer.Items.FileItem;

import com.bumptech.glide.disklrucache.DiskLruCache;

public final class ThumbnailCacheIdentity {

    private static final String FILE_NAMESPACE = "thumbFile";
    private static final String VIDEO_NAMESPACE = "thumbVideo";
    private static final String VIDEO_RELOAD_NAMESPACE = "thumbVideoReload";
    private static final String VIDEO_VERSION_TOKEN = "|thumbV2";
    private static final String CACHE_PROBE_URL_PREFIX = "http://127.0.0.1/cacheProbe";

    /** Upper bound when scanning reload-epoch disk keys after a cold start. */
    static final int MAX_RELOAD_EPOCH_DISK_PROBE = 64;

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
                context,
                item.getRemote().getName(),
                item.getPath(),
                item.getMimeType());
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
            @NonNull Context context,
            @NonNull String remoteName,
            @NonNull String remoteFilePath,
            @Nullable String mimeType) {
        if (mimeType == null) {
            return null;
        }
        if (mimeType.startsWith("image/")) {
            return fileDataCacheKey(remoteName, remoteFilePath);
        }
        if (mimeType.startsWith("video/")) {
            return resolveVideoDiskCacheKeyLabel(context, remoteName, remoteFilePath);
        }
        return null;
    }

    /**
     * Batch disk probe variant — uses an already-open {@link DiskLruCache} (explorer seed / prefetch).
     */
    @Nullable
    public static String prefetchDiskCacheKeyLabel(
            @NonNull DiskLruCache cache,
            @NonNull String remoteName,
            @NonNull String remoteFilePath,
            @Nullable String mimeType) {
        if (mimeType == null) {
            return null;
        }
        if (mimeType.startsWith("image/")) {
            return fileDataCacheKey(remoteName, remoteFilePath);
        }
        if (mimeType.startsWith("video/")) {
            return resolveVideoDiskCacheKeyIn(cache, remoteName, remoteFilePath);
        }
        return null;
    }

    /**
     * Legacy label without disk probe — uses in-memory reload epoch only (tests / stable key naming).
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

    /**
     * Resolves the disk-cache label Glide should load for a video after cold start. Reload JPEGs
     * may live under {@code thumbVideoReload|reloadN} while in-memory epoch resets to 0.
     */
    @NonNull
    public static String resolveVideoDiskCacheKeyLabel(
            @NonNull Context context,
            @NonNull String remoteName,
            @NonNull String remoteFilePath) {
        final String[] resolved = new String[] {
                videoDiskCacheKeyLabel(remoteName, remoteFilePath, 0),
        };
        ThumbnailDiskCacheEvictor.withOpenCache(context, cache -> {
            resolved[0] = resolveVideoDiskCacheKeyIn(cache, remoteName, remoteFilePath);
        });
        return resolved[0];
    }

    /**
     * Same as {@link #resolveVideoDiskCacheKeyLabel} but uses an open cache (batch folder probe).
     */
    @NonNull
    public static String resolveVideoDiskCacheKeyIn(
            @NonNull DiskLruCache cache,
            @NonNull String remoteName,
            @NonNull String remoteFilePath) {
        String stableNormalized = ThumbnailStablePath.normalize(
                stableServePath(remoteName, remoteFilePath));
        int reloadEpoch = ThumbnailReloadEpoch.get(stableNormalized);
        if (reloadEpoch > 0) {
            String reloadKey = videoDiskCacheKeyLabel(remoteName, remoteFilePath, reloadEpoch);
            if (ThumbnailDiskCacheEvictor.isCachedIn(cache, reloadKey)) {
                return reloadKey;
            }
        }
        String canonicalKey = videoDiskCacheKeyLabel(remoteName, remoteFilePath, 0);
        if (ThumbnailDiskCacheEvictor.isCachedIn(cache, canonicalKey)) {
            return canonicalKey;
        }
        for (int epoch = MAX_RELOAD_EPOCH_DISK_PROBE; epoch >= 1; epoch--) {
            String reloadKey = videoDiskCacheKeyLabel(remoteName, remoteFilePath, epoch);
            if (ThumbnailDiskCacheEvictor.isCachedIn(cache, reloadKey)) {
                return reloadKey;
            }
        }
        return canonicalKey;
    }

    /**
     * Resolves a video disk-cache label from a legacy encoded stable path (Glide model path).
     */
    @NonNull
    public static String resolveVideoDiskCacheKeyLabelFromLegacyPath(
            @NonNull Context context,
            @NonNull String legacyStablePath) {
        final String[] resolved = new String[] {
                defaultVideoDiskCacheKeyLabelForLegacyPath(legacyStablePath),
        };
        ThumbnailDiskCacheEvictor.withOpenCache(context, cache -> {
            resolved[0] = resolveVideoDiskCacheKeyFromLegacyPathIn(cache, legacyStablePath);
        });
        return resolved[0];
    }

    @NonNull
    static String resolveVideoDiskCacheKeyFromLegacyPathIn(
            @NonNull DiskLruCache cache,
            @NonNull String legacyStablePath) {
        String normalized = ThumbnailStablePath.normalize(legacyStablePath);
        int reloadEpoch = ThumbnailReloadEpoch.get(normalized);
        if (reloadEpoch > 0) {
            String reloadKey = ReadableCacheKey.fromStablePath(
                    legacyStablePath + "|reload" + reloadEpoch,
                    VIDEO_RELOAD_NAMESPACE);
            if (ThumbnailDiskCacheEvictor.isCachedIn(cache, reloadKey)) {
                return reloadKey;
            }
        }
        String canonicalKey = ReadableCacheKey.fromStablePath(
                legacyStablePath + VIDEO_VERSION_TOKEN,
                VIDEO_NAMESPACE);
        if (ThumbnailDiskCacheEvictor.isCachedIn(cache, canonicalKey)) {
            return canonicalKey;
        }
        for (int epoch = MAX_RELOAD_EPOCH_DISK_PROBE; epoch >= 1; epoch--) {
            String reloadKey = ReadableCacheKey.fromStablePath(
                    legacyStablePath + "|reload" + epoch,
                    VIDEO_RELOAD_NAMESPACE);
            if (ThumbnailDiskCacheEvictor.isCachedIn(cache, reloadKey)) {
                return reloadKey;
            }
        }
        return defaultVideoDiskCacheKeyLabelForLegacyPath(legacyStablePath);
    }

    @NonNull
    private static String defaultVideoDiskCacheKeyLabelForLegacyPath(@NonNull String legacyStablePath) {
        int reloadEpoch = ThumbnailReloadEpoch.get(
                ThumbnailStablePath.normalize(legacyStablePath));
        if (reloadEpoch > 0) {
            return ReadableCacheKey.fromStablePath(
                    legacyStablePath + "|reload" + reloadEpoch,
                    VIDEO_RELOAD_NAMESPACE);
        }
        return ReadableCacheKey.fromStablePath(
                legacyStablePath + VIDEO_VERSION_TOKEN,
                VIDEO_NAMESPACE);
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
