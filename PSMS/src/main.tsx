import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
// 设计令牌需先于全局样式载入：global.css 与各业务样式均引用 tokens 中的自定义属性
import './styles/tokens.css';
import './styles/global.css';

async function bootstrap(): Promise<void> {
  if (new URLSearchParams(window.location.search).get('railway') === '1') {
    const { Alert } = await import('antd');
    let readOnly = false;
    if (import.meta.env.VITE_PSMS_REAL_API === 'true') {
      try {
        const { liveApi } = await import('./real/client');
        const current = (await liveApi('/auth/me')).data;
        if (current?.menuOperations != null) {
          createRoot(document.getElementById('root')!).render(<Alert type="warning" title="铁路运行台尚未完成区域权限适配" />);
          return;
        }
        readOnly = current?.readOnly === true;
      } catch {
        createRoot(document.getElementById('root')!).render(<Alert type="error" title="请先通过统一入口登录生产调度系统" />);
        return;
      }
    }
    const { default: RailwayConsole } = await import('./real/RailwayConsole');
    createRoot(document.getElementById('root')!).render(<StrictMode><RailwayConsole readOnly={readOnly} /></StrictMode>);
    return;
  }
  if (import.meta.env.VITE_PSMS_REAL_API === 'true') {
    // Do not import the demo router, fixture store, MSW, or reset endpoint in API mode.
    const { default: RealApp } = await import('./real/LiveApp');
    createRoot(document.getElementById('root')!).render(<StrictMode><RealApp /></StrictMode>);
    return;
  }
  const [{ default: App }, { bootstrapDemoRuntime, enableMocking }] = await Promise.all([
    import('./app/App'), import('./runtime'),
  ]);
  await enableMocking();
  const runtime = await bootstrapDemoRuntime(window.location.search);
  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <App runtime={runtime} />
    </StrictMode>,
  );
}

void bootstrap();
