"""
e_cost_record ｜ 月度成本记录（REQ-051~056，第五幕）
DDL: datagen/ddl/V001__energy_domain.sql §22
"""

from sqlalchemy import (
    CHAR,
    BigInteger,
    Boolean,
    Column,
    Computed,
    DateTime,
    Integer,
    Numeric,
    String,
    Text,
)

from config.database import Base


class ECostRecord(Base):
    """月度成本记录"""

    __tablename__ = 'e_cost_record'
    __table_args__ = {'comment': '月度成本记录'}

    id = Column(BigInteger, primary_key=True, nullable=False, autoincrement=True, comment='主键')
    object_type = Column(String(16), nullable=False, comment='对象类型（area/equipment/system）')
    object_id = Column(BigInteger, nullable=True, comment='对象ID')
    normalized_object_id = Column(
        BigInteger,
        Computed('ifnull(object_id, 0)', persisted=True),
        comment='唯一键对象ID',
    )
    stat_month = Column(CHAR(7), nullable=False, comment='统计月份（YYYY-MM）')
    energy_type_code = Column(String(32), nullable=False, comment='能源类型')
    cost_version = Column(Integer, nullable=False, server_default='1', comment='成本版本')
    is_current = Column(Boolean, nullable=False, server_default='1', comment='是否当前版本')
    current_guard = Column(
        String(160),
        Computed(
            "case when is_current = 1 then concat(object_type, ':', "
            "ifnull(object_id, 0), ':', stat_month, ':', energy_type_code) else null end",
            persisted=True,
        ),
        comment='仅current行生成的业务唯一键',
    )
    usage_qty = Column(Numeric(20, 4), nullable=True, server_default='0', comment='合格用量')
    peak_qty = Column(Numeric(20, 4), nullable=True, server_default='0', comment='峰段用量')
    flat_qty = Column(Numeric(20, 4), nullable=True, server_default='0', comment='平段用量')
    valley_qty = Column(Numeric(20, 4), nullable=True, server_default='0', comment='谷段用量')
    peak_cost = Column(Numeric(20, 4), nullable=True, server_default='0', comment='峰段成本')
    flat_cost = Column(Numeric(20, 4), nullable=True, server_default='0', comment='平段成本')
    valley_cost = Column(Numeric(20, 4), nullable=True, server_default='0', comment='谷段成本')
    total_cost = Column(Numeric(20, 4), nullable=True, server_default='0', comment='总成本')
    tariff_version_no = Column(String(64), nullable=True, comment='单价版本快照')
    alloc_rule_version_no = Column(String(64), nullable=True, comment='分摊规则版本快照')
    formula_version = Column(String(64), nullable=False, comment='成本公式版本')
    tariff_snapshot_json = Column(Text, nullable=False, comment='实际参与单价冻结快照')
    alloc_rule_snapshot_json = Column(Text, nullable=False, comment='分摊规则冻结快照')
    source_stat_snapshot_json = Column(Text, nullable=False, comment='来源统计冻结快照')
    status = Column(
        String(16),
        nullable=True,
        server_default='draft',
        comment='draft/pendingReview/reviewed/frozen/pendingRecompute/void',
    )
    reviewed_at = Column(DateTime, nullable=True, comment='复核时间')
    signature = Column(String(96), nullable=False, comment='完整成本签名（REQ-062）')
    computed_by = Column(String(64), nullable=False, comment='计算账号/任务标识')
    computed_at = Column(DateTime, nullable=True, comment='计算时间')
    frozen_at = Column(DateTime, nullable=True, comment='冻结时间')
