import { Alert, Button } from 'antd';

export type RailwaySource = { kind: 'LOCAL_DEMO' | 'SERVER_DATABASE'; database: string };

export default function RailwayDataSource({ source }: { source?: RailwaySource }) {
  if (!source) return <Alert type="info" showIcon title="正在确认工单数据来源" style={{ marginBottom: 16 }} />;
  if (source.kind === 'LOCAL_DEMO') return <Alert type="warning" showIcon
    title="当前显示本机验证工单，关机期间本机停止生成"
    description="本页读取本机独立验证库。电脑关机后，每日工单由服务器生成并保存在服务器数据库中，请从线上入口查看连续记录。"
    action={<Button href="https://100.65.200.125:18443/production-dispatch/?railway=1" target="_blank" rel="noopener noreferrer">查看服务器每日工单</Button>}
    style={{ marginBottom: 16 }} />;
  return <Alert type="info" showIcon title={'当前读取服务器工单 · ' + source.database}
    description="每日工单由服务器后台生成并写入服务器数据库。你的电脑关机、退出登录或关闭浏览器后，服务器后台继续运行。"
    style={{ marginBottom: 16 }} />;
}
