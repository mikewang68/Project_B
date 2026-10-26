// Explicit projections exclude driver identity documents and internal network addresses.
export const businessCollections = {
  'work-orders': { table: 'work_orders', area: true,
    columns: 'id,plan_id,plan_batch_no,work_area,equipment_id,equipment_name,assigned_operator,status,order_type,priority,description,actual_start_time,actual_end_time,created_at,updated_at' },
  tasks: { table: 'tasks', area: true,
    columns: 'id,plan_id,work_order_id,task_no,name,description,work_area,equipment_id,status,order_no,started_at,completed_at,created_at,updated_at' },
  equipment: { table: 'equipment', area: true,
    columns: 'id,equipment_id,name,type,model,work_area,status,last_heartbeat,last_telemetry_at,updated_at' },
  appointments: { table: 'appointments', area: false,
    columns: 'id,appointment_id,vehicle_plate,vehicle_type,company,cargo_type,planned_arrive_time,check_in_time,called_at,enter_time,exit_time,queue_number,status,gate_no,parking_bay,created_at,updated_at' },
  exceptions: { table: 'exceptions', area: true,
    columns: 'id,exception_id,type,severity,title,description,equipment_id,work_area,status,assigned_to,acknowledged_by,acknowledged_at,resolution,resolved_at,closed_at,created_at,updated_at' },
} as const;
export type BusinessCollection = keyof typeof businessCollections;
export function isBusinessCollection(value: string): value is BusinessCollection {
  return Object.hasOwn(businessCollections, value);
}
export function businessReadPermission(collection: BusinessCollection): string {
  return `pps:api:${collection === 'equipment' || collection === 'tasks' ? 'monitor' : collection}:read`;
}
