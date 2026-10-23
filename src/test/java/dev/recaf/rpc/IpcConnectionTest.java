package dev.recaf.rpc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class IpcConnectionTest {
    static byte[] frame(int opcode, String json) {
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(8 + data.length).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(opcode).putInt(data.length).put(data).array();
    }

    static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "Timed out waiting for IPC event");
    }

    static final class FakeTransport implements IpcTransport {
        final PipedInputStream input = new PipedInputStream(4096);
        final PipedOutputStream server = new PipedOutputStream(input);
        final BlockingQueue<byte[]> sent = new LinkedBlockingQueue<>();
        FakeTransport() throws IOException {}
        public int read(byte[] b, int off, int len) throws IOException { return input.read(b, off, Math.min(len, 3)); }
        public void write(byte[] bytes) { sent.add(bytes); }
        public void close() throws IOException { server.close(); input.close(); }
        void event(int op, String json) throws IOException { server.write(frame(op, json)); server.flush(); }
        IpcConnection.Frame outgoing() throws Exception {
            byte[] bytes = sent.poll(3, TimeUnit.SECONDS);
            assertNotNull(bytes);
            return IpcConnection.readFrame(bytesTransport(bytes));
        }
    }

    static IpcTransport bytesTransport(byte[] bytes) {
        return new IpcTransport() {
            final ByteArrayInputStream input = new ByteArrayInputStream(bytes);
            public int read(byte[] b, int off, int len) { return input.read(b, off, Math.min(len, 2)); }
            public void write(byte[] b) {}
            public void close() {}
        };
    }

    static JsonObject json(IpcConnection.Frame frame) {
        return JsonParser.parseString(new String(frame.payload(), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test void handshakeReadyActivityAcknowledgementAndPing() throws Exception {
        FakeTransport transport = new FakeTransport();
        var errors = new CopyOnWriteArrayList<String>();
        try (var ipc = new IpcConnection(transport, "1352044516411965551", errors::add)) {
            var handshake = transport.outgoing();
            assertEquals(0, handshake.opcode());
            assertEquals("1352044516411965551", json(handshake).get("client_id").getAsString());
            ipc.setActivity(new JsonObject());
            assertTrue(transport.sent.isEmpty(), "No activity may be sent before READY");
            transport.event(1, "{\"cmd\":\"DISPATCH\",\"evt\":\"READY\"}");
            await(ipc::isReady);
            JsonObject activity = new JsonObject();
            activity.addProperty("state", "Editing test.jar");
            ipc.setActivity(activity);
            var command = json(transport.outgoing());
            assertEquals("Editing test.jar", command.getAsJsonObject("args").getAsJsonObject("activity").get("state").getAsString());
            assertTrue(ipc.hasPendingCommand());
            transport.event(1, "{\"cmd\":\"SET_ACTIVITY\",\"nonce\":\"wrong\"}");
            transport.event(3, "{\"ping\":1}");
            var pong = transport.outgoing();
            assertEquals(4, pong.opcode());
            assertEquals("{\"ping\":1}", new String(pong.payload(), StandardCharsets.UTF_8));
            assertTrue(ipc.hasPendingCommand(), "Unrelated replies must not acknowledge a command");
            transport.event(1, "{\"cmd\":\"SET_ACTIVITY\",\"nonce\":" + command.get("nonce") + "}");
            await(() -> !ipc.hasPendingCommand());
            transport.event(2, "{}");
            await(ipc::isClosed);
            assertTrue(errors.isEmpty(), errors.toString());
        }
    }

    @Test void discordErrorClosesSessionAndIsReported() throws Exception {
        var transport = new FakeTransport();
        var errors = new CopyOnWriteArrayList<String>();
        try (var ipc = new IpcConnection(transport, "1352044516411965551", errors::add)) {
            transport.event(1, "{\"evt\":\"ERROR\",\"data\":{\"code\":4000,\"message\":\"bad id\"}}");
            await(ipc::isClosed);
            assertTrue(errors.getFirst().contains("bad id"));
        }
    }

    @Test void heartbeatUsesPingWithoutPublishingAnotherActivity() throws Exception {
        var transport = new FakeTransport();
        try (var ipc = new IpcConnection(transport, "1352044516411965551", ignored -> {})) {
            transport.outgoing();
            transport.event(1, "{\"cmd\":\"DISPATCH\",\"evt\":\"READY\"}");
            await(ipc::isReady);
            ipc.heartbeat();
            assertEquals(3, transport.outgoing().opcode());
            ipc.heartbeat();
            assertTrue(transport.sent.isEmpty());
            transport.event(4, "{}");
            transport.event(3, "{}");
            assertEquals(4, transport.outgoing().opcode());
            assertFalse(ipc.timedOut());
        }
    }

    @Test void malformedOrTruncatedFrameIsRejected() {
        for (int size : new int[]{-1, IpcConnection.MAX_FRAME + 1}) {
            byte[] header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(1).putInt(size).array();
            assertThrows(IOException.class, () -> IpcConnection.readFrame(bytesTransport(header)));
        }
        assertThrows(EOFException.class, () -> IpcConnection.readFrame(bytesTransport(new byte[4])));
        byte[] truncated = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN).putInt(1).putInt(3).put((byte)'{').array();
        assertThrows(EOFException.class, () -> IpcConnection.readFrame(bytesTransport(truncated)));
    }
}
