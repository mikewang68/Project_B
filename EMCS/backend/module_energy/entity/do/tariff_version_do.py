"""
e_tariff_version ｜ 电/水/气 单价版本（REQ-051/052）
DDL: datagen/ddl/V001__energy_domain.sql §20
数据来源：PRD §7.1 峰 1.20 / 平 0.75 / 谷 0.40；水 4.50；气 0.12
"""

from sqlalchemy import BigInteger, Column, Date, DateTime, Integer, Numeric, String

from config.database import Base


class ETariffVersion(Base):
    """单价版本表"""

    __tablename__ = 'e_tariff_version'
    __table_args__ = {'comment': '单价版本表'}

    tariff_id = Column(BigInteger, primary_key=True, nullable=False, autoincrement=True, comment='单价版本ID')
    energy_type_code = Column(String(32), nullable=False, comment='能源类型')
    tou_period = Column(String(16), nullable=False, comment='峰平谷（peak/flat/valley/flat_only）')
    price = Column(Numeric(10, 4), nullable=False, comment='单价')
    currency = Column(String(8), nullable=True, server_default='CNY', comment='币种')
    effective_from = Column(Date, nullable=False, comment='生效起')
    effective_to = Column(Date, nullable=True, comment='生效止（null 表示当前有效）')
    version_no = Column(Integer, nullable=True, server_default='1', comment='版本号')
    remark = Column(String(255), nullable=True, comment='备注')
    create_by = Column(String(64), nullable=True, comment='创建者')
    create_time = Column(DateTime, nullable=True, comment='创建时间')
