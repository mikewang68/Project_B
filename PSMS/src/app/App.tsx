import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { RouterProvider } from 'react-router-dom';
import { appRouter } from './router';
import { DemoRuntimeProvider, type DemoRuntime } from '../runtime';
import { getAppTheme } from '../styles/theme';
import { usePreferenceStore } from '../stores/preference';

type AppProps = {
  router?: typeof appRouter;
  runtime?: DemoRuntime;
};

export default function App({ router = appRouter, runtime }: AppProps) {
  // 皮肤 id 来自外观偏好 store；切换时此处重新下发 antd 主题配置。
  // <html data-theme> 由 store 负责写入，供 tokens.css 的自定义 CSS 使用。
  const themeId = usePreferenceStore((state) => state.theme);

  return (
    <DemoRuntimeProvider runtime={runtime}>
      <ConfigProvider locale={zhCN} theme={getAppTheme(themeId)}>
        <RouterProvider router={router} />
      </ConfigProvider>
    </DemoRuntimeProvider>
  );
}
