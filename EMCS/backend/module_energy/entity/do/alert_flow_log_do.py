"""
e_alert_flow_log ｜ 告警状态流转留痕
REQ 锚点：REQ-041 状态、处理人、时间和备注完整可查
"""

from sqlalchemy import BigInteger, Column, DateTime, String

from config.database import Base


class EAlertFlowLog(Base):
    """告警状态流转日志。"""

    __tablename__ = 'e_alert_flow_log'
    __table_args__ = {'comment': '告警状态流转留痕'}

    flow_id = Column(BigInteger, primary_key=True, nullable=False, autoincrement=True, comment='流转ID')
    event_id = Column(BigInteger, nullable=False, comment='告警事件ID')
    from_status = Column(String(16), nullable=False, comment='流转前状态')
    to_status = Column(String(16), nullable=False, comment='流转后状态')
    operator = Column(String(64), nullable=False, comment='操作人')
    remark = Column(String(255), nullable=True, comment='处置备注')
    occur_time = Column(DateTime, nullable=False, comment='流转时间')
