import { Button, Result } from 'antd';
import { useRouteError } from 'react-router-dom';
import { withAppBasePath } from '../runtime/appBasePath';

export default function RouteErrorPage() {
  const error = useRouteError();
  const detail = error instanceof Error ? error.message : '页面资源或运行数据无法加载';

  return (
    <div className="route-status-page">
      <Result
        status="error"
        title="路由加载失败"
        subTitle={`页面加载失败：${detail}。请刷新页面，或返回调度总览重试。`}
        extra={
          <Button type="primary" href={withAppBasePath('/dispatch/overview')}>
            返回调度总览
          </Button>
        }
      />
    </div>
  );
}
