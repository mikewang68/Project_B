import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

final class JdbcSessionTest extends SecurityHttpTest {
    static void run(Path temp) throws Exception {
        Object lock = new Object(); AtomicInteger opened = new AtomicInteger();
        String url = "jdbc:h2:mem:lease;DB_CLOSE_DELAY=-1";
        Path metrics = temp.resolve("timing.jsonl");
        IngressTiming timing = new IngressTiming(metrics, 4096);
        JdbcSession pool = new JdbcSession(lock, () -> { opened.incrementAndGet(); return DriverManager.getConnection(url); }, true, timing);
        try {
            try { pool.borrow(); throw new AssertionError("unlocked borrow"); }
            catch (IllegalStateException expected) { check(true, "reuse requires original db lock"); }
            synchronized (lock) {
                try (Connection c = pool.borrow(); Statement s = c.createStatement()) { s.execute("CREATE TABLE t(id INT PRIMARY KEY)"); }
                try (Connection c = pool.borrow()) {
                    c.setAutoCommit(false); try (Statement s = c.createStatement()) { s.execute("INSERT INTO t VALUES(1)"); }
                    // Deliberately omit commit: release must roll back, never commit.
                }
                try (Connection c = pool.borrow(); Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT COUNT(*) FROM t")) {
                    r.next(); check(r.getInt(1) == 0 && c.getAutoCommit(), "lease release rolls back and resets autocommit");
                }
                check(opened.get() == 1, "sequential operations reuse one physical connection");
                try (Connection c = pool.borrow(); Statement s = c.createStatement()) {
                    s.execute("INSERT INTO t VALUES(2)");
                    try { s.execute("INSERT INTO t VALUES(2)"); throw new AssertionError("expected duplicate"); }
                    catch (SQLException expected) { check(true, "SQL error surfaces without automatic retry"); }
                }
                try (Connection c = pool.borrow(); Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT 1")) {
                    check(opened.get() == 2 && r.next() && r.getInt(1)==1, "SQL error discards connection and next request succeeds");
                }
                pool.cached.close();
                try (Connection c = pool.borrow()) { check(opened.get() == 3, "closed idle connection replaced"); }
            }
        } finally { pool.close(); }
        AtomicInteger commits = new AtomicInteger();
        JdbcSession ambiguous = new JdbcSession(lock, () -> {
            Connection raw = DriverManager.getConnection(url);
            return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (p,m,args) -> {
                try {
                    Object result = m.invoke(raw,args);
                    if (m.getName().equals("commit")) { commits.incrementAndGet(); throw new SQLException("synthetic lost commit response"); }
                    return result;
                } catch (InvocationTargetException e) { throw e.getCause(); }
            });
        }, true, timing);
        try {
            synchronized (lock) {
                try (Connection c = ambiguous.borrow()) {
                    c.setAutoCommit(false); try (Statement s = c.createStatement()) { s.execute("INSERT INTO t VALUES(3)"); }
                    try { c.commit(); throw new AssertionError("ambiguous commit"); }
                    catch (SQLException expected) { check(true, "ambiguous commit failure reported"); }
                }
                check(commits.get() == 1 && ambiguous.cached == null, "no replay after ambiguous commit");
                try (Connection c=ambiguous.borrow();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT 1")) {
                    check(r.next() && r.getInt(1)==1, "fresh session works after ambiguous commit without replay");
                }
                try (Connection c = DriverManager.getConnection(url); Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT COUNT(*) FROM t WHERE id=3")) {
                    r.next(); check(r.getInt(1) == 1, "ambiguous outcome remains exactly one committed effect");
                }
            }
        } finally { ambiguous.close(); }
        AtomicBoolean disconnect = new AtomicBoolean(true); AtomicInteger reconnects = new AtomicInteger();
        JdbcSession interrupted = new JdbcSession(lock, () -> {
            reconnects.incrementAndGet(); Connection raw=DriverManager.getConnection(url);
            return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,args)->{
                try {
                    Object value=m.invoke(raw,args);
                    if(value instanceof Statement) return Proxy.newProxyInstance(Statement.class.getClassLoader(),new Class<?>[]{Statement.class},(sp,sm,sa)->{
                        if(sm.getName().startsWith("execute") && disconnect.getAndSet(false)) throw new SQLException("synthetic connection loss","08006");
                        try{return sm.invoke(value,sa);}catch(InvocationTargetException failure){throw failure.getCause();}
                    });
                    return value;
                }catch(InvocationTargetException failure){throw failure.getCause();}
            });
        },true,timing);
        try {
            synchronized(lock) {
                try(Connection c=interrupted.borrow();Statement s=c.createStatement()) {
                    try{s.executeQuery("SELECT 1");throw new AssertionError("disconnect expected");}
                    catch(SQLException expected){check("08006".equals(expected.getSQLState()),"in-flight connection loss reaches caller");}
                }
                check(interrupted.cached==null && reconnects.get()==1,"lost connection discarded without same-request replay");
                try(Connection c=interrupted.borrow();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT 1")) {
                    check(reconnects.get()==2 && r.next() && r.getInt(1)==1,"request after disconnect reconnects and succeeds");
                }
            }
        }finally{interrupted.close();}
        DeviceIngress app = new DeviceIngress(timing, true);
        ExecutorService workers = Executors.newFixedThreadPool(4);
        try {
            app.token = LEGACY.getBytes(java.nio.charset.StandardCharsets.UTF_8); start(app);
            byte[] httpEvent = wire(event("SIM-TIMING", 500));
            response(request("POST", "/events", LEGACY, httpEvent), 202, null, "instrumented reuse HTTP accept");
            check(app.consume(httpEvent,"local-timing"), "instrumented reuse consume");
            String cursor = app.history(Map.of("device_id","SIM-TIMING")).path("next_cursor").asText();
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i=501;i<=520;i++) { final int seq=i; results.add(workers.submit(() -> {
                byte[] body=wire(event("SIM-TIMING",seq)); app.accept(body); return app.consume(body,"local-timing");
            })); }
            for (Future<Boolean> result:results) check(result.get(15,TimeUnit.SECONDS),"concurrent commit serialized");
            var page = app.history(Map.of("device_id","SIM-TIMING","cursor",cursor));
            check(page.path("items").size()==20,"reuse preserves cursor catchup without missing commits");
            long previous=0;
            for (var item:page.path("items")) { check(item.path("committed_ms").asLong()>previous,"commit watermark remains strictly increasing"); previous=item.path("committed_ms").asLong(); }
            check(app.accept(httpEvent).path("duplicate").asBoolean(),"reuse preserves duplicate event idempotency");
            check(timing.counters.containsKey("db.lock_wait") && timing.counters.containsKey("jdbc.connect")
                    && timing.counters.containsKey("jdbc.execute") && timing.counters.containsKey("request.post:events"),"fixed request lock connection SQL counters present");
            for(int i=0;i<100;i++) timing.flush();
        } finally { workers.shutdownNow(); app.close(); }
        check(Files.size(metrics)<=4096,"timing output obeys hard cap without rotating evidence");
        String log=Files.readString(metrics);
        check(!log.contains(LEGACY) && !log.contains("SIM-TIMING") && !log.contains("INSERT") && !log.contains("jdbc:h2"),"timing excludes credentials IDs SQL and connection strings");
        try { new IngressTiming(metrics,4096); throw new AssertionError("overwrite"); }
        catch(FileAlreadyExistsException expected) { check(true,"timing refuses to overwrite previous file"); }
    }
}
