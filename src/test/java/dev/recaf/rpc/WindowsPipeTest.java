package dev.recaf.rpc;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class WindowsPipeTest {
    @Test @EnabledOnOs(OS.WINDOWS) @Timeout(20)
    void realWindowsNamedPipeRoundTripAndDisconnect() throws Exception {
        String name = "recaf-rpc-test-" + UUID.randomUUID();
        String script = """
                $ErrorActionPreference = 'Stop'
                $pipe = [System.IO.Pipes.NamedPipeServerStream]::new('%s', [System.IO.Pipes.PipeDirection]::InOut)
                $reader = [System.IO.BinaryReader]::new($pipe)
                $writer = [System.IO.BinaryWriter]::new($pipe)
                function Read-Frame {
                    $op = $reader.ReadInt32()
                    $length = $reader.ReadInt32()
                    $bytes = $reader.ReadBytes($length)
                    if ($bytes.Length -ne $length) { throw 'Short frame' }
                    return @{Op=$op; Data=[System.Text.Encoding]::UTF8.GetString($bytes)}
                }
                function Send-Frame($op, $text) {
                    $bytes = [System.Text.Encoding]::UTF8.GetBytes($text)
                    $writer.Write([int]$op)
                    $writer.Write([int]$bytes.Length)
                    $writer.Write($bytes)
                    $writer.Flush()
                }
                Write-Output 'LISTENING'
                $pipe.WaitForConnection()
                $h = Read-Frame
                if ($h.Op -ne 0 -or ($h.Data | ConvertFrom-Json).client_id -ne '1352044516411965551') { throw 'Bad handshake' }
                Send-Frame 1 '{"cmd":"DISPATCH","evt":"READY"}'
                $a = Read-Frame
                $command = $a.Data | ConvertFrom-Json
                if ($command.args.activity.state -ne 'Editing test.jar') { throw 'Bad activity' }
                Send-Frame 1 ('{"cmd":"SET_ACTIVITY","nonce":"' + $command.nonce + '"}')
                Send-Frame 3 '{"test":true}'
                $pong = Read-Frame
                if ($pong.Op -ne 4 -or $pong.Data -ne '{"test":true}') { throw 'Bad pong' }
                Write-Output 'ROUNDTRIP_OK'
                # Leave the client's reader blocked while the client closes its handle.
                if ($pipe.ReadByte() -ne -1) { throw 'Expected disconnect' }
                $pipe.Dispose()
                Write-Output 'CLOSED_OK'
                """.formatted(name);
        Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script)
                .redirectErrorStream(true).start();
        try {
            BufferedReader output = new BufferedReader(new InputStreamReader(process.getInputStream()));
            assertEquals("LISTENING", output.readLine());
            var errors = new java.util.concurrent.CopyOnWriteArrayList<String>();
            var ipc = new IpcConnection(new IpcTransport.WindowsPipe("\\\\.\\pipe\\" + name), "1352044516411965551", errors::add);
            try {
                IpcConnectionTest.await(ipc::isReady);
                var activity = new JsonObject();
                activity.addProperty("state", "Editing test.jar");
                ipc.setActivity(activity);
                assertEquals("ROUNDTRIP_OK", output.readLine());
                assertTrue(errors.isEmpty(), errors.toString());
            } finally { ipc.close(); }
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Server did not observe disconnect");
            assertEquals("CLOSED_OK", output.readLine());
            assertEquals(0, process.exitValue());
        } finally { process.destroyForcibly(); }
    }
}
