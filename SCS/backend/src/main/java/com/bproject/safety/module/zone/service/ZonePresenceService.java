package com.bproject.safety.module.zone.service;

import com.bproject.safety.module.fence.model.DemoFence;
import com.bproject.safety.module.fence.model.FencePoint;
import com.bproject.safety.module.fence.model.FenceStatuses;
import com.bproject.safety.module.fence.repository.FenceRepository;
import com.bproject.safety.module.personnel.model.DemoPersonnel;
import com.bproject.safety.module.personnel.repository.PersonnelRepository;
import com.bproject.safety.module.personnel.service.PersonnelPositionService;
import com.bproject.safety.module.zone.model.ZonePresence;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 区域实时在场人数（首页"装卸区状态"卡的实时人员感知）。
 *
 * <p>固定作业区按 {@link PersonnelPositionService} 的矩形归属口径统计；
 * 已生效临时围栏按围栏多边形逐点判定（含隔离带缓冲人数）。人员名单走
 * 与 /personnel 相同的仓库读路径（scoped 会话所见与地图一致），
 * 实时坐标优先，未上报人员回落台账坐标。</p>
 */
@Service
public class ZonePresenceService {

    private final PersonnelRepository personnelRepository;
    private final FenceRepository fenceRepository;
    private final PersonnelPositionService positions;

    public ZonePresenceService(PersonnelRepository personnelRepository,
                               FenceRepository fenceRepository,
                               PersonnelPositionService positions) {
        this.personnelRepository = personnelRepository;
        this.fenceRepository = fenceRepository;
        this.positions = positions;
    }

    public List<ZonePresence> list() {
        var live = positions.liveSnapshot();
        List<DemoPersonnel> people = personnelRepository.findAll().stream()
                .filter(p -> !p.isDeleted)
                .toList();

        // 固定作业区：人员按实时坐标（回落台账坐标）归区计数
        Map<String, Integer> areaCounts = new HashMap<>();
        List<double[]> points = new ArrayList<>();
        for (DemoPersonnel person : people) {
            var pos = live.get(person.id);
            double x = pos != null ? pos.x() : person.x;
            double y = pos != null ? pos.y() : person.y;
            points.add(new double[]{x, y});
            areaCounts.merge(PersonnelPositionService.areaOf(x, y), 1, Integer::sum);
        }
        var result = new ArrayList<ZonePresence>();
        for (String area : PersonnelPositionService.areaNames()) {
            result.add(ZonePresence.fixed(area, areaCounts.getOrDefault(area, 0)));
        }

        // 已生效临时围栏：逐人多边形判定（核心区 + 隔离带）
        var polygons = new ArrayList<double[][]>();
        var buffers = new ArrayList<double[][]>();
        var fences = new ArrayList<DemoFence>();
        for (DemoFence fence : fenceRepository.findAll()) {
            if (!FenceStatuses.EFFECTIVE.equals(fence.statusCode)) {
                continue;
            }
            if (fence.polygon == null || fence.polygon.size() < 3) {
                continue;
            }
            fences.add(fence);
            polygons.add(toXY(fence.polygon));
            buffers.add(fence.bufferPolygon != null && fence.bufferPolygon.size() >= 3
                    ? toXY(fence.bufferPolygon) : null);
        }
        for (int i = 0; i < fences.size(); i++) {
            var polygon = polygons.get(i);
            var buffer = buffers.get(i);
            int inside = 0;
            int inBufferOnly = 0;
            for (double[] point : points) {
                boolean inPoly = pointInPolygon(point[0], point[1], polygon);
                if (inPoly) {
                    inside++;
                } else if (buffer != null && pointInPolygon(point[0], point[1], buffer)) {
                    inBufferOnly++;
                }
            }
            var fence = fences.get(i);
            result.add(ZonePresence.fence(fence.id, fence.name, inside, inBufferOnly,
                    FenceStatuses.label(fence.statusCode)));
        }
        return result;
    }

    private static double[][] toXY(List<FencePoint> polygon) {
        var xy = new double[polygon.size()][2];
        for (int i = 0; i < polygon.size(); i++) {
            xy[i][0] = polygon.get(i).x();
            xy[i][1] = polygon.get(i).y();
        }
        return xy;
    }

    /** 偶奇规则射线法（百分比坐标平面，顶点按序闭合）。 */
    static boolean pointInPolygon(double x, double y, double[][] polygon) {
        boolean inside = false;
        int n = polygon.length;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = polygon[i][0], yi = polygon[i][1];
            double xj = polygon[j][0], yj = polygon[j][1];
            boolean intersect = ((yi > y) != (yj > y))
                    && (x < (xj - xi) * (y - yi) / (yj - yi) + xi);
            if (intersect) {
                inside = !inside;
            }
        }
        return inside;
    }
}
