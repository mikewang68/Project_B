"""e_suggestion_template ORM mapping (REQ-045)."""

from sqlalchemy import BigInteger, Boolean, Column, DateTime, Integer, Numeric, String, Text

from config.database import Base


class ESuggestionTemplate(Base):
    """Versioned suggestion template; updates always create a new row."""

    __tablename__ = 'e_suggestion_template'
    __table_args__ = {'comment': '节能建议模板（REQ-045）'}

    template_id = Column(BigInteger, primary_key=True, autoincrement=True)
    template_code = Column(String(64), nullable=False)
    template_name = Column(String(128), nullable=False)
    category = Column(String(64), nullable=False)
    source_rule_code = Column(String(16), nullable=True)
    applicable_object_type = Column(String(32), nullable=False)
    action_content = Column(Text, nullable=False)
    required_data = Column(Text, nullable=True)
    estimated_saving = Column(Text, nullable=True)
    cost_impact = Column(Text, nullable=True)
    reliability_impact = Column(Text, nullable=True)
    verification_method = Column(Text, nullable=True)
    default_implementation_difficulty = Column(Numeric(5, 2), nullable=False)
    default_safety_impact = Column(Numeric(5, 2), nullable=False)
    enabled = Column(Boolean, nullable=False, server_default='1')
    version = Column(Integer, nullable=False, server_default='1')
    create_time = Column(DateTime, nullable=False)
    update_time = Column(DateTime, nullable=False)
