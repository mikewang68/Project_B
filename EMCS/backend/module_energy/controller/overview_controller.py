"""
能源总览（驾驶舱）控制器
契约事实来源：docs/mock-contracts.md v0.1 第 1 节 + PRD §5.1
REQ 锚点：REQ-057~062、REQ-019、REQ-029、REQ-039/040/042、REQ-053

端点：GET /overview/summary
- 请求：query 参数 timeRange / zone / energyType（camelCase）
- 响应：走骨架统一 {code, msg, data} 包裹；data 结构见 OverviewPayloadModel
"""

from typing import Annotated

from fastapi import Query, Request, Response
from sqlalchemy.ext.asyncio import AsyncSession

from common.aspect.db_seesion import DBSessionDependency
from common.aspect.pre_auth import PreAuthDependency
from common.router import APIRouterPro
from module_energy.entity.vo.overview_vo import OverviewQueryModel
from module_energy.service.overview_service import OverviewService
from utils.log_util import logger
from utils.response_util import ResponseUtil

# 沿用骨架 APIRouterPro；order_num=50 落在系统管理路由之前但不与其冲突
overview_controller = APIRouterPro(
    prefix='/overview',
    order_num=50,
    tags=['能源管控-能源总览'],
    dependencies=[PreAuthDependency()],
)


@overview_controller.get(
    '/summary',
    summary='能源总览驾驶舱聚合接口',
    description='一次拉取总览页所需的全部数据：口径签名、演示态、6 张 KPI、24h 负荷+基线带、Top5、告警、质量。REQ-057~062、019、029、039/040/042、053',
)
async def get_overview_summary(
    request: Request,
    query: Annotated[OverviewQueryModel, Query()],
    query_db: Annotated[AsyncSession, DBSessionDependency()],
) -> Response:
    # REQ-057：总览页面入口聚合；REQ-058：按 zone / energyType / timeRange 过滤联动
    payload = await OverviewService.get_overview_summary(query_db, query)
    logger.info(
        f'能源总览聚合成功：timeRange={query.time_range}, zone={query.zone}, energyType={query.energy_type}'
    )
    # 用 dict_content={'data': ...} 是为了保留 by_alias 序列化（camelCase）
    return ResponseUtil.success(data=payload.model_dump(by_alias=True))
