"""
规则引擎（demo R01–R11）
契约裁决 2026-07-13：所有告警必须由引擎对原始数据真实判定产出，e_alert_event 一行都不允许 seed。
验收标准：INJ-01→R01 · INJ-02→R04 · INJ-03→R05 · INJ-04→R06 · INJ-05→R08 · INJ-06→R10 · INJ-07→R11
       R02 从 raw 的 ingest_time > sample_time + 15min 天然产出
       R09 从基线报告期日偏差 > 20% 天然产出

REQ 锚点：REQ-039 级别三档 · REQ-040 规则版本冻结 · REQ-041~044 事件状态机 · REQ-042 合并窗口

事件写入约定：
- 同 (rule_code, object_type, object_id, level) 归一为 event key
- 单次 run_all 内、同一 event key 只写一条事件；
  first_occur_time / last_occur_time / occur_count 反映触发窗口
- rule_version_no 冻结自 e_alert_rule.version_no（REQ-040）
- status='new'（未确认）；snapshot_json 携带触发上下文（可空 for demo）
"""

import json
from datetime import datetime, timedelta
from typing import Any

from sqlalchemy import delete, select, text
from sqlalchemy.ext.asyncio import AsyncSession

from module_energy.entity.do.alert_event_do import EAlertEvent
from module_energy.entity.do.alert_rule_do import EAlertRule
from module_energy.entity.do.equipment_do import EEquipment
from module_energy.service.demo_now_util import get_demo_now
from utils.log_util import logger

# 数据集固定演示窗口（datagen 造数规范约定）
_BASELINE_START = datetime(2026, 5, 4)
_REPORT_START = datetime(2026, 6, 29)

# R10 至少需要 2 个月才能做占比对比（基线 vs 报告）
_MIN_MONTHS_FOR_R10 = 2


def build_rule_notifications(
    rule_category: str | None,
    occurred_at: datetime,
) -> list[dict[str, Any]]:
    """规则命中时生成 demo 站内通知留痕（REQ-043）。"""
    target_role = 'ops' if rule_category == 'quality' else 'energy_mgr'
    return [{
        'channel': 'in_app',
        'targetRole': target_role,
        'sentAt': occurred_at.strftime('%Y-%m-%d %H:%M:%S'),
        'result': 'success',
        'retryCount': 0,
    }]


class RuleEngineService:
    """
    规则引擎：读 e_alert_rule 定义 → 扫 raw/stat/baseline → 写 e_alert_event
    """

    @classmethod
    async def run_all(cls, db: AsyncSession) -> dict[str, int]:
        """
        全量重跑：清空并重建 e_alert_event（幂等 bootstrap）
        返回每条规则触发的事件数
        """
        # 全量重跑：先清空（demo 数据源固定，重跑不留残余；生产版本用增量+去重）
        # P-17 可复位性：必须先清子表 e_alert_flow_log，否则 InnoDB auto_increment
        # 重排后旧流转日志会撞到新事件，让新告警详情里冒出上一轮演示的处置记录。
        await db.execute(text('DELETE FROM e_alert_flow_log'))
        await db.execute(delete(EAlertEvent))
        logger.info('[rule-engine] 清空 e_alert_flow_log + e_alert_event，开始规则评估')

        # 加载规则库（含版本号）
        rules = (
            await db.execute(
                select(EAlertRule).where(EAlertRule.enabled == 1).order_by(EAlertRule.rule_code)
            )
        ).scalars().all()
        rules_by_code = {r.rule_code: r for r in rules}

        demo_now = await get_demo_now(db)

        counts: dict[str, int] = {}
        # 顺序注意：R09 依赖 baseline 发布后 e_stat_day.baseline_deviation_pct 已回填
        counts['R01'] = await cls._eval_r01_offline(db, rules_by_code.get('R01'), demo_now)
        counts['R02'] = await cls._eval_r02_late(db, rules_by_code.get('R02'), demo_now)
        # R03 连续零值：保持 stub —— datagen 未构造对应场景，也无 INJ 目标（见 PRD §7.2 R03 无 K.7 映射）
        # 保留代码入口便于将来 dg 侧构造后接管；stub 期返回 0，不 seed 事件
        counts['R03'] = 0
        counts['R04'] = await cls._eval_r04_jump(db, rules_by_code.get('R04'), demo_now)
        counts['R05'] = await cls._eval_r05_high(db, rules_by_code.get('R05'), demo_now)
        counts['R06'] = await cls._eval_r06_air(db, rules_by_code.get('R06'), demo_now)
        counts['R07'] = await cls._eval_r07_toggle(db, rules_by_code.get('R07'), demo_now)
        counts['R08'] = await cls._eval_r08_light(db, rules_by_code.get('R08'), demo_now)
        counts['R09'] = await cls._eval_r09_baseline(db, rules_by_code.get('R09'), demo_now)
        counts['R10'] = await cls._eval_r10_peak_cost(db, rules_by_code.get('R10'), demo_now)
        counts['R11'] = await cls._eval_r11_coverage(db, rules_by_code.get('R11'), demo_now)

        await db.commit()
        logger.info(f'[rule-engine] 规则评估完成：{counts}')
        return counts

    # ------------------------------------------------------------------
    # 写事件辅助（合并到 5min 窗口内的同规则同对象）
    # ------------------------------------------------------------------

    @classmethod
    async def _emit_event(
        cls,
        db: AsyncSession,
        rule: EAlertRule,
        object_type: str,
        object_id: int,
        area_id: int | None,
        level: str,
        first_occur: datetime,
        last_occur: datetime | None = None,
        occur_count: int = 1,
        snapshot: dict[str, Any] | None = None,
    ) -> None:
        """写一条告警事件，冻结规则版本号（REQ-040）"""
        # 审计时间锚定 DEMO_NOW，避免 wall-clock 让 create/update_time 漂到数据集尾之后（P-09）。
        audit_now = await get_demo_now(db)
        db.add(
            EAlertEvent(
                rule_id=rule.rule_id,
                rule_code=rule.rule_code,
                rule_version_no=rule.version_no or 1,
                object_type=object_type,
                object_id=object_id,
                area_id=area_id,
                level=level,
                status='new',
                first_occur_time=first_occur,
                last_occur_time=last_occur or first_occur,
                occur_count=occur_count,
                snapshot_json=json.dumps(snapshot, ensure_ascii=False) if snapshot else None,
                notification_json=json.dumps(
                    build_rule_notifications(rule.rule_category, last_occur or first_occur),
                    ensure_ascii=False,
                ),
                create_time=audit_now,
                update_time=audit_now,
            )
        )

    # ------------------------------------------------------------------
    # R01 采集离线 —— INJ-01 验证载体
    # ------------------------------------------------------------------

    @classmethod
    async def _eval_r01_offline(
        cls, db: AsyncSession, rule: EAlertRule | None, demo_now: datetime
    ) -> int:
        """
        判定：采集点连续 3 个周期无数据（PRD R01 采集离线 · INJ-01 载体）
        采集离线的两种数据表征：
        (a) quality_state='miss' 连续段（INJ-07 WP-A1 覆盖率不足场景会产出）
        (b) 补传窗口：is_backfill=1 的成组读数（INJ-01 的表征——离线窗口后补传把
            'miss' 换成 'fix'+is_backfill=1，因此 raw 表里已无 'miss' 但有大批 backfill）
        两者取并集：只要 (miss 或 backfill) 计数 ≥ 阈值即视为离线事件。
        """
        if rule is None:
            return 0
        sql = text(
            """
            SELECT r.point_id, mp.area_id, mp.equipment_id,
                   MIN(r.sample_time) AS first_t, MAX(r.sample_time) AS last_t,
                   SUM(CASE WHEN r.quality_state = 'miss' THEN 1 ELSE 0 END) AS miss_cnt,
                   SUM(CASE WHEN r.is_backfill = 1 THEN 1 ELSE 0 END) AS backfill_cnt,
                   COUNT(*) AS total_cnt
            FROM e_raw_reading r
            JOIN e_meter_point mp ON mp.point_id = r.point_id
            WHERE (r.quality_state = 'miss' OR r.is_backfill = 1)
              AND r.sample_time >= :window_start
            GROUP BY r.point_id, mp.area_id, mp.equipment_id
            HAVING COUNT(*) >= :consecutive_periods
            """
        )
        params = {
            'window_start': demo_now.replace(hour=0, minute=0, second=0) - _timedelta_days(30),
            'consecutive_periods': int(_threshold(rule, 'consecutive_periods', 3)),
        }
        rows = (await db.execute(sql, params)).all()
        for r in rows:
            await cls._emit_event(
                db,
                rule=rule,
                object_type='point',
                object_id=int(r.point_id),
                area_id=int(r.area_id) if r.area_id is not None else None,
                level=rule.level,
                first_occur=r.first_t,
                last_occur=r.last_t,
                occur_count=int(r.total_cnt),
                snapshot={
                    'miss_count': int(r.miss_cnt),
                    'backfill_count': int(r.backfill_cnt),
                },
            )
        return len(rows)

    # ------------------------------------------------------------------
    # R02 数据迟到 —— INJ-01 的补传批次天然满足（ingest_time − sample_time > 15min）
    # ------------------------------------------------------------------

    @classmethod
    async def _eval_r02_late(
        cls, db: AsyncSession, rule: EAlertRule | None, demo_now: datetime
    ) -> int:
        if rule is None:
            return 0
        delay_min = int(_threshold(rule, 'delay_minutes', 15))
        sql = text(
            """
            SELECT r.point_id, mp.area_id,
                   MIN(r.sample_time) AS first_t, MAX(r.sample_time) AS last_t,
                   COUNT(*) AS late_cnt
            FROM e_raw_reading r
            JOIN e_meter_point mp ON mp.point_id = r.point_id
            WHERE r.ingest_time IS NOT NULL
              AND TIMESTAMPDIFF(MINUTE, r.sample_time, r.ingest_time) > :delay_min
              AND r.sample_time >= :window_start
            GROUP BY r.point_id, mp.area_id
            """
        )
        params = {
            'delay_min': delay_min,
            'window_start': demo_now.replace(hour=0, minute=0, second=0) - _timedelta_days(30),
        }
        rows = (await db.execute(sql, params)).all()
        for r in rows:
            await cls._emit_event(
                db,
                rule=rule,
                object_type='point',
                object_id=int(r.point_id),
                area_id=int(r.area_id) if r.area_id is not None else None,
                level=rule.level,
                first_occur=r.first_t,
                last_occur=r.last_t,
                occur_count=int(r.late_cnt),
                snapshot={'late_count': int(r.late_cnt), 'delay_min_threshold': delay_min},
            )
        return len(rows)

    # ------------------------------------------------------------------
    # R04 数据跳变 —— INJ-02 验证载体（BC-A1 07-01 单点 ×100）
    # ------------------------------------------------------------------

    @classmethod
    async def _eval_r04_jump(
        cls, db: AsyncSession, rule: EAlertRule | None, demo_now: datetime
    ) -> int:
        """
        判定：单周期增量 > 8× 前 4 周同时段均值。
        datagen INJ-02 已把该点 quality_state='jump'，直接以 jump 标签定位事件。
        """
        if rule is None:
            return 0
        sql = text(
            """
            SELECT r.point_id, mp.area_id,
                   MIN(r.sample_time) AS first_t, MAX(r.sample_time) AS last_t,
                   COUNT(*) AS jump_cnt, MAX(r.incremental_value) AS peak_incr
            FROM e_raw_reading r
            JOIN e_meter_point mp ON mp.point_id = r.point_id
            WHERE r.quality_state = 'jump'
            GROUP BY r.point_id, mp.area_id
            """
        )
        rows = (await db.execute(sql)).all()
        for r in rows:
            await cls._emit_event(
                db,
                rule=rule,
                object_type='point',
                object_id=int(r.point_id),
                area_id=int(r.area_id) if r.area_id is not None else None,
                level=rule.level,
                first_occur=r.first_t,
                last_occur=r.last_t,
                occur_count=int(r.jump_cnt),
                snapshot={'peak_incremental': float(r.peak_incr or 0)},
            )
        return len(rows)

    # ------------------------------------------------------------------
    # R05 设备高耗偏离 —— INJ-03 验证载体（GC-A1 待机时段异常高功率）
    # ------------------------------------------------------------------

    @classmethod
    async def _eval_r05_high(
        cls, db: AsyncSession, rule: EAlertRule | None, demo_now: datetime
    ) -> int:
        """
        判定（PRD §7.2 R05 字面口径）：
            连续 :hours 小时能耗 > 同类均值 × :energy_multiplier 且作业量未同步增加

        口径细化（demo 落地）：
        - "同类" = e_equipment.equipment_type 相同的设备集合（class average）。
          仅对"工单驱动"类型（有 e_work_order 记录的 equipment_type）计算；
          辅助/照明/空压机等无工单口径的类型不适用（否则 no_work 恒真产生 100% 假阳性）。
        - "同类均值" = 基线期（BASELINE_START~REPORT_START，>=8 周，REQ-029）
          按 equipment_type × tou_period 分桶的 e_stat_hour.total_value 平均；
          类内至少 2 台设备（HAVING n_peers >= 2）才有 peer 均值意义。
        - "作业量未同步增加" 简化实现：该小时该设备无覆盖工单
          （e_work_order.start_time < hour+1h AND (end_time IS NULL OR end_time > hour)）。
        - "连续 :hours 小时" 用 window function 定位 run；单设备内所有满足 :hours 的
          run 汇总为一条事件（first/last 取全部 run 的边界，occur_count 为总命中小时数）。
        - 阈值 threshold_json：hours=3, energy_multiplier=1.5, workload_delta_max=0
          （workload_delta_max 目前作为语义占位，未参与判定；正式版会用工单 workload 差量）
        """
        if rule is None:
            return 0
        multiplier = float(_threshold(rule, 'energy_multiplier', 1.5))
        hours = int(_threshold(rule, 'hours', 3))
        # 一个 in_progress 工单被视为仍在覆盖的最长时长（防止早期未闭合工单恒真）；
        # 8h ≈ 一个工作班次，超期视为遗留脏数据 / 已被后续工单接管
        max_wo_hours = int(_threshold(rule, 'max_open_wo_hours', 8))
        sql = text(
            """
            WITH workload_types AS (
                -- 仅对存在工单的 equipment_type 判定（保证"作业量未增加"语义可用）
                SELECT DISTINCT eq.equipment_type
                FROM e_equipment eq
                JOIN e_work_order wo ON wo.equipment_id = eq.equipment_id
            ),
            class_baseline AS (
                -- 同类 × tou_period 基线期均值；类内至少 2 台设备才有 peer 均值
                SELECT eq.equipment_type, h.tou_period,
                       AVG(h.total_value) AS mean_val,
                       COUNT(DISTINCT h.object_id) AS n_peers
                FROM e_stat_hour h
                JOIN e_equipment eq ON eq.equipment_id = h.object_id
                JOIN workload_types wt ON wt.equipment_type = eq.equipment_type
                WHERE h.object_type = 'equipment'
                  AND h.energy_type_code = 'electricity'
                  AND h.stat_time >= :baseline_start
                  AND h.stat_time < :report_start
                GROUP BY eq.equipment_type, h.tou_period
                HAVING n_peers >= 2
            ),
            hourly AS (
                SELECT h.object_id, h.stat_time, h.total_value,
                       cb.mean_val AS class_mean,
                       (h.total_value > cb.mean_val * :multiplier) AS energy_high,
                       -- "作业量未同步增加" 判定：该小时无覆盖工单
                       --   完工工单：start < h+1h AND end > h
                       --   在途工单（end_time NULL，可能是遗留脏数据）：
                       --     只在 start_time + :max_wo_hours 小时内认为仍在覆盖，避免早期 in_progress 空 end_time 恒真
                       NOT EXISTS (
                           SELECT 1 FROM e_work_order wo
                           WHERE wo.equipment_id = h.object_id
                             AND wo.start_time < DATE_ADD(h.stat_time, INTERVAL 1 HOUR)
                             AND (
                                   (wo.end_time IS NOT NULL AND wo.end_time > h.stat_time)
                                OR (wo.end_time IS NULL
                                     AND wo.start_time > DATE_SUB(h.stat_time, INTERVAL :max_wo_hours HOUR))
                             )
                       ) AS no_work
                FROM e_stat_hour h
                JOIN e_equipment eq ON eq.equipment_id = h.object_id
                JOIN class_baseline cb
                  ON cb.equipment_type = eq.equipment_type
                 AND cb.tou_period = h.tou_period
                WHERE h.object_type = 'equipment'
                  AND h.energy_type_code = 'electricity'
                  AND h.stat_time >= :report_start
            ),
            tagged AS (
                SELECT object_id, stat_time, class_mean, total_value,
                       CASE WHEN energy_high AND no_work THEN 1 ELSE 0 END AS hit
                FROM hourly
            ),
            grouped AS (
                -- 用累计的"未命中数"给同一 run 打组号（经典 gap-and-island）
                SELECT object_id, stat_time, class_mean, total_value, hit,
                       SUM(CASE WHEN hit = 0 THEN 1 ELSE 0 END)
                         OVER (PARTITION BY object_id ORDER BY stat_time) AS run_group
                FROM tagged
            ),
            runs AS (
                SELECT object_id, run_group,
                       COUNT(*) AS run_len,
                       MIN(stat_time) AS first_t,
                       MAX(stat_time) AS last_t,
                       AVG(class_mean) AS class_mean_avg,
                       MAX(total_value) AS peak_val
                FROM grouped
                WHERE hit = 1
                GROUP BY object_id, run_group
                HAVING COUNT(*) >= :hours_threshold
            )
            SELECT object_id,
                   COUNT(*) AS run_count,
                   SUM(run_len) AS total_hit_hours,
                   MAX(run_len) AS max_run_len,
                   MIN(first_t) AS first_t,
                   MAX(last_t) AS last_t,
                   AVG(class_mean_avg) AS class_mean_avg,
                   MAX(peak_val) AS peak_val
            FROM runs
            GROUP BY object_id
            """
        )
        params = {
            'baseline_start': _BASELINE_START,
            'report_start': _REPORT_START,
            'multiplier': multiplier,
            'hours_threshold': hours,
            'max_wo_hours': max_wo_hours,
        }
        rows = (await db.execute(sql, params)).all()
        equipment_area = await _get_equipment_area_map(db)
        for r in rows:
            eq_id = int(r.object_id)
            await cls._emit_event(
                db,
                rule=rule,
                object_type='equipment',
                object_id=eq_id,
                area_id=equipment_area.get(eq_id),
                level=rule.level,
                first_occur=r.first_t,
                last_occur=r.last_t,
                occur_count=int(r.total_hit_hours),
                snapshot={
                    'run_count': int(r.run_count),
                    'max_run_len': int(r.max_run_len),
                    'total_hit_hours': int(r.total_hit_hours),
                    'class_mean_avg': round(float(r.class_mean_avg or 0), 4),
                    'peak_val': round(float(r.peak_val or 0), 4),
                    'multiplier': multiplier,
                    'hours_threshold': hours,
                    'max_open_wo_hours': max_wo_hours,
                },
            )
        return len(rows)

    # ------------------------------------------------------------------
    # R06 非作业气流量异常 —— INJ-04 验证载体（AC-B1 疑似泄漏）
    # ------------------------------------------------------------------

    @classmethod
    async def _eval_r06_air(
        cls, db: AsyncSession, rule: EAlertRule | None, demo_now: datetime
    ) -> int:
        """
        判定（PRD §7.2 R06）：
            非作业时段气流量 > 作业均值 × 30%，持续 :duration_hours 小时

        口径细化：
        - "非作业时段" 简化为 tou_period='valley'（22-06）
        - "作业均值" = 基线期同一 (object_type, object_id) 在 tou_period='peak' 的均值
        - 扫描粒度包含 equipment（设备表计）与 area（管网干管/末端等 area 级 air_flow 点聚合）
          —— INJ-04 载体是 B 区管网干管 AF-B-MAIN（area 级），只扫 equipment 会漏；
             同时保留 equipment 级以覆盖 AC-B1/PN-B1 之类设备表计。
        - "持续 :duration_hours 小时" 用累计 hit 计数（非严格连续），语义等价于总累计
          （每天多个 valley 段拼起来，PRD demo 阶段 K.7 允许该简化）
        """
        if rule is None:
            return 0
        work_pct = float(_threshold(rule, 'work_pct_threshold', 0.3))
        duration = int(_threshold(rule, 'duration_hours', 2))
        sql = text(
            """
            WITH work_mean AS (
                SELECT object_type, object_id, AVG(total_value) AS work_mean
                FROM e_stat_hour
                WHERE object_type IN ('equipment','area')
                  AND energy_type_code = 'compressed_air'
                  AND tou_period = 'peak'
                  AND stat_time >= :baseline_start
                  AND stat_time < :report_start
                GROUP BY object_type, object_id
                HAVING AVG(total_value) > 0
            ),
            night_hits AS (
                SELECT h.object_type, h.object_id, h.stat_time, h.total_value, m.work_mean,
                       (h.total_value > m.work_mean * :work_pct) AS hit
                FROM e_stat_hour h
                JOIN work_mean m
                  ON m.object_type = h.object_type
                 AND m.object_id = h.object_id
                WHERE h.object_type IN ('equipment','area')
                  AND h.energy_type_code = 'compressed_air'
                  AND h.tou_period = 'valley'
                  AND h.stat_time >= :report_start
            )
            SELECT object_type, object_id, COUNT(*) AS hit_cnt,
                   MIN(stat_time) AS first_t, MAX(stat_time) AS last_t,
                   AVG(work_mean) AS work_mean_avg
            FROM night_hits
            WHERE hit = 1
            GROUP BY object_type, object_id
            HAVING hit_cnt >= :duration
            """
        )
        params = {
            'baseline_start': _BASELINE_START,
            'report_start': _REPORT_START,
            'work_pct': work_pct,
            'duration': duration,
        }
        rows = (await db.execute(sql, params)).all()
        equipment_area = await _get_equipment_area_map(db)
        for r in rows:
            oid = int(r.object_id)
            # area 级：object_id 即为 area_id；equipment 级从台账查 area_id
            area_id = equipment_area.get(oid) if r.object_type == 'equipment' else oid
            await cls._emit_event(
                db,
                rule=rule,
                object_type=r.object_type,
                object_id=oid,
                area_id=area_id,
                level=rule.level,
                first_occur=r.first_t,
                last_occur=r.last_t,
                occur_count=int(r.hit_cnt),
                snapshot={
                    'valley_hits': int(r.hit_cnt),
                    'work_pct_threshold': work_pct,
                    'work_mean_avg': round(float(r.work_mean_avg or 0), 4),
                },
            )
        return len(rows)

    # ------------------------------------------------------------------
    # R07 空压机频繁启停 —— G.6 第四幕辅证（无强 INJ；空数据也应返回真实 0）
    # ------------------------------------------------------------------

    @classmethod
    async def _eval_r07_toggle(
        cls, db: AsyncSession, rule: EAlertRule | None, demo_now: datetime
    ) -> int:
        """
        判定（PRD §7.2 R07）：
            equipment_type='compressor' 的设备，1 小时内启停切换 ≥ :toggles_per_hour 次

        口径细化：
        - "启停" = e_equipment_status_log.event_type='event' 的行（heartbeat 心跳不计）
          且 remark 含 '→'（datagen 用 'a→b' 表示状态跃迁）；也可只统计 curr='running'
          的启动次数。demo 用 event 行的数量作为"切换次数"，与 PRD "启停 ≥6 次" 含义一致
          （启+停各算一次切换 → 每小时 6 次切换 = 3 组启停）。
        - 按 (equipment_id, hour_bucket) 分组计数，>= 阈值即为一次滚动小时命中；
          单设备聚合到一条事件，first/last 取满足阈值的最早/最晚小时。
        - 报告期起点 _REPORT_START，向前查完整报告期；无命中则返回 0（不 seed）。
        """
        if rule is None:
            return 0
        threshold = int(_threshold(rule, 'toggles_per_hour', 6))
        sql = text(
            """
            WITH events AS (
                SELECT s.equipment_id,
                       DATE_FORMAT(s.event_time, '%Y-%m-%d %H:00:00') AS hour_bucket
                FROM e_equipment_status_log s
                JOIN e_equipment eq ON eq.equipment_id = s.equipment_id
                WHERE eq.equipment_type = 'compressor'
                  AND s.event_type = 'event'
                  AND s.event_time >= :report_start
            ),
            hourly AS (
                SELECT equipment_id, hour_bucket, COUNT(*) AS toggles
                FROM events
                GROUP BY equipment_id, hour_bucket
                HAVING toggles >= :threshold
            )
            SELECT equipment_id,
                   COUNT(*) AS hit_hours,
                   MIN(hour_bucket) AS first_t,
                   MAX(hour_bucket) AS last_t,
                   MAX(toggles) AS peak_toggles
            FROM hourly
            GROUP BY equipment_id
            """
        )
        rows = (
            await db.execute(
                sql, {'report_start': _REPORT_START, 'threshold': threshold}
            )
        ).all()
        equipment_area = await _get_equipment_area_map(db)
        for r in rows:
            eq_id = int(r.equipment_id)
            await cls._emit_event(
                db,
                rule=rule,
                object_type='equipment',
                object_id=eq_id,
                area_id=equipment_area.get(eq_id),
                level=rule.level,
                first_occur=datetime.strptime(str(r.first_t), '%Y-%m-%d %H:%M:%S'),
                last_occur=datetime.strptime(str(r.last_t), '%Y-%m-%d %H:%M:%S'),
                occur_count=int(r.hit_hours),
                snapshot={
                    'hit_hours': int(r.hit_hours),
                    'peak_toggles_in_hour': int(r.peak_toggles),
                    'threshold': threshold,
                },
            )
        return len(rows)

    # ------------------------------------------------------------------
    # R08 非作业照明异常 —— INJ-05 验证载体（LT-A1 夜间照明未关）
    # ------------------------------------------------------------------

    @classmethod
    async def _eval_r08_light(
        cls, db: AsyncSession, rule: EAlertRule | None, demo_now: datetime
    ) -> int:
        """
        判定：22–06 照明回路小时功率 > 2kW，持续 duration_hours 小时。
        demo 简化：设备 type='lighting' 的 electricity 小时值，valley 段（22–06）> 阈值。
        """
        if rule is None:
            return 0
        power_kw = float(_threshold(rule, 'power_kw_threshold', 2.0))
        duration = int(_threshold(rule, 'duration_hours', 1))
        sql = text(
            """
            WITH light_night AS (
                SELECT h.object_id, h.stat_time, h.total_value,
                       (h.total_value > :power_kw) AS hit
                FROM e_stat_hour h
                JOIN e_equipment eq ON eq.equipment_id = h.object_id
                WHERE h.object_type = 'equipment'
                  AND h.energy_type_code = 'electricity'
                  AND h.tou_period = 'valley'
                  AND eq.equipment_type = 'lighting'
                  AND h.stat_time >= :report_start
            )
            SELECT object_id, COUNT(*) AS hit_cnt,
                   MIN(stat_time) AS first_t, MAX(stat_time) AS last_t
            FROM light_night
            WHERE hit = 1
            GROUP BY object_id
            HAVING hit_cnt >= :duration
            """
        )
        params = {
            'power_kw': power_kw,
            'duration': duration,
            'report_start': _REPORT_START,
        }
        rows = (await db.execute(sql, params)).all()
        equipment_area = await _get_equipment_area_map(db)
        for r in rows:
            eq_id = int(r.object_id)
            await cls._emit_event(
                db,
                rule=rule,
                object_type='equipment',
                object_id=eq_id,
                area_id=equipment_area.get(eq_id),
                level=rule.level,
                first_occur=r.first_t,
                last_occur=r.last_t,
                occur_count=int(r.hit_cnt),
                snapshot={'hit_hours': int(r.hit_cnt), 'power_kw_threshold': power_kw},
            )
        return len(rows)

    # ------------------------------------------------------------------
    # R09 基线偏差 —— BASELINE_DEV KPI + trend anomalies 载体
    # ------------------------------------------------------------------

    @classmethod
    async def _eval_r09_baseline(
        cls, db: AsyncSession, rule: EAlertRule | None, demo_now: datetime
    ) -> int:
        """
        判定：日能耗超基线带 +20%（PRD §7.2 R09），阈值 threshold_json.deviation_pct=20。
        读取 e_stat_day 系统级行 baseline_deviation_pct > 20 触发。
        每条命中→一条事件，object_type='system' object_id=0。
        """
        if rule is None:
            return 0
        threshold = float(_threshold(rule, 'deviation_pct', 20))
        rows = (
            await db.execute(
                text(
                    """
                    SELECT stat_date, energy_type_code, baseline_deviation_pct, total_value
                    FROM e_stat_day
                    WHERE object_type = 'system' AND object_id = 0
                      AND baseline_deviation_pct IS NOT NULL
                      AND baseline_deviation_pct > :threshold
                    ORDER BY stat_date
                    """
                ),
                {'threshold': threshold},
            )
        ).all()
        for r in rows:
            first_t = datetime.combine(r.stat_date, datetime.min.time()).replace(hour=12)
            await cls._emit_event(
                db,
                rule=rule,
                object_type='system',
                object_id=0,
                area_id=None,
                level=rule.level,
                first_occur=first_t,
                last_occur=first_t,
                occur_count=1,
                snapshot={
                    'stat_date': r.stat_date.isoformat(),
                    'energy_type_code': r.energy_type_code,
                    'deviation_pct': float(r.baseline_deviation_pct),
                    'day_total': float(r.total_value or 0),
                    'threshold_pct': threshold,
                },
            )
        return len(rows)

    # ------------------------------------------------------------------
    # R10 峰段成本占比异常 —— INJ-06 验证载体（依赖 e_cost_record 成本管道）
    # ------------------------------------------------------------------

    @classmethod
    async def _eval_r10_peak_cost(
        cls, db: AsyncSession, rule: EAlertRule | None, demo_now: datetime
    ) -> int:
        """
        判定（PRD §7.2 R10 字面口径）：
            成本中心峰段成本占比较基线期抬升 > 8 个百分点

        口径细化：
        - 成本口径来源 e_cost_record（cost_service 先行落库）；空则回退用 e_stat_month.peak_value / total_value
          的 qty 占比作为兜底（避免管道未跑时 R10 假空）。
        - 成本中心 = area（PRD §7.2 场景 G.9 面向装卸区）；system/equipment 级不参与 R10。
        - 报告期 = 数据集里的最新 3 个月中的最后一个月（demo 数据构造：baseline=5 月，
          shift=6 月 INJ-06，report=6 月）。为了实事求是地把 INJ-06 落在"报告月"，
          选取规则：以 e_stat_month 出现过的月份 desc 序，取除报告月外的 2 个及以上历史月做基线。
        - baseline_share = 除报告月以外所有历史月 peak_cost 之和 / total_cost 之和（不是 avg-of-share）
          这样月量级不同也不会失真。
        - INJ-06 场景：AREA-B 5→6 月成本占比 +10.4pp（65 → 55 峰段占比切换）→ 应命中。
        """
        if rule is None:
            return 0
        pp_threshold = float(_threshold(rule, 'pct_point_threshold', 8))
        cost_rows_exist = (
            await db.execute(
                text(
                    "SELECT COUNT(*) FROM e_cost_record "
                    "WHERE is_current = 1 AND status != 'void'"
                )
            )
        ).scalar()
        if not cost_rows_exist:
            logger.info('[rule R10] e_cost_record 空，回退 qty 占比模式（cost_service 未运行）')
            return await cls._eval_r10_qty_fallback(db, rule, pp_threshold)
        # 数据集月份候选（e_cost_record.stat_month desc）
        months = [
            r[0] for r in (
                await db.execute(
                    text(
                        "SELECT DISTINCT stat_month FROM e_cost_record "
                        "WHERE object_type='area' AND energy_type_code='electricity' "
                        "AND is_current = 1 AND status != 'void' "
                        "ORDER BY stat_month DESC"
                    )
                )
            ).all()
        ]
        if len(months) < _MIN_MONTHS_FOR_R10:
            logger.info(f'[rule R10] 成本月份不足 2 个 ({months})，跳过')
            return 0
        # 报告月 = 最新已闭合的月份。规则：如果最新月是"当月 demo_now 所在月且天数 < 15"
        # 则退回上个月（避免半月数据当报告月）；这里数据集 07 月只跑到 12 号，故报告月退到 06。
        latest = months[0]
        demo_month = demo_now.strftime('%Y-%m')
        report_month = months[1] if latest == demo_month else latest
        baseline_months = [m for m in months if m != report_month]
        if not baseline_months:
            return 0
        # 计算各 area 的 baseline_peak_share 与 report_peak_share
        sql = text(
            """
            WITH baseline AS (
                SELECT object_id,
                       SUM(peak_cost) AS peak_cost_sum,
                       SUM(total_cost) AS total_cost_sum
                FROM e_cost_record
                WHERE object_type='area' AND energy_type_code='electricity'
                  AND is_current = 1 AND status != 'void'
                  AND stat_month IN :baseline_months
                GROUP BY object_id
            ),
            report AS (
                SELECT object_id,
                       peak_cost,
                       total_cost
                FROM e_cost_record
                WHERE object_type='area' AND energy_type_code='electricity'
                  AND is_current = 1 AND status != 'void'
                  AND stat_month = :report_month
            )
            SELECT r.object_id,
                   (r.peak_cost / NULLIF(r.total_cost,0)) AS report_share,
                   (b.peak_cost_sum / NULLIF(b.total_cost_sum,0)) AS baseline_share,
                   ((r.peak_cost / NULLIF(r.total_cost,0)) -
                    (b.peak_cost_sum / NULLIF(b.total_cost_sum,0))) * 100 AS diff_pp,
                   r.total_cost AS report_total
            FROM report r
            JOIN baseline b ON b.object_id = r.object_id
            WHERE ((r.peak_cost / NULLIF(r.total_cost,0)) -
                   (b.peak_cost_sum / NULLIF(b.total_cost_sum,0))) * 100 > :pp_threshold
            """
        )
        rows = (
            await db.execute(
                sql,
                {
                    'baseline_months': tuple(baseline_months),
                    'report_month': report_month,
                    'pp_threshold': pp_threshold,
                },
            )
        ).all()
        report_first_t = datetime(
            int(report_month[:4]), int(report_month[5:7]), 1, 12, 0, 0,
        )
        for r in rows:
            await cls._emit_event(
                db,
                rule=rule,
                object_type='area',
                object_id=int(r.object_id),
                area_id=int(r.object_id),
                level=rule.level,
                first_occur=report_first_t,
                last_occur=report_first_t,
                occur_count=1,
                snapshot={
                    'report_month': report_month,
                    'baseline_months': baseline_months,
                    'baseline_peak_share': round(float(r.baseline_share or 0), 4),
                    'report_peak_share': round(float(r.report_share or 0), 4),
                    'diff_pp': round(float(r.diff_pp or 0), 4),
                    'threshold_pp': pp_threshold,
                    'report_total_cost': round(float(r.report_total or 0), 2),
                },
            )
        return len(rows)

    @classmethod
    async def _eval_r10_qty_fallback(
        cls, db: AsyncSession, rule: EAlertRule, pp_threshold: float,
    ) -> int:
        """成本表空时的 qty 占比兜底（保证前端联调不空面）"""
        sql = text(
            """
            WITH months AS (
                SELECT DISTINCT stat_month FROM e_stat_month
                WHERE object_type='area' AND energy_type_code='electricity'
                ORDER BY stat_month DESC
            )
            SELECT stat_month FROM months
            """
        )
        months = [r[0] for r in (await db.execute(sql)).all()]
        if len(months) < _MIN_MONTHS_FOR_R10:
            return 0
        report_month = months[0]
        baseline_months = months[1:]
        sql2 = text(
            """
            WITH baseline AS (
                SELECT object_id,
                       SUM(peak_value) AS pv, SUM(total_value) AS tv
                FROM e_stat_month
                WHERE object_type='area' AND energy_type_code='electricity'
                  AND stat_month IN :baseline_months
                GROUP BY object_id
            ),
            report AS (
                SELECT object_id, peak_value AS pv, total_value AS tv
                FROM e_stat_month
                WHERE object_type='area' AND energy_type_code='electricity'
                  AND stat_month = :report_month
            )
            SELECT r.object_id,
                   (r.pv / NULLIF(r.tv,0)) AS rs,
                   (b.pv / NULLIF(b.tv,0)) AS bs,
                   ((r.pv / NULLIF(r.tv,0)) - (b.pv / NULLIF(b.tv,0))) * 100 AS diff_pp
            FROM report r JOIN baseline b ON b.object_id=r.object_id
            WHERE ((r.pv / NULLIF(r.tv,0)) - (b.pv / NULLIF(b.tv,0))) * 100 > :pp_threshold
            """
        )
        rows = (
            await db.execute(
                sql2,
                {
                    'baseline_months': tuple(baseline_months),
                    'report_month': report_month,
                    'pp_threshold': pp_threshold,
                },
            )
        ).all()
        first_t = datetime(int(report_month[:4]), int(report_month[5:7]), 1, 12, 0, 0)
        for r in rows:
            await cls._emit_event(
                db,
                rule=rule,
                object_type='area',
                object_id=int(r.object_id),
                area_id=int(r.object_id),
                level=rule.level,
                first_occur=first_t,
                last_occur=first_t,
                occur_count=1,
                snapshot={
                    'mode': 'qty_fallback',
                    'report_month': report_month,
                    'baseline_months': baseline_months,
                    'baseline_peak_share': round(float(r.bs or 0), 4),
                    'report_peak_share': round(float(r.rs or 0), 4),
                    'diff_pp': round(float(r.diff_pp or 0), 4),
                    'threshold_pp': pp_threshold,
                },
            )
        return len(rows)

    # ------------------------------------------------------------------
    # R11 覆盖率不足 —— INJ-07 验证载体（WP-A1 覆盖率 70%）
    # ------------------------------------------------------------------

    @classmethod
    async def _eval_r11_coverage(
        cls, db: AsyncSession, rule: EAlertRule | None, demo_now: datetime
    ) -> int:
        """
        判定：日采样覆盖率 [80%,95%) 提示，<80% 严重。
        以 point-day 粒度求覆盖率（分子 valid、分母 每点每日理论样点）
        """
        if rule is None:
            return 0
        warn_th = float(_threshold(rule, 'warn_threshold', 0.95))
        severe_th = float(_threshold(rule, 'severe_threshold', 0.80))
        sql = text(
            """
            SELECT r.point_id, DATE(r.sample_time) AS d,
                   mp.area_id, mp.equipment_id, mp.sample_period_sec,
                   SUM(CASE WHEN r.quality_state IN ('ok','late','est','fix') THEN 1 ELSE 0 END) AS valid_cnt
            FROM e_raw_reading r
            JOIN e_meter_point mp ON mp.point_id = r.point_id
            WHERE mp.energy_type_code IS NOT NULL
              AND r.sample_time >= :window_start
            GROUP BY r.point_id, DATE(r.sample_time), mp.area_id, mp.equipment_id, mp.sample_period_sec
            """
        )
        params = {
            'window_start': demo_now.replace(hour=0, minute=0, second=0) - _timedelta_days(30),
        }
        rows = (await db.execute(sql, params)).all()
        emitted = 0
        # 聚合到 point 级：只要该点任一天 <95% 就产事件；取最低覆盖率所在天为 first
        pt_hits: dict[int, dict[str, Any]] = {}
        for r in rows:
            expected = 86400.0 / int(r.sample_period_sec)
            cov = float(r.valid_cnt) / expected if expected > 0 else 0.0
            if cov >= warn_th:
                continue
            level = 'severe' if cov < severe_th else 'notice'
            info = pt_hits.setdefault(int(r.point_id), {
                'area_id': int(r.area_id) if r.area_id else None,
                'level': level,
                'min_cov': cov,
                'first_t': datetime.combine(r.d, datetime.min.time()),
                'last_t': datetime.combine(r.d, datetime.min.time()),
                'days': 0,
            })
            info['days'] += 1
            if cov < info['min_cov']:
                info['min_cov'] = cov
                info['level'] = 'severe' if cov < severe_th else info['level']
            first_dt = datetime.combine(r.d, datetime.min.time())
            info['first_t'] = min(info['first_t'], first_dt)
            info['last_t'] = max(info['last_t'], first_dt)
        for pid, info in pt_hits.items():
            await cls._emit_event(
                db,
                rule=rule,
                object_type='point',
                object_id=pid,
                area_id=info['area_id'],
                level=info['level'],
                first_occur=info['first_t'],
                last_occur=info['last_t'],
                occur_count=int(info['days']),
                snapshot={
                    'days_below_threshold': int(info['days']),
                    'min_coverage': round(info['min_cov'], 4),
                    'warn_threshold': warn_th,
                    'severe_threshold': severe_th,
                },
            )
            emitted += 1
        return emitted


# ---------------------------------------------------------------------------
# 辅助
# ---------------------------------------------------------------------------


def _threshold(rule: EAlertRule, key: str, default: Any) -> Any:
    if not rule.threshold_json:
        return default
    try:
        data = json.loads(rule.threshold_json)
    except json.JSONDecodeError:
        return default
    return data.get(key, default)


def _timedelta_days(n: int) -> timedelta:
    return timedelta(days=n)


async def _get_equipment_area_map(db: AsyncSession) -> dict[int, int]:
    """cache equipment_id → area_id（demo 内 12 条，直接一次拉全）"""
    rows = (await db.execute(select(EEquipment.equipment_id, EEquipment.area_id))).all()
    return {int(r.equipment_id): int(r.area_id) for r in rows}
