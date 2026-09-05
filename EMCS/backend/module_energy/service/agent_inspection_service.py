"""AI 自主巡检 workflow（REQ-AGENT-TBD · 设计稿 docs/agent-ai-design.md §6）

「能 workflow 不 agent」：5 步流程写死在代码里，每步用工具取数据交 LLM 判读，
最后一步 LLM 汇总为结构化报告 JSON，写入 ai_inspection_report（本表唯一写
入点）。LLM 不可用或超时时任务落库 status=failed，不产半成品报告；演示前
需人工确认至少 1 份 success 报告兜底。

- report_date 一律取 DEMO_NOW 的日期（不是真实系统日期）——否则报告日期
  与"近 7 天"数据窗口对不上（DEMO_NOW 坑点变种）。
- 文案规则同问数：findings 里定位一律用业务名称链，禁止外显内部编号；
  related_ids 只作为前端跳转参数。
- LLM 调用走 openai SDK（agentscope 传递依赖），指向 AGENT_LLM_* 环境变量
  配置的 OpenAI 兼容接口；不需要 agent 循环，因此不引 AgentScope Agent。
"""

from __future__ import annotations

import json
import os
import time
from datetime import date, timedelta
from typing import Any

from openai import AsyncOpenAI
from sqlalchemy import desc, select
from sqlalchemy.ext.asyncio import AsyncSession

from config.database import AsyncSessionLocal
from module_energy.entity.do.ai_inspection_report_do import AiInspectionReport
from module_energy.service import agent_tools
from module_energy.service.demo_now_util import get_demo_now
from utils.log_util import logger

# LLM 单次调用超时（秒）；巡检共 5 次 LLM 调用，累计不超 5 分钟
_LLM_CALL_TIMEOUT_S = 60
# 巡检 workflow 总超时（秒）——超过则任务标 failed
_WORKFLOW_TIMEOUT_S = 300


# ---------------------------------------------------------------------------
# LLM 客户端
# ---------------------------------------------------------------------------


def _build_openai_client() -> tuple[AsyncOpenAI, str]:
    base_url = os.environ.get('AGENT_LLM_BASE_URL', 'https://api.deepseek.com')
    api_key = os.environ.get('AGENT_LLM_API_KEY', '')
    model = os.environ.get('AGENT_LLM_MODEL', 'deepseek-v4-flash')
    if not api_key:
        raise RuntimeError('AGENT_LLM_API_KEY 未配置')
    client = AsyncOpenAI(
        api_key=api_key,
        base_url=base_url,
        timeout=_LLM_CALL_TIMEOUT_S,
    )
    return client, model


async def _llm_judge(
    client: AsyncOpenAI,
    model: str,
    step_title: str,
    payload_json: str,
    instruction: str,
) -> str:
    """让 LLM 对一步数据做判读；返回自然语言判读结论（1-2 段）。"""
    system = (
        '你是能源管控巡检助手。对给定 JSON 数据做要点判读，只关注异常与风险。'
        '定位一律用业务名称链（区域·设备·计量对象），不要出现内部编号（id/pointId 等）。'
        '2-4 句话说清结论与依据，不要复述原始 JSON。'
    )
    user = f'【巡检步骤】{step_title}\n【任务】{instruction}\n【数据 JSON】\n{payload_json}'
    resp = await client.chat.completions.create(
        model=model,
        messages=[
            {'role': 'system', 'content': system},
            {'role': 'user', 'content': user},
        ],
        temperature=0.2,
        stream=False,
    )
    return (resp.choices[0].message.content or '').strip()


async def _llm_summarize(
    client: AsyncOpenAI,
    model: str,
    demo_now_iso: str,
    step_judgments: list[dict[str, str]],
) -> dict[str, Any]:
    """让 LLM 汇总各步判读为结构化 JSON 报告。"""
    system = (
        '你是能源管控巡检助手，把多步判读汇总成一份 JSON 巡检报告。\n'
        '严格输出如下 JSON（不要 Markdown 围栏、不要额外文本）：\n'
        '{\n'
        '  "summary": "60-200 字巡检结论（先给整体判断，再列 top-2 隐患）",\n'
        '  "findings": [\n'
        '    {\n'
        '      "category": "data_quality|alert|energy|cost|equipment",\n'
        '      "severity": "high|medium|low",\n'
        '      "title": "简短标题（业务名称链定位，禁止内部编号）",\n'
        '      "evidence": "证据说明（引用具体数字与业务名称链）",\n'
        '      "suggestion": "行动建议（1 句话）",\n'
        '      "related_ids": {"alert_ids": [], "equipment_codes": [], "point_ids": []}\n'
        '    }\n'
        '  ]\n'
        '}\n'
        '规则：\n'
        '- findings 不多于 6 条，按 severity 优先级排序；\n'
        '- 每条 evidence 必须带具体数字；\n'
        '- title / evidence / suggestion 中禁止出现 id/pointId/eventId 等内部编号，'
        '仅在 related_ids 里放编号（供前端跳转），且从判读文本能推断出的编号才填。'
    )
    joined = '\n\n'.join(
        f'## 步骤 {i + 1}：{j["title"]}\n{j["judgment"]}'
        for i, j in enumerate(step_judgments)
    )
    user = f'DEMO_NOW 日期：{demo_now_iso}\n以下是本次巡检各步判读结论：\n\n{joined}'
    resp = await client.chat.completions.create(
        model=model,
        messages=[
            {'role': 'system', 'content': system},
            {'role': 'user', 'content': user},
        ],
        temperature=0.1,
        stream=False,
    )
    raw = (resp.choices[0].message.content or '').strip()
    # 兼容模型附带 markdown 围栏的场景
    if raw.startswith('```'):
        raw = raw.strip('`')
        # 去掉首行可能的 "json" 标记
        if '\n' in raw:
            first_line, rest = raw.split('\n', 1)
            if first_line.strip().lower().startswith('json'):
                raw = rest
    try:
        report = json.loads(raw)
    except json.JSONDecodeError as e:
        raise RuntimeError(f'LLM 汇总输出非合法 JSON：{e}；raw={raw[:200]!r}') from e
    if not isinstance(report, dict) or 'summary' not in report:
        raise RuntimeError('LLM 汇总输出缺少 summary 字段')
    report.setdefault('findings', [])
    return report


# ---------------------------------------------------------------------------
# workflow 步骤（固定顺序）
# ---------------------------------------------------------------------------


async def _run_workflow_steps(
    demo_now_iso: str,
    client: AsyncOpenAI,
    model: str,
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    """执行 5 步 workflow；返回 (step_judgments, stats)。"""
    demo_now_date = date.fromisoformat(demo_now_iso)
    date_start = (demo_now_date - timedelta(days=6)).isoformat()
    date_end = demo_now_date.isoformat()
    month = demo_now_iso[:7]

    step_judgments: list[dict[str, str]] = []
    stats: dict[str, Any] = {'demo_now': demo_now_iso, 'window': [date_start, date_end]}

    # 步骤 1：数据质量
    dq_raw = await agent_tools.query_data_quality(date_start=date_start, date_end=date_end, area='ALL')
    dq_judge = await _llm_judge(
        client, model, '数据质量（近 7 天）', dq_raw,
        '识别缺数/补传/覆盖率不足的计量对象；有必要时点名区域与设备。',
    )
    step_judgments.append({'title': '数据质量', 'judgment': dq_judge})
    try:
        dq_obj = json.loads(dq_raw)
        # 工具返回结构是 coverage.pct（FX-40：原取 .overall 恒为 None，前端"数据完整率"显示破折号）
        stats['data_quality_coverage'] = (dq_obj.get('coverage') or {}).get('pct')
    except Exception:  # noqa: BLE001
        pass

    # 步骤 2：未处理告警
    al_raw = await agent_tools.query_alerts(status='new', area='ALL', date_start=date_start, date_end=date_end)
    al_judge = await _llm_judge(
        client, model, '未处理告警（近 7 天）', al_raw,
        '给出优先级最高的 2-3 条告警，说明业务位置与频次；严重级优先。',
    )
    step_judgments.append({'title': '未处理告警', 'judgment': al_judge})
    try:
        al_obj = json.loads(al_raw)
        stats['alert_total'] = al_obj.get('items_total')
    except Exception:  # noqa: BLE001
        pass

    # 步骤 3：总览 + 月度成本
    ov_raw = await agent_tools.query_overview(date=date_end, zone='ALL')
    cm_raw = await agent_tools.query_cost_month(month=month, zone='ALL')
    combined_3 = json.dumps({'overview': _try_load(ov_raw), 'cost_month': _try_load(cm_raw)}, ensure_ascii=False)
    en_judge = await _llm_judge(
        client, model, '能耗/成本环比', combined_3[:6000],
        '判读整体能耗、基线偏差、成本环比是否异常；引用具体百分比与金额。',
    )
    step_judgments.append({'title': '能耗与成本', 'judgment': en_judge})

    # 步骤 4：设备画像抽查（取卡片墙前 3 台高能耗设备）
    ep_raw = await agent_tools.query_equipment_profile(equipment_id=None, date_start=date_start, date_end=date_end)
    top_codes: list[str] = []
    ep_obj = _try_load(ep_raw) or {}
    for e in (ep_obj.get('equipments') or [])[:3]:
        code = e.get('code')
        if code:
            top_codes.append(code)
    details: list[Any] = []
    for code in top_codes:
        detail = await agent_tools.query_equipment_profile(
            equipment_id=code, date_start=date_start, date_end=date_end,
        )
        details.append(_try_load(detail))
    eq_payload = json.dumps({'top_equipment_codes': top_codes, 'details': details}, ensure_ascii=False)[:6000]
    eq_judge = await _llm_judge(
        client, model, '高能耗设备抽查', eq_payload,
        '指出这几台设备中是否存在待机/停机能耗偏高或异常数量偏高的情况；用业务名称链定位。',
    )
    step_judgments.append({'title': '设备抽查', 'judgment': eq_judge})
    stats['sampled_equipment_codes'] = top_codes

    # 步骤 5：节能建议看板（补充上下文）
    sg_raw = await agent_tools.query_suggestions(status='pending', zone='ALL')
    sg_judge = await _llm_judge(
        client, model, '待审节能建议', sg_raw,
        '说明当前待审建议的数量与优先级分布，若某类规则集中出现请点名。',
    )
    step_judgments.append({'title': '待办建议', 'judgment': sg_judge})

    # 本轮工具调用次数（前端"检查项"统计卡）：固定 6 次 + 设备抽查逐台明细
    stats['checks'] = 6 + len(top_codes)

    return step_judgments, stats


def _try_load(raw: str) -> Any:
    try:
        return json.loads(raw)
    except Exception:  # noqa: BLE001
        return None


# ---------------------------------------------------------------------------
# 主入口
# ---------------------------------------------------------------------------


async def run_inspection(trigger_type: str = 'manual') -> int:
    """执行一次巡检 workflow 并写入 ai_inspection_report；返回报告 id。

    LLM 不可用/超时 → 记录 failed（summary 存错误摘要，findings/stats 为空）。
    调用方（HTTP / scheduler）都独占一个 AsyncSession。
    """
    if trigger_type not in ('manual', 'scheduled'):
        trigger_type = 'manual'
    t0 = time.time()

    # DEMO_NOW 与模型客户端准备
    async with AsyncSessionLocal() as db:
        try:
            demo_now = await get_demo_now(db)
        except Exception as e:  # noqa: BLE001
            demo_now = None
            logger.exception(f'[agent-inspection] DEMO_NOW 读取失败：{e}')
    if demo_now is None:
        from datetime import datetime as _dt
        demo_now = _dt.now()
    demo_now_iso = demo_now.date().isoformat()

    model_name = os.environ.get('AGENT_LLM_MODEL', 'unknown')
    try:
        client, model_id = _build_openai_client()
    except Exception as e:  # noqa: BLE001
        return await _persist_failed(demo_now.date(), trigger_type, model_name, t0,
                                     f'模型初始化失败：{e}')

    # 执行 workflow（整体带一个大超时）
    try:
        import asyncio
        step_judgments, stats = await asyncio.wait_for(
            _run_workflow_steps(demo_now_iso, client, model_id),
            timeout=_WORKFLOW_TIMEOUT_S,
        )
        report = await _llm_summarize(client, model_id, demo_now_iso, step_judgments)
    except asyncio.TimeoutError:
        return await _persist_failed(demo_now.date(), trigger_type, model_name, t0,
                                     f'巡检 workflow 超时（>{_WORKFLOW_TIMEOUT_S}s）')
    except Exception as e:  # noqa: BLE001
        logger.exception(f'[agent-inspection] workflow 异常：{e}')
        return await _persist_failed(demo_now.date(), trigger_type, model_name, t0, str(e))

    elapsed_ms = int((time.time() - t0) * 1000)
    async with AsyncSessionLocal() as db:
        row = AiInspectionReport(
            report_date=demo_now.date(),
            trigger_type=trigger_type,
            status='success',
            summary=(report.get('summary') or '')[:1024],
            findings_json=report.get('findings') or [],
            stats_json=stats,
            model_name=model_name[:64],
            elapsed_ms=elapsed_ms,
        )
        db.add(row)
        await db.commit()
        await db.refresh(row)
        report_id = int(row.id)
    logger.info(f'[agent-inspection] 巡检报告落库 id={report_id} elapsed_ms={elapsed_ms}')
    return report_id


async def _persist_failed(
    report_date: date,
    trigger_type: str,
    model_name: str,
    t0: float,
    error_msg: str,
) -> int:
    elapsed_ms = int((time.time() - t0) * 1000)
    async with AsyncSessionLocal() as db:
        row = AiInspectionReport(
            report_date=report_date,
            trigger_type=trigger_type,
            status='failed',
            summary=f'巡检失败：{error_msg}'[:1024],
            findings_json=[],
            stats_json={'error': error_msg[:512]},
            model_name=model_name[:64],
            elapsed_ms=elapsed_ms,
        )
        db.add(row)
        await db.commit()
        await db.refresh(row)
        logger.error(f'[agent-inspection] 巡检失败落库 id={row.id}：{error_msg}')
        return int(row.id)


# ---------------------------------------------------------------------------
# 查询：列表 + 详情
# ---------------------------------------------------------------------------


async def list_reports(
    db: AsyncSession,
    page_num: int,
    page_size: int,
) -> dict[str, Any]:
    offset = (page_num - 1) * page_size
    # 总数
    from sqlalchemy import func
    total = (await db.execute(select(func.count(AiInspectionReport.id)))).scalar() or 0
    # 分页列表
    stmt = (
        select(AiInspectionReport)
        .order_by(desc(AiInspectionReport.created_at))
        .offset(offset)
        .limit(page_size)
    )
    rows = (await db.execute(stmt)).scalars().all()
    items = [
        {
            'id': int(r.id),
            'report_date': r.report_date.isoformat() if r.report_date else None,
            'trigger_type': r.trigger_type,
            'status': r.status,
            'summary': r.summary,
            'findings_count': len(r.findings_json) if isinstance(r.findings_json, list) else 0,
            'model_name': r.model_name,
            'elapsed_ms': r.elapsed_ms,
            'created_at': r.created_at.isoformat() if r.created_at else None,
        }
        for r in rows
    ]
    return {'total': int(total), 'page_num': page_num, 'page_size': page_size, 'items': items}


async def get_report(db: AsyncSession, report_id: int) -> dict[str, Any] | None:
    row = (
        await db.execute(select(AiInspectionReport).where(AiInspectionReport.id == report_id))
    ).scalar_one_or_none()
    if row is None:
        return None
    return {
        'id': int(row.id),
        'report_date': row.report_date.isoformat() if row.report_date else None,
        'trigger_type': row.trigger_type,
        'status': row.status,
        'summary': row.summary,
        'findings': row.findings_json or [],
        'stats': row.stats_json or {},
        'model_name': row.model_name,
        'elapsed_ms': row.elapsed_ms,
        'created_at': row.created_at.isoformat() if row.created_at else None,
    }
