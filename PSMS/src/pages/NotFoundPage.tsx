import { Button, Result } from 'antd';
import { withAppBasePath } from '../runtime/appBasePath';

export default function NotFoundPage() {
  return (
    <div className="route-status-page">
      <Result
        status="404"
        title="404"
        subTitle="未找到对应页面，请从稳定入口重新进入。"
        extra={
          <Button type="primary" href={withAppBasePath('/dispatch/overview')}>
            返回调度总览
          </Button>
        }
      />
    </div>
  );
}
