"""
e_alert_event ｜ 告警事件（backend 由规则引擎真实算出）
DDL: datagen/ddl/V001__energy_domain.sql §18
REQ 锚点：REQ-041~044 状态机与合并窗口、REQ-043 通知记录、REQ-040 触发时版本冻结
"""

from sqlalchemy import BigInteger, Column, DateTime, Integer, String, Text

from config.database import Base


class EAlertEvent(Base):
    """告警事件"""

    __tablename__ = 'e_alert_event'
    __table_args__ = {'comment': '告警事件'}

    event_id = Column(BigInteger, primary_key=True, nullable=False, autoincrement=True, comment='事件ID')
    rule_id = Column(BigInteger, nullable=False, comment='规则ID')
    rule_code = Column(String(16), nullable=False, comment='规则编码（冗余便于查询）')
    rule_version_no = Column(Integer, nullable=False, comment='触发时规则版本号（REQ-040）')
    object_type = Column(String(16), nullable=False, comment='涉及对象类型（area/equipment/point）')
    object_id = Column(BigInteger, nullable=False, comment='对象ID')
    area_id = Column(BigInteger, nullable=True, comment='装卸区ID（冗余便于数据范围过滤）')
    level = Column(String(8), nullable=False, comment='级别（notice/normal/severe）')
    status = Column(String(16), nullable=False,
                    comment='状态（new/ack/dispatched/processing/closed/false_closed/escalated）')
    first_occur_time = Column(DateTime, nullable=False, comment='首次触发时刻')
    last_occur_time = Column(DateTime, nullable=False, comment='最近触发时刻')
    occur_count = Column(Integer, nullable=True, server_default='1', comment='合并触发次数（REQ-042）')
    snapshot_json = Column(Text, nullable=True, comment='触发时刻曲线快照（json）')
    assigned_to = Column(String(64), nullable=True, comment='派发对象')
    closed_at = Column(DateTime, nullable=True, comment='关闭时间')
    close_reason = Column(String(255), nullable=True, comment='关闭原因（必填）')
    close_type = Column(String(16), nullable=True, comment='关闭类型（valid/false_positive）')
    notification_json = Column(String(1024), nullable=True, comment='通知记录（json，REQ-043）')
    create_time = Column(DateTime, nullable=True, comment='创建时间')
    update_time = Column(DateTime, nullable=True, comment='更新时间')
