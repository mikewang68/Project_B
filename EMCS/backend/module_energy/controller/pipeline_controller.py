"""
统计管道触发接口（backend 内部运维用，非 demo 页面展示）
REQ 锚点：REQ-024~028 · REQ-020（重算）· REQ-060（总览刷新触发）

端点：
- POST /pipeline/aggregation/rebuild-all       全量重跑 hour/day/month × equipment/area
- POST /pipeline/aggregation/rebuild-range     指定时间窗增量重跑

后续 task #14 阶段 2 加：baseline/rules/cost 的触发；APScheduler 定时接管。
"""

from datetime import datetime
from typing import Annotated

from fastapi import Query, Request, Response
from sqlalchemy.ext.asyncio import AsyncSession

from common.aspect.db_seesion import DBSessionDependency
from common.aspect.pre_auth import PreAuthDependency
from common.router import APIRouterPro
from module_energy.service.aggregation_service import AggregationService
from module_energy.service.baseline_service import BaselineService
from module_energy.service.cost_service import CostService
from module_energy.service.rule_engine_service import RuleEngineService
from utils.log_util import logger
from utils.response_util import ResponseUtil

pipeline_controller = APIRouterPro(
    prefix='/pipeline',
    order_num=60,
    tags=['能源管控-统计管道'],
    dependencies=[PreAuthDependency()],
)


@pipeline_controller.post(
    '/aggregation/rebuild-all',
    summary='全量重跑统计聚合',
    description='bootstrap 用：读取全部 e_raw_reading，重建 hour/day/month × equipment/area。'
    ' 支持任意重跑（幂等 upsert）。REQ-024~028',
)
async def rebuild_all_aggregation(
    request: Request,
    query_db: Annotated[AsyncSession, DBSessionDependency()],
) -> Response:
    counts = await AggregationService.rebuild_all(query_db)
    logger.info(f'[pipeline] rebuild-all 完成 rowcounts={counts}')
    return ResponseUtil.success(msg='聚合重跑完成', data=counts)


@pipeline_controller.post(
    '/aggregation/rebuild-range',
    summary='指定时间窗增量重跑统计聚合',
    description='REQ-020 重算触发：给定 [start, end) 时间窗刷 hour/day/month 各级。',
)
async def rebuild_range_aggregation(
    request: Request,
    query_db: Annotated[AsyncSession, DBSessionDependency()],
    start: Annotated[datetime, Query(description='起始时间 ISO8601')],
    end: Annotated[datetime, Query(description='结束时间 ISO8601（不含）')],
) -> Response:
    counts = await AggregationService.rebuild_range(query_db, start, end)
    logger.info(f'[pipeline] rebuild-range {start}→{end} rowcounts={counts}')
    return ResponseUtil.success(msg='聚合重跑完成', data=counts)


@pipeline_controller.post(
    '/baseline/publish',
    summary='计算并发布能源基线（REQ-029）',
    description='读取 e_energy_baseline draft → 6 桶均值+σ → status=published；'
    ' 回填报告期日偏差到 e_stat_day.baseline_deviation_pct',
)
async def publish_baseline(
    request: Request,
    query_db: Annotated[AsyncSession, DBSessionDependency()],
) -> Response:
    publish_result = await BaselineService.compute_and_publish(query_db)
    dev_counts = await BaselineService.compute_daily_deviation(query_db)
    logger.info(f'[pipeline] baseline publish + deviation: {publish_result} / {dev_counts}')
    return ResponseUtil.success(
        msg='基线发布 + 报告期偏差回填完成',
        data={'publish': publish_result, 'deviation': dev_counts},
    )


@pipeline_controller.post(
    '/rules/run',
    summary='跑规则引擎 R01–R11（重建 e_alert_event，REQ-039~044）',
    description='对原始数据/统计/基线做真实判定，全量刷 e_alert_event；'
    ' 契约裁决 2026-07-13：告警不 seed，只由此接口的引擎产出',
)
async def run_rules(
    request: Request,
    query_db: Annotated[AsyncSession, DBSessionDependency()],
) -> Response:
    counts = await RuleEngineService.run_all(query_db)
    logger.info(f'[pipeline] rules.run counts={counts}')
    return ResponseUtil.success(msg='规则引擎评估完成', data=counts)


@pipeline_controller.post(
    '/cost/rebuild',
    summary='显式初始化月度成本 v1（REQ-051~056、REQ-062）',
    description='管理端显式调用：仅在成本表为空时从统计输入创建不可变 v1；已有版本时只做完整性检查，'
    '绝不 UPSERT 或覆盖金额。后续变化必须调用 /cost/recomputations。',
)
async def rebuild_cost(
    request: Request,
    query_db: Annotated[AsyncSession, DBSessionDependency()],
) -> Response:
    result = await CostService.initialize_v1(query_db, computed_by='pipeline-cost-initialize')
    integrity = await CostService.validate_current_integrity(query_db)
    logger.info(f'[pipeline] cost.initialize result={result} integrity={integrity}')
    return ResponseUtil.success(
        msg='成本 v1 初始化/完整性检查完成',
        data={'initialize': result, 'currentIntegrity': integrity},
    )


@pipeline_controller.post(
    '/bootstrap',
    summary='一键 bootstrap（聚合 → 基线 → v1 初始化 → 完整性检查 → 规则）',
    description='顺序执行：aggregation → baseline → initialize_v1 → current 完整性检查 → rules；'
    '用于首次拉起或数据集刷新。R10 只能在完整 current 成本批次就绪后运行。'
    ' force_republish=true 时 baseline 无条件重算（口径变更后必用）',
)
async def bootstrap(
    request: Request,
    query_db: Annotated[AsyncSession, DBSessionDependency()],
    force_republish: Annotated[
        bool,
        Query(description='为 true 时基线无条件重算（聚合口径变更后需要）'),
    ] = False,
) -> Response:
    agg = await AggregationService.rebuild_all(query_db)
    baseline_publish = await BaselineService.compute_and_publish(query_db, force_republish=force_republish)
    baseline_dev = await BaselineService.compute_daily_deviation(query_db)
    cost_initialize = await CostService.initialize_v1(
        query_db,
        computed_by='pipeline-bootstrap',
    )
    cost_integrity = await CostService.validate_current_integrity(query_db)
    rules = await RuleEngineService.run_all(query_db)
    return ResponseUtil.success(
        msg='bootstrap 完成',
        data={
            'aggregation': agg,
            'baseline_publish': baseline_publish,
            'baseline_deviation': baseline_dev,
            'cost_initialize': cost_initialize,
            'cost_current_integrity': cost_integrity,
            'rules': rules,
        },
    )
