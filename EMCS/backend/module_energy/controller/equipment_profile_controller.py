"""第三幕设备能耗画像 API（REQ-031~035）。"""

from datetime import datetime
from typing import Annotated, Literal

from fastapi import Path, Query, Request, Response
from sqlalchemy.ext.asyncio import AsyncSession

from common.aspect.db_seesion import DBSessionDependency
from common.aspect.interface_auth import UserInterfaceAuthDependency
from common.aspect.pre_auth import PreAuthDependency
from common.router import APIRouterPro
from module_energy.service.equipment_profile_service import EquipmentProfileService
from utils.response_util import ResponseUtil

equipment_profile_controller = APIRouterPro(
    prefix='/equipment-profiles',
    order_num=67,
    tags=['能源管控-设备能耗画像'],
    dependencies=[PreAuthDependency()],
)


@equipment_profile_controller.get(
    '',
    summary='设备画像卡片墙',
    dependencies=[UserInterfaceAuthDependency('energy:analysis:profile')],
)
async def list_equipment_profiles(
    request: Request,
    query_db: Annotated[AsyncSession, DBSessionDependency()],
    zone: Annotated[Literal['ALL', 'A', 'B'], Query()] = 'ALL',
    energy_type: Annotated[Literal['ELEC', 'WATER', 'AIR'], Query(alias='energyType')] = 'ELEC',
    period_start: Annotated[datetime | None, Query(alias='periodStart')] = None,
    period_end: Annotated[datetime | None, Query(alias='periodEnd')] = None,
) -> Response:
    # REQ-034：设备卡片热度与异常数都从当前筛选结果真算。
    payload = await EquipmentProfileService.list_profiles(
        query_db,
        zone=zone,
        energy_type=energy_type,
        period_start=period_start,
        period_end=period_end,
    )
    return ResponseUtil.success(msg='equipment profiles', data=payload)


@equipment_profile_controller.get(
    '/{equipment_code}',
    summary='单设备能耗画像',
    dependencies=[UserInterfaceAuthDependency('energy:analysis:profile')],
)
async def get_equipment_profile(
    request: Request,
    query_db: Annotated[AsyncSession, DBSessionDependency()],
    equipment_code: Annotated[str, Path(pattern=r'^[A-Z0-9-]+$')],
    energy_type: Annotated[Literal['ELEC', 'WATER', 'AIR'], Query(alias='energyType')] = 'ELEC',
    period_start: Annotated[datetime | None, Query(alias='periodStart')] = None,
    period_end: Annotated[datetime | None, Query(alias='periodEnd')] = None,
    event_id: Annotated[int | None, Query(alias='eventId', ge=1)] = None,
) -> Response:
    # REQ-033~035：状态叠加、工单对照和疑似低效证据同时间窗返回。
    payload = await EquipmentProfileService.get_profile(
        query_db,
        equipment_code=equipment_code,
        energy_type=energy_type,
        period_start=period_start,
        period_end=period_end,
        event_id=event_id,
    )
    return ResponseUtil.success(msg='equipment profile', data=payload)
