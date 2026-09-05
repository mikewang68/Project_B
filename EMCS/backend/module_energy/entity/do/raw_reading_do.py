"""
e_raw_reading ｜ 原始采集读数（时序）
DDL: datagen/ddl/V001__energy_domain.sql §5
REQ 锚点：REQ-011 幂等键（point_id + sample_time）、REQ-014 补传标记、
         REQ-015 保留原始值、REQ-020 迟到/估算/修正质量标记
"""

from sqlalchemy import BigInteger, Column, DateTime, Numeric, SmallInteger, String

from config.database import Base


class ERawReading(Base):
    """原始采集读数"""

    __tablename__ = 'e_raw_reading'
    __table_args__ = {'comment': '原始采集读数（时序）'}

    reading_id = Column(BigInteger, primary_key=True, nullable=False, autoincrement=True, comment='原始读数ID')
    point_id = Column(BigInteger, nullable=False, comment='采集点ID')
    sample_time = Column(DateTime, nullable=False, comment='采样时间（业务时刻）')
    cumulative_value = Column(Numeric(20, 4), nullable=True, comment='累计值（表计读数）')
    incremental_value = Column(Numeric(20, 4), nullable=True, comment='本周期增量')
    status_value = Column(String(16), nullable=True, comment='状态值（running/standby/stopped/maintenance）')
    unit = Column(String(16), nullable=True, comment='单位（冗余）')
    quality_state = Column(String(16), nullable=False,
                           comment='质量状态八态短码（ok/miss/late/dup/jump/est/fix/frozen）')
    source_batch = Column(String(64), nullable=True, comment='来源批次号')
    ingest_time = Column(DateTime, nullable=False, comment='入库时间（用于迟到判定 R02）')
    is_backfill = Column(SmallInteger, nullable=True, server_default='0', comment='是否补传（0否 1是）')
    is_estimated = Column(SmallInteger, nullable=True, server_default='0', comment='是否估算填补')
    correction_reason = Column(String(255), nullable=True, comment='人工修正原因')
