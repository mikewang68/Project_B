"""ai_inspection_report ｜ AI 自主巡检报告（REQ-AGENT-TBD）

DDL: datagen/ddl/V003__ai_inspection.sql
本 DO 由 agent_inspection_service 作为唯一写入点（其余模块只读）。
"""

from sqlalchemy import JSON, BigInteger, Column, Date, DateTime, Integer, String, func

from config.database import Base


class AiInspectionReport(Base):
    """AI 巡检报告实体（REQ-AGENT-TBD）"""

    __tablename__ = 'ai_inspection_report'
    __table_args__ = {'comment': 'AI 自主巡检报告（agent_inspection_service 唯一写入点）'}

    id = Column(BigInteger, primary_key=True, nullable=False, autoincrement=True, comment='主键')
    report_date = Column(Date, nullable=False, comment='报告日期（DEMO_NOW 日期）')
    trigger_type = Column(String(16), nullable=False, comment='触发类型 scheduled/manual')
    status = Column(String(16), nullable=False, comment='报告状态 success/failed')
    summary = Column(String(1024), nullable=True, comment='巡检结论摘要（LLM 汇总）')
    findings_json = Column(JSON, nullable=True, comment='结构化发现列表')
    stats_json = Column(JSON, nullable=True, comment='统计快照')
    model_name = Column(String(64), nullable=True, comment='本次巡检使用的模型标识')
    elapsed_ms = Column(Integer, nullable=True, comment='端到端耗时（毫秒）')
    created_at = Column(DateTime, nullable=False, server_default=func.current_timestamp(), comment='记录写入时间')
