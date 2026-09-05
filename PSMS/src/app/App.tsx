import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { RouterProvider } from 'react-router-dom';
import { appRouter } from './router';
import { DemoRuntimeProvider, type DemoRuntime } from '../runtime';
import { appTheme } from '../styles/theme';

type AppProps = {
  router?: typeof appRouter;
  runtime?: DemoRuntime;
};

export default function App({ router = appRouter, runtime }: AppProps) {
  return (
    <DemoRuntimeProvider runtime={runtime}>
      <ConfigProvider locale={zhCN} theme={appTheme}>
        <RouterProvider router={router} />
      </ConfigProvider>
    </DemoRuntimeProvider>
  );
}
