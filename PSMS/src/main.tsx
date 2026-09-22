import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import App from './app/App';
import { bootstrapDemoRuntime, enableMocking } from './runtime';
// 设计令牌需先于全局样式载入：global.css 与各业务样式均引用 tokens 中的自定义属性
import './styles/tokens.css';
import './styles/global.css';

async function bootstrap(): Promise<void> {
  await enableMocking();
  const runtime = await bootstrapDemoRuntime(window.location.search);
  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <App runtime={runtime} />
    </StrictMode>,
  );
}

void bootstrap();
