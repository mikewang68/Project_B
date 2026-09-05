"""e_suggestion ORM mapping for the Act 4 closure domain (REQ-046~050)."""

from sqlalchemy import BigInteger, Column, Date, DateTime, Integer, Numeric, String, Text

from config.database import Base


class ESuggestion(Base):
    """Suggestion instance; source/template evidence is frozen as JSON text."""

    __tablename__ = 'e_suggestion'
    __table_args__ = {'comment': '节能建议实例（REQ-046~050）'}

    suggestion_id = Column(BigInteger, primary_key=True, autoincrement=True)
    source_type = Column(String(16), nullable=False)
    source_alert_id = Column(BigInteger, nullable=True)
    source_fingerprint = Column(String(128), nullable=True)
    source_snapshot_json = Column(Text, nullable=True)
    template_id = Column(BigInteger, nullable=True)
    template_version = Column(Integer, nullable=True)
    template_snapshot_json = Column(Text, nullable=False)
    trigger_basis = Column(Text, nullable=True)
    rule_code = Column(String(16), nullable=True)
    title = Column(String(128), nullable=False)
    measure_content = Column(Text, nullable=False)
    responsible_user = Column(String(64), nullable=True)
    responsible_role = Column(String(32), nullable=True)
    verify_start = Column(Date, nullable=True)
    verify_end = Column(Date, nullable=True)
    area_id = Column(BigInteger, nullable=True)
    equipment_id = Column(BigInteger, nullable=True)
    object_type = Column(String(32), nullable=False)
    object_id = Column(BigInteger, nullable=True)
    status = Column(String(16), nullable=False)
    priority_score = Column(Numeric(6, 2), nullable=False)
    priority_band = Column(String(16), nullable=False)
    priority_formula_version = Column(String(32), nullable=False)
    priority_factors_json = Column(Text, nullable=False)
    repair_at = Column(DateTime, nullable=True)
    baseline_start = Column(DateTime, nullable=True)
    baseline_end = Column(DateTime, nullable=True)
    report_start = Column(DateTime, nullable=True)
    report_end = Column(DateTime, nullable=True)
    saving_value = Column(Numeric(20, 4), nullable=True)
    saving_unit = Column(String(16), nullable=True)
    close_type = Column(String(32), nullable=True)
    close_reason = Column(String(512), nullable=True)
    rejection_reason = Column(String(512), nullable=True)
    invalid_category = Column(String(32), nullable=True)
    deferred_from_status = Column(String(16), nullable=True)
    defer_reason = Column(String(512), nullable=True)
    defer_until = Column(Date, nullable=True)
    effect_summary = Column(String(1024), nullable=True)
    attachments_json = Column(Text, nullable=True)
    created_by = Column(String(64), nullable=False)
    closed_by = Column(String(64), nullable=True)
    closed_at = Column(DateTime, nullable=True)
    row_version = Column(Integer, nullable=False, server_default='1')
    create_time = Column(DateTime, nullable=False)
    update_time = Column(DateTime, nullable=False)
