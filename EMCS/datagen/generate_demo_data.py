"""B-Demo 演示数据构造脚本。

依据：
  - 知识库《demo-演示数据构造规范》§1 对象模型、§3 曲线形态、§4 异常注入清单
  - PRD-能源管控系统 §3.2 五角色账号（附录 K.6）、§7 运行参数与规则库、附录 C 状态语义
  - docs/mock-contracts.md 规则编号裁决（demo-R01–R11）与质量短码（ok/miss/late/dup/jump/est/fix/frozen）

用法（幂等，可重复运行）：
  # 方案 A：从空库重建（推荐）——先跑骨架 SQL + DDL，再种子数据
  python datagen/generate_demo_data.py --reset
  # 方案 B：只重跑业务数据（骨架 + DDL 已存在时使用）
  python datagen/generate_demo_data.py

数据安全红线：全部构造值。禁止读取、变换或抽样任何真实计量数据、真实人名、真实工号。
"""

from __future__ import annotations

import argparse
import json
import os
import random
import re
import sys
import time
from datetime import date, datetime, timedelta
from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path

import pymysql
from pymysql.constants import CLIENT

# ----------------------------------------------------------------------
# 基础配置
# ----------------------------------------------------------------------

REPO_ROOT = Path(__file__).resolve().parent.parent
SKELETON_SQL = REPO_ROOT / "datagen" / "skeleton" / "skeleton-init.sql"
DDL_SQL = REPO_ROOT / "datagen" / "ddl" / "V001__energy_domain.sql"
DDL_AI_SQL = REPO_ROOT / "datagen" / "ddl" / "V003__ai_inspection.sql"
BACKEND_ROOT = REPO_ROOT / "backend"
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

from module_energy.domain.suggestion_calculation import (  # noqa: E402
    HISTORY_VERIFICATION_VERSION,
    VALID_QUALITY_CODES,
    ReadingSample,
    TariffRate,
    VerificationInput,
    VerificationResult,
    VerificationWindow,
    WorkloadSample,
    build_calculation_signature,
    calculate_daily_metric_series,
    calculate_history_verification,
)


ACT5_TEST_DB = os.environ.get("ACT5_TEST_DB")
ACT4_TEST_DB = os.environ.get("ACT4_TEST_DB")
if ACT5_TEST_DB and ACT4_TEST_DB and ACT5_TEST_DB != ACT4_TEST_DB:
    raise ValueError("ACT5_TEST_DB and ACT4_TEST_DB must match when both are set")
TEST_DB = ACT5_TEST_DB or ACT4_TEST_DB
if TEST_DB and not re.fullmatch(r"codex_[a-z0-9_]*_test", TEST_DB):
    raise ValueError("test database must match codex_*_test")

DB_CONF = dict(
    host="127.0.0.1",
    port=3306,
    user="demo",
    password="bdemo_dev",
    database=TEST_DB or "b_demo",
    charset="utf8mb4",
)

RANDOM_SEED = 20260713

# 时间跨度：构造规范 §2
DEMO_START = date(2026, 5, 4)   # 周一
DEMO_END = date(2026, 7, 12)    # 周日
BASELINE_END = date(2026, 6, 28)         # 基线期末（前 8 周）
REPORTING_START = date(2026, 6, 29)      # 报告期起
TOTAL_DAYS = (DEMO_END - DEMO_START).days + 1  # 70

# 峰平谷时段（PRD §7.1）
TOU_PEAK_HOURS = {8, 9, 10, 18, 19, 20}         # 峰 08–11、18–21（左闭右开）
TOU_VALLEY_HOURS = {22, 23, 0, 1, 2, 3, 4, 5}   # 谷 22–06

# 常量密码：与 RuoYi 骨架 admin/admin123 一致（bcrypt cost=10）。
# demo 阶段五角色共用，节约演示成本。README 已声明骨架默认口令。
DEMO_PASSWORD_BCRYPT = "$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2"

# ----------------------------------------------------------------------
# 主数据配置（构造规范 §1）
# ----------------------------------------------------------------------

# 装卸区（§1.1）——dept_id 关联骨架 sys_dept 中的 A/B 装卸区节点（103/104）
AREAS: list[dict] = [
    {"code": "AREA-A", "name": "A 钢材装卸区", "cargo": "钢材（吊装+平车转运）", "dept_id": 103},
    {"code": "AREA-B", "name": "B 粉煤灰筒仓区", "cargo": "粉煤灰（气力输送+筒仓）", "dept_id": 104},
]

# 设备台账 12 台（§1.2）
EQUIPMENTS: list[dict] = [
    {"code": "GC-A1", "name": "龙门吊 1 号", "area": "AREA-A", "type": "loading_main", "rated_kw": 180.0,
     "energy": "electricity", "demo_role": "第三幕主角（高耗/空载异常 INJ-03）"},
    {"code": "GC-A2", "name": "龙门吊 2 号", "area": "AREA-A", "type": "loading_main", "rated_kw": 180.0,
     "energy": "electricity", "demo_role": "正常对照组"},
    {"code": "FC-A1", "name": "转运平车", "area": "AREA-A", "type": "transfer", "rated_kw": 45.0,
     "energy": "electricity", "demo_role": ""},
    {"code": "BC-A1", "name": "皮带输送机", "area": "AREA-A", "type": "conveyor", "rated_kw": 30.0,
     "energy": "electricity", "demo_role": "数据跳变 INJ-02 载体"},
    {"code": "DF-A1", "name": "除尘风机", "area": "AREA-A", "type": "aux", "rated_kw": 22.0,
     "energy": "electricity", "demo_role": ""},
    {"code": "LT-A1", "name": "A 区照明回路", "area": "AREA-A", "type": "lighting", "rated_kw": 12.0,
     "energy": "electricity", "demo_role": "第四幕支线 G.8 夜间照明 INJ-05"},
    {"code": "WP-A1", "name": "A 区冲洗水泵", "area": "AREA-A", "type": "aux", "rated_kw": 7.5,
     "energy": "electricity,water", "demo_role": "覆盖率不足 INJ-07 载体"},
    {"code": "PN-B1", "name": "气力输送系统", "area": "AREA-B", "type": "loading_main", "rated_kw": 132.0,
     "energy": "electricity,compressed_air", "demo_role": ""},
    {"code": "AC-B1", "name": "空压机 1 号", "area": "AREA-B", "type": "compressor", "rated_kw": 90.0,
     "energy": "electricity,compressed_air", "demo_role": "第四幕主角（G.6 疑似泄漏 INJ-04）"},
    {"code": "AC-B2", "name": "空压机 2 号", "area": "AREA-B", "type": "compressor", "rated_kw": 90.0,
     "energy": "electricity,compressed_air", "demo_role": "正常对照组"},
    {"code": "SF-B1", "name": "筒仓风机", "area": "AREA-B", "type": "aux", "rated_kw": 15.0,
     "energy": "electricity", "demo_role": ""},
    {"code": "LT-B1", "name": "B 区照明回路", "area": "AREA-B", "type": "lighting", "rated_kw": 8.0,
     "energy": "electricity", "demo_role": ""},
]

# 能源类型字典（PRD §7.1）
ENERGY_TYPES = [
    {"code": "electricity", "name": "电", "unit": "kWh", "sort": 1},
    {"code": "water", "name": "水", "unit": "m3", "sort": 2},
    {"code": "compressed_air", "name": "压缩空气", "unit": "m3", "sort": 3},
]

# 采集点类别 → 采样周期（秒），对齐构造规范 §1.3
CATEGORY_SAMPLE_SEC = {
    "device_meter": 900,   # 15 min
    "area_meter":   900,
    "branch_meter": 900,
    "water":        900,
    "air_flow":     300,   # 5 min
    "status":       300,   # 5 min（设备运行状态点）
    "env":          900,
}

# 告警规则库（PRD §7.2）
ALERT_RULES = [
    ("R01", "采集离线", "quality", "采集点连续 3 个周期无数据",
     {"consecutive_periods": 3}, "normal", "K.7-R02 / G.3 第二幕（INJ-01）"),
    ("R02", "数据迟到", "quality", "批次入库时间 − 采样时间 > 15 分钟",
     {"delay_minutes": 15}, "notice", "质量类"),
    ("R03", "连续零值", "quality", "设备运行中且连续 4 个周期增量 = 0",
     {"consecutive_periods": 4}, "normal", "疑似表计故障"),
    ("R04", "数据跳变", "quality", "单周期增量 > 8× 前 4 周同时段均值",
     {"multiplier": 8, "reference_weeks": 4}, "normal", "K.7-R03 / INJ-02"),
    ("R05", "设备高耗偏离", "energy", "连续 3 小时能耗 > 同类均值 ×1.5 且作业量未同步增加",
     {"hours": 3, "energy_multiplier": 1.5, "workload_delta_max": 0}, "severe", "K.7-R05 / G.5 第三幕（INJ-03）"),
    ("R06", "非作业气流量异常", "energy", "非作业时段气流量 > 作业均值 ×30%，持续 2 小时",
     {"work_pct_threshold": 0.30, "duration_hours": 2}, "severe", "K.7-R06 / G.6 第四幕（INJ-04）"),
    ("R07", "空压机频繁启停", "energy", "1 小时内启停 ≥6 次",
     {"toggles_per_hour": 6}, "normal", "G.6 辅证"),
    ("R08", "非作业照明异常", "energy", "非作业时段照明回路功率 > 2kW，持续 1 小时",
     {"power_kw_threshold": 2, "duration_hours": 1}, "notice", "K.7-R07 / G.8 支线（INJ-05）"),
    ("R09", "基线偏差", "energy", "日能耗超出基线带 +20%（演示态）",
     {"deviation_pct": 20}, "normal", "总览联动 REQ-029"),
    ("R10", "峰段成本占比异常", "cost", "成本中心峰段成本占比较基线期抬升 > 8 个百分点",
     {"pct_point_threshold": 8}, "normal", "K.7-R10 / G.9 第五幕（INJ-06）"),
    ("R11", "覆盖率不足", "quality", "日采样覆盖率 [80%,95%) 提示，< 80% 严重",
     {"warn_threshold": 0.95, "severe_threshold": 0.80}, "notice", "K.7-R04 / 第二幕佐证（INJ-07 触发严重档）"),
]

# 单价（PRD §7.1）——生效起日期取 demo 起始日之前，覆盖全时段
TARIFFS = [
    ("electricity", "peak",    1.20, "峰时段单价（08–11、18–21）"),
    ("electricity", "flat",    0.75, "平时段单价"),
    ("electricity", "valley",  0.40, "谷时段单价（22–06）"),
    ("water",       "flat_only", 4.50, "水单一价"),
    ("compressed_air", "flat_only", 0.12, "压缩空气折算单价（本 PRD 暂定样例）"),
]

# 分摊规则（PRD §7.3）
ALLOC_RULE = {
    "name": "按额定功率权重分摊",
    "scope": "area",
    "method": "rated_power_weight",
    "config": {"basis": "equipment.rated_power_kw"},
    "effective_from": DEMO_START.isoformat(),
}

# 第四幕固定演示时钟与历史建议（知识库《demo-演示数据构造规范》§8）。
# REQ-045~050：默认模板是配置；H1~H8 是人工历史，不伪造规则/告警来源。
DEMO_NOW = datetime(2026, 7, 12, 23, 59)
DEFAULT_SUGGESTION_TEMPLATE_CODE = "TPL-R06-AIR-LEAK-DEFAULT"

HISTORY_SUGGESTIONS: tuple[dict, ...] = (
    {
        "code": "H1",
        "title": "A 区龙门吊 2 号待机用能复核",
        "area": "AREA-A",
        "equipment": "GC-A2",
        "status": "pending",
        "responsible_user": None,
        "measure": "复核待机时段用能记录并形成书面巡检结论。",
        "factors": (68, 60, 55, 40, 45),
        "path": (None, "pending"),
        "times": (datetime(2026, 7, 12, 8, 30),),
    },
    {
        "code": "H2",
        "title": "A 区转运平车班后用能巡检",
        "area": "AREA-A",
        "equipment": "FC-A1",
        "status": "dispatched",
        "responsible_user": "ops_user",
        "measure": "安排运维核对班后用能曲线并记录现场巡检情况。",
        "factors": (72, 65, 60, 55, 50),
        "path": (None, "pending", "dispatched"),
        "times": (
            datetime(2026, 7, 9, 9, 0),
            datetime(2026, 7, 10, 9, 0),
        ),
    },
    {
        "code": "H3",
        "title": "A 区除尘风机运行效率巡检",
        "area": "AREA-A",
        "equipment": "DF-A1",
        "status": "executing",
        "responsible_user": "ops_user",
        "measure": "人工检查滤网与风道状态，补充巡检记录和现场观察。",
        "factors": (80, 72, 70, 45, 65),
        "path": (None, "pending", "dispatched", "executing"),
        "times": (
            datetime(2026, 7, 6, 9, 0),
            datetime(2026, 7, 7, 9, 0),
            datetime(2026, 7, 8, 9, 0),
        ),
    },
    {
        "code": "H4",
        "title": "B 区筒仓风机运行效率验证",
        "area": "AREA-B",
        "equipment": "SF-B1",
        "status": "verifying",
        "responsible_user": "ops_user",
        "measure": "人工核对风机巡检前后用能、成本、作业量和质量覆盖。",
        "factors": (76, 70, 75, 40, 70),
        "path": (None, "pending", "dispatched", "executing", "verifying"),
        "times": (
            datetime(2026, 6, 20, 9, 0),
            datetime(2026, 6, 21, 9, 0),
            datetime(2026, 6, 22, 9, 0),
            datetime(2026, 7, 8, 18, 0),
        ),
    },
    {
        "code": "H5",
        "title": "B 区空压机 2 号运行电耗优化复盘",
        "area": "AREA-B",
        "equipment": "AC-B2",
        "status": "valid_closed",
        "responsible_user": "ops_user",
        "measure": "完成人工巡检记录，并以固定窗口数据复核实施效果。",
        "factors": (84, 78, 80, 35, 75),
        "path": (
            None,
            "pending",
            "dispatched",
            "executing",
            "verifying",
            "valid_closed",
        ),
        "times": (
            datetime(2026, 6, 20, 10, 0),
            datetime(2026, 6, 21, 10, 0),
            datetime(2026, 6, 22, 10, 0),
            datetime(2026, 7, 8, 17, 0),
            datetime(2026, 7, 9, 10, 0),
        ),
    },
    {
        "code": "H6",
        "title": "A 区皮带输送机人工巡检效果复盘",
        "area": "AREA-A",
        "equipment": "BC-A1",
        "status": "invalid_closed",
        "responsible_user": "ops_user",
        "measure": "复核人工巡检后的用能表现，记录未达到预期的验证结论。",
        "factors": (65, 58, 68, 50, 55),
        "path": (
            None,
            "pending",
            "dispatched",
            "executing",
            "verifying",
            "invalid_closed",
        ),
        "times": (
            datetime(2026, 6, 24, 10, 0),
            datetime(2026, 6, 25, 10, 0),
            datetime(2026, 6, 26, 10, 0),
            datetime(2026, 7, 12, 10, 0),
            datetime(2026, 7, 12, 15, 0),
        ),
    },
    {
        "code": "H7",
        "title": "B 区气力输送系统重复建议归档",
        "area": "AREA-B",
        "equipment": "PN-B1",
        "status": "invalid_closed",
        "responsible_user": None,
        "measure": "与既有人工巡检建议核对后，按重复建议归档并保留原因。",
        "factors": (50, 45, 40, 65, 35),
        "path": (None, "pending", "invalid_closed"),
        "times": (
            datetime(2026, 7, 10, 8, 0),
            datetime(2026, 7, 10, 9, 0),
        ),
    },
    {
        "code": "H8",
        "title": "A 区冲洗水泵维护窗口协调延期",
        "area": "AREA-A",
        "equipment": "WP-A1",
        "status": "deferred",
        "responsible_user": "ops_user",
        "measure": "等待维护窗口后开展人工巡检，并记录恢复安排。",
        "factors": (70, 66, 90, 75, 60),
        "path": (None, "pending", "dispatched", "deferred"),
        "times": (
            datetime(2026, 7, 9, 8, 0),
            datetime(2026, 7, 10, 8, 0),
            datetime(2026, 7, 12, 8, 0),
        ),
    },
)

HISTORY_VERIFICATION_CASES: dict[str, dict] = {
    "H4": {
        "point_code": "SF-B1-E",
        "repair_at": datetime(2026, 7, 1),
        "baseline_start": datetime(2026, 6, 24),
        "baseline_end": datetime(2026, 7, 1),
        "report_start": datetime(2026, 7, 1),
        "report_end": datetime(2026, 7, 8),
        "generated_at": datetime(2026, 7, 8, 18, 30),
        "status": "effective",
        "anchors": (Decimal("9.61"), Decimal("15.52"), Decimal("2.38")),
    },
    "H5": {
        "point_code": "AC-B2-E",
        "repair_at": datetime(2026, 7, 1),
        "baseline_start": datetime(2026, 6, 24),
        "baseline_end": datetime(2026, 7, 1),
        "report_start": datetime(2026, 7, 1),
        "report_end": datetime(2026, 7, 8),
        "generated_at": datetime(2026, 7, 9, 9, 30),
        "status": "effective",
        "anchors": (Decimal("8.50"), Decimal("14.78"), Decimal("1.18")),
    },
    "H6": {
        "point_code": "BC-A1-E",
        "repair_at": datetime(2026, 7, 5),
        "baseline_start": datetime(2026, 6, 28),
        "baseline_end": datetime(2026, 7, 5),
        "report_start": datetime(2026, 7, 5),
        "report_end": datetime(2026, 7, 12),
        "generated_at": datetime(2026, 7, 12, 14, 30),
        "status": "ineffective",
        "anchors": (Decimal("-4.76"), Decimal("-5.29"), Decimal("-14.04")),
    },
}

# ----------------------------------------------------------------------
# 异常注入清单（构造规范 §4）
# ----------------------------------------------------------------------

INJ_01 = {
    "date": date(2026, 7, 6),
    "outage_start_hm": (9, 20),
    "outage_end_hm": (13, 40),
    "equipment_codes": ["GC-A1", "GC-A2", "FC-A1", "BC-A1"],  # A 区 4 台设备电表
    "reason": "采集网关-A 主链路中断",
    "backfill_batch": "BACKFILL-20260706-1340",
    "backfill_ingest_hm": (14, 0),
}

INJ_02 = {
    "date": date(2026, 7, 1),
    "time_hm": (10, 30),
    "equipment_code": "BC-A1",
    "multiplier": 100,
}

INJ_03 = {
    "date": date(2026, 7, 8),
    "start_hm": (13, 0),
    "end_hm": (17, 0),
    "equipment_code": "GC-A1",
    "utilization": 0.55,  # 保持额定 55%
    "reason": "待机但高功率——载体：疑似空载",
}

INJ_04 = {
    "start_date": date(2026, 7, 2),
    "repair_date": date(2026, 7, 10),
    "point_code": "AF-B-MAIN",  # B 区管网干管流量
    "abnormal_pct": 0.38,       # 非作业时段抬升至作业均值 38%
    "baseline_pct": 0.05,
}

INJ_05 = {
    "dates": [date(2026, 7, 3), date(2026, 7, 7)],
    "equipment_code": "LT-A1",
    "night_hours": range(22, 24),  # 22:00–24:00
    "morning_hours": range(0, 6),  # 00:00–06:00
    "utilization": 0.65,           # 保持白天负荷
}

INJ_06 = {
    "month_year": 2026,
    "month": 6,
    "area_code": "AREA-B",
    "peak_shift_pct": 0.12,  # 峰段用电占比抬升 12 个百分点
}

INJ_07 = {
    "date": date(2026, 7, 5),
    "point_code": "WP-A1-E",   # WP-A1 电表；水也可选，规范原文说"水表 WP-A1"
    "water_point_code": "WP-A1-W",
    "coverage_target": 0.70,
}

# INJ-08 由权限拦截真实生成审计记录（构造规范 §7 自检项：不允许手工插）。
# 本脚本仅确保 ops_user 无成本菜单权限（seed_accounts），演示时现场触发。

# INJ-09（2026-07-13 用户决策补入）：报告期工作日全厂电力同比例抬升，触发 R09 基线偏差。
# 只作用于电表类采集点（device_meter / branch_meter），area_meter 因由 device 求和构造会自动跟随；
# 水/气/状态/环境不受影响，避免扰动其它 INJ。
INJ_09 = {
    "date": date(2026, 7, 9),
    "scale": 1.25,   # 每时段增量 ×1.25，目标日偏差 >+21%（超 R09 +20% 阈值留余量）
}

# ----------------------------------------------------------------------
# 主设备与作业窗（2026-07-13 任务 #17：工单覆盖补齐 —— R05 收敛到 INJ-03 单窗口）
# 规范 §3 相关性："作业量与设备能耗按相关性生成，INJ-03 时段刻意不排工单"
# —— 言下之意：其它主设备高负荷时段都应有工单覆盖，否则 backend R05
#    "高耗且作业未增" 会在自然峰段处误报（对照组也会中枪）。
# 覆盖范围：loading_main / conveyor / transfer 三类主设备
# ----------------------------------------------------------------------
MAIN_EQUIPMENT_BY_AREA: dict[str, list[str]] = {
    "AREA-A": ["GC-A1", "GC-A2", "FC-A1", "BC-A1"],  # loading_main×2 + transfer + conveyor
    "AREA-B": ["PN-B1"],                              # loading_main
}
WORK_WINDOWS: list[tuple[int, int]] = [(8, 11), (14, 17)]  # 上午 / 下午（对齐 §3 双峰）


# ----------------------------------------------------------------------
# DB 辅助
# ----------------------------------------------------------------------

def connect(multi: bool = False) -> pymysql.connections.Connection:
    kwargs = dict(DB_CONF)
    if multi:
        kwargs["client_flag"] = CLIENT.MULTI_STATEMENTS
    return pymysql.connect(**kwargs, autocommit=False)


def split_sql_statements(text: str) -> list[str]:
    """按 `;\\n` 切分 SQL 语句，跳过注释与空行。适用于 skeleton/DDL 这类结构清晰的文件。"""
    # 去掉 -- 注释行
    lines = []
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("--") or stripped.startswith("#") or not stripped:
            continue
        lines.append(line)
    joined = "\n".join(lines)
    stmts = [s.strip() for s in re.split(r";\s*\n", joined) if s.strip()]
    # 兼容最后一行没换行的情形
    return [s.rstrip(";").strip() for s in stmts if s.rstrip(";").strip()]


def exec_sql_file(conn, path: Path, label: str) -> None:
    print(f"  ├─ exec {label}: {path.relative_to(REPO_ROOT)}", flush=True)
    text = path.read_text(encoding="utf-8")
    stmts = split_sql_statements(text)
    with conn.cursor() as cur:
        for i, stmt in enumerate(stmts, 1):
            try:
                cur.execute(stmt)
            except Exception as exc:
                print(f"     ! stmt #{i} 失败: {exc}\n{stmt[:200]}", file=sys.stderr)
                raise
    conn.commit()
    print(f"  │   done, {len(stmts)} statements executed.")


# ----------------------------------------------------------------------
# 阶段 1：完整重置（skeleton + DDL）
# ----------------------------------------------------------------------

def reset_schema(conn) -> None:
    """执行骨架 SQL 与能源域 DDL；两者都是 drop-if-exists 幂等的。"""
    print("[1/5] reset schema")
    exec_sql_file(conn, SKELETON_SQL, "skeleton-init.sql")
    exec_sql_file(conn, DDL_SQL, "V001__energy_domain.sql")
    # V003：AI Agent 域（ai_inspection_report），drop-if-exists 幂等；
    # V002 是第五幕影子迁移（仅供 test_act5_migration 校验），不在 reset 路径执行。
    exec_sql_file(conn, DDL_AI_SQL, "V003__ai_inspection.sql")


def truncate_business_data(conn) -> None:
    """幂等模式下清空能源域业务表（保留骨架系统表结构，跳过 sys_dept/sys_user/sys_role 之类已由 skeleton seed 的内容——这些也会在 seed_accounts() 中被 upsert）。

    注意：ai_inspection_report 不进本列表——真实巡检报告由 backend 侧 agent workflow 产出，
    非 reset 路径应保留既有报告（数据安全纪律 P-01：报数必查库，不能被幂等清掉）；
    如需彻底清空 AI 报告表，跑 --reset 走 V003 的 drop-if-exists。
    """
    print("[1/5] truncate business tables")
    tables = [
        "e_report_archive", "e_cost_recompute_record", "e_cost_record",
        "e_suggestion_flow_log", "e_suggestion_verification",
        "e_suggestion", "e_suggestion_template",
        "e_dict_energy_type", "e_area", "e_equipment", "e_meter_point",
        "e_raw_reading", "e_collect_task", "e_equipment_status_log", "e_work_order",
        "e_stat_hour", "e_stat_day", "e_stat_month", "e_recompute_log",
        "e_energy_baseline", "e_equipment_profile", "e_forecast_result",
        "e_alert_rule", "e_alert_rule_version", "e_alert_event", "e_alert_flow_log",
        "e_tariff_version", "e_cost_alloc_rule",
        "e_audit_security",
    ]
    with conn.cursor() as cur:
        cur.execute("SET FOREIGN_KEY_CHECKS=0")
        for t in tables:
            cur.execute(f"TRUNCATE TABLE {t}")
        cur.execute("SET FOREIGN_KEY_CHECKS=1")
    conn.commit()


# ----------------------------------------------------------------------
# 阶段 2：种子账号（PRD §3.2 附录 K.6 五角色）
# ----------------------------------------------------------------------

def seed_accounts(conn) -> None:
    """新增能源域菜单 + K.6 四个演示账号（admin 已由骨架 seed）+ 角色/关联关系。

    权限差异（对齐 PRD §3.1 K.6）：
    - energy_mgr  role_id=3 data_scope=1 全部业务菜单
    - ops_user    role_id=4 data_scope=1 全部业务菜单**不含成本/报表**（越权样例 INJ-08 落点）
    - dispatch_user role_id=5 data_scope=2 自定义→仅 A 钢材装卸区（sys_dept 103）
    - finance_user role_id=6 data_scope=1 成本+报表+总览
    - admin       复用骨架 role_id=1 (超级管理员)，用户名沿用 skeleton 已 seed 的 admin

    部门树由骨架 seed 一次到位（100–109 B 项目组织，含 A/B 装卸区 dept 103/104）。
    """
    print("[2/5] seed accounts + menus + roles")
    now = datetime.now().replace(microsecond=0)
    with conn.cursor() as cur:
        # ---- 菜单（能源域一级目录 + 12 个业务页面），从 ID 2000 起 ----
        # 2026-07-14 A1 修复：能源总览 component 指到骨架 dashboard/index；
        #   原始数据与质量指到 web/src/views/raw-quality/index；
        #   第四幕节能建议、第五幕成本与报表已开放；其余未开发页 visible='1' 继续隐藏
        cur.execute("DELETE FROM sys_menu WHERE menu_id >= 2000")
        menus: list[tuple] = [
            # (id, name, parent, order, path, component, perms, icon, menu_type, visible, remark)
            (2000, "能源管控",   0,    3, "energy",         None,                              "",                             "chart",     "M", "0", "能源域一级目录"),
            (2001, "能源总览",   2000, 1, "overview",       "dashboard/index",                 "energy:overview:view",         "monitor",   "C", "0", "REQ-057~062 驾驶舱（第一幕）"),
            (2002, "能源分析",   2000, 2, "analysis",       None,                              "",                             "chart",     "M", "0", "分析目录｜第三幕开放设备能耗画像，统计分析/作业归因仍隐藏"),
            (2003, "统计分析",   2002, 1, "statistics",     "energy/analysis/statistics",      "energy:analysis:statistics",   "chart",     "C", "1", "REQ-024~030/104｜A1 隐藏，未开发"),
            (2004, "设备能耗画像", 2002, 2, "profile",       "energy/analysis/profile",         "energy:analysis:profile",      "peoples",   "C", "0", "REQ-033~035 第三幕已开发"),
            (2005, "作业能耗归因", 2002, 3, "attribution",   "energy/analysis/attribution",     "energy:analysis:attribution",  "job",       "C", "1", "REQ-031~038 归因｜A1 隐藏，未开发"),
            (2006, "采集与质量",  2000, 3, "collect",        None,                              "",                             "eye",       "M", "0", "采集目录"),
            (2007, "采集点台账",  2006, 1, "meter-point",    "energy/collect/meter-point",      "energy:collect:point:list",    "tree",      "C", "1", "REQ-002~009/103｜A1 隐藏，未开发"),
            (2008, "原始数据与质量", 2006, 2, "raw-quality",  "raw-quality/index",               "energy:collect:raw:view",      "form",      "C", "0", "REQ-010~023 第二幕（差异化）"),
            (2009, "异常与建议",  2000, 4, "alert",          None,                              "",                             "bug",       "M", "0", "异常目录｜第三幕异常告警、第四幕节能建议均已开放"),
            (2010, "异常告警",    2009, 1, "alert-list",     "energy/alert/list",               "energy:alert:list",            "message",   "C", "0", "REQ-039~044 第三幕已开发"),
            (2011, "节能建议",    2009, 2, "suggestion",     "energy/alert/suggestion",         "energy:alert:suggestion",      "star",      "C", "0", "REQ-045~050 第四幕已开发"),
            (2012, "成本与报表",  2000, 5, "cost",           None,                              "",                             "money",     "M", "0", "REQ-051~062 成本与报表目录｜第五幕已开发"),
            (2013, "成本核算",    2012, 1, "cost-record",    "energy/cost/record",              "energy:cost:record",           "money",     "C", "0", "REQ-051~056 成本核算｜第五幕已开发"),
            (2014, "报表导出",    2012, 2, "cost-report",    "energy/cost/report",              "energy:cost:report",           "excel",     "C", "0", "REQ-059~062 五段式月报｜第五幕已开发"),
            # REQ-AGENT-TBD：demo 范围外新增，AI 自主巡检报告页；K.6 窄授权同报表页（仅 admin/energy_mgr 可见）
            (2015, "AI 巡检",     2000, 6, "ai-inspection",  "energy/aiInspection/index",       "energy:agent:inspection",      "eye-open",  "C", "0", "REQ-AGENT-TBD AI 自主巡检报告｜K.6 仅 admin/energy_mgr 可见"),
        ]
        cur.executemany(
            "INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, "
            "is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark) "
            "VALUES (%s, %s, %s, %s, %s, %s, '', '', 1, 0, %s, %s, '0', %s, %s, 'datagen', %s, %s)",
            [(mid, name, parent, ordn, path, comp, mtype, visible, perms, icon, now, remark)
             for (mid, name, parent, ordn, path, comp, perms, icon, mtype, visible, remark) in menus],
        )

        # ---- 角色（3=energy_mgr / 4=ops / 5=dispatch / 6=finance；1=admin 复用骨架超管）----
        cur.execute("DELETE FROM sys_role WHERE role_id BETWEEN 3 AND 6")
        roles = [
            (3, "能源管理员", "energy_mgr",  3, "1"),  # data_scope 1=全部
            (4, "运维",       "ops",         4, "1"),
            (5, "调度",       "dispatch",    5, "2"),  # 2=自定义（sys_role_dept 绑定）
            (6, "财务",       "finance",     6, "1"),
        ]
        cur.executemany(
            "INSERT INTO sys_role (role_id, role_name, role_key, role_sort, data_scope, menu_check_strictly, "
            "dept_check_strictly, status, del_flag, create_by, create_time, remark) "
            "VALUES (%s, %s, %s, %s, %s, 1, 1, '0', '0', 'datagen', %s, %s)",
            [(r[0], r[1], r[2], r[3], r[4], now, f"PRD K.6 - {r[1]}") for r in roles],
        )

        # ---- 用户（100=energy_mgr / 101=ops_user / 102=dispatch_user / 103=finance_user；admin 复用骨架 user_id=1）----
        # dept 分配对齐骨架 B 项目组织树：能源管理部 105 / 调度中心 106 / 运维部 107 / 财务部 108
        cur.execute("DELETE FROM sys_user WHERE user_id BETWEEN 100 AND 103")
        users = [
            (100, 105, "energy_mgr",    "能源管理员（演示）", "energy_mgr@demo.example.com", "138****0002", "五幕主角色"),
            (101, 107, "ops_user",      "运维（演示）",       "ops_user@demo.example.com",   "138****0003", "第二幕补传操作 / G.12 越权样例"),
            (102, 106, "dispatch_user", "调度（演示）",       "dispatch@demo.example.com",   "138****0004", "第三幕作业对照（A 钢材装卸区数据范围）"),
            (103, 108, "finance_user",  "财务（演示）",       "finance@demo.example.com",    "138****0005", "第五幕成本复核"),
        ]
        cur.executemany(
            "INSERT INTO sys_user (user_id, dept_id, user_name, nick_name, user_type, email, phonenumber, sex, "
            "avatar, password, status, del_flag, login_ip, login_date, pwd_update_date, create_by, create_time, "
            "update_by, update_time, remark) "
            "VALUES (%s, %s, %s, %s, '00', %s, %s, '0', '', %s, '0', '0', '127.0.0.1', %s, %s, 'datagen', %s, '', NULL, %s)",
            [(u[0], u[1], u[2], u[3], u[4], u[5], DEMO_PASSWORD_BCRYPT, now, now, now, u[6]) for u in users],
        )

        # ---- 用户 → 角色 ----
        cur.execute("DELETE FROM sys_user_role WHERE user_id BETWEEN 100 AND 103")
        cur.executemany(
            "INSERT INTO sys_user_role (user_id, role_id) VALUES (%s, %s)",
            [(100, 3), (101, 4), (102, 5), (103, 6)],
        )

        # ---- 角色 → 部门（dispatch 仅 A 钢材装卸区 dept=103）----
        cur.execute("DELETE FROM sys_role_dept WHERE role_id = 5")
        cur.execute("INSERT INTO sys_role_dept (role_id, dept_id) VALUES (5, 103)")

        # ---- 角色 → 菜单 ----
        # energy_mgr 全菜单 + 系统查询菜单（登录必要）
        common_sys_menus = [1, 100, 1000]  # 系统管理目录、用户菜单、用户查询（最简）
        energy_all = [m[0] for m in menus]
        # ops 不含成本/报表（INJ-08 落点）；AI 巡检（2015）同报表页 K.6 窄授权，也不给 ops。
        energy_no_cost = [m for m in energy_all if m not in (2012, 2013, 2014, 2015)]
        dispatch_menus = [2000, 2001, 2002, 2005]  # 总览 + 作业归因
        finance_menus = [2000, 2001, 2012, 2013, 2014]  # 总览 + 成本目录 + 成本核算 + 报表导出

        role_menu_pairs: list[tuple[int, int]] = []
        for m in energy_all + common_sys_menus:
            role_menu_pairs.append((3, m))
        for m in energy_no_cost + common_sys_menus:
            role_menu_pairs.append((4, m))
        for m in dispatch_menus:
            role_menu_pairs.append((5, m))
        for m in finance_menus:
            role_menu_pairs.append((6, m))
        # skeleton 已给 role_id=1 (admin) 全部骨架菜单；再补上能源域，便于 admin 查审计时可见
        for m in energy_all:
            role_menu_pairs.append((1, m))
        cur.execute("DELETE FROM sys_role_menu WHERE role_id IN (1,3,4,5,6) AND menu_id >= 2000")
        cur.execute("DELETE FROM sys_role_menu WHERE role_id IN (3,4,5,6) AND menu_id < 2000")
        cur.executemany("INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES (%s, %s)", role_menu_pairs)

    conn.commit()


# ----------------------------------------------------------------------
# 阶段 3：种子主数据（区/设备/采集点/规则/单价/分摊/基线草稿）
# ----------------------------------------------------------------------

def build_meter_points() -> list[dict]:
    """构造 48 个采集点，编号规则：<设备/区代号>-<能源/类别>[-序号]"""
    points: list[dict] = []

    # 12 台设备各挂 1 个电表（device_meter）
    for eq in EQUIPMENTS:
        points.append({
            "code": f"{eq['code']}-E",
            "name": f"{eq['name']}·电表",
            "area": eq["area"],
            "equipment": eq["code"],
            "category": "device_meter",
            "energy": "electricity",
            "unit": "kWh",
        })

    # 2 个区级电总表
    for a in AREAS:
        points.append({
            "code": f"{a['code']}-E-MAIN",
            "name": f"{a['name']}·区级电总表",
            "area": a["code"], "equipment": None,
            "category": "area_meter", "energy": "electricity", "unit": "kWh",
        })

    # 6 条支路电表（A 区 4、B 区 2）
    for i in range(1, 5):
        points.append({
            "code": f"AREA-A-BR{i}",
            "name": f"A 区支路{i}电表",
            "area": "AREA-A", "equipment": None,
            "category": "branch_meter", "energy": "electricity", "unit": "kWh",
        })
    for i in range(1, 3):
        points.append({
            "code": f"AREA-B-BR{i}",
            "name": f"B 区支路{i}电表",
            "area": "AREA-B", "equipment": None,
            "category": "branch_meter", "energy": "electricity", "unit": "kWh",
        })

    # 4 个水表（区总 ×2 + WP-A1 水表 + 生活水）
    for a in AREAS:
        points.append({
            "code": f"{a['code']}-W-MAIN",
            "name": f"{a['name']}·区水总表",
            "area": a["code"], "equipment": None,
            "category": "water", "energy": "water", "unit": "m3",
        })
    points.append({
        "code": "WP-A1-W", "name": "A 区冲洗水泵·水表",
        "area": "AREA-A", "equipment": "WP-A1",
        "category": "water", "energy": "water", "unit": "m3",
    })
    points.append({
        "code": "AREA-A-W-LIFE", "name": "A 区生活水表",
        "area": "AREA-A", "equipment": None,
        "category": "water", "energy": "water", "unit": "m3",
    })

    # 6 压缩空气流量计（AC-B1/B2 出口、PN-B1 入口、B 干管、A 用气支管、末端）
    air_points = [
        ("AF-AC-B1-OUT",  "AC-B1 出口流量",     "AREA-B", "AC-B1"),
        ("AF-AC-B2-OUT",  "AC-B2 出口流量",     "AREA-B", "AC-B2"),
        ("AF-PN-B1-IN",   "PN-B1 入口流量",     "AREA-B", "PN-B1"),
        ("AF-B-MAIN",     "B 区管网干管流量",    "AREA-B", None),   # INJ-04 载体
        ("AF-A-BRANCH",   "A 区用气支管流量",    "AREA-A", None),
        ("AF-B-TERMINAL", "B 区管网末端流量",    "AREA-B", None),
    ]
    for code, name, area, eq in air_points:
        points.append({
            "code": code, "name": name, "area": area, "equipment": eq,
            "category": "air_flow", "energy": "compressed_air", "unit": "m3",
        })

    # 12 个设备状态点
    for eq in EQUIPMENTS:
        points.append({
            "code": f"{eq['code']}-STATUS",
            "name": f"{eq['name']}·运行状态",
            "area": eq["area"], "equipment": eq["code"],
            "category": "status", "energy": None, "unit": "none",
        })

    # 6 个环境点（区温度 ×2、气压 ×2、光照 ×2）
    env_specs = [
        ("ENV-A-TEMP",  "A 区温度",  "AREA-A", "℃"),
        ("ENV-B-TEMP",  "B 区温度",  "AREA-B", "℃"),
        ("ENV-A-PRESS", "A 区气压",  "AREA-A", "kPa"),
        ("ENV-B-PRESS", "B 区气压",  "AREA-B", "kPa"),
        ("ENV-A-LIGHT", "A 区光照",  "AREA-A", "lx"),
        ("ENV-B-LIGHT", "B 区光照",  "AREA-B", "lx"),
    ]
    for code, name, area, unit in env_specs:
        points.append({
            "code": code, "name": name, "area": area, "equipment": None,
            "category": "env", "energy": None, "unit": unit,
        })
    return points


def seed_master_data(conn) -> dict:
    """写入能源域主数据，返回 (equipment_code→id、point_code→id、area_code→id、rule_code→id)"""
    print("[3/5] seed master data (energy types / areas / equipment / meter points / rules / tariffs / baseline)")
    now = datetime.now().replace(microsecond=0)
    ids: dict[str, dict] = {"area": {}, "equipment": {}, "point": {}, "rule": {}}
    with conn.cursor() as cur:
        # 能源类型
        cur.executemany(
            "INSERT INTO e_dict_energy_type (type_code, type_name, base_unit, sort_no, remark) VALUES (%s,%s,%s,%s,%s)",
            [(t["code"], t["name"], t["unit"], t["sort"], "") for t in ENERGY_TYPES],
        )

        # 装卸区
        for a in AREAS:
            cur.execute(
                "INSERT INTO e_area (area_code, area_name, cargo_type, dept_id, status, remark, create_time, update_time) "
                "VALUES (%s,%s,%s,%s,'0',%s,%s,%s)",
                (a["code"], a["name"], a["cargo"], a["dept_id"], "构造规范 §1.1", now, now),
            )
            ids["area"][a["code"]] = cur.lastrowid

        # 设备
        for eq in EQUIPMENTS:
            cur.execute(
                "INSERT INTO e_equipment (equipment_code, equipment_name, area_id, equipment_type, rated_power_kw, "
                "energy_types, demo_role, status, remark, create_time, update_time) "
                "VALUES (%s,%s,%s,%s,%s,%s,%s,'0',%s,%s,%s)",
                (eq["code"], eq["name"], ids["area"][eq["area"]], eq["type"], eq["rated_kw"],
                 eq["energy"], eq["demo_role"], "构造规范 §1.2", now, now),
            )
            ids["equipment"][eq["code"]] = cur.lastrowid

        # 采集点
        points = build_meter_points()
        for p in points:
            area_id = ids["area"][p["area"]]
            eq_id = ids["equipment"].get(p["equipment"]) if p["equipment"] else None
            sample_sec = CATEGORY_SAMPLE_SEC[p["category"]]
            cur.execute(
                "INSERT INTO e_meter_point (point_code, point_name, area_id, equipment_id, point_category, "
                "energy_type_code, unit, sample_period_sec, source_type, multiplier, provision_status, access_mode, "
                "status, remark, create_time, update_time) "
                "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,'gateway',1.0000,'active','direct','enabled',%s,%s,%s)",
                (p["code"], p["name"], area_id, eq_id, p["category"], p["energy"], p["unit"],
                 sample_sec, f"构造规范 §1.3 类别={p['category']}", now, now),
            )
            ids["point"][p["code"]] = cur.lastrowid

        # 告警规则 R01–R11
        for code, name, cat, expr, thresholds, level, remark in ALERT_RULES:
            cur.execute(
                "INSERT INTO e_alert_rule (rule_code, rule_name, rule_category, expression, threshold_json, level, "
                "enabled, effective_from, version_no, remark, create_by, create_time, update_by, update_time) "
                "VALUES (%s,%s,%s,%s,%s,%s,1,%s,1,%s,'datagen',%s,'datagen',%s)",
                (code, name, cat, expr, json.dumps(thresholds, ensure_ascii=False),
                 level, datetime(2026, 5, 1), remark, now, now),
            )
            rid = cur.lastrowid
            ids["rule"][code] = rid
            cur.execute(
                "INSERT INTO e_alert_rule_version (rule_id, version_no, snapshot_json, effective_from, create_by, create_time) "
                "VALUES (%s,1,%s,%s,'datagen',%s)",
                (rid, json.dumps({"code": code, "name": name, "expr": expr, "thresholds": thresholds, "level": level},
                                 ensure_ascii=False),
                 datetime(2026, 5, 1), now),
            )

        # 单价版本
        for etype, tou, price, remark in TARIFFS:
            cur.execute(
                "INSERT INTO e_tariff_version (energy_type_code, tou_period, price, currency, effective_from, "
                "version_no, remark, create_by, create_time) VALUES (%s,%s,%s,'CNY',%s,1,%s,'datagen',%s)",
                (etype, tou, price, date(2026, 5, 1), remark, now),
            )

        # 分摊规则
        cur.execute(
            "INSERT INTO e_cost_alloc_rule (rule_name, scope, method, config_json, effective_from, version_no, "
            "create_by, create_time) VALUES (%s,%s,%s,%s,%s,1,'datagen',%s)",
            (ALLOC_RULE["name"], ALLOC_RULE["scope"], ALLOC_RULE["method"],
             json.dumps(ALLOC_RULE["config"], ensure_ascii=False), ALLOC_RULE["effective_from"], now),
        )

        # 能源基线（草稿占位；backend 起动后根据基线期真实计算发布）
        # FX-13：system + A/B 分区共 3 条草稿，分区偏差按各自基线真算（同一基线期与分桶方法）
        for baseline_code, object_scope, object_id in (
            ("BASELINE-SYSTEM-ELEC-2026", "system", None),
            ("BASELINE-AREA-A-ELEC-2026", "area", ids["area"]["AREA-A"]),
            ("BASELINE-AREA-B-ELEC-2026", "area", ids["area"]["AREA-B"]),
        ):
            cur.execute(
                "INSERT INTO e_energy_baseline (baseline_code, object_scope, object_id, energy_type_code, "
                "baseline_start, baseline_end, method, formula_version, status, remark, create_time, update_time) "
                "VALUES (%s, %s, %s, 'electricity', %s, %s, 'bucket', 'v0.1', "
                "'draft', 'demo 演示态占位，backend 起动后按 §7.3 分桶均值+σ 真实计算', %s, %s)",
                (baseline_code, object_scope, object_id, DEMO_START, BASELINE_END, now, now),
            )
    conn.commit()
    return ids


# ----------------------------------------------------------------------
# 第四幕：默认模板与历史建议 fixture（REQ-045~050）
# ----------------------------------------------------------------------

def seed_default_suggestion_template(conn) -> int:
    """无条件恢复 REQ-045 授权的 R06 系统内置默认模板。"""
    print("[3b/5] seed default R06 suggestion template")
    fixed_time = datetime(2026, 5, 1)
    with conn.cursor() as cur:
        cur.execute(
            "INSERT INTO e_suggestion_template (template_code, template_name, category, "
            "source_rule_code, applicable_object_type, action_content, required_data, "
            "estimated_saving, cost_impact, reliability_impact, verification_method, "
            "default_implementation_difficulty, default_safety_impact, enabled, version, "
            "create_time, update_time) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,1,1,%s,%s)",
            (
                DEFAULT_SUGGESTION_TEMPLATE_CODE,
                "压缩空气非作业时段异常人工巡检",
                "compressed_air_efficiency",
                "R06",
                "area",
                "派发运维人员巡检 B 区干管、接头、阀门和用气末端，记录疑似泄漏位置、人工处理过程与现场附件，处理后发起效果验证。",
                "AF-B-MAIN 流量、AREA-B mock 工单、验证期生效单价版本、相关采集点质量与覆盖率。",
                "按实施前后非作业时段有效小时均值差与报告期有效小时真算，不预置固定绝对值。",
                "按同一窗口用量与生效单价版本真算；单价缺失时不生成金额结论。",
                "通过人工巡检降低长期泄漏与空压系统额外负荷风险。",
                "修复点 2026-07-10 00:00:00；基线 [2026-07-02,2026-07-10)，报告 [2026-07-10,DEMO_NOW]；比较用量、成本、作业量和数据质量。",
                Decimal("35"),
                Decimal("70"),
                fixed_time,
                fixed_time,
            ),
        )
        template_id = cur.lastrowid
    conn.commit()
    return template_id


def load_verification_input(
    conn,
    *,
    point_code: str,
    area_code: str,
    baseline_start: datetime,
    baseline_end: datetime,
    report_start: datetime,
    report_end: datetime,
) -> VerificationInput:
    """把 raw/meter/tariff/work-order 行适配成共享历史验证 DTO。"""
    if baseline_end <= baseline_start or report_end <= report_start:
        raise ValueError("verification windows must be non-empty right-open ranges")
    point_spec = next(
        (point for point in build_meter_points() if point["code"] == point_code),
        None,
    )
    if point_spec is None or point_spec["area"] != area_code:
        raise ValueError(f"point {point_code} does not belong to {area_code}")

    start = min(baseline_start, report_start)
    end = max(baseline_end, report_end)
    with conn.cursor() as cur:
        cur.execute(
            "SELECT point_id, area_id, energy_type_code, unit, sample_period_sec "
            "FROM e_meter_point WHERE point_code=%s",
            (point_code,),
        )
        point_row = cur.fetchone()
        if point_row is None:
            raise ValueError(f"unknown meter point {point_code}")
        point_id, area_id, energy_type, unit, sample_period_seconds = point_row
        if not energy_type:
            raise ValueError(f"meter point {point_code} has no energy type")

        cur.execute(
            "SELECT sample_time, incremental_value, quality_state "
            "FROM e_raw_reading WHERE point_id=%s AND sample_time >= %s "
            "AND sample_time < %s ORDER BY sample_time",
            (point_id, start, end),
        )
        readings = tuple(
            ReadingSample(
                sample_time=sample_time,
                incremental_value=incremental_value,
                quality_state=quality_state,
            )
            for sample_time, incremental_value, quality_state in cur.fetchall()
        )

        cur.execute(
            "SELECT energy_type_code, tou_period, price, effective_from, "
            "effective_to, version_no FROM e_tariff_version "
            "WHERE energy_type_code=%s ORDER BY effective_from, version_no, tou_period",
            (energy_type,),
        )
        tariffs = tuple(
            TariffRate(
                energy_type=tariff_energy_type,
                tou_period=tou_period,
                price=price,
                effective_from=effective_from,
                effective_to=effective_to,
                version=version,
            )
            for (
                tariff_energy_type,
                tou_period,
                price,
                effective_from,
                effective_to,
                version,
            ) in cur.fetchall()
        )

        cur.execute(
            "SELECT start_time, workload_value, workload_unit, status "
            "FROM e_work_order WHERE area_id=%s AND start_time >= %s "
            "AND start_time < %s ORDER BY start_time, work_order_id",
            (area_id, start, end),
        )
        workloads = tuple(
            WorkloadSample(
                start_time=start_time,
                value=value,
                unit=workload_unit,
                status=status,
            )
            for start_time, value, workload_unit, status in cur.fetchall()
        )

    def window(window_start: datetime, window_end: datetime) -> VerificationWindow:
        return VerificationWindow(
            start=window_start,
            end=window_end,
            sample_period_seconds=sample_period_seconds,
            point_code=point_code,
            energy_type=energy_type,
            unit=unit,
            readings=readings,
            tariffs=tariffs,
            workloads=workloads,
        )

    return VerificationInput(
        baseline=window(baseline_start, baseline_end),
        report=window(report_start, report_end),
        formula_version=HISTORY_VERIFICATION_VERSION,
    )


def _priority_result(factors: tuple[int, int, int, int, int]) -> tuple[Decimal, str]:
    energy_scale, cost_impact, duration, difficulty, safety = map(Decimal, factors)
    score = (
        energy_scale * Decimal("0.25")
        + cost_impact * Decimal("0.25")
        + duration * Decimal("0.20")
        + (Decimal(100) - difficulty) * Decimal("0.10")
        + safety * Decimal("0.20")
    ).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
    if score >= Decimal("75"):
        band = "high"
    elif score >= Decimal("50"):
        band = "medium"
    else:
        band = "low"
    return score, band


def _json_value(value):
    if isinstance(value, Decimal):
        return float(value)
    if isinstance(value, datetime):
        return value.isoformat(timespec="seconds")
    if isinstance(value, date):
        return value.isoformat()
    if isinstance(value, dict):
        return {key: _json_value(item) for key, item in value.items()}
    if isinstance(value, (set, frozenset)):
        return [_json_value(item) for item in sorted(value, key=str)]
    if isinstance(value, (list, tuple)):
        return [_json_value(item) for item in value]
    return value


def _json_dump(payload: dict | list) -> str:
    return json.dumps(_json_value(payload), ensure_ascii=False, sort_keys=True)


def _verification_payloads(
    verification_input: VerificationInput,
    result: VerificationResult,
) -> tuple[dict, dict, dict, dict, str]:
    baseline = result.baseline
    report = result.report
    daily_series = calculate_daily_metric_series(verification_input)
    usage = {
        "baseline": {
            "validUsage": baseline.valid_usage,
            "validHours": baseline.valid_hours,
            "usageRate": baseline.usage_rate,
            "unit": verification_input.baseline.unit,
        },
        "report": {
            "validUsage": report.valid_usage,
            "validHours": report.valid_hours,
            "usageRate": report.usage_rate,
            "unit": verification_input.report.unit,
        },
        "savingPct": result.usage_saving_pct,
        "series": {
            "baseline": [point.usage_rate for point in daily_series.baseline],
            "report": [point.usage_rate for point in daily_series.report],
        },
    }
    cost = {
        "baseline": {
            "validCost": baseline.valid_cost,
            "costRate": baseline.cost_rate,
            "tariffVersions": baseline.tariff_versions,
            "currency": "CNY",
        },
        "report": {
            "validCost": report.valid_cost,
            "costRate": report.cost_rate,
            "tariffVersions": report.tariff_versions,
            "currency": "CNY",
        },
        "savingPct": result.cost_saving_pct,
        "series": {
            "baseline": [point.cost_rate for point in daily_series.baseline],
            "report": [point.cost_rate for point in daily_series.report],
        },
    }
    workload = {
        "baseline": {
            "workload": baseline.workload,
            "usageIntensity": baseline.usage_intensity,
            "unit": "吨",
        },
        "report": {
            "workload": report.workload,
            "usageIntensity": report.usage_intensity,
            "unit": "吨",
        },
        "savingPct": result.intensity_saving_pct,
        "series": {
            "baseline": [point.workload for point in daily_series.baseline],
            "report": [point.workload for point in daily_series.report],
        },
    }
    quality = {
        "baseline": {
            "validCount": baseline.valid_count,
            "theoreticalCount": baseline.theoretical_count,
            "coveragePct": baseline.coverage_pct,
        },
        "report": {
            "validCount": report.valid_count,
            "theoreticalCount": report.theoretical_count,
            "coveragePct": report.coverage_pct,
        },
        "validQualityCodes": sorted(VALID_QUALITY_CODES),
        "series": {
            "baseline": [point.coverage_pct for point in daily_series.baseline],
            "report": [point.coverage_pct for point in daily_series.report],
        },
    }
    signature_payload = {
        "formulaVersion": result.formula_version,
        "pointCode": verification_input.baseline.point_code,
        "energyType": verification_input.baseline.energy_type,
        "baselineWindow": {
            "start": verification_input.baseline.start,
            "end": verification_input.baseline.end,
        },
        "reportWindow": {
            "start": verification_input.report.start,
            "end": verification_input.report.end,
        },
        "validQualityCodes": sorted(VALID_QUALITY_CODES),
        "tariffVersions": sorted(
            set(baseline.tariff_versions) | set(report.tariff_versions)
        ),
        "usageComparison": usage,
        "costComparison": cost,
        "workloadComparison": workload,
        "qualityComparison": quality,
        "savingValue": result.saving_value,
        "savingUnit": result.saving_unit,
    }
    signature = build_calculation_signature(signature_payload)
    labels = [
        f"第{index + 1}日"
        for index in range(max(len(daily_series.baseline), len(daily_series.report)))
    ]
    for comparison in (usage, cost, workload, quality):
        comparison["series"]["labels"] = labels
    return usage, cost, workload, quality, signature


def _calculate_history_cases(conn) -> dict[str, tuple[VerificationInput, VerificationResult]]:
    calculated: dict[str, tuple[VerificationInput, VerificationResult]] = {}
    history_by_code = {item["code"]: item for item in HISTORY_SUGGESTIONS}
    for code, case in HISTORY_VERIFICATION_CASES.items():
        history = history_by_code[code]
        if case["generated_at"] > DEMO_NOW:
            raise RuntimeError(f"{code} generated_at exceeds DEMO_NOW")
        verification_input = load_verification_input(
            conn,
            point_code=case["point_code"],
            area_code=history["area"],
            baseline_start=case["baseline_start"],
            baseline_end=case["baseline_end"],
            report_start=case["report_start"],
            report_end=case["report_end"],
        )
        result = calculate_history_verification(verification_input)
        actual = (
            result.usage_saving_pct,
            result.cost_saving_pct,
            result.intensity_saving_pct,
        )
        if result.status != case["status"] or any(
            value is None or abs(value - expected) > Decimal("0.01")
            for value, expected in zip(actual, case["anchors"], strict=True)
        ):
            raise RuntimeError(
                f"{code} verification self-check failed: status={result.status}, "
                f"values={actual}, expected={case['status']}/{case['anchors']}; "
                "update knowledge-base object/window before changing fixtures"
            )
        calculated[code] = (verification_input, result)
    return calculated


def _suggestion_insert_values(
    history: dict,
    ids: dict,
    calculated: dict[str, tuple[VerificationInput, VerificationResult]],
) -> tuple:
    factors = history["factors"]
    score, band = _priority_result(factors)
    priority_payload = {
        "energyScale": factors[0],
        "costImpact": factors[1],
        "duration": factors[2],
        "implementationDifficulty": factors[3],
        "safetyImpact": factors[4],
        "weights": {
            "energyScale": 0.25,
            "costImpact": 0.25,
            "duration": 0.20,
            "implementationDifficulty": 0.10,
            "safetyImpact": 0.20,
        },
        "basis": "知识库 §8.4 历史人工建议固定构造值",
    }
    template_snapshot = {
        "sourceType": "manual",
        "sourceLabel": "人工创建",
        "templateCode": None,
        "templateName": f"{history['title']}（人工冻结快照）",
        "category": "manual_energy_review",
        "sourceRuleCode": None,
        "applicableObjectType": "equipment",
        "actionContent": history["measure"],
        "requiredData": "人工巡检记录、固定构造计量数据、mock 工单和质量标记",
        "estimatedSaving": "不预置固定值；如有验证快照则以共享公式真算结果为准。",
        "costImpact": "仅展示构造单价与有效读数计算的演示成本影响。",
        "reliabilityImpact": "以人工巡检留痕支持后续复盘。",
        "verificationMethod": "比较固定基线/报告窗口的用量、成本、作业量与质量覆盖。",
        "defaultImplementationDifficulty": factors[3],
        "defaultSafetyImpact": factors[4],
        "enabled": False,
        "version": 1,
    }
    verification_case = HISTORY_VERIFICATION_CASES.get(history["code"])
    verification_result = calculated.get(history["code"])
    repair_at = verification_case["repair_at"] if verification_case else None
    baseline_start = verification_case["baseline_start"] if verification_case else None
    baseline_end = verification_case["baseline_end"] if verification_case else None
    report_start = verification_case["report_start"] if verification_case else None
    report_end = verification_case["report_end"] if verification_case else None
    saving_value = verification_result[1].saving_value if verification_result else None
    saving_unit = verification_result[1].saving_unit if verification_result else None

    attachments = None
    effect_summary = None
    close_type = None
    close_reason = None
    invalid_category = None
    deferred_from_status = None
    defer_reason = None
    defer_until = None
    closed_by = None
    closed_at = None
    if history["code"] in {"H5", "H6"}:
        attachments = [
            {
                "name": f"demo-placeholder-{history['code'].lower()}-inspection.jpg",
                "url": f"/demo/placeholders/{history['code'].lower()}-inspection.jpg",
                "size": 24576,
                "type": "image/jpeg",
                "description": "现场巡检占位附件（无真实数据）",
            }
        ]
        close_type = "implemented"
        effect_summary = (
            "共享公式验证达到预期，保留实施完成证据。"
            if history["code"] == "H5"
            else "共享公式验证未达到预期，保留无效结论供复盘。"
        )
        closed_by = "energy_mgr"
        closed_at = history["times"][-1]
    elif history["code"] == "H7":
        close_type = "archived_invalid"
        close_reason = "与既有 PN-B1 人工建议重复，归档无效。"
        invalid_category = "duplicate"
        closed_by = "energy_mgr"
        closed_at = history["times"][-1]
    elif history["code"] == "H8":
        deferred_from_status = "dispatched"
        defer_reason = "维护窗口与作业排期冲突"
        defer_until = date(2026, 7, 20)

    return (
        "manual",
        None,
        None,
        None,
        None,
        None,
        _json_dump(template_snapshot),
        "历史人工建议复盘占位数据",
        None,
        history["title"],
        history["measure"],
        history["responsible_user"],
        "ops" if history["responsible_user"] else None,
        repair_at.date() if repair_at else None,
        report_end.date() if report_end else None,
        ids["area"][history["area"]],
        ids["equipment"][history["equipment"]],
        "equipment",
        ids["equipment"][history["equipment"]],
        history["status"],
        score,
        band,
        "PRIORITY-V1",
        _json_dump(priority_payload),
        repair_at,
        baseline_start,
        baseline_end,
        report_start,
        report_end,
        saving_value,
        saving_unit,
        close_type,
        close_reason,
        None,
        invalid_category,
        deferred_from_status,
        defer_reason,
        defer_until,
        effect_summary,
        _json_dump(attachments) if attachments else None,
        "energy_mgr",
        closed_by,
        closed_at,
        1,
        history["times"][0],
        history["times"][-1],
    )


def _flow_payload(
    history: dict,
    transition_index: int,
    verification_id: int | None,
    attachments_json: str | None,
) -> tuple[str, str, dict]:
    to_status = history["path"][transition_index + 1]
    if transition_index == 0:
        return "energy_mgr", "energy_mgr", {
            "sourceType": "manual",
            "sourceLabel": "人工创建",
        }
    if to_status == "dispatched":
        return "energy_mgr", "energy_mgr", {
            "responsibleUser": history["responsible_user"]
        }
    if to_status == "executing":
        return "ops_user", "ops", {
            "activity": {
                "type": "manual_inspection",
                "content": f"{history['title']}：已补充人工巡检记录。",
                "recordedBy": "ops_user",
                "attachments": [],
            }
        }
    if to_status == "verifying":
        return "energy_mgr", "energy_mgr", {
            "verificationId": verification_id,
            "formulaVersion": HISTORY_VERIFICATION_VERSION,
        }
    if to_status in {"valid_closed", "invalid_closed"}:
        if history["code"] == "H7":
            return "energy_mgr", "energy_mgr", {
                "closeType": "archived_invalid",
                "invalidCategory": "duplicate",
                "closeReason": "与既有 PN-B1 人工建议重复，归档无效。",
            }
        return "energy_mgr", "energy_mgr", {
            "closeType": "implemented",
            "verificationId": verification_id,
            "attachments": json.loads(attachments_json or "[]"),
        }
    if to_status == "deferred":
        return "energy_mgr", "energy_mgr", {
            "deferredFromStatus": "dispatched",
            "deferReason": "维护窗口与作业排期冲突",
            "deferUntil": "2026-07-20",
        }
    raise ValueError(f"unsupported fixture transition to {to_status}")


def seed_historical_suggestions(conn, ids: dict) -> dict[str, int]:
    """从 raw/tariff/work-order/quality 真算并恢复 H1~H8 与 25/3 证据。"""
    print("[4e/5] seed Act 4 historical manual suggestions")
    calculated = _calculate_history_cases(conn)
    suggestion_ids: dict[str, int] = {}
    verification_ids: dict[str, int] = {}
    suggestion_values: dict[str, tuple] = {}
    flow_count = 0
    history_by_code = {item["code"]: item for item in HISTORY_SUGGESTIONS}
    try:
        with conn.cursor() as cur:
            insert_suggestion = (
                "INSERT INTO e_suggestion (source_type, source_alert_id, source_fingerprint, "
                "source_snapshot_json, template_id, template_version, template_snapshot_json, "
                "trigger_basis, rule_code, title, measure_content, responsible_user, "
                "responsible_role, verify_start, verify_end, area_id, equipment_id, object_type, "
                "object_id, status, priority_score, priority_band, priority_formula_version, "
                "priority_factors_json, repair_at, baseline_start, baseline_end, report_start, "
                "report_end, saving_value, saving_unit, close_type, close_reason, rejection_reason, "
                "invalid_category, deferred_from_status, defer_reason, defer_until, effect_summary, "
                "attachments_json, created_by, closed_by, closed_at, row_version, create_time, "
                "update_time) VALUES ("
                + ",".join(["%s"] * 46)
                + ")"
            )
            for history in HISTORY_SUGGESTIONS:
                values = _suggestion_insert_values(history, ids, calculated)
                cur.execute(
                    insert_suggestion,
                    values,
                )
                suggestion_ids[history["code"]] = cur.lastrowid
                suggestion_values[history["code"]] = values

            for code, (verification_input, result) in calculated.items():
                case = HISTORY_VERIFICATION_CASES[code]
                usage, cost, workload, quality, signature = _verification_payloads(
                    verification_input, result
                )
                cur.execute(
                    "INSERT INTO e_suggestion_verification (suggestion_id, version, status, "
                    "repair_at, baseline_start, baseline_end, report_start, report_end, "
                    "usage_comparison_json, cost_comparison_json, workload_comparison_json, "
                    "quality_comparison_json, saving_value, saving_unit, saving_pct, "
                    "calculation_note, formula_version, signature, generated_by, generated_at) "
                    "VALUES (%s,1,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
                    (
                        suggestion_ids[code],
                        result.status,
                        case["repair_at"],
                        case["baseline_start"],
                        case["baseline_end"],
                        case["report_start"],
                        case["report_end"],
                        _json_dump(usage),
                        _json_dump(cost),
                        _json_dump(workload),
                        _json_dump(quality),
                        result.saving_value,
                        result.saving_unit,
                        result.usage_saving_pct,
                        "用量/有效小时、成本/有效小时及用量/区域已完成工单吨数；Decimal ROUND_HALF_UP 两位。",
                        result.formula_version,
                        signature,
                        "energy_mgr",
                        case["generated_at"],
                    ),
                )
                verification_ids[code] = cur.lastrowid

            action_by_status = {
                "pending": "create",
                "dispatched": "dispatch",
                "executing": "start_execution",
                "verifying": "start_verification",
                "valid_closed": "close",
                "invalid_closed": "close",
                "deferred": "defer",
            }
            for code in (item["code"] for item in HISTORY_SUGGESTIONS):
                history = history_by_code[code]
                for index, occur_time in enumerate(history["times"]):
                    from_status = history["path"][index]
                    to_status = history["path"][index + 1]
                    operator, role, payload = _flow_payload(
                        history,
                        index,
                        verification_ids.get(code),
                        suggestion_values[code][39],
                    )
                    cur.execute(
                        "INSERT INTO e_suggestion_flow_log (suggestion_id, from_status, "
                        "to_status, operator, operator_role, action, remark, "
                        "payload_snapshot_json, occur_time) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s)",
                        (
                            suggestion_ids[code],
                            from_status,
                            to_status,
                            operator,
                            role,
                            action_by_status[to_status],
                            f"{code} 固定历史流转证据",
                            _json_dump(payload),
                            occur_time,
                        ),
                    )
                    flow_count += 1

            if (
                len(suggestion_ids) != 8
                or len(verification_ids) != 3
                or flow_count != 25
            ):
                raise RuntimeError("Act 4 fixture count self-check failed")
        conn.commit()
    except Exception:
        conn.rollback()
        raise

    print(
        "      1 default template · 8 manual suggestions · 25 flows · "
        "3 calculated verification snapshots inserted."
    )
    return suggestion_ids


# ----------------------------------------------------------------------
# 阶段 4：作业工单（构造规范 §3/§5；任务 #17 改为"覆盖驱动"）
#
# 规则：每台主设备（loading_main / transfer / conveyor）× 每个作业窗（08–11 / 14–17）
#      都必须有一张覆盖工单，唯一例外是 INJ-03（GC-A1 2026-07-08 下午）。
# 目的：backend R05 "高耗且作业未增" 只在 INJ-03 单窗口触发，其它高负荷时段有工单佐证。
# 隔离：独立 wo_rng（seed+1），不消耗主 rng，工单结构调整不扰动 raw/status/INJ 命中。
# ----------------------------------------------------------------------

def generate_work_orders(conn, ids: dict, _rng: random.Random) -> int:
    print("[4a/5] generate mock work orders (coverage-driven)")
    wo_rng = random.Random(RANDOM_SEED + 1)
    now = datetime.now().replace(microsecond=0)
    rows: list[tuple] = []
    counter = 1
    inj03_skipped = 0

    def _emit(area_code: str, eq_code: str, start_dt: datetime, end_dt: datetime,
              workload: float, status: str, remark: str) -> None:
        nonlocal counter
        order_no = f"WO-{start_dt.strftime('%Y%m%d')}-{counter:04d}"
        counter += 1
        cargo = "钢材" if area_code == "AREA-A" else "粉煤灰"
        rows.append((
            order_no, ids["area"][area_code], ids["equipment"][eq_code], cargo,
            workload, "吨",
            start_dt, end_dt if status != "in_progress" else None, status,
            remark, now,
        ))

    for day_offset in range(TOTAL_DAYS):
        d = DEMO_START + timedelta(days=day_offset)
        weekday = d.weekday()  # 0=Mon .. 6=Sun
        if weekday == 6:  # 周日不作业
            continue
        # 周六仅上午作业窗
        # 注：A 区周六 = 4 台主设备 × 1 窗 = 4 单/日，规范建议值 2-3；
        # +1 覆盖第 4 台主设备，防 R05 同类均值口径周末误报（2026-07-13 主控裁决）
        windows_today = WORK_WINDOWS[:1] if weekday == 5 else WORK_WINDOWS

        # ---- 1) 每台主设备 × 每个作业窗生成一张覆盖工单 ----
        for area in AREAS:
            for eq_code in MAIN_EQUIPMENT_BY_AREA[area["code"]]:
                for hstart, hend in windows_today:
                    # INJ-03：GC-A1 2026-07-08 下午（14–17 与 INJ-03 13–17 有重叠）刻意不排
                    if (eq_code == INJ_03["equipment_code"] and d == INJ_03["date"]
                            and hstart == 14):
                        inj03_skipped += 1
                        continue
                    start_min = wo_rng.choice([0, 15, 30])
                    duration_min = (hend - hstart) * 60 + wo_rng.choice([-15, 0, 15])
                    start_dt = datetime.combine(d, datetime.min.time()).replace(
                        hour=hstart, minute=start_min)
                    end_dt = start_dt + timedelta(minutes=duration_min)
                    workload_range = (80, 220) if area["code"] == "AREA-A" else (120, 260)
                    workload = round(wo_rng.uniform(*workload_range), 2)
                    # 覆盖工单不生成 cancelled（cancelled workload 会被 R05 忽略，等同无工单）
                    status = "completed" if wo_rng.random() > 0.05 else "in_progress"
                    _emit(area["code"], eq_code, start_dt, end_dt, workload, status,
                          f"REQ-032 mock 覆盖工单｜设备={eq_code}｜窗口={hstart:02d}-{hend:02d}")

        # ---- 2) B 区补 padding：PN-B1 是 B 区唯一主设备，工作日仅 2 单/日 < 规范 4-8/区
        #        用 PN-B1 的额外短工单把 B 区拉到 4-5 单/日（周六 1-2 单，仍在 2-3 范围附近）
        if weekday <= 4:
            padding = wo_rng.randint(2, 3)
        else:
            padding = wo_rng.randint(1, 2)
        for _ in range(padding):
            hstart, hend = wo_rng.choice(windows_today)
            start_hour = wo_rng.randint(hstart, hend - 1)
            start_min = wo_rng.choice([0, 15, 30, 45])
            duration_min = wo_rng.choice([60, 90, 120])
            start_dt = datetime.combine(d, datetime.min.time()).replace(
                hour=start_hour, minute=start_min)
            end_dt = start_dt + timedelta(minutes=duration_min)
            workload = round(wo_rng.uniform(80, 180), 2)
            # padding 允许 cancelled 存在，供归因页展示多态过滤
            status = "completed" if wo_rng.random() > 0.08 else wo_rng.choice(
                ["in_progress", "cancelled"])
            _emit("AREA-B", "PN-B1", start_dt, end_dt, workload, status,
                  "REQ-032 mock 补充工单｜设备=PN-B1")

    with conn.cursor() as cur:
        cur.executemany(
            "INSERT INTO e_work_order (order_no, area_id, equipment_id, cargo_type, workload_value, workload_unit, "
            "start_time, end_time, status, remark, create_time) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
            rows,
        )
    conn.commit()
    print(f"      {len(rows)} work orders inserted "
          f"(INJ-03 skipped {inj03_skipped} 处：应为 1 = GC-A1 2026-07-08 下午).")
    return len(rows)


# ----------------------------------------------------------------------
# 阶段 4b：设备运行状态日志（事件驱动 + 小时心跳）
# ----------------------------------------------------------------------

def is_working_hour(dt: datetime, weekday_bias: bool = True) -> bool:
    """粗略判定作业时段：工作日 08–11 或 14–17；周六 08–11；周日不作业。"""
    weekday = dt.weekday()
    if weekday == 6:
        return False
    hour = dt.hour
    if weekday == 5:  # 周六只上午
        return 8 <= hour < 11
    return (8 <= hour < 11) or (14 <= hour < 17)


def generate_equipment_status(conn, ids: dict, rng: random.Random) -> int:
    print("[4b/5] generate equipment status log")
    rows: list[tuple] = []

    for eq in EQUIPMENTS:
        eq_id = ids["equipment"][eq["code"]]
        # 简化：每小时一条 heartbeat + 每次状态切换一条 event
        prev_state = "stopped"
        for day_offset in range(TOTAL_DAYS):
            d = DEMO_START + timedelta(days=day_offset)
            for hour in range(24):
                ts = datetime.combine(d, datetime.min.time()).replace(hour=hour, minute=rng.randint(0, 5))
                working = is_working_hour(ts)
                # 照明设备走反向：非工作时段基础照明，工作时段较低
                if eq["type"] == "lighting":
                    new_state = "running" if (hour >= 22 or hour < 6 or working) else "standby"
                elif eq["type"] in ("aux", "compressor"):
                    new_state = "running" if working else ("standby" if rng.random() < 0.6 else "stopped")
                else:
                    new_state = "running" if working else ("standby" if hour in (6, 7, 11, 12, 13, 17, 18) else "stopped")
                # INJ-03 GC-A1 07-08 13:00–17:00 强制 standby
                if (eq["code"] == INJ_03["equipment_code"] and d == INJ_03["date"]
                        and INJ_03["start_hm"][0] <= hour < INJ_03["end_hm"][0]):
                    new_state = "standby"
                # heartbeat
                rows.append((eq_id, ts, new_state, "heartbeat", "", ))
                if new_state != prev_state:
                    rows.append((eq_id, ts, new_state, "event", f"{prev_state}→{new_state}", ))
                    prev_state = new_state

    with conn.cursor() as cur:
        cur.executemany(
            "INSERT INTO e_equipment_status_log (equipment_id, event_time, status_value, event_type, remark) "
            "VALUES (%s,%s,%s,%s,%s)",
            rows,
        )
    conn.commit()
    print(f"      {len(rows)} status log rows inserted.")
    return len(rows)


# ----------------------------------------------------------------------
# 阶段 4c：原始时序读数（核心 —— 承载 INJ-01/02/04/05/06/07）
# ----------------------------------------------------------------------

# ---- 曲线形态基函数 ----

def load_factor_for_equipment(dt: datetime, eq: dict, rng: random.Random) -> float:
    """返回 0–1 之间的负载系数：工作日双峰 + 夜间基础负荷 + 周末低负荷。"""
    hour = dt.hour + dt.minute / 60.0
    weekday = dt.weekday()

    if eq["type"] == "lighting":
        # 照明：夜间待机（应急照明）；白天低（自然光）；18–22 收工照明较高
        # 任务 #17：夜间 22–06 因子降到 0.05–0.10，避开 R08 阈值（>2kW/回路）
        # LT-A1 12kW × 0.10 = 1.2kW，LT-B1 8kW × 0.10 = 0.8kW，均低于 2kW
        # INJ-05 在 _apply_inj_05 中把 LT-A1 07-03/07 夜间强制 0.65 = 7.8kW，覆盖回来触发 R08
        if 22 <= hour or hour < 6:
            base = rng.uniform(0.05, 0.10)     # 应急待机照明（<2kW）
        elif 6 <= hour < 18:
            base = rng.uniform(0.10, 0.18)     # 白天低（自然光）
        else:  # 18–22 收工时段
            base = rng.uniform(0.30, 0.45)
        return base

    if weekday == 6:  # 周日
        return rng.uniform(0.08, 0.13)
    if weekday == 5:  # 周六上午
        if 8 <= hour < 11.5:
            return rng.uniform(0.45, 0.65)
        return rng.uniform(0.08, 0.15)

    # 工作日双峰
    if 8 <= hour < 11.5:
        return rng.uniform(0.60, 0.85)
    if 14 <= hour < 17.5:
        return rng.uniform(0.60, 0.85)
    if 11.5 <= hour < 14:
        return rng.uniform(0.25, 0.35)  # 午休
    if 22 <= hour or hour < 6:
        return rng.uniform(0.08, 0.15)  # 夜间基础
    return rng.uniform(0.18, 0.30)      # 工前/工后


def tou_for(hour: int) -> str:
    if hour in TOU_PEAK_HOURS:
        return "peak"
    if hour in TOU_VALLEY_HOURS:
        return "valley"
    return "flat"


def _apply_inj_04(dt: datetime, base_factor: float) -> tuple[float, bool]:
    """B 区管网干管流量：非作业时段抬升。返回 (调整后系数, 是否触发)"""
    if dt.date() < INJ_04["start_date"] or dt.date() >= INJ_04["repair_date"]:
        return base_factor, False
    if is_working_hour(dt):
        return base_factor, False
    # 非作业时段：从 baseline_pct 5% 抬到 abnormal_pct 38%
    return INJ_04["abnormal_pct"] + random.gauss(0, 0.02), True


def _apply_inj_05(dt: datetime, factor: float) -> tuple[float, bool]:
    """LT-A1 夜间照明未关。"""
    if dt.date() not in INJ_05["dates"]:
        return factor, False
    hour = dt.hour
    if hour in INJ_05["night_hours"] or hour in INJ_05["morning_hours"]:
        return INJ_05["utilization"], True
    return factor, False


def _apply_inj_06(dt: datetime, area_code: str, factor: float) -> float:
    """B 区 6 月峰段用电占比抬升 ~12 个百分点。做法：峰段 ×1.50、平谷段 ×0.90。

    数学：baseline 峰段占比约 35%，applying ratios → 35×1.5 / (35×1.5 + 65×0.9) ≈ 47.3%，
    比 5 月同区 35.1% 高约 +12.2pp，明显超过 R10 阈值 8pp。
    """
    if area_code != INJ_06["area_code"]:
        return factor
    if dt.year != INJ_06["month_year"] or dt.month != INJ_06["month"]:
        return factor
    period = tou_for(dt.hour)
    if period == "peak":
        return factor * 1.50
    return factor * 0.90


def _apply_inj_03(dt: datetime, eq_code: str, factor: float) -> tuple[float, bool]:
    """GC-A1 07-08 13:00–17:00 保持额定 55%（待机高耗）。"""
    if eq_code != INJ_03["equipment_code"] or dt.date() != INJ_03["date"]:
        return factor, False
    if INJ_03["start_hm"][0] <= dt.hour < INJ_03["end_hm"][0]:
        return INJ_03["utilization"], True
    return factor, False


def generate_raw_readings(conn, ids: dict, rng: random.Random) -> tuple[int, dict]:
    """生成 10 周原始读数，返回 (行数, INJ 摘要)

    对账保证：area_meter 在 Phase 2 直接由 Phase 1 生成的 device_meter 实际增量求和 × 固定线损，
    保证"区总表 ≈ Σ设备表 + 2–4% 线损"（构造规范 §7 自检项）。
    """
    print("[4c/5] generate raw readings (10 weeks × 48 points) - this takes ~30s")
    inj_stats: dict[str, int] = {f"INJ-0{i}": 0 for i in [1, 2, 3, 4, 5, 6, 7, 9]}

    equip_by_code = {eq["code"]: eq for eq in EQUIPMENTS}
    points = build_meter_points()  # 与 seed_master_data 顺序一致
    point_ids = ids["point"]

    # Phase 1 生成时按 (equipment_code, ts) 记录实际 kWh 增量，供 Phase 2 区总表求和
    device_load: dict[tuple[str, datetime], float] = {}

    # 固定线损 2.8%（介于 2%–4%），保证跨天对账偏差稳定 ≤4%
    LINE_LOSS_A = 1.028
    LINE_LOSS_B = 1.032

    total_rows = 0
    batch: list[tuple] = []
    BATCH = 5000

    def flush():
        nonlocal batch, total_rows
        if not batch:
            return
        with conn.cursor() as cur:
            cur.executemany(
                "INSERT INTO e_raw_reading (point_id, sample_time, cumulative_value, incremental_value, "
                "status_value, unit, quality_state, source_batch, ingest_time, is_backfill, is_estimated) "
                "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
                batch,
            )
        conn.commit()
        total_rows += len(batch)
        batch = []

    # Phase 1：设备电表 / 支路 / 水 / 气流 / 状态 / 环境
    for p in points:
        if p["category"] == "area_meter":
            continue  # Phase 2 单独处理，保证对账
        pid = point_ids[p["code"]]
        eq_code = p["equipment"]
        eq = equip_by_code.get(eq_code)
        area_code = p["area"]
        sample_sec = CATEGORY_SAMPLE_SEC[p["category"]]
        step = timedelta(seconds=sample_sec)
        cumulative = 0.0

        t = datetime.combine(DEMO_START, datetime.min.time())
        t_end = datetime.combine(DEMO_END + timedelta(days=1), datetime.min.time())
        while t < t_end:
            # 每天先决定当日 WP-A1 是否要触发 INJ-07（drop 30%）
            batch_id: str | None = None
            ingest_time = t + timedelta(seconds=rng.randint(0, sample_sec // 3))
            is_backfill = 0
            quality = "ok"
            cum_val: float | None = None
            inc_val: float | None = None
            status_val: str | None = None

            # ---------- 计算基础增量 ----------
            if p["category"] == "status":
                # 状态点：不累计，只写 status_value；与 e_equipment_status_log 保持一致粗略
                if eq is None:
                    status_val = "stopped"
                else:
                    working = is_working_hour(t)
                    if eq["type"] == "lighting":
                        status_val = "running" if (t.hour >= 22 or t.hour < 6 or working) else "standby"
                    elif eq["type"] in ("aux", "compressor"):
                        status_val = "running" if working else ("standby" if rng.random() < 0.6 else "stopped")
                    else:
                        status_val = "running" if working else ("standby" if t.hour in (6, 7, 11, 12, 13, 17, 18) else "stopped")
                    # INJ-03 强制 standby
                    if (eq_code == INJ_03["equipment_code"] and t.date() == INJ_03["date"]
                            and INJ_03["start_hm"][0] <= t.hour < INJ_03["end_hm"][0]):
                        status_val = "standby"
                inc_val = None
                cum_val = None

            elif p["category"] == "env":
                # 环境点：温度/气压/光照
                base = {"℃": 26.0, "kPa": 101.3, "lx": 400.0}.get(p["unit"], 0.0)
                if p["unit"] == "lx":
                    val = base * (1.6 if 6 <= t.hour < 18 else 0.05) * (1 + random.gauss(0, 0.08))
                else:
                    val = base + random.gauss(0, 1.0 if p["unit"] == "℃" else 0.3)
                cum_val = round(val, 2)
                inc_val = cum_val
                # 环境点不做异常注入

            elif p["category"] == "air_flow":
                # 压缩空气流量 (m3/5min)。任务 #17：夜间 22–06 对非 INJ-04 载体强制低基线
                # 避免 R06 副命中；只有 AF-B-MAIN 通过 _apply_inj_04 在 07-02–07-09 抬升。
                is_night = t.hour >= 22 or t.hour < 6
                if eq_code in ("AC-B1", "AC-B2"):
                    # AC 出口流量：白天随负载，夜间强制 3%–5% 待机流量
                    if is_night:
                        factor = rng.uniform(0.03, 0.05)
                    else:
                        factor = load_factor_for_equipment(t, equip_by_code[eq_code], rng)
                    base_flow = factor * 12.0  # 5-min m3
                    factor_adj, hit = _apply_inj_03(t, eq_code, factor)
                    if hit:
                        base_flow = factor_adj * 12.0
                    inc = max(0.0, base_flow * (1 + random.gauss(0, 0.05)))
                elif p["code"] == "AF-PN-B1-IN":
                    # PN-B1 入口流量：白天随负载，夜间强制 3%–5%
                    if is_night:
                        factor = rng.uniform(0.03, 0.05)
                    else:
                        factor = load_factor_for_equipment(t, equip_by_code["PN-B1"], rng)
                    inc = max(0.0, factor * 22.0 * (1 + random.gauss(0, 0.05)))
                elif p["code"] == "AF-B-MAIN":
                    # 干管：产气 - 消耗 - 泄漏（INJ-04 唯一注入落点）
                    # 夜间基线降噪：AC 双机在非工作时段仅有微弱待机产气，突出 INJ-04 泄漏对比
                    if is_night:
                        factor_ac1 = rng.uniform(0.03, 0.05)
                        factor_ac2 = rng.uniform(0.03, 0.05)
                    else:
                        factor_ac1 = load_factor_for_equipment(t, equip_by_code["AC-B1"], rng)
                        factor_ac2 = load_factor_for_equipment(t, equip_by_code["AC-B2"], rng)
                    production = (factor_ac1 + factor_ac2) * 12.0
                    factor_air = production / 24.0
                    factor_air, hit4 = _apply_inj_04(t, factor_air)
                    if hit4:
                        inj_stats["INJ-04"] += 1
                    inc = max(0.0, factor_air * 24.0 * (1 + random.gauss(0, 0.04)))
                elif p["code"] == "AF-A-BRANCH":
                    if is_night:
                        factor = rng.uniform(0.03, 0.05) * 0.4
                    else:
                        factor = load_factor_for_equipment(t, equip_by_code["GC-A1"], rng) * 0.4
                    inc = max(0.0, factor * 8.0 * (1 + random.gauss(0, 0.05)))
                elif p["code"] == "AF-B-TERMINAL":
                    if is_night:
                        factor = rng.uniform(0.03, 0.05) * 0.8
                    else:
                        factor = load_factor_for_equipment(t, equip_by_code["PN-B1"], rng) * 0.8
                    inc = max(0.0, factor * 18.0 * (1 + random.gauss(0, 0.05)))
                else:
                    inc = 0.0
                cumulative += inc
                cum_val = round(cumulative, 4)
                inc_val = round(inc, 4)

            elif p["category"] == "water":
                # 水表（区总/生活/WP-A1）
                if p["code"] == "WP-A1-W":
                    working = is_working_hour(t)
                    inc = (rng.uniform(0.05, 0.25) if working else rng.uniform(0.0, 0.02))
                elif p["code"] == "AREA-A-W-LIFE":
                    inc = rng.uniform(0.02, 0.15) if 6 <= t.hour < 22 else rng.uniform(0.0, 0.02)
                else:  # 区水总表
                    if p["area"] == "AREA-A":
                        working = is_working_hour(t)
                        inc = (rng.uniform(0.25, 0.55) if working else rng.uniform(0.05, 0.15))
                    else:
                        working = is_working_hour(t)
                        inc = (rng.uniform(0.15, 0.35) if working else rng.uniform(0.02, 0.10))
                cumulative += inc
                cum_val = round(cumulative, 4)
                inc_val = round(inc, 4)

            elif p["category"] == "device_meter":
                # 设备电表：功率(kW) × 0.25h = kWh
                factor = load_factor_for_equipment(t, eq, rng)
                factor, hit03 = _apply_inj_03(t, eq_code, factor)
                if hit03:
                    inj_stats["INJ-03"] += 1
                factor, hit05 = _apply_inj_05(t, factor) if eq_code == INJ_05["equipment_code"] else (factor, False)
                if hit05:
                    inj_stats["INJ-05"] += 1
                factor = _apply_inj_06(t, area_code, factor)
                # 6 月峰段占比抬升算 INJ-06 命中
                if (area_code == INJ_06["area_code"] and t.month == INJ_06["month"]
                        and t.year == INJ_06["month_year"] and tou_for(t.hour) == "peak"):
                    inj_stats["INJ-06"] += 1
                power_kw = eq["rated_kw"] * factor * (1 + random.gauss(0, 0.05))
                inc = max(0.0, power_kw * (sample_sec / 3600.0))
                # INJ-02：BC-A1 07-01 10:30 单点 ×100
                if (eq_code == INJ_02["equipment_code"] and t.date() == INJ_02["date"]
                        and t.hour == INJ_02["time_hm"][0] and t.minute == INJ_02["time_hm"][1]):
                    inc *= INJ_02["multiplier"]
                    quality = "jump"
                    inj_stats["INJ-02"] += 1
                # INJ-09：2026-07-09 全厂电力同比例抬升 +25%（device_meter 层落点）
                if t.date() == INJ_09["date"]:
                    inc *= INJ_09["scale"]
                    inj_stats["INJ-09"] += 1
                # 记录 Phase 2 用（含 INJ-01 缺测/补传后的最终值，含 INJ-02 跳变、INJ-09 抬升）
                device_load[(eq_code, t)] = inc
                cumulative += inc
                cum_val = round(cumulative, 4)
                inc_val = round(inc, 4)

            elif p["category"] == "branch_meter":
                # 支路电表：区总的部分
                if area_code == "AREA-A":
                    factor_pool = ["GC-A1", "GC-A2", "FC-A1", "BC-A1", "DF-A1", "LT-A1", "WP-A1"]
                    ratio = rng.uniform(0.10, 0.25)
                else:
                    factor_pool = ["PN-B1", "AC-B1", "AC-B2", "SF-B1", "LT-B1"]
                    ratio = rng.uniform(0.15, 0.35)
                sub_kw = 0.0
                for eqc in factor_pool:
                    f = load_factor_for_equipment(t, equip_by_code[eqc], rng)
                    sub_kw += equip_by_code[eqc]["rated_kw"] * f
                power_kw = sub_kw * ratio * (1 + random.gauss(0, 0.03))
                inc = max(0.0, power_kw * (sample_sec / 3600.0))
                # INJ-09：支路电表同步 +25%（避免与 area_meter/device_meter 出现分歧）
                if t.date() == INJ_09["date"]:
                    inc *= INJ_09["scale"]
                    inj_stats["INJ-09"] += 1
                cumulative += inc
                cum_val = round(cumulative, 4)
                inc_val = round(inc, 4)

            else:
                inc_val = 0.0
                cum_val = 0.0

            # ---------- INJ-01：A 区 4 设备电表 07-06 09:20–13:40 缺测→补传 ----------
            if (eq_code in INJ_01["equipment_codes"] and p["category"] == "device_meter"
                    and t.date() == INJ_01["date"]):
                outage_start = datetime.combine(INJ_01["date"], datetime.min.time()).replace(
                    hour=INJ_01["outage_start_hm"][0], minute=INJ_01["outage_start_hm"][1])
                outage_end = datetime.combine(INJ_01["date"], datetime.min.time()).replace(
                    hour=INJ_01["outage_end_hm"][0], minute=INJ_01["outage_end_hm"][1])
                if outage_start <= t < outage_end:
                    quality = "fix"      # 补传后最终状态
                    is_backfill = 1
                    batch_id = INJ_01["backfill_batch"]
                    ingest_time = datetime.combine(INJ_01["date"], datetime.min.time()).replace(
                        hour=INJ_01["backfill_ingest_hm"][0], minute=INJ_01["backfill_ingest_hm"][1])
                    inj_stats["INJ-01"] += 1

            # ---------- INJ-07：WP-A1 07-05 覆盖率 70% ----------
            if p["code"] in (INJ_07["point_code"], INJ_07["water_point_code"]) and t.date() == INJ_07["date"]:
                # 随机丢 30% 采样点：quality=miss、值 NULL
                if rng.random() > INJ_07["coverage_target"]:
                    quality = "miss"
                    cum_val = None
                    inc_val = None
                    inj_stats["INJ-07"] += 1

            batch.append((
                pid, t, cum_val, inc_val, status_val, p["unit"], quality, batch_id, ingest_time,
                is_backfill, 1 if quality == "est" else 0,
            ))
            if len(batch) >= BATCH:
                flush()
            t += step
    flush()

    # Phase 2：区总表由实际 device_load 求和 × 固定线损构造，保证对账 ≤4%
    for p in points:
        if p["category"] != "area_meter":
            continue
        pid = point_ids[p["code"]]
        area_devices = [eq["code"] for eq in EQUIPMENTS if eq["area"] == p["area"]]
        line_loss = LINE_LOSS_A if p["area"] == "AREA-A" else LINE_LOSS_B
        cumulative = 0.0
        step = timedelta(seconds=CATEGORY_SAMPLE_SEC["area_meter"])
        t = datetime.combine(DEMO_START, datetime.min.time())
        t_end = datetime.combine(DEMO_END + timedelta(days=1), datetime.min.time())
        while t < t_end:
            base_sum = sum(device_load.get((eqc, t), 0.0) for eqc in area_devices)
            inc = base_sum * line_loss  # 固定线损，无额外随机噪声
            cumulative += inc
            ingest_time = t + timedelta(seconds=30)
            batch.append((
                pid, t, round(cumulative, 4), round(inc, 4), None, p["unit"], "ok",
                None, ingest_time, 0, 0,
            ))
            if len(batch) >= BATCH:
                flush()
            t += step
    flush()
    print(f"      {total_rows} raw readings inserted.")
    return total_rows, inj_stats


# ----------------------------------------------------------------------
# 阶段 4d：采集任务批次（INJ-01 关键载体）
# ----------------------------------------------------------------------

def generate_collect_tasks(conn, ids: dict) -> int:
    print("[4d/5] generate collect task records (batch summary + INJ-01 failed/backfilled)")
    now = datetime.now().replace(microsecond=0)
    rows: list[tuple] = []

    # 每小时一个成功批次（跨全部采集点），共 70 天 × 24 = 1680 条
    for day_offset in range(TOTAL_DAYS):
        d = DEMO_START + timedelta(days=day_offset)
        for hour in range(24):
            ts = datetime.combine(d, datetime.min.time()).replace(hour=hour, minute=0)
            batch_no = f"BATCH-{d.strftime('%Y%m%d')}-{hour:02d}"
            rows.append((
                batch_no, None, "ALL", ts, ts + timedelta(seconds=90), "success", 48,
                None, 0, None, None, None, now,
            ))

    # INJ-01：A 区 4 个设备电表 07-06 09:20–13:40 采集失败
    inj01_start = datetime.combine(INJ_01["date"], datetime.min.time()).replace(
        hour=INJ_01["outage_start_hm"][0], minute=INJ_01["outage_start_hm"][1])
    inj01_end = datetime.combine(INJ_01["date"], datetime.min.time()).replace(
        hour=INJ_01["outage_end_hm"][0], minute=INJ_01["outage_end_hm"][1])
    inj01_backfill_ts = datetime.combine(INJ_01["date"], datetime.min.time()).replace(
        hour=INJ_01["backfill_ingest_hm"][0], minute=INJ_01["backfill_ingest_hm"][1])
    failed_batch = "FAIL-20260706-0920"
    scope_codes = ",".join(f"{c}-E" for c in INJ_01["equipment_codes"])
    rows.append((
        failed_batch, ids["area"]["AREA-A"], scope_codes, inj01_start, None, "failed", 4,
        INJ_01["reason"], 3, inj01_start, inj01_end, None, now,
    ))
    # 补传任务
    rows.append((
        INJ_01["backfill_batch"], ids["area"]["AREA-A"], scope_codes, inj01_backfill_ts,
        inj01_backfill_ts + timedelta(seconds=180), "backfilled", 4,
        f"人工触发补传（{INJ_01['reason']}恢复）", 0, inj01_start, inj01_end, None, now,
    ))

    with conn.cursor() as cur:
        cur.executemany(
            "INSERT INTO e_collect_task (batch_no, area_id, point_scope, scheduled_time, actual_ingest_time, "
            "task_status, affected_point_count, failure_reason, retry_count, outage_start, outage_end, "
            "parent_task_id, create_time) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
            rows,
        )
        # 关联 backfilled → failed 的 parent_task_id
        cur.execute(
            "UPDATE e_collect_task SET parent_task_id = "
            "(SELECT task_id FROM (SELECT task_id FROM e_collect_task WHERE batch_no=%s) t) "
            "WHERE batch_no=%s",
            (failed_batch, INJ_01["backfill_batch"]),
        )
    conn.commit()
    print(f"      {len(rows)} collect task rows inserted.")
    return len(rows)


# ----------------------------------------------------------------------
# 阶段 5：报告
# ----------------------------------------------------------------------

def report(conn, inj_stats: dict, elapsed_sec: float) -> None:
    print("\n" + "=" * 70)
    print("B-Demo 构造数据生成报告")
    print("=" * 70)
    with conn.cursor() as cur:
        cur.execute("SELECT COUNT(*) FROM e_area")
        n_area = cur.fetchone()[0]
        cur.execute("SELECT COUNT(*) FROM e_equipment")
        n_eq = cur.fetchone()[0]
        cur.execute("SELECT COUNT(*) FROM e_meter_point")
        n_pt = cur.fetchone()[0]
        cur.execute("SELECT COUNT(*) FROM e_raw_reading")
        n_rr = cur.fetchone()[0]
        cur.execute("SELECT COUNT(*) FROM e_work_order")
        n_wo = cur.fetchone()[0]
        cur.execute("SELECT COUNT(*) FROM e_collect_task")
        n_ct = cur.fetchone()[0]
        cur.execute("SELECT COUNT(*) FROM e_equipment_status_log")
        n_sl = cur.fetchone()[0]
        cur.execute("SELECT COUNT(*) FROM e_alert_rule")
        n_ar = cur.fetchone()[0]
        cur.execute("SELECT COUNT(*) FROM e_tariff_version")
        n_tf = cur.fetchone()[0]
        cur.execute("SELECT user_name, nick_name FROM sys_user WHERE user_id BETWEEN 100 AND 103 OR user_id=1 ORDER BY user_id")
        users = cur.fetchall()
        cur.execute("SELECT COUNT(*) FROM e_raw_reading WHERE is_backfill=1")
        n_bf = cur.fetchone()[0]
        cur.execute("SELECT COUNT(*) FROM e_raw_reading WHERE quality_state='miss'")
        n_miss = cur.fetchone()[0]
        cur.execute("SELECT COUNT(*) FROM e_raw_reading WHERE quality_state='jump'")
        n_jump = cur.fetchone()[0]

    print(f"数据规模：{n_area} 区 · {n_eq} 设备 · {n_pt} 采集点 · {n_rr:,} 原始读数 · {n_wo} 工单 · "
          f"{n_ct} 采集任务 · {n_sl:,} 状态日志")
    print(f"规则库：{n_ar} 条（R01–R11）  单价版本：{n_tf} 条  质量：miss {n_miss} 条 / jump {n_jump} 条 / 补传 {n_bf} 条")
    print()
    print("异常注入命中统计（backend 起动后应真实产出对应告警）：")
    for k in sorted(inj_stats):
        print(f"  {k}: {inj_stats[k]} 条原始读数命中")
    print("  INJ-08 : 由权限拦截真实生成，演示时现场触发 ops_user 访问成本页")
    print()
    print("演示账号（口令统一 admin123，与骨架默认一致）：")
    for u in users:
        print(f"  {u[0]:<15}  {u[1]}")
    print()
    print(f"耗时 {elapsed_sec:.1f}s。数据可复现：种子 seed={RANDOM_SEED}。")
    print("=" * 70)


# ----------------------------------------------------------------------
# 主入口
# ----------------------------------------------------------------------

def main() -> int:
    parser = argparse.ArgumentParser(description="B-Demo 演示数据构造脚本（一次性生成 10 周数据）")
    parser.add_argument("--reset", action="store_true",
                        help="完整重置：先跑 skeleton-init.sql + V001__energy_domain.sql（drop-if-exists 幂等），再种数据")
    args = parser.parse_args()

    random.seed(RANDOM_SEED)
    rng = random.Random(RANDOM_SEED)

    start_ts = time.perf_counter()
    print(f"connecting to MySQL {DB_CONF['host']}:{DB_CONF['port']} db={DB_CONF['database']} ...")
    conn = connect(multi=False)
    try:
        if args.reset:
            reset_schema(conn)
        else:
            print("[1/5] 幂等模式：跳过 schema 重建，只清空业务数据")
            truncate_business_data(conn)

        seed_accounts(conn)
        ids = seed_master_data(conn)
        seed_default_suggestion_template(conn)
        generate_work_orders(conn, ids, rng)
        generate_equipment_status(conn, ids, rng)
        _rows, inj_stats = generate_raw_readings(conn, ids, rng)
        generate_collect_tasks(conn, ids)
        seed_historical_suggestions(conn, ids)

        elapsed = time.perf_counter() - start_ts
        report(conn, inj_stats, elapsed)
    finally:
        conn.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
