import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import App from './app/App';
import { bootstrapDemoRuntime, enableMocking } from './runtime';
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
