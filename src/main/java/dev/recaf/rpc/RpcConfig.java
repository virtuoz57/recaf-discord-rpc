package dev.recaf.rpc;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

record RpcConfig(String applicationId, boolean enabled, String details, String idleState,
                 String workspaceState, boolean showWorkspaceName, boolean showElapsedTime,
                 String largeImage, String largeImageText, int updateIntervalSeconds,
                 boolean showActivity, boolean showClassName, boolean showMemberName, boolean showFullClassName) {
    static RpcConfig load(Path path) throws IOException {
        Properties p = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { p.load(reader); }
        String id = p.getProperty("applicationId", "1352044516411965551").trim();
        if (!id.matches("[0-9]{17,20}"))
            throw new IllegalArgumentException("applicationId must be a 17-20 digit Discord application ID");
        int interval = Integer.parseInt(p.getProperty("updateIntervalSeconds", "15").trim());
        if (interval < 15 || interval > 300)
            throw new IllegalArgumentException("updateIntervalSeconds must be between 15 and 300");
        return new RpcConfig(id, bool(p, "enabled", true), text(p, "details", "Working with Java bytecode"),
                text(p, "idleState", "No workspace open"), text(p, "workspaceState", "Editing workspace"),
                bool(p, "showWorkspaceName", true), bool(p, "showElapsedTime", true),
                text(p, "largeImage", ""), text(p, "largeImageText", "Recaf 4"), interval,
                bool(p, "showActivity", true), bool(p, "showClassName", true),
                bool(p, "showMemberName", true), bool(p, "showFullClassName", false));
    }

    private static String text(Properties p, String key, String fallback) {
        String value = p.getProperty(key, fallback).trim();
        int count = Math.min(value.codePointCount(0, value.length()), 128);
        return value.substring(0, value.offsetByCodePoints(0, count));
    }

    private static boolean bool(Properties p, String key, boolean fallback) {
        String value = p.getProperty(key, Boolean.toString(fallback)).trim();
        if (value.equalsIgnoreCase("true")) return true;
        if (value.equalsIgnoreCase("false")) return false;
        throw new IllegalArgumentException(key + " must be true or false");
    }
}
