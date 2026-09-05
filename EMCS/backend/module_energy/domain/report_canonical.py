"""Deterministic canonical report payload and signature (REQ-030/062)."""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from datetime import date, datetime
from decimal import Decimal
from typing import Any


@dataclass(frozen=True)
class CanonicalReportPayload:
    """The single immutable snapshot shared by preview/export/archive."""

    template_code: str
    template_version: str
    period: dict[str, Any]
    filters: dict[str, Any]
    generated_at: datetime
    sections: dict[str, Any]
    quality_summary: dict[str, Any]
    version_snapshots: dict[str, Any]

    def to_dict(self) -> dict[str, Any]:
        return {
            'templateCode': self.template_code,
            'templateVersion': self.template_version,
            'period': _canonical_value(self.period),
            'filters': _canonical_value(self.filters),
            'generatedAt': _canonical_value(self.generated_at),
            'sections': _canonical_value(self.sections),
            'qualitySummary': _canonical_value(self.quality_summary),
            'versionSnapshots': _canonical_value(self.version_snapshots),
        }


def canonical_report_bytes(payload: CanonicalReportPayload) -> bytes:
    """Stable UTF-8 JSON used by every report signature consumer."""
    return json.dumps(
        payload.to_dict(),
        ensure_ascii=False,
        sort_keys=True,
        separators=(',', ':'),
    ).encode('utf-8')


def build_report_signature(payload: CanonicalReportPayload) -> str:
    digest = hashlib.sha256(canonical_report_bytes(payload)).hexdigest()
    return f'REPORT-SHA256-V1:{digest}'


def _canonical_value(value: Any) -> Any:
    if isinstance(value, dict):
        return {
            str(key): _canonical_value(item)
            for key, item in sorted(value.items(), key=lambda pair: str(pair[0]))
        }
    if isinstance(value, (list, tuple)):
        return [_canonical_value(item) for item in value]
    if isinstance(value, Decimal):
        return format(value, 'f')
    if isinstance(value, datetime):
        return value.isoformat(timespec='seconds')
    if isinstance(value, date):
        return value.isoformat()
    return value
