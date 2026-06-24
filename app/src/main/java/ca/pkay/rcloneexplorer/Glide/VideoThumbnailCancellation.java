package ca.pkay.rcloneexplorer.Glide;

/**
 * Lets Exo/MMR thumbnail work consult cancellation without tying to a Glide {@link VideoThumbnailFetcher}.
 */
public interface VideoThumbnailCancellation {

    VideoThumbnailCancellation NEVER_CANCELLED = () -> false;

    boolean isCancelled();
}
