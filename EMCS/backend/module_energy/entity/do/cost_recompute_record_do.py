"""e_cost_recompute_record frozen recomputation evidence (REQ-073/074)."""

from sqlalchemy import CHAR, BigInteger, Column, DateTime, Integer, String, Text

from config.database import Base


class ECostRecomputeRecord(Base):
    __tablename__ = 'e_cost_recompute_record'
    __table_args__ = {'comment': '成本重算版本差异记录'}

    recompute_id = Column(BigInteger, primary_key=True, autoincrement=True)
    period_key = Column(String(64), nullable=False)
    stat_month = Column(CHAR(7), nullable=False)
    energy_type_code = Column(String(32), nullable=False)
    scope = Column(String(64), nullable=False)
    old_cost_version = Column(Integer, nullable=False)
    new_cost_version = Column(Integer, nullable=False)
    trigger_reason = Column(String(255), nullable=False)
    trigger_type = Column(String(32), nullable=False)
    triggered_by = Column(String(64), nullable=False)
    triggered_at = Column(DateTime, nullable=False)
    tariff_snapshot_json = Column(Text, nullable=False)
    alloc_rule_snapshot_json = Column(Text, nullable=False)
    diff_summary_json = Column(Text, nullable=False)
    review_status = Column(String(16), nullable=False, server_default='pending')
    reviewed_by = Column(String(64), nullable=True)
    reviewed_at = Column(DateTime, nullable=True)
    review_remark = Column(String(255), nullable=True)
