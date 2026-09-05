"""
统计聚合管道（e_stat_hour / e_stat_day / e_stat_month）
REQ 锚点：REQ-024~028（多维统计）· REQ-019（覆盖率）· REQ-020（重算标记 + version_no 递增）· REQ-053（月度 Top）
契约：docs/mock-contracts.md 全局约定 2026-07-13

设计要点：
- 三级对象：equipment（设备）+ area（装卸区）；system 级留给报表页
- 三级时间：小时/日/月；重算通过 ON DUPLICATE KEY UPDATE + version_no+1
- 质量口径（REQ-019）：ok/late/est/fix 计入合格样点；miss/dup/jump/frozen 不计
- 覆盖率（PRD §7.3）：有效采样点 ÷ 理论采样点；理论 = 3600/sample_period_sec × 点数
- 峰平谷（PRD §7.1）：peak 08–11、18–21；valley 22–06；其余 flat
- 全表 UPSERT 支持任意重跑（幂等）；bootstrap 一次即可全量刷 10 周历史
"""

from datetime import date, datetime, timedelta
from typing import Any

from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession

from utils.log_util import logger

# 合格质量口径（REQ-019 附录 C）——进正式统计与覆盖率分子
_VALID_QUALITY_CODES: tuple[str, ...] = ('ok', 'late', 'est', 'fix')

# 峰平谷时段（PRD §7.1，REQ-027 演示配置）
_PEAK_HOURS: tuple[int, ...] = (8, 9, 10, 18, 19, 20)
_VALLEY_HOURS: tuple[int, ...] = (22, 23, 0, 1, 2, 3, 4, 5)

# ---------------------------------------------------------------------------
# 计量层级口径（team-lead 2026-07-13 裁决：严禁跨层级相加）
#
# - equipment 级 total = device_meter / equipment-attached air_flow / equipment-attached water
#   （其余分类不进 equipment 级）——通过 p.equipment_id IS NOT NULL 天然过滤，无需额外分类
# - area 级 total = 每 (area, energy_type) 一个"区总表口径"读数：
#     electricity   → point_category = 'area_meter'
#     compressed_air → equipment_id IS NULL AND (point_code LIKE '%-MAIN' OR '%-BRANCH')
#                       —— AF-A-BRANCH 是 A 区从 B 区分出的支线（A 无独立空压站），
#                          AF-B-MAIN 是 B 区主管；AF-B-TERMINAL 是同一路的末端读数，
#                          与 MAIN 会重复计量，不进 area 总
#     water         → equipment_id IS NULL AND point_code LIKE '%-MAIN'
#                       —— AREA-A-W-LIFE 生活水口径独立不并入区总
# - branch_meter 保留作分项拆解维度，不进任何层级 total（team-lead 裁决 2026-07-13）
# - system 级 = area 级之和（由于 area 已收敛到单表口径，system 相加不重复）
# ---------------------------------------------------------------------------
_AREA_MAIN_METER_SQL = """
(
    (p.energy_type_code = 'electricity' AND p.point_category = 'area_meter')
    OR (p.energy_type_code = 'compressed_air' AND p.equipment_id IS NULL
        AND (p.point_code LIKE '%-MAIN' OR p.point_code LIKE '%-BRANCH'))
    OR (p.energy_type_code = 'water' AND p.equipment_id IS NULL
        AND p.point_code LIKE '%-MAIN')
)
"""


class AggregationService:
    """
    统计聚合服务：raw → hour → day → month，equipment / area 两级对象

    使用方式：
        await AggregationService.rebuild_all(db)       # 全量重跑（bootstrap）
        await AggregationService.rebuild_range(db, start, end)  # 增量重跑指定时间窗
    """

    # ------------------------------------------------------------------
    # 对外主入口
    # ------------------------------------------------------------------

    @classmethod
    async def rebuild_all(cls, db: AsyncSession) -> dict[str, int]:
        """
        全量重跑：清空聚合表 → 读取全部 e_raw_reading → 重建 hour/day/month × equipment/area/system
        用于 bootstrap（首次或数据集刷新后调用）；先清后建避免口径变化后残留旧行
        """
        logger.info('[aggregation] 开始全量重跑 stat_hour/stat_day/stat_month')
        # 清空聚合表（TRUNCATE 幂等，无残留 stale key；team-lead 2026-07-13 裁决后
        # 口径可能变化，UPSERT 不足以清理旧行，必须先 truncate）
        await db.execute(text('TRUNCATE TABLE e_stat_hour'))
        await db.execute(text('TRUNCATE TABLE e_stat_day'))
        await db.execute(text('TRUNCATE TABLE e_stat_month'))
        counts: dict[str, int] = {}
        counts['stat_hour_equipment'] = await cls._rebuild_stat_hour_equipment(db)
        counts['stat_hour_area'] = await cls._rebuild_stat_hour_area(db)
        counts['stat_day_equipment'] = await cls._rebuild_stat_day_equipment(db)
        counts['stat_day_area'] = await cls._rebuild_stat_day_area(db)
        counts['stat_day_system'] = await cls._rebuild_stat_day_system(db)
        counts['stat_month_equipment'] = await cls._rebuild_stat_month_equipment(db)
        counts['stat_month_area'] = await cls._rebuild_stat_month_area(db)
        counts['stat_month_system'] = await cls._rebuild_stat_month_system(db)
        await db.commit()
        logger.info(f'[aggregation] 全量重跑完成：{counts}')
        return counts

    @classmethod
    async def rebuild_range(
        cls, db: AsyncSession, start: datetime, end: datetime
    ) -> dict[str, int]:
        """
        增量重跑：只重建 [start, end) 时间窗内的 hour/day/month 行
        用于 APScheduler 定时任务（近 N 小时增量刷新）
        """
        logger.info(f'[aggregation] 增量重跑 {start} → {end}')
        counts: dict[str, int] = {}
        counts['stat_hour_equipment'] = await cls._rebuild_stat_hour_equipment(db, start, end)
        counts['stat_hour_area'] = await cls._rebuild_stat_hour_area(db, start, end)
        # 覆盖到日 / 月粒度需要向下取整
        day_start = date(start.year, start.month, start.day)
        day_end = date(end.year, end.month, end.day) + timedelta(days=1)
        counts['stat_day_equipment'] = await cls._rebuild_stat_day_equipment(db, day_start, day_end)
        counts['stat_day_area'] = await cls._rebuild_stat_day_area(db, day_start, day_end)
        counts['stat_day_system'] = await cls._rebuild_stat_day_system(db, day_start, day_end)
        month_start = date(start.year, start.month, 1)
        counts['stat_month_equipment'] = await cls._rebuild_stat_month_equipment(db, month_start)
        counts['stat_month_area'] = await cls._rebuild_stat_month_area(db, month_start)
        counts['stat_month_system'] = await cls._rebuild_stat_month_system(db, month_start)
        await db.commit()
        logger.info(f'[aggregation] 增量重跑完成：{counts}')
        return counts

    # ------------------------------------------------------------------
    # 内部：小时统计
    # ------------------------------------------------------------------

    @classmethod
    def _tou_case_sql(cls, hour_expr: str) -> str:
        peak = ','.join(str(h) for h in _PEAK_HOURS)
        valley = ','.join(str(h) for h in _VALLEY_HOURS)
        return (
            f"CASE "
            f"WHEN {hour_expr} IN ({peak}) THEN 'peak' "
            f"WHEN {hour_expr} IN ({valley}) THEN 'valley' "
            f"ELSE 'flat' END"
        )

    @classmethod
    async def _rebuild_stat_hour_equipment(
        cls, db: AsyncSession, start: datetime | None = None, end: datetime | None = None
    ) -> int:
        """
        e_stat_hour 按 equipment × energy_type × 整点桶聚合
        质量口径：只算 ok/late/est/fix；覆盖率 = 合格样点/理论样点（理论 = 3600/sample_period × 点数）
        """
        valid_codes_sql = ','.join(f"'{c}'" for c in _VALID_QUALITY_CODES)
        tou_case = cls._tou_case_sql('HOUR(r.sample_time)')
        where_time = ''
        params: dict[str, Any] = {}
        if start is not None and end is not None:
            where_time = 'AND r.sample_time >= :start AND r.sample_time < :end'
            params = {'start': start, 'end': end}
        sql = f"""
            INSERT INTO e_stat_hour
              (object_type, object_id, energy_type_code, stat_time,
               total_value, avg_power_kw, coverage_ratio, quality_summary,
               tou_period, version_no, computed_at)
            SELECT
              'equipment' AS object_type,
              p.equipment_id AS object_id,
              p.energy_type_code,
              DATE_FORMAT(r.sample_time, '%Y-%m-%d %H:00:00') AS stat_time,
              COALESCE(SUM(CASE WHEN r.quality_state IN ({valid_codes_sql})
                                THEN r.incremental_value ELSE 0 END), 0) AS total_value,
              /* 电类：kWh/h ≈ kW 平均功率；其他能源 NULL */
              CASE WHEN p.energy_type_code = 'electricity'
                   THEN COALESCE(SUM(CASE WHEN r.quality_state IN ({valid_codes_sql})
                                          THEN r.incremental_value ELSE 0 END), 0)
                   ELSE NULL END AS avg_power_kw,
              /* 覆盖率（PRD §7.3）：合格样点 / (点数 × 3600/sample_period_sec)
                 同一 energy_type 内 sample_period 一致，用 MIN() 满足 only_full_group_by；
                 分子 SUM(valid) / 分母 (distinct 出现点数 × 每点每小时理论样点) */
              SUM(CASE WHEN r.quality_state IN ({valid_codes_sql}) THEN 1 ELSE 0 END)
                / NULLIF(COUNT(DISTINCT r.point_id)
                         * (3600.0 / MIN(p.sample_period_sec)), 0) AS coverage_ratio,
              CONCAT('ok=', SUM(CASE WHEN r.quality_state='ok' THEN 1 ELSE 0 END),
                     ' late=', SUM(CASE WHEN r.quality_state='late' THEN 1 ELSE 0 END),
                     ' miss=', SUM(CASE WHEN r.quality_state='miss' THEN 1 ELSE 0 END),
                     ' est=', SUM(CASE WHEN r.quality_state='est' THEN 1 ELSE 0 END),
                     ' fix=', SUM(CASE WHEN r.quality_state='fix' THEN 1 ELSE 0 END)) AS quality_summary,
              {tou_case} AS tou_period,
              1 AS version_no,
              NOW() AS computed_at
            FROM e_raw_reading r
            JOIN e_meter_point p ON p.point_id = r.point_id
            WHERE p.energy_type_code IS NOT NULL
              AND p.equipment_id IS NOT NULL
              AND p.status = 'enabled'
              {where_time}
            GROUP BY p.equipment_id, p.energy_type_code,
                     DATE_FORMAT(r.sample_time, '%Y-%m-%d %H:00:00'),
                     {tou_case}
            ON DUPLICATE KEY UPDATE
              total_value = VALUES(total_value),
              avg_power_kw = VALUES(avg_power_kw),
              coverage_ratio = VALUES(coverage_ratio),
              quality_summary = VALUES(quality_summary),
              tou_period = VALUES(tou_period),
              version_no = version_no + 1,
              computed_at = VALUES(computed_at)
        """
        result = await db.execute(text(sql), params)
        return result.rowcount or 0

    @classmethod
    async def _rebuild_stat_hour_area(
        cls, db: AsyncSession, start: datetime | None = None, end: datetime | None = None
    ) -> int:
        """
        e_stat_hour 按 area × energy_type × 整点桶聚合（等价于设备行的 area 累加）
        """
        valid_codes_sql = ','.join(f"'{c}'" for c in _VALID_QUALITY_CODES)
        tou_case = cls._tou_case_sql('HOUR(r.sample_time)')
        where_time = ''
        params: dict[str, Any] = {}
        if start is not None and end is not None:
            where_time = 'AND r.sample_time >= :start AND r.sample_time < :end'
            params = {'start': start, 'end': end}
        sql = f"""
            INSERT INTO e_stat_hour
              (object_type, object_id, energy_type_code, stat_time,
               total_value, avg_power_kw, coverage_ratio, quality_summary,
               tou_period, version_no, computed_at)
            SELECT
              'area' AS object_type,
              p.area_id AS object_id,
              p.energy_type_code,
              DATE_FORMAT(r.sample_time, '%Y-%m-%d %H:00:00') AS stat_time,
              COALESCE(SUM(CASE WHEN r.quality_state IN ({valid_codes_sql})
                                THEN r.incremental_value ELSE 0 END), 0),
              CASE WHEN p.energy_type_code = 'electricity'
                   THEN COALESCE(SUM(CASE WHEN r.quality_state IN ({valid_codes_sql})
                                          THEN r.incremental_value ELSE 0 END), 0)
                   ELSE NULL END,
              /* 覆盖率同 _rebuild_stat_hour_equipment：valid / (distinct点数 × 每点每小时理论样点) */
              SUM(CASE WHEN r.quality_state IN ({valid_codes_sql}) THEN 1 ELSE 0 END)
                / NULLIF(COUNT(DISTINCT r.point_id)
                         * (3600.0 / MIN(p.sample_period_sec)), 0),
              CONCAT('ok=', SUM(CASE WHEN r.quality_state='ok' THEN 1 ELSE 0 END),
                     ' late=', SUM(CASE WHEN r.quality_state='late' THEN 1 ELSE 0 END),
                     ' miss=', SUM(CASE WHEN r.quality_state='miss' THEN 1 ELSE 0 END),
                     ' est=', SUM(CASE WHEN r.quality_state='est' THEN 1 ELSE 0 END),
                     ' fix=', SUM(CASE WHEN r.quality_state='fix' THEN 1 ELSE 0 END)),
              {tou_case},
              1,
              NOW()
            FROM e_raw_reading r
            JOIN e_meter_point p ON p.point_id = r.point_id
            WHERE p.energy_type_code IS NOT NULL
              AND p.status = 'enabled'
              AND {_AREA_MAIN_METER_SQL}  /* team-lead 裁决：area 只取区总表口径 */
              {where_time}
            GROUP BY p.area_id, p.energy_type_code,
                     DATE_FORMAT(r.sample_time, '%Y-%m-%d %H:00:00'),
                     {tou_case}
            ON DUPLICATE KEY UPDATE
              total_value = VALUES(total_value),
              avg_power_kw = VALUES(avg_power_kw),
              coverage_ratio = VALUES(coverage_ratio),
              quality_summary = VALUES(quality_summary),
              tou_period = VALUES(tou_period),
              version_no = version_no + 1,
              computed_at = VALUES(computed_at)
        """
        result = await db.execute(text(sql), params)
        return result.rowcount or 0

    # ------------------------------------------------------------------
    # 内部：日统计
    # ------------------------------------------------------------------

    @classmethod
    async def _rebuild_stat_day_common(
        cls,
        db: AsyncSession,
        object_type: str,
        day_start: date | None,
        day_end: date | None,
    ) -> int:
        """
        e_stat_day 从 e_stat_hour 汇总，object_type 一份（equipment / area）
        peak/flat/valley 拆分从 hour.tou_period 汇总，coverage_ratio 用小时平均
        workday_flag：周一至周五=1
        """
        where_date = ''
        params: dict[str, Any] = {'object_type': object_type}
        if day_start is not None and day_end is not None:
            where_date = 'AND DATE(h.stat_time) >= :day_start AND DATE(h.stat_time) < :day_end'
            params['day_start'] = day_start
            params['day_end'] = day_end
        sql = f"""
            INSERT INTO e_stat_day
              (object_type, object_id, energy_type_code, stat_date,
               total_value, peak_value, flat_value, valley_value,
               coverage_ratio, workday_flag, version_no, computed_at)
            SELECT
              h.object_type,
              h.object_id,
              h.energy_type_code,
              DATE(h.stat_time) AS stat_date,
              COALESCE(SUM(h.total_value), 0) AS total_value,
              COALESCE(SUM(CASE WHEN h.tou_period='peak' THEN h.total_value ELSE 0 END), 0) AS peak_value,
              COALESCE(SUM(CASE WHEN h.tou_period='flat' THEN h.total_value ELSE 0 END), 0) AS flat_value,
              COALESCE(SUM(CASE WHEN h.tou_period='valley' THEN h.total_value ELSE 0 END), 0) AS valley_value,
              AVG(h.coverage_ratio) AS coverage_ratio,
              /* MAX(CASE) 满足 only_full_group_by；同组行日期一致故取值稳定 */
              MAX(CASE WHEN DAYOFWEEK(DATE(h.stat_time)) IN (1, 7) THEN 0 ELSE 1 END) AS workday_flag,
              1 AS version_no,
              NOW() AS computed_at
            FROM e_stat_hour h
            WHERE h.object_type = :object_type
              {where_date}
            GROUP BY h.object_type, h.object_id, h.energy_type_code, DATE(h.stat_time)
            ON DUPLICATE KEY UPDATE
              total_value = VALUES(total_value),
              peak_value = VALUES(peak_value),
              flat_value = VALUES(flat_value),
              valley_value = VALUES(valley_value),
              coverage_ratio = VALUES(coverage_ratio),
              workday_flag = VALUES(workday_flag),
              version_no = version_no + 1,
              computed_at = VALUES(computed_at)
        """
        result = await db.execute(text(sql), params)
        return result.rowcount or 0

    @classmethod
    async def _rebuild_stat_day_equipment(
        cls, db: AsyncSession, day_start: date | None = None, day_end: date | None = None
    ) -> int:
        return await cls._rebuild_stat_day_common(db, 'equipment', day_start, day_end)

    @classmethod
    async def _rebuild_stat_day_area(
        cls, db: AsyncSession, day_start: date | None = None, day_end: date | None = None
    ) -> int:
        return await cls._rebuild_stat_day_common(db, 'area', day_start, day_end)

    @classmethod
    async def _rebuild_stat_day_system(
        cls, db: AsyncSession, day_start: date | None = None, day_end: date | None = None
    ) -> int:
        """
        e_stat_day 系统级：把 area 行按 (energy, day) 汇总为 object_type='system', object_id=0
        用途：R09 基线偏差、KPI BASELINE_DEV、KPI ENERGY_DAY 系统级读取
        """
        where_date = ''
        params: dict[str, Any] = {}
        if day_start is not None and day_end is not None:
            where_date = 'AND d.stat_date >= :day_start AND d.stat_date < :day_end'
            params['day_start'] = day_start
            params['day_end'] = day_end
        sql = f"""
            INSERT INTO e_stat_day
              (object_type, object_id, energy_type_code, stat_date,
               total_value, peak_value, flat_value, valley_value,
               coverage_ratio, workday_flag, version_no, computed_at)
            SELECT
              'system' AS object_type,
              0 AS object_id,
              d.energy_type_code,
              d.stat_date,
              COALESCE(SUM(d.total_value), 0),
              COALESCE(SUM(d.peak_value), 0),
              COALESCE(SUM(d.flat_value), 0),
              COALESCE(SUM(d.valley_value), 0),
              AVG(d.coverage_ratio),
              MAX(d.workday_flag),
              1,
              NOW()
            FROM e_stat_day d
            WHERE d.object_type = 'area'
              {where_date}
            GROUP BY d.energy_type_code, d.stat_date
            ON DUPLICATE KEY UPDATE
              total_value = VALUES(total_value),
              peak_value = VALUES(peak_value),
              flat_value = VALUES(flat_value),
              valley_value = VALUES(valley_value),
              coverage_ratio = VALUES(coverage_ratio),
              workday_flag = VALUES(workday_flag),
              version_no = version_no + 1,
              computed_at = VALUES(computed_at)
        """
        result = await db.execute(text(sql), params)
        return result.rowcount or 0

    # ------------------------------------------------------------------
    # 内部：月统计
    # ------------------------------------------------------------------

    @classmethod
    async def _rebuild_stat_month_common(
        cls, db: AsyncSession, object_type: str, month_start: date | None
    ) -> int:
        """e_stat_month 从 e_stat_day 汇总"""
        where_month = ''
        params: dict[str, Any] = {'object_type': object_type}
        if month_start is not None:
            where_month = "AND d.stat_date >= :month_start"
            params['month_start'] = month_start
        sql = f"""
            INSERT INTO e_stat_month
              (object_type, object_id, energy_type_code, stat_month,
               total_value, peak_value, flat_value, valley_value,
               coverage_ratio, version_no, computed_at)
            SELECT
              d.object_type,
              d.object_id,
              d.energy_type_code,
              DATE_FORMAT(d.stat_date, '%Y-%m') AS stat_month,
              COALESCE(SUM(d.total_value), 0),
              COALESCE(SUM(d.peak_value), 0),
              COALESCE(SUM(d.flat_value), 0),
              COALESCE(SUM(d.valley_value), 0),
              AVG(d.coverage_ratio),
              1,
              NOW()
            FROM e_stat_day d
            WHERE d.object_type = :object_type
              {where_month}
            GROUP BY d.object_type, d.object_id, d.energy_type_code,
                     DATE_FORMAT(d.stat_date, '%Y-%m')
            ON DUPLICATE KEY UPDATE
              total_value = VALUES(total_value),
              peak_value = VALUES(peak_value),
              flat_value = VALUES(flat_value),
              valley_value = VALUES(valley_value),
              coverage_ratio = VALUES(coverage_ratio),
              version_no = version_no + 1,
              computed_at = VALUES(computed_at)
        """
        result = await db.execute(text(sql), params)
        return result.rowcount or 0

    @classmethod
    async def _rebuild_stat_month_equipment(
        cls, db: AsyncSession, month_start: date | None = None
    ) -> int:
        return await cls._rebuild_stat_month_common(db, 'equipment', month_start)

    @classmethod
    async def _rebuild_stat_month_area(
        cls, db: AsyncSession, month_start: date | None = None
    ) -> int:
        return await cls._rebuild_stat_month_common(db, 'area', month_start)

    @classmethod
    async def _rebuild_stat_month_system(
        cls, db: AsyncSession, month_start: date | None = None
    ) -> int:
        """e_stat_month 系统级：从 area 汇总"""
        where_month = ''
        params: dict[str, Any] = {}
        if month_start is not None:
            where_month = "AND m.stat_month >= :month_start_str"
            params['month_start_str'] = month_start.strftime('%Y-%m')
        sql = f"""
            INSERT INTO e_stat_month
              (object_type, object_id, energy_type_code, stat_month,
               total_value, peak_value, flat_value, valley_value,
               coverage_ratio, version_no, computed_at)
            SELECT
              'system', 0, m.energy_type_code, m.stat_month,
              COALESCE(SUM(m.total_value), 0),
              COALESCE(SUM(m.peak_value), 0),
              COALESCE(SUM(m.flat_value), 0),
              COALESCE(SUM(m.valley_value), 0),
              AVG(m.coverage_ratio),
              1, NOW()
            FROM e_stat_month m
            WHERE m.object_type = 'area'
              {where_month}
            GROUP BY m.energy_type_code, m.stat_month
            ON DUPLICATE KEY UPDATE
              total_value = VALUES(total_value),
              peak_value = VALUES(peak_value),
              flat_value = VALUES(flat_value),
              valley_value = VALUES(valley_value),
              coverage_ratio = VALUES(coverage_ratio),
              version_no = version_no + 1,
              computed_at = VALUES(computed_at)
        """
        result = await db.execute(text(sql), params)
        return result.rowcount or 0
