"""e_report_archive canonical payload mapping (REQ-030/059/062)."""

from sqlalchemy import BigInteger, Column, Date, DateTime, String, Text

from config.database import Base


class EReportArchive(Base):
    __tablename__ = 'e_report_archive'
    __table_args__ = {'comment': '报表快照归档'}

    archive_id = Column(BigInteger, primary_key=True, autoincrement=True)
    template_code = Column(String(64), nullable=False)
    template_version = Column(String(32), nullable=False)
    period_start = Column(Date, nullable=False)
    period_end = Column(Date, nullable=False)
    filters_snapshot_json = Column(Text, nullable=False)
    payload_snapshot_json = Column(Text, nullable=False)
    version_snapshots_json = Column(Text, nullable=False)
    full_signature = Column(String(96), nullable=False)
    generated_at = Column(DateTime, nullable=False)
    archived_by = Column(String(64), nullable=False)
    archived_at = Column(DateTime, nullable=False)
