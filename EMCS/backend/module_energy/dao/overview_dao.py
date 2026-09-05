"""
能源总览 DAO
REQ 锚点：REQ-057~062、REQ-019、REQ-029、REQ-039/040/042、REQ-053

设计说明（2026-07-13 阶段 2）：
- entity/do 模型对齐 datagen/ddl/V001__energy_domain.sql（23 张 e_* 表）
- 主数据/时序表已由 datagen 造数入库：e_area(2)/e_equipment(12)/e_meter_point(48)/
  e_raw_reading(56w)/e_alert_rule(11)/e_energy_baseline(1)/e_tariff_version(5)
- 聚合表暂空（e_stat_hour/e_stat_day/e_stat_month/e_cost_record/e_alert_event/e_suggestion）：
  由 task #14 的统计聚合管道 + 规则引擎写入；本 DAO 对空表返回空 list/None/零，
  service 层负责组装成"合法结构 + 零值/占位"

约定：dao 只出数据，不做业务判断或口径拼装；口径由 service 层负责。
"""

from datetime import date, datetime, timedelta
from decimal import Decimal
from typing import Any

from sqlalchemy import and_, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from module_energy.entity.do.alert_event_do import EAlertEvent
from module_energy.entity.do.alert_rule_do import EAlertRule
from module_energy.entity.do.area_do import EArea
from module_energy.entity.do.cost_record_do import ECostRecord
from module_energy.entity.do.energy_baseline_do import EEnergyBaseline
from module_energy.entity.do.equipment_do import EEquipment
from module_energy.entity.do.meter_point_do import EMeterPoint
from module_energy.entity.do.raw_reading_do import ERawReading
from module_energy.entity.do.stat_day_do import EStatDay
from module_energy.entity.do.stat_hour_do import EStatHour
from module_energy.entity.do.stat_month_do import EStatMonth
from module_energy.entity.do.suggestion_do import ESuggestion
from module_energy.entity.do.tariff_version_do import ETariffVersion


class OverviewDao:
    """
    能源总览 DAO：聚合驾驶舱一次拉取所需的多面数据。
    """

    # ------------------------------------------------------------------
    # 主数据（datagen 已入库，可直接查）
    # ------------------------------------------------------------------

    @classmethod
    async def list_areas(cls, db: AsyncSession) -> list[EArea]:
        """全部装卸区（demo：2 条）"""
        return (await db.execute(select(EArea).order_by(EArea.area_id))).scalars().all()

    @classmethod
    async def get_area_by_code(cls, db: AsyncSession, area_code: str) -> EArea | None:
        """按 AREA-A/AREA-B 定位 area_id"""
        return (
            await db.execute(select(EArea).where(EArea.area_code == area_code))
        ).scalars().first()

    @classmethod
    async def list_equipments(cls, db: AsyncSession, area_id: int | None = None) -> list[EEquipment]:
        stmt = select(EEquipment).order_by(EEquipment.equipment_id)
        if area_id is not None:
            stmt = stmt.where(EEquipment.area_id == area_id)
        return (await db.execute(stmt)).scalars().all()

    @classmethod
    async def get_equipment_map(cls, db: AsyncSession) -> dict[int, EEquipment]:
        """全体设备的 id→row 索引（Top5 用能对象拼装 meta）"""
        rows = (await db.execute(select(EEquipment))).scalars().all()
        return {r.equipment_id: r for r in rows}

    @classmethod
    async def list_meter_points(cls, db: AsyncSession, area_id: int | None = None) -> list[EMeterPoint]:
        """全部计量点（demo：48 条，按 area+point_id 稳定序，用于 12×4 网格）"""
        stmt = select(EMeterPoint).order_by(EMeterPoint.area_id, EMeterPoint.point_id)
        if area_id is not None:
            stmt = stmt.where(EMeterPoint.area_id == area_id)
        return (await db.execute(stmt)).scalars().all()

    # ------------------------------------------------------------------
    # 质量：每点最新一条 reading 的 quality_state（REQ-019 48 点信号阵列）
    # ------------------------------------------------------------------

    @classmethod
    async def get_latest_reading_quality_per_point(
        cls, db: AsyncSession
    ) -> dict[int, str]:
        """
        每个采集点最近一条 e_raw_reading 的 quality_state 短码
        返回 {point_id: 'ok'|'miss'|'late'|'dup'|'jump'|'est'|'fix'|'frozen'}
        """
        sub = (
            select(
                ERawReading.point_id.label('point_id'),
                func.max(ERawReading.sample_time).label('max_time'),
            )
            .group_by(ERawReading.point_id)
            .subquery()
        )
        stmt = select(ERawReading.point_id, ERawReading.quality_state).join(
            sub,
            and_(
                ERawReading.point_id == sub.c.point_id,
                ERawReading.sample_time == sub.c.max_time,
            ),
        )
        rows = (await db.execute(stmt)).all()
        return {r.point_id: r.quality_state for r in rows}

    @classmethod
    async def get_latest_ingest_time(cls, db: AsyncSession) -> datetime | None:
        """全库最近一次 ingest_time（用于质量摘要 latestIngestAt）"""
        return (await db.execute(select(func.max(ERawReading.ingest_time)))).scalar()

    @classmethod
    async def count_meter_points(cls, db: AsyncSession, only_energy: bool = False) -> int:
        """
        计量点计数。only_energy=True 时只算能源类型非空的点（状态/环境点不计入覆盖率分母）
        默认 False：total=48 与前端 quality.total 对齐
        """
        stmt = select(func.count()).select_from(EMeterPoint)
        if only_energy:
            stmt = stmt.where(EMeterPoint.energy_type_code.isnot(None))
        return int((await db.execute(stmt)).scalar() or 0)

    @classmethod
    async def get_daily_valid_expected(
        cls, db: AsyncSession, stat_date: date,
    ) -> tuple[int, int]:
        """
        QA-S3 累计口径（team-lead 2026-07-14 裁决）——DEMO_NOW 日的
        (合格样点总数, 理论样点总数)。REQ-019 语义：按周期计算 = 每点每日
        86400/sample_period_sec 之和，仅对能源类型点位（状态/环境点不进分母）。
        """
        day_start = datetime.combine(stat_date, datetime.min.time())
        day_end = day_start + timedelta(days=1)
        # 分子：valid 样点数（quality IN ok/late/est/fix）
        valid_row = (
            await db.execute(
                select(func.count(ERawReading.reading_id))
                .join(EMeterPoint, EMeterPoint.point_id == ERawReading.point_id)
                .where(
                    ERawReading.sample_time >= day_start,
                    ERawReading.sample_time < day_end,
                    ERawReading.quality_state.in_(('ok', 'late', 'est', 'fix')),
                    EMeterPoint.energy_type_code.isnot(None),
                    EMeterPoint.status == 'enabled',
                )
            )
        ).scalar()
        # 分母：sum(86400 / sample_period_sec) 对所有能源类型点位
        expected_row = (
            await db.execute(
                select(func.coalesce(func.sum(86400.0 / EMeterPoint.sample_period_sec), 0)).where(
                    EMeterPoint.energy_type_code.isnot(None),
                    EMeterPoint.status == 'enabled',
                )
            )
        ).scalar()
        return int(valid_row or 0), int(float(expected_row or 0))

    @classmethod
    async def get_alert_new_by_day(
        cls, db: AsyncSession, day: date,
    ) -> int:
        """
        QA-S5（team-lead 2026-07-14 裁决）——按 first_occur_time 分日计数
        （e_alert_event.first_occur_time 已由规则引擎写死，成本 = 1 次 GROUP BY）
        表征"该日新触发的告警数"，用于 OPEN_ALARMS delta"vs 昨日新增"叙事。
        """
        day_start = datetime.combine(day, datetime.min.time())
        day_end = day_start + timedelta(days=1)
        val = (
            await db.execute(
                select(func.count(EAlertEvent.event_id)).where(
                    EAlertEvent.first_occur_time >= day_start,
                    EAlertEvent.first_occur_time < day_end,
                )
            )
        ).scalar()
        return int(val or 0)

    # ------------------------------------------------------------------
    # 基线 & 单价（REQ-029 演示态 + REQ-051/052 口径）
    # ------------------------------------------------------------------

    @classmethod
    async def get_active_baseline(
        cls,
        db: AsyncSession,
        energy_type_code: str,
        object_scope: str = 'system',
        object_id: int | None = None,
    ) -> EEnergyBaseline | None:
        """
        取当前生效基线（优先 published，兜底 draft）
        demo 阶段 datagen 只落 draft，取 draft 也算演示态启用
        FX-13：scope=area 时按 object_id 取该区自己的基线
        """
        stmt = (
            select(EEnergyBaseline)
            .where(
                EEnergyBaseline.object_scope == object_scope,
                EEnergyBaseline.energy_type_code == energy_type_code,
                EEnergyBaseline.status.in_(('published', 'draft')),
            )
            .order_by(EEnergyBaseline.baseline_end.desc())
        )
        if object_id is not None:
            stmt = stmt.where(EEnergyBaseline.object_id == object_id)
        return (await db.execute(stmt)).scalars().first()

    @classmethod
    async def list_active_tariff_versions(
        cls, db: AsyncSession, energy_type_code: str, on_date: date | None = None
    ) -> list[ETariffVersion]:
        """
        按能源类型取当前生效单价版本（可能多条：峰/平/谷）
        生效区间：effective_from <= on_date AND (effective_to IS NULL OR effective_to > on_date)
        """
        if on_date is None:
            on_date = date.today()
        stmt = (
            select(ETariffVersion)
            .where(
                ETariffVersion.energy_type_code == energy_type_code,
                ETariffVersion.effective_from <= on_date,
                (ETariffVersion.effective_to.is_(None)) | (ETariffVersion.effective_to > on_date),
            )
            .order_by(ETariffVersion.tou_period)
        )
        return (await db.execute(stmt)).scalars().all()

    # ------------------------------------------------------------------
    # 日/时/月统计（e_stat_* — 空表期间返回空 list / None）
    # ------------------------------------------------------------------

    @classmethod
    async def sum_stat_day_totals(
        cls,
        db: AsyncSession,
        stat_date: date,
        energy_type_code: str,
        object_type: str = 'area',
        area_id: int | None = None,
    ) -> dict[str, Decimal | None]:
        """
        对给定日期做系统级或分区级聚合（sum total_value + avg coverage + avg baseline_deviation_pct）
        用于 KPI ENERGY_DAY / COVERAGE / BASELINE_DEV
        REQ-024 多维聚合 · REQ-019 覆盖率 · REQ-029 基线偏差
        """
        stmt = select(
            func.coalesce(func.sum(EStatDay.total_value), 0).label('total_value'),
            func.avg(EStatDay.coverage_ratio).label('coverage_ratio'),
            func.avg(EStatDay.baseline_deviation_pct).label('baseline_deviation_pct'),
        ).where(
            EStatDay.stat_date == stat_date,
            EStatDay.energy_type_code == energy_type_code,
            EStatDay.object_type == object_type,
        )
        if area_id is not None:
            stmt = stmt.where(EStatDay.object_id == area_id)
        row = (await db.execute(stmt)).one_or_none()
        if row is None:
            return {'total_value': None, 'coverage_ratio': None, 'baseline_deviation_pct': None}
        return {
            'total_value': row.total_value,
            'coverage_ratio': row.coverage_ratio,
            'baseline_deviation_pct': row.baseline_deviation_pct,
        }

    @classmethod
    async def get_system_day_total(
        cls,
        db: AsyncSession,
        stat_date: date,
        energy_type_code: str,
    ) -> Decimal | None:
        """
        取 system 级 e_stat_day.total_value（用于 KPI ENERGY_DAY 环比昨日等口径）
        无 system 行时回退到 area 行求和
        """
        stmt = select(func.sum(EStatDay.total_value)).where(
            EStatDay.stat_date == stat_date,
            EStatDay.energy_type_code == energy_type_code,
            EStatDay.object_type == 'system',
        )
        val = (await db.execute(stmt)).scalar()
        if val is not None and float(val) > 0:
            return val
        # 回退 area 求和
        stmt = select(func.sum(EStatDay.total_value)).where(
            EStatDay.stat_date == stat_date,
            EStatDay.energy_type_code == energy_type_code,
            EStatDay.object_type == 'area',
        )
        return (await db.execute(stmt)).scalar()

    @classmethod
    async def get_hourly_stats_between(
        cls,
        db: AsyncSession,
        start_time: datetime,
        end_time: datetime,
        energy_type_code: str,
        object_type: str = 'area',
        object_id: int | None = None,
    ) -> list[EStatHour]:
        """
        取时间段小时统计（用于总览 trend 24h 曲线）
        object_id 空表示取全部区/系统行，由 service 按 stat_time 聚合
        """
        stmt = (
            select(EStatHour)
            .where(
                EStatHour.stat_time >= start_time,
                EStatHour.stat_time < end_time,
                EStatHour.energy_type_code == energy_type_code,
                EStatHour.object_type == object_type,
            )
            .order_by(EStatHour.stat_time)
        )
        if object_id is not None:
            stmt = stmt.where(EStatHour.object_id == object_id)
        return (await db.execute(stmt)).scalars().all()

    # ------------------------------------------------------------------
    # 成本（REQ-051~056）
    # ------------------------------------------------------------------

    @classmethod
    async def get_cost_month_summary(
        cls,
        db: AsyncSession,
        stat_month: str,
        energy_type_code: str,
        area_id: int | None = None,
    ) -> dict[str, Any] | None:
        """
        当月累计成本 + 峰平谷用量拆分
        默认聚合 area 维度全部行（对应"全站月度成本"）；area_id 提供时限定到单区
        """
        base_where = [
            ECostRecord.stat_month == stat_month,
            ECostRecord.energy_type_code == energy_type_code,
            ECostRecord.is_current.is_(True),
            ECostRecord.status != 'void',
        ]
        if area_id is not None:
            base_where.append(ECostRecord.object_type == 'area')
            base_where.append(ECostRecord.object_id == area_id)
        else:
            base_where.append(ECostRecord.object_type == 'area')

        stmt = select(
            func.coalesce(func.sum(ECostRecord.total_cost), 0).label('total_cost'),
            func.coalesce(func.sum(ECostRecord.peak_qty), 0).label('peak_qty'),
            func.coalesce(func.sum(ECostRecord.flat_qty), 0).label('flat_qty'),
            func.coalesce(func.sum(ECostRecord.valley_qty), 0).label('valley_qty'),
            func.max(ECostRecord.tariff_version_no).label('tariff_version_no'),
            func.max(ECostRecord.cost_version).label('current_cost_version'),
        ).where(*base_where)
        row = (await db.execute(stmt)).one_or_none()
        if row is None:
            return None
        return {
            'total_cost': row.total_cost,
            'peak_qty': row.peak_qty,
            'flat_qty': row.flat_qty,
            'valley_qty': row.valley_qty,
            'tariff_version_no': row.tariff_version_no,
            'current_cost_version': row.current_cost_version,
        }

    @classmethod
    async def get_top_cost_objects(
        cls,
        db: AsyncSession,
        stat_month: str,
        limit: int = 5,
        area_id: int | None = None,
    ) -> list[dict[str, Any]]:
        """
        月度成本 Top N 用能对象（REQ-053 月度 Top 5）
        以 equipment 维度取 top；等值时按 object_id 稳定序
        """
        stmt = (
            select(
                ECostRecord.object_id.label('equipment_id'),
                func.sum(ECostRecord.total_cost).label('total_cost'),
                func.max(ECostRecord.cost_version).label('current_cost_version'),
            )
            .where(
                ECostRecord.stat_month == stat_month,
                ECostRecord.object_type == 'equipment',
                ECostRecord.is_current.is_(True),
                ECostRecord.status != 'void',
            )
            .group_by(ECostRecord.object_id)
            .order_by(func.sum(ECostRecord.total_cost).desc(), ECostRecord.object_id)
            .limit(limit)
        )
        if area_id is not None:
            stmt = stmt.join(
                EEquipment, EEquipment.equipment_id == ECostRecord.object_id
            ).where(EEquipment.area_id == area_id)
        rows = (await db.execute(stmt)).all()
        return [
            {
                'equipment_id': r.equipment_id,
                'total_cost': r.total_cost,
                'current_cost_version': r.current_cost_version,
            }
            for r in rows
        ]

    @classmethod
    async def get_top_energy_objects(
        cls,
        db: AsyncSession,
        stat_month: str,
        energy_type_code: str,
        limit: int = 5,
        area_id: int | None = None,
    ) -> list[dict[str, Any]]:
        """
        月度能耗 Top N 用能对象（REQ-053 · #23 追加）
        - 数据源：e_stat_month equipment 行，仅当前 energy_type_code
        - **不跨介质相加**：一次调用只在单一介质内排序
        - 与 cost 口径同月（默认取 cost pipeline 同一 stat_month）
        """
        stmt = (
            select(
                EStatMonth.object_id.label('equipment_id'),
                EStatMonth.total_value.label('total_value'),
            )
            .where(
                EStatMonth.stat_month == stat_month,
                EStatMonth.object_type == 'equipment',
                EStatMonth.energy_type_code == energy_type_code,
            )
            .order_by(EStatMonth.total_value.desc(), EStatMonth.object_id)
            .limit(limit)
        )
        if area_id is not None:
            stmt = stmt.join(
                EEquipment, EEquipment.equipment_id == EStatMonth.object_id
            ).where(EEquipment.area_id == area_id)
        rows = (await db.execute(stmt)).all()
        return [{'equipment_id': r.equipment_id, 'total_value': r.total_value} for r in rows]

    # ------------------------------------------------------------------
    # 告警（REQ-039/040/042）
    # ------------------------------------------------------------------

    _OPEN_STATUSES = ('new', 'ack', 'dispatched', 'processing', 'escalated')

    @classmethod
    async def get_open_alert_summary(
        cls, db: AsyncSession, area_id: int | None = None
    ) -> dict[str, int]:
        """
        未关闭告警数 + 级别拆分（REQ-039 级别三档 notice/normal/severe）
        状态 ∉ {closed, false_closed} 视为未关闭
        """
        stmt = select(EAlertEvent.level, func.count()).group_by(EAlertEvent.level).where(
            EAlertEvent.status.in_(cls._OPEN_STATUSES)
        )
        if area_id is not None:
            stmt = stmt.where(EAlertEvent.area_id == area_id)
        rows = (await db.execute(stmt)).all()
        summary = {'total': 0, 'notice': 0, 'normal': 0, 'severe': 0}
        for level, cnt in rows:
            if level in summary:
                summary[level] = int(cnt)
            summary['total'] += int(cnt)
        return summary

    @classmethod
    async def get_recent_alerts(
        cls, db: AsyncSession, limit: int = 6, area_id: int | None = None
    ) -> list[dict[str, Any]]:
        """
        最新告警滚动（REQ-039/040/042）
        联 e_alert_rule 取 rule_name；level/rule_version_no 都取事件行冻结版本（REQ-040）
        """
        stmt = (
            select(
                EAlertEvent.event_id,
                EAlertEvent.rule_code,
                EAlertEvent.rule_version_no,
                EAlertEvent.level,
                EAlertEvent.last_occur_time,
                EAlertEvent.occur_count,
                EAlertEvent.object_type,
                EAlertEvent.object_id,
                EAlertEvent.area_id,
                EAlertRule.rule_name,
                EAlertRule.expression,
            )
            .join(EAlertRule, EAlertRule.rule_id == EAlertEvent.rule_id)
            .where(EAlertEvent.status.in_(cls._OPEN_STATUSES))
            .order_by(EAlertEvent.last_occur_time.desc(), EAlertEvent.event_id.desc())
            .limit(limit)
        )
        if area_id is not None:
            stmt = stmt.where(EAlertEvent.area_id == area_id)
        return [
            {
                'event_id': r.event_id,
                'rule_code': r.rule_code,
                'rule_version_no': r.rule_version_no,
                'rule_name': r.rule_name,
                'expression': r.expression,
                'level': r.level,
                'last_occur_time': r.last_occur_time,
                'occur_count': r.occur_count,
                'object_type': r.object_type,
                'object_id': r.object_id,
                'area_id': r.area_id,
            }
            for r in (await db.execute(stmt)).all()
        ]

    @classmethod
    async def get_alerts_in_hour_range(
        cls,
        db: AsyncSession,
        start_time: datetime,
        end_time: datetime,
        rule_codes: tuple[str, ...] | None = None,
        area_id: int | None = None,
    ) -> list[dict[str, Any]]:
        """
        某时间窗内的告警（trend.anomalies 用；契约 v0.1 更新 2026-07-13：只挂 R09 基线偏差点）
        联 e_alert_rule 取 rule_name 便于 service 组装"R09 基线偏差 · 演示态"格式的 note
        """
        stmt = (
            select(
                EAlertEvent.event_id,
                EAlertEvent.rule_code,
                EAlertEvent.rule_version_no,
                EAlertEvent.level,
                EAlertEvent.last_occur_time,
                EAlertEvent.object_type,
                EAlertEvent.object_id,
                EAlertEvent.area_id,
                EAlertRule.rule_name,
            )
            .join(EAlertRule, EAlertRule.rule_id == EAlertEvent.rule_id)
            .where(
                EAlertEvent.last_occur_time >= start_time,
                EAlertEvent.last_occur_time < end_time,
            )
        )
        if rule_codes:
            stmt = stmt.where(EAlertEvent.rule_code.in_(rule_codes))
        if area_id is not None:
            stmt = stmt.where(EAlertEvent.area_id == area_id)
        stmt = stmt.order_by(EAlertEvent.last_occur_time)
        return [
            {
                'event_id': r.event_id,
                'rule_code': r.rule_code,
                'rule_version_no': r.rule_version_no,
                'rule_name': r.rule_name,
                'level': r.level,
                'last_occur_time': r.last_occur_time,
                'object_type': r.object_type,
                'object_id': r.object_id,
                'area_id': r.area_id,
            }
            for r in (await db.execute(stmt)).all()
        ]

    # ------------------------------------------------------------------
    # 节能建议（REQ-045~050）
    # ------------------------------------------------------------------

    # PRD §5.1 KPI 表：待办 = 状态 ∈ {待审核, 已分派, 执行中}；验证中不属于待办
    _OPEN_SUGGESTION_STATUSES = ('pending', 'dispatched', 'executing')

    @classmethod
    async def get_open_suggestion_summary(
        cls, db: AsyncSession, area_id: int | None = None
    ) -> dict[str, int]:
        """
        待办节能建议（PRD §5.1：状态 ∈ 待审核/已分派/执行中）
        """
        stmt = (
            select(ESuggestion.status, func.count())
            .group_by(ESuggestion.status)
            .where(ESuggestion.status.in_(cls._OPEN_SUGGESTION_STATUSES))
        )
        if area_id is not None:
            stmt = stmt.where(ESuggestion.area_id == area_id)
        rows = (await db.execute(stmt)).all()
        summary = {'total': 0, 'pending': 0, 'dispatched': 0, 'executing': 0}
        for status, cnt in rows:
            summary[status] = int(cnt)
            summary['total'] += int(cnt)
        return summary
