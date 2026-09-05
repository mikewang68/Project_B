"""
能源总览（驾驶舱）契约模型
契约事实来源：docs/mock-contracts.md v0.1 + PRD §5.1 页面级字段表
REQ 锚点：REQ-057~062、REQ-019、REQ-029、REQ-039/040/042、REQ-053
可执行参考：web/src/api/overview.js + web/src/views/dashboard/mock.js

约定：
- 序列化统一采用 camelCase 别名（alias_generator=to_camel），响应字段与前端 mock 逐字段对齐
- 本文件只定义契约形状；聚合口径与规则实装在 service 层
"""

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

# ---------------------------------------------------------------------------
# 请求参数
# ---------------------------------------------------------------------------


class OverviewQueryModel(BaseModel):
    """
    /overview/summary 查询参数
    契约来源：docs/mock-contracts.md v0.1 第 1 节请求参数表
    """

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    time_range: Literal['today', 'week', 'month', 'custom'] = Field(
        default='week', description='全局筛选器时间档（today/week/month/custom）'
    )
    zone: Literal['ALL', 'A', 'B'] = Field(default='ALL', description='装卸区（ALL/A/B，2 区口径）')
    energy_type: Literal['ELEC', 'WATER', 'AIR'] = Field(
        default='ELEC', description='能源介质（ELEC 电 / WATER 水 / AIR 压缩空气）'
    )


# ---------------------------------------------------------------------------
# 顶部：口径签名 & 演示态声明
# ---------------------------------------------------------------------------


class SignatureModel(BaseModel):
    """
    口径签名（REQ-062：导出/接口口径说明必备的版本信息）
    """

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    version: str = Field(description='需求基线版本（如 v0.3.4-demo）')
    formula_version: str = Field(description='统计公式版本（f-x.y）')
    price_version: str = Field(description='单价版本号（REQ-051/052）')
    baseline_version: str = Field(description='能源基线版本号（REQ-029）')
    sig_id: str = Field(description='本次响应口径签名短标识')
    seed: int = Field(description='构造数据固定随机种子（数据构造规范约定 42）')
    generated_at: str = Field(description='响应生成时间（YYYY-MM-DD HH:MM:SS）')
    coverage: float = Field(description='当日采样覆盖率百分比（REQ-019）')


class DemoStateModel(BaseModel):
    """
    演示态声明（REQ-029：能源基线默认关闭，demo 环境显式启用需明示）
    """

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    enabled: bool = Field(description='demo 演示态是否启用（EnPI/基线展示）')
    hint: str = Field(description='演示态说明文案')
    req_anchor: str = Field(description='REQ 锚点（形如 REQ-029）')


class FiltersEchoModel(BaseModel):
    """
    响应回显的当前筛选条件（便于前端稳定过滤器展示）
    """

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    time_range: str = Field(description='时间档回显')
    zone: str = Field(description='装卸区回显')
    energy_type: str = Field(description='能源介质回显')


# ---------------------------------------------------------------------------
# KPI 卡片（REQ-053、REQ-019、REQ-039/042、REQ-045、REQ-058、REQ-029）
# ---------------------------------------------------------------------------


class KpiDeltaModel(BaseModel):
    """
    KPI 卡上的环比/同比/状态徽标
    """

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    value: str = Field(description='增减幅（含符号/单位）或状态短句')
    kind: Literal['up', 'dn', 'neutral'] = Field(description='徽标视觉档：上升/下降/中性')
    label: str = Field(description='对照口径说明（如 "环比昨日"）')


class KpiCardModel(BaseModel):
    """
    KPI 卡片（PRD §5.1 关键字段表 · 6 张）
    code 枚举：ENERGY_DAY / BASELINE_DEV / OPEN_ALARMS / COVERAGE / COST_MTD / SUGGESTIONS
    """

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    code: Literal[
        'ENERGY_DAY', 'BASELINE_DEV', 'OPEN_ALARMS', 'COVERAGE', 'COST_MTD', 'SUGGESTIONS'
    ] = Field(description='KPI 卡代码')
    label: str = Field(description='卡片标题')
    unit: str = Field(description='数值单位')
    value: int | float | str = Field(description='当前值（可为整数/小数/短字符串以承载 "+8.2" 一类符号）')
    tag: str = Field(description='角标短文本')
    tag_kind: Literal['ok', 'warn', 'hi', 'neutral'] = Field(description='角标语义档（合格/超阈/严重/中性）')
    # spark / spark_kind 于 2026-07-14 移除（QA #25 C1）：PRD §5.1 KPI 表无此字段，
    # 服务层从未装配真实数据、恒为空数组 + 硬编码色标，属 mock 形态占位；契约 §1 已同步。
    delta: KpiDeltaModel = Field(description='增减/状态徽标')
    sub_label: str = Field(description='卡片副标题（口径注释）')
    req_anchor: str = Field(description='REQ 锚点')


# ---------------------------------------------------------------------------
# 24h 负荷趋势 + 基线带 + 尖峰异常（REQ-058、REQ-029、R03/R08）
# ---------------------------------------------------------------------------


class TrendAnomalyModel(BaseModel):
    """
    24h 曲线上的异常点标注（挂 R03 尖峰规则）
    """

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    hour_index: int = Field(description='小时序号（0~23）')
    value: int | float = Field(description='异常点数值')
    rule_id: str = Field(description='触发规则编号（如 R03）')
    note: str = Field(description='异常说明（如 "尖峰超基线上限 8.2%"）')


class TrendPeakModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    hour: str = Field(description='峰值出现时刻（HH:MM）')
    value: int | float = Field(description='峰值数值')
    note: str = Field(description='峰值口径说明')


class TrendValleyModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    hour: str = Field(description='谷值出现时刻（HH:MM）')
    value: int | float = Field(description='谷值数值')


class TrendPayloadModel(BaseModel):
    """
    24h 负荷曲线 + 基线带（±12% 演示态）
    REQ-058 / REQ-029 / 初始规则库 R03
    """

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    hours: list[str] = Field(description='24 个时刻标签（HH:00）')
    load: list[int | float] = Field(description='24 个采样点负荷值')
    baseline_high: list[int | float] = Field(description='基线带上界（+12%）')
    baseline_mid: list[int | float] = Field(description='基线中位')
    baseline_low: list[int | float] = Field(description='基线带下界（-12%）')
    anomalies: list[TrendAnomalyModel] = Field(description='挂规则的异常点标注')
    peak: TrendPeakModel = Field(description='峰值信息')
    valley: TrendValleyModel = Field(description='谷值信息')
    now_index: int = Field(description='当前时刻游标（当前小时序号）')
    unit: str = Field(description='负荷单位（kW / m³/h 等）')
    sampling_interval: str = Field(description='采样周期（15min / 5min，REQ-010）')
    formula_version: str = Field(description='曲线所用公式版本')


# ---------------------------------------------------------------------------
# Top5 用能对象（REQ-053 / REQ-058）
# ---------------------------------------------------------------------------


class TopObjectModel(BaseModel):
    """
    Top5 用能对象排行（REQ-053 月度成本 Top 5，可切能耗口径）
    """

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    rank: int = Field(description='排名（1~5）')
    name: str = Field(description='对象显示名')
    meta: str = Field(description='对象元信息（区域/能源类型/工况标签）')
    value: int | float = Field(description='排行值（成本或能耗）')
    unit: str = Field(description='排行值单位（¥ / kWh 等）')
    share: str = Field(description='占比文案（含 % 号）')
    bar_pct: int = Field(description='对齐第一名的相对条形宽度百分比')
    kind: Literal['hot', 'cool'] = Field(description='视觉档：Top 1 hot / 其余 cool')


# ---------------------------------------------------------------------------
# 最新告警滚动（REQ-039/040/042）
# ---------------------------------------------------------------------------


class AlarmItemModel(BaseModel):
    """
    告警滚动项（级别三档 + 版本冻结口径 + 合并计数）
    REQ-039 级别（提示/一般/严重）→ level: info/warn/crit
    REQ-040 规则版本冻结 → ruleVersion
    REQ-042 同规则同对象 5min 合并 → mergedCount
    """

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    time: str = Field(description='触发时刻（HH:MM:SS）')
    level: Literal['info', 'warn', 'crit'] = Field(description='级别（提示/一般/严重）')
    rule_id: str = Field(description='规则编号（R01~R10）')
    rule_version: str = Field(description='规则版本（历史告警按触发时版本展示）')
    title: str = Field(description='告警标题')
    lead: str = Field(description='一句话描述')
    obj: str = Field(description='涉及对象（区/设备/计量点）')
    merged_count: int = Field(description='合并触发次数（默认 5min 窗口）')


# ---------------------------------------------------------------------------
# 数据质量摘要 + 48 计量点信号阵列（REQ-019）
# ---------------------------------------------------------------------------


class QualityCategoryModel(BaseModel):
    """
    质量分类计数（正常/缺测/迟到/估算/人工修正，附录 C 枚举）
    """

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    key: Literal['ok', 'miss', 'late', 'est', 'fix'] = Field(description='质量类别键')
    label: str = Field(description='类别中文标签')
    count: int = Field(description='该类别计量点数')
    pct: str = Field(description='占比文案（含 % 号）')


class QualityPayloadModel(BaseModel):
    """
    数据质量摘要 + 48 计量点信号阵列（12×4 网格）
    REQ-019：覆盖率阈值 95%/80% 两档；本响应回显演示态值
    """

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    coverage: int | float = Field(description='当日采样覆盖率百分比')
    coverage_threshold: int | float = Field(description='当前生效的合格阈值（默认 95%）')
    total: int = Field(description='计量点总数（demo 48 点）')
    categories: list[QualityCategoryModel] = Field(description='分类计数')
    cells: list[Literal['ok', 'miss', 'late', 'est', 'fix']] = Field(
        description='48 个格子的质量状态（对应 12×4 网格）'
    )
    zone_split: str = Field(description='分区计数文案（如 "A 区 24 点 · B 区 24 点"）')
    latest_ingest_at: str = Field(description='最近入库时刻（HH:MM:SS）')


# ---------------------------------------------------------------------------
# 顶层响应
# ---------------------------------------------------------------------------


class OverviewPayloadModel(BaseModel):
    """
    /overview/summary data 字段完整结构（一次聚合返回）
    契约事实来源：docs/mock-contracts.md v0.1 + web/src/api/overview.js OverviewPayload
    """

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    signature: SignatureModel
    demo_state: DemoStateModel
    filters: FiltersEchoModel
    kpis: list[KpiCardModel] = Field(description='6 张 KPI 卡')
    trend: TrendPayloadModel
    top_objects: list[TopObjectModel] = Field(description='Top5 用能对象（成本口径 · 跨介质 ¥ 汇总）')
    top_objects_by_energy: list[TopObjectModel] = Field(
        default_factory=list,
        description='Top5 用能对象（能耗口径 · 仅当前 energyType 的月累计量，单一介质内排序）',
    )
    alarms: list[AlarmItemModel] = Field(description='最新告警滚动')
    quality: QualityPayloadModel
