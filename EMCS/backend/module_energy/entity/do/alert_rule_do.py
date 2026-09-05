"""
e_alert_rule ｜ 告警规则库（demo R01–R11）
DDL: datagen/ddl/V001__energy_domain.sql §16
REQ 锚点：REQ-039 级别三档、REQ-040 规则版本
数据来源：PRD §7.2 初始规则库（demo 全局编号约定，与需求基线 K.7 语义不同，参见 docs/mock-contracts.md）
"""

from sqlalchemy import BigInteger, Column, DateTime, Integer, SmallInteger, String

from config.database import Base


class EAlertRule(Base):
    """告警规则库"""

    __tablename__ = 'e_alert_rule'
    __table_args__ = {'comment': '告警规则库'}

    rule_id = Column(BigInteger, primary_key=True, nullable=False, autoincrement=True, comment='规则ID')
    rule_code = Column(String(16), nullable=False, unique=True, comment='规则编码（R01..R11）')
    rule_name = Column(String(64), nullable=False, comment='规则名称')
    rule_category = Column(String(32), nullable=True, comment='规则分类（quality/energy/cost/security）')
    expression = Column(String(512), nullable=True, comment='判定表达式（可读描述）')
    threshold_json = Column(String(512), nullable=True, comment='阈值参数（json）')
    level = Column(String(8), nullable=False, comment='级别（notice/normal/severe）')
    enabled = Column(SmallInteger, nullable=True, server_default='1', comment='启用（0停用 1启用）')
    effective_from = Column(DateTime, nullable=True, comment='生效起')
    deprecated_at = Column(DateTime, nullable=True, comment='停用时间')
    version_no = Column(Integer, nullable=True, server_default='1', comment='当前版本号')
    remark = Column(String(255), nullable=True, comment='备注')
    create_by = Column(String(64), nullable=True, comment='创建者')
    update_by = Column(String(64), nullable=True, comment='更新者')
    create_time = Column(DateTime, nullable=True, comment='创建时间')
    update_time = Column(DateTime, nullable=True, comment='更新时间')
