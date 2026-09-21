package com.bproject.safety.support.masterdata;

import static org.assertj.core.api.Assertions.assertThat;

import com.bproject.safety.module.ai.model.CameraInfo;
import com.bproject.safety.module.ai.model.DemoAiEvent;
import com.bproject.safety.module.ai.repository.AiEventRepository;
import com.bproject.safety.module.ai.seed.AiDemoSeeder;
import com.bproject.safety.module.alert.model.DemoAlert;
import com.bproject.safety.module.alert.seed.AlertDemoSeeder;
import com.bproject.safety.module.collision.model.DemoCollisionDevice;
import com.bproject.safety.module.collision.repository.InMemoryCollisionRepository;
import com.bproject.safety.module.fence.model.DemoFence;
import com.bproject.safety.module.fence.repository.InMemoryFenceRepository;
import com.bproject.safety.module.ops.repository.InMemoryEdgeNodeRepository;
import com.bproject.safety.module.ops.service.OpsInventory;
import com.bproject.safety.module.personnel.model.DemoPersonnel;
import com.bproject.safety.module.personnel.repository.InMemoryPersonnelRepository;
import com.bproject.safety.module.projection.shared.RiskClassifier;
import com.bproject.safety.module.rule.model.DemoRule;
import com.bproject.safety.module.rule.repository.InMemoryRuleRepository;
import com.bproject.safety.support.masterdata.DemoMasterData.DemoArea;
import com.bproject.safety.support.masterdata.DemoMasterData.DemoTeam;
import com.bproject.safety.support.masterdata.DemoMasterData.DemoUser;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Demo 主数据一致性测试：所有业务 Seed 引用的区域 / 班组必须落在 {@link DemoMasterData}
 * 定义的 canonical 集合内，且用户主数据自身唯一、自洽。
 *
 * <p>这保证了 AI→Alert、人员越界→Alert（取围栏区域）、碰撞→Alert（取设备区域）
 * 三条运行时建单链路的区域口径都收敛到同一份字典，Analytics 不会因别名产生重复聚合桶。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class MasterDataConsistencyTest {

    @Autowired DemoMasterData master;
    @Autowired DemoDeviceMasterData deviceMaster;
    @Autowired InMemoryPersonnelRepository personnelRepository;
    @Autowired InMemoryFenceRepository fenceRepository;
    @Autowired InMemoryCollisionRepository collisionRepository;
    @Autowired InMemoryRuleRepository ruleRepository;
    @Autowired InMemoryEdgeNodeRepository edgeNodeRepository;
    @Autowired OpsInventory opsInventory;
    @Autowired AiEventRepository aiEventRepository;

    private Set<String> areaNames() {
        return master.areas().stream().map(DemoArea::name).collect(Collectors.toSet());
    }

    private Set<String> teamNames() {
        return master.teams().stream().map(DemoTeam::name).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("User Master：7 个用户 ID / 姓名唯一，班组 code 有效且与班组名一致")
    void usersAreUniqueAndSelfConsistent() {
        List<DemoUser> users = master.users();
        assertThat(users).hasSize(7);
        assertThat(users.stream().map(DemoUser::id).distinct()).hasSize(7);
        assertThat(users.stream().map(DemoUser::name).distinct()).hasSize(7);
        for (DemoUser user : users) {
            assertThat(master.isValidTeamCode(user.teamCode()))
                    .as("用户 %s 的 teamCode=%s 必须存在于班组字典", user.id(), user.teamCode())
                    .isTrue();
            assertThat(user.teamName()).isEqualTo(master.teamName(user.teamCode()));
        }
        // USR-005 ~ USR-007 班组关系无权威人员主数据佐证，必须显式标记 Demo 待核验。
        assertThat(master.user("USR-005").demoUnverified()).isTrue();
        assertThat(master.user("USR-006").demoUnverified()).isTrue();
        assertThat(master.user("USR-007").demoUnverified()).isTrue();
        assertThat(master.users().subList(0, 4)).allMatch(u -> !u.demoUnverified());
    }

    @Test
    @DisplayName("Team / Area 字典：code 与名称均唯一")
    void codesAndNamesAreUnique() {
        assertThat(master.teams().stream().map(DemoTeam::code).distinct()).hasSize(master.teams().size());
        assertThat(master.teams().stream().map(DemoTeam::name).distinct()).hasSize(master.teams().size());
        assertThat(master.areas().stream().map(DemoArea::code).distinct()).hasSize(master.areas().size());
        assertThat(master.areas().stream().map(DemoArea::name).distinct()).hasSize(master.areas().size());
    }

    @Test
    @DisplayName("Alert Seed：area 全部属于 canonical 区域字典，RiskClassifier 班组全部 canonical")
    void alertSeedsUseCanonicalAreasAndTeams() {
        List<DemoAlert> alerts = AlertDemoSeeder.buildSeeds(Clock.systemUTC());
        Set<String> areas = areaNames();
        Set<String> teams = teamNames();
        for (DemoAlert alert : alerts) {
            assertThat(areas).as("告警 %s area=%s 必须在区域字典内", alert.id, alert.area).contains(alert.area);
            assertThat(teams).as("告警 %s 归并班组必须 canonical", alert.id).contains(RiskClassifier.teamOf(alert));
        }
    }

    @Test
    @DisplayName("AI Seed：事件与摄像头 area 全部 canonical（保证 AI→Alert 区域口径）")
    void aiSeedsUseCanonicalAreas() {
        Set<String> areas = areaNames();
        for (CameraInfo camera : AiDemoSeeder.buildCameras(deviceMaster)) {
            assertThat(areas).as("摄像头 %s area=%s 必须在区域字典内", camera.cameraId(), camera.area())
                    .contains(camera.area());
        }
        List<DemoAiEvent> events = aiEventRepository.findAll();
        assertThat(events).isNotEmpty();
        for (DemoAiEvent event : events) {
            assertThat(areas).as("AI 事件 %s area=%s 必须在区域字典内", event.id, event.area).contains(event.area);
        }
    }

    @Test
    @DisplayName("Personnel Seed：team / area 全部 canonical")
    void personnelSeedsUseCanonicalTeamsAndAreas() {
        Set<String> areas = areaNames();
        Set<String> teams = teamNames();
        List<DemoPersonnel> people = personnelRepository.findAll();
        assertThat(people).isNotEmpty();
        for (DemoPersonnel person : people) {
            assertThat(teams).as("人员 %s(%s) team=%s 必须在班组字典内", person.id, person.name, person.team)
                    .contains(person.team);
            assertThat(areas).as("人员 %s area=%s 必须在区域字典内", person.id, person.area).contains(person.area);
        }
    }

    @Test
    @DisplayName("Fence Seed：area 全部 canonical（人员越界建单取围栏区域）")
    void fenceSeedsUseCanonicalAreas() {
        Set<String> areas = areaNames();
        List<DemoFence> fences = fenceRepository.findAll();
        assertThat(fences).isNotEmpty();
        for (DemoFence fence : fences) {
            assertThat(areas).as("围栏 %s area=%s 必须在区域字典内", fence.id, fence.area).contains(fence.area);
        }
    }

    @Test
    @DisplayName("Collision Seed：设备 area 全部 canonical（碰撞建单取设备区域）")
    void collisionSeedsUseCanonicalAreas() {
        Set<String> areas = areaNames();
        List<DemoCollisionDevice> devices = collisionRepository.findAll();
        assertThat(devices).isNotEmpty();
        for (DemoCollisionDevice device : devices) {
            assertThat(areas).as("设备 %s area=%s 必须在区域字典内", device.id, device.area).contains(device.area);
        }
    }

    @Test
    @DisplayName("Rule Seed：适用范围只允许 canonical 区域或“全部区域”通配")
    void ruleSeedsUseCanonicalAreas() {
        Set<String> allowed = areaNames();
        allowed.add("全部区域");
        List<DemoRule> rules = ruleRepository.findAll();
        assertThat(rules).isNotEmpty();
        for (DemoRule rule : rules) {
            for (String area : rule.areas) {
                assertThat(allowed).as("规则 %s area=%s 必须 canonical 或通配", rule.id, area).contains(area);
            }
        }
    }

    @Test
    @DisplayName("Ops Seed：边缘节点与台账设备 area 全部 canonical")
    void opsSeedsUseCanonicalAreas() {
        Set<String> areas = areaNames();
        edgeNodeRepository.findAll().forEach((node) ->
                assertThat(areas).as("边缘节点 %s area=%s 必须在区域字典内", node.id, node.area).contains(node.area));
        opsInventory.devices().forEach((device) ->
                assertThat(areas).as("设备 %s area=%s 必须在区域字典内", device.id(), device.area()).contains(device.area()));
    }
}
