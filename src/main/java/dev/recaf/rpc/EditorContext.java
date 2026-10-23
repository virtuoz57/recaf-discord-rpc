package dev.recaf.rpc;

record EditorContext(String action, String className, String memberName, String fileName) {
    static EditorContext action(String action) { return new EditorContext(action, null, null, null); }

    String details(RpcConfig config) {
        if (!config.showActivity()) return config.details();
        String target = null;
        if (className != null && config.showClassName()) {
            target = config.showFullClassName() ? className.replace('/', '.') : Activity.basename(className);
            if (memberName != null && config.showMemberName()) target += "." + memberName;
        } else if (fileName != null && config.showWorkspaceName()) {
            target = Activity.basename(fileName);
        }
        return target == null ? action : action + " · " + target;
    }
}
