"""
e_dict_energy_type ｜ 能源类型字典
DDL: datagen/ddl/V001__energy_domain.sql §1
REQ 锚点：REQ-002（采集点单位与能源类型匹配）、PRD §7.1 电/水/压缩空气
"""

from sqlalchemy import Column, Integer, String

from config.database import Base


class EDictEnergyType(Base):
    """能源类型字典（电/水/压缩空气）"""

    __tablename__ = 'e_dict_energy_type'
    __table_args__ = {'comment': '能源类型字典（电/水/压缩空气）'}

    type_code = Column(String(32), primary_key=True, nullable=False, comment='能源类型编码')
    type_name = Column(String(64), nullable=False, comment='能源类型名称')
    base_unit = Column(String(16), nullable=False, comment='基础计量单位（kWh/m3）')
    sort_no = Column(Integer, nullable=True, server_default='0', comment='显示顺序')
    remark = Column(String(255), nullable=True, comment='备注')
