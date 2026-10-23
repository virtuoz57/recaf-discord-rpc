package dev.recaf.rpc;

import java.io.IOException;
import java.io.FileInputStream;
import java.io.RandomAccessFile;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

interface IpcTransport extends AutoCloseable {
    int read(byte[] bytes, int offset, int length) throws IOException;
    void write(byte[] bytes) throws IOException;
    @Override void close() throws IOException;

    static IpcTransport connect() throws IOException {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Set<String> roots = new LinkedHashSet<>();
        if (windows) {
            // RandomAccessFile requires the Win32 device path instead of \\?\.
            roots.add("\\\\.\\pipe\\");
        } else {
            for (String key : new String[]{"XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP"}) {
                String value = System.getenv(key);
                if (value != null && !value.isBlank()) roots.add(value);
            }
            roots.add("/tmp");
        }
        IOException last = null;
        for (String root : roots) {
            for (int i = 0; i < 10; i++) {
                try {
                    return windows ? new WindowsPipe(root + "discord-ipc-" + i)
                            : new UnixSocket(Path.of(root, "discord-ipc-" + i));
                } catch (IOException ex) { last = ex; }
            }
        }
        throw new IOException("Discord desktop IPC is unavailable", last);
    }

    final class WindowsPipe implements IpcTransport {
        private final RandomAccessFile pipe;
        private final FileInputStream input;
        private volatile boolean closed;
        WindowsPipe(String name) throws IOException {
            pipe = new RandomAccessFile(name, "rw");
            input = new FileInputStream(pipe.getFD());
        }
        public int read(byte[] b, int off, int len) throws IOException {
            // Blocking reads on this handle prevent writes. available() uses
            // PeekNamedPipe, so read only buffered bytes.
            while (!closed) {
                int available = input.available();
                if (available > 0) return pipe.read(b, off, Math.min(len, available));
                try { Thread.sleep(20); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IOException("IPC read interrupted", ex); }
            }
            return -1;
        }
        public void write(byte[] bytes) throws IOException { pipe.write(bytes); }
        public void close() throws IOException { closed = true; pipe.close(); }
    }

    final class UnixSocket implements IpcTransport {
        private final SocketChannel channel;
        UnixSocket(Path path) throws IOException {
            channel = SocketChannel.open(StandardProtocolFamily.UNIX);
            try { channel.connect(UnixDomainSocketAddress.of(path)); }
            catch (IOException | RuntimeException ex) { channel.close(); throw ex; }
        }
        // Channels stream adapters share a blocking lock; use duplex channel I/O.
        public int read(byte[] b, int off, int len) throws IOException {
            return channel.read(ByteBuffer.wrap(b, off, len));
        }
        public void write(byte[] bytes) throws IOException {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
        }
        public void close() throws IOException { channel.close(); }
    }
}
