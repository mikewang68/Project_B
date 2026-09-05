"""第三幕后端最小契约测试（REQ-033~044）。"""

import inspect
import unittest
from datetime import datetime
from types import SimpleNamespace

from common.aspect.interface_auth import CheckRoleInterfaceAuth, CheckUserInterfaceAuth
from module_energy.service import alert_service as alert_service_module
from module_energy.service.alert_service import plan_transition
from module_energy.service.equipment_profile_service import (
    EquipmentProfileService,
    _signature,
    build_composition,
    normalize_heat_ratios,
    series_unit,
    widen_period_for_event,
)
from module_energy.service.rule_engine_service import (
    RuleEngineService,
    build_rule_notifications,
)
from server import create_app


class AlertTransitionContractTest(unittest.TestCase):
    def test_new_to_dispatched_keeps_ack_evidence(self) -> None:
        steps = plan_transition(
            current_status='new',
            to_status='dispatched',
            assigned_to='ops_user',
            close_reason=None,
        )

        self.assertEqual(steps, [('new', 'ack'), ('ack', 'dispatched')])

    def test_dispatch_requires_assignee(self) -> None:
        with self.assertRaisesRegex(ValueError, 'assignedTo'):
            plan_transition('ack', 'dispatched', None, None)

    def test_close_requires_reason(self) -> None:
        with self.assertRaisesRegex(ValueError, 'closeReason'):
            plan_transition('processing', 'closed', None, '   ')

    def test_illegal_transition_is_rejected(self) -> None:
        with self.assertRaisesRegex(ValueError, '非法'):
            plan_transition('new', 'closed', None, '已处理')


class EquipmentProfileContractTest(unittest.TestCase):
    def test_composition_uses_work_order_before_equipment_state(self) -> None:
        rows = [
            {'energy': 10.0, 'state': 'standby', 'has_work_order': True},
            {'energy': 20.0, 'state': 'standby', 'has_work_order': False},
            {'energy': 5.0, 'state': 'stopped', 'has_work_order': False},
            {'energy': 3.0, 'state': 'running', 'has_work_order': False},
        ]

        result = build_composition(rows)

        self.assertEqual(
            result,
            {
                'workEnergy': 10.0,
                'standbyEnergy': 20.0,
                'stoppedEnergy': 5.0,
                'auxiliaryEnergy': 3.0,
                'totalEnergy': 38.0,
            },
        )

    def test_heat_ratio_is_normalized_by_filtered_maximum(self) -> None:
        self.assertEqual(normalize_heat_ratios([20.0, 10.0, 0.0]), [1.0, 0.5, 0.0])
        self.assertEqual(normalize_heat_ratios([0.0, 0.0]), [0.0, 0.0])

    def test_electricity_power_curve_uses_power_unit(self) -> None:
        self.assertEqual(series_unit('electricity'), 'kW')
        self.assertEqual(series_unit('compressed_air'), 'm³')

    def test_signature_baseline_version_reflects_query_window(self) -> None:
        # REQ-062：peerComparison 用查询窗算同类均值，baselineVersion 必须如实反映窗口天数，
        # 不再固定 'PROFILE-PEER-8W'（P-09 修复批 2026-07-15）。
        sig_7d = _signature(
            datetime(2026, 7, 5), datetime(2026, 7, 12), 'A', 'electricity', 0.98
        )
        self.assertEqual(sig_7d['baselineVersion'], 'PROFILE-PEER-7D')
        sig_1d = _signature(
            datetime(2026, 7, 12, 8), datetime(2026, 7, 12, 20), 'A', 'water', 0.9
        )
        self.assertEqual(sig_1d['baselineVersion'], 'PROFILE-PEER-1D')


class AlertNotificationContractTest(unittest.TestCase):
    def test_rule_engine_builds_real_in_app_notification(self) -> None:
        notifications = build_rule_notifications(
            rule_category='quality',
            occurred_at=datetime(2026, 7, 8, 16, 0),
        )

        self.assertEqual(
            notifications,
            [{
                'channel': 'in_app',
                'targetRole': 'ops',
                'sentAt': '2026-07-08 16:00:00',
                'result': 'success',
                'retryCount': 0,
            }],
        )


class Act3PermissionContractTest(unittest.TestCase):
    @staticmethod
    def _dependencies(path: str, method: str) -> list[object]:
        route = next(
            item for item in create_app().routes
            if getattr(item, 'path', None) == path and method in getattr(item, 'methods', set())
        )
        return [dependency.call for dependency in route.dependant.dependencies]

    def test_alert_get_uses_menu_permission(self) -> None:
        dependencies = self._dependencies('/alerts', 'GET')

        self.assertTrue(any(
            isinstance(dependency, CheckUserInterfaceAuth)
            and dependency.perm == 'energy:alert:list'
            for dependency in dependencies
        ))

    def test_profile_get_uses_menu_permission(self) -> None:
        dependencies = self._dependencies('/equipment-profiles', 'GET')

        self.assertTrue(any(
            isinstance(dependency, CheckUserInterfaceAuth)
            and dependency.perm == 'energy:analysis:profile'
            for dependency in dependencies
        ))

    def test_transition_is_limited_to_business_roles(self) -> None:
        dependencies = self._dependencies('/alerts/{event_id}/transition', 'POST')

        self.assertTrue(any(
            isinstance(dependency, CheckRoleInterfaceAuth)
            and dependency.role_key == ['energy_mgr', 'ops', 'admin']
            for dependency in dependencies
        ))


class InefficiencyEvidenceContractTest(unittest.TestCase):
    def test_non_standby_window_does_not_claim_standby(self) -> None:
        event = SimpleNamespace(
            rule_code='R05',
            first_occur_time=datetime(2026, 7, 8, 13),
            last_occur_time=datetime(2026, 7, 8, 14),
            snapshot_json='{"class_mean_avg": 50, "max_run_len": 2}',
        )
        hourly = [
            SimpleNamespace(
                stat_time=datetime(2026, 7, 8, hour),
                total_value=100,
                state='running',
            )
            for hour in (13, 14)
        ]

        evidence = EquipmentProfileService._inefficiency_evidence(event, hourly, [])

        self.assertEqual(evidence['reason'], '运行高耗 + 无工单')


class DemoNowAnchoringContractTest(unittest.TestCase):
    """B3/P-09：流转与规则写库的"当前时刻"必须走 get_demo_now，不许 datetime.now()。"""

    def test_alert_transition_reads_demo_now_not_wall_clock(self) -> None:
        source = inspect.getsource(alert_service_module.AlertService.transition)
        self.assertIn('get_demo_now(db)', source)
        self.assertNotIn('datetime.now()', source)


class BootstrapResetContractTest(unittest.TestCase):
    """B4/P-17：run_all 必须先清子表 e_alert_flow_log 再清 e_alert_event。"""

    def test_run_all_clears_flow_log_before_event(self) -> None:
        source = inspect.getsource(RuleEngineService.run_all)
        self.assertIn('DELETE FROM e_alert_flow_log', source)
        flow_idx = source.index('e_alert_flow_log')
        event_idx = source.index('delete(EAlertEvent)')
        self.assertLess(
            flow_idx, event_idx,
            'flow_log 清理必须在 e_alert_event 之前（子表先删）'
        )


class ProfileEventWindowWideningTest(unittest.TestCase):
    """B5：带 eventId 时把事件窗口并入查询周期，而不是硬 404。"""

    def test_widen_extends_end_to_cover_event_tail(self) -> None:
        start = datetime(2026, 7, 5)
        end = datetime(2026, 7, 12)
        # 事件在默认 7 天窗之前，扩窗起点应回落到事件起点
        widened_start, widened_end = widen_period_for_event(
            start, end,
            event_first=datetime(2026, 6, 20, 10),
            event_last=datetime(2026, 6, 20, 13),
        )
        self.assertEqual(widened_start, datetime(2026, 6, 20, 10))
        self.assertEqual(widened_end, end)

    def test_widen_extends_start_and_pads_hour_on_tail(self) -> None:
        start = datetime(2026, 7, 5)
        end = datetime(2026, 7, 12)
        # 事件末端超出查询窗右界，扩窗末端应含 event_last + 1h
        widened_start, widened_end = widen_period_for_event(
            start, end,
            event_first=datetime(2026, 7, 6, 8),
            event_last=datetime(2026, 7, 13, 9),
        )
        self.assertEqual(widened_start, start)
        self.assertEqual(widened_end, datetime(2026, 7, 13, 10))

    def test_widen_is_noop_when_event_inside_window(self) -> None:
        start = datetime(2026, 7, 5)
        end = datetime(2026, 7, 12)
        widened_start, widened_end = widen_period_for_event(
            start, end,
            event_first=datetime(2026, 7, 8, 10),
            event_last=datetime(2026, 7, 8, 13),
        )
        self.assertEqual(widened_start, start)
        self.assertEqual(widened_end, end)


if __name__ == '__main__':
    unittest.main()
