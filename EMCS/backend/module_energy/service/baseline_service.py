"""
能源基线计算与报告期偏差回填（REQ-029 演示态）
PRD §7.3 demo 实现：前 8 周按 (工作日/周末 × 峰平谷) 分桶均值 + ±1σ

流程：
1. compute_and_publish(db)：
   - 遍历 e_energy_baseline 中 status='draft' 记录（system 级 + area 级，FX-13）
   - 从 e_stat_hour 取基线期 [start, end] 的小时值：system 基线聚合全部 area 行，
     area 基线只取该区行
   - 按 (workday_flag, tou_period) 分桶算 mean + sigma → 6 桶
   - 写 buckets_json + status='published' + published_at
2. compute_daily_deviation(db)：
   - 对报告期（基线期结束 → demo_now）内每一天：
     - 按基线 scope 拉取对应 e_stat_day 行（system→system/0，area→area/object_id）的实际 total
     - 用 baseline buckets_json 计算期望值：
       expected = peak_mean × peak_hours + flat_mean × flat_hours + valley_mean × valley_hours
     - deviation_pct = (actual - expected) / expected × 100
   - 回填对应 scope 的 e_stat_day 行。**FX-13（2026-07-15）**：废除"area 行复制 system
     偏差"的第一幕捷径——A/B 区偏差按各自分区基线真算，与 system 值互不相同

REQ 锚点：REQ-029 EnPI · REQ-020 重算版本 · demo-R09 基线偏差 +20% 触发
"""

import json
from datetime import date, datetime, timedelta
from decimal import Decimal
from statistics import mean, stdev
from typing import Any

from sqlalchemy import func, select, update
from sqlalchemy.ext.asyncio import AsyncSession

from module_energy.entity.do.energy_baseline_do import EEnergyBaseline
from module_energy.entity.do.stat_day_do import EStatDay
from module_energy.entity.do.stat_hour_do import EStatHour
from module_energy.service.demo_now_util import get_demo_now
from utils.log_util import logger

# TOU 分档（对齐 PRD §7.1；同 aggregation_service._PEAK_HOURS）
_PEAK_HOURS: tuple[int, ...] = (8, 9, 10, 18, 19, 20)
_VALLEY_HOURS: tuple[int, ...] = (22, 23, 0, 1, 2, 3, 4, 5)
_HOURS_IN_PEAK = 6
_HOURS_IN_VALLEY = 8
_HOURS_IN_FLAT = 24 - _HOURS_IN_PEAK - _HOURS_IN_VALLEY  # 10


def _tou_period(hour: int) -> str:
    if hour in _PEAK_HOURS:
        return 'peak'
    if hour in _VALLEY_HOURS:
        return 'valley'
    return 'flat'


def _bucket_key(workday: int, tou: str) -> str:
    prefix = 'workday' if workday else 'weekend'
    return f'{prefix}_{tou}'


def _hours_in_period(period: str, workday: int) -> int:
    if period == 'peak':
        return _HOURS_IN_PEAK
    if period == 'valley':
        return _HOURS_IN_VALLEY
    return _HOURS_IN_FLAT


class BaselineService:
    """
    能源基线计算 service（system 级 + area 级基线，FX-13；后续可扩 equipment 级）
    """

    @classmethod
    async def compute_and_publish(
        cls, db: AsyncSession, force_republish: bool = False
    ) -> dict[str, Any]:
        """
        计算 6 桶 → published。
        - 默认（force_republish=False）：只处理 status='draft' 的记录（幂等 bootstrap 保护，
          聚合口径不变时不重复刷新）
        - force_republish=True：处理 status IN ('draft','published')，无条件重算并刷新
          buckets_json（用于聚合口径变化后重跑，team-lead 2026-07-13 裁决）
        """
        status_filter = (
            EEnergyBaseline.status.in_(('draft', 'published'))
            if force_republish
            else (EEnergyBaseline.status == 'draft')
        )
        drafts = (
            await db.execute(
                select(EEnergyBaseline).where(status_filter)
            )
        ).scalars().all()
        result: dict[str, Any] = {
            'published': [],
            'skipped_no_data': [],
            'force_republish': force_republish,
        }
        for b in drafts:
            buckets = await cls._compute_buckets(db, b)
            if not buckets:
                logger.warning(f'[baseline] {b.baseline_code} 无小时数据，跳过')
                result['skipped_no_data'].append(b.baseline_code)
                continue
            await db.execute(
                update(EEnergyBaseline)
                .where(EEnergyBaseline.baseline_id == b.baseline_id)
                .values(
                    buckets_json=json.dumps(buckets, ensure_ascii=False),
                    status='published',
                    # P-09：published_at 是基线元数据展示字段，须锚定 DEMO_NOW
                    published_at=await get_demo_now(db),
                    published_by='pipeline',
                    formula_version=b.formula_version or 'f-1.3',
                )
            )
            result['published'].append({'code': b.baseline_code, 'buckets': buckets})
            logger.info(f'[baseline] {b.baseline_code} 发布，6 桶：{buckets}')
        await db.commit()
        return result

    @classmethod
    async def _compute_buckets(
        cls, db: AsyncSession, baseline: EEnergyBaseline
    ) -> dict[str, dict[str, float]]:
        """
        取基线期 [start, end] 的小时值，按 (workday_flag, tou_period) 分 6 桶
        返回 {bucket_key: {mean, sigma, samples}}。
        scope=system：SUM 全部 area 行到系统小时（当前 aggregation 未落 hour-system 行）；
        scope=area（FX-13）：只取该区（object_id）自己的小时行
        """
        stmt = (
            select(
                EStatHour.stat_time,
                func.sum(EStatHour.total_value).label('system_total'),
            )
            .where(
                EStatHour.object_type == 'area',
                EStatHour.energy_type_code == baseline.energy_type_code,
                EStatHour.stat_time >= datetime.combine(baseline.baseline_start, datetime.min.time()),
                EStatHour.stat_time < datetime.combine(baseline.baseline_end, datetime.min.time()),
            )
            .group_by(EStatHour.stat_time)
            .order_by(EStatHour.stat_time)
        )
        if baseline.object_scope == 'area' and baseline.object_id is not None:
            stmt = stmt.where(EStatHour.object_id == baseline.object_id)
        rows = (await db.execute(stmt)).all()
        buckets_data: dict[str, list[float]] = {}
        for r in rows:
            ts: datetime = r.stat_time
            total = float(r.system_total or 0)
            # weekday: python weekday() 返回 0=Mon..6=Sun；周末 = Sat/Sun
            is_workday = 0 if ts.weekday() >= 5 else 1  # noqa: PLR2004
            tou = _tou_period(ts.hour)
            buckets_data.setdefault(_bucket_key(is_workday, tou), []).append(total)
        result: dict[str, dict[str, float]] = {}
        for key, vals in buckets_data.items():
            m = float(mean(vals))
            s = float(stdev(vals)) if len(vals) > 1 else 0.0
            result[key] = {'mean': round(m, 4), 'sigma': round(s, 4), 'samples': len(vals)}
        return result

    @classmethod
    async def compute_daily_deviation(cls, db: AsyncSession) -> dict[str, int]:
        """
        对所有 published 基线：算报告期（baseline_end → demo_now）每日偏差，回填 e_stat_day
        FX-13：按基线 scope 各写各的行——system 基线写 system/0 行，area 基线写本区行；
        不再把 system 偏差复制进 area 行（A/B 区偏差必须是各自分区基线的真算值）
        """
        counts: dict[str, int] = {'system_updated': 0, 'area_updated': 0}
        demo_now = await get_demo_now(db)
        published = (
            await db.execute(
                select(EEnergyBaseline).where(EEnergyBaseline.status == 'published')
            )
        ).scalars().all()
        for b in published:
            if not b.buckets_json:
                continue
            buckets = json.loads(b.buckets_json)
            if b.object_scope == 'area' and b.object_id is not None:
                target = ('area', int(b.object_id), 'area_updated')
            else:
                target = ('system', 0, 'system_updated')
            object_type, object_id, counter_key = target
            # 报告期：baseline_end 次日 → demo_now.date()（含）
            day = b.baseline_end + timedelta(days=1)
            end_day = demo_now.date()
            while day <= end_day:
                deviation = await cls._compute_day_deviation(
                    db, b, buckets, day, object_type, object_id
                )
                if deviation is None:
                    day += timedelta(days=1)
                    continue
                result = await db.execute(
                    update(EStatDay)
                    .where(
                        EStatDay.object_type == object_type,
                        EStatDay.object_id == object_id,
                        EStatDay.energy_type_code == b.energy_type_code,
                        EStatDay.stat_date == day,
                    )
                    .values(baseline_id=b.baseline_id, baseline_deviation_pct=deviation)
                )
                counts[counter_key] += result.rowcount or 0
                day += timedelta(days=1)
        await db.commit()
        logger.info(f'[baseline] 报告期偏差回填完成：{counts}')
        return counts

    @classmethod
    async def _compute_day_deviation(
        cls,
        db: AsyncSession,
        baseline: EEnergyBaseline,
        buckets: dict[str, dict[str, float]],
        day: date,
        object_type: str = 'system',
        object_id: int = 0,
    ) -> Decimal | None:
        """
        单日偏差：actual（基线 scope 对应对象的日总量）vs expected(from baseline buckets)
        expected = peak_mean × 6h + flat_mean × 10h + valley_mean × 8h
                   （按当日 workday_flag 选 workday/weekend 桶）
        """
        row = (
            await db.execute(
                select(EStatDay).where(
                    EStatDay.object_type == object_type,
                    EStatDay.object_id == object_id,
                    EStatDay.energy_type_code == baseline.energy_type_code,
                    EStatDay.stat_date == day,
                )
            )
        ).scalars().first()
        if row is None or row.total_value is None:
            return None
        # workday 判定
        is_workday = 0 if day.weekday() >= 5 else 1  # noqa: PLR2004
        prefix = 'workday' if is_workday else 'weekend'
        expected = 0.0
        for tou in ('peak', 'flat', 'valley'):
            b = buckets.get(f'{prefix}_{tou}')
            if not b:
                continue
            expected += b['mean'] * _hours_in_period(tou, is_workday)
        if expected <= 0:
            return None
        actual = float(row.total_value)
        deviation = (actual - expected) / expected * 100.0
        return Decimal(str(round(deviation, 4)))
