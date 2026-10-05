package ca.pkay.rcloneexplorer.Glide;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.bumptech.glide.signature.ObjectKey;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class ThumbnailDiskCacheEvictorTest {

    private static final long DISK_CACHE_SIZE_BYTES = 500L * 1024L * 1024L;

    private ThumbnailDiskCache cache;

    @Before
    public void setUp() {
        cache = new ThumbnailDiskCache(createTempCacheDir(), DISK_CACHE_SIZE_BYTES);
    }

    @After
    public void tearDown() {
        cache.close();
    }

    @Test
    public void isCachedIn_returnsTrueWhenSafeKeyPresent() {
        String label = "sample__abc123.mkv";
        cache.store(new ObjectKey(label), new byte[] {9});
        assertTrue(ThumbnailDiskCacheEvictor.isCachedIn(cache, label));
        assertFalse(ThumbnailDiskCacheEvictor.isCachedIn(cache, "missing-key"));
    }

    @Test
    public void put_doesNotOverwrite_butStoreDoes() throws Exception {
        ObjectKey key = new ObjectKey("thumb__replace.jpg");
        cache.store(key, new byte[] {1, 1});
        cache.put(key, file -> {
            try (OutputStream out = new FileOutputStream(file)) {
                out.write(new byte[] {2, 2, 2});
                return true;
            } catch (IOException e) {
                return false;
            }
        });
        File afterPut = cache.get(key);
        assertNotNull(afterPut);
        assertArrayEquals(new byte[] {1, 1}, Files.readAllBytes(afterPut.toPath()));

        cache.store(key, new byte[] {3});
        File afterStore = cache.get(key);
        assertNotNull(afterStore);
        assertArrayEquals(new byte[] {3}, Files.readAllBytes(afterStore.toPath()));
    }

    @Test
    public void put_replacesPoisonedSvgEntry() throws Exception {
        ObjectKey key = new ObjectKey("thumb__poisoned.jpg");
        byte[] jpeg = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
        cache.store(key, "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8));

        cache.put(key, file -> writeBytes(file, jpeg));

        File afterPut = cache.get(key);
        assertNotNull(afterPut);
        assertArrayEquals(jpeg, Files.readAllBytes(afterPut.toPath()));

        // A second put over the now-healthy JPEG entry must still be a no-op.
        cache.put(key, file -> writeBytes(file, new byte[] {5, 5}));
        assertArrayEquals(jpeg, Files.readAllBytes(cache.get(key).toPath()));
    }

    @Test
    public void removeSafeKey_dropsEntryByJournalKey() {
        ObjectKey key = new ObjectKey("thumb__remove.jpg");
        cache.store(key, new byte[] {7});
        String safeKey = cache.safeKeyFor(key);
        assertNotNull(cache.fileForSafeKey(safeKey));
        assertTrue(cache.removeSafeKey(safeKey));
        assertNull(cache.get(key));
        assertFalse(cache.removeSafeKey(safeKey));
    }

    private static boolean writeBytes(File file, byte[] bytes) {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static File createTempCacheDir() {
        File dir = new File(System.getProperty("java.io.tmpdir"), "thumb-disk-evictor-test-" + System.nanoTime());
        assertTrue(dir.mkdirs() || dir.isDirectory());
        return dir;
    }
}
