import { Button, Result } from 'antd';
import { useRouteError } from 'react-router-dom';
import { withAppBasePath } from '../runtime/appBasePath';

export default function RouteErrorPage() {
  useRouteError();

  return (
    <div className="route-status-page">
      <Result
        status="error"
        title="路由加载失败"
        subTitle="工程骨架未能加载该页面，请返回稳定入口后重试。"
        extra={
          <Button type="primary" href={withAppBasePath('/dispatch/overview')}>
            返回调度总览
          </Button>
        }
      />
    </div>
  );
}
