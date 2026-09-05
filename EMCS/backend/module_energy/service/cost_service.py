"""Immutable monthly cost versions and atomic recomputation review.

REQ-051/052/055/062/073/074. Cost inputs are read from server-side frozen
statistics, tariffs, and allocation rules; client-provided amounts or evidence
are never persisted.
"""

from __future__ import annotations

import json
from calendar import monthrange
from collections import Counter
from collections.abc import AsyncIterator, Mapping
from contextlib import asynccontextmanager
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from decimal import Decimal, InvalidOperation
from typing import TYPE_CHECKING, Any

from fastapi import HTTPException
from sqlalchemy.ext.asyncio import AsyncConnection, AsyncEngine

from module_energy.dao.cost_dao import CostDao
from module_energy.domain.cost_calculation import (
    AmbiguousTariffError,
    CostCalculationInput,
    CostCalculationResult,
    MissingTariffError,
    TariffSnapshot,
    build_cost_diff,
    build_cost_signature,
    calculate_cost,
)
from module_energy.entity.do.cost_alloc_rule_do import ECostAllocRule
from module_energy.entity.do.cost_recompute_record_do import ECostRecomputeRecord
from module_energy.entity.do.cost_record_do import ECostRecord
from module_energy.entity.do.tariff_version_do import ETariffVersion
from module_energy.service.audit_service import append_cost_operation_audit
from module_energy.service.demo_now_util import get_demo_now
from utils.log_util import logger

if TYPE_CHECKING:
    from sqlalchemy.ext.asyncio import AsyncSession

    from module_energy.entity.do.stat_month_do import EStatMonth

_ZERO = Decimal('0')
_STAT_MONTH_LENGTH = 7
_TARIFF_SNAPSHOT_FIELDS = {
    'tariffId',
    'energyType',
    'touPeriod',
    'price',
    'currency',
    'effectiveFrom',
    'effectiveTo',
    'versionNo',
}
_ALLOC_SNAPSHOT_FIELDS = {
    'ruleId',
    'ruleName',
    'scope',
    'method',
    'config',
    'effectiveFrom',
    'effectiveTo',
    'versionNo',
    'applicationStatus',
    'allocationDetails',
    'message',
}
_SOURCE_SNAPSHOT_FIELDS = {
    'table',
    'recordId',
    'version',
    'qualifiedUsageQty',
    'quality',
}


class CostStateError(RuntimeError):
    """Raised when persisted version state is incomplete or inconsistent."""


class CostNotFoundError(LookupError):
    """Raised when a requested cost batch or recomputation does not exist."""


class CostConflictError(HTTPException):
    """409-semantic conflict for an in-flight cost recomputation."""

    def __init__(self, detail: str) -> None:
        super().__init__(status_code=409, detail=detail)


@dataclass(frozen=True)
class CostBatchResult:
    cost_version: int
    affected_object_count: int
    counts: dict[str, int]
    initialized: bool


@dataclass(frozen=True)
class CostRecomputeResult:
    recompute_id: int
    stat_month: str
    energy_type: str
    old_cost_version: int
    new_cost_version: int
    review_status: str
    current_cost_version: int
    affected_object_count: int
    diff_summary: tuple[dict[str, object], ...]


class CostService:
    """Versioned monthly cost persistence; one final commit per write operation."""

    @classmethod
    async def initialize_v1(
        cls,
        db: AsyncSession,
        *,
        computed_by: str,
    ) -> CostBatchResult:
        """Create reviewed/current v1 exactly once from every e_stat_month key."""
        actor = cls._required_text(computed_by, 'computed_by')
        try:
            stats = await CostDao.load_stats(db)
            existing_count = await CostDao.count_versions(db)
            if existing_count:
                existing = await CostDao.load_all_cost_rows(db)
                return cls._validate_initialized(stats, existing)

            if not stats:
                return CostBatchResult(1, 0, {}, True)

            tariffs = await CostDao.load_tariffs(db)
            alloc_rules = await CostDao.load_alloc_rules(db)
            computed_at = await get_demo_now(db)
            records = [
                cls._record_from_stat(
                    stat,
                    cls._select_tariffs(tariffs, stat.energy_type_code, stat.stat_month),
                    cls._select_alloc_rule(alloc_rules, stat.stat_month),
                    cost_version=1,
                    status='reviewed',
                    is_current=True,
                    computed_by=actor,
                    computed_at=computed_at,
                )
                for stat in stats
            ]
            counts = dict(Counter(record.object_type for record in records))
            await CostDao.insert_versions(db, records)
            await db.commit()
        except Exception:
            await db.rollback()
            raise

        logger.info(f'[cost] initialized immutable v1 rows={len(records)} counts={counts}')
        return CostBatchResult(1, len(records), counts, True)

    @classmethod
    async def validate_current_integrity(cls, db: AsyncSession) -> CostBatchResult:
        """REQ-062 bootstrap gate: every statistic key has one verified current row."""
        stats = await CostDao.load_stats(db)
        rows = await CostDao.load_all_cost_rows(db)
        return cls._validate_initialized(stats, rows)

    @classmethod
    async def list_tariffs(
        cls,
        db: AsyncSession,
        *,
        energy_type: str | None,
        effective_on: date | None,
        include_history: bool,
    ) -> list[dict[str, object]]:
        # REQ-051/052: default reads only versions effective at DEMO_NOW.
        filter_date = effective_on
        if filter_date is None and not include_history:
            filter_date = (await get_demo_now(db)).date()
        rows = await CostDao.list_tariff_versions(
            db,
            energy_type=energy_type,
            effective_on=filter_date,
        )
        return [cls._serialize_tariff(row) for row in rows]

    @classmethod
    async def create_tariff_version(
        cls,
        db: AsyncSession,
        request: object,
        *,
        operator: str,
    ) -> dict[str, object]:
        """Append a complete tariff pack; overlap fails before any INSERT."""
        actor = cls._required_text(operator, 'operator')
        energy_type = cls._required_text(
            cls._request_value(request, 'energy_type'),
            'energy_type',
        )
        effective_from = cls._request_value(request, 'effective_from')
        effective_to = cls._request_value(request, 'effective_to')
        remark = cls._required_text(cls._request_value(request, 'remark'), 'remark')
        prices = cls._request_value(request, 'prices')
        if not isinstance(effective_from, date):
            raise ValueError('effective_from must be a date')
        if effective_to is not None and not isinstance(effective_to, date):
            raise ValueError('effective_to must be a date')
        price_map = cls._request_mapping(prices)
        period_prices = (
            {
                'peak': price_map.get('peak'),
                'flat': price_map.get('flat'),
                'valley': price_map.get('valley'),
            }
            if energy_type == 'electricity'
            else {'flat_only': price_map.get('flat_only', price_map.get('flatOnly'))}
        )
        if any(value is None for value in period_prices.values()):
            raise ValueError('tariff price pack is incomplete')

        lock_name = f'bdemo:cost:tariff:{energy_type}'
        try:
            async with cls._config_lock(db, lock_name):
                try:
                    overlap = await CostDao.lock_overlapping_tariffs(
                        db,
                        energy_type=energy_type,
                        periods=tuple(period_prices),
                        effective_from=effective_from,
                        effective_to=effective_to,
                    )
                    cls._close_superseded_tariff_pack(
                        overlap,
                        periods=tuple(period_prices),
                        effective_from=effective_from,
                    )
                    version_no = await CostDao.max_tariff_version(db, energy_type) + 1
                    created_at = await get_demo_now(db)
                    rows = [
                        ETariffVersion(
                            energy_type_code=energy_type,
                            tou_period=period,
                            price=Decimal(str(price)),
                            currency='CNY',
                            effective_from=effective_from,
                            effective_to=effective_to,
                            version_no=version_no,
                            remark=remark,
                            create_by=actor,
                            create_time=created_at,
                        )
                        for period, price in period_prices.items()
                    ]
                    await CostDao.insert_tariff_versions(db, rows)
                    affected_periods = await CostDao.list_affected_stat_months(
                        db,
                        energy_type=energy_type,
                        effective_from=effective_from,
                        effective_to=effective_to,
                    )
                    await db.commit()
                except Exception:
                    await db.rollback()
                    raise
        except TimeoutError as exc:
            raise CostConflictError(str(exc)) from exc
        return {
            'tariffVersion': version_no,
            'snapshot': [cls._serialize_tariff(row) for row in rows],
            'affectedPeriods': affected_periods,
            'recomputeRequired': True,
        }

    @classmethod
    async def list_allocation_rules(
        cls,
        db: AsyncSession,
        *,
        scope: str | None,
        effective_on: date | None,
        include_history: bool,
    ) -> list[dict[str, object]]:
        filter_date = effective_on
        if filter_date is None and not include_history:
            filter_date = (await get_demo_now(db)).date()
        rows = await CostDao.list_allocation_rules(
            db,
            scope=scope,
            effective_on=filter_date,
        )
        return [cls._serialize_allocation_rule(row) for row in rows]

    @classmethod
    async def create_allocation_rule(
        cls,
        db: AsyncSession,
        request: object,
        *,
        operator: str,
    ) -> dict[str, object]:
        """REQ-054 append-only rule creation with method-specific normalization."""
        actor = cls._required_text(operator, 'operator')
        rule_name = cls._required_text(
            cls._request_value(request, 'rule_name'),
            'rule_name',
        )
        scope = cls._required_text(cls._request_value(request, 'scope'), 'scope')
        method = cls._required_text(cls._request_value(request, 'method'), 'method')
        if method not in {'ratio', 'weight', 'workload', 'manual'}:
            raise ValueError('allocation method is invalid')
        effective_from = cls._request_value(request, 'effective_from')
        effective_to = cls._request_value(request, 'effective_to')
        if not isinstance(effective_from, date):
            raise ValueError('effective_from must be a date')
        if effective_to is not None and not isinstance(effective_to, date):
            raise ValueError('effective_to must be a date')
        config = cls._request_mapping(cls._request_value(request, 'config'))
        config = cls._normalize_allocation_config(method, config)

        lock_name = f'bdemo:cost:allocation:{scope}'
        try:
            async with cls._config_lock(db, lock_name):
                try:
                    overlap = await CostDao.lock_overlapping_allocation_rules(
                        db,
                        scope=scope,
                        effective_from=effective_from,
                        effective_to=effective_to,
                    )
                    cls._close_superseded_allocation_rule(
                        overlap,
                        effective_from=effective_from,
                    )
                    version_no = await CostDao.max_allocation_version(db, scope) + 1
                    created_at = await get_demo_now(db)
                    row = ECostAllocRule(
                        rule_name=rule_name,
                        scope=scope,
                        method=method,
                        config_json=cls._json_dump(config),
                        effective_from=effective_from,
                        effective_to=effective_to,
                        version_no=version_no,
                        create_by=actor,
                        create_time=created_at,
                    )
                    await CostDao.insert_allocation_rule(db, row)
                    await db.commit()
                except Exception:
                    await db.rollback()
                    raise
        except TimeoutError as exc:
            raise CostConflictError(str(exc)) from exc
        return cls._serialize_allocation_rule(row)

    @classmethod
    def _normalize_allocation_config(  # noqa: PLR0912
        cls,
        method: str,
        config: Mapping[str, object],
    ) -> dict[str, list[dict[str, object]]]:
        """Validate explainability inputs and make stored ratios server-owned."""
        allocations = config.get('allocations')
        if not isinstance(allocations, list) or not allocations:
            raise ValueError('allocation config is incomplete')
        normalized_ids: list[int] = []
        normalized_allocations: list[dict[str, object]] = []
        ratio_total = Decimal('0')
        basis_total = Decimal('0')
        for allocation in allocations:
            item = cls._request_mapping(allocation)
            object_id = item.get('objectId', item.get('object_id'))
            ratio = item.get('ratio')
            basis_value = item.get('basisValue', item.get('basis_value'))
            try:
                parsed_id = int(object_id)
                parsed_ratio = Decimal(str(ratio)) if ratio is not None else None
                parsed_basis = Decimal(str(basis_value)) if basis_value is not None else None
            except (InvalidOperation, TypeError, ValueError):
                raise ValueError('allocation objectId/ratio/basisValue is invalid') from None
            if parsed_id < 1:
                raise ValueError('allocation objectId/ratio is invalid')
            if method in {'weight', 'workload'}:
                if parsed_basis is None or parsed_basis <= 0:
                    raise ValueError(f'{method} allocation requires positive basisValue')
                basis_total += parsed_basis
            elif parsed_ratio is None or parsed_ratio <= 0 or parsed_ratio > 1:
                raise ValueError('allocation objectId/ratio is invalid')
            normalized_ids.append(parsed_id)
            ratio_total += parsed_ratio or Decimal('0')
            normalized_allocations.append(
                {
                    'objectId': parsed_id,
                    'ratio': parsed_ratio,
                    'basisValue': parsed_basis,
                }
            )
        if len(normalized_ids) != len(set(normalized_ids)):
            raise ValueError('allocation object ids must be unique')
        if method in {'ratio', 'manual'} and ratio_total != Decimal('1'):
            raise ValueError('allocation ratios must be unique by object and total 1')
        if method in {'weight', 'workload'}:
            running = Decimal('0')
            for index, item in enumerate(normalized_allocations):
                if index == len(normalized_allocations) - 1:
                    normalized_ratio = Decimal('1') - running
                else:
                    basis_value = item['basisValue']
                    if not isinstance(basis_value, Decimal):
                        raise ValueError(f'{method} allocation requires positive basisValue')
                    normalized_ratio = (basis_value / basis_total).quantize(
                        Decimal('0.00000001')
                    )
                    running += normalized_ratio
                item['ratio'] = normalized_ratio
        return {'allocations': normalized_allocations}

    @staticmethod
    def _close_superseded_tariff_pack(
        overlap: list[ETariffVersion],
        *,
        periods: tuple[str, ...],
        effective_from: date,
    ) -> None:
        """Close exactly one prior open pack; reject every other intersection."""
        if not overlap:
            return
        expected_periods = set(periods)
        found_periods = {row.tou_period for row in overlap}
        versions = {int(row.version_no or 1) for row in overlap}
        closable = (
            found_periods == expected_periods
            and len(overlap) == len(expected_periods)
            and len(versions) == 1
            and all(
                row.effective_to is None and row.effective_from < effective_from
                for row in overlap
            )
        )
        if not closable:
            raise HTTPException(
                status_code=422,
                detail='单价生效日期区间与既有版本真实交叉',
            )
        closed_on = effective_from - timedelta(days=1)
        for row in overlap:
            row.effective_to = closed_on

    @staticmethod
    def _close_superseded_allocation_rule(
        overlap: list[ECostAllocRule],
        *,
        effective_from: date,
    ) -> None:
        """Close the sole prior open rule for a scope; reject other overlaps."""
        if not overlap:
            return
        closable = (
            len(overlap) == 1
            and overlap[0].effective_to is None
            and overlap[0].effective_from < effective_from
        )
        if not closable:
            raise HTTPException(
                status_code=422,
                detail='分摊规则生效日期区间与既有版本真实交叉',
            )
        overlap[0].effective_to = effective_from - timedelta(days=1)

    @staticmethod
    @asynccontextmanager
    async def _config_lock(
        db: AsyncSession,
        lock_name: str,
    ) -> AsyncIterator[None]:
        """Pin GET_LOCK to a dedicated connection across the business commit."""
        bind = db.bind
        if bind is None:
            raise CostStateError('cost config lock requires a bound async engine')
        engine = bind.engine if isinstance(bind, AsyncConnection) else bind
        if not isinstance(engine, AsyncEngine):
            raise CostStateError('cost config lock requires an async engine')
        async with engine.connect() as lock_db:
            await CostDao.acquire_config_lock(lock_db, lock_name)
            try:
                yield
            finally:
                try:
                    await CostDao.release_config_lock(lock_db, lock_name)
                except Exception:
                    await lock_db.invalidate()
                    raise

    @classmethod
    async def list_recomputations(
        cls,
        db: AsyncSession,
        *,
        stat_month: str | None,
        energy_type: str | None,
        review_status: str | None,
        object_type: str | None,
        object_id: int | None,
        page_num: int,
        page_size: int,
    ) -> dict[str, object]:
        rows = await CostDao.list_recompute_records(
            db,
            stat_month=stat_month,
            energy_type=energy_type,
            review_status=review_status,
        )
        items: list[dict[str, object]] = []
        for row in rows:
            diff = cls._json_list(row.diff_summary_json)
            if object_type is not None or object_id is not None:
                matching = [
                    item
                    for item in diff
                    if (object_type is None or item.get('objectType') == object_type)
                    and (object_id is None or item.get('objectId') == object_id)
                ]
                if not matching:
                    continue
            items.append(cls._serialize_recompute_record(row, include_diff=False))
        total = len(items)
        start = (page_num - 1) * page_size
        return {
            'items': items[start : start + page_size],
            'total': total,
            'filters': {
                'statMonth': stat_month,
                'energyType': energy_type,
                'reviewStatus': review_status,
                'objectType': object_type,
                'objectId': object_id,
                'pageNum': page_num,
                'pageSize': page_size,
            },
        }

    @classmethod
    async def get_recomputation(
        cls,
        db: AsyncSession,
        recompute_id: int,
    ) -> dict[str, object]:
        record = await CostDao.get_recompute_record(db, recompute_id)
        if record is None:
            raise CostNotFoundError(f'recomputation not found: {recompute_id}')
        chain = await CostDao.load_recompute_version_chain(db, record)
        payload = cls._serialize_recompute_record(record, include_diff=True)
        payload['versionChain'] = cls._serialize_version_chain(chain)
        diff = cls._json_list(record.diff_summary_json)
        objects = sorted(
            {
                (str(item.get('objectType')), item.get('objectId'))
                for item in diff
                if item.get('objectType') in {'system', 'area', 'equipment'}
            },
            key=lambda item: (item[0], int(item[1] or 0)),
        )
        payload['traceLinks'] = [
            {
                'objectType': object_type,
                'objectId': object_id,
                'old': {
                    'statMonth': record.stat_month,
                    'objectType': object_type,
                    'objectId': object_id,
                    'energyType': record.energy_type_code,
                    'costVersion': f'v{record.old_cost_version}',
                },
                'new': {
                    'statMonth': record.stat_month,
                    'objectType': object_type,
                    'objectId': object_id,
                    'energyType': record.energy_type_code,
                    'costVersion': f'v{record.new_cost_version}',
                },
            }
            for object_type, object_id in objects
        ]
        return payload

    @classmethod
    async def recompute(
        cls,
        db: AsyncSession,
        request: object,
        *,
        operator: str,
    ) -> CostRecomputeResult:
        """Atomically insert a complete new month/media batch and frozen diff."""
        actor = cls._required_text(operator, 'operator')
        stat_month = cls._required_text(cls._request_value(request, 'stat_month'), 'stat_month')
        energy_type = cls._required_text(
            cls._request_value(request, 'energy_type'),
            'energy_type',
        )
        trigger_reason = cls._required_text(
            cls._request_value(request, 'trigger_reason'),
            'trigger_reason',
        )
        tariff_version = cls._optional_int(cls._request_value(request, 'tariff_version'))
        alloc_rule_version = cls._optional_int(cls._request_value(request, 'alloc_rule_version'))
        cls._month_bounds(stat_month)

        try:
            current_rows = await CostDao.lock_current_batch(db, stat_month, energy_type)
            if not current_rows:
                raise CostNotFoundError(f'current cost batch not found: {stat_month}/{energy_type}')
            pending_header = await CostDao.lock_pending_recompute(
                db,
                stat_month,
                energy_type,
            )
            if pending_header is not None or any(row.status == 'pendingRecompute' for row in current_rows):
                raise CostConflictError('该月/介质已有待复核成本重算')
            old_versions = {int(row.cost_version) for row in current_rows}
            if len(old_versions) != 1:
                raise CostStateError('current batch contains mixed cost versions')
            old_version = old_versions.pop()
            new_version = (await CostDao.max_batch_version(db, stat_month, energy_type)) + 1
            tariffs = cls._select_tariffs(
                await CostDao.load_tariffs(db),
                energy_type,
                stat_month,
                version_no=tariff_version,
            )
            alloc_rule = cls._select_alloc_rule(
                await CostDao.load_alloc_rules(db),
                stat_month,
                version_no=alloc_rule_version,
            )
            computed_at = await get_demo_now(db)
            object_codes = await CostDao.load_object_codes(db)

            new_rows: list[ECostRecord] = []
            diff_summary: list[dict[str, object]] = []
            for old_row in current_rows:
                source_snapshot = cls._json_load(old_row.source_stat_snapshot_json)
                calculation = calculate_cost(
                    CostCalculationInput(
                        object_type=old_row.object_type,
                        object_id=old_row.object_id,
                        stat_month=old_row.stat_month,
                        energy_type=old_row.energy_type_code,
                        cost_version=new_version,
                        usage_qty=Decimal(old_row.usage_qty or 0),
                        peak_qty=Decimal(old_row.peak_qty or 0),
                        flat_qty=Decimal(old_row.flat_qty or 0),
                        valley_qty=Decimal(old_row.valley_qty or 0),
                        tariffs=tariffs,
                        alloc_rule_snapshot=alloc_rule,
                        source_stat_snapshot=source_snapshot,
                    )
                )
                new_row = cls._record_from_calculation(
                    calculation,
                    status='pendingRecompute',
                    is_current=False,
                    computed_by=actor,
                    computed_at=computed_at,
                )
                new_rows.append(new_row)
                old_calculation = cls._calculation_from_record(old_row)
                diff_summary.extend(
                    {
                        'objectType': old_row.object_type,
                        'objectId': old_row.object_id,
                        'objectCode': cls._object_code(old_row, object_codes),
                        'metric': item.metric,
                        'oldValue': float(item.old_value),
                        'newValue': float(item.new_value),
                        'deltaValue': float(item.delta_value),
                        'deltaPct': (float(item.delta_pct) if item.delta_pct is not None else None),
                        'oldSignature': old_row.signature,
                        'newSignature': new_row.signature,
                    }
                    for item in build_cost_diff(old_calculation, calculation)
                )
            # New versions enter non-current first so MySQL's current_guard is never violated.
            await CostDao.insert_versions(db, new_rows)
            for row in current_rows:
                row.is_current = False
            await db.flush()
            for row in new_rows:
                row.is_current = True

            recompute_record = ECostRecomputeRecord(
                period_key=f'{stat_month}:{energy_type}',
                stat_month=stat_month,
                energy_type_code=energy_type,
                scope='completeMonthEnergyBatch',
                old_cost_version=old_version,
                new_cost_version=new_version,
                trigger_reason=trigger_reason,
                trigger_type='manual',
                triggered_by=actor,
                triggered_at=computed_at,
                tariff_snapshot_json=new_rows[0].tariff_snapshot_json,
                alloc_rule_snapshot_json=cls._json_dump(alloc_rule),
                diff_summary_json=cls._json_dump(diff_summary),
                review_status='pending',
            )
            await CostDao.insert_recompute_record(db, recompute_record)
            await db.flush()
            recompute_id = int(recompute_record.recompute_id)
            await append_cost_operation_audit(
                db,
                title='成本重算发起',
                operator=actor,
                action='recompute',
                target='/cost/recomputations',
                before={
                    'statMonth': stat_month,
                    'energyType': energy_type,
                    'costVersion': f'v{old_version}',
                },
                after={
                    'recomputeId': recompute_id,
                    'costVersion': f'v{new_version}',
                    'reviewStatus': 'pending',
                },
                reason=trigger_reason,
                occurred_at=computed_at,
            )
            await db.commit()
        except Exception:
            await db.rollback()
            raise

        return CostRecomputeResult(
            recompute_id=recompute_id,
            stat_month=stat_month,
            energy_type=energy_type,
            old_cost_version=old_version,
            new_cost_version=new_version,
            review_status='pending',
            current_cost_version=new_version,
            affected_object_count=len(new_rows),
            diff_summary=tuple(diff_summary),
        )

    @classmethod
    async def review(  # noqa: PLR0912 - keep the lock/revalidate transaction visible
        cls,
        db: AsyncSession,
        recompute_id: int,
        action: str,
        remark: str,
        *,
        reviewer: str,
    ) -> CostRecomputeResult:
        """Approve vN or void it and atomically restore the previous current batch."""
        reviewer_name = cls._required_text(reviewer, 'reviewer')
        review_remark = cls._required_text(remark, 'remark')
        if action not in {'approve', 'reject'}:
            raise ValueError('action must be approve or reject')

        try:
            header = await CostDao.get_recompute_record(db, int(recompute_id))
            if header is None:
                raise CostNotFoundError(f'recomputation not found: {recompute_id}')
            chain = await CostDao.lock_version_chain(db, header)
            record = await CostDao.lock_recompute_record(db, int(recompute_id))
            if record is None:
                raise CostNotFoundError(f'recomputation not found: {recompute_id}')
            if (
                record.stat_month != header.stat_month
                or record.energy_type_code != header.energy_type_code
                or record.old_cost_version != header.old_cost_version
                or record.new_cost_version != header.new_cost_version
            ):
                raise CostConflictError('成本重算版本链已变化，请刷新后重试')
            if record.review_status != 'pending':
                raise CostConflictError('recomputation has already been reviewed')
            old_rows = [row for row in chain if row.cost_version == record.old_cost_version]
            new_rows = [row for row in chain if row.cost_version == record.new_cost_version]
            if not old_rows or len(old_rows) != len(new_rows):
                raise CostConflictError('recomputation version chain is incomplete')
            if any(row.status != 'pendingRecompute' or not row.is_current for row in new_rows):
                raise CostConflictError('new cost batch is not pending/current')

            reviewed_at = await get_demo_now(db)
            if action == 'approve':
                for row in new_rows:
                    row.status = 'reviewed'
                    row.reviewed_at = reviewed_at
                review_status = 'approved'
            else:
                for row in new_rows:
                    row.is_current = False
                    row.status = 'void'
                    row.reviewed_at = reviewed_at
                await db.flush()
                for row in old_rows:
                    row.is_current = True
                review_status = 'rejected'

            record.review_status = review_status
            record.reviewed_by = reviewer_name
            record.reviewed_at = reviewed_at
            record.review_remark = review_remark
            await db.flush()
            diff_summary = tuple(cls._json_load(record.diff_summary_json))
            result_values = (
                int(record.recompute_id),
                record.stat_month,
                record.energy_type_code,
                int(record.old_cost_version),
                int(record.new_cost_version),
                len(new_rows),
            )
            current_cost_version = (
                int(record.new_cost_version)
                if action == 'approve'
                else int(record.old_cost_version)
            )
            await append_cost_operation_audit(
                db,
                title='成本重算复核',
                operator=reviewer_name,
                action='review',
                target=f'/cost/recomputations/{recompute_id}/review',
                before={
                    'reviewStatus': 'pending',
                    'currentCostVersion': f'v{record.new_cost_version}',
                },
                after={
                    'reviewStatus': review_status,
                    'currentCostVersion': f'v{current_cost_version}',
                },
                reason=review_remark,
                occurred_at=reviewed_at,
            )
            await db.commit()
        except Exception:
            await db.rollback()
            raise

        return CostRecomputeResult(
            recompute_id=result_values[0],
            stat_month=result_values[1],
            energy_type=result_values[2],
            old_cost_version=result_values[3],
            new_cost_version=result_values[4],
            review_status=review_status,
            current_cost_version=current_cost_version,
            affected_object_count=result_values[5],
            diff_summary=diff_summary,
        )

    @classmethod
    async def rebuild_all(cls, db: AsyncSession) -> CostBatchResult:
        """Compatibility entry point: initialize only, never overwrite a version."""
        return await cls.initialize_v1(db, computed_by='pipeline-bootstrap')

    @classmethod
    def _validate_initialized(
        cls,
        stats: list[EStatMonth],
        rows: list[ECostRecord],
    ) -> CostBatchResult:
        expected = {(row.object_type, row.object_id, row.stat_month, row.energy_type_code) for row in stats}
        current = [row for row in rows if row.is_current]
        actual = {(row.object_type, row.object_id, row.stat_month, row.energy_type_code) for row in current}
        if len(current) != len(actual) or actual != expected:
            raise CostStateError('current cost versions do not cover every e_stat_month key')
        if any(
            not row.formula_version
            or not row.tariff_snapshot_json
            or not row.alloc_rule_snapshot_json
            or not row.source_stat_snapshot_json
            or not row.signature
            or not row.computed_by
            for row in rows
        ):
            raise CostStateError('cost version snapshot evidence is incomplete')
        for row in rows:
            try:
                calculation = cls._calculation_from_record(row)
                expected_signature = build_cost_signature(calculation)
            except (KeyError, TypeError, ValueError, json.JSONDecodeError) as exc:
                raise CostStateError(f'cost version snapshot shape is invalid: row {row.id}') from exc
            if row.signature != expected_signature:
                raise CostStateError(f'cost version signature mismatch: row {row.id}')
        return CostBatchResult(
            max((int(row.cost_version) for row in current), default=1),
            len(current),
            dict(Counter(row.object_type for row in current)),
            False,
        )

    @classmethod
    def _record_from_stat(
        cls,
        stat: EStatMonth,
        tariffs: tuple[TariffSnapshot, ...],
        alloc_rule: Mapping[str, object],
        *,
        cost_version: int,
        status: str,
        is_current: bool,
        computed_by: str,
        computed_at: datetime,
    ) -> ECostRecord:
        source_snapshot = {
            'table': 'e_stat_month',
            'recordId': stat.id,
            'version': int(stat.version_no or 1),
            'qualifiedUsageQty': Decimal(stat.total_value or 0),
            'quality': {'coverageRatio': stat.coverage_ratio},
        }
        calculation = calculate_cost(
            CostCalculationInput(
                object_type=stat.object_type,
                object_id=stat.object_id,
                stat_month=stat.stat_month,
                energy_type=stat.energy_type_code,
                cost_version=cost_version,
                usage_qty=Decimal(stat.total_value or 0),
                peak_qty=Decimal(stat.peak_value or 0),
                flat_qty=Decimal(stat.flat_value or 0),
                valley_qty=Decimal(stat.valley_value or 0),
                tariffs=tariffs,
                alloc_rule_snapshot=alloc_rule,
                source_stat_snapshot=source_snapshot,
            )
        )
        return cls._record_from_calculation(
            calculation,
            status=status,
            is_current=is_current,
            computed_by=computed_by,
            computed_at=computed_at,
            reviewed_at=computed_at if status == 'reviewed' else None,
        )

    @classmethod
    def _record_from_calculation(
        cls,
        result: CostCalculationResult,
        *,
        status: str,
        is_current: bool,
        computed_by: str,
        computed_at: datetime,
        reviewed_at: datetime | None = None,
    ) -> ECostRecord:
        tariffs = [cls._tariff_snapshot_payload(item) for item in result.tariff_snapshot]
        tariff_version = '|'.join(f'{item.tou_period}_v{item.version_no}' for item in result.tariff_snapshot)
        alloc_name = str(result.alloc_rule_snapshot.get('ruleName', 'ALLOC'))
        alloc_version = int(result.alloc_rule_snapshot.get('versionNo', 1))
        return ECostRecord(
            object_type=result.object_type,
            object_id=result.object_id,
            stat_month=result.stat_month,
            energy_type_code=result.energy_type,
            cost_version=result.cost_version,
            is_current=is_current,
            usage_qty=result.usage_qty,
            peak_qty=result.peak_qty,
            flat_qty=result.flat_qty,
            valley_qty=result.valley_qty,
            peak_cost=result.peak_cost,
            flat_cost=result.flat_cost,
            valley_cost=result.valley_cost,
            total_cost=result.total_cost,
            tariff_version_no=tariff_version,
            alloc_rule_version_no=f'{alloc_name}-v{alloc_version}',
            formula_version=result.formula_version,
            tariff_snapshot_json=cls._json_dump(tariffs),
            alloc_rule_snapshot_json=cls._json_dump(result.alloc_rule_snapshot),
            source_stat_snapshot_json=cls._json_dump(result.source_stat_snapshot),
            status=status,
            reviewed_at=reviewed_at,
            signature=build_cost_signature(result),
            computed_by=computed_by,
            computed_at=computed_at,
        )

    @classmethod
    def _calculation_from_record(cls, row: ECostRecord) -> CostCalculationResult:
        tariff_payload = cls._json_load(row.tariff_snapshot_json)
        alloc_payload = cls._json_load(row.alloc_rule_snapshot_json)
        source_payload = cls._json_load(row.source_stat_snapshot_json)
        cls._validate_snapshot_shapes(tariff_payload, alloc_payload, source_payload)
        return CostCalculationResult(
            object_type=row.object_type,
            object_id=row.object_id,
            stat_month=row.stat_month,
            energy_type=row.energy_type_code,
            cost_version=int(row.cost_version),
            usage_qty=Decimal(row.usage_qty or 0),
            peak_qty=Decimal(row.peak_qty or 0),
            flat_qty=Decimal(row.flat_qty or 0),
            valley_qty=Decimal(row.valley_qty or 0),
            peak_cost=Decimal(row.peak_cost or 0),
            flat_cost=Decimal(row.flat_cost or 0),
            valley_cost=Decimal(row.valley_cost or 0),
            total_cost=Decimal(row.total_cost or 0),
            formula_version=row.formula_version,
            tariff_snapshot=tuple(cls._tariff_snapshot_from_payload(item) for item in tariff_payload),
            alloc_rule_snapshot=alloc_payload,
            source_stat_snapshot=source_payload,
            is_current=bool(row.is_current),
            status=row.status,
        )

    @staticmethod
    def _validate_snapshot_shapes(
        tariff_payload: object,
        alloc_payload: object,
        source_payload: object,
    ) -> None:
        if not isinstance(tariff_payload, list) or not tariff_payload:
            raise ValueError('tariff snapshot must be a non-empty list')
        if any(not isinstance(item, Mapping) or not _TARIFF_SNAPSHOT_FIELDS.issubset(item) for item in tariff_payload):
            raise ValueError('tariff snapshot fields are incomplete')
        if not isinstance(alloc_payload, Mapping) or not _ALLOC_SNAPSHOT_FIELDS.issubset(alloc_payload):
            raise ValueError('allocation snapshot fields are incomplete')
        if (
            alloc_payload['applicationStatus'] != 'notApplied'
            or alloc_payload['allocationDetails'] != []
            or alloc_payload['message'] != '本对象无共享分摊'
            or not isinstance(alloc_payload['config'], Mapping)
        ):
            raise ValueError('allocation snapshot notApplied evidence is invalid')
        if not isinstance(source_payload, Mapping) or not _SOURCE_SNAPSHOT_FIELDS.issubset(source_payload):
            raise ValueError('source statistic snapshot fields are incomplete')
        quality = source_payload['quality']
        if (
            source_payload['table'] != 'e_stat_month'
            or not isinstance(quality, Mapping)
            or 'coverageRatio' not in quality
        ):
            raise ValueError('source statistic snapshot evidence is invalid')

    @staticmethod
    def _object_code(
        row: ECostRecord,
        object_codes: Mapping[tuple[str, int], str],
    ) -> str:
        if row.object_type == 'system':
            return 'SYSTEM'
        if row.object_id is None:
            raise CostStateError(f'cost object id is missing: row {row.id}')
        code = object_codes.get((row.object_type, int(row.object_id)))
        if code is None:
            raise CostStateError(f'cost object code is missing: {row.object_type}/{row.object_id}')
        return code

    @classmethod
    def serialize_recompute_result(
        cls,
        result: CostRecomputeResult,
    ) -> dict[str, object]:
        """Public write response; all values originate from persisted server state."""
        return {
            'recompute': {
                'recomputeId': result.recompute_id,
                'statMonth': result.stat_month,
                'energyType': result.energy_type,
                'oldCostVersion': f'v{result.old_cost_version}',
                'newCostVersion': f'v{result.new_cost_version}',
                'reviewStatus': result.review_status,
                'diffSummary': list(result.diff_summary),
            },
            'currentCostVersion': f'v{result.current_cost_version}',
            'affectedObjectCount': result.affected_object_count,
        }

    @classmethod
    def _serialize_tariff(cls, row: ETariffVersion) -> dict[str, object]:
        return {
            'tariffId': int(row.tariff_id),
            'energyType': row.energy_type_code,
            'touPeriod': cls._api_tou_period(row.tou_period),
            'price': float(row.price),
            'currency': row.currency or 'CNY',
            'effectiveFrom': row.effective_from,
            'effectiveTo': row.effective_to,
            'versionNo': int(row.version_no or 1),
            'remark': row.remark,
            'createBy': row.create_by,
            'createTime': row.create_time,
        }

    @classmethod
    def _serialize_allocation_rule(cls, row: ECostAllocRule) -> dict[str, object]:
        config = cls._json_load(row.config_json) if row.config_json else {}
        allocations = config.get('allocations') if isinstance(config, Mapping) else None
        affected_meters = (
            [item.get('objectId') for item in allocations if isinstance(item, Mapping)]
            if isinstance(allocations, list)
            else []
        )
        return {
            'ruleId': int(row.rule_id),
            'ruleName': row.rule_name,
            'scope': row.scope,
            'method': row.method,
            'config': config,
            'effectiveFrom': row.effective_from,
            'effectiveTo': row.effective_to,
            'versionNo': int(row.version_no or 1),
            'createBy': row.create_by,
            'createTime': row.create_time,
            'affectedMeters': affected_meters,
        }

    @classmethod
    def _serialize_recompute_record(
        cls,
        row: ECostRecomputeRecord,
        *,
        include_diff: bool,
    ) -> dict[str, object]:
        diff = cls._json_list(row.diff_summary_json)
        objects = {(item.get('objectType'), item.get('objectId')) for item in diff}
        total_delta = sum(
            (Decimal(str(item.get('deltaValue') or 0)) for item in diff if item.get('metric') == 'totalCost'),
            Decimal('0'),
        )
        payload: dict[str, object] = {
            'recomputeId': int(row.recompute_id),
            'periodKey': row.period_key,
            'statMonth': row.stat_month,
            'energyType': row.energy_type_code,
            'scope': row.scope,
            'oldCostVersion': f'v{row.old_cost_version}',
            'newCostVersion': f'v{row.new_cost_version}',
            'triggerReason': row.trigger_reason,
            'triggerType': row.trigger_type,
            'triggeredBy': row.triggered_by,
            'triggeredAt': row.triggered_at,
            'totalDelta': float(total_delta),
            'affectedObjectCount': len(objects),
            'reviewStatus': row.review_status,
            'reviewedBy': row.reviewed_by,
            'reviewedAt': row.reviewed_at,
            'reviewRemark': row.review_remark,
        }
        if include_diff:
            payload.update(
                {
                    'tariffSnapshot': cls._json_load(row.tariff_snapshot_json),
                    'allocRuleSnapshot': cls._json_load(row.alloc_rule_snapshot_json),
                    'diffSummary': diff,
                }
            )
        return payload

    @classmethod
    def _serialize_version_chain(
        cls,
        rows: list[ECostRecord],
    ) -> list[dict[str, object]]:
        grouped: dict[int, list[ECostRecord]] = {}
        for row in rows:
            grouped.setdefault(int(row.cost_version), []).append(row)
        return [
            {
                'costVersion': f'v{version}',
                'isCurrent': all(bool(row.is_current) for row in version_rows),
                'statuses': sorted({row.status for row in version_rows}),
                'affectedObjectCount': len(version_rows),
                'tariffSnapshot': cls._json_load(version_rows[0].tariff_snapshot_json),
                'allocRuleSnapshot': cls._json_load(version_rows[0].alloc_rule_snapshot_json),
                'sourceStatSnapshots': [
                    {
                        'objectType': row.object_type,
                        'objectId': row.object_id,
                        'snapshot': cls._json_load(row.source_stat_snapshot_json),
                    }
                    for row in version_rows
                ],
            }
            for version, version_rows in sorted(grouped.items())
        ]

    @classmethod
    def _select_tariffs(
        cls,
        tariffs: list[ETariffVersion],
        energy_type: str,
        stat_month: str,
        *,
        version_no: int | None = None,
    ) -> tuple[TariffSnapshot, ...]:
        month_start, month_end = cls._month_bounds(stat_month)
        active = [
            row
            for row in tariffs
            if row.energy_type_code == energy_type
            and row.effective_from <= month_end
            and (row.effective_to is None or row.effective_to >= month_start)
            and (version_no is None or int(row.version_no or 1) == version_no)
        ]
        if not active:
            suffix = f' v{version_no}' if version_no is not None else ''
            raise ValueError(f'no applicable tariff{suffix}: {stat_month}/{energy_type}')
        selected = sorted(
            active,
            key=lambda row: (
                row.tou_period,
                row.effective_from,
                int(row.version_no or 1),
                int(row.tariff_id or 0),
            ),
        )
        if version_no is not None:
            required_periods = {'peak', 'flat', 'valley'} if energy_type == 'electricity' else {'flat_only'}
            actual_periods = {row.tou_period for row in selected}
            missing = required_periods - actual_periods
            if missing:
                expected = ', '.join(sorted(required_periods))
                raise MissingTariffError(f'explicit tariff version requires buckets: {expected}')
            for tou_period in actual_periods:
                if sum(row.tou_period == tou_period for row in selected) > 1:
                    raise AmbiguousTariffError(f'multiple active tariffs for {tou_period}')
        return tuple(
            TariffSnapshot(
                tariff_id=int(row.tariff_id),
                energy_type=row.energy_type_code,
                tou_period=row.tou_period,
                price=Decimal(row.price),
                currency=row.currency or 'CNY',
                effective_from=row.effective_from,
                effective_to=row.effective_to,
                version_no=int(row.version_no or 1),
            )
            for row in selected
        )

    @classmethod
    def _select_alloc_rule(
        cls,
        rules: list[ECostAllocRule],
        stat_month: str,
        *,
        version_no: int | None = None,
    ) -> Mapping[str, object]:
        month_start, month_end = cls._month_bounds(stat_month)
        active = [
            row
            for row in rules
            if row.effective_from <= month_end
            and (row.effective_to is None or row.effective_to >= month_start)
            and (version_no is None or int(row.version_no or 1) == version_no)
        ]
        if not active:
            suffix = f' v{version_no}' if version_no is not None else ''
            raise ValueError(f'no applicable allocation rule{suffix}: {stat_month}')
        selected = max(
            active,
            key=lambda row: (
                row.effective_from,
                int(row.version_no or 1),
                int(row.rule_id or 0),
            ),
        )
        config = cls._json_load(selected.config_json) if selected.config_json else {}
        return {
            'ruleId': int(selected.rule_id),
            'ruleName': selected.rule_name,
            'scope': selected.scope,
            'method': selected.method,
            'config': config,
            'effectiveFrom': selected.effective_from,
            'effectiveTo': selected.effective_to,
            'versionNo': int(selected.version_no or 1),
            # Demo statistics already contain area/system rows and no shared-meter
            # amount is allocated here. Freeze the real rule without implying use.
            'applicationStatus': 'notApplied',
            'allocationDetails': [],
            'message': '本对象无共享分摊',
        }

    @staticmethod
    def _request_value(request: object, field: str) -> object:
        if isinstance(request, Mapping):
            return request.get(field)
        return getattr(request, field, None)

    @staticmethod
    def _request_mapping(value: object) -> dict[str, object]:
        if isinstance(value, Mapping):
            return dict(value)
        model_dump = getattr(value, 'model_dump', None)
        if callable(model_dump):
            dumped = model_dump(by_alias=True)
            if isinstance(dumped, Mapping):
                return dict(dumped)
        raise ValueError('request object must be a mapping')

    @staticmethod
    def _required_text(value: object, field: str) -> str:
        if not isinstance(value, str) or not value.strip():
            raise ValueError(f'{field} is required')
        return value.strip()

    @staticmethod
    def _optional_int(value: object) -> int | None:
        if value is None:
            return None
        parsed = int(value)
        if parsed < 1:
            raise ValueError('version must be >= 1')
        return parsed

    @staticmethod
    def _month_bounds(stat_month: str) -> tuple[date, date]:
        if len(stat_month) != _STAT_MONTH_LENGTH or stat_month[4] != '-':
            raise ValueError('stat_month must be YYYY-MM')
        try:
            start = date(int(stat_month[:4]), int(stat_month[5:]), 1)
        except ValueError:
            raise ValueError('stat_month must be YYYY-MM') from None
        return start, date(start.year, start.month, monthrange(start.year, start.month)[1])

    @staticmethod
    def _json_dump(value: object) -> str:
        return json.dumps(
            CostService._json_value(value),
            ensure_ascii=False,
            separators=(',', ':'),
            sort_keys=True,
        )

    @staticmethod
    def _json_load(value: str | None) -> Any:
        if not value:
            return {}
        return json.loads(value)

    @staticmethod
    def _json_list(value: str | None) -> list[dict[str, object]]:
        parsed = CostService._json_load(value)
        if not isinstance(parsed, list) or any(not isinstance(item, Mapping) for item in parsed):
            raise CostStateError('diff summary snapshot must be a list of objects')
        return [dict(item) for item in parsed]

    @staticmethod
    def _json_value(value: object) -> object:
        if isinstance(value, Mapping):
            return {
                str(key): CostService._json_value(item)
                for key, item in sorted(value.items(), key=lambda pair: str(pair[0]))
            }
        if isinstance(value, (list, tuple)):
            return [CostService._json_value(item) for item in value]
        if isinstance(value, Decimal):
            return format(value, 'f')
        if isinstance(value, datetime):
            return value.isoformat(timespec='seconds')
        if isinstance(value, date):
            return value.isoformat()
        return value

    @staticmethod
    def _tariff_snapshot_payload(item: TariffSnapshot) -> dict[str, object]:
        return {
            'tariffId': item.tariff_id,
            'energyType': item.energy_type,
            'touPeriod': CostService._api_tou_period(item.tou_period),
            'price': item.price,
            'currency': item.currency,
            'effectiveFrom': item.effective_from,
            'effectiveTo': item.effective_to,
            'versionNo': item.version_no,
        }

    @staticmethod
    def _api_tou_period(value: str) -> str:
        return 'flatOnly' if value == 'flat_only' else value

    @staticmethod
    def _tariff_snapshot_from_payload(value: Mapping[str, object]) -> TariffSnapshot:
        effective_to = value.get('effectiveTo')
        return TariffSnapshot(
            tariff_id=int(value['tariffId']),
            energy_type=str(value['energyType']),
            tou_period=(
                'flat_only'
                if str(value['touPeriod']) == 'flatOnly'
                else str(value['touPeriod'])
            ),
            price=Decimal(str(value['price'])),
            currency=str(value['currency']),
            effective_from=date.fromisoformat(str(value['effectiveFrom'])),
            effective_to=(date.fromisoformat(str(effective_to)) if effective_to else None),
            version_no=int(value['versionNo']),
        )

    # Legacy helpers retained for existing FX-12 unit tests and Task 3 consumers.
    @staticmethod
    def _bucket_prices(tariff_pack: dict[str, Decimal]) -> dict[str, Decimal]:
        flat = tariff_pack.get('flat', tariff_pack.get('flat_only', _ZERO))
        return {
            'peak': tariff_pack.get('peak', flat),
            'flat': flat,
            'valley': tariff_pack.get('valley', flat),
        }

    @classmethod
    async def _load_tariffs(cls, db: AsyncSession) -> list[ETariffVersion]:
        return await CostDao.load_tariffs(db)

    @classmethod
    async def _load_alloc_rule_version(cls, db: AsyncSession) -> str:
        rules = await CostDao.load_alloc_rules(db)
        if not rules:
            return 'ALLOC-N/A'
        selected = max(rules, key=lambda row: (row.effective_from, row.version_no or 1))
        return f'{selected.rule_name}-v{selected.version_no or 1}'

    @classmethod
    def _pick_tariff_pack(
        cls,
        tariffs: list[ETariffVersion],
        energy_type: str,
        stat_month: str,
    ) -> dict[str, Decimal]:
        return {item.tou_period: item.price for item in cls._select_tariffs(tariffs, energy_type, stat_month)}

    @classmethod
    def _tariff_pack_version_no(
        cls,
        tariffs: list[ETariffVersion],
        energy_type: str,
        stat_month: str,
    ) -> str:
        selected = cls._select_tariffs(tariffs, energy_type, stat_month)
        return '|'.join(f'{item.tou_period}_v{item.version_no}' for item in selected)

    @staticmethod
    def _month_last_day(stat_month: str) -> date:
        return CostService._month_bounds(stat_month)[1]


def build_rule_input_json(record: ECostRecord) -> str:
    """Serialize stable cost fields for the existing R10 rule evidence."""
    return json.dumps(
        {
            'object_type': record.object_type,
            'object_id': record.object_id,
            'stat_month': record.stat_month,
            'energy_type_code': record.energy_type_code,
            'total_cost': float(record.total_cost or 0),
            'signature': record.signature,
        },
        ensure_ascii=False,
    )
