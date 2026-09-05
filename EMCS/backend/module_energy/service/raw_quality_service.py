"""
原始数据与质量 service（第二幕 · 契约 docs/mock-contracts.md §2）
REQ 锚点：REQ-010 采集字段 · 011 幂等键（点位+时间）· 012 迟到/补采重算标记
         013 采集任务状态 · 014 P0 断网缓存与补传可视 · 018 质量枚举 · 019 覆盖率两档
         020 重算任务 · 022 原始/换算/修正/估算对应 · 023 质量总览 · 062 口径签名

契约字段形状严格对齐 `web/src/api/rawQuality.js` + `web/src/views/raw-quality/mock.js`。

写操作复位约束：所有 mutate（backfill/recompute）落库在 e_collect_task / e_recompute_log /
e_raw_reading（更新 quality_state 与 is_backfill），重跑 datagen/generate_demo_data.py 全量复位
（DROP TABLE + INSERT 覆盖）——满足契约 2.2/2.3 的"演示可重复"硬约束。
"""

import hashlib
import json
from datetime import date, datetime, timedelta
from decimal import Decimal
from typing import Any

from sqlalchemy import and_, func, or_, select, update
from sqlalchemy.ext.asyncio import AsyncSession

from module_energy.entity.do.collect_task_do import ECollectTask
from module_energy.entity.do.energy_baseline_do import EEnergyBaseline
from module_energy.entity.do.equipment_do import EEquipment
from module_energy.entity.do.meter_point_do import EMeterPoint
from module_energy.entity.do.raw_reading_do import ERawReading
from module_energy.entity.do.recompute_log_do import ERecomputeLog
from module_energy.entity.do.stat_day_do import EStatDay
from module_energy.entity.do.stat_month_do import EStatMonth
from module_energy.entity.do.tariff_version_do import ETariffVersion
from module_energy.service.aggregation_service import AggregationService
from module_energy.service.demo_now_util import get_demo_now
from utils.log_util import logger

# 常量（避免 PLR2004 magic values）
_AREA_A_ID = 1
_AREA_B_ID = 2
_TASK_NAME_SCOPE_MAX = 40  # point_scope 显示裁剪阈值

_DEMO_SEED = 42
_VERSION = 'v0.3.4-demo'
_FORMULA_VERSION_DEFAULT = 'f-1.3'

# 覆盖率两档（REQ-019）
_COV_WARN = 95  # ≥95 ok
_COV_SERIOUS = 80  # <80 insufficient
# 质量八态（附录 C）；demo 主动构造 ok/miss/late/est/fix/jump 前 6 态
_QUALITY_STATES: tuple[str, ...] = ('ok', 'miss', 'late', 'dup', 'jump', 'est', 'fix', 'frozen')
_VALID_QUALITY_CODES: tuple[str, ...] = ('ok', 'late', 'est', 'fix')

# 前端 energyType → DB
_ENERGY_TYPE_MAP: dict[str, str] = {
    'ELEC': 'electricity',
    'WATER': 'water',
    'AIR': 'compressed_air',
}
_ENERGY_TYPE_INV: dict[str, str] = {v: k for k, v in _ENERGY_TYPE_MAP.items()}
_ZONE_TO_AREA_ID: dict[str, int | None] = {'ALL': None, 'A': 1, 'B': 2}


class RawQualityService:
    """第二幕原始数据与质量：汇总 + 补传 + 重算"""

    # ------------------------------------------------------------------
    # GET /raw-quality/summary
    # ------------------------------------------------------------------

    @classmethod
    async def get_summary(
        cls,
        db: AsyncSession,
        *,
        point_id: str | None = None,
        time_start: datetime | None = None,
        time_end: datetime | None = None,
        zone: str = 'ALL',
    ) -> dict[str, Any]:
        """汇总（REQ-010~023、062）"""
        demo_now = await get_demo_now(db)
        # 默认时间窗：INJ-01 主案例日 07-06 06:00 → 18:00（fits demo narrative）
        scene_date = date(2026, 7, 6)
        if time_start is None:
            time_start = datetime.combine(scene_date, datetime.min.time()) + timedelta(hours=6)
        if time_end is None:
            time_end = datetime.combine(scene_date, datetime.min.time()) + timedelta(hours=18)

        area_id_filter = _ZONE_TO_AREA_ID.get(zone)

        # 主数据：点位树 + 设备名 + 区名
        points_tree = await cls._build_points_tree(db, area_id_filter)
        # 选中点位（缺省用主案例点 GC-A1-E，若不在筛选后的点位内则用 tree[0]）
        selected_point = await cls._resolve_selected_point(db, point_id, points_tree)

        # 读数（含 outage 窗内的 miss/fix）
        readings = await cls._fetch_readings(db, selected_point, time_start, time_end)
        # 覆盖率与质量分布
        coverage_info = cls._compute_coverage(readings)
        quality_breakdown = cls._compute_quality_breakdown(readings)

        # 采集任务（近 30 日 + failed/backfilled 全量）
        tasks = await cls._fetch_tasks(db, demo_now, area_id_filter)
        # 补传批次（从 backfilled 任务派生）
        backfill_batches = await cls._fetch_backfill_batches(db, demo_now, area_id_filter)
        # 重算提示（pending/done）
        recompute_hints = await cls._fetch_recompute_hints(db)

        # 签名
        baseline = (
            await db.execute(
                select(EEnergyBaseline).where(EEnergyBaseline.status == 'published')
            )
        ).scalars().first()
        tariffs = (
            await db.execute(
                select(ETariffVersion).where(ETariffVersion.effective_to.is_(None))
            )
        ).scalars().all()
        signature = cls._build_signature(
            zone=zone,
            point_id=selected_point.point_code if selected_point else None,
            time_start=time_start,
            time_end=time_end,
            coverage=coverage_info['pct'],
            baseline=baseline,
            tariffs=tariffs,
            now=demo_now,
        )

        return {
            'signature': signature,
            'demoState': cls._build_demo_state(selected_point),
            'filters': {
                'pointId': selected_point.point_code if selected_point else None,
                'timeStart': time_start.strftime('%Y-%m-%d %H:%M:%S'),
                'timeEnd': time_end.strftime('%Y-%m-%d %H:%M:%S'),
                'zone': zone,
            },
            'points': points_tree,
            'selected': cls._build_selected(selected_point, coverage_info, tasks),
            'readings': readings,
            'coverage': coverage_info,
            'tasks': tasks,
            'backfillBatches': backfill_batches,
            'recomputeHints': recompute_hints,
            'qualityBreakdown': quality_breakdown,
        }

    # ------------------------------------------------------------------
    # POST /raw-quality/backfill
    # ------------------------------------------------------------------

    @classmethod
    async def do_backfill(
        cls,
        db: AsyncSession,
        *,
        point_id: str,
        cache_start: datetime,
        cache_end: datetime,
        dry_run: bool = False,
        operator: str = 'ops_user',
    ) -> dict[str, Any]:
        """
        触发补传（REQ-014 P0）——真实写：
        1. 命中 point_id×[cache_start, cache_end) 中 quality_state='miss' 的读数
        2. UPDATE 为 quality_state='fix', is_backfill=1, ingest_time=now, source_batch=<batchNo>
        3. INSERT e_collect_task（task_status='backfilled', parent 指向原 failed 任务如有）
        4. 派生日/月 recompute hint 落 e_recompute_log（status=pending）
        dryRun=True 只做 SELECT 与 preview，不写。

        幂等：如已 backfill（miss=0 但存在 is_backfill=1 的 fix），返回历史 batch 快照。
        """
        point = await cls._get_point_by_code(db, point_id)
        if point is None:
            return {'batch': None, 'derivedRecomputeHints': [],
                    'error': f'pointId not found: {point_id}'}
        # 提前捕获属性到 locals：后续 commit 会 expire ORM 对象，避免懒加载重触 IO
        # 引发 MissingGreenlet；同名局部变量在 dryRun / 幂等 replay / 实做路径统一使用
        point_pk = int(point.point_id)
        point_code_val = str(point.point_code)
        point_area_id = int(point.area_id) if point.area_id is not None else None
        point_energy_type = str(point.energy_type_code) if point.energy_type_code else None

        miss_rows = (
            await db.execute(
                select(ERawReading.reading_id, ERawReading.sample_time).where(
                    ERawReading.point_id == point_pk,
                    ERawReading.sample_time >= cache_start,
                    ERawReading.sample_time < cache_end,
                    ERawReading.quality_state == 'miss',
                )
            )
        ).all()
        existing_backfill = (
            await db.execute(
                select(func.count(ERawReading.reading_id)).where(
                    ERawReading.point_id == point_pk,
                    ERawReading.sample_time >= cache_start,
                    ERawReading.sample_time < cache_end,
                    ERawReading.is_backfill == 1,
                )
            )
        ).scalar() or 0

        n_to_backfill = len(miss_rows)
        # dryRun：只返回计划
        if dry_run:
            return {
                'batch': {
                    'batchId': 'DRY-RUN',
                    'triggeredBy': operator,
                    # P-09：dryRun triggeredAt 展示给前端，须锚定 DEMO_NOW
                    'triggeredAt': (await get_demo_now(db)).strftime('%Y-%m-%d %H:%M:%S'),
                    'cacheStart': cache_start.strftime('%Y-%m-%d %H:%M:%S'),
                    'cacheEnd': cache_end.strftime('%Y-%m-%d %H:%M:%S'),
                    'pointIds': [point_code_val],
                    'recordsIngested': n_to_backfill,
                    'dupHandledCount': int(existing_backfill),
                    'failureReason': None,
                    'status': 'dryRun',
                    'affectedPeriods': cls._derive_period_keys(cache_start, cache_end),
                },
                'derivedRecomputeHints': [],
            }

        # 幂等：已 backfill、无 miss → 返回历史 batch 快照
        if n_to_backfill == 0 and int(existing_backfill) > 0:
            latest_task = (
                await db.execute(
                    select(ECollectTask).where(
                        ECollectTask.task_status == 'backfilled',
                        ECollectTask.outage_start <= cache_start,
                        ECollectTask.outage_end >= cache_end,
                    ).order_by(ECollectTask.task_id.desc())
                )
            ).scalars().first()
            if latest_task:
                batch_dict = cls._task_to_batch_dict(latest_task, [point_code_val])
                # 用实际 is_backfill=1 计数覆盖 _task_to_batch_dict 的近似值，
                # 前端展示"已补传条数"更真
                batch_dict['recordsIngested'] = int(existing_backfill)
                derived_hints = await cls._fetch_recompute_hints_for_periods(
                    db, cls._derive_period_keys(cache_start, cache_end),
                )
                return {
                    'batch': batch_dict,
                    'derivedRecomputeHints': derived_hints,
                    'idempotentReplay': True,
                }

        # 实做补传
        # P-09：now 落到 ingest_time / scheduled_time / actual_ingest_time / create_time，
        # 前端补传时间线/审计视图直接读取，须锚定 DEMO_NOW
        now = await get_demo_now(db)
        batch_no = cls._next_batch_no(cache_start)
        # 1) 更新 raw_reading —— 同时估算 incremental_value（用同点位同日 ok/late 均值），
        #    否则 fix 行 incremental 恒为 NULL，重算 diff 恒为 0，演示叙事失效
        if n_to_backfill > 0:
            reading_ids = [row.reading_id for row in miss_rows]
            est_incr = (
                await db.execute(
                    select(func.avg(ERawReading.incremental_value)).where(
                        ERawReading.point_id == point_pk,
                        ERawReading.sample_time >= cache_start,
                        ERawReading.sample_time < cache_end,
                        ERawReading.quality_state.in_(('ok', 'late')),
                        ERawReading.incremental_value.isnot(None),
                    )
                )
            ).scalar()
            est_incr_val = float(est_incr) if est_incr is not None else 0.0
            await db.execute(
                update(ERawReading)
                .where(ERawReading.reading_id.in_(reading_ids))
                .values(
                    quality_state='fix',
                    is_backfill=1,
                    ingest_time=now,
                    source_batch=batch_no,
                    correction_reason=f'REQ-014 补传批次 {batch_no} · 估算增量={est_incr_val:.4f}',
                    incremental_value=est_incr_val,
                )
            )
        # 2) 落 collect_task
        # 找同 point + 时间窗内的 failed 任务作 parent_task_id
        parent_task = (
            await db.execute(
                select(ECollectTask).where(
                    ECollectTask.task_status == 'failed',
                    ECollectTask.outage_start <= cache_start,
                    ECollectTask.outage_end >= cache_end,
                ).order_by(ECollectTask.task_id.desc())
            )
        ).scalars().first()
        task = ECollectTask(
            batch_no=batch_no,
            area_id=point_area_id,
            point_scope=point_code_val,
            scheduled_time=now,
            actual_ingest_time=now,
            task_status='backfilled',
            affected_point_count=1,
            failure_reason=None,
            retry_count=0,
            outage_start=cache_start,
            outage_end=cache_end,
            parent_task_id=parent_task.task_id if parent_task else None,
            create_time=now,
        )
        db.add(task)
        await db.flush()
        # 3) 派生 pending recompute hints
        derived: list[dict[str, Any]] = []
        for period_key in cls._derive_period_keys(cache_start, cache_end):
            scope = 'day' if period_key.endswith('D') else 'month'
            target_key = {
                'period_key': period_key,
                'scope': scope,
                'point_code': point_code_val,
                'energy_type': point_energy_type,
            }
            # 幂等：同 target_key 已存在的 pending 复用
            existing = (
                await db.execute(
                    select(ERecomputeLog).where(
                        ERecomputeLog.target_key_json == json.dumps(target_key, ensure_ascii=False),
                        ERecomputeLog.new_version_no.is_(None),
                    )
                )
            ).scalars().first()
            if existing:
                derived.append(cls._recompute_log_to_hint(existing))
                continue
            row = ERecomputeLog(
                target_table='e_stat_day' if scope == 'day' else 'e_stat_month',
                target_key_json=json.dumps(target_key, ensure_ascii=False),
                old_version_no=await cls._get_stat_version(db, period_key, scope, point_energy_type or 'electricity'),
                new_version_no=None,
                delta_value=None,
                delta_pct=None,
                trigger_reason=f'REQ-020 补传触发 · batch={batch_no}',
                operator=operator,
                created_at=now,
            )
            db.add(row)
            await db.flush()
            derived.append(cls._recompute_log_to_hint(row))

        await db.commit()
        logger.info(
            f'[raw-quality] backfill 完成 point={point_code_val} '
            f'batch={batch_no} rows={n_to_backfill} derived={len(derived)}'
        )
        return {
            'batch': {
                'batchId': batch_no,
                'triggeredBy': operator,
                'triggeredAt': now.strftime('%Y-%m-%d %H:%M:%S'),
                'cacheStart': cache_start.strftime('%Y-%m-%d %H:%M:%S'),
                'cacheEnd': cache_end.strftime('%Y-%m-%d %H:%M:%S'),
                'pointIds': [point_code_val],
                'recordsIngested': n_to_backfill,
                'dupHandledCount': int(existing_backfill),
                'failureReason': None,
                'status': 'succeeded',
                'affectedPeriods': cls._derive_period_keys(cache_start, cache_end),
            },
            'derivedRecomputeHints': derived,
        }

    # ------------------------------------------------------------------
    # POST /raw-quality/recompute
    # ------------------------------------------------------------------

    @classmethod
    async def do_recompute(
        cls, db: AsyncSession, *, period_key: str, scope: str, operator: str = 'ops_user',
    ) -> dict[str, Any]:
        """
        触发重算（REQ-012 / 020）：
        1. 找 target_key_json.period_key == :period_key 的 pending log 行
        2. 计算 old vs new：old = log.old_version_no 时的 stat_value（现存），new = 重算后的
           stat_value。因数据集固定，重算 =重新聚合。差量 = new - old。
        3. UPDATE stat_day / stat_month version_no += 1；写回 log 的 new_version_no 与
           delta_value / delta_pct；返回 hint dict
        """
        target_key_filter = f'"period_key": "{period_key}"'
        log = (
            await db.execute(
                select(ERecomputeLog).where(
                    ERecomputeLog.target_key_json.like(f'%{target_key_filter}%'),
                    ERecomputeLog.new_version_no.is_(None),
                ).order_by(ERecomputeLog.id.desc())
            )
        ).scalars().first()
        if log is None:
            return {'error': f'periodKey not found or already recomputed: {period_key}',
                    'periodKey': period_key}

        key = json.loads(log.target_key_json)
        energy_type = key.get('energy_type', 'electricity')
        # 影响点位（backfill 派生 log 时会带上，直接算该点位增量避免层级口径歧义）
        # 若 log 无 point_code（历史或人工触发），退化到 area 汇总
        affected_point_code = key.get('point_code')

        # 计算 old / new
        if scope == 'day' or period_key.endswith('D'):
            stat_date = cls._parse_day_period(period_key)
            day_start = datetime.combine(stat_date, datetime.min.time())
            day_end = day_start + timedelta(days=1)
            # 触发聚合重跑（口径签名 + version_no++）
            await AggregationService.rebuild_range(db, day_start, day_end)
            new_version_no = await cls._get_stat_version(db, period_key, 'day', energy_type)
            # 差量口径：以受影响 point 为准，直接算点位增量在
            # (补传前, 补传后) 之间的差；这样 device_meter/branch/area 层级都不失真
            if affected_point_code:
                old_val, new_val = await cls._get_point_period_totals(
                    db, affected_point_code, day_start, day_end,
                )
                metric_label = f'{affected_point_code} 当日增量'
            else:
                old_val = new_val = await cls._get_stat_day_total(db, stat_date, energy_type)
                metric_label = '当日总能耗'
        else:
            stat_month = period_key.replace('M', '')
            day = date(int(stat_month[:4]), int(stat_month[5:7]), 1)
            day_start = datetime.combine(day, datetime.min.time())
            day_end = day_start + timedelta(days=31)
            await AggregationService.rebuild_range(db, day_start, day_end)
            new_version_no = await cls._get_stat_version(db, period_key, 'month', energy_type)
            if affected_point_code:
                old_val, new_val = await cls._get_point_period_totals(
                    db, affected_point_code, day_start, day_end,
                )
                metric_label = f'{affected_point_code} 当月增量'
            else:
                old_val = new_val = await cls._get_stat_month_total(db, stat_month, energy_type)
                metric_label = '当月累计能耗'

        old_f = float(old_val or 0)
        new_f = float(new_val or 0)
        delta = new_f - old_f
        delta_pct = (delta / old_f * 100) if old_f > 0 else 0.0

        log.new_version_no = new_version_no
        log.delta_value = Decimal(str(round(delta, 4)))
        log.delta_pct = Decimal(str(round(delta_pct, 4)))
        await db.commit()
        await db.refresh(log)
        logger.info(
            f'[raw-quality] recompute done period={period_key} scope={scope} '
            f'old={old_f} new={new_f} delta_pct={delta_pct:.2f}%'
        )
        hint = cls._recompute_log_to_hint(log)
        # 补充 diffSummary 前端需要的整型/千位视图（用真实值，不做加工）
        hint['diffSummary'] = {
            'metric': metric_label,
            'oldValue': round(old_f, 2),
            'newValue': round(new_f, 2),
            'deltaPct': round(delta_pct, 4),
        }
        # P-09：finishedAt 前端展示"重算完成时间"，须锚定 DEMO_NOW
        hint['finishedAt'] = (await get_demo_now(db)).strftime('%Y-%m-%d %H:%M:%S')
        return hint

    # ==================================================================
    # 内部辅助
    # ==================================================================

    @classmethod
    async def _build_points_tree(
        cls, db: AsyncSession, area_id_filter: int | None,
    ) -> list[dict[str, Any]]:
        """
        点位树 —— 按 zone/deviceId 分组；无 equipment 的 area 级点位归属虚拟设备"区共用"
        返回 flat 列表（前端已用 flatten），保留 zone/deviceId/energyType 便于分组渲染
        """
        stmt = select(EMeterPoint).order_by(EMeterPoint.area_id, EMeterPoint.point_id)
        if area_id_filter is not None:
            stmt = stmt.where(EMeterPoint.area_id == area_id_filter)
        points = (await db.execute(stmt)).scalars().all()
        # 拉设备名 (equipment_id → name)
        equipment_rows = (await db.execute(select(EEquipment))).scalars().all()
        eq_map = {r.equipment_id: r for r in equipment_rows}
        result: list[dict[str, Any]] = []
        for p in points:
            eq = eq_map.get(p.equipment_id) if p.equipment_id else None
            device_id = eq.equipment_code if eq else f'AREA-{p.area_id}'
            device_name = eq.equipment_name if eq else ('A 区共用点' if p.area_id == _AREA_A_ID else 'B 区共用点')
            energy_type_ui = _ENERGY_TYPE_INV.get(p.energy_type_code or '', p.energy_type_code or '')
            result.append({
                'zone': 'A' if p.area_id == 1 else 'B',
                'deviceId': device_id,
                'deviceName': device_name,
                'energyType': energy_type_ui,
                'pointId': p.point_code,
                'pointName': p.point_name,
                'unit': p.unit,
                'samplingInterval': cls._format_interval(p.sample_period_sec),
                'status': cls._status_label(p.status),
            })
        return result

    @classmethod
    async def _resolve_selected_point(
        cls, db: AsyncSession, point_id: str | None, points_tree: list[dict[str, Any]],
    ) -> EMeterPoint | None:
        """按 point_code 定位；缺省用 tree[0]"""
        code = point_id
        if code is None and points_tree:
            code = points_tree[0]['pointId']
        if code is None:
            return None
        return (
            await db.execute(select(EMeterPoint).where(EMeterPoint.point_code == code))
        ).scalars().first()

    @classmethod
    async def _get_point_by_code(cls, db: AsyncSession, point_code: str) -> EMeterPoint | None:
        return (
            await db.execute(select(EMeterPoint).where(EMeterPoint.point_code == point_code))
        ).scalars().first()

    @classmethod
    async def _fetch_readings(
        cls,
        db: AsyncSession,
        point: EMeterPoint | None,
        time_start: datetime,
        time_end: datetime,
    ) -> list[dict[str, Any]]:
        """取选中点位在时间窗内的原始读数（按 sample_time asc）"""
        if point is None:
            return []
        rows = (
            await db.execute(
                select(ERawReading).where(
                    ERawReading.point_id == point.point_id,
                    ERawReading.sample_time >= time_start,
                    ERawReading.sample_time < time_end,
                ).order_by(ERawReading.sample_time)
            )
        ).scalars().all()
        return [
            {
                'ts': r.sample_time.strftime('%Y-%m-%d %H:%M:%S'),
                'cumulative': float(r.cumulative_value) if r.cumulative_value is not None else None,
                'delta': float(r.incremental_value) if r.incremental_value is not None else None,
                'unit': r.unit,
                'quality': r.quality_state,
                'sourceBatchId': r.source_batch,
                'ingestedAt': r.ingest_time.strftime('%Y-%m-%d %H:%M:%S') if r.ingest_time else None,
                'isBackfill': bool(r.is_backfill),
                'remark': r.correction_reason,
                # QA-#23 契约 §2.1 新增 statusValue：状态类点位（point_category='status'）
                # 回填 e_raw_reading.status_value，计量点为 null；前端按此渲染状态带
                'statusValue': r.status_value,
            }
            for r in rows
        ]

    @classmethod
    def _compute_coverage(cls, readings: list[dict[str, Any]]) -> dict[str, Any]:
        total = len(readings) or 1
        good = sum(1 for r in readings if r['quality'] in _VALID_QUALITY_CODES)
        pct = round(good / total * 100, 1)
        if pct < _COV_SERIOUS:
            band = 'insufficient'
        elif pct < _COV_WARN:
            band = 'degraded'
        else:
            band = 'ok'
        return {
            'pct': pct,
            'threshold': {'warn': _COV_WARN, 'serious': _COV_SERIOUS},
            'band': band,
            'missingSlotCount': total - good,
        }

    @classmethod
    def _compute_quality_breakdown(
        cls, readings: list[dict[str, Any]],
    ) -> list[dict[str, Any]]:
        total = len(readings) or 1
        counts: dict[str, int] = dict.fromkeys(_QUALITY_STATES, 0)
        first_ts_by_state: dict[str, str] = {}
        for r in readings:
            q = r['quality'] if r['quality'] in counts else 'ok'
            counts[q] = counts.get(q, 0) + 1
            first_ts_by_state.setdefault(q, r['ts'])
        result: list[dict[str, Any]] = []
        for q, c in counts.items():
            if c == 0:
                continue
            item: dict[str, Any] = {
                'quality': q,
                'count': c,
                'pct': round(c / total * 100, 1),
            }
            if q in ('jump', 'miss', 'late', 'fix', 'est'):
                item['sampleTs'] = first_ts_by_state.get(q)
            result.append(item)
        return result

    @classmethod
    async def _fetch_tasks(
        cls, db: AsyncSession, demo_now: datetime, area_id_filter: int | None,
    ) -> list[dict[str, Any]]:
        """
        采集任务：failed / backfilled / late 全量 + 近 3 日 success 抽样
        转 UI 结构：taskId/taskName/dataSource/lastSuccessAt/lastFailureAt/lastError/
                  failureBatchCount/currentState/affectedPoints[]/samplingInterval/timeoutThreshold
        """
        conds = [ECollectTask.task_status.in_(('failed', 'backfilled', 'late'))]
        if area_id_filter is not None:
            conds.append(ECollectTask.area_id == area_id_filter)
        anomaly_tasks = (
            await db.execute(
                select(ECollectTask).where(and_(*conds)).order_by(ECollectTask.scheduled_time.desc())
            )
        ).scalars().all()
        # 近 3 日 success 抽样一条
        success_tasks = (
            await db.execute(
                select(ECollectTask).where(
                    ECollectTask.task_status == 'success',
                    ECollectTask.scheduled_time >= demo_now - timedelta(days=3),
                ).order_by(ECollectTask.scheduled_time.desc()).limit(1)
            )
        ).scalars().all()
        all_tasks = list(anomaly_tasks) + list(success_tasks)
        result: list[dict[str, Any]] = []
        for t in all_tasks:
            affected = (t.point_scope or '').split(',') if t.point_scope and t.point_scope != 'ALL' else []
            result.append({
                'taskId': t.batch_no,
                'taskName': cls._task_name_hint(t),
                'dataSource': 'gateway',  # datagen 阶段固定 gateway；后续可从 e_meter_point.source_type 派生
                'lastSuccessAt': (t.actual_ingest_time or t.scheduled_time).strftime('%Y-%m-%d %H:%M:%S')
                    if t.task_status in ('success', 'backfilled', 'late') else None,
                'lastFailureAt': t.scheduled_time.strftime('%Y-%m-%d %H:%M:%S')
                    if t.task_status == 'failed' else None,
                'lastError': t.failure_reason,
                'failureBatchCount': int(t.retry_count or 0),
                'currentState': cls._task_state_ui(t.task_status),
                'affectedPoints': affected,
                'samplingInterval': '15min',
                'timeoutThreshold': '45min',
            })
        return result

    @classmethod
    async def _fetch_backfill_batches(
        cls, db: AsyncSession, demo_now: datetime, area_id_filter: int | None,
    ) -> list[dict[str, Any]]:
        conds = [ECollectTask.task_status == 'backfilled']
        if area_id_filter is not None:
            conds.append(ECollectTask.area_id == area_id_filter)
        rows = (
            await db.execute(
                select(ECollectTask).where(and_(*conds)).order_by(ECollectTask.task_id.desc())
            )
        ).scalars().all()
        return [
            cls._task_to_batch_dict(
                t, (t.point_scope or '').split(',') if t.point_scope else [],
            )
            for t in rows
        ]

    @classmethod
    def _task_to_batch_dict(
        cls, task: ECollectTask, point_ids: list[str],
    ) -> dict[str, Any]:
        outage_start = task.outage_start
        outage_end = task.outage_end
        # 补传批次通常"缓存起=离线起，缓存止=离线止"（REQ-014 缓存起止）
        affected_periods: list[str] = []
        if outage_start is not None:
            affected_periods = cls._derive_period_keys(outage_start, outage_end or outage_start)
        return {
            'batchId': task.batch_no,
            'triggeredBy': (task.point_scope.split(',')[0].split('-')[0].lower() if task.point_scope else None) or 'ops_user',
            'triggeredAt': (task.actual_ingest_time or task.scheduled_time).strftime('%Y-%m-%d %H:%M:%S'),
            'cacheStart': outage_start.strftime('%Y-%m-%d %H:%M:%S') if outage_start else None,
            'cacheEnd': outage_end.strftime('%Y-%m-%d %H:%M:%S') if outage_end else None,
            'pointIds': [p for p in point_ids if p],
            'recordsIngested': int(task.affected_point_count or 0) * 17,  # demo 近似
            'dupHandledCount': 0,
            'failureReason': task.failure_reason,
            'status': 'succeeded',
            'affectedPeriods': affected_periods,
        }

    @classmethod
    async def _fetch_recompute_hints(
        cls, db: AsyncSession,
    ) -> list[dict[str, Any]]:
        rows = (
            await db.execute(
                select(ERecomputeLog).order_by(ERecomputeLog.id.desc()).limit(20)
            )
        ).scalars().all()
        return [cls._recompute_log_to_hint(r) for r in rows]

    @classmethod
    async def _fetch_recompute_hints_for_periods(
        cls, db: AsyncSession, period_keys: list[str],
    ) -> list[dict[str, Any]]:
        if not period_keys:
            return []
        conditions = [ERecomputeLog.target_key_json.like(f'%"period_key": "{pk}"%')
                      for pk in period_keys]
        # 用 OR 组合（or_ 已在模块顶部 import）
        rows = (
            await db.execute(
                select(ERecomputeLog).where(or_(*conditions)).order_by(ERecomputeLog.id.desc())
            )
        ).scalars().all()
        return [cls._recompute_log_to_hint(r) for r in rows]

    @classmethod
    def _recompute_log_to_hint(cls, log: ERecomputeLog) -> dict[str, Any]:
        try:
            key = json.loads(log.target_key_json)
        except Exception:
            key = {}
        status = 'pending' if log.new_version_no is None else 'done'
        hint: dict[str, Any] = {
            'periodKey': key.get('period_key'),
            'scope': key.get('scope', 'day'),
            'status': status,
            'triggeredBy': log.trigger_reason or '',
            'oldVersion': f'v{log.old_version_no}' if log.old_version_no else 'v1',
            'newVersion': f'v{log.new_version_no}' if log.new_version_no else None,
            'diffSummary': None,
            'finishedAt': None,
        }
        if log.delta_value is not None:
            # 存量 log 行只回 delta 数值；实时重算的完整 old/new 由 do_recompute 现算并覆盖
            hint['diffSummary'] = {
                'metric': '增量差量（REQ-020）',
                'oldValue': None,
                'newValue': None,
                'deltaValue': float(log.delta_value),
                'deltaPct': float(log.delta_pct or 0),
            }
        return hint

    @classmethod
    async def _get_stat_version(
        cls, db: AsyncSession, period_key: str, scope: str, energy_type: str,
    ) -> int:
        if scope == 'day' or period_key.endswith('D'):
            stat_date = cls._parse_day_period(period_key)
            row = (
                await db.execute(
                    select(func.max(EStatDay.version_no)).where(
                        EStatDay.stat_date == stat_date,
                        EStatDay.energy_type_code == energy_type,
                    )
                )
            ).scalar()
        else:
            stat_month = period_key.replace('M', '')
            row = (
                await db.execute(
                    select(func.max(EStatMonth.version_no)).where(
                        EStatMonth.stat_month == stat_month,
                        EStatMonth.energy_type_code == energy_type,
                    )
                )
            ).scalar()
        return int(row or 1)

    @classmethod
    async def _get_stat_day_total(
        cls, db: AsyncSession, stat_date: date, energy_type: str,
    ) -> Decimal:
        row = (
            await db.execute(
                select(func.sum(EStatDay.total_value)).where(
                    EStatDay.stat_date == stat_date,
                    EStatDay.energy_type_code == energy_type,
                    EStatDay.object_type == 'area',
                )
            )
        ).scalar()
        return Decimal(str(row or 0))

    @classmethod
    async def _get_point_period_totals(
        cls, db: AsyncSession, point_code: str, ts_start: datetime, ts_end: datetime,
    ) -> tuple[Decimal, Decimal]:
        """
        取受影响 point 在 [ts_start, ts_end) 内的两口径合格增量：
          - old = 补传发生前口径（仅计 is_backfill=0）
          - new = 补传发生后口径（含 is_backfill=1 的 fix）
        差量 = new - old = 本次补传对该点位当日/月增量的净影响
        （避免 stat_day/area 层级口径导致的"看不到差"）
        """
        point = await cls._get_point_by_code(db, point_code)
        if point is None:
            return Decimal(0), Decimal(0)
        point_pk = int(point.point_id)
        old_row = (
            await db.execute(
                select(func.coalesce(func.sum(ERawReading.incremental_value), 0)).where(
                    ERawReading.point_id == point_pk,
                    ERawReading.sample_time >= ts_start,
                    ERawReading.sample_time < ts_end,
                    ERawReading.quality_state.in_(_VALID_QUALITY_CODES),
                    ERawReading.is_backfill == 0,
                )
            )
        ).scalar()
        new_row = (
            await db.execute(
                select(func.coalesce(func.sum(ERawReading.incremental_value), 0)).where(
                    ERawReading.point_id == point_pk,
                    ERawReading.sample_time >= ts_start,
                    ERawReading.sample_time < ts_end,
                    ERawReading.quality_state.in_(_VALID_QUALITY_CODES),
                )
            )
        ).scalar()
        return Decimal(str(old_row or 0)), Decimal(str(new_row or 0))

    @classmethod
    async def _get_stat_month_total(
        cls, db: AsyncSession, stat_month: str, energy_type: str,
    ) -> Decimal:
        row = (
            await db.execute(
                select(func.sum(EStatMonth.total_value)).where(
                    EStatMonth.stat_month == stat_month,
                    EStatMonth.energy_type_code == energy_type,
                    EStatMonth.object_type == 'area',
                )
            )
        ).scalar()
        return Decimal(str(row or 0))

    # ------------------------------------------------------------------
    # 小工具
    # ------------------------------------------------------------------

    @classmethod
    def _build_signature(
        cls,
        *,
        zone: str,
        point_id: str | None,
        time_start: datetime,
        time_end: datetime,
        coverage: float,
        baseline: EEnergyBaseline | None,
        tariffs: list[ETariffVersion],
        now: datetime,
    ) -> dict[str, Any]:
        formula_version = baseline.formula_version if (baseline and baseline.formula_version) else _FORMULA_VERSION_DEFAULT
        baseline_version = baseline.baseline_code if baseline else 'BASELINE-N/A'
        price_version = f'v{max((t.effective_from for t in tariffs), default=now.date()).strftime("%Y-%m")}' if tariffs else 'v-none'
        raw = f'{_VERSION}|{formula_version}|{price_version}|{baseline_version}|{zone}|{point_id}|{time_start}|{time_end}'
        sig_hex = hashlib.md5(raw.encode('utf-8')).hexdigest()[:8]
        return {
            'version': _VERSION,
            'formulaVersion': formula_version,
            'priceVersion': price_version,
            'baselineVersion': baseline_version,
            'sigId': f'{sig_hex[:4]}-{sig_hex[4:]}',
            'seed': _DEMO_SEED,
            'generatedAt': now.strftime('%Y-%m-%d %H:%M:%S'),
            'coverage': coverage,
        }

    @classmethod
    def _build_demo_state(cls, point: EMeterPoint | None) -> dict[str, Any]:
        pc = point.point_code if point else '—'
        return {
            'enabled': True,
            'hint': (
                f'第二幕 · 原始数据与质量演示态：选中 {pc}；'
                'INJ-01 A 区电表 07-06 09:20-13:40 断传→补传→重算主案例；'
                'INJ-07 WP-A1 07-05 覆盖率 <80% 严重不足；INJ-02 BC-A1 07-01 单点跳变'
            ),
            'reqAnchor': 'REQ-014 / 019 / 020',
        }

    @classmethod
    def _build_selected(
        cls,
        point: EMeterPoint | None,
        coverage_info: dict[str, Any],
        tasks: list[dict[str, Any]],
    ) -> dict[str, Any]:
        if point is None:
            return {}
        # currentTaskState 用点位涉及的最新任务状态；没有就 running
        current_state = 'running'
        for t in tasks:
            if point.point_code in (t.get('affectedPoints') or []):
                current_state = t.get('currentState') or 'running'
                break
        return {
            'pointId': point.point_code,
            'pointName': point.point_name,
            'deviceId': point.equipment_id or f'AREA-{point.area_id}',
            'deviceName': point.point_name,
            'zone': 'A' if point.area_id == 1 else 'B',
            'energyType': _ENERGY_TYPE_INV.get(point.energy_type_code or '', point.energy_type_code or ''),
            'unit': point.unit,
            'samplingInterval': cls._format_interval(point.sample_period_sec),
            'currentTaskState': current_state,
            'coverageToday': coverage_info['pct'],
        }

    @staticmethod
    def _format_interval(sec: int | None) -> str:
        if sec is None:
            return '—'
        if sec % 60 == 0:
            return f'{sec // 60}min'
        return f'{sec}s'

    @staticmethod
    def _status_label(status: str | None) -> str:
        m = {
            'pending_mapping': '待映射',
            'enabled': '启用',
            'disabled': '停用',
            'maintenance': '维护中',
            'replaced': '已换表',
            'archived': '已归档',
        }
        return m.get(status or 'enabled', status or 'enabled')

    @staticmethod
    def _task_state_ui(status: str) -> str:
        m = {'success': 'running', 'failed': 'failed', 'late': 'degraded', 'backfilled': 'running'}
        return m.get(status, status)

    @staticmethod
    def _task_name_hint(task: ECollectTask) -> str:
        if task.point_scope:
            scope_short = (
                task.point_scope
                if len(task.point_scope) < _TASK_NAME_SCOPE_MAX
                else task.point_scope[: _TASK_NAME_SCOPE_MAX - 3] + '...'
            )
        else:
            scope_short = 'ALL'
        if task.area_id == _AREA_A_ID:
            area = 'A 区'
        elif task.area_id == _AREA_B_ID:
            area = 'B 区'
        else:
            area = '全域'
        return f'{area} · {scope_short}'

    @staticmethod
    def _derive_period_keys(cache_start: datetime, cache_end: datetime) -> list[str]:
        """从 [cache_start, cache_end] 派生日/月 period_key"""
        keys: list[str] = []
        # 日 key：以 cache_start 所在日为准（跨日则加两个）
        d = cache_start.date()
        end_d = cache_end.date()
        while d <= end_d:
            keys.append(f'{d.strftime("%Y-%m-%d")}D')
            d = d + timedelta(days=1)
        # 月 key
        month_key = cache_start.strftime('%Y-%mM')
        if month_key not in keys:
            keys.append(month_key)
        return keys

    @staticmethod
    def _parse_day_period(period_key: str) -> date:
        # "2026-07-06D"
        return datetime.strptime(period_key.rstrip('D'), '%Y-%m-%d').date()

    @staticmethod
    def _next_batch_no(cache_start: datetime) -> str:
        return f'BF-{cache_start.strftime("%Y%m%d-%H%M")}-{datetime.now().strftime("%f")[:3]}'
