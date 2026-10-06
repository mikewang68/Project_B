package com.bproject.safety.module.alert.repository;

import com.bproject.safety.module.alert.model.AlertPageResult;
import com.bproject.safety.module.alert.model.AlertStatuses;
import com.bproject.safety.module.alert.model.DemoAlert;
import com.bproject.safety.module.alert.model.RiskLevels;
import com.bproject.safety.module.alert.seed.AlertDemoSeeder;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Step 2: AlertRepository 契约一致性测试 (InMemory 基线)")
class AlertRepositoryContractTest {

    private AlertRepository repository;
    private Clock clock;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-09-22T12:00:00Z"), ZoneId.of("Asia/Shanghai"));
        repository = new InMemoryAlertRepository(clock);
        List<DemoAlert> seeds = AlertDemoSeeder.buildSeeds(clock);
        seeds.forEach(repository::save);
    }

    @Test
    @DisplayName("findAll / count 契约")
    void testFindAllAndCount() {
        assertThat(repository.count()).isEqualTo(12);
        List<DemoAlert> all = repository.findAll();
        assertThat(all).hasSize(12);
    }

    @Test
    @DisplayName("findById 契约")
    void testFindById() {
        Optional<DemoAlert> opt = repository.findById("ALM-20260904-001");
        assertThat(opt).isPresent();
        assertThat(opt.get().title).isEqualTo("人员进入龙门吊作业区域");
        assertThat(opt.get().timeline).isNotEmpty();
        assertThat(opt.get().evidence).isNotNull();
    }

    @Test
    @DisplayName("findOpenByDedupKey: 找到未关闭告警，忽略已关闭告警")
    void testFindOpenByDedupKey() {
        DemoAlert alert = new DemoAlert();
        alert.id = "ALM-DEDUP-001";
        alert.dedupKey = "COLLISION:DEV-01:DEV-02";
        alert.statusCode = AlertStatuses.PROCESSING;
        repository.save(alert);

        Optional<DemoAlert> found = repository.findOpenByDedupKey("COLLISION:DEV-01:DEV-02");
        assertThat(found).isPresent();
        assertThat(found.get().id).isEqualTo("ALM-DEDUP-001");

        // 关闭告警
        alert.statusCode = AlertStatuses.CLOSED;
        repository.save(alert);

        Optional<DemoAlert> foundAfterClose = repository.findOpenByDedupKey("COLLISION:DEV-01:DEV-02");
        assertThat(foundAfterClose).isEmpty();
    }

    @Test
    @DisplayName("filter & page 契约")
    void testFilterAndPage() {
        AlertQuery q = AlertQuery.of(null, "紧急", null, null, null, null, null, null, null, null, null, null, null, 1, 10);
        AlertPageResult page = repository.page(q);
        assertThat(page.list()).allMatch(a -> RiskLevels.URGENT.equals(a.riskCode));
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.pageSize()).isEqualTo(10);
    }
}
