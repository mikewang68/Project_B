import type { CSSProperties } from 'react';
import {
  DndContext,
  PointerSensor,
  closestCenter,
  useSensor,
  useSensors,
  type DragEndEvent,
} from '@dnd-kit/core';
import { SortableContext, verticalListSortingStrategy, useSortable } from '@dnd-kit/sortable';
import { Button, Tag } from 'antd';

import type { Appointment } from '../../contracts';
import { businessLabel, businessTone } from '../../presentation/businessCopy';
import { displayVehicleNo } from './model';

type SortableQueueProps = Readonly<{
  appointments: readonly Appointment[];
  selectedId?: string;
  onSelect: (appointmentId: string) => void;
  onReorder: (activeId: string, overId: string) => void;
}>;

const toneColor = {
  success: 'success', processing: 'processing', warning: 'warning', danger: 'error', neutral: 'default',
} as const;

function QueueItem({ appointment, selected, onSelect }: {
  appointment: Appointment;
  selected: boolean;
  onSelect: () => void;
}) {
  const sortable = useSortable({ id: appointment.id });
  const style: CSSProperties = {
    transform: sortable.transform
      ? `translate3d(${Math.round(sortable.transform.x)}px, ${Math.round(sortable.transform.y)}px, 0)`
      : undefined,
    transition: sortable.transition,
  };
  return (
    <div
      ref={sortable.setNodeRef}
      style={style}
      className={`appointment-queue-item${selected ? ' is-selected' : ''}`}
      {...sortable.attributes}
    >
      <button
        type="button"
        className="appointment-drag-handle"
        aria-label={`拖动调整 ${displayVehicleNo(appointment.vehicleNo)} 顺序`}
        {...sortable.listeners}
      >
        <span aria-hidden="true">≡</span>
      </button>
      <Button type="text" className="appointment-queue-select" onClick={onSelect}>
        <strong>{appointment.queueNo} · {displayVehicleNo(appointment.vehicleNo)}</strong>
        <span>{appointment.reservationNo}</span>
      </Button>
      <Tag color={toneColor[businessTone(appointment.status)]}>
        {businessLabel(appointment.status)}
      </Tag>
    </div>
  );
}

export default function SortableQueue({
  appointments,
  selectedId,
  onSelect,
  onReorder,
}: SortableQueueProps) {
  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 6 } }));
  const handleDragEnd = ({ active, over }: DragEndEvent) => {
    if (over && active.id !== over.id) onReorder(String(active.id), String(over.id));
  };

  return (
    <DndContext
      sensors={sensors}
      collisionDetection={closestCenter}
      onDragEnd={handleDragEnd}
      accessibility={{
        announcements: {
          onDragStart: ({ active }) => `开始调整预约 ${String(active.id)}。`,
          onDragOver: ({ active, over }) => over
            ? `预约 ${String(active.id)} 移动到 ${String(over.id)} 附近。`
            : `预约 ${String(active.id)} 已离开队列。`,
          onDragEnd: ({ active, over }) => over
            ? `预约 ${String(active.id)} 已移动到 ${String(over.id)}。`
            : `预约 ${String(active.id)} 顺序未改变。`,
          onDragCancel: ({ active }) => `已取消调整预约 ${String(active.id)}。`,
        },
      }}
    >
      <SortableContext items={appointments.map(({ id }) => id)} strategy={verticalListSortingStrategy}>
        <div className="appointment-queue-list" aria-label="候车叫号队列">
          {appointments.map((appointment) => (
            <QueueItem
              key={appointment.id}
              appointment={appointment}
              selected={appointment.id === selectedId}
              onSelect={() => onSelect(appointment.id)}
            />
          ))}
        </div>
      </SortableContext>
    </DndContext>
  );
}
