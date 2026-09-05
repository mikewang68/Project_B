import type { Appointment } from '../../contracts';

export type AppointmentStatus = Appointment['status'];

export type AppointmentAction = Readonly<{
  code: string;
  label: string;
  permission: 'yard:call' | 'yard:gate' | 'yard:release' | 'yard:review' | 'yard:submit';
  next: AppointmentStatus;
}>;

const actions: Partial<Record<AppointmentStatus, AppointmentAction>> = {
  DRAFT: { code: 'SUBMIT', label: '提交预约', permission: 'yard:submit', next: 'SUBMITTED' },
  SUBMITTED: { code: 'APPROVE', label: '审核通过', permission: 'yard:review', next: 'APPROVED' },
  APPROVED: { code: 'JOIN_QUEUE', label: '进入候车队列', permission: 'yard:review', next: 'QUEUED' },
  QUEUED: { code: 'CALL', label: '叫号入场', permission: 'yard:call', next: 'CALLED' },
  CALLED: { code: 'GATE_PASS', label: '确认过闸', permission: 'yard:gate', next: 'ENTERED' },
  ENTERED: { code: 'START_OPERATION', label: '开始作业', permission: 'yard:gate', next: 'OPERATING' },
  OPERATING: { code: 'RELEASE', label: '确认放行', permission: 'yard:release', next: 'RELEASED' },
  RELEASED: { code: 'EXIT', label: '确认离场', permission: 'yard:gate', next: 'EXITED' },
  NEED_FIX: { code: 'RESUBMIT', label: '补正后提交', permission: 'yard:submit', next: 'SUBMITTED' },
};

const queueStatuses = new Set<AppointmentStatus>([
  'SUBMITTED', 'APPROVED', 'QUEUED', 'CALLED', 'ENTERED', 'OPERATING',
]);

export function appointmentAction(status: AppointmentStatus): AppointmentAction | undefined {
  return actions[status];
}

export function activeQueueAppointments(items: readonly Appointment[]): Appointment[] {
  return items
    .filter(({ status }) => queueStatuses.has(status))
    .sort((left, right) => left.queueNo.localeCompare(right.queueNo));
}

export function displayVehicleNo(value: string): string {
  return value.replace('DEMO', '样车');
}

export function displayDriver(value: string): string {
  const suffix = value.match(/\d+$/)?.[0] ?? value;
  return `司机${suffix}`;
}

export function appointmentLocation(index: number): string {
  return ['北门候车区', '一号门闸', '卸车作业位', '装载作业位', '放行检查区', '南门出口'][index % 6];
}
