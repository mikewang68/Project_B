import { Card, Space, Tag, Typography } from 'antd';

import { businessLabel } from '../../../presentation/businessCopy';
import { displayVehicleNo } from '../../road-appointment/model';
import type { VisibleYardObjectsViewModel } from '../types';

export type YardSchematicProps = {
  yard: VisibleYardObjectsViewModel;
};

export default function YardSchematic({ yard }: YardSchematicProps) {
  return (
    <Card
      className="overview-section-card yard-schematic-card"
      title="场区态势"
      extra={<Typography.Text type="secondary">股道 / 资源 / 公路车辆</Typography.Text>}
      aria-label="场区态势"
    >
      <section className="yard-schematic-scroll">
        <div className="yard-schematic-canvas">
          <div className="yard-track-list">
            {yard.tracks.map((track) => (
              <article
                key={track.id}
                className="yard-track-row"
                aria-label={`股道 ${track.trackNo}`}
                data-lane-order={track.laneOrder}
              >
                <div className="yard-track-meta">
                  <Typography.Text strong className="yard-track-number">
                    {track.trackNo}
                  </Typography.Text>
                  <Tag color={track.statusTone}>{track.occupyStatusLabel}</Tag>
                  <Typography.Text type="secondary" className="yard-cargo-summary">
                    {track.compatibleCargoSummary}
                  </Typography.Text>
                </div>
                <div className="yard-track-line">
                  {track.occupancyMarker ? (
                    <Tag
                      className="yard-occupancy-marker"
                      color={track.occupancyMarker.tone}
                    >
                      {track.occupancyMarker.label}
                    </Tag>
                  ) : (
                    <span className="yard-track-empty">暂无当前占用</span>
                  )}
                </div>
              </article>
            ))}
          </div>

          <div className="yard-support-grid">
            <section className="yard-support-lane" aria-label="作业资源">
              <Typography.Text strong>作业资源</Typography.Text>
              <div className="yard-marker-row yard-resource-lane">
                {yard.resourceMarkers.map((resource) => (
                  <span
                    key={resource.id}
                    className="yard-resource-marker"
                    aria-label={`资源 ${resource.id}`}
                    data-lane-order={resource.laneOrder}
                    data-position={resource.positionPercent}
                    style={{ insetInlineStart: `${resource.positionPercent}%` }}
                  >
                    <Space size={5}>
                      <Typography.Text title={businessLabel(resource.resourceType)}>
                        {businessLabel(resource.resourceType)}
                      </Typography.Text>
                      <Tag color={resource.statusTone} title={businessLabel(resource.status)}>
                        {businessLabel(resource.status)}
                      </Tag>
                      <Typography.Text type="secondary">{resource.location}</Typography.Text>
                    </Space>
                  </span>
                ))}
              </div>
            </section>
            <section className="yard-support-lane" aria-label="车辆队列">
              <Typography.Text strong>公路车辆</Typography.Text>
              <ol className="yard-marker-row yard-appointment-lane" aria-label="预约车辆标记">
                {yard.appointmentMarkers.map((appointment) => (
                  <li
                    key={appointment.id}
                    className="yard-vehicle-marker"
                    aria-label={`车辆 ${displayVehicleNo(appointment.vehicleNo)}`}
                    data-lane-order={appointment.laneOrder}
                  >
                    <Typography.Text strong>{displayVehicleNo(appointment.vehicleNo)}</Typography.Text>
                    <Tag color={appointment.statusTone} title={businessLabel(appointment.status)}>
                      {businessLabel(appointment.status)}
                    </Tag>
                    <Typography.Text type="secondary">{appointment.queueNo}</Typography.Text>
                    <Typography.Text type="secondary" title={businessLabel(appointment.gateStatus)}>
                      {businessLabel(appointment.gateStatus)}
                    </Typography.Text>
                  </li>
                ))}
              </ol>
            </section>
          </div>
        </div>
      </section>
    </Card>
  );
}
