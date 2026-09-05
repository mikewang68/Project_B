"""
e_stat_day ｜ 日统计
DDL: datagen/ddl/V001__energy_domain.sql §10
REQ 锚点：REQ-024/029（基线偏差）、REQ-020（重算标记）
"""

from sqlalchemy import BigInteger, Column, Date, DateTime, Integer, Numeric, SmallInteger, String

from config.database import Base


class EStatDay(Base):
    """日统计"""

    __tablename__ = 'e_stat_day'
    __table_args__ = {'comment': '日统计'}

    id = Column(BigInteger, primary_key=True, nullable=False, autoincrement=True, comment='主键')
    object_type = Column(String(16), nullable=False, comment='统计对象（point/equipment/area）')
    object_id = Column(BigInteger, nullable=False, comment='对象ID')
    energy_type_code = Column(String(32), nullable=False, comment='能源类型')
    stat_date = Column(Date, nullable=False, comment='统计日期')
    total_value = Column(Numeric(20, 4), nullable=True, server_default='0', comment='当日总量')
    peak_value = Column(Numeric(20, 4), nullable=True, server_default='0', comment='峰段量')
    flat_value = Column(Numeric(20, 4), nullable=True, server_default='0', comment='平段量')
    valley_value = Column(Numeric(20, 4), nullable=True, server_default='0', comment='谷段量')
    coverage_ratio = Column(Numeric(6, 4), nullable=True, comment='覆盖率（0–1）')
    workday_flag = Column(SmallInteger, nullable=True, server_default='1', comment='是否工作日')
    baseline_id = Column(BigInteger, nullable=True, comment='关联基线ID')
    baseline_deviation_pct = Column(Numeric(8, 4), nullable=True, comment='基线偏差百分比')
    version_no = Column(Integer, nullable=True, server_default='1', comment='版本号')
    has_recompute_pending = Column(SmallInteger, nullable=True, server_default='0',
                                   comment='待重算标记（REQ-020）')
    computed_at = Column(DateTime, nullable=True, comment='计算时间')
