package ca.pkay.rcloneexplorer.util;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Polls a URL with HEAD requests until the server responds with HTTP 200,
 * a timeout elapses, or the check is cancelled.
 *
 * <p>Extracted from the existing video streaming readiness pattern in
 * FileExplorerFragment so both thumbnail server and video streaming share
 * the same implementation.</p>
 *
 * <p>All callbacks are invoked on the background polling thread. The caller
 * is responsible for switching to the main thread if UI updates are needed.</p>
 */
public class ServerReadinessChecker {

    private static final String TAG = "ServerReadinessChecker";

    /** Default total wait time before calling {@link Callback#onTimeout()}. */
    public static final long DEFAULT_TIMEOUT_MS = 30_000;

    /** Default delay between successive HEAD requests. */
    public static final long DEFAULT_POLL_INTERVAL_MS = 500;

    /** Connect and read timeout for each individual HEAD request. */
    private static final long REQUEST_TIMEOUT_S = 2;

    public interface Callback {
        /** Called when the server responds with HTTP 200. */
        void onReady();

        /** Called when the total timeout expires without a successful response. */
        void onTimeout();

        /**
         * Called when an unrecoverable error (other than a refused connection)
         * prevents polling from continuing (e.g. malformed URL).
         */
        void onError(Exception e);
    }

    private final String url;
    private final long timeoutMs;
    private final long pollIntervalMs;
    private final Callback callback;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    private final OkHttpClient client;

    private Thread pollingThread;

    private ServerReadinessChecker(Builder builder) {
        this.url = builder.url;
        this.timeoutMs = builder.timeoutMs;
        this.pollIntervalMs = builder.pollIntervalMs;
        this.callback = builder.callback;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(REQUEST_TIMEOUT_S, TimeUnit.SECONDS)
                .readTimeout(REQUEST_TIMEOUT_S, TimeUnit.SECONDS)
                .build();
    }

    /**
     * Starts polling on a new daemon thread. Returns immediately.
     * Safe to call only once per instance.
     */
    public synchronized void start() {
        if (pollingThread != null) {
            throw new IllegalStateException("ServerReadinessChecker already started");
        }
        cancelled.set(false);
        pollingThread = new Thread(this::poll, "ServerReadinessChecker");
        pollingThread.setDaemon(true);
        pollingThread.start();
    }

    /**
     * Signals the polling thread to stop. No callback will be invoked after
     * this returns (on a best-effort basis — a callback already in progress
     * may still complete).
     */
    public void cancel() {
        cancelled.set(true);
        Thread t = pollingThread;
        if (t != null) {
            t.interrupt();
        }
    }

    private void poll() {
        Request request;
        try {
            request = new Request.Builder().url(url).head().build();
        } catch (IllegalArgumentException e) {
            FLog.e(TAG, "Invalid URL for readiness check: %s", url);
            if (!cancelled.get()) {
                callback.onError(e);
            }
            return;
        }

        long remaining = timeoutMs;

        while (remaining > 0 && !cancelled.get()) {
            long iterStart = System.nanoTime();

            int code = -1;
            try {
                FLog.v(TAG, "HEAD %s", url);
                Response response = client.newCall(request).execute();
                code = response.code();
                response.close();
            } catch (IOException e) {
                FLog.v(TAG, "Server not yet available: %s", e.getMessage());
            }

            if (code == 200) {
                if (!cancelled.get()) {
                    callback.onReady();
                }
                return;
            }

            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            long elapsed = (System.nanoTime() - iterStart) / 1_000_000;
            remaining -= elapsed;
        }

        if (!cancelled.get()) {
            FLog.w(TAG, "Server readiness timeout after %dms for %s", timeoutMs, url);
            callback.onTimeout();
        }
    }

    public static final class Builder {
        private final String url;
        private long timeoutMs = DEFAULT_TIMEOUT_MS;
        private long pollIntervalMs = DEFAULT_POLL_INTERVAL_MS;
        private Callback callback;

        public Builder(String url) {
            this.url = url;
        }

        public Builder timeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
            return this;
        }

        public Builder pollIntervalMs(long pollIntervalMs) {
            this.pollIntervalMs = pollIntervalMs;
            return this;
        }

        public Builder callback(Callback callback) {
            this.callback = callback;
            return this;
        }

        public ServerReadinessChecker build() {
            if (callback == null) {
                throw new IllegalStateException("callback must be set");
            }
            return new ServerReadinessChecker(this);
        }
    }
}
