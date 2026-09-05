"""
e_equipment ｜ 设备台账（12 台）
DDL: datagen/ddl/V001__energy_domain.sql §3
REQ 锚点：REQ-033~035（设备画像）、构造规范 §1.2
"""

from sqlalchemy import CHAR, BigInteger, Column, DateTime, Numeric, String

from config.database import Base


class EEquipment(Base):
    """设备台账"""

    __tablename__ = 'e_equipment'
    __table_args__ = {'comment': '设备台账'}

    equipment_id = Column(BigInteger, primary_key=True, nullable=False, autoincrement=True, comment='设备ID')
    equipment_code = Column(String(32), nullable=False, unique=True, comment='设备编码（GC-A1/AC-B1 等）')
    equipment_name = Column(String(64), nullable=False, comment='设备名称')
    area_id = Column(BigInteger, nullable=False, comment='所属装卸区ID')
    equipment_type = Column(String(32), nullable=False, comment='设备类型（装卸主/动力/输送/辅助/照明/转运）')
    rated_power_kw = Column(Numeric(10, 2), nullable=True, comment='额定功率(kW)')
    energy_types = Column(String(64), nullable=True, comment='涉及能源类型（逗号分隔）')
    demo_role = Column(String(64), nullable=True, comment='五幕角色标注')
    status = Column(CHAR(1), nullable=True, server_default='0', comment='状态（0启用 1停用）')
    remark = Column(String(255), nullable=True, comment='备注')
    create_time = Column(DateTime, nullable=True, comment='创建时间')
    update_time = Column(DateTime, nullable=True, comment='更新时间')
