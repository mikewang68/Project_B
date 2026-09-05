package com.bproject.safety.module.alert.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 告警证据联合类型，与前端 types/alert.ts 的 AlertEvidence 联合一一对应。
 * 本阶段只做序列化输出（后端 Demo 数据 → 前端），不做反序列化多态。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public sealed interface AlertEvidence
        permits AlertEvidence.PersonnelEvidence, AlertEvidence.CollisionEvidence,
                AlertEvidence.AiEvidence, AlertEvidence.MetricEvidence {

    /** 人员轨迹点（相对安全地图百分比坐标）。 */
    record Point(int x, int y) {
    }

    /** AI 检测框。 */
    record DetectionBox(String id, String label, double score, int x, int y, int w, int h, String tone) {
    }

    /** 指标项（设备/系统异常证据），tone ∈ ok/warn/danger。 */
    record MetricItem(String label, String value, String tone) {
    }

    /** 人员类证据。 */
    record PersonnelEvidence(String kind, List<Point> track, String currentPosition, String fence,
                             String band, String bandState, String heartRate) implements AlertEvidence {
        public static PersonnelEvidence of(List<Point> track, String currentPosition, String fence,
                                           String band, String bandState, String heartRate) {
            return new PersonnelEvidence("personnel", track, currentPosition, fence, band, bandState, heartRate);
        }
    }

    /** 设备防碰撞证据。 */
    record CollisionEvidence(String kind, double distance, double relSpeed, List<Double> trend,
                             String radar, String brakeDistance) implements AlertEvidence {
        public static CollisionEvidence of(double distance, double relSpeed, List<Double> trend,
                                           String radar, String brakeDistance) {
            return new CollisionEvidence("collision", distance, relSpeed, trend, radar, brakeDistance);
        }
    }

    /** AI 违规证据。 */
    record AiEvidence(String kind, String scene, List<DetectionBox> boxes, double confidence,
                      String model, String camera, String time) implements AlertEvidence {
        public static AiEvidence of(String scene, List<DetectionBox> boxes, double confidence,
                                    String model, String camera, String time) {
            return new AiEvidence("ai", scene, boxes, confidence, model, camera, time);
        }
    }

    /** 设备异常 / 系统异常证据（kind 为 device-metric 或 system-metric）。 */
    record MetricEvidence(String kind, List<MetricItem> metrics, String description) implements AlertEvidence {
        public static MetricEvidence device(List<MetricItem> metrics, String description) {
            return new MetricEvidence("device-metric", metrics, description);
        }

        public static MetricEvidence system(List<MetricItem> metrics, String description) {
            return new MetricEvidence("system-metric", metrics, description);
        }
    }
}
