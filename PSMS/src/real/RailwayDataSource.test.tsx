import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import RailwayDataSource from './RailwayDataSource';

afterEach(cleanup);
describe('railway database source shown to users', () => {
  it('explains local shutdown gaps and points to the server runtime', () => {
    render(<RailwayDataSource source={{ kind: 'LOCAL_DEMO', database: '本机独立验证库' }} />);
    expect(screen.getByText('当前显示本机验证工单，关机期间本机停止生成')).toBeVisible();
    expect(screen.getByRole('link', { name: '查看服务器每日工单' })).toHaveAttribute('href',
      'https://100.65.200.125:18443/production-dispatch/?railway=1');
    expect(screen.queryByText(/当前读取服务器工单/)).not.toBeInTheDocument();
  });
  it('identifies the actual server database after the snapshot is received', () => {
    render(<RailwayDataSource source={{ kind: 'SERVER_DATABASE', database: 'psms' }} />);
    expect(screen.getByText('当前读取服务器工单 · psms')).toBeVisible();
    expect(screen.queryByRole('link', { name: '查看服务器每日工单' })).not.toBeInTheDocument();
  });
  it('waits for the database response before identifying either environment', () => {
    render(<RailwayDataSource />);
    expect(screen.getByText('正在确认工单数据来源')).toBeVisible();
    expect(screen.queryByText(/当前读取服务器工单/)).not.toBeInTheDocument();
  });
});
