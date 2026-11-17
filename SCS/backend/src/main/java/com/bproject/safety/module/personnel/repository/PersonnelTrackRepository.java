package com.bproject.safety.module.personnel.repository;

import com.bproject.safety.module.personnel.model.TrackPoint;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 人员低频轨迹样本存储。
 *
 * <p>定位上报是 1Hz 实时量，不把原始点全部写关系库；服务按 10 秒降采样落一个点，
 * 供“指定时间范围的历史轨迹”查询。生产规模更大时应切换 openGemini 时序库，
 * 本接口保持存储无关，便于替换。</p>
 */
public interface PersonnelTrackRepository {

    void append(String personnelCode, double x, double y, String area, OffsetDateTime recordedAt);

    /** 按人员与时间范围查询，按时间升序，最多 limit 个点。 */
    List<TrackPoint> query(String personnelCode, OffsetDateTime from, OffsetDateTime to, int limit);

    /** 测试/演示复位用；生产实现可空操作。 */
    default void clear() {
    }
}
