package dev.recaf.rpc;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

final class Activity {
    private Activity() {}

    static JsonObject create(RpcConfig config, String workspace, long startedAt) {
        return create(config, workspace, startedAt, null);
    }

    static JsonObject create(RpcConfig config, String workspace, long startedAt, EditorContext editor) {
        JsonObject activity = new JsonObject();
        activity.addProperty("type", 0);
        putText(activity, "details", editor == null ? config.details() : editor.details(config));
        String state = workspace == null ? config.idleState() : config.workspaceState();
        if (workspace != null && config.showWorkspaceName())
            state = (editor != null && config.showActivity() ? "Workspace: " : "Editing ") + basename(workspace);
        putText(activity, "state", state);
        if (config.showElapsedTime()) {
            JsonObject timestamps = new JsonObject();
            timestamps.addProperty("start", startedAt);
            activity.add("timestamps", timestamps);
        }
        if (!config.largeImage().isBlank()) {
            JsonObject assets = new JsonObject();
            assets.addProperty("large_image", config.largeImage());
            putText(assets, "large_text", config.largeImageText());
            activity.add("assets", assets);
        }
        return activity;
    }

    static JsonObject command(JsonObject activity, String nonce) {
        JsonObject args = new JsonObject();
        args.addProperty("pid", ProcessHandle.current().pid());
        args.add("activity", activity == null ? JsonNull.INSTANCE : activity);
        JsonObject command = new JsonObject();
        command.addProperty("cmd", "SET_ACTIVITY");
        command.add("args", args);
        command.addProperty("nonce", nonce);
        return command;
    }

    static String basename(String name) {
        String normalized = name.replace('\\', '/');
        int end = normalized.length();
        while (end > 0 && normalized.charAt(end - 1) == '/') end--;
        int start = normalized.lastIndexOf('/', Math.max(0, end - 1)) + 1;
        return end > start ? normalized.substring(start, end) : "Workspace";
    }

    private static void putText(JsonObject object, String key, String text) {
        if (text.isBlank()) return;
        // Limit UTF-8 bytes without splitting a code point.
        StringBuilder result = new StringBuilder();
        int bytes = 0;
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            int length = cp <= 0x7f ? 1 : cp <= 0x7ff ? 2 : cp <= 0xffff ? 3 : 4;
            if (bytes + length > 128) break;
            result.appendCodePoint(cp);
            bytes += length;
            i += Character.charCount(cp);
        }
        if (result.length() == 1) result.append(' ');
        object.addProperty(key, result.toString());
    }
}
