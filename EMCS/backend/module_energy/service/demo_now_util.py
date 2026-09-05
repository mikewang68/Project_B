"""
演示日锚定工具（DEMO_NOW）
契约裁决：docs/mock-contracts.md 全局约定 2026-07-13
REQ 锚点：REQ-058 / 060（总览时间口径）+ demo 数据构造规范"当日/今日"语义

约定：
- 若 AppConfig.demo_now 配置了 ISO 时间串（如 '2026-07-12 23:59:00'），直接解析返回
- 否则回退 max(e_raw_reading.sample_time) 所在时刻（避免 wall-clock 走过数据集尾巴导致"今日"恒 0）
- 若原始表也空（bootstrap 前），再兜底到 datetime.now()

所有 backend 里"当日/今日/now"口径统一从本模块取值，不直接调用 datetime.now()：
- 总览 KPI 当日窗口、trend nowIndex
- 聚合任务"今日"档
- 告警"今日触发"计数
"""

from datetime import datetime

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from config.env import AppConfig
from module_energy.entity.do.raw_reading_do import ERawReading
from utils.log_util import logger

_DATE_FORMATS: tuple[str, ...] = ('%Y-%m-%d %H:%M:%S', '%Y-%m-%dT%H:%M:%S', '%Y-%m-%d')


class _DemoNowCache:
    """进程内缓存 holder（避免 module 级 global 声明触发 ruff PLW0603）"""

    value: datetime | None = None


def _parse_env_demo_now() -> datetime | None:
    raw = (AppConfig.demo_now or '').strip()
    if not raw:
        return None
    for fmt in _DATE_FORMATS:
        try:
            return datetime.strptime(raw, fmt)
        except ValueError:  # noqa: PERF203 - 三种候选格式的顺序尝试，PERF 忽略无损
            continue
    logger.warning(f'DEMO_NOW 配置格式无法解析（{raw!r}），回退到数据尾时间/wall-clock')
    return None


async def get_demo_now(db: AsyncSession, *, refresh: bool = False) -> datetime:
    """
    取"当前时刻"锚。优先级：AppConfig.demo_now > max(e_raw_reading.sample_time) > wall-clock。
    进程内缓存结果（第一次命中后不再回查），除非 refresh=True。
    """
    if _DemoNowCache.value is not None and not refresh:
        return _DemoNowCache.value

    env_now = _parse_env_demo_now()
    if env_now is not None:
        _DemoNowCache.value = env_now
        return env_now

    fallback = (await db.execute(select(func.max(ERawReading.sample_time)))).scalar()
    if fallback is not None:
        _DemoNowCache.value = fallback
        return fallback

    return datetime.now()


def reset_cache() -> None:
    """测试/重跑聚合时用；清缓存后下次 get_demo_now 会重新解析。"""
    _DemoNowCache.value = None
