"""
e_stat_hour ｜ 小时统计（backend 计算后写入）
DDL: datagen/ddl/V001__energy_domain.sql §9
REQ 锚点：REQ-024~028 多维统计、REQ-060 总览刷新
"""

from sqlalchemy import BigInteger, Column, DateTime, Integer, Numeric, String

from config.database import Base


class EStatHour(Base):
    """小时统计"""

    __tablename__ = 'e_stat_hour'
    __table_args__ = {'comment': '小时统计'}

    id = Column(BigInteger, primary_key=True, nullable=False, autoincrement=True, comment='主键')
    object_type = Column(String(16), nullable=False, comment='统计对象（point/equipment/area）')
    object_id = Column(BigInteger, nullable=False, comment='对象ID')
    energy_type_code = Column(String(32), nullable=False, comment='能源类型')
    stat_time = Column(DateTime, nullable=False, comment='小时时间桶（整点）')
    total_value = Column(Numeric(20, 4), nullable=True, server_default='0', comment='本小时增量')
    avg_power_kw = Column(Numeric(12, 4), nullable=True, comment='平均功率(kW)（电类）')
    coverage_ratio = Column(Numeric(6, 4), nullable=True, comment='覆盖率（0–1）')
    quality_summary = Column(String(255), nullable=True, comment='质量摘要')
    tou_period = Column(String(8), nullable=True, comment='峰平谷（peak/flat/valley）')
    version_no = Column(Integer, nullable=True, server_default='1', comment='版本号（重算+1）')
    computed_at = Column(DateTime, nullable=True, comment='计算时间')
