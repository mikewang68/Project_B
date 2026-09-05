"""
e_recompute_log ｜ 统计重算日志
DDL: datagen/ddl/V001__energy_domain.sql §12
REQ 锚点：REQ-020 重算标记 + version_no 递增；差异说明供审计与页面 diff 展示

target_key_json 语义：
- {"period_key": "2026-07-06D", "scope": "day", "energy_type": "electricity"}
- {"period_key": "2026-07M",   "scope": "month", ...}
"""

from sqlalchemy import BigInteger, Column, DateTime, Integer, Numeric, String

from config.database import Base


class ERecomputeLog(Base):
    """统计重算日志"""

    __tablename__ = 'e_recompute_log'
    __table_args__ = {'comment': '统计重算日志'}

    id = Column(BigInteger, primary_key=True, nullable=False, autoincrement=True, comment='主键')
    target_table = Column(String(32), nullable=False, comment='重算目标表名')
    target_key_json = Column(String(512), nullable=False, comment='目标定位键（json）')
    old_version_no = Column(Integer, nullable=True, comment='旧版本号')
    new_version_no = Column(Integer, nullable=True, comment='新版本号')
    delta_value = Column(Numeric(20, 4), nullable=True, comment='数值差异')
    delta_pct = Column(Numeric(8, 4), nullable=True, comment='差异百分比')
    trigger_reason = Column(String(255), nullable=True, comment='触发原因（补传/换表/规则调整）')
    operator = Column(String(64), nullable=True, comment='操作人')
    created_at = Column(DateTime, nullable=True, comment='记录时间')
