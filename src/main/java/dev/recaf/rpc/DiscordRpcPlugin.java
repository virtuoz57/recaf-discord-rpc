package dev.recaf.rpc;

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.plugin.Plugin;
import software.coley.recaf.plugin.PluginInformation;
import software.coley.recaf.services.file.RecafDirectoriesConfig;
import software.coley.recaf.services.workspace.WorkspaceCloseListener;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.services.workspace.WorkspaceOpenListener;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResource;
import software.coley.recaf.ui.docking.DockingManager;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

@Dependent
@PluginInformation(id = "dev.recaf.discord-rpc", name = "Discord Rich Presence", version = "1.1.0",
        description = "Current class, assembler, editor activity and session timer for Recaf 4")
public class DiscordRpcPlugin implements Plugin {
    private static final Logger LOG = Logging.get(DiscordRpcPlugin.class);
    private final WorkspaceManager workspaceManager;
    private final RecafDirectoriesConfig directories;
    private final Instance<DockingManager> docking;
    private final AtomicReference<String> workspace = new AtomicReference<>();
    private final AtomicReference<EditorContext> editor = new AtomicReference<>();
    private final WorkspaceOpenListener opened = this::workspaceOpened;
    private final WorkspaceCloseListener closed = ignored -> { workspace.set(null); editor.set(null); };
    private PresenceService service;
    private EditorTracker tracker;

    @Inject
    public DiscordRpcPlugin(WorkspaceManager workspaceManager, RecafDirectoriesConfig directories,
                            Instance<DockingManager> docking) {
        this.workspaceManager = workspaceManager;
        this.directories = directories;
        this.docking = docking;
    }

    @Override public synchronized void onEnable() {
        if (service != null) return;
        Path path = directories.getConfigDirectory().resolve("discord-rpc.properties");
        try {
            Files.createDirectories(path.getParent());
            if (Files.notExists(path)) {
                try (InputStream defaults = DiscordRpcPlugin.class.getResourceAsStream("/discord-rpc.properties")) {
                    if (defaults == null) throw new IOException("Missing default configuration resource");
                    Files.copy(defaults, path);
                }
            }
        } catch (IOException ex) {
            LOG.error("Could not initialize Discord RPC config: {}", path, ex);
            return;
        }
        workspaceManager.addWorkspaceOpenListener(opened);
        workspaceManager.addWorkspaceCloseListener(closed);
        if (workspaceManager.hasCurrentWorkspace()) workspaceOpened(workspaceManager.getCurrent());
        else workspace.set(null);
        tracker = new EditorTracker(() -> docking.isResolvable() ? docking.get().getBento() : null, editor);
        service = new PresenceService(path, workspace, editor, tracker);
        service.start();
        LOG.info("Discord RPC enabled. Configuration: {}", path);
    }

    private void workspaceOpened(Workspace current) {
        editor.set(null);
        if (current.getPrimaryResource() instanceof WorkspaceFileResource resource)
            workspace.set(Activity.basename(resource.getFileInfo().getName()));
        else workspace.set("Workspace");
    }

    @Override public synchronized void onDisable() {
        workspaceManager.removeWorkspaceOpenListener(opened);
        workspaceManager.removeWorkspaceCloseListener(closed);
        if (service != null) { service.close(); service = null; }
        if (tracker != null) { tracker.close(); tracker = null; }
        workspace.set(null);
        LOG.info("Discord RPC disabled");
    }
}
