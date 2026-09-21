package com.bproject.safety.support.demo;

import com.bproject.safety.module.collision.repository.InMemoryCollisionRepository;
import com.bproject.safety.module.fence.repository.InMemoryFenceRepository;
import com.bproject.safety.module.ops.repository.InMemoryEdgeNodeRepository;
import com.bproject.safety.module.ops.service.OpsInventory;
import com.bproject.safety.module.personnel.repository.InMemoryPersonnelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Demo 种子统一初始化入口（落库前运行边界收口 F-11）。
 *
 * <p>此前 Fence / Personnel / Collision / EdgeNode 仓库与 OpsInventory 在构造器中隐式灌种，
 * 导致任何 profile（含未来生产）启动都会自动写入 Demo 数据。现统一迁移到本初始化器，
 * 仅在 {@code app.demo.seed-enabled=true}（dev profile 默认开启）时执行；
 * Alert / AI / Rule 的 CommandLineRunner 同样受 {@link DemoFeatureGuard} 控制。</p>
 *
 * <p>顺序 {@code @Order(5)} 早于 AlertDemoSeeder(10) 与 Ai/RuleDemoSeeder(11)，
 * 保证业务种子引用的区域 / 围栏 / 人员 / 设备台账已就绪。</p>
 */
@Component
@Order(5)
public class DemoSeedInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoSeedInitializer.class);

    private final DemoFeatureGuard guard;
    private final InMemoryFenceRepository fenceRepository;
    private final InMemoryPersonnelRepository personnelRepository;
    private final ObjectProvider<InMemoryCollisionRepository> collisionRepositoryProvider;
    private final ObjectProvider<InMemoryEdgeNodeRepository> edgeNodeRepositoryProvider;
    private final OpsInventory opsInventory;

    public DemoSeedInitializer(DemoFeatureGuard guard,
                               InMemoryFenceRepository fenceRepository,
                               InMemoryPersonnelRepository personnelRepository,
                               ObjectProvider<InMemoryCollisionRepository> collisionRepositoryProvider,
                               ObjectProvider<InMemoryEdgeNodeRepository> edgeNodeRepositoryProvider,
                               OpsInventory opsInventory) {
        this.guard = guard;
        this.fenceRepository = fenceRepository;
        this.personnelRepository = personnelRepository;
        this.collisionRepositoryProvider = collisionRepositoryProvider;
        this.edgeNodeRepositoryProvider = edgeNodeRepositoryProvider;
        this.opsInventory = opsInventory;
    }

    @Override
    public void run(String... args) {
        if (!guard.isSeedEnabled()) {
            log.info("Demo 种子初始化已关闭（app.demo.seed-enabled=false），跳过 Fence/Personnel/Collision/Edge/Ops 台账灌种");
            return;
        }
        fenceRepository.resetDemoData();
        personnelRepository.resetDemoData();
        collisionRepositoryProvider.ifAvailable(DemoResettableStore::resetDemoData);
        edgeNodeRepositoryProvider.ifAvailable(DemoResettableStore::resetDemoData);
        opsInventory.reset();
        log.info("Demo 基础台账种子初始化完成（Fence/Personnel/Collision/Edge/Ops）");
    }
}
