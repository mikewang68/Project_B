"""
e_audit_security ｜ 安全审计事件（REQ-076；INJ-08 越权载体）
DDL: datagen/ddl/V001__energy_domain.sql §23

事件类型：
- unauthorized_access 越权（如 ops_user 访问成本页）
- abnormal_login 异常登录
- auth_failed 鉴权失败

约定：不 seed，全部由后端拦截时现场写入（对应 PRD 数据构造规范"INJ-08 不 seed 结果"）。
"""

from sqlalchemy import BigInteger, Column, DateTime, String

from config.database import Base


class EAuditSecurity(Base):
    """安全审计事件"""

    __tablename__ = 'e_audit_security'
    __table_args__ = {'comment': '安全审计事件'}

    audit_id = Column(BigInteger, primary_key=True, nullable=False, autoincrement=True, comment='审计ID')
    event_time = Column(DateTime, nullable=False, comment='事件时刻')
    event_type = Column(String(32), nullable=False,
                        comment='事件类型（unauthorized_access/abnormal_login/auth_failed）')
    user_name = Column(String(64), nullable=True, comment='操作账号')
    user_role = Column(String(32), nullable=True, comment='操作角色')
    target_module = Column(String(64), nullable=True, comment='目标模块（如 cost）')
    target_resource = Column(String(128), nullable=True, comment='目标资源URL/标识')
    client_ip = Column(String(64), nullable=True, comment='客户端IP')
    user_agent = Column(String(255), nullable=True, comment='UA')
    action_result = Column(String(16), nullable=True, server_default='blocked',
                           comment='处置结果（blocked/allowed_flagged）')
    remark = Column(String(255), nullable=True, comment='备注')
    create_time = Column(DateTime, nullable=True, comment='创建时间')
