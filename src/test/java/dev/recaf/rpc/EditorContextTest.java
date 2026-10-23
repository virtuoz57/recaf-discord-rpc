package dev.recaf.rpc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.info.member.BasicMethodMember;
import software.coley.recaf.info.member.BasicFieldMember;
import software.coley.recaf.path.ClassPathNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EditorContextTest {
    @TempDir Path temp;

    static ClassPathNode classPath(String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitEnd();
        return new ClassPathNode(new JvmClassInfoBuilder(writer.toByteArray()).build());
    }

    RpcConfig config(String text) throws Exception {
        Path path = temp.resolve("rpc.properties");
        Files.writeString(path, text);
        return RpcConfig.load(path);
    }

    @Test void assemblerReportsTheMethodFromRecafMemberPath() throws Exception {
        var path = classPath("com/example/Main").child(new BasicMethodMember("main", "([Ljava/lang/String;)V", null, 9, List.of()));
        var context = EditorResolver.fromPath("Assembler", path);
        var activity = Activity.create(config(""), "app.jar", 1, context);
        assertEquals("Assembler · Main.main()", activity.get("details").getAsString());
        assertEquals("Workspace: app.jar", activity.get("state").getAsString());
        assertEquals("Assembler · com.example.Main.main()", context.details(config("showFullClassName=true")));
    }

    @Test void fieldPathDoesNotInventMethodParentheses() throws Exception {
        var path = classPath("app/Flags").child(new BasicFieldMember("enabled", "Z", null, 1, null));
        assertEquals("Assembler · Flags.enabled", EditorResolver.fromPath("Assembler", path).details(config("")));
    }

    @Test void privacySwitchesIndependentlyHideNamesAndActivity() throws Exception {
        var context = new EditorContext("Assembler", "private/package/Secret", "secret()", null);
        assertEquals("Assembler", context.details(config("showClassName=false")));
        assertEquals("Assembler · Secret", context.details(config("showMemberName=false")));
        assertEquals("Custom status", context.details(config("showActivity=false\ndetails=Custom status")));
        var resource = new EditorContext("Editing text", null, null, "C:\\secret\\config.yml");
        assertEquals("Editing text · config.yml", resource.details(config("")));
        assertEquals("Editing text", resource.details(config("showWorkspaceName=false")));
    }

    @Test void oldConfigurationEnablesDetailedActivityWithoutMigration() throws Exception {
        var config = config("applicationId=1352044516411965551\nenabled=true");
        assertTrue(config.showActivity());
        assertTrue(config.showClassName());
        assertTrue(config.showMemberName());
        assertFalse(config.showFullClassName());
    }

    @Test void longClassAndMemberStillFitDiscordUtf8Limit() throws Exception {
        var context = new EditorContext("Assembler", "app/" + "Ж".repeat(100), "run()", null);
        var activity = Activity.create(config(""), "app.jar", 0, context);
        assertTrue(activity.get("details").getAsString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 128);
    }
}
