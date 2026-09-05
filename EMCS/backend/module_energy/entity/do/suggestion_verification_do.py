"""e_suggestion_verification ORM mapping (REQ-048)."""

from sqlalchemy import BigInteger, Column, DateTime, Integer, Numeric, String, Text

from config.database import Base


class ESuggestionVerification(Base):
    """Immutable versioned verification snapshot."""

    __tablename__ = 'e_suggestion_verification'
    __table_args__ = {'comment': '建议验证快照（REQ-048）'}

    verification_id = Column(BigInteger, primary_key=True, autoincrement=True)
    suggestion_id = Column(BigInteger, nullable=False)
    version = Column(Integer, nullable=False)
    status = Column(String(16), nullable=False)
    repair_at = Column(DateTime, nullable=False)
    baseline_start = Column(DateTime, nullable=False)
    baseline_end = Column(DateTime, nullable=False)
    report_start = Column(DateTime, nullable=False)
    report_end = Column(DateTime, nullable=False)
    usage_comparison_json = Column(Text, nullable=False)
    cost_comparison_json = Column(Text, nullable=False)
    workload_comparison_json = Column(Text, nullable=False)
    quality_comparison_json = Column(Text, nullable=False)
    saving_value = Column(Numeric(20, 4), nullable=True)
    saving_unit = Column(String(16), nullable=False)
    saving_pct = Column(Numeric(9, 4), nullable=True)
    calculation_note = Column(Text, nullable=False)
    formula_version = Column(String(64), nullable=False)
    signature = Column(String(64), nullable=False)
    generated_by = Column(String(64), nullable=False)
    generated_at = Column(DateTime, nullable=False)
