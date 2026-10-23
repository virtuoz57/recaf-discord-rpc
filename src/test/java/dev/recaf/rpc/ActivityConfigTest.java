package dev.recaf.rpc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ActivityConfigTest {
    @TempDir Path temp;
    private RpcConfig config(String extra) throws Exception {
        Path path = temp.resolve("config.properties");
        Files.writeString(path, extra, StandardCharsets.UTF_8);
        return RpcConfig.load(path);
    }

    @Test void defaultsContainRequestedIdAndTimer() throws Exception {
        var c = config("");
        assertEquals("1352044516411965551", c.applicationId());
        var activity = Activity.create(c, null, 1700000000);
        assertEquals("No workspace open", activity.get("state").getAsString());
        assertEquals(1700000000L, activity.getAsJsonObject("timestamps").get("start").getAsLong());
        assertFalse(activity.has("assets"));
    }

    @Test void workspaceStripsBothKindsOfPathsAndRespectsPrivacy() throws Exception {
        var activity = Activity.create(config(""), "C:\\private\\secret\\app.jar", 0);
        assertEquals("Editing app.jar", activity.get("state").getAsString());
        assertEquals("app.jar", Activity.basename("/private/secret/app.jar"));
        var privateActivity = Activity.create(config("showWorkspaceName=false\nworkspaceState=Editing bytecode\n"), "secret.jar", 0);
        assertEquals("Editing bytecode", privateActivity.get("state").getAsString());
        assertFalse(privateActivity.toString().contains("secret.jar"));
    }

    @Test void russianTextIsUtf8BoundedWithoutSplittingEmoji() throws Exception {
        var activity = Activity.create(config("details=" + "😀".repeat(100)), null, 0);
        String details = activity.get("details").getAsString();
        assertEquals("😀".repeat(32), details);
        assertEquals(128, details.getBytes(StandardCharsets.UTF_8).length);
        assertEquals("Работа", Activity.create(config("details=Работа"), null, 0).get("details").getAsString());
    }

    @Test void canDisableTimerAndAddImage() throws Exception {
        var activity = Activity.create(config("showElapsedTime=false\nlargeImage=recaf"), null, 123);
        assertFalse(activity.has("timestamps"));
        assertEquals("recaf", activity.getAsJsonObject("assets").get("large_image").getAsString());
    }

    @Test void badConfigurationIsRejected() {
        for (String value : new String[]{"applicationId=token", "updateIntervalSeconds=1", "enabled=yes"})
            assertThrows(IllegalArgumentException.class, () -> config(value));
    }

    @Test void clearActivityHasNullPayloadAndRealPid() {
        var command = Activity.command(null, "test-nonce");
        assertTrue(command.getAsJsonObject("args").get("activity").isJsonNull());
        assertEquals(ProcessHandle.current().pid(), command.getAsJsonObject("args").get("pid").getAsLong());
    }
}
