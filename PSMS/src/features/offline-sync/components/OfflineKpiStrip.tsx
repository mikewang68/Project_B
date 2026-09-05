import { Card, Statistic } from 'antd';

import type { OfflinePacketKpis } from '../offlinePacketTypes';

const entries: ReadonlyArray<readonly [keyof OfflinePacketKpis, string]> = [
  ['cached', '缓存'],
  ['pendingUpload', '待上传'],
  ['validating', '校验中'],
  ['merged', '已合并'],
  ['conflict', '冲突'],
  ['rejected', '已驳回'],
  ['retry', '重试'],
];

export default function OfflineKpiStrip({ kpis }: { kpis: OfflinePacketKpis }) {
  return (
    <div className="offline-kpi-strip" aria-label="离线同步指标">
      {entries.map(([key, label]) => (
        <Card key={key} size="small" className="offline-kpi-card">
          <Statistic title={label} value={kpis[key]} />
        </Card>
      ))}
    </div>
  );
}
