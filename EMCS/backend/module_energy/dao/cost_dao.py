"""Persistence primitives for immutable cost versions (REQ-051/055/062/073/074)."""

from datetime import date, datetime
from typing import Any

from sqlalchemy import func, or_, select, text
from sqlalchemy.ext.asyncio import AsyncConnection, AsyncSession

from module_energy.entity.do.area_do import EArea
from module_energy.entity.do.cost_alloc_rule_do import ECostAllocRule
from module_energy.entity.do.cost_recompute_record_do import ECostRecomputeRecord
from module_energy.entity.do.cost_record_do import ECostRecord
from module_energy.entity.do.equipment_do import EEquipment
from module_energy.entity.do.meter_point_do import EMeterPoint
from module_energy.entity.do.stat_day_do import EStatDay
from module_energy.entity.do.stat_hour_do import EStatHour
from module_energy.entity.do.stat_month_do import EStatMonth
from module_energy.entity.do.tariff_version_do import ETariffVersion


class CostDao:
    @staticmethod
    async def acquire_config_lock(
        db: AsyncConnection | AsyncSession,
        lock_name: str,
        timeout_seconds: int = 5,
    ) -> None:
        """Serialize version allocation without adding a project-wide migration."""
        acquired = (
            await db.execute(
                text('SELECT GET_LOCK(:lock_name, :timeout_seconds)'),
                {'lock_name': lock_name, 'timeout_seconds': timeout_seconds},
            )
        ).scalar_one()
        if int(acquired or 0) != 1:
            raise TimeoutError('成本配置版本正在变更，请稍后重试')

    @staticmethod
    async def release_config_lock(
        db: AsyncConnection | AsyncSession,
        lock_name: str,
    ) -> None:
        released = (
            await db.execute(
                text('SELECT RELEASE_LOCK(:lock_name)'),
                {'lock_name': lock_name},
            )
        ).scalar_one()
        if int(released or 0) != 1:
            raise RuntimeError(f'failed to release cost config lock: {lock_name}')

    @staticmethod
    async def list_tariff_versions(
        db: AsyncSession,
        *,
        energy_type: str | None = None,
        effective_on: date | None = None,
    ) -> list[ETariffVersion]:
        statement = select(ETariffVersion)
        if energy_type is not None:
            statement = statement.where(ETariffVersion.energy_type_code == energy_type)
        if effective_on is not None:
            statement = statement.where(
                ETariffVersion.effective_from <= effective_on,
                or_(
                    ETariffVersion.effective_to.is_(None),
                    ETariffVersion.effective_to >= effective_on,
                ),
            )
        statement = statement.order_by(
            ETariffVersion.energy_type_code,
            ETariffVersion.tou_period,
            ETariffVersion.version_no,
            ETariffVersion.effective_from,
            ETariffVersion.tariff_id,
        )
        return list((await db.execute(statement)).scalars())

    @staticmethod
    async def lock_overlapping_tariffs(
        db: AsyncSession,
        *,
        energy_type: str,
        periods: tuple[str, ...],
        effective_from: date,
        effective_to: date | None,
    ) -> list[ETariffVersion]:
        statement = (
            select(ETariffVersion)
            .where(
                ETariffVersion.energy_type_code == energy_type,
                ETariffVersion.tou_period.in_(periods),
                or_(
                    ETariffVersion.effective_to.is_(None),
                    ETariffVersion.effective_to >= effective_from,
                ),
            )
            .order_by(ETariffVersion.tariff_id)
            .with_for_update()
        )
        if effective_to is not None:
            statement = statement.where(ETariffVersion.effective_from <= effective_to)
        return list((await db.execute(statement)).scalars())

    @staticmethod
    async def max_tariff_version(db: AsyncSession, energy_type: str) -> int:
        value = (
            await db.execute(
                select(func.max(ETariffVersion.version_no)).where(ETariffVersion.energy_type_code == energy_type)
            )
        ).scalar_one()
        return int(value or 0)

    @staticmethod
    async def insert_tariff_versions(
        db: AsyncSession,
        rows: list[ETariffVersion],
    ) -> None:
        db.add_all(rows)
        await db.flush()

    @staticmethod
    async def list_affected_stat_months(
        db: AsyncSession,
        *,
        energy_type: str,
        effective_from: date,
        effective_to: date | None,
    ) -> list[str]:
        start_month = effective_from.strftime('%Y-%m')
        statement = select(EStatMonth.stat_month).where(
            EStatMonth.energy_type_code == energy_type,
            EStatMonth.stat_month >= start_month,
        )
        if effective_to is not None:
            statement = statement.where(EStatMonth.stat_month <= effective_to.strftime('%Y-%m'))
        statement = statement.distinct().order_by(EStatMonth.stat_month)
        return list((await db.execute(statement)).scalars())

    @staticmethod
    async def list_allocation_rules(
        db: AsyncSession,
        *,
        scope: str | None = None,
        effective_on: date | None = None,
    ) -> list[ECostAllocRule]:
        statement = select(ECostAllocRule)
        if scope is not None:
            statement = statement.where(ECostAllocRule.scope == scope)
        if effective_on is not None:
            statement = statement.where(
                ECostAllocRule.effective_from <= effective_on,
                or_(
                    ECostAllocRule.effective_to.is_(None),
                    ECostAllocRule.effective_to >= effective_on,
                ),
            )
        statement = statement.order_by(
            ECostAllocRule.scope,
            ECostAllocRule.version_no,
            ECostAllocRule.effective_from,
            ECostAllocRule.rule_id,
        )
        return list((await db.execute(statement)).scalars())

    @staticmethod
    async def lock_overlapping_allocation_rules(
        db: AsyncSession,
        *,
        scope: str,
        effective_from: date,
        effective_to: date | None,
    ) -> list[ECostAllocRule]:
        statement = (
            select(ECostAllocRule)
            .where(
                ECostAllocRule.scope == scope,
                or_(
                    ECostAllocRule.effective_to.is_(None),
                    ECostAllocRule.effective_to >= effective_from,
                ),
            )
            .order_by(ECostAllocRule.rule_id)
            .with_for_update()
        )
        if effective_to is not None:
            statement = statement.where(ECostAllocRule.effective_from <= effective_to)
        return list((await db.execute(statement)).scalars())

    @staticmethod
    async def max_allocation_version(db: AsyncSession, scope: str) -> int:
        value = (
            await db.execute(select(func.max(ECostAllocRule.version_no)).where(ECostAllocRule.scope == scope))
        ).scalar_one()
        return int(value or 0)

    @staticmethod
    async def insert_allocation_rule(
        db: AsyncSession,
        row: ECostAllocRule,
    ) -> None:
        db.add(row)
        await db.flush()

    @staticmethod
    async def count_versions(db: AsyncSession) -> int:
        value = (await db.execute(select(func.count()).select_from(ECostRecord))).scalar_one()
        return int(value)

    @staticmethod
    async def load_stats(db: AsyncSession) -> list[EStatMonth]:
        return list(
            (
                await db.execute(
                    select(EStatMonth).order_by(
                        EStatMonth.stat_month,
                        EStatMonth.energy_type_code,
                        EStatMonth.object_type,
                        EStatMonth.object_id,
                    )
                )
            ).scalars()
        )

    @staticmethod
    async def load_all_cost_rows(db: AsyncSession) -> list[ECostRecord]:
        statement = select(ECostRecord).order_by(ECostRecord.id)
        return list((await db.execute(statement)).scalars())

    @staticmethod
    async def list_current_query_rows(
        db: AsyncSession,
        *,
        energy_type: str,
        stat_month: str | None = None,
    ) -> list[ECostRecord]:
        """REQ-051/053: immutable page rows; history never enters page totals."""
        statement = select(ECostRecord).where(
            ECostRecord.energy_type_code == energy_type,
            ECostRecord.is_current.is_(True),
            ECostRecord.status != 'void',
        )
        if stat_month is not None:
            statement = statement.where(ECostRecord.stat_month == stat_month)
        statement = statement.order_by(
            ECostRecord.stat_month,
            ECostRecord.object_type,
            ECostRecord.normalized_object_id,
        )
        return list((await db.execute(statement)).scalars())

    @staticmethod
    async def get_cost_trace_row(
        db: AsyncSession,
        *,
        stat_month: str,
        object_type: str,
        object_id: int | None,
        energy_type: str,
        cost_version: int | None,
    ) -> ECostRecord | None:
        normalized_object_id = 0 if object_type == 'system' else object_id
        statement = select(ECostRecord).where(
            ECostRecord.stat_month == stat_month,
            ECostRecord.object_type == object_type,
            ECostRecord.normalized_object_id == normalized_object_id,
            ECostRecord.energy_type_code == energy_type,
        )
        if cost_version is None:
            statement = statement.where(
                ECostRecord.is_current.is_(True),
                ECostRecord.status != 'void',
            )
        else:
            statement = statement.where(ECostRecord.cost_version == cost_version)
        return (await db.execute(statement)).scalar_one_or_none()

    @staticmethod
    async def load_month_data_bounds(
        db: AsyncSession,
        *,
        energy_type: str,
    ) -> dict[str, tuple[date, date]]:
        rows = (
            await db.execute(
                select(
                    func.date_format(EStatDay.stat_date, '%Y-%m').label('stat_month'),
                    func.min(EStatDay.stat_date).label('data_start'),
                    func.max(EStatDay.stat_date).label('data_end'),
                )
                .where(EStatDay.energy_type_code == energy_type)
                .group_by(func.date_format(EStatDay.stat_date, '%Y-%m'))
                .order_by(func.date_format(EStatDay.stat_date, '%Y-%m'))
            )
        ).all()
        return {
            str(row.stat_month): (row.data_start, row.data_end)
            for row in rows
        }

    @staticmethod
    async def load_cost_object_catalog(
        db: AsyncSession,
    ) -> tuple[list[EArea], list[EEquipment]]:
        areas = list((await db.execute(select(EArea).order_by(EArea.area_id))).scalars())
        equipment = list(
            (await db.execute(select(EEquipment).order_by(EEquipment.equipment_id))).scalars()
        )
        return areas, equipment

    @staticmethod
    async def load_peak_window_rows(
        db: AsyncSession,
        *,
        energy_type: str,
        period_start: datetime,
        period_end: datetime,
        area_id: int | None,
        limit: int = 5,
    ) -> tuple[list[Any], dict[int, list[int]]]:
        """REQ-052: highest real equipment-hour loads plus their source points."""
        statement = (
            select(EStatHour, EEquipment)
            .join(
                EEquipment,
                EStatHour.object_id == EEquipment.equipment_id,
            )
            .where(
                EStatHour.object_type == 'equipment',
                EStatHour.energy_type_code == energy_type,
                EStatHour.stat_time >= period_start,
                EStatHour.stat_time < period_end,
                EStatHour.avg_power_kw.is_not(None),
            )
        )
        if area_id is not None:
            statement = statement.where(EEquipment.area_id == area_id)
        rows = (
            await db.execute(
                statement.order_by(
                    EStatHour.avg_power_kw.desc(),
                    EStatHour.stat_time,
                    EEquipment.equipment_id,
                ).limit(limit)
            )
        ).all()
        equipment_ids = sorted({int(row[1].equipment_id) for row in rows})
        points: dict[int, list[int]] = {equipment_id: [] for equipment_id in equipment_ids}
        if equipment_ids:
            point_rows = (
                await db.execute(
                    select(EMeterPoint.equipment_id, EMeterPoint.point_id)
                    .where(
                        EMeterPoint.equipment_id.in_(equipment_ids),
                        EMeterPoint.energy_type_code == energy_type,
                        EMeterPoint.status == 'enabled',
                    )
                    .order_by(EMeterPoint.equipment_id, EMeterPoint.point_id)
                )
            ).all()
            for equipment_id, point_id in point_rows:
                points[int(equipment_id)].append(int(point_id))
        return rows, points

    @staticmethod
    async def load_tariffs(db: AsyncSession) -> list[ETariffVersion]:
        statement = select(ETariffVersion).order_by(
            ETariffVersion.energy_type_code,
            ETariffVersion.tou_period,
            ETariffVersion.effective_from,
            ETariffVersion.version_no,
        )
        return list((await db.execute(statement)).scalars())

    @staticmethod
    async def load_alloc_rules(db: AsyncSession) -> list[ECostAllocRule]:
        statement = select(ECostAllocRule).order_by(
            ECostAllocRule.effective_from,
            ECostAllocRule.version_no,
            ECostAllocRule.rule_id,
        )
        return list((await db.execute(statement)).scalars())

    @staticmethod
    async def insert_versions(db: AsyncSession, rows: list[ECostRecord]) -> None:
        db.add_all(rows)
        await db.flush()

    @staticmethod
    async def lock_current_batch(
        db: AsyncSession,
        stat_month: str,
        energy_type: str,
    ) -> list[ECostRecord]:
        statement = (
            select(ECostRecord)
            .where(
                ECostRecord.stat_month == stat_month,
                ECostRecord.energy_type_code == energy_type,
                ECostRecord.is_current.is_(True),
            )
            .order_by(ECostRecord.object_type, ECostRecord.object_id)
            .execution_options(populate_existing=True)
            .with_for_update()
        )
        return list((await db.execute(statement)).scalars())

    @staticmethod
    async def max_batch_version(db: AsyncSession, stat_month: str, energy_type: str) -> int:
        value = (
            await db.execute(
                select(func.max(ECostRecord.cost_version)).where(
                    ECostRecord.stat_month == stat_month,
                    ECostRecord.energy_type_code == energy_type,
                )
            )
        ).scalar_one()
        return int(value or 0)

    @staticmethod
    async def insert_recompute_record(
        db: AsyncSession,
        record: ECostRecomputeRecord,
    ) -> None:
        db.add(record)
        await db.flush()

    @staticmethod
    async def get_recompute_record(
        db: AsyncSession,
        recompute_id: int,
    ) -> ECostRecomputeRecord | None:
        statement = select(ECostRecomputeRecord).where(ECostRecomputeRecord.recompute_id == recompute_id)
        return (await db.execute(statement)).scalar_one_or_none()

    @staticmethod
    async def list_recompute_records(
        db: AsyncSession,
        *,
        stat_month: str | None = None,
        energy_type: str | None = None,
        review_status: str | None = None,
    ) -> list[ECostRecomputeRecord]:
        statement = select(ECostRecomputeRecord)
        if stat_month is not None:
            statement = statement.where(ECostRecomputeRecord.stat_month == stat_month)
        if energy_type is not None:
            statement = statement.where(ECostRecomputeRecord.energy_type_code == energy_type)
        if review_status is not None:
            statement = statement.where(ECostRecomputeRecord.review_status == review_status)
        statement = statement.order_by(
            ECostRecomputeRecord.triggered_at.desc(),
            ECostRecomputeRecord.recompute_id.desc(),
        )
        return list((await db.execute(statement)).scalars())

    @staticmethod
    async def load_recompute_version_chain(
        db: AsyncSession,
        record: ECostRecomputeRecord,
    ) -> list[ECostRecord]:
        """Load the complete immutable history for the detail trace, not just its edge."""
        statement = (
            select(ECostRecord)
            .where(
                ECostRecord.stat_month == record.stat_month,
                ECostRecord.energy_type_code == record.energy_type_code,
            )
            .order_by(
                ECostRecord.cost_version,
                ECostRecord.object_type,
                ECostRecord.object_id,
            )
        )
        return list((await db.execute(statement)).scalars())

    @staticmethod
    async def lock_pending_recompute(
        db: AsyncSession,
        stat_month: str,
        energy_type: str,
    ) -> ECostRecomputeRecord | None:
        statement = (
            select(ECostRecomputeRecord)
            .where(
                ECostRecomputeRecord.stat_month == stat_month,
                ECostRecomputeRecord.energy_type_code == energy_type,
                ECostRecomputeRecord.review_status == 'pending',
            )
            .order_by(ECostRecomputeRecord.recompute_id)
            .limit(1)
            .with_for_update()
        )
        return (await db.execute(statement)).scalar_one_or_none()

    @staticmethod
    async def lock_recompute_record(
        db: AsyncSession,
        recompute_id: int,
    ) -> ECostRecomputeRecord | None:
        statement = (
            select(ECostRecomputeRecord)
            .where(ECostRecomputeRecord.recompute_id == recompute_id)
            .execution_options(populate_existing=True)
            .with_for_update()
        )
        return (await db.execute(statement)).scalar_one_or_none()

    @staticmethod
    async def lock_version_chain(
        db: AsyncSession,
        record: ECostRecomputeRecord,
    ) -> list[ECostRecord]:
        statement = (
            select(ECostRecord)
            .where(
                ECostRecord.stat_month == record.stat_month,
                ECostRecord.energy_type_code == record.energy_type_code,
                ECostRecord.cost_version.in_((record.old_cost_version, record.new_cost_version)),
            )
            .order_by(ECostRecord.cost_version, ECostRecord.id)
            .execution_options(populate_existing=True)
            .with_for_update()
        )
        return list((await db.execute(statement)).scalars())

    @staticmethod
    async def load_object_codes(db: AsyncSession) -> dict[tuple[str, int], str]:
        areas = (await db.execute(select(EArea.area_id, EArea.area_code))).all()
        equipment = (await db.execute(select(EEquipment.equipment_id, EEquipment.equipment_code))).all()
        codes = {('area', int(row.area_id)): row.area_code for row in areas}
        codes.update({('equipment', int(row.equipment_id)): row.equipment_code for row in equipment})
        return codes
