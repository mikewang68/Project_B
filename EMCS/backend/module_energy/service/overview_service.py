"""
能源总览（驾驶舱）聚合服务
REQ 锚点：REQ-057~062、REQ-019、REQ-029、REQ-039/040/042、REQ-053
契约事实来源：docs/mock-contracts.md v0.1 + PRD §5.1

阶段说明（2026-07-13 阶段 2）：
- 主数据/时序表已入库（e_area/e_equipment/e_meter_point/e_raw_reading/e_alert_rule/
  e_energy_baseline/e_tariff_version），本服务从 DAO 取真实值组装 quality/signature/demoState
- 聚合表（e_stat_*/e_cost_record/e_alert_event/e_suggestion）由 task #14 的统计聚合管道
  + 规则引擎写入；当前为空表，服务按"合法结构 + 零值/占位"策略返回，字段形状不变
- 规则编号：一律使用 PRD §7.2 demo R01–R11（docs/mock-contracts.md 全局约定 2026-07-13）
  R01 采集离线 · R02 数据迟到 · R03 连续零值 · R04 数据跳变 · R05 设备高耗偏离
  R06 非作业气流量异常 · R07 空压机频繁启停 · R08 非作业照明异常 · R09 基线偏差
  R10 峰段成本占比异常 · R11 覆盖率不足
- trend.anomalies 分工（契约 v0.1 更新 2026-07-13）：只挂 R09 基线偏差点；
  R05/R04/R06 等其它规则事件由 alarms 列表承载，不进 trend
- mock 定稿（2026-07-13）：BASELINE_DEV 演示阈值 +20%（对齐 R09）；
  alarms 定稿分布 R06/crit · R09/warn · R05/crit · R01/warn · R02/info · R11/crit
"""

import hashlib
from datetime import datetime, time, timedelta
from decimal import Decimal
from typing import Any

from sqlalchemy.ext.asyncio import AsyncSession

from module_energy.dao.overview_dao import OverviewDao
from module_energy.entity.do.energy_baseline_do import EEnergyBaseline
from module_energy.entity.do.equipment_do import EEquipment
from module_energy.entity.do.meter_point_do import EMeterPoint
from module_energy.entity.do.stat_hour_do import EStatHour
from module_energy.entity.do.tariff_version_do import ETariffVersion
from module_energy.entity.vo.overview_vo import (
    AlarmItemModel,
    DemoStateModel,
    FiltersEchoModel,
    KpiCardModel,
    KpiDeltaModel,
    OverviewPayloadModel,
    OverviewQueryModel,
    QualityCategoryModel,
    QualityPayloadModel,
    SignatureModel,
    TopObjectModel,
    TrendAnomalyModel,
    TrendPayloadModel,
    TrendPeakModel,
    TrendValleyModel,
)
from module_energy.service.demo_now_util import get_demo_now

# 数据构造规范固定种子（web/src/views/dashboard/mock.js 亦用此种子）
_DEMO_SEED = 42

# 演示口径版本号（跟随需求基线 v0.3.4）
_VERSION = 'v0.3.4-demo'
_FORMULA_VERSION_DEFAULT = 'f-1.3'

# 前端 energyType 枚举 → DB energy_type_code
_ENERGY_TYPE_MAP: dict[str, str] = {
    'ELEC': 'electricity',
    'WATER': 'water',
    'AIR': 'compressed_air',
}
# 前端 zone 枚举 → DB area_code
_ZONE_TO_AREA_CODE: dict[str, str | None] = {
    'ALL': None,
    'A': 'AREA-A',
    'B': 'AREA-B',
}

# KPI 单位（REQ-053 电 kWh / 水 m³ / 气 m³ 切换）
_ENERGY_UNIT_BY_TYPE = {'electricity': 'kWh', 'water': 'm³', 'compressed_air': 'm³'}

# 覆盖率阈值（REQ-019 双阈值 95%/80%，前端展示为整数）
_COVERAGE_THRESHOLD_OK = 95
_COVERAGE_THRESHOLD_DEGRADED = 80

# R09 基线偏差阈值：PRD §7.2 demo 规则库 —— 日能耗超出基线带 +20% 触发（演示态）
_BASELINE_DEV_WARN_PCT = 20.0

# QA-#23 小数精度分能源类型（team-lead 2026-07-14 走查修复）
# 电：kWh 量级几十到千，1 位小数即可（0 位也可；保留 1 位便于总览细粒度）
# 水/压缩空气：m³/h 量级 0.2~2.0，必须 2 位小数否则被 round 抹成 0
_PRECISION_BY_ENERGY_TYPE: dict[str, int] = {
    'electricity': 1,
    'water': 2,
    'compressed_air': 2,
}


def _round_by_energy(v: float, energy_type_code: str) -> float:
    """按能源类型选精度舍入（保留 int 类型仅当精度=0）"""
    p = _PRECISION_BY_ENERGY_TYPE.get(energy_type_code, 2)
    if p == 0:
        return round(v)
    return round(v, p)

# Trend 曲线小时数（24h 驾驶舱标准视窗）
_TREND_HOURS = 24

# 区代号（demo 固定 AREA-A/AREA-B → area_id 1/2）
_AREA_ID_A = 1
_AREA_ID_B = 2

# 质量短码合法集（附录 C 八态，demo 阵列色 5 态；dup/jump/frozen 归 miss 家族）
_QUALITY_CELL_KEYS = ('ok', 'miss', 'late', 'est', 'fix')


class OverviewService:
    """
    能源总览聚合 service
    """

    # ------------------------------------------------------------------
    # 对外主入口
    # ------------------------------------------------------------------

    @classmethod
    async def get_overview_summary(
        cls, db: AsyncSession, query: OverviewQueryModel
    ) -> OverviewPayloadModel:
        """
        聚合能源总览一次拉取所需的全部数据（REQ-057 总览页 + REQ-058 过滤联动）
        """
        # 参数解析
        energy_type_code = _ENERGY_TYPE_MAP[query.energy_type]
        zone_area_code = _ZONE_TO_AREA_CODE[query.zone]
        area_id: int | None = None
        if zone_area_code is not None:
            area = await OverviewDao.get_area_by_code(db, zone_area_code)
            area_id = area.area_id if area else None

        # 用 DEMO_NOW 锚代替 wall-clock（避免走过数据集尾巴导致"今日"恒 0）
        now = await get_demo_now(db)
        today = now.date()
        cur_month = today.strftime('%Y-%m')
        stat_start_of_day = datetime.combine(today, time(0, 0, 0))
        trend_end = stat_start_of_day + timedelta(days=1)
        trend_start = trend_end - timedelta(hours=24)

        # ---- 主数据（真实）----
        meter_points = await OverviewDao.list_meter_points(db)  # 48 条
        equipment_map = await OverviewDao.get_equipment_map(db)
        latest_quality_by_point = await OverviewDao.get_latest_reading_quality_per_point(db)
        latest_ingest = await OverviewDao.get_latest_ingest_time(db)
        # FX-13：基线按 zone 取对应 scope——ALL 用 system 基线，A/B 用各自分区基线
        baseline = await OverviewDao.get_active_baseline(
            db,
            energy_type_code,
            object_scope='system' if area_id is None else 'area',
            object_id=area_id,
        )
        tariffs = await OverviewDao.list_active_tariff_versions(db, energy_type_code, today)

        # ---- 聚合表（空表期间零值/空数组）----
        day_totals = await OverviewDao.sum_stat_day_totals(
            db, today, energy_type_code, object_type='area', area_id=area_id
        )
        # FX-13：zone=ALL 的基线偏差必须读 system 行（system 偏差 ≠ A/B 偏差的算术平均，
        # 分区偏差改为各自真算后两者会分叉；总量 sum 不受影响仍走 area 行求和）
        if area_id is None:
            system_totals = await OverviewDao.sum_stat_day_totals(
                db, today, energy_type_code, object_type='system', area_id=None
            )
            day_totals['baseline_deviation_pct'] = system_totals['baseline_deviation_pct']
        # ENERGY_DAY 环比昨日（PRD §5.1 KPI 表口径；无 area 过滤时对齐 zone=ALL 系统级）
        yesterday_total: Decimal | None = None
        if area_id is None:
            yesterday_total = await OverviewDao.get_system_day_total(
                db, today - timedelta(days=1), energy_type_code
            )
        hourly_stats = await OverviewDao.get_hourly_stats_between(
            db,
            start_time=trend_start,
            end_time=trend_end,
            energy_type_code=energy_type_code,
            object_type='area',
            object_id=area_id,
        )
        alert_summary = await OverviewDao.get_open_alert_summary(db, area_id=area_id)
        recent_alarms = await OverviewDao.get_recent_alerts(db, limit=6, area_id=area_id)
        # QA-S5 vs 昨日新增（team-lead 2026-07-14 裁决走真值）
        alerts_new_today = await OverviewDao.get_alert_new_by_day(db, today)
        alerts_new_yesterday = await OverviewDao.get_alert_new_by_day(db, today - timedelta(days=1))
        # 契约裁决 2026-07-13：trend.anomalies 只承载 demo-R09 基线偏差点
        # 其它规则事件（R05 高耗偏离 / R04 数据跳变 / R06 气流量 等）不进 trend，改由 alarms 呈现
        trend_anomaly_events = await OverviewDao.get_alerts_in_hour_range(
            db,
            start_time=trend_start,
            end_time=trend_end,
            rule_codes=('R09',),
            area_id=area_id,
        )
        suggestion_summary = await OverviewDao.get_open_suggestion_summary(db, area_id=area_id)
        cost_summary = await OverviewDao.get_cost_month_summary(
            db, cur_month, energy_type_code, area_id=area_id
        )
        top_cost_objects = await OverviewDao.get_top_cost_objects(
            db, cur_month, limit=5, area_id=area_id
        )
        # #23 追加：能耗口径 Top5（仅当前 energyType，单一介质内排序，不跨介质相加）
        top_energy_objects = await OverviewDao.get_top_energy_objects(
            db, cur_month, energy_type_code=energy_type_code, limit=5, area_id=area_id
        )

        # ---- 装配（先算 quality 供 signature.coverage 引用）----
        # QA-S3 累计口径（team-lead 2026-07-14 裁决）：
        # coverage 用当日累计（DEMO_NOW 日 valid 样点/理论样点），cells 保留最新快照
        valid_cnt, expected_cnt = await OverviewDao.get_daily_valid_expected(db, today)
        quality_payload = cls._build_quality(
            meter_points, latest_quality_by_point, latest_ingest,
            daily_valid=valid_cnt, daily_expected=expected_cnt,
        )

        payload = OverviewPayloadModel(
            signature=cls._build_signature(
                query, now, baseline, tariffs, quality_payload.coverage
            ),
            demo_state=cls._build_demo_state(baseline),
            filters=FiltersEchoModel(
                time_range=query.time_range,
                zone=query.zone,
                energy_type=query.energy_type,
            ),
            kpis=cls._build_kpis(
                query=query,
                energy_type_code=energy_type_code,
                day_totals=day_totals,
                yesterday_total=yesterday_total,
                alert_summary=alert_summary,
                alerts_new_today=alerts_new_today,
                alerts_new_yesterday=alerts_new_yesterday,
                suggestion_summary=suggestion_summary,
                cost_summary=cost_summary,
                tariffs=tariffs,
                quality_coverage=quality_payload.coverage,
                baseline=baseline,
            ),
            trend=cls._build_trend(
                now=now,
                energy_type_code=energy_type_code,
                hourly_stats=hourly_stats,
                anomalies_events=trend_anomaly_events,
                trend_start=trend_start,
                formula_version=_derive_formula_version(baseline),
            ),
            top_objects=cls._build_top_objects(top_cost_objects, equipment_map),
            top_objects_by_energy=cls._build_top_objects_by_energy(
                top_energy_objects, equipment_map, energy_type_code,
            ),
            alarms=cls._build_alarms(
                recent_alarms,
                equipment_map=equipment_map,
                point_map={p.point_id: p for p in meter_points},
                area_map={a.area_id: a for a in await OverviewDao.list_areas(db)},
            ),
            quality=quality_payload,
        )
        return payload

    # ------------------------------------------------------------------
    # 口径签名 & 演示态
    # ------------------------------------------------------------------

    @classmethod
    def _build_signature(
        cls,
        query: OverviewQueryModel,
        now: datetime,
        baseline: EEnergyBaseline | None,
        tariffs: list[ETariffVersion],
        coverage: float,
    ) -> SignatureModel:
        # REQ-062：口径签名。sigId 由版本 + 请求要素哈希得来，便于导出溯源
        formula_version = _derive_formula_version(baseline)
        baseline_version = baseline.baseline_code if baseline else 'BASELINE-N/A'
        price_version = _price_version_from_tariffs(tariffs)

        raw = f'{_VERSION}|{formula_version}|{price_version}|{baseline_version}|{query.time_range}|{query.zone}|{query.energy_type}'
        sig_id_hex = hashlib.md5(raw.encode('utf-8')).hexdigest()[:8]
        sig_id = f'{sig_id_hex[:4]}-{sig_id_hex[4:]}'
        return SignatureModel(
            version=_VERSION,
            formula_version=formula_version,
            price_version=price_version,
            baseline_version=baseline_version,
            sig_id=sig_id,
            seed=_DEMO_SEED,
            generated_at=now.strftime('%Y-%m-%d %H:%M:%S'),
            coverage=round(float(coverage), 2),
        )

    @classmethod
    def _build_demo_state(cls, baseline: EEnergyBaseline | None) -> DemoStateModel:
        # REQ-029：能源基线默认关闭；datagen 已发布基线记录即视为演示态启用
        if baseline is None:
            return DemoStateModel(
                enabled=False,
                hint='REQ-029 能源基线演示态未启用：datagen 尚未发布基线记录，页面隐藏基线偏差字段。',
                req_anchor='REQ-029',
            )
        return DemoStateModel(
            enabled=True,
            hint=(
                f'REQ-029 能源基线演示态：数据集已包含基线记录 {baseline.baseline_code}'
                f'（method={baseline.method}, status={baseline.status}），'
                '报告期 vs 基线期偏差可展示。'
            ),
            req_anchor='REQ-029',
        )

    # ------------------------------------------------------------------
    # KPI 卡片（6 张）
    # ------------------------------------------------------------------

    @classmethod
    def _build_kpis(  # noqa: PLR0913, PLR0915, PLR0912
        cls,
        query: OverviewQueryModel,
        energy_type_code: str,
        day_totals: dict[str, Any],
        yesterday_total: Decimal | None,
        alert_summary: dict[str, int],
        alerts_new_today: int,
        alerts_new_yesterday: int,
        suggestion_summary: dict[str, int],
        cost_summary: dict[str, Any] | None,
        tariffs: list[ETariffVersion],
        quality_coverage: float,
        baseline: EEnergyBaseline | None,
    ) -> list[KpiCardModel]:
        unit = _ENERGY_UNIT_BY_TYPE.get(energy_type_code, '—')

        # ---- KPI 1: 当日总能耗（REQ-053 合格用量聚合 + REQ-019 覆盖率降级） ----
        day_total_val = _to_number(day_totals.get('total_value'))
        # QA-#23: 电取整数、水/气保留 2 位小数（不再对全量 round 到 int 抹掉 0.x m³）
        if day_total_val:
            energy_day_value: int | float = _round_by_energy(float(day_total_val), energy_type_code)
        else:
            energy_day_value = 0
        # PRD §5.1 KPI 表：ENERGY_DAY 环比昨日
        energy_day_delta = cls._build_delta_vs_yesterday(day_total_val, yesterday_total)
        energy_day = KpiCardModel(
            code='ENERGY_DAY',
            label='当日总能耗',
            unit=unit,
            value=energy_day_value,
            tag='今日',
            tag_kind='neutral',
            delta=energy_day_delta,
            sub_label=(
                f'{query.energy_type} 口径 · 合格样点聚合'
                if day_total_val
                else f'{query.energy_type} 口径 · 当日暂无有效数据'
            ),
            req_anchor='REQ-024 / 025 / 019',
        )

        # ---- KPI 2: 基线偏差 %（REQ-029 演示态） ----
        # QA-#23：基线目前只对电力发布（BASELINE-SYSTEM-ELEC-2026），
        # 切水/气时 baseline_deviation_pct 恒 NULL；subLabel 明示"基线为电力口径（演示态）"，
        # 避免用户把"待数据"当 bug
        dev = _to_number(day_totals.get('baseline_deviation_pct'))
        is_electricity = energy_type_code == 'electricity'
        if dev is None:
            if not is_electricity:
                non_elec_hint = f'{query.energy_type} 口径 · 基线目前仅覆盖电力口径（演示态），水/气基线待建'
            elif baseline:
                non_elec_hint = f'REQ-029 演示态 · 基线 {baseline.baseline_code} 已就位，当日暂无有效偏差数据'
            else:
                non_elec_hint = 'REQ-029 演示态未启用（无基线记录）'
            baseline_kpi = KpiCardModel(
                code='BASELINE_DEV',
                label='基线偏差',
                unit='%',
                value=0,
                tag='待数据' if is_electricity else '不适用',
                tag_kind='neutral',
                delta=KpiDeltaModel(value='—', kind='neutral', label=''),
                sub_label=non_elec_hint,
                req_anchor='REQ-029 / R09',
            )
        else:
            dev_f = float(dev)
            sign = '+' if dev_f >= 0 else ''
            baseline_code_show = baseline.baseline_code if baseline else '—'
            # QA-S1（team-lead 2026-07-14 裁决）：sub_label 分正负超阈叙事，
            # 只在正向超阈时挂"触发 R09"（R09 只判定正向偏离 > +20%）；
            # 负向低于 -20% 属"下偏预警（量能不足）"，规则库未覆盖但演示时要点出；
            # 落在带内 = 合格
            if dev_f > _BASELINE_DEV_WARN_PCT:
                dev_narrative = '正向超阈 · 触发 R09'
            elif dev_f < -_BASELINE_DEV_WARN_PCT:
                dev_narrative = '负向下偏预警 · 量能不足（R09 不判定负向）'
            else:
                dev_narrative = '带内合格'
            baseline_kpi = KpiCardModel(
                code='BASELINE_DEV',
                label='基线偏差',
                unit='%',
                value=f'{sign}{dev_f:.1f}',
                tag='超阈' if abs(dev_f) > _BASELINE_DEV_WARN_PCT else '合格',
                tag_kind='warn' if abs(dev_f) > _BASELINE_DEV_WARN_PCT else 'ok',
                delta=KpiDeltaModel(
                    value='阈值 +20%', kind='up' if dev_f >= 0 else 'dn', label=''
                ),
                sub_label=f'REQ-029 演示态 · 报告期 vs 基线期（{baseline_code_show}）· {dev_narrative}',
                req_anchor='REQ-029 / R09',
            )

        # ---- KPI 3: 未关闭告警（REQ-039 级别三档 + REQ-042 合并计数） ----
        open_total = alert_summary.get('total', 0)
        severe_count = alert_summary.get('severe', 0)
        # QA-S5（team-lead 2026-07-14 修正裁决）：走真值——按 first_occur_time 分日计数
        # 表征"该日新触发的告警数"，与 OPEN_ALARMS 累计口径互补
        new_delta = alerts_new_today - alerts_new_yesterday
        if new_delta > 0:
            delta_value = f'+{new_delta}'
            delta_kind: Any = 'up'
        elif new_delta < 0:
            delta_value = str(new_delta)
            delta_kind = 'dn'
        else:
            delta_value = '±0'
            delta_kind = 'neutral'
        open_alarms = KpiCardModel(
            code='OPEN_ALARMS',
            label='未关闭告警',
            unit='条',
            value=open_total,
            tag=f'{severe_count} 严重' if severe_count else '暂无严重',
            tag_kind='hi' if severe_count else 'ok',
            delta=KpiDeltaModel(value=delta_value, kind=delta_kind, label='vs 昨日新增'),
            sub_label=(
                f'严重级 {severe_count} · 一般级 {alert_summary.get("normal", 0)} · 提示级 {alert_summary.get("notice", 0)}'
                f' · 今日新增 {alerts_new_today} 条 / 昨日 {alerts_new_yesterday} 条'
                if open_total
                else '当前无未关闭告警'
            ),
            req_anchor='REQ-039 / 042',
        )

        # ---- KPI 4: 当日采样覆盖率（REQ-019 双阈值 95%/80%） ----
        cov = quality_coverage
        if cov >= _COVERAGE_THRESHOLD_OK:
            cov_tag, cov_tag_kind = '≥95%', 'ok'
        elif cov >= _COVERAGE_THRESHOLD_DEGRADED:
            cov_tag, cov_tag_kind = '降级', 'warn'
        else:
            cov_tag, cov_tag_kind = '<80%', 'hi'
        # QA-S3（team-lead 2026-07-14 修正裁决）：coverage KPI 与 signature.coverage 走
        # 当日累计口径（合格样点/理论样点，DEMO_NOW 日），与 R11 告警口径完全一致；
        # 48 格阵列 cells 保留最新快照，两口径分工明确不再割裂
        coverage_kpi = KpiCardModel(
            code='COVERAGE',
            label='当日采样覆盖率',
            unit='%',
            value=round(cov, 1),
            tag=cov_tag,
            tag_kind=cov_tag_kind,  # type: ignore[arg-type]
            delta=KpiDeltaModel(value='—', kind='neutral', label=''),
            sub_label='REQ-019 阈值 95%/80% · 当日累计口径（∑合格样点/∑理论样点），与 R11 告警同源',
            req_anchor='REQ-019',
        )

        # ---- KPI 5: 当月累计成本（REQ-051/052 单价×合格用量×峰平谷） ----
        total_cost = _to_number(cost_summary.get('total_cost') if cost_summary else None)
        peak_q = _to_number(cost_summary.get('peak_qty') if cost_summary else None) or Decimal(0)
        flat_q = _to_number(cost_summary.get('flat_qty') if cost_summary else None) or Decimal(0)
        valley_q = _to_number(cost_summary.get('valley_qty') if cost_summary else None) or Decimal(0)
        # 展示单位与 mock 对齐"万元"；整数值发 int
        cost_value_wan_raw = (float(total_cost) / 10000.0) if total_cost else 0
        cost_value_wan = _num_or_zero(round(cost_value_wan_raw, 2)) if cost_value_wan_raw else 0
        tou_ratio_hint = _format_tou_ratio(peak_q, flat_q, valley_q)
        price_version = _price_version_from_tariffs(tariffs)
        cost_kpi = KpiCardModel(
            code='COST_MTD',
            label='当月累计成本',
            unit='万元',
            value=cost_value_wan,
            tag='当月',
            tag_kind='neutral',
            delta=KpiDeltaModel(value='—', kind='neutral', label='同比去年'),
            sub_label=(
                f'单价 {price_version} · 峰:平:谷 {tou_ratio_hint}'
                if total_cost
                else f'单价 {price_version} · 当月暂无成本记录'
            ),
            req_anchor='REQ-051 / 052',
        )

        # ---- KPI 6: 待办节能建议（PRD §5.1：状态 ∈ 待审核/已分派/执行中；验证中不计待办） ----
        s_total = suggestion_summary.get('total', 0)
        s_pending = suggestion_summary.get('pending', 0)
        s_dispatched = suggestion_summary.get('dispatched', 0)
        s_executing = suggestion_summary.get('executing', 0)
        suggestions_kpi = KpiCardModel(
            code='SUGGESTIONS',
            label='待办节能建议',
            unit='条',
            value=s_total,
            tag='流转中' if s_total else '暂无',
            tag_kind='neutral',
            delta=KpiDeltaModel(
                value=f'待审 {s_pending} · 已派 {s_dispatched} · 执行 {s_executing}',
                kind='neutral',
                label='',
            ),
            sub_label='节能建议状态分档 · REQ-045',
            req_anchor='REQ-045 / 047',
        )

        return [energy_day, baseline_kpi, open_alarms, coverage_kpi, cost_kpi, suggestions_kpi]

    # ------------------------------------------------------------------
    # 24h 负荷 + 基线带 + 尖峰异常（REQ-058、REQ-029、R09/R05）
    # ------------------------------------------------------------------

    @classmethod
    def _build_trend(
        cls,
        now: datetime,
        energy_type_code: str,
        hourly_stats: list[EStatHour],
        anomalies_events: list[dict[str, Any]],
        trend_start: datetime,
        formula_version: str,
    ) -> TrendPayloadModel:
        # 落桶：24 个小时桶，index=0 对应 trend_start
        load_by_hour: dict[int, float] = {}
        for row in hourly_stats:
            delta_hours = int((row.stat_time - trend_start).total_seconds() // 3600)
            if 0 <= delta_hours < _TREND_HOURS:
                load_by_hour[delta_hours] = float(row.total_value or 0)

        hours: list[str] = []
        load: list[int | float] = []
        baseline_mid: list[int | float] = []
        baseline_high: list[int | float] = []
        baseline_low: list[int | float] = []
        for i in range(_TREND_HOURS):
            bucket_time = trend_start + timedelta(hours=i)
            hours.append(bucket_time.strftime('%H:00'))
            v = load_by_hour.get(i, 0.0)
            # QA-#23: 按能源类型选精度——电取整数（kW 量级几十~几千）；
            # 水/气保留 2 位小数（m³/h 量级 0.2~2.0，取整会被抹成 0）
            load.append(_round_by_energy(v, energy_type_code) if v else 0)
            # 基线带 demo 简化：±12% 相对当前小时值（真实基线在 baseline.buckets_json）
            mid = v * 0.94 if v else 0
            baseline_mid.append(_round_by_energy(mid, energy_type_code) if mid else 0)
            baseline_high.append(_round_by_energy(mid * 1.12, energy_type_code) if mid else 0)
            baseline_low.append(_round_by_energy(mid * 0.88, energy_type_code) if mid else 0)

        # 尖峰异常：只承载 demo-R09 基线偏差事件（契约裁决 2026-07-13）
        # note 与 mock 对齐 "R09 基线偏差 · 演示态"，rule_name 从 DAO join 取
        anomalies: list[TrendAnomalyModel] = []
        for ev in anomalies_events:
            occur_at: datetime = ev['last_occur_time']
            delta_hours = int((occur_at - trend_start).total_seconds() // 3600)
            if not 0 <= delta_hours < _TREND_HOURS:
                continue
            rule_code = ev['rule_code']
            rule_name = ev.get('rule_name') or '基线偏差'
            anomalies.append(
                TrendAnomalyModel(
                    hour_index=delta_hours,
                    value=load[delta_hours] if delta_hours < len(load) else 0,
                    rule_id=rule_code,
                    note=f'{rule_code} {rule_name} · 演示态',
                )
            )

        # 峰谷
        if any(load):
            peak_idx = max(range(_TREND_HOURS), key=lambda i: load[i])
            positive = [i for i in range(_TREND_HOURS) if load[i] > 0]
            valley_idx = min(positive, key=lambda i: load[i]) if positive else 0
            peak = TrendPeakModel(hour=hours[peak_idx], value=load[peak_idx], note='今日曲线峰值')
            valley = TrendValleyModel(hour=hours[valley_idx], value=load[valley_idx])
        else:
            peak = TrendPeakModel(hour='—', value=0, note='暂无有效小时数据')
            valley = TrendValleyModel(hour='—', value=0)

        # 采样周期：电/水 15min、气 5min（REQ-010）
        sampling_interval = '5min' if energy_type_code == 'compressed_air' else '15min'

        return TrendPayloadModel(
            hours=hours,
            load=load,
            baseline_high=baseline_high,
            baseline_mid=baseline_mid,
            baseline_low=baseline_low,
            anomalies=anomalies,
            peak=peak,
            valley=valley,
            now_index=min(now.hour, _TREND_HOURS - 1),
            unit='kW' if energy_type_code == 'electricity' else 'm³/h',
            sampling_interval=sampling_interval,
            formula_version=formula_version,
        )

    # ------------------------------------------------------------------
    # Top5 用能对象（REQ-053）
    # ------------------------------------------------------------------

    @classmethod
    def _build_top_objects(
        cls,
        top_cost_objects: list[dict[str, Any]],
        equipment_map: dict[int, EEquipment],
    ) -> list[TopObjectModel]:
        if not top_cost_objects:
            return []
        max_cost = float(top_cost_objects[0]['total_cost']) or 1.0
        total_all = sum(float(o['total_cost']) for o in top_cost_objects) or 1.0
        result: list[TopObjectModel] = []
        for rank, obj in enumerate(top_cost_objects, start=1):
            eq = equipment_map.get(obj['equipment_id'])
            eq_name = eq.equipment_name if eq else f'设备 {obj["equipment_id"]}'
            area_hint = _area_label(eq.area_id) if eq else ''
            energy_hint = (eq.energy_types or '') if eq else ''
            meta_parts = [p for p in (area_hint, energy_hint) if p]
            meta = ' · '.join(meta_parts) if meta_parts else '—'
            cost_val = float(obj['total_cost'])
            result.append(
                TopObjectModel(
                    rank=rank,
                    name=eq_name,
                    meta=meta,
                    value=round(cost_val, 2),
                    unit='¥',
                    share=f'{(cost_val / total_all * 100):.1f}%',
                    bar_pct=int(cost_val / max_cost * 100),
                    kind='hot' if rank == 1 else 'cool',
                )
            )
        return result

    @classmethod
    def _build_top_objects_by_energy(
        cls,
        top_energy_objects: list[dict[str, Any]],
        equipment_map: dict[int, EEquipment],
        energy_type_code: str,
    ) -> list[TopObjectModel]:
        """
        #23 追加：能耗口径 Top5（REQ-053 用能对象双定义之一）
        - 单一介质内排序，value 为量值（kWh / m³），unit 按介质
        - 与成本口径分开，前端切换"按能耗/按成本"用两个数组独立渲染
        """
        if not top_energy_objects:
            return []
        max_val = float(top_energy_objects[0]['total_value']) or 1.0
        total_all = sum(float(o['total_value']) for o in top_energy_objects) or 1.0
        unit_by_type = _ENERGY_UNIT_BY_TYPE.get(energy_type_code, '—')
        precision = _PRECISION_BY_ENERGY_TYPE.get(energy_type_code, 2)
        result: list[TopObjectModel] = []
        for rank, obj in enumerate(top_energy_objects, start=1):
            eq = equipment_map.get(obj['equipment_id'])
            eq_name = eq.equipment_name if eq else f'设备 {obj["equipment_id"]}'
            area_hint = _area_label(eq.area_id) if eq else ''
            meta = area_hint or '—'
            energy_val = float(obj['total_value'])
            result.append(
                TopObjectModel(
                    rank=rank,
                    name=eq_name,
                    meta=meta,
                    value=round(energy_val, precision) if precision else round(energy_val),
                    unit=unit_by_type,
                    share=f'{(energy_val / total_all * 100):.1f}%',
                    bar_pct=int(energy_val / max_val * 100),
                    kind='hot' if rank == 1 else 'cool',
                )
            )
        return result

    # ------------------------------------------------------------------
    # 最新告警滚动（REQ-039/040/042）
    # ------------------------------------------------------------------

    @classmethod
    def _build_alarms(
        cls,
        recent_alarms: list[dict[str, Any]],
        *,
        equipment_map: dict[int, EEquipment] | None = None,
        point_map: dict[int, EMeterPoint] | None = None,
        area_map: dict[int, Any] | None = None,
    ) -> list[AlarmItemModel]:
        # DB level notice/normal/severe → 前端 info/warn/crit
        level_map = {'notice': 'info', 'normal': 'warn', 'severe': 'crit'}
        equipment_map = equipment_map or {}
        point_map = point_map or {}
        area_map = area_map or {}
        result: list[AlarmItemModel] = []
        for r in recent_alarms:
            level_ui = level_map.get(r['level'], 'info')
            occur_time: datetime | None = r.get('last_occur_time')
            time_str = occur_time.strftime('%H:%M:%S') if occur_time else '—'
            obj_str = cls._describe_alert_object(r, equipment_map, point_map, area_map)
            result.append(
                AlarmItemModel(
                    time=time_str,
                    level=level_ui,  # type: ignore[arg-type]
                    rule_id=r['rule_code'],
                    rule_version=f'v{r["rule_version_no"]}',
                    title=r.get('rule_name') or f'规则 {r["rule_code"]}',
                    lead=r.get('expression') or '（无描述）',
                    obj=obj_str,
                    merged_count=int(r.get('occur_count') or 1),
                )
            )
        return result

    @staticmethod
    def _build_delta_vs_yesterday(
        today_val: Any, yesterday_val: Decimal | None
    ) -> KpiDeltaModel:
        """
        环比昨日 delta（PRD §5.1 ENERGY_DAY 表）
        今日 vs 昨日：>0 up、<0 dn、无对比数据用 '—' neutral
        """
        if today_val is None or yesterday_val is None or float(yesterday_val) <= 0:
            return KpiDeltaModel(value='—', kind='neutral', label='环比昨日')
        today_f = float(today_val)
        yesterday_f = float(yesterday_val)
        pct = (today_f - yesterday_f) / yesterday_f * 100
        sign = '+' if pct >= 0 else ''
        kind: Any = 'up' if pct >= 0 else 'dn'
        return KpiDeltaModel(value=f'{sign}{pct:.1f}%', kind=kind, label='环比昨日')

    @staticmethod
    def _describe_alert_object(
        r: dict[str, Any],
        equipment_map: dict[int, EEquipment],
        point_map: dict[int, EMeterPoint],
        area_map: dict[int, Any],
    ) -> str:
        """
        QA-S2（team-lead 2026-07-14 裁决）：把 alarms.obj 从原始 ID
        （equipment#1 / point#7 / area#2）替换为业务名。
          - equipment → equipment_name（如"龙门吊 1 号"）
          - point     → point_name    （如"龙门吊 1 号·电表"）
          - area      → area_name     （如"A 区"）
          - system    → "全站"
        area 前缀继续保留（多点合并展示时区别一眼可见）；找不到 ID 时兜底回原 ID 占位
        """
        ot = r.get('object_type', 'system')
        oid = r.get('object_id')
        aid = r.get('area_id')
        area_hint = _area_label(aid) if aid is not None else ''
        prefix = f'{area_hint} · ' if area_hint else ''
        name: str
        if ot == 'equipment' and oid is not None and int(oid) in equipment_map:
            name = equipment_map[int(oid)].equipment_name
        elif ot == 'point' and oid is not None and int(oid) in point_map:
            name = point_map[int(oid)].point_name
        elif ot == 'area' and oid is not None and int(oid) in area_map:
            area_row = area_map[int(oid)]
            name = getattr(area_row, 'area_name', None) or _area_label(int(oid)) or f'区 {oid}'
            prefix = ''  # area 本身即区，不再重复前缀
        elif ot == 'system':
            name = '全站'
            prefix = ''
        else:
            name = f'{ot}#{oid}'
        return f'{prefix}{name}'

    # ------------------------------------------------------------------
    # 48 计量点信号阵列 + 数据质量摘要（REQ-019）
    # ------------------------------------------------------------------

    @classmethod
    def _build_quality(
        cls,
        meter_points: list[EMeterPoint],
        latest_quality_by_point: dict[int, str],
        latest_ingest: datetime | None,
        *,
        daily_valid: int = 0,
        daily_expected: int = 0,
    ) -> QualityPayloadModel:
        """
        QA-S3 双口径（team-lead 2026-07-14 裁决）：
        - `coverage` 字段 = 当日累计口径 (daily_valid / daily_expected × 100)，
          与 R11 日累计告警口径一致，语义为"REQ-019 按周期计算的合格样点率"
        - `cells[48]` = 每点最新读数快照的 quality 短码，用于 12×4 阵列色块可视化，
          语义为"上一采样周期的实时质量态"
        - 两口径并存不矛盾：DEMO_NOW=2026-07-12 当日数据齐全时两值恰好一致；
          回看 07-05 WP-A1 INJ-07 案例时 coverage=~99% 但 cells 里 WP-A1 两格显示 miss
        """
        # 48 格按 area+point_id 稳定序（DAO 已按此排序）
        cells: list[Any] = []
        for pt in meter_points:
            q = latest_quality_by_point.get(pt.point_id, 'miss')
            # 附录 C 八态映射到前端阵列色 5 态；dup/jump/frozen 归 miss 家族（不合格）
            if q not in _QUALITY_CELL_KEYS:
                q = 'miss'
            cells.append(q)

        total = len(cells) or 1
        counts: dict[str, int] = dict.fromkeys(_QUALITY_CELL_KEYS, 0)
        for c in cells:
            counts[c] = counts.get(c, 0) + 1

        # 分区 split 文案
        area_counts: dict[int, int] = {}
        for pt in meter_points:
            area_counts[pt.area_id] = area_counts.get(pt.area_id, 0) + 1
        zone_split_parts: list[str] = []
        for area_id, cnt in sorted(area_counts.items()):
            zone_split_parts.append(f'{_area_label(area_id) or f"区 {area_id}"} {cnt} 点')
        zone_split = ' · '.join(zone_split_parts) if zone_split_parts else '—'

        # coverage 走当日累计口径（QA-S3 裁决）；无累计数据时回退到快照口径
        coverage_pct = (
            daily_valid / daily_expected * 100.0
            if daily_expected > 0
            else counts['ok'] / total * 100.0
        )

        category_labels = {'ok': '正常', 'miss': '缺测', 'late': '迟到', 'est': '估算', 'fix': '人工修正'}
        categories = [
            QualityCategoryModel(
                key=k,  # type: ignore[arg-type]
                label=category_labels[k],
                count=counts.get(k, 0),
                pct=f'{(counts.get(k, 0) / total * 100):.1f}%',
            )
            for k in _QUALITY_CELL_KEYS
        ]

        return QualityPayloadModel(
            coverage=round(coverage_pct, 2),
            coverage_threshold=_COVERAGE_THRESHOLD_OK,
            total=total,
            categories=categories,
            cells=cells,
            zone_split=zone_split,
            latest_ingest_at=(latest_ingest.strftime('%H:%M:%S') if latest_ingest else '—'),
        )


# ---------------------------------------------------------------------------
# 私有工具函数
# ---------------------------------------------------------------------------


def _to_number(v: Any) -> float | Decimal | None:
    """把 DAO 返回的可能是 Decimal/None/数字/字符串统一到可运算数字，None 直传"""
    if v is None:
        return None
    if isinstance(v, Decimal):
        return v
    if isinstance(v, (int, float)):
        return v
    try:
        return Decimal(str(v))
    except Exception:
        return None


def _num_or_zero(v: Any) -> int | float:
    """
    数字规范化：整数值统一发 int（避免 Decimal(0) 变成 0.0 与 mock int 类型不一致）。
    小数值发 float。
    """
    if v is None:
        return 0
    if isinstance(v, Decimal):
        f = float(v)
        return int(f) if f.is_integer() else f
    if isinstance(v, float) and v.is_integer():
        return int(v)
    return v


def _format_tou_ratio(peak: Any, flat: Any, valley: Any) -> str:
    p, f, v = float(peak or 0), float(flat or 0), float(valley or 0)
    total = p + f + v
    if total <= 0:
        return '—:—:—'
    return f'{int(p / total * 100)}:{int(f / total * 100)}:{int(v / total * 100)}'


def _price_version_from_tariffs(tariffs: list[ETariffVersion]) -> str:
    if not tariffs:
        return 'v-none'
    latest = max(t.effective_from for t in tariffs)
    return f'v{latest.strftime("%Y-%m")}'


def _derive_formula_version(baseline: EEnergyBaseline | None) -> str:
    if baseline is None or not baseline.formula_version:
        return _FORMULA_VERSION_DEFAULT
    return baseline.formula_version


def _area_label(area_id: int | None) -> str:
    if area_id == _AREA_ID_A:
        return 'A 区'
    if area_id == _AREA_ID_B:
        return 'B 区'
    return ''
