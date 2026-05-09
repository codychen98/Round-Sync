package ca.pkay.rcloneexplorer.Glide;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pure-JVM tests for {@link OkHttpMediaDataSource} URL-resolution and retry-once logic.
 *
 * All tests use injectable {@link OkHttpMediaDataSource.BaseUrlSupplier} and
 * {@link OkHttpMediaDataSource.HttpCaller} seams — no real OkHttp, no Android Context,
 * no {@link ca.pkay.rcloneexplorer.Services.ThumbnailServerManager} in these tests.
 *
 * Validation is via {@link OkHttpMediaDataSource#getSize()} since it exercises the same
 * resolution + retry path as {@code readAt} without requiring a byte-buffer fixture.
 */
public class OkHttpMediaDataSourceResolveTest {

    private static final String STABLE = "/myRemote/Cosplay/Nurse_Cosplay.mkv";

    // --- Helpers ---

    private static OkHttpMediaDataSource.RawResponse ok(long contentLength) {
        return new OkHttpMediaDataSource.RawResponse(200, null, contentLength);
    }

    private static OkHttpMediaDataSource.RawResponse httpError(int code) {
        return new OkHttpMediaDataSource.RawResponse(code, null, -1);
    }

    private static OkHttpMediaDataSource makeDs(
            OkHttpMediaDataSource.BaseUrlSupplier supplier,
            OkHttpMediaDataSource.HttpCaller caller) {
        return new OkHttpMediaDataSource(STABLE, null, supplier, caller);
    }

    // --- URL composition ---

    @Test
    public void getSize_composesResolvedUrlFromBaseAndStablePath() throws IOException {
        String[] calledWith = {null};
        OkHttpMediaDataSource ds = makeDs(
                () -> "http://127.0.0.1:12345/authTOKEN",
                new OkHttpMediaDataSource.HttpCaller() {
                    @Override
                    public OkHttpMediaDataSource.RawResponse range(String u, long p, long e) {
                        return httpError(500);
                    }

                    @Override
                    public OkHttpMediaDataSource.RawResponse head(String resolvedUrl) {
                        calledWith[0] = resolvedUrl;
                        return ok(7_000_000_000L);
                    }
                });
        ds.getSize();
        assertEquals("http://127.0.0.1:12345/authTOKEN" + STABLE, calledWith[0]);
    }

    // --- Manager not READY ---

    @Test
    public void getSize_managerNotReady_returnsNegativeOne() throws IOException {
        OkHttpMediaDataSource ds = makeDs(
                () -> null,
                new OkHttpMediaDataSource.HttpCaller() {
                    @Override
                    public OkHttpMediaDataSource.RawResponse range(String u, long p, long e) {
                        return httpError(500);
                    }

                    @Override
                    public OkHttpMediaDataSource.RawResponse head(String u) {
                        return ok(1L);
                    }
                });
        assertEquals(-1L, ds.getSize());
    }

    // --- HTTP error → retry-once ---

    @Test
    public void getSize_401OnFirstAttempt_retriesWithFreshUrlAndSucceeds() throws IOException {
        AtomicInteger calls = new AtomicInteger(0);
        OkHttpMediaDataSource ds = makeDs(
                () -> "http://127.0.0.1:29180/newauth",
                new OkHttpMediaDataSource.HttpCaller() {
                    @Override
                    public OkHttpMediaDataSource.RawResponse range(String u, long p, long e) {
                        return httpError(500);
                    }

                    @Override
                    public OkHttpMediaDataSource.RawResponse head(String u) {
                        return calls.incrementAndGet() == 1 ? httpError(401) : ok(7_320_000_000L);
                    }
                });
        assertEquals(7_320_000_000L, ds.getSize());
        assertEquals(2, calls.get());
    }

    @Test
    public void getSize_404OnFirstAttempt_retriesAndSucceeds() throws IOException {
        AtomicInteger calls = new AtomicInteger(0);
        OkHttpMediaDataSource ds = makeDs(
                () -> "http://127.0.0.1:29180/auth",
                new OkHttpMediaDataSource.HttpCaller() {
                    @Override
                    public OkHttpMediaDataSource.RawResponse range(String u, long p, long e) {
                        return httpError(500);
                    }

                    @Override
                    public OkHttpMediaDataSource.RawResponse head(String u) {
                        return calls.incrementAndGet() == 1 ? httpError(404) : ok(999L);
                    }
                });
        assertEquals(999L, ds.getSize());
        assertEquals(2, calls.get());
    }

    @Test
    public void getSize_5xxOnBothAttempts_returnsNegativeOne() throws IOException {
        OkHttpMediaDataSource ds = makeDs(
                () -> "http://127.0.0.1:29180/auth",
                new OkHttpMediaDataSource.HttpCaller() {
                    @Override
                    public OkHttpMediaDataSource.RawResponse range(String u, long p, long e) {
                        return httpError(500);
                    }

                    @Override
                    public OkHttpMediaDataSource.RawResponse head(String u) {
                        return httpError(503);
                    }
                });
        assertEquals(-1L, ds.getSize());
    }

    // --- IOException → retry-once ---

    @Test
    public void getSize_ioExceptionOnFirstAttempt_retriesAndSucceeds() throws IOException {
        AtomicInteger calls = new AtomicInteger(0);
        OkHttpMediaDataSource ds = makeDs(
                () -> "http://127.0.0.1:29180/auth",
                new OkHttpMediaDataSource.HttpCaller() {
                    @Override
                    public OkHttpMediaDataSource.RawResponse range(String u, long p, long e) {
                        return httpError(500);
                    }

                    @Override
                    public OkHttpMediaDataSource.RawResponse head(String u) throws IOException {
                        if (calls.incrementAndGet() == 1) {
                            throw new IOException("connection refused");
                        }
                        return ok(12345L);
                    }
                });
        assertEquals(12345L, ds.getSize());
        assertEquals(2, calls.get());
    }

    // --- Stale URL: different base URL per attempt ---

    @Test
    public void getSize_staleUrl_supplierReturnsNewBase_retriesAndSucceeds() throws IOException {
        AtomicInteger supplierCalls = new AtomicInteger(0);
        AtomicInteger headCalls = new AtomicInteger(0);
        OkHttpMediaDataSource ds = makeDs(
                () -> supplierCalls.incrementAndGet() == 1
                        ? "http://127.0.0.1:29179/oldauth"
                        : "http://127.0.0.1:29180/newauth",
                new OkHttpMediaDataSource.HttpCaller() {
                    @Override
                    public OkHttpMediaDataSource.RawResponse range(String u, long p, long e) {
                        return httpError(500);
                    }

                    @Override
                    public OkHttpMediaDataSource.RawResponse head(String resolvedUrl) {
                        headCalls.incrementAndGet();
                        // Old port → 401 (stale auth); new port → success
                        if (resolvedUrl.startsWith("http://127.0.0.1:29179/")) {
                            return httpError(401);
                        }
                        return ok(8_000_000_000L);
                    }
                });
        assertEquals(8_000_000_000L, ds.getSize());
        assertEquals(2, supplierCalls.get());
        assertEquals(2, headCalls.get());
    }

    // --- Cached size not re-fetched ---

    @Test
    public void getSize_cachedAfterFirstSuccess_doesNotCallHeadAgain() throws IOException {
        AtomicInteger calls = new AtomicInteger(0);
        OkHttpMediaDataSource ds = makeDs(
                () -> "http://127.0.0.1:12345/auth",
                new OkHttpMediaDataSource.HttpCaller() {
                    @Override
                    public OkHttpMediaDataSource.RawResponse range(String u, long p, long e) {
                        return httpError(500);
                    }

                    @Override
                    public OkHttpMediaDataSource.RawResponse head(String u) {
                        calls.incrementAndGet();
                        return ok(42L);
                    }
                });
        assertEquals(42L, ds.getSize());
        assertEquals(42L, ds.getSize());
        assertEquals("HEAD must be issued only once; second call served from cachedSize", 1, calls.get());
    }
}
