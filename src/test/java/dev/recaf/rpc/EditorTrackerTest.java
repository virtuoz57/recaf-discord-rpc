package dev.recaf.rpc;

import javafx.application.Platform;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import software.coley.bentofx.Bento;
import software.coley.bentofx.dockable.Dockable;
import software.coley.bentofx.layout.container.DockContainerLeaf;
import software.coley.bentofx.layout.container.DockContainerRootBranch;
import software.coley.recaf.path.PathNode;
import software.coley.recaf.services.navigation.Navigable;
import software.coley.recaf.ui.docking.DockingManager;
import software.coley.recaf.util.FxThreadUtil;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
@EnabledOnOs(OS.WINDOWS) // Recaf's test JAR bundles Windows JavaFX natives.
class EditorTrackerTest {
    @BeforeAll static void initFx() throws Exception {
        CompletableFuture<Void> started = new CompletableFuture<>();
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            FxThreadUtil.onInitialize();
            started.complete(null);
        });
        started.get(10, TimeUnit.SECONDS);
    }

    static void fx(Runnable action) throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();
        Platform.runLater(() -> {
            try { action.run(); done.complete(null); }
            catch (Throwable ex) { done.completeExceptionally(ex); }
        });
        done.get(10, TimeUnit.SECONDS);
    }

    static class ClassView extends StackPane implements Navigable {
        PathNode<?> path;
        ClassView(String name) { path = EditorContextTest.classPath(name); }
        public PathNode<?> getPath() { return path; }
        public Collection<Navigable> getNavigableChildren() { return List.of(); }
        public void disable() {}
    }

    @Test void followsExistingTabSelectionRenameReplacementAndCloseWithoutLeakingListeners() throws Exception {
        fx(() -> {
            Bento bento = new Bento();
            var root = new DockContainerRootBranch(bento, "test-root");
            var leaf = new DockContainerLeaf(bento, DockingManager.ID_CONTAINER_WORKSPACE_PRIMARY);
            root.addContainer(leaf);
            bento.registerRoot(root);
            Dockable first = new Dockable(bento, "first");
            var view = new ClassView("app/First");
            first.setNode(view);
            Dockable second = new Dockable(bento, "second");
            second.setNode(new ClassView("app/Second"));
            leaf.addDockable(first);
            leaf.addDockable(second);
            leaf.selectDockable(first);
            AtomicReference<EditorContext> snapshot = new AtomicReference<>();
            int selects = bento.events().getDockableSelectListener().size();
            int closes = bento.events().getDockableCloseListener().size();
            var tracker = new EditorTracker(() -> bento, snapshot);
            tracker.requestSample();
            assertEquals("app/First", snapshot.get().className());
            assertEquals(selects + 1, bento.events().getDockableSelectListener().size());
            leaf.selectDockable(second);
            assertEquals("app/Second", snapshot.get().className());
            leaf.selectDockable(first);
            view.path = EditorContextTest.classPath("renamed/First");
            tracker.sample();
            assertEquals("renamed/First", snapshot.get().className());
            first.setNode(new StackPane());
            tracker.sample();
            assertNull(snapshot.get(), "Unrelated content must not keep the old class name");
            leaf.selectDockable(second);
            leaf.closeDockable(second);
            tracker.sample();
            assertNull(snapshot.get(), "Closing a class must remove its name");
            tracker.close();
            assertEquals(selects, bento.events().getDockableSelectListener().size());
            assertEquals(closes, bento.events().getDockableCloseListener().size());
            assertNull(snapshot.get());
            bento.unregisterRoot(root);
        });
    }

    @Test void disablingBeforeQueuedStartupDoesNotAttachAnything() throws Exception {
        AtomicReference<EditorContext> snapshot = new AtomicReference<>();
        var tracker = new EditorTracker(() -> { fail("Provider must not be called after disable"); return null; }, snapshot);
        tracker.close();
        tracker.requestSample();
        fx(() -> assertNull(snapshot.get()));
    }
}
