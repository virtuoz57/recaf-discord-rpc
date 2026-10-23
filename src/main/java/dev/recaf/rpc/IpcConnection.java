package dev.recaf.rpc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class IpcConnection implements AutoCloseable {
    static final int MAX_FRAME = 1024 * 1024;
    private final IpcTransport transport;
    private final Consumer<String> errors;
    private final Object writeLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final long openedAt = System.nanoTime();
    private volatile boolean ready;
    private volatile String pendingNonce;
    private volatile long pendingSince;
    private volatile long pingSince;
    private volatile boolean waitingForPong;

    IpcConnection(IpcTransport transport, String applicationId, Consumer<String> errors) throws IOException {
        this.transport = transport;
        this.errors = errors;
        JsonObject handshake = new JsonObject();
        handshake.addProperty("v", 1);
        handshake.addProperty("client_id", applicationId);
        try { write(0, handshake.toString().getBytes(StandardCharsets.UTF_8)); }
        catch (IOException ex) { close(); throw ex; }
        Thread.ofPlatform().daemon().name("recaf-discord-rpc-reader").start(this::readLoop);
    }

    boolean isClosed() { return closed.get(); }
    boolean isReady() { return ready && !isClosed(); }
    boolean hasPendingCommand() { return pendingNonce != null; }

    boolean timedOut() {
        long now = System.nanoTime();
        return (!ready && now - openedAt > 10_000_000_000L)
                || (pendingNonce != null && now - pendingSince > 10_000_000_000L)
                || (waitingForPong && now - pingSince > 10_000_000_000L);
    }

    void heartbeat() throws IOException {
        long now = System.nanoTime();
        if (!isReady() || waitingForPong || now - pingSince < 15_000_000_000L) return;
        pingSince = now;
        waitingForPong = true;
        write(3, "{}".getBytes(StandardCharsets.UTF_8));
    }

    void setActivity(JsonObject activity) throws IOException {
        if (!isReady() || hasPendingCommand()) return;
        String nonce = UUID.randomUUID().toString();
        pendingSince = System.nanoTime();
        pendingNonce = nonce;
        write(1, Activity.command(activity, nonce).toString().getBytes(StandardCharsets.UTF_8));
    }

    private void readLoop() {
        try {
            while (!isClosed()) {
                Frame frame = readFrame(transport);
                switch (frame.opcode()) {
                    case 1 -> {
                        JsonObject message = JsonParser.parseString(new String(frame.payload(), StandardCharsets.UTF_8)).getAsJsonObject();
                        String event = string(message, "evt");
                        if ("ERROR".equals(event)) {
                            errors.accept("Discord RPC error: " + message.get("data"));
                            return;
                        }
                        if ("READY".equals(event) && "DISPATCH".equals(string(message, "cmd"))) ready = true;
                        if ("SET_ACTIVITY".equals(string(message, "cmd")) && pendingNonce != null
                                && pendingNonce.equals(string(message, "nonce"))) pendingNonce = null;
                    }
                    case 2 -> { return; }
                    case 3 -> write(4, frame.payload());
                    case 4 -> waitingForPong = false;
                    default -> throw new IOException("Unexpected IPC opcode: " + frame.opcode());
                }
            }
        } catch (IOException | RuntimeException ex) {
            if (!isClosed() && !(ex instanceof EOFException)) errors.accept("Discord IPC: " + ex.getMessage());
        } finally { close(); }
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }

    private void write(int opcode, byte[] payload) throws IOException {
        byte[] frame = ByteBuffer.allocate(8 + payload.length).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(opcode).putInt(payload.length).put(payload).array();
        synchronized (writeLock) {
            if (isClosed()) throw new IOException("IPC connection is closed");
            transport.write(frame);
        }
    }

    static Frame readFrame(IpcTransport transport) throws IOException {
        byte[] header = new byte[8];
        readFully(transport, header);
        ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int opcode = buffer.getInt();
        int size = buffer.getInt();
        if (size < 0 || size > MAX_FRAME) throw new IOException("Invalid IPC payload length: " + size);
        byte[] payload = new byte[size];
        readFully(transport, payload);
        return new Frame(opcode, payload);
    }

    private static void readFully(IpcTransport transport, byte[] bytes) throws IOException {
        int offset = 0;
        while (offset < bytes.length) {
            int count = transport.read(bytes, offset, bytes.length - offset);
            if (count < 0) throw new EOFException("Discord IPC closed mid-frame");
            if (count == 0) throw new IOException("IPC returned an empty read");
            offset += count;
        }
    }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            ready = false;
            try { transport.close(); } catch (IOException ignored) { }
        }
    }

    record Frame(int opcode, byte[] payload) {}
}
