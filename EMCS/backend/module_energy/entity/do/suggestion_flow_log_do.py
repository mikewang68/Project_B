"""e_suggestion_flow_log ORM mapping (REQ-047)."""

from sqlalchemy import BigInteger, Column, DateTime, String, Text

from config.database import Base


class ESuggestionFlowLog(Base):
    """Append-only suggestion evidence ordered by flow_id, never occur_time."""

    __tablename__ = 'e_suggestion_flow_log'
    __table_args__ = {'comment': '建议流转留痕（REQ-047）'}

    flow_id = Column(BigInteger, primary_key=True, autoincrement=True)
    suggestion_id = Column(BigInteger, nullable=False)
    from_status = Column(String(16), nullable=True)
    to_status = Column(String(16), nullable=False)
    operator = Column(String(64), nullable=False)
    operator_role = Column(String(32), nullable=False)
    action = Column(String(32), nullable=False)
    remark = Column(String(512), nullable=True)
    payload_snapshot_json = Column(Text, nullable=True)
    occur_time = Column(DateTime, nullable=False)
