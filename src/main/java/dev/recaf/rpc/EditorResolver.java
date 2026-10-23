package dev.recaf.rpc;

import javafx.scene.Node;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.FileInfo;
import software.coley.recaf.info.member.ClassMember;
import software.coley.recaf.path.PathNode;
import software.coley.recaf.services.navigation.Navigable;
import software.coley.recaf.ui.pane.editing.FilePane;
import software.coley.recaf.ui.pane.editing.assembler.AssemblerPane;
import software.coley.recaf.ui.pane.editing.android.AndroidClassPane;
import software.coley.recaf.ui.pane.editing.jvm.JvmClassPane;

final class EditorResolver {
    private EditorResolver() {}

    static EditorContext resolve(Node node) {
        EditorContext specific = specific(node);
        if (specific != null) return specific;
        if (node instanceof Navigable navigable) {
            EditorContext context = fromPath(null, navigable.getPath());
            if (context.className() != null) return fromPath("Viewing class", navigable.getPath());
            if (context.fileName() != null) return fromPath("Viewing resource", navigable.getPath());
        }
        return null;
    }

    static EditorContext specific(Node node) {
        if (node instanceof AssemblerPane assembler) return fromPath("Assembler", assembler.getPath());
        if (node instanceof JvmClassPane pane) {
            String action = pane.getEditorType() == null ? "Viewing class" : switch (pane.getEditorType()) {
                case DECOMPILE -> "Decompiling";
                case LOW_LEVEL -> "Class structure";
                case HEX -> "Hex editor";
            };
            return fromPath(action, pane.getPath());
        }
        if (node instanceof AndroidClassPane pane) return fromPath("Decompiling Android", pane.getPath());
        if (node instanceof FilePane pane) {
            String action = pane.getMode() == null ? "Viewing resource" : switch (pane.getMode()) {
                case HEX -> "Hex editor";
                case TEXT -> "Editing text";
                case TEXT_BINARY_XML -> "Binary XML";
                case IMAGE -> "Viewing image";
                case AUDIO -> "Audio viewer";
                case VIDEO -> "Video viewer";
                case EXECUTABLE_PE, EXECUTABLE_ELF -> "Executable structure";
                case ARSC -> "Android resources";
            };
            return fromPath(action, pane.getPath());
        }
        String tool = toolAction(node.getClass());
        if (tool != null) {
            for (Node current = node; current != null; current = current.getParent())
                if (current instanceof Navigable navigable && navigable.getPath() != null)
                    return fromPath(tool, navigable.getPath());
            return EditorContext.action(tool);
        }
        return null;
    }

    static EditorContext fromPath(String action, PathNode<?> path) {
        if (path == null) return EditorContext.action(action);
        ClassInfo cls = path.getValueOfType(ClassInfo.class);
        ClassMember member = path.getValueOfType(ClassMember.class);
        FileInfo file = path.getValueOfType(FileInfo.class);
        return new EditorContext(action, cls == null ? null : cls.getName(),
                member == null ? null : member.getName() + (member.isMethod() ? "()" : ""),
                file == null ? null : file.getName());
    }

    private static String toolAction(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (!current.getName().startsWith("software.coley.recaf.ui.")) continue;
            String action = switch (current.getSimpleName()) {
                case "AbstractSearchPane", "SearchResultsPane" -> "Searching workspace";
                case "MappingGeneratorPane" -> "Generating mappings";
                case "MappingApplicationPane" -> "Applying mappings";
                case "MappingProgressPane" -> "Mapping preview";
                case "ScriptManagerPane" -> "Managing scripts";
                case "ScriptEditor", "ScriptEditorPane" -> "Editing script";
                case "FieldsAndMethodsPane" -> "Browsing members";
                case "InheritancePane" -> "Inspecting inheritance";
                case "MethodCallGraphPane", "MethodCallGraphsPane" -> "Inspecting call graph";
                case "CommentEditPane", "CommentListPane" -> "Editing comments";
                case "WorkspaceInformationPane" -> "Workspace overview";
                case "WorkspaceExplorerPane" -> "Browsing workspace";
                default -> null;
            };
            if (action != null) return action;
        }
        return null;
    }
}
