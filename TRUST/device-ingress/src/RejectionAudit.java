import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.UUID;

/** One bounded append-only file; failure latches until an operator fixes it and restarts. */
final class RejectionAudit implements AutoCloseable {
    static final int RECORD_RESERVE = 2048;
    final FileChannel file;
    final FileLock lock;
    final long maxBytes;
    final Path path;
    final Object fileKey;
    final IdentityReader identityReader;
    private boolean failed;
    @FunctionalInterface interface IdentityReader {
        Object read(Path path, BasicFileAttributes attributes) throws IOException;
    }
    RejectionAudit(Path path, long maxBytes) throws IOException {
        this(path, maxBytes, (p, attributes) -> attributes.fileKey());
    }
    // Package-private identity seam is only used by tests on filesystems lacking fileKey.
    // Production construction always uses BasicFileAttributes.fileKey and fails if unsupported.
    RejectionAudit(Path path, long maxBytes, IdentityReader identityReader) throws IOException {
        if (maxBytes < 4096) throw new IOException("INVALID_AUDIT_CAPACITY");
        this.maxBytes = maxBytes; this.path = path.toAbsolutePath().normalize(); this.identityReader = identityReader;
        try { Files.createFile(this.path); } catch (FileAlreadyExistsException existing) { /* inspect below */ }
        this.fileKey = currentIdentity();
        file = FileChannel.open(this.path, StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        FileLock acquired = null;
        try {
            verifyIdentity();
            acquired = file.tryLock();
            if (acquired == null) throw new IOException("AUDIT_LOCKED");
            if (file.size() > 0) {
                ByteBuffer last = ByteBuffer.allocate(1); file.read(last, file.size() - 1);
                if (last.array()[0] != '\n') throw new IOException("AUDIT_INCOMPLETE_RECORD");
            }
            if (file.size() > maxBytes - RECORD_RESERVE) throw new IOException("AUDIT_FULL");
            file.force(true); verifyIdentity(); lock = acquired;
        } catch (Exception ex) { if (acquired != null) acquired.release(); file.close(); throw ex; }
    }
    private Object currentIdentity() throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()) throw new IOException("INVALID_AUDIT_FILE");
        Object key = identityReader.read(path, attributes);
        if (key == null) throw new IOException("AUDIT_FILE_IDENTITY_UNAVAILABLE");
        return key;
    }
    private void verifyIdentity() throws IOException {
        if (!fileKey.equals(currentIdentity())) throw new IOException("AUDIT_FILE_REPLACED");
    }
    synchronized void ensureWritable() {
        try {
            if (failed || !file.isOpen() || file.size() > maxBytes - RECORD_RESERVE) throw new IOException("AUDIT_UNAVAILABLE");
            verifyIdentity(); file.force(true); verifyIdentity();
        } catch (Exception ex) { failed = true; throw new DeviceIngress.Problem(503, "AUDIT_UNAVAILABLE"); }
    }
    synchronized boolean append(String principal, int status, String reason, String route, byte[] body) {
        try {
            if (failed || !file.isOpen()) return false;
            verifyIdentity();
            // All caller-controlled strings are excluded. principal and route are trusted constants/config.
            byte[] row = (DeviceIngress.JSON.writeValueAsString(Map.of(
                    "request_id", UUID.randomUUID().toString(), "received_ms", DeviceIngress.now(),
                    "principal", principal, "status", status, "reason", reason, "route", route,
                    "body_bytes", body == null ? 0 : body.length,
                    "body_sha256", body == null ? "unread" : DeviceIngress.sha(body))) + "\n").getBytes(StandardCharsets.UTF_8);
            if (row.length > RECORD_RESERVE || file.size() + row.length > maxBytes) throw new IOException("AUDIT_FULL");
            file.position(file.size()); ByteBuffer buffer = ByteBuffer.wrap(row);
            while (buffer.hasRemaining()) file.write(buffer);
            file.force(true); verifyIdentity(); return true;
        } catch (Exception ex) { failed = true; return false; }
    }
    @Override public synchronized void close() throws IOException {
        failed = true;
        if (file.isOpen()) { if (lock.isValid()) lock.release(); file.close(); }
    }
}
