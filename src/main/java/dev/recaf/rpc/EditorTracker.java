package dev.recaf.rpc;

import javafx.scene.Node;
import javafx.stage.Window;
import software.coley.bentofx.Bento;
import software.coley.bentofx.dockable.Dockable;
import software.coley.bentofx.dockable.DockableCloseListener;
import software.coley.bentofx.dockable.DockableSelectListener;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.ui.docking.DockingManager;
import software.coley.recaf.util.FxThreadUtil;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

final class EditorTracker implements AutoCloseable {
    private final Supplier<Bento> bentoProvider;
    private final AtomicReference<EditorContext> snapshot;
    private final AtomicBoolean queued = new AtomicBoolean();
    private volatile boolean stopped;
    // FX thread only.
    private Bento bento;
    private Dockable selected;
    private Node lastEditorFocus;
    private final DockableSelectListener selection = (path, dockable) -> {
        selected = dockable;
        lastEditorFocus = null;
        sample();
    };
    private final DockableCloseListener closure = (path, dockable) -> onClose(dockable);

    private void onClose(Dockable dockable) {
        if (selected == dockable) {
            selected = null;
            lastEditorFocus = null;
            snapshot.set(null);
        }
    }

    EditorTracker(Supplier<Bento> bentoProvider, AtomicReference<EditorContext> snapshot) {
        this.bentoProvider = bentoProvider;
        this.snapshot = snapshot;
    }

    void requestSample() {
        if (stopped || !queued.compareAndSet(false, true)) return;
        FxThreadUtil.run(() -> {
            try {
                if (stopped) return;
                if (bento == null) {
                    bento = bentoProvider.get();
                    if (bento == null) return;
                    bento.events().addDockableSelectListener(selection);
                    bento.events().addDockableCloseListener(closure);
                }
                sample();
            } catch (RuntimeException | LinkageError ex) {
                snapshot.set(null);
                Logging.get(EditorTracker.class).warn("Cannot inspect Recaf editor: {}", ex.toString());
                stopOnFxThread();
            } finally { queued.set(false); }
        });
    }

    void sample() {
        if (stopped || bento == null) return;
        var dockables = bento.search().allDockables();
        if (selected == null || dockables.stream().noneMatch(path -> path.dockable() == selected)) {
            var primary = bento.search().container(DockingManager.ID_CONTAINER_WORKSPACE_PRIMARY);
            selected = primary != null && primary.tailContainer() instanceof software.coley.bentofx.layout.container.DockContainerLeaf leaf
                    ? leaf.getSelectedDockable() : null;
            lastEditorFocus = null;
        }
        Node content = selected == null ? null : selected.getNode();
        for (Window window : Window.getWindows()) {
            if (!window.isFocused() || window.getScene() == null) continue;
            Node focus = window.getScene().getFocusOwner();
            if (content != null && !inside(focus, content) && content.getScene() == window.getScene()) break;
            EditorContext context = resolveAncestors(focus);
            if (context != null) {
                if (inside(focus, content)) lastEditorFocus = focus;
                snapshot.set(context);
                return;
            }
        }
        // Preserve the side-panel editor when its tab header takes focus.
        if (visibleInside(lastEditorFocus, content)) {
            EditorContext context = resolveAncestors(lastEditorFocus);
            if (context != null) { snapshot.set(context); return; }
        }
        snapshot.set(content == null ? null : EditorResolver.resolve(content));
    }

    private static EditorContext resolveAncestors(Node node) {
        EditorContext generic = null;
        for (Node current = node; current != null; current = current.getParent()) {
            EditorContext context = EditorResolver.specific(current);
            if (context != null) return context;
            if (generic == null) generic = EditorResolver.resolve(current);
        }
        return generic;
    }

    private static boolean inside(Node node, Node parent) {
        if (parent == null) return false;
        for (Node current = node; current != null; current = current.getParent()) if (current == parent) return true;
        return false;
    }

    private static boolean visibleInside(Node node, Node parent) {
        if (!inside(node, parent)) return false;
        for (Node current = node; current != null; current = current.getParent()) {
            if (!current.isVisible()) return false;
            if (current == parent) return true;
        }
        return false;
    }

    private void stopOnFxThread() {
        stopped = true;
        if (bento != null) {
            bento.events().removeDockableSelectListener(selection);
            bento.events().removeDockableCloseListener(closure);
        }
        selected = null;
        lastEditorFocus = null;
        bento = null;
        snapshot.set(null);
    }

    @Override public void close() {
        stopped = true;
        snapshot.set(null);
        FxThreadUtil.run(this::stopOnFxThread);
    }
}
