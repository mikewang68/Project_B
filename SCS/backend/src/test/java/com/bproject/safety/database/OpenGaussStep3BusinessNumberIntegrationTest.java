package com.bproject.safety.database;

import com.bproject.safety.module.alert.service.AlertNumberGenerator;
import com.bproject.safety.support.number.JdbcAlertNumberGenerator;
import com.bproject.safety.support.number.JdbcBusinessNumberStore;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCS Phase 1 / Step 3: openGauss 真实数据库 sys_business_number 发号器实机测试。
 * 覆盖：O(1) 增量与首日创建、32 线程高并发争夺、多独立连接/多实例竞争、日切与类型隔离、
 * REQUIRES_NEW 事务回滚断号（GAP_ALLOWED）语义及 Alert 集成。
 *
 * <p>安全隔离：测试业务类型前缀 {@code SCS_STEP3_IT_}，完成后精确清理自身记录，严禁触碰正式生产数据。</p>
 */
@EnabledIfEnvironmentVariable(named = OpenGaussStep3BusinessNumberIntegrationTest.ENV_STEP3_ENABLED, matches = "true")
@DisplayName("Phase 1 / Step 3: openGauss 真实数据库 sys_business_number 分布式发号器验收测试")
public class OpenGaussStep3BusinessNumberIntegrationTest extends OpenGaussSpikeSupport {

    public static final String ENV_STEP3_ENABLED = "RUN_OPENGAUSS_STEP3";
    private static final String TEST_TYPE_PREFIX = "SCS_STEP3_IT_";

    private NamedParameterJdbcTemplate jdbcTemplate;
    private DataSourceTransactionManager transactionManager;
    private JdbcBusinessNumberStore numberStore;
    private Clock clock;

    @BeforeEach
    void setUp() {
        if (dataSource == null) {
            return;
        }
        jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        numberStore = new JdbcBusinessNumberStore(jdbcTemplate, transactionManager);
        clock = Clock.system(ZoneId.of("Asia/Shanghai"));

        cleanupTestData();
    }

    @AfterEach
    void tearDown() {
        cleanupTestData();
    }

    private void cleanupTestData() {
        if (dataSource == null) {
            return;
        }
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM safety.sys_business_number WHERE number_type LIKE 'SCS_STEP3_IT_%'")) {
            ps.executeUpdate();
        } catch (Exception ignored) {
        }
    }

    @Test
    @DisplayName("发号基础自增：首发插入初始值 1，后续发号严格 O(1) 递增")
    void testBasicSequenceIncrementOnOpenGauss() throws Exception {
        String type = TEST_TYPE_PREFIX + "BASIC";
        String date = "20260923";

        long seq1 = numberStore.nextSequence(type, date);
        assertThat(seq1).isEqualTo(1L);

        long seq2 = numberStore.nextSequence(type, date);
        assertThat(seq2).isEqualTo(2L);

        long seq3 = numberStore.nextSequence(type, date);
        assertThat(seq3).isEqualTo(3L);

        // 验证数据库物理行状态
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT current_value, lock_version FROM safety.sys_business_number WHERE number_type = ? AND business_date = ?")) {
            ps.setString(1, type);
            ps.setString(2, date);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong("current_value")).isEqualTo(3L);
                assertThat(rs.getInt("lock_version")).isEqualTo(2);
            }
        }
    }

    @Test
    @DisplayName("日切与类型隔离：不同日期、不同业务类型各自拥有独立的序号计数")
    void testDateResetAndTypeIsolation() {
        String typeA = TEST_TYPE_PREFIX + "A";
        String typeB = TEST_TYPE_PREFIX + "B";
        String date1 = "20260923";
        String date2 = "20260924";

        assertThat(numberStore.nextSequence(typeA, date1)).isEqualTo(1L);
        assertThat(numberStore.nextSequence(typeA, date1)).isEqualTo(2L);

        // 同类型跨天：独立重置从 1 开始
        assertThat(numberStore.nextSequence(typeA, date2)).isEqualTo(1L);

        // 同天不同类型：独立从 1 开始
        assertThat(numberStore.nextSequence(typeB, date1)).isEqualTo(1L);
        assertThat(numberStore.nextSequence(typeB, date2)).isEqualTo(1L);
    }

    @Test
    @DisplayName("32 线程高并发竞争同一业务键：产生且仅产生 1..32 连续唯一序号，0 重复")
    void test32ThreadsConcurrencyOnOpenGauss() throws Exception {
        int threads = 32;
        String type = TEST_TYPE_PREFIX + "CONC";
        String date = "20260923";

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch readyLatch = new CountDownLatch(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Long> results = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    long seq = numberStore.nextSequence(type, date);
                    results.add(seq);
                } catch (Throwable t) {
                    errors.add(t);
                }
            });
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();
        executor.shutdown();
        boolean finished = executor.awaitTermination(20, TimeUnit.SECONDS);
        assertThat(finished).isTrue();

        assertThat(errors).isEmpty();
        assertThat(results).hasSize(threads);

        // 验证 32 个序号全部唯一且无缝递增
        List<Long> sorted = new ArrayList<>(results);
        Collections.sort(sorted);
        for (int i = 0; i < threads; i++) {
            assertThat(sorted.get(i)).isEqualTo((long) (i + 1));
        }

        // 验证数据库物理存储
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT current_value FROM safety.sys_business_number WHERE number_type = ? AND business_date = ?")) {
            ps.setString(1, type);
            ps.setString(2, date);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong(1)).isEqualTo(32L);
            }
        }
    }

    @Test
    @DisplayName("多独立 DataSource 连接并发竞争（模拟多实例 / 多 JVM 部署）：行锁原子串行化")
    void testMultiInstanceSimulationWithIndependentPools() throws Exception {
        String type = TEST_TYPE_PREFIX + "MULTI_INSTANCE";
        String date = "20260923";

        // 构建第二个独立连接池模拟外部应用实例
        HikariConfig secondaryConfig = new HikariConfig();
        secondaryConfig.setDriverClassName("org.postgresql.Driver");
        secondaryConfig.setJdbcUrl(dataSource.getJdbcUrl());
        secondaryConfig.setUsername(dataSource.getUsername());
        secondaryConfig.setPassword(dataSource.getPassword());
        secondaryConfig.setMaximumPoolSize(2);
        secondaryConfig.setPoolName("SecondaryInstancePool");

        try (HikariDataSource secondaryDataSource = new HikariDataSource(secondaryConfig)) {
            NamedParameterJdbcTemplate secondaryJdbcTemplate = new NamedParameterJdbcTemplate(secondaryDataSource);
            DataSourceTransactionManager secondaryTxManager = new DataSourceTransactionManager(secondaryDataSource);
            JdbcBusinessNumberStore store2 = new JdbcBusinessNumberStore(secondaryJdbcTemplate, secondaryTxManager);

            int iterations = 10;
            ExecutorService executor = Executors.newFixedThreadPool(4);
            List<Long> allocated = Collections.synchronizedList(new ArrayList<>());
            List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

            for (int i = 0; i < iterations; i++) {
                executor.submit(() -> {
                    try {
                        allocated.add(numberStore.nextSequence(type, date));
                    } catch (Throwable t) {
                        errors.add(t);
                    }
                });
                executor.submit(() -> {
                    try {
                        allocated.add(store2.nextSequence(type, date));
                    } catch (Throwable t) {
                        errors.add(t);
                    }
                });
            }

            executor.shutdown();
            executor.awaitTermination(15, TimeUnit.SECONDS);

            assertThat(errors).isEmpty();
            assertThat(allocated).hasSize(iterations * 2);
            long distinctCount = allocated.stream().distinct().count();
            assertThat(distinctCount).isEqualTo((long) (iterations * 2));
        }
    }

    @Test
    @DisplayName("回滚语义验证（GAP_ALLOWED）：外层事务回滚，发号短事务已提交释放锁，号段安全消耗绝不重复")
    void testNumberRollbackSemanticsGapAllowed() {
        String type = TEST_TYPE_PREFIX + "ROLLBACK";
        String date = "20260923";

        TransactionTemplate outerTx = new TransactionTemplate(transactionManager);
        outerTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);

        try {
            outerTx.execute(status -> {
                long seq = numberStore.nextSequence(type, date);
                assertThat(seq).isEqualTo(1L);

                // 模拟业务处理异常导致外层业务回滚
                status.setRollbackOnly();
                throw new RuntimeException("Simulated business failure after sequence allocated");
            });
        } catch (RuntimeException ignored) {
        }

        // 外层业务回滚后，再次申请序号：应当获取 2L（号段安全消耗，允许断号，杜绝并发重号）
        long nextSeq = numberStore.nextSequence(type, date);
        assertThat(nextSeq).isEqualTo(2L);
    }

    @Test
    @DisplayName("JdbcAlertNumberGenerator 整合验证：生成 ALM-yyyyMMdd-NNN 并在 openGauss 持久化")
    void testJdbcAlertNumberGeneratorIntegration() {
        AlertNumberGenerator generator = new JdbcAlertNumberGenerator(numberStore, clock);
        String todayStr = LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai")))
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));

        String alertNo1 = generator.nextAlertNumber();
        assertThat(alertNo1).startsWith("ALM-" + todayStr + "-");

        String alertNo2 = generator.nextAlertNumber();
        assertThat(alertNo2).startsWith("ALM-" + todayStr + "-");

        assertThat(alertNo1).isNotEqualTo(alertNo2);
    }

    @Test
    @DisplayName("重启持久化验证 Part 1（重启前发号）：获取编号 N")
    void testRestartPersistencePart1() {
        String type = "SCS_STEP3_RESTART_PERSISTENCE";
        String date = "20260923";
        // 清理旧值
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM safety.sys_business_number WHERE number_type = ?")) {
            ps.setString(1, type);
            ps.executeUpdate();
        } catch (Exception ignored) {
        }

        long n = numberStore.nextSequence(type, date);
        System.out.println("[RESTART-PERSISTENCE] Before Restart allocated sequence N = " + n);
        assertThat(n).isEqualTo(1L);
    }

    @Test
    @DisplayName("重启持久化验证 Part 2（重启后发号）：获取编号 N+1，绝不重置为 1")
    void testRestartPersistencePart2() {
        String type = "SCS_STEP3_RESTART_PERSISTENCE";
        String date = "20260923";

        long next = numberStore.nextSequence(type, date);
        System.out.println("[RESTART-PERSISTENCE] After Restart allocated sequence N+1 = " + next);
        assertThat(next).isEqualTo(2L);

        // 清理测试数据
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM safety.sys_business_number WHERE number_type = ?")) {
            ps.setString(1, type);
            ps.executeUpdate();
        } catch (Exception ignored) {
        }
    }
}
