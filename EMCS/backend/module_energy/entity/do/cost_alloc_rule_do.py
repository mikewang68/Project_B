"""e_cost_alloc_rule ORM mapping (REQ-055)."""

from sqlalchemy import BigInteger, Column, Date, DateTime, Integer, String

from config.database import Base


class ECostAllocRule(Base):
    __tablename__ = 'e_cost_alloc_rule'
    __table_args__ = {'comment': '成本分摊规则'}

    rule_id = Column(BigInteger, primary_key=True, autoincrement=True)
    rule_name = Column(String(64), nullable=False)
    scope = Column(String(32), nullable=True)
    method = Column(String(32), nullable=False)
    config_json = Column(String(1024), nullable=True)
    effective_from = Column(Date, nullable=False)
    effective_to = Column(Date, nullable=True)
    version_no = Column(Integer, nullable=True, server_default='1')
    create_by = Column(String(64), nullable=True)
    create_time = Column(DateTime, nullable=True)
