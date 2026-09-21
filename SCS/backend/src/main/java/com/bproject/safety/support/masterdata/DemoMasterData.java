package com.bproject.safety.support.masterdata;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Backend Demo 阶段唯一的主数据权威来源（User Master / Team / Area）。
 *
 * <p>各业务模块 Seed（Alert / AI / Personnel / Fence / Collision / Rule / Ops）以及
 * {@code /api/v1/meta/dictionaries} 字典接口都必须引用本类中的稳定 code / 规范名称，
 * 不得在各模块另行硬编码区域、班组中文字符串。</p>
 *
 * <p><b>注意：本类是 Demo Canonical Master Data，不是正式生产主数据。</b>
 * 其中 USR-005 ~ USR-007 的班组关系缺少权威人员主数据佐证，已标记
 * {@code demoUnverified=true}，生产上线前必须与正式组织/区域主数据核验。
 * 详见 {@code docs/demo-master-data.md}。</p>
 */
@Component
public class DemoMasterData {

    /** Demo 班组（稳定 code + 规范中文名）。 */
    public record DemoTeam(String code, String name) {
    }

    /** Demo 作业区域（稳定 code + 规范中文名）。 */
    public record DemoArea(String code, String name) {
    }

    /**
     * Demo 责任人（派单字典用户）。
     *
     * @param id             稳定用户 ID（USR-xxx），业务关联值
     * @param name           中文姓名（展示值）
     * @param teamCode       班组稳定 code
     * @param teamName       班组规范中文名（由 {@link #teamName(String)} 解析，避免重复书写）
     * @param demoUnverified true 表示该用户的班组关系未经权威人员主数据核验
     */
    public record DemoUser(String id, String name, String teamCode, String teamName, boolean demoUnverified) {
    }

    public static final String TEAM_LOADING_1 = "LOADING_TEAM_1";
    public static final String TEAM_LOADING_2 = "LOADING_TEAM_2";
    public static final String TEAM_SAFETY = "SAFETY_MANAGEMENT";
    public static final String TEAM_EQUIPMENT = "EQUIPMENT_MAINTENANCE";
    public static final String TEAM_CONTRACTOR = "CONTRACTOR";
    public static final String TEAM_QUALITY = "QUALITY_MANAGEMENT";

    public static final String AREA_LOADING_A = "LOADING_AREA_A";
    public static final String AREA_LOADING_B = "LOADING_AREA_B";
    public static final String AREA_GANTRY_CRANE = "GANTRY_CRANE_AREA";
    public static final String AREA_TIPPER = "CONTAINER_TIPPER_AREA";
    public static final String AREA_VEHICLE_LANE = "VEHICLE_LANE";
    public static final String AREA_TEMP_WORK = "TEMPORARY_WORK_AREA";
    // 以下区域与装卸区/车辆通道的关系未经权威站场图核验，先作为独立 Demo 区域保留（UNRESOLVED）。
    public static final String AREA_BLOCK_A = "CONTAINER_BLOCK_A";
    public static final String AREA_BLOCK_B = "CONTAINER_BLOCK_B";
    public static final String AREA_LANE_C = "CONTAINER_LANE_C";
    public static final String AREA_MAINTENANCE_LANE = "MAINTENANCE_LANE";
    public static final String AREA_RAILWAY_LINE_B = "RAILWAY_LOADING_LINE_B";
    public static final String AREA_EQUIPMENT_ROOM = "EQUIPMENT_ROOM";

    private static final List<DemoTeam> TEAMS = List.of(
            new DemoTeam(TEAM_LOADING_1, "装卸一班"),
            new DemoTeam(TEAM_LOADING_2, "装卸二班"),
            new DemoTeam(TEAM_SAFETY, "安全管理组"),
            new DemoTeam(TEAM_EQUIPMENT, "设备维保班"),
            new DemoTeam(TEAM_CONTRACTOR, "外协单位"),
            new DemoTeam(TEAM_QUALITY, "质量管理"));

    private static final List<DemoArea> AREAS = List.of(
            new DemoArea(AREA_LOADING_A, "装卸区 A"),
            new DemoArea(AREA_LOADING_B, "装卸区 B"),
            new DemoArea(AREA_GANTRY_CRANE, "龙门吊作业区"),
            new DemoArea(AREA_TIPPER, "翻箱机区"),
            new DemoArea(AREA_VEHICLE_LANE, "车辆通道"),
            new DemoArea(AREA_TEMP_WORK, "临时施工区域"),
            new DemoArea(AREA_BLOCK_A, "箱区 A"),
            new DemoArea(AREA_BLOCK_B, "箱区 B"),
            new DemoArea(AREA_LANE_C, "箱区通道 C"),
            new DemoArea(AREA_MAINTENANCE_LANE, "维修通道"),
            new DemoArea(AREA_RAILWAY_LINE_B, "铁路装卸线 B"),
            new DemoArea(AREA_EQUIPMENT_ROOM, "机房"));

    private static final List<DemoUser> USERS = List.of(
            new DemoUser("USR-001", "李娜", TEAM_SAFETY, teamNameOf(TEAM_SAFETY), false),
            new DemoUser("USR-002", "王建国", TEAM_SAFETY, teamNameOf(TEAM_SAFETY), false),
            new DemoUser("USR-003", "赵明", TEAM_LOADING_1, teamNameOf(TEAM_LOADING_1), false),
            new DemoUser("USR-004", "陈静", TEAM_EQUIPMENT, teamNameOf(TEAM_EQUIPMENT), false),
            // USR-005 ~ USR-007 的班组关系缺少权威人员主数据佐证，仅沿用 Demo Seed，待生产前核验。
            new DemoUser("USR-005", "刘志明", TEAM_LOADING_2, teamNameOf(TEAM_LOADING_2), true),
            new DemoUser("USR-006", "陈晓", TEAM_SAFETY, teamNameOf(TEAM_SAFETY), true),
            new DemoUser("USR-007", "周海", TEAM_EQUIPMENT, teamNameOf(TEAM_EQUIPMENT), true));

    private static String teamNameOf(String code) {
        return TEAMS.stream().filter((t) -> t.code().equals(code)).findFirst()
                .map(DemoTeam::name).orElse(null);
    }

    private final Map<String, DemoTeam> teamByCode = TEAMS.stream()
            .collect(Collectors.toUnmodifiableMap(DemoTeam::code, Function.identity()));
    private final Map<String, DemoArea> areaByCode = AREAS.stream()
            .collect(Collectors.toUnmodifiableMap(DemoArea::code, Function.identity()));
    private final Map<String, DemoArea> areaByName = AREAS.stream()
            .collect(Collectors.toUnmodifiableMap(DemoArea::name, Function.identity()));
    private final Map<String, DemoUser> userById = USERS.stream()
            .collect(Collectors.toUnmodifiableMap(DemoUser::id, Function.identity()));

    public List<DemoTeam> teams() {
        return TEAMS;
    }

    public List<DemoArea> areas() {
        return AREAS;
    }

    public List<DemoUser> users() {
        return USERS;
    }

    public String teamName(String code) {
        DemoTeam team = teamByCode.get(code);
        return team == null ? null : team.name();
    }

    public boolean isValidTeamCode(String code) {
        return code != null && teamByCode.containsKey(code);
    }

    public boolean isValidAreaName(String name) {
        return name != null && areaByName.containsKey(name);
    }

    public boolean isValidAreaCode(String code) {
        return code != null && areaByCode.containsKey(code);
    }

    public DemoUser user(String id) {
        return userById.get(id);
    }

    /**
     * 按中文姓名唯一匹配用户（仅用于旧客户端只传姓名的 Deprecated 兼容路径）。
     * 重名或查无此人返回 null，调用方应按 422 处理，不得自行猜测班组。
     */
    public DemoUser userByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        DemoUser found = null;
        for (DemoUser u : USERS) {
            if (name.equals(u.name())) {
                if (found != null) {
                    return null; // 重名，拒绝歧义匹配
                }
                found = u;
            }
        }
        return found;
    }

    /**
     * 解析责任人：优先用户 code（USR-xxx），其次唯一姓名；都不匹配返回 null。
     * 派单 / 转派的权威入口，Service 不得信任前端传入的姓名作为人员信息。
     */
    public DemoUser resolveUser(String userCode, String name) {
        if (userCode != null && !userCode.isBlank()) {
            DemoUser byId = userById.get(userCode);
            if (byId != null) {
                return byId;
            }
            // 兼容前端误把姓名放进 id 位的情况
            DemoUser byName = userByName(userCode);
            if (byName != null) {
                return byName;
            }
            return null;
        }
        return userByName(name);
    }
}
