package ca.pkay.rcloneexplorer.Glide;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.bumptech.glide.disklrucache.DiskLruCache;
import com.bumptech.glide.load.engine.cache.SafeKeyGenerator;
import com.bumptech.glide.signature.ObjectKey;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;

public class ThumbnailDiskCacheEvictorTest {

    private static final int DISK_CACHE_VERSION = 1;
    private static final int DISK_CACHE_VALUE_COUNT = 1;
    private static final long DISK_CACHE_SIZE_BYTES = 500L * 1024L * 1024L;

    @Test
    public void isCachedIn_returnsTrueWhenSafeKeyPresent() throws Exception {
        File cacheDir = createTempCacheDir();
        String label = "sample__abc123.mkv";
        String safeKey = new SafeKeyGenerator().getSafeKey(new ObjectKey(label));
        DiskLruCache cache = DiskLruCache.open(
                cacheDir,
                DISK_CACHE_VERSION,
                DISK_CACHE_VALUE_COUNT,
                DISK_CACHE_SIZE_BYTES);
        try {
            DiskLruCache.Editor editor = cache.edit(safeKey);
            editor.set(0, new ByteArrayInputStream(new byte[] {9}));
            editor.commit();
            assertTrue(ThumbnailDiskCacheEvictor.isCachedIn(cache, label));
            assertFalse(ThumbnailDiskCacheEvictor.isCachedIn(cache, "missing-key"));
        } finally {
            cache.close();
        }
    }

    private static File createTempCacheDir() {
        File dir = new File(System.getProperty("java.io.tmpdir"), "thumb-disk-evictor-test-" + System.nanoTime());
        assertTrue(dir.mkdirs() || dir.isDirectory());
        return dir;
    }
}
