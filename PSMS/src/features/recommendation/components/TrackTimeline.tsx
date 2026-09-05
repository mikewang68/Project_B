import { Card, Tag, Timeline, Typography } from 'antd';

import type { Plan, Track } from '../../../contracts';
import type { RecommendationCandidate } from '../schemas';

export type TrackTimelineProps = {
  plan: Readonly<Plan>;
  track?: Readonly<Track>;
  candidate?: RecommendationCandidate;
};

export default function TrackTimeline({ plan, track, candidate }: TrackTimelineProps) {
  const [arrival = plan.arrivalDepartureTime, departure = '数据未提供'] =
    plan.arrivalDepartureTime.split('/');
  const delayed = candidate ? Date.parse(candidate.windowStart) > Date.parse(arrival) : false;
  const items = [
    { color: 'blue', content: <><Typography.Text strong>计划到达</Typography.Text><br />{arrival}</> },
    ...(track && track.occupyStatus !== 'FREE'
      ? [{ color: 'orange', content: <><Typography.Text strong>股道预计释放</Typography.Text><br />{track.estimateReleaseTime}</> }]
      : []),
    ...(candidate
      ? [{
          color: delayed ? 'orange' : 'green',
          content: (
            <>
              <Typography.Text strong>候选接车窗口</Typography.Text>{' '}
              {delayed ? <Tag color="warning">错峰</Tag> : <Tag color="success">准点匹配</Tag>}
              <br />
              {candidate.windowStart} → {candidate.windowEnd}
            </>
          ),
        }]
      : []),
    { color: 'gray', content: <><Typography.Text strong>计划离开</Typography.Text><br />{departure}</> },
  ];

  return (
    <section aria-label="接车时间轴" className="recommendation-section">
      <Card title="接车时间轴">
        <Timeline items={items} />
      </Card>
    </section>
  );
}
