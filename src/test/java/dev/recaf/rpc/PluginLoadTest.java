package dev.recaf.rpc;

import org.jboss.weld.environment.se.Weld;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import software.coley.recaf.services.file.RecafDirectoriesConfig;
import software.coley.recaf.services.plugin.BasicPluginManager;
import software.coley.recaf.services.plugin.PluginManager;
import software.coley.recaf.services.plugin.PluginManagerConfig;
import software.coley.recaf.services.plugin.discovery.DirectoryPluginDiscoverer;
import software.coley.recaf.services.workspace.BasicWorkspaceManager;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.services.workspace.WorkspaceManagerConfig;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.resource.WorkspaceResourceBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class PluginLoadTest {
    @Test @Timeout(30)
    void realRecafLoaderInjectsEnablesAndUnloadsPackagedJar() throws Exception {
        Path root = Path.of(System.getProperty("RECAF_DIR"));
        Files.createDirectories(root.resolve("config"));
        // Do not publish activity during tests.
        Files.writeString(root.resolve("config/discord-rpc.properties"), "enabled=false\n");
        Path plugins = Files.createDirectories(root.resolve("load-test"));
        Files.copy(Path.of(System.getProperty("pluginJar")), plugins.resolve("rpc.jar"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        try (var container = new Weld().disableDiscovery().addBeanClasses(BasicPluginManager.class,
                PluginManagerConfig.class, BasicWorkspaceManager.class, WorkspaceManagerConfig.class,
                RecafDirectoriesConfig.class).initialize()) {
            var manager = container.select(PluginManager.class).get();
            var workspaces = container.select(WorkspaceManager.class).get();
            int opens = workspaces.getWorkspaceOpenListeners().size();
            int closes = workspaces.getWorkspaceCloseListeners().size();
            manager.loadPlugins(new DirectoryPluginDiscoverer(plugins));
            assertTrue(manager.isPluginLoaded("dev.recaf.discord-rpc"));
            var plugin = manager.getPlugin("dev.recaf.discord-rpc").plugin();
            assertNotSame(DiscordRpcPlugin.class.getClassLoader(), plugin.getClass().getClassLoader());
            assertEquals(opens + 1, workspaces.getWorkspaceOpenListeners().size());
            assertEquals(closes + 1, workspaces.getWorkspaceCloseListeners().size());
            workspaces.setCurrent(new BasicWorkspace(new WorkspaceResourceBuilder().build()));
            workspaces.closeCurrent();
            manager.unloaderFor("dev.recaf.discord-rpc").commit();
            assertFalse(manager.isPluginLoaded("dev.recaf.discord-rpc"));
            assertEquals(opens, workspaces.getWorkspaceOpenListeners().size());
            assertEquals(closes, workspaces.getWorkspaceCloseListeners().size());
        }
    }
}
