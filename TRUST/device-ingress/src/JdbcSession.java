import java.lang.reflect.*;
import java.sql.*;

/** At most one physical connection, borrowed ONLY inside the existing dbLock.
 * No query or transaction retries, including ambiguous commit failures. */
final class JdbcSession implements AutoCloseable {
    interface Connector { Connection open() throws SQLException; }
    final Object lock;
    final Connector connector;
    final boolean reuse;
    final IngressTiming timing;
    Connection cached;
    boolean borrowed, closed;
    JdbcSession(Object lock, Connector connector, boolean reuse, IngressTiming timing) {
        this.lock = lock; this.connector = connector; this.reuse = reuse; this.timing = timing;
    }
    static boolean configuredReuse() {
        String value = DeviceIngress.env("DEVICE_JDBC_REUSE", "false");
        if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException("INVALID_JDBC_REUSE");
        return value.equals("true");
    }
    Connection borrow() throws SQLException {
        if (!Thread.holdsLock(lock)) throw new IllegalStateException("DB_LOCK_REQUIRED");
        if (closed || borrowed) throw new SQLException("JDBC_SESSION_UNAVAILABLE");
        if (!reuse && !timing.enabled) return connector.open(); // Exact legacy connection lifetime.
        if (cached != null) {
            try { if (cached.isClosed()) cached = null; }
            catch (SQLException ex) { try { cached.close(); } catch (SQLException ignored) { } cached = null; throw ex; }
        }
        Connection physical = cached;
        if (physical == null) {
            long start = timing.begin();
            try { physical = connector.open(); } finally { timing.end("jdbc.connect", start); }
        }
        borrowed = true; cached = null;
        try { return new Lease(physical).proxy(); }
        catch (SQLException ex) { borrowed = false; try { physical.close(); } catch (SQLException ignored) { } throw ex; }
    }
    final class Lease implements InvocationHandler {
        final Connection physical;
        final int isolation;
        final boolean readOnly;
        final long started = timing.begin();
        boolean ended, failed;
        Lease(Connection physical) throws SQLException {
            this.physical = physical; isolation = physical.getTransactionIsolation(); readOnly = physical.isReadOnly();
        }
        Connection proxy() { return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, this); }
        Object call(Object target, Method method, Object[] args, String category) throws Throwable {
            long start = timing.begin();
            try { return method.invoke(target, args); }
            catch (InvocationTargetException ex) { if (ex.getCause() instanceof SQLException) failed = true; throw ex.getCause(); }
            finally { if (category != null) timing.end(category, start); }
        }
        Object wrap(Object target, Class<?> iface) {
            return Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, (p, m, args) -> {
                if (ended) throw new SQLException("JDBC_LEASE_CLOSED");
                if (m.getName().equals("unwrap")) throw new SQLException("JDBC_UNWRAP_DISABLED");
                if (m.getName().equals("isWrapperFor")) return false;
                String category = m.getName().startsWith("execute") ? "jdbc.execute" : m.getName().equals("next") ? "jdbc.fetch" : null;
                Object value = call(target, m, args, category);
                if (value instanceof ResultSet) return wrap(value, ResultSet.class);
                return value;
            });
        }
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (name.equals("close")) { release(); return null; }
            if (name.equals("isClosed") && ended) return true;
            if (ended) throw new SQLException("JDBC_LEASE_CLOSED");
            if (name.equals("unwrap")) throw new SQLException("JDBC_UNWRAP_DISABLED");
            if (name.equals("isWrapperFor")) return false;
            String category = name.equals("commit") || name.equals("rollback") ? "jdbc." + name : name.startsWith("prepare") ? "jdbc.prepare" : null;
            Object value = call(physical, method, args, category);
            if (value instanceof PreparedStatement) return wrap(value, PreparedStatement.class);
            if (value instanceof Statement) return wrap(value, Statement.class);
            return value;
        }
        void release() throws SQLException {
            if (ended) return;
            if (!Thread.holdsLock(lock)) throw new IllegalStateException("DB_LOCK_REQUIRED");
            ended = true; SQLException error = null;
            try {
                if (!physical.getAutoCommit()) { physical.rollback(); physical.setAutoCommit(true); }
                if (physical.isReadOnly() != readOnly) physical.setReadOnly(readOnly);
                if (physical.getTransactionIsolation() != isolation) physical.setTransactionIsolation(isolation);
                physical.clearWarnings();
            } catch (SQLException ex) { failed = true; error = ex; }
            finally {
                borrowed = false;
                if (reuse && !failed && !closed) cached = physical;
                else try { physical.close(); } catch (SQLException ex) { if (error == null) error = ex; }
                timing.end("jdbc.lease", started);
            }
            if (error != null) throw error;
        }
    }
    public void close() {
        synchronized (lock) {
            closed = true;
            if (cached != null) try { cached.close(); } catch (SQLException ignored) { }
            cached = null;
        }
    }
}
