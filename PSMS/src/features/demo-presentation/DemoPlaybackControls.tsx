import { Button, Space, Tag, Typography } from 'antd';

import type { DemoSequencePlayback } from './useDemoSequence';

export type DemoPlaybackControlsProps<StepId extends string> = {
  title: string;
  boundary: string;
  playback: DemoSequencePlayback<StepId>;
};

const statusLabels = {
  idle: '等待演示',
  running: '演示中',
  completed: '演示完成',
  error: '演示异常',
} as const;

const statusColors = {
  idle: 'default',
  running: 'processing',
  completed: 'success',
  error: 'error',
} as const;

export default function DemoPlaybackControls<StepId extends string>({
  title,
  boundary,
  playback,
}: DemoPlaybackControlsProps<StepId>) {
  const running = playback.status === 'running';
  return (
    <section className="demo-playback-controls" aria-label={title}>
      <div className="demo-playback-copy">
        <Space size={8} wrap>
          <Typography.Text strong>{title}</Typography.Text>
          <Tag color={statusColors[playback.status]}>
            {statusLabels[playback.status]}
          </Tag>
        </Space>
        <Typography.Text type="secondary">{boundary}</Typography.Text>
        {playback.errorMessage ? (
          <Typography.Text type="danger">{playback.errorMessage}</Typography.Text>
        ) : null}
      </div>
      <Space size={8}>
        <Button
          type="primary"
          disabled={running}
          onClick={() => {
            void playback.start();
          }}
        >
          开始演示
        </Button>
        <Button
          disabled={running}
          onClick={() => {
            void playback.replay();
          }}
        >
          重新播放
        </Button>
      </Space>
    </section>
  );
}
