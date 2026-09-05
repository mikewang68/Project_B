"""
安全审计写入（REQ-076，INJ-08 载体）
契约裁决 2026-07-13：e_audit_security 不 seed，全部由后端 guard/中间件现场写入

导出：
- record_unauthorized_access(db, ...) 写入一条"越权访问被拦截"审计
"""

import json
from collections.abc import Mapping
from datetime import datetime
from typing import Any

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from module_admin.entity.do.log_do import SysOperLog
from module_energy.entity.do.audit_security_do import EAuditSecurity
from module_energy.service.demo_now_util import get_demo_now


async def record_unauthorized_access(
    db: AsyncSession,
    *,
    user_name: str | None,
    user_role: str | None,
    target_module: str,
    target_resource: str,
    client_ip: str | None,
    user_agent: str | None,
    remark: str | None = None,
) -> int:
    """写一条 unauthorized_access 审计事件；返回 audit_id"""
    # P-09：event_time/create_time 参与"最近审计时间线"展示，须锚定 DEMO_NOW
    now = await get_demo_now(db)
    row = EAuditSecurity(
        event_time=now,
        event_type='unauthorized_access',
        user_name=user_name,
        user_role=user_role,
        target_module=target_module,
        target_resource=target_resource,
        client_ip=client_ip,
        user_agent=user_agent,
        action_result='blocked',
        remark=remark,
        create_time=now,
    )
    db.add(row)
    await db.commit()
    await db.refresh(row)
    return int(row.audit_id)


async def list_recent_security_events(
    db: AsyncSession, limit: int = 20,
) -> list[dict[str, Any]]:
    """最近若干条安全审计事件（第五幕 G.12 展示用）"""
    stmt = (
        select(EAuditSecurity)
        .order_by(EAuditSecurity.event_time.desc(), EAuditSecurity.audit_id.desc())
        .limit(limit)
    )
    rows = (await db.execute(stmt)).scalars().all()
    return [
        {
            'audit_id': r.audit_id,
            'event_time': r.event_time.strftime('%Y-%m-%d %H:%M:%S'),
            'event_type': r.event_type,
            'user_name': r.user_name,
            'user_role': r.user_role,
            'target_module': r.target_module,
            'target_resource': r.target_resource,
            'client_ip': r.client_ip,
            'user_agent': r.user_agent,
            'action_result': r.action_result,
            'remark': r.remark,
        }
        for r in rows
    ]


async def append_cost_operation_audit(
    db: AsyncSession,
    *,
    title: str,
    operator: str,
    action: str,
    target: str,
    before: Mapping[str, Any],
    after: Mapping[str, Any],
    reason: str,
    occurred_at: datetime,
) -> None:
    """REQ-073: append cost before/after/reason evidence in the caller transaction."""
    db.add(
        SysOperLog(
            title=title,
            business_type=2,
            method=f'CostService.{action}',
            request_method='POST',
            operator_type=1,
            oper_name=operator,
            oper_url=target,
            oper_param=json.dumps(
                {'before': before, 'reason': reason},
                ensure_ascii=False,
                separators=(',', ':'),
                sort_keys=True,
                default=str,
            ),
            json_result=json.dumps(
                {'after': after},
                ensure_ascii=False,
                separators=(',', ':'),
                sort_keys=True,
                default=str,
            ),
            status=0,
            oper_time=occurred_at,
            cost_time=0,
        )
    )
    await db.flush()
