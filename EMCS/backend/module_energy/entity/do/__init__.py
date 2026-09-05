"""
能源域 ORM 实体（Data Object）。

约定：本目录下的 SQLAlchemy 模型只映射由 datagen 目录发布的业务表 DDL，
不允许本模块自创业务表；DDL 就绪前该目录留空占位。
"""

from module_energy.entity.do.ai_inspection_report_do import AiInspectionReport
from module_energy.entity.do.cost_alloc_rule_do import ECostAllocRule
from module_energy.entity.do.cost_recompute_record_do import ECostRecomputeRecord
from module_energy.entity.do.cost_record_do import ECostRecord
from module_energy.entity.do.report_archive_do import EReportArchive

__all__ = (
    'AiInspectionReport',
    'ECostAllocRule',
    'ECostRecomputeRecord',
    'ECostRecord',
    'EReportArchive',
)
