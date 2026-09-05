"""
e_collect_task ｜ 采集任务与批次
DDL: datagen/ddl/V001__energy_domain.sql §6
REQ 锚点：REQ-013 3× 周期上限 15 分钟异常判定 · REQ-014 缓存起止/补传批次/重复处理/失败原因
         REQ-068 重试策略 · REQ-079 数据延迟监控

状态语义：
- success   正常入库
- failed    连续 3 个周期无数据（离线）
- late      迟到入库（ingest - sample > 15min）
- backfilled 补传成功，parent_task_id 指向原离线任务
"""

from sqlalchemy import BigInteger, Column, DateTime, Integer, String

from config.database import Base


class ECollectTask(Base):
    """采集任务与批次"""

    __tablename__ = 'e_collect_task'
    __table_args__ = {'comment': '采集任务与批次'}

    task_id = Column(BigInteger, primary_key=True, nullable=False, autoincrement=True, comment='任务ID')
    batch_no = Column(String(64), nullable=False, comment='批次号')
    area_id = Column(BigInteger, nullable=True, comment='涉及装卸区')
    point_scope = Column(String(255), nullable=True, comment='涉及采集点范围（CSV）')
    scheduled_time = Column(DateTime, nullable=False, comment='计划执行时间')
    actual_ingest_time = Column(DateTime, nullable=True, comment='实际入库时间')
    task_status = Column(String(16), nullable=False, comment='任务状态（success/failed/late/backfilled）')
    affected_point_count = Column(Integer, nullable=True, server_default='0', comment='受影响采集点数')
    failure_reason = Column(String(255), nullable=True, comment='失败原因')
    retry_count = Column(Integer, nullable=True, server_default='0', comment='重试次数')
    outage_start = Column(DateTime, nullable=True, comment='断传起始（缓存起 REQ-014）')
    outage_end = Column(DateTime, nullable=True, comment='断传结束（缓存止）')
    parent_task_id = Column(BigInteger, nullable=True, comment='父任务ID（补传→原离线）')
    create_time = Column(DateTime, nullable=True, comment='创建时间')
