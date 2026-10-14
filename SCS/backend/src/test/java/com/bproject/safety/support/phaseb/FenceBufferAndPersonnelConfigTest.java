package com.bproject.safety.support.phaseb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bproject.safety.common.geo.Polygon2D;
import com.bproject.safety.module.fence.dto.FenceRequests.CreateFenceRequest;
import com.bproject.safety.module.fence.dto.FenceRequests.UpdateFenceRequest;
import com.bproject.safety.module.fence.model.DemoFence;
import com.bproject.safety.module.fence.model.FencePoint;
import com.bproject.safety.module.fence.service.FenceService;
import com.bproject.safety.module.personnel.model.DemoPersonnel;
import com.bproject.safety.module.personnel.model.PersonRiskLevels;
import com.bproject.safety.module.personnel.service.PersonnelService;
import com.bproject.safety.module.personnel.service.PersonnelService.PersonnelConfig;
import com.bproject.safety.module.personnel.service.PersonnelService.UpdatePersonnelConfigRequest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 验证电子围栏逻辑隔离带（缓冲区）扩展与人员定位刷新频率配置机制：
 * 1. 明确人员定位刷新频率（默认 1000ms，支持 200~60000ms 配置与广播）；
 * 2. 电子围栏外围逻辑缓冲区（隔离带预警卡控，不等人到红线才报警）；
 * 3. 电子围栏编辑与保存支持 bufferDistance 与动态计算 bufferPolygon。
 */
@SpringBootTest
@ActiveProfiles("test")
class FenceBufferAndPersonnelConfigTest {

    @Autowired
    private FenceService fenceService;

    @Autowired
    private PersonnelService personnelService;

    @Test
    @DisplayName("Test 1: 几何算法 - 多边形隔离带外扩计算与点位距离判定")
    void polygonBufferExtensionAndApproachingDetection() {
        List<FencePoint> polygon = List.of(
                new FencePoint(10.0, 10.0),
                new FencePoint(30.0, 10.0),
                new FencePoint(30.0, 30.0),
                new FencePoint(10.0, 30.0)
        );

        // 扩展 5.0 米逻辑隔离带
        List<FencePoint> bufferPolygon = Polygon2D.expandPolygon(polygon, 5.0);
        assertThat(bufferPolygon).isNotNull().hasSize(4);

        // 验证外扩后顶点范围向外扩张
        assertThat(bufferPolygon.get(0).x()).isLessThan(10.0);
        assertThat(bufferPolygon.get(0).y()).isLessThan(10.0);
        assertThat(bufferPolygon.get(1).x()).isGreaterThan(30.0);
        assertThat(bufferPolygon.get(2).y()).isGreaterThan(30.0);

        // 点 (20, 20) 在核心红线内部
        assertThat(Polygon2D.contains(20.0, 20.0, polygon.stream().map(FencePoint::xy).toList())).isTrue();
        // 核心红线内的点不是“处于隔离带”（隔离带定义为核心红线外部且距离 <= 缓冲距离）
        assertThat(Polygon2D.isInBufferZone(20.0, 20.0, polygon, 5.0)).isFalse();

        // 点 (33, 20) 距红线边界线 x=30 距离为 3.0，小于等于 5.0，属于外围隔离带
        assertThat(Polygon2D.contains(33.0, 20.0, polygon.stream().map(FencePoint::xy).toList())).isFalse();
        double dist = Polygon2D.distanceToPolygon(33.0, 20.0, polygon);
        assertThat(dist).isCloseTo(3.0, org.assertj.core.data.Offset.offset(0.001));
        assertThat(Polygon2D.isInBufferZone(33.0, 20.0, polygon, 5.0)).isTrue();

        // 点 (40, 20) 距红线为 10.0，超出隔离带
        assertThat(Polygon2D.isInBufferZone(40.0, 20.0, polygon, 5.0)).isFalse();
    }

    @Test
    @DisplayName("Test 2: 人员定位配置 - 明确默认 1000ms (1.0s / 1.0Hz) 并卡控配置合法范围")
    void personnelConfigDefaultAndValidation() {
        PersonnelConfig config = personnelService.getConfig();
        assertThat(config).isNotNull();
        assertThat(config.refreshIntervalMs()).isEqualTo(1000);
        assertThat(config.defaultBufferDistance()).isEqualTo(5.0);

        // 更新合法值 500ms
        PersonnelConfig updated = personnelService.updateConfig(new UpdatePersonnelConfigRequest(500, 6.0));
        assertThat(updated.refreshIntervalMs()).isEqualTo(500);
        assertThat(updated.defaultBufferDistance()).isEqualTo(6.0);
        assertThat(personnelService.getConfig().refreshIntervalMs()).isEqualTo(500);

        // 非法值低于 200ms
        assertThatThrownBy(() -> personnelService.updateConfig(new UpdatePersonnelConfigRequest(100, 5.0)))
                .isInstanceOf(com.bproject.safety.common.error.ApiException.class)
                .hasMessageContaining("200ms ~ 60000ms");

        // 非法值高于 60000ms
        assertThatThrownBy(() -> personnelService.updateConfig(new UpdatePersonnelConfigRequest(70000, 5.0)))
                .isInstanceOf(com.bproject.safety.common.error.ApiException.class)
                .hasMessageContaining("200ms ~ 60000ms");

        // 恢复默认 1000ms 避免影响其它单测
        personnelService.updateConfig(new UpdatePersonnelConfigRequest(1000, 5.0));
    }

    @Test
    @DisplayName("Test 3: 分层防御卡控 - 靠近外围隔离带预警 (未触红线不停机) vs 越界红线严重停机告警")
    void tieredWarningVsIntrusionEscalation() {
        // 1. 模拟靠近隔离带 -> 状态变为 warning 预警，riskCode 为 ATTENTION
        DemoPersonnel approached = personnelService.simulateAbnormal("P-ZHAO", "approach");
        assertThat(approached.state).isEqualTo("warning");
        assertThat(approached.riskCode).isEqualTo(PersonRiskLevels.ATTENTION);

        // 2. 模拟越界核心红线 -> 状态变为 danger 严重停机告警，riskCode 为 HIGH
        DemoPersonnel intruded = personnelService.simulateAbnormal("P-ZHAO", "intrusion");
        assertThat(intruded.state).isEqualTo("danger");
        assertThat(intruded.riskCode).isEqualTo(PersonRiskLevels.HIGH);

        // 恢复正常
        personnelService.simulateAbnormal("P-ZHAO", "restore");
    }

    @Test
    @DisplayName("Test 4: 电子围栏 - 创建与更新时支持 bufferDistance 并自动生成外围逻辑隔离多边形")
    void fenceCreationAndBufferPropagation() {
        List<FencePoint> polygon = List.of(
                new FencePoint(20.0, 20.0),
                new FencePoint(40.0, 20.0),
                new FencePoint(40.0, 50.0),
                new FencePoint(20.0, 50.0)
        );

        CreateFenceRequest createReq = new CreateFenceRequest(
                "装卸警戒区",
                "危险区域",
                "紧急",
                "装卸一班",
                "2026-09-04 08:00",
                "2026-09-04 20:00",
                polygon,
                8.0,
                false
        );

        DemoFence created = fenceService.create(createReq);
        assertThat(created.id).isNotBlank();
        assertThat(created.bufferDistance).isEqualTo(8.0);
        assertThat(created.bufferPolygon).isNotNull().hasSize(4);

        // 更新围栏的隔离带距离为 12.0 米
        UpdateFenceRequest updateReq = new UpdateFenceRequest(
                "装卸警戒区-修编",
                "危险区域",
                "装卸一班",
                "紧急",
                "2026-09-04 08:00",
                "2026-09-04 20:00",
                polygon,
                12.0
        );

        DemoFence updated = fenceService.update(created.id, updateReq);
        assertThat(updated.name).isEqualTo("装卸警戒区-修编");
        assertThat(updated.bufferDistance).isEqualTo(12.0);
        assertThat(updated.bufferPolygon).isNotNull().hasSize(4);
    }
}
