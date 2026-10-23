package dev.recaf.rpc;

import com.google.gson.JsonObject;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class PresenceService implements AutoCloseable {
    private static final Logger LOG = Logging.get(PresenceService.class);
    private final Path configPath;
    private final AtomicReference<String> workspace;
    private final AtomicReference<EditorContext> editor;
    private final EditorTracker tracker;
    private final long startedAt = Instant.now().getEpochSecond();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("recaf-discord-rpc-worker").factory());
    private volatile IpcConnection connection;
    private volatile boolean stopped;
    private RpcConfig config;
    private JsonObject lastActivity;
    private long nextConfigRead, nextConnect, lastSent;
    private long lastWarning;
    private String configError;
    private boolean connectionLogged;

    PresenceService(Path configPath, AtomicReference<String> workspace,
                    AtomicReference<EditorContext> editor, EditorTracker tracker) {
        this.configPath = configPath;
        this.workspace = workspace;
        this.editor = editor;
        this.tracker = tracker;
    }

    void start() { executor.scheduleWithFixedDelay(this::tick, 0, 1, TimeUnit.SECONDS); }

    private void tick() {
        if (stopped) return;
        try {
            long now = System.nanoTime();
            if (now >= nextConfigRead) {
                nextConfigRead = now + TimeUnit.SECONDS.toNanos(3);
                reload();
            }
            if (config == null || !config.enabled()) { disconnect(); return; }
            if (config.showActivity()) tracker.requestSample();
            IpcConnection ipc = connection;
            if (ipc != null && (ipc.isClosed() || ipc.timedOut())) { disconnect(); ipc = null; }
            if (ipc == null) {
                if (now < nextConnect) return;
                nextConnect = now + TimeUnit.SECONDS.toNanos(10);
                ipc = new IpcConnection(IpcTransport.connect(), config.applicationId(), this::warn);
                connection = ipc;
                // close() may have raced with the connect attempt.
                if (stopped) { disconnect(); return; }
                lastActivity = null;
                connectionLogged = false;
            }
            if (!ipc.isReady()) return;
            ipc.heartbeat();
            if (ipc.hasPendingCommand()) return;
            if (!connectionLogged) {
                LOG.info("Discord RPC connected (application {})", config.applicationId());
                connectionLogged = true;
            }
            JsonObject activity = Activity.create(config, workspace.get(), startedAt, editor.get());
            if (!activity.equals(lastActivity)
                    && (lastActivity == null || now - lastSent >= TimeUnit.SECONDS.toNanos(config.updateIntervalSeconds()))) {
                ipc.setActivity(activity);
                lastActivity = activity;
                lastSent = now;
            }
        } catch (IOException | RuntimeException ex) {
            warn(ex.getMessage());
            disconnect();
        }
    }

    private void reload() {
        try {
            RpcConfig updated = RpcConfig.load(configPath);
            if (config != null && !config.applicationId().equals(updated.applicationId())) {
                disconnect();
                nextConnect = 0;
            }
            config = updated;
            configError = null;
        } catch (IOException | RuntimeException ex) {
            String error = ex.getMessage();
            if (!java.util.Objects.equals(error, configError)) {
                LOG.warn("Cannot reload {}: {}. Keeping the previous configuration.", configPath, error);
                configError = error;
            }
        }
    }

    private synchronized void warn(String message) {
        long now = System.nanoTime();
        if (lastWarning == 0 || now - lastWarning >= TimeUnit.SECONDS.toNanos(60)) {
            LOG.warn("Discord RPC: {}. Retrying automatically.", message);
            lastWarning = now;
        }
    }

    private void disconnect() {
        IpcConnection ipc = connection;
        connection = null;
        if (ipc != null) ipc.close();
        lastActivity = null;
    }

    @Override public void close() {
        stopped = true;
        executor.shutdownNow();
        // Discord removes this process's activity when its IPC connection closes.
        disconnect();
    }
}
