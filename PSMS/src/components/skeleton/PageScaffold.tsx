import { Card, Space, Tag, Typography } from 'antd';
import { matchPath, useLocation } from 'react-router-dom';
import { routeCatalog } from '../../app/routeCatalog';

export default function PageScaffold() {
  const location = useLocation();
  const route = routeCatalog.find((candidate) =>
    matchPath({ path: candidate.path, end: true }, location.pathname),
  );

  if (!route) {
    throw new Error(`未找到页面骨架元数据：${location.pathname}`);
  }

  return (
    <Card className="page-scaffold">
      <Space orientation="vertical" size={0}>
        <Tag color="blue">{route.id}</Tag>
        <Typography.Title level={2} className="page-scaffold-heading">
          {route.name}
        </Typography.Title>
        <Typography.Text className="page-scaffold-route">
          当前路由：{location.pathname}
        </Typography.Text>
        <Typography.Paragraph className="page-scaffold-note">
          后续页面任务负责实现业务功能；当前 C01 仅提供可验证的工程骨架，不包含业务字段、数据、接口或操作。
        </Typography.Paragraph>
      </Space>
    </Card>
  );
}
