"""
原始数据与质量 API（第二幕 · 契约 docs/mock-contracts.md §2）
REQ 锚点：REQ-010 采集字段 · 011 幂等键（点位+时间）· 012 迟到/补采重算标记
         013 采集任务状态 · 014 P0 断网缓存与补传可视 · 018 质量枚举 · 019 覆盖率两档
         020 重算任务 · 022 原始/换算/修正/估算对应 · 023 质量总览 · 062 口径签名

端点：
- GET  /raw-quality/summary     汇总（点位树 + 读数 + 任务 + 补传 + 重算 + 质量分布）
- POST /raw-quality/backfill    触发补传（REQ-014）—— 写：raw_reading + e_collect_task + e_recompute_log
- POST /raw-quality/recompute   触发重算（REQ-020）—— 写：e_recompute_log 更新 + 聚合刷新

字段形状严格对齐 web/src/api/rawQuality.js + web/src/views/raw-quality/mock.js（team-lead
2026-07-13 契约裁决 §2.2 响应形状 = {batch, derivedRecomputeHints[]}）。

写操作复位约束：所有 mutate（backfill/recompute）落在 e_collect_task / e_recompute_log /
e_raw_reading（更新 quality_state 与 is_backfill），重跑 datagen/generate_demo_data.py 全量复位
（DROP TABLE + INSERT 覆盖）——满足契约 2.2/2.3 的"演示可重复"硬约束。
"""

from datetime import datetime
from typing import Annotated, Literal

from fastapi import Body, Query, Request, Response
from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel
from sqlalchemy.ext.asyncio import AsyncSession

from common.aspect.db_seesion import DBSessionDependency
from common.aspect.pre_auth import PreAuthDependency
from common.context import RequestContext
from common.router import APIRouterPro
from module_energy.service.raw_quality_service import RawQualityService
from utils.log_util import logger
from utils.response_util import ResponseUtil


class BackfillRequest(BaseModel):
    """POST /raw-quality/backfill 请求体（camelCase 对齐前端契约）"""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    point_id: str = Field(description='采集点编号（e_meter_point.point_code）')
    cache_start: datetime = Field(description='缓存起（断传起始，ISO8601）')
    cache_end: datetime = Field(description='缓存止（断传结束，ISO8601）')
    dry_run: bool = Field(default=False, description='true=只返回计划不落库（REQ-014 preview）')


class RecomputeRequest(BaseModel):
    """POST /raw-quality/recompute 请求体"""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    period_key: str = Field(description='周期键（如 2026-07-06D / 2026-07M）')
    scope: Literal['day', 'month'] = Field(description='重算范围')


raw_quality_controller = APIRouterPro(
    prefix='/raw-quality',
    order_num=65,
    tags=['能源管控-原始数据与质量'],
    dependencies=[PreAuthDependency()],
)


@raw_quality_controller.get(
    '/summary',
    summary='原始数据与质量汇总（第二幕主页面）',
    description='返回点位树 + 选中点位读数 + 采集任务 + 补传批次 + 重算提示 + 质量分布。'
    ' 字段形状严格对齐 docs/mock-contracts.md §2.1 + web/src/views/raw-quality/mock.js。'
    ' 默认时间窗 = INJ-01 主案例 2026-07-06 06:00–18:00（覆盖 09:20-13:40 断传窗）',
)
async def raw_quality_summary(
    request: Request,
    query_db: Annotated[AsyncSession, DBSessionDependency()],
    point_id: Annotated[str | None, Query(alias='pointId', description='采集点编号')] = None,
    time_start: Annotated[datetime | None, Query(alias='timeStart', description='ISO8601')] = None,
    time_end: Annotated[datetime | None, Query(alias='timeEnd', description='ISO8601')] = None,
    zone: Annotated[Literal['ALL', 'A', 'B'], Query(description='装卸区 ALL/A/B')] = 'ALL',
) -> Response:
    payload = await RawQualityService.get_summary(
        query_db,
        point_id=point_id,
        time_start=time_start,
        time_end=time_end,
        zone=zone,
    )
    return ResponseUtil.success(msg='raw-quality summary', data=payload)


@raw_quality_controller.post(
    '/backfill',
    summary='触发补传（REQ-014 P0）',
    description='写操作：quality_state miss→fix + is_backfill=1；'
    ' 幂等键 (point_id, sample_time)（REQ-011）；同时 INSERT e_collect_task（backfilled）'
    ' 与派生 pending recompute hint（e_recompute_log）。'
    ' 响应契约 §2.2：{batch, derivedRecomputeHints[]}。'
    ' dryRun=true 只返回计划、不落库。复位：重跑 datagen/generate_demo_data.py',
)
async def raw_quality_backfill(
    request: Request,
    query_db: Annotated[AsyncSession, DBSessionDependency()],
    body: Annotated[BackfillRequest, Body()],
) -> Response:
    current_user = RequestContext.get_current_user()
    operator = current_user.user.user_name if (current_user and current_user.user) else 'anonymous'
    payload = await RawQualityService.do_backfill(
        query_db,
        point_id=body.point_id,
        cache_start=body.cache_start,
        cache_end=body.cache_end,
        dry_run=body.dry_run,
        operator=operator,
    )
    logger.info(
        f'[raw-quality] backfill point={body.point_id} '
        f'window=[{body.cache_start}, {body.cache_end}) dry={body.dry_run} '
        f'batch={payload.get("batch", {}).get("batchId")}'
    )
    return ResponseUtil.success(msg='backfill 完成', data=payload)


@raw_quality_controller.post(
    '/recompute',
    summary='触发重算（REQ-012 / 020）',
    description='写操作：找到 pending e_recompute_log 行 → 重跑该周期聚合（rebuild_range）'
    ' → 计算 old/new 差异 → 回写 delta_value / delta_pct / new_version_no。'
    ' 响应契约 §2.3：更新后的 RecomputeHint（含 diffSummary）。复位：重跑 datagen',
)
async def raw_quality_recompute(
    request: Request,
    query_db: Annotated[AsyncSession, DBSessionDependency()],
    body: Annotated[RecomputeRequest, Body()],
) -> Response:
    current_user = RequestContext.get_current_user()
    operator = current_user.user.user_name if (current_user and current_user.user) else 'anonymous'
    payload = await RawQualityService.do_recompute(
        query_db,
        period_key=body.period_key,
        scope=body.scope,
        operator=operator,
    )
    logger.info(
        f'[raw-quality] recompute period={body.period_key} scope={body.scope} '
        f'delta_pct={payload.get("diffSummary", {}).get("deltaPct") if isinstance(payload.get("diffSummary"), dict) else None}'
    )
    return ResponseUtil.success(msg='recompute 完成', data=payload)
