"""
e_stat_month ｜ 月统计
DDL: datagen/ddl/V001__energy_domain.sql §11
"""

from sqlalchemy import CHAR, BigInteger, Column, DateTime, Integer, Numeric, String

from config.database import Base


class EStatMonth(Base):
    """月统计"""

    __tablename__ = 'e_stat_month'
    __table_args__ = {'comment': '月统计'}

    id = Column(BigInteger, primary_key=True, nullable=False, autoincrement=True, comment='主键')
    object_type = Column(String(16), nullable=False, comment='统计对象')
    object_id = Column(BigInteger, nullable=False, comment='对象ID')
    energy_type_code = Column(String(32), nullable=False, comment='能源类型')
    stat_month = Column(CHAR(7), nullable=False, comment='统计月份（YYYY-MM）')
    total_value = Column(Numeric(20, 4), nullable=True, server_default='0', comment='当月总量')
    peak_value = Column(Numeric(20, 4), nullable=True, server_default='0', comment='峰段量')
    flat_value = Column(Numeric(20, 4), nullable=True, server_default='0', comment='平段量')
    valley_value = Column(Numeric(20, 4), nullable=True, server_default='0', comment='谷段量')
    coverage_ratio = Column(Numeric(6, 4), nullable=True, comment='覆盖率')
    version_no = Column(Integer, nullable=True, server_default='1', comment='版本号')
    computed_at = Column(DateTime, nullable=True, comment='计算时间')
