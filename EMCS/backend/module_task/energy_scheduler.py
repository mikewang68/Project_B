"""
能源域定时任务编排（APScheduler 直接注册；不走 sys_job DB 表）
REQ 锚点：REQ-020 重算触发·REQ-024~028 聚合·REQ-039~044 规则·REQ-060 总览刷新

策略：
- 首启空表 → 自动一键 bootstrap（聚合→基线→成本→规则）
- 每 15 分钟：增量聚合（近 30 min） + 规则重跑（demo 数据集固定，全量刷开销可控）
- 每日 03:00：成本重算（月度成本对齐夜间批处理惯例）

多 worker 场景下只让 leader 注册（由 caller 判断 SchedulerUtil._is_leader）
"""

import asyncio
from datetime import datetime, timedelta
from typing import Any

from apscheduler.schedulers.asyncio import AsyncIOScheduler
from apscheduler.triggers.cron import CronTrigger
from apscheduler.triggers.interval import IntervalTrigger
from sqlalchemy import text

from config.database import AsyncSessionLocal
from module_energy.service.aggregation_service import AggregationService
from module_energy.service.baseline_service import BaselineService
from module_energy.service.cost_service import CostService
from module_energy.service.rule_engine_service import RuleEngineService
from utils.log_util import logger

# 任务 ID（下划线前缀让 scheduler_event_listener 跳过 job_log 记录，避免 sys_job_log 泛滥）
_JOB_ID_BOOTSTRAP = '_energy_bootstrap_first_start'
_JOB_ID_INCREMENTAL = '_energy_incremental_15min'
_JOB_ID_COST_DAILY = '_energy_cost_daily_0300'
_JOB_ID_AGENT_INSPECTION = '_agent_inspection_daily_0700'  # REQ-AGENT-TBD


async def _is_pipeline_empty() -> bool:
    """检查聚合表是否为空（e_stat_hour + e_alert_event）"""
    async with AsyncSessionLocal() as db:
        n_hour = (await db.execute(text('SELECT COUNT(*) FROM e_stat_hour'))).scalar() or 0
        n_event = (await db.execute(text('SELECT COUNT(*) FROM e_alert_event'))).scalar() or 0
    return int(n_hour) == 0 and int(n_event) == 0


async def _run_full_bootstrap() -> dict[str, Any]:
    """一键 bootstrap：聚合 → 基线 → 成本 → 规则（首启用）"""
    logger.info('[energy-scheduler] 首启 bootstrap 开始')
    async with AsyncSessionLocal() as db:
        agg = await AggregationService.rebuild_all(db)
        baseline_publish = await BaselineService.compute_and_publish(db)
        baseline_dev = await BaselineService.compute_daily_deviation(db)
        cost = await CostService.rebuild_all(db)
        rules = await RuleEngineService.run_all(db)
    logger.info(
        f'[energy-scheduler] bootstrap 完成 agg={agg} '
        f'baseline={baseline_publish} dev={baseline_dev} cost={cost} rules={rules}'
    )
    return {'agg': agg, 'baseline': baseline_publish, 'dev': baseline_dev,
            'cost': cost, 'rules': rules}


async def _run_incremental_agg_and_rules() -> None:
    """
    每 15 分钟增量：只重算最近 30 min 窗口的小时/日聚合，然后全量重跑规则（demo 数据量小）
    生产版本应改为规则的增量判定；本 demo 简化。
    """
    end = datetime.now()
    start = end - timedelta(minutes=30)
    logger.info(f'[energy-scheduler] 15min 增量聚合 [{start}, {end})')
    async with AsyncSessionLocal() as db:
        try:
            await AggregationService.rebuild_range(db, start, end)
        except Exception as e:
            logger.warning(f'[energy-scheduler] rebuild_range 异常（demo 静态数据集常态）：{e}')
        # 规则全量重跑（不 seed，纯计算）
        rule_counts = await RuleEngineService.run_all(db)
    logger.info(f'[energy-scheduler] 15min 增量完成 rules={rule_counts}')


async def _run_daily_cost() -> None:
    """每日 03:00 成本重算"""
    logger.info('[energy-scheduler] 每日 03:00 成本重算开始')
    async with AsyncSessionLocal() as db:
        counts = await CostService.rebuild_all(db)
    logger.info(f'[energy-scheduler] 每日成本重算完成 counts={counts}')


async def _run_daily_agent_inspection() -> None:
    """每日 07:00 AI 自主巡检（REQ-AGENT-TBD · docs/agent-ai-design.md §6）"""
    logger.info('[agent-inspection] 每日 07:00 巡检开始')
    from module_energy.service.agent_inspection_service import run_inspection
    try:
        report_id = await run_inspection(trigger_type='scheduled')
        logger.info(f'[agent-inspection] 每日巡检完成 report_id={report_id}')
    except Exception as e:  # noqa: BLE001
        logger.exception(f'[agent-inspection] 每日巡检异常：{e}')


def setup_energy_scheduler(scheduler: AsyncIOScheduler) -> None:
    """
    向 scheduler 注册能源域定时任务（幂等：replace_existing=True）
    调用侧责任：仅在 leader worker 调用；调用前确保 scheduler 已 start
    """
    # 首启 bootstrap：延后 5 秒执行，让 app 完全启动；异步内部判断是否需要跑
    scheduler.add_job(
        func=_maybe_first_start_bootstrap,
        trigger='date',
        run_date=datetime.now() + timedelta(seconds=5),
        id=_JOB_ID_BOOTSTRAP,
        name='首启 bootstrap（仅当聚合表为空时）',
        replace_existing=True,
    )
    # 15min 增量
    scheduler.add_job(
        func=_run_incremental_agg_and_rules,
        trigger=IntervalTrigger(minutes=15),
        id=_JOB_ID_INCREMENTAL,
        name='能源增量聚合+规则（15min）',
        replace_existing=True,
        max_instances=1,
        coalesce=True,
    )
    # 每日 03:00 成本
    scheduler.add_job(
        func=_run_daily_cost,
        trigger=CronTrigger(hour=3, minute=0),
        id=_JOB_ID_COST_DAILY,
        name='能源月度成本重算（每日 03:00）',
        replace_existing=True,
        max_instances=1,
        coalesce=True,
    )
    # 每日 07:00 AI 自主巡检（REQ-AGENT-TBD）
    scheduler.add_job(
        func=_run_daily_agent_inspection,
        trigger=CronTrigger(hour=7, minute=0),
        id=_JOB_ID_AGENT_INSPECTION,
        name='AI 自主巡检（每日 07:00）',
        replace_existing=True,
        max_instances=1,
        coalesce=True,
    )
    logger.info('[energy-scheduler] 能源域定时任务已注册（bootstrap/15min/daily-cost/agent-inspection）')


async def _maybe_first_start_bootstrap() -> None:
    """启动后首次跑：只在聚合表为空时触发一键 bootstrap（避免热重载多次跑）"""
    try:
        empty = await _is_pipeline_empty()
    except Exception as e:
        logger.error(f'[energy-scheduler] 检查空表失败，跳过 bootstrap：{e}')
        return
    if not empty:
        logger.info('[energy-scheduler] 聚合表非空，跳过 first-start bootstrap')
        return
    # 使用 asyncio.shield 保护完整执行
    try:
        await asyncio.shield(_run_full_bootstrap())
    except Exception as e:
        logger.error(f'[energy-scheduler] first-start bootstrap 失败：{e}')
