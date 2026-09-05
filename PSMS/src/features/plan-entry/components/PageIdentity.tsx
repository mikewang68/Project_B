import { Space, Tag, Typography } from 'antd';

export type PageIdentityProps = {
  pageId: string;
  name: string;
  path: string;
};

export default function PageIdentity({ pageId, name, path }: PageIdentityProps) {
  return (
    <div className="plan-entry-page-identity">
      <Space align="start" size={12}>
        <Tag className="plan-entry-page-tag">
          {pageId}
        </Tag>
        <div>
          <Typography.Title level={2} className="plan-entry-page-title">
            {name}
          </Typography.Title>
          <Typography.Text className="plan-entry-page-route">
            当前路由：{path}
          </Typography.Text>
        </div>
      </Space>
    </div>
  );
}
