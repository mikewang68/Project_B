"""
e_area ｜ 装卸区（AREA-A / AREA-B）
DDL: datagen/ddl/V001__energy_domain.sql §2
REQ 锚点：REQ-057（总览按区分组）、REQ-071~076（数据范围绑定 dept_id）
"""

from sqlalchemy import CHAR, BigInteger, Column, DateTime, String

from config.database import Base


class EArea(Base):
    """装卸区"""

    __tablename__ = 'e_area'
    __table_args__ = {'comment': '装卸区'}

    area_id = Column(BigInteger, primary_key=True, nullable=False, autoincrement=True, comment='装卸区ID')
    area_code = Column(String(32), nullable=False, unique=True, comment='装卸区编码（AREA-A/AREA-B）')
    area_name = Column(String(64), nullable=False, comment='装卸区名称')
    cargo_type = Column(String(32), nullable=False, comment='货类（钢材/粉煤灰，对齐 S208）')
    dept_id = Column(BigInteger, nullable=True, comment='关联部门ID（sys_dept，dispatch 本区数据范围）')
    status = Column(CHAR(1), nullable=True, server_default='0', comment='状态（0启用 1停用）')
    remark = Column(String(255), nullable=True, comment='备注')
    create_time = Column(DateTime, nullable=True, comment='创建时间')
    update_time = Column(DateTime, nullable=True, comment='更新时间')
