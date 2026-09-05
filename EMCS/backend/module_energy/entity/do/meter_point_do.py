"""
e_meter_point ｜ 采集点主数据（约 48 个）
DDL: datagen/ddl/V001__energy_domain.sql §4
REQ 锚点：REQ-002 必填七项、REQ-006 配备状态、REQ-007 未映射/停用不进正式统计、REQ-103 外置接入字段
"""

from sqlalchemy import BigInteger, Column, DateTime, Integer, Numeric, String

from config.database import Base


class EMeterPoint(Base):
    """采集点主数据"""

    __tablename__ = 'e_meter_point'
    __table_args__ = {'comment': '采集点主数据'}

    point_id = Column(BigInteger, primary_key=True, nullable=False, autoincrement=True, comment='采集点ID')
    point_code = Column(String(64), nullable=False, unique=True, comment='采集点编号（全局唯一）')
    point_name = Column(String(128), nullable=False, comment='显示名称')
    area_id = Column(BigInteger, nullable=False, comment='所属装卸区ID')
    equipment_id = Column(BigInteger, nullable=True, comment='所属设备ID（环境/照明点可空）')
    point_category = Column(String(32), nullable=False,
                            comment='采集点类别（device_meter/area_meter/branch_meter/water/air_flow/status/env）')
    energy_type_code = Column(String(32), nullable=True, comment='能源类型编码（关联 e_dict_energy_type）')
    unit = Column(String(16), nullable=False, comment='计量单位（kWh/m3/none）')
    sample_period_sec = Column(Integer, nullable=False, comment='采样周期（秒）')
    source_type = Column(String(32), nullable=False,
                         comment='接口来源（gateway/data_platform/offline_import/manual）')
    multiplier = Column(Numeric(10, 4), nullable=True, server_default='1.0000', comment='倍率')
    multiplier_effective_time = Column(DateTime, nullable=True, comment='倍率生效时间')
    range_min = Column(Numeric(20, 4), nullable=True, comment='量程下限（REQ-021）')
    range_max = Column(Numeric(20, 4), nullable=True, comment='量程上限')
    provision_status = Column(String(32), nullable=True, server_default='active',
                              comment='配备状态（REQ-006）')
    access_mode = Column(String(16), nullable=True, server_default='direct',
                         comment='接入方式（direct/external，REQ-103）')
    protocol_type = Column(String(32), nullable=True, comment='协议类型')
    edge_gateway = Column(String(64), nullable=True, comment='边缘网关来源')
    owner_role = Column(String(32), nullable=True, comment='责任对象角色')
    status = Column(String(16), nullable=True, server_default='enabled',
                    comment='状态（pending_mapping/enabled/disabled/maintenance/replaced/archived）')
    remark = Column(String(255), nullable=True, comment='备注')
    create_time = Column(DateTime, nullable=True, comment='创建时间')
    update_time = Column(DateTime, nullable=True, comment='更新时间')
