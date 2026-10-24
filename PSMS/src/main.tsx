import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
// 设计令牌需先于全局样式载入：global.css 与各业务样式均引用 tokens 中的自定义属性
import './styles/tokens.css';
import './styles/global.css';

async function bootstrap(): Promise<void> {
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
