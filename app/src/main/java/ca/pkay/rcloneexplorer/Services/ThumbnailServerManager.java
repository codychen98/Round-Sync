package ca.pkay.rcloneexplorer.Services;

import android.content.Context;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

import ca.pkay.rcloneexplorer.Items.RemoteItem;
import ca.pkay.rcloneexplorer.Rclone;
import ca.pkay.rcloneexplorer.util.FLog;
import ca.pkay.rcloneexplorer.util.ServerReadinessChecker;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Singleton manager for the rclone HTTP serve process used to load thumbnails.
 *
 * <p>Lifecycle: STOPPED -> STARTING -> READY, with FAILED as the terminal error
 * state after MAX_RESTARTS unsuccessful attempts. Callers observe
 * {@link #getState()} to know when to issue Glide thumbnail requests.</p>
 *
 * <p>Thread safety: all state transitions are guarded by the instance monitor.
 * A generation counter prevents stale background threads from triggering
 * restarts after a newer process has already been spawned.</p>
 */
public class ThumbnailServerManager {

    private static final String TAG = "ThumbnailServerManager";

    public enum ServerState { STOPPED, STARTING, READY, FAILED }

    private static final int MAX_RESTARTS = 3;
    private static final long[] BACKOFF_MS = {2_000L, 4_000L, 8_000L};
    private static final long HEALTH_CHECK_INTERVAL_MS = 10_000L;
    private static final int HEALTH_FAIL_THRESHOLD = 3;
    private static final long HEALTH_REQUEST_TIMEOUT_S = 2L;

    private static volatile ThumbnailServerManager INSTANCE;

    private final MutableLiveData<ServerState> stateLiveData =
            new MutableLiveData<>(ServerState.STOPPED);

    // Written only from synchronized blocks; read by background threads (generation check).
    private Context appContext;
    private RemoteItem currentRemote;
    private int currentPort;
    private String currentAuth;

    private Process serverProcess;
    private ServerReadinessChecker readinessChecker;

    /** Incremented each time a new process is spawned. Background threads capture
     *  their own copy and bail out if it no longer matches. */
    private int generation = 0;
    private int restartCount = 0;
    private boolean intentionalStop = false;
    private long spawnStartTimeMs = 0;

    private ThumbnailServerManager() {}

    public static ThumbnailServerManager getInstance() {
        if (INSTANCE == null) {
            synchronized (ThumbnailServerManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new ThumbnailServerManager();
                }
            }
        }
        return INSTANCE;
    }

    public LiveData<ServerState> getState() {
        return stateLiveData;
    }

    /** Starts the thumbnail server for the given remote. No-op if already STARTING or READY. */
    public synchronized void start(Context context, RemoteItem remote, int port, String auth) {
        ServerState current = stateLiveData.getValue();
        if (current == ServerState.STARTING || current == ServerState.READY) {
            FLog.d(TAG, "start() ignored — already in state %s", current);
            return;
        }
        appContext = context.getApplicationContext();
        currentRemote = remote;
        currentPort = port;
        currentAuth = auth;
        intentionalStop = false;
        restartCount = 0;
        spawnProcess();
    }

    /** Stops the server. Cancels any pending readiness checker and destroys the process. */
    public synchronized void stop() {
        intentionalStop = true;
        cancelReadinessChecker();
        destroyProcess();
        setState(ServerState.STOPPED);
    }

    // region — Internal lifecycle (must be called from synchronized context)

    private void spawnProcess() {
        setState(ServerState.STARTING);
        spawnStartTimeMs = System.currentTimeMillis();
        generation++;
        final int gen = generation;

        Rclone rclone = new Rclone(appContext);
        String hiddenPath = "/" + currentAuth + "/" + currentRemote.getName();
        serverProcess = rclone.serve(
                Rclone.SERVE_PROTOCOL_HTTP, currentPort, false,
                null, null, currentRemote, "", hiddenPath);

        if (serverProcess == null) {
            FLog.e(TAG, "rclone.serve() returned null — cannot start thumbnail server");
            handleFailure(gen);
            return;
        }

        launchProcessMonitor(gen, serverProcess);
        beginReadinessCheck(gen);
    }

    private void handleFailure(int gen) {
        // Must be called from synchronized context.
        if (intentionalStop || generation != gen) {
            return;
        }
        if (restartCount < MAX_RESTARTS) {
            long backoff = BACKOFF_MS[restartCount];
            FLog.w(TAG, "Scheduling restart attempt %d/%d after %dms", restartCount + 1, MAX_RESTARTS, backoff);
            restartCount++;
            scheduleRestart(gen, backoff);
        } else {
            FLog.e(TAG, "Thumbnail server failed after %d restart attempts", MAX_RESTARTS);
            setState(ServerState.FAILED);
        }
    }

    private void scheduleRestart(int failedGen, long delayMs) {
        // Called from synchronized context; spawns a thread to wait then re-enter.
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            synchronized (ThumbnailServerManager.this) {
                if (!intentionalStop && generation == failedGen) {
                    destroyProcess();
                    spawnProcess();
                }
            }
        }, "ThumbnailServer-Restart-" + failedGen);
        t.setDaemon(true);
        t.start();
    }

    private void beginReadinessCheck(int gen) {
        cancelReadinessChecker();
        String probeUrl = buildProbeUrl();
        readinessChecker = new ServerReadinessChecker.Builder(probeUrl)
                .callback(new ServerReadinessChecker.Callback() {
                    @Override
                    public void onReady() {
                        synchronized (ThumbnailServerManager.this) {
                            if (intentionalStop || generation != gen) return;
                            long elapsedMs = System.currentTimeMillis() - spawnStartTimeMs;
                            FLog.d(TAG, "STARTING -> READY at %s (took %.1fs)", probeUrl, elapsedMs / 1000.0);
                            restartCount = 0;
                            setState(ServerState.READY);
                            launchHealthCheck(gen);
                        }
                    }

                    @Override
                    public void onTimeout() {
                        synchronized (ThumbnailServerManager.this) {
                            FLog.w(TAG, "Readiness timeout for gen %d", gen);
                            handleFailure(gen);
                        }
                    }

                    @Override
                    public void onError(Exception e) {
                        FLog.e(TAG, "Readiness checker error for gen %d", e);
                        synchronized (ThumbnailServerManager.this) {
                            handleFailure(gen);
                        }
                    }
                })
                .build();
        readinessChecker.start();
    }

    private void launchProcessMonitor(int gen, Process process) {
        Thread t = new Thread(() -> {
            int exitCode = -1;
            try {
                exitCode = process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            synchronized (ThumbnailServerManager.this) {
                if (intentionalStop || generation != gen) {
                    return;
                }
            }
            FLog.w(TAG, "rclone process (gen %d) exited unexpectedly with code %d", gen, exitCode);
            drainStderr(process, gen);
            synchronized (ThumbnailServerManager.this) {
                if (!intentionalStop && generation == gen) {
                    handleFailure(gen);
                }
            }
        }, "ThumbnailServer-Monitor-" + gen);
        t.setDaemon(true);
        t.start();
    }

    private void drainStderr(Process process, int gen) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream()))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            if (sb.length() > 0) {
                FLog.e(TAG, "rclone stderr (gen %d):\n%s", gen, sb.toString().trim());
            }
        } catch (IOException e) {
            FLog.d(TAG, "drainStderr: could not read stderr for gen %d: %s", gen, e.getMessage());
        }
    }

    private void launchHealthCheck(int gen) {
        // Called from synchronized context; captures immutable params for the thread.
        final String probeUrl = buildProbeUrl();
        final OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(HEALTH_REQUEST_TIMEOUT_S, TimeUnit.SECONDS)
                .readTimeout(HEALTH_REQUEST_TIMEOUT_S, TimeUnit.SECONDS)
                .build();
        final Request request = new Request.Builder().url(probeUrl).head().build();

        Thread t = new Thread(() -> {
            int consecutiveFailures = 0;
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(HEALTH_CHECK_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                synchronized (ThumbnailServerManager.this) {
                    if (intentionalStop || generation != gen) return;
                }
                boolean healthy = false;
                try {
                    Response response = client.newCall(request).execute();
                    healthy = response.code() == 200;
                    response.close();
                } catch (IOException e) {
                    FLog.v(TAG, "Health check request failed: %s", e.getMessage());
                }
                if (healthy) {
                    consecutiveFailures = 0;
                } else {
                    consecutiveFailures++;
                    FLog.w(TAG, "Health check failure %d/%d for gen %d",
                            consecutiveFailures, HEALTH_FAIL_THRESHOLD, gen);
                    if (consecutiveFailures >= HEALTH_FAIL_THRESHOLD) {
                        synchronized (ThumbnailServerManager.this) {
                            if (!intentionalStop && generation == gen) {
                                destroyProcess();
                                handleFailure(gen);
                            }
                        }
                        return;
                    }
                }
            }
        }, "ThumbnailServer-Health-" + gen);
        t.setDaemon(true);
        t.start();
    }

    // endregion

    // region — Helpers (may be called from any synchronized context)

    private String buildProbeUrl() {
        return "http://127.0.0.1:" + currentPort
                + "/" + currentAuth
                + "/" + currentRemote.getName() + "/";
    }

    private void cancelReadinessChecker() {
        if (readinessChecker != null) {
            readinessChecker.cancel();
            readinessChecker = null;
        }
    }

    private void destroyProcess() {
        if (serverProcess != null) {
            serverProcess.destroy();
            serverProcess = null;
        }
    }

    private void setState(ServerState newState) {
        FLog.d(TAG, "State -> %s", newState);
        stateLiveData.postValue(newState);
    }

    // endregion
}
