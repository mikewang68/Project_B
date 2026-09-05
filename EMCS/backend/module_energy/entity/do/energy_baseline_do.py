"""
e_energy_baseline ｜ 能源基线（REQ-029 演示态）
DDL: datagen/ddl/V001__energy_domain.sql §13
数据来源：PRD §7.3 前 8 周分桶均值 ±1σ；报告期偏差 R09
"""

from sqlalchemy import BigInteger, Column, Date, DateTime, String, Text

from config.database import Base


class EEnergyBaseline(Base):
    """能源基线"""

    __tablename__ = 'e_energy_baseline'
    __table_args__ = {'comment': '能源基线'}

    baseline_id = Column(BigInteger, primary_key=True, nullable=False, autoincrement=True, comment='基线ID')
    baseline_code = Column(String(64), nullable=False, unique=True, comment='基线编号')
    object_scope = Column(String(16), nullable=False, comment='范围（area/equipment/system）')
    object_id = Column(BigInteger, nullable=True, comment='对象ID（system 时空）')
    energy_type_code = Column(String(32), nullable=False, comment='能源类型')
    baseline_start = Column(Date, nullable=False, comment='基线期起')
    baseline_end = Column(Date, nullable=False, comment='基线期止')
    method = Column(String(32), nullable=False, comment='方法（bucket / linear）')
    formula_version = Column(String(32), nullable=True, comment='公式版本')
    buckets_json = Column(Text, nullable=True, comment='分桶均值+σ（json）')
    status = Column(String(16), nullable=True, server_default='draft',
                    comment='状态（draft/published/archived）')
    adjustment_reason = Column(String(255), nullable=True, comment='调整原因（附录C）')
    published_at = Column(DateTime, nullable=True, comment='发布时间')
    published_by = Column(String(64), nullable=True, comment='发布人')
    remark = Column(String(255), nullable=True, comment='备注')
    create_time = Column(DateTime, nullable=True, comment='创建时间')
    update_time = Column(DateTime, nullable=True, comment='更新时间')
